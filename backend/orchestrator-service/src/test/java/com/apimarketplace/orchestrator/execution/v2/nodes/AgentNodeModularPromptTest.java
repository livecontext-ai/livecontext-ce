package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto;
import com.apimarketplace.agent.client.dto.execution.AgentExecutionResponseDto;
import com.apimarketplace.orchestrator.domain.workflow.Agent;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.engine.ServiceRegistry;
import com.apimarketplace.orchestrator.services.agent.AgentConfigResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for AgentNode's modular prompt system.
 * Validates that workflow agents receive the correct system prompt
 * based on their entity's toolsConfig.
 *
 * Note: Core tools resolution is now handled by agent-service remotely.
 * AgentNode sends autoDiscoverTools=true and tools=null to the remote service.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentNode - Modular Prompt & Core Tools")
class AgentNodeModularPromptTest {

    @Mock private WorkflowPlan mockPlan;
    @Mock private AgentClient mockAgentClient;
    @Mock private AgentConfigResolver mockAgentConfigResolver;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create(
            "run-1", "workflow-run-1", "tenant-1",
            "item-1", 0,
            Map.of("user_input", "Hello"),
            mockPlan
        );
    }

    /**
     * Helper to create a ServiceRegistry with the mocked services.
     */
    private ServiceRegistry buildServiceRegistry() {
        return ServiceRegistry.builder()
            .agentClient(mockAgentClient)
            .agentConfigResolver(mockAgentConfigResolver)
            .build();
    }

    /**
     * Helper to create a successful agent response for mocking.
     */
    private AgentExecutionResponseDto successResponse() {
        return new AgentExecutionResponseDto(
            true, "OK", "OK", List.of(), 1, Map.of(), null, 100L,
            "openai", "gpt-4o", List.of(), null, Map.of(),
            List.of(), List.of(), List.of(), null, null
        , null);
    }

    /**
     * Helper to capture the AgentExecutionRequestDto passed to AgentClient.
     */
    private AgentExecutionRequestDto captureRequest(AgentNode node) {
        when(mockAgentClient.executeAgent(any(AgentExecutionRequestDto.class)))
            .thenReturn(successResponse());

        node.execute(context);

        ArgumentCaptor<AgentExecutionRequestDto> captor = ArgumentCaptor.forClass(AgentExecutionRequestDto.class);
        verify(mockAgentClient).executeAgent(captor.capture());
        return captor.getValue();
    }

    // =====================================================================
    // NO agentConfigId -> unrestricted (all modules)
    // =====================================================================

    @Nested
    @DisplayName("No agentConfigId (inline agent)")
    class NoAgentConfigIdTests {

        @Test
        @DisplayName("No agentConfigId -> modular prompt with ALL modules, conversationMode=false by default")
        void noConfigIdUsesAllModules() {
            Agent agent = createAgent(null, null, false, "Do something");
            AgentNode node = new AgentNode("agent:test", agent);
            node.acceptServices(buildServiceRegistry());

            AgentExecutionRequestDto request = captureRequest(node);

            // System prompt should contain all resource modules
            assertThat(request.systemPrompt())
                .contains("catalog")
                .contains("table")
                .contains("interface")
                .contains("agent")
                .contains("skill")
                .contains("stateful")
                .contains("application")
                .contains("web_search")
                .contains("Help First");
        }

        @Test
        @DisplayName("No agentConfigId + withMemory=true -> no Task Management in prompt")
        void noConfigIdWithMemoryDoesNotIncludeTaskManagement() {
            Agent agent = createAgent(null, null, true, "Do something");
            AgentNode node = new AgentNode("agent:test", agent);
            node.acceptServices(buildServiceRegistry());

            AgentExecutionRequestDto request = captureRequest(node);

            assertThat(request.systemPrompt()).doesNotContain("Task Management");
            assertThat(request.systemPrompt()).doesNotContain("When to Suggest Workflows");
        }

        @Test
        @DisplayName("No agentConfigId + withMemory=false -> no Task Management in prompt")
        void noConfigIdWithoutMemoryExcludesTaskManagement() {
            Agent agent = createAgent(null, null, false, "Do something");
            AgentNode node = new AgentNode("agent:test", agent);
            node.acceptServices(buildServiceRegistry());

            AgentExecutionRequestDto request = captureRequest(node);

            assertThat(request.systemPrompt()).doesNotContain("Task Management");
        }
    }

    // =====================================================================
    // With agentConfigId -> config from entity
    // =====================================================================

    @Nested
    @DisplayName("With agentConfigId (entity-based agent)")
    class WithAgentConfigIdTests {

        private final String entityId = UUID.randomUUID().toString();

        @Test
        @DisplayName("toolsConfig=null -> all modules (unrestricted)")
        void nullToolsConfigMeansUnrestricted() {
            when(mockAgentConfigResolver.getToolsConfig(eq(entityId), eq("tenant-1"), isNull()))
                .thenReturn(null);

            Agent agent = createAgent(entityId, null, false, "Do something");
            AgentNode node = new AgentNode("agent:test", agent);
            node.acceptServices(buildServiceRegistry());

            AgentExecutionRequestDto request = captureRequest(node);

            assertThat(request.systemPrompt())
                .contains("catalog")
                .contains("table")
                .contains("interface")
                .contains("agent")
                .contains("stateful")
                .contains("application");
        }

        @Test
        @DisplayName("tables=[] -> table module excluded from prompt")
        void emptyTablesExcludesTableModule() {
            Map<String, Object> toolsConfig = new HashMap<>();
            toolsConfig.put("tables", List.of());
            when(mockAgentConfigResolver.getToolsConfig(eq(entityId), eq("tenant-1"), isNull()))
                .thenReturn(toolsConfig);

            Agent agent = createAgent(entityId, null, false, "Do something");
            AgentNode node = new AgentNode("agent:test", agent);
            node.acceptServices(buildServiceRegistry());

            AgentExecutionRequestDto request = captureRequest(node);

            // table should NOT be in the prompt - check that table-specific text is absent
            assertThat(request.systemPrompt()).doesNotContain("CRUD rows/columns");
            // but interface/workflow/agent should be
            assertThat(request.systemPrompt())
                .contains("interface")
                .contains("stateful")
                .contains("agent");
        }

        @Test
        @DisplayName("interfaces=[] -> interface module excluded")
        void emptyInterfacesExcludesInterfaceModule() {
            Map<String, Object> toolsConfig = new HashMap<>();
            toolsConfig.put("interfaces", List.of());
            when(mockAgentConfigResolver.getToolsConfig(eq(entityId), eq("tenant-1"), isNull()))
                .thenReturn(toolsConfig);

            Agent agent = createAgent(entityId, null, false, "Do something");
            AgentNode node = new AgentNode("agent:test", agent);
            node.acceptServices(buildServiceRegistry());

            AgentExecutionRequestDto request = captureRequest(node);

            assertThat(request.systemPrompt()).doesNotContain("standalone interactive HTML");
            assertThat(request.systemPrompt()).contains("table");
        }

        @Test
        @DisplayName("agents=[] -> agent module excluded")
        void emptyAgentsExcludesAgentModule() {
            Map<String, Object> toolsConfig = new HashMap<>();
            toolsConfig.put("agents", List.of());
            when(mockAgentConfigResolver.getToolsConfig(eq(entityId), eq("tenant-1"), isNull()))
                .thenReturn(toolsConfig);

            Agent agent = createAgent(entityId, null, false, "Do something");
            AgentNode node = new AgentNode("agent:test", agent);
            node.acceptServices(buildServiceRegistry());

            AgentExecutionRequestDto request = captureRequest(node);

            assertThat(request.systemPrompt()).doesNotContain("AI agent configurations");
            assertThat(request.systemPrompt()).contains("table");
        }

        @Test
        @DisplayName("workflows=[] -> workflow module excluded, no automation hint")
        void emptyWorkflowsExcludesWorkflowModule() {
            Map<String, Object> toolsConfig = new HashMap<>();
            toolsConfig.put("workflows", List.of());
            when(mockAgentConfigResolver.getToolsConfig(eq(entityId), eq("tenant-1"), isNull()))
                .thenReturn(toolsConfig);

            // withMemory=true but workflows blocked -> no automation hint
            Agent agent = createAgent(entityId, null, true, "Do something");
            AgentNode node = new AgentNode("agent:test", agent);
            node.acceptServices(buildServiceRegistry());

            AgentExecutionRequestDto request = captureRequest(node);

            assertThat(request.systemPrompt())
                .doesNotContain("STATEFUL builder")
                .doesNotContain("When to Suggest Workflows");
        }

        @Test
        @DisplayName("mode=none -> catalog blocked, internal tools (table, interface, workflow, etc.) remain enabled")
        void modeNoneMeansOnlyCatalog() {
            Map<String, Object> toolsConfig = new HashMap<>();
            toolsConfig.put("mode", "none");
            when(mockAgentConfigResolver.getToolsConfig(eq(entityId), eq("tenant-1"), isNull()))
                .thenReturn(toolsConfig);

            Agent agent = createAgent(entityId, null, false, "Do something");
            AgentNode node = new AgentNode("agent:test", agent);
            node.acceptServices(buildServiceRegistry());

            AgentExecutionRequestDto request = captureRequest(node);

            // mode=none blocks MCP/catalog tools but keeps internal tools enabled
            assertThat(request.systemPrompt())
                .doesNotContain("Search and execute external APIs")
                .contains("Persistent database tables")
                .contains("interface")
                .contains("stateful");

            // The SAME module set must travel to agent-service on the request so the remote
            // loop scopes the core tool SCHEMAS (not just the prompt text). Pre-fix the
            // resolved module set was computed for the prompt then thrown away, so the remote
            // loop billed every core schema regardless of mode. catalog is dropped; the
            // internal modules ride along.
            assertThat(request.enabledModules())
                .doesNotContain("catalog")
                .contains("table", "interface", "workflow");
        }

        @Test
        @DisplayName("tables=[id1,id2] (custom) -> table module included")
        void customTableIdsIncludesTableModule() {
            Map<String, Object> toolsConfig = new HashMap<>();
            toolsConfig.put("tables", List.of("1", "2"));
            when(mockAgentConfigResolver.getToolsConfig(eq(entityId), eq("tenant-1"), isNull()))
                .thenReturn(toolsConfig);

            Agent agent = createAgent(entityId, null, false, "Do something");
            AgentNode node = new AgentNode("agent:test", agent);
            node.acceptServices(buildServiceRegistry());

            AgentExecutionRequestDto request = captureRequest(node);

            // table IS included (custom IDs means accessible)
            assertThat(request.systemPrompt()).contains("Persistent database tables");
        }
    }

    // =====================================================================
    // CREDENTIALS FORWARDING -- allowedXxxIds in credentials
    // =====================================================================

    @Nested
    @DisplayName("Credentials Forwarding")
    class CredentialsForwardingTests {

        private final String entityId = UUID.randomUUID().toString();

        @Test
        @DisplayName("tables=[1,2] -> allowedTableIds=[1,2] in credentials")
        void tableIdsForwardedInCredentials() {
            Map<String, Object> toolsConfig = new HashMap<>();
            toolsConfig.put("tables", List.of("1", "2"));
            when(mockAgentConfigResolver.getToolsConfig(eq(entityId), eq("tenant-1"), isNull()))
                .thenReturn(toolsConfig);

            Agent agent = createAgent(entityId, null, false, "Do something");
            AgentNode node = new AgentNode("agent:test", agent);
            node.acceptServices(buildServiceRegistry());

            AgentExecutionRequestDto request = captureRequest(node);

            assertThat(request.credentials())
                .containsKey("allowedTableIds");
            @SuppressWarnings("unchecked")
            List<String> tableIds = (List<String>) request.credentials().get("allowedTableIds");
            assertThat(tableIds).containsExactly("1", "2");
        }

        @Test
        @DisplayName("interfaces=[] -> allowedInterfaceIds=[] in credentials")
        void emptyInterfaceIdsForwardedInCredentials() {
            Map<String, Object> toolsConfig = new HashMap<>();
            toolsConfig.put("interfaces", List.of());
            when(mockAgentConfigResolver.getToolsConfig(eq(entityId), eq("tenant-1"), isNull()))
                .thenReturn(toolsConfig);

            Agent agent = createAgent(entityId, null, false, "Do something");
            AgentNode node = new AgentNode("agent:test", agent);
            node.acceptServices(buildServiceRegistry());

            AgentExecutionRequestDto request = captureRequest(node);

            assertThat(request.credentials())
                .containsKey("allowedInterfaceIds");
            @SuppressWarnings("unchecked")
            List<String> interfaceIds = (List<String>) request.credentials().get("allowedInterfaceIds");
            assertThat(interfaceIds).isEmpty();
        }

        @Test
        @DisplayName("workflows=[wf-uuid] -> allowedWorkflowIds=[wf-uuid] in credentials")
        void workflowIdsForwardedInCredentials() {
            String wfId = UUID.randomUUID().toString();
            Map<String, Object> toolsConfig = new HashMap<>();
            toolsConfig.put("workflows", List.of(wfId));
            when(mockAgentConfigResolver.getToolsConfig(eq(entityId), eq("tenant-1"), isNull()))
                .thenReturn(toolsConfig);

            Agent agent = createAgent(entityId, null, false, "Do something");
            AgentNode node = new AgentNode("agent:test", agent);
            node.acceptServices(buildServiceRegistry());

            AgentExecutionRequestDto request = captureRequest(node);

            assertThat(request.credentials())
                .containsKey("allowedWorkflowIds");
            @SuppressWarnings("unchecked")
            List<String> wfIds = (List<String>) request.credentials().get("allowedWorkflowIds");
            assertThat(wfIds).containsExactly(wfId);
        }

        @Test
        @DisplayName("null toolsConfig -> all 5 allowedXxxIds set to [] (regression)")
        void nullToolsConfigDeniesAllInternalResources() {
            // Pre-fix this test asserted `doesNotContainKey(...)` for all 5 - i.e.
            // a null toolsConfig left the credential map untouched, and the tool
            // modules' null check fell back to "no restriction" → a workflow agent
            // node with a legacy agent (tools_config IS NULL in DB) ran with full
            // tenant access. Post-fix, applyToolsConfigCredentials wraps null in
            // Map.of() so every passAllowedIds path writes List.of() - denying
            // every internal category until explicit grants are added. Mirrors
            // SubAgentExecutionHandlerTest#subAgentCascadeSetsEmptyCredsWhenChildHasAbsentKey.
            when(mockAgentConfigResolver.getToolsConfig(eq(entityId), eq("tenant-1"), isNull()))
                .thenReturn(null);

            Agent agent = createAgent(entityId, null, false, "Do something");
            AgentNode node = new AgentNode("agent:test", agent);
            node.acceptServices(buildServiceRegistry());

            AgentExecutionRequestDto request = captureRequest(node);

            assertThat(request.credentials().get("allowedTableIds")).isEqualTo(List.of());
            assertThat(request.credentials().get("allowedInterfaceIds")).isEqualTo(List.of());
            assertThat(request.credentials().get("allowedAgentIds")).isEqualTo(List.of());
            assertThat(request.credentials().get("allowedWorkflowIds")).isEqualTo(List.of());
            assertThat(request.credentials().get("allowedApplicationIds")).isEqualTo(List.of());
        }

        @Test
        @DisplayName("Multiple restriction types forwarded together")
        void multipleRestrictionTypesForwarded() {
            Map<String, Object> toolsConfig = new HashMap<>();
            toolsConfig.put("tables", List.of("1"));
            toolsConfig.put("interfaces", List.of());
            toolsConfig.put("agents", List.of("agent-uuid"));
            when(mockAgentConfigResolver.getToolsConfig(eq(entityId), eq("tenant-1"), isNull()))
                .thenReturn(toolsConfig);

            Agent agent = createAgent(entityId, null, false, "Do something");
            AgentNode node = new AgentNode("agent:test", agent);
            node.acceptServices(buildServiceRegistry());

            AgentExecutionRequestDto request = captureRequest(node);

            assertThat(request.credentials()).containsKey("allowedTableIds");
            assertThat(request.credentials()).containsKey("allowedInterfaceIds");
            assertThat(request.credentials()).containsKey("allowedAgentIds");
        }
    }

    // =====================================================================
    // CUSTOM SYSTEM PROMPT -- entity's system prompt overrides modular builder
    // =====================================================================

    @Nested
    @DisplayName("Custom System Prompt Override")
    class CustomSystemPromptTests {

        @Test
        @DisplayName("Entity system prompt is used as-is if non-blank")
        void entitySystemPromptUsedWhenPresent() {
            Agent agent = createAgent(null, "You are a custom assistant", false, "Do something");
            AgentNode node = new AgentNode("agent:test", agent);
            node.acceptServices(buildServiceRegistry());

            AgentExecutionRequestDto request = captureRequest(node);

            assertThat(request.systemPrompt())
                .contains("You are a custom assistant")
                .contains("Current date:");
        }

        @Test
        @DisplayName("Null system prompt triggers modular builder")
        void nullSystemPromptTriggersBuilder() {
            Agent agent = createAgent(null, null, false, "Do something");
            AgentNode node = new AgentNode("agent:test", agent);
            node.acceptServices(buildServiceRegistry());

            AgentExecutionRequestDto request = captureRequest(node);

            // Built by modular builder -> non-blank, contains foundation blocks
            assertThat(request.systemPrompt())
                .isNotBlank()
                .contains("autonomous assistant");
        }
    }

    // =====================================================================
    // REMOTE TOOL DISCOVERY -- tools=null, autoDiscoverTools=true
    // =====================================================================

    @Nested
    @DisplayName("Remote Tool Discovery")
    class RemoteToolDiscoveryTests {

        @Test
        @DisplayName("Tools are null (auto-discovered by agent-service remotely)")
        void toolsAreNullForRemoteDiscovery() {
            Agent agent = createAgent(null, null, false, "Do something");
            AgentNode node = new AgentNode("agent:test", agent);
            node.acceptServices(buildServiceRegistry());

            AgentExecutionRequestDto request = captureRequest(node);

            // Tools are null because agent-service handles tool discovery remotely
            assertThat(request.tools()).isNull();
            assertThat(request.autoDiscoverTools()).isTrue();
            // No entity (entityId=null) → no toolsConfig → enabledModules stays null so
            // agent-service keeps the legacy unrestricted "all core tools" fallback.
            assertThat(request.enabledModules()).isNull();
        }
    }

    // =====================================================================
    // EDGE CASES
    // =====================================================================

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("No AgentConfigResolver -> toolsConfig=null (unrestricted)")
        void noAgentConfigResolverMeansUnrestricted() {
            ServiceRegistry registry = ServiceRegistry.builder()
                .agentClient(mockAgentClient)
                // No agentConfigResolver
                .build();

            String entityId = UUID.randomUUID().toString();
            Agent agent = createAgent(entityId, null, false, "Do something");
            AgentNode node = new AgentNode("agent:test", agent);
            node.acceptServices(registry);

            AgentExecutionRequestDto request = captureRequest(node);

            // Should have all modules
            assertThat(request.systemPrompt())
                .contains("table")
                .contains("interface")
                .contains("stateful");
        }

        @Test
        @DisplayName("toolsConfig with only unknown keys -> all modules enabled (unrestricted)")
        void unknownToolsConfigKeysIgnored() {
            Map<String, Object> toolsConfig = new HashMap<>();
            toolsConfig.put("unknown_field", "value");
            String entityId = UUID.randomUUID().toString();

            when(mockAgentConfigResolver.getToolsConfig(eq(entityId), eq("tenant-1"), isNull()))
                .thenReturn(toolsConfig);

            Agent agent = createAgent(entityId, null, false, "Do something");
            AgentNode node = new AgentNode("agent:test", agent);
            node.acceptServices(buildServiceRegistry());

            AgentExecutionRequestDto request = captureRequest(node);

            // Unknown keys don't block anything
            assertThat(request.systemPrompt())
                .contains("table")
                .contains("interface")
                .contains("stateful");
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /**
     * Create an Agent with specified config for testing.
     */
    private Agent createAgent(String agentConfigId, String systemPrompt,
                               boolean withMemory, String prompt) {
        return new Agent(
            "agent-1",          // id
            "agent",            // type
            "Test Agent",       // label
            agentConfigId,      // agentConfigId
            withMemory,         // withMemory
            "openai",           // provider
            "gpt-4o",           // model
            systemPrompt,       // systemPrompt
            prompt,             // prompt
            0.7,                // temperature
            4096,               // maxTokens
            10,                 // maxIterations
            5,                  // maxTools
            List.of(),          // tools (empty = auto-discover)
            null,               // parentLoopId
            Map.of(),           // params
            List.of(),          // classifyCategories
            null,               // classifyParams
            List.of(),          // guardrailRules
            null                // guardrailParams
        , null);
    }
}
