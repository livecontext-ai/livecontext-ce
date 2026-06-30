package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.tools.workflow.WorkflowHelpProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for WorkflowBuilderHelpModule.
 * Validates help action handling, documentation structure, and topic delegation.
 */
@DisplayName("WorkflowBuilderHelpModule Tests")
@ExtendWith(MockitoExtension.class)
class WorkflowBuilderHelpModuleTest {

    @Mock
    private WorkflowHelpProvider workflowHelpProvider;

    private WorkflowBuilderHelpModule helpModule;

    @BeforeEach
    void setUp() {
        helpModule = new WorkflowBuilderHelpModule(workflowHelpProvider);
    }

    // ==================== canHandle Tests ====================

    @Nested
    @DisplayName("canHandle")
    class CanHandle {

        @Test
        @DisplayName("Should return true for 'help'")
        void canHandleHelp() {
            assertThat(helpModule.canHandle("help")).isTrue();
        }

        @Test
        @DisplayName("Should return false for 'init'")
        void cannotHandleInit() {
            assertThat(helpModule.canHandle("init")).isFalse();
        }

        @Test
        @DisplayName("Should return false for 'add_node'")
        void cannotHandleAddNode() {
            assertThat(helpModule.canHandle("add_node")).isFalse();
        }

        @Test
        @DisplayName("Should return false for null")
        void cannotHandleNull() {
            assertThat(helpModule.canHandle(null)).isFalse();
        }

        @Test
        @DisplayName("Should return false for empty string")
        void cannotHandleEmpty() {
            assertThat(helpModule.canHandle("")).isFalse();
        }
    }

    // ==================== getToolDefinitions Tests ====================

    @Nested
    @DisplayName("getToolDefinitions")
    class GetToolDefinitions {

        @Test
        @DisplayName("Should return empty list (definitions are centralized in provider)")
        void returnsEmptyList() {
            assertThat(helpModule.getToolDefinitions()).isEmpty();
        }
    }

    // ==================== Execute Tests ====================

    @Nested
    @DisplayName("Execute")
    class Execute {

        @Test
        @DisplayName("Help without topics should return generic documentation")
        void helpWithoutTopicsReturnsGenericDoc() {
            Optional<ToolExecutionResult> result = helpModule.execute("help", Map.of(), "tenant-1",
                ToolExecutionContext.of("tenant-1"));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();

            assertThat(data).containsKeys("description", "actions", "node_creation", "examples", "tips", "related_tools");
            verifyNoInteractions(workflowHelpProvider);
        }

        @Test
        @DisplayName("Help with single topic should delegate to WorkflowHelpProvider")
        void helpWithSingleTopicDelegatesToProvider() {
            Map<String, Object> classifyHelp = new LinkedHashMap<>();
            classifyHelp.put("type", "classify");
            classifyHelp.put("description", "AI classification node");
            classifyHelp.put("params", Map.of("prompt", Map.of("required", true)));

            List<String> topics = List.of("classify");
            when(workflowHelpProvider.getHelp(topics)).thenReturn(classifyHelp);

            Optional<ToolExecutionResult> result = helpModule.execute("help",
                Map.of("topics", topics), "tenant-1", ToolExecutionContext.of("tenant-1"));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            assertThat(data).containsKey("type");
            assertThat(data.get("type")).isEqualTo("classify");
            assertThat(data).containsKey("params");

            // Should NOT contain generic help sections
            assertThat(data).doesNotContainKey("actions");
            assertThat(data).doesNotContainKey("tips");

            verify(workflowHelpProvider).getHelp(topics);
        }

