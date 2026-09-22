package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.ToolRef;
import com.apimarketplace.orchestrator.domain.workflow.CredentialSource;
import com.apimarketplace.orchestrator.domain.workflow.NodePolicy;
import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.services.interfaces.ExecutionResult;
import com.apimarketplace.orchestrator.services.interfaces.ToolsGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * Who decides how long a provider call may spend waiting out a rate-limit refusal.
 *
 * <p>The failure this pins is not a crash. The platform retries a 429 by default, which is right
 * for a step with no pacing of its own; a node that retries, or a loop that calls, waits and comes
 * back, is the opposite case, and there the two layers <b>multiply</b>: three node attempts around
 * a call the platform re-sends twice is nine requests to a provider that just asked us to slow
 * down. So the author who was careful hammers the provider harder than the one who was not, and
 * nothing in the run says so.
 *
 * <p>These tests assert on the marker that actually leaves the node, not on the helper that
 * computes it: a correct decision that never reaches the gateway is the same outcome as no
 * decision at all.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StepNode - the provider retry budget that travels with the call")
class StepNodeProviderRetryBudgetTest {

    private static final String MARKER = "__providerRetryMaxWaitSec__";

    @Mock private WorkflowPlan plan;
    @Mock private ToolsGateway toolsGateway;
    @Mock private V2TemplateAdapter templateAdapter;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create(
                "run-1", "workflow-run-1", "tenant-1", "item-1", 0, new HashMap<>(), plan);
        lenient().when(plan.getId()).thenReturn("workflow-1");
        lenient().when(templateAdapter.resolveTemplates(anyMap(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(templateAdapter.hasUnresolvedTemplates(anyMap(), any())).thenReturn(false);
        lenient().when(toolsGateway.executeTool(any(ToolRef.class), anyMap(), anyString(), anyMap()))
                .thenReturn(new ExecutionResult(true, Map.of("ok", true), List.of(), List.of()));
    }

    private StepNode node() {
        Step step = new Step("instagram/publish", "mcp", "Publish", null,
                Map.of("caption", "hello"), null, null, "node-1",
                null, CredentialSource.USER, null, null);
        StepNode stepNode = new StepNode("node-1", step);
        stepNode.setToolsGateway(toolsGateway);
        stepNode.setTemplateAdapter(templateAdapter);
        return stepNode;
    }

    private Map<String, Object> markersSentToGateway() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(toolsGateway).executeTool(any(ToolRef.class), anyMap(), anyString(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a node with no policy says nothing, so the platform's own budget applies")
    void noPolicySaysNothing() {
        // DEFAULT, not null: WorkflowPlan.getNodePolicy answers DEFAULT for a node without a block,
        // so this is the state a real plan actually produces. Stubbing null instead would test a
        // branch the engine never reaches and leave the real one uncovered.
        lenient().when(plan.getNodePolicy("node-1")).thenReturn(NodePolicy.DEFAULT);

        node().execute(context);

        assertThat(markersSentToGateway())
                .as("absent is not '0': the overwhelming majority of steps never thought about a "
                        + "429, and honouring the delay the provider asked for is our job, not theirs")
                .doesNotContainKey(MARKER);
    }

    @Test
    @DisplayName("a node that retries itself makes the platform stand down, without anyone "
            + "setting a budget")
    void aNodeThatRetriesMakesThePlatformStandDown() {
        // THE case this feature exists for. The author configured a retry; that is the author
        // saying they own the retrying, and a second retry underneath them was never asked for.
        lenient().when(plan.getNodePolicy("node-1"))
                .thenReturn(new NodePolicy(2, 1_000L, false, 0L, false, null));

        node().execute(context);

        assertThat(markersSentToGateway()).containsEntry(MARKER, 0);
    }

    @Test
    @DisplayName("an explicit budget wins over that inference, in the direction of MORE waiting")
    void explicitBudgetWinsOverTheInference() {
        // An author who wants both layers can have both: the platform absorbs the burst limit
        // inside one attempt, and the node's own retry covers the rest. Only they can judge that,
        // so an explicit value is never second-guessed.
        lenient().when(plan.getNodePolicy("node-1"))
                .thenReturn(new NodePolicy(3, 1_000L, false, 0L, false, 45));

        node().execute(context);

        assertThat(markersSentToGateway()).containsEntry(MARKER, 45);
    }

    @Test
    @DisplayName("an explicit zero is sent, and is not mistaken for 'said nothing'")
    void explicitZeroIsSent() {
        // The workflow paces itself in a loop, which no heuristic can see: retryCount is 0 here,
        // so without the explicit field this node would get the platform default.
        lenient().when(plan.getNodePolicy("node-1"))
                .thenReturn(new NodePolicy(0, 0L, false, 0L, false, 0));

        node().execute(context);

        assertThat(markersSentToGateway()).containsEntry(MARKER, 0);
    }

    @Test
    @DisplayName("a budget set without any node retry is sent as it stands")
    void budgetWithoutRetryIsSent() {
        lenient().when(plan.getNodePolicy("node-1"))
                .thenReturn(new NodePolicy(0, 0L, false, 0L, false, 60));

        node().execute(context);

        assertThat(markersSentToGateway()).containsEntry(MARKER, 60);
    }

    @Test
    @DisplayName("THE money case: a per-attempt timeout bounds the wait, so the node cannot abandon "
            + "an attempt the provider call then completes and charges for")
    void aTimeoutBoundsTheBudget() {
        // Without this, timeoutMs reproduced the worst failure this platform has: the node gives up
        // at 3s, the catalog sleeps out a 5s Retry-After, re-sends, succeeds, stores the result and
        // commits the charge. The run says FAILED, the credit is spent, and nothing releases it
        // because from the catalog's side nothing failed.
        lenient().when(plan.getNodePolicy("node-1"))
                .thenReturn(new NodePolicy(0, 0L, false, 3_000L, false, null));

        node().execute(context);

        assertThat(markersSentToGateway())
                .as("half the window: the attempt pays for the requests as well as the wait")
                .containsEntry(MARKER, 1);
    }

    @Test
    @DisplayName("a generous timeout leaves the platform's own budget the effective one")
    void aGenerousTimeoutChangesNothingInPractice() {
        // 30s timeout -> 15s asked, which the catalog caps at its own budget. The point is that the
        // bound does not make an ordinary node retry LESS than it did before.
        lenient().when(plan.getNodePolicy("node-1"))
                .thenReturn(new NodePolicy(0, 0L, false, 30_000L, false, null));

        node().execute(context);

        assertThat(markersSentToGateway()).containsEntry(MARKER, 15);
    }

    @Test
    @DisplayName("THE money case again: an EXPLICIT budget is still capped by the node's own timeout")
    void anExplicitBudgetIsCappedByTheTimeout() {
        // The same billed-while-FAILED incident, reached by setting the field instead of leaving it
        // empty. An author may raise the budget against the PLATFORM default; they may not raise it
        // past the deadline they gave this attempt, because past that point the node abandons the
        // attempt while the catalog sleeps on, re-sends, succeeds and commits the charge.
        lenient().when(plan.getNodePolicy("node-1"))
                .thenReturn(new NodePolicy(0, 0L, false, 3_000L, false, 30));

        node().execute(context);

        assertThat(markersSentToGateway())
                .as("30 asked, 1 allowed by a 3s attempt window")
                .containsEntry(MARKER, 1);
    }

    @Test
    @DisplayName("an explicit budget BELOW that ceiling is honoured as it stands")
    void anExplicitBudgetBelowTheCeilingIsHonoured() {
        // The cap is min(), not "ignore the author": tightening is always theirs to do.
        lenient().when(plan.getNodePolicy("node-1"))
                .thenReturn(new NodePolicy(0, 0L, false, 30_000L, false, 5));

        node().execute(context);

        assertThat(markersSentToGateway()).containsEntry(MARKER, 5);
    }

    @Test
    @DisplayName("with no timeout, an explicit budget is sent untouched")
    void anExplicitBudgetWithoutATimeoutIsUntouched() {
        lenient().when(plan.getNodePolicy("node-1"))
                .thenReturn(new NodePolicy(0, 0L, false, 0L, false, 30));

        node().execute(context);

        assertThat(markersSentToGateway()).containsEntry(MARKER, 30);
    }

    @Test
    @DisplayName("a node that retries AND has a timeout still stands the platform down entirely")
    void retryStillWinsOverTheTimeoutBound() {
        // retryCount is the stronger statement: the author owns the retrying. Reading the timeout
        // first would give the platform a wait the author did not ask it to take.
        lenient().when(plan.getNodePolicy("node-1"))
                .thenReturn(new NodePolicy(2, 1_000L, false, 30_000L, false, null));

        node().execute(context);

        assertThat(markersSentToGateway()).containsEntry(MARKER, 0);
    }

    @Test
    @DisplayName("a timeout shorter than the retry floor asks for 0, not for a wait it cannot afford")
    void aVeryShortTimeoutAsksForNoWait() {
        // 1500ms / 2 = 0s. The catalog's minimum wait is 250ms, so any budget of 0 refuses every
        // retry - which is the right answer for a node that gave itself no room.
        lenient().when(plan.getNodePolicy("node-1"))
                .thenReturn(new NodePolicy(0, 0L, false, 1_500L, false, null));

        node().execute(context);

        assertThat(markersSentToGateway()).containsEntry(MARKER, 0);
    }

    @Test
    @DisplayName("a null policy is tolerated rather than thrown on, for the callers that mock the plan")
    void aNullPolicyIsTolerated() {
        // Not a state WorkflowPlan produces (it answers DEFAULT), so this is a defensive guard
        // rather than a behaviour - said plainly here so nobody reads it as the real path. It earns
        // its keep because getNodePolicy is a mocked collaborator in a great many tests, and an NPE
        // in the middle of executing a step is a very expensive way to learn that.
        lenient().when(plan.getNodePolicy("node-1")).thenReturn(null);

        assertThat(node().resolveProviderRetryBudget(context)).isEmpty();
    }

    @Test
    @DisplayName("the budget is resolved from the plan, so a node with no plan behind it is "
            + "simply silent")
    void noPlanIsSilent() {
        ExecutionContext contextWithoutPlan = ExecutionContext.create(
                "run-1", "workflow-run-1", "tenant-1", "item-1", 0, new HashMap<>(), null);

        assertThat(node().resolveProviderRetryBudget(contextWithoutPlan)).isEmpty();
        assertThat(node().resolveProviderRetryBudget(null)).isEmpty();
    }
}
