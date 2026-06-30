package com.apimarketplace.agent.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guardrail unit test for {@link AgentExecutionRepository#findRunningByAgentEntityIdSince}
 * - the query backing the {@code agent:activity} WebSocket snapshot (late-subscribe replay).
 *
 * <p>The controller test mocks the repository, so this JPQL is never parsed against
 * Hibernate at test time - a field-name or status-literal typo would pass every test and
 * only blow up at application bootstrap (EntityManagerFactory init). Reflection-based JPQL
 * inspection (mirror of {@link AgentExecutionRepositoryIncrementCountersQueryTest}) cheaply
 * pins the three load-bearing clauses: the agent filter, the RUNNING status, and the
 * recency cutoff that excludes crashed-pod leftovers.
 */
@DisplayName("AgentExecutionRepository.findRunningByAgentEntityIdSince - agent + RUNNING + recency cutoff")
class AgentExecutionRepositoryRunningSnapshotQueryTest {

    @Test
    @DisplayName("filters by agentEntityId, status='RUNNING', and startedAt > :cutoff")
    void filtersRunningWithRecencyCutoff() throws NoSuchMethodException {
        String jpql = jpqlOf("findRunningByAgentEntityIdSince", UUID.class, Instant.class);
        assertThat(jpql)
            .as("snapshot must re-emit only this agent's RUNNING, recent executions")
            .contains("e.agentEntityId = :agentEntityId")
            .contains("e.status = 'RUNNING'")
            .contains("e.startedAt > :cutoff");
    }

    private String jpqlOf(String methodName, Class<?>... paramTypes) throws NoSuchMethodException {
        Method m = AgentExecutionRepository.class.getMethod(methodName, paramTypes);
        Query q = m.getAnnotation(Query.class);
        assertThat(q).as("expected @Query on %s", methodName).isNotNull();
        return q.value();
    }
}
