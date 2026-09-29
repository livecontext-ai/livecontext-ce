package com.apimarketplace.orchestrator.tools.workflow.builder;

import static org.assertj.core.api.Assertions.assertThat;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.orchestrator.domain.NodeTypeDocumentationEntity;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import com.apimarketplace.orchestrator.tools.workflow.builder.validation.StepValidator;
import com.apimarketplace.orchestrator.tools.workflow.builder.session.SessionPlanBuilder;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * A classify or guardrail node coming back from a stored plan must be readable by the
 * builder session, and must go back out carrying what the session holds.
 *
 * <p>Bug, hit live on 2026-09-28: the export renames {@code categories} to
 * {@code classifyCategories} (and {@code rules} to {@code guardrailRules},
 * {@code content} to {@code classifyParams}/{@code guardrailParams}), but neither
 * {@code load} nor {@code set_plan} renamed them back. After a {@code load},
 * {@code modify} on a classify prompt reported the categories as {@code "(not set)"}
 * and {@code validate}/{@code finish} refused the workflow for missing categories that
 * the stored plan did hold. On a guardrail it was silent: both spellings stayed on the
 * node, and since the export only writes {@code guardrailRules} when absent, a rules
 * edit was reported OK and never reached the engine.
 */
@DisplayName("classify and guardrail nodes keep one spelling through load, set_plan and export")
class ClassifyGuardrailPlanSpellingTest {

    private static final List<Map<String, Object>> CATEGORIES = List.of(
            Map.of("label", "billing", "description", "Money matters"),
            Map.of("label", "technical", "description", "Bugs and errors"));

