package dev.learn.agent.agent;

import dev.learn.agent.context.Compactor;
import dev.learn.agent.provider.StopReason;
import dev.learn.agent.provider.Usage;
import dev.learn.agent.tool.ToolResult;

/**
 * agent 循环里发生的事，用默认空实现全部列出来。
 */
public interface AgentListener {

    /** 模型吐出一段正文。 */
    default void onText(String delta) {
    }

    /** 模型吐出一段思考内容（thinking 开启时才有）。 */
    default void onReasoning(String delta) {
    }

    /** 模型开始调用某个工具（参数还在流式传入）。 */
    default void onToolCallStart(String name) {
    }

    /** 工具参数的一个碎片。<b>边收边显示它，就能亲眼看到铁律 3。</b> */
    default void onToolCallArgs(String fragment) {
    }

    /** 某个工具执行完毕。 */
    default void onToolResult(ToolResult result) {
    }

    /** 一轮（一次模型调用 + 可能的工具执行）结束。 */
    default void onRoundEnd(StopReason reason, Usage usage) {
    }

    /** 出错。 */
    default void onFailure(String message) {
    }

    /** 达到最大轮数保护，强制停止。 */
    default void onLimitReached(int maxRounds) {
    }

    // ─────────────────── M4 新增：上下文管理 ───────────────────

    /**
     * 每次发请求之前上报上下文用量。
     *
     * @param estimated 估算值（决定用）
     * @param budget    预算上限
     * @param actual    上一次请求实测的输入 token（0 表示还没发过请求）
     */
    default void onContextUsage(int estimated, int budget, int actual) {
    }

    /** 压缩过程中的进展文字（总结要花几秒，让用户知道在干什么）。 */
    default void onCompactionLog(String message) {
    }

    /** 压缩完成。 */
    default void onCompacted(Compactor.Result result) {
    }
}
