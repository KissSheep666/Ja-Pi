package dev.jpi.ui;

import dev.jpi.agent.Agent;
import dev.jpi.config.Settings;
import dev.jpi.context.Compactor;
import dev.jpi.context.Costs;
import dev.jpi.mcp.McpManager;
import dev.jpi.permission.PermissionManager;
import dev.jpi.provider.Providers;
import dev.jpi.provider.SwitchingProvider;
import dev.jpi.provider.Usage;
import dev.jpi.tool.Tool;
import dev.jpi.tool.ToolRegistry;
import dev.jpi.util.Cancellation;
import org.jline.reader.EndOfFileException;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** 增强 REPL：流式渲染、斜杠命令、审批、取消、token/费用统计。 */
public final class Repl {

    private final TerminalIO io;
    private final Ansi ansi;
    private final Agent agent;
    private final Settings settings;
    private final SwitchingProvider provider;
    private final ToolRegistry registry;
    private final PermissionManager permissions;
    private final McpManager mcp;
    private final ConsoleListener listener;
    private final Path cwd;
    private final String version;

    private volatile Cancellation activeCancellation;

    public Repl(TerminalIO io,
                Agent agent,
                Settings settings,
                SwitchingProvider provider,
                ToolRegistry registry,
                PermissionManager permissions,
                McpManager mcp,
                ConsoleListener listener,
                Path cwd,
                String version) {
        this.io = io;
        this.ansi = io.ansi();
        this.agent = agent;
        this.settings = settings;
        this.provider = provider;
        this.registry = registry;
        this.permissions = permissions;
        this.mcp = mcp;
        this.listener = listener;
        this.cwd = cwd;
        this.version = version;
    }

