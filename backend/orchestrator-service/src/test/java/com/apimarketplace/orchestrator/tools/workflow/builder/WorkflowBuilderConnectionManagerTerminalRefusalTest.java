package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the prod report 2026-05-14: the agent connected an {@code exit}
 * (terminal type) as the source of an edge to a {@code merge} downstream, and
 * the workflow tool accepted it. Terminal nodes have no successors by
 * definition; allowing the edge either dead-ends silently or strands the merge
 * waiting forever for a predecessor that cannot fire. The fix refuses the
 * connection upfront in {@link WorkflowBuilderConnectionManager#executeConnect}.
 *
 * <p>Coverage: each of the three terminal types (exit, end, stop_on_error)
 * gets a refusal test, plus a non-terminal control test to make sure we
 * didn't over-block normal connections.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderConnectionManager - terminal-node outgoing edge refusal")
class WorkflowBuilderConnectionManagerTerminalRefusalTest {

    @Mock
    private WorkflowBuilderSessionStore sessionStore;

    private WorkflowBuilderConnectionManager connectionManager;

    @BeforeEach
    void setUp() {
        connectionManager = new WorkflowBuilderConnectionManager(sessionStore);
    }

    private WorkflowBuilderSession createSession() {
        return WorkflowBuilderSession.builder()
                .sessionId("test")
                .tenantId("test-tenant")
                .workflowName("Test Workflow")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private void addCore(WorkflowBuilderSession session, String id, String type, String label) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", id);
        node.put("type", type);
        node.put("label", label);
        session.getCores().add(node);
    }

    @Test
    @DisplayName("Refuses connect FROM an exit node - terminal types end a branch")
    void refusesConnectFromExitNode() {
        WorkflowBuilderSession session = createSession();
        addCore(session, "core:no_triggered_stock", "exit", "no triggered stock");
        addCore(session, "core:rejoin", "merge", "Rejoin");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("from", "no triggered stock");
        args.put("to", "Rejoin");

        ToolExecutionResult result = connectionManager.executeConnect(session, args);

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .contains("terminal")
                .contains("exit")
                .containsIgnoringCase("no outgoing edges");
        // No edge was created
        assertThat(session.getEdges()).isEmpty();
    }

    @Test
    @DisplayName("Refuses connect FROM a stop_on_error node")
    void refusesConnectFromStopOnError() {
        WorkflowBuilderSession session = createSession();
        addCore(session, "core:halt", "stop_on_error", "Halt");
        addCore(session, "core:after", "merge", "After");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("from", "Halt");
        args.put("to", "After");

        ToolExecutionResult result = connectionManager.executeConnect(session, args);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("stop_on_error");
        assertThat(session.getEdges()).isEmpty();
    }

    @Test
    @DisplayName("Refuses connect FROM an end node")
    void refusesConnectFromEndNode() {
        WorkflowBuilderSession session = createSession();
        addCore(session, "core:done", "end", "Done");
        addCore(session, "core:after", "merge", "After");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("from", "Done");
        args.put("to", "After");

        ToolExecutionResult result = connectionManager.executeConnect(session, args);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("end");
        assertThat(session.getEdges()).isEmpty();
    }

    @Test
    @DisplayName("Allows connect FROM a non-terminal node (control: no over-blocking)")
    void allowsConnectFromNonTerminalNode() {
        WorkflowBuilderSession session = createSession();
        // A regular MCP step has no terminal semantic - must connect freely.
        Map<String, Object> mcp = new LinkedHashMap<>();
        mcp.put("id", "mcp:fetch_data");
        mcp.put("type", "mcp");
        mcp.put("label", "Fetch Data");
        session.getMcps().add(mcp);
        addCore(session, "core:rejoin", "merge", "Rejoin");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("from", "Fetch Data");
        args.put("to", "Rejoin");

        ToolExecutionResult result = connectionManager.executeConnect(session, args);

        assertThat(result.success()).isTrue();
        assertThat(session.getEdges()).hasSize(1);
    }

    @Test
    @DisplayName("Allows connect TO a terminal node (terminal as target is fine, only `from` is forbidden)")
    void allowsConnectToTerminalNode() {
        WorkflowBuilderSession session = createSession();
        Map<String, Object> mcp = new LinkedHashMap<>();
        mcp.put("id", "mcp:check");
        mcp.put("type", "mcp");
        mcp.put("label", "Check");
        session.getMcps().add(mcp);
        addCore(session, "core:done", "exit", "Done");

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("from", "Check");
        args.put("to", "Done");

        ToolExecutionResult result = connectionManager.executeConnect(session, args);

        assertThat(result.success())
                .as("connecting TO an exit is the whole point - only outgoing from terminal is forbidden")
                .isTrue();
        assertThat(session.getEdges()).hasSize(1);
    }
}
