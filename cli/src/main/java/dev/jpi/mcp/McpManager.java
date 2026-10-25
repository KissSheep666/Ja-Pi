package dev.jpi.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jpi.tool.ToolRegistry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 管理本进程启动的所有 MCP 服务器。
 */
public final class McpManager implements AutoCloseable {

    public record Attached(String server, int tools) {
    }

    private final List<McpClient> clients = new CopyOnWriteArrayList<>();
    private final List<Attached> attachments = new CopyOnWriteArrayList<>();

    public Attached attach(String commandLine, ToolRegistry registry) throws IOException {
        List<String> command = split(commandLine);
        if (command.isEmpty()) {
            throw new IOException("MCP 命令为空");
        }
        McpClient client = McpClient.start(adaptForWindows(command));
        return register(client, registry);
    }

    /** 用当前 JVM 的 classpath 启动内置示例服务器。 */
    public Attached attachDemo(ToolRegistry registry) throws IOException {
        String javaBin = ProcessHandle.current().info().command().orElse("java");
        String classpath = System.getProperty("java.class.path");
        McpClient client = McpClient.start(
                List.of(javaBin, "-cp", classpath, "dev.jpi.mcp.demo.DemoMcpServer"));
        return register(client, registry);
    }

    private Attached register(McpClient client, ToolRegistry registry) throws IOException {
        int count = 0;
        try {
            for (JsonNode definition : client.listTools()) {
                McpToolAdapter adapter = new McpToolAdapter(client, client.serverName(), definition);
                if (registry.has(adapter.name())) {
                    continue;   // 名字冲突时跳过，不覆盖本地工具
                }
                registry.register(adapter);
                count++;
            }
        } catch (IOException | RuntimeException e) {
            client.close();
            throw e;
        }
        clients.add(client);
        Attached attached = new Attached(client.serverName(), count);
        attachments.add(attached);
        return attached;
    }

    public List<Attached> attachments() {
        return List.copyOf(attachments);
    }

    @Override
    public void close() {
        for (McpClient client : clients) {
            client.close();
        }
        clients.clear();
        attachments.clear();
    }

    /** 支持引号的简单命令行切分：npx -y "@scope/server" "/tmp/my dir"。 */
    static List<String> split(String commandLine) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;

        for (int i = 0; i < commandLine.length(); i++) {
            char c = commandLine.charAt(i);

            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                    continue;
                }
                // 引号内只把 \" 和 \\ 当转义，保留 Windows 路径里的反斜杠
                if (c == '\\' && i + 1 < commandLine.length()) {
                    char next = commandLine.charAt(i + 1);
                    if (next == quote || next == '\\') {
                        current.append(next);
                        i++;
                        continue;
                    }
                }
                current.append(c);
                continue;
            }

            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (!current.isEmpty()) {
                    out.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            // 引号外的反斜杠原样保留（C:\Users\... 不能被吃掉）
            current.append(c);
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }

    /**
     * Windows 上 .cmd / .bat 不能直接被 CreateProcess 执行，
     * 常见包管理器命令需要先套一层 cmd /c。
     */
    static List<String> adaptForWindows(List<String> command) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (!os.contains("win") || command.isEmpty()) {
            return command;
        }
        String exe = command.get(0).toLowerCase(Locale.ROOT);
        boolean batch = exe.endsWith(".cmd") || exe.endsWith(".bat");
        boolean packageRunner = switch (exe) {
            case "npx", "npm", "pnpm", "yarn", "uvx" -> true;
            default -> false;
        };
        if (batch || packageRunner) {
            List<String> adapted = new ArrayList<>(command.size() + 2);
            adapted.add("cmd.exe");
            adapted.add("/c");
            adapted.addAll(command);
            return adapted;
        }
        return command;
    }
}
