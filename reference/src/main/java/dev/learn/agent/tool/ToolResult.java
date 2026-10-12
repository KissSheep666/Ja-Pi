package dev.learn.agent.tool;

/**
 * 一次工具执行的结果。
 *
 * <p>⚠️ 注意 {@code error} 标记只用于<b>我们自己的显示</b>。
 * 回传给模型时没有独立的错误字段——错误信息就是 content 的内容本身。
 * 所以错误文案要写成模型看得懂、能据此纠正的样子，
 * 比如"文件不存在：src/Main.java（当前目录下有 src/ 但没有 Main.java）"，
 * 而不是 Java 的堆栈。
 *
 * @param toolCallId 对应哪次调用
 * @param content    给模型看的文本
 * @param error      我们自己标记这是失败，用于显示成红色
 */
public record ToolResult(String toolCallId, String content, boolean error) {

    public static ToolResult ok(String toolCallId, String content) {
        return new ToolResult(toolCallId, content, false);
    }

    public static ToolResult error(String toolCallId, String message) {
        return new ToolResult(toolCallId, message, true);
    }
}
