package dev.jpi.provider;

import java.util.List;

/**
 * 对话历史里的一条消息。
 *
 * <p>四种形态：system / user / assistant（可带 reasoning 和 tool_calls）/ tool。
 * 字段允许为空，是因为它们和两种线格式几乎一一对应；硬拆成子类型反而要在
 * 序列化时再合并回去。
 *
 * @param reasoningContent 思考内容。带 tools 的请求里必须回传，否则 DeepSeek 返回 400
 * @param toolCalls        assistant 消息里"我要调这些工具"
 * @param toolCallId       tool 消息回答的是哪一次调用
 */
public record Message(
        String role,
        String content,
        String reasoningContent,
        List<ToolCall> toolCalls,
        String toolCallId) {

    public static Message system(String content) {
        return new Message("system", content, null, null, null);
    }

    public static Message user(String content) {
        return new Message("user", content, null, null, null);
    }

    public static Message assistant(String content, String reasoningContent, List<ToolCall> toolCalls) {
        List<ToolCall> calls = (toolCalls == null || toolCalls.isEmpty())
                ? null
                : List.copyOf(toolCalls);
        return new Message("assistant", content, reasoningContent, calls, null);
    }

    public static Message tool(String toolCallId, String content) {
        return new Message("tool", content, null, null, toolCallId);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
