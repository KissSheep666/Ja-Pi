package dev.jpi.provider.deepseek;

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
 * OpenAI / Chat Completions 方言（DeepSeek 默认端点）。
 *
 * <p>协议细节是实测的，最容易踩的三处：
 * <ol>
 *   <li>工具参数是分片 JSON，{@code id/name} 只在第一片出现，后续片只有 {@code index}；</li>
 *   <li>继续循环的信号是 {@code finish_reason == "tool_calls"}，不是"有没有 tool_calls 字段"；</li>
 *   <li>usage 和 finish_reason 在同一个 chunk 上，不存在独立的 usage 帧。</li>
 * </ol>
 */
public final class DeepSeekProvider implements Provider {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";
    private static final String DATA_PREFIX = "data:";
    private static final String DONE_SENTINEL = "[DONE]";
    private static final int MAX_ERROR_BODY = 4_000;

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
        this.baseUrl = stripTrailingSlash(baseUrl);
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
        for (Message message : request.messages()) {
            messages.add(messageToJson(message, request.thinking()));
        }

        // 空数组有些实现会报错；没有工具时干脆不带这个字段
        if (!request.tools().isEmpty()) {
            body.set("tools", toolsToJson(request.tools()));
        }
        return body.toString();
    }

    private ObjectNode messageToJson(Message message, boolean thinking) {
        ObjectNode node = json.createObjectNode();
        node.put("role", message.role());

        if ("tool".equals(message.role())) {
            // 铁律 1：每个工具结果是一条独立的 tool 消息
            node.put("tool_call_id", message.toolCallId());
            node.put("content", message.content() == null ? "" : message.content());
            return node;
        }

        // 铁律 6：带 tools 且开思考时，历史轮次的 reasoning_content 必须回传
        if (thinking && "assistant".equals(message.role()) && message.reasoningContent() != null) {
            node.put("reasoning_content", message.reasoningContent());
        }

        if (message.hasToolCalls()) {
            if (message.content() == null || message.content().isEmpty()) {
                node.putNull("content");
            } else {
                node.put("content", message.content());
            }
            ArrayNode calls = node.putArray("tool_calls");
            for (ToolCall call : message.toolCalls()) {
                ObjectNode callNode = calls.addObject();
                callNode.put("id", call.id());
                callNode.put("type", "function");
                // arguments 必须是字符串，原样送回
                callNode.putObject("function")
                        .put("name", call.name())
                        .put("arguments", call.arguments());
            }
            return node;
        }

        node.put("content", message.content() == null ? "" : message.content());
        return node;
    }

    private ArrayNode toolsToJson(List<ToolSpec> specs) {
        ArrayNode tools = json.createArrayNode();
        for (ToolSpec spec : specs) {
            ObjectNode tool = tools.addObject();
            tool.put("type", "function");
            ObjectNode function = tool.putObject("function");
            function.put("name", spec.name());
            function.put("description", spec.description());
            function.set("parameters", spec.parametersSchema());
        }
        return tools;
    }

    // ═══════════════════════════════════════════════════════════
    //  流解析
    // ═══════════════════════════════════════════════════════════

    private void readStream(InputStream body,
                            Cancellation cancellation,
                            Consumer<StreamEvent> onEvent) throws Exception {
        Map<Integer, ToolCallIdentity> identities = new HashMap<>();

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

    private record ToolCallIdentity(String id, String name) {
    }

    private void handleChunk(String payload,
                             Map<Integer, ToolCallIdentity> identities,
                             Consumer<StreamEvent> onEvent) {
        JsonNode frame;
        try {
            frame = json.readTree(payload);
        } catch (Exception e) {
            onEvent.accept(new StreamEvent.Failure("无法解析 SSE 帧：" + payload));
            return;
        }

        // 有些兼容层会发不带 choices 的 ping / usage 帧
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
