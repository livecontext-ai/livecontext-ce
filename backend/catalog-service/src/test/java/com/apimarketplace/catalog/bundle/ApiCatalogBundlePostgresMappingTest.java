package com.apimarketplace.catalog.bundle;

import com.apimarketplace.catalog.domain.ApiCatalogBundleEntity;
import com.apimarketplace.catalog.repository.ApiCatalogBundleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Writes and reads {@link ApiCatalogBundleEntity} against a REAL PostgreSQL,
 * because H2 cannot see the failure this guards.
 *
 * <p>The generation-prices column was first mapped as {@code jsonb} on a
 * {@code String} without {@code @JdbcTypeCode(SqlTypes.JSON)}. Hibernate then
 * binds the parameter as varchar, and Postgres refuses a varchar-to-jsonb
 * assignment (42804) - which would have failed EVERY write of this table, on the
 * cloud and on every CE, for a column nothing even queries into. H2 in
 * PostgreSQL mode accepts that same mapping, so the whole H2-backed suite stayed
 * green. The column is now {@code text}; this test is what keeps the mapping
 * honest if anyone changes it back.
 *
 * <p><b>It must not be able to skip itself in CI.</b> The runners have no Docker
 * socket, so a container-only test would skip there and report green having
 * verified nothing - the exact green-by-absence this class exists to remove. It
 * therefore prefers the Postgres CI hands over through
 * {@code CATALOG_TEST_PG_URL} (same contract as {@code MIGRATION_TEST_PG_URL} in
 * migration-service) and only falls back to a container on a developer machine.
 *
 * <p><b>Known limit.</b> Hibernate creates the table from the entity here, so
 * this pins the ENTITY mapping, not entity-versus-V476 agreement: flipping the
 * migration alone back to {@code jsonb} would still pass. Pinning that needs the
 * migration replayed, which is migration-service's job.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("bundle-pg-slice")
@DisplayName("ApiCatalogBundleEntity - round trip on real PostgreSQL")
class ApiCatalogBundlePostgresMappingTest {

    /**
     * A schema of this test's own, NOT {@code catalog}.
     *
     * <p>{@code ddl-auto=create-drop} plus an {@code @EntityScan} of the whole
     * domain package means this class creates and then DROPS every entity table
     * it can see. On the CI job's shared database that would be a dozen
     * production-named {@code catalog.*} tables appearing and vanishing under
     * the other steps. Giving this class a schema of its own keeps that
     * contained.
     */
    private static final String SCHEMA = "bundle_mapping_it";

    private static PostgreSQLContainer<?> container;
    private static String url;
    private static String user;
    private static String password;

