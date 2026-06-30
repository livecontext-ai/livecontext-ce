package com.apimarketplace.catalog.bundle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SQL-level contract of the CE merge: UUID upserts with resurrection,
 * custom-API protection, per-API failure isolation, child replacement, the
 * orphan soft-deprecation sweep, and credential-template upserts.
 *
 * <p>The {@code NamedParameterJdbcTemplate} is mocked and every statement is
 * recorded - assertions inspect the statements + bound parameters, which is
 * where ALL of the protection semantics live (source guards, NOT IN lists,
 * {@code deprecated_at = NULL} resurrection).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ApiCatalogMergeService - upsert/protect/deprecate semantics")
class ApiCatalogMergeServiceTest {

    @Mock private NamedParameterJdbcTemplate jdbc;
    @Mock private PlatformTransactionManager txManager;

    private ApiCatalogMergeService service;

    private record Statement(String sql, SqlParameterSource params) {}
    private final List<Statement> updates = new ArrayList<>();
    /** Recorded orphan-sweep SELECTs (the present-list query of deprecateOrphanApis). */
    private final List<Statement> orphanSelects = new ArrayList<>();

    /** apiId → existing source value ('import', 'bundle', 'custom'); absent = new row. */
    private final Map<UUID, String> existingSources = new HashMap<>();
    /** apiIds whose apis-INSERT should blow up (unique-collision simulation). */
    private final List<UUID> failingApiInserts = new ArrayList<>();
    /** apiIds whose apis-upsert reports 0 rows (ON CONFLICT … WHERE source guard fired). */
    private final List<UUID> suppressedApiUpserts = new ArrayList<>();
    /** What the orphan-sweep SELECT returns (APIs to deprecate in this sweep). */
    private final List<UUID> orphanApiIds = new ArrayList<>();
    /** Update count returned by the orphan TOOL sweep statement. */
    private int orphanToolSweepCount = 1;

    private static final UUID CATEGORY_ID = UUID.randomUUID();
    private static final UUID SUBCATEGORY_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ApiCatalogMergeService(jdbc, txManager);
        updates.clear();
        orphanSelects.clear();
        existingSources.clear();
        failingApiInserts.clear();
        suppressedApiUpserts.clear();
        orphanApiIds.clear();
        orphanToolSweepCount = 1;

