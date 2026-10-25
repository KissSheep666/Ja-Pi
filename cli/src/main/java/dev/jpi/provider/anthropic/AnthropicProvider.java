package dev.jpi.provider.anthropic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.jpi.provider.ChatRequest;
import dev.jpi.provider.Message;
import dev.jpi.provider.Provider;
import dev.jpi.provider.StopReason;
import dev.jpi.provider.StreamEvent;
import dev.jpi.provider.ToolCall;
import dev.jpi.provider.ToolSpec;
import dev.jpi.provider.Usage;
import dev.jpi.util.Cancellation;

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
 * Anthropic Messages 方言。
 *
 * <p>默认连 DeepSeek 的 Anthropic 兼容端点：同一个账号、同一个模型，
 * 完全不同的协议。这样任何行为差异都只能来自协议，不可能来自模型能力。
 *
 * <p>它抹平了四处差异：
 * <ol>
 *   <li>工具结果：Anthropic 要求所有结果塞进同一条 user 消息；OpenAI 要求各自独立；</li>
 *   <li>工具参数：Anthropic 的 input 是对象；OpenAI 的 arguments 是字符串；</li>
 *   <li>SSE 结构：有 event 行、ping、没有 [DONE]；</li>
 *   <li>input_tokens 语义：只算未命中缓存的部分，要加上 cache_read / cache_creation。</li>
 * </ol>
 *
 * <p>抹不平的一处：Anthropic 没有"对话中途的 system 消息"。
 * 压缩摘要只能降级成一条 user 消息 —— 这是该协议下能做的最好选择。
 */
public final class AnthropicProvider implements Provider {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com/anthropic";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String DATA_PREFIX = "data:";
    private static final int MAX_ERROR_BODY = 4_000;

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
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.maxTokens = maxTokens;
        this.dumpRaw = dumpRaw;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Override
    public void stream(ChatRequest request, Consumer<StreamEvent> onEvent) {
        stream(request, new Cancellation(), onEvent);
    }

    @Override
    public void stream(ChatRequest request, Cancellation cancellation, Consumer<StreamEvent> onEvent) {
        try {
            HttpResponse<InputStream> response = http.send(
                    buildHttpRequest(request),
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String body = readErrorBody(response.body());
                onEvent.accept(new StreamEvent.Failure(
                        "HTTP " + response.statusCode() + " — " + body));
                return;
            }

            try (InputStream body = response.body()) {
                cancellation.register(body);
                try {
                    readStream(body, cancellation, onEvent);
                } finally {
                    cancellation.unregister(body);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            onEvent.accept(new StreamEvent.Failure("请求被中断"));
        } catch (Exception e) {
            onEvent.accept(new StreamEvent.Failure(failureMessage(e, cancellation)));
        }
    }

    private static String failureMessage(Exception e, Cancellation cancellation) {
        if (cancellation != null && cancellation.isCancelled()) {
            return Cancellation.MESSAGE;
        }
        String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null ? "" : ": " + message);
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
                .header("x-api-key", apiKey)
                .header("anthropic-version", ANTHROPIC_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(
                        buildBody(request), StandardCharsets.UTF_8))
                .build();
    }

    private String buildBody(ChatRequest request) {
        ObjectNode body = json.createObjectNode();
        body.put("model", request.model());
        body.put("max_tokens", maxTokens);
        body.put("stream", true);
        body.putObject("thinking").put("type", request.thinking() ? "enabled" : "disabled");

        // system 是顶层参数，不是消息
        String system = collectLeadingSystem(request.messages());
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
     * 只收集开头连续的 system 消息。
     *
     * <p>⚠️ 不能收集"所有" system：压缩摘要插在对话中途，如果把它也塞进顶层
     * system，(a) 会和降级成的 user 消息重复，(b) 顶层 system 变了，
     * 整个 KV 缓存前缀就失效了。
     */
    private static String collectLeadingSystem(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message message : messages) {
            if (!"system".equals(message.role())) {
                break;
            }
            if (message.content() != null && !message.content().isEmpty()) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(message.content());
            }
        }
        return sb.toString();
    }

