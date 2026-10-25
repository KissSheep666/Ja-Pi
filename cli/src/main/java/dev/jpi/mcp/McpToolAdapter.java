package dev.jpi.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jpi.tool.Tool;
import dev.jpi.tool.ToolContext;

/**
 * 把 MCP 工具包装成普通的 {@link Tool}。
 *
 * <p>几十行代码，让一整个外部工具生态变成 ToolRegistry 里的普通一员：
 * Agent、PermissionManager、Compactor 完全不知道"这是 MCP 工具"。
 *
 * <p>工具名统一加 {@code mcp__<server>__<tool>} 前缀，避免和本地工具静默冲突。
 */
public final class McpToolAdapter implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_NAME_LENGTH = 64;

    private final McpClient client;
    private final String serverName;
    private final String remoteName;
    private final String exposedName;
    private final String description;
    private final JsonNode inputSchema;

    public McpToolAdapter(McpClient client, String serverName, JsonNode toolDefinition) {
        this.client = client;
        this.serverName = serverName;
        this.remoteName = toolDefinition.path("name").asText();
        this.exposedName = sanitize("mcp__" + serverName + "__" + remoteName);
        this.description = toolDefinition.path("description").asText("(MCP 工具，没有提供描述)");
        this.inputSchema = toolDefinition.path("inputSchema").isObject()
                ? toolDefinition.path("inputSchema")
                : null;
    }

    @Override
    public String name() {
        return exposedName;
    }

    @Override
    public String description() {
        return "（来自 MCP 服务器 " + serverName + "）\n" + description;
    }

    @Override
    public JsonNode parametersSchema() {
        if (inputSchema != null) {
            return inputSchema;
        }
        // MCP 规范里 inputSchema 必填，但总有服务器不守规矩
        return JSON.createObjectNode().put("type", "object");
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws Exception {
        try {
            return client.callTool(remoteName, args);
        } catch (Exception e) {
            // 失败是信息，不是终止：把原因带回去，模型能换个办法
            return "调用 MCP 工具 " + remoteName + " 失败：" + e.getMessage();
        }
    }

    @Override
    public String describeAction(JsonNode args, ToolContext ctx) {
        return "调用外部 MCP 工具 " + serverName + " / " + remoteName
                + "\n    参数：" + args;
    }

    @Override
    public String permissionScope(JsonNode args, ToolContext ctx) {
        return exposedName;
    }

    @Override
    public String preview(JsonNode args) {
        return exposedName;
    }

    /** 工具名只允许字母、数字、下划线、连字符，长度 ≤ 64。 */
    private static String sanitize(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-'
                    ? c : '_');
        }
        return sb.length() <= MAX_NAME_LENGTH ? sb.toString() : sb.substring(0, MAX_NAME_LENGTH);
    }
}
