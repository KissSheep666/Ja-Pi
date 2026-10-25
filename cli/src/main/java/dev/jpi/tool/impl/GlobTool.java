package dev.jpi.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jpi.tool.Schema;
import dev.jpi.tool.Tool;
import dev.jpi.tool.ToolContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/** 按 glob 模式查找文件。 */
public final class GlobTool implements Tool {

    private static final int MAX_RESULTS = 200;
    private static final int MAX_DEPTH = 12;

    private static final Set<String> SKIP_DIRS =
            Set.of(".git", "target", "build", "node_modules", ".idea", ".gradle", "dist", "out", ".venv");

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public boolean requiresApproval() {
        return false;
    }

    @Override
    public String description() {
        return """
                按 glob 模式查找文件，返回匹配的相对路径列表（按修改时间从新到旧）。
                什么时候用：想知道某个文件在哪、想看清项目结构、想按扩展名筛选文件时。
                参数示例："**/*.java"、"src/**/*.md"、"*.xml"。
                注意：结果最多 200 条；自动跳过 .git、target、node_modules 等目录。
                不含 / 的模式（如 "*.xml"）按文件名递归匹配。
                """;
    }

    @Override
    public JsonNode parametersSchema() {
        return Schema.object()
                .string("pattern", "glob 模式。要递归查找请用 **，例如 **/*.java")
                .required("pattern")
                .build();
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws Exception {
        String pattern = args.path("pattern").asText("").trim();
        if (pattern.isEmpty()) {
            return "错误：pattern 不能为空。例如 \"**/*.java\"";
        }

        GlobPattern matcher;
        try {
            matcher = GlobPattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return "错误：glob 模式无效：" + e.getDescription();
        }

        Path root = ctx.root();
        List<Path> matches;
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            matches = walk
                    .filter(Files::isRegularFile)
                    .filter(path -> !isSkipped(root, path))
                    .filter(path -> {
                        if (ctx.cancellation() != null) {
                            ctx.cancellation().throwIfCancelled();
                        }
                        return matcher.matches(root.relativize(path));
                    })
                    .sorted(Comparator.comparingLong(GlobTool::lastModified).reversed())
                    .limit(MAX_RESULTS)
                    .toList();
        }

        if (matches.isEmpty()) {
            return "没有文件匹配 " + pattern + "（工作目录：" + root + "）";
        }

        StringBuilder sb = new StringBuilder();
        for (Path path : matches) {
            sb.append(ctx.display(path)).append('\n');
        }
        if (matches.size() == MAX_RESULTS) {
            sb.append("...（已达上限 ").append(MAX_RESULTS).append(" 条，结果可能不完整）\n");
        }
        return sb.toString();
    }

    private static boolean isSkipped(Path root, Path file) {
        for (Path segment : root.relativize(file)) {
            if (SKIP_DIRS.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    @Override
    public String preview(JsonNode args) {
        return name() + " " + args.path("pattern").asText("");
    }
}
