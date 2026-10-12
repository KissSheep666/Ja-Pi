package dev.learn.agent.provider;

import java.util.List;

/**
 * 对话历史里的一条消息。
 *
 * <p>M2 相比 M1 多了三个字段，都是为了工具调用：
 * <ul>
 *   <li>{@code reasoningContent} —— 见铁律 6。带 tools 的请求必须把历史轮次的思考内容回传，否则 400。</li>
 *   <li>{@code toolCalls} —— assistant 消息里"我要调这些工具"。</li>
 *   <li>{@code toolCallId} —— tool 消息回答的是哪一次调用（铁律 1）。</li>
 * </ul>
 *
 * <p>用一堆可空字段而不是 sealed 子类型，是因为消息的这几种形态和线格式几乎一一对应，
 * 硬拆成子类型反而要在序列化时再合并回去，得不偿失。
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

    /**
     * 助手消息。reasoningContent 传 null 表示"这一轮没有思考内容"。
     * toolCalls 为空时会被规整成 null，避免序列化时多出一个空数组。
     */
    public static Message assistant(String content, String reasoningContent, List<ToolCall> toolCalls) {
        List<ToolCall> calls = (toolCalls == null || toolCalls.isEmpty())
                ? null
                : List.copyOf(toolCalls);
        return new Message("assistant", content, reasoningContent, calls, null);
    }

    /** 工具执行结果。toolCallId 必须对应某条 assistant 消息里的某个 toolCall.id。 */
    public static Message tool(String toolCallId, String content) {
        return new Message("tool", content, null, null, toolCallId);
    }
}
