package dev.jpi.tool;

/**
 * 一次工具执行的结果。
 *
 * <p>{@code error} 只用于我们自己的显示。回传给模型时错误信息就是 content 本身，
 * 所以文案要写成模型看得懂、能据此纠正的样子。
 *
 * @param durationMs 执行耗时，仅用于 UI
 */
public record ToolResult(String toolCallId, String content, boolean error, long durationMs) {

    public static ToolResult ok(String toolCallId, String content) {
        return new ToolResult(toolCallId, content, false, 0);
    }

    public static ToolResult error(String toolCallId, String message) {
        return new ToolResult(toolCallId, message, true, 0);
    }

    public static ToolResult timed(String toolCallId, String content, boolean error, long durationMs) {
        return new ToolResult(toolCallId, content, error, durationMs);
    }

    public ToolResult withDuration(long durationMs) {
        return new ToolResult(toolCallId, content, error, durationMs);
    }
}
