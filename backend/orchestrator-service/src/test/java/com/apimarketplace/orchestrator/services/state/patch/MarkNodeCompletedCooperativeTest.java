package com.apimarketplace.orchestrator.services.state.patch;

import com.apimarketplace.orchestrator.domain.execution.StateSnapshot;
import com.apimarketplace.orchestrator.services.state.elide.TenantElideFlagResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan v4 §2t - cooperative DELTA+ASSIGN builder variant for markNodeCompleted.
 * Pins the contract: 1 DELTA patch on nodes.{X}.completed + N ASSIGN patches
 * on the rest (sets + NodeCounts sub-fields excluding completed).
 */
@DisplayName("Plan v4 §2t - MarkNodeCompletedPatchBuilder.buildWithDeltaCounter")
class MarkNodeCompletedCooperativeTest {

    private static final String TRIGGER = "trigger:webhook";
    private static final int EPOCH = 0;
    private static final String NODE = "X";
    private static final String TENANT = "tenant-a";

    private MarkNodeCompletedPatchBuilder builder;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        TenantElideFlagResolver elideOff = tenantId -> false;
        builder = new MarkNodeCompletedPatchBuilder(mapper, elideOff);
    }

    private StateSnapshot beforeWithEpoch() {
        return StateSnapshot.empty()
                .ensureDagInitialized(TRIGGER, EPOCH)
                .addRunningNode(TRIGGER, NODE, EPOCH);
    }

    @Test
    @DisplayName("Cooperative variant emits 1 DELTA on completed + ASSIGN sub-path patches")
    void cooperativeEmitsDeltaPlusAssigns() {
        StateSnapshot before = beforeWithEpoch();
        StateSnapshot after = before.markNodeCompleted(TRIGGER, NODE, EPOCH);

        var result = builder.buildWithDeltaCounter(before, after, TRIGGER, EPOCH, NODE, TENANT);

        assertThat(result.isFallback()).isFalse();
        List<JsonbPatch> patches = result.patches().orElseThrow();

        // Find the DELTA on nodes.X.completed
        JsonbPatch deltaPatch = patches.stream()
                .filter(p -> p.opKind() == JsonbPatch.OpKind.COMMUTATIVE_DELTA)
                .findFirst().orElseThrow();
        assertThat(deltaPatch.path()).containsExactly("nodes", NODE, "completed");
        assertThat(deltaPatch.jsonValue()).isEqualTo("1");

        // All other patches are ASSIGN
        long deltaCount = patches.stream()
                .filter(p -> p.opKind() == JsonbPatch.OpKind.COMMUTATIVE_DELTA)
                .count();
        assertThat(deltaCount).as("exactly 1 DELTA patch on completed").isEqualTo(1);

        // Verify ASSIGN sub-fields on NodeCounts are present (running/failed/skipped/timing)
        long nodeCountsSubFields = patches.stream()
                .filter(p -> p.opKind() == JsonbPatch.OpKind.ASSIGN)
                .filter(p -> p.path().length == 3
                        && "nodes".equals(p.path()[0])
                        && NODE.equals(p.path()[1]))
                .count();
        assertThat(nodeCountsSubFields)
                .as("running + failed + skipped + 3 timing fields = 6 sub-path ASSIGN")
                .isEqualTo(6);
    }

    @Test
    @DisplayName("Fallback when nodes.{X} record missing (jsonb_set leaf-only constraint)")
    void fallbackWhenNodeMissing() {
        StateSnapshot before = beforeWithEpoch();
        // 'after' without the markNodeCompleted call → no NodeCounts entry
        StateSnapshot after = before;  // unchanged

        var result = builder.buildWithDeltaCounter(before, after, TRIGGER, EPOCH, NODE, TENANT);

        assertThat(result.isFallback()).isTrue();
    }

    @Test
    @DisplayName("Fallback when epoch hasn't been opened in 'before' snapshot")
    void fallbackWhenEpochNotOpened() {
        StateSnapshot before = StateSnapshot.empty();  // no DAG initialized
        StateSnapshot after = before.markNodeCompleted(TRIGGER, NODE, EPOCH);

        var result = builder.buildWithDeltaCounter(before, after, TRIGGER, EPOCH, NODE, TENANT);

        assertThat(result.isFallback()).isTrue();
    }

    @Test
    @DisplayName("Elide ON → omits runningNodeIds patch (mirrors legacy build())")
    void elideOmitsRunningNodeIds() {
        TenantElideFlagResolver elideOn = tenantId -> true;
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        MarkNodeCompletedPatchBuilder builderElideOn =
                new MarkNodeCompletedPatchBuilder(mapper, elideOn);

        StateSnapshot before = beforeWithEpoch();
        StateSnapshot after = before.markNodeCompleted(TRIGGER, NODE, EPOCH);

        var result = builderElideOn.buildWithDeltaCounter(before, after, TRIGGER, EPOCH, NODE, TENANT);

        long runningSetPatches = result.patches().orElseThrow().stream()
                .filter(p -> p.path().length == 5
                        && PatchPaths.RUNNING_NODE_IDS.equals(p.path()[4]))
                .count();
        assertThat(runningSetPatches).as("elide ON → runningNodeIds patch omitted").isZero();
    }
}
