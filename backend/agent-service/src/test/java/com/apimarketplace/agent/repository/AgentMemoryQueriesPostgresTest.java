package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.config.AgentDefaultsConfig;
import com.apimarketplace.agent.domain.AgentMemoryEntity;
import com.apimarketplace.agent.memory.MemoryLimitsConfig;
import com.apimarketplace.agent.memory.MemoryPromptSection;
import com.apimarketplace.agent.memory.MemoryService;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.memory.MemoryCrudModule;
import com.apimarketplace.agent.tools.memory.MemoryHelpModule;
import com.apimarketplace.agent.tools.memory.MemoryToolsProvider;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The memory queries, tool lifecycle and V479 migration against real Postgres.
 *
 * <p>The unit suites cover branching with mocks. This class also exercises the
 * real tool, service, JPA repository and renderer across committed calls: a mocked
 * repository returns whatever the test says, so it cannot tell whether the SQL
 * runs at all. Four things in this feature live only in the database and would
 * ship broken with a fully green mock suite: the generated {@code tsvector}
 * column, the {@code @@ plainto_tsquery} search with its
 * {@code CAST(:agentId AS uuid)} branch, the two PARTIAL unique indexes that make
 * {@code save} an upsert, and the {@code ON DELETE CASCADE} that takes an agent's
 * private memory with it.
 *
 * <p>The search SQL is not copied here. It is read off the {@code @Query}
 * annotation by reflection and executed verbatim through
 * {@link NamedParameterJdbcTemplate}, which binds the {@code :name} placeholders
 * the annotation already uses. A test that pasted the query would keep passing
 * after someone edited the repository, which is the failure this class exists to
 * prevent.
 *
 * <p><b>Where the database comes from, and why it is not only Testcontainers.</b>
 * With {@code AGENT_TEST_PG_URL} set it uses that scratch database; this is the
 * CI path, and it is the reason the class is not gated on a Docker socket the
 * build runners do not have. Without it, and with Docker present, it starts its
 * own container, which is the convenient local path. With neither, on CI, it
 * FAILS rather than skipping: a class built to be the only executor of this SQL
 * is worthless if dropping an env block silently turns it green.
 */
@DisplayName("agent.agent_memories - real Postgres")
class AgentMemoryQueriesPostgresTest {

    private static final String ENV_URL = System.getenv("AGENT_TEST_PG_URL");
    private static final String ENV_USER = System.getenv().getOrDefault("AGENT_TEST_PG_USER", "postgres");
    private static final String ENV_PASSWORD = System.getenv().getOrDefault("AGENT_TEST_PG_PASSWORD", "postgres");

    @SuppressWarnings("resource")
    private static PostgreSQLContainer<?> container;

    /** How we actually connected, whichever source won. */
    private static String jdbcUrl;
    private static String jdbcUser;
    private static String jdbcPassword;

    private static final String ORG = "org-alpha";
    private static final String OTHER_ORG = "org-beta";

    static JdbcTemplate jdbc;
    static NamedParameterJdbcTemplate named;

    private UUID agentId;
    private UUID siblingAgentId;

    @BeforeAll
    static void setUpClass() {
        DataSource ds = openDatabase();
        String migration = loadMigration();
        // A hard failure, not a skip. The migration is checked in, so "not found"
        // means this test is misconfigured, and a misconfigured test that skips reads
        // as coverage: the whole class would go green while executing nothing. The
        // only legitimate skip here is the absence of Docker, which the class-level
        // annotation already handles and which is visible in the run output.
        if (migration == null) {
            throw new AssertionError(
                "V479__agent_long_term_memory.sql was not found from " + Path.of(".").toAbsolutePath()
                + " - the migration moved, or this test is being run from an unexpected directory. "
                + "Skipping here would hide every query this class exists to execute.");
        }

        jdbc = new JdbcTemplate(ds);
        named = new NamedParameterJdbcTemplate(ds);

        jdbc.execute("CREATE SCHEMA IF NOT EXISTS agent");
        // The FK target. Only the column V479 references, so the fixture cannot
        // accidentally depend on anything else about agents.
        jdbc.execute("CREATE TABLE IF NOT EXISTS agent.agents (id UUID PRIMARY KEY)");
        // Dropped first: the scratch database is SHARED with the other Postgres
        // tests in this module, so a table left by an earlier revision of this
        // migration would otherwise survive the idempotent CREATE and be tested
        // in place of the one that ships.
        jdbc.execute("DROP TABLE IF EXISTS agent.agent_memories");
        jdbc.execute(migration);
    }