    @BeforeAll
    static void openPostgres() {
        String ciUrl = System.getenv("CATALOG_TEST_PG_URL");
        if (ciUrl != null && !ciUrl.isBlank()) {
            // CI handed us one: the test MUST run, no assumption, no skip.
            url = ciUrl;
            user = envOr("CATALOG_TEST_PG_USER", "postgres");
            password = envOr("CATALOG_TEST_PG_PASSWORD", "postgres");
            createCatalogSchema();
            return;
        }
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "no CATALOG_TEST_PG_URL and no Docker: nothing to run against");
        container = new PostgreSQLContainer<>("postgres:17-alpine")
                .withDatabaseName("bundle_mapping_it")
                .withUsername("postgres")
                .withPassword("postgres");
        container.start();
        url = container.getJdbcUrl();
        user = container.getUsername();
        password = container.getPassword();
        createCatalogSchema();
    }

    /**
     * Creates the schema this test owns. The reader takes its qualifier from
     * {@code hibernate.default_schema}, the same property production sets, so
     * pointing that at a schema of our own is enough to reach these tables.
     */
    private static void createCatalogSchema() {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(url, user, password);
             java.sql.Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("could not prepare the catalog schema", e);
        }
    }

    @org.junit.jupiter.api.AfterEach
    void removeCommittedFixtures() {
        // Only the NOT_SUPPORTED tests commit; the rest roll back on their own.
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(url, user, password);
             java.sql.Statement st = c.createStatement()) {
            // The range this class owns, not a list of the versions it uses today:
            // a new committed fixture outside that list would leave a second active
            // row behind, and the Hibernate-created schema carries no partial
            // unique index to stop it - the next test's "exactly one active" would
            // then fail depending on method order.
            st.execute("DELETE FROM " + SCHEMA + ".api_catalog_bundles WHERE version BETWEEN 50 AND 99");
        } catch (java.sql.SQLException ignored) {
            // The table may not exist yet on the first run; nothing to clean.
        }
    }

    @AfterAll
    static void closePostgres() {
        if (container != null) {
            container.stop();
        }
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return v != null && !v.isBlank() ? v : fallback;
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        // No currentSchema: production does not set one either. The reader
        // qualifies its statements with the schema Hibernate is configured with,
        // which is what makes this test reach its own tables.
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @SpringBootConfiguration
    @Profile("bundle-pg-slice")
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = ApiCatalogBundleEntity.class)
    @EnableJpaRepositories(
            basePackageClasses = ApiCatalogBundleRepository.class,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = ApiCatalogBundleRepository.class))
    static class SliceConfig {

        /** The sliced reader is plain JDBC, so the slice can build it directly. */
        @org.springframework.context.annotation.Bean
        ApiCatalogBundleChunkReader chunkReader(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
            return new ApiCatalogBundleChunkReader(jdbcTemplate, SCHEMA);
        }
    }

    @Autowired private ApiCatalogBundleRepository repo;
    @Autowired private ApiCatalogBundleChunkReader chunkReader;

    private static ApiCatalogBundleEntity row(long version, String prices) {
        ApiCatalogBundleEntity e = new ApiCatalogBundleEntity();
        e.setVersion(version);
        e.setSchemaVersion(1);
        e.setChecksum("c".repeat(64));
        e.setSignature("sig");
        e.setSigningKeyId("k1");
        e.setIssuer("cloud");
        e.setApiCount(1);
        e.setToolCount(1);
        e.setRawBytesSize(10);
        e.setGenerationPrices(prices);
        e.setActive(true);
        e.setImportedAt(Instant.now());
        return e;
    }

    @Test
    @DisplayName("Stored prices survive a write and a read on Postgres, which is where the varchar binding would fail")
    void storedPricesRoundTrip() {
        repo.saveAndFlush(row(1L, "[{\"toolSlug\":\"flux-generate\",\"credits\":12}]"));

        assertThat(repo.findFirstByActiveTrue().orElseThrow().getGenerationPrices())
                .contains("flux-generate");
    }

    @Test
    @DisplayName("An empty capture and a NULL are both writable and stay distinguishable")
    void emptyAndNullAreBothWritable() {
        // The distinction the conditional-GET path depends on: "[]" means
        // captured-and-says-nothing, NULL means never captured.
        repo.saveAndFlush(row(2L, "[]"));
        repo.saveAndFlush(row(3L, null));

        List<ApiCatalogBundleRepository.BundleSummary> all = repo.findAllSummariesNewestFirst();
        assertThat(all).hasSize(2);
        assertThat(repo.findByVersion(2L).orElseThrow().getGenerationPrices()).isEqualTo("[]");
        assertThat(repo.findByVersion(3L).orElseThrow().getGenerationPrices()).isNull();
    }

    @Test
    @DisplayName("The payload-free projection reports pricesStored correctly on Postgres too")
    void projectionFlagsOnPostgres() {
        repo.saveAndFlush(row(4L, "[]"));

        ApiCatalogBundleRepository.ActiveBundleMeta meta = repo.findActiveMetadata().get(0);

        assertThat(meta.getPricesStored()).isEqualTo(1);
        assertThat(meta.getChecksum()).isEqualTo("c".repeat(64));
    }

    @Test
    @DisplayName("The serving projection returns every envelope field from Postgres, so a mis-named getter cannot pass")
    void servingViewCarriesTheWholeEnvelope() {
        // A Spring Data interface projection binds by getter name. A rename or a
        // typo on any of these nine is invisible to a mocked repository - the
        // stub returns whatever the test says - and only shows up as a failed
        // query or a null field once a real EntityManager resolves it.
        ApiCatalogBundleEntity stored = row(7L, "[]");
        stored.setSchemaVersion(3);
        stored.setSignature("sig-7");
        stored.setSigningKeyId("key-7");
        stored.setIssuer("issuer-7");
        stored.setApiCount(11);
        stored.setToolCount(22);
        stored.setRawBytesSize(33);
        repo.saveAndFlush(stored);

        List<ApiCatalogBundleRepository.ServingView> active = repo.findActiveServingView();
        assertThat(active).hasSize(1);
        ApiCatalogBundleRepository.ServingView view = active.get(0);
        assertThat(view.getVersion()).isEqualTo(7L);
        assertThat(view.getSchemaVersion()).isEqualTo(3);
        assertThat(view.getChecksum()).isEqualTo("c".repeat(64));
        assertThat(view.getSignature()).isEqualTo("sig-7");
        assertThat(view.getSigningKeyId()).isEqualTo("key-7");
        assertThat(view.getIssuer()).isEqualTo("issuer-7");
        assertThat(view.getApiCount()).isEqualTo(11);
        assertThat(view.getToolCount()).isEqualTo(22);
        assertThat(view.getRawBytesSize()).isEqualTo(33);

        assertThat(repo.findServingViewByVersion(7L)).hasSize(1);
        assertThat(repo.findServingViewByVersion(999L))
                .as("an unknown version must come back empty, not as a row of nulls")
                .isEmpty();
    }

    // The sliced reader carries no transaction of its own, deliberately: in
    // production it runs with none, one slice per statement, so the connection
    // goes back to the pool between slices. These three therefore run with the
    // test transaction suspended (NOT_SUPPORTED), which is that same shape, and
    // commit their fixtures so they clean up after themselves.
    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName("The production stream over the real reader reassembles the payload exactly, so a 0-based substring is caught")
    void productionStreamReassemblesExactly() throws java.io.IOException {
        // This must drive ChunkedPayloadInputStream, not a hand-written loop.
        // A loop that advances by the REQUESTED length hides a 0-based
        // substring: every slice would lose its last byte and the next would
        // start one byte earlier, so the pieces still concatenate cleanly. The
        // stream advances by the RETURNED length, exactly as production does,
        // so the same mistake duplicates a byte at every slice boundary and the
        // reassembled payload no longer matches.
        byte[] payload = new byte[7_000];
        new java.util.Random(11).nextBytes(payload);
        ApiCatalogBundleEntity row = row(50L, null);
        row.setPayloadGz(payload);
        repo.saveAndFlush(row);

        assertThat(chunkReader.payloadLength(50L)).isEqualTo(payload.length);

        byte[] streamed;
        try (ChunkedPayloadInputStream in =
                     new ChunkedPayloadInputStream(chunkReader, 50L, payload.length, 1024)) {
            streamed = in.readAllBytes();
        }

        assertThat(streamed)
                .as("every byte once, in order, through the same code the download uses")
                .isEqualTo(payload);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName("A slice returns exactly what was asked for, so the caller's offset arithmetic stays honest")
    void sliceReturnsTheRequestedWindow() {
        byte[] payload = new byte[3_000];
        new java.util.Random(12).nextBytes(payload);
        ApiCatalogBundleEntity row = row(53L, null);
        row.setPayloadGz(payload);
        repo.saveAndFlush(row);

        byte[] first = chunkReader.readChunk(53L, 0, 1024);
        byte[] second = chunkReader.readChunk(53L, 1024, 1024);

        assertThat(first).hasSize(1024).isEqualTo(java.util.Arrays.copyOfRange(payload, 0, 1024));
        assertThat(second).hasSize(1024).isEqualTo(java.util.Arrays.copyOfRange(payload, 1024, 2048));
        assertThat(chunkReader.readChunk(53L, 2048, 1024))
                .as("the tail is shorter than a full slice")
                .hasSize(952);
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName("A row with no payload reports length zero instead of failing")
    void missingPayloadReportsZeroLength() {
        repo.saveAndFlush(row(51L, null));

        assertThat(chunkReader.payloadLength(51L)).isZero();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName("An unknown version measures zero instead of throwing, so a missing bundle is a 404 not a 500")
    void anUnknownVersionMeasuresZero() {
        // This is the whole reason payloadLength uses queryForList: queryForObject
        // throws on an empty result set, which would turn "no such bundle" into a
        // 500 for anyone asking for a version that never existed.
        assertThat(chunkReader.payloadLength(9_999L)).isZero();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName("An EMPTY payload measures zero too, not only a NULL one")
    void anEmptyPayloadAlsoMeasuresZero() {
        // The service treats "absent" and "empty" as one branch. That is only
        // sound because both really do measure 0 here: octet_length('') is 0 and
        // COALESCE covers the NULL. Nothing else asserted the empty case.
        ApiCatalogBundleEntity empty = row(53L, null);
        empty.setPayloadGz(new byte[0]);
        repo.saveAndFlush(empty);

        assertThat(chunkReader.payloadLength(53L)).isZero();
    }

    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName("Reading past the end returns nothing rather than an error")
    void readingPastTheEndIsEmpty() {
        ApiCatalogBundleEntity row = row(52L, null);
        row.setPayloadGz(new byte[]{1, 2, 3});
        repo.saveAndFlush(row);

        assertThat(chunkReader.readChunk(52L, 100, 1024)).isEmpty();
    }
}
