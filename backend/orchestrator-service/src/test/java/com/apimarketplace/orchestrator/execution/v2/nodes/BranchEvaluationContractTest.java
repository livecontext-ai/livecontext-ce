package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.TypeCastingService;
import com.apimarketplace.orchestrator.services.template.NamespaceResolver;
import com.apimarketplace.orchestrator.services.template.PathNavigator;
import com.apimarketplace.orchestrator.services.template.SpelEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every branching node reports its branches in ONE shape, and its parameters panel
 * agrees with the branch it took.
 *
 * <p>Two defects this pins, both of which shipped and neither of which any test could
 * see, because each node was tested only against its own private spelling:
 *
 * <ol>
 *   <li>No entry said which branch was SELECTED, and an {@code else} reported
 *       {@code result: true} because it has no condition to evaluate. Side by side with
 *       a matched {@code if} also reporting {@code result: true}, the row the run took
 *       was indistinguishable from the row it skipped.</li>
 *   <li>The parameters panel re-resolved each expression through a SECOND resolver.
 *       That one renders an absent value as an empty string where the evaluator renders
 *       it as {@code null}, so one expression in one execution produced
 *       {@code " == null"} in Params and {@code "null == null"} in Output.</li>
 * </ol>
 *
 * <p>Run against a REAL TemplateEngine on purpose. A mocked engine returns whatever the
 * test tells it to, which certifies the author's assumption rather than the behaviour.
 */
@DisplayName("Branch evaluation contract, shared by every branching node")
class BranchEvaluationContractTest {

    private TemplateEngine templateEngine;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        SpelEvaluator spelEvaluator = new SpelEvaluator();
        spelEvaluator.init();
        PathNavigator pathNavigator = new PathNavigator();
        templateEngine = new TemplateEngine(
                new TypeCastingService(),
                new NamespaceResolver(pathNavigator),
                pathNavigator,
                spelEvaluator);

        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("value", 15);
        triggerData.put("status", "active");