    /** A classify node as the canvas and the export store it: plan spelling, no flags. */
    private static Map<String, Object> storedClassify() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "f48489fb-665a-4523-9ef5-012a17de316b");
        node.put("type", "classify");
        node.put("label", "Classify ticket");
        node.put("prompt", "Sort this ticket: {{trigger:start.output.body}}");
        node.put("classifyCategories", CATEGORIES);
        node.put("classifyParams", "{{trigger:start.output.body}}");
        return node;
    }

    /**
     * A guardrail saved by the builder before this fix: both spellings, and the canvas
     * has since edited the plan-side rules, which is what the engine runs.
     */
    private static Map<String, Object> storedGuardrail() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "0b4c7f8e-1a2b-4c3d-9e8f-001122334455");
        node.put("type", "guardrail");
        node.put("label", "Check reply");
        node.put("rules", List.of(Map.of("type", "pii", "action", "block")));
        node.put("guardrailRules", List.of(Map.of("type", "toxicity", "action", "flag")));
        node.put("guardrailParams", "{{trigger:start.output.body}}");
        return node;
    }

    private static Map<String, Object> planWith(Map<String, Object>... agents) {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("label", "start");
        trigger.put("type", "form");
        trigger.put("params", new LinkedHashMap<>(Map.of(
                "fields", List.of(Map.of("name", "body", "label", "Body", "type", "text")))));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(trigger)));
        plan.put("agents", new ArrayList<>(List.of(agents)));
        List<Map<String, Object>> edges = new ArrayList<>();
        for (Map<String, Object> agent : agents) {
            edges.add(new LinkedHashMap<>(Map.of("from", "trigger:start", "to", agent.get("label"))));
        }
        plan.put("edges", edges);
        return plan;
    }

    private static WorkflowBuilderSession load(Map<String, Object> plan) throws Exception {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(UUID.randomUUID());
        workflow.setName("Triage");
        workflow.setPlan(plan);
        WorkflowBuilderLoader loader =
                Mockito.mock(WorkflowBuilderLoader.class, Mockito.CALLS_REAL_METHODS);
        Method method = WorkflowBuilderLoader.class.getDeclaredMethod(
                "convertWorkflowToSession", WorkflowEntity.class, String.class, String.class);
        method.setAccessible(true);
        return (WorkflowBuilderSession) method.invoke(loader, workflow, "test-tenant", "conv-1");
    }

    private static Map<String, Object> node(WorkflowBuilderSession session, String label) {
        return session.getMcps().stream()
                .filter(n -> label.equals(n.get("label")))
                .findFirst()
                .orElseThrow();
    }

    private static List<Object> labels(Object list) {
        return ((List<?>) list).stream().map(c -> ((Map<?, ?>) c).get("label")).map(Object.class::cast).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> exported(WorkflowBuilderSession session, String label) {
        List<Map<String, Object>> agents = (List<Map<String, Object>>) session.buildPlanMap().get("agents");
        return agents.stream().filter(a -> label.equals(a.get("label"))).findFirst().orElseThrow();
    }

    @Nested
    @DisplayName("after load")
    class AfterLoad {

        @Test
        @DisplayName("a classify node exposes its categories under the key validate and modify read")
        void classifyCategoriesAreVisibleToTheSession() throws Exception {
            Map<String, Object> node = node(load(planWith(storedClassify())), "Classify ticket");

            assertThat(node.get("categories"))
                    .as("validate checks 'categories': without it a loaded classify can never be finished")
                    .isEqualTo(CATEGORIES);
            assertThat(node).containsEntry("isClassify", true)
                    .containsEntry("content", "{{trigger:start.output.body}}");
            assertThat(node)
                    .as("the plan spelling is removed, so the export cannot carry a stale copy")
                    .doesNotContainKeys("classifyCategories", "classifyParams");
        }

        @Test
        @DisplayName("a guardrail keeps the rules the engine was running, not the stale session copy")
        void guardrailPlanRulesWin() throws Exception {
            Map<String, Object> node = node(load(planWith(storedGuardrail())), "Check reply");

            assertThat(node.get("rules"))
                    .as("guardrailRules is what the last run read and what the canvas edits")
                    .isEqualTo(List.of(Map.of("type", "toxicity", "action", "flag")));
            assertThat(node).containsEntry("isGuardrail", true)
                    .containsEntry("content", "{{trigger:start.output.body}}")
                    .doesNotContainKeys("guardrailRules", "guardrailParams");
        }

        @Test
        @DisplayName("an unmodified load exports the same config it loaded")
        void aRoundTripIsANoOp() throws Exception {
            WorkflowBuilderSession session = load(planWith(storedClassify()));

            Map<String, Object> out = exported(session, "Classify ticket");
            assertThat(out.get("classifyCategories")).isEqualTo(CATEGORIES);
            assertThat(out.get("classifyParams")).isEqualTo("{{trigger:start.output.body}}");
            assertThat(out).doesNotContainKey("categories");
        }
    }

    @Nested
    @DisplayName("load, then modify with the spelling get_plan shows")
    class ThroughModify {

        private final WorkflowBuilderModifier modifier =
                new WorkflowBuilderModifier(Mockito.mock(WorkflowBuilderSessionStore.class));

        private void modify(WorkflowBuilderSession session, String node, Map<String, Object> params) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", node);
            args.put("params", params);
            ToolExecutionResult result = modifier.executeModifyNode(session, args);
            assertThat(result.success()).as(String.valueOf(result.data())).isTrue();
        }

        @Test
        @DisplayName("editing only the prompt leaves a workflow validate accepts (the reported symptom)")
        void promptEditThenValidatePasses() throws Exception {
            WorkflowBuilderSession session = load(planWith(storedClassify()));
            modify(session, "Classify ticket", Map.of("prompt", "Sort it, and say how sure you are."));

            NodeLibraryService library = Mockito.mock(NodeLibraryService.class);
            NodeTypeDocumentationEntity doc = new NodeTypeDocumentationEntity();
            doc.setType("classify");
            doc.setParameters(Map.of(
                    "prompt", Map.of("required", true),
                    "categories", Map.of("required", true)));
            Mockito.when(library.findByType("classify")).thenReturn(Optional.of(doc));
            ValidationResult result = ValidationResult.builder().build();
            new StepValidator(Mockito.mock(ToolSchemaFetcher.class), Mockito.mock(DataSourceClient.class), library)
                    .validate(session, result);

            assertThat(result.getErrors())
                    .as("pre-fix validate answered that the node requires 'categories', so finish refused")
                    .noneMatch(e -> "AGENT_MISSING_PARAM".equals(e.code()));
        }

        @Test
        @DisplayName("a classifyCategories edit merges by label, keeps every port index, and is exported")
        void classifyCategoriesEditReachesTheExport() throws Exception {
            WorkflowBuilderSession session = load(planWith(storedClassify()));
            modify(session, "Classify ticket", Map.of("classifyCategories", List.of(
                    Map.of("label", "billing", "description", "Invoices and refunds"),
                    Map.of("label", "account", "description", "Access and seats"))));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> exportedCategories =
                    (List<Map<String, Object>>) exported(session, "Classify ticket").get("classifyCategories");
            assertThat(exportedCategories).extracting(c -> c.get("label"))
                    .as("billing stays category_0 and technical category_1: the edges hang off those indexes")
                    .containsExactly("billing", "technical", "account");
            assertThat(exportedCategories.get(0)).containsEntry("description", "Invoices and refunds");
            assertThat(node(session, "Classify ticket")).doesNotContainKey("classifyCategories");
        }

        @Test
        @DisplayName("a guardrailRules edit reaches the engine instead of the stale copy")
        void guardrailRulesEditReachesTheExport() throws Exception {
            WorkflowBuilderSession session = load(planWith(storedGuardrail()));
            List<Map<String, Object>> newRules = List.of(Map.of("type", "injection", "action", "block"));
            modify(session, "Check reply", Map.of("guardrailRules", newRules));

            assertThat(exported(session, "Check reply").get("guardrailRules"))
                    .as("pre-fix the loaded node kept both spellings and the export kept the old guardrailRules")
                    .isEqualTo(newRules);
            assertThat(node(session, "Check reply")).doesNotContainKey("guardrailRules");
        }

        @Test
        @DisplayName("a guardrail 'input' edit lands on the checked text, as add_node takes it")
        void guardrailInputEditReachesTheExport() throws Exception {
            WorkflowBuilderSession session = load(planWith(storedGuardrail()));
            modify(session, "Check reply", Map.of("input", "{{trigger:start.output.draft}}"));

            assertThat(node(session, "Check reply"))
                    .containsEntry("content", "{{trigger:start.output.draft}}")
                    .doesNotContainKey("input");
            assertThat(exported(session, "Check reply").get("guardrailParams"))
                    .isEqualTo("{{trigger:start.output.draft}}");
        }
        @Test
        @DisplayName("'categories' (the add_node spelling) still replaces the list, so a category can be removed")
        void categoriesReplacesSoRemovalWorks() throws Exception {
            WorkflowBuilderSession session = load(planWith(storedClassify()));
            modify(session, "Classify ticket", Map.of("categories", List.of(
                    Map.of("label", "billing", "description", "Money matters"))));

            assertThat(labels(exported(session, "Classify ticket").get("classifyCategories")))
                    .containsExactly("billing");
        }

        @Test
        @DisplayName("a classifyParams edit lands on the session content and is exported")
        void classifyParamsEditReachesTheExport() throws Exception {
            WorkflowBuilderSession session = load(planWith(storedClassify()));
            modify(session, "Classify ticket", Map.of("classifyParams", "{{trigger:start.output.subject}}"));

            assertThat(node(session, "Classify ticket"))
                    .containsEntry("content", "{{trigger:start.output.subject}}")
                    .doesNotContainKey("classifyParams");
            assertThat(exported(session, "Classify ticket").get("classifyParams"))
                    .isEqualTo("{{trigger:start.output.subject}}");
        }

        @Test
        @DisplayName("a session opened before the fix (plan spelling only) is healed by the first modify")
        void inFlightSessionIsHealed() throws Exception {
            WorkflowBuilderSession session = WorkflowBuilderSession.builder()
                    .sessionId("s").tenantId("test-tenant").workflowName("Triage")
                    .createdAt(Instant.now()).updatedAt(Instant.now()).build();
            Map<String, Object> stale = storedClassify();
            stale.put("isAgent", true);
            stale.put("isClassify", true);
            session.getMcps().add(stale);

            modify(session, "Classify ticket", Map.of("classifyCategories", List.of(
                    Map.of("label", "technical", "description", "Anything broken"))));

            assertThat(labels(exported(session, "Classify ticket").get("classifyCategories")))
                    .as("a partial edit must not wipe the other category or move its port")
                    .containsExactly("billing", "technical");
        }
        @Test
        @DisplayName("a loaded classify keeps a valid port mock, and a new one is accepted")
        void portMockStaysValid() throws Exception {
            Map<String, Object> classify = storedClassify();
            classify.put("mock", new LinkedHashMap<>(Map.of("port", "category_1")));
            WorkflowBuilderSession session = load(planWith(classify));

            ValidationResult result = ValidationResult.builder().build();
            new com.apimarketplace.orchestrator.tools.workflow.builder.validation.MockConfigValidator()
                    .validate(session, result);
            assertThat(result.getErrors())
                    .as("the parser reads classify ports from classifyCategories; the session node has categories")
                    .noneMatch(e -> "MOCK_INVALID".equals(e.code()));

            Map<String, Object> args = new LinkedHashMap<>();
            args.put("node", "Classify ticket");
            args.put("mock", Map.of("port", "category_0"));
            ToolExecutionResult modified = modifier.executeModifyNode(session, args);
            assertThat(modified.success()).as(String.valueOf(modified.data())).isTrue();
        }
    }

    @Nested
    @DisplayName("load, other stored shapes")
    class OtherShapes {

        @Test
        @DisplayName("a classify stored under mcps with isAgent is adopted too")
        void classifyUnderMcps() throws Exception {
            Map<String, Object> classify = storedClassify();
            classify.put("isAgent", true);
            Map<String, Object> plan = planWith();
            plan.put("mcps", new ArrayList<>(List.of(classify)));

            Map<String, Object> node = node(load(plan), "Classify ticket");

            assertThat(node.get("categories")).isEqualTo(CATEGORIES);
            assertThat(node).doesNotContainKey("classifyCategories");
        }

        @Test
        @DisplayName("a guardrail whose guardrailParams is not text keeps it where the engine reads it")
        void nonTextGuardrailParamsStay() throws Exception {
            Map<String, Object> guardrail = storedGuardrail();
            guardrail.put("guardrailParams", Map.of("source", "draft"));

            Map<String, Object> node = node(load(planWith(guardrail)), "Check reply");

            assertThat(node).containsEntry("guardrailParams", Map.of("source", "draft"))
                    .doesNotContainKey("content");
        }

        @Test
        @DisplayName("guardrail rules in map form survive unchanged")
        void mapFormRules() throws Exception {
            Map<String, Object> guardrail = storedGuardrail();
            guardrail.remove("rules");
            guardrail.put("guardrailRules", Map.of("pii", "No personal data"));

            Map<String, Object> node = node(load(planWith(guardrail)), "Check reply");

            assertThat(node.get("rules")).isEqualTo(Map.of("pii", "No personal data"));
        }
    }

    @Nested
    @DisplayName("after set_plan")
    class AfterSetPlan {

        @Test
        @DisplayName("an imported classify is readable by the session too")
        void setPlanAdoptsThePlanSpelling() {
            WorkflowBuilderSession session = WorkflowBuilderSession.builder()
                    .sessionId("s").tenantId("test-tenant").workflowName("Triage")
                    .createdAt(Instant.now()).updatedAt(Instant.now()).build();
            WorkflowBuilderPlanExporter exporter = new WorkflowBuilderPlanExporter(
                    Mockito.mock(WorkflowBuilderSessionStore.class), Mockito.mock(ToolSchemaFetcher.class));

            ToolExecutionResult result = exporter.executeSetPlan(
                    session, Map.of("plan", planWith(storedClassify(), storedGuardrail())));

            assertThat(result.success()).as(String.valueOf(result.data())).isTrue();
            assertThat(node(session, "Classify ticket").get("categories")).isEqualTo(CATEGORIES);
            assertThat(node(session, "Check reply").get("rules"))
                    .isEqualTo(List.of(Map.of("type", "toxicity", "action", "flag")));
        }
        @Test
        @DisplayName("an AI node filed under mcps is adopted by set_plan as load adopts it")
        void setPlanAdoptsMcpsShape() {
            WorkflowBuilderSession session = WorkflowBuilderSession.builder()
                    .sessionId("s").tenantId("test-tenant").workflowName("Triage")
                    .createdAt(Instant.now()).updatedAt(Instant.now()).build();
            ToolSchemaFetcher fetcher = Mockito.mock(ToolSchemaFetcher.class);
            Mockito.lenient().when(fetcher.checkToolExists(Mockito.anyString()))
                    .thenReturn(ToolSchemaFetcher.ToolExistence.EXISTS);
            WorkflowBuilderPlanExporter exporter = new WorkflowBuilderPlanExporter(
                    Mockito.mock(WorkflowBuilderSessionStore.class), fetcher);
            Map<String, Object> classify = storedClassify();
            classify.put("isAgent", true);
            Map<String, Object> plan = planWith();
            plan.put("mcps", new ArrayList<>(List.of(classify)));
            plan.put("edges", new ArrayList<>(List.of(
                    new LinkedHashMap<>(Map.of("from", "trigger:start", "to", "Classify ticket")))));

            ToolExecutionResult result = exporter.executeSetPlan(session, Map.of("plan", plan));

            assertThat(result.success()).as(String.valueOf(result.data())).isTrue();
            assertThat(node(session, "Classify ticket").get("categories")).isEqualTo(CATEGORIES);
            assertThat(node(session, "Classify ticket")).doesNotContainKey("classifyCategories");
        }
    }

    @Nested
    @DisplayName("adoptPlanSpellings")
    class Helper {

        @Test
        @DisplayName("an ordinary agent node is left untouched")
        void ordinaryAgentUntouched() {
            Map<String, Object> agent = new LinkedHashMap<>(Map.of(
                    "type", "agent", "label", "Writer", "content", "hello", "rules", "keep me"));
            Map<String, Object> before = new LinkedHashMap<>(agent);

            SessionPlanBuilder.adoptPlanSpellings(agent);

            assertThat(agent).isEqualTo(before);
        }

        @Test
        @DisplayName("a non-text *Params value stays where the engine reads it")
        void nonTextParamsStay() {
            Map<String, Object> classify = new LinkedHashMap<>(Map.of(
                    "type", "classify", "classifyParams", Map.of("expr", "x"), "content", "old"));

            SessionPlanBuilder.adoptPlanSpellings(classify);

            assertThat(classify)
                    .as("the export only regenerates classifyParams from a String content")
                    .containsEntry("classifyParams", Map.of("expr", "x"))
                    .containsEntry("content", "old");
        }

        @Test
        @DisplayName("a node already in session spelling is unchanged apart from its flag")
        void sessionSpellingUnchanged() {
            Map<String, Object> classify = new LinkedHashMap<>(Map.of(
                    "type", "classify", "categories", CATEGORIES, "content", "x"));

            SessionPlanBuilder.adoptPlanSpellings(classify);

            assertThat(classify).containsEntry("categories", CATEGORIES)
                    .containsEntry("content", "x").containsEntry("isClassify", true);
        }
        @Test
        @DisplayName("a blank *Params value never overwrites the session content")
        void blankParamsIgnored() {
            Map<String, Object> classify = new LinkedHashMap<>(Map.of(
                    "type", "classify", "classifyParams", "  ", "content", "{{trigger:start.output.body}}"));

            SessionPlanBuilder.adoptPlanSpellings(classify);

            assertThat(classify).containsEntry("content", "{{trigger:start.output.body}}")
                    .as("left in place, the blank key would stop the export writing content back")
                    .doesNotContainKey("classifyParams");
        }
    }
}
