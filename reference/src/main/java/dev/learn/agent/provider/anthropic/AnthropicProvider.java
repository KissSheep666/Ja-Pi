package dev.learn.agent.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.learn.agent.provider.ChatRequest;
import dev.learn.agent.provider.Message;
import dev.learn.agent.provider.Provider;
import dev.learn.agent.provider.StopReason;
import dev.learn.agent.provider.StreamEvent;
import dev.learn.agent.provider.ToolCall;
import dev.learn.agent.provider.ToolSpec;
import dev.learn.agent.provider.Usage;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 第二个 Provider 实现：Anthropic Messages API 格式。
 *
 * <p>它连的是 DeepSeek 的 Anthropic 兼容端点
 * （{@code https://api.deepseek.com/anthropic}）。<b>同一个账号、同一个模型，
 * 完全不同的协议。</b>这是验证抽象最干净的方式——因为任何行为差异都只能来自协议，
 * 不可能来自模型能力。
 *
 * <h3>它抹平了三处差异</h3>
 * <ol>
 *   <li><b>工具结果的消息形态</b>：Anthropic 要求所有工具结果塞进<b>同一条</b> user 消息；
 *       OpenAI 要求每个结果<b>独立</b>成一条。上层完全不用知道。</li>
 *   <li><b>工具参数的序列化</b>：Anthropic 的 {@code input} 是 JSON <b>对象</b>；
 *       OpenAI 的 {@code arguments} 是 JSON <b>字符串</b>。同一个累积结果，两种写法。</li>
 *   <li><b>SSE 的行结构</b>：Anthropic 有 {@code event:} 行、有 {@code ping} 事件、
 *       没有 {@code [DONE]} 哨兵；OpenAI 只有 {@code data:} 行。</li>
 * </ol>
 *
 * <h3>还有一处它抹不平（这是本节最值钱的发现）</h3>
 * Anthropic 格式<b>没有"对话中途的 system 消息"</b>。
 * 而我们的 M4 压缩器恰恰要在中间插一条 system 摘要。
 * Provider 只能把它降级成一条 user 消息——语义变了，但这是它能做的最好选择。
 * <b>抽象的裂缝，就出现在两个协议真正不同的地方。</b>
 */
public class AnthropicProvider implements Provider {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com/anthropic";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String DATA_PREFIX = "data:";

    private final String apiKey;
    private final String baseUrl;
    private final int maxTokens;
    private final boolean dumpRaw;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public AnthropicProvider(String apiKey, boolean dumpRaw) {
        this(apiKey, DEFAULT_BASE_URL, 8192, dumpRaw);
    }

    public AnthropicProvider(String apiKey, String baseUrl, int maxTokens, boolean dumpRaw) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.maxTokens = maxTokens;
        this.dumpRaw = dumpRaw;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public void stream(ChatRequest request, Consumer<StreamEvent> onEvent) {
        try {
            HttpResponse<InputStream> response = http.send(
                    buildHttpRequest(request),
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                onEvent.accept(new StreamEvent.Failure(
                        "HTTP " + response.statusCode() + " — " + body));
                return;
            }

            readStream(response.body(), onEvent);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            onEvent.accept(new StreamEvent.Failure("请求被中断"));
        } catch (Exception e) {
            onEvent.accept(new StreamEvent.Failure(
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    // ═══════════════════════════════════════════════════════════
    //  请求构建
    // ═══════════════════════════════════════════════════════════

    private HttpRequest buildHttpRequest(ChatRequest request) {
        return HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/messages"))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                // ⚠️ Anthropic 用 x-api-key，不是 Authorization: Bearer
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(
                        buildBody(request), StandardCharsets.UTF_8))
                .build();
    }

    private String buildBody(ChatRequest request) {
        ObjectNode body = json.createObjectNode();
        body.put("model", request.model());
        // ⚠️ Anthropic 格式里 max_tokens 是必填的。OpenAI 格式里是可选。
        body.put("max_tokens", maxTokens);
        body.put("stream", true);
        body.putObject("thinking").put("type", request.thinking() ? "enabled" : "disabled");

        // ⚠️ system 是顶层参数，不是消息！（差异 4）
        String system = collectSystem(request.messages());
        if (!system.isEmpty()) {
            body.put("system", system);
        }

        ArrayNode messages = body.putArray("messages");
        appendMessages(request.messages(), messages);

        if (!request.tools().isEmpty()) {
            body.set("tools", toolsToJson(request.tools()));
        }
        return body.toString();
    }

    /**
     * 把<b>开头的连续 system 消息</b>拼成顶层的 system 字段。
     *
     * <p>⚠️ 注意这里有个坑：不能收集"所有" system 消息。
     * M4 的压缩会在对话中途插一条 system 摘要消息，如果把它也塞进顶层 system，
     * 那么（a）它会和降级成的 user 消息重复发一遍，
     * （b）更糟的是 —— <b>顶层的 system 变了，整个缓存前缀就失效了</b>。
     * 实测：这个 bug 让压缩后的缓存命中从 1536 掉到 0。
     */
    private static String collectSystem(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message m : messages) {
            if (!"system".equals(m.role())) {
                break;      // 只看开头那一段连续的 system
            }
            if (m.content() != null && !m.content().isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(m.content());
            }
        }
        return sb.toString();
    }

    /**
     * 把内部消息列表翻译成 Anthropic 的 messages 数组。
     *
     * <p>这里做了三件 OpenAI 格式不需要做的事：
     * <ol>
     *   <li>跳过 system 消息（它们去顶层了）——但<b>中途插入的</b> system
     *       （压缩摘要）只能降级成 user；</li>
     *   <li>把<b>连续的</b> tool 消息合并成一条 user 消息（差异 1）；</li>
     *   <li>把 tool_calls 转成 tool_use 内容块，input 是对象不是字符串（差异 2）。</li>
     * </ol>
     */
    private void appendMessages(List<Message> messages, ArrayNode out) {
        boolean inLeadingSystemRun = true;
        int i = 0;
        while (i < messages.size()) {
            Message m = messages.get(i);

            switch (m.role()) {
                case "system" -> {
                    if (inLeadingSystemRun) {
                        // 开头的系统提示词，已经被 collectSystem 拿走放进顶层了
                    } else {
                        // ⚠️ 中途插入的 system（M4 压缩摘要）——
                        //    Anthropic 没有"对话中途的 system 消息"，只能降级成 user。
                        //    语义上会有轻微扭曲，但这是这个协议下能做得最好的选择。
                        out.add(userText(m.content() == null ? "" : m.content()));
                    }
                    i++;
                }

                case "user" -> {
                    inLeadingSystemRun = false;
                    out.add(userText(m.content() == null ? "" : m.content()));
                    i++;
                }

                case "assistant" -> {
                    inLeadingSystemRun = false;
                    out.add(assistantMessage(m));
                    i++;
                }

                case "tool" -> {
                    inLeadingSystemRun = false;
                    // ★ 差异 1：把所有连续的 tool 结果合并进一条 user 消息
                    ArrayNode blocks = json.createArrayNode();
                    while (i < messages.size() && "tool".equals(messages.get(i).role())) {
                        Message toolMsg = messages.get(i);
                        ObjectNode block = blocks.addObject();
                        block.put("type", "tool_result");
                        block.put("tool_use_id", toolMsg.toolCallId());
                        block.put("content", toolMsg.content() == null ? "" : toolMsg.content());
                        i++;
                    }
                    ObjectNode userMsg = json.createObjectNode();
                    userMsg.put("role", "user");
                    userMsg.set("content", blocks);
                    out.add(userMsg);
                }

                default -> i++;
            }
        }
    }

    private ObjectNode userText(String text) {
        ObjectNode node = json.createObjectNode();
        node.put("role", "user");
        ArrayNode blocks = node.putArray("content");
        blocks.addObject().put("type", "text").put("text", text);
        return node;
    }

    private ObjectNode assistantMessage(Message m) {
        ObjectNode node = json.createObjectNode();
        node.put("role", "assistant");
        ArrayNode blocks = node.putArray("content");

        if (m.content() != null && !m.content().isEmpty()) {
            blocks.addObject().put("type", "text").put("text", m.content());
        }

        if (m.toolCalls() != null) {
            for (ToolCall call : m.toolCalls()) {
                ObjectNode use = blocks.addObject();
                use.put("type", "tool_use");
                use.put("id", call.id());
                use.put("name", call.name());
                // ★ 差异 2：input 必须是 JSON 对象，不是字符串。
                //    OpenAI 格式里我们原样透传字符串，这里必须解析成对象。
                try {
                    use.set("input", json.readTree(call.arguments()));
                } catch (Exception e) {
                    use.set("input", json.createObjectNode());
                }
            }
        }

        // Anthropic 不允许空 content 数组
        if (blocks.isEmpty()) {
            blocks.addObject().put("type", "text").put("text", "");
        }
        return node;
    }

    /** ToolSpec → Anthropic 格式（注意是 input_schema 不是 parameters，且没有 function 包装层）。 */
    private ArrayNode toolsToJson(List<ToolSpec> specs) {
        ArrayNode tools = json.createArrayNode();
        for (ToolSpec spec : specs) {
            ObjectNode tool = tools.addObject();
            tool.put("name", spec.name());
            tool.put("description", spec.description());
            tool.set("input_schema", spec.parametersSchema());
        }
        return tools;
    }

    // ═══════════════════════════════════════════════════════════
    //  流解析（差异 3：和 OpenAI 格式完全不同的 SSE 结构）
    // ═══════════════════════════════════════════════════════════

    private void readStream(InputStream body, Consumer<StreamEvent> onEvent) throws Exception {
        Map<Integer, String> blockIds = new HashMap<>();
        Accumulator acc = new Accumulator();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                if (dumpRaw) {
                    System.out.println("[RAW] " + line);
                }
                if (line.isBlank() || line.startsWith(":")) {
                    continue;
                }
                // ⚠️ Anthropic 的 SSE 有 `event: xxx` 行。我们不需要它 ——
                //    因为 JSON 负载里本来就有 `type` 字段。跳过即可。
                if (!line.startsWith(DATA_PREFIX)) {
                    continue;
                }

                String payload = line.substring(DATA_PREFIX.length()).trim();
                JsonNode frame;
                try {
                    frame = json.readTree(payload);
                } catch (Exception e) {
                    continue;   // 解析不了的帧（比如心跳）直接跳过
                }
                handleEvent(frame, blockIds, acc, onEvent);
            }
        }
    }

    /** 一次流里累积的状态。Anthropic 把用量拆在 message_start 和 message_delta 两处。 */
    private static final class Accumulator {
        int inputTokens;
        int outputTokens;
        int cacheReadTokens;
        int cacheCreationTokens;

        /**
         * ⚠️ 这里有一处必须"翻译"的语义差异，否则你的成本统计会错得离谱。
         *
         * <p>两个协议对"输入 token"的定义不一样：
         * <pre>
         *   OpenAI/DeepSeek:  prompt_tokens = 全部输入（命中 + 未命中）
         *   Anthropic:        input_tokens  = 只是【未命中缓存】的那部分
         *                     总输入 = input_tokens + cache_read + cache_creation
         * </pre>
         *
         * <p>实测：同一份对话，OpenAI 格式报 prompt_tokens=1607，
         * Anthropic 格式报 input_tokens=199 + cache_read=1408 = 1607。
         * <b>数字一样，但字段的含义完全不同。</b>
         *
         * <p>我们的 Usage.promptTokens 约定为"总输入"（OpenAI 语义），
         * 所以这里必须把三部分加起来。<b>这就是 Provider 该做的事——
         * 把方言翻译成统一的语义，而不只是字段改名。</b>
         */
        Usage usage() {
            return new Usage(
                    inputTokens + cacheReadTokens + cacheCreationTokens,
                    outputTokens,
                    cacheReadTokens);
        }
    }

    private void handleEvent(JsonNode frame,
                             Map<Integer, String> blockIds,
                             Accumulator acc,
                             Consumer<StreamEvent> onEvent) {

        String type = frame.path("type").asText("");

        switch (type) {
            case "message_start" -> {
                JsonNode usage = frame.path("message").path("usage");
                if (usage.isObject()) {
                    acc.inputTokens = usage.path("input_tokens").asInt(acc.inputTokens);
                    acc.cacheReadTokens =
                            usage.path("cache_read_input_tokens").asInt(acc.cacheReadTokens);
                    acc.cacheCreationTokens =
                            usage.path("cache_creation_input_tokens").asInt(acc.cacheCreationTokens);
                }
                // message_start 里的 stop_reason 一定是 null，不在这里发 Done
            }

            case "content_block_start" -> {
                JsonNode block = frame.path("content_block");
                if ("tool_use".equals(block.path("type").asText())) {
                    int index = frame.path("index").asInt();
                    String id = block.path("id").asText();
                    String name = block.path("name").asText();
                    blockIds.put(index, id);
                    onEvent.accept(new StreamEvent.ToolUseStart(id, name));
                }
                // type == "text" 或 "thinking" 的块，内容靠 delta 事件流过来
            }

            case "content_block_delta" -> {
                JsonNode delta = frame.path("delta");
                String deltaType = delta.path("type").asText("");
                switch (deltaType) {
                    case "text_delta" -> {
                        String text = delta.path("text").asText("");
                        if (!text.isEmpty()) {
                            onEvent.accept(new StreamEvent.TextDelta(text));
                        }
                    }
                    case "thinking_delta" -> {
                        String text = delta.path("thinking").asText("");
                        if (!text.isEmpty()) {
                            onEvent.accept(new StreamEvent.ReasoningDelta(text));
                        }
                    }
                    case "input_json_delta" -> {
                        int index = frame.path("index").asInt();
                        String id = blockIds.get(index);
                        String partial = delta.path("partial_json").asText("");
                        if (id != null && !partial.isEmpty()) {
                            onEvent.accept(new StreamEvent.ToolUseArgsDelta(id, partial));
                        }
                    }
                    default -> {
                        // signature_delta 等，忽略
                    }
                }
            }

            case "message_delta" -> {
                JsonNode usage = frame.path("usage");
                if (usage.isObject()) {
                    acc.outputTokens = usage.path("output_tokens").asInt(acc.outputTokens);
                    acc.inputTokens = usage.path("input_tokens").asInt(acc.inputTokens);
                    acc.cacheReadTokens =
                            usage.path("cache_read_input_tokens").asInt(acc.cacheReadTokens);
                    acc.cacheCreationTokens =
                            usage.path("cache_creation_input_tokens").asInt(acc.cacheCreationTokens);
                }
                JsonNode stopReason = frame.path("delta").path("stop_reason");
                if (stopReason.isTextual()) {
                    onEvent.accept(new StreamEvent.Done(
                            mapStopReason(stopReason.asText()), acc.usage()));
                }
            }

            case "error" -> onEvent.accept(new StreamEvent.Failure(
                    frame.path("error").path("message").asText(frame.toString())));

            default -> {
                // ping / content_block_stop / message_stop 都不用处理
                // ⚠️ 注意这里<b>没有 [DONE] 哨兵</b> —— 连接直接关闭就是结束。
                //    这是和 OpenAI 格式最直观的一个区别。
            }
        }
    }

    private static StopReason mapStopReason(String reason) {
        return switch (reason) {
            case "end_turn", "stop_sequence" -> StopReason.END_TURN;
            case "tool_use" -> StopReason.TOOL_USE;
            case "max_tokens" -> StopReason.MAX_TOKENS;
            default -> StopReason.OTHER;
        };
    }
}
