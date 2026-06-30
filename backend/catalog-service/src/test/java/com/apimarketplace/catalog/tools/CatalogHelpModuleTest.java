package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogHelpModuleTest {

    private CatalogHelpModule module;

    @BeforeEach
    void setUp() {
        module = new CatalogHelpModule();
    }

    @Nested
    @DisplayName("canHandle")
    class CanHandle {

        @Test
        void shouldHandleHelp() {
            assertThat(module.canHandle("help")).isTrue();
        }

        @Test
        void shouldNotHandleOtherActions() {
            assertThat(module.canHandle("search")).isFalse();
            assertThat(module.canHandle("execute")).isFalse();
            assertThat(module.canHandle("register_api")).isFalse();
            assertThat(module.canHandle("response_schema")).isFalse();
        }
    }

    @Nested
    @DisplayName("General help (no topics)")
    class GeneralHelp {

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnOverviewWithExpectedKeys() {
            Optional<ToolExecutionResult> result = module.execute("help", Map.of(), null, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            assertThat(data).containsKeys("title", "actions", "topics", "usage_flow_existing", "usage_flow_custom");
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldListAvailableTopics() {
            Optional<ToolExecutionResult> result = module.execute("help", Map.of(), null, null);
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            Map<String, Object> topics = (Map<String, Object>) data.get("topics");
            assertThat(topics).containsKeys("register", "schema", "shaping", "file_storage");
        }

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("search help explains API-scoped search")
        void searchHelpExplainsApiScopedSearch() {
            Optional<ToolExecutionResult> result = module.execute("help", Map.of(), null, null);
            Map<String, Object> data = (Map<String, Object>) result.get().data();

            assertThat(data.get("actions").toString()).contains("api='gmail'");
            assertThat(data.get("usage_flow_existing").toString())
                .contains("apis=['gmail','slack']")
                .contains("[gmail, slack] send message")
                .contains("gmail, list messages");
        }
    }

    @Nested
    @DisplayName("Topic: file_storage")
    class FileStorageTopic {
        @Test
        @DisplayName("returns FileStorageHelp.get() verbatim (anti-drift with InterfaceHelpModule)")
        @SuppressWarnings("unchecked")
        void fileStorageTopicReturnsSharedHelp() {
            Optional<ToolExecutionResult> result = module.execute("help",
                Map.of("topics", List.of("file_storage")), null, null);
            assertThat(result).isPresent();

            Map<String, Object> data = (Map<String, Object>) result.get().data();
            assertThat(data).containsKey("file_storage");
            // Must equal the shared map verbatim - same anti-drift contract is
            // pinned in InterfaceHelpModuleTest.filesSectionMatchesSharedHelp.
            assertThat(data.get("file_storage"))
                .isEqualTo(com.apimarketplace.agent.tools.help.FileStorageHelp.get());
        }
    }

    @Nested
    @DisplayName("Topic: shaping")
    class ShapingTopic {

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnShapingHelpWithExpectedKeys() {
            Optional<ToolExecutionResult> result = module.execute("help",
                Map.of("topics", List.of("shaping")), null, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            assertThat(data).containsKey("shaping");
            Map<String, Object> shapingHelp = (Map<String, Object>) data.get("shaping");
            assertThat(shapingHelp).containsKeys(
                "title", "why", "default_behavior", "response_metadata_to_read",
                "when_to_use_expand", "when_to_use_max_items",
                "when_nextAction_has_concrete_params", "when_nextAction_is_prose_only",
                "filerefs", "opaque_cursors", "dont"
            );
        }

        @Test
        @SuppressWarnings("unchecked")
        void shapingHelpWarnsAgainstGetToolResultForCurrentTurn() {
            // The help must explicitly tell the agent NOT to call get_tool_result
            // for the current turn - this is the primary boundary between
            // get_tool_result (compaction recovery) and shaping (current turn).
            Optional<ToolExecutionResult> result = module.execute("help",
                Map.of("topics", List.of("shaping")), null, null);
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            Map<String, Object> shapingHelp = (Map<String, Object>) data.get("shaping");
            List<?> dontList = (List<?>) shapingHelp.get("dont");
            assertThat(dontList.toString()).contains("get_tool_result");
        }
    }

    @Nested
    @DisplayName("Topic: register")
    class RegisterTopic {

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnRegisterHelpWithExpectedKeys() {
            Optional<ToolExecutionResult> result = module.execute("help",
                Map.of("topics", List.of("register")), null, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            assertThat(data).containsKey("register");
            Map<String, Object> registerHelp = (Map<String, Object>) data.get("register");
            assertThat(registerHelp).containsKeys(
                "title", "minimum_example", "api_level_fields", "endpoint_fields",
                "param_fields", "execution_modes", "output_schema", "synthesis_fields",
                "fixtures", "registration_steps"
            );
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldIncludeApiFixturesInOptionalFields() {
            Optional<ToolExecutionResult> result = module.execute("help",
                Map.of("topics", List.of("register")), null, null);
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            Map<String, Object> registerHelp = (Map<String, Object>) data.get("register");
            Map<String, Object> apiLevelFields = (Map<String, Object>) registerHelp.get("api_level_fields");
            Map<String, Object> optional = (Map<String, Object>) apiLevelFields.get("optional");
            assertThat(optional).containsKey("apiFixtures");
        }
    }

    @Nested
    @DisplayName("Topic: schema")
    class SchemaTopic {

        @Test
        @SuppressWarnings("unchecked")
        void shouldReturnSchemaHelpWithExpectedKeys() {
            Optional<ToolExecutionResult> result = module.execute("help",
                Map.of("topics", List.of("schema")), null, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            assertThat(data).containsKey("schema");
            Map<String, Object> schemaHelp = (Map<String, Object>) data.get("schema");
            assertThat(schemaHelp).containsKeys(
                "title", "skeleton_format", "spel_mapping", "workflow_usage", "best_practices"
            );
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldUseCorrectWorkflowReferenceSyntax() {
            Optional<ToolExecutionResult> result = module.execute("help",
                Map.of("topics", List.of("schema")), null, null);
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            Map<String, Object> schemaHelp = (Map<String, Object>) data.get("schema");
            Map<String, Object> workflowUsage = (Map<String, Object>) schemaHelp.get("workflow_usage");
            Map<String, Object> crossStepRef = (Map<String, Object>) workflowUsage.get("cross_step_reference");
            assertThat((String) crossStepRef.get("syntax")).contains("{{mcp:");
            assertThat((String) crossStepRef.get("example")).contains("{{mcp:");
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldIncludeToolIdInBestPractices() {
            Optional<ToolExecutionResult> result = module.execute("help",
                Map.of("topics", List.of("schema")), null, null);
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            Map<String, Object> schemaHelp = (Map<String, Object>) data.get("schema");
            List<String> bestPractices = (List<String>) schemaHelp.get("best_practices");
            String responseSchemaEntry = bestPractices.stream()
                .filter(s -> s.contains("response_schema"))
                .findFirst().orElseThrow();
            assertThat(responseSchemaEntry).contains("tool_id=");
        }
    }

    @Nested
    @DisplayName("V145 - webhook mode is no longer mentioned")
    class V145WebhookRemoved {

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("register topic must not mention webhook (V145 retired the mode)")
        void helpDoesNotMentionWebhookMode() {
            // Regression guard: V145 retired the webhook execution mode. Agent-facing help
            // must not advertise it (otherwise the LLM would attempt to use a value that the
            // submission validator + CHECK constraint now reject).
            Optional<ToolExecutionResult> result = module.execute("help",
                Map.of("topics", List.of("register")), null, null);
            assertThat(result).isPresent();
            String fullJson = result.get().data().toString();
            // Tolerate occurrences inside generic OAuth refresh/auth paragraphs that mention
            // 'webhook' as an unrelated noun: scope the assertion to the execution-mode area.
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            Map<String, Object> registerHelp = (Map<String, Object>) data.get("register");
            Map<String, Object> executionModes = (Map<String, Object>) registerHelp.get("execution_modes");
            assertThat(executionModes).doesNotContainKey("webhook");
            assertThat(executionModes).doesNotContainKey("note");
            assertThat(executionModes.toString().toLowerCase()).doesNotContain("webhook");
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @SuppressWarnings("unchecked")
        void shouldHandleUnknownTopic() {
            Optional<ToolExecutionResult> result = module.execute("help",
                Map.of("topics", List.of("nonexistent")), null, null);

            assertThat(result).isPresent();
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            assertThat(data).containsKey("nonexistent");
            Map<String, Object> errorEntry = (Map<String, Object>) data.get("nonexistent");
            assertThat(errorEntry).containsKey("error");
            assertThat(errorEntry).containsKey("valid_topics");
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldHandleMultipleTopics() {
            Optional<ToolExecutionResult> result = module.execute("help",
                Map.of("topics", List.of("register", "schema")), null, null);

            assertThat(result).isPresent();
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            assertThat(data).containsKeys("register", "schema");
        }

        @Test
        @SuppressWarnings("unchecked")
        void shouldHandleTopicsAsString() {
            Optional<ToolExecutionResult> result = module.execute("help",
                Map.of("topics", "register"), null, null);

            assertThat(result).isPresent();
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            assertThat(data).containsKey("register");
        }

        @Test
        void shouldReturnEmptyForNonHelpAction() {
            Optional<ToolExecutionResult> result = module.execute("search", Map.of(), null, null);
            assertThat(result).isEmpty();
        }
    }
}
