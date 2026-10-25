package dev.jpi.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliOptionsTest {

    @TempDir
    Path temp;

    @Test
    void parsesLongAndShortForms() {
        CliOptions options = CliOptions.parse(new String[]{
                "--provider", "anthropic",
                "--model=x-model",
                "--think",
                "--budget=5000",
                "--keep", "4",
                "--mcp=demo",
                "--mcp", "cmd /c server",
                "--yes",
                "--raw",
                "--no-color",
                "--cwd=" + temp
        });

        assertEquals("anthropic", options.provider());
        assertEquals("x-model", options.model());
        assertTrue(options.thinking());
        assertEquals(5000, options.budget());
        assertEquals(4, options.keep());
        assertEquals(2, options.mcpCommands().size());
        assertTrue(options.autoApprove());
        assertTrue(options.dumpRaw());
        assertFalse(options.color());
        assertEquals(temp.toAbsolutePath().normalize(), options.cwd());
        assertFalse(options.help());
        assertFalse(options.version());
    }

    @Test
    void rejectsUnknownFlag() {
        assertThrows(IllegalArgumentException.class,
                () -> CliOptions.parse(new String[]{"--nope"}));
    }

    @Test
    void rejectsUnknownProvider() {
        assertThrows(IllegalArgumentException.class,
                () -> CliOptions.parse(new String[]{"--provider=gemini"}));
    }

    @Test
    void rejectsMissingDirectory() {
        assertThrows(IllegalArgumentException.class,
                () -> CliOptions.parse(new String[]{"--cwd=" + temp.resolve("missing")}));
    }

    @Test
    void helpAndVersionFlags() {
        assertTrue(CliOptions.parse(new String[]{"--help"}).help());
        assertTrue(CliOptions.parse(new String[]{"-V"}).version());
    }
}
