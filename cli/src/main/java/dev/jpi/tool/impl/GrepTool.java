package dev.jpi.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jpi.tool.Schema;
import dev.jpi.tool.Tool;
import dev.jpi.tool.ToolContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/** 按内容搜索文件。agent 最常用的工具，没有之一。 */
public final class GrepTool implements Tool {

    private static final int MAX_MATCHES = 100;
    private static final int MAX_FILE_SIZE = 1_000_000;
    private static final int MAX_DEPTH = 12;

    private static final Set<String> SKIP_DIRS =
            Set.of(".git", "target", "build", "node_modules", ".idea", ".gradle", "dist", "out", ".venv");

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public boolean requiresApproval() {
        return false;
    }

    @Override
    public String description() {
        return """
                用正则表达式搜索文件内容，返回 "文件路径:行号: 该行内容"。
                什么时候用：知道某个标识符/字符串/报错信息，想找到它在哪里定义或被使用。
                这是定位代码最快的手段——比 read_file 一个个翻文件高效得多，优先用它。
                注意：结果最多 100 条；只搜文本文件；默认跳过 target/.git/node_modules 等目录。
                """;
    }

    @Override
    public JsonNode parametersSchema() {
        return Schema.object()
                .string("pattern", "正则表达式，例如 \"class\\s+Agent\" 或 \"TODO\"。注意反斜杠要转义")
                .string("glob", "只搜匹配该 glob 的文件。可选，例如 \"**/*.java\"。默认搜所有文本文件")
                .required("pattern")
                .build();
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws Exception {
        String patternText = args.path("pattern").asText("");
        if (patternText.isEmpty()) {
            return "错误：pattern 不能为空";
        }

        Pattern regex;
        try {
            regex = Pattern.compile(patternText);
        } catch (PatternSyntaxException e) {
            return "错误：正则表达式无效：" + e.getDescription()
                    + "（在位置 " + e.getIndex() + "）。你给的是：" + patternText;
        }

        String glob = args.path("glob").asText("**/*");
        GlobPattern matcher;
        try {
            matcher = GlobPattern.compile(glob);
        } catch (PatternSyntaxException e) {
            return "错误：glob 模式无效：" + e.getDescription();
        }

        Path root = ctx.root();
        StringBuilder sb = new StringBuilder();
        int[] count = {0};
        int[] scanned = {0};

        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            List<Path> files = walk
                    .filter(Files::isRegularFile)
                    .filter(path -> !isSkipped(root, path))
                    .filter(path -> matcher.matches(root.relativize(path)))
                    .toList();

            for (Path file : files) {
                if (count[0] >= MAX_MATCHES) {
                    break;
                }
                if (ctx.cancellation() != null) {
                    ctx.cancellation().throwIfCancelled();
                }
                scanned[0]++;
                searchFile(file, regex, ctx, sb, count);
            }
        }

        if (sb.isEmpty()) {
            return "没有找到匹配 " + patternText + " 的内容（搜索了 " + scanned[0] + " 个文件）";
        }
        if (count[0] >= MAX_MATCHES) {
            sb.append("...（已达上限 ").append(MAX_MATCHES).append(" 条）\n");
        }
        return sb.toString();
    }

    private void searchFile(Path file, Pattern regex, ToolContext ctx,
                            StringBuilder sb, int[] count) {
        List<String> lines;
        try {
            if (Files.size(file) > MAX_FILE_SIZE) {
                return;
            }
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException | OutOfMemoryError e) {
            // 二进制文件在这里抛 MalformedInputException；静默跳过
            return;
        }

        String display = ctx.display(file);
        for (int i = 0; i < lines.size() && count[0] < MAX_MATCHES; i++) {
            String line = lines.get(i);
            if (regex.matcher(line).find()) {
                String trimmed = line.strip();
                if (trimmed.length() > 300) {
                    trimmed = trimmed.substring(0, 300) + "…";
                }
                sb.append(display).append(':').append(i + 1).append(": ").append(trimmed).append('\n');
                count[0]++;
            }
        }
    }

    private static boolean isSkipped(Path root, Path file) {
        for (Path segment : root.relativize(file)) {
            if (SKIP_DIRS.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String preview(JsonNode args) {
        String glob = args.path("glob").asText("");
        return name() + " " + args.path("pattern").asText("")
                + (glob.isBlank() ? "" : "  (" + glob + ")");
    }
}
