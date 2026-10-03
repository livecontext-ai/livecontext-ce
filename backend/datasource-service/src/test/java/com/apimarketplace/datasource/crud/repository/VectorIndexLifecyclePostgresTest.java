package com.apimarketplace.datasource.crud.repository;

import com.apimarketplace.datasource.crud.repository.VectorRepository.IndexState;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Real-Postgres contract for the per-datasource HNSW index lifecycle.
 *
 * <p>Three defects lived here with no test able to see them, because every
 * existing test mocks the repository: an INVALID index left by a failed
 * {@code CREATE INDEX CONCURRENTLY} that {@code IF NOT EXISTS} then treated as
 * present forever (the datasource sequential-scanned for good, silently); a
 * {@code DROP INDEX} without {@code CONCURRENTLY} that would have taken ACCESS
 * EXCLUSIVE on the table every tenant shares; and a similarity query whose
 * partial index was only usable under a custom plan. None of those produce an
 * error. They produce the right rows, slowly, or a stall.
 *
 * <p>Needs pgvector. With {@code CI} set and no URL the class FAILS rather than
 * skipping, and it also FAILS on CI if the scratch database lacks the
 * extension: a job that quietly downgraded its Postgres image to one without
 * pgvector would otherwise turn this whole class into a green no-op.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("HNSW index lifecycle - real Postgres with pgvector")
class VectorIndexLifecyclePostgresTest {

    private static final String URL = System.getenv("DATASOURCE_TEST_PG_URL");
    private static final String USER = System.getenv().getOrDefault("DATASOURCE_TEST_PG_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("DATASOURCE_TEST_PG_PASSWORD", "postgres");

    private static final long DS = 42L;
    private static final String TENANT = "tenant-under-test";
    private static final int DIM = 64;

    private JdbcTemplate jdbc;
    private VectorRepository repository;

    @BeforeAll
    void setUpSchema() {
        requireDatabaseOnCi();
        String database = URL.substring(URL.lastIndexOf('/') + 1).split("\\?")[0];
        if (!database.toLowerCase(Locale.ROOT).contains("test")) {
            throw new IllegalStateException("DATASOURCE_TEST_PG_URL must name a scratch database "
                    + "containing 'test' (this class drops data_source_vectors), got: " + database);
        }
        awaitDatabase();
        DriverManagerDataSource ds = new DriverManagerDataSource(URL, USER, PASSWORD);
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);
        repository = new VectorRepository(new NamedParameterJdbcTemplate(ds));

        requirePgvector();

        // The columns the repository reads and joins on, in the default schema so
        // the unqualified table name the repository uses resolves here.
        jdbc.execute("DROP TABLE IF EXISTS data_source_vectors");
        jdbc.execute("DROP TABLE IF EXISTS data_source_items");
        jdbc.execute("""
                CREATE TABLE data_source_items (
                    id BIGSERIAL PRIMARY KEY,
                    data JSONB,
                    priority INTEGER,
                    created_at TIMESTAMPTZ DEFAULT now(),
                    -- V538 (CASA LC-066): the vector search selects it.
                    data_sensitivity VARCHAR(16) NOT NULL DEFAULT 'NORMAL')
                """);
        jdbc.execute("""
                CREATE TABLE data_source_vectors (
                    id BIGSERIAL PRIMARY KEY,
                    data_source_id BIGINT NOT NULL,
                    item_id BIGINT NOT NULL REFERENCES data_source_items(id) ON DELETE CASCADE,
                    column_name VARCHAR(255) NOT NULL,
                    embedding vector NOT NULL,
                    dimension SMALLINT NOT NULL,
                    tenant_id VARCHAR(255) NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    UNIQUE(item_id, column_name))
                """);
        jdbc.execute("CREATE INDEX idx_dsv_datasource ON data_source_vectors(data_source_id)");
        jdbc.execute("CREATE INDEX idx_dsv_tenant ON data_source_vectors(tenant_id)");
    }

