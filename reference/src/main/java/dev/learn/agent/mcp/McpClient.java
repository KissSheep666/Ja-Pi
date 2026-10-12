package dev.learn.agent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一个最小的 MCP 客户端（stdio 传输）。
 *
 * <h3>MCP 协议其实就是 JSON-RPC 2.0</h3>
 * 剥掉所有包装，MCP 就是：
 * <pre>
 *   你 → 子进程 stdin:  {"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}\n
 *   你 ← 子进程 stdout: {"jsonrpc":"2.0","id":1,"result":{...}}\n
 * </pre>
 * <b>一行一个 JSON，换行分隔。</b>就这么简单。
 *
 * <p>为什么用 stdio 而不是 HTTP？因为 stdio 的 MCP server 就是一个可以
 * {@code npx @modelcontextprotocol/server-filesystem /path} 直接跑起来的进程，
 * 不需要端口、不需要配置、不需要鉴权。对本地工具来说这是最省事的形态。
 *
 * <h3>三个必须守的规矩</h3>
 * <ol>
 *   <li><b>stdout 是协议专用的。</b>服务器往 stdout 打任何日志都会破坏协议。
 *       日志必须走 stderr。（我们这个客户端的子进程遵守了这条，你的也要。）</li>
 *   <li><b>必须先 initialize。</b>握手之后才能调别的。</li>
 *   <li><b>id 要自己管。</b>请求和响应靠 id 配对。并发请求时 id 必须唯一。</li>
 * </ol>
 */
public final class McpClient implements AutoCloseable {

    /** 我们声明的协议版本。服务器如果支持会回同一个，否则回它自己的。 */
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final Process process;
    private final BufferedWriter toServer;
    private final BufferedReader fromServer;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger nextId = new AtomicInteger(1);

    /**
     * ⚠️ 所有请求串行化。
     *
     * <p>因为我们的工具是<b>并发执行</b>的（M2 用的虚拟线程），
     * 如果两个 MCP 工具同时调用，两个线程会同时往 stdin 写、同时从 stdout 读，
     * 响应就串了。加锁是最省事的正确做法。
     *
     * <p>真实产品会做 id → Future 的映射表来支持并发，代价是复杂度。
     * 对学习项目来说，串行化的 MCP 调用完全够用。
     */
    private final Object lock = new Object();

    /** 服务器在握手时自报的名字，用来给工具名加前缀。 */
    private String serverName = "unknown";

    private McpClient(Process process) {
        this.process = process;
        this.toServer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.fromServer = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    /**
     * 启动一个 MCP 服务器进程并完成握手。
     *
     * @param command 命令行，例如 {@code ["npx", "-y", "@modelcontextprotocol/server-filesystem", "/tmp"]}
     */
    public static McpClient start(List<String> command) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command);
        // ⚠️ stderr 必须单独处理。如果让它混进 stdout，协议就废了。
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);

        McpClient client = new McpClient(builder.start());
        client.initialize();
        return client;
    }

    /** 握手。成功后服务器才认识我们。 */
    public JsonNode initialize() throws IOException {
        ObjectNode params = json.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", json.createObjectNode());
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "learn-agent");
        clientInfo.put("version", "0.1.0");

        JsonNode result = request("initialize", params);

        String reported = result.path("serverInfo").path("name").asText("");
        if (!reported.isBlank()) {
            this.serverName = reported;
        }

        // ⚠️ 握手后必须发这个"通知"。它没有 id，服务器不会回。
        notify("notifications/initialized", json.createObjectNode());
        return result;
    }

    /** 服务器自报的名字。工具名前缀用它。 */
    public String serverName() {
        return serverName;
    }

    /** 列出服务器提供的所有工具。 */
    public List<JsonNode> listTools() throws IOException {
        JsonNode result = request("tools/list", json.createObjectNode());
        List<JsonNode> tools = new ArrayList<>();
        result.path("tools").forEach(tools::add);
        return tools;
    }

    /**
     * 调用一个工具，返回它产出的文本。
     *
     * <p>MCP 的工具结果是一组 content 块（可以是 text / image / resource）。
     * 我们只取文本 —— 编解码工作不需要图片。
     */
    public String callTool(String name, JsonNode arguments) throws IOException {
        ObjectNode params = json.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null ? json.createObjectNode() : arguments);

        JsonNode result = request("tools/call", params);

        StringBuilder sb = new StringBuilder();
        for (JsonNode block : result.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(block.path("text").asText(""));
            }
        }
        if (sb.isEmpty()) {
            return "（MCP 工具没有返回文本内容）";
        }
        // ⚠️ 工具自己报告的失败也在 result 里，靠 isError 标记
        if (result.path("isError").asBoolean(false)) {
            return "MCP 工具报告错误：" + sb;
        }
        return sb.toString();
    }

    // ───────────────────────────────────────────────────────────

    private JsonNode request(String method, JsonNode params) throws IOException {
        synchronized (lock) {
            int id = nextId.getAndIncrement();
            ObjectNode msg = json.createObjectNode();
            msg.put("jsonrpc", "2.0");
            msg.put("id", id);
            msg.put("method", method);
            msg.set("params", params);
            writeLine(msg);

            // 一直读到 id 对上的那条。中间可能有服务器发来的通知（没有 id），跳过。
            String line;
            while ((line = fromServer.readLine()) != null) {
                JsonNode frame;
                try {
                    frame = json.readTree(line);
                } catch (Exception e) {
                    continue;
                }
                if (frame.path("id").asInt(-1) != id) {
                    continue;   // 不是给我们的（通知，或者别的请求的响应）
                }
                if (frame.has("error")) {
                    throw new IOException("MCP 错误：" + frame.path("error").path("message").asText()
                            + "（" + frame.path("error").toString() + "）");
                }
                return frame.path("result");
            }
            throw new IOException("MCP 服务器关闭了连接（method=" + method + "）");
        }
    }

    private void notify(String method, JsonNode params) throws IOException {
        synchronized (lock) {
            ObjectNode msg = json.createObjectNode();
            msg.put("jsonrpc", "2.0");
            msg.put("method", method);
            msg.set("params", params);
            writeLine(msg);
        }
    }

    private void writeLine(JsonNode msg) throws IOException {
        toServer.write(msg.toString());
        toServer.write('\n');
        toServer.flush();
    }

    @Override
    public void close() {
        try {
            toServer.close();
        } catch (IOException ignored) {
            // 关闭失败没什么可做的
        }
        process.destroy();
    }
}
