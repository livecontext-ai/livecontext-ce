package com.apimarketplace.orchestrator.tools.application;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for ApplicationHelpModule.
 * Validates help action handling and documentation structure.
 */
@DisplayName("ApplicationHelpModule Tests")
class ApplicationHelpModuleTest {

    private ApplicationHelpModule helpModule;

    @BeforeEach
    void setUp() {
        helpModule = new ApplicationHelpModule();
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
        @DisplayName("Should return false for 'list'")
        void cannotHandleList() {
            assertThat(helpModule.canHandle("list")).isFalse();
        }

        @Test
        @DisplayName("Should return false for 'get'")
        void cannotHandleGet() {
            assertThat(helpModule.canHandle("get")).isFalse();
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
        @DisplayName("Help action should return success with all documentation sections")
        void helpReturnsDocumentationSections() {
            ToolExecutionContext context = ToolExecutionContext.of("tenant-1");
            Optional<ToolExecutionResult> result = helpModule.execute("help", Map.of(), "tenant-1", context);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();

            assertThat(data).containsKeys("description", "actions", "parameters", "examples", "tips", "related_tools");
        }

        @Test
        @DisplayName("Help description should surface the owned_by_me branch + data_inputs_schema as the central agent rules")
        void helpDescriptionContainsKeyInfo() {
            ToolExecutionContext context = ToolExecutionContext.of("tenant-1");
            Optional<ToolExecutionResult> result = helpModule.execute("help", Map.of(), "tenant-1", context);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            String description = (String) data.get("description");

            assertThat(description).contains("APPLICATION");
            // The two rules that close the 2026-05-15 regression class:
            assertThat(description).contains("owned_by_me");
            assertThat(description).contains("data_inputs_schema");
        }

        @Test
        @DisplayName("Help actions should be grouped by category and cover all supported actions")
        void helpActionsContainAllActions() {
            ToolExecutionContext context = ToolExecutionContext.of("tenant-1");
            Optional<ToolExecutionResult> result = helpModule.execute("help", Map.of(), "tenant-1", context);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            @SuppressWarnings("unchecked")
            Map<String, Object> actions = (Map<String, Object>) data.get("actions");

            assertThat(actions).containsKeys("marketplace", "publishing", "execution", "run_inspection", "other");

            // Flatten all action keys across groups and assert every supported action is covered.
            Set<String> allActionKeys = new java.util.HashSet<>();
            for (Object group : actions.values()) {
                @SuppressWarnings("unchecked")
                Map<String, String> typed = (Map<String, String>) group;
                allActionKeys.addAll(typed.keySet());
            }
            // Refactor 2026-05-10: marketplace browse renamed `list` → `search`
            // (matches the actual MCP action name; the module exposes `search`,
            // not `list`). All other action names are unchanged.
            assertThat(allActionKeys).contains("create", "search", "my", "get", "acquire", "execute",
                    "runs", "get_run", "get_node_output", "visualize", "help");
        }

        @Test
        @DisplayName("PR5 - search action docs surface typo tolerance, stemming, multi-word, and cross-tool fallback")
        @SuppressWarnings("unchecked")
        void helpSearchDocsCoverFtsCapabilities() {
            ToolExecutionContext context = ToolExecutionContext.of("tenant-1");
            Map<String, Object> data = (Map<String, Object>) helpModule
                    .execute("help", Map.of(), "tenant-1", context).get().data();
            Map<String, Object> actions = (Map<String, Object>) data.get("actions");
            Map<String, String> marketplace = (Map<String, String>) actions.get("marketplace");
            String searchDoc = marketplace.get("search");

            assertThat(searchDoc).isNotNull();
            // The 4 capabilities the agent needs to know about post-PR5
            assertThat(searchDoc).contains("TYPO-TOLERANT", "gmial");
            assertThat(searchDoc).contains("STEMMING", "emails", "email");
            assertThat(searchDoc).contains("MULTI-WORD");
            assertThat(searchDoc).contains("if_no_match_search_tools");
            assertThat(searchDoc).contains("SYNONYMS NOT SUPPORTED");
        }

        @Test
        @DisplayName("response_fields disambiguates id/application_id from workflowId (fix 2026-06-05)")
        @SuppressWarnings("unchecked")
        void responseFieldsDisambiguateIdFromWorkflowId() {
            ToolExecutionContext context = ToolExecutionContext.of("tenant-1");
            Map<String, Object> data = (Map<String, Object>) helpModule
                    .execute("help", Map.of(), "tenant-1", context).get().data();
            Map<String, Object> fields = (Map<String, Object>) data.get("response_fields");

            // The three keys must coexist and each must point at the right param.
            assertThat(fields).containsKeys("id", "application_id", "workflowId");
            assertThat((String) fields.get("id")).contains("application_id");
            assertThat((String) fields.get("workflowId"))
                    .contains("NOT the application_id");

            // Troubleshooting carries the recovery path for the exact mistake.
            Map<String, Object> troubleshooting = (Map<String, Object>) data.get("troubleshooting");
            assertThat(troubleshooting).containsKey("get_or_execute_not_found_on_owned_app");
        }

        @Test
        @DisplayName("Help parameters should contain all documented parameters")
        void helpParametersContainAllParams() {
            ToolExecutionContext context = ToolExecutionContext.of("tenant-1");
            Optional<ToolExecutionResult> result = helpModule.execute("help", Map.of(), "tenant-1", context);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) data.get("parameters");

            assertThat(params).containsKeys("action", "workflow_id", "application_id",
                "title", "description", "query", "category", "limit", "offset");
        }

        @Test
        @DisplayName("Parameter docs should be non-blank strings for each param")
        void parameterDocsAreNonBlankStrings() {
            ToolExecutionContext context = ToolExecutionContext.of("tenant-1");
            Optional<ToolExecutionResult> result = helpModule.execute("help", Map.of(), "tenant-1", context);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) data.get("parameters");

            for (Map.Entry<String, Object> entry : params.entrySet()) {
                assertThat(entry.getValue())
                    .as("Parameter '%s' should be a non-blank string", entry.getKey())
                    .isInstanceOf(String.class);
                assertThat((String) entry.getValue()).isNotBlank();
            }
        }

