package com.apimarketplace.orchestrator.persistence;

import com.apimarketplace.common.storage.repository.OrgStorageBreakdownRepository;
import com.apimarketplace.orchestrator.services.storage.StorageReconciliationQueries;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The storage accounting SQL, run against a real engine: the two category predicates, the org
 * counter's zero clamp, and the scope enumeration the nightly pass walks.
 *
 * <p><b>Why executing it is the whole point.</b> The failure this guards produced no error of any
 * kind. On 2026-09-18 the busiest production tenant held 21 GB of ACTIVE rows while the breakdown
 * reported 4.9 GB: the FILES predicate tested {@code source_type IN ('S3_FILE','CHAT_ATTACHMENT')}
 * although {@code S3_FILE} is a STORAGE type, and STEP_OUTPUTS required
 * {@code storage_type = 'JSON'}, so 2105 {@code S3_FILE/STEP_OUTPUT} rows carrying 15 GB, plus
 * every interface video, screenshot and PDF, fell between the two. Reconciliation is an absolute
 * set, so each night it wrote that under-count over the correct total the incremental save path had
 * kept during the day. A string-pattern test cannot see any of that: the old SQL was well-formed
 * and named the right table. Only running both predicates over a row matrix and comparing against
 * an independent total can.
 *
 * <p><b>How it runs.</b> Plain JDBC against the scratch database named by
 * {@code ORCHESTRATOR_TEST_PG_URL}, the same handle its siblings in this package use. Testcontainers
 * is deliberately NOT used: the {@code arc-build} runners expose no Docker socket, so such a class
 * SKIPS there, which looks like coverage and is not. That is not hypothetical, it is how this test
 * was first written. With {@code CI} set and no URL the class FAILS rather than skipping, so it
 * cannot be quietly disabled by dropping the env block from its workflow step.
 *
 * <p>The SQL is never copied here. The predicates come from {@link StorageReconciliationQueries}
 * and the clamp is read off the repository's {@code @Query} by reflection, so a refactor that
 * changes either is tested rather than described.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("storage accounting - real Postgres, the shipped SQL")
class StorageAccountingQueriesPostgresTest {

