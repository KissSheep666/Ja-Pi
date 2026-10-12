package dev.learn.agent.permission;

import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.tool.ToolContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 危险操作识别。
 *
 * <p>这是 M3 里最"策略化"的一块：它不是安全性问题（路径沙箱才是），
 * 而是<b>判断力问题</b> —— 什么算危险，取决于你要保护什么。
 *
 * <p>⚠️ 这里用的是<b>正则黑名单</b>。它必然漏报（永远列不全），
 * 只能当作"最后一道提醒"，绝不能当作唯一防线。
 * 真正可靠的做法是白名单：只允许明确列举的操作。但那会牺牲灵活性。
 * <b>黑名单换灵活，白名单换安全 —— 没有免费的选择。</b>
 */
public final class DangerDetector {

    /** 一次评估的结果。 */
    public record Assessment(Risk risk, String reason) {

        static Assessment low() {
            return new Assessment(Risk.LOW, null);
        }
    }

    private record Rule(Pattern pattern, String reason) {
    }

    /**
     * 危险命令规则表。
     *
     * <p>每一条都是一个"如果命中，就必须让用户停下来看一眼"的模式。
     * 注意它们都是<b>模式</b>而不是精确字符串 —— 因为 `rm -rf /` 有一万种写法。
     */
    private static final List<Rule> BASH_RULES = List.of(
            new Rule(Pattern.compile(
                    "\\brm\\s+-\\w*r\\w*\\s+(/|~|\\$HOME|\\*|\\.)(\\s|$)", Pattern.CASE_INSENSITIVE),
                    "递归删除根目录 / 家目录 / 当前目录 / 通配符"),

            new Rule(Pattern.compile("\\bgit\\s+push\\b[^|;]*(--force\\b|\\s-f\\b)"),
                    "强制推送，会覆盖远端历史，别人的提交可能丢失"),

            new Rule(Pattern.compile("\\bgit\\s+reset\\s+--hard\\b"),
                    "丢弃所有未提交的本地修改，无法恢复"),

            new Rule(Pattern.compile("\\bgit\\s+clean\\s+-\\w*[fdx]"),
                    "删除未跟踪的文件/目录，无法恢复"),

            new Rule(Pattern.compile("\\bgit\\s+branch\\s+-D\\b"),
                    "强制删除分支，未合并的提交会丢"),

            new Rule(Pattern.compile("\\bgit\\s+checkout\\s+--\\s+\\."),
                    "丢弃工作区所有未提交修改"),

            new Rule(Pattern.compile("\\bsudo\\b"),
                    "以超级用户身份执行"),

            new Rule(Pattern.compile("(curl|wget)\\b[^|;]*\\|\\s*(ba|z)?sh\\b"),
                    "把从网上下载的脚本直接交给 shell 执行"),

            new Rule(Pattern.compile("\\bchmod\\s+(-R\\s+)?777\\b"),
                    "把权限开成任何人可读写执行"),

            new Rule(Pattern.compile("\\b(mkfs|fdisk|parted|mkswap)\\b"),
                    "格式化或重新分区磁盘"),

            new Rule(Pattern.compile("\\bdd\\b[^|;]*\\bof=/dev/"),
                    "直接写裸设备，可能摧毁磁盘内容"),

            new Rule(Pattern.compile(">\\s*/dev/(sd|nvme|hd)"),
                    "重定向覆盖磁盘设备"),

            new Rule(Pattern.compile(":\\s*\\(\\s*\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:"),
                    "fork 炸弹，会耗尽系统进程"),

            new Rule(Pattern.compile("\\b(shutdown|reboot|halt|poweroff)\\b"),
                    "关机或重启"),

            new Rule(Pattern.compile("\\bkill\\s+-9\\s+(-1|1)\\b"),
                    "杀死 init 或所有进程"),

            new Rule(Pattern.compile("\\bhistory\\s+-c\\b|\\b>\\.bash_history\\b"),
                    "清除或篡改 shell 历史（可能在掩盖痕迹）")
    );

    /** 破坏性但不足以判为 HIGH 的动词。 */
    private static final Pattern DESTRUCTIVE =
            Pattern.compile("\\b(rm|rmdir|mv|truncate|>|>>)\\b");

    private DangerDetector() {
    }

    /**
     * 评估一次调用的风险。
     *
     * <p>注意这个方法是<b>纯函数</b>：只读参数，不看全局状态。
     * 所以它可以被单元测试覆盖到每一条规则，不需要启动 agent。
     */
    public static Assessment assess(String toolName, JsonNode args, ToolContext ctx) {
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
            // 路径非法的话，工具自己会拒绝，不用在这里报警
        }
        return Assessment.low();
    }
}
