package com.apimarketplace.agent.tools.agent;

import com.apimarketplace.agent.tools.agent.AgentConversationModule;
import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentToolsProvider Tests")
class AgentToolsProviderTest {

    @Mock private AgentCrudModule crudModule;
    @Mock private AgentHelpModule helpModule;
    @Mock private AgentConversationModule conversationModule;
    @Mock private AgentDelegationModule delegationModule;
    @Mock private AgentPublishModule publishModule;
    @Mock private AgentTaskContextModule taskContextModule;

    private AgentToolsProvider provider;

    private static final String TENANT = "tenant-123";

    @BeforeEach
    void setUp() {
        provider = new AgentToolsProvider(crudModule, helpModule, conversationModule,
                delegationModule, publishModule, taskContextModule);
    }

    @Nested
    @DisplayName("Tool Definitions")
    class ToolDefinitions {

        @Test
        @DisplayName("getTools() returns exactly 1 tool named 'agent'")
        void returnsSingleAgentTool() {
            List<AgentToolDefinition> tools = provider.getTools();
            assertThat(tools).hasSize(1);
            assertThat(tools.get(0).name()).isEqualTo("agent");
        }

        @Test
        @DisplayName("getCategory() returns AGENT")
        void categoryIsAgent() {
            assertThat(provider.getCategory()).isEqualTo(ToolCategory.AGENT);
        }

        @Test
        @DisplayName("Tool has 'action' as only required parameter")
        void actionIsOnlyRequired() {
            AgentToolDefinition tool = provider.getTools().get(0);
            assertThat(tool.requiredParameters()).containsExactly("action");
        }

        @Test
        @DisplayName("Tool has all expected parameters")
        void hasAllParams() {
            AgentToolDefinition tool = provider.getTools().get(0);
            List<String> names = tool.parameters().stream().map(p -> p.name()).toList();
            assertThat(names).contains("action", "agent_id", "name", "system_prompt",
                "model_provider", "model_name", "temperature", "max_tokens",
                "prompt", "limit", "offset");
        }

        @Test
        @DisplayName("Tool requires auth")
        void requiresAuth() {
            assertThat(provider.getTools().get(0).requiresAuth()).isTrue();
        }

        @Test
        @DisplayName("tools_mode enum accepts 'off' (the no-tools judge mode) alongside all/none/custom")
        void toolsModeEnumIncludesOff() {
            // ToolParameterValidator rejects any tools_mode value NOT in this enum (INVALID_ENUM_VALUE
            // → VALIDATION_ERROR), so 'off' MUST be listed or agent(action='create', tools_mode='off')
            // fails - even though AgentHelpModule documents it. Guards the agent-facing entry point of
            // the reasoning-only (zero tool schemas) agent option.
            ToolParameter toolsMode = findParam("tools_mode");
            assertThat(toolsMode.enumValues())
                .as("tools_mode must allow 'off' so a reasoning-only agent is reachable via the agent tool")
                .containsExactlyInAnyOrder("all", "none", "off", "custom");
        }

        @Test
        @DisplayName("model_provider is a plain stringParam, NOT an enum")
        void modelProviderIsNotEnum() {
            // Regression guard: the old schema had a hardcoded
            // ["openai", "anthropic", "google", "mistral", "deepseek"] enum that
            // was static per-process AND leaked training-data-era provider names
            // to every LLM. The authoritative catalog now comes from the live
            // injected "Available AI Models" section (see AgentContextBuilder).
            // If someone re-adds an enum here they will silently cap the list
            // again - fail loudly instead.
            ToolParameter modelProvider = findParam("model_provider");
            assertThat(modelProvider.enumValues())
                    .as("model_provider must NOT have an enum - the list is tenant-dynamic, live-injected")
                    .isNull();
        }

        @Test
        @DisplayName("model_provider description marks the field OPTIONAL and routes to the live catalog action")
        void modelProviderDescriptionWarns() {
            // The field is optional (default = platform model #1) and unknown
            // values are silently substituted - the description must say so,
            // and route the LLM to help_models (the dedicated catalog action,
            // not the slim default help) when it DOES want to pick a specific
            // provider.
            ToolParameter modelProvider = findParam("model_provider");
            assertThat(modelProvider.description())
                    .contains("OPTIONAL")
                    .contains("model_substituted")
                    .contains("help_models");
        }