    public void run() {
        installInterruptHandler();
        printBanner();

        while (true) {
            String line;
            try {
                line = io.readLine(prompt());
            } catch (UserInterruptException e) {
                io.println();
                continue;
            } catch (EndOfFileException e) {
                break;
            }
            if (line == null) {
                break;
            }

            line = line.strip();
            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith("/")) {
                if (!handleCommand(line)) {
                    break;
                }
            } else {
                runAgent(line);
            }
        }
    }

    public void farewell() {
        Usage total = agent.totalUsage();
        io.println();
        io.println(ansi.dim(String.format(Locale.ROOT,
                "再见。累计输入 %s（缓存命中 %s） / 输出 %s · 估算费用 %s",
                formatTokens(total.promptTokens()),
                formatTokens(total.cacheHitTokens()),
                formatTokens(total.completionTokens()),
                Costs.formatUsd(Costs.estimateUsd(total)))));
    }

    private void installInterruptHandler() {
        try {
            io.terminal().handle(Terminal.Signal.INT, signal -> {
                Cancellation cancellation = activeCancellation;
                if (cancellation != null) {
                    cancellation.cancel();
                    io.println(ansi.yellow("\n[正在取消…]"));
                }
            });
        } catch (Exception ignored) {
            // 某些终端不支持信号处理；退化为无法中途取消
        }
    }

    private void runAgent(String input) {
        Cancellation cancellation = new Cancellation();
        activeCancellation = cancellation;
        try {
            agent.run(input, listener, cancellation);
        } catch (Cancellation.CancelledException e) {
            io.println(ansi.yellow("[已取消]"));
        } catch (Exception e) {
            io.println(ansi.red("\n[异常] " + e.getMessage()));
        } finally {
            activeCancellation = null;
            listener.reset();
        }
    }

    private boolean handleCommand(String line) {
        int space = line.indexOf(' ');
        String command = (space < 0 ? line.substring(1) : line.substring(1, space)).toLowerCase();
        String arg = space < 0 ? "" : line.substring(space + 1).strip();

        switch (command) {
            case "help", "?" -> printHelp();
            case "quit", "exit", "q" -> {
                return false;
            }
            case "clear" -> {
                agent.clearConversation();
                io.println(ansi.green("对话历史已清空。"));
            }
            case "compact" -> compactNow();
            case "model" -> switchModel(arg);
            case "provider" -> switchProvider(arg);
            case "think" -> toggleThinking(arg);
            case "budget" -> changeBudget(arg);
            case "keep" -> changeKeep(arg);
            case "tools" -> listTools();
            case "mcp" -> handleMcp(arg);
            case "permissions" -> handlePermissions(arg);
            case "usage" -> showUsage();
            default -> {
                io.println(ansi.yellow("未知命令：/" + command));
                io.println(ansi.dim("输入 /help 查看可用命令。"));
            }
        }
        return true;
    }

    private void compactNow() {
        Cancellation cancellation = new Cancellation();
        activeCancellation = cancellation;
        try {
            Compactor.Result result = agent.forceCompact(listener, cancellation);
            if (result.messagesRemoved() == 0) {
                io.println(ansi.dim("没有压缩：历史太短，或摘要没有比原文更短。"));
            }
        } finally {
            activeCancellation = null;
            listener.reset();
        }
    }

    private void switchModel(String arg) {
        if (arg.isEmpty()) {
            io.println(ansi.dim("当前模型：") + settings.model());
            io.println(ansi.dim("用法：/model <id>"));
            return;
        }
        try {
            settings.setModel(arg);
            io.println(ansi.green("模型已切换为 ") + settings.model());
        } catch (IllegalArgumentException e) {
            io.println(ansi.red("切换失败：" + e.getMessage()));
        }
    }

    private void switchProvider(String arg) {
        if (arg.isEmpty()) {
            io.println(ansi.dim("当前协议：") + provider.name()
                    + ansi.dim("  ·  " + Providers.endpointHint(provider.name())));
            io.println(ansi.dim("用法：/provider deepseek | anthropic"));
            return;
        }

        String name = Providers.normalize(arg);
        if (!Providers.isKnown(name)) {
            io.println(ansi.red("未知 provider：" + arg + "（可选：deepseek / anthropic）"));
            return;
        }
        try {
            var newProvider = Providers.create(name, settings.dumpRaw());
            provider.switchTo(name, newProvider);
            io.println(ansi.green("协议已切换为 ") + name
                    + ansi.dim("  ·  " + Providers.endpointHint(name)));
            io.println(ansi.dim("模型仍是 " + settings.model() + "；需要的话用 /model 切换。"));
        } catch (Exception e) {
            io.println(ansi.red("切换失败：" + e.getMessage()));
        }
    }

    private void toggleThinking(String arg) {
        if (arg.isEmpty() || arg.equals("status")) {
            io.println(ansi.dim("思考模式：") + (settings.thinking() ? "on" : "off"));
            io.println(ansi.dim("用法：/think on | off"));
            return;
        }
        switch (arg.toLowerCase()) {
            case "on", "true", "1", "yes" -> {
                settings.setThinking(true);
                io.println(ansi.green("思考模式已开启。"));
            }
            case "off", "false", "0", "no" -> {
                settings.setThinking(false);
                io.println(ansi.green("思考模式已关闭。"));
            }
            default -> io.println(ansi.yellow("用法：/think on | off"));
        }
    }

    private void changeBudget(String arg) {
        if (arg.isEmpty()) {
            io.println(ansi.dim(String.format(
                    "上下文预算：%d token（超过 %d 触发压缩，保留最近 %d 条）",
                    settings.tokenBudget(), settings.budget().triggerThreshold(),
                    settings.keepRecentMessages())));
            io.println(ansi.dim("用法：/budget <tokens>"));
            return;
        }
        try {
            settings.setTokenBudget(Integer.parseInt(arg));
            io.println(ansi.green(String.format(
                    "预算已设为 %d token（超过 %d 触发压缩）",
                    settings.tokenBudget(), settings.budget().triggerThreshold())));
        } catch (NumberFormatException e) {
            io.println(ansi.red("预算必须是整数：" + arg));
        } catch (IllegalArgumentException e) {
            io.println(ansi.red("设置失败：" + e.getMessage()));
        }
    }

    private void changeKeep(String arg) {
        if (arg.isEmpty()) {
            io.println(ansi.dim("压缩时保留最近消息数：" + settings.keepRecentMessages()));
            io.println(ansi.dim("用法：/keep <n>"));
            return;
        }
        try {
            settings.setKeepRecentMessages(Integer.parseInt(arg));
            io.println(ansi.green("保留消息数已设为 " + settings.keepRecentMessages()));
        } catch (NumberFormatException e) {
            io.println(ansi.red("保留消息数必须是整数：" + arg));
        } catch (IllegalArgumentException e) {
            io.println(ansi.red("设置失败：" + e.getMessage()));
        }
    }

    private void listTools() {
        List<Tool> tools = registry.all();
        io.println(ansi.dim("可用工具（" + tools.size() + "）："));
        for (Tool tool : tools) {
            String flag = tool.requiresApproval()
                    ? ansi.yellow("需审批")
                    : ansi.green("只读");
            String firstLine = tool.description().strip().lines().findFirst().orElse("");
            io.println("  " + ansi.cyan(tool.name()) + "  " + flag
                    + "\n    " + ansi.dim(firstLine));
        }
    }

    private void handleMcp(String arg) {
        if (arg.isEmpty() || arg.equals("list")) {
            if (mcp.attachments().isEmpty()) {
                io.println(ansi.dim("没有挂载 MCP 服务器。"));
                io.println(ansi.dim("用法：/mcp add <command>  或  /mcp demo"));
                return;
            }
            io.println(ansi.dim("已挂载的 MCP 服务器："));
            for (McpManager.Attached attached : mcp.attachments()) {
                io.println("  " + ansi.cyan(attached.server())
                        + ansi.dim("  " + attached.tools() + " 个工具"));
            }
            return;
        }

        String commandLine;
        boolean demo;
        if (arg.equals("demo") || arg.equals("add demo")) {
            demo = true;
            commandLine = "";
        } else if (arg.startsWith("add ")) {
            demo = false;
            commandLine = arg.substring("add ".length()).strip();
            if (commandLine.isEmpty()) {
                io.println(ansi.yellow("用法：/mcp add <command>"));
                return;
            }
        } else {
            io.println(ansi.yellow("用法：/mcp list | /mcp add <command> | /mcp demo"));
            return;
        }

        try {
            McpManager.Attached attached = demo
                    ? mcp.attachDemo(registry)
                    : mcp.attach(commandLine, registry);
            io.println(ansi.green("MCP 已挂载：") + attached.server()
                    + ansi.dim("（" + attached.tools() + " 个工具）"));
        } catch (IOException | RuntimeException e) {
            io.println(ansi.red("挂载失败：" + e.getMessage()));
        }
    }

    private void handlePermissions(String arg) {
        if (arg.equals("reset")) {
            permissions.resetSession();
            io.println(ansi.green("本会话的授权已清空。"));
            return;
        }
        var scopes = permissions.grantedScopes();
        if (scopes.isEmpty()) {
            io.println(ansi.dim("本会话还没有\"总是允许\"的授权。"));
        } else {
            io.println(ansi.dim("本会话已授权的粒度："));
            for (String scope : scopes.stream().sorted().toList()) {
                io.println("  " + scope);
            }
        }
        io.println(ansi.dim("用法：/permissions reset"));
    }

    private void showUsage() {
        Usage total = agent.totalUsage();
        Agent.Posture posture = agent.posture();
        int budget = settings.tokenBudget();

        io.println(ansi.dim("累计输入：") + formatTokens(total.promptTokens())
                + ansi.dim("（缓存命中 ") + formatTokens(total.cacheHitTokens())
                + ansi.dim("）"));
        io.println(ansi.dim("累计输出：") + formatTokens(total.completionTokens()));
        io.println(ansi.dim("上次请求实测输入：") + formatTokens(agent.lastPromptTokens()));
        io.println(ansi.dim("估算费用：") + Costs.formatUsd(Costs.estimateUsd(total))
                + ansi.dim("（按 deepseek-flash 非高峰价，仅供参考）"));
        io.println(ansi.dim("对话消息：") + posture.messages() + " 条"
                + ansi.dim("  ·  上下文估算：") + formatTokens(posture.estimatedTokens())
                + ansi.dim(" / " + formatTokens(budget) + "（"
                + Math.round(100.0 * posture.estimatedTokens() / budget) + "%）"));
    }

    private void printHelp() {
        io.println(ansi.bold("命令"));
        io.println("  " + pad("/help") + "显示本帮助");
        io.println("  " + pad("/model [id]") + "查看或切换模型");
        io.println("  " + pad("/provider [name]") + "查看或切换协议（deepseek / anthropic）");
        io.println("  " + pad("/think [on|off]") + "开关思考模式");
        io.println("  " + pad("/compact") + "立即压缩上下文");
        io.println("  " + pad("/clear") + "清空对话历史");
        io.println("  " + pad("/budget [tokens]") + "查看或设置上下文预算");
        io.println("  " + pad("/keep [n]") + "查看或设置压缩时保留的最近消息数");
        io.println("  " + pad("/tools") + "列出可用工具");
        io.println("  " + pad("/mcp [add <cmd>|demo]") + "查看或挂载 MCP 服务器");
        io.println("  " + pad("/permissions [reset]") + "查看或清空本会话授权");
        io.println("  " + pad("/usage") + "查看 token 与费用");
        io.println("  " + pad("/quit") + "退出");
        io.println();
        io.println(ansi.dim("Ctrl+C 取消当前请求；Ctrl+D 退出。"));
    }

    private void printBanner() {
        io.println();
        io.println(ansi.bold("jpi " + version) + ansi.dim("  ·  Java 21 终端编码 agent"));
        io.println(ansi.dim("model   ") + settings.model()
                + ansi.dim("  (" + provider.name() + ")")
                + ansi.dim("   thinking ") + (settings.thinking() ? "on" : "off")
                + ansi.dim("   approvals ") + (settings.autoApprove() ? "auto" : "ask"));
        io.println(ansi.dim("cwd     ") + cwd);
        io.println(ansi.dim("tools   ") + String.join(" · ",
                registry.all().stream().map(Tool::name).toList()));
        if (!mcp.attachments().isEmpty()) {
            io.println(ansi.dim("mcp     ") + mcp.attachments().stream()
                    .map(a -> a.server() + " (" + a.tools() + ")")
                    .reduce((a, b) -> a + " · " + b).orElse(""));
        }
        io.println(ansi.dim("budget  ") + formatTokens(settings.tokenBudget())
                + ansi.dim(" token（超过 ") + formatTokens(settings.budget().triggerThreshold())
                + ansi.dim(" 触发压缩）"));
        io.println(ansi.dim("输入 /help 查看命令，Ctrl+C 取消，Ctrl+D 退出。"));
        io.println();
    }

    private String prompt() {
        return ansi.bold(ansi.cyan("你")) + ansi.dim(" › ");
    }

    private static String pad(String command) {
        return String.format("%-24s", command);
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
}
