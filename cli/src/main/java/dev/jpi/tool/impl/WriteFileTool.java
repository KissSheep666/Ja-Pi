package dev.jpi.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jpi.tool.Schema;
import dev.jpi.tool.Tool;
import dev.jpi.tool.ToolContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 创建或覆盖文件。
 *
 * <p>⚠️ 它是整体覆盖，不是追加、不是合并。修改已有文件必须用 edit_file。
 */
public final class WriteFileTool implements Tool {

    private static final int MAX_CONTENT_BYTES = 1_000_000;

    @Override
    public String name() {
        return "write_file";
    }

    @Override
    public String description() {
        return """
                创建或【整体覆盖】一个文件。⚠️ 是覆盖，不是追加、不是修改。
                什么时候用：创建新文件。
                什么时候不要用：修改已有文件时【必须】用 edit_file —— 用 write_file 改文件
                会让你重写整个文件，非常容易丢掉你没注意到的内容。
                注意：会自动创建缺失的父目录；content 是完整的文件内容。
                """;
    }

    @Override
    public JsonNode parametersSchema() {
        return Schema.object()
                .string("path", "文件路径，相对工作目录")
                .string("content", "文件的完整内容（不是片段！）")
                .required("path")
                .required("content")
                .build();
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws Exception {
        String rawPath = args.path("path").asText("");
        if (rawPath.isBlank()) {
            return "错误：path 不能为空";
        }
        if (!args.has("content")) {
            return "错误：缺少 content 参数";
        }
        if (ctx.cancellation() != null && ctx.cancellation().isCancelled()) {
            return "已取消，未写入文件。";
        }

        String content = args.path("content").asText("");
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            return "错误：内容超过 1MB，请拆分成多次写入";
        }

        Path file = ctx.resolve(rawPath);
        boolean existed = Files.exists(file);

        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, content, StandardCharsets.UTF_8);

        long bytes = Files.size(file);
        return (existed ? "已覆盖 " : "已创建 ") + ctx.display(file)
                + "（" + bytes + " 字节，" + content.lines().count() + " 行）";
    }

    @Override
    public String describeAction(JsonNode args, ToolContext ctx) {
        String path = args.path("path").asText("");
        String content = args.path("content").asText("");
        long lines = content.lines().count();
        try {
            Path target = ctx.resolve(path);
            if (Files.exists(target)) {
                long oldSize = Files.size(target);
                return "覆盖已有文件 " + ctx.display(target)
                        + "\n    现有 " + oldSize + " 字节 → 新的 " + content.length()
                        + " 字符 / " + lines + " 行"
                        + "\n    ⚠️ 原内容将全部丢失";
            }
        } catch (Exception ignored) {
            // 路径非法由 execute 负责报错
        }
        return "新建文件 " + path + "（" + lines + " 行）";
    }

    /** 授权粒度是父目录，而不是整个工具。 */
    @Override
    public String permissionScope(JsonNode args, ToolContext ctx) {
        String path = args.path("path").asText("");
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return name() + ":" + (slash < 0 ? "." : path.substring(0, slash));
    }

    @Override
    public String preview(JsonNode args) {
        String path = args.path("path").asText("");
        long lines = args.path("content").asText("").lines().count();
        return name() + " " + path + " (" + lines + " 行)";
    }
}
