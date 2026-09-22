package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.client.dto.AgentRunFireDto;
import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.AgentExecutionEntity;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.jpa.repository.Query;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The agenda's agent-history JPQL, executed by a real Hibernate against a real Postgres.
 *
 * <p><b>Why this class exists.</b> agent-service has no Spring test slice, so every other
 * test of this query either mocks the repository or inspects the {@code @Query} string.
 * Neither can tell whether the query PARSES, whether the constructor expression matches
 * {@link AgentRunFireDto}, or whether it selects the right rows. A mistake in any of
 * those three fails at application startup in production, not in CI - and the one that
 * does NOT fail loudly, a wrong row set, would quietly put other people's runs on a
 * calendar or leave a workspace's history looking empty.
 *
 * <p>Three properties in particular are invisible to a string assertion and are pinned
 * here by real rows: the INNER join dropping executions whose agent was deleted (or which
 * never had one), the agent-type allow-list being compared lower-case against a column
 * six services write in three different cases, and the ordering the caller relies on to
 * read "the oldest row I reached" off the end of the list.
 *
 * <p>The JPQL is read off the shipped annotation by reflection, so this cannot drift into
 * testing a copy. It runs against the scratch database named by {@code AGENT_TEST_PG_URL}
 * (already provisioned on the CI steps that carry the postgres service); with {@code CI}
 * set and no URL it FAILS rather than skipping, so the coverage cannot be lost by quietly
 * dropping an env block.
 *
 * <p><b>It builds its tables in a schema of its OWN</b>, not in {@code agent}. The
 * scratch database is shared by every Postgres test in the {@code backend-targeted} job,
 * and two of them also live in the {@code agent} schema. The first version of this class
 * created {@code agent.agents} with {@code tenant_id}/{@code name} NOT NULL, and
 * {@code AgentMemoryQueriesPostgresTest} - which runs later in the same job and does
 * {@code CREATE TABLE IF NOT EXISTS agent.agents (id UUID PRIMARY KEY)} followed by an
 * insert of the id alone - would have hit a not-null violation and failed an entire
 * unrelated suite. Hibernate is pointed at this schema instead, so the entities' own
 * table names resolve here and nothing outside it is touched.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("agenda agent-run window - the shipped JPQL, real Hibernate, real Postgres")
class AgentRunWindowQueryPostgresTest {

    private static final String URL = System.getenv("AGENT_TEST_PG_URL");
    private static final String USER = System.getenv().getOrDefault("AGENT_TEST_PG_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("AGENT_TEST_PG_PASSWORD", "postgres");

    /**
     * This class's private schema. Named after the test so a stray table found later is
     * traceable, and dropped whole in {@link #tearDown()}.
     */
    private static final String SCHEMA = "agenda_window_probe";

    private static final String ORG = "org-under-test";
    private static final String OTHER_ORG = "org-next-door";
    private static final String TENANT = "tenant-1";

    private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");
    private static final Instant FROM = NOW.minus(7, ChronoUnit.DAYS);
    private static final Instant TO = NOW;

    private static final UUID VISIBLE_AGENT = UUID.randomUUID();
    private static final UUID SECOND_AGENT = UUID.randomUUID();

    private SessionFactory sessionFactory;
    private String jpql;

    @BeforeAll
    void setUp() throws Exception {
        requireDatabaseOnCi();
        String database = URL.substring(URL.lastIndexOf('/') + 1).split("\\?")[0];
        if (!database.toLowerCase(Locale.ROOT).contains("test")) {
            throw new IllegalStateException(
                    "AGENT_TEST_PG_URL must point at a scratch database whose name contains 'test' "
                            + "(this test creates and drops a schema of its own in it), got: "
                            + database);
        }
        jpql = shippedJpql();
        awaitDatabase();
        createSchema();
        // Hibernate builds the query from the ENTITIES, so a renamed field or a
        // constructor-expression mismatch fails right here, which is the point.
        sessionFactory = new Configuration()
                .addAnnotatedClass(AgentExecutionEntity.class)
                .addAnnotatedClass(AgentEntity.class)
                .setProperty("hibernate.connection.url", URL)
                .setProperty("hibernate.connection.username", USER)
                .setProperty("hibernate.connection.password", PASSWORD)
                .setProperty("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect")
                .setProperty("hibernate.default_schema", SCHEMA)
                .setProperty("hibernate.hbm2ddl.auto", "none")
                .buildSessionFactory();
        seed();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (sessionFactory != null) sessionFactory.close();
        // Leave the shared database as it was found.
        if (URL != null && !URL.isBlank()) {
            try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
                 Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            }
        }
    }