    /**
     * The scratch database named by the environment, or a container, or a failure.
     *
     * <p>Order matters: the env URL wins, because that is what CI provides and CI
     * is where this must not be skippable. The container is the local convenience.
     */
    private static DataSource openDatabase() {
        if (ENV_URL != null && !ENV_URL.isBlank()) {
            String database = ENV_URL.substring(ENV_URL.lastIndexOf('/') + 1).split("\\?")[0];
            if (!database.toLowerCase(java.util.Locale.ROOT).contains("test")) {
                throw new IllegalStateException(
                    "AGENT_TEST_PG_URL must point at a scratch database whose name contains 'test' "
                    + "(this test drops agent.agent_memories), got: " + database);
            }
            jdbcUrl = ENV_URL;
            jdbcUser = ENV_USER;
            jdbcPassword = ENV_PASSWORD;
            return new DriverManagerDataSource(jdbcUrl, jdbcUser, jdbcPassword);
        }

        if (DockerClientFactory.instance().isDockerAvailable()) {
            container = new PostgreSQLContainer<>("postgres:16-alpine");
            container.start();
            jdbcUrl = container.getJdbcUrl();
            jdbcUser = container.getUsername();
            jdbcPassword = container.getPassword();
            return new DriverManagerDataSource(jdbcUrl, jdbcUser, jdbcPassword);
        }

        boolean onCi = System.getenv("CI") != null && !System.getenv("CI").isBlank();
        if (onCi) {
            throw new IllegalStateException(
                "AGENT_TEST_PG_URL is unset on CI and no Docker socket is available. This class "
                + "must execute there: it is the only test that runs the memory search SQL, the "
                + "generated tsvector, the two partial unique indexes and the FK cascade against "
                + "a real engine, and every other memory test mocks the repository. Restore the "
                + "env block on the workflow step that runs it, and keep that step in a job "
                + "carrying the postgres service.");
        }
        throw new org.opentest4j.TestAbortedException(
            "no AGENT_TEST_PG_URL and no Docker: set one to run the memory SQL against Postgres");
    }

