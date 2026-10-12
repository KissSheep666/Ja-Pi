package dev.learn.agent.context;

/**
 * 上下文预算：什么时候该压缩。
 *
 * <p>这里只有三个数字，但每一个都要想清楚为什么是它。
 *
 * @param tokenBudget       我们允许对话历史涨到多大。<b>注意这不是模型的上下文上限</b>
 *                          （deepseek-flash 是 1M），而是我们自愿设的一个"舒适区上限"。
 *                          设得远低于上限，理由有三：更便宜、更快、模型在短上下文里更专注。
 * @param compactAtRatio    用量达到预算的百分之多少就触发压缩。留出余量是因为
 *                          压缩本身要发一次请求，而它也会消耗上下文。
 * @param keepRecentMessages 最近多少条消息不参与压缩。
 *                          <b>压缩丢掉的是"已经做完的事"，绝不能丢掉"正在做的事"。</b>
 */
public record ContextBudget(int tokenBudget, double compactAtRatio, int keepRecentMessages) {

    /**
     * 默认预算。
     *
     * <p>⚠️ 这里刻意设成 24000 而不是接近 1M 上限。两个原因：
     * <ol>
     *   <li>1M 的上下文跑满一次要花不少钱，学习项目没必要；</li>
     *   <li><b>只有设小了，你才能在一次对话里看到压缩真的发生。</b>
     *       真实的压缩可能几十轮才触发一次，那样你什么都观察不到。</li>
     * </ol>
     * 真实产品里这个值会接近模型上限的 60%~80%。
     */
    public static final ContextBudget DEFAULT = new ContextBudget(24_000, 0.6, 8);

    public boolean shouldCompact(int estimatedTokens) {
        return estimatedTokens >= tokenBudget * compactAtRatio;
    }

    public int triggerThreshold() {
        return (int) (tokenBudget * compactAtRatio);
    }
}