    private List<AgentRunFireDto> run(Instant from, Instant to, List<String> types, int limit) {
        try (EntityManager em = sessionFactory.createEntityManager()) {
            return em.createQuery(jpql, AgentRunFireDto.class)
                    .setParameter("orgId", ORG)
                    .setParameter("from", from)
                    .setParameter("to", to)
                    .setParameter("agentTypes", types)
                    .setMaxResults(limit)
                    .getResultList();
        }
    }

    private static final List<String> SHIPPED_TYPES = List.of("agent", "sub_agent");

    @Test
    @DisplayName("parses, binds the record, and returns the workspace's runs newest first")
    void parsesAndReturnsTheRightRows() {
        List<AgentRunFireDto> runs = run(FROM, TO, SHIPPED_TYPES, 50);

        // Two eligible rows only: see seed() for the six that must not appear.
        assertThat(runs).extracting(AgentRunFireDto::agentName)
                .containsExactly("Support agent", "Research agent");
        // Newest first is a CONTRACT, not a nicety: the caller reads the oldest row it
        // reached off the END of this list to say where its coverage starts.
        assertThat(runs.get(0).startedAt()).isAfter(runs.get(1).startedAt());

        AgentRunFireDto newest = runs.get(0);
        assertThat(newest.agentId()).isEqualTo(VISIBLE_AGENT);
        assertThat(newest.status()).isEqualTo("COMPLETED");
        assertThat(newest.source()).isEqualTo("CHAT");
        assertThat(newest.conversationId()).isEqualTo("conv-1");
        assertThat(newest.endedAt()).isNotNull();
        assertThat(newest.executionId()).isNotNull();
    }

    @Test
    @DisplayName("drops an execution whose agent is gone: it could be neither named nor opened")
    void innerJoinDropsOrphans() {
        // The seed holds a row of an ALLOWED type with agent_entity_id NULL, which is
        // what an execution becomes once its agent is deleted. It must be the allowed
        // type or the type filter removes it first and this assertion proves nothing:
        // verified by mutating JOIN to LEFT JOIN, which makes this test fail.
        assertThat(run(FROM, TO, SHIPPED_TYPES, 50))
                .allSatisfy(row -> assertThat(row.agentId()).isNotNull());
    }

    @Test
    @DisplayName("matches the agent type case-insensitively, as the writers spell it")
    void matchesTypeCaseInsensitively() {
        // The seed stores 'SUB_AGENT' upper-case, which is how SubAgentExecutionHandler
        // writes it, while the allow-list is lower-case. Drop the LOWER() and this row
        // silently disappears from every calendar.
        assertThat(run(FROM, TO, SHIPPED_TYPES, 50))
                .extracting(AgentRunFireDto::source)
                .contains("SUB_AGENT");
    }

    @Test
    @DisplayName("excludes the internal agent types, whatever their case")
    void excludesInternalTypes() {
        assertThat(run(FROM, TO, SHIPPED_TYPES, 50))
                .extracting(AgentRunFireDto::agentName)
                .doesNotContain("Compaction", "Classifier");
    }

    @Test
    @DisplayName("stays inside the window and inside the workspace")
    void scopesToWindowAndWorkspace() {
        // A row 30 days old and a row belonging to another org are both seeded.
        assertThat(run(FROM, TO, SHIPPED_TYPES, 50)).hasSize(2);
        assertThat(run(NOW.minus(60, ChronoUnit.DAYS), TO, SHIPPED_TYPES, 50)).hasSize(3);
    }