    @BeforeEach
    void reset() {
        // DROP INDEX CONCURRENTLY cannot run inside a transaction; JdbcTemplate
        // autocommits, so this is fine here.
        jdbc.execute("DROP INDEX CONCURRENTLY IF EXISTS idx_vectors_ds_" + DS);
        jdbc.execute("TRUNCATE data_source_vectors, data_source_items");
    }

    // ===== the invalid-index trap =====

    @Nested
    @DisplayName("The invalid-index trap")
    class InvalidIndexTrap {

        /**
         * THE regression. A build that fails part-way leaves an INVALID index
         * bearing the datasource's index name; {@code IF NOT EXISTS} then sees
         * it on every later attempt and does nothing, so the datasource
         * sequential-scans forever. The failure is reproduced the way it happens
         * in the field: a row whose dimension does not match the cast.
         */
        @Test
        @DisplayName("A build wrecked by a bad row is repaired by the next build, not skipped forever")
        void failedBuildIsRepairedNotSkipped() {
            insertVector(1L, randomVector(DIM + 1));   // wrong dimension: the cast to vector(DIM) fails
            assertThatCode(() -> repository.createHnswIndex(DS, DIM, "cosine"))
                    .as("the first build must fail on the bad row")
                    .isInstanceOf(Exception.class);
            assertThat(repository.indexState(DS))
                    .as("a failed CONCURRENTLY build leaves an INVALID index behind")
                    .isEqualTo(IndexState.INVALID);

            // The operator fixes the data by removing the bad row, which is what
            // happens in the field. NOT with TRUNCATE: TRUNCATE rebuilds every index on
            // the table and would silently turn the invalid index valid again, so a
            // test that truncated here passed against the unfixed code and proved
            // nothing (caught by mutation). Before the fix, this second build was a
            // silent no-op and the index stayed INVALID for the life of the table.
            jdbc.update("DELETE FROM data_source_vectors WHERE item_id = 1");
            insertVector(2L, randomVector(DIM));
            assertThat(repository.indexState(DS))
                    .as("deleting the bad row does not repair the index by itself")
                    .isEqualTo(IndexState.INVALID);
            repository.createHnswIndex(DS, DIM, "cosine");

            assertThat(repository.indexState(DS)).isEqualTo(IndexState.VALID);
        }

        @Test
        @DisplayName("indexState tells a missing index from an invalid one")
        void indexStateDistinguishesMissingFromInvalid() {
            assertThat(repository.indexState(DS)).isEqualTo(IndexState.MISSING);
            assertThat(repository.isIndexValid(DS)).isFalse();

            insertVector(1L, randomVector(DIM));
            repository.createHnswIndex(DS, DIM, "cosine");

            assertThat(repository.indexState(DS)).isEqualTo(IndexState.VALID);
            assertThat(repository.isIndexValid(DS)).isTrue();
        }

        /**
         * pg_class.relname is not schema-qualified and the one database holds every
         * schema. Anchoring on the table OID is what stops a same-named leftover
         * elsewhere from being the row that is read; if THAT one were invalid, every
         * build would drop the real valid index and rebuild it.
         */
        @Test
        @DisplayName("A same-named INVALID index on another table in another schema is invisible to indexState")
        void sameNamedIndexElsewhereIsIgnored() {
            // The decoy is created FIRST, so its pg_index row is the older one and
            // an unanchored query would read it before the real index's row: that
            // is what makes the indexState half of this test discriminate.
            jdbc.execute("CREATE SCHEMA IF NOT EXISTS decoy");
            try {
                jdbc.execute("DROP TABLE IF EXISTS decoy.data_source_vectors");
                jdbc.execute("CREATE TABLE decoy.data_source_vectors (id bigint, data_source_id bigint)");
                jdbc.execute("INSERT INTO decoy.data_source_vectors VALUES (1, 1), (2, 1)");
                // Same NAME as the real index, made INVALID the privilege-free way: a
                // CONCURRENTLY unique build over duplicate keys fails and leaves the
                // invalid index behind. No superuser catalog edit needed.
                assertThatCode(() -> jdbc.execute("CREATE UNIQUE INDEX CONCURRENTLY idx_vectors_ds_" + DS
                        + " ON decoy.data_source_vectors (data_source_id)"))
                        .isInstanceOf(Exception.class);
                Boolean decoyValid = jdbc.queryForObject(
                        "SELECT indisvalid FROM pg_index WHERE indexrelid = 'decoy.idx_vectors_ds_" + DS + "'::regclass",
                        Boolean.class);
                assertThat(decoyValid).as("the decoy must really be INVALID").isFalse();

                insertVector(1L, randomVector(DIM));
                repository.createHnswIndex(DS, DIM, "cosine");

                assertThat(repository.indexState(DS))
                        .as("the decoy in another schema must not be the row read for this datasource")
                        .isEqualTo(IndexState.VALID);
                assertThat(repository.countHnswIndexes())
                        .as("the decoy must not be counted against the ceiling")
                        .isEqualTo(1);
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS decoy CASCADE");
            }
        }

