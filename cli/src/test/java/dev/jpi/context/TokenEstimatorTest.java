package dev.jpi.context;

import dev.jpi.provider.Message;
import dev.jpi.provider.ToolCall;
import dev.jpi.provider.ToolSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenEstimatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void estimatesAsciiAndCjk() {
        assertEquals(2, TokenEstimator.estimate("abcd"));
        assertEquals(2, TokenEstimator.estimate("你好"));
        assertEquals(0, TokenEstimator.estimate(""));
        assertEquals(0, TokenEstimator.estimate((String) null));
    }

    @Test
    void messageEstimateIncludesReasoningAndToolArguments() {
        Message plain = Message.user("hello");
        Message withTools = Message.assistant("hi", "思考内容", List.of(
                new ToolCall("id", "read_file", "{\"path\":\"a.txt\"}")));

        assertTrue(TokenEstimator.estimate(List.of(withTools))
                > TokenEstimator.estimate(List.of(plain)));
    }

    @Test
    void toolEstimateCountsDescriptionAndSchema() {
        ToolSpec spec = new ToolSpec("read_file", "读取文件内容", JSON.createObjectNode()
                .put("type", "object"));
        assertTrue(TokenEstimator.estimateTools(List.of(spec)) > 0);
        assertEquals(0, TokenEstimator.estimateTools(List.of()));
    }
}