        context = ExecutionContext.create(
                "run-1", "workflow-run-1", "tenant-1", "item-1", 0,
                triggerData, Mockito.mock(WorkflowPlan.class));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> evaluationsOf(NodeExecutionResult result) {
        Object raw = result.output().get("evaluations");
        assertNotNull(raw, "a branching node must report its evaluations");
        return (List<Map<String, Object>>) raw;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> paramsOf(NodeExecutionResult result) {
        return (Map<String, Object>) result.output().get("resolved_params");
    }

    /** Applies the shared contract to whatever a branching node just reported. */
    private void assertCanonical(NodeExecutionResult result, String node) {
        List<Map<String, Object>> evaluations = evaluationsOf(result);
        assertFalse(evaluations.isEmpty(), node + " reported no branch");

        long selectedCount = 0;
        for (Map<String, Object> entry : evaluations) {
            assertTrue(entry.keySet().containsAll(BranchEvaluationReport.REQUIRED_KEYS),
                    node + " must report the canonical keys, got " + entry.keySet());

            Object branch = entry.get(BranchEvaluationReport.BRANCH);
            assertTrue(branch instanceof String && !((String) branch).isBlank(),
                    node + " must name the port it routes through, got " + branch);

            assertNotNull(entry.get(BranchEvaluationReport.RESOLVED),
                    node + " must report what the branch resolved to");

            if (Boolean.TRUE.equals(entry.get(BranchEvaluationReport.SELECTED))) {
                selectedCount++;
            }
        }

        // EXACTLY one, not at most one. "At most" also passes for a node that marks
        // nothing, which is the state this whole contract exists to make impossible to
        // confuse with a healthy run, and it is what every caller here produces.
        assertEquals(1, selectedCount,
                node + " selected " + selectedCount + " branches; exactly one is taken here");

        // A selected branch must never report an outcome that says it was not taken. A
        // loop exiting on a false condition reported `not_matched` on the path the run
        // actually took, which is the opposite of what a reader needs.
        for (Map<String, Object> entry : evaluations) {
            if (Boolean.TRUE.equals(entry.get(BranchEvaluationReport.SELECTED))) {
                assertTrue(
                        List.of("matched", "fallback", "taken").contains(entry.get(BranchEvaluationReport.OUTCOME)),
                        node + " marked a branch selected with outcome "
                                + entry.get(BranchEvaluationReport.OUTCOME));
            }
        }
    }

    /** A healthy run must report nothing at all about unresolved references. */
    private void assertNoUnresolvedReported(NodeExecutionResult result, String node) {
        for (Map<String, Object> entry : evaluationsOf(result)) {
            assertNull(entry.get(BranchEvaluationReport.UNRESOLVED),
                    node + " accused a healthy condition: " + entry);
            Object resolved = entry.get(BranchEvaluationReport.RESOLVED);
            assertFalse(String.valueOf(resolved).contains("<unresolved"),
                    node + " marked a resolved expression as missing: " + resolved);
        }
    }

    @Nested
    @DisplayName("Decision")
    class DecisionTests {

        private DecisionNode node(String ifCondition) {
            return DecisionNode.builder()
                    .nodeId("core:check")
                    .templateEngine(templateEngine)
                    .ifBranch(ifCondition, List.of())
                    .elseBranch(List.of())
                    .build();
        }

        @Test
        @DisplayName("reports the canonical shape")
        void reportsCanonicalShape() {
            assertCanonical(node("{{value}} > 10").execute(context), "decision");
        }

        @Test
        @DisplayName("marks the branch it took, and only that one")
        void marksTheBranchItTook() {
            List<Map<String, Object>> evaluations = evaluationsOf(node("{{value}} > 10").execute(context));

            assertEquals("if", evaluations.get(0).get("branch"));
            assertEquals(true, evaluations.get(0).get("selected"));
            assertEquals("else", evaluations.get(1).get("branch"));
            assertEquals(false, evaluations.get(1).get("selected"),
                    "the else was not taken and must not look like it was");
        }

        @Test
        @DisplayName("an else reports no result rather than a tautological true")
        void elseReportsNoResult() {
            List<Map<String, Object>> evaluations = evaluationsOf(node("{{value}} > 10").execute(context));

            assertNull(evaluations.get(1).get("result"),
                    "an else has no condition to evaluate: reporting true made a skipped "
                            + "else read exactly like a matched if");
            assertEquals("fallback", evaluations.get(1).get("outcome"));
        }

        @Test
        @DisplayName("a branch that matched but lost says so")
        void matchedButNotSelectedIsDistinct() {
            DecisionNode node = DecisionNode.builder()
                    .nodeId("core:check")
                    .templateEngine(templateEngine)
                    .ifBranch("{{value}} > 10", List.of())
                    .elsifBranch("{{value}} > 5", List.of())
                    .build();

            List<Map<String, Object>> evaluations = evaluationsOf(node.execute(context));

            assertEquals("matched", evaluations.get(0).get("outcome"));
            assertEquals(true, evaluations.get(1).get("result"),
                    "the elseif was true as well, which is the answer to why the if won");
            assertEquals("matched_not_selected", evaluations.get(1).get("outcome"));
            assertEquals(false, evaluations.get(1).get("selected"));
        }

        @Test
        @DisplayName("Params carry exactly what the evaluation resolved")
        void paramsMatchTheEvaluation() {
            NodeExecutionResult result = node("{{value}} > 10").execute(context);

            Map<String, Object> params = paramsOf(result);
            for (Map<String, Object> entry : evaluationsOf(result)) {
                assertEquals(entry.get("resolved"), params.get(entry.get("branch")),
                        "Params and Output must not disagree about one expression");
            }
            assertEquals("15 > 10", params.get("if"));
        }

        @Test
        @DisplayName("A healthy condition is never accused of a missing reference")
        void healthyConditionIsNotAccused() {
            // Against the REAL EvalContextBuilder layout, including the string-literal
            // shape that the first version of the diagnostic reported as a missing node.
            assertNoUnresolvedReported(node("{{value}} > 10").execute(context), "decision");
            assertNoUnresolvedReported(node("{{status}} == 'active'").execute(context), "decision");
        }

        @Test
        @DisplayName("A real step reference resolves through the engine's own context")
        void realStepReferenceResolves() {
            // Pins the probe against the key shape EvalContextBuilder actually produces
            // (node id at the top level, output under an "output" wrapper), so a change
            // to that layout shows up here instead of as a wave of false accusations.
            NodeExecutionResult stepResult =
                    NodeExecutionResult.success("mcp:score", Map.of("score", 42));
            ExecutionContext withStep = context.withResult("mcp:score", stepResult);

            NodeExecutionResult result = node("{{mcp:score.output.score}} > 40").execute(withStep);

            Map<String, Object> ifEval = evaluationsOf(result).get(0);
            assertEquals(true, ifEval.get("result"));
            assertEquals("42 > 40", ifEval.get("resolved"));
            assertNull(ifEval.get("unresolved"));
        }

        @Test
        @DisplayName("An unresolvable reference is named rather than read as null")
        void unresolvedReferenceIsNamed() {
            // The reported bug: {{trigger:move_a_task.output.task}} == null was true
            // because the reference pointed at nothing, and every panel said null == null.
            NodeExecutionResult result = node("{{trigger:ghost.output.task}} == null").execute(context);

            Map<String, Object> ifEval = evaluationsOf(result).get(0);
            assertEquals(true, ifEval.get("result"));
            assertEquals("<unresolved: trigger:ghost.output.task> == null", ifEval.get("resolved"));
            assertNotNull(ifEval.get("unresolved"), "the reference that resolved to nothing must be listed");
            assertEquals("<unresolved: trigger:ghost.output.task> == null", paramsOf(result).get("if"));
        }
    }

    @Nested
    @DisplayName("Switch")
    class SwitchTests {

        private SwitchNode node() {
            return SwitchNode.builder()
                    .nodeId("core:route")
                    .switchExpression("{{status}}")
                    .templateEngine(templateEngine)
                    .addCase("archived", "Archived")
                    .addCase("active", "Active")
                    .addDefault("Default")
                    .build();
        }

        @Test
        @DisplayName("reports the canonical shape")
        void reportsCanonicalShape() {
            assertCanonical(node().execute(context), "switch");
        }

        @Test
        @DisplayName("every case says what it was compared against")
        void everyCaseShowsTheComparison() {
            List<Map<String, Object>> evaluations = evaluationsOf(node().execute(context));

            // Pre-fix a case reported its own value and nothing about the subject, so the
            // reader could not see WHY a case did not match.
            assertEquals("active == archived", evaluations.get(0).get("resolved"));
            assertEquals(false, evaluations.get(0).get("result"));
            assertEquals("active == active", evaluations.get(1).get("resolved"));
            assertEquals(true, evaluations.get(1).get("selected"));
        }

        @Test
        @DisplayName("a default declared FIRST is not taken while a case matches")
        void defaultDeclaredFirstDoesNotWin() {
            // The selection used to be decided while scanning, so a default placed before
            // its siblings was tested against a selection that could not exist yet.
            SwitchNode node = SwitchNode.builder()
                    .nodeId("core:route")
                    .switchExpression("{{status}}")
                    .templateEngine(templateEngine)
                    .addDefault("Default")
                    .addCase("active", "Active")
                    .build();

            NodeExecutionResult result = node.execute(context);
            List<Map<String, Object>> evaluations = evaluationsOf(result);

            assertEquals(false, evaluations.get(0).get("selected"), "the default must not win here");
            assertEquals(true, evaluations.get(1).get("selected"));
            assertEquals(1, result.output().get("selected_case_index"));

            // The old positional code omitted a first-declared default from skipped_cases,
            // because it decided "did anything match yet" while scanning.
            @SuppressWarnings("unchecked")
            List<String> skipped = (List<String>) result.output().get("skipped_cases");
            assertEquals(List.of("default"), skipped);
        }

        @Test
        @DisplayName("keeps the author's case name beside the port")
        void keepsTheAuthorCaseName() {
            // The port (case_1) cannot say "Active", and the Cases table shows the name.
            List<Map<String, Object>> evaluations = evaluationsOf(node().execute(context));

            assertEquals("Archived", evaluations.get(0).get("case_label"));
            assertEquals("Active", evaluations.get(1).get("case_label"));
        }

        @Test
        @DisplayName("a subject that points at nothing says so on every case row")
        void unresolvableSubjectIsMarked() {
            // A switch on a mistyped reference resolves to null and quietly takes the
            // default, which looks exactly like a deliberate default.
            SwitchNode node = SwitchNode.builder()
                    .nodeId("core:route")
                    .switchExpression("{{trigger:ghost.output.status}}")
                    .templateEngine(templateEngine)
                    .addCase("active", "Active")
                    .addDefault("Default")
                    .build();

            NodeExecutionResult result = node.execute(context);

            assertEquals("<unresolved: trigger:ghost.output.status> == active",
                    evaluationsOf(result).get(0).get("resolved"));
            assertEquals("<unresolved: trigger:ghost.output.status>",
                    paramsOf(result).get("switchExpression"));
        }
    }

    @Nested
    @DisplayName("Option")
    class OptionTests {

        private OptionNode node() {
            return OptionNode.builder()
                    .nodeId("core:pick")
                    .templateEngine(templateEngine)
                    .addChoice("c1", "High", "{{value}} > 10", List.of())
                    .addChoice("c2", "Low", "{{value}} < 5", List.of())
                    .build();
        }

        @Test
        @DisplayName("reports the canonical shape")
        void reportsCanonicalShape() {
            assertCanonical(node().execute(context), "option");
        }

        @Test
        @DisplayName("Params stay keyed by the author's label and valued by the evaluation")
        void paramsKeyedByLabel() {
            NodeExecutionResult result = node().execute(context);

            // The label is what the plan names and what the inspector labels; the VALUE
            // has to come from the evaluation, or the two panels can disagree again.
            assertEquals("15 > 10", paramsOf(result).get("High"));
            assertEquals("15 < 5", paramsOf(result).get("Low"));
            assertEquals("choice_0", evaluationsOf(result).get(0).get("branch"));
            assertEquals("c1", evaluationsOf(result).get(0).get("choice_id"));
        }
    }

    @Nested
    @DisplayName("Loop")
    class LoopTests {

        @Test
        @DisplayName("reports the condition that decided its path")
        void reportsItsCondition() {
            LoopNode node = LoopNode.builder()
                    .nodeId("core:repeat")
                    .loopCondition("{{value}} > 10")
                    .maxIterations(3)
                    .templateEngine(templateEngine)
                    .build();

            NodeExecutionResult result = node.execute(context);

            // A loop reported nothing at all about its condition: not the expression, not
            // what it resolved to, not the answer.
            assertCanonical(result, "loop");
            assertEquals("15 > 10", result.output().get("condition_resolved"));
            assertEquals(true, result.output().get("condition_result"));
            assertEquals("{{value}} > 10", result.output().get("loop_condition"));
            assertEquals(3, result.output().get("max_iterations"));
            assertEquals("15 > 10", paramsOf(result).get("loopCondition"));
            assertEquals("body", evaluationsOf(result).get(0).get("branch"));
        }

        @Test
        @DisplayName("a counted loop with no condition says so instead of inventing a result")
        void countedLoopHasNoResult() {
            LoopNode node = LoopNode.builder()
                    .nodeId("core:repeat")
                    .maxIterations(2)
                    .templateEngine(templateEngine)
                    .build();

            NodeExecutionResult result = node.execute(context);

            assertNull(evaluationsOf(result).get(0).get("result"));
            assertEquals("fallback", evaluationsOf(result).get(0).get("outcome"));
            // The SAME words the evaluation entry uses. Params saying "(none)" while
            // Output said "(no condition)" is a small version of the two-panel
            // disagreement this contract exists to remove.
            assertEquals("(no condition)", paramsOf(result).get("loopCondition"));
            assertEquals("(no condition)", evaluationsOf(result).get(0).get("resolved"));
        }

        @Test
        @DisplayName("an exit reports the exit port, not the body")
        void exitReportsTheExitPort() {
            LoopNode node = LoopNode.builder()
                    .nodeId("core:repeat")
                    .loopCondition("{{value}} > 100")
                    .maxIterations(3)
                    .templateEngine(templateEngine)
                    .build();

            NodeExecutionResult result = node.execute(context);

            assertEquals("exit", evaluationsOf(result).get(0).get("branch"));
            assertEquals("15 > 100", result.output().get("condition_resolved"));
            assertEquals(false, result.output().get("condition_result"));
            // The run TOOK this path. Reporting not_matched on a selected branch
            // contradicts what every other node means by selected.
            assertEquals(true, evaluationsOf(result).get(0).get("selected"));
            assertEquals("taken", evaluationsOf(result).get(0).get("outcome"));
        }
    }
}
