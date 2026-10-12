package dev.learn.agent.provider;

import java.util.List;

/**
 * 一次模型请求。
 *
 * <p>M2 相比 M1 多了一个 {@code tools} 字段——这就是当初把它做成对象而不是
 * 直接传 List&lt;Message&gt; 的回报：Provider 接口一个字都没改。
 *
 * @param model    模型 ID
 * @param messages 完整对话历史（含 system、含工具结果）
 * @param tools    本次允许模型调用的工具。空列表表示不带工具。
 * @param thinking 是否开启思考模式
 */
public record ChatRequest(
        String model,
        List<Message> messages,
        List<ToolSpec> tools,
        boolean thinking) {

    public static ChatRequest plain(String model, List<Message> messages) {
        return new ChatRequest(model, messages, List.of(), false);
    }
}
