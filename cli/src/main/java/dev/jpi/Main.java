package dev.jpi;

import dev.jpi.agent.Agent;
import dev.jpi.config.CliOptions;
import dev.jpi.config.Settings;
import dev.jpi.context.Compactor;
import dev.jpi.mcp.McpManager;
import dev.jpi.permission.Approval;
import dev.jpi.permission.Approver;
import dev.jpi.permission.PermissionManager;
import dev.jpi.provider.Providers;
import dev.jpi.provider.SwitchingProvider;
import dev.jpi.tool.ToolContext;
import dev.jpi.tool.ToolRegistry;
import dev.jpi.tool.impl.BashTool;
import dev.jpi.tool.impl.EditFileTool;
import dev.jpi.tool.impl.GlobTool;
import dev.jpi.tool.impl.GrepTool;
import dev.jpi.tool.impl.ReadFileTool;
import dev.jpi.tool.impl.WriteFileTool;
import dev.jpi.ui.Ansi;
import dev.jpi.ui.ConsoleApprover;
import dev.jpi.ui.ConsoleListener;
import dev.jpi.ui.Repl;
import dev.jpi.ui.TerminalIO;

import java.io.BufferedOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;

/** jpi 入口。 */
public final class Main {

    public static final String VERSION = "0.1.0";

    public static void main(String[] args) {
        configureUtf8Streams();

        CliOptions options;
        try {
            options = CliOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            return;
        }

        if (options.help()) {
            System.out.print(CliOptions.helpText());
            return;
        }
        if (options.version()) {
            System.out.println("jpi " + VERSION);
            return;
        }

        int exitCode = run(options);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static int run(CliOptions options) {
        Path cwd = options.cwd() != null
                ? options.cwd()
                : Path.of("").toAbsolutePath().normalize();

        Settings settings = new Settings(
                options.model(),
                options.thinking(),
                options.budget(),
                Settings.DEFAULT_COMPACT_RATIO,
                options.keep(),
                options.autoApprove(),
                options.dumpRaw());

        McpManager mcp = new McpManager();

        try (TerminalIO io = TerminalIO.open(options.color())) {
            Ansi ansi = io.ansi();

            SwitchingProvider provider;
            try {
                provider = new SwitchingProvider(
                        options.provider(),
                        Providers.create(options.provider(), options.dumpRaw()));
            } catch (Exception e) {
                io.println(ansi.red("无法初始化模型："));
                io.println(e.getMessage());
                return 1;
            }

            Approver approver = options.autoApprove()
                    ? (toolName, action, risk) -> {
                        io.println();
                        io.println(ansi.yellow("[--yes] 自动批准 " + toolName + "（" + risk + "）"));
                        return Approval.once();
                    }
                    : new ConsoleApprover(io);
            PermissionManager permissions = new PermissionManager(approver);

            ToolRegistry registry = new ToolRegistry()
                    .register(new ReadFileTool())
                    .register(new GlobTool())
                    .register(new GrepTool())
                    .register(new WriteFileTool())
                    .register(new EditFileTool())
                    .register(new BashTool());

            ToolContext context = new ToolContext(cwd, permissions);

            for (String mcpCommand : options.mcpCommands()) {
                try {
                    McpManager.Attached attached = "demo".equalsIgnoreCase(mcpCommand)
                            ? mcp.attachDemo(registry)
                            : mcp.attach(mcpCommand, registry);
                    io.println(ansi.green("MCP 已挂载：") + attached.server()
                            + ansi.dim("（" + attached.tools() + " 个工具）"));
                } catch (Exception e) {
                    io.println(ansi.yellow("MCP 挂载失败（" + mcpCommand + "）：" + e.getMessage()));
                }
            }

            Compactor compactor = new Compactor(provider, settings);
            Agent agent = new Agent(provider, registry, context, settings,
                    systemPrompt(cwd), compactor);
            ConsoleListener listener = new ConsoleListener(io, settings);

            Repl repl = new Repl(io, agent, settings, provider, registry, permissions,
                    mcp, listener, cwd, VERSION);
            repl.run();
            repl.farewell();
            return 0;
        } catch (Exception e) {
            System.err.println("启动失败：" + e.getMessage());
            return 1;
        } finally {
            mcp.close();
        }
    }

    private static String systemPrompt(Path cwd) {        String os = System.getProperty("os.name", "unknown")
                + " " + System.getProperty("os.version", "");
        return """
                你是一个运行在用户终端里的编码助手。

                环境：
                - 工作目录：%s
                - 操作系统：%s
                - 今天：%s

                工作方式：
                - 需要了解代码时，主动用工具去看，不要凭空猜测或假设。
                - 定位代码优先用 grep 找内容、glob 找文件，最后才用 read_file 精读。
                - 修改已有文件【必须】用 edit_file，不要用 write_file 整体重写。
                - 创建新文件可以用 write_file；它会整体覆盖，所以不要用它改已有文件。
                - 一次可以调用多个工具并行收集信息。
                - 执行有副作用的操作前，工具系统会向用户申请授权；被拒绝时不要原样重试，
                  先解释为什么需要，或者换一种用户能接受的方式。
                - 上下文可能被压缩成摘要。摘要里的事实仍然有效，但细节不确定时重新用工具确认。

                回答要求：简洁、直接、不客套；代码块标明语言；不要重复用户的问题。
                """.formatted(cwd, os, LocalDate.now());
    }

    /**
     * Windows 上 System.out 默认用本地代码页（常是 GBK），
     * 而终端和管道通常按 UTF-8 解码。统一成 UTF-8，避免中文乱码。
     */
    private static void configureUtf8Streams() {
        try {
            System.setOut(new PrintStream(
                    new BufferedOutputStream(new FileOutputStream(FileDescriptor.out), 8192),
                    true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(
                    new BufferedOutputStream(new FileOutputStream(FileDescriptor.err), 8192),
                    true, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // 拿不到标准流时保持 JVM 默认
        }
    }
}
