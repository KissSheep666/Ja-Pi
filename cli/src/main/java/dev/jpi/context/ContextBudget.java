package dev.jpi.context;

/**
 * 上下文预算：什么时候该压缩。
 *
 * @param tokenBudget        我们允许对话历史涨到多大。这不是模型上限，
 *                           而是自愿设的"舒适区上限"：更便宜、更快、模型更专注。
 * @param compactAtRatio     用量达到预算的百分之多少触发压缩。留余量是因为压缩本身也要发请求。
 * @param keepRecentMessages 最近多少条消息不参与压缩。丢掉"已经做完的事"，保住"正在做的事"。
 */
public record ContextBudget(int tokenBudget, double compactAtRatio, int keepRecentMessages) {

    public static final ContextBudget DEFAULT = new ContextBudget(
            dev.jpi.config.Settings.DEFAULT_BUDGET,
            dev.jpi.config.Settings.DEFAULT_COMPACT_RATIO,
            dev.jpi.config.Settings.DEFAULT_KEEP);

    public boolean shouldCompact(int estimatedTokens) {
        return estimatedTokens >= tokenBudget * compactAtRatio;
    }

    public int triggerThreshold() {
        return (int) (tokenBudget * compactAtRatio);
    }
}
