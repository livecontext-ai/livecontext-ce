package com.apimarketplace.agent.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guardrail unit test: asserts that the three reset-path JPQL queries in {@link AgentRepository}
 * zero BOTH {@code credits_consumed} AND {@code credits_consumed_from_subagents} in the SET clause.
 *
 * <p>Rationale: the V71 observability column is written only by
 * {@code BudgetReservationService.settleReservationChain} (native SQL) and must be reset in
 * lockstep with {@code credits_consumed} by the three reset queries, otherwise the invariant
 * {@code credits_consumed_from_subagents <= credits_consumed} breaks the first time a reset
 * window rolls over (e.g. weekly reset zeroes {@code consumed} but a non-zero
 * {@code consumed_from_subagents} survives, producing a nonsense derived {@code consumed_own}).
 *
 * <p>A runtime @DataJpaTest would also catch this but agent-service has no existing Spring
 * test slice and introducing one just for three UPDATE assertions is overkill. Reading the
 * {@code @Query} string via reflection is pragmatic and catches the exact regression - a typo,
 * merge-conflict revert, or someone "cleaning up" the SET clause - that the auditor flagged.
 */
@DisplayName("AgentRepository reset queries - credits_consumed_from_subagents")
class AgentRepositoryResetQueryTest {

    @Test
    @DisplayName("zeroCreditsConsumedById sets creditsConsumedFromSubagents = 0 alongside creditsConsumed")
    void manualResetZeroesBothColumns() throws NoSuchMethodException {
        String jpql = jpqlOf("zeroCreditsConsumedById", UUID.class, Instant.class);
        assertThat(jpql)
            .contains("a.creditsConsumed = 0")
            .contains("a.creditsConsumedFromSubagents = 0");
    }

    @Test
    @DisplayName("resetConsumedIfUnreservedAndUnchanged sets creditsConsumedFromSubagents = 0 alongside creditsConsumed")
    void casResetZeroesBothColumns() throws NoSuchMethodException {
        String jpql = jpqlOf("resetConsumedIfUnreservedAndUnchanged",
            UUID.class, Instant.class, Instant.class);
        assertThat(jpql)
            .contains("a.creditsConsumed = 0")
            .contains("a.creditsConsumedFromSubagents = 0");
    }

    @Test
    @DisplayName("resetConsumedIfFirstReset sets creditsConsumedFromSubagents = 0 alongside creditsConsumed")
    void firstResetZeroesBothColumns() throws NoSuchMethodException {
        String jpql = jpqlOf("resetConsumedIfFirstReset", UUID.class, Instant.class);
        assertThat(jpql)
            .contains("a.creditsConsumed = 0")
            .contains("a.creditsConsumedFromSubagents = 0");
    }

    private String jpqlOf(String methodName, Class<?>... paramTypes) throws NoSuchMethodException {
        Method m = AgentRepository.class.getMethod(methodName, paramTypes);
        Query q = m.getAnnotation(Query.class);
        assertThat(q).as("expected @Query on %s", methodName).isNotNull();
        return q.value();
    }
}
