package com.apimarketplace.catalog.seed;

import com.apimarketplace.catalog.bundle.ApiCatalogGenerationPriceApplier;
import com.apimarketplace.catalog.domain.ApiCatalogBundleEntity;
import com.apimarketplace.catalog.repository.ApiCatalogBundleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The seed's hand-written SQL against REAL PostgreSQL: which rows it may write
 * a generation descriptor into, and how the version marker behaves.
 *
 * <p>These are the branches a mocked JDBC template cannot prove, because the
 * whole rule lives in a WHERE clause: an endpoint is the seed's to describe only
 * while its API is still {@code source='import'}, the state the shipped SQL dump
 * leaves it in. Once the cloud bundle has written that API it is
 * {@code 'bundle'}, and an API created on this install is {@code 'custom'};
 * neither may be rewritten by a seed that ships in the image.
 *
 * <p>Same harness and reasoning as {@code ApiCatalogBundleSqlIntegrationTest}:
 * Testcontainers rather than the H2 harness, DDL restated in
 * {@code schema-catalog-bundle-postgres.sql}, skipped (not failed) with no
 * Docker daemon.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Generation seed SQL - real-Postgres integration (Testcontainers)")
class GenerationSeedBootstrapPostgresTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("generation_seed_it")
            .withUsername("postgres")
            .withPassword("postgres");

    private static final UUID TOOL = UUID.fromString("11111111-1111-4111-8111-111111111111");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;
    private DataSourceTransactionManager txManager;
    private ApiCatalogBundleRepository bundleRepository;
    private ApiCatalogGenerationPriceApplier priceApplier;

    @BeforeAll
    void initSchema() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        ds.setDriverClassName(POSTGRES.getDriverClassName());
        try (Connection connection = ds.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("schema-catalog-bundle-postgres.sql"));
        }
        jdbc = new JdbcTemplate(ds);
        named = new NamedParameterJdbcTemplate(jdbc);
        txManager = new DataSourceTransactionManager(ds);
    }

    @BeforeEach
    void reset() {
        jdbc.execute("""
                TRUNCATE catalog.tool_credentials, catalog.tool_responses,
                         catalog.api_tool_parameters, catalog.api_tools, catalog.apis,
                         catalog.api_subcategories, catalog.api_categories,
                         catalog.credentials CASCADE""");
        jdbc.update("UPDATE catalog.generation_seed_state SET applied_version = NULL WHERE id = 1");
        bundleRepository = mock(ApiCatalogBundleRepository.class);
        priceApplier = mock(ApiCatalogGenerationPriceApplier.class);
        when(bundleRepository.findActiveMetadata()).thenReturn(List.of());
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private void seedApiWithTool(String source, String platformCredentialName) {
        UUID apiId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        UUID subcategoryId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO catalog.api_categories (id, name, slug) VALUES (?, 'Media', 'media')
                """, categoryId);
        jdbc.update("""
                INSERT INTO catalog.api_subcategories (id, category_id, name, slug)
                VALUES (?, ?, 'Video', 'video')
                """, subcategoryId, categoryId);
        jdbc.update("""
                INSERT INTO catalog.apis (id, created_by, api_name, api_slug, description,
                    category_id, subcategory_id, base_url, auth_type, status, is_public,
                    is_active, source, platform_credential_name, icon_slug)
                VALUES (?, 'SYSTEM', 'Seedance', 'seedance', 'video', ?, ?, 'https://api.example',
                    'apiKey', 'APPROVED', true, true, ?, ?, 'seedance')
                """, apiId, categoryId, subcategoryId, source, platformCredentialName);
        jdbc.update("""
                INSERT INTO catalog.api_tools (id, api_id, tool_slug, description, method,
                    endpoint, status, is_active)
                VALUES (?, ?, 'create-video', 'creates a video', 'POST', '/tasks', 'ACTIVE', true)
                """, TOOL, apiId);
    }

    private static Resource seed(long version) {
        String json = """
                {
                  "version": %d,
                  "endpoints": [
                    {
                      "apiToolId": "%s",
                      "endpointName": "create_video_task",
                      "sourceFile": "seedance.json",
                      "generationSpec": {
                        "kind": "video",
                        "assetPath": "content.video_url",
                        "paramMap": { "prompt": "prompt" },
                        "models": [ { "id": "seedance-2.0", "capabilities": ["prompt"],
                                      "price": { "unit": "call", "baseCredits": 120 } } ]
                      }
                    }
                  ]
                }
                """.formatted(version, TOOL);
        return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8));
    }

    private GenerationSeedBootstrap bootstrap(long version) {
        return new GenerationSeedBootstrap(named, bundleRepository, priceApplier,
                objectMapper, txManager, seed(version));
    }

    private String storedSpec() {
        List<String> found = jdbc.query(
                "SELECT generation_spec::text AS spec FROM catalog.api_tools WHERE id = ?",
                (rs, n) -> rs.getString("spec"), TOOL);
        return found.isEmpty() ? null : found.get(0);
    }

    private Long markerVersion() {
        return jdbc.queryForObject(
                "SELECT applied_version FROM catalog.generation_seed_state WHERE id = 1", Long.class);
    }

    // ── branches ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a dump-seeded endpoint gets its descriptor, and the marker records the version")
    void writesTheDescriptorOnADumpSeededEndpoint() throws Exception {
        seedApiWithTool("import", "seedance");

        GenerationSeedBootstrap.Result result = bootstrap(2012).seedNow();

        assertThat(result.descriptorsApplied()).isEqualTo(1);
        assertThat(storedSpec()).contains("seedance-2.0").contains("\"kind\": \"video\"");
        assertThat(markerVersion()).isEqualTo(2012L);
    }

    @Test
    @DisplayName("an endpoint the cloud bundle owns keeps the cloud's descriptor")
    void leavesABundleOwnedEndpointAlone() throws Exception {
        seedApiWithTool("bundle", "seedance");

        GenerationSeedBootstrap.Result result = bootstrap(2012).seedNow();

        // The bundle is authoritative for the rows it wrote. Rewriting one here
        // would stick, because the applier treats an already-active version as
        // ALREADY_APPLIED and never re-merges its descriptors.
        assertThat(storedSpec()).isNull();
        assertThat(result.descriptorsSkipped()).isEqualTo(1);
        assertThat(result.descriptorsApplied()).isZero();
    }

    @Test
    @DisplayName("an API created on this install is never rewritten by a shipped seed")
    void leavesALocallyCreatedApiAlone() throws Exception {
        seedApiWithTool("custom", "seedance");

        GenerationSeedBootstrap.Result result = bootstrap(2012).seedNow();

        assertThat(storedSpec()).isNull();
        assertThat(result.descriptorsSkipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("re-running the same version rewrites nothing and still offers the prices")
    void isIdempotentAcrossBoots() throws Exception {
        seedApiWithTool("import", "seedance");
        bootstrap(2012).seedNow();
        jdbc.update("UPDATE catalog.api_tools SET generation_spec = '{\"local\":true}'::jsonb WHERE id = ?", TOOL);

        GenerationSeedBootstrap.Result second = bootstrap(2012).seedNow();

        assertThat(second.descriptorsAlreadyApplied()).isTrue();
        assertThat(storedSpec()).isEqualTo("{\"local\": true}");
        assertThat(markerVersion()).isEqualTo(2012L);
        verify(priceApplier, org.mockito.Mockito.times(2))
                .apply(any(), any(), anyString());
    }

    @Test
    @DisplayName("a later release refreshes the descriptor an earlier one wrote")
    void aBumpedVersionRefreshes() throws Exception {
        seedApiWithTool("import", "seedance");
        bootstrap(2012).seedNow();
        jdbc.update("UPDATE catalog.api_tools SET generation_spec = '{\"stale\":true}'::jsonb WHERE id = ?", TOOL);

        bootstrap(2013).seedNow();

        assertThat(storedSpec()).contains("seedance-2.0");
        assertThat(markerVersion()).isEqualTo(2013L);
    }

    @Test
    @DisplayName("once a signed bundle has landed, the seed writes nothing at all")
    void standsDownForTheBundle() throws Exception {
        seedApiWithTool("import", "seedance");
        // The bootstrap only asks WHETHER a bundle owns the catalog, so it reads
        // the payload-free projection: the entity finder would pull ~24 MB of
        // gzip into heap on every boot to answer a yes/no question.
        when(bundleRepository.findActiveMetadata()).thenReturn(List.of(org.mockito.Mockito.mock(ApiCatalogBundleRepository.ActiveBundleMeta.class)));

        GenerationSeedBootstrap.Result result = bootstrap(2012).seedNow();

        assertThat(result.supersededByBundle()).isTrue();
        assertThat(storedSpec()).isNull();
        assertThat(markerVersion()).isNull();
    }

    @Test
    @DisplayName("the starting price is keyed on the endpoint and the integration the catalog names")
    @SuppressWarnings("unchecked")
    void pricesAreKeyedOnTheCatalogsOwnIntegrationName() throws Exception {
        seedApiWithTool("import", "seedance");

        bootstrap(2012).seedNow();

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(priceApplier).apply(captor.capture(), any(), anyString());
        assertThat(captor.getValue()).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("integrationName", "seedance")
                .containsEntry("apiToolId", TOOL.toString())
                .containsEntry("modelId", "seedance-2.0")
                .containsEntry("baseCredits", "120"));
    }
}
