package dev.jpi.mcp;

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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 最小但可用的 MCP 客户端（stdio 传输）。
 *
 * <p>MCP 剥掉包装就是换行分隔的 JSON-RPC 2.0：
 * <pre>
 *   你 → 子进程 stdin:  {"jsonrpc":"2.0","id":1,"method":"initialize","params":{...}}\n
 *   你 ← 子进程 stdout: {"jsonrpc":"2.0","id":1,"result":{...}}\n
 * </pre>
 *
 * <p>和参考实现不同，这里支持并发请求：
 * 一个读线程把响应按 id 分发给对应的 CompletableFuture，
 * 写操作串行化，每个请求都有超时。工具是并发执行的，这一点很重要。
 *
 * <p>三条规矩：stdout 只走协议；必须先 initialize；id 必须唯一。
 */
public final class McpClient implements AutoCloseable {

    private static final String PROTOCOL_VERSION = "2024-11-05";
    private static final long INIT_TIMEOUT_MS = 20_000;
    private static final long LIST_TIMEOUT_MS = 20_000;
    private static final long CALL_TIMEOUT_MS = 120_000;

    private final Process process;
    private final BufferedWriter toServer;
    private final BufferedReader fromServer;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private final Thread readerThread;

    private volatile String serverName = "unknown";
    private volatile boolean closed;

    private McpClient(Process process) throws IOException {
        this.process = process;
        this.toServer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        this.fromServer = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        this.readerThread = Thread.ofVirtual()
                .name("mcp-reader-" + process.pid())
                .start(this::readLoop);
    }

    public static McpClient start(List<String> command) throws IOException {
        if (command == null || command.isEmpty()) {
            throw new IOException("MCP 命令不能为空");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        // stderr 单独走终端；混进 stdout 会破坏协议
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);

        McpClient client = new McpClient(builder.start());
        try {
            client.initialize();
            return client;
        } catch (IOException e) {
            client.close();
            throw e;
        }
    }

    public String serverName() {
        return serverName;
    }

    public JsonNode initialize() throws IOException {
        ObjectNode params = json.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", json.createObjectNode());
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "jpi");
        clientInfo.put("version", "0.1.0");

        JsonNode result = request("initialize", params, INIT_TIMEOUT_MS);

        String reported = result.path("serverInfo").path("name").asText("");
        if (!reported.isBlank()) {
            this.serverName = reported;
        }

        // 握手后的通知：没有 id，服务器不会回
        notify("notifications/initialized", json.createObjectNode());
        return result;
    }

    /** 列出全部工具，自动跟随 nextCursor 分页。 */
    public List<JsonNode> listTools() throws IOException {
        List<JsonNode> tools = new ArrayList<>();
        String cursor = null;
        do {
            ObjectNode params = json.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = request("tools/list", params, LIST_TIMEOUT_MS);
            result.path("tools").forEach(tools::add);
            JsonNode next = result.path("nextCursor");
            cursor = next.isTextual() ? next.asText() : null;
        } while (cursor != null && !cursor.isBlank());
        return tools;
    }

    /** 调用工具，返回文本内容。 */
    public String callTool(String name, JsonNode arguments) throws IOException {
        ObjectNode params = json.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null ? json.createObjectNode() : arguments);

        JsonNode result = request("tools/call", params, CALL_TIMEOUT_MS);

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
        if (result.path("isError").asBoolean(false)) {
            return "MCP 工具报告错误：" + sb;
        }
        return sb.toString();
    }

    // ───────────────────────────────────────────────────────────

    private void readLoop() {
        try (BufferedReader reader = fromServer) {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                JsonNode frame;
                try {
                    frame = json.readTree(line);
                } catch (Exception e) {
                    // 服务器往 stdout 打了日志，或者有不认识的帧
                    continue;
                }

                JsonNode idNode = frame.path("id");
                if (!idNode.isInt() && !idNode.isLong() && !idNode.isTextual()) {
                    continue;   // 通知
                }
                int id = idNode.asInt(Integer.MIN_VALUE);
                CompletableFuture<JsonNode> future = pending.remove(id);
                if (future == null) {
                    continue;
                }
                if (frame.has("error")) {
                    future.completeExceptionally(new IOException(
                            "MCP 错误：" + frame.path("error").path("message").asText()
                                    + "（" + frame.path("error") + "）"));
                } else {
                    future.complete(frame.path("result"));
                }
            }
        } catch (IOException e) {
            failAll(new IOException("MCP 读取失败：" + e.getMessage(), e));
        } finally {
            failAll(new IOException("MCP 服务器关闭了连接"));
        }
    }

    private JsonNode request(String method, JsonNode params, long timeoutMs) throws IOException {
        if (closed) {
            throw new IOException("MCP 客户端已关闭");
        }

        int id = nextId.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);

        ObjectNode message = json.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("id", id);
        message.put("method", method);
        message.set("params", params);
        writeLine(message);

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IOException("MCP 请求超时（" + method + "，" + timeoutMs + "ms）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("MCP 请求被中断（" + method + "）", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new IOException(cause == null ? "MCP 请求失败" : cause.getMessage(), cause);
        } finally {
            pending.remove(id);
        }
    }

    private void notify(String method, JsonNode params) throws IOException {
        ObjectNode message = json.createObjectNode();
        message.put("jsonrpc", "2.0");
        message.put("method", method);
        message.set("params", params);
        writeLine(message);
    }

    private void writeLine(JsonNode message) throws IOException {
        synchronized (writeLock) {
            if (closed) {
                throw new IOException("MCP 客户端已关闭");
            }
            toServer.write(message.toString());
            toServer.write('\n');
            toServer.flush();
        }
    }

    private void failAll(IOException error) {
        for (Integer id : List.copyOf(pending.keySet())) {
            CompletableFuture<JsonNode> future = pending.remove(id);
            if (future != null) {
                future.completeExceptionally(error);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        failAll(new IOException("MCP 客户端已关闭"));
        try {
            toServer.close();
        } catch (IOException ignored) {
            // 关闭失败没有补救动作
        }
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
        readerThread.interrupt();
    }
}
