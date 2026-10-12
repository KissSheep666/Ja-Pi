package dev.learn.agent.provider;

public enum StopReason {

    END_TURN,

    TOOL_USE,

    MAX_TOKENS,

    OTHER;

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
