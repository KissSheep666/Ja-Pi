package dev.jpi.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class McpClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void startsDemoServerListsToolsAndCallsOne() throws Exception {
        String javaBin = ProcessHandle.current().info().command().orElse("java");
        String classpath = System.getProperty("java.class.path");

        try (McpClient client = McpClient.start(List.of(
                javaBin, "-cp", classpath, "dev.jpi.mcp.demo.DemoMcpServer"))) {

            assertEquals("demo-mcp", client.serverName());

            List<JsonNode> tools = client.listTools();
            assertEquals(2, tools.size());
            assertTrue(tools.stream().anyMatch(t -> "word_count".equals(t.path("name").asText())));

            String result = client.callTool("word_count", JSON.readTree("{\"text\":\"hello world\"}"));
            assertTrue(result.contains("字符数：11"));
            assertTrue(result.contains("按空格分词：2"));
        }
    }

    @Test
    void splitsCommandLineWithQuotes() {
        assertEquals(List.of("npx", "-y", "@scope/server", "/tmp/my dir"),
                McpManager.split("npx -y \"@scope/server\" '/tmp/my dir'"));
        assertEquals(List.of("cmd", "/c", "a b"), McpManager.split("cmd /c \"a b\""));
        assertEquals(List.of(), McpManager.split("   "));
    }

    @Test
    void wrapsWindowsPackageRunners() {
        List<String> adapted = McpManager.adaptForWindows(List.of("npx", "-y", "pkg"));
        if (System.getProperty("os.name").toLowerCase().contains("win")) {
            assertEquals(List.of("cmd.exe", "/c", "npx", "-y", "pkg"), adapted);
        } else {
            assertEquals(List.of("npx", "-y", "pkg"), adapted);
        }
    }
}
