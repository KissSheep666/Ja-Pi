package dev.jpi.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jpi.tool.Schema;
import dev.jpi.tool.Tool;
import dev.jpi.tool.ToolContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 执行 shell 命令。
 *
 * <p>最强大也最危险的工具：它能做任何事，所以路径沙箱对它无效。
 * 约束靠审批 + 危险命令识别。
 *
 * <p>两个实现要点：
 * <ul>
 *   <li>输出必须在另一个线程里读，否则管道缓冲区满会与 waitFor 互相死锁；</li>
 *   <li>等待时轮询取消令牌，用户按 Ctrl+C 能杀掉整个进程树。</li>
 * </ul>
 */
public final class BashTool implements Tool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int MAX_TIMEOUT_SECONDS = 300;
    private static final int MAX_OUTPUT_CHARS = 30_000;
    private static final long POLL_MILLIS = 200;

    private static final List<String> SHELL = detectShell();

    @Override
    public String name() {
        return "run_bash";
    }

    @Override
    public String description() {
        return """
                在 shell 里执行一条命令，返回它的输出（stdout 和 stderr 合并）和退出码。
                工作目录是项目根目录。
                什么时候用：跑测试、编译构建、git 操作、查看环境信息、执行脚本。
                什么时候【不要】用：读文件用 read_file，找文件用 glob，搜内容用 grep。
                那些专用工具比 `cat` / `find` / `grep` 更可控，结果也更省 token。
                注意：有超时限制（默认 30 秒，最长 300 秒）；输出超过 3 万字符会被截断。
                """;
    }

    @Override
    public JsonNode parametersSchema() {
        return Schema.object()
                .string("command", "要执行的命令，例如 \"mvn -q test\" 或 \"git status\"")
                .integer("timeout_seconds", "超时秒数。可选，默认 30，最大 300")
                .required("command")
                .build();
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws Exception {
        String command = args.path("command").asText("");
        if (command.isBlank()) {
            return "错误：command 不能为空";
        }

        int timeout = args.path("timeout_seconds").asInt(DEFAULT_TIMEOUT_SECONDS);
        timeout = Math.clamp(timeout, 1, MAX_TIMEOUT_SECONDS);

        ProcessBuilder builder = new ProcessBuilder(SHELL.get(0), SHELL.get(1), command);
        builder.directory(ctx.root().toFile());
        builder.redirectErrorStream(true);

        Process process = builder.start();

        StringBuilder output = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    if (output.length() < MAX_OUTPUT_CHARS * 2) {
                        output.append(line).append('\n');
                    }
                }
            } catch (IOException ignored) {
                // 进程被杀时流会断，正常
            }
        });

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
        while (true) {
            if (ctx.cancellation() != null && ctx.cancellation().isCancelled()) {
                killTree(process);
                reader.join(2_000);
                return "已取消：命令被用户中断。\n已产生的输出：\n" + truncate(output.toString());
            }
            if (process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                break;
            }
            if (System.nanoTime() >= deadline) {
                killTree(process);
                reader.join(2_000);
                return "错误：命令超过 " + timeout + " 秒仍未结束，已被强制终止。\n"
                        + "已产生的输出：\n" + truncate(output.toString())
                        + "\n（提示：可以用 timeout_seconds 参数调大超时，最多 300 秒）";
            }
        }

        reader.join(5_000);
        int exitCode = process.exitValue();

        String body = truncate(output.toString());
        if (body.isEmpty()) {
            body = "（命令没有产生任何输出）";
        }
        return "退出码：" + exitCode + "\n" + body;
    }

    @Override
    public String describeAction(JsonNode args, ToolContext ctx) {
        String command = args.path("command").asText("");
        return "执行命令：\n      " + command.replace("\n", "\n      ");
    }

    /** 会话级授权粒度是命令的第一个词。 */
    @Override
    public String permissionScope(JsonNode args, ToolContext ctx) {
        String command = args.path("command").asText("").strip();
        String firstWord = command.isEmpty() ? "" : command.split("\\s+", 2)[0];
        return name() + ":" + firstWord;
    }

    @Override
    public String preview(JsonNode args) {
        String command = args.path("command").asText("").strip().replace('\n', ' ');
        if (command.length() > 80) {
            command = command.substring(0, 80) + "…";
        }
        return name() + " " + command;
    }

    private static void killTree(Process process) {
        try (var descendants = process.descendants()) {
            descendants.forEach(ProcessHandle::destroyForcibly);
        }
        process.destroyForcibly();
        try {
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String truncate(String s) {
        if (s.length() <= MAX_OUTPUT_CHARS) {
            return s;
        }
        return s.substring(0, MAX_OUTPUT_CHARS)
                + "\n...（输出被截断，共 " + s.length() + " 字符，只显示了前 "
                + MAX_OUTPUT_CHARS + " 字符）";
    }

    /** 优先 bash，找不到再退回系统自带 shell。 */
    private static List<String> detectShell() {
        String os = System.getProperty("os.name", "").toLowerCase();

        if (os.contains("win")) {
            Path bash = findOnPath("bash.exe");
            if (bash != null) {
                return List.of(bash.toString(), "-lc");
            }
            return List.of("cmd.exe", "/c");
        }

        if (Files.isExecutable(Path.of("/bin/bash"))) {
            return List.of("/bin/bash", "-lc");
        }
        return List.of("/bin/sh", "-c");
    }

    private static Path findOnPath(String executable) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return null;
        }
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(dir, executable);
                if (Files.isExecutable(candidate)) {
                    return candidate;
                }
            } catch (Exception ignored) {
                // PATH 里可能有非法路径，跳过
            }
        }
        return null;
    }
}
