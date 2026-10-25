package dev.jpi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jpi.provider.ToolSpec;

/**
 * 一个工具。
 *
 * <p>描述和参数 schema 决定模型会不会正确使用你 —— 比 execute 写得多漂亮更重要。
 */
public interface Tool {

    String name();

    String description();

    JsonNode parametersSchema();

    /**
     * @param args 模型给的参数（已解析）
     * @param ctx  工作目录、权限、取消令牌
     * @return 给模型看的文本结果
     */
    String execute(JsonNode args, ToolContext ctx) throws Exception;

    default ToolSpec spec() {
        return new ToolSpec(name(), description(), parametersSchema());
    }

    /** 只读、无副作用的工具覆写成 false，避免审批疲劳。 */
    default boolean requiresApproval() {
        return true;
    }

    /** 给用户看的"这次要做什么"，用于审批。 */
    default String describeAction(JsonNode args, ToolContext ctx) {
        return name() + " " + args;
    }

    /**
     * 会话级授权粒度。默认整个工具 —— 这个默认值太粗，
     * 有副作用的工具应该覆写，把粒度收窄到具体对象。
     */
    default String permissionScope(JsonNode args, ToolContext ctx) {
        return name();
    }

    /** UI 里的一行摘要，例如 "read_file src/Main.java"。 */
    default String preview(JsonNode args) {
        return name();
    }
}