        @Test
        @DisplayName("Help with multiple topics should delegate to WorkflowHelpProvider")
        void helpWithMultipleTopicsDelegatesToProvider() {
            Map<String, Object> batchHelp = new LinkedHashMap<>();
            batchHelp.put("batch", true);
            batchHelp.put("requested", 3);
            batchHelp.put("found", 3);
            batchHelp.put("content", Map.of(
                "classify", Map.of("type", "classify"),
                "transform", Map.of("type", "transform"),
                "webhook", Map.of("type", "webhook")
            ));

            List<String> topics = List.of("classify", "transform", "webhook");
            when(workflowHelpProvider.getHelp(topics)).thenReturn(batchHelp);

            Optional<ToolExecutionResult> result = helpModule.execute("help",
                Map.of("topics", topics), "tenant-1", ToolExecutionContext.of("tenant-1"));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            assertThat(data).containsKey("batch");
            assertThat(data.get("batch")).isEqualTo(true);
            assertThat(data).containsKey("content");

            verify(workflowHelpProvider).getHelp(topics);
        }

        @Test
        @DisplayName("Help with topics returning null should fall back to generic help")
        void helpWithTopicsReturningNullFallsBackToGeneric() {
            List<String> topics = List.of("nonexistent");
            when(workflowHelpProvider.getHelp(topics)).thenReturn(null);

            Optional<ToolExecutionResult> result = helpModule.execute("help",
                Map.of("topics", topics), "tenant-1", ToolExecutionContext.of("tenant-1"));

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            // Falls back to generic help
            assertThat(data).containsKeys("description", "actions", "node_creation");

            verify(workflowHelpProvider).getHelp(topics);
        }

        @Test
        @DisplayName("Help with topics returning empty map should fall back to generic help")
        void helpWithTopicsReturningEmptyFallsBackToGeneric() {
            List<String> topics = List.of("nonexistent");
            when(workflowHelpProvider.getHelp(topics)).thenReturn(Map.of());

            Optional<ToolExecutionResult> result = helpModule.execute("help",
                Map.of("topics", topics), "tenant-1", ToolExecutionContext.of("tenant-1"));

            assertThat(result).isPresent();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            assertThat(data).containsKeys("description", "actions");
        }

        @Test
        @DisplayName("Help description should mention init and load as entry points")
        void helpDescriptionMentionsInitAndLoad() {
            Optional<ToolExecutionResult> result = helpModule.execute("help", Map.of(), null,
                ToolExecutionContext.empty());

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            String description = (String) data.get("description");

            assertThat(description).contains("init");
            assertThat(description).contains("load");
            assertThat(description).contains("LIFECYCLE");
        }

        @Test
        @DisplayName("Actions should cover all categories: session, node, connection, inspection, advanced")
        void actionsHaveAllCategories() {
            Optional<ToolExecutionResult> result = helpModule.execute("help", Map.of(), null,
                ToolExecutionContext.empty());

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            @SuppressWarnings("unchecked")
            Map<String, Object> actions = (Map<String, Object>) data.get("actions");

            assertThat(actions).containsKeys(
                "session_management", "node_operations", "connection_operations", "inspection", "advanced");
        }

        @Test
        @DisplayName("Session management should list init, load, save, discard, finish, execute")
        void sessionManagementHasAllActions() {
            Optional<ToolExecutionResult> result = helpModule.execute("help", Map.of(), null,
                ToolExecutionContext.empty());

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            @SuppressWarnings("unchecked")
            Map<String, Object> actions = (Map<String, Object>) data.get("actions");
            @SuppressWarnings("unchecked")
            Map<String, String> sessionActions = (Map<String, String>) actions.get("session_management");

            // 'finish' is the canonical action; 'create' is hidden from help (alias only)
            assertThat(sessionActions).containsKeys("init", "load", "save", "discard", "finish", "execute");
            assertThat(sessionActions.get("finish")).contains("Finalize");
        }

        @Test
        @DisplayName("Node creation docs should list trigger types and step types")
        void nodeCreationDocsHaveTriggerAndStepTypes() {
            Optional<ToolExecutionResult> result = helpModule.execute("help", Map.of(), null,
                ToolExecutionContext.empty());

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            @SuppressWarnings("unchecked")
            Map<String, Object> nodeCreation = (Map<String, Object>) data.get("node_creation");

            assertThat(nodeCreation).containsKeys("syntax", "trigger_types", "step_types", "port_syntax");
        }

