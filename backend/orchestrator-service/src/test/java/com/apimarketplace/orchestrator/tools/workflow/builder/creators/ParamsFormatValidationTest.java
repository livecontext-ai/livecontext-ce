package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentDto;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.tools.workflow.builder.*;
import com.apimarketplace.trigger.client.TriggerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.lenient;

/**
 * Tests that all creators accept flat parameters (after WorkflowBuilderProvider flattens them).
 * WorkflowBuilderProvider merges params={...} into the root parameter map before calling creators.
 * So creators always receive flat parameters at the root level.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Params Format Validation Tests")
class ParamsFormatValidationTest {

    @Mock
    private WorkflowBuilderSessionStore sessionStore;

    @Mock
    private DataSourceClient dataSourceService;

    @Mock
    private CredentialClient credentialClient;

    @Mock
    private ToolSchemaFetcher toolSchemaFetcher;

    @Mock
    private SmartDefaultsEngine smartDefaultsEngine;

    @Mock
    private ResponseOptimizer responseOptimizer;

    @Mock
    private AgentClient agentClient;

    @Mock
    private TriggerClient triggerClient;

    @Mock
    private NodeLibraryService nodeLibraryService;

    @Mock
    private WorkflowRepository workflowRepository;

    private WorkflowBuilderSession session;
    private DecisionNodeCreator decisionNodeCreator;
    private ForkMergeNodeCreator forkMergeNodeCreator;
    private UtilityNodeCreator utilityNodeCreator;
    private TriggerCreator triggerCreator;
    private McpCreator mcpCreator;
    private TableCreator tableCreator;
    private AgentCreator agentCreator;

    @BeforeEach
    void setUp() {
        decisionNodeCreator = new DecisionNodeCreator(sessionStore, responseOptimizer);
        forkMergeNodeCreator = new ForkMergeNodeCreator(sessionStore);
        utilityNodeCreator = new UtilityNodeCreator(sessionStore, responseOptimizer, nodeLibraryService, workflowRepository);
        triggerCreator = new TriggerCreator(sessionStore, dataSourceService, smartDefaultsEngine, responseOptimizer, triggerClient);
        mcpCreator = new McpCreator(sessionStore, toolSchemaFetcher, dataSourceService, credentialClient, responseOptimizer);
        tableCreator = new TableCreator(sessionStore, dataSourceService, responseOptimizer, nodeLibraryService);
        agentCreator = new AgentCreator(sessionStore, agentClient, dataSourceService, responseOptimizer);

        session = WorkflowBuilderSession.builder()
            .sessionId("test-session")
            .tenantId("test-tenant")
            .workflowName("Test Workflow")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();

        // Setup mocks
        lenient().when(smartDefaultsEngine.applyTriggerDefaults(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(responseOptimizer.buildTriggerResponse(any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(new LinkedHashMap<>(Map.of("status", "OK")));
        lenient().when(responseOptimizer.buildDecisionResponse(any(), any(), any(), any()))
            .thenReturn(new LinkedHashMap<>(Map.of("status", "OK")));
        lenient().when(responseOptimizer.buildStepResponse(any(), anyString(), anyString(), anyString(), anyString(), anyString(), anyBoolean(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(new LinkedHashMap<>(Map.of("status", "OK")));
        lenient().when(responseOptimizer.buildAgentResponse(any(), anyString(), anyString(), anyString(), anyString(), any(), any(), any(), any(), any(), anyBoolean()))
            .thenReturn(new LinkedHashMap<>(Map.of("status", "OK")));
    }

    private void addTriggerToSession() {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("label", "Start");
        trigger.put("id", "trigger:start");
        trigger.put("type", "webhook");
        session.getTriggers().add(trigger);
    }

    // ==================== Decision Tests ====================

    @Nested
    @DisplayName("Decision with flat parameters")
    class DecisionParamsFormat {

        @Test
        @DisplayName("Should accept conditions array as flat parameter")
        void shouldAcceptConditionsInParams() {
            addTriggerToSession();

            List<Map<String, Object>> conditions = List.of(
                Map.of("condition", "{{status == 'success'}}", "label", "Success"),
                Map.of("condition", "default", "label", "Failure")
            );

            // Flat parameters
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Check Status");
            parameters.put("conditions", conditions);
            parameters.put("connect_after", "Start");

            ToolExecutionResult result = decisionNodeCreator.executeAddDecision(session, parameters);

            assertThat(result.success()).isTrue();
            assertThat(session.getCores()).hasSize(1);

            Map<String, Object> decision = session.getCores().get(0);
            assertThat(decision.get("label")).isEqualTo("Check Status");
            assertThat(decision.get("type")).isEqualTo("decision");
        }
    }

    // ==================== Switch Tests ====================

    @Nested
    @DisplayName("Switch with flat parameters")
    class SwitchParamsFormat {

        @Test
        @DisplayName("Should accept expression and cases as flat parameters")
        void shouldAcceptExpressionAndCasesInParams() {
            addTriggerToSession();

            List<Map<String, Object>> cases = List.of(
                Map.of("type", "case", "label", "Case A", "value", "a"),
                Map.of("type", "case", "label", "Case B", "value", "b"),
                Map.of("type", "default", "label", "Default")
            );

            // Flat parameters
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Route By Type");
            parameters.put("expression", "{{trigger:start.type}}");
            parameters.put("cases", cases);
            parameters.put("connect_after", "Start");

            ToolExecutionResult result = decisionNodeCreator.executeAddSwitch(session, parameters);

            assertThat(result.success()).isTrue();
            assertThat(session.getCores()).hasSize(1);

            Map<String, Object> switchNode = session.getCores().get(0);
            assertThat(switchNode.get("switchExpression")).isEqualTo("{{trigger:start.type}}");
        }
    }

    // ==================== Fork Tests ====================

    @Nested
    @DisplayName("Fork with flat parameters")
    class ForkParamsFormat {

        @Test
        @DisplayName("Should accept branches array as flat parameter")
        void shouldAcceptBranchesInParams() {
            addTriggerToSession();

            List<Map<String, Object>> branches = List.of(
                Map.of("label", "Branch A"),
                Map.of("label", "Branch B"),
                Map.of("label", "Branch C")
            );

            // Flat parameters
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Parallel Tasks");
            parameters.put("branches", branches);
            parameters.put("connect_after", "Start");

            ToolExecutionResult result = forkMergeNodeCreator.executeAddFork(session, parameters);

            assertThat(result.success()).isTrue();
            assertThat(session.getCores()).hasSize(1);

            Map<String, Object> fork = session.getCores().get(0);
            assertThat(fork.get("type")).isEqualTo("fork");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> forkOutputs = (List<Map<String, Object>>) fork.get("forkOutputs");
            assertThat(forkOutputs).hasSize(3);
        }
    }

    // ==================== Merge Tests ====================

    @Nested
    @DisplayName("Merge with flat parameters")
    class MergeParamsFormat {

        @Test
        @DisplayName("Should accept label as flat parameter")
        void shouldAcceptLabelInParams() {
            addTriggerToSession();

            // Flat parameters
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Wait All");

            ToolExecutionResult result = forkMergeNodeCreator.executeAddMerge(session, parameters);

            assertThat(result.success()).isTrue();
            assertThat(session.getCores()).hasSize(1);

            Map<String, Object> merge = session.getCores().get(0);
            assertThat(merge.get("label")).isEqualTo("Wait All");
            assertThat(merge.get("type")).isEqualTo("merge");
        }
    }

    // ==================== Transform Tests ====================

    @Nested
    @DisplayName("Transform with flat parameters")
    class TransformParamsFormat {

        @Test
        @DisplayName("Should accept mappings array as flat parameter")
        void shouldAcceptMappingsInParams() {
            addTriggerToSession();

            List<Map<String, Object>> mappings = List.of(
                Map.of("label", "full_name", "expression", "{{firstName}} {{lastName}}"),
                Map.of("label", "is_active", "expression", "{{status == 'active'}}")
            );

            // Flat parameters
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Format Data");
            parameters.put("mappings", mappings);
            parameters.put("connect_after", "Start");

            ToolExecutionResult result = utilityNodeCreator.executeAddTransform(session, parameters);

            assertThat(result.success()).isTrue();
            assertThat(session.getCores()).hasSize(1);

            Map<String, Object> transform = session.getCores().get(0);
            assertThat(transform.get("type")).isEqualTo("transform");
        }
    }

    // ==================== Wait Tests ====================

    @Nested
    @DisplayName("Wait with flat parameters")
    class WaitParamsFormat {

        @Test
        @DisplayName("Should accept duration as flat parameter")
        void shouldAcceptDurationInParams() {
            addTriggerToSession();

            // Flat parameters
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Wait 5 seconds");
            parameters.put("duration", 5000);
            parameters.put("connect_after", "Start");

            ToolExecutionResult result = utilityNodeCreator.executeAddWait(session, parameters);

            assertThat(result.success()).isTrue();
            assertThat(session.getCores()).hasSize(1);

            Map<String, Object> wait = session.getCores().get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> waitConfig = (Map<String, Object>) wait.get("wait");
            assertThat(waitConfig.get("duration")).isEqualTo(5000);
        }

        @Test
        @DisplayName("Should accept seconds alias as flat parameter")
        void shouldAcceptSecondsAliasInParams() {
            addTriggerToSession();

            // Flat parameters
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Wait 10 seconds");
            parameters.put("seconds", 10);
            parameters.put("connect_after", "Start");

            ToolExecutionResult result = utilityNodeCreator.executeAddWait(session, parameters);

            assertThat(result.success()).isTrue();

            Map<String, Object> wait = session.getCores().get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> waitConfig = (Map<String, Object>) wait.get("wait");
            assertThat(waitConfig.get("duration")).isEqualTo(10000); // 10 seconds = 10000 ms
        }
    }

    // ==================== Download File Tests ====================

    @Nested
    @DisplayName("Download File with flat parameters")
    class DownloadFileParamsFormat {

        @Test
        @DisplayName("Should accept url as flat parameter")
        void shouldAcceptUrlInParams() {
            addTriggerToSession();

            // Flat parameters
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Download Image");
            parameters.put("url", "{{trigger:start.image_url}}");
            parameters.put("filename", "image.png");
            parameters.put("connect_after", "Start");

            ToolExecutionResult result = utilityNodeCreator.executeAddDownloadFile(session, parameters);

            assertThat(result.success()).isTrue();
            assertThat(session.getCores()).hasSize(1);

            Map<String, Object> downloadFile = session.getCores().get(0);
            assertThat(downloadFile.get("type")).isEqualTo("download_file");

            @SuppressWarnings("unchecked")
            Map<String, Object> downloadConfig = (Map<String, Object>) downloadFile.get("download");
            assertThat(downloadConfig.get("url")).isEqualTo("{{trigger:start.image_url}}");
            assertThat(downloadConfig.get("filename")).isEqualTo("image.png");
        }
    }

    // ==================== Trigger Tests ====================

    @Nested
    @DisplayName("Trigger with flat parameters")
    class TriggerParamsFormat {

        @Test
        @DisplayName("Should accept trigger_type as flat parameter")
        void shouldAcceptTriggerTypeInParams() {
            // Flat parameters (WorkflowBuilderProvider converts type='webhook' to trigger_type='webhook')
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "My Webhook");
            parameters.put("trigger_type", "webhook");

            ToolExecutionResult result = triggerCreator.executeAddTrigger(session, parameters, "test-tenant");

            assertThat(result.success()).isTrue();
            assertThat(session.getTriggers()).hasSize(1);

            Map<String, Object> trigger = session.getTriggers().get(0);
            assertThat(trigger.get("label")).isEqualTo("My Webhook");
            assertThat(trigger.get("type")).isEqualTo("webhook");
        }

        @Test
        @DisplayName("Should accept schedule cron as flat parameter")
        void shouldAcceptScheduleCronInParams() {
            // Flat parameters
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Daily Job");
            parameters.put("trigger_type", "schedule");
            parameters.put("cron", "0 9 * * *");
            parameters.put("timezone", "America/New_York");

            ToolExecutionResult result = triggerCreator.executeAddTrigger(session, parameters, "test-tenant");

            assertThat(result.success()).isTrue();

            Map<String, Object> trigger = session.getTriggers().get(0);
            assertThat(trigger.get("type")).isEqualTo("schedule");

            // Schedule config is stored under "params" (not "input")
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) trigger.get("params");
            assertThat(params).isNotNull();
            assertThat(params.get("cron")).isEqualTo("0 9 * * *");
            assertThat(params.get("timezone")).isEqualTo("America/New_York");
        }
    }

    // ==================== Step Tests ====================

    @Nested
    @DisplayName("Step with flat parameters")
    class StepParamsFormat {

        @Test
        @DisplayName("Should accept tool id as separate argument")
        void shouldAcceptToolIdInParams() {
            addTriggerToSession();

            // Flat parameters - tool params at root level
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Send Email");
            parameters.put("to", "{{trigger:start.email}}");
            parameters.put("subject", "Hello");
            parameters.put("connect_after", "Start");

            String toolId = "550e8400-e29b-41d4-a716-446655440099";

            // Tool MUST exist in the catalog - checkToolExists returns EXISTS,
            // fetchToolInfo returns the cached info. Otherwise McpCreator rejects.
            lenient().when(toolSchemaFetcher.checkToolExists(toolId))
                .thenReturn(com.apimarketplace.orchestrator.tools.workflow.builder.ToolSchemaFetcher.ToolExistence.EXISTS);
            lenient().when(toolSchemaFetcher.fetchToolInfo(any()))
                .thenReturn(Optional.of(Map.of("name", "send_email", "iconSlug", "gmail")));
            lenient().when(toolSchemaFetcher.fetchToolSchema(any())).thenReturn(Optional.empty());
            lenient().when(toolSchemaFetcher.fetchToolInputSchema(any())).thenReturn(Optional.empty());

            ToolExecutionResult result = mcpCreator.executeAddMcp(session, parameters, toolId);

            assertThat(result.success()).isTrue();
            assertThat(session.getMcps()).hasSize(1);

            Map<String, Object> step = session.getMcps().get(0);
            assertThat(step.get("label")).isEqualTo("Send Email");
            assertThat(step.get("id")).isEqualTo(toolId);
        }

        @Test
        @DisplayName("Should REJECT tool id that does not exist in catalog")
        void shouldRejectMissingToolId() {
            addTriggerToSession();

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Bogus");
            parameters.put("connect_after", "Start");

            // Mimic the LLM hallucinating a fake UUID - definitive 404 from catalog.
            lenient().when(toolSchemaFetcher.checkToolExists(any()))
                .thenReturn(com.apimarketplace.orchestrator.tools.workflow.builder.ToolSchemaFetcher.ToolExistence.NOT_FOUND);

            ToolExecutionResult result = mcpCreator.executeAddMcp(
                session, parameters, "00000000-0000-0000-0000-000000000000");

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("does not exist in the catalog");
            assertThat(session.getMcps()).isEmpty();
        }

        @Test
        @DisplayName("Should REJECT non-UUID tool id (e.g. 'Label_1' hallucination)")
        void shouldRejectNonUuidToolId() {
            addTriggerToSession();

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Apply Label");
            parameters.put("connect_after", "Start");

            // Real fetcher would short-circuit "Label_1" to NOT_FOUND via isValidUUID;
            // mocked fetcher needs explicit stubbing.
            lenient().when(toolSchemaFetcher.checkToolExists("Label_1"))
                .thenReturn(com.apimarketplace.orchestrator.tools.workflow.builder.ToolSchemaFetcher.ToolExistence.NOT_FOUND);

            ToolExecutionResult result = mcpCreator.executeAddMcp(session, parameters, "Label_1");

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("does not exist in the catalog");
            assertThat(session.getMcps()).isEmpty();
        }

        @Test
        @DisplayName("Should accept tool id when catalog is unreachable (UNKNOWN ≠ NOT_FOUND)")
        void shouldAcceptOnTransientCatalogOutage() {
            addTriggerToSession();

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Send Email");
            parameters.put("connect_after", "Start");

            String toolId = "550e8400-e29b-41d4-a716-446655440042";
            // Simulate transient outage: existence check returns UNKNOWN, info call returns empty.
            // Permissive path - node should still be created so a flaky catalog doesn't block work.
            lenient().when(toolSchemaFetcher.checkToolExists(toolId))
                .thenReturn(com.apimarketplace.orchestrator.tools.workflow.builder.ToolSchemaFetcher.ToolExistence.UNKNOWN);
            lenient().when(toolSchemaFetcher.fetchToolInfo(any())).thenReturn(Optional.empty());
            lenient().when(toolSchemaFetcher.fetchToolSchema(any())).thenReturn(Optional.empty());
            lenient().when(toolSchemaFetcher.fetchToolInputSchema(any())).thenReturn(Optional.empty());

            ToolExecutionResult result = mcpCreator.executeAddMcp(session, parameters, toolId);

            assertThat(result.success()).isTrue();
            assertThat(session.getMcps()).hasSize(1);
        }

        @Test
        @DisplayName("Should accept reserved sentinels (__transform__, __wait__) without catalog lookup")
        void shouldAcceptReservedSentinels() {
            addTriggerToSession();

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Transform");
            parameters.put("connect_after", "Start");

            // No mock setup for fetchToolInfo - sentinel must NOT trigger a catalog call
            lenient().when(toolSchemaFetcher.fetchToolSchema(any())).thenReturn(Optional.empty());
            lenient().when(toolSchemaFetcher.fetchToolInputSchema(any())).thenReturn(Optional.empty());

            ToolExecutionResult result = mcpCreator.executeAddMcp(session, parameters, "__transform__");

            assertThat(result.success()).isTrue();
            assertThat(session.getMcps()).hasSize(1);
            assertThat(session.getMcps().get(0).get("type")).isEqualTo("transform");
        }
    }

    // ==================== Agent Tests ====================

    private static final String AGENT_UUID = "550e8400-e29b-41d4-a716-446655440000";

    private AgentDto createMockAgentDto() {
        AgentDto agentDto = new AgentDto();
        agentDto.setId(UUID.fromString(AGENT_UUID));
        agentDto.setName("Test Agent");
        agentDto.setTenantId("test-tenant");
        return agentDto;
    }

    @Nested
    @DisplayName("Agent with flat parameters")
    class AgentParamsFormat {

        @Test
        @DisplayName("Should accept agent_id and prompt as flat parameters")
        void shouldAcceptAgentIdAndPromptInParams() {
            addTriggerToSession();
            lenient().when(agentClient.getAgent(UUID.fromString(AGENT_UUID), "test-tenant"))
                .thenReturn(createMockAgentDto());

            // Flat parameters
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Analyzer");
            parameters.put("agent_id", AGENT_UUID);
            parameters.put("prompt", "Analyze the following data: {{trigger:start.data}}. Return JSON with {category, priority}.");
            parameters.put("connect_after", "Start");

            ToolExecutionResult result = agentCreator.executeAddAgent(session, parameters);

            assertThat(result.success()).isTrue();
            assertThat(session.getMcps()).hasSize(1);

            Map<String, Object> agent = session.getMcps().get(0);
            assertThat(agent.get("label")).isEqualTo("Analyzer");
            assertThat(agent.get("agentConfigId")).isEqualTo(AGENT_UUID);
            assertThat(agent.get("agentConfigName")).isEqualTo("Test Agent");
            assertThat(agent.get("prompt")).isEqualTo("Analyze the following data: {{trigger:start.data}}. Return JSON with {category, priority}.");
            assertThat(agent.get("isAgent")).isEqualTo(true);
        }

        @Test
        @DisplayName("Should fail without agent_id")
        void shouldFailWithoutAgentId() {
            addTriggerToSession();

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Smart Agent");
            parameters.put("prompt", "Process this: {{data}}");
            parameters.put("connect_after", "Start");

            ToolExecutionResult result = agentCreator.executeAddAgent(session, parameters);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("agent_id");
        }

        @Test
        @DisplayName("Should fail with invalid UUID")
        void shouldFailWithInvalidUuid() {
            addTriggerToSession();

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Smart Agent");
            parameters.put("agent_id", "not-a-uuid");
            parameters.put("connect_after", "Start");

            ToolExecutionResult result = agentCreator.executeAddAgent(session, parameters);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("valid UUID");
        }

        @Test
        @DisplayName("Should fail when agent not found in DB")
        void shouldFailWhenAgentNotFound() {
            addTriggerToSession();
            lenient().when(agentClient.getAgent(UUID.fromString(AGENT_UUID), "test-tenant"))
                .thenReturn(null);

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Smart Agent");
            parameters.put("agent_id", AGENT_UUID);
            parameters.put("connect_after", "Start");

            ToolExecutionResult result = agentCreator.executeAddAgent(session, parameters);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("No agent found");
        }
    }

    // ==================== Table (CRUD) Tests ====================

    @Nested
    @DisplayName("Table with flat parameters")
    class TableParamsFormat {

        @Test
        @DisplayName("Should accept crud toolId and flat parameters")
        void shouldAcceptCrudToolId() {
            addTriggerToSession();

            // Flat parameters - CRUD params at root level
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Save Record");
            parameters.put("dataSourceId", 42L);
            parameters.put("crud", Map.of("rows", List.of(Map.of("columns", Map.of("name", "John")))));
            parameters.put("connect_after", "Start");

            String toolId = "crud/create-row";

            ToolExecutionResult result = tableCreator.executeAddTable(session, parameters, toolId);

            assertThat(result.success()).isTrue();
            assertThat(session.getTables()).hasSize(1);

            Map<String, Object> step = session.getTables().get(0);
            assertThat(step.get("label")).isEqualTo("Save Record");
            assertThat(step.get("id")).isEqualTo("crud/create-row");
            assertThat(step.get("type")).isEqualTo("crud-create-row");
            assertThat(step.get("dataSourceId")).isEqualTo(42L);
        }

        @Test
        @DisplayName("Should store in tables list, not mcps")
        void shouldStoreInTablesList() {
            addTriggerToSession();

            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Fetch Data");
            parameters.put("connect_after", "Start");

            tableCreator.executeAddTable(session, parameters, "crud/read-row");

            assertThat(session.getTables()).hasSize(1);
            assertThat(session.getMcps()).isEmpty();
        }
    }

    // ==================== Backward Compatibility Tests ====================

    @Nested
    @DisplayName("Backward Compatibility")
    class BackwardCompatibility {

        @Test
        @DisplayName("Should accept conditions at root level for decision")
        void shouldAcceptConditionsAtRootLevel() {
            addTriggerToSession();

            // Conditions at root level
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("label", "Check");
            parameters.put("conditions", List.of(
                Map.of("condition", "{{x > 0}}", "label", "Positive"),
                Map.of("label", "Default")
            ));
            parameters.put("connect_after", "Start");

            ToolExecutionResult result = decisionNodeCreator.executeAddDecision(session, parameters);

            assertThat(result.success()).isTrue();
        }
    }
}
