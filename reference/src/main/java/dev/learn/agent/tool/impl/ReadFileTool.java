package dev.learn.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.tool.Schema;
import dev.learn.agent.tool.Tool;
import dev.learn.agent.tool.ToolContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 读取文件内容。只读工具，最安全，也最常用。
 */
public final class ReadFileTool implements Tool {

    /** 一次最多返回多少行。见下面"为什么要截断"。 */
    private static final int MAX_LINES = 2000;

    @Override
    public String name() {
        return "read_file";
    }

    /** 只读，无副作用，不该打扰用户。 */
    @Override
    public boolean requiresApproval() {
        return false;
    }

    @Override
    public String description() {
        return """
                读取一个文本文件的内容，返回带行号的文本。
                什么时候用：需要看某个文件的具体实现、配置或内容时。
                什么时候不要用：想"看看目录里有什么"时用 glob；想按内容搜索时用 grep。
                注意：一次最多返回 2000 行；文件很长时用 offset 分段读，不要反复重读同一个文件。
                """;
    }

    @Override
    public JsonNode parametersSchema() {
        return Schema.object()
                .string("path", "文件路径，相对工作目录，例如 src/main/java/dev/learn/agent/Main.java")
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
            // 💡 错误信息要指向正确的下一步动作，模型才知道怎么办
            return "错误：" + ctx.display(file) + " 是目录不是文件。用 glob 查看目录里有什么。";
        }
        if (Files.size(file) > 2_000_000) {
            return "错误：文件超过 2MB，太大不便整体读取。请用 grep 定位具体内容。";
        }

        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

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
            // 💡 明确告诉模型"还有多少"以及"怎么继续拿"，它才会正确地再调一次
            sb.append(String.format("...（还有 %d 行未显示，用 offset=%d 继续读）%n",
                    lines.size() - end, end));
        }
        return sb.isEmpty() ? "（文件是空的）" : sb.toString();
    }
}
