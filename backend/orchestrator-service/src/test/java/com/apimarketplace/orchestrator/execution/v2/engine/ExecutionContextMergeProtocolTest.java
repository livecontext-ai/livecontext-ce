package com.apimarketplace.orchestrator.execution.v2.engine;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.nodes.NodeExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for the {@code ExecutionContext.merge} stepOutputs collision protocol
 * (Phase-1 engine parallelism hardening).
 *
 * <p>Contract: parallel branches write disjoint nodeIds by construction, so the
 * only legitimate overlaps are (a) identical pre-fork prefix values and (b) a
 * RUNNING-vs-terminal race on the same node. On a value conflict the side with
 * the more advanced node status wins (completed-over-running); ties keep the
 * incoming side (legacy {@code putAll} semantics).
 */
@DisplayName("ExecutionContext.merge - stepOutputs collision protocol")
class ExecutionContextMergeProtocolTest {

    private ExecutionContext baseContext() {
        return ExecutionContext.create(
            "run-1", "workflow-run-1", "tenant-1",
            "item-1", 0, Map.of(), mock(WorkflowPlan.class)
        );
    }

    @Test
    @DisplayName("disjoint branch outputs are unioned (both nodes present after merge)")
    void disjointOutputsAreUnioned() {
        ExecutionContext base = baseContext();
        ExecutionContext branchA = base.withResult("mcp:a", NodeExecutionResult.success("mcp:a", Map.of("v", "fromA")));
        ExecutionContext branchB = base.withResult("mcp:b", NodeExecutionResult.success("mcp:b", Map.of("v", "fromB")));

        ExecutionContext merged = branchA.merge(branchB);

        assertThat(merged.isSuccess("mcp:a")).isTrue();
        assertThat(merged.isSuccess("mcp:b")).isTrue();
        assertThat(merged.stepOutputs()).containsKeys("mcp:a", "a", "mcp:b", "b");
    }

    @Test
    @DisplayName("identical pre-fork prefix values pass through untouched")
    void identicalPrefixValuesPassThrough() {
        ExecutionContext prefix = baseContext()
            .withResult("trigger:start", NodeExecutionResult.success("trigger:start", Map.of("v", "shared")));
        // Both branches derive from the same prefix context
        ExecutionContext branchA = prefix.withResult("mcp:a", NodeExecutionResult.success("mcp:a", Map.of()));
        ExecutionContext branchB = prefix.withResult("mcp:b", NodeExecutionResult.success("mcp:b", Map.of()));

        ExecutionContext merged = branchA.merge(branchB);

        assertThat(merged.getStepOutput("trigger:start")).isPresent();
        assertThat(merged.getStepOutput("trigger:start").get())
            .isEqualTo(prefix.getStepOutput("trigger:start").get());
    }

    @Test
    @DisplayName("conflict: COMPLETED side beats RUNNING side when COMPLETED is the receiver (pre-fix putAll let RUNNING win)")
    void completedReceiverBeatsRunningIncoming() {
        ExecutionContext base = baseContext();

        // Receiver: node mcp:x COMPLETED with terminal output
        ExecutionContext completedSide = base.withResult("mcp:x",
            NodeExecutionResult.success("mcp:x", Map.of("v", "final")));

        // Incoming: node mcp:x only STARTED (RUNNING) with a partial/stale output
        ExecutionContext runningSide = base.withStart("mcp:x")
            .withStepOutput("mcp:x", Map.of("v", "partial"));

        ExecutionContext merged = completedSide.merge(runningSide);

        // Pre-fix: blind putAll → the RUNNING side's partial output clobbered the
        // terminal output. Post-fix: completed-over-running keeps the terminal value.
        assertThat(merged.stepOutputs().get("mcp:x"))
            .as("terminal output must survive a merge against a RUNNING sibling record")
            .isEqualTo(completedSide.stepOutputs().get("mcp:x"));
        // The merged STATE is terminal either way (ExecutionState.merge is advancement-aware)
        assertThat(merged.isSuccess("mcp:x")).isTrue();
    }

    @Test
    @DisplayName("conflict: COMPLETED incoming side beats RUNNING receiver")
    void completedIncomingBeatsRunningReceiver() {
        ExecutionContext base = baseContext();

        ExecutionContext runningSide = base.withStart("mcp:x")
            .withStepOutput("mcp:x", Map.of("v", "partial"));
        ExecutionContext completedSide = base.withResult("mcp:x",
            NodeExecutionResult.success("mcp:x", Map.of("v", "final")));

        ExecutionContext merged = runningSide.merge(completedSide);

        assertThat(merged.stepOutputs().get("mcp:x"))
            .isEqualTo(completedSide.stepOutputs().get("mcp:x"));
        assertThat(merged.isSuccess("mcp:x")).isTrue();
    }

    @Test
    @DisplayName("conflict tie (both terminal): incoming side wins - legacy putAll semantics preserved")
    void terminalTieKeepsIncoming() {
        ExecutionContext base = baseContext();
        ExecutionContext first = base.withResult("mcp:x",
            NodeExecutionResult.success("mcp:x", Map.of("v", "first")));
        ExecutionContext second = base.withResult("mcp:x",
            NodeExecutionResult.success("mcp:x", Map.of("v", "second")));

        ExecutionContext merged = first.merge(second);

        assertThat(merged.stepOutputs().get("mcp:x"))
            .as("equal advancement keeps the incoming side, matching the historical putAll order")
            .isEqualTo(second.stepOutputs().get("mcp:x"));
    }

    @Test
    @DisplayName("alias key follows the same winner as its full key (no split-brain between 'mcp:x' and 'x')")
    void aliasFollowsFullKeyWinner() {
        ExecutionContext base = baseContext();

        ExecutionContext completedSide = base.withResult("mcp:x",
            NodeExecutionResult.success("mcp:x", Map.of("v", "final")));
        ExecutionContext runningSide = base.withStart("mcp:x")
            .withStepOutput("mcp:x", Map.of("v", "partial"));

        ExecutionContext merged = completedSide.merge(runningSide);

        // Both the full key and the bare alias must resolve to the SAME (terminal) value.
        assertThat(merged.stepOutputs().get("mcp:x"))
            .isEqualTo(merged.stepOutputs().get("x"))
            .isEqualTo(completedSide.stepOutputs().get("mcp:x"));
    }

    @Test
    @DisplayName("merge(null) returns this")
    void mergeNullReturnsThis() {
        ExecutionContext base = baseContext()
            .withResult("mcp:a", NodeExecutionResult.success("mcp:a", Map.of()));
        assertThat(base.merge(null)).isSameAs(base);
    }
}