    private static final String URL = System.getenv("ORCHESTRATOR_TEST_PG_URL");
    private static final String USER = System.getenv().getOrDefault("ORCHESTRATOR_TEST_PG_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("ORCHESTRATOR_TEST_PG_PASSWORD", "postgres");

    private static final String TENANT = "tenant-accounting-test";
    private static final String OTHER_TENANT = "tenant-next-door-test";
    private static final String SYSTEM_TENANT = "_publications";
    private static final String ORG = "org-accounting-test";
    private static final String OTHER_ORG = "org-next-door-test";

    /** Size every probe row carries, so a probe can be removed without touching the matrix. */
    private static final long PROBE_BYTES = 1_000_003L;

    /** Every storage type that exists, plus one that does not exist yet. */
    private static final List<String> STORAGE_TYPES =
            Arrays.asList("JSON", "TEXT", "BINARY", "S3_FILE", "SOME_FUTURE_TYPE", null);

    /** Every source type that exists, plus one that does not exist yet. */
    private static final List<String> SOURCE_TYPES = Arrays.asList(
            "S3_FILE", "CHAT_ATTACHMENT", "STEP_OUTPUT",
            "INTERFACE_SCREENSHOT", "INTERFACE_PDF", "INTERFACE_VIDEO",
            "SKIPPED_NODE", "SIGNAL", "INTERFACE_ACTION", "SOME_FUTURE_SOURCE", null);

    private NamedParameterJdbcTemplate jdbc;
    private String orgIncrementSql;

    @BeforeAll
    void setUpSchema() throws Exception {
        requireDatabaseOnCi();

        String database = URL.substring(URL.lastIndexOf('/') + 1).split("\\?")[0];
        if (!database.toLowerCase(Locale.ROOT).contains("test")) {
            throw new IllegalStateException(
                    "ORCHESTRATOR_TEST_PG_URL must point at a scratch database whose name contains "
                            + "'test' (this test drops tables in the storage schema), got: " + database);
        }
        awaitDatabase();

        DataSource ds = new DriverManagerDataSource(URL, USER, PASSWORD);
        jdbc = new NamedParameterJdbcTemplate(ds);

        orgIncrementSql = shippedSql(OrgStorageBreakdownRepository.class, "incrementUsage",
                String.class, String.class, long.class, int.class);

        jdbc.getJdbcOperations().execute("CREATE SCHEMA IF NOT EXISTS storage");
        jdbc.getJdbcOperations().execute("DROP TABLE IF EXISTS storage.storage CASCADE");
        jdbc.getJdbcOperations().execute("DROP TABLE IF EXISTS storage.tenant_storage_breakdown CASCADE");
        jdbc.getJdbcOperations().execute("DROP TABLE IF EXISTS storage.org_storage_breakdown CASCADE");
        jdbc.getJdbcOperations().execute("""
                CREATE TABLE storage.storage (
                    id              BIGSERIAL PRIMARY KEY,
                    tenant_id       VARCHAR(255) NOT NULL,
                    organization_id VARCHAR(255),
                    status          VARCHAR(32)  NOT NULL,
                    storage_type    VARCHAR(64),
                    source_type     VARCHAR(64),
                    is_folder       BOOLEAN      NOT NULL DEFAULT false,
                    size_bytes      BIGINT       NOT NULL
                )""");
        jdbc.getJdbcOperations().execute("""
                CREATE TABLE storage.tenant_storage_breakdown (
                    tenant_id     VARCHAR(255) NOT NULL,
                    category      VARCHAR(50)  NOT NULL,
                    used_bytes    BIGINT       NOT NULL,
                    item_count    INT          NOT NULL,
                    calculated_at TIMESTAMPTZ  NOT NULL,
                    PRIMARY KEY (tenant_id, category)
                )""");
        jdbc.getJdbcOperations().execute("""
                CREATE TABLE storage.org_storage_breakdown (
                    organization_id VARCHAR(255) NOT NULL,
                    category        VARCHAR(50)  NOT NULL,
                    used_bytes      BIGINT       NOT NULL,
                    item_count      INT          NOT NULL,
                    calculated_at   TIMESTAMPTZ  NOT NULL,
                    PRIMARY KEY (organization_id, category)
                )""");

        seedMatrix();
    }

    @BeforeEach
    void requireSetUp() {
        Assumptions.assumeTrue(jdbc != null, "no scratch Postgres - skipped");
    }

    /**
     * One ACTIVE row per (storage_type, source_type) pair, each a distinct size, so a row counted
     * twice or not at all changes the total rather than cancelling out.
     */
    private void seedMatrix() {
        long size = 1;
        for (String storageType : STORAGE_TYPES) {
            for (String sourceType : SOURCE_TYPES) {
                insert(TENANT, ORG, "ACTIVE", storageType, sourceType, size, false);
                size += 1;
            }
        }
    }

    private void insert(String tenant, String org, String status, String storageType,
                        String sourceType, long sizeBytes, boolean isFolder) {
        jdbc.update("""
                INSERT INTO storage.storage
                    (tenant_id, organization_id, status, storage_type, source_type, is_folder, size_bytes)
                VALUES (:tenant, :org, :status, :storageType, :sourceType, :isFolder, :size)
                """,
                new MapSqlParameterSource()
                        .addValue("tenant", tenant)
                        .addValue("org", org)
                        .addValue("status", status)
                        .addValue("storageType", storageType)
                        .addValue("sourceType", sourceType)
                        .addValue("isFolder", isFolder)
                        .addValue("size", sizeBytes));
    }

    private void deleteProbes() {
        jdbc.getJdbcOperations().update("DELETE FROM storage.storage WHERE size_bytes = " + PROBE_BYTES);
    }

    private Totals run(String sql, String paramName, String paramValue) {
        Map<String, Object> row = jdbc.queryForMap(sql, new MapSqlParameterSource(paramName, paramValue));
        List<Object> values = List.copyOf(row.values());
        return new Totals(((Number) values.get(0)).longValue(), ((Number) values.get(1)).longValue());
    }

    private record Totals(long bytes, long count) {
    }

    @Nested
    @DisplayName("the two categories partition what a scope holds")
    class Partition {

        @Test
        @DisplayName("tenant scope: FILES + STEP_OUTPUTS equal every held row, byte for byte and row for row")
        void tenantCategoriesAddUp() {
            Totals files = run(StorageReconciliationQueries.FILES, "tid", TENANT);
            Totals stepOutputs = run(StorageReconciliationQueries.STEP_OUTPUTS, "tid", TENANT);
            Totals total = run(StorageReconciliationQueries.TOTAL_ACTIVE_BYTES, "tid", TENANT);

            assertThat(files.bytes() + stepOutputs.bytes())
                    .as("bytes in no category are invisible on a billed dimension: this is the 16 GB "
                            + "a production tenant could not see")
                    .isEqualTo(total.bytes());
            assertThat(files.count() + stepOutputs.count())
                    .as("a row counted twice inflates the gauge as silently as a missing one deflates it")
                    .isEqualTo(total.count());
        }

        @Test
        @DisplayName("org scope: the same, and the two scopes classify the same rows the same way")
        void orgCategoriesAddUpAndMatchTheTenantAnswer() {
            Totals files = run(StorageReconciliationQueries.FILES_BY_ORG, "oid", ORG);
            Totals stepOutputs = run(StorageReconciliationQueries.STEP_OUTPUTS_BY_ORG, "oid", ORG);
            Totals total = run(StorageReconciliationQueries.TOTAL_ACTIVE_BYTES_BY_ORG, "oid", ORG);

            assertThat(files.bytes() + stepOutputs.bytes()).isEqualTo(total.bytes());
            assertThat(files.count() + stepOutputs.count()).isEqualTo(total.count());

            // The seeded rows carry both a tenant and an org, exactly as production rows do. The
            // org predicate was fixed once while the tenant copy kept losing every S3-backed file;
            // they must not be able to drift again.
            assertThat(files).isEqualTo(run(StorageReconciliationQueries.FILES, "tid", TENANT));
        }

        @Test
        @DisplayName("an S3-backed step output counts as a FILE, the row shape that carried 15 GB")
        void s3StepOutputIsCountedAsAFile() {
            Totals before = run(StorageReconciliationQueries.FILES, "tid", TENANT);

            insert(TENANT, ORG, "ACTIVE", "S3_FILE", "STEP_OUTPUT", PROBE_BYTES, false);
            try {
                assertThat(run(StorageReconciliationQueries.FILES, "tid", TENANT).bytes() - before.bytes())
                        .isEqualTo(PROBE_BYTES);
            } finally {
                deleteProbes();
            }
        }

        @Test
        @DisplayName("a DELETED row reaches neither category: those bytes are not held any more")
        void deletedRowsAreExcluded() {
            Totals filesBefore = run(StorageReconciliationQueries.FILES, "tid", TENANT);
            Totals stepBefore = run(StorageReconciliationQueries.STEP_OUTPUTS, "tid", TENANT);

            insert(TENANT, ORG, "DELETED", "S3_FILE", "STEP_OUTPUT", PROBE_BYTES, false);
            insert(TENANT, ORG, "DELETED", "JSON", "STEP_OUTPUT", PROBE_BYTES, false);
            try {
                assertThat(run(StorageReconciliationQueries.FILES, "tid", TENANT)).isEqualTo(filesBefore);
                assertThat(run(StorageReconciliationQueries.STEP_OUTPUTS, "tid", TENANT)).isEqualTo(stepBefore);
            } finally {
                deleteProbes();
            }
        }

        @Test
        @DisplayName("another tenant's and another org's rows never leak in")
        void otherScopesAreExcluded() {
            Totals filesBefore = run(StorageReconciliationQueries.FILES, "tid", TENANT);
            Totals orgFilesBefore = run(StorageReconciliationQueries.FILES_BY_ORG, "oid", ORG);

            insert(OTHER_TENANT, OTHER_ORG, "ACTIVE", "S3_FILE", "STEP_OUTPUT", PROBE_BYTES, false);
            try {
                assertThat(run(StorageReconciliationQueries.FILES, "tid", TENANT)).isEqualTo(filesBefore);
                assertThat(run(StorageReconciliationQueries.FILES_BY_ORG, "oid", ORG)).isEqualTo(orgFilesBefore);
            } finally {
                deleteProbes();
            }
        }

        @Test
        @DisplayName("a manual folder is in neither category and in no item count")
        void manualFoldersAreNotCounted() {
            Totals filesBefore = run(StorageReconciliationQueries.FILES, "tid", TENANT);
            Totals stepBefore = run(StorageReconciliationQueries.STEP_OUTPUTS, "tid", TENANT);
            Totals totalBefore = run(StorageReconciliationQueries.TOTAL_ACTIVE_BYTES, "tid", TENANT);

            // A folder is a sentinel with no payload, so it moves no bytes either way. Counting it
            // as an ITEM would tell the user they had stored one more step output per folder they
            // made, and the count would then drift down forever, because creating a folder credits
            // no counter while deleting one debits an item.
            insert(TENANT, ORG, "ACTIVE", "JSON", "FOLDER", PROBE_BYTES, true);
            try {
                assertThat(run(StorageReconciliationQueries.FILES, "tid", TENANT)).isEqualTo(filesBefore);
                assertThat(run(StorageReconciliationQueries.STEP_OUTPUTS, "tid", TENANT)).isEqualTo(stepBefore);
                assertThat(run(StorageReconciliationQueries.TOTAL_ACTIVE_BYTES, "tid", TENANT)).isEqualTo(totalBefore);
            } finally {
                deleteProbes();
            }
        }
    }

    @Nested
    @DisplayName("the org counter clamps at zero")
    class OrgClamp {

        private static final String CATEGORY = "STEP_OUTPUTS";

        @BeforeEach
        void clearCounters() {
            jdbc.getJdbcOperations().execute("TRUNCATE storage.org_storage_breakdown");
        }

        @Test
        @DisplayName("a debit larger than the row clamps to zero instead of granting free storage")
        void clampsWhenDeltaExceedsCurrent() {
            seedCounter(500L, 2);

            increment(-2_000L, -10);

            assertThat(usedBytes())
                    .as("would have been -1500, which canStore reads as room to spare")
                    .isZero();
            assertThat(itemCount()).isZero();
        }

        @Test
        @DisplayName("the INSERT branch clamps too: a debit on a bucket that was never credited")
        void clampsOnFirstInsert() {
            // Exactly the shape the classification bug produced: FILES credited on save,
            // STEP_OUTPUTS debited on delete, so the first touch of STEP_OUTPUTS was negative. The
            // tenant twin has carried this clamp since V184; the org table had neither it nor a
            // CHECK constraint.
            increment(-500L, -1);

            assertThat(usedBytes()).isZero();
            assertThat(itemCount()).isZero();
        }

        @Test
        @DisplayName("credits and ordinary debits are untouched")
        void ordinaryDeltasAreUnchanged() {
            seedCounter(1_000L, 5);

            increment(250L, 2);
            increment(-400L, -3);

            assertThat(usedBytes()).isEqualTo(850L);
            assertThat(itemCount()).isEqualTo(4);
        }

        private void seedCounter(long usedBytes, int itemCount) {
            jdbc.update("""
                    INSERT INTO storage.org_storage_breakdown
                        (organization_id, category, used_bytes, item_count, calculated_at)
                    VALUES (:org, :category, :usedBytes, :itemCount, now())
                    """,
                    new MapSqlParameterSource()
                            .addValue("org", ORG)
                            .addValue("category", CATEGORY)
                            .addValue("usedBytes", usedBytes)
                            .addValue("itemCount", itemCount));
        }

        private void increment(long deltaBytes, int deltaCount) {
            jdbc.update(orgIncrementSql, new MapSqlParameterSource()
                    .addValue("organizationId", ORG)
                    .addValue("category", CATEGORY)
                    .addValue("deltaBytes", deltaBytes)
                    .addValue("deltaCount", deltaCount));
        }

        private long usedBytes() {
            return queryCounter("used_bytes");
        }

        private long itemCount() {
            return queryCounter("item_count");
        }

        private long queryCounter(String column) {
            Long value = jdbc.queryForObject(
                    "SELECT " + column + " FROM storage.org_storage_breakdown "
                            + "WHERE organization_id = :org AND category = :category",
                    new MapSqlParameterSource().addValue("org", ORG).addValue("category", CATEGORY),
                    Long.class);
            return value != null ? value : -1L;
        }
    }

    @Nested
    @DisplayName("the nightly pass enumerates the scopes that need it")
    class ScopeEnumeration {

        @BeforeEach
        void clearCounters() {
            jdbc.getJdbcOperations().execute("TRUNCATE storage.tenant_storage_breakdown");
            jdbc.getJdbcOperations().execute("TRUNCATE storage.org_storage_breakdown");
        }

        @Test
        @DisplayName("a scope holding rows but no breakdown row IS enumerated")
        void findsScopesThatWereNeverReconciled() {
            // Taking the list from the breakdown table alone could only ever return scopes already
            // visited, so one that had never been reconciled never would be. Production
            // 2026-09-18: 31 organizations and 30 tenants were in exactly that state.
            assertThat(tenants()).contains(TENANT);
            assertThat(orgs()).contains(ORG);
        }

        @Test
        @DisplayName("a scope with a breakdown row and no storage row is still enumerated")
        void keepsScopesThatHoldOnlyCounters() {
            jdbc.update("""
                    INSERT INTO storage.tenant_storage_breakdown
                        (tenant_id, category, used_bytes, item_count, calculated_at)
                    VALUES ('tenant-counters-only-test', 'EXECUTION_DATA', 10, 1, now())
                    """, new MapSqlParameterSource());

            assertThat(tenants()).contains("tenant-counters-only-test");
        }

        @Test
        @DisplayName("each scope appears once, however many rows or categories it has")
        void scopesAreNotDuplicated() {
            insert(TENANT, ORG, "ACTIVE", "S3_FILE", "STEP_OUTPUT", PROBE_BYTES, false);
            jdbc.update("""
                    INSERT INTO storage.tenant_storage_breakdown
                        (tenant_id, category, used_bytes, item_count, calculated_at)
                    VALUES (:tenant, 'FILES', 10, 1, now()), (:tenant, 'STEP_OUTPUTS', 20, 2, now())
                    """, new MapSqlParameterSource("tenant", TENANT));
            try {
                // UNION, not UNION ALL. A duplicated scope means reconciling it twice a night,
                // which is 5 extra HTTP calls per duplicate to sibling services.
                assertThat(tenants()).containsOnlyOnce(TENANT);
            } finally {
                deleteProbes();
            }
        }

        @Test
        @DisplayName("system tenants are left out: they are exempt from quota anyway")
        void systemTenantsAreExcluded() {
            insert(SYSTEM_TENANT, ORG, "ACTIVE", "S3_FILE", "STEP_OUTPUT", PROBE_BYTES, false);
            try {
                assertThat(tenants())
                        .as("StorageService.validateQuota already refuses to enforce a quota on "
                                + "an underscore-prefixed tenant, so reconciling it buys nothing")
                        .doesNotContain(SYSTEM_TENANT);
            } finally {
                deleteProbes();
            }
        }

        @Test
        @DisplayName("a system tenant is dropped even when it already HAS breakdown rows")
        void systemTenantsAreExcludedFromTheBreakdownArmToo() {
            // The filter is on both arms of the union, so this is a removal as well as an
            // addition: a system tenant that accumulated breakdown rows (trackSave books them for
            // every tenant, only quota VALIDATION is skipped) stops being maintained. Harmless,
            // because no quota is enforced on it and nobody reads its storage page, but it is a
            // deliberate drop rather than a side effect, so it is pinned here.
            jdbc.update("""
                    INSERT INTO storage.tenant_storage_breakdown
                        (tenant_id, category, used_bytes, item_count, calculated_at)
                    VALUES (:tenant, 'FILES', 4096, 1, now())
                    """, new MapSqlParameterSource("tenant", SYSTEM_TENANT));

            assertThat(tenants()).doesNotContain(SYSTEM_TENANT);
        }

        @Test
        @DisplayName("a NULL organization_id never reaches the org list")
        void nullOrgIsExcluded() {
            insert(TENANT, null, "ACTIVE", "S3_FILE", "STEP_OUTPUT", PROBE_BYTES, false);
            try {
                assertThat(orgs()).doesNotContainNull();
            } finally {
                deleteProbes();
            }
        }

        private List<String> tenants() {
            return jdbc.getJdbcOperations().queryForList(
                    StorageReconciliationQueries.TENANTS_TO_RECONCILE, String.class);
        }

        private List<String> orgs() {
            return jdbc.getJdbcOperations().queryForList(
                    StorageReconciliationQueries.ORGS_TO_RECONCILE, String.class);
        }
    }

    // ================================================================================
    // Harness
    // ================================================================================

    private static String shippedSql(Class<?> repository, String method, Class<?>... params)
            throws NoSuchMethodException {
        Query query = repository.getMethod(method, params).getAnnotation(Query.class);
        if (query == null || !query.nativeQuery()) {
            throw new IllegalStateException(method + " no longer carries a native @Query. If the SQL "
                    + "moved, move this test with it: it is the only place it runs against a real engine.");
        }
        return query.value();
    }

    private static void requireDatabaseOnCi() {
        if (URL != null && !URL.isBlank()) {
            return;
        }
        boolean onCi = System.getenv("CI") != null && !System.getenv("CI").isBlank();
        if (onCi) {
            throw new IllegalStateException(
                    "ORCHESTRATOR_TEST_PG_URL is unset on CI. This class must execute there: it is "
                            + "the only test that runs the storage category predicates against a real "
                            + "engine, and their known failure mode is a plausible wrong answer (a row "
                            + "shape matching neither category, so 16 GB reported nowhere), never an "
                            + "error. Restore the env block on the workflow step that runs it, and keep "
                            + "that step in a job carrying the postgres service.");
        }
        Assumptions.abort(
                "no scratch Postgres: set ORCHESTRATOR_TEST_PG_URL to run this locally "
                        + "(CI always sets it)");
    }

    private static void awaitDatabase() {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            try (Connection ignored = DriverManager.getConnection(URL, USER, PASSWORD)) {
                return;
            } catch (Exception e) {
                last = new IllegalStateException("cannot reach " + URL, e);
                try {
                    Thread.sleep(1_000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
        }
        throw last;
    }
}
