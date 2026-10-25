package dev.jpi.provider;

public enum StopReason {

    END_TURN,

    TOOL_USE,

    MAX_TOKENS,

    OTHER;

    /** OpenAI / DeepSeek 的 finish_reason。 */
    public static StopReason fromWire(String finishReason) {
        if (finishReason == null) {
            return OTHER;
        }
        return switch (finishReason) {
            case "stop" -> END_TURN;
            case "tool_calls", "function_call" -> TOOL_USE;
            case "length" -> MAX_TOKENS;
            default -> OTHER;
        };
    }
}
