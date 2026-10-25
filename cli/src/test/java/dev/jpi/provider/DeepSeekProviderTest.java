package dev.jpi.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.jpi.agent.AssistantTurn;
import dev.jpi.provider.deepseek.DeepSeekProvider;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(20)
class DeepSeekProviderTest {

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
    void parsesFragmentedToolCallsAndUsage() throws Exception {
        String sse = """
                data: {"choices":[{"delta":{"reasoning_content":"think"}}]}
                data: {"choices":[{"delta":{"content":"你"}}]}
                data: {"choices":[{"delta":{"content":"好"}}]}
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"read_file","arguments":""}}]}}]}
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\\"path\\":"}}]}}]}
                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\\"a.txt\\"}"}}]}}]}
                data: {"choices":[{"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":100,"completion_tokens":20,"prompt_cache_hit_tokens":40}}
                data: [DONE]
                """;
        String baseUrl = startServer(200, sse);

        DeepSeekProvider provider = new DeepSeekProvider("sk-test", baseUrl, false);
        List<StreamEvent> events = new ArrayList<>();
        provider.stream(new ChatRequest("deepseek-flash", List.of(Message.user("hi")),
                List.of(spec()), false), events::add);

        AssistantTurn turn = new AssistantTurn();
        events.forEach(turn::accept);

        assertEquals("你好", turn.contentText());
        assertEquals("think", turn.reasoningText());
        assertEquals(StopReason.TOOL_USE, turn.stopReason());
        assertEquals(1, turn.toolCalls().size());
        assertEquals("call_1", turn.toolCalls().get(0).id());
        assertEquals("read_file", turn.toolCalls().get(0).name());
        assertEquals("{\"path\":\"a.txt\"}", turn.toolCalls().get(0).arguments());
        assertEquals(new Usage(100, 20, 40), turn.usage());
        assertTrue(events.stream().anyMatch(e -> e instanceof StreamEvent.ToolUseStart));
        assertEquals("Bearer sk-test", apiKeyHeader.get());
    }

    @Test
    void sendsReasoningAndToolMessagesBackToApi() throws Exception {
        String baseUrl = startServer(200, """
                data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":5,"completion_tokens":1,"prompt_cache_hit_tokens":0}}
                data: [DONE]
                """);

        DeepSeekProvider provider = new DeepSeekProvider("sk-test", baseUrl, false);
        List<Message> messages = List.of(
                Message.system("SYS"),
                Message.user("hi"),
                Message.assistant("calling", "thought", List.of(
                        new ToolCall("call_1", "read_file", "{\"path\":\"a.txt\"}"))),
                Message.tool("call_1", "file-content"));

        provider.stream(new ChatRequest("deepseek-flash", messages, List.of(spec()), true),
                new Cancellation(), event -> {
                });

        JsonNode body = JSON.readTree(requestBody.get());
        assertEquals("enabled", body.path("thinking").path("type").asText());
        assertEquals("deepseek-flash", body.path("model").asText());

        JsonNode sent = body.path("messages");
        assertEquals("system", sent.get(0).path("role").asText());
        assertEquals("thought", sent.get(2).path("reasoning_content").asText());
        assertEquals("call_1", sent.get(3).path("tool_call_id").asText());
        assertEquals("tool", sent.get(3).path("role").asText());

        JsonNode tool = body.path("tools").get(0);
        assertEquals("function", tool.path("type").asText());
        assertTrue(tool.path("function").path("parameters").isObject());
    }

    @Test
    void reportsHttpErrors() throws Exception {
        String baseUrl = startServer(400, "{\"error\":{\"message\":\"bad key\"}}");

        DeepSeekProvider provider = new DeepSeekProvider("sk-test", baseUrl, false);
        List<StreamEvent> events = new ArrayList<>();
        provider.stream(new ChatRequest("m", List.of(Message.user("hi")), List.of(), false),
                events::add);

        StreamEvent.Failure failure = (StreamEvent.Failure) events.get(0);
        assertTrue(failure.message().contains("400"));
        assertTrue(failure.message().contains("bad key"));
    }

    @Test
    void cancelledRequestReportsCancellation() throws Exception {
        String baseUrl = startServer(200, """
                data: {"choices":[{"delta":{"content":"partial"}}]}
                """);

        DeepSeekProvider provider = new DeepSeekProvider("sk-test", baseUrl, false);
        Cancellation cancellation = new Cancellation();
        cancellation.cancel();
        List<StreamEvent> events = new ArrayList<>();

        provider.stream(new ChatRequest("m", List.of(Message.user("hi")), List.of(), false),
                cancellation, events::add);

        StreamEvent.Failure failure = (StreamEvent.Failure) events.get(0);
        assertEquals("已取消", failure.message());
    }

    private String startServer(int status, String response) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type",
                    status == 200 ? "text/event-stream" : "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
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
