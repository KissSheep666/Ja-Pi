package dev.learn.agent;

import dev.learn.agent.agent.Agent;
import dev.learn.agent.agent.AgentListener;
import dev.learn.agent.context.Compactor;
import dev.learn.agent.context.ContextBudget;
import dev.learn.agent.permission.Approval;
import dev.learn.agent.permission.Approver;
import dev.learn.agent.permission.PermissionManager;
import dev.learn.agent.permission.Risk;
import dev.learn.agent.provider.Provider;
import dev.learn.agent.provider.StopReason;
import dev.learn.agent.provider.Usage;
import dev.learn.agent.provider.deepseek.DeepSeekProvider;
import dev.learn.agent.provider.anthropic.AnthropicProvider;
import dev.learn.agent.mcp.McpClient;
import dev.learn.agent.mcp.McpToolAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.tool.ToolContext;
import dev.learn.agent.tool.ToolRegistry;
import dev.learn.agent.tool.ToolResult;
import dev.learn.agent.tool.impl.BashTool;
import dev.learn.agent.tool.impl.EditFileTool;
import dev.learn.agent.tool.impl.GlobTool;
import dev.learn.agent.tool.impl.GrepTool;
import dev.learn.agent.tool.impl.ReadFileTool;
import dev.learn.agent.tool.impl.WriteFileTool;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

public class Main {

    private static final String MODEL = "deepseek-flash";

