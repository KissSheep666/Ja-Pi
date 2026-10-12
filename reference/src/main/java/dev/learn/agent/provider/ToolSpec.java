package dev.learn.agent.provider;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 一个工具的"说明书"，用厂商无关的形式描述。
 *
 * <p>注意 parametersSchema 是标准 JSON Schema，不含任何厂商特有的包装。
 * 各个 Provider 负责把它翻译成自己家的方言：
 * <pre>
 *   DeepSeek/OpenAI:  {"type":"function","function":{"name":..., "description":..., "parameters":...}}
 *   Anthropic:        {"name":..., "description":..., "input_schema":...}
 * </pre>
 * ⚠️ 同一个 ToolSpec，两种完全不同的线格式。这就是抽象的价值所在。
 */
public record ToolSpec(String name, String description, JsonNode parametersSchema) {
}