        @Test
        @DisplayName("model_name description marks the field OPTIONAL, routes to help_models, no training-data bait")
        void modelNameDescriptionWarns() {
            ToolParameter modelName = findParam("model_name");
            assertThat(modelName.description())
                    .contains("OPTIONAL")
                    .contains("model_substituted")
                    .contains("help_models");
            // Belt-and-suspenders: make sure we didn't accidentally re-introduce
            // the old training-data bait example (`gpt-4`, `claude-3-opus`) that
            // primed the LLM to hallucinate those exact names.
            assertThat(modelName.description())
                    .doesNotContain("e.g., gpt-4, claude-3-opus");
        }

        @Test
        @DisplayName("file_access_mode is in the schema with enum read/write - AgentCrudModule consumes it, so the LLM must be able to discover it")
        void fileAccessModeIsAdvertised() {
            // Regression guard: create/update forward file_access_mode to toolsConfig.fileAccessMode
            // (AgentCrudModule) and AgentHelpModule documents it, but the param was missing from the
            // tool schema - an agent could never discover it. It must stay advertised.
            ToolParameter fileAccessMode = findParam("file_access_mode");
            assertThat(fileAccessMode.enumValues()).containsExactlyInAnyOrder("read", "write");
        }

        @Test
        @DisplayName("RESOURCE GRANTS cross-references resolve: params point at a block that exists in the tool description")
        void resourceGrantsCrossRefResolves() {
            // The grant/list/access-mode params share one semantics block ('RESOURCE GRANTS') in the
            // tool description instead of repeating it per param. If the block is renamed or dropped,
            // every 'see RESOURCE GRANTS' pointer becomes a dangling reference the LLM cannot follow.
            AgentToolDefinition tool = provider.getTools().get(0);
            boolean anyParamPointsAtBlock = tool.parameters().stream()
                    .anyMatch(p -> p.description() != null && p.description().contains("RESOURCE GRANTS"));
            assertThat(anyParamPointsAtBlock).isTrue();
            assertThat(tool.description()).contains("RESOURCE GRANTS");
            // The block must carry the full shared semantics the per-param texts no longer repeat.
            assertThat(tool.description())
                    .contains("'none'=no access")
                    .contains("'custom'=only the listed IDs")
                    .contains("Omit to derive from the list");
        }

        @Test
        @DisplayName("ROLE RULE cross-reference resolves: the description points at a rule that exists on the action param")
        void roleRuleCrossRefResolves() {
            // The assignee-vs-reviewer rule used to be stated 3x in the same payload; it now
            // lives ONLY on the action param and the description points at it. If the pointer
            // or the rule disappears, a reviewer agent may call task_complete on a review task.
            AgentToolDefinition tool = provider.getTools().get(0);
            assertThat(tool.description()).contains("ROLE RULE");
            ToolParameter action = findParam("action");
            assertThat(action.description())
                    .contains("ROLE RULE")
                    .contains("NEVER task_complete");
        }

        @Test
        @DisplayName("max_tokens advertises the platform default (16000), matching the runtime default")
        void maxTokensSchemaDefaultMatchesRuntime() {
            // Regression guard: the advertised schema default must track
            // AgentDefaultsConfig.maxTokens (and AgentHelpModule). A stale value here
            // tells the LLM a different default than the one applied server-side.
            assertThat(findParam("max_tokens").defaultValue()).isEqualTo(16000);
        }

        private ToolParameter findParam(String name) {
            AgentToolDefinition tool = provider.getTools().get(0);
            return tool.parameters().stream()
                    .filter(p -> name.equals(p.name()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Parameter not found: " + name));
        }
    }

    @Nested
    @DisplayName("Execute Routing")
    class ExecuteRouting {

        private ToolExecutionContext ctx(String tenantId) {
            return new ToolExecutionContext(tenantId, null, Map.of(), null, null, null, null, null);
        }

        @Test
        @DisplayName("Unknown tool name returns failure")
        void unknownToolFails() {
            ToolExecutionResult result = provider.execute("unknown", Map.of("action", "list"), ctx(TENANT));
            assertThat(result.success()).isFalse();
        }

        @Test
        @DisplayName("Missing action returns failure")
        void missingActionFails() {
            ToolExecutionResult result = provider.execute("agent", Map.of(), ctx(TENANT));
            assertThat(result.success()).isFalse();
            assertThat(result.toMap().toString()).contains("action is required");
        }

        @Test
        @DisplayName("Null tenantId for non-help action returns failure")
        void nullTenantFails() {
            ToolExecutionResult result = provider.execute("agent", Map.of("action", "list"), ctx(null));
            assertThat(result.success()).isFalse();
            assertThat(result.toMap().toString()).contains("tenantId");
        }

        @Test
        @DisplayName("Help action works without tenantId")
        void helpWithoutTenant() {
            when(helpModule.canHandle("help")).thenReturn(true);
            when(helpModule.execute(eq("help"), any(), isNull(), any()))
                .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("docs", "ok"))));

