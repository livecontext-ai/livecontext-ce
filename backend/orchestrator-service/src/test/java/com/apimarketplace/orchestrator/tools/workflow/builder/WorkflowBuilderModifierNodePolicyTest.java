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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Setting the per-node execution policy through {@code modify}, which is how an agent turns the
 * platform's provider retry off under a workflow that paces itself.
 *
 * <p>The assertions here are mostly about WHERE the value lands, because the failure mode is
 * silence: this project has met, more than once, a parameter the documentation advertises and the
 * code never reads. On an mcp node it is worse than a no-op, because every key that is not lifted
 * out of the patch becomes an argument sent to the provider.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("workflow(action='modify') - the per-node execution policy")
class WorkflowBuilderModifierNodePolicyTest {

    @Mock private WorkflowBuilderSessionStore sessionStore;

    private WorkflowBuilderModifier modifier;

    @BeforeEach
    void setUp() {
        modifier = new WorkflowBuilderModifier(sessionStore);
    }

    private WorkflowBuilderSession session() {
        WorkflowBuilderSession session = WorkflowBuilderSession.builder()
                .sessionId("test-session")
                .tenantId("test-tenant")
                .workflowName("Publisher")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "mcp:publish");
        node.put("type", "mcp");
        node.put("label", "Publish");
        node.put("params", new LinkedHashMap<>(Map.of("caption", "hello")));
        session.getMcps().add(node);
        return session;
    }

    /** Adds a decision core, the node type the engine refuses continueOnFailure on. */
    private void addDecision(WorkflowBuilderSession session) {
        Map<String, Object> core = new LinkedHashMap<>();
        core.put("id", "core:check");
        core.put("type", "decision");
        core.put("label", "Check");
        session.getCores().add(core);
    }

    private void addTrigger(WorkflowBuilderSession session) {
        Map<String, Object> trigger = new LinkedHashMap<>();
        trigger.put("id", "trigger:start");
        trigger.put("type", "webhook");
        trigger.put("label", "Start");
        session.getTriggers().add(trigger);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> publishNode(WorkflowBuilderSession session) {
        return session.getMcps().stream()
                .filter(m -> "Publish".equals(m.get("label")))
                .findFirst().orElseThrow();
    }

    private ToolExecutionResult modifyWithPolicy(WorkflowBuilderSession session, String nodeLabel, Object policy) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("node", nodeLabel);
        args.put("nodePolicy", policy);
        return modifier.executeModifyNode(session, args);
    }

    @Test
    @DisplayName("a policy sent alone, with no params, is accepted and written onto the node")
    void policyAloneIsEnough() {
        // Like connect_after and mock: a policy is not a parameter of the node, so requiring a
        // params object alongside it would force the agent to re-send configuration it is not
        // changing, and the whole-map merge would then be the thing that broke the node.
        WorkflowBuilderSession session = session();

        ToolExecutionResult result = modifyWithPolicy(session, "Publish",
                Map.of("retryCount", 2, "retryBackoffMs", 3000));

        assertThat(result.success()).isTrue();
        assertThat(publishNode(session).get(NodePolicy.JSON_KEY))
                .isEqualTo(Map.of("retryCount", 2, "retryBackoffMs", 3000L));
    }

    @Test
    @DisplayName("the policy never lands in the node's params, where it would be sent to the provider")
    @SuppressWarnings("unchecked")
    void policyNeverLandsInTheToolParams() {
        WorkflowBuilderSession session = session();

        modifyWithPolicy(session, "Publish", Map.of("providerRetryMaxWaitSec", 0));

        Map<String, Object> node = publishNode(session);
        assertThat((Map<String, Object>) node.get("params"))
                .as("an argument the endpoint never declared is either ignored or rejected by the "
                        + "provider, and either way the policy did nothing")
                .containsOnlyKeys("caption");
        assertThat(node).doesNotContainKey("providerRetryMaxWaitSec");
    }

    @Test
    @DisplayName("a zero provider budget survives, because that is the whole point of the setting")
    void zeroProviderBudgetSurvives() {
        WorkflowBuilderSession session = session();

        modifyWithPolicy(session, "Publish", Map.of("providerRetryMaxWaitSec", 0));

        assertThat(publishNode(session).get(NodePolicy.JSON_KEY))
                .isEqualTo(Map.of("providerRetryMaxWaitSec", 0));
    }

    @Test
    @DisplayName("the reply says what was set, so the agent can read it back without another call")
    @SuppressWarnings("unchecked")
    void theReplyReportsThePolicy() {
        WorkflowBuilderSession session = session();

        ToolExecutionResult result = modifyWithPolicy(session, "Publish", Map.of("retryCount", 2));

        Map<String, Object> data = (Map<String, Object>) result.data();
        assertThat(data.get(NodePolicy.JSON_KEY)).isEqualTo(Map.of("retryCount", 2));
        assertThat((Iterable<String>) data.get("modified_fields")).contains(NodePolicy.JSON_KEY);
        assertThat(String.valueOf(data.get("node_policy_hint"))).contains("every execution");
    }

    @Test
    @DisplayName("an empty policy removes it, and the reply says the node is back to the defaults")
    @SuppressWarnings("unchecked")
    void emptyPolicyRemovesIt() {
        WorkflowBuilderSession session = session();
        modifyWithPolicy(session, "Publish", Map.of("retryCount", 2));

        ToolExecutionResult result = modifyWithPolicy(session, "Publish", Map.of());

        assertThat(result.success()).isTrue();
        assertThat(publishNode(session)).doesNotContainKey(NodePolicy.JSON_KEY);
        assertThat(String.valueOf(((Map<String, Object>) result.data()).get("node_policy_hint")))
                .contains("removed");
    }

    @Test
    @DisplayName("the snake_case spelling is accepted, and lands under the canonical key")
    void snakeCaseSpellingIsAccepted() {
        WorkflowBuilderSession session = session();

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("node", "Publish");
        args.put("node_policy", Map.of("retryCount", 1));
        ToolExecutionResult result = modifier.executeModifyNode(session, args);

        assertThat(result.success()).isTrue();
        assertThat(publishNode(session).get(NodePolicy.JSON_KEY)).isEqualTo(Map.of("retryCount", 1));
    }

    @Test
    @DisplayName("a policy nested inside params is honoured, not sent to the provider")
    @SuppressWarnings("unchecked")
    void policyNestedInsideParamsIsHonoured() {
        // The mistake a model actually makes: params is where everything else goes.
        WorkflowBuilderSession session = session();

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("node", "Publish");
        args.put("params", new LinkedHashMap<>(Map.of(
                "caption", "new text", "nodePolicy", Map.of("retryCount", 2))));
        ToolExecutionResult result = modifier.executeModifyNode(session, args);

        assertThat(result.success()).isTrue();
        Map<String, Object> node = publishNode(session);
        assertThat(node.get(NodePolicy.JSON_KEY)).isEqualTo(Map.of("retryCount", 2));
        assertThat((Map<String, Object>) node.get("params"))
                .containsEntry("caption", "new text")
                .doesNotContainKey("nodePolicy");
    }

    @Test
    @DisplayName("a malformed policy is refused and the node is left exactly as it was")
    void malformedPolicyLeavesTheNodeAlone() {
        WorkflowBuilderSession session = session();

        ToolExecutionResult result = modifyWithPolicy(session, "Publish", Map.of("retryCount", -1));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("retryCount").contains("left unchanged");
        assertThat(publishNode(session)).doesNotContainKey(NodePolicy.JSON_KEY);
    }

    @Test
    @DisplayName("continueOnFailure on a decision is refused HERE, not at the next run")
    void continueOnFailureOnADecisionIsRefused() {
        WorkflowBuilderSession session = session();
        addDecision(session);

        ToolExecutionResult result = modifyWithPolicy(session, "Check", Map.of("continueOnFailure", true));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("ALL its ports");
        assertThat(session.getCores().get(0)).doesNotContainKey(NodePolicy.JSON_KEY);
    }

    @Test
    @DisplayName("a policy on a trigger is refused, instead of being stored where nothing reads it")
    void policyOnATriggerIsRefused() {
        WorkflowBuilderSession session = session();
        addTrigger(session);

        ToolExecutionResult result = modifyWithPolicy(session, "Start", Map.of("retryCount", 2));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not available on trigger or note nodes");
        assertThat(session.getTriggers().get(0)).doesNotContainKey(NodePolicy.JSON_KEY);
    }

    @Test
    @DisplayName("a refused policy does not apply the rest of the patch either")
    @SuppressWarnings("unchecked")
    void aRefusedPolicyAbandonsTheWholePatch() {
        // The refusal message says "the node was left unchanged", so it has to be true: a patch
        // half-applied under a message saying nothing happened is worse than either outcome.
        WorkflowBuilderSession session = session();

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("node", "Publish");
        args.put("params", new LinkedHashMap<>(Map.of("caption", "would be lost")));
        args.put("nodePolicy", Map.of("timeoutMs", -5));
        ToolExecutionResult result = modifier.executeModifyNode(session, args);

        assertThat(result.success()).isFalse();
        assertThat((Map<String, Object>) publishNode(session).get("params"))
                .containsEntry("caption", "hello");
    }

    @Test
    @DisplayName("undo restores the absence of a policy, not a policy set to null")
    void undoRestoresTheAbsence() {
        // node.put(key, null) would answer containsKey, and the report keys "is a policy
        // configured" off exactly that: undo would then report a policy the node no longer has.
        WorkflowBuilderSession session = session();
        modifyWithPolicy(session, "Publish", Map.of("retryCount", 2));

        modifier.executeUndo(session);

        assertThat(publishNode(session)).doesNotContainKey(NodePolicy.JSON_KEY);
    }

    @Test
    @DisplayName("undo restores the PREVIOUS policy when there was one")
    void undoRestoresThePreviousPolicy() {
        WorkflowBuilderSession session = session();
        modifyWithPolicy(session, "Publish", Map.of("retryCount", 2));
        modifyWithPolicy(session, "Publish", Map.of("retryCount", 5));

        modifier.executeUndo(session);

        assertThat(publishNode(session).get(NodePolicy.JSON_KEY)).isEqualTo(Map.of("retryCount", 2));
    }

    @Test
    @DisplayName("a provider-retry budget on a node that calls no provider is refused")
    void providerBudgetOnANonToolNodeIsRefused() {
        WorkflowBuilderSession session = session();
        addDecision(session);

        ToolExecutionResult result = modifyWithPolicy(session, "Check",
                Map.of("providerRetryMaxWaitSec", 0));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("catalog tool step only").contains("left unchanged");
        assertThat(session.getCores().get(0)).doesNotContainKey(NodePolicy.JSON_KEY);
    }

    @Test
    @DisplayName("a modify with neither params nor a policy is still refused, so the door stays shut")
    void stillRefusesAnEmptyModify() {
        WorkflowBuilderSession session = session();

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("node", "Publish");
        ToolExecutionResult result = modifier.executeModifyNode(session, args);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("'params' is required");
    }
}
