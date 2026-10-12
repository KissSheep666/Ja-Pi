package dev.learn.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 拼 JSON Schema 的小 DSL。
 *
 * <p>没有它的话，每个工具都要写十几行 createObjectNode / putObject / putArray，
 * 六个工具就是上百行噪声，而且 schema 长什么样反而看不出来了。
 *
 * <p>⚠️ 这里刻意不用 Jackson 的 ObjectNode 链式调用。原因：
 * schema 的可读性直接决定模型的调用准确率。工具描述和参数说明写得清楚，
 * 模型就用得对；写得含糊，模型就乱传参数。值得为"一眼能看懂"多写一个类。
 */
public final class Schema {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ObjectNode root = MAPPER.createObjectNode();
    private final ObjectNode properties = MAPPER.createObjectNode();
    private final List<String> required = new ArrayList<>();

    private Schema() {
        root.put("type", "object");
        root.set("properties", properties);
    }

    public static Schema object() {
        return new Schema();
    }

    public Schema string(String name, String description) {
        return add(name, "string", description);
    }

    public Schema integer(String name, String description) {
        return add(name, "integer", description);
    }

    public Schema bool(String name, String description) {
        return add(name, "boolean", description);
    }

    /** 把某个参数标记为必填。可以链式调用多次。 */
    public Schema required(String name) {
        required.add(name);
        return this;
    }

    public JsonNode build() {
        if (!required.isEmpty()) {
            ArrayNode arr = root.putArray("required");
            required.forEach(arr::add);
        }
        return root;
    }

    private Schema add(String name, String type, String description) {
        properties.putObject(name)
                .put("type", type)
                .put("description", description);
        return this;
    }
}
