package com.apimarketplace.agent.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guardrail for the agenda's agent-history query.
 *
 * <p>agent-service has no Spring test slice (see
 * {@link AgentExecutionRepositoryIncrementCountersQueryTest} for why), so this JPQL is
 * never parsed by Hibernate at test time and its callers all mock the repository.
 * Reflection-based inspection pins the clauses that are load-bearing and easy to break
 * silently.
 *
 * <p>Each assertion below protects a specific failure the reviewer would not see:
 * <ul>
 *   <li><b>The projection.</b> Selecting the entity instead would move the system prompt,
 *       the tool sequence and two JSONB snapshots of every run in a month across a
 *       service boundary to draw a row of chips. Nothing would fail; the page would just
 *       get slow and heavy as a workspace ages.</li>
 *   <li><b>The org equality and the {@code startedAt} range and ordering.</b> V210's
 *       {@code idx_agent_executions_org_started} covers exactly this shape. Ordering by
 *       {@code endedAt}, or filtering the org with anything but an equality, trades an
 *       index scan for a sort over the whole workspace.</li>
 *   <li><b>The INNER join to the agent.</b> {@code agent_entity_id} is SET NULL when an
 *       agent is deleted; a LEFT join would put unnamed chips on the calendar that lead
 *       nowhere.</li>
 *   <li><b>The agent-type ALLOW-list.</b> The same table records internal LLM calls
 *       (compaction / cold summaries) and the workflow-internal classify / guardrail
 *       nodes. A deny-list would put the next internal type on a user's calendar the day
 *       someone adds one.</li>
 * </ul>
 */
@DisplayName("AgentExecutionRepository.findWorkspaceRunsBetweenStrict - the agenda's agent history")
class AgentExecutionRepositoryWorkspaceRunsQueryTest {

    private static final String JPQL = jpql();

    @Test
    @DisplayName("projects into the wire DTO instead of selecting whole executions")
    void projectsRatherThanSelectingEntities() {
        assertThat(JPQL)
            .contains("SELECT new com.apimarketplace.agent.client.dto.AgentRunFireDto(")
            .doesNotContain("SELECT e FROM");
    }

    @Test
    @DisplayName("selects exactly the eight fields the record declares, in its order")
    void selectsTheRecordsFields() {
        assertThat(JPQL).contains(
            "e.id, e.agentEntityId, a.name, e.startedAt, e.endedAt, e.status, e.source, e.conversationId");
    }

    @Test
    @DisplayName("scopes by organization with an equality and bounds startedAt, so V210's index serves it")
    void isIndexShaped() {
        assertThat(JPQL)
            .contains("e.organizationId = :orgId")
            .contains("e.startedAt >= :from")
            .contains("e.startedAt <= :to")
            .contains("ORDER BY e.startedAt DESC");
    }

    @Test
    @DisplayName("joins the agent INNER, so a run whose agent was deleted is not reported")
    void joinsTheAgentInner() {
        assertThat(JPQL).contains("JOIN AgentEntity a ON a.id = e.agentEntityId");
        assertThat(JPQL).doesNotContain("LEFT JOIN");
    }

    @Test
    @DisplayName("filters agent types with an IN allow-list, lower-cased, never a NOT IN")
    void filtersWithAnAllowList() {
        assertThat(JPQL)
            .contains("LOWER(e.agentType) IN :agentTypes")
            .doesNotContain("NOT IN");
    }

    @Test
    @DisplayName("is bounded by a Pageable, not a Page: a count query would be a second pass")
    void isBoundedWithoutACount() throws NoSuchMethodException {
        Method m = method();
        assertThat(m.getReturnType()).isEqualTo(java.util.List.class);
        assertThat(m.getParameterTypes()[m.getParameterCount() - 1]).isEqualTo(Pageable.class);
    }

    private static Method method() throws NoSuchMethodException {
        return AgentExecutionRepository.class.getMethod("findWorkspaceRunsBetweenStrict",
            String.class, Instant.class, Instant.class, Collection.class, Pageable.class);
    }

    private static String jpql() {
        try {
            Query q = method().getAnnotation(Query.class);
            assertThat(q).as("expected @Query on findWorkspaceRunsBetweenStrict").isNotNull();
            return q.value();
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }
}
