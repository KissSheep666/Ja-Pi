package dev.jpi.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jpi.tool.Schema;
import dev.jpi.tool.Tool;
import dev.jpi.tool.ToolContext;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 读取文本文件，返回带行号的内容。 */
public final class ReadFileTool implements Tool {

    private static final int MAX_LINES = 2000;
    private static final long MAX_BYTES = 2_000_000;

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public boolean requiresApproval() {
        return false;
    }

    @Override
    public String description() {
        return """
                读取一个文本文件的内容，返回带行号的文本。
                什么时候用：需要看某个文件的具体实现、配置或内容时。
                什么时候不要用：想看目录里有什么用 glob；想按内容搜索用 grep。
                注意：一次最多返回 2000 行；文件很长时用 offset 分段读，不要反复重读同一个文件。
                """;
    }

    @Override
    public JsonNode parametersSchema() {
        return Schema.object()
                .string("path", "文件路径，相对工作目录")
                .integer("offset", "从第几行开始读（从 0 计数）。可选，默认 0")
                .integer("limit", "最多读多少行。可选，默认 2000")
                .required("path")
                .build();
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws Exception {
        Path file = ctx.resolve(args.path("path").asText());

        if (!Files.exists(file)) {
            return "错误：文件不存在：" + ctx.display(file);
        }
        if (Files.isDirectory(file)) {
            return "错误：" + ctx.display(file) + " 是目录不是文件。用 glob 查看目录里有什么。";
        }
        if (Files.size(file) > MAX_BYTES) {
            return "错误：文件超过 2MB，太大不便整体读取。请用 grep 定位具体内容。";
        }

        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (CharacterCodingException e) {
            return "错误：" + ctx.display(file) + " 不是有效的 UTF-8 文本文件，可能是二进制文件。";
        }

        int offset = Math.max(0, args.path("offset").asInt(0));
        int limit = args.has("limit") ? args.get("limit").asInt() : MAX_LINES;
        if (limit <= 0) {
            limit = MAX_LINES;
        }
        if (offset >= lines.size()) {
            return "错误：offset=" + offset + " 超出文件长度（共 " + lines.size() + " 行）";
        }

        int end = Math.min(offset + limit, lines.size());
        StringBuilder sb = new StringBuilder();
        for (int i = offset; i < end; i++) {
            sb.append(String.format("%6d\t%s%n", i + 1, lines.get(i)));
        }
        if (end < lines.size()) {
            sb.append(String.format("...（还有 %d 行未显示，用 offset=%d 继续读）%n",
                    lines.size() - end, end));
        }
        return sb.isEmpty() ? "（文件是空的）" : sb.toString();
    }

    @Override
    public String preview(JsonNode args) {
        return name() + " " + args.path("path").asText("");
    }
}
