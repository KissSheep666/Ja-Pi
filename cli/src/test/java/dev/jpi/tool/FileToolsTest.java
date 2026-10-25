package dev.jpi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jpi.permission.PermissionManager;
import dev.jpi.tool.impl.GlobTool;
import dev.jpi.tool.impl.GrepTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** glob / grep 的顶层文件匹配回归测试。 */
class FileToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    private ToolContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        ctx = new ToolContext(temp, PermissionManager.allowAll());
        Files.createDirectories(temp.resolve("src/sub"));
        Files.createDirectories(temp.resolve("target"));
        Files.writeString(temp.resolve("hello.txt"), "hi there\n");
        Files.writeString(temp.resolve("src/A.java"), "class A { // TODO }\n");
        Files.writeString(temp.resolve("src/sub/B.java"), "class B { // hi }\n");
        Files.writeString(temp.resolve("src/root.txt"), "hi from src\n");
        Files.writeString(temp.resolve("target/ignored.txt"), "hi but ignored\n");
    }

    @Test
    void doubleStarMatchesTopLevelFiles() throws Exception {
        String result = new GlobTool().execute(globArgs("**/*.txt"), ctx);

        assertTrue(result.contains("hello.txt"), result);
        assertTrue(result.contains("src/root.txt"), result);
        assertFalse(result.contains("ignored.txt"), result);
        assertFalse(result.contains(".java"), result);
    }

    @Test
    void doubleStarMatchesNestedFiles() throws Exception {
        String result = new GlobTool().execute(globArgs("**/*.java"), ctx);

        assertTrue(result.contains("src/A.java"), result);
        assertTrue(result.contains("src/sub/B.java"), result);
    }

    @Test
    void srcDoubleStarMatchesFilesDirectlyUnderSrc() throws Exception {
        String result = new GlobTool().execute(globArgs("src/**/*.java"), ctx);

        assertTrue(result.contains("src/A.java"), result);
        assertTrue(result.contains("src/sub/B.java"), result);
    }

    @Test
    void barePatternMatchesByFilenameRecursively() throws Exception {
        String result = new GlobTool().execute(globArgs("*.txt"), ctx);

        assertTrue(result.contains("hello.txt"), result);
        assertTrue(result.contains("src/root.txt"), result);
    }

    @Test
    void grepUsesGlobAndFindsTopLevelFile() throws Exception {
        JsonNode args = JSON.createObjectNode()
                .put("pattern", "hi")
                .put("glob", "**/*.txt");

        String result = new GrepTool().execute(args, ctx);

        assertTrue(result.contains("hello.txt:1:"), result);
        assertTrue(result.contains("src/root.txt:1:"), result);
        assertFalse(result.contains("ignored.txt"), result);
    }

    @Test
    void grepDefaultGlobIncludesTopLevelFiles() throws Exception {
        JsonNode args = JSON.createObjectNode().put("pattern", "TODO");

        String result = new GrepTool().execute(args, ctx);

        assertTrue(result.contains("src/A.java:1:"), result);
    }

    private static JsonNode globArgs(String pattern) {
        return JSON.createObjectNode().put("pattern", pattern);
    }
}
