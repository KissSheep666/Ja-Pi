package dev.learn.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.tool.Schema;
import dev.learn.agent.tool.Tool;
import dev.learn.agent.tool.ToolContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 精确字符串替换。修改已有代码的<b>首选</b>方式。
 *
 * <p>它比 write_file 安全得多，因为模型只需要提供"改动的那一段"，
 * 而不是重写整个文件 —— 于是它不可能在重写时顺手丢掉别的内容。
 */
public final class EditFileTool implements Tool {

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description() {
        return """
                在文件中做精确的字符串替换。修改已有代码时优先用这个，不要用 write_file。
                要求：old_string 必须与文件中的内容【完全一致】（包括空格和缩进），
                并且在文件中【唯一出现】。如果它出现多次，请多带几行上下文使它唯一，
                或者把 replace_all 设为 true。
                建议：改动之前先用 read_file 看清原文，不要凭记忆猜格式。
                """;
    }

    @Override
    public JsonNode parametersSchema() {
        return Schema.object()
                .string("path", "文件路径，相对工作目录")
                .string("old_string", "要被替换的原文。必须与文件内容完全一致，包括缩进")
                .string("new_string", "替换成的新内容。传空字符串表示删除这段")
                .bool("replace_all", "是否替换所有出现的位置。默认 false")
                .required("path")
                .required("old_string")
                .required("new_string")
                .build();
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws Exception {
        Path file = ctx.resolve(args.path("path").asText(""));

        if (!Files.exists(file)) {
            return "错误：文件不存在：" + ctx.display(file) + "。新建文件请用 write_file。";
        }
        if (!args.has("old_string")) {
            return "错误：缺少 old_string 参数";
        }

        String oldString = args.path("old_string").asText("");
        String newString = args.path("new_string").asText("");
        boolean replaceAll = args.path("replace_all").asBoolean(false);

        if (oldString.isEmpty()) {
            // ⚠️ 空字符串在 Java 里会匹配到每个位置，直接把文件搞乱
            return "错误：old_string 不能为空。如果你想整体重写文件，用 write_file。";
        }

        String original = Files.readString(file, StandardCharsets.UTF_8);
        int occurrences = countOccurrences(original, oldString);

        if (occurrences == 0) {
            // 💡 这是最常见的失败。错误信息要给出"下一步怎么办"，而不是只说"没找到"。
            return "错误：在 " + ctx.display(file) + " 中没有找到 old_string。\n"
                    + "可能原因：缩进或空格不一致、内容已变化。\n"
                    + "建议：先用 read_file 读一遍这个文件，用文件里的原文重试。\n"
                    + "你给的 old_string 是：\n" + abbreviate(oldString);
        }

        if (occurrences > 1 && !replaceAll) {
            // 💡 唯一性检查是 edit 工具的灵魂。
            //    没有它，模型会在你没注意到的地方悄悄改掉一处不该改的代码。
            return "错误：old_string 在文件中出现了 " + occurrences + " 次，无法确定改哪一处。\n"
                    + "解决办法：多带几行上下文（比如把上一行和下一行也包进来）使它唯一，\n"
                    + "或者显式设置 replace_all=true 来替换全部 " + occurrences + " 处。";
        }

        String updated = replaceAll
                ? original.replace(oldString, newString)
                : replaceFirst(original, oldString, newString);

        Files.writeString(file, updated, StandardCharsets.UTF_8);

        return "已修改 " + ctx.display(file)
                + "（替换 " + (replaceAll ? occurrences : 1) + " 处）";
    }

    @Override
    public String describeAction(JsonNode args, ToolContext ctx) {
        String path = args.path("path").asText("");
        String oldString = args.path("old_string").asText("");
        String newString = args.path("new_string").asText("");
        return "修改 " + path
                + "\n    - " + abbreviate(oldString).replace("\n", "\n      ")
                + "\n    + " + abbreviate(newString).replace("\n", "\n      ");
    }

    /** 粒度是具体文件，不是整个工具。 */
    @Override
    public String permissionScope(JsonNode args, ToolContext ctx) {
        return name() + ":" + args.path("path").asText("");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index >= 0) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }

    private static String replaceFirst(String haystack, String target, String replacement) {
        int index = haystack.indexOf(target);
        return haystack.substring(0, index) + replacement + haystack.substring(index + target.length());
    }

    private static String abbreviate(String s) {
        String one = s.strip();
        return one.length() <= 200 ? one : one.substring(0, 200) + "…";
    }
}