        @Test
        @DisplayName("Building on an already valid index is a no-op, not an error")
        void validIndexIsLeftAlone() {
            insertVector(1L, randomVector(DIM));
            repository.createHnswIndex(DS, DIM, "cosine");

            assertThatCode(() -> repository.createHnswIndex(DS, DIM, "cosine")).doesNotThrowAnyException();
            assertThat(repository.countHnswIndexes()).isEqualTo(1);
        }
    }

    // ===== dropping =====

    @Nested
    @DisplayName("Dropping")
    class Dropping {

        @Test
        @DisplayName("dropHnswIndex removes the index and is safe to call when there is none")
        void dropRemovesAndIsIdempotent() {
            insertVector(1L, randomVector(DIM));
            repository.createHnswIndex(DS, DIM, "cosine");
            assertThat(repository.countHnswIndexes()).isEqualTo(1);

            repository.dropHnswIndex(DS);
            assertThat(repository.indexState(DS)).isEqualTo(IndexState.MISSING);
            assertThat(repository.countHnswIndexes()).isZero();

            assertThatCode(() -> repository.dropHnswIndex(DS)).doesNotThrowAnyException();
        }

        /**
         * Structural pin. A plain DROP INDEX takes ACCESS EXCLUSIVE on the shared
         * table and stalls every tenant's vector traffic behind any long query;
         * CONCURRENTLY cannot run inside a transaction block, so the method has to
         * opt out of transactions the way createHnswIndex does. Both halves are
         * asserted here because losing either one re-creates the stall.
         */
        @Test
        @DisplayName("dropHnswIndex is CONCURRENTLY and runs outside any transaction")
        void dropIsConcurrentAndNonTransactional() throws NoSuchMethodException {
            Method drop = VectorRepository.class.getMethod("dropHnswIndex", Long.class);
            Transactional tx = drop.getAnnotation(Transactional.class);
            assertThat(tx).as("dropHnswIndex must declare its transaction propagation").isNotNull();
            assertThat(tx.propagation()).isEqualTo(Propagation.NOT_SUPPORTED);

            // The reflection pin above is what guards the lock mode; a behavioural
            // lock test needs a second session, a thread and pg_stat_activity polling
            // and is not attempted here. This half only checks the drop still works.
            insertVector(1L, randomVector(DIM));
            repository.createHnswIndex(DS, DIM, "cosine");
            assertThatCode(() -> repository.dropHnswIndex(DS)).doesNotThrowAnyException();
            assertThat(repository.indexState(DS)).isEqualTo(IndexState.MISSING);
        }
    }

    // ===== the partial index must survive a generic plan =====

    @Nested
    @DisplayName("Similarity search keeps its index under a generic plan")
    class GenericPlan {

