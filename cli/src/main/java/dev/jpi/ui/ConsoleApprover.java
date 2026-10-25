package dev.jpi.ui;

import dev.jpi.permission.Approval;
import dev.jpi.permission.Approver;
import dev.jpi.permission.Risk;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;

/**
 * 终端里的审批交互。
 *
 * <p>工具是并发执行的，所以这里用一把锁把提示串行化 ——
 * 否则多个虚拟线程会同时抢 stdin，提示会糊在一起。
 */
public final class ConsoleApprover implements Approver {

    private static final String RULE = "────────────────────────────────────────────────────────";

    private final TerminalIO io;
    private final Ansi ansi;
    private final Object lock = new Object();

    public ConsoleApprover(TerminalIO io) {
        this.io = io;
        this.ansi = io.ansi();
    }

    @Override
    public Approval ask(String toolName, String action, Risk risk) {
        synchronized (lock) {
            return askLocked(toolName, action, risk);
        }
    }

    private Approval askLocked(String toolName, String action, Risk risk) {
        io.println();
        io.println(ansi.gray("┌─ 需要授权 " + RULE.substring(11)));
        io.println(ansi.gray("│ ") + "工具  " + toolName);
        for (String line : action.split("\n", -1)) {
            io.println(ansi.gray("│ ") + line);
        }
        io.println(ansi.gray("│ ") + "风险  " + riskLabel(risk));
        io.println(ansi.gray("└" + RULE));

        while (true) {
            String prompt = risk == Risk.HIGH
                    ? "  允许吗？ [y] 允许一次  [n] 拒绝 > "
                    : "  允许吗？ [y] 允许一次  [a] 本会话总是允许  [n] 拒绝 > ";
            String line;
            try {
                line = io.readLine(prompt);
            } catch (UserInterruptException e) {
                return Approval.deny("用户在审批时中断");
            } catch (EndOfFileException e) {
                return Approval.deny("输入已结束");
            }
            if (line == null) {
                return Approval.deny("输入已结束");
            }

            switch (line.strip().toLowerCase()) {
                case "y", "" -> {
                    return Approval.once();
                }
                case "a" -> {
                    if (risk == Risk.HIGH) {
                        io.println(ansi.yellow("  高风险操作不提供本会话授权，每次都必须重新确认。"));
                        continue;
                    }
                    return Approval.always();
                }
                case "n" -> {
                    String reason;
                    try {
                        reason = io.readLine("  拒绝理由（模型会看到，可留空直接回车）> ");
                    } catch (UserInterruptException | EndOfFileException e) {
                        reason = "";
                    }
                    return Approval.deny(reason == null || reason.isBlank()
                            ? "用户未说明理由" : reason);
                }
                default -> io.println("  请输入 y / a / n");
            }
        }
    }

    private String riskLabel(Risk risk) {
        return switch (risk) {
            case LOW -> ansi.green("LOW");
            case MEDIUM -> ansi.yellow("MEDIUM");
            case HIGH -> ansi.red("HIGH");
        };
    }
}
