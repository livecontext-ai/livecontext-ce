package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for SshNode.
 * Tests focus on validation paths (null config, missing required fields)
 * since real SSH connections cannot be tested in unit tests.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SshNode")
class SshNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-0",
            0,
            Map.of(),
            mockPlan
        );
    }

    @Nested
    @DisplayName("execute - validation")
    class ExecuteValidation {

        @Test
        @DisplayName("should return failure when config is null")
        void execute_withNullConfig_returnsFailure() {
            SshNode node = new SshNode("core:ssh", null);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().isPresent());
            assertTrue(result.errorMessage().get().contains("SSH configuration is required"));
            assertEquals("SSH", result.output().get("node_type"));
        }

        @Test
        @DisplayName("should return failure when host is missing")
        void execute_withMissingHost_returnsFailure() {
            Core.SshConfig config = new Core.SshConfig(
                null, null, "user", "password", "pass123", null, "ls -la", null, null
            );
            SshNode node = new SshNode("core:ssh", config);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().isPresent());
            assertTrue(result.errorMessage().get().contains("'host' is required"));
        }

        @Test
        @DisplayName("should return failure when command is missing")
        void execute_withMissingCommand_returnsFailure() {
            Core.SshConfig config = new Core.SshConfig(
                "myhost.example.com", null, "user", "password", "pass123", null, null, null, null
            );
            SshNode node = new SshNode("core:ssh", config);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().isPresent());
            assertTrue(result.errorMessage().get().contains("'command' is required"));
        }

        @Test
        @DisplayName("should return failure when host is blank")
        void execute_withBlankHost_returnsFailure() {
            Core.SshConfig config = new Core.SshConfig(
                "   ", null, "user", "password", "pass123", null, "ls", null, null
            );
            SshNode node = new SshNode("core:ssh", config);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().get().contains("'host' is required"));
        }

        @Test
        @DisplayName("should return failure when command is blank")
        void execute_withBlankCommand_returnsFailure() {
            Core.SshConfig config = new Core.SshConfig(
                "myhost.example.com", null, "user", "password", "pass123", null, "   ", null, null
            );
            SshNode node = new SshNode("core:ssh", config);

            NodeExecutionResult result = node.execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().get().contains("'command' is required"));
        }
    }

    @Nested
    @DisplayName("Builder")
    class BuilderTests {

        @Test
        @DisplayName("should build SshNode with builder pattern")
        void builder_pattern_works() {
            Core.SshConfig config = new Core.SshConfig(
                "host.example.com", 2222, "admin", "password", "secret", null, "uptime", 60000, null
            );

            SshNode node = SshNode.builder()
                .nodeId("core:my_ssh")
                .sshConfig(config)
                .build();

            assertEquals("core:my_ssh", node.getNodeId());
            assertEquals(NodeType.SSH, node.getType());
            assertNotNull(node.getConfig());
            assertEquals("host.example.com", node.getConfig().host());
            assertEquals(2222, node.getConfig().port());
            assertEquals("uptime", node.getConfig().command());
        }
    }
}
