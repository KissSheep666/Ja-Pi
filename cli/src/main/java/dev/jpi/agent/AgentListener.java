package dev.jpi.agent;

import dev.jpi.context.Compactor;
import dev.jpi.provider.StopReason;
import dev.jpi.provider.Usage;
import dev.jpi.tool.ToolResult;

/**
 * agent 循环里发生的事。全部是 default 空实现，UI 只覆写关心的。
 */
public interface AgentListener {

    /** 一次模型调用开始。UI 用它计时。 */
    default void onRoundStart() {
    }

    default void onText(String delta) {
    }

    default void onReasoning(String delta) {
    }

    default void onToolCallStart(String id, String name) {
    }

    default void onToolCallArgs(String id, String fragment) {
    }

    /**
     * @param result 工具结果
     * @param label  "工具名 + 关键参数"的短标签，给用户看
     */
    default void onToolResult(ToolResult result, String label) {
    }

    default void onRoundEnd(StopReason reason, Usage usage) {
    }

    default void onFailure(String message) {
    }

    default void onLimitReached(int maxRounds) {
    }

    default void onCancelled() {
    }

    default void onContextUsage(int estimated, int budget, int actual) {
    }

    default void onCompactionLog(String message) {
    }

    default void onCompacted(Compactor.Result result) {
    }
}