        @Test
        @DisplayName("Help examples should be Maps with description + (call|steps)")
        void helpExamplesContainStructuredExamples() {
            ToolExecutionContext context = ToolExecutionContext.of("tenant-1");
            Optional<ToolExecutionResult> result = helpModule.execute("help", Map.of(), "tenant-1", context);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            @SuppressWarnings("unchecked")
            Map<String, Object> examples = (Map<String, Object>) data.get("examples");

            assertThat(examples).isNotEmpty();
            assertThat(examples).containsKeys("create", "create_current", "browse", "preview", "acquire",
                "execute_simple", "inspect_run_macro", "inspect_run_epoch", "inspect_node_output", "list_runs");

            // Verify each example is a Map<String,Object> with at least description + (call OR steps).
            for (Map.Entry<String, Object> entry : examples.entrySet()) {
                assertThat(entry.getValue())
                    .as("Example '%s' should be a Map", entry.getKey())
                    .isInstanceOf(Map.class);
                @SuppressWarnings("unchecked")
                Map<String, Object> ex = (Map<String, Object>) entry.getValue();
                assertThat(ex).as("Example '%s'", entry.getKey()).containsKey("description");
                assertThat(ex.containsKey("call") || ex.containsKey("steps"))
                    .as("Example '%s' should have either 'call' or 'steps'", entry.getKey())
                    .isTrue();
            }
        }

        @Test
        @DisplayName("Run inspection examples should reference application tool drill-down chain")
        void inspectRunExamplesReferenceApplicationTool() {
            ToolExecutionContext context = ToolExecutionContext.of("tenant-1");
            Optional<ToolExecutionResult> result = helpModule.execute("help", Map.of(), "tenant-1", context);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            @SuppressWarnings("unchecked")
            Map<String, Object> examples = (Map<String, Object>) data.get("examples");

            assertThat(examples).containsKeys("inspect_run_macro", "inspect_run_epoch", "inspect_node_output", "list_runs");
            @SuppressWarnings("unchecked")
            Map<String, Object> macro = (Map<String, Object>) examples.get("inspect_run_macro");
            assertThat((String) macro.get("call")).contains("application(action='get_run'");
            @SuppressWarnings("unchecked")
            Map<String, Object> nodeOutput = (Map<String, Object>) examples.get("inspect_node_output");
            assertThat((String) nodeOutput.get("call")).contains("application(action='get_node_output'");
        }

        @Test
        @DisplayName("related_tools should expose build_time helpers (run inspection is now built-in)")
        void relatedToolsExposeBuildTime() {
            ToolExecutionContext context = ToolExecutionContext.of("tenant-1");
            Optional<ToolExecutionResult> result = helpModule.execute("help", Map.of(), "tenant-1", context);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            @SuppressWarnings("unchecked")
            Map<String, Object> related = (Map<String, Object>) data.get("related_tools");

            assertThat(related).containsKeys("build_time");

            @SuppressWarnings("unchecked")
            Map<String, String> buildTime = (Map<String, String>) related.get("build_time");
            assertThat(buildTime).containsKeys("workflow", "interface", "agent");
        }

