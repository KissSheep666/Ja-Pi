package dev.jpi.config;

import dev.jpi.context.ContextBudget;

/**
 * 一次会话里可以热修改的设置。
 *
 * <p>Agent、Compactor、REPL 都持有同一个实例：斜杠命令改这里，
 * 下一次请求立刻生效。字段用 volatile 是因为 ToolRegistry 的虚拟线程
 * 和 REPL 线程可能同时读。
 */
public final class Settings {

    public static final String DEFAULT_MODEL = "deepseek-flash";
    public static final int DEFAULT_BUDGET = 24_000;
    public static final double DEFAULT_COMPACT_RATIO = 0.6;
    public static final int DEFAULT_KEEP = 8;

    private volatile String model;
    private volatile boolean thinking;
    private volatile int tokenBudget;
    private volatile double compactAtRatio;
    private volatile int keepRecentMessages;
    private volatile boolean autoApprove;
    private volatile boolean dumpRaw;

    public Settings(String model,
                    boolean thinking,
                    int tokenBudget,
                    double compactAtRatio,
                    int keepRecentMessages,
                    boolean autoApprove,
                    boolean dumpRaw) {
        setModel(model);
        this.thinking = thinking;
        setTokenBudget(tokenBudget);
        setCompactAtRatio(compactAtRatio);
        setKeepRecentMessages(keepRecentMessages);
        this.autoApprove = autoApprove;
        this.dumpRaw = dumpRaw;
    }

    public String model() {
        return model;
    }

    public void setModel(String model) {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("模型 ID 不能为空");
        }
        this.model = model.trim();
    }

    public boolean thinking() {
        return thinking;
    }

    public void setThinking(boolean thinking) {
        this.thinking = thinking;
    }

    public int tokenBudget() {
        return tokenBudget;
    }

    public void setTokenBudget(int tokenBudget) {
        if (tokenBudget < 1_000) {
            throw new IllegalArgumentException("上下文预算不能小于 1000 token");
        }
        this.tokenBudget = tokenBudget;
    }

    public double compactAtRatio() {
        return compactAtRatio;
    }

    public void setCompactAtRatio(double compactAtRatio) {
        if (compactAtRatio <= 0.1 || compactAtRatio >= 1.0) {
            throw new IllegalArgumentException("压缩阈值必须在 0.1 到 1.0 之间");
        }
        this.compactAtRatio = compactAtRatio;
    }

    public int keepRecentMessages() {
        return keepRecentMessages;
    }

    public void setKeepRecentMessages(int keepRecentMessages) {
        if (keepRecentMessages < 2 || keepRecentMessages > 200) {
            throw new IllegalArgumentException("保留消息数必须在 2 到 200 之间");
        }
        this.keepRecentMessages = keepRecentMessages;
    }

    public boolean autoApprove() {
        return autoApprove;
    }

    public boolean dumpRaw() {
        return dumpRaw;
    }

    public ContextBudget budget() {
        return new ContextBudget(tokenBudget, compactAtRatio, keepRecentMessages);
    }
}
