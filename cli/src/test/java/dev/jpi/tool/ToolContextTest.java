package dev.jpi.tool;

import dev.jpi.permission.PermissionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ToolContextTest {

    @TempDir
    Path temp;

    @Test
    void resolvesRelativePathInsideRoot() {
        ToolContext ctx = new ToolContext(temp, PermissionManager.allowAll());
        Path resolved = ctx.resolve("src/main/A.java");
        assertEquals(temp.resolve("src/main/A.java").toAbsolutePath().normalize(), resolved);
        assertEquals("src/main/A.java", ctx.display(resolved));
    }

    @Test
    void resolvesExistingFileInsideRoot() throws Exception {
        Files.writeString(temp.resolve("a.txt"), "x");
        ToolContext ctx = new ToolContext(temp, PermissionManager.allowAll());
        assertEquals(temp.resolve("a.txt").toAbsolutePath().normalize(),
                ctx.resolve("a.txt"));
    }

    @Test
    void rejectsParentTraversal() {
        ToolContext ctx = new ToolContext(temp, PermissionManager.allowAll());
        assertThrows(IllegalArgumentException.class, () -> ctx.resolve("../secret.txt"));
        assertThrows(IllegalArgumentException.class, () -> ctx.resolve("src/../../secret.txt"));
    }

    @Test
    void rejectsAbsolutePathOutsideRoot() {
        ToolContext ctx = new ToolContext(temp, PermissionManager.allowAll());
        Path outside = temp.resolveSibling("outside.txt").toAbsolutePath();
        assertThrows(IllegalArgumentException.class, () -> ctx.resolve(outside.toString()));
    }

    @Test
    void rejectsSymlinkEscapingRoot() throws Exception {
        Path outside = Files.createTempDirectory("jpi-outside-");
        Path link = temp.resolve("escape");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (Exception e) {
            assumeTrue(false, "当前环境不支持创建符号链接：" + e.getMessage());
        }

        ToolContext ctx = new ToolContext(temp, PermissionManager.allowAll());
        try {
            assertThrows(IllegalArgumentException.class,
                    () -> ctx.resolve("escape/secret.txt"));
            assertTrue(Files.isSymbolicLink(link));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void rejectsBrokenSymlink() throws Exception {
        Path link = temp.resolve("broken");
        try {
            Files.createSymbolicLink(link, temp.resolve("../does-not-exist"));
        } catch (Exception e) {
            assumeTrue(false, "当前环境不支持创建符号链接：" + e.getMessage());
        }

        ToolContext ctx = new ToolContext(temp, PermissionManager.allowAll());
        try {
            assertThrows(IllegalArgumentException.class, () -> ctx.resolve("broken/file.txt"));
        } finally {
            Files.deleteIfExists(link);
        }
    }
}
