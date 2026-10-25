package dev.jpi.provider;

/**
 * 一个已经组装完成的工具调用。
 *
 * <p>arguments 保留原始 JSON 字符串，不提前解析：
 * 回传时要原样送回；解析失败时也还能把原文打进日志。
 *
 * @param id        服务端给的调用 ID，回灌结果时要带上
 * @param name      工具名
 * @param arguments 完整 JSON 字符串，例如 {"path":"a.txt"}
 */
public record ToolCall(String id, String name, String arguments) {
}
