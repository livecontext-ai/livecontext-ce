package com.apimarketplace.orchestrator.persistence;

import com.apimarketplace.orchestrator.execution.v2.constants.ExecutionMetadataKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * {@code WorkflowStepDataRepository.findPassedItemIndicesByEpoch}: which split items reach the node
 * below a node. The items it COMPLETED, plus the ones whose latest failure continued
 * ({@code continueOnFailure}, flagged in the row's metadata by StepDataPersistenceService).
 *
 * <p>REGRESSION (2026-09-29): the first version was a native query on the Postgres JSON operator
 * {@code ->>}. The H2 integration suites route splits through this method too, the query threw
 * there, the routing fell back, and a post-merge node ran three times instead of once
 * (PerItemContinuationIntegrationTest). The flag is now read in Java from two portable JPQL
 * queries, which Spring Data parses at boot on every database.
 *
 * <p>REGRESSION (round-2 audit, 2026-09-29): every FAILED row of the epoch used to count, whatever
 * loop turn or rerun it came from, so a node that continued in turn 1 carried turn 2's refusal for
 * missing credits past itself. Only each item's latest failure counts now
 * ({@code findFailedItemMetadataByEpoch}), which readiness reads too.
 */
@DisplayName("findPassedItemIndicesByEpoch - split routing past a continueOnFailure item")
class WorkflowStepDataPassedItemIndicesTest {

    private static final String RUN = "run_passed_items";
    private static final String KEY = "mcp:call";
    private static final int EPOCH = 3;

    private static final Map<String, Object> CONTINUED = Map.of(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, true);
    private static final Map<String, Object> STOPPED = Map.of("statusMessage", "Insufficient credits (pre-flight tenant budget)");

    private WorkflowStepDataRepository repository;

    @BeforeEach
    void setUp() {
        repository = mock(WorkflowStepDataRepository.class, CALLS_REAL_METHODS);
    }

    private void rows(List<Integer> completed, List<Object[]> failed) {
        doReturn(completed).when(repository).findCompletedItemIndicesByEpoch(RUN, KEY, EPOCH);
        doReturn(failed).when(repository).findFailedItemRowsByEpoch(RUN, KEY, EPOCH);
    }

    private static Object[] failedRow(int itemIndex, Map<String, Object> metadata) {
        return failedRow(itemIndex, metadata, 0, 0);
    }

    private static Object[] failedRow(int itemIndex, Map<String, Object> metadata, Integer spawn, Integer iteration) {
        return new Object[]{itemIndex, metadata, spawn, iteration};
    }

    @Test
    @DisplayName("REGRESSION: a FAILED item flagged continueOnFailure passes, like a COMPLETED one; an unflagged failure does not")
    void continuedFailurePassesUnflaggedFailureDoesNot() {
        List<Object[]> failed = new ArrayList<>();
        failed.add(failedRow(1, CONTINUED));
        failed.add(failedRow(2, null));
        failed.add(failedRow(3, Map.of("statusMessage", "provider down")));
        rows(List.of(0), failed);

        assertThat(repository.findPassedItemIndicesByEpoch(RUN, KEY, EPOCH)).containsExactly(0, 1);
    }

    @Test
    @DisplayName("a false flag does not pass")
    void falseFlagDoesNotPass() {
        List<Object[]> failed = new ArrayList<>();
        failed.add(failedRow(0, Map.of(ExecutionMetadataKeys.POLICY_CONTINUE_ON_FAILURE, false)));
        rows(List.of(), failed);

        assertThat(repository.findPassedItemIndicesByEpoch(RUN, KEY, EPOCH)).isEmpty();
    }

    @Test
    @DisplayName("an item with a COMPLETED row and a continued FAILED row (a re-run spawn) is returned once")
    void itemReturnedOnce() {
        List<Object[]> failed = new ArrayList<>();
        failed.add(failedRow(0, CONTINUED));
        rows(List.of(0, 1), failed);

        assertThat(repository.findPassedItemIndicesByEpoch(RUN, KEY, EPOCH)).containsExactly(0, 1);
    }

    @Test
    @DisplayName("without any flagged row it returns exactly the COMPLETED items (the routing before continueOnFailure)")
    void noFlagMeansCompletedOnly() {
        List<Object[]> failed = new ArrayList<>();
        failed.add(failedRow(1, null));
        rows(List.of(0, 2), failed);

        assertThat(repository.findPassedItemIndicesByEpoch(RUN, KEY, EPOCH)).containsExactly(0, 2);
    }

    @Nested
    @DisplayName("Only each item's LATEST failure counts")
    class LatestFailure {

        @Test
        @DisplayName("REGRESSION: a loop turn that continued does not carry a later turn's refusal past the node")
        void earlierLoopTurnDoesNotCount() {
            List<Object[]> failed = new ArrayList<>();
            failed.add(failedRow(0, CONTINUED, 0, 0));
            failed.add(failedRow(0, STOPPED, 0, 1));
            rows(List.of(), failed);

            assertThat(repository.findPassedItemIndicesByEpoch(RUN, KEY, EPOCH)).isEmpty();
            assertThat(repository.findFailedItemMetadataByEpoch(RUN, KEY, EPOCH))
                .singleElement().satisfies(row -> assertThat(row).containsExactly(0, STOPPED));
        }

        @Test
        @DisplayName("the latest turn that continued passes, whatever an earlier turn did")
        void latestContinuedTurnPasses() {
            List<Object[]> failed = new ArrayList<>();
            failed.add(failedRow(0, STOPPED, 0, 1));
            failed.add(failedRow(0, CONTINUED, 0, 2));
            rows(List.of(), failed);

            assertThat(repository.findPassedItemIndicesByEpoch(RUN, KEY, EPOCH)).containsExactly(0);
        }

        @Test
        @DisplayName("a rerun (a later spawn) outranks every turn of the run before it")
        void laterSpawnOutranksAnyIteration() {
            List<Object[]> failed = new ArrayList<>();
            failed.add(failedRow(0, CONTINUED, 0, 5));
            failed.add(failedRow(0, STOPPED, 1, 0));
            rows(List.of(), failed);

            assertThat(repository.findPassedItemIndicesByEpoch(RUN, KEY, EPOCH)).isEmpty();
        }

        @Test
        @DisplayName("each item is judged on its own: rerunning item 2 leaves items 0 and 1 continued")
        void eachItemOnItsOwn() {
            List<Object[]> failed = new ArrayList<>();
            failed.add(failedRow(0, CONTINUED, 0, 0));
            failed.add(failedRow(1, CONTINUED, 0, 0));
            failed.add(failedRow(2, STOPPED, 1, 0));
            rows(List.of(), failed);

            assertThat(repository.findPassedItemIndicesByEpoch(RUN, KEY, EPOCH)).containsExactly(0, 1);
        }

        @Test
        @DisplayName("a missing spawn or iteration reads as 0")
        void missingCoordinatesReadAsZero() {
            List<Object[]> failed = new ArrayList<>();
            failed.add(failedRow(0, CONTINUED, null, null));
            failed.add(failedRow(0, STOPPED, 0, 1));
            rows(List.of(), failed);

            assertThat(repository.findPassedItemIndicesByEpoch(RUN, KEY, EPOCH)).isEmpty();
        }
    }

    @Test
    @DisplayName("no query of the routing uses a Postgres-only operator: both are JPQL, parsed at boot on every database")
    void theQueriesArePortable() throws Exception {
        for (String method : List.of("findCompletedItemIndicesByEpoch", "findFailedItemRowsByEpoch")) {
            Query query = WorkflowStepDataRepository.class
                .getDeclaredMethod(method, String.class, String.class, int.class).getAnnotation(Query.class);
            assertThat(query.nativeQuery()).as(method).isFalse();
            assertThat(query.value()).as(method).doesNotContain("->>").doesNotContain("->");
        }
    }
}
