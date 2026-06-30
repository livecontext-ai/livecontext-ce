package com.apimarketplace.agent.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guardrail unit test: asserts that {@link AgentExecutionRepository#incrementCounters}
 * bumps BOTH {@code lastExecutionAt} AND {@code updatedAt} in the SET clause.
 *
 * <p>Rationale: the bell's Activity tab orders by {@code agents.updated_at DESC} via
 * V236 partial indexes. Before this guard, only {@code lastExecutionAt} was bumped
 * per execution (used by AgentMetrics) - {@code updatedAt} stayed frozen on the last
 * config edit, leaving agents stuck at the bottom of the Activity feed even when
 * they were actively executing tasks. {@code @PreUpdate} on {@link com.apimarketplace.agent.domain.AgentEntity}
 * does NOT cover this path because JPQL {@code @Modifying @Query} bulk UPDATEs
 * bypass JPA lifecycle callbacks (Hibernate contract).
 *
 * <p>Mirror of {@link AgentRepositoryResetQueryTest} - a runtime {@code @DataJpaTest}
 * would also catch this but agent-service has no Spring test slice and introducing
 * one just to assert two SET fields is overkill. Reflection-based JPQL inspection
 * is pragmatic and catches the exact regression - a typo, merge-conflict revert, or
 * someone removing the new field - that the audit flagged.
 */
@DisplayName("AgentExecutionRepository.incrementCounters - bumps updated_at alongside last_execution_at")
class AgentExecutionRepositoryIncrementCountersQueryTest {

    @Test
    @DisplayName("incrementCounters SET clause contains both a.lastExecutionAt = :now AND a.updatedAt = :now")
    void bumpsBothTimestamps() throws NoSuchMethodException {
        String jpql = jpqlOf("incrementCounters",
            UUID.class, long.class, int.class, int.class, int.class, long.class, Instant.class);
        assertThat(jpql)
            .as("activity-feed contract: row must surface in /api/activities/recent on execution, "
                + "which requires updated_at to advance")
            .contains("a.lastExecutionAt = :now")
            .contains("a.updatedAt = :now");
    }

    private String jpqlOf(String methodName, Class<?>... paramTypes) throws NoSuchMethodException {
        Method m = AgentExecutionRepository.class.getMethod(methodName, paramTypes);
        Query q = m.getAnnotation(Query.class);
        assertThat(q).as("expected @Query on %s", methodName).isNotNull();
        return q.value();
    }
}
