package dev.learn.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.tool.Schema;
import dev.learn.agent.tool.Tool;
import dev.learn.agent.tool.ToolContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 创建或覆盖文件。
 *
 * <p>⚠️ 它是<b>整体覆盖</b>，不是追加，也不是合并。
 * 模型非常容易把 write_file 当成"编辑"来用，然后把你文件里原有的内容全删了。
 * 所以描述和错误信息里都要反复强调这一点。
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
                .string("path", "文件路径，相对工作目录，例如 src/main/java/demo/Helper.java")
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
                // ⚠️ 一定要把"原有大小"和"新大小"都写出来，
                //    用户才能一眼看出这是不是在毁掉什么
                long oldSize = Files.size(target);
                return "覆盖已有文件 " + ctx.display(target)
                        + "\n    现有 " + oldSize + " 字节 → 新的 " + content.length() + " 字符 / " + lines + " 行"
                        + "\n    ⚠️ 原内容将全部丢失";
            }
        } catch (Exception ignored) {
            // 路径非法由 execute 负责报错
        }
        return "新建文件 " + path + "（" + lines + " 行）";
    }

    /**
     * 会话级授权的粒度是<b>父目录</b>而不是整个工具。
     * "总是允许 write_file" 等于允许写任何文件；
     * "总是允许写 src/main/java/demo/ 下的文件" 就合理得多。
     */
    @Override
    public String permissionScope(JsonNode args, ToolContext ctx) {
        String path = args.path("path").asText("");
        int slash = path.lastIndexOf('/');
        return name() + ":" + (slash < 0 ? "." : path.substring(0, slash));
    }
}