    private static final String SYSTEM_PROMPT = """
            你是一个编码助手，工作目录就是当前目录。

            工作方式：
            - 需要了解代码时，主动用工具去看，不要凭空猜测或假设。
            - 定位代码优先用 grep 找内容，用 glob 找文件，最后才用 read_file 精读。
            - 改已有文件【必须】用 edit_file，不要用 write_file 整体重写。
            - 一次可以用多个工具并行收集信息。

            注意：较早的对话可能已被摘要（你会看到 [以下是更早对话的摘要] 这样的消息）。
            摘要里的事实仍然有效，但更细节的原文可能已经不在上下文里了。
            如果需要确认某个细节，重新用工具去查，不要靠回忆。

            回答要求：简洁，不客套，不重复用户的问题。
            """;

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("请先设置环境变量 DEEPSEEK_API_KEY");
            System.exit(1);
        }

        List<String> flags = List.of(args);
        boolean dumpRaw = flags.contains("--raw");
        boolean thinking = flags.contains("--think");
        boolean autoApprove = flags.contains("--yes");
        String mcpCommand = stringFlag(flags, "--mcp=", null);
        List<McpClient> mcpClients = new java.util.ArrayList<>();

        // ── M4：上下文预算。--budget=N 调小预算、--keep=N 调小保留条数，
        //    两者都是为了让压缩在一次对话里就能观察到 ──
        ContextBudget budget = new ContextBudget(
                intFlag(flags, "--budget=", ContextBudget.DEFAULT.tokenBudget()),
                0.6,
                intFlag(flags, "--keep=", ContextBudget.DEFAULT.keepRecentMessages()));

        BufferedReader stdin = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));

        Approver approver = autoApprove
                ? (tool, action, risk) -> {
                    System.out.printf("%n[--yes] 自动批准 %s（%s）%n", tool, risk);
                    return Approval.once();
                }
                : new ConsoleApprover(stdin);
        PermissionManager permissions = new PermissionManager(approver);

        Provider provider = switch (stringFlag(flags, "--provider=", "deepseek")) {
            case "anthropic" -> new AnthropicProvider(apiKey, dumpRaw);
            default -> new DeepSeekProvider(apiKey, dumpRaw);
        };

        ToolRegistry tools = new ToolRegistry()
                .register(new ReadFileTool())
                .register(new GlobTool())
                .register(new GrepTool())
                .register(new WriteFileTool())
                .register(new EditFileTool())
                .register(new BashTool());

        ToolContext context = new ToolContext(Path.of("."), permissions);

        // ── M5：可选挂载 MCP 服务器。挂上之后它的工具就变成普通工具 ──
        if (mcpCommand != null && !mcpCommand.isBlank()) {
            // 便捷写法：--mcp=demo 会用当前 classpath 启动自带的演示服务器，
            // 省掉手写一长串 classpath 的麻烦
            if ("demo".equals(mcpCommand)) {
                String javaBin = ProcessHandle.current().info().command().orElse("java");
                mcpCommand = javaBin + " -cp " + System.getProperty("java.class.path")
                        + " dev.learn.agent.mcp.demo.DemoMcpServer";
            }
            try {
                McpClient client = McpClient.start(List.of(mcpCommand.split("\\s+")));
                mcpClients.add(client);
                int attached = 0;
                for (JsonNode definition : client.listTools()) {
                    tools.register(new McpToolAdapter(client, client.serverName(), definition));
                    attached++;
                }
                System.out.printf("MCP[%s] 已挂载 %d 个工具%n", client.serverName(), attached);
            } catch (Exception e) {
                System.err.println("MCP 服务器启动失败：" + e.getMessage());
            }
        }

        Compactor compactor = new Compactor(provider, MODEL, budget.keepRecentMessages());

        Agent agent = new Agent(provider, tools, context, MODEL, thinking,
                SYSTEM_PROMPT, budget, compactor);
        ConsoleListener listener = new ConsoleListener();

        System.out.printf("模型=%s  thinking=%s  raw=%s  autoApprove=%s%n",
                MODEL, thinking, dumpRaw, autoApprove);
        System.out.println("工作目录：" + context.root());
        System.out.printf("上下文预算：%d token（超过 %d 触发压缩，保留最近 %d 条）%n",
                budget.tokenBudget(), budget.triggerThreshold(), budget.keepRecentMessages());
        System.out.printf("可用工具：%s%n", tools.specs().stream().map(s -> s.name()).toList());
        System.out.println("输入问题，空行退出。");

        while (true) {
            System.out.print("\n你> ");
            System.out.flush();
            String input = stdin.readLine();
            if (input == null || input.isBlank()) {
                break;
            }

            try {
                agent.run(input, listener);
            } catch (Exception e) {
                System.err.println("\n[异常] " + e);
            }
            listener.reset();
        }

        Usage total = agent.totalUsage();
        System.out.printf("%n再见。%n");
        System.out.printf("累计输出 token：%d%n", total.completionTokens());
        System.out.printf("最后一次请求的输入 token（实测）：%d%n", agent.lastPromptTokens());
        System.out.printf("累计缓存命中 token：%d%n", total.cacheHitTokens());
    }

    /** 读一个形如 --name=N 的整数参数。 */
    private static int intFlag(List<String> flags, String prefix, int fallback) {
        return flags.stream()
                .filter(f -> f.startsWith(prefix))
                .findFirst()
                .map(f -> Integer.parseInt(f.substring(prefix.length())))
                .orElse(fallback);
    }

    /** 读一个形如 --name=value 的字符串参数。 */
    private static String stringFlag(List<String> flags, String prefix, String fallback) {
        return flags.stream()
                .filter(f -> f.startsWith(prefix))
                .findFirst()
                .map(f -> f.substring(prefix.length()))
                .orElse(fallback);
    }

    private static final class ConsoleApprover implements Approver {

        private final BufferedReader stdin;
        private final Object lock = new Object();

        ConsoleApprover(BufferedReader stdin) {
            this.stdin = stdin;
        }

        @Override
        public Approval ask(String toolName, String action, Risk risk) {
            synchronized (lock) {
                String color = switch (risk) {
                    case LOW -> "[32m";
                    case MEDIUM -> "[33m";
                    case HIGH -> "[31m";
                };

                System.out.printf("%n%s┌─ 需要授权 ─────────────────────────────%n", color);
                System.out.printf("│ 工具：%s%n", toolName);
                for (String line : action.split("\n")) {
                    System.out.printf("│ %s%n", line);
                }
                System.out.printf("│ 风险：%s[0m%n", risk);
                System.out.printf("%s└────────────────────────────────────────%n[0m", color);

                while (true) {
                    System.out.print("  允许吗？ [y]允许  [a]本会话总是允许  [n]拒绝 > ");
                    System.out.flush();
                    String line = readLine();
                    if (line == null) {
                        return Approval.deny("输入已结束");
                    }
                    switch (line.strip().toLowerCase()) {
                        case "y", "" -> {
                            return Approval.once();
                        }
                        case "a" -> {
                            return Approval.always();
                        }
                        case "n" -> {
                            System.out.print("  拒绝理由（模型会看到，可留空直接回车）> ");
                            System.out.flush();
                            String reason = readLine();
                            return Approval.deny(
                                    reason == null || reason.isBlank() ? "用户未说明理由" : reason);
                        }
                        default -> System.out.println("  请输入 y / a / n");
                    }
                }
            }
        }

        private String readLine() {
            try {
                return stdin.readLine();
            } catch (java.io.IOException e) {
                return null;
            }
        }
    }

    private static final class ConsoleListener implements AgentListener {

        private boolean reasoningOpen = false;
        private boolean toolOpen = false;

        void reset() {
            closeReasoning();
            System.out.println();
            toolOpen = false;
        }

        private void closeReasoning() {
            if (reasoningOpen) {
                System.out.print("[0m");
                reasoningOpen = false;
            }
        }

        @Override
        public void onText(String delta) {
            closeReasoning();
            System.out.print(delta);
            System.out.flush();
        }

        @Override
        public void onReasoning(String delta) {
            if (!reasoningOpen) {
                System.out.print("[2m");
                reasoningOpen = true;
            }
            System.out.print(delta);
            System.out.flush();
        }

        @Override
        public void onToolCallStart(String name) {
            closeReasoning();
            System.out.print("\n\n[36m⚙ " + name + " [2m");
            System.out.flush();
            toolOpen = true;
        }

        @Override
        public void onToolCallArgs(String fragment) {
            System.out.print(fragment);
            System.out.flush();
        }

        @Override
        public void onToolResult(ToolResult result) {
            if (toolOpen) {
                System.out.println("[0m");
                toolOpen = false;
            }
            String preview = result.content().strip();
            String firstLine = preview.isEmpty() ? "（空）" : preview.split("\n")[0];
            if (firstLine.length() > 90) {
                firstLine = firstLine.substring(0, 90) + "…";
            }
            String color = result.error() ? "[31m" : "[32m";
            System.out.println(color + "  ← " + (result.error() ? "失败：" : "")
                    + firstLine + "[0m");
            System.out.flush();
        }

        @Override
        public void onRoundEnd(StopReason reason, Usage usage) {
            System.out.printf("%n[2m[轮次结束 %s  in=%d out=%d cache=%d][0m%n",
                    reason, usage.promptTokens(), usage.completionTokens(), usage.cacheHitTokens());
        }

        @Override
        public void onFailure(String message) {
            closeReasoning();
            System.err.println("\n[失败] " + message);
        }

        @Override
        public void onLimitReached(int maxRounds) {
            System.err.printf("%n[已停止] 达到最大轮数 %d。可能是模型陷入了循环。%n", maxRounds);
        }

        // ── M4 ──

        @Override
        public void onContextUsage(int estimated, int budget, int actual) {
            // 用量还小的时候不刷屏
            if (estimated < budget * 0.4) {
                return;
            }
            System.out.printf("%n[2m[上下文] 估算 %d / %d token"
                            + "（上次实测 %d，估算误差 %+d）[0m%n",
                    estimated, budget, actual,
                    actual == 0 ? 0 : estimated - actual);
        }

        @Override
        public void onCompactionLog(String message) {
            System.out.print(message);
            System.out.flush();
        }

        @Override
        public void onCompacted(Compactor.Result result) {
            System.out.printf("[压缩完成] 原始消息已被一段摘要替换%n");
            System.out.flush();
        }
    }
}