    @org.junit.jupiter.api.AfterAll
    static void stopContainer() {
        if (container != null) {
            container.stop();
            container = null;
        }
    }

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE agent.agent_memories");
        // Fresh ids per test rather than clearing agent.agents: on CI this runs
        // against a database shared with the other Postgres tests in this module,
        // and deleting every row of a table another class owns is how one test
        // starts failing because of a change in an unrelated one.
        agentId = UUID.randomUUID();
        siblingAgentId = UUID.randomUUID();
        jdbc.update("INSERT INTO agent.agents (id) VALUES (?)", agentId);
        jdbc.update("INSERT INTO agent.agents (id) VALUES (?)", siblingAgentId);
    }

    // ------------------------------------------------------------------ helpers

    private static String loadMigration() {
        for (String prefix : List.of("../", "")) {
            Path p = Path.of(prefix + "migration-service/src/main/resources/db/migration/"
                + "V479__agent_long_term_memory.sql");
            if (Files.exists(p)) {
                try {
                    return Files.readString(p, StandardCharsets.UTF_8);
                } catch (Exception unreadable) {
                    return null;
                }
            }
        }
        return null;
    }

    /** The SQL Spring Data will actually send, read off the annotation rather than retyped. */
    private static String sqlOf(String methodName, Class<?>... paramTypes) {
        try {
            Method method = AgentMemoryRepository.class.getMethod(methodName, paramTypes);
            Query query = method.getAnnotation(Query.class);
            assertThat(query).as("%s has no @Query to execute", methodName).isNotNull();
            assertThat(query.nativeQuery()).as("%s must be native for this test to run its SQL", methodName).isTrue();
            return query.value();
        } catch (NoSuchMethodException e) {
            throw new AssertionError("signature changed: " + methodName, e);
        }
    }

    private UUID insert(String slug, UUID scopeAgentId, String title, String summary,
                        String content, boolean active) {
        return insert(ORG, slug, scopeAgentId, title, summary, content, active);
    }

    private UUID insert(String orgId, String slug, UUID scopeAgentId, String title, String summary,
                        String content, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO agent.agent_memories
                (id, tenant_id, organization_id, agent_id, type, slug, title, summary, content, is_active)
            VALUES (?, 'user-1', ?, ?, 'PROJECT', ?, ?, ?, ?, ?)
            """, id, orgId, scopeAgentId, slug, title, summary, content, active);
        return id;
    }

    private List<String> search(String query, UUID callerAgentId) {
        Map<String, Object> params = new HashMap<>();
        params.put("orgId", ORG);
        params.put("agentId", callerAgentId == null ? null : callerAgentId.toString());
        params.put("query", query);
        params.put("maxResults", 50);
        return named.queryForList(
            sqlOf("searchStrict", String.class, String.class, String.class, int.class),
            new MapSqlParameterSource(params)).stream()
            .map(row -> (String) row.get("slug"))
            .toList();
    }

    // -------------------------------------------------------------------- tests

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("memory tool lifecycle with real persistence and prompt rendering")
    class ToolLifecycle {
        private SessionFactory sessions;

        @BeforeAll
        void openPersistence() {
            // Use the shipped migration above, never Hibernate-created tables.
            sessions = new Configuration()
                .addAnnotatedClass(AgentMemoryEntity.class)
                .setProperty("hibernate.connection.url", jdbcUrl)
                .setProperty("hibernate.connection.username", jdbcUser)
                .setProperty("hibernate.connection.password", jdbcPassword)
                .setProperty("hibernate.hbm2ddl.auto", "none")
                .buildSessionFactory();
        }

        @AfterAll
        void closePersistence() {
            if (sessions != null) sessions.close();
        }

        private <T> T inSession(Function<MemoryService, T> operation) {
            try (var session = sessions.openSession()) {
                var tx = session.beginTransaction();
                try {
                    var repository = new JpaRepositoryFactory(session).getRepository(AgentMemoryRepository.class);
                    T result = operation.apply(new MemoryService(repository, new MemoryLimitsConfig()));
                    tx.commit();
                    return result;
                } catch (RuntimeException | Error failure) {
                    tx.rollback();
                    throw failure;
                }
            }
        }

        private ToolExecutionResult call(String org, UUID caller, String access, Map<String, Object> parameters) {
            // Each call gets a new persistence context: later reads cannot pass by
            // observing the same Java entity that save just changed.
            return inSession(service -> {
                var limits = new MemoryLimitsConfig();
                var renderer = new MemoryPromptSection(service, limits);
                var provider = new MemoryToolsProvider(
                    new MemoryCrudModule(service, renderer, null, new AgentDefaultsConfig()),
                    new MemoryHelpModule(limits), limits);
                Map<String, Object> credentials = new HashMap<>();
                if (caller != null) credentials.put("__agentId__", caller.toString());
                credentials.put("__memoryAccessMode__", access);
                return provider.execute("memory", parameters,
                    new ToolExecutionContext("user-1", credentials, Map.of(), Set.of(), null, null, org, "MEMBER"));
            });
        }

        private Map<?, ?> ok(ToolExecutionResult result) {
            assertThat(result.success()).as("tool result: %s", result.error()).isTrue();
            return (Map<?, ?>) result.data();
        }

        private String prompt(String org, UUID caller) {
            return inSession(service -> new MemoryPromptSection(service, new MemoryLimitsConfig())
                .appendTo("Base instructions", org, caller));
        }

        @Test
        @DisplayName("a saved preference is recalled and corrected in later runs without retaining obsolete details")
        void savesRecallsAndCorrectsAcrossRuns() {
            String frozenPrompt = prompt(ORG, agentId);
            Map<?, ?> saved = ok(call(ORG, agentId, "write", Map.of(
                "action", "save", "slug", "sam-answer-length", "title", "Sam answer length", "type", "user",
                "summary", "Sam prefers short answers.", "content", "Sam prefers a maximum of five lines.")));

            assertThat(saved.get("status")).isEqualTo("CREATED");
            assertThat(frozenPrompt).doesNotContain("Sam prefers");
            assertThat(prompt(ORG, agentId)).contains("Sam prefers short answers.").doesNotContain("five lines");
            assertThat(ok(call(ORG, agentId, "read", Map.of("action", "list", "as_index", true))).get("index"))
                .isEqualTo(prompt(ORG, agentId).substring("Base instructions\n\n".length()));
            assertThat(ok(call(ORG, agentId, "read", Map.of("action", "search", "query", "five"))).get("count"))
                .isEqualTo(1);
            Map<?, ?> recalled = ok(call(ORG, agentId, "read", Map.of("action", "get", "slug", "sam-answer-length")));
            assertThat(recalled.get("content")).isEqualTo("Sam prefers a maximum of five lines.");

            Map<?, ?> corrected = ok(call(ORG, agentId, "write", Map.of(
                "action", "save", "slug", "sam-answer-length", "scope", recalled.get("scope"),
                "title", "Sam answer length", "summary", "Sam prefers detailed explanations.", "content", "")));
            assertThat(corrected.get("status")).isEqualTo("REPLACED");
            assertThat(corrected.get("id")).isEqualTo(saved.get("id"));
            assertThat(prompt(ORG, agentId)).contains("Sam prefers detailed explanations.").doesNotContain("short answers");
            assertThat(ok(call(ORG, agentId, "read", Map.of("action", "search", "query", "five"))).get("count"))
                .isEqualTo(0);
        }

        @Test
        @DisplayName("agent memory survives corrections without becoming shared or crossing a workspace")
        void agentScopeRemainsIsolatedAcrossRuns() {
            ok(call(ORG, agentId, "write", Map.of("action", "save", "scope", "agent", "slug", "atlas-context",
                "title", "Atlas context", "summary", "Atlas uses sentence case.", "pinned", true,
                "content", "Atlas labels have no terminal punctuation.")));

            assertThat(prompt(ORG, agentId)).contains("Atlas labels have no terminal punctuation.");
            assertThat(prompt(ORG, siblingAgentId)).doesNotContain("Atlas");
            assertThat(prompt(OTHER_ORG, agentId)).doesNotContain("Atlas");
            assertThat(call(ORG, siblingAgentId, "read", Map.of("action", "get", "slug", "atlas-context")).success()).isFalse();
            Map<?, ?> recalled = ok(call(ORG, agentId, "read", Map.of("action", "get", "slug", "atlas-context")));
            ok(call(ORG, agentId, "write", Map.of("action", "save", "scope", recalled.get("scope"),
                "slug", "atlas-context", "title", "Atlas context", "summary", "Atlas uses title case.",
                "content", "Atlas labels use title case.")));

            assertThat(prompt(ORG, agentId)).contains("Atlas labels use title case.").doesNotContain("sentence case");
            assertThat(prompt(ORG, null)).doesNotContain("Atlas");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent.agent_memories", Integer.class)).isEqualTo(1);
        }

        @Test
        @DisplayName("read-only writes are refused and disabled or deleted facts disappear from later prompts")
        void respectsAccessDeactivationAndDeletion() {
            Map<?, ?> saved = ok(call(ORG, agentId, "write", Map.of("action", "save", "slug", "atlas-team",
                "title", "Atlas team", "summary", "Atlas is a distributed team.")));
            UUID id = UUID.fromString(saved.get("id").toString());
            assertThat(call(ORG, agentId, "read", Map.of("action", "save", "slug", "atlas-team",
                "title", "Atlas team", "summary", "Atlas is an office team.")).success()).isFalse();
            assertThat(prompt(ORG, agentId)).contains("distributed team");

            jdbc.update("UPDATE agent.agent_memories SET is_active = false WHERE id = ?", id);
            assertThat(prompt(ORG, agentId)).doesNotContain("Atlas");
            assertThat(call(ORG, agentId, "read", Map.of("action", "get", "slug", "atlas-team")).success()).isFalse();
            assertThat(ok(call(ORG, agentId, "write", Map.of("action", "save", "slug", "atlas-team",
                "title", "Atlas team", "summary", "Atlas is an office team."))).get("is_active")).isEqualTo(false);
            assertThat(prompt(ORG, agentId)).doesNotContain("Atlas");

            jdbc.update("UPDATE agent.agent_memories SET is_active = true WHERE id = ?", id);
            ok(call(ORG, agentId, "write", Map.of("action", "delete", "slug", "atlas-team")));
            assertThat(prompt(ORG, agentId)).doesNotContain("Atlas");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM agent.agent_memories", Integer.class)).isZero();
        }
    }

    @Nested
    @DisplayName("full-text search")
    class Search {

        @Test
        @DisplayName("finds an entry by a word that appears only in its body, proving the generated column is populated")
        void findsAWordOnlyInTheBody() {
            insert("deploy-cadence", null, "Deploy cadence", "How we ship",
                "The team ships on Thursdays from the harbour branch.", true);
            insert("unrelated", null, "Unrelated", "Nothing to do with it", "Lorem ipsum.", true);

            // 'harbour' is in neither title nor summary. If the STORED tsvector were
            // not maintained, or were built over the wrong columns, this returns
            // nothing while every mock-based search test still passes.
            assertThat(search("harbour", null)).containsExactly("deploy-cadence");
        }

        @Test
        @DisplayName("returns nothing rather than raising when the query is punctuation a person typed")
        void punctuationDoesNotRaise() {
            insert("deploy-cadence", null, "Deploy cadence", "How we ship", "Thursdays.", true);

            // plainto_tsquery tolerates this; to_tsquery raises a syntax error on it.
            // The distinction is invisible without a database.
            assertThat(search("what's the ??? (cadence) & !", null)).isEmpty();
        }

        @Test
        @DisplayName("a null agent id matches workspace rows and never an agent's private ones")
        void nullAgentIdCollapsesToWorkspaceRows() {
            insert("shared", null, "Shared", "Everyone sees this", "harbour", true);
            insert("private", agentId, "Private", "Only one agent", "harbour", true);

            // The CAST(:agentId AS uuid) branch with a NULL bind. Postgres has to be
            // handed a typed null here; an untyped one is a "could not determine data
            // type" error at execution, never at compile time.
            assertThat(search("harbour", null)).containsExactly("shared");
        }

        @Test
        @DisplayName("an agent sees the workspace rows plus its own, and not a sibling's")
        void agentSeesOwnAndWorkspaceOnly() {
            insert("shared", null, "Shared", "Everyone", "harbour", true);
            insert("mine", agentId, "Mine", "Just me", "harbour", true);
            insert("theirs", siblingAgentId, "Theirs", "Another agent", "harbour", true);

            assertThat(search("harbour", agentId)).containsExactlyInAnyOrder("shared", "mine");
        }

        @Test
        @DisplayName("excludes a deactivated entry, so switching one off really stops the agent finding it")
        void excludesDeactivated() {
            insert("live", null, "Live", "On", "harbour", true);
            insert("switched-off", null, "Switched off", "Off", "harbour", false);

            assertThat(search("harbour", null)).containsExactly("live");
        }

        @Test
        @DisplayName("never crosses into another workspace, whatever the query matches")
        void neverCrossesWorkspaces() {
            insert(OTHER_ORG, "elsewhere", null, "Elsewhere", "Another workspace", "harbour", true);

            assertThat(search("harbour", null)).isEmpty();
        }

        @Test
        @DisplayName("the person's search DOES return a deactivated entry and a private one, or they could not fix either")
        void workspaceSearchKeepsWhatTheAgentSearchDrops() {
            insert("switched-off", null, "Switched off", "Off", "harbour", false);
            insert("private", agentId, "Private", "One agent only", "harbour", true);

            List<String> hits = named.queryForList(
                sqlOf("searchWorkspaceStrict", String.class, String.class, int.class),
                new MapSqlParameterSource(Map.of("orgId", ORG, "query", "harbour", "maxResults", 50)))
                .stream().map(row -> (String) row.get("slug")).toList();

            assertThat(hits).containsExactlyInAnyOrder("switched-off", "private");
        }
    }

    @Nested
    @DisplayName("the two partial unique indexes")
    class SlugUniqueness {

        @Test
        @DisplayName("refuses a second workspace entry with the same slug, which is what makes save an upsert")
        void workspaceSlugIsUnique() {
            insert("deploy-cadence", null, "First", "s", "", true);

            assertThatThrownBy(() -> insert("deploy-cadence", null, "Second", "s", "", true))
                .hasMessageContaining("uq_agent_memories_workspace_slug");
        }

        @Test
        @DisplayName("refuses a second entry with the same slug for the SAME agent")
        void agentSlugIsUniquePerAgent() {
            insert("my-note", agentId, "First", "s", "", true);

            assertThatThrownBy(() -> insert("my-note", agentId, "Second", "s", "", true))
                .hasMessageContaining("uq_agent_memories_agent_slug");
        }

        @Test
        @DisplayName("lets two DIFFERENT agents, and the workspace, each hold the same slug")
        void theScopesAreDisjoint() {
            insert("cadence", null, "Workspace", "s", "", true);
            insert("cadence", agentId, "Agent one", "s", "", true);
            insert("cadence", siblingAgentId, "Agent two", "s", "", true);

            // A single UNIQUE(organization_id, agent_id, slug) would allow all three
            // too, but would ALSO allow a second workspace row: NULL is distinct from
            // NULL in a unique index. That is the case the partial index exists for and
            // the one asserted above.
            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent.agent_memories WHERE slug = 'cadence'", Integer.class))
                .isEqualTo(3);
        }

        @Test
        @DisplayName("the same slug in another workspace is a different entry, not a conflict")
        void slugsAreScopedToTheWorkspace() {
            insert("cadence", null, "Ours", "s", "", true);
            insert(OTHER_ORG, "cadence", null, "Theirs", "s", "", true);

            assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent.agent_memories WHERE slug = 'cadence'", Integer.class))
                .isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("recording a recall")
    class Recall {

        @Test
        @DisplayName("counts the read without moving updated_at, so recency still means edited")
        void aRecallDoesNotLookLikeAnEdit() {
            UUID id = insert("deploy-cadence", null, "Deploy cadence", "s", "", true);
            java.sql.Timestamp before = jdbc.queryForObject(
                "SELECT updated_at FROM agent.agent_memories WHERE id = ?", java.sql.Timestamp.class, id);

            // The shape of the shipped bulk UPDATE: only these two columns. It is a
            // @Modifying query rather than a load-mutate-save precisely so the entity
            // lifecycle does not run and @PreUpdate never stamps updated_at.
            jdbc.update("""
                UPDATE agent.agent_memories
                SET recall_count = recall_count + 1, last_recalled_at = NOW()
                WHERE id = ?
                """, id);

            Map<String, Object> after = jdbc.queryForMap(
                "SELECT recall_count, last_recalled_at, updated_at FROM agent.agent_memories WHERE id = ?", id);

            assertThat(after.get("recall_count")).isEqualTo(1);
            assertThat(after.get("last_recalled_at")).isNotNull();
            // The property the whole read path rests on. updated_at orders the index
            // and is what a person reads as "last changed"; if a mere recall moved it,
            // every agent that opened an entry would reorder the block and the tab
            // would report edits nobody made.
            assertThat(after.get("updated_at"))
                .as("a read must not look like a write")
                .isEqualTo(before);
        }

        @Test
        @DisplayName("has no database trigger that would stamp updated_at behind the query's back")
        void nothingInTheSchemaBumpsUpdatedAt() {
            // The test above proves today's schema behaves; this one pins WHY, and
            // fails the day somebody adds a moddatetime trigger for the convenience of
            // some other write path. A trigger would defeat the @Modifying query
            // silently, from outside the Java the other test reads.
            assertThat(jdbc.queryForList("""
                SELECT tgname FROM pg_trigger t
                JOIN pg_class c ON c.oid = t.tgrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'agent' AND c.relname = 'agent_memories' AND NOT t.tgisinternal
                """, String.class))
                .as("a trigger here would move updated_at on every recall")
                .isEmpty();
        }
    }

    @Nested
    @DisplayName("agent deletion")
    class Cascade {

        @Test
        @DisplayName("takes that agent's private memory with it and leaves the workspace's alone")
        void privateMemoryDiesWithItsAgent() {
            insert("shared", null, "Shared", "s", "", true);
            insert("mine", agentId, "Mine", "s", "", true);
            insert("theirs", siblingAgentId, "Theirs", "s", "", true);

            jdbc.update("DELETE FROM agent.agents WHERE id = ?", agentId);

            // SET NULL would have widened 'mine' to the whole workspace instead, which
            // is the silent audience change the column exists to prevent.
            assertThat(jdbc.queryForList("SELECT slug FROM agent.agent_memories", String.class))
                .containsExactlyInAnyOrder("shared", "theirs");
        }
    }

    @Nested
    @DisplayName("the type and source check constraints")
    class Constraints {

        @Test
        @DisplayName("refuse a value outside the four types, so a bad enum name cannot be stored")
        void typeIsConstrained() {
            assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO agent.agent_memories
                    (id, tenant_id, organization_id, type, slug, title, summary)
                VALUES (?, 'u', ?, 'HABIT', 'x', 't', 's')
                """, UUID.randomUUID(), ORG))
                .hasMessageContaining("ck_agent_memories_type");
        }

        @Test
        @DisplayName("refuse a source that is neither AGENT nor USER, which is what the badge relies on")
        void sourceIsConstrained() {
            assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO agent.agent_memories
                    (id, tenant_id, organization_id, type, slug, title, summary, source)
                VALUES (?, 'u', ?, 'PROJECT', 'x', 't', 's', 'SYSTEM')
                """, UUID.randomUUID(), ORG))
                .hasMessageContaining("ck_agent_memories_source");
        }
    }
    @Nested
    @DisplayName("the storage-usage rollup query")
    class StorageUsage {

        @Test
        @DisplayName("runs the SERVICE's own SQL, and counts the three text columns for the right tenant only")
        void countsThisTenantsText() {
            // The real service against the real table. Its unit test mocks the row and
            // routes on sql.contains("agent_memories"), so a wrong column name, a
            // missing schema qualifier or a tenant predicate that never matches all
            // pass there - and then fail nightly in production in silence, because the
            // reconciliation swallows its own errors and reports zero.
            //
            // Pasting the query here instead would have the same blind spot as the
            // mock: it would test this file's copy, not the service's.
            jdbc.update("""
                INSERT INTO agent.agent_memories
                    (id, tenant_id, organization_id, type, slug, title, summary, content)
                VALUES (?, ?, ?, 'PROJECT', 'a', 'abc', 'de', 'fghi')
                """, UUID.randomUUID(), "tenant-a", ORG);
            jdbc.update("""
                INSERT INTO agent.agent_memories
                    (id, tenant_id, organization_id, type, slug, title, summary, content)
                VALUES (?, ?, ?, 'PROJECT', 'b', 'zzzz', 'zz', 'zz')
                """, UUID.randomUUID(), "tenant-b", ORG);

            // The service's SQL is unqualified and relies on the connection's
            // search_path, exactly as it does in the running application. It goes on the
            // URL, not through a SET: this DataSource opens a fresh connection per
            // operation, so a session-level SET would be gone by the next statement -
            // which is why the first attempt failed with "bad SQL grammar" and looked
            // like a broken query.
            javax.sql.DataSource agentSchema = new DriverManagerDataSource(
                jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=agent",
                jdbcUser, jdbcPassword);
            var usage = new com.apimarketplace.agent.service.AgentStorageUsageService(
                new JdbcTemplate(agentSchema)).getStorageUsage("tenant-a");

            // 3 (title) + 2 (summary) + 4 (content) for tenant-a, and nothing from the
            // other tenant's row.
            assertThat(usage).containsKey("MEMORIES");
            assertThat(usage.get("MEMORIES").usedBytes()).isEqualTo(9L);
            assertThat(usage.get("MEMORIES").itemCount()).isEqualTo(1);
        }
    }
}
