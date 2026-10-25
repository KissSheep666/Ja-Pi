package dev.jpi.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.jpi.agent.AssistantTurn;
import dev.jpi.provider.anthropic.AnthropicProvider;
import dev.jpi.util.Cancellation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class AnthropicProviderTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> apiKeyHeader = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void parsesToolUseAndNormalizesInputTokens() throws Exception {
        String sse = """
                data: {"type":"message_start","message":{"usage":{"input_tokens":199,"cache_read_input_tokens":1408,"cache_creation_input_tokens":0}}}

                data: {"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"read_file"}}

                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\\"path\\":"}}

                data: {"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"\\"a.txt\\"}"}}

                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":12}}

                """;
        String baseUrl = startServer(sse);

        AnthropicProvider provider = new AnthropicProvider("sk-ant", baseUrl, 8192, false);
        List<StreamEvent> events = new ArrayList<>();
        provider.stream(new ChatRequest("deepseek-flash", List.of(Message.user("hi")),
                List.of(spec()), false), events::add);

        AssistantTurn turn = new AssistantTurn();
        events.forEach(turn::accept);

        assertEquals(StopReason.TOOL_USE, turn.stopReason());
        assertEquals(new Usage(1607, 12, 1408), turn.usage());
        assertEquals(1, turn.toolCalls().size());
        assertEquals("toolu_1", turn.toolCalls().get(0).id());
        assertEquals("read_file", turn.toolCalls().get(0).name());
        assertEquals("{\"path\":\"a.txt\"}", turn.toolCalls().get(0).arguments());
        assertEquals("sk-ant", apiKeyHeader.get());
    }

    @Test
    void serializesSystemToolResultsAndToolSchemas() throws Exception {
        String baseUrl = startServer("""
                data: {"type":"message_start","message":{"usage":{"input_tokens":10}}}

                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}

                """);

        AnthropicProvider provider = new AnthropicProvider("sk-ant", baseUrl, 8192, false);
        List<Message> messages = List.of(
                Message.system("SYS"),
                Message.user("hi"),
                Message.assistant("calling", "", List.of(
                        new ToolCall("toolu_1", "read_file", "{\"path\":\"a.txt\"}"),
                        new ToolCall("toolu_2", "glob", "{\"pattern\":\"*.java\"}"))),
                Message.tool("toolu_1", "r1"),
                Message.tool("toolu_2", "r2"),
                Message.user("next"));

        provider.stream(new ChatRequest("model", messages, List.of(spec()), true),
                new Cancellation(), event -> {
                });

        JsonNode body = JSON.readTree(requestBody.get());
        assertEquals("SYS", body.path("system").asText());
        assertEquals("enabled", body.path("thinking").path("type").asText());
        assertEquals("model", body.path("model").asText());

        JsonNode sent = body.path("messages");
        assertEquals(4, sent.size());
        assertEquals("user", sent.get(0).path("role").asText());
        assertEquals("hi", sent.get(0).path("content").get(0).path("text").asText());

        JsonNode assistant = sent.get(1);
        assertEquals("assistant", assistant.path("role").asText());
        assertEquals("calling", assistant.path("content").get(0).path("text").asText());
        assertEquals("tool_use", assistant.path("content").get(1).path("type").asText());
        assertEquals("a.txt", assistant.path("content").get(1)
                .path("input").path("path").asText());

        // 两个 tool 结果合并进同一条 user 消息
        JsonNode toolResults = sent.get(2);
        assertEquals("user", toolResults.path("role").asText());
        assertEquals(2, toolResults.path("content").size());
        assertEquals("tool_result", toolResults.path("content").get(0).path("type").asText());
        assertEquals("toolu_1", toolResults.path("content").get(0).path("tool_use_id").asText());

        assertEquals("next", sent.get(3).path("content").get(0).path("text").asText());

        JsonNode tool = body.path("tools").get(0);
        assertTrue(tool.path("input_schema").isObject());
        assertFalse(tool.has("parameters"));
    }

    @Test
    void midConversationSystemIsDowngradedToUser() throws Exception {
        String baseUrl = startServer("""
                data: {"type":"message_start","message":{"usage":{"input_tokens":10}}}

                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}

                """);

        AnthropicProvider provider = new AnthropicProvider("sk-ant", baseUrl, 8192, false);
        List<Message> messages = List.of(
                Message.system("SYS"),
                Message.user("hi"),
                Message.system("[以下是更早对话的摘要]"),
                Message.user("next"));

        provider.stream(new ChatRequest("model", messages, List.of(), false),
                new Cancellation(), event -> {
                });

        JsonNode sent = JSON.readTree(requestBody.get()).path("messages");
        assertEquals(3, sent.size());
        for (JsonNode message : sent) {
            assertEquals("user", message.path("role").asText());
        }
        assertTrue(sent.get(1).path("content").get(0).path("text").asText()
                .contains("摘要"));
    }

    private String startServer(String response) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static ToolSpec spec() {
        var schema = JSON.createObjectNode();
        schema.put("type", "object");
        schema.putObject("properties").putObject("path").put("type", "string");
        schema.putArray("required").add("path");
        return new ToolSpec("read_file", "read a file", schema);
    }
}
