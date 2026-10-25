package dev.jpi.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.jpi.tool.Tool;
import dev.jpi.tool.ToolContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionManagerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temp;

    private ToolContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new ToolContext(temp, PermissionManager.allowAll());
    }

    @Test
    void readOnlyToolNeverAsks() {
        AtomicInteger asked = new AtomicInteger();
        PermissionManager manager = new PermissionManager((tool, action, risk) -> {
            asked.incrementAndGet();
            return Approval.once();
        });

        Approval approval = manager.check(tool("read_file", false, "read_file"), args(), ctx);

        assertInstanceOf(Approval.Allowed.class, approval);
        assertEquals(0, asked.get());
    }

    @Test
    void alwaysRemembersMediumRiskScope() {
        AtomicInteger asked = new AtomicInteger();
        PermissionManager manager = new PermissionManager((tool, action, risk) -> {
            asked.incrementAndGet();
            return Approval.always();
        });
        Tool tool = tool("edit_file", true, "edit_file:a.txt");

        manager.check(tool, args(), ctx);
        manager.check(tool, args(), ctx);

        assertEquals(1, asked.get());
        assertTrue(manager.grantedScopes().contains("edit_file:a.txt"));
    }

    @Test
    void highRiskIsNeverRemembered() {
        AtomicInteger asked = new AtomicInteger();
        PermissionManager manager = new PermissionManager((tool, action, risk) -> {
            asked.incrementAndGet();
            return Approval.always();
        });
        Tool tool = tool("run_bash", true, "run_bash:rm");
        JsonNode dangerous = JSON.createObjectNode().put("command", "rm -rf /");

        manager.check(tool, dangerous, ctx);
        manager.check(tool, dangerous, ctx);

        assertEquals(2, asked.get());
        assertTrue(manager.grantedScopes().isEmpty());
    }

    @Test
    void deniedCarriesReason() {
        PermissionManager manager = new PermissionManager(
                (tool, action, risk) -> Approval.deny("不要动这个文件"));
        Tool tool = tool("edit_file", true, "edit_file:a.txt");

        Approval approval = manager.check(tool, args(), ctx);

        Approval.Denied denied = assertInstanceOf(Approval.Denied.class, approval);
        assertEquals("不要动这个文件", denied.reason());
    }

    @Test
    void resetClearsRememberedScopes() {
        PermissionManager manager = new PermissionManager(
                (tool, action, risk) -> Approval.always());
        Tool tool = tool("edit_file", true, "edit_file:a.txt");
        manager.check(tool, args(), ctx);
        manager.resetSession();
        assertTrue(manager.grantedScopes().isEmpty());
    }

    private static JsonNode args() {
        return JSON.createObjectNode().put("path", "a.txt");
    }

    private static Tool tool(String name, boolean requiresApproval, String scope) {
        return new Tool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public JsonNode parametersSchema() {
                return JSON.createObjectNode().put("type", "object");
            }

            @Override
            public String execute(JsonNode args, ToolContext ctx) {
                return "ok";
            }

            @Override
            public boolean requiresApproval() {
                return requiresApproval;
            }

            @Override
            public String permissionScope(JsonNode args, ToolContext ctx) {
                return scope;
            }
        };
    }
}
