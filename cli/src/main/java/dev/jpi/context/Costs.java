package dev.jpi.context;

import dev.jpi.provider.Usage;

/**
 * 按 deepseek-flash 非高峰价估算费用（每 1M token）：
 * 缓存命中 0.003，缓存未命中 0.15，输出 0.60 美元。
 *
 * <p>只用于给用户一个量级感；真实账单以服务端为准。
 * 换成别的模型/端点是，这个数字会不准 —— 所以 UI 里统一标"≈"。
 */
public final class Costs {

    private static final double CACHE_HIT_PER_MILLION = 0.003;
    private static final double CACHE_MISS_PER_MILLION = 0.15;
    private static final double OUTPUT_PER_MILLION = 0.60;

    private Costs() {
    }

    public static double estimateUsd(Usage usage) {
        if (usage == null) {
            return 0;
        }
        double cost = usage.cacheHitTokens() / 1_000_000.0 * CACHE_HIT_PER_MILLION;
        cost += usage.cacheMissTokens() / 1_000_000.0 * CACHE_MISS_PER_MILLION;
        cost += usage.completionTokens() / 1_000_000.0 * OUTPUT_PER_MILLION;
        return cost;
    }

    public static String formatUsd(double usd) {
        if (usd <= 0) {
            return "$0";
        }
        if (usd < 0.000001) {
            return "<$0.000001";
        }
        return String.format("$%.6f", usd);
    }
}
