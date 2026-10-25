package dev.jpi.provider;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一个工具的"说明书"，用厂商无关的形式描述。
 *
 * <p>parametersSchema 是标准 JSON Schema，不含任何厂商包装。
 * 各 Provider 负责翻译：
 * <pre>
 *   OpenAI / DeepSeek:  {"type":"function","function":{"name":...,"parameters":...}}
 *   Anthropic:          {"name":...,"input_schema":...}
 * </pre>
 */
public record ToolSpec(String name, String description, JsonNode parametersSchema) {
}
