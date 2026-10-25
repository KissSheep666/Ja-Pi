package dev.jpi.context;

import dev.jpi.config.Settings;
import dev.jpi.provider.ChatRequest;
import dev.jpi.provider.Message;
import dev.jpi.provider.Provider;
import dev.jpi.provider.StopReason;
import dev.jpi.provider.StreamEvent;
import dev.jpi.provider.ToolCall;
import dev.jpi.provider.Usage;
import dev.jpi.util.Cancellation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactorTest {

    @Test
    void summarizesOldHistoryAndKeepsRecentMessages() {
        SummaryProvider provider = new SummaryProvider(false);
        Settings settings = new Settings("test-model", false, 24_000, 0.6, 3, false, false);
        Compactor compactor = new Compactor(provider, settings);

        List<Message> conversation = List.of(
                Message.system("SYS"),
                Message.user("u1"),
                Message.assistant("a1", "", List.of(new ToolCall("call_1", "read_file", "{}"))),
                Message.tool("call_1", "file-content"),
                Message.user("u2"),
                Message.assistant("a2", "", null),
                Message.user("u3")
        );

        List<String> logs = new ArrayList<>();
        Compactor.Result result = compactor.compact(
                conversation, 100, new Cancellation(), logs::add);

        assertEquals(3, result.messagesRemoved());
        assertEquals(5, result.messages().size());
        assertEquals("system", result.messages().get(0).role());
        assertTrue(result.messages().get(1).content().contains("SUMMARY"));
        assertEquals(conversation.subList(4, 7), result.messages().subList(2, 5));

        ChatRequest request = provider.requests.get(0);
        assertTrue(request.tools().isEmpty());
        assertFalse(request.thinking());
        assertEquals(1, request.messages().size());
        assertTrue(request.messages().get(0).content().contains("=== 对话历史开始 ==="));
    }

    @Test
    void keepsHistoryOnSummaryFailure() {
        SummaryProvider provider = new SummaryProvider(true);
        Settings settings = new Settings("test-model", false, 24_000, 0.6, 2, false, false);
        Compactor compactor = new Compactor(provider, settings);

        List<Message> conversation = List.of(
                Message.system("SYS"),
                Message.user("u1"),
                Message.assistant("a1", "", null),
                Message.user("u2"),
                Message.assistant("a2", "", null),
                Message.user("u3")
        );
        Compactor.Result result = compactor.compact(
                conversation, 0, new Cancellation(), message -> {
                });

        assertEquals(0, result.messagesRemoved());
        assertEquals(conversation, result.messages());
    }

    @Test
    void doesNothingWhenHistoryIsShort() {
        SummaryProvider provider = new SummaryProvider(false);
        Settings settings = new Settings("test-model", false, 24_000, 0.6, 8, false, false);
        Compactor compactor = new Compactor(provider, settings);

        List<Message> conversation = List.of(
                Message.system("SYS"),
                Message.user("u1"),
                Message.assistant("a1", "", null));

        Compactor.Result result = compactor.compact(
                conversation, 0, new Cancellation(), message -> {
                });

        assertEquals(0, result.messagesRemoved());
        assertTrue(provider.requests.isEmpty());
    }

    @Test
    void cutAlwaysLandsOnUserBoundary() {
        SummaryProvider provider = new SummaryProvider(false);
        Settings settings = new Settings("test-model", false, 24_000, 0.6, 2, false, false);
        Compactor compactor = new Compactor(provider, settings);

        // 从末尾数第 2 条（index 5）是 tool，算法必须往前退到最近的 user（index 3）
        List<Message> conversation = List.of(
                Message.system("SYS"),
                Message.user("u1 " + "x".repeat(2000)),
                Message.assistant("a1", "", null),
                Message.user("u2"),
                Message.assistant("a2", "", List.of(new ToolCall("call_2", "read_file", "{}"))),
                Message.tool("call_2", "r"),
                Message.user("u3"));

        Compactor.Result result = compactor.compact(
                conversation, 0, new Cancellation(), message -> {
                });

        assertTrue(result.messagesRemoved() > 0);
        assertEquals("user", result.messages().get(2).role());
        assertEquals("u2", result.messages().get(2).content());
        assertEquals("tool", result.messages().get(4).role());
    }

    private static final class SummaryProvider implements Provider {
        private final boolean fail;
        private final List<ChatRequest> requests = new ArrayList<>();

        private SummaryProvider(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void stream(ChatRequest request, Consumer<StreamEvent> onEvent) {
            requests.add(request);
            if (fail) {
                onEvent.accept(new StreamEvent.Failure("boom"));
                return;
            }
            onEvent.accept(new StreamEvent.TextDelta("SUMMARY"));
            onEvent.accept(new StreamEvent.Done(StopReason.END_TURN, Usage.EMPTY));
        }
    }
}
