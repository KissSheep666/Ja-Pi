package dev.jpi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 拼 JSON Schema 的小 DSL，避免每个工具都写十几行噪声。
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

    public Schema required(String name) {
        required.add(name);
        return this;
    }

    public JsonNode build() {
        if (!required.isEmpty()) {
            ArrayNode array = root.putArray("required");
            required.forEach(array::add);
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
