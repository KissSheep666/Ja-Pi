package dev.jpi.agent;

import dev.jpi.provider.StopReason;
import dev.jpi.provider.StreamEvent;
import dev.jpi.provider.ToolCall;
import dev.jpi.provider.Usage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantTurnTest {

    @Test
    void accumulatesFragmentedToolArgumentsByCallId() {
        AssistantTurn turn = new AssistantTurn();
        turn.accept(new StreamEvent.ReasoningDelta("先想一下"));
        turn.accept(new StreamEvent.TextDelta("我来读文件"));
        turn.accept(new StreamEvent.ToolUseStart("call_1", "read_file"));
        turn.accept(new StreamEvent.ToolUseArgsDelta("call_1", "{\"pa"));
        turn.accept(new StreamEvent.ToolUseArgsDelta("call_1", "th\":\"a.txt\"}"));
        turn.accept(new StreamEvent.ToolUseStart("call_2", "glob"));
        turn.accept(new StreamEvent.ToolUseArgsDelta("call_2", "{}"));
        turn.accept(new StreamEvent.Done(StopReason.TOOL_USE, new Usage(10, 5, 2)));

        assertEquals("我来读文件", turn.contentText());
        assertEquals("先想一下", turn.reasoningText());
        assertEquals(StopReason.TOOL_USE, turn.stopReason());
        assertEquals(new Usage(10, 5, 2), turn.usage());

        List<ToolCall> calls = turn.toolCalls();
        assertEquals(2, calls.size());
        assertEquals("read_file", calls.get(0).name());
        assertEquals("{\"path\":\"a.txt\"}", calls.get(0).arguments());
        assertEquals("glob", calls.get(1).name());
        assertEquals("{}", calls.get(1).arguments());
    }

    @Test
    void ignoresArgumentsForUnknownCall() {
        AssistantTurn turn = new AssistantTurn();
        turn.accept(new StreamEvent.ToolUseArgsDelta("missing", "{}"));
        assertTrue(turn.toolCalls().isEmpty());
        assertFalse(turn.failed());
    }

    @Test
    void recordsFailure() {
        AssistantTurn turn = new AssistantTurn();
        turn.accept(new StreamEvent.Failure("HTTP 400"));
        assertTrue(turn.failed());
        assertEquals("HTTP 400", turn.failureMessage());
    }

    @Test
    void reasoningIsEmptyStringWhenAbsent() {
        AssistantTurn turn = new AssistantTurn();
        assertEquals("", turn.reasoningText());
    }
}
