package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests for WorkflowBuilderController.
 * Verifies that session info includes full plan and context.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowBuilderControllerTest {

    @Mock
    private WorkflowBuilderSessionStore sessionStore;

    @Mock
    private NodeLibraryService nodeLibraryService;

    private WorkflowBuilderController controller;

    @BeforeEach
    void setUp() {
        controller = new WorkflowBuilderController(sessionStore, nodeLibraryService);
    }

    @Nested
    @DisplayName("getActiveSessions")
    class GetActiveSessionsTests {

        @Test
        @DisplayName("Returns hasActiveSession=false when no active session")
        void returnsNoActiveSessionWhenEmpty() {
            when(sessionStore.getSessionForConversation("tenant-1", "conv-123"))
                .thenReturn(Optional.empty());

            ResponseEntity<Map<String, Object>> response = controller.getActiveSessions("tenant-1", "conv-123");

            assertThat(response.getBody()).containsEntry("hasActiveSession", false);
        }

        @Test
        @DisplayName("Returns session info with full plan")
        @SuppressWarnings("unchecked")
        void returnsSessionInfoWithPlan() {
            WorkflowBuilderSession session = createTestSession();
            when(sessionStore.getSessionForConversation("tenant-1", "conv-123"))
                .thenReturn(Optional.of(session));
            when(nodeLibraryService.getNodeTypesMap())
                .thenReturn(Map.of("triggers", List.of("webhook", "schedule")));

            ResponseEntity<Map<String, Object>> response = controller.getActiveSessions("tenant-1", "conv-123");

            assertThat(response.getBody()).containsEntry("hasActiveSession", true);

            Map<String, Object> sessionInfo = (Map<String, Object>) response.getBody().get("session");
            assertThat(sessionInfo).isNotNull();
            assertThat(sessionInfo).containsEntry("sessionId", "wb_test123");
            assertThat(sessionInfo).containsEntry("name", "Test Workflow");
            assertThat(sessionInfo).containsEntry("description", "A test workflow");

            // Verify plan is included
            Map<String, Object> plan = (Map<String, Object>) sessionInfo.get("plan");
            assertThat(plan).isNotNull();
            assertThat(plan).containsKeys("triggers", "mcps", "cores", "edges", "interfaces", "tables");

            // Verify triggers
            List<Map<String, Object>> triggers = (List<Map<String, Object>>) plan.get("triggers");
            assertThat(triggers).hasSize(1);
            assertThat(triggers.get(0)).containsEntry("label", "My Webhook");
        }

        @Test
        @DisplayName("Returns session info with context (rules, variable_syntax, actions)")
        @SuppressWarnings("unchecked")
        void returnsSessionInfoWithContext() {
            WorkflowBuilderSession session = createTestSession();
            when(sessionStore.getSessionForConversation("tenant-1", "conv-123"))
                .thenReturn(Optional.of(session));
            when(nodeLibraryService.getNodeTypesMap())
                .thenReturn(Map.of("triggers", List.of("webhook")));

            ResponseEntity<Map<String, Object>> response = controller.getActiveSessions("tenant-1", "conv-123");

            Map<String, Object> sessionInfo = (Map<String, Object>) response.getBody().get("session");
            Map<String, Object> context = (Map<String, Object>) sessionInfo.get("context");

            assertThat(context).isNotNull();
            assertThat(context).containsKeys("rules", "variable_syntax", "available_node_types_by_category", "actions", "phase", "NEXT", "help");

            // Verify rules (should not include "trigger first" since session has trigger)
            Map<String, Object> rules = (Map<String, Object>) context.get("rules");
            assertThat(rules).isNotNull();
            assertThat(rules.values()).noneMatch(v -> v.toString().contains("Trigger must be created FIRST"));

            // Verify variable_syntax
            Map<String, String> variableSyntax = (Map<String, String>) context.get("variable_syntax");
            assertThat(variableSyntax).containsEntry("trigger", "{{trigger:label.output.field}}");
            assertThat(variableSyntax).containsEntry("mcp", "{{mcp:label.output.field}}");

            // Verify phase
            assertThat(context.get("phase")).isNotNull();
        }

        @Test
        @DisplayName("Includes trigger_first rule when session has no trigger")
        @SuppressWarnings("unchecked")
        void includesTriggerFirstRuleWhenNoTrigger() {
            WorkflowBuilderSession session = createEmptySession();
            when(sessionStore.getSessionForConversation("tenant-1", "conv-123"))
                .thenReturn(Optional.of(session));
            when(nodeLibraryService.getNodeTypesMap())
                .thenReturn(Map.of("triggers", List.of("webhook")));

            ResponseEntity<Map<String, Object>> response = controller.getActiveSessions("tenant-1", "conv-123");

            Map<String, Object> sessionInfo = (Map<String, Object>) response.getBody().get("session");
            Map<String, Object> context = (Map<String, Object>) sessionInfo.get("context");
            Map<String, Object> rules = (Map<String, Object>) context.get("rules");

            // Should include "trigger first" rule since session is empty
            assertThat(rules.values().stream().anyMatch(v -> v.toString().contains("Trigger must be created FIRST")))
                .isTrue();
        }

        @Test
        @DisplayName("NEXT guidance suggests trigger when session is empty")
        @SuppressWarnings("unchecked")
        void nextGuidanceSuggestsTriggerWhenEmpty() {
            WorkflowBuilderSession session = createEmptySession();
            when(sessionStore.getSessionForConversation("tenant-1", "conv-123"))
                .thenReturn(Optional.of(session));
            when(nodeLibraryService.getNodeTypesMap())
                .thenReturn(Map.of("triggers", List.of("webhook")));

            ResponseEntity<Map<String, Object>> response = controller.getActiveSessions("tenant-1", "conv-123");

            Map<String, Object> sessionInfo = (Map<String, Object>) response.getBody().get("session");
            Map<String, Object> context = (Map<String, Object>) sessionInfo.get("context");

            String next = (String) context.get("NEXT");
            assertThat(next).contains("trigger");
            assertThat(next).contains("MUST be created first");
        }

        @Test
        @DisplayName("NEXT guidance suggests finish when session is ready (has trigger, nodes, and edges)")
        @SuppressWarnings("unchecked")
        void nextGuidanceSuggestsFinishWhenReady() {
            // Session with trigger + mcp + edge = READY phase
            WorkflowBuilderSession session = createTestSession();
            when(sessionStore.getSessionForConversation("tenant-1", "conv-123"))
                .thenReturn(Optional.of(session));
            when(nodeLibraryService.getNodeTypesMap())
                .thenReturn(Map.of("triggers", List.of("webhook")));

            ResponseEntity<Map<String, Object>> response = controller.getActiveSessions("tenant-1", "conv-123");

            Map<String, Object> sessionInfo = (Map<String, Object>) response.getBody().get("session");
            Map<String, Object> context = (Map<String, Object>) sessionInfo.get("context");

            String next = (String) context.get("NEXT");
            assertThat(next).contains("finish");
            assertThat(context.get("phase")).isEqualTo("READY");
        }

        @Test
        @DisplayName("NEXT guidance suggests connect_after when in BUILDING phase")
        @SuppressWarnings("unchecked")
        void nextGuidanceSuggestsConnectAfterWhenBuilding() {
            // Session with trigger only = BUILDING phase
            WorkflowBuilderSession session = createSessionInBuildingPhase();
            when(sessionStore.getSessionForConversation("tenant-1", "conv-123"))
                .thenReturn(Optional.of(session));
            when(nodeLibraryService.getNodeTypesMap())
                .thenReturn(Map.of("triggers", List.of("webhook")));

            ResponseEntity<Map<String, Object>> response = controller.getActiveSessions("tenant-1", "conv-123");

            Map<String, Object> sessionInfo = (Map<String, Object>) response.getBody().get("session");
            Map<String, Object> context = (Map<String, Object>) sessionInfo.get("context");

            String next = (String) context.get("NEXT");
            assertThat(next).contains("connect_after");
            assertThat(context.get("phase")).isEqualTo("BUILDING");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════════

    private WorkflowBuilderSession createTestSession() {
        WorkflowBuilderSession session = WorkflowBuilderSession.builder()
            .sessionId("wb_test123")
            .tenantId("tenant-1")
            .conversationId("conv-123")
            .workflowName("Test Workflow")
            .workflowDescription("A test workflow")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

        // Add a trigger
        session.getTriggers().add(Map.of(
            "label", "My Webhook",
            "type", "webhook"
        ));

        // Add an mcp
        session.getMcps().add(Map.of(
            "label", "Fetch Data",
            "id", "tool-uuid-123"
        ));

        // Add an edge
        session.getEdges().add(Map.of(
            "from", "trigger:my_webhook",
            "to", "mcp:fetch_data"
        ));

        // Set last added node
        session.setLastAddedNodeId("mcp:fetch_data");

        return session;
    }

    private WorkflowBuilderSession createEmptySession() {
        return WorkflowBuilderSession.builder()
            .sessionId("wb_empty")
            .tenantId("tenant-1")
            .conversationId("conv-123")
            .workflowName("Empty Workflow")
            .workflowDescription("An empty workflow")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    }

    private WorkflowBuilderSession createSessionInBuildingPhase() {
        WorkflowBuilderSession session = WorkflowBuilderSession.builder()
            .sessionId("wb_building")
            .tenantId("tenant-1")
            .conversationId("conv-123")
            .workflowName("Building Workflow")
            .workflowDescription("A workflow in building phase")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

        // Add a trigger only (no mcps yet = BUILDING phase)
        session.getTriggers().add(Map.of(
            "label", "My Webhook",
            "type", "webhook"
        ));

        // Set last added node
        session.setLastAddedNodeId("trigger:my_webhook");

        return session;
    }
}
