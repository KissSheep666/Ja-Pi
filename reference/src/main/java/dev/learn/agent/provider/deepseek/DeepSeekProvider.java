package dev.learn.agent.provider.deepseek;

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
import java.util.Map;
import java.util.function.Consumer;

public class DeepSeekProvider implements Provider {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final String DATA_PREFIX      = "data:";
    private static final String DONE_SENTINEL    = "[DONE]";

    private final String apiKey;
    private final String baseUrl;
    private final boolean dumpRaw;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public DeepSeekProvider(String apiKey, boolean dumpRaw) {
        this(apiKey, DEFAULT_BASE_URL, dumpRaw);
    }

    public DeepSeekProvider(String apiKey, String baseUrl, boolean dumpRaw) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
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
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(
                        buildBody(request), StandardCharsets.UTF_8))
                .build();
    }

    private String buildBody(ChatRequest request) {
        ObjectNode body = json.createObjectNode();
        body.put("model", request.model());
        body.put("stream", true);
        body.putObject("thinking")
            .put("type", request.thinking() ? "enabled" : "disabled");

        ArrayNode messages = body.putArray("messages");
        for (Message m : request.messages()) {
            messages.add(messageToJson(m, request.thinking()));
        }

        // ⚠️ 空数组有些实现会报错，没工具时干脆不带这个字段
        if (!request.tools().isEmpty()) {
            body.set("tools", toolsToJson(request.tools()));
        }

        return body.toString();
    }

    /**
     * 把内部 Message 翻译成 DeepSeek 的线格式。
     *
     * <p>这是整个 Provider 里最容易出错的地方，因为消息有四种形态：
     * <pre>
     *   system/user : {role, content}
     *   assistant   : {role, content, reasoning_content?, tool_calls?}
     *   tool        : {role, tool_call_id, content}
     * </pre>
     *
     * @param thinking 开启思考模式时，assistant 消息必须回传 reasoning_content（铁律 6）
     */
    private ObjectNode messageToJson(Message m, boolean thinking) {
        ObjectNode node = json.createObjectNode();
        node.put("role", m.role());

        // ---- tool 消息：工具执行结果 ----
        if ("tool".equals(m.role())) {
            node.put("tool_call_id", m.toolCallId());
            node.put("content", m.content() == null ? "" : m.content());
            return node;
        }

        // ---- assistant 消息：思考内容（铁律 6）----
        // ⚠️ 只在 thinking 开启时回传。不开思考模式时没有 reasoning_content 可言，
        //    硬塞一个空串进去是无意义的噪声。
        if (thinking && "assistant".equals(m.role()) && m.reasoningContent() != null) {
            node.put("reasoning_content", m.reasoningContent());
        }

        // ---- assistant 消息：工具调用 ----
        boolean hasCalls = m.toolCalls() != null && !m.toolCalls().isEmpty();
        if (hasCalls) {
            // content 允许是 null：模型可能"只调工具不说话"
            if (m.content() == null || m.content().isEmpty()) {
                node.putNull("content");
            } else {
                node.put("content", m.content());
            }
            ArrayNode calls = node.putArray("tool_calls");
            for (ToolCall c : m.toolCalls()) {
                ObjectNode call = calls.addObject();
                call.put("id", c.id());
                call.put("type", "function");
                // ⚠️ arguments 必须是字符串，不是对象！原样送回我们收到的那段 JSON。
                call.putObject("function")
                        .put("name", c.name())
                        .put("arguments", c.arguments());
            }
            return node;
        }

        node.put("content", m.content() == null ? "" : m.content());
        return node;
    }

    /** ToolSpec → DeepSeek 的三层嵌套格式（铁律 4）。 */
    private ArrayNode toolsToJson(java.util.List<ToolSpec> specs) {
        ArrayNode tools = json.createArrayNode();
        for (ToolSpec spec : specs) {
            ObjectNode tool = tools.addObject();
            tool.put("type", "function");
            ObjectNode fn = tool.putObject("function");
            fn.put("name", spec.name());
            fn.put("description", spec.description());
            fn.set("parameters", spec.parametersSchema());
        }
        return tools;
    }

    // ═══════════════════════════════════════════════════════════
    //  流解析
    // ═══════════════════════════════════════════════════════════

    private void readStream(InputStream body, Consumer<StreamEvent> onEvent) throws Exception {
        Map<Integer, ToolCallIdentity> identities = new HashMap<>();

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
                if (!line.startsWith(DATA_PREFIX)) {
                    continue;
                }
                String payload = line.substring(DATA_PREFIX.length()).trim();
                if (payload.equals(DONE_SENTINEL)) {
                    return;
                }
                handleChunk(payload, identities, onEvent);
            }
        }
    }

    private record ToolCallIdentity(String id, String name) {}

    private void handleChunk(String payload,
                             Map<Integer, ToolCallIdentity> identities,
                             Consumer<StreamEvent> onEvent) {

        JsonNode frame;
        try {
            frame = json.readTree(payload);
        } catch (Exception e) {
            onEvent.accept(new StreamEvent.Failure("无法解析的 SSE 帧: " + payload));
            return;
        }

        JsonNode choices = frame.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return;
        }
        JsonNode choice = choices.get(0);

        JsonNode delta = choice.path("delta");
        if (delta.isObject()) {
            dispatchDelta(delta, identities, onEvent);
        }

        JsonNode finishReason = choice.path("finish_reason");
        if (finishReason.isTextual()) {
            onEvent.accept(new StreamEvent.Done(
                    StopReason.fromWire(finishReason.asText()),
                    readUsage(frame.path("usage"))));
        }
    }

    private void dispatchDelta(JsonNode delta,
                               Map<Integer, ToolCallIdentity> identities,
                               Consumer<StreamEvent> onEvent) {

        JsonNode reasoning = delta.path("reasoning_content");
        if (reasoning.isTextual() && !reasoning.asText().isEmpty()) {
            onEvent.accept(new StreamEvent.ReasoningDelta(reasoning.asText()));
        }

        JsonNode content = delta.path("content");
        if (content.isTextual() && !content.asText().isEmpty()) {
            onEvent.accept(new StreamEvent.TextDelta(content.asText()));
        }

        JsonNode toolCalls = delta.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode toolCall : toolCalls) {
                translateToolCall(toolCall, identities, onEvent);
            }
        }
    }

    private void translateToolCall(JsonNode toolCall,
                                   Map<Integer, ToolCallIdentity> identities,
                                   Consumer<StreamEvent> onEvent) {

        int index = toolCall.path("index").asInt(0);

        JsonNode id = toolCall.path("id");
        if (id.isTextual()) {
            String name = toolCall.path("function").path("name").asText("");
            identities.put(index, new ToolCallIdentity(id.asText(), name));
            onEvent.accept(new StreamEvent.ToolUseStart(id.asText(), name));
        }

        JsonNode arguments = toolCall.path("function").path("arguments");
        if (arguments.isTextual() && !arguments.asText().isEmpty()) {
            ToolCallIdentity identity = identities.get(index);
            if (identity == null) {
                onEvent.accept(new StreamEvent.Failure(
                        "收到 index=" + index + " 的参数碎片，但从未见过它的 id"));
                return;
            }
            onEvent.accept(new StreamEvent.ToolUseArgsDelta(identity.id(), arguments.asText()));
        }
    }

    private Usage readUsage(JsonNode usage) {
        if (!usage.isObject()) {
            return Usage.EMPTY;
        }
        return new Usage(
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                usage.path("prompt_cache_hit_tokens").asInt(0));
    }
}
