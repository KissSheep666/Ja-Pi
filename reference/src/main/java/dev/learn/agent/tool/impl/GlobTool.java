package dev.learn.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.tool.Schema;
import dev.learn.agent.tool.Tool;
import dev.learn.agent.tool.ToolContext;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 按 glob 模式查找文件。
 */
public final class GlobTool implements Tool {

    private static final int MAX_RESULTS = 200;
    private static final int MAX_DEPTH = 12;

    /** 这些目录永远不进。见下面"为什么要跳过"。 */
    private static final Set<String> SKIP_DIRS =
            Set.of(".git", "target", "build", "node_modules", ".idea", ".gradle", "dist", "out");

    @Override
    public String name() {
        return "glob";
    }

    /** 只读，无副作用，不该打扰用户。 */
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

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        Path root = ctx.root();

        List<Path> matches;
        try (Stream<Path> walk = Files.walk(root, MAX_DEPTH)) {
            matches = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> !isSkipped(root, p))
                    .filter(p -> matchesPath(matcher, root, p, pattern))
                    .sorted(Comparator.comparingLong(GlobTool::lastModified).reversed())
                    .limit(MAX_RESULTS)
                    .toList();
        }

        if (matches.isEmpty()) {
            return "没有文件匹配 " + pattern + "（工作目录：" + root + "）";
        }

        StringBuilder sb = new StringBuilder();
        for (Path p : matches) {
            sb.append(ctx.display(p)).append('\n');
        }
        if (matches.size() == MAX_RESULTS) {
            sb.append("...（已达上限 ").append(MAX_RESULTS).append(" 条，结果可能不完整）\n");
        }
        return sb.toString();
    }

    /**
     * Java 的 glob 里 {@code **}{@code /*.java} 要求路径里至少有一个 {@code /}，
     * 所以匹配不到顶层的 {@code Main.java}。这里做了一次兼容：
     * 模式里不含 {@code /} 时，也拿文件名单独试一次。
     */
    private static boolean matchesPath(PathMatcher matcher, Path root, Path file, String pattern) {
        Path relative = root.relativize(file);
        if (matcher.matches(relative)) {
            return true;
        }
        return !pattern.contains("/") && matcher.matches(relative.getFileName());
    }

    private static boolean isSkipped(Path root, Path file) {
        for (Path segment : root.relativize(file)) {
            if (SKIP_DIRS.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
