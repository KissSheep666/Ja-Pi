package dev.jpi.mcp.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 一个最小的 MCP 服务器，用于测试客户端和演示协议。
 *
 * <p>它提供两个工具：
 * <ul>
 *   <li>{@code current_time} —— 返回服务器当前时间</li>
 *   <li>{@code word_count} —— 统计一段文本的字符数/行数</li>
 * </ul>
 *
 * <p>⚠️ 所有输出都走 stdout 的协议通道，调试信息一律走 stderr。
 */
public final class DemoMcpServer {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        // Windows 下 System.err 默认用本地代码页；日志统一成 UTF-8，避免乱码
        System.setErr(new PrintStream(
                new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));

        BufferedReader in = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8));

        System.err.println("[demo-mcp] 启动，等待握手……");

        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode message;
            try {
                message = JSON.readTree(line);
            } catch (Exception e) {
                System.err.println("[demo-mcp] 收到无法解析的内容：" + line);
                continue;
            }

            String method = message.path("method").asText("");
            boolean notification = !message.has("id");

            switch (method) {
                case "initialize" -> respond(out, message, initializeResult());
                case "notifications/initialized" -> System.err.println("[demo-mcp] 客户端握手完成");
                case "ping" -> respond(out, message, JSON.createObjectNode());
                case "tools/list" -> respond(out, message, toolsListResult());
                case "tools/call" -> respond(out, message, callTool(message.path("params")));
                default -> {
                    if (!notification) {
                        respondError(out, message, -32601, "不支持的方法：" + method);
                    }
                }
            }
        }
        System.err.println("[demo-mcp] stdin 关闭，退出");
    }

    private static JsonNode initializeResult() {
        ObjectNode result = JSON.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        result.putObject("capabilities").putObject("tools").put("listChanged", false);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "demo-mcp");
        serverInfo.put("version", "0.1.0");
        return result;
    }

    private static JsonNode toolsListResult() {
        ObjectNode result = JSON.createObjectNode();
        ArrayNode tools = result.putArray("tools");

        ObjectNode time = tools.addObject();
        time.put("name", "current_time");
        time.put("description", "返回服务器当前的日期和时间。用户问'现在几点''今天几号'时使用。");
        time.putObject("inputSchema").put("type", "object").putObject("properties");

        ObjectNode wordCount = tools.addObject();
        wordCount.put("name", "word_count");
        wordCount.put("description", "统计一段文本的字符数和行数。");
        ObjectNode schema = wordCount.putObject("inputSchema");
        schema.put("type", "object");
        schema.putObject("properties")
                .putObject("text").put("type", "string").put("description", "要统计的文本");
        schema.putArray("required").add("text");

        return result;
    }

    private static JsonNode callTool(JsonNode params) {
        String name = params.path("name").asText("");
        JsonNode arguments = params.path("arguments");

        String text;
        boolean isError = false;

        switch (name) {
            case "current_time" -> text = ZonedDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"));

            case "word_count" -> {
                String input = arguments.path("text").asText("");
                if (input.isEmpty()) {
                    text = "错误：text 参数不能为空";
                    isError = true;
                } else {
                    text = "字符数：" + input.length()
                            + "，行数：" + input.lines().count()
                            + "，按空格分词：" + input.strip().split("\\s+").length + " 个";
                }
            }

            default -> {
                text = "没有名为 " + name + " 的工具";
                isError = true;
            }
        }

        ObjectNode result = JSON.createObjectNode();
        result.putArray("content").addObject().put("type", "text").put("text", text);
        result.put("isError", isError);
        return result;
    }

    private static void respond(BufferedWriter out, JsonNode request, JsonNode result)
            throws Exception {
        ObjectNode response = JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", request.path("id"));
        response.set("result", result);
        write(out, response);
    }

    private static void respondError(BufferedWriter out, JsonNode request, int code, String message)
            throws Exception {
        ObjectNode response = JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", request.path("id"));
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        write(out, response);
    }

    private static void write(BufferedWriter out, JsonNode message) throws Exception {
        out.write(message.toString());
        out.write('\n');
        out.flush();
    }
}
