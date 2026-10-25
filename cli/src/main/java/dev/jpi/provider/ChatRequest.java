package dev.jpi.provider;

import java.util.List;

/**
 * 一次模型请求。
 *
 * @param model    模型 ID
 * @param messages 完整对话历史（含 system、含工具结果）
 * @param tools    本次允许模型调用的工具；空列表表示不带工具
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
