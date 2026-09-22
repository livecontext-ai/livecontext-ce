package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code workflow(action='set_plan')} answers to the same execution-policy rules as
 * {@code add_node} and {@code modify}.
 *
 * <p><b>Why this exists.</b> A rule enforced in one of the three doors is a rule an agent walks
 * around by using another, and here the walk-around was silent in the worst way: the plan stored
 * fine, {@code validate} passed, {@code describe} announced the block, and the engine read nothing.
 * The field involved is the one that stops the platform multiplying an author's requests to a
 * provider that has just asked them to slow down, so believing it was set is the whole problem.
 *
 * <p>The refusal lives HERE and not in the parser on purpose: the parser drops the field with a
 * warning instead of throwing, so a plan already stored with one stays openable and repairable.
 * This is the door; the parser is not the wall.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderPlanExporter - set_plan validation of nodePolicy")
class WorkflowBuilderPlanExporterNodePolicyTest {

    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private ToolSchemaFetcher toolSchemaFetcher;

    private WorkflowBuilderPlanExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new WorkflowBuilderPlanExporter(sessionStore, toolSchemaFetcher);
    }

    private WorkflowBuilderSession newSession() {
        return WorkflowBuilderSession.builder()
                .sessionId("test-session")
                .tenantId("test-tenant")
                .workflowName("Publisher")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private Map<String, Object> manualTrigger() {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("label", "start");
        trigger.put("type", "manual");
        return trigger;
    }

    private Map<String, Object> transformCore(Map<String, Object> nodePolicy) {
        Map<String, Object> core = new LinkedHashMap<>();
        core.put("id", "c1");
        core.put("type", "transform");
        core.put("label", "Format Data");
        core.put("params", new LinkedHashMap<>(Map.of("mapping", Map.of("a", "b"))));
        if (nodePolicy != null) {
            core.put(NodePolicy.JSON_KEY, new LinkedHashMap<>(nodePolicy));
        }
        return core;
    }

    private ToolExecutionResult setPlan(WorkflowBuilderSession session, String arrayName,
                                        List<Map<String, Object>> entries) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(manualTrigger())));
        plan.put(arrayName, new ArrayList<>(entries));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("plan", plan);
        return exporter.executeSetPlan(session, parameters);
    }

    @Test
    @DisplayName("a provider-retry budget on a core entry is REFUSED, and the plan is not stored")
    void providerBudgetOnACoreEntryIsRefused() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session, "cores",
                List.of(transformCore(Map.of("providerRetryMaxWaitSec", 0))));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("providerRetryMaxWaitSec").contains("catalog tool step only");
        assertThat(session.getCores())
                .as("a refused plan must leave the session as it was")
                .isEmpty();
    }

    @Test
    @DisplayName("the same rule applies to agents, interfaces and tables, not only cores")
    void theRuleCoversEveryNonToolArray() {
        // An AI node and a generate node DO call providers, and are the entries an author would
        // reach for first; neither goes through StepNode, so neither reads the field.
        for (String arrayName : List.of("agents", "interfaces", "tables")) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("label", "Node " + arrayName);
            entry.put("type", "agent");
            entry.put(NodePolicy.JSON_KEY, new LinkedHashMap<>(Map.of("providerRetryMaxWaitSec", 0)));

            ToolExecutionResult result = setPlan(newSession(), arrayName, List.of(entry));

            assertThat(result.success()).as("array '%s'", arrayName).isFalse();
            assertThat(result.error()).as("array '%s'", arrayName).contains("catalog tool step only");
        }
    }

    @Test
    @DisplayName("the rest of the policy is accepted on those same entries")
    void theOtherFieldsAreAcceptedOnACoreEntry() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session, "cores", List.of(transformCore(
                Map.of("retryCount", 2, "retryBackoffMs", 1000, "timeoutMs", 30000))));

        assertThat(result.success())
                .as("the refusal must be narrow, got: " + result.error())
                .isTrue();
        assertThat(session.getCores().get(0).get(NodePolicy.JSON_KEY))
                .isEqualTo(Map.of("retryCount", 2, "retryBackoffMs", 1000, "timeoutMs", 30000));
    }

    @Test
    @DisplayName("a malformed policy anywhere in the plan is refused with the engine's own wording")
    void aMalformedPolicyIsRefused() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session, "cores",
                List.of(transformCore(Map.of("retryCount", -1))));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("retryCount");
        assertThat(session.getCores()).isEmpty();
    }

    @Test
    @DisplayName("ANY policy on a trigger entry is refused, because the engine collects none there")
    void anyPolicyOnATriggerIsRefused() {
        // The parser skips triggers entirely, so a policy written on one is accepted and ignored -
        // which reads to the caller exactly like one that works. add_node and modify refuse it; this
        // was the third door.
        WorkflowBuilderSession session = newSession();
        Map<String, Object> trigger = manualTrigger();
        trigger.put(NodePolicy.JSON_KEY, new LinkedHashMap<>(Map.of("retryCount", 2)));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(trigger)));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("plan", plan);

        ToolExecutionResult result = exporter.executeSetPlan(session, parameters);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not available on trigger or note nodes");
        assertThat(session.getTriggers()).isEmpty();
    }

    @Test
    @DisplayName("an mcps entry ACCEPTS the budget, which is what makes every refusal above narrow")
    void anMcpsEntryAcceptsTheBudget() {
        // Without this, set_plan refusing the budget on the one node type the feature exists for
        // would leave the feature dead through this door with every other test still green.
        WorkflowBuilderSession session = newSession();
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", "slack/send-message");
        step.put("label", "Publish");
        step.put(NodePolicy.JSON_KEY, new LinkedHashMap<>(Map.of("providerRetryMaxWaitSec", 0)));

        ToolExecutionResult result = setPlan(session, "mcps", List.of(step));

        assertThat(result.success()).as(String.valueOf(result.error())).isTrue();
        assertThat(session.getMcps().get(0).get(NodePolicy.JSON_KEY))
                .isEqualTo(Map.of("providerRetryMaxWaitSec", 0));
    }

    @Test
    @DisplayName("a MALFORMED policy on an mcps entry is refused, not stored to throw at parse time")
    void aMalformedPolicyOnAnMcpsEntryIsRefused() {
        // mcps is the one array allowed to carry the budget, which is exactly why its SHAPE has to
        // be checked here too. Skipping it let a negative retryCount through to the session on the
        // one node type the feature exists for, and the parser throws on it: the workflow stored
        // fine and could then neither be opened nor run.
        WorkflowBuilderSession session = newSession();
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", "slack/send-message");
        step.put("label", "Publish");
        step.put(NodePolicy.JSON_KEY, new LinkedHashMap<>(Map.of("retryCount", -1)));

        ToolExecutionResult result = setPlan(session, "mcps", List.of(step));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("retryCount");
        assertThat(session.getMcps()).isEmpty();
    }

    @Test
    @DisplayName("a non-numeric timeout on an mcps entry is refused for the same reason")
    void aNonNumericTimeoutOnAnMcpsEntryIsRefused() {
        WorkflowBuilderSession session = newSession();
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", "slack/send-message");
        step.put("label", "Publish");
        step.put(NodePolicy.JSON_KEY, new LinkedHashMap<>(Map.of("timeoutMs", "soon")));

        ToolExecutionResult result = setPlan(session, "mcps", List.of(step));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("timeoutMs");
        assertThat(session.getMcps()).isEmpty();
    }

    @Test
    @DisplayName("a policy on a NOTE entry is refused too, not only on a trigger")
    void anyPolicyOnANoteIsRefused() {
        // The refusal message, the help and the docs all say "trigger or note". Checking only
        // triggers left the second half of every one of those sentences untrue.
        WorkflowBuilderSession session = newSession();
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("id", "n1");
        note.put("label", "Reminder");
        note.put(NodePolicy.JSON_KEY, new LinkedHashMap<>(Map.of("retryCount", 2)));

        ToolExecutionResult result = setPlan(session, "notes", List.of(note));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not available on trigger or note nodes");
        assertThat(session.getNotes()).isEmpty();
    }

    @Test
    @DisplayName("continueOnFailure on a decision is refused HERE, where the parser would THROW")
    void continueOnFailureOnADecisionIsRefused() {
        // The parser rejects this by throwing, so storing the plan leaves a workflow that cannot be
        // opened and cannot be run, with the error arriving on some later unrelated call. Strictly
        // worse than the budget case this validator was written for.
        WorkflowBuilderSession session = newSession();
        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("id", "d1");
        decision.put("type", "decision");
        decision.put("label", "Check");
        decision.put("conditions", List.of(Map.of("condition", "1 == 1", "label", "yes")));
        decision.put(NodePolicy.JSON_KEY, new LinkedHashMap<>(Map.of("continueOnFailure", true)));

        ToolExecutionResult result = setPlan(session, "cores", List.of(decision));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("ALL its ports");
        assertThat(session.getCores()).isEmpty();
    }

    @Test
    @DisplayName("executeOnce on a loop is refused here for the same reason")
    void executeOnceOnALoopIsRefused() {
        WorkflowBuilderSession session = newSession();
        Map<String, Object> loop = new LinkedHashMap<>();
        loop.put("id", "l1");
        loop.put("type", "loop");
        loop.put("label", "Repeat");
        loop.put("maxIterations", 3);
        loop.put(NodePolicy.JSON_KEY, new LinkedHashMap<>(Map.of("executeOnce", true)));

        ToolExecutionResult result = setPlan(session, "cores", List.of(loop));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("never loop iterations");
        assertThat(session.getCores()).isEmpty();
    }

    @Test
    @DisplayName("a plan with no policy at all is untouched, so ordinary set_plan is unaffected")
    void aPlanWithoutPoliciesIsUnaffected() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session, "cores", List.of(transformCore(null)));

        assertThat(result.success()).as(String.valueOf(result.error())).isTrue();
        assertThat(session.getCores().get(0)).doesNotContainKey(NodePolicy.JSON_KEY);
    }
}
