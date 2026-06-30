package com.apimarketplace.orchestrator.domain.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 2.A regression tests - partial-failure tracking on EpochState.
 *
 * Cross-references:
 * - Phase 2.A: durable storage for "node completed globally with at least one per-item failure"
 * - Phase 2.E: aggregate write at barrier seal calls markNodePartialFailure when mixed
 *
 * The bug this defends against:
 * Run run_<id> (2026-04-29) - 26-of-30 split-classify items succeeded
 * but the global agent:classify ended up in failedNodeIds because per-item markNodeFailed
 * fired on each of the 4 Google-429 items, poisoning the readiness gate. Phase 2.A
 * provides the storage; Phase 2.E provides the aggregate write that uses it.
 */
class EpochStatePartialFailureTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("withPartialFailure adds the node to partialFailedNodeIds")
    void epochStateWithPartialFailureReturnsNewInstanceWithMarker() {
        EpochState fresh = EpochState.fresh();
        assertTrue(fresh.getPartialFailedNodeIds().isEmpty(), "fresh state has no partial failures");

        EpochState updated = fresh.markNodePartialFailure("agent:classify");
        assertTrue(updated.getPartialFailedNodeIds().contains("agent:classify"),
            "after markNodePartialFailure the node is in the set");
        assertTrue(updated.isPartialFailure("agent:classify"), "isPartialFailure helper returns true");
        assertFalse(fresh.isPartialFailure("agent:classify"), "original instance unchanged (immutable)");
    }

    @Test
    @DisplayName("markNodePartialFailure is idempotent - re-applying does not duplicate or fail")
    void markNodePartialFailureIsIdempotent() {
        EpochState fresh = EpochState.fresh();
        EpochState once = fresh.markNodePartialFailure("agent:classify");
        EpochState twice = once.markNodePartialFailure("agent:classify");

        assertEquals(1, twice.getPartialFailedNodeIds().size());
        assertTrue(twice.isPartialFailure("agent:classify"));
    }

    @Test
    @DisplayName("partialFailedNodeIds is preserved by all with*/mark* mutators")
    void partialFailedNodeIdsPreservedByOtherMutators() {
        EpochState s = EpochState.fresh().markNodePartialFailure("agent:classify");

        assertTrue(s.markNodeCompleted("mcp:apply_finance").isPartialFailure("agent:classify"),
            "markNodeCompleted preserves partial-failure markers");
        assertTrue(s.markNodeFailed("mcp:apply_other").isPartialFailure("agent:classify"),
            "markNodeFailed preserves partial-failure markers");
        assertTrue(s.markNodeSkipped("mcp:apply_skipped").isPartialFailure("agent:classify"),
            "markNodeSkipped preserves partial-failure markers");
        assertTrue(s.addRunningNode("mcp:apply_running").isPartialFailure("agent:classify"),
            "addRunningNode preserves partial-failure markers");
        assertTrue(s.markNodeAwaitingSignal("interface:approval").isPartialFailure("agent:classify"),
            "markNodeAwaitingSignal preserves partial-failure markers");
        assertTrue(s.withReadyNodes(Set.of("x")).isPartialFailure("agent:classify"),
            "withReadyNodes preserves partial-failure markers");
        assertTrue(s.recordDecisionBranch("core:decision", "if").isPartialFailure("agent:classify"),
            "recordDecisionBranch preserves partial-failure markers");
    }

    @Test
    @DisplayName("removeNodes drops the targeted nodes from partialFailedNodeIds")
    void removeNodesDropsFromPartialFailedSet() {
        EpochState s = EpochState.fresh()
            .markNodePartialFailure("agent:classify")
            .markNodePartialFailure("agent:guardrail");

        EpochState removed = s.removeNodes(Set.of("agent:classify"));
        assertFalse(removed.isPartialFailure("agent:classify"));
        assertTrue(removed.isPartialFailure("agent:guardrail"));
    }

    @Test
    @DisplayName("epoch state forward-compat: deserializes old JSON snapshot without partialFailedNodeIds")
    void epochStatePartialFailedNodeIdsForwardCompatDeserializesOldSnapshots() throws Exception {
        // Simulates an EpochState payload written by an older orchestrator version
        // (no partialFailedNodeIds field). With @JsonIgnoreProperties(ignoreUnknown=true)
        // and the @JsonCreator null-handling, this must deserialize cleanly with the
        // new field defaulting to an empty Set.
        String legacyJson = """
            {
              "completedNodeIds": ["mcp:fetch", "core:is_new"],
              "failedNodeIds": ["agent:classify"],
              "skippedNodeIds": [],
              "runningNodeIds": [],
              "readyNodeIds": [],
              "awaitingSignalNodeIds": [],
              "decisionBranches": {},
              "loops": {},
              "splits": {},
              "startedAt": "2026-04-29T14:06:48Z"
            }
            """;
        EpochState restored = mapper.readValue(legacyJson, EpochState.class);
        assertEquals(2, restored.getCompletedNodeIds().size());
        assertEquals(1, restored.getFailedNodeIds().size());
        assertNotNull(restored.getPartialFailedNodeIds(),
            "partialFailedNodeIds defaults to empty Set, not null");
        assertTrue(restored.getPartialFailedNodeIds().isEmpty());
    }

    @Test
    @DisplayName("epoch state round-trip: serializes and deserializes partialFailedNodeIds")
    void epochStateSerializationRoundTrip() throws Exception {
        EpochState original = EpochState.fresh()
            .markNodeCompleted("mcp:fetch")
            .markNodePartialFailure("agent:classify");

        String json = mapper.writeValueAsString(original);
        EpochState restored = mapper.readValue(json, EpochState.class);

        assertTrue(restored.isPartialFailure("agent:classify"));
        assertTrue(restored.getCompletedNodeIds().contains("mcp:fetch"));
    }

    @Test
    @DisplayName("backward-compat constructor (10-arg) defaults partialFailedNodeIds to empty")
    void backwardCompatConstructorDefaultsPartialFailedToEmpty() {
        // Old test fixtures and existing production callers use the 10-arg constructor
        // without specifying partialFailedNodeIds. The shim constructor must default it to empty.
        EpochState legacy = new EpochState(
            Set.of("mcp:fetch"),       // completed
            Set.of(),                  // failed
            Set.of(),                  // skipped
            Set.of(),                  // running
            Set.of(),                  // ready
            Set.of(),                  // awaitingSignal
            java.util.Map.of(),        // decisionBranches
            java.util.Map.of(),        // loops
            java.util.Map.of(),        // splits
            Instant.now()              // startedAt
        );
        assertNotNull(legacy.getPartialFailedNodeIds());
        assertTrue(legacy.getPartialFailedNodeIds().isEmpty());
    }
}