            ToolExecutionResult result = provider.execute("agent", Map.of("action", "help"), ctx(null));
            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("CRUD actions route to crudModule")
        void crudActionsRoute() {
            for (String action : List.of("create", "get", "list", "update", "delete")) {
                when(crudModule.canHandle(action)).thenReturn(true);
                when(crudModule.execute(eq(action), any(), eq(TENANT), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("status", "OK"))));

                ToolExecutionResult result = provider.execute("agent", Map.of("action", action), ctx(TENANT));
                assertThat(result.success()).as("Action '%s' should succeed", action).isTrue();
            }
        }

        @Test
        @DisplayName("Execute action returns retry-guidance error when unrouted")
        void executeNotMigrated() {
            ToolExecutionResult result = provider.execute("agent", Map.of("action", "execute"), ctx(TENANT));
            assertThat(result.success()).isFalse();
            assertThat(result.toMap().toString()).contains("could not be handled");
        }

        @Test
        @DisplayName("Invalid action returns failure")
        void invalidActionFails() {
            ToolExecutionResult result = provider.execute("agent", Map.of("action", "bogus"), ctx(TENANT));
            assertThat(result.success()).isFalse();
            assertThat(result.toMap().toString()).contains("Invalid action");
        }

        @Test
        @DisplayName("Conversation actions route to conversationModule and its result flows through")
        void conversationActionsRoute() {
            // The conversation actions (get_history, search_messages, share, unshare,
            // refresh_share) must hit conversationModule, not any earlier module - and
            // its result must be the provider's result verbatim.
            for (String action : List.of("get_history", "search_messages", "share", "unshare", "refresh_share")) {
                when(conversationModule.canHandle(action)).thenReturn(true);
                when(conversationModule.execute(eq(action), any(), eq(TENANT), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("module", "conversation"))));

                ToolExecutionResult result = provider.execute("agent", Map.of("action", action), ctx(TENANT));

                assertThat(result.success()).as("Conversation action '%s' should succeed", action).isTrue();
                assertThat(result.data()).isEqualTo(Map.of("module", "conversation"));
                verify(conversationModule).execute(eq(action), any(), eq(TENANT), any());
            }
        }

        @Test
        @DisplayName("Delegation actions route to delegationModule and its result flows through")
        void delegationActionsRoute() {
            // A representative sample across the assignee path (assign), reviewer path
            // (review_inbox), backlog (claim) and recurrences (recurrence_create) - all
            // owned by delegationModule.
            for (String action : List.of("assign", "task_complete", "review_inbox", "claim", "recurrence_create")) {
                when(delegationModule.canHandle(action)).thenReturn(true);
                when(delegationModule.execute(eq(action), any(), eq(TENANT), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("module", "delegation"))));

                ToolExecutionResult result = provider.execute("agent", Map.of("action", action), ctx(TENANT));

                assertThat(result.success()).as("Delegation action '%s' should succeed", action).isTrue();
                assertThat(result.data()).isEqualTo(Map.of("module", "delegation"));
                verify(delegationModule).execute(eq(action), any(), eq(TENANT), any());
            }
        }

        @Test
        @DisplayName("Publish and unpublish actions route to publishModule and its result flows through")
        void publishActionsRoute() {
            for (String action : List.of("publish", "unpublish")) {
                when(publishModule.canHandle(action)).thenReturn(true);
                when(publishModule.execute(eq(action), any(), eq(TENANT), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("module", "publish"))));

                ToolExecutionResult result = provider.execute("agent", Map.of("action", action), ctx(TENANT));

                assertThat(result.success()).as("Publish action '%s' should succeed", action).isTrue();
                assertThat(result.data()).isEqualTo(Map.of("module", "publish"));
                verify(publishModule).execute(eq(action), any(), eq(TENANT), any());
            }
        }

        @Test
        @DisplayName("Task-context actions route to taskContextModule and its result flows through")
        void taskContextActionsRoute() {
            for (String action : List.of("task_get_context", "task_get_execution")) {
                when(taskContextModule.canHandle(action)).thenReturn(true);
                when(taskContextModule.execute(eq(action), any(), eq(TENANT), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("module", "taskContext"))));

                ToolExecutionResult result = provider.execute("agent", Map.of("action", action), ctx(TENANT));

                assertThat(result.success()).as("Task-context action '%s' should succeed", action).isTrue();
                assertThat(result.data()).isEqualTo(Map.of("module", "taskContext"));
                verify(taskContextModule).execute(eq(action), any(), eq(TENANT), any());
            }
        }

        @Test
        @DisplayName("CRUD module returning Optional.empty() yields the CRUD-specific failure message")
        void crudModuleEmptyOptionalFails() {
            // The module accepts the action (canHandle=true) but returns no result -
            // the provider must surface a deterministic, action-named failure rather
            // than throwing or returning success.
            when(crudModule.canHandle("list")).thenReturn(true);
            when(crudModule.execute(eq("list"), any(), eq(TENANT), any())).thenReturn(Optional.empty());

            ToolExecutionResult result = provider.execute("agent", Map.of("action", "list"), ctx(TENANT));

            assertThat(result.success()).isFalse();
            assertThat(result.toMap().toString()).contains("CRUD module failed for action: list");
        }

        @Test
        @DisplayName("Conversation module returning Optional.empty() yields the conversation-specific failure message")
        void conversationModuleEmptyOptionalFails() {
            when(conversationModule.canHandle("get_history")).thenReturn(true);
            when(conversationModule.execute(eq("get_history"), any(), eq(TENANT), any())).thenReturn(Optional.empty());

            ToolExecutionResult result = provider.execute("agent", Map.of("action", "get_history"), ctx(TENANT));

            assertThat(result.success()).isFalse();
            assertThat(result.toMap().toString()).contains("Conversation module failed for action: get_history");
        }

        @Test
        @DisplayName("Delegation module returning Optional.empty() yields the delegation-specific failure message")
        void delegationModuleEmptyOptionalFails() {
            when(delegationModule.canHandle("assign")).thenReturn(true);
            when(delegationModule.execute(eq("assign"), any(), eq(TENANT), any())).thenReturn(Optional.empty());

            ToolExecutionResult result = provider.execute("agent", Map.of("action", "assign"), ctx(TENANT));

            assertThat(result.success()).isFalse();
            assertThat(result.toMap().toString()).contains("Delegation module failed for action: assign");
        }

        @Test
        @DisplayName("Task-context module returning Optional.empty() yields the task-context-specific failure message")
        void taskContextModuleEmptyOptionalFails() {
            when(taskContextModule.canHandle("task_get_context")).thenReturn(true);
            when(taskContextModule.execute(eq("task_get_context"), any(), eq(TENANT), any())).thenReturn(Optional.empty());

            ToolExecutionResult result = provider.execute("agent", Map.of("action", "task_get_context"), ctx(TENANT));

            assertThat(result.success()).isFalse();
            assertThat(result.toMap().toString()).contains("Task-context module failed for action: task_get_context");
        }

        @Test
        @DisplayName("Exception thrown by a module is caught and surfaced as an 'Error:' failure")
        void moduleExceptionIsCaughtAndSurfaced() {
            // The execute() body is wrapped in a try/catch that logs and converts any
            // exception into a failure carrying the exception message - the provider
            // must never let a module's RuntimeException escape to the caller.
            when(crudModule.canHandle("list")).thenReturn(true);
            when(crudModule.execute(eq("list"), any(), eq(TENANT), any()))
                .thenThrow(new RuntimeException("boom from crud"));

            ToolExecutionResult result = provider.execute("agent", Map.of("action", "list"), ctx(TENANT));

            assertThat(result.success()).isFalse();
            assertThat(result.toMap().toString()).contains("Error: boom from crud");
        }
    }
}