        @Test
        @DisplayName("Trigger types should include form, webhook, schedule, table, manual, chat, workflow")
        void triggerTypesComplete() {
            Optional<ToolExecutionResult> result = helpModule.execute("help", Map.of(), null,
                ToolExecutionContext.empty());

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            @SuppressWarnings("unchecked")
            Map<String, Object> nodeCreation = (Map<String, Object>) data.get("node_creation");
            @SuppressWarnings("unchecked")
            Map<String, String> triggerTypes = (Map<String, String>) nodeCreation.get("trigger_types");

            assertThat(triggerTypes).containsKeys("form", "webhook", "schedule", "table", "manual", "chat", "workflow");
        }

        @Test
        @DisplayName("Examples should contain practical workflow building scenarios")
        void examplesContainPracticalScenarios() {
            Optional<ToolExecutionResult> result = helpModule.execute("help", Map.of(), null,
                ToolExecutionContext.empty());

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            @SuppressWarnings("unchecked")
            Map<String, Object> examples = (Map<String, Object>) data.get("examples");

            assertThat(examples).isNotEmpty();
            assertThat(examples).containsKeys("new_workflow", "add_trigger", "add_agent");

            for (Map.Entry<String, Object> entry : examples.entrySet()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> example = (Map<String, Object>) entry.getValue();
                assertThat(example).as("Example '%s' should have 'description'", entry.getKey())
                    .containsKey("description");
            }
        }

        @Test
        @DisplayName("Tips should mention sequential execution and connect_after")
        void tipsContainKeyGuidance() {
            Optional<ToolExecutionResult> result = helpModule.execute("help", Map.of(), null,
                ToolExecutionContext.empty());

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            @SuppressWarnings("unchecked")
            List<String> tips = (List<String>) data.get("tips");

            assertThat(tips).isNotEmpty();

            boolean hasSequentialTip = tips.stream().anyMatch(t -> t.contains("SEQUENTIAL") || t.contains("ONE AT A TIME"));
            boolean hasConnectAfterTip = tips.stream().anyMatch(t -> t.contains("CONNECT_AFTER") || t.contains("connect_after"));
            assertThat(hasSequentialTip).as("Tips should mention sequential execution").isTrue();
            assertThat(hasConnectAfterTip).as("Tips should mention connect_after").isTrue();
        }

        @Test
        @DisplayName("Related tools should reference workflow_help, interface, and application")
        void relatedToolsPresent() {
            Optional<ToolExecutionResult> result = helpModule.execute("help", Map.of(), null,
                ToolExecutionContext.empty());

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            @SuppressWarnings("unchecked")
            Map<String, String> relatedTools = (Map<String, String>) data.get("related_tools");

            assertThat(relatedTools).containsKeys("help_topics", "interface", "application");
        }

        @Test
        @DisplayName("Non-help action should return empty Optional")
        void nonHelpActionReturnsEmpty() {
            Optional<ToolExecutionResult> result = helpModule.execute("init", Map.of(), "tenant-1",
                ToolExecutionContext.of("tenant-1"));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Help should work without tenantId (null)")
        void helpWorksWithNullTenantId() {
            Optional<ToolExecutionResult> result = helpModule.execute("help", Map.of(), null,
                ToolExecutionContext.empty());

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
        }

        @Test
        @DisplayName("Port syntax should document decision, switch, loop, and fork")
        void portSyntaxDocumented() {
            Optional<ToolExecutionResult> result = helpModule.execute("help", Map.of(), null,
                ToolExecutionContext.empty());

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            @SuppressWarnings("unchecked")
            Map<String, Object> nodeCreation = (Map<String, Object>) data.get("node_creation");
            @SuppressWarnings("unchecked")
            Map<String, String> portSyntax = (Map<String, String>) nodeCreation.get("port_syntax");

            assertThat(portSyntax).containsKeys("decision", "switch", "loop", "fork");
        }
    }
}
