package com.apimarketplace.catalog.bundle;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the hand-written bundle SQL (the ~12 statements of
 * {@link ApiCatalogMergeService} and the 6 snapshot queries of
 * {@link ApiCatalogSnapshotReader}) against a REAL PostgreSQL database, plus a
 * full cross-side wire round trip through
 * {@link ApiCatalogBundlePayload#canonicalBytes}.
 *
 * <p><b>Why Testcontainers and not the H2 harness (schema-h2.sql):</b> H2
 * 2.3.232 in {@code MODE=PostgreSQL} cannot parse {@code ON CONFLICT (…) DO
 * UPDATE} (the apis/api_tools/credentials upserts - the core of the merge) nor
 * {@code INSERT … RETURNING} (category creation); verified empirically against
 * the test-classpath H2. The repo's existing pattern for Postgres-only SQL is
 * a Testcontainers PG (see {@code TypedExecutionPostgresE2ETest}, same image).
 * Unlike that test, this one does NOT run the full Flyway chain (which fails
 * on the pgvector V12/V74 ordering) - the bundle-relevant DDL is restated in
 * {@code schema-catalog-bundle-postgres.sql}, mirroring V12/V52/V82/V87/V103/
 * V107/V166/V331.
 *
 * <p>Skipped (not failed) when no Docker daemon is available.
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("API catalog bundle SQL - real-Postgres integration (Testcontainers)")
class ApiCatalogBundleSqlIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg17")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("catalog_bundle_it")
            .withUsername("postgres")
            .withPassword("postgres");

    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};
    private static final Instant SNAPSHOT_AT = Instant.parse("2026-06-10T00:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private JdbcTemplate jdbc;
    private NamedParameterJdbcTemplate named;
    private ApiCatalogMergeService mergeService;
    private ApiCatalogSnapshotReader snapshotReader;

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
        mergeService = new ApiCatalogMergeService(named, new DataSourceTransactionManager(ds));
        snapshotReader = new ApiCatalogSnapshotReader(jdbc);
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("""
                TRUNCATE catalog.tool_credentials, catalog.tool_responses,
                         catalog.api_tool_parameters, catalog.api_tools, catalog.apis,
                         catalog.api_subcategories, catalog.api_categories,
                         catalog.credentials CASCADE""");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (a) Full cross-side round trip
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(a) Cloud snapshot → canonical gzip payload → CE merge reproduces every payload-carried row (byte-identical re-snapshot)")
    void fullCrossSideRoundTrip() throws Exception {
        // Cloud dataset: 2 APIs, one with a full tool tree, plus templates.
        UUID slackApi = UUID.randomUUID();
        UUID stripeApi = UUID.randomUUID();
        UUID slackTool = UUID.randomUUID();
        UUID stripeTool = UUID.randomUUID();
        UUID paramId = UUID.randomUUID();
        UUID responseId = UUID.randomUUID();

        UUID cat = seedCategory("Communication", "communication");
        UUID sub = seedSubcategory(cat, "Email", "email");
        seedApi(slackApi, "Slack", "import", cat, sub, "slack");
        seedApi(stripeApi, "Stripe", "bundle", cat, sub, "stripe");
        seedTool(slackTool, slackApi, "send-message", "/v1/send", "slack-send");
        seedTool(stripeTool, stripeApi, "create-charge", "/v1/charges", "stripe-charge");
        seedParameter(paramId, slackTool, "channel");
        seedResponse(responseId, slackTool, "success");
        seedToolCredential(slackTool, "slack", "oauth2");
        seedTemplate("slack", "oauth2");
        seedTemplate("smtp", "primary");          // native core-node template - ships
        seedTemplate("unrelated-cred", "primary"); // backs no API, not native - must NOT ship

        // Cloud side: snapshot → canonical bytes → gzip (what gets signed).
        ApiCatalogSnapshotReader.Snapshot cloudSnapshot = snapshotReader.snapshot();
        assertThat(cloudSnapshot.apiCount()).isEqualTo(2);
        assertThat(cloudSnapshot.toolCount()).isEqualTo(2);
        byte[] canonical = ApiCatalogBundlePayload.canonicalBytes(
                7L, 1, "cloud", SNAPSHOT_AT,
                cloudSnapshot.apis(), cloudSnapshot.credentialTemplates());
        byte[] wire = ApiCatalogBundlePayload.gzip(canonical);

        // CE side: gunzip + parse exactly like ApiCatalogBundleApplier.
        Map<String, Object> root =
                objectMapper.readValue(ApiCatalogBundlePayload.gunzip(wire), JSON_MAP);
        List<Map<String, Object>> apiMaps = listOfMaps(root.get("apis"));
        List<Map<String, Object>> templateMaps = listOfMaps(root.get("credentialTemplates"));
        assertThat(apiMaps).hasSize(2);
        assertThat(templateMaps).extracting(t -> t.get("credentialName"))
                .containsExactlyInAnyOrder("slack", "smtp"); // unrelated-cred filtered out

        // Fresh CE database.
        cleanTables();
        ApiCatalogMergeService.MergeResult merge = mergeService.merge(apiMaps, templateMaps);
        assertThat(merge.failedApis()).isZero();
        assertThat(merge.skippedCustom()).isZero();
        assertThat(merge.upsertedApis()).isEqualTo(2);
        assertThat(merge.upsertedTools()).isEqualTo(2);
        assertThat(merge.upsertedTemplates()).isEqualTo(2);
        assertThat(merge.deprecatedApis()).isZero();

        // Row-for-row fidelity: re-snapshotting the merged CE catalog must
        // produce BYTE-IDENTICAL canonical payload (same version/issuer/time).
        ApiCatalogSnapshotReader.Snapshot ceSnapshot = snapshotReader.snapshot();
        byte[] ceCanonical = ApiCatalogBundlePayload.canonicalBytes(
                7L, 1, "cloud", SNAPSHOT_AT,
                ceSnapshot.apis(), ceSnapshot.credentialTemplates());
        assertThat(ceCanonical).isEqualTo(canonical);

        // CE provenance columns (not payload-carried, set by the merge).
        assertThat(jdbc.queryForList("SELECT source FROM catalog.apis", String.class))
                .containsOnly("bundle");
        assertThat(jdbc.queryForList("SELECT created_by FROM catalog.apis", String.class))
                .containsOnly("SYSTEM");
        assertThat(count("catalog.api_tool_parameters")).isEqualTo(1);
        assertThat(count("catalog.tool_responses")).isEqualTo(1);
        assertThat(count("catalog.tool_credentials")).isEqualTo(1);
        assertThat(count("catalog.credentials")).isEqualTo(2);

        // Re-applying the same payload re-resolves the tool-credential link
        // against the now-present local template (and stays idempotent).
        ApiCatalogMergeService.MergeResult again = mergeService.merge(apiMaps, templateMaps);
        assertThat(again.failedApis()).isZero();
        assertThat(count("catalog.apis")).isEqualTo(2);
        assertThat(count("catalog.api_tools")).isEqualTo(2);
        UUID localCredId = jdbc.queryForObject(
                "SELECT id FROM catalog.credentials WHERE credential_name = 'slack'", UUID.class);
        assertThat(jdbc.queryForObject(
                "SELECT credential_id FROM catalog.tool_credentials WHERE credential_name = 'slack'",
                UUID.class)).isEqualTo(localCredId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (b) UPSERT update path
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(b) Re-merging a changed payload UPDATES rows in place - never duplicates")
    void upsertUpdatePathUpdatesInPlace() {
        UUID apiId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();
        UUID paramId = UUID.randomUUID();
        Map<String, Object> tool = toolPayload(toolId, "send", "/v1/send",
                List.of(paramPayload(paramId, "channel")));
        List<Map<String, Object>> apis = List.of(apiPayload(apiId, "Slack", List.of(tool)));

        assertThat(mergeService.merge(apis, List.of()).upsertedApis()).isEqualTo(1);

        // Change one field on each level + replace the parameter.
        Map<String, Object> changedTool = toolPayload(toolId, "send", "/v2/send",
                List.of(paramPayload(paramId, "channel_id")));
        Map<String, Object> changedApi = apiPayload(apiId, "Slack", List.of(changedTool));
        changedApi.put("description", "updated description");
        List<Map<String, Object>> changed = List.of(changedApi);

        ApiCatalogMergeService.MergeResult second = mergeService.merge(changed, List.of());
        assertThat(second.upsertedApis()).isEqualTo(1);
        assertThat(second.failedApis()).isZero();

        assertThat(count("catalog.apis")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT description FROM catalog.apis WHERE id = ?", String.class, apiId))
                .isEqualTo("updated description");
        assertThat(count("catalog.api_tools")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT endpoint FROM catalog.api_tools WHERE id = ?", String.class, toolId))
                .isEqualTo("/v2/send");
        // Leaf children replaced wholesale, not accumulated.
        assertThat(count("catalog.api_tool_parameters")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT name FROM catalog.api_tool_parameters WHERE id = ?", String.class, paramId))
                .isEqualTo("channel_id");
    }

    @Test
    @DisplayName("(b2) Per-API isolation on a REAL unique collision: one API fails, the other lands")
    void perApiIsolationOnRealUniqueCollision() {
        // An import-era row already owns ('SYSTEM', 'Slack') under another UUID
        // → the bundle's INSERT of a NEW UUID with the same name violates
        // UNIQUE(created_by, api_name).
        UUID cat = seedCategory("Communication", "communication");
        UUID sub = seedSubcategory(cat, "Email", "email");
        UUID oldSlack = UUID.randomUUID();
        seedApi(oldSlack, "Slack", "import", cat, sub, "slack");

        UUID newSlack = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        ApiCatalogMergeService.MergeResult result = mergeService.merge(List.of(
                apiPayload(newSlack, "Slack", List.of()),
                apiPayload(healthy, "Stripe", List.of())), List.of());

        assertThat(result.failedApis()).isEqualTo(1);
        assertThat(result.upsertedApis()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0)).contains(newSlack.toString()).contains("Slack");
        // The healthy API landed despite the sibling failure.
        assertThat(jdbc.queryForObject(
                "SELECT api_name FROM catalog.apis WHERE id = ?", String.class, healthy))
                .isEqualTo("Stripe");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (c) Orphan soft-deprecation + resurrection
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(c) APIs absent from the bundle are soft-deprecated (with their tools), re-listed ones resurrect")
    void orphanDeprecationAndResurrection() {
        UUID apiA = UUID.randomUUID();
        UUID apiB = UUID.randomUUID();
        UUID toolA = UUID.randomUUID();
        UUID toolB = UUID.randomUUID();
        List<Map<String, Object>> both = List.of(
                apiPayload(apiA, "Alpha", List.of(toolPayload(toolA, "a", "/a", List.of()))),
                apiPayload(apiB, "Beta", List.of(toolPayload(toolB, "b", "/b", List.of()))));
        mergeService.merge(both, List.of());

        // Bundle v2 drops Beta → soft-deprecated with its tool, Alpha untouched.
        ApiCatalogMergeService.MergeResult drop = mergeService.merge(
                List.of(apiPayload(apiA, "Alpha", List.of(toolPayload(toolA, "a", "/a", List.of())))),
                List.of());
        assertThat(drop.deprecatedApis()).isEqualTo(1);
        assertThat(drop.deprecatedTools()).isEqualTo(1); // Beta's tool, counted (finding 4)
        assertThat(deprecatedAt("catalog.apis", apiB)).isNotNull();
        assertThat(deprecatedAt("catalog.api_tools", toolB)).isNotNull();
        assertThat(deprecatedAt("catalog.apis", apiA)).isNull();
        assertThat(deprecatedAt("catalog.api_tools", toolA)).isNull();
        // Soft-delete: the rows still exist (execution by UUID resolves).
        assertThat(count("catalog.apis")).isEqualTo(2);

        // Bundle v3 re-lists Beta → resurrection (deprecated_at = NULL).
        mergeService.merge(both, List.of());
        assertThat(deprecatedAt("catalog.apis", apiB)).isNull();
        assertThat(deprecatedAt("catalog.api_tools", toolB)).isNull();
    }

    @Test
    @DisplayName("(c2) Tool sweep only touches APIs deprecated in THIS sweep - a manually resurrected tool on a long-deprecated API survives")
    void orphanToolSweepDoesNotClobberResurrectedTools() {
        UUID cat = seedCategory("Communication", "communication");
        UUID sub = seedSubcategory(cat, "Email", "email");

        // Long-deprecated bundle-managed API with one manually resurrected tool.
        UUID oldApi = UUID.randomUUID();
        UUID revivedTool = UUID.randomUUID();
        seedApi(oldApi, "Legacy", "bundle", cat, sub, null);
        jdbc.update("UPDATE catalog.apis SET deprecated_at = NOW() - INTERVAL '30 days' "
                + "WHERE id = ?", oldApi);
        seedTool(revivedTool, oldApi, "revived", "/r", "legacy-revived"); // deprecated_at NULL = live

        // A live bundle-managed API absent from the bundle → THIS sweep
        // deprecates it (and its tool). The pre-fix global tool sweep matched
        // "any deprecated bundle-managed API" and would have re-deprecated
        // Legacy's resurrected tool along the way.
        UUID orphanApi = UUID.randomUUID();
        UUID orphanTool = UUID.randomUUID();
        seedApi(orphanApi, "Orphan", "bundle", cat, sub, null);
        seedTool(orphanTool, orphanApi, "stale", "/s", "orphan-stale");

        UUID present = UUID.randomUUID();
        ApiCatalogMergeService.MergeResult result = mergeService.merge(
                List.of(apiPayload(present, "Fresh", List.of())), List.of());

        // Exactly the orphan was swept - apis AND tools counted (finding 4).
        assertThat(result.deprecatedApis()).isEqualTo(1);
        assertThat(result.deprecatedTools()).isEqualTo(1);
        assertThat(deprecatedAt("catalog.apis", orphanApi)).isNotNull();
        assertThat(deprecatedAt("catalog.api_tools", orphanTool)).isNotNull();
        // Regression (finding 4): the resurrected tool of the LONG-deprecated
        // API (not part of this sweep) must stay live.
        assertThat(deprecatedAt("catalog.api_tools", revivedTool)).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (d) Custom-API protection
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(d) A custom API occupying a bundle UUID is never overwritten, deprecated, or tool-swept")
    void customApiRowUntouchedByConflictingBundleId() {
        UUID cat = seedCategory("Mine", "mine");
        UUID sub = seedSubcategory(cat, "Sub", "sub");
        UUID customId = UUID.randomUUID();
        UUID customTool = UUID.randomUUID();
        named.update("""
                INSERT INTO catalog.apis (id, created_by, api_name, api_slug, description,
                    category_id, subcategory_id, base_url, source)
                VALUES (:id, 'user-1', 'My Custom Api', 'my-custom-api', 'precious user data',
                    :cat, :sub, 'https://mine', 'custom')
                """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("id", customId).addValue("cat", cat).addValue("sub", sub));
        seedTool(customTool, customId, "my-tool", "/mine", "custom-tool");

        ApiCatalogMergeService.MergeResult result = mergeService.merge(
                List.of(apiPayload(customId, "Evil Twin",
                        List.of(toolPayload(UUID.randomUUID(), "evil", "/evil", List.of())))),
                List.of());

        assertThat(result.skippedCustom()).isEqualTo(1);
        assertThat(result.upsertedApis()).isZero();
        // Row untouched: name, description, owner, source all intact.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT api_name, description, created_by, source, deprecated_at "
                        + "FROM catalog.apis WHERE id = ?", customId);
        assertThat(row.get("api_name")).isEqualTo("My Custom Api");
        assertThat(row.get("description")).isEqualTo("precious user data");
        assertThat(row.get("created_by")).isEqualTo("user-1");
        assertThat(row.get("source")).isEqualTo("custom");
        assertThat(row.get("deprecated_at")).isNull(); // sweep excluded it (counts as present)
        // Its tools were neither replaced nor deprecated; no evil tool added.
        assertThat(jdbc.queryForList("SELECT tool_slug FROM catalog.api_tools WHERE api_id = ?",
                String.class, customId)).containsExactly("my-tool");
        assertThat(deprecatedAt("catalog.api_tools", customTool)).isNull();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // (e) Snapshot reader shape
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("(e) Snapshot reader's 6 queries return the seeded shape and exclude custom/deprecated rows")
    void snapshotReaderReturnsSeededShape() {
        UUID cat = seedCategory("Communication", "communication");
        UUID sub = seedSubcategory(cat, "Email", "email");

        UUID liveApi = UUID.randomUUID();
        UUID liveTool = UUID.randomUUID();
        UUID deadTool = UUID.randomUUID();
        seedApi(liveApi, "Slack", "import", cat, sub, "slack");
        seedTool(liveTool, liveApi, "send", "/send", "slack-send");
        seedTool(deadTool, liveApi, "old", "/old", "slack-old");
        jdbc.update("UPDATE catalog.api_tools SET deprecated_at = NOW() WHERE id = ?", deadTool);
        seedParameter(UUID.randomUUID(), liveTool, "channel");
        seedResponse(UUID.randomUUID(), liveTool, "success");
        seedToolCredential(liveTool, "slack", "oauth2");

        UUID deprecatedApi = UUID.randomUUID();
        seedApi(deprecatedApi, "Gone", "import", cat, sub, "gone");
        jdbc.update("UPDATE catalog.apis SET deprecated_at = NOW() WHERE id = ?", deprecatedApi);

        UUID customApi = UUID.randomUUID();
        seedApi(customApi, "Private", "custom", cat, sub, "private-cred");

        seedTemplate("slack", "oauth2");        // backs liveApi → ships
        seedTemplate("smtp", "primary");        // native → ships
        seedTemplate("imap", "primary");        // native → ships
        seedTemplate("private-cred", "primary"); // backs only the custom API → no ship
        seedTemplate("gone", "primary");        // backs only the deprecated API → no ship
        seedTemplate("dead", "primary");
        jdbc.update("UPDATE catalog.credentials SET deprecated_at = NOW() "
                + "WHERE credential_name = 'dead'");

        ApiCatalogSnapshotReader.Snapshot snapshot = snapshotReader.snapshot();

        assertThat(snapshot.apis()).hasSize(1);
        ApiCatalogBundlePayload.ApiRow api = snapshot.apis().get(0);
        assertThat(api.id()).isEqualTo(liveApi);
        assertThat(api.apiName()).isEqualTo("Slack");
        assertThat(api.categoryName()).isEqualTo("Communication");
        assertThat(api.categorySlug()).isEqualTo("communication");
        assertThat(api.subcategoryName()).isEqualTo("Email");
        assertThat(api.rateLimits()).contains("\"plan\""); // jsonb read back as text

        assertThat(api.tools()).hasSize(1); // deprecated tool excluded
        ApiCatalogBundlePayload.ToolRow tool = api.tools().get(0);
        assertThat(tool.id()).isEqualTo(liveTool);
        assertThat(tool.executionSpec()).contains("\"kind\"");
        assertThat(tool.parameters()).hasSize(1);
        assertThat(tool.parameters().get(0).name()).isEqualTo("channel");
        assertThat(tool.responses()).hasSize(1);
        assertThat(tool.responses().get(0).name()).isEqualTo("success");
        assertThat(tool.toolCredentials()).hasSize(1);
        assertThat(tool.toolCredentials().get(0).credentialName()).isEqualTo("slack");
        assertThat(tool.toolCredentials().get(0).variant()).isEqualTo("oauth2");

        assertThat(snapshot.credentialTemplates())
                .extracting(ApiCatalogBundlePayload.CredentialTemplateRow::credentialName)
                .containsExactlyInAnyOrder("slack", "smtp", "imap");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Seed + payload helpers
    // ─────────────────────────────────────────────────────────────────────────

    private UUID seedCategory(String name, String slug) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO catalog.api_categories (id, name, slug) VALUES (?, ?, ?)",
                id, name, slug);
        return id;
    }

    private UUID seedSubcategory(UUID categoryId, String name, String slug) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO catalog.api_subcategories (id, category_id, name, slug) "
                + "VALUES (?, ?, ?, ?)", id, categoryId, name, slug);
        return id;
    }

    private void seedApi(UUID id, String name, String source, UUID cat, UUID sub,
                         String platformCredentialName) {
        jdbc.update("""
                INSERT INTO catalog.apis (id, created_by, api_name, api_slug, description,
                    category_id, subcategory_id, base_url, auth_type, status, is_public,
                    is_active, source, platform_credential_name, icon_slug, rate_limits)
                VALUES (?, 'SYSTEM', ?, ?, 'desc of ' || ?, ?, ?, 'https://api.example', 'apiKey',
                    'APPROVED', true, true, ?, ?, ?, '{"plan":"free"}'::jsonb)
                """, id, name, name.toLowerCase(), name, cat, sub, source,
                platformCredentialName, name.toLowerCase() + "-icon");
    }

    private void seedTool(UUID id, UUID apiId, String slug, String endpoint, String toolNameId) {
        jdbc.update("""
                INSERT INTO catalog.api_tools (id, api_id, tool_slug, description, tool_name_id,
                    method, endpoint, status, is_active, execution_spec, execution_mode)
                VALUES (?, ?, ?, 'does ' || ?, ?, 'POST', ?, 'ACTIVE', true,
                    '{"kind":"http"}'::jsonb, 'http')
                """, id, apiId, slug, slug, toolNameId, endpoint);
    }

    private void seedParameter(UUID id, UUID toolId, String name) {
        jdbc.update("""
                INSERT INTO catalog.api_tool_parameters (id, api_tool_id, parameter_type, name,
                    data_type, is_required, description, extras, created_at)
                VALUES (?, ?, 'query', ?, 'string', true, 'param desc',
                    '{"hint":"x"}'::jsonb, EXTRACT(EPOCH FROM NOW()) * 1000)
                """, id, toolId, name);
    }

    private void seedResponse(UUID id, UUID toolId, String name) {
        jdbc.update("""
                INSERT INTO catalog.tool_responses (id, tool_id, name, description, schema,
                    example, format, status_code, is_default, is_active, created_by)
                VALUES (?, ?, ?, 'resp desc', '{"type":"object"}'::jsonb, '{}', 'json', 200,
                    true, true, 'SYSTEM')
                """, id, toolId, name);
    }

    private void seedToolCredential(UUID toolId, String credentialName, String variant) {
        jdbc.update("""
                INSERT INTO catalog.tool_credentials (id, api_tool_id, credential_name, variant,
                    is_required, usage, condition)
                VALUES (gen_random_uuid(), ?, ?, ?, true, 'auth', '{"when":"always"}'::jsonb)
                """, toolId, credentialName, variant);
    }

    private void seedTemplate(String credentialName, String variant) {
        jdbc.update("""
                INSERT INTO catalog.credentials (id, credential_name, variant, display_name,
                    credential_type, auth_type, properties)
                VALUES (gen_random_uuid(), ?, ?, ?, 'api_key', 'apiKey',
                    '{"api_key":{"required":true}}'::jsonb)
                """, credentialName, variant, credentialName + " display");
    }

    /** Minimal CE-side payload map for an API (what the applier hands to merge()). */
    private static Map<String, Object> apiPayload(UUID id, String name,
                                                  List<Map<String, Object>> tools) {
        Map<String, Object> api = new java.util.HashMap<>();
        api.put("id", id.toString());
        api.put("apiName", name);
        api.put("apiSlug", name.toLowerCase());
        api.put("description", "d");
        api.put("baseUrl", "https://x");
        api.put("category", Map.of("name", "Communication", "slug", "communication"));
        api.put("subcategory", Map.of("name", "Email", "slug", "email"));
        api.put("tools", tools);
        return api;
    }

    private static Map<String, Object> toolPayload(UUID id, String slug, String endpoint,
                                                   List<Map<String, Object>> parameters) {
        Map<String, Object> tool = new java.util.HashMap<>();
        tool.put("id", id.toString());
        tool.put("toolSlug", slug);
        tool.put("description", "does " + slug);
        tool.put("method", "POST");
        tool.put("endpoint", endpoint);
        tool.put("parameters", parameters);
        tool.put("responses", List.of());
        tool.put("toolCredentials", List.of());
        return tool;
    }

    private static Map<String, Object> paramPayload(UUID id, String name) {
        Map<String, Object> p = new java.util.HashMap<>();
        p.put("id", id.toString());
        p.put("name", name);
        p.put("parameterType", "query");
        p.put("dataType", "string");
        p.put("extras", "{\"hint\": \"x\"}");
        return p;
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? -1 : n;
    }

    private Object deprecatedAt(String table, UUID id) {
        return jdbc.queryForMap("SELECT deprecated_at FROM " + table + " WHERE id = ?", id)
                .get("deprecated_at");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        return value instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }
}