        @Test
        @DisplayName("Help tips should be a non-empty list with create guidance")
        void helpTipsAreNonEmptyList() {
            ToolExecutionContext context = ToolExecutionContext.of("tenant-1");
            Optional<ToolExecutionResult> result = helpModule.execute("help", Map.of(), "tenant-1", context);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            @SuppressWarnings("unchecked")
            List<String> tips = (List<String>) data.get("tips");

            assertThat(tips).isNotEmpty();
            assertThat(tips.size()).isGreaterThanOrEqualTo(5);

            // Should mention create-specific guidance
            boolean hasCreateTip = tips.stream().anyMatch(t -> t.contains("CREATE") || t.contains("create"));
            assertThat(hasCreateTip).as("Tips should mention create action").isTrue();
        }

        @Test
        @DisplayName("tips -> actions cross-refs resolve at BOTH ends: pointers name the topic, and the actions authorities keep the deferred content")
        @SuppressWarnings("unchecked")
        void tipsActionsCrossRefsResolveBothEnds() {
            // 2026-07 dedup: the CREATE and RUN INSPECTION tips defer their detail to the
            // actions section. If the authority loses that content, the pointer dangles -
            // guard both ends (same pattern as publishHelpCrossRefResolves in the workflow
            // factory test).
            ToolExecutionContext context = ToolExecutionContext.of("tenant-1");
            Map<String, Object> data = (Map<String, Object>) helpModule
                    .execute("help", Map.of(), "tenant-1", context).get().data();

            List<String> tips = (List<String>) data.get("tips");
            assertThat(tips).anySatisfy(t -> assertThat(t)
                    .contains("actions.publishing.create")
                    .contains("topics=['actions']"));
            assertThat(tips).anySatisfy(t -> assertThat(t)
                    .contains("actions.run_inspection"));

            Map<String, Object> actions = (Map<String, Object>) data.get("actions");
            Map<String, String> publishing = (Map<String, String>) actions.get("publishing");
            assertThat(publishing.get("create"))
                    .as("authority the CREATE tip defers to")
                    .contains("WAITING_TRIGGER")
                    .contains("run_id")
                    .contains("epoch");
            Map<String, String> runInspection = (Map<String, String>) actions.get("run_inspection");
            assertThat(runInspection.get("get_node_output"))
                    .as("authority the RUN INSPECTION tip defers to")
                    .contains("128 KB")
                    .contains("NEXT");
        }

        @Test
        @DisplayName("topics filter returns only the requested sections plus description and available_topics")
        @SuppressWarnings("unchecked")
        void topicsFilterReturnsOnlyRequestedSections() {
            // The full help payload is ~25KB; topics lets the agent fetch one section
            // instead of paying for all of them on every help call.
            ToolExecutionContext context = ToolExecutionContext.of("tenant-1");
            Map<String, Object> data = (Map<String, Object>) helpModule
                    .execute("help", Map.of("topics", List.of("actions")), "tenant-1", context).get().data();

            assertThat(data).containsKeys("description", "actions", "available_topics");
            assertThat(data).doesNotContainKeys("parameters", "response_fields", "examples",
                    "tips", "troubleshooting", "response_glossary", "related_tools");
        }

        @Test
        @DisplayName("topics filter accepts several sections and is case-insensitive")
        @SuppressWarnings("unchecked")
        void topicsFilterAcceptsSeveralSectionsCaseInsensitive() {
            ToolExecutionContext context = ToolExecutionContext.of("tenant-1");
            Map<String, Object> data = (Map<String, Object>) helpModule
                    .execute("help", Map.of("topics", List.of("EXAMPLES", "troubleshooting")), "tenant-1", context)
                    .get().data();

            assertThat(data).containsKeys("description", "examples", "troubleshooting", "available_topics");
            assertThat(data).doesNotContainKeys("actions", "parameters", "response_glossary");
        }

        @Test
        @DisplayName("a bare string topics value is accepted like the catalog help sibling")
        @SuppressWarnings("unchecked")
        void topicsAcceptsBareString() {
            ToolExecutionContext context = ToolExecutionContext.of("tenant-1");
            Map<String, Object> data = (Map<String, Object>) helpModule
                    .execute("help", Map.of("topics", "actions"), "tenant-1", context).get().data();

            assertThat(data).containsKeys("description", "actions", "available_topics");
            assertThat(data).doesNotContainKeys("parameters", "examples");
        }