        when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            SqlParameterSource p = inv.getArgument(1);
            if (sql.contains("INSERT INTO catalog.apis") && p.hasValue("id")
                    && failingApiInserts.contains(p.getValue("id"))) {
                throw new org.springframework.dao.DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"apis_created_by_api_name_key\"");
            }
            updates.add(new Statement(sql, p));
            if (sql.contains("INSERT INTO catalog.apis") && p.hasValue("id")
                    && suppressedApiUpserts.contains(p.getValue("id"))) {
                return 0;
            }
            if (sql.contains("UPDATE catalog.api_tools") && sql.contains("api_id IN (:orphanIds)")) {
                return orphanToolSweepCount;
            }
            return 1;
        });
        // Existing-source lookup.
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(String.class)))
                .thenAnswer(inv -> {
                    SqlParameterSource p = inv.getArgument(1);
                    Object id = p.getValue("id");
                    String source = existingSources.get(id);
                    return source == null ? List.of() : List.of(source);
                });
        // UUID list queries: the orphan-sweep SELECT (recorded + configurable)
        // vs category/subcategory resolution (pretend they already exist).
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(UUID.class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    if (sql.contains("FROM catalog.apis")) {
                        orphanSelects.add(new Statement(sql, inv.getArgument(1)));
                        return List.copyOf(orphanApiIds);
                    }
                    return sql.contains("api_subcategories")
                            ? List.of(SUBCATEGORY_ID) : List.of(CATEGORY_ID);
                });
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(UUID.class)))
                .thenAnswer(inv -> UUID.randomUUID());
    }

    private static Map<String, Object> apiMap(UUID id, String name, List<Map<String, Object>> tools) {
        Map<String, Object> api = new HashMap<>();
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

    private static Map<String, Object> toolMap(UUID id, String slug) {
        Map<String, Object> tool = new HashMap<>();
        tool.put("id", id.toString());
        tool.put("toolSlug", slug);
        tool.put("description", "does " + slug);
        tool.put("method", "GET");
        tool.put("endpoint", "/v1/" + slug);
        tool.put("parameters", List.of(Map.of(
                "id", UUID.randomUUID().toString(), "name", "to",
                "parameterType", "query", "dataType", "string")));
        tool.put("responses", List.of(Map.of(
                "id", UUID.randomUUID().toString(), "example", "{}", "format", "json")));
        tool.put("toolCredentials", List.of(Map.of(
                "credentialName", "slack", "variant", "oauth2")));
        return tool;
    }

    private List<Statement> matching(Predicate<String> sqlMatch) {
        return updates.stream().filter(s -> sqlMatch.test(s.sql())).toList();
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Upserts an existing import-sourced API by UUID - resurrects and re-stamps source='bundle'")
    void upsertExistingImportApi() {
        UUID apiId = UUID.randomUUID();
        existingSources.put(apiId, "import");

        ApiCatalogMergeService.MergeResult result =
                service.merge(List.of(apiMap(apiId, "Slack", List.of())), List.of());

        assertThat(result.upsertedApis()).isEqualTo(1);
        assertThat(result.skippedCustom()).isZero();
        assertThat(result.failedApis()).isZero();

        List<Statement> apiUpserts = matching(sql -> sql.contains("INSERT INTO catalog.apis"));
        assertThat(apiUpserts).hasSize(1);
        Statement upsert = apiUpserts.get(0);
        assertThat(upsert.params().getValue("id")).isEqualTo(apiId);
        // Resurrection + provenance stamp + custom-protection guard live in the SQL itself.
        assertThat(upsert.sql()).contains("ON CONFLICT (id) DO UPDATE");
        assertThat(upsert.sql()).contains("deprecated_at = NULL");
        assertThat(upsert.sql()).contains("source = 'bundle'");
        assertThat(upsert.sql()).contains("WHERE apis.source IN ('import','bundle')");
    }

    @Test
    @DisplayName("Custom-API protection: an existing source='custom' row is never written")
    void customApiNeverTouched() {
        UUID customId = UUID.randomUUID();
        existingSources.put(customId, "custom");

        ApiCatalogMergeService.MergeResult result =
                service.merge(List.of(apiMap(customId, "MyCustomApi", List.of())), List.of());

        assertThat(result.skippedCustom()).isEqualTo(1);
        assertThat(result.upsertedApis()).isZero();
        // No write of the custom API tree happened: no apis upsert, no tool
        // upsert, no per-API tool sweep. (The global orphan sweep still runs -
        // it is source-guarded and never reaches custom rows.)
        assertThat(matching(sql -> sql.contains("INSERT INTO catalog.apis"))).isEmpty();
        assertThat(matching(sql -> sql.contains("INSERT INTO catalog.api_tools"))).isEmpty();
        assertThat(matching(sql -> sql.contains("api_id = :apiId"))).isEmpty();
    }

    @Test
    @DisplayName("Orphan sweep selects ONLY source IN ('import','bundle') rows absent from the bundle, then deprecates exactly those ids")
    void orphanSweepScopedToBundleManaged() {
        UUID presentId = UUID.randomUUID();
        UUID orphanId = UUID.randomUUID();
        orphanApiIds.add(orphanId);

        ApiCatalogMergeService.MergeResult result =
                service.merge(List.of(apiMap(presentId, "Slack", List.of())), List.of());

        // The present-list SELECT carries the protection semantics: source
        // guard (custom-API protection on the deprecation side) + NOT IN.
        assertThat(orphanSelects).hasSize(1);
        Statement select = orphanSelects.get(0);
        assertThat(select.sql()).contains("source IN ('import','bundle')");
        assertThat(select.sql()).contains("deprecated_at IS NULL");
        assertThat(select.sql()).contains("id NOT IN (:presentIds)");
        assertThat((List<Object>) select.params().getValue("presentIds")).containsExactly(presentId);

        // The UPDATE then targets exactly the selected orphan ids.
        List<Statement> sweeps = matching(sql ->
                sql.contains("UPDATE catalog.apis") && sql.contains("SET deprecated_at = NOW()"));
        assertThat(sweeps).hasSize(1);
        assertThat(sweeps.get(0).sql()).contains("id IN (:orphanIds)");
        assertThat((List<Object>) sweeps.get(0).params().getValue("orphanIds"))
                .containsExactly(orphanId);
        assertThat(result.deprecatedApis()).isEqualTo(1);
    }

    @Test
    @DisplayName("No orphans selected → no deprecation UPDATE is issued at all")
    void noOrphansMeansNoSweepUpdate() {
        service.merge(List.of(apiMap(UUID.randomUUID(), "Slack", List.of())), List.of());

        assertThat(matching(sql ->
                sql.contains("UPDATE catalog.apis") && sql.contains("SET deprecated_at = NOW()")))
                .isEmpty();
        assertThat(matching(sql -> sql.contains("api_id IN (:orphanIds)"))).isEmpty();
    }

    @Test
    @DisplayName("Orphan TOOL sweep is scoped to the APIs deprecated in THIS sweep and counted in deprecatedTools")
    void orphanToolSweepScopedToThisSweep() {
        UUID orphanId = UUID.randomUUID();
        orphanApiIds.add(orphanId);
        orphanToolSweepCount = 3;

        // Present API has no tools → its per-API sweep contributes its own
        // count; isolate the orphan contribution via a tool-less bundle.
        ApiCatalogMergeService.MergeResult result =
                service.merge(List.of(apiMap(UUID.randomUUID(), "Slack", List.of())), List.of());

        List<Statement> toolSweeps = matching(sql ->
                sql.contains("UPDATE catalog.api_tools") && sql.contains("api_id IN (:orphanIds)"));
        assertThat(toolSweeps).hasSize(1);
        Statement toolSweep = toolSweeps.get(0);
        // Scoped by the ids deprecated in THIS sweep - NOT by
        // "deprecated_at IS NOT NULL" on the API side, which would clobber
        // manually resurrected tools of long-deprecated APIs.
        assertThat((List<Object>) toolSweep.params().getValue("orphanIds"))
                .containsExactly(orphanId);
        assertThat(toolSweep.sql()).contains("deprecated_at IS NULL");
        // Counted: per-API sweep of the present API (1, mocked default) +
        // orphan sweep (3).
        assertThat(result.deprecatedTools()).isEqualTo(4);
    }

    @Test
    @DisplayName("A custom row occupying a bundle UUID still counts as present - sweep excludes it")
    void skippedCustomStillExcludedFromSweep() {
        UUID customId = UUID.randomUUID();
        existingSources.put(customId, "custom");

        service.merge(List.of(apiMap(customId, "Occupied", List.of())), List.of());

        assertThat(orphanSelects).hasSize(1);
        assertThat((List<Object>) orphanSelects.get(0).params().getValue("presentIds"))
                .containsExactly(customId);
    }

    @Test
    @DisplayName("Per-API isolation: one failing API does not prevent the others, and stays un-deprecated")
    void perApiFailureIsolation() {
        UUID failing = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        failingApiInserts.add(failing);

        ApiCatalogMergeService.MergeResult result = service.merge(List.of(
                apiMap(failing, "Broken", List.of()),
                apiMap(healthy, "Fine", List.of())), List.of());

        assertThat(result.failedApis()).isEqualTo(1);
        assertThat(result.upsertedApis()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0)).contains(failing.toString());

        // The failed API is still in the bundle → must NOT be swept as orphan.
        assertThat(orphanSelects).hasSize(1);
        assertThat((List<Object>) orphanSelects.get(0).params().getValue("presentIds"))
                .containsExactlyInAnyOrder(failing, healthy);
    }

    @Test
    @DisplayName("Upsert count 0 (source guard fired concurrently) aborts that API's tree - counted skippedCustom, nothing else touched")
    void suppressedUpsertAbortsApiTree() {
        UUID racedId = UUID.randomUUID();
        UUID healthy = UUID.randomUUID();
        existingSources.put(racedId, "import"); // up-front check passes…
        suppressedApiUpserts.add(racedId);      // …but the row flips to custom before the upsert

        ApiCatalogMergeService.MergeResult result = service.merge(List.of(
                apiMap(racedId, "Raced", List.of(toolMap(UUID.randomUUID(), "send"))),
                apiMap(healthy, "Fine", List.of())), List.of());

        assertThat(result.skippedCustom()).isEqualTo(1);
        assertThat(result.upsertedApis()).isEqualTo(1); // the healthy one
        assertThat(result.failedApis()).isZero();       // a guarded skip is not a failure

        // The raced API's tree was aborted: no tool insert under the (now
        // custom) API, no child replacement, no per-API tool sweep - the
        // sweep has NO source guard and would deprecate the custom API's own
        // tools.
        assertThat(matching(sql -> sql.contains("INSERT INTO catalog.api_tools"))).isEmpty();
        assertThat(matching(sql -> sql.contains("DELETE FROM catalog.api_tool_parameters"))).isEmpty();
        List<Statement> perApiSweeps = matching(sql ->
                sql.contains("UPDATE catalog.api_tools") && sql.contains("api_id = :apiId"));
        assertThat(perApiSweeps.stream().map(s -> s.params().getValue("apiId")))
                .doesNotContain(racedId);

        // Still "present" for the orphan sweep - a concurrent flip must not
        // get the row deprecated either.
        assertThat((List<Object>) orphanSelects.get(0).params().getValue("presentIds"))
                .contains(racedId);
    }

    @Test
    @DisplayName("Tool upsert resurrects by UUID; tools absent from the bundle are soft-deprecated per API")
    void toolUpsertAndPerApiToolSweep() {
        UUID apiId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();

        ApiCatalogMergeService.MergeResult result = service.merge(
                List.of(apiMap(apiId, "Slack", List.of(toolMap(toolId, "send-message")))), List.of());

        assertThat(result.upsertedTools()).isEqualTo(1);

        Statement toolUpsert = matching(sql -> sql.contains("INSERT INTO catalog.api_tools")).get(0);
        assertThat(toolUpsert.params().getValue("id")).isEqualTo(toolId);
        assertThat(toolUpsert.sql()).contains("ON CONFLICT (id) DO UPDATE");
        assertThat(toolUpsert.sql()).contains("deprecated_at = NULL");

        Statement toolSweep = matching(sql ->
                sql.contains("UPDATE catalog.api_tools") && sql.contains("SET deprecated_at = NOW()")
                        && sql.contains("api_id = :apiId")).get(0);
        assertThat(toolSweep.params().getValue("apiId")).isEqualTo(apiId);
        assertThat(toolSweep.sql()).contains("id NOT IN (:toolIds)");
        assertThat((List<Object>) toolSweep.params().getValue("toolIds")).containsExactly(toolId);
    }

    @Test
    @DisplayName("Leaf children are replaced wholesale: delete-then-insert for params/responses/links")
    void childrenReplacedWholesale() {
        UUID apiId = UUID.randomUUID();
        UUID toolId = UUID.randomUUID();

        service.merge(List.of(apiMap(apiId, "Slack", List.of(toolMap(toolId, "send")))), List.of());

        for (String table : List.of("catalog.api_tool_parameters", "catalog.tool_responses",
                "catalog.tool_credentials")) {
            List<Statement> deletes = matching(sql -> sql.contains("DELETE FROM " + table));
            assertThat(deletes).as("delete on " + table).hasSize(1);
            assertThat(deletes.get(0).params().getValue("toolId")).isEqualTo(toolId);
            assertThat(matching(sql -> sql.contains("INSERT INTO " + table)))
                    .as("insert on " + table).hasSize(1);
        }
        // The re-created link resolves credential_id locally by (name, variant),
        // never trusting the cloud's credentials UUID.
        Statement link = matching(sql -> sql.contains("INSERT INTO catalog.tool_credentials")).get(0);
        assertThat(link.sql()).contains("SELECT c.id FROM catalog.credentials");
        assertThat(link.params().getValue("credentialName")).isEqualTo("slack");
        assertThat(link.params().getValue("variant")).isEqualTo("oauth2");
    }

    @Test
    @DisplayName("Credential templates upsert on (credential_name, variant) and resurrect")
    void credentialTemplateUpsert() {
        ApiCatalogMergeService.MergeResult result = service.merge(List.of(), List.of(Map.of(
                "credentialName", "slack", "variant", "oauth2",
                "displayName", "Slack", "authType", "oauth2",
                "properties", "{\"client_id\":{}}")));

        assertThat(result.upsertedTemplates()).isEqualTo(1);
        Statement upsert = matching(sql -> sql.contains("INSERT INTO catalog.credentials")).get(0);
        assertThat(upsert.sql()).contains("ON CONFLICT (credential_name, variant) DO UPDATE");
        assertThat(upsert.sql()).contains("deprecated_at = NULL");
        assertThat(upsert.params().getValue("credentialName")).isEqualTo("slack");
        assertThat(upsert.params().getValue("variant")).isEqualTo("oauth2");
    }

    // NOTE: there is deliberately no merge-level "empty apis list" test any
    // more - an empty bundle is rejected by ApiCatalogBundleApplier's wipe
    // guard BEFORE merge() is ever called (see
    // ApiCatalogBundleApplierTest.emptyApisPayloadRejected), so the
    // "deprecate everything" shape is unreachable by design.

    @Test
    @DisplayName("Unparseable API id is counted failed and does not abort the merge")
    void unparseableIdSkipped() {
        UUID healthy = UUID.randomUUID();
        Map<String, Object> broken = new HashMap<>();
        broken.put("id", "not-a-uuid");
        broken.put("apiName", "Broken");
        broken.put("tools", List.of());

        ApiCatalogMergeService.MergeResult result = service.merge(
                List.of(broken, apiMap(healthy, "Fine", List.of())), List.of());

        assertThat(result.failedApis()).isEqualTo(1);
        assertThat(result.upsertedApis()).isEqualTo(1);
    }

    @Test
    @DisplayName("All ids unparseable: orphan sweep is skipped entirely (structural wipe guard)")
    void allIdsUnparseableSkipsOrphanSweep() {
        // A signed-but-corrupted bundle whose every id fails UUID parsing must
        // NOT reach the NOT-IN orphan sweep with an empty present-list - that
        // would soft-deprecate every bundle-managed API on the install.
        Map<String, Object> broken = new HashMap<>();
        broken.put("id", "not-a-uuid");
        broken.put("apiName", "Broken");
        broken.put("tools", List.of());

        ApiCatalogMergeService.MergeResult result = service.merge(List.of(broken), List.of());

        assertThat(result.failedApis()).isEqualTo(1);
        assertThat(result.deprecatedApis()).isZero();
        // No orphan SELECT and no apis-deprecation UPDATE may run.
        verify(jdbc, never()).queryForList(contains("deprecated_at IS NULL"),
                any(SqlParameterSource.class), eq(UUID.class));
        verify(jdbc, never()).update(contains("SET deprecated_at = NOW()"),
                any(SqlParameterSource.class));
    }

    @Test
    @DisplayName("Category resolution creates missing category and subcategory rows")
    void categoryCreationWhenMissing() {
        // Override: no category match found anywhere.
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(UUID.class)))
                .thenReturn(List.of());
        List<String> insertSqls = new ArrayList<>();
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(UUID.class)))
                .thenAnswer(inv -> {
                    insertSqls.add(inv.getArgument(0));
                    return UUID.randomUUID();
                });

        service.merge(List.of(apiMap(UUID.randomUUID(), "Slack", List.of())), List.of());

        assertThat(insertSqls).hasSize(2);
        assertThat(insertSqls.get(0)).contains("INSERT INTO catalog.api_categories");
        assertThat(insertSqls.get(1)).contains("INSERT INTO catalog.api_subcategories");
        // The new API row links to the freshly created category pair (NOT NULL FKs).
        Statement apiUpsert = matching(sql -> sql.contains("INSERT INTO catalog.apis")).get(0);
        assertThat(apiUpsert.params().getValue("categoryId")).isNotNull();
        assertThat(apiUpsert.params().getValue("subcategoryId")).isNotNull();
    }

    @Test
    @DisplayName("New API rows are inserted with created_by='SYSTEM' and source='bundle'")
    void newRowProvenance() {
        UUID apiId = UUID.randomUUID(); // no existing source → brand-new row

        service.merge(List.of(apiMap(apiId, "Fresh", List.of())), List.of());

        Statement upsert = matching(sql -> sql.contains("INSERT INTO catalog.apis")).get(0);
        assertThat(upsert.sql()).contains("'SYSTEM'");
        assertThat(upsert.sql()).contains("'bundle'");
        MapSqlParameterSource p = (MapSqlParameterSource) upsert.params();
        assertThat(p.getValue("apiName")).isEqualTo("Fresh");
    }
}
