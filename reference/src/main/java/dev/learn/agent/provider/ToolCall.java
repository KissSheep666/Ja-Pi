package dev.learn.agent.provider;

/**
 * 一个已经"组装完成"的工具调用。
 *
 * <p>注意 arguments 是**原始 JSON 字符串**，不是解析好的对象。
 * 两个原因：
 * <ol>
 *   <li>回传给 API 时必须原样送回字符串，解析再序列化可能改变格式；</li>
 *   <li>解析失败时你还能把原始文本打进日志，否则排查无从下手。</li>
 * </ol>
 *
 * @param id        服务端给的调用 ID（形如 call_00_EQSI...），回灌结果时要带上
 * @param name      工具名
 * @param arguments 完整的 JSON 字符串，例如 {"location":"Hangzhou"}
 */
public record ToolCall(String id, String name, String arguments) {
}