        @Test
        @DisplayName("an empty topics list behaves like no filter (full payload)")
        @SuppressWarnings("unchecked")
        void emptyTopicsListMeansNoFilter() {
            ToolExecutionContext context = ToolExecutionContext.of("tenant-1");
            Map<String, Object> data = (Map<String, Object>) helpModule
                    .execute("help", Map.of("topics", List.of()), "tenant-1", context).get().data();

            assertThat(data).containsKeys("description", "actions", "parameters", "examples",
                    "tips", "troubleshooting", "response_glossary", "related_tools");
            assertThat(data).doesNotContainKey("available_topics");
        }

        @Test
        @DisplayName("all-unknown topics fall back to the FULL payload - a typo never returns an empty help")
        @SuppressWarnings("unchecked")
        void unknownTopicsFallBackToFullPayload() {
            ToolExecutionContext context = ToolExecutionContext.of("tenant-1");
            Map<String, Object> data = (Map<String, Object>) helpModule
                    .execute("help", Map.of("topics", List.of("bogus_section")), "tenant-1", context).get().data();

            assertThat(data).containsKeys("description", "actions", "parameters", "response_fields",
                    "examples", "tips", "troubleshooting", "response_glossary", "related_tools");
            assertThat(data).doesNotContainKey("available_topics");
        }

        @Test
        @DisplayName("Non-help action should return empty Optional")
        void nonHelpActionReturnsEmpty() {
            ToolExecutionContext context = ToolExecutionContext.of("tenant-1");
            Optional<ToolExecutionResult> result = helpModule.execute("list", Map.of(), "tenant-1", context);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Help should work without tenantId (null)")
        void helpWorksWithNullTenantId() {
            ToolExecutionContext context = ToolExecutionContext.empty();
            Optional<ToolExecutionResult> result = helpModule.execute("help", Map.of(), null, context);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
        }

        @Test
        @DisplayName("Help description should surface owned_by_me, data_inputs_schema, and the FlyFinder regression cue")
        void helpDescriptionMentionsLifecycleSteps() {
            ToolExecutionContext context = ToolExecutionContext.of("tenant-1");
            Optional<ToolExecutionResult> result = helpModule.execute("help", Map.of(), "tenant-1", context);

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            String description = (String) data.get("description");

            // The owned-vs-not-owned branch + schema-not-guess rule must remain in the
            // top-level description so the agent reads them before any action call.
            assertThat(description).contains("owned_by_me");
            assertThat(description).contains("execute");
            assertThat(description).contains("acquire");
            assertThat(description).contains("data_inputs_schema");
            // The concrete regression cue keeps the agent honest about field names.
            assertThat(description).contains("departure_id");
        }

        @Test
        @DisplayName("Help should expose a response_glossary explaining status / visibility / last_run / data_inputs_schema enums")
        @SuppressWarnings("unchecked")
        void helpExposesResponseGlossary() {
            ToolExecutionContext context = ToolExecutionContext.of("tenant-1");
            Map<String, Object> data = (Map<String, Object>) helpModule
                    .execute("help", Map.of(), "tenant-1", context).get().data();

            Map<String, Object> glossary = (Map<String, Object>) data.get("response_glossary");
            assertThat(glossary).isNotNull();
            assertThat(glossary).containsKeys("status_values", "visibility_values",
                    "last_run_status_values", "data_inputs_schema");

            Map<String, Object> statusValues = (Map<String, Object>) glossary.get("status_values");
            assertThat(statusValues).containsKeys("ACTIVE", "INACTIVE", "PENDING_REVIEW");
        }

        @Test
        @DisplayName("Help should expose a troubleshooting section with the acquire-own escape hatch + execute missing-field error")
        @SuppressWarnings("unchecked")
        void helpExposesTroubleshooting() {
            ToolExecutionContext context = ToolExecutionContext.of("tenant-1");
            Map<String, Object> data = (Map<String, Object>) helpModule
                    .execute("help", Map.of(), "tenant-1", context).get().data();

            Map<String, Object> troubleshooting = (Map<String, Object>) data.get("troubleshooting");
            assertThat(troubleshooting).containsKeys(
                    "acquire_own_publication_error", "execute_missing_field_error");
            assertThat(troubleshooting.get("acquire_own_publication_error").toString())
                    .contains("application(action='create'");
        }
    }
}