        /**
         * The 153x defect. With data_source_id bound as a parameter the planner
         * cannot prove the query implies the partial index's predicate under a
         * GENERIC plan and silently falls back to a Bitmap Heap Scan; the same rows
         * come back 153x slower. The id is now a literal, so the proof holds in
         * every plan mode. Asserted behaviourally: force generic plans on the
         * session, run the shipped query, and check the partial index was scanned.
         */
        @Test
        @DisplayName("The shipped similarity SQL scans the partial index even when generic plans are forced")
        void partialIndexIsUsedUnderGenericPlan() {
            // Enough rows that the planner genuinely prefers the HNSW index over a
            // sequential scan: at a few hundred rows it would rationally seq-scan
            // even with a usable index, and the test would say nothing.
            insertVectors(3000);
            repository.createHnswIndex(DS, DIM, "cosine");
            jdbc.execute("ANALYZE data_source_vectors");

            var one = new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                    URL, USER, PASSWORD, true);
            one.setDriverClassName("org.postgresql.Driver");
            try {
                NamedParameterJdbcTemplate session = new NamedParameterJdbcTemplate(one);
                session.getJdbcTemplate().execute("SET plan_cache_mode = force_generic_plan");
                float[] query = randomVector(DIM);

                // Self-check first: the SAME query with data_source_id as a bind
                // parameter must NOT reach the partial index under a generic plan.
                // If this ever starts passing, the discriminator is broken and the
                // real assertion below proves nothing.
                long before = indexScans(session);
                session.queryForList(
                        "SELECT i.id FROM data_source_vectors v JOIN data_source_items i ON v.item_id = i.id "
                                + "WHERE v.data_source_id = :ds AND v.tenant_id = :t AND v.column_name = :c "
                                + "ORDER BY (v.embedding::vector(" + DIM + ") <=> :q::vector(" + DIM + ")) LIMIT 10",
                        Map.of("ds", DS, "t", TENANT, "c", "emb", "q", literal(query)));
                assertThat(indexScans(session))
                        .as("a bind-parameter data_source_id must be unable to use the partial index "
                                + "under a generic plan, or this test cannot discriminate")
                        .isEqualTo(before);

                // The real assertion: the SHIPPED query, id inlined as a literal.
                long scansBefore = indexScans(session);
                VectorRepository underGeneric = new VectorRepository(session);
                List<Map<String, Object>> rows = underGeneric.similaritySearch(
                        DS, TENANT, "emb", query, DIM, "cosine", 10, null, null, null);
                long scansAfter = indexScans(session);

                assertThat(rows).hasSize(10);
                assertThat(scansAfter)
                        .as("the partial HNSW index must be scanned; a fallback seq/bitmap scan "
                                + "returns the same rows and is how the 153x regression hides")
                        .isGreaterThan(scansBefore);
            } finally {
                one.destroy();
            }
        }

        /** Reads idx_scan after forcing this session's pending stats out. */
        private long indexScans(NamedParameterJdbcTemplate session) {
            session.getJdbcTemplate().execute("SELECT pg_stat_force_next_flush()");
            Long n = session.getJdbcTemplate().queryForObject(
                    "SELECT coalesce(idx_scan, 0) FROM pg_stat_user_indexes "
                            + "WHERE indexrelname = 'idx_vectors_ds_" + DS + "' AND schemaname = current_schema()",
                    Long.class);
            return n != null ? n : 0L;
        }

