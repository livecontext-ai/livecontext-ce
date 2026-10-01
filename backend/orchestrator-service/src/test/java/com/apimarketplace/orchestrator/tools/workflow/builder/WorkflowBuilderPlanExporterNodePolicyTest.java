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
 *
 * <p>The removed {@code providerRetryMaxWaitSec} is refused HERE and not in the parser on purpose:
 * the parser ignores it, so a plan already stored with it stays openable, and {@code get_plan}
 * does not hand it back, so such a plan survives its own round trip. This is the door; the parser
 * is not the wall.
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
    @DisplayName("the removed provider-retry budget on a core entry is REFUSED, and the plan is not stored")
    void providerBudgetOnACoreEntryIsRefused() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session, "cores",
                List.of(transformCore(Map.of("providerRetryMaxWaitSec", 0))));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("providerRetryMaxWaitSec").contains("no longer exists");
        assertThat(session.getCores())
                .as("a refused plan must leave the session as it was")
                .isEmpty();
    }

    @Test
    @DisplayName("the same rule applies to agents, interfaces and tables, not only cores")
    void theRuleCoversEveryNonToolArray() {
        for (String arrayName : List.of("agents", "interfaces", "tables")) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("label", "Node " + arrayName);
            entry.put("type", "agent");
            entry.put(NodePolicy.JSON_KEY, new LinkedHashMap<>(Map.of("providerRetryMaxWaitSec", 0)));

            ToolExecutionResult result = setPlan(newSession(), arrayName, List.of(entry));

            assertThat(result.success()).as("array '%s'", arrayName).isFalse();
            assertThat(result.error()).as("array '%s'", arrayName).contains("no longer exists");
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
    @DisplayName("REGRESSION: an mcps entry REFUSES the removed budget too, since the platform no longer retries")
    void anMcpsEntryRefusesTheRemovedBudget() {
        WorkflowBuilderSession session = newSession();
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", "slack/send-message");
        step.put("label", "Publish");
        step.put(NodePolicy.JSON_KEY, new LinkedHashMap<>(Map.of("providerRetryMaxWaitSec", 0)));

        ToolExecutionResult result = setPlan(session, "mcps", List.of(step));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("never retries a provider refusal").contains("retryCount");
        assertThat(session.getMcps()).as("a refused plan leaves the session as it was").isEmpty();
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
    @DisplayName("continueOnFailure on a loop is refused here too, although the parser lets a stored one open")
    void continueOnFailureOnALoopIsRefused() {
        WorkflowBuilderSession session = newSession();
        Map<String, Object> loop = new LinkedHashMap<>();
        loop.put("id", "l1");
        loop.put("type", "loop");
        loop.put("label", "Repeat");
        loop.put("maxIterations", 3);
        loop.put(NodePolicy.JSON_KEY, new LinkedHashMap<>(Map.of("continueOnFailure", true)));

        ToolExecutionResult result = setPlan(session, "cores", List.of(loop));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("both at once");
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

    @Test
    @DisplayName("REGRESSION: a plan stored with the removed key survives get_plan then set_plan unchanged")
    @SuppressWarnings("unchecked")
    void aStoredLegacyKeyDoesNotBlockTheRoundTrip() {
        // The key was never written by this caller: it sits in a plan stored before the removal.
        // get_plan must not hand it back, or the agent's own untouched round trip is refused.
        WorkflowBuilderSession session = newSession();
        Map<String, Object> legacyPolicy = new LinkedHashMap<>();
        legacyPolicy.put("retryCount", 1);
        legacyPolicy.put("providerRetryMaxWaitSec", 30);
        session.getCores().add(transformCore(legacyPolicy));
        Map<String, Object> onlyLegacy = transformCore(Map.of("providerRetryMaxWaitSec", 0));
        onlyLegacy.put("id", "c2");
        onlyLegacy.put("label", "Second");
        session.getCores().add(onlyLegacy);

        ToolExecutionResult got = exporter.executeGetPlan(session);
        Map<String, Object> plan = (Map<String, Object>) ((Map<String, Object>) got.data()).get("plan");
        List<Map<String, Object>> cores = (List<Map<String, Object>>) plan.get("cores");

        assertThat((Map<String, Object>) cores.get(0).get(NodePolicy.JSON_KEY))
                .containsEntry("retryCount", 1)
                .doesNotContainKey("providerRetryMaxWaitSec");
        assertThat(cores.get(1))
                .as("a block left empty by the removal is dropped, not handed back as {}")
                .doesNotContainKey(NodePolicy.JSON_KEY);
        assertThat((Map<String, Object>) session.getCores().get(0).get(NodePolicy.JSON_KEY))
                .as("get_plan reads; it never rewrites the session")
                .containsKey("providerRetryMaxWaitSec");

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("plan", plan);
        ToolExecutionResult set = exporter.executeSetPlan(session, parameters);

        assertThat(set.success()).as(String.valueOf(set.error())).isTrue();
    }

    @Test
    @DisplayName("set_plan refuses a retryCount or a retryBackoffMs above its cap, and an unknown retryOn")
    void setPlanRefusesValuesAboveCaps() {
        for (Map<String, Object> policy : List.of(
                Map.<String, Object>of("retryCount", 11),
                Map.<String, Object>of("retryCount", 1, "retryBackoffMs", 300_001),
                Map.<String, Object>of("retryCount", 1, "retryOn", "bogus"))) {
            WorkflowBuilderSession session = newSession();

            ToolExecutionResult result = setPlan(session, "cores", List.of(transformCore(policy)));

            assertThat(result.success()).as("policy %s", policy).isFalse();
            assertThat(session.getCores()).as("nothing stored for %s", policy).isEmpty();
        }
    }

    @Test
    @DisplayName("set_plan refuses retryOn on a node that is not a tool step")
    void setPlanRefusesRetryOnOffToolSteps() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session, "cores", List.of(transformCore(
                Map.of("retryCount", 2, "retryOn", "rate_limit"))));

        assertThat(result.success()).isFalse();
        assertThat(String.valueOf(result.error()) + result.data()).contains("only available on a tool step");
        assertThat(session.getCores()).isEmpty();
    }

    @Test
    @DisplayName("set_plan does not refuse retryOn on a tool step (mcps entry)")
    void setPlanAcceptsRetryOnOnToolSteps() {
        WorkflowBuilderSession session = newSession();
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", "slack/send-message");
        step.put("label", "Publish");
        step.put(NodePolicy.JSON_KEY, new LinkedHashMap<>(
                Map.of("retryCount", 2, "retryBackoffMs", 60000, "retryOn", "rate_limit")));

        ToolExecutionResult result = setPlan(session, "mcps", List.of(step));

        assertThat(String.valueOf(result.error()) + result.data())
                .doesNotContain("only available on a tool step")
                .doesNotContain("retryOn only applies");
    }

    @Test
    @DisplayName("retryOn on a stored tool step is handed back by get_plan")
    @SuppressWarnings("unchecked")
    void retryOnIsHandedBackByGetPlan() {
        WorkflowBuilderSession session = newSession();
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", "mcp:publish");
        step.put("label", "Publish");
        step.put(NodePolicy.JSON_KEY, new LinkedHashMap<>(Map.of("retryCount", 2, "retryOn", "rate_limit")));
        session.getMcps().add(step);

        Map<String, Object> plan = (Map<String, Object>) ((Map<String, Object>)
                exporter.executeGetPlan(session).data()).get("plan");
        Map<String, Object> mcp = ((List<Map<String, Object>>) plan.get("mcps")).get(0);
        assertThat((Map<String, Object>) mcp.get(NodePolicy.JSON_KEY)).containsEntry("retryOn", "rate_limit");
    }
}
