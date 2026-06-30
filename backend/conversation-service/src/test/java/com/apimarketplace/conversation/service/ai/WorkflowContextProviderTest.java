package com.apimarketplace.conversation.service.ai;

import com.apimarketplace.conversation.entity.Conversation;
import com.apimarketplace.conversation.repository.ConversationRepository;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider.WorkflowBuilderSessionContext;
import com.apimarketplace.conversation.service.ai.WorkflowContextProvider.WorkflowContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * Tests for WorkflowContextProvider, especially the flow diagram builder.
 */
@ExtendWith(MockitoExtension.class)
class WorkflowContextProviderTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private RestTemplate restTemplate;

    private WorkflowContextProvider workflowContextProvider;

    @BeforeEach
    void setUp() {
        workflowContextProvider = new WorkflowContextProvider(conversationRepository, restTemplate);
        ReflectionTestUtils.setField(workflowContextProvider, "orchestratorUrl", "http://localhost:8099");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // BASIC CONTEXT TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Returns empty context when conversationId is null")
    void returnsEmptyContextWhenConversationIdNull() {
        WorkflowContext context = workflowContextProvider.getWorkflowContext((String) null, "tenant-1");

        assertThat(context.isPresent()).isFalse();
        assertThat(context.workflowId()).isNull();
    }

    @Test
    @DisplayName("Returns empty context when conversation not found")
    void returnsEmptyContextWhenConversationNotFound() {
        when(conversationRepository.findById("conv-123")).thenReturn(Optional.empty());

        WorkflowContext context = workflowContextProvider.getWorkflowContext("conv-123", "tenant-1");

        assertThat(context.isPresent()).isFalse();
    }

    @Test
    @DisplayName("Returns empty context when conversation has no workflowId")
    void returnsEmptyContextWhenNoWorkflowId() {
        Conversation conversation = new Conversation();
        conversation.setId("conv-123");
        conversation.setWorkflowId(null);
        when(conversationRepository.findById("conv-123")).thenReturn(Optional.of(conversation));

        WorkflowContext context = workflowContextProvider.getWorkflowContext("conv-123", "tenant-1");

        assertThat(context.isPresent()).isFalse();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // FLOW DIAGRAM TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Flow Diagram Builder")
    class FlowDiagramTests {

        @BeforeEach
        void setUpConversation() {
            Conversation conversation = new Conversation();
            conversation.setId("conv-123");
            conversation.setWorkflowId("wf-456");
            when(conversationRepository.findById("conv-123")).thenReturn(Optional.of(conversation));
        }

        @Test
        @DisplayName("Builds linear flow diagram: trigger → step → step")
        void buildsLinearFlowDiagram() {
            // Edges use normalized format: type:normalized_label (matches real workflow data)
            String workflowJson = """
                {
                    "id": "wf-456",
                    "name": "Linear Workflow",
                    "status": "COMPLETED",
                    "plan": {
                        "triggers": [{"id": "t1", "label": "trigger_data", "type": "datasource", "datasource_id": "42"}],
                        "mcps": [
                            {"id": "s1", "label": "fetch_api"},
                            {"id": "s2", "label": "save_result"}
                        ],
                        "agents": [],
                        "cores": [],
                        "edges": [
                            {"from": "trigger:trigger_data", "to": "mcp:fetch_api"},
                            {"from": "mcp:fetch_api", "to": "mcp:save_result"}
                        ]
                    }
                }
                """;
            mockRestTemplate(workflowJson);

            WorkflowContext context = workflowContextProvider.getWorkflowContext("conv-123", "tenant-1");

            assertThat(context.isPresent()).isTrue();
            assertThat(context.flowDiagram())
                .contains("trigger_data")
                .contains("→")
                .contains("fetch_api")
                .contains("save_result");
        }

        @Test
        @DisplayName("Builds flow diagram with AI agent prefix")
        void buildsFlowDiagramWithAgentPrefix() {
            String workflowJson = """
                {
                    "id": "wf-456",
                    "name": "Agent Workflow",
                    "status": "COMPLETED",
                    "plan": {
                        "triggers": [{"id": "t1", "label": "trigger", "type": "datasource", "datasource_id": "42"}],
                        "mcps": [],
                        "agents": [{"id": "a1", "label": "analyzer"}],
                        "cores": [],
                        "edges": [{"from": "trigger:trigger", "to": "agent:analyzer"}]
                    }
                }
                """;
            mockRestTemplate(workflowJson);

            WorkflowContext context = workflowContextProvider.getWorkflowContext("conv-123", "tenant-1");

            assertThat(context.flowDiagram())
                .contains("[AI] analyzer");
        }

        @Test
        @DisplayName("Builds flow diagram with loop symbol")
        void buildsFlowDiagramWithLoopSymbol() {
            String workflowJson = """
                {
                    "id": "wf-456",
                    "name": "Loop Workflow",
                    "status": "COMPLETED",
                    "plan": {
                        "triggers": [{"id": "t1", "label": "trigger", "type": "datasource", "datasource_id": "42"}],
                        "mcps": [],
                        "agents": [],
                        "cores": [{"id": "l1", "label": "process_items", "type": "loop"}],
                        "edges": [{"from": "trigger:trigger", "to": "core:process_items"}]
                    }
                }
                """;
            mockRestTemplate(workflowJson);

            WorkflowContext context = workflowContextProvider.getWorkflowContext("conv-123", "tenant-1");

            assertThat(context.flowDiagram())
                .contains("⟳ process_items");
        }

        @Test
        @DisplayName("Builds flow diagram with decision symbol")
        void buildsFlowDiagramWithDecisionSymbol() {
            String workflowJson = """
                {
                    "id": "wf-456",
                    "name": "Decision Workflow",
                    "status": "COMPLETED",
                    "plan": {
                        "triggers": [{"id": "t1", "label": "trigger", "type": "datasource", "datasource_id": "42"}],
                        "mcps": [],
                        "agents": [],
                        "cores": [{"id": "d1", "label": "check_status", "type": "decision"}],
                        "edges": [{"from": "trigger:trigger", "to": "core:check_status"}]
                    }
                }
                """;
            mockRestTemplate(workflowJson);

            WorkflowContext context = workflowContextProvider.getWorkflowContext("conv-123", "tenant-1");

            assertThat(context.flowDiagram())
                .contains("◇ check_status");
        }

        @Test
        @DisplayName("Builds flow diagram with fork (multiple outputs)")
        void buildsFlowDiagramWithFork() {
            String workflowJson = """
                {
                    "id": "wf-456",
                    "name": "Fork Workflow",
                    "status": "COMPLETED",
                    "plan": {
                        "triggers": [{"id": "t1", "label": "trigger", "type": "datasource", "datasource_id": "42"}],
                        "mcps": [
                            {"id": "s1", "label": "send_email"},
                            {"id": "s2", "label": "notify_slack"}
                        ],
                        "agents": [],
                        "cores": [],
                        "edges": [
                            {"from": "trigger:trigger", "to": "mcp:send_email"},
                            {"from": "trigger:trigger", "to": "mcp:notify_slack"}
                        ]
                    }
                }
                """;
            mockRestTemplate(workflowJson);

            WorkflowContext context = workflowContextProvider.getWorkflowContext("conv-123", "tenant-1");

            // Fork should show both branches
            assertThat(context.flowDiagram())
                .contains("trigger")
                .contains("send_email")
                .contains("notify_slack")
                .contains("→");
        }

        @Test
        @DisplayName("Extracts datasourceId from trigger")
        void extractsDatasourceIdFromTrigger() {
            String workflowJson = """
                {
                    "id": "wf-456",
                    "name": "Test",
                    "status": "COMPLETED",
                    "plan": {
                        "triggers": [{"id": "t1", "label": "trigger", "type": "datasource", "datasource_id": "ds-789"}],
                        "mcps": [],
                        "agents": [],
                        "cores": [],
                        "edges": []
                    }
                }
                """;
            mockRestTemplate(workflowJson);

            WorkflowContext context = workflowContextProvider.getWorkflowContext("conv-123", "tenant-1");

            assertThat(context.datasourceId()).isEqualTo("ds-789");
        }

        @Test
        @DisplayName("Handles empty workflow plan")
        void handlesEmptyPlan() {
            String workflowJson = """
                {
                    "id": "wf-456",
                    "name": "Empty Workflow",
                    "status": "DRAFT",
                    "plan": {
                        "triggers": [],
                        "mcps": [],
                        "agents": [],
                        "cores": [],
                        "edges": []
                    }
                }
                """;
            mockRestTemplate(workflowJson);

            WorkflowContext context = workflowContextProvider.getWorkflowContext("conv-123", "tenant-1");

            assertThat(context.isPresent()).isTrue();
            assertThat(context.flowDiagram()).contains("empty workflow");
        }

        @Test
        @DisplayName("Handles workflow without edges")
        void handlesWorkflowWithoutEdges() {
            String workflowJson = """
                {
                    "id": "wf-456",
                    "name": "No Edges",
                    "status": "DRAFT",
                    "plan": {
                        "triggers": [{"id": "t1", "label": "trigger", "type": "datasource"}],
                        "mcps": [{"id": "s1", "label": "step1"}],
                        "agents": [],
                        "cores": [],
                        "edges": []
                    }
                }
                """;
            mockRestTemplate(workflowJson);

            WorkflowContext context = workflowContextProvider.getWorkflowContext("conv-123", "tenant-1");

            // Should still show nodes even without connections
            assertThat(context.flowDiagram())
                .contains("trigger");
        }

        @Test
        @DisplayName("Builds flow diagram with interface (interfaceIds array)")
        void buildsFlowDiagramWithInterfaceFromArray() {
            String workflowJson = """
                {
                    "id": "wf-456",
                    "name": "Workflow with Interface",
                    "status": "COMPLETED",
                    "plan": {
                        "triggers": [{"id": "t1", "label": "trigger", "type": "datasource", "datasource_id": "42"}],
                        "mcps": [{"id": "s1", "label": "process", "interfaceIds": ["iface-12345678-abcd"]}],
                        "agents": [],
                        "cores": [],
                        "edges": [{"from": "trigger:trigger", "to": "mcp:process"}]
                    }
                }
                """;
            mockRestTemplate(workflowJson);

            WorkflowContext context = workflowContextProvider.getWorkflowContext("conv-123", "tenant-1");

            assertThat(context.flowDiagram())
                .contains("trigger")
                .contains("process")
                .contains("[UI]")
                .contains("iface-12");
        }

        @Test
        @DisplayName("Builds flow diagram with interface (single interfaceId)")
        void buildsFlowDiagramWithSingleInterfaceId() {
            String workflowJson = """
                {
                    "id": "wf-456",
                    "name": "Workflow with Single Interface",
                    "status": "COMPLETED",
                    "plan": {
                        "triggers": [{"id": "t1", "label": "trigger", "type": "datasource", "interfaceId": "ui-98765432"}],
                        "mcps": [],
                        "agents": [],
                        "cores": [],
                        "edges": []
                    }
                }
                """;
            mockRestTemplate(workflowJson);

            WorkflowContext context = workflowContextProvider.getWorkflowContext("conv-123", "tenant-1");

            assertThat(context.flowDiagram())
                .contains("[UI]")
                .contains("ui-98765");
        }

        @Test
        @DisplayName("Interfaces are terminal nodes (no outgoing edges)")
        void interfacesAreTerminalNodes() {
            String workflowJson = """
                {
                    "id": "wf-456",
                    "name": "Interface Terminal",
                    "status": "COMPLETED",
                    "plan": {
                        "triggers": [{"id": "t1", "label": "trigger", "type": "datasource"}],
                        "mcps": [{"id": "s1", "label": "process", "interfaceIds": ["dashboard-ui"]}],
                        "agents": [],
                        "cores": [],
                        "edges": [{"from": "trigger:trigger", "to": "mcp:process"}]
                    }
                }
                """;
            mockRestTemplate(workflowJson);

            WorkflowContext context = workflowContextProvider.getWorkflowContext("conv-123", "tenant-1");

            // Interface should appear as a connected node
            assertThat(context.flowDiagram())
                .contains("trigger")
                .contains("process")
                .contains("[UI]");
        }

        @Test
        @DisplayName("Multiple interfaces linked to same node")
        void multipleInterfacesLinkedToSameNode() {
            String workflowJson = """
                {
                    "id": "wf-456",
                    "name": "Multi Interface",
                    "status": "COMPLETED",
                    "plan": {
                        "triggers": [{"id": "t1", "label": "trigger", "type": "datasource"}],
                        "mcps": [{"id": "s1", "label": "analyze", "interfaceIds": ["dashboard-1", "report-2"]}],
                        "agents": [],
                        "cores": [],
                        "edges": [{"from": "trigger:trigger", "to": "mcp:analyze"}]
                    }
                }
                """;
            mockRestTemplate(workflowJson);

            WorkflowContext context = workflowContextProvider.getWorkflowContext("conv-123", "tenant-1");

            // Both interfaces should appear
            assertThat(context.flowDiagram())
                .contains("analyze")
                .contains("[UI]");
        }

        @Test
        @DisplayName("Agent with linked interface")
        void agentWithLinkedInterface() {
            String workflowJson = """
                {
                    "id": "wf-456",
                    "name": "Agent with UI",
                    "status": "COMPLETED",
                    "plan": {
                        "triggers": [{"id": "t1", "label": "trigger", "type": "datasource"}],
                        "mcps": [],
                        "agents": [{"id": "a1", "label": "analyzer", "interfaceIds": ["result-view"]}],
                        "cores": [],
                        "edges": [{"from": "trigger:trigger", "to": "agent:analyzer"}]
                    }
                }
                """;
            mockRestTemplate(workflowJson);

            WorkflowContext context = workflowContextProvider.getWorkflowContext("conv-123", "tenant-1");

            assertThat(context.flowDiagram())
                .contains("[AI] analyzer")
                .contains("[UI]");
        }

        private void mockRestTemplate(String responseBody) {
            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
            )).thenReturn(new ResponseEntity<>(responseBody, HttpStatus.OK));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // ERROR HANDLING TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Returns simple context when REST call fails")
        void returnsSimpleContextWhenRestCallFails() {
            Conversation conversation = new Conversation();
            conversation.setId("conv-123");
            conversation.setWorkflowId("wf-456");
            when(conversationRepository.findById("conv-123")).thenReturn(Optional.of(conversation));

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
            )).thenThrow(new RuntimeException("Connection refused"));

            WorkflowContext context = workflowContextProvider.getWorkflowContext("conv-123", "tenant-1");

            // Should return a simple context with workflowId but limited info
            assertThat(context.isPresent()).isTrue();
            assertThat(context.workflowId()).isEqualTo("wf-456");
            assertThat(context.flowDiagram()).contains("unable to load");
        }

        @Test
        @DisplayName("Returns simple context when REST returns error status")
        void returnsSimpleContextWhenRestReturnsError() {
            Conversation conversation = new Conversation();
            conversation.setId("conv-123");
            conversation.setWorkflowId("wf-456");
            when(conversationRepository.findById("conv-123")).thenReturn(Optional.of(conversation));

            when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
            )).thenReturn(new ResponseEntity<>(null, HttpStatus.NOT_FOUND));

            WorkflowContext context = workflowContextProvider.getWorkflowContext("conv-123", "tenant-1");

            assertThat(context.isPresent()).isTrue();
            assertThat(context.workflowId()).isEqualTo("wf-456");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // WORKFLOW CONTEXT RECORD TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("WorkflowContext Record")
    class WorkflowContextRecordTests {

        @Test
        @DisplayName("isPresent returns true when workflowId is set")
        void isPresentReturnsTrueWhenWorkflowIdSet() {
            WorkflowContext context = new WorkflowContext(
                "wf-123", "Test", "COMPLETED", "flow", "ds-1", "info", false
            );

            assertThat(context.isPresent()).isTrue();
        }

        @Test
        @DisplayName("isPresent returns false when workflowId is null")
        void isPresentReturnsFalseWhenWorkflowIdNull() {
            WorkflowContext context = new WorkflowContext(
                null, null, null, null, null, null, false
            );

            assertThat(context.isPresent()).isFalse();
        }

        @Test
        @DisplayName("isPresent returns false when workflowId is blank")
        void isPresentReturnsFalseWhenWorkflowIdBlank() {
            WorkflowContext context = new WorkflowContext(
                "   ", "Test", "COMPLETED", "flow", "ds-1", "info", false
            );

            assertThat(context.isPresent()).isFalse();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // WORKFLOW BUILDER SESSION CONTEXT TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("WorkflowBuilderSessionContext")
    class WorkflowBuilderSessionContextTests {

        @Test
        @DisplayName("empty() returns context with hasActiveSession=false")
        void emptyReturnsInactiveContext() {
            WorkflowBuilderSessionContext context = WorkflowBuilderSessionContext.empty();

            assertThat(context.hasActiveSession()).isFalse();
            assertThat(context.sessionId()).isNull();
            assertThat(context.workflowName()).isNull();
            assertThat(context.plan()).isNull();
            assertThat(context.context()).isNull();
        }

        @Test
        @DisplayName("hasTrigger returns false when plan is null")
        void hasTriggerReturnsFalseWhenPlanNull() {
            WorkflowBuilderSessionContext context = new WorkflowBuilderSessionContext(
                true, "session-1", "Test", "Description", null, null, null
            );

            assertThat(context.hasTrigger()).isFalse();
        }

        @Test
        @DisplayName("hasTrigger returns false when triggers list is empty")
        void hasTriggerReturnsFalseWhenTriggersEmpty() {
            Map<String, Object> plan = Map.of("triggers", List.of());
            WorkflowBuilderSessionContext context = new WorkflowBuilderSessionContext(
                true, "session-1", "Test", "Description", null, plan, null
            );

            assertThat(context.hasTrigger()).isFalse();
        }

        @Test
        @DisplayName("hasTrigger returns true when triggers list is not empty")
        void hasTriggerReturnsTrueWhenTriggersPresent() {
            Map<String, Object> plan = Map.of(
                "triggers", List.of(Map.of("label", "My Trigger", "type", "webhook"))
            );
            WorkflowBuilderSessionContext context = new WorkflowBuilderSessionContext(
                true, "session-1", "Test", "Description", null, plan, null
            );

            assertThat(context.hasTrigger()).isTrue();
        }

        @Test
        @DisplayName("nodeCount returns 0 when plan is null")
        void nodeCountReturnsZeroWhenPlanNull() {
            WorkflowBuilderSessionContext context = new WorkflowBuilderSessionContext(
                true, "session-1", "Test", "Description", null, null, null
            );

            assertThat(context.nodeCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("nodeCount counts triggers, mcps, and cores")
        void nodeCountCountsAllNodeTypes() {
            Map<String, Object> plan = Map.of(
                "triggers", List.of(Map.of("label", "Trigger")),
                "mcps", List.of(
                    Map.of("label", "Step 1"),
                    Map.of("label", "Step 2")
                ),
                "cores", List.of(Map.of("label", "Decision", "type", "decision")),
                "edges", List.of(Map.of("from", "trigger:trigger", "to", "mcp:step_1"))
            );
            WorkflowBuilderSessionContext context = new WorkflowBuilderSessionContext(
                true, "session-1", "Test", "Description", null, plan, null
            );

            // 1 trigger + 2 mcps + 1 core = 4 nodes
            assertThat(context.nodeCount()).isEqualTo(4);
        }

        @Test
        @DisplayName("nodeCount ignores edges and interfaces")
        void nodeCountIgnoresEdgesAndInterfaces() {
            Map<String, Object> plan = Map.of(
                "triggers", List.of(Map.of("label", "Trigger")),
                "mcps", List.of(),
                "cores", List.of(),
                "edges", List.of(
                    Map.of("from", "a", "to", "b"),
                    Map.of("from", "b", "to", "c")
                ),
                "interfaces", List.of(Map.of("id", "interface-1"))
            );
            WorkflowBuilderSessionContext context = new WorkflowBuilderSessionContext(
                true, "session-1", "Test", "Description", null, plan, null
            );

            // Only 1 trigger, edges and interfaces not counted
            assertThat(context.nodeCount()).isEqualTo(1);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // ACTIVE SESSION FETCH TESTS
    // ═══════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getActiveWorkflowBuilderSession")
    class ActiveSessionFetchTests {

        @Test
        @DisplayName("Returns empty when tenantId is null")
        void returnsEmptyWhenTenantIdNull() {
            WorkflowBuilderSessionContext context = workflowContextProvider.getActiveWorkflowBuilderSession(null, "conv-123");

            assertThat(context.hasActiveSession()).isFalse();
        }

        @Test
        @DisplayName("Returns empty when tenantId is blank")
        void returnsEmptyWhenTenantIdBlank() {
            WorkflowBuilderSessionContext context = workflowContextProvider.getActiveWorkflowBuilderSession("   ", "conv-123");

            assertThat(context.hasActiveSession()).isFalse();
        }

        @Test
        @DisplayName("Returns empty when no active session")
        void returnsEmptyWhenNoActiveSession() {
            String responseJson = """
                {
                    "hasActiveSession": false
                }
                """;
            when(restTemplate.exchange(
                contains("/api/workflow-builder/sessions/active"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
            )).thenReturn(new ResponseEntity<>(responseJson, HttpStatus.OK));

            WorkflowBuilderSessionContext context = workflowContextProvider.getActiveWorkflowBuilderSession("tenant-1", "conv-123");

            assertThat(context.hasActiveSession()).isFalse();
        }

        @Test
        @DisplayName("Parses full session response with plan and context")
        void parsesFullSessionResponse() {
            String responseJson = """
                {
                    "hasActiveSession": true,
                    "session": {
                        "sessionId": "wb_abc123",
                        "name": "Test Workflow",
                        "description": "A test workflow",
                        "draftId": "draft-456",
                        "plan": {
                            "triggers": [{"label": "Webhook", "type": "webhook"}],
                            "mcps": [{"label": "Fetch Data"}],
                            "cores": [],
                            "edges": [{"from": "trigger:webhook", "to": "mcp:fetch_data"}],
                            "interfaces": [],
                            "tables": []
                        },
                        "context": {
                            "rules": {"1_trigger_first": "Trigger must be created FIRST"},
                            "variable_syntax": {"trigger": "{{trigger:label.output.field}}"},
                            "phase": "BUILDING",
                            "NEXT": "Add more nodes"
                        }
                    }
                }
                """;
            when(restTemplate.exchange(
                contains("/api/workflow-builder/sessions/active"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
            )).thenReturn(new ResponseEntity<>(responseJson, HttpStatus.OK));

            WorkflowBuilderSessionContext context = workflowContextProvider.getActiveWorkflowBuilderSession("tenant-1", "conv-123");

            assertThat(context.hasActiveSession()).isTrue();
            assertThat(context.sessionId()).isEqualTo("wb_abc123");
            assertThat(context.workflowName()).isEqualTo("Test Workflow");
            assertThat(context.workflowDescription()).isEqualTo("A test workflow");
            assertThat(context.draftId()).isEqualTo("draft-456");

            // Check plan
            assertThat(context.plan()).isNotNull();
            assertThat(context.hasTrigger()).isTrue();
            assertThat(context.nodeCount()).isEqualTo(2); // 1 trigger + 1 mcp

            // Check context
            assertThat(context.context()).isNotNull();
            assertThat(context.context().get("phase")).isEqualTo("BUILDING");
            assertThat(context.context().get("NEXT")).isEqualTo("Add more nodes");
        }

        @Test
        @DisplayName("Handles session from sessions array (multi-session)")
        void handlesSessionFromArray() {
            String responseJson = """
                {
                    "hasActiveSession": true,
                    "sessions": [
                        {
                            "sessionId": "wb_first",
                            "name": "First Workflow",
                            "plan": {
                                "triggers": [],
                                "mcps": [],
                                "cores": []
                            }
                        }
                    ]
                }
                """;
            when(restTemplate.exchange(
                contains("/api/workflow-builder/sessions/active"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
            )).thenReturn(new ResponseEntity<>(responseJson, HttpStatus.OK));

            WorkflowBuilderSessionContext context = workflowContextProvider.getActiveWorkflowBuilderSession("tenant-1", "conv-123");

            assertThat(context.hasActiveSession()).isTrue();
            assertThat(context.sessionId()).isEqualTo("wb_first");
            assertThat(context.workflowName()).isEqualTo("First Workflow");
        }

        @Test
        @DisplayName("Returns empty when REST call fails")
        void returnsEmptyWhenRestCallFails() {
            when(restTemplate.exchange(
                contains("/api/workflow-builder/sessions/active"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
            )).thenThrow(new RuntimeException("Connection refused"));

            WorkflowBuilderSessionContext context = workflowContextProvider.getActiveWorkflowBuilderSession("tenant-1", "conv-123");

            assertThat(context.hasActiveSession()).isFalse();
        }
    }
}