        private static String literal(float[] v) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < v.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(v[i]);
            }
            return sb.append(']').toString();
        }
    }

    // ===== the capacity reporter, against the real catalog =====

    @Nested
    @DisplayName("Capacity reporter reads the real catalog")
    class Reporter {

        /**
         * Five hand-written catalog queries, pinned elsewhere only by a mock that
         * answers 3L to any SQL. A typo in one of them would read NaN forever. This
         * runs them against the real catalog, including the orphan join on
         * data_sources and the decoy-schema exclusion.
         */
        @Test
        @DisplayName("counts total, invalid and orphan indexes from the real catalog, ignoring other schemas")
        void reporterCountsFromTheCatalog() {
            jdbc.execute("DROP TABLE IF EXISTS data_sources");
            jdbc.execute("CREATE TABLE data_sources (id BIGINT PRIMARY KEY)");
            jdbc.execute("CREATE SCHEMA IF NOT EXISTS decoy");
            try {
                jdbc.execute("INSERT INTO data_sources (id) VALUES (" + DS + ")");
                insertVector(1L, randomVector(DIM));
                repository.createHnswIndex(DS, DIM, "cosine");

                // A decoy in another schema that an unanchored query would count.
                jdbc.execute("DROP TABLE IF EXISTS decoy.data_source_vectors");
                jdbc.execute("CREATE TABLE decoy.data_source_vectors (id bigint, data_source_id bigint)");
                jdbc.execute("CREATE INDEX idx_vectors_ds_999 ON decoy.data_source_vectors (data_source_id)");

                var reporter = new com.apimarketplace.datasource.metrics.VectorCapacityReporter(
                        jdbc, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
                var snapshot = reporter.snapshot();

                assertThat(snapshot).isNotNull();
                assertThat(snapshot.hnswIndexes()).as("one real index, decoy excluded").isEqualTo(1);
                assertThat(snapshot.invalidIndexes()).isZero();
                assertThat(snapshot.orphanIndexes()).as("its datasource row exists").isZero();
                assertThat(snapshot.tableBytes()).isPositive();

                // The datasource disappears without its index: that is an orphan.
                jdbc.execute("DELETE FROM data_sources WHERE id = " + DS);
                reporter.invalidate();
                assertThat(reporter.snapshot().orphanIndexes()).isEqualTo(1);
            } finally {
                jdbc.execute("DROP SCHEMA IF EXISTS decoy CASCADE");
                jdbc.execute("DROP TABLE IF EXISTS data_sources");
            }
        }
    }

    // ===== helpers =====

    /** Bulk path: one multi-row INSERT for the items, then the repository's own batch upsert. */
    private void insertVectors(int count) {
        String items = IntStream.rangeClosed(1, count)
                .mapToObj(i -> "(" + i + ", '{}'::jsonb, 0)")
                .collect(Collectors.joining(","));
        jdbc.execute("INSERT INTO data_source_items (id, data, priority) VALUES " + items);
        List<VectorRepository.VectorEntry> entries = IntStream.rangeClosed(1, count)
                .mapToObj(i -> new VectorRepository.VectorEntry((long) i, "emb", randomVector(DIM)))
                .toList();
        repository.insertVectorBatch(DS, TENANT, entries);
    }

    private void insertVector(long itemId, float[] embedding) {
        jdbc.update("INSERT INTO data_source_items (id, data, priority) VALUES (?, '{}'::jsonb, 0) "
                + "ON CONFLICT (id) DO NOTHING", itemId);
        repository.insertVector(DS, TENANT, itemId, "emb", embedding);
    }

    private static float[] randomVector(int dim) {
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = (float) Math.random();
        }
        return v;
    }

    private void requirePgvector() {
        List<String> installed = jdbc.queryForList(
                "SELECT extname FROM pg_extension WHERE extname = 'vector'", String.class);
        if (installed.isEmpty()) {
            try {
                jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
                return;
            } catch (Exception e) {
                boolean onCi = System.getenv("CI") != null && !System.getenv("CI").isBlank();
                String message = "pgvector is not available in the scratch database (" + e.getMessage()
                        + "). This class is the only test of the HNSW index lifecycle; the CI "
                        + "postgres service must run an image that ships pgvector (pgvector/pgvector:pg16).";
                if (onCi) {
                    throw new IllegalStateException(message, e);
                }
                Assumptions.abort(message);
            }
        }
    }

    private static void requireDatabaseOnCi() {
        if (URL != null && !URL.isBlank()) {
            return;
        }
        boolean onCi = System.getenv("CI") != null && !System.getenv("CI").isBlank();
        if (onCi) {
            throw new IllegalStateException("DATASOURCE_TEST_PG_URL is unset on CI. This class must "
                    + "execute there: it is the only test of the HNSW index lifecycle, whose defects "
                    + "(an INVALID index treated as present, a table-wide lock, a dropped partial "
                    + "index) all fail as slow-but-correct rather than as errors.");
        }
        Assumptions.abort("no scratch Postgres: set DATASOURCE_TEST_PG_URL to run this locally");
    }

    private static void awaitDatabase() {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try (Connection ignored = DriverManager.getConnection(URL, USER, PASSWORD)) {
                return;
            } catch (Exception e) {
                last = new IllegalStateException("cannot reach " + URL, e);
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
