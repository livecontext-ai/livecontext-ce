package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.Agent;
import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link NodePolicyRunner#effectivePolicy}: the policy a node runs under.
 *
 * <p>A classify or guardrail agent picks a branch; a failed one picked none, so continuing past
 * its failure would send its input down EVERY branch at once (a failed guardrail feeding its pass
 * branch). The builder refuses the flag there; a plan stored before that refusal keeps opening
 * and runs the node without the continuation.
 */
@DisplayName("NodePolicyRunner.effectivePolicy - classify and guardrail never continue")
class NodePolicyRunnerEffectivePolicyTest {

    private static final NodePolicy CONTINUING = new NodePolicy(2, 500L, true, 30_000L, false, null);

    private static WorkflowPlan planWithAgent(String nodeId, String type) {
        WorkflowPlan plan = mock(WorkflowPlan.class);
        when(plan.getNodePolicy(nodeId)).thenReturn(CONTINUING);
        Agent agent = mock(Agent.class);
        when(agent.type()).thenReturn(type);
        when(plan.findAgent(nodeId)).thenReturn(Optional.of(agent));
        return plan;
    }

    @Test
    @DisplayName("REGRESSION: a stored continueOnFailure on a classify or a guardrail is dropped, every other field kept")
    void branchingAgentLosesOnlyTheContinuation() {
        for (String type : new String[] {"classify", "guardrail", "Guardrail"}) {
            NodePolicy effective = NodePolicyRunner.effectivePolicy(planWithAgent("agent:route", type), "agent:route");

            assertThat(effective.continueOnFailure()).as(type).isFalse();
            assertThat(effective.retryCount()).isEqualTo(2);
            assertThat(effective.retryBackoffMs()).isEqualTo(500L);
            assertThat(effective.timeoutMs()).isEqualTo(30_000L);
        }
    }

    @Test
    @DisplayName("REGRESSION: a stored continueOnFailure on a loop is dropped: a failed loop would start its body AND its exit")
    void loopLosesTheContinuation() {
        WorkflowPlan plan = planWithCore("core:repeat", "loop");

        NodePolicy effective = NodePolicyRunner.effectivePolicy(plan, "core:repeat");

        assertThat(effective.continueOnFailure()).isFalse();
        assertThat(effective.retryCount()).isEqualTo(2);
        assertThat(effective.timeoutMs()).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("any other core keeps it, a transform or a fork")
    void otherCoresKeepIt() {
        for (String type : new String[] {"transform", "fork"}) {
            assertThat(NodePolicyRunner.effectivePolicy(planWithCore("core:step", type), "core:step"))
                .as(type).isEqualTo(CONTINUING);
        }
    }

    private static WorkflowPlan planWithCore(String nodeId, String type) {
        WorkflowPlan plan = mock(WorkflowPlan.class);
        when(plan.getNodePolicy(nodeId)).thenReturn(CONTINUING);
        com.apimarketplace.orchestrator.domain.workflow.Core core =
            mock(com.apimarketplace.orchestrator.domain.workflow.Core.class);
        when(core.getNormalizedKey()).thenReturn(nodeId);
        when(core.type()).thenReturn(type);
        when(plan.getCores()).thenReturn(java.util.List.of(core));
        return plan;
    }

    @Test
    @DisplayName("a plain agent, a tool step and a core keep their continuation")
    void otherNodesKeepIt() {
        assertThat(NodePolicyRunner.effectivePolicy(planWithAgent("agent:writer", "agent"), "agent:writer"))
            .isEqualTo(CONTINUING);
        WorkflowPlan plan = mock(WorkflowPlan.class);
        when(plan.getNodePolicy("mcp:call")).thenReturn(CONTINUING);
        assertThat(NodePolicyRunner.effectivePolicy(plan, "mcp:call")).isEqualTo(CONTINUING);
    }

    @Test
    @DisplayName("the strip keeps every other field, executeOnce and retryOn included")
    void theStripKeepsEveryOtherField() {
        // A strip that dropped executeOnce would make a classify in a split run for every item.
        NodePolicy full = new NodePolicy(3, 750L, true, 20_000L, true, NodePolicy.RETRY_ON_RATE_LIMIT);

        NodePolicy stripped = full.withoutContinueOnFailure();

        assertThat(stripped).isEqualTo(new NodePolicy(3, 750L, false, 20_000L, true, NodePolicy.RETRY_ON_RATE_LIMIT));
        assertThat(new NodePolicy(1, 0L, false).withoutContinueOnFailure()).isEqualTo(new NodePolicy(1, 0L, false));
    }

    @Test
    @DisplayName("no plan or no policy is the default policy")
    void defaults() {
        assertThat(NodePolicyRunner.effectivePolicy(null, "agent:x")).isEqualTo(NodePolicy.DEFAULT);
        assertThat(NodePolicyRunner.effectivePolicy(mock(WorkflowPlan.class), "agent:x")).isEqualTo(NodePolicy.DEFAULT);
    }
}
