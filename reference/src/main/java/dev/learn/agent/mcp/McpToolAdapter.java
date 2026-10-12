package dev.learn.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import dev.learn.agent.tool.Tool;
import dev.learn.agent.tool.ToolContext;

/**
 * 把一个 MCP 工具包装成我们自己的 {@link Tool}。
 *
 * <h3>这就是 MCP 能被接进来的全部原因</h3>
 * 这个类只有几十行，但它让<b>一整个外部工具生态</b>变成了我们
 * {@code ToolRegistry} 里的普通一员。{@code Agent}、{@code PermissionManager}、
 * {@code Compactor} 一个字都不用改，甚至连"这是 MCP 工具"都不知道。
 *
 * <p><b>这就是 M5 要验证的那件事：一个好的抽象能让新东西"插进来"而不是"改进去"。</b>
 *
 * <h3>名字为什么加前缀</h3>
 * MCP 工具的名字由服务器决定，我们控制不了。如果某个服务器提供一个叫
 * {@code read_file} 的工具，就会和我们的本地工具<b>静默冲突</b>——
 * 后注册的把先注册的覆盖掉，而你不会收到任何警告。
 *
 * <p>所以统一加前缀 {@code mcp__<服务器名>__<工具名>}。模型看到的是带前缀的名字，
 * 调用时我们再剥掉前缀转发给服务器。Claude Code 用的也是这个约定。
 */
public final class McpToolAdapter implements Tool {

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
        this.exposedName = "mcp__" + serverName + "__" + remoteName;
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
        // 把来源标出来，对模型理解工具的用途有帮助
        return "（来自 MCP 服务器 " + serverName + "）\n" + description;
    }

    @Override
    public JsonNode parametersSchema() {
        if (inputSchema != null) {
            return inputSchema;
        }
        // ⚠️ MCP 规范里 inputSchema 是必填的，但总有服务器不守规矩。
        //    给一个空 schema 至少能让它被列出来，而不是整个注册流程崩掉。
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .createObjectNode().put("type", "object");
    }

    @Override
    public String execute(JsonNode args, ToolContext ctx) throws Exception {
        try {
            return client.callTool(remoteName, args);
        } catch (Exception e) {
            // 💡 和其他工具一样：失败是信息，不是终止。
            //    把原始错误带回去，模型能据此判断是换个参数还是换个办法。
            return "调用 MCP 工具 " + remoteName + " 失败：" + e.getMessage();
        }
    }

    // ── 权限：MCP 工具是"外来的"，我们不知道它背后做什么 ──

    @Override
    public String describeAction(JsonNode args, ToolContext ctx) {
        return "调用外部 MCP 工具 " + serverName + " / " + remoteName
                + "\n    参数：" + args;
    }

    /**
     * ⚠️ 粒度是<b>具体工具</b>而不是整个服务器。
     * 因为一个 MCP 服务器上可能既有只读工具也有破坏性工具，
     * 按服务器授权等于把整个服务器的权限一起给出去。
     */
    @Override
    public String permissionScope(JsonNode args, ToolContext ctx) {
        return exposedName;
    }
}
