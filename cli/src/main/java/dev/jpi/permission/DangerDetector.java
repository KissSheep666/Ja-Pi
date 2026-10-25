package dev.jpi.permission;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jpi.tool.ToolContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 危险操作识别。
 *
 * <p>⚠️ 这是<b>正则黑名单</b>，必然漏报。它只是"最后一道提醒"，不是唯一防线；
 * 真正的安全靠容器隔离、最小权限和用户判断。
 * 黑名单换灵活，白名单换安全 —— 没有免费的选择。
 */
public final class DangerDetector {

    public record Assessment(Risk risk, String reason) {

        static Assessment low() {
            return new Assessment(Risk.LOW, null);
        }
    }

    private record Rule(Pattern pattern, String reason) {
    }

    private static final List<Rule> BASH_RULES = List.of(
            new Rule(Pattern.compile(
                    "\\brm\\s+-\\w*r\\w*\\s+(/|~|\\$HOME|\\*|\\.)(\\s|$)", Pattern.CASE_INSENSITIVE),
                    "递归删除根目录 / 家目录 / 当前目录 / 通配符"),

            new Rule(Pattern.compile("\\bgit\\s+push\\b[^|;]*(--force\\b|\\s-f\\b)"),
                    "强制推送，会覆盖远端历史"),

            new Rule(Pattern.compile("\\bgit\\s+reset\\s+--hard\\b"),
                    "丢弃所有未提交的本地修改，无法恢复"),

            new Rule(Pattern.compile("\\bgit\\s+clean\\s+-\\w*[fdx]"),
                    "删除未跟踪的文件/目录，无法恢复"),

            new Rule(Pattern.compile("\\bgit\\s+branch\\s+-D\\b"),
                    "强制删除分支，未合并的提交会丢"),

            new Rule(Pattern.compile("\\bgit\\s+checkout\\s+--\\s+\\."),
                    "丢弃工作区所有未提交修改"),

            new Rule(Pattern.compile("\\bgit\\s+(filter-branch|filter-repo)\\b"),
                    "重写仓库历史，可能影响所有协作者"),

            new Rule(Pattern.compile("\\b(sudo|runas)\\b", Pattern.CASE_INSENSITIVE),
                    "以超级用户身份执行"),

            new Rule(Pattern.compile("(curl|wget|iwr|Invoke-WebRequest)\\b[^\\n|;]*(\\||>)\\s*(ba|z)?sh\\b",
                    Pattern.CASE_INSENSITIVE),
                    "把从网上下载的脚本直接交给 shell 执行"),

            new Rule(Pattern.compile("\\b(iex|Invoke-Expression)\\b", Pattern.CASE_INSENSITIVE),
                    "用 Invoke-Expression 执行动态内容，可能是下载的脚本"),

            new Rule(Pattern.compile("\\bchmod\\s+(-R\\s+)?777\\b"),
                    "把权限开成任何人可读写执行"),

            new Rule(Pattern.compile("\\b(mkfs|fdisk|parted|mkswap|diskpart)\\b", Pattern.CASE_INSENSITIVE),
                    "格式化或重新分区磁盘"),

            new Rule(Pattern.compile("\\bformat\\s+[a-z]:", Pattern.CASE_INSENSITIVE),
                    "格式化磁盘分区"),

            new Rule(Pattern.compile("\\bdd\\b[^|;]*\\bof=/dev/"),
                    "直接写裸设备，可能摧毁磁盘内容"),

            new Rule(Pattern.compile(">\\s*/dev/(sd|nvme|hd)"),
                    "重定向覆盖磁盘设备"),

            new Rule(Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:"),
                    "fork 炸弹，会耗尽系统进程"),

            new Rule(Pattern.compile("\\b(shutdown|reboot|halt|poweroff)\\b", Pattern.CASE_INSENSITIVE),
                    "关机或重启"),

            new Rule(Pattern.compile("\\b(Stop-Computer|Restart-Computer)\\b", Pattern.CASE_INSENSITIVE),
                    "关机或重启"),

            new Rule(Pattern.compile("\\bkill\\s+-9\\s+(-1|1)\\b"),
                    "杀死 init 或所有进程"),

            new Rule(Pattern.compile("\\b(history\\s+-c|Clear-History)\\b|>\\.bash_history", Pattern.CASE_INSENSITIVE),
                    "清除或篡改 shell 历史（可能在掩盖痕迹）"),

            // ── Windows 递归删除 ──
            new Rule(Pattern.compile("\\b(del|erase)\\b[^|;]*\\s/[sqf]\\b", Pattern.CASE_INSENSITIVE),
                    "Windows 递归/强制删除文件"),

            new Rule(Pattern.compile("\\b(rd|rmdir)\\b[^|;]*\\s/s\\b", Pattern.CASE_INSENSITIVE),
                    "Windows 递归删除目录"),

            new Rule(Pattern.compile("\\bRemove-Item\\b[^|;]*-(Recurse|Force)\\b", Pattern.CASE_INSENSITIVE),
                    "PowerShell 递归/强制删除"),

            new Rule(Pattern.compile("\\b(Clear-Disk|Format-Volume|Initialize-Disk)\\b", Pattern.CASE_INSENSITIVE),
                    "PowerShell 磁盘操作"),

            new Rule(Pattern.compile("\\breg\\s+delete\\b", Pattern.CASE_INSENSITIVE),
                    "删除注册表项"),

            new Rule(Pattern.compile("\\bcrontab\\s+-r\\b", Pattern.CASE_INSENSITIVE),
                    "删除所有定时任务")
    );

    /** 破坏性但不足以判为 HIGH 的动词。 */
    private static final Pattern DESTRUCTIVE = Pattern.compile(
            "\\b(rm|rmdir|mv|truncate|del|erase|Remove-Item|Move-Item|Clear-Content)\\b|>>?",
            Pattern.CASE_INSENSITIVE);

    private DangerDetector() {
    }

    /** 纯函数：只读参数，可被单元测试逐条覆盖。 */
    public static Assessment assess(String toolName, JsonNode args, ToolContext ctx) {
        if (toolName.startsWith("mcp__")) {
            return new Assessment(Risk.MEDIUM, "调用外部 MCP 工具，其副作用未知");
        }
        return switch (toolName) {
            case "run_bash" -> assessBash(args);
            case "write_file" -> assessWrite(args, ctx);
            case "edit_file" -> new Assessment(Risk.MEDIUM, "修改已有文件的内容");
            default -> Assessment.low();
        };
    }

    private static Assessment assessBash(JsonNode args) {
        String command = args.path("command").asText("");
        for (Rule rule : BASH_RULES) {
            if (rule.pattern().matcher(command).find()) {
                return new Assessment(Risk.HIGH, rule.reason());
            }
        }
        if (DESTRUCTIVE.matcher(command).find()) {
            return new Assessment(Risk.MEDIUM, "命令包含删除/移动/重定向等破坏性操作");
        }
        return Assessment.low();
    }

    private static Assessment assessWrite(JsonNode args, ToolContext ctx) {
        String path = args.path("path").asText("");
        try {
            Path target = ctx.resolve(path);
            if (Files.exists(target)) {
                return new Assessment(Risk.MEDIUM, "覆盖一个已经存在的文件");
            }
        } catch (RuntimeException ignored) {
            // 路径非法由工具自己拒绝
        }
        return Assessment.low();
    }
}
