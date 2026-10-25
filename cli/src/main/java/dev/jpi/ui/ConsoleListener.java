package dev.jpi.ui;

import dev.jpi.agent.AgentListener;
import dev.jpi.config.Settings;
import dev.jpi.context.Compactor;
import dev.jpi.context.Costs;
import dev.jpi.provider.StopReason;
import dev.jpi.provider.Usage;
import dev.jpi.tool.ToolResult;

import java.util.Locale;

/** 把 agent 事件渲染成终端输出。 */
public final class ConsoleListener implements AgentListener {

    private final TerminalIO io;
    private final Ansi ansi;
    private final Settings settings;

    private boolean reasoningOpen;
    private boolean textOpen;
    private boolean toolOpen;
    private long roundStartNanos;
    private int lastContextPrinted = -1;

    public ConsoleListener(TerminalIO io, Settings settings) {
        this.io = io;
        this.ansi = io.ansi();
        this.settings = settings;
    }

    @Override
    public void onRoundStart() {
        closeOpenLines();
        roundStartNanos = System.nanoTime();
    }

    @Override
    public void onText(String delta) {
        if (reasoningOpen) {
            io.println();
            reasoningOpen = false;
        }
        io.print(delta);
        textOpen = true;
    }

    @Override
    public void onReasoning(String delta) {
        if (textOpen) {
            io.println();
            textOpen = false;
        }
        if (!reasoningOpen) {
            io.print(ansi.dim("思考 "));
            reasoningOpen = true;
        }
        io.print(ansi.dim(delta));
    }

    @Override
    public void onToolCallStart(String id, String name) {
        closeOpenLines();
        io.print("  " + ansi.cyan("⚙ " + name) + ansi.dim(" …"));
        toolOpen = true;
    }

    @Override
    public void onToolCallArgs(String id, String fragment) {
        // 参数是分片流；UI 里不逐片显示，避免刷屏。结果出来时统一展示摘要。
    }

    @Override
    public void onToolResult(ToolResult result, String label) {
        if (toolOpen) {
            io.println();
            toolOpen = false;
        }
        String mark = result.error() ? ansi.red("✖") : ansi.green("✔");
        io.println("  " + mark + " " + label + ansi.dim(" · " + result.durationMs() + "ms"));
        String preview = firstLine(result.content());
        if (!preview.isBlank()) {
            io.println("    " + ansi.dim(preview));
        }
    }

    @Override
    public void onRoundEnd(StopReason reason, Usage usage) {
        // 要调工具的轮次后面马上会跟审批和工具结果；统计行先别插在中间，
        // 等最终回答的那一轮再打印（累计用量看 /usage）。
        if (reason == StopReason.TOOL_USE) {
            roundStartNanos = 0;
            return;
        }
        closeOpenLines();
        String duration = roundStartNanos == 0
                ? "-"
                : formatDuration(System.nanoTime() - roundStartNanos);
        String stats = String.format(Locale.ROOT,
                "[%s · %s · in %s (cache %s) · out %s · ≈%s]",
                settings.model(),
                duration,
                formatTokens(usage.promptTokens()),
                formatTokens(usage.cacheHitTokens()),
                formatTokens(usage.completionTokens()),
                Costs.formatUsd(Costs.estimateUsd(usage)));
        io.println(ansi.dim(stats));
        roundStartNanos = 0;
    }

    @Override
    public void onFailure(String message) {
        closeOpenLines();
        io.println(ansi.red("[失败] " + message));
    }

    @Override
    public void onLimitReached(int maxRounds) {
        closeOpenLines();
        io.println(ansi.yellow("[已停止] 达到最大轮数 " + maxRounds + "，可能是模型陷入了循环。"));
    }

    @Override
    public void onCancelled() {
        closeOpenLines();
        io.println(ansi.yellow("[已取消]"));
    }

    @Override
    public void onContextUsage(int estimated, int budget, int actual) {
        if (estimated < budget * 0.5) {
            lastContextPrinted = -1;
            return;
        }
        // 用量变化不大就不重复刷屏
        int step = Math.max(1, budget / 20);
        if (lastContextPrinted > 0 && Math.abs(estimated - lastContextPrinted) < step) {
            return;
        }
        lastContextPrinted = estimated;
        String actualText = actual <= 0 ? "" : " · 上次实测 " + formatTokens(actual);
        io.println(ansi.dim(String.format(Locale.ROOT,
                "[上下文 %s / %s (%d%%)%s]",
                formatTokens(estimated), formatTokens(budget),
                Math.round(100.0 * estimated / budget), actualText)));
    }

    @Override
    public void onCompactionLog(String message) {
        closeOpenLines();
        io.print(ansi.dim(message));
    }

    @Override
    public void onCompacted(Compactor.Result result) {
        closeOpenLines();
        io.println(ansi.green("[压缩完成] ") + ansi.dim(String.format(Locale.ROOT,
                "%d 条 → 摘要；估算 %s → %s token（省 %d%%）",
                result.messagesRemoved(),
                formatTokens(result.tokensBefore()),
                formatTokens(result.tokensAfter()),
                result.savedRatioPercent())));
        lastContextPrinted = -1;
    }

    /** 一轮对话结束、回到提示符之前调用。 */
    public void reset() {
        closeOpenLines();
    }

    private void closeOpenLines() {
        if (reasoningOpen) {
            io.println();
            reasoningOpen = false;
        }
        if (textOpen) {
            io.println();
            textOpen = false;
        }
        if (toolOpen) {
            io.println();
            toolOpen = false;
        }
    }

    private static String firstLine(String content) {
        if (content == null) {
            return "";
        }
        for (String line : content.split("\n", -1)) {
            String stripped = line.strip();
            if (!stripped.isEmpty()) {
                return stripped.length() <= 160 ? stripped : stripped.substring(0, 160) + "…";
            }
        }
        return "";
    }

    private static String formatTokens(int tokens) {
        if (tokens >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", tokens / 1_000_000.0);
        }
        if (tokens >= 1_000) {
            return String.format(Locale.ROOT, "%.1fk", tokens / 1_000.0);
        }
        return Integer.toString(tokens);
    }

    private static String formatDuration(long nanos) {
        long millis = nanos / 1_000_000;
        if (millis < 1_000) {
            return millis + "ms";
        }
        return String.format(Locale.ROOT, "%.1fs", millis / 1_000.0);
    }
}
