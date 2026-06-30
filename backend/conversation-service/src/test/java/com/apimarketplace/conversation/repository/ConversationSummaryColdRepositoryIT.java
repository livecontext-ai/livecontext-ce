package com.apimarketplace.conversation.repository;

import com.apimarketplace.conversation.entity.Conversation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.Persistence;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration pins for the three {@code summary_cold} native queries against
 * a real PostgreSQL 16 testcontainer, exercising the REAL Spring Data
 * repository proxy (built via {@link JpaRepositoryFactory} over a bare
 * EntityManager - same pattern as {@code MessageSearchRepositoryIT}; the
 * {@code @DataJpaTest} slice is unusable here because the app's RedisConfig
 * collides with IntegrationTestConfig's bean overrides). The H2 integration
 * profile never parses these queries ({@code jsonb_array_length} /
 * {@code jsonb_set} / {@code jsonb_typeof} are PG-only), so without this
 * class the monotone-recall guard's WHERE clause has zero automated
 * coverage: a future edit flipping {@code <=} to {@code <} or dropping the
 * typeof CASE would pass the whole unit suite and break recall in production.
 *
 * <p>What this pins (regression: non-monotone / status-blind COLD recall):
 * <ul>
 *   <li>First write on a NULL column lands.</li>
 *   <li>Broader/equal coverage overwrites; smaller coverage is REJECTED
 *       (0 rows) and leaves the stored envelope intact - the exact
 *       post-ShedLock-TTL racing-writer scenario.</li>
 *   <li>A stale-flagged envelope accepts a smaller-coverage regeneration
 *       (convergence escape hatch) which lands as active again.</li>
 *   <li>{@code markSummaryColdStale} transitions exactly once (1 row, then
 *       0), works on legacy envelopes without a status key, preserves
 *       content, and no-ops on missing/NULL/junk rows instead of ERRORing.</li>
 *   <li>Malformed stored shapes (jsonb-null column, scalar column,
 *       non-array {@code turns_covered}) never ERROR the guard and stay
 *       overwritable (self-healing).</li>
 *   <li>ORM writes cannot clobber the column: a dirty-entity flush leaves
 *       {@code summary_cold} untouched ({@code insertable/updatable=false}) -
 *       the bypass that would otherwise undo both the guard and the flag.</li>
 * </ul>
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("ConversationRepository - summary_cold native queries against real Postgres")
class ConversationSummaryColdRepositoryIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("conv_guard_test")
            .withUsername("test")
            .withPassword("test");

    private JdbcTemplate jdbc;
    private EntityManagerFactory emf;
    private EntityManager em;
    private ConversationRepository repository;

    private static final String ACTIVE_3 =
            "{\"turns_covered\":[0,1,2],\"status\":\"active\",\"generated_at\":\"T1\"}";
    private static final String ACTIVE_5 =
            "{\"turns_covered\":[0,1,2,3,4],\"status\":\"active\",\"generated_at\":\"T2\"}";
    private static final String ACTIVE_2 =
            "{\"turns_covered\":[0,1],\"status\":\"active\",\"generated_at\":\"T3\"}";

    @BeforeAll
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName(POSTGRES.getDriverClassName());
        jdbc = new JdbcTemplate(ds);

        // Hibernate creates the entity tables at EMF init (hbm2ddl=create),
        // but the schema itself must pre-exist.
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS conversation");

        Map<String, String> jpaProps = new HashMap<>();
        jpaProps.put("jakarta.persistence.jdbc.url", POSTGRES.getJdbcUrl());
        jpaProps.put("jakarta.persistence.jdbc.user", POSTGRES.getUsername());
        jpaProps.put("jakarta.persistence.jdbc.password", POSTGRES.getPassword());
        jpaProps.put("jakarta.persistence.jdbc.driver", POSTGRES.getDriverClassName());
        emf = Persistence.createEntityManagerFactory("summary-cold-pu", jpaProps);
        em = emf.createEntityManager();

        // The REAL repository proxy - @Query/@Modifying handling identical to
        // the production wiring, minus the Spring container.
        repository = new JpaRepositoryFactory(em).getRepository(ConversationRepository.class);
    }

    @AfterEach
    void cleanRows() {
        em.clear();
        inTx(() -> {
            em.createNativeQuery("DELETE FROM conversation.messages").executeUpdate();
            em.createNativeQuery("DELETE FROM conversation.conversations").executeUpdate();
        });
    }

    @AfterAll
    void tearDown() {
        if (em != null) em.close();
        if (emf != null) emf.close();
    }

    /** Run {@code work} in a resource-local transaction (commit on success). */
    private void inTx(Runnable work) {
        em.getTransaction().begin();
        try {
            work.run();
            em.getTransaction().commit();
        } catch (RuntimeException e) {
            em.getTransaction().rollback();
            throw e;
        }
    }

    /** Persist a minimal conversation row through JPA and commit it. */
    private String newConversation() {
        Conversation c = new Conversation();
        c.setUserId("user-1");
        c.setOrganizationId("org-1"); // OrgScopedEntityListener fails loud on null
        inTx(() -> em.persist(c));
        return c.getId();
    }

    private String storedEnvelope(String id) {
        return jdbc.queryForObject(
                "SELECT summary_cold::text FROM conversation.conversations WHERE id = ?",
                String.class, id);
    }

    private void seedRawEnvelope(String id, String jsonLiteral) {
        jdbc.update("UPDATE conversation.conversations SET summary_cold = CAST(? AS jsonb) WHERE id = ?",
                jsonLiteral, id);
    }

    // -------------------------------------------------------------------------
    // updateSummaryCold - monotone-recall guard
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("updateSummaryCold (monotone guard)")
    class UpdateSummaryCold {

        @Test
        @DisplayName("first write on a NULL column lands")
        void firstWriteLands() {
            String id = newConversation();

            int[] rows = new int[1];
            inTx(() -> rows[0] = repository.updateSummaryCold(id, ACTIVE_3, 3));

            assertThat(rows[0]).isEqualTo(1);
            assertThat(storedEnvelope(id)).contains("\"generated_at\": \"T1\"");
        }

        @Test
        @DisplayName("broader coverage overwrites a smaller active envelope")
        void broaderCoverageOverwrites() {
            String id = newConversation();
            inTx(() -> repository.updateSummaryCold(id, ACTIVE_3, 3));

            int[] rows = new int[1];
            inTx(() -> rows[0] = repository.updateSummaryCold(id, ACTIVE_5, 5));

            assertThat(rows[0]).isEqualTo(1);
            assertThat(storedEnvelope(id)).contains("\"generated_at\": \"T2\"");
        }

        @Test
        @DisplayName("equal coverage overwrites - same-size regeneration refreshes content")
        void equalCoverageOverwrites() {
            String id = newConversation();
            inTx(() -> repository.updateSummaryCold(id, ACTIVE_3, 3));

            String refreshed = ACTIVE_3.replace("T1", "T1b");
            int[] rows = new int[1];
            inTx(() -> rows[0] = repository.updateSummaryCold(id, refreshed, 3));

            assertThat(rows[0]).isEqualTo(1);
            assertThat(storedEnvelope(id)).contains("\"generated_at\": \"T1b\"");
        }

        @Test
        @DisplayName("regression - smaller coverage over an ACTIVE envelope is rejected, stored envelope intact")
        void smallerCoverageRejectedEnvelopeIntact() {
            // The racing-writer scenario the guard exists for: this pod's
            // ShedLock TTL expired under a slow LLM, another pod persisted
            // 5-turn coverage, and our late 2-turn write must NOT shrink the
            // agent's recall. Pre-fix this was unconditional last-write-wins.
            String id = newConversation();
            inTx(() -> repository.updateSummaryCold(id, ACTIVE_5, 5));

            int[] rows = new int[1];
            inTx(() -> rows[0] = repository.updateSummaryCold(id, ACTIVE_2, 2));

            assertThat(rows[0]).isZero();
            assertThat(storedEnvelope(id))
                    .contains("\"generated_at\": \"T2\"")
                    .doesNotContain("T3");
        }

        @Test
        @DisplayName("stale escape hatch - smaller coverage lands over a STALE envelope and is active again")
        void staleEscapeHatchAllowsShrinkAndReactivates() {
            String id = newConversation();
            inTx(() -> repository.updateSummaryCold(id, ACTIVE_5, 5));
            int[] marked = new int[1];
            inTx(() -> marked[0] = repository.markSummaryColdStale(id));
            assertThat(marked[0]).isEqualTo(1);

            int[] rows = new int[1];
            inTx(() -> rows[0] = repository.updateSummaryCold(id, ACTIVE_2, 2));

            assertThat(rows[0]).isEqualTo(1);
            assertThat(storedEnvelope(id))
                    .contains("\"generated_at\": \"T3\"")
                    .contains("\"status\": \"active\"");
        }

        @Test
        @DisplayName("legacy envelope without turns_covered counts as coverage 0 - any write lands")
        void legacyEnvelopeWithoutCoverageIsOverwritable() {
            String id = newConversation();
            seedRawEnvelope(id, "{\"user_intents\":[\"x\"]}");

            int[] rows = new int[1];
            inTx(() -> rows[0] = repository.updateSummaryCold(id, ACTIVE_2, 2));
            assertThat(rows[0]).isEqualTo(1);
        }

        @Test
        @DisplayName("malformed shapes (jsonb-null column, scalar column, non-array turns_covered) never ERROR and stay overwritable")
        void malformedShapesSelfHeal() {
            String idJsonNull = newConversation();
            seedRawEnvelope(idJsonNull, "null");
            int[] r1 = new int[1];
            inTx(() -> r1[0] = repository.updateSummaryCold(idJsonNull, ACTIVE_2, 2));
            assertThat(r1[0]).isEqualTo(1);

            String idScalar = newConversation();
            seedRawEnvelope(idScalar, "\"junk\"");
            int[] r2 = new int[1];
            inTx(() -> r2[0] = repository.updateSummaryCold(idScalar, ACTIVE_2, 2));
            assertThat(r2[0]).isEqualTo(1);

            String idNullCoverage = newConversation();
            seedRawEnvelope(idNullCoverage, "{\"turns_covered\": null, \"status\":\"active\"}");
            int[] r3 = new int[1];
            inTx(() -> r3[0] = repository.updateSummaryCold(idNullCoverage, ACTIVE_2, 2));
            assertThat(r3[0]).isEqualTo(1);

            String idNumberCoverage = newConversation();
            seedRawEnvelope(idNumberCoverage, "{\"turns_covered\": 7, \"status\":\"active\"}");
            int[] r4 = new int[1];
            inTx(() -> r4[0] = repository.updateSummaryCold(idNumberCoverage, ACTIVE_2, 2));
            assertThat(r4[0]).isEqualTo(1);
        }

        @Test
        @DisplayName("missing conversation row → 0 rows (caller maps to Failed via existsById)")
        void missingRowYieldsZero() {
            int[] rows = new int[1];
            inTx(() -> rows[0] = repository.updateSummaryCold("no-such-id", ACTIVE_2, 2));
            assertThat(rows[0]).isZero();
        }
    }

    // -------------------------------------------------------------------------
    // markSummaryColdStale - status transitions
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("markSummaryColdStale (status-aware recall)")
    class MarkStale {

        @Test
        @DisplayName("transitions exactly once: 1 row, then 0 on repeat; content preserved")
        void transitionsOnceAndPreservesContent() {
            String id = newConversation();
            inTx(() -> repository.updateSummaryCold(id,
                    "{\"turns_covered\":[0,1],\"status\":\"active\",\"user_intents\":[\"keep me\"]}", 2));

            int[] first = new int[1];
            int[] second = new int[1];
            inTx(() -> first[0] = repository.markSummaryColdStale(id));
            inTx(() -> second[0] = repository.markSummaryColdStale(id));

            assertThat(first[0]).isEqualTo(1);
            assertThat(second[0]).isZero();
            assertThat(storedEnvelope(id))
                    .contains("\"status\": \"stale\"")
                    .contains("keep me");
        }

        @Test
        @DisplayName("legacy envelope without a status key is stale-markable (status key added)")
        void legacyEnvelopeGetsStatusKey() {
            String id = newConversation();
            seedRawEnvelope(id, "{\"turns_covered\":[0,1,2,3]}");

            int[] rows = new int[1];
            inTx(() -> rows[0] = repository.markSummaryColdStale(id));

            assertThat(rows[0]).isEqualTo(1);
            assertThat(storedEnvelope(id)).contains("\"status\": \"stale\"");
        }

        @Test
        @DisplayName("no-ops without ERROR on: missing row, NULL column, jsonb-null column, scalar column")
        void noOpsOnUnflaggableRows() {
            int[] missing = new int[1];
            inTx(() -> missing[0] = repository.markSummaryColdStale("no-such-id"));
            assertThat(missing[0]).isZero();

            String idNull = newConversation();
            int[] nul = new int[1];
            inTx(() -> nul[0] = repository.markSummaryColdStale(idNull));
            assertThat(nul[0]).isZero();

            String idJsonNull = newConversation();
            seedRawEnvelope(idJsonNull, "null");
            int[] jn = new int[1];
            inTx(() -> jn[0] = repository.markSummaryColdStale(idJsonNull));
            assertThat(jn[0]).isZero();

            String idScalar = newConversation();
            seedRawEnvelope(idScalar, "\"junk\"");
            int[] sc = new int[1];
            inTx(() -> sc[0] = repository.markSummaryColdStale(idScalar));
            assertThat(sc[0]).isZero();
        }
    }

    // -------------------------------------------------------------------------
    // ORM bypass closed - the column is read-only for entity flushes
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("ORM writes cannot clobber summary_cold")
    class OrmBypassClosed {

        @Test
        @DisplayName("regression - dirty-entity save() leaves summary_cold untouched (no stale-snapshot restore)")
        void dirtyEntityFlushDoesNotRewriteSummaryCold() {
            // Pre-fix: Conversation has no @DynamicUpdate, so ANY
            // save(conversation) flushed the full row - including the
            // summary_cold snapshot taken at load time - silently undoing a
            // concurrent summariser persist or stale flag. The read-only
            // column mapping must keep the column out of entity UPDATEs.
            String id = newConversation();
            em.clear();

            inTx(() -> {
                Conversation loaded = repository.findById(id).orElseThrow();
                // Simulate a concurrent native write AFTER the entity snapshot
                // was taken (the entity still sees summary_cold = null).
                repository.updateSummaryCold(id, ACTIVE_5, 5);
                repository.markSummaryColdStale(id);

                loaded.setTitle("renamed while summariser ran");
                // Poison the in-memory map too - even an explicit setter write
                // must not reach the DB through the entity path.
                loaded.setSummaryCold(Map.of("poison", true));
                repository.saveAndFlush(loaded);
            });

            assertThat(storedEnvelope(id))
                    .contains("\"generated_at\": \"T2\"")
                    .contains("\"status\": \"stale\"")
                    .doesNotContain("poison");
            assertThat(jdbc.queryForObject(
                    "SELECT title FROM conversation.conversations WHERE id = ?",
                    String.class, id)).isEqualTo("renamed while summariser ran");
        }
    }
}