    private void appendMessages(List<Message> messages, ArrayNode out) {
        boolean inLeadingSystemRun = true;
        int i = 0;
        while (i < messages.size()) {
            Message message = messages.get(i);
            switch (message.role()) {
                case "system" -> {
                    if (!inLeadingSystemRun) {
                        // 中途 system（压缩摘要）降级成 user
                        out.add(userText(message.content() == null ? "" : message.content()));
                    }
                    i++;
                }

                case "user" -> {
                    inLeadingSystemRun = false;
                    out.add(userText(message.content() == null ? "" : message.content()));
                    i++;
                }

                case "assistant" -> {
                    inLeadingSystemRun = false;
                    out.add(assistantMessage(message));
                    i++;
                }

                case "tool" -> {
                    inLeadingSystemRun = false;
                    // 差异 1：所有连续的 tool 结果合并进一条 user 消息
                    ArrayNode blocks = json.createArrayNode();
                    while (i < messages.size() && "tool".equals(messages.get(i).role())) {
                        Message toolMessage = messages.get(i);
                        ObjectNode block = blocks.addObject();
                        block.put("type", "tool_result");
                        block.put("tool_use_id", toolMessage.toolCallId());
                        block.put("content", toolMessage.content() == null ? "" : toolMessage.content());
                        i++;
                    }
                    ObjectNode userMessage = json.createObjectNode();
                    userMessage.put("role", "user");
                    userMessage.set("content", blocks);
                    out.add(userMessage);
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

    private ObjectNode assistantMessage(Message message) {
        ObjectNode node = json.createObjectNode();
        node.put("role", "assistant");
        ArrayNode blocks = node.putArray("content");

        if (message.content() != null && !message.content().isEmpty()) {
            blocks.addObject().put("type", "text").put("text", message.content());
        }

        if (message.toolCalls() != null) {
            for (ToolCall call : message.toolCalls()) {
                ObjectNode use = blocks.addObject();
                use.put("type", "tool_use");
                use.put("id", call.id());
                use.put("name", call.name());
                // 差异 2：input 是对象，不是字符串
                try {
                    use.set("input", json.readTree(call.arguments()));
                } catch (Exception e) {
                    use.set("input", json.createObjectNode());
                }
            }
        }

        if (blocks.isEmpty()) {
            blocks.addObject().put("type", "text").put("text", "");
        }
        return node;
    }

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
    //  流解析
    // ═══════════════════════════════════════════════════════════

    private void readStream(InputStream body,
                            Cancellation cancellation,
                            Consumer<StreamEvent> onEvent) throws Exception {
        Map<Integer, String> blockIds = new HashMap<>();
        Accumulator accumulator = new Accumulator();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancellation.isCancelled()) {
                    throw new java.io.IOException(Cancellation.MESSAGE);
                }
                if (dumpRaw) {
                    System.err.println("[RAW] " + line);
                }
                if (line.isBlank() || line.startsWith(":")) {
                    continue;
                }
                // event: 行可以跳过，JSON 负载里本来就有 type
                if (!line.startsWith(DATA_PREFIX)) {
                    continue;
                }
                String payload = line.substring(DATA_PREFIX.length()).trim();
                JsonNode frame;
                try {
                    frame = json.readTree(payload);
                } catch (Exception e) {
                    continue;
                }
                handleEvent(frame, blockIds, accumulator, onEvent);
            }
        }
    }

    private static final class Accumulator {
        int inputTokens;
        int outputTokens;
        int cacheReadTokens;
        int cacheCreationTokens;

        /**
         * Anthropic 的 input_tokens 只是未命中缓存的部分。
         * 统一成"总输入"语义，否则成本统计会错得离谱。
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
                             Accumulator accumulator,
                             Consumer<StreamEvent> onEvent) {
        String type = frame.path("type").asText("");

        switch (type) {
            case "message_start" -> {
                JsonNode usage = frame.path("message").path("usage");
                if (usage.isObject()) {
                    accumulator.inputTokens = usage.path("input_tokens").asInt(accumulator.inputTokens);
                    accumulator.cacheReadTokens =
                            usage.path("cache_read_input_tokens").asInt(accumulator.cacheReadTokens);
                    accumulator.cacheCreationTokens =
                            usage.path("cache_creation_input_tokens").asInt(accumulator.cacheCreationTokens);
                }
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
                    accumulator.outputTokens = usage.path("output_tokens").asInt(accumulator.outputTokens);
                    accumulator.inputTokens = usage.path("input_tokens").asInt(accumulator.inputTokens);
                    accumulator.cacheReadTokens =
                            usage.path("cache_read_input_tokens").asInt(accumulator.cacheReadTokens);
                    accumulator.cacheCreationTokens =
                            usage.path("cache_creation_input_tokens").asInt(accumulator.cacheCreationTokens);
                }
                JsonNode stopReason = frame.path("delta").path("stop_reason");
                if (stopReason.isTextual()) {
                    onEvent.accept(new StreamEvent.Done(
                            mapStopReason(stopReason.asText()), accumulator.usage()));
                }
            }

            case "error" -> onEvent.accept(new StreamEvent.Failure(
                    frame.path("error").path("message").asText(frame.toString())));

            default -> {
                // ping / content_block_stop / message_stop：不需要处理。
                // 注意没有 [DONE] 哨兵，连接关闭就是结束。
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

    private static String readErrorBody(InputStream body) {
        try (InputStream in = body) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return text.length() <= MAX_ERROR_BODY ? text : text.substring(0, MAX_ERROR_BODY) + "…";
        } catch (Exception e) {
            return "（无法读取错误响应体：" + e.getMessage() + "）";
        }
    }

    private static String stripTrailingSlash(String url) {
        String trimmed = url == null ? DEFAULT_BASE_URL : url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
