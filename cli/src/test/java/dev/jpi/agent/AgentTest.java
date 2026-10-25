package dev.jpi.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jpi.config.Settings;
import dev.jpi.permission.PermissionManager;
import dev.jpi.provider.ChatRequest;
import dev.jpi.provider.Message;
import dev.jpi.provider.Provider;
import dev.jpi.provider.StopReason;
import dev.jpi.provider.StreamEvent;
import dev.jpi.provider.Usage;
import dev.jpi.tool.Schema;
import dev.jpi.tool.Tool;
import dev.jpi.tool.ToolContext;
import dev.jpi.tool.ToolRegistry;
import dev.jpi.tool.ToolResult;
import dev.jpi.util.Cancellation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    private ScriptedProvider provider;
    private Agent agent;
    private RecordingListener listener;

    @BeforeEach
    void setUp() {
        provider = new ScriptedProvider();
        ToolRegistry registry = new ToolRegistry().register(new EchoTool());
        Settings settings = new Settings("test-model", false, 24_000, 0.6, 8, false, false);
        ToolContext context = new ToolContext(temp, PermissionManager.allowAll());
        agent = new Agent(provider, registry, context, settings, "SYS", null);
        listener = new RecordingListener();
    }

    @Test
    void runsToolLoopAndWritesToolResultsAsSeparateMessages() {
        provider.enqueue(
                new StreamEvent.ToolUseStart("call_1", "echo"),
                new StreamEvent.ToolUseArgsDelta("call_1", "{\"x\":"),
                new StreamEvent.ToolUseArgsDelta("call_1", "\"1\"}"),
                new StreamEvent.Done(StopReason.TOOL_USE, new Usage(10, 2, 0)));
        provider.enqueue(
                new StreamEvent.ReasoningDelta("想一下"),
                new StreamEvent.TextDelta("done"),
                new StreamEvent.Done(StopReason.END_TURN, new Usage(20, 3, 1)));

        agent.run("hi", listener, new Cancellation());

        assertEquals(2, provider.calls);
        assertEquals(List.of("done"), listener.texts);
        assertEquals(List.of("OK:1"), listener.toolResults.stream().map(ToolResult::content).toList());
        assertEquals(List.of("echo 1"), listener.labels);
        assertEquals(2, listener.roundEnds);
        assertNull(listener.failure);
        assertTrue(!listener.cancelled);

        List<Message> conversation = agent.conversation();
        assertEquals(List.of("system", "user", "assistant", "tool", "assistant"),
                conversation.stream().map(Message::role).toList());

        Message assistantWithTool = conversation.get(2);
        assertTrue(assistantWithTool.hasToolCalls());
        assertEquals("call_1", assistantWithTool.toolCalls().get(0).id());
        assertEquals("{\"x\":\"1\"}", assistantWithTool.toolCalls().get(0).arguments());

        Message toolMessage = conversation.get(3);
        assertEquals("call_1", toolMessage.toolCallId());
        assertEquals("OK:1", toolMessage.content());

        Message finalAssistant = conversation.get(4);
        assertEquals("done", finalAssistant.content());
        assertEquals("想一下", finalAssistant.reasoningContent());

        assertEquals(new Usage(30, 5, 1), agent.totalUsage());
        assertEquals(20, agent.lastPromptTokens());
    }

    @Test
    void cancellationBeforeRoundDoesNotCallProvider() {
        Cancellation cancellation = new Cancellation();
        cancellation.cancel();

        agent.run("hi", listener, cancellation);

        assertEquals(0, provider.calls);
        assertTrue(listener.cancelled);
        assertEquals(List.of("system", "user"),
                agent.conversation().stream().map(Message::role).toList());
    }

    @Test
    void providerFailureIsReportedAndNoAssistantMessageIsAdded() {
        provider.enqueue(new StreamEvent.Failure("HTTP 400"));

        agent.run("hi", listener, new Cancellation());

        assertEquals("HTTP 400", listener.failure);
        assertEquals(List.of("system", "user"),
                agent.conversation().stream().map(Message::role).toList());
    }

    @Test
    void toolUseWithoutCallsIsReported() {
        provider.enqueue(new StreamEvent.Done(StopReason.TOOL_USE, Usage.EMPTY));

        agent.run("hi", listener, new Cancellation());

        assertNotNull(listener.failure);
        assertTrue(listener.failure.contains("没有收到任何工具调用"));
    }

    @Test
    void clearResetsConversationAndUsage() {
        provider.enqueue(new StreamEvent.TextDelta("done"),
                new StreamEvent.Done(StopReason.END_TURN, new Usage(10, 1, 0)));
        agent.run("hi", listener, new Cancellation());

        agent.clearConversation();

        assertEquals(1, agent.conversation().size());
        assertEquals("system", agent.conversation().get(0).role());
        assertEquals(Usage.EMPTY, agent.totalUsage());
        assertEquals(0, agent.lastPromptTokens());
    }

    private static final class ScriptedProvider implements Provider {
        private final Deque<List<StreamEvent>> scripts = new ArrayDeque<>();
        private int calls;

        void enqueue(StreamEvent... events) {
            scripts.add(List.of(events));
        }

        @Override
        public void stream(ChatRequest request, Consumer<StreamEvent> onEvent) {
            calls++;
            List<StreamEvent> script = scripts.poll();
            assertNotNull(script, "没有为第 " + calls + " 次调用准备脚本");
            script.forEach(onEvent);
        }
    }

    private static final class EchoTool implements Tool {
        @Override
        public String name() {
            return "echo";
        }

        @Override
        public String description() {
            return "echo x";
        }

        @Override
        public JsonNode parametersSchema() {
            return Schema.object().string("x", "x").required("x").build();
        }

        @Override
        public String execute(JsonNode args, ToolContext ctx) {
            return "OK:" + args.path("x").asText();
        }

        @Override
        public boolean requiresApproval() {
            return false;
        }

        @Override
        public String preview(JsonNode args) {
            return "echo " + args.path("x").asText();
        }
    }

    private static final class RecordingListener implements AgentListener {
        private final List<String> texts = new ArrayList<>();
        private final List<ToolResult> toolResults = new ArrayList<>();
        private final List<String> labels = new ArrayList<>();
        private String failure;
        private boolean cancelled;
        private int roundEnds;

        @Override
        public void onText(String delta) {
            texts.add(delta);
        }

        @Override
        public void onToolResult(ToolResult result, String label) {
            toolResults.add(result);
            labels.add(label);
        }

        @Override
        public void onRoundEnd(StopReason reason, Usage usage) {
            roundEnds++;
        }

        @Override
        public void onFailure(String message) {
            failure = message;
        }

        @Override
        public void onCancelled() {
            cancelled = true;
        }
    }
}