    @Test
    @DisplayName("honours the row cap, keeping the NEWEST rows")
    void capKeepsTheNewest() {
        List<AgentRunFireDto> capped = run(NOW.minus(60, ChronoUnit.DAYS), TO, SHIPPED_TYPES, 1);

        // "Keep the newest" is what makes the caller's coverage boundary meaningful: the
        // rows dropped are the far end of the window, and the last row returned is where
        // its history starts.
        assertThat(capped).hasSize(1);
        assertThat(capped.get(0).agentName()).isEqualTo("Support agent");
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private void seed() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement s = c.createStatement()) {
            s.execute("TRUNCATE " + SCHEMA + ".agent_executions, " + SCHEMA + ".agents");
            agent(s, VISIBLE_AGENT, "Support agent", ORG);
            agent(s, SECOND_AGENT, "Research agent", ORG);

            // Eligible.
            execution(s, VISIBLE_AGENT, "agent", "CHAT", "COMPLETED", NOW.minus(1, ChronoUnit.HOURS),
                    "conv-1", ORG);
            execution(s, SECOND_AGENT, "SUB_AGENT", "SUB_AGENT", "COMPLETED",
                    NOW.minus(2, ChronoUnit.HOURS), null, ORG);

            // Not eligible, one reason each.
            // An ORPHAN OF AN ALLOWED TYPE: this is the row that exercises the join, and
            // the first version of this fixture got it wrong by giving it type 'CLI',
            // which the type filter already removed. Mutating JOIN to LEFT JOIN then
            // changed nothing and the test passed - a guard that could not fail. It is
            // also the true shape of reality: agent_entity_id is SET NULL when an agent
            // is deleted, so its finished runs keep their type and lose their agent.
            execution(s, null, "agent", "CHAT", "COMPLETED", NOW.minus(3, ChronoUnit.HOURS),
                    null, ORG);
            execution(s, null, "CLI", "WORKFLOW", "COMPLETED", NOW.minus(6, ChronoUnit.HOURS),
                    null, ORG);                                      // excluded type AND no agent
            execution(s, VISIBLE_AGENT, "compaction_summary", "CHAT", "COMPLETED",
                    NOW.minus(4, ChronoUnit.HOURS), null, ORG);      // internal type
            execution(s, VISIBLE_AGENT, "classify", "WORKFLOW", "COMPLETED",
                    NOW.minus(5, ChronoUnit.HOURS), null, ORG);      // routing node
            execution(s, VISIBLE_AGENT, "agent", "CHAT", "COMPLETED",
                    NOW.minus(30, ChronoUnit.DAYS), null, ORG);      // outside the window
            agent(s, UUID.randomUUID(), "Neighbour", OTHER_ORG);
            execution(s, VISIBLE_AGENT, "agent", "CHAT", "COMPLETED",
                    NOW.minus(1, ChronoUnit.HOURS), null, OTHER_ORG); // another workspace
        }
    }

    private void agent(Statement s, UUID id, String name, String org) throws Exception {
        s.execute("INSERT INTO " + SCHEMA + ".agents (id, tenant_id, organization_id, name) "
                + "VALUES ('" + id + "', '" + TENANT + "', '" + org + "', '" + name + "')");
    }

    private void execution(Statement s, UUID agentId, String type, String source, String status,
                           Instant startedAt, String conversationId, String org) throws Exception {
        s.execute("INSERT INTO " + SCHEMA + ".agent_executions (id, tenant_id, organization_id, "
                + "agent_entity_id, agent_type, source, status, started_at, ended_at, "
                + "conversation_id, created_at) VALUES ('" + UUID.randomUUID() + "', '" + TENANT
                + "', '" + org + "', " + (agentId == null ? "NULL" : "'" + agentId + "'")
                + ", '" + type + "', '" + source + "', '" + status + "', '" + startedAt + "', '"
                + startedAt.plusSeconds(5) + "', "
                + (conversationId == null ? "NULL" : "'" + conversationId + "'")
                + ", '" + startedAt + "')");
    }

    private void createSchema() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, USER, PASSWORD);
             Statement s = c.createStatement()) {
            // A schema of this class's own: see the class javadoc for the suite this
            // used to break by sharing `agent` with two other Postgres tests.
            s.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
            s.execute("CREATE SCHEMA " + SCHEMA);
            // Only the columns the entities map for this query; Hibernate is told
            // hbm2ddl=none, so anything missing surfaces as a failure rather than being
            // invented.
            s.execute("""
                CREATE TABLE %s.agents (
                    id UUID PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    organization_id VARCHAR(255),
                    name VARCHAR(255) NOT NULL)
                """.formatted(SCHEMA));
            s.execute("""
                CREATE TABLE %s.agent_executions (
                    id UUID PRIMARY KEY,
                    tenant_id VARCHAR(255) NOT NULL,
                    organization_id VARCHAR(255),
                    agent_entity_id UUID,
                    agent_type VARCHAR(20) NOT NULL,
                    source VARCHAR(20) NOT NULL,
                    status VARCHAR(20) NOT NULL,
                    epoch INT NOT NULL DEFAULT 0,
                    spawn INT NOT NULL DEFAULT 0,
                    item_index INT NOT NULL DEFAULT 0,
                    iteration_count INT NOT NULL DEFAULT 0,
                    total_tool_calls INT NOT NULL DEFAULT 0,
                    successful_tool_calls INT NOT NULL DEFAULT 0,
                    failed_tool_calls INT NOT NULL DEFAULT 0,
                    message_count INT NOT NULL DEFAULT 0,
                    initial_history_size INT NOT NULL DEFAULT 0,
                    total_prompt_tokens INT NOT NULL DEFAULT 0,
                    total_completion_tokens INT NOT NULL DEFAULT 0,
                    total_tokens INT NOT NULL DEFAULT 0,
                    total_cache_creation_tokens INT NOT NULL DEFAULT 0,
                    total_cache_read_tokens INT NOT NULL DEFAULT 0,
                    total_cached_tokens INT NOT NULL DEFAULT 0,
                    total_reasoning_tokens INT NOT NULL DEFAULT 0,
                    loop_detected BOOLEAN NOT NULL DEFAULT FALSE,
                    credits_consumed NUMERIC(15,4) NOT NULL DEFAULT 0,
                    depth INT NOT NULL DEFAULT 0,
                    conversation_id VARCHAR(255),
                    started_at TIMESTAMPTZ NOT NULL,
                    ended_at TIMESTAMPTZ,
                    created_at TIMESTAMPTZ NOT NULL)
                """.formatted(SCHEMA));
        }
    }

    private static String shippedJpql() throws NoSuchMethodException {
        Query query = AgentExecutionRepository.class.getMethod("findWorkspaceRunsBetweenStrict",
                String.class, Instant.class, Instant.class, java.util.Collection.class,
                org.springframework.data.domain.Pageable.class).getAnnotation(Query.class);
        if (query == null || query.nativeQuery()) {
            throw new IllegalStateException(
                    "findWorkspaceRunsBetweenStrict no longer carries a JPQL @Query. If the agenda's "
                            + "agent-history query moved, move this test with it: it is the only place "
                            + "that query is parsed and executed at all.");
        }
        return query.value();
    }

    private static void requireDatabaseOnCi() {
        if (URL != null && !URL.isBlank()) return;
        boolean onCi = System.getenv("CI") != null && !System.getenv("CI").isBlank();
        if (onCi) {
            throw new IllegalStateException(
                    "AGENT_TEST_PG_URL is unset on CI. This class must execute there: agent-service "
                            + "has no Spring slice, so this is the only test that parses and runs the "
                            + "agenda's agent-history JPQL. Without it a bad query reaches production "
                            + "and fails at startup. Keep this class on a step carrying the postgres "
                            + "service and its env block.");
        }
        Assumptions.abort("no scratch Postgres: set AGENT_TEST_PG_URL to run this locally "
                + "(CI always sets it)");
    }

    private static void awaitDatabase() {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try (Connection ignored = DriverManager.getConnection(URL, USER, PASSWORD)) {
                return;
            } catch (Exception e) {
                last = new IllegalStateException("scratch Postgres not reachable at " + URL, e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
        }
        throw last;
    }
}
