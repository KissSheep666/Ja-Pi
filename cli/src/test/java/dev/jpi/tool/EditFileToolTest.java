package dev.jpi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jpi.permission.PermissionManager;
import dev.jpi.tool.impl.EditFileTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditFileToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    private EditFileTool tool;
    private ToolContext ctx;
    private Path file;

    @BeforeEach
    void setUp() throws Exception {
        tool = new EditFileTool();
        ctx = new ToolContext(temp, PermissionManager.allowAll());
        file = temp.resolve("a.txt");
        Files.writeString(file, "one\ntwo\none\n");
    }

    @Test
    void replacesUniqueOccurrence() throws Exception {
        String result = tool.execute(args("two", "2", false), ctx);
        assertTrue(result.contains("已修改"));
        assertEquals("one\n2\none\n", Files.readString(file));
    }

    @Test
    void refusesAmbiguousReplacement() throws Exception {
        String result = tool.execute(args("one", "1", false), ctx);
        assertTrue(result.contains("出现了 2 次"));
        assertEquals("one\ntwo\none\n", Files.readString(file));
    }

    @Test
    void replaceAllReplacesEveryOccurrence() throws Exception {
        String result = tool.execute(args("one", "1", true), ctx);
        assertTrue(result.contains("替换 2 处"));
        assertEquals("1\ntwo\n1\n", Files.readString(file));
    }

    @Test
    void emptyOldStringIsRejected() throws Exception {
        String result = tool.execute(args("", "x", false), ctx);
        assertTrue(result.contains("old_string 不能为空"));
        assertEquals("one\ntwo\none\n", Files.readString(file));
    }

    @Test
    void missingFilePointsToWriteFile() throws Exception {
        JsonNode args = JSON.createObjectNode()
                .put("path", "missing.txt")
                .put("old_string", "x")
                .put("new_string", "y");
        String result = tool.execute(args, ctx);
        assertTrue(result.contains("文件不存在"));
        assertTrue(result.contains("write_file"));
    }

    private static JsonNode args(String oldString, String newString, boolean replaceAll) {
        return JSON.createObjectNode()
                .put("path", "a.txt")
                .put("old_string", oldString)
                .put("new_string", newString)
                .put("replace_all", replaceAll);
    }
}
