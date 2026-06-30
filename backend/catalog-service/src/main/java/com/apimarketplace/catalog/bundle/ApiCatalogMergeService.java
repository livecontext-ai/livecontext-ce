package com.apimarketplace.catalog.bundle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * CE-side row merge for a verified API-catalog bundle payload. Owns ALL SQL;
 * {@link ApiCatalogBundleApplier} owns orchestration (verify-assumed input,
 * idempotency, bundle-row + sync-status bookkeeping).
 *
 * <p><b>Merge semantics:</b>
 * <ul>
 *   <li><b>Per-API transaction</b> - each API tree (api row + tools +
 *       parameters + responses + tool-credential links) is upserted in its own
 *       TX; one failing API (e.g. a {@code UNIQUE(created_by, api_name)}
 *       collision with a pre-existing local row under a different UUID) never
 *       rolls back the rest of the catalog.</li>
 *   <li><b>UPSERT in-place by UUID, never TRUNCATE/DELETE of apis/tools</b> -
 *       the execution path ({@code ToolContextService.loadToolContext}) always
 *       finds its row. Leaf children (parameters, responses, tool-credential
 *       links) ARE replaced wholesale per tool: nothing references them by id.</li>
 *   <li><b>Custom-API protection</b> - an existing row whose {@code source}
 *       is NOT {@code import}/{@code bundle} (i.e. {@code custom},
 *       tenant-created) is never updated, deprecated, or overwritten. Checked
 *       up-front per API AND enforced again in the upsert's
 *       {@code ON CONFLICT … WHERE} clause (defense-in-depth).</li>
 *   <li><b>Soft-delete</b> - after all APIs: bundle-managed rows absent from
 *       the bundle get {@code deprecated_at = NOW()} (hidden from
 *       builder/search; execution by UUID still resolves). Rows present again
 *       are resurrected ({@code deprecated_at = NULL}, part of the upsert).</li>
 *   <li><b>Categories</b> - the payload carries {@code category{name,slug}};
 *       resolution is slug-first, then name, then CREATE (bundle category
 *       UUIDs are install-local, so re-linking by slug keeps CE-edited
 *       category rows intact).</li>
 *   <li><b>Credential templates</b> - upserted by
 *       {@code (credential_name, variant)} (the post-V103 unique key).</li>
 * </ul>
 */
@Slf4j
@Service
public class ApiCatalogMergeService {

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionOperations perApiTx;

    public ApiCatalogMergeService(NamedParameterJdbcTemplate jdbc,
                                  PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.perApiTx = new TransactionTemplate(transactionManager);
    }

    public record MergeResult(int upsertedApis, int upsertedTools, int skippedCustom,
                              int failedApis, int deprecatedApis, int deprecatedTools,
                              int upsertedTemplates, List<String> errors) {}

    /**
     * Apply the parsed payload maps. Caller MUST have verified the bundle
     * signature and gunzipped the payload first.
     */
    public MergeResult merge(List<Map<String, Object>> apiMaps,
                             List<Map<String, Object>> templateMaps) {
        int upsertedApis = 0;
        int upsertedTools = 0;
        int skippedCustom = 0;
        int failedApis = 0;
        int deprecatedTools = 0;
        List<String> errors = new ArrayList<>();
        List<UUID> bundleApiIds = new ArrayList<>(apiMaps.size());

        for (Map<String, Object> apiMap : apiMaps) {
            UUID apiId;
            try {
                apiId = UUID.fromString(str(apiMap, "id"));
            } catch (Exception e) {
                failedApis++;
                errors.add("api with unparseable id '" + apiMap.get("id") + "' skipped");
                continue;
            }
            // Every API listed in the bundle is "present" for the orphan sweep,
            // even if its upsert fails - a transient failure must not deprecate it.
            bundleApiIds.add(apiId);

            String existingSource = findExistingSource(apiId);
            if (existingSource != null && !isBundleManaged(existingSource)) {
                // The UUID is occupied by a user-created API on this install.
                // Never overwrite it.
                skippedCustom++;
                log.warn("API catalog bundle: skipping api {} - local row has source='{}' (user-created)",
                        apiId, existingSource);
                continue;
            }

            try {
                int[] toolCounts = Objects.requireNonNullElse(
                        perApiTx.execute(status -> upsertApiTree(apiId, apiMap)), new int[]{0, 0});
                upsertedApis++;
                upsertedTools += toolCounts[0];
                deprecatedTools += toolCounts[1];
            } catch (ConcurrentSourceConflictException e) {
                // The api-row upsert updated 0 rows: its ON CONFLICT … WHERE
                // source guard fired because the row flipped to a non-bundle
                // source between the up-front check and the statement. Same
                // outcome as the up-front check: leave the custom row alone.
                skippedCustom++;
                log.warn("API catalog bundle: skipping api {} - row became user-owned concurrently " +
                        "(upsert suppressed by source guard)", apiId);
            } catch (Exception e) {
                failedApis++;
                String msg = "api " + apiId + " (" + apiMap.get("apiName") + "): "
                        + e.getClass().getSimpleName() + ": " + e.getMessage();
                errors.add(msg);
                log.error("API catalog bundle: failed to upsert {}", msg);
            }
        }

        // Per-tool deprecation happened inside each API TX; the cross-API
        // orphan sweep + template upsert run in one final TX.
        // Structural wipe guard: the applier already rejects empty `apis`
        // payloads, but if every id in a (signed) bundle failed to parse the
        // present-list would be empty here and the NOT-IN sweep would deprecate
        // the whole catalog - skip the sweep entirely in that case.
        final List<UUID> presentIds = List.copyOf(bundleApiIds);
        final boolean sweepSafe = !presentIds.isEmpty();
        if (!sweepSafe) {
            log.warn("[api-bundle] no parseable API ids in a non-empty bundle - skipping orphan sweep");
        }
        int[] sweepCounts = Objects.requireNonNullElse(perApiTx.execute(status -> {
            int[] deprecated = sweepSafe ? deprecateOrphanApis(presentIds) : new int[]{0, 0};
            int templates = upsertTemplates(templateMaps);
            return new int[]{deprecated[0], deprecated[1], templates};
        }), new int[]{0, 0, 0});

        return new MergeResult(upsertedApis, upsertedTools, skippedCustom, failedApis,
                sweepCounts[0], deprecatedTools + sweepCounts[1], sweepCounts[2], errors);
    }

    /**
     * Thrown when the api-row upsert's {@code ON CONFLICT … WHERE source}
     * guard suppressed the update (0 rows): the row flipped to a user-owned
     * source concurrently. Aborts (rolls back) this API's tree only.
     */
    private static final class ConcurrentSourceConflictException extends RuntimeException {
        ConcurrentSourceConflictException(UUID apiId) {
            super("api " + apiId + " upsert suppressed by source guard (row is user-owned)");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-API tree upsert (runs inside one TX per API)
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns {@code {toolsUpserted, toolsDeprecated}} for this API. */
    private int[] upsertApiTree(UUID apiId, Map<String, Object> apiMap) {
        UUID categoryId = resolveCategory(map(apiMap, "category"));
        UUID subcategoryId = resolveSubcategory(categoryId, map(apiMap, "subcategory"));

        if (upsertApi(apiId, apiMap, categoryId, subcategoryId) == 0) {
            // The ON CONFLICT … WHERE source guard suppressed the update -
            // the row flipped to a user-owned source after the up-front check.
            // Abort this API's whole tree: inserting tools under the custom
            // API (and then sweeping ITS tools) would clobber user data.
            throw new ConcurrentSourceConflictException(apiId);
        }

        List<Map<String, Object>> tools = list(apiMap, "tools");
        List<UUID> bundleToolIds = new ArrayList<>(tools.size());
        int applied = 0;
        for (Map<String, Object> toolMap : tools) {
            UUID toolId = UUID.fromString(str(toolMap, "id"));
            bundleToolIds.add(toolId);
            upsertTool(apiId, toolId, toolMap);
            replaceParameters(toolId, list(toolMap, "parameters"));
            replaceResponses(toolId, list(toolMap, "responses"));
            replaceToolCredentials(toolId, list(toolMap, "toolCredentials"));
            applied++;
        }

        // Soft-deprecate this API's tools that the bundle no longer lists.
        // Resurrection of re-listed tools is part of upsertTool (deprecated_at=NULL).
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("apiId", apiId);
        String notIn = "";
        if (!bundleToolIds.isEmpty()) {
            notIn = " AND id NOT IN (:toolIds)";
            params.addValue("toolIds", bundleToolIds);
        }
        int deprecated = jdbc.update("""
                UPDATE catalog.api_tools
                   SET deprecated_at = NOW()
                 WHERE api_id = :apiId
                   AND deprecated_at IS NULL""" + notIn, params);
        return new int[]{applied, deprecated};
    }

    /**
     * Returns the statement's update count: 1 when the row was inserted or
     * updated, 0 when the {@code ON CONFLICT … WHERE} source guard suppressed
     * the update (the existing row is user-owned).
     */
    private int upsertApi(UUID apiId, Map<String, Object> api, UUID categoryId, UUID subcategoryId) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("id", apiId)
                .addValue("apiName", str(api, "apiName"))
                .addValue("apiSlug", str(api, "apiSlug"))
                .addValue("description", Objects.requireNonNullElse(str(api, "description"), ""))
                .addValue("baseUrl", Objects.requireNonNullElse(str(api, "baseUrl"), ""))
                .addValue("healthcheckEndpoint", str(api, "healthcheckEndpoint"))
                .addValue("categoryId", categoryId)
                .addValue("subcategoryId", subcategoryId)
                .addValue("authType", str(api, "authType"))
                .addValue("authHeaderName", str(api, "authHeaderName"))
                .addValue("authHeaderValue", str(api, "authHeaderValue"))
                .addValue("visibility", Objects.requireNonNullElse(str(api, "visibility"), "public"))
                .addValue("isPublic", bool(api, "isPublic", false))
                .addValue("isActive", bool(api, "isActive", true))
                .addValue("isLocal", bool(api, "isLocal", false))
                .addValue("pricingModel", Objects.requireNonNullElse(str(api, "pricingModel"), "free"))
                .addValue("status", Objects.requireNonNullElse(str(api, "status"), "APPROVED"))
                .addValue("version", str(api, "version"))
                .addValue("iconSlug", str(api, "iconSlug"))
                .addValue("platformCredentialName", str(api, "platformCredentialName"))
                .addValue("iconUrl", str(api, "iconUrl"))
                .addValue("apiVersion", str(api, "apiVersion"))
                .addValue("documentation", str(api, "documentation"))
                .addValue("rateLimits", str(api, "rateLimits"));

        // ON CONFLICT … WHERE source guard = defense-in-depth: even if the
        // up-front source check raced, a custom row is never overwritten (the
        // statement then updates 0 rows - surfaced via the return count).
        return jdbc.update("""
                INSERT INTO catalog.apis (
                    id, created_by, api_name, api_slug, description, base_url,
                    healthcheck_endpoint, category_id, subcategory_id, auth_type,
                    auth_header_name, auth_header_value, visibility, is_public, is_active,
                    is_local, pricing_model, status, version, icon_slug,
                    platform_credential_name, icon_url, api_version, documentation,
                    rate_limits, source, deprecated_at, created_at, updated_at)
                VALUES (
                    :id, 'SYSTEM', :apiName, :apiSlug, :description, :baseUrl,
                    :healthcheckEndpoint, :categoryId, :subcategoryId, :authType,
                    :authHeaderName, :authHeaderValue, :visibility, :isPublic, :isActive,
                    :isLocal, :pricingModel, :status, :version, :iconSlug,
                    :platformCredentialName, :iconUrl, :apiVersion, :documentation,
                    :rateLimits::jsonb, 'bundle', NULL,
                    EXTRACT(EPOCH FROM NOW()) * 1000, EXTRACT(EPOCH FROM NOW()) * 1000)
                ON CONFLICT (id) DO UPDATE SET
                    api_name = EXCLUDED.api_name,
                    api_slug = EXCLUDED.api_slug,
                    description = EXCLUDED.description,
                    base_url = EXCLUDED.base_url,
                    healthcheck_endpoint = EXCLUDED.healthcheck_endpoint,
                    category_id = EXCLUDED.category_id,
                    subcategory_id = EXCLUDED.subcategory_id,
                    auth_type = EXCLUDED.auth_type,
                    auth_header_name = EXCLUDED.auth_header_name,
                    auth_header_value = EXCLUDED.auth_header_value,
                    visibility = EXCLUDED.visibility,
                    is_public = EXCLUDED.is_public,
                    is_active = EXCLUDED.is_active,
                    is_local = EXCLUDED.is_local,
                    pricing_model = EXCLUDED.pricing_model,
                    status = EXCLUDED.status,
                    version = EXCLUDED.version,
                    icon_slug = EXCLUDED.icon_slug,
                    platform_credential_name = EXCLUDED.platform_credential_name,
                    icon_url = EXCLUDED.icon_url,
                    api_version = EXCLUDED.api_version,
                    documentation = EXCLUDED.documentation,
                    rate_limits = EXCLUDED.rate_limits,
                    source = 'bundle',
                    deprecated_at = NULL,
                    updated_at = EXTRACT(EPOCH FROM NOW()) * 1000
                WHERE apis.source IN ('import','bundle')
                """, p);
    }

    private void upsertTool(UUID apiId, UUID toolId, Map<String, Object> tool) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("id", toolId)
                .addValue("apiId", apiId)
                .addValue("toolSlug", str(tool, "toolSlug"))
                .addValue("description", Objects.requireNonNullElse(str(tool, "description"), ""))
                .addValue("toolNameId", str(tool, "toolNameId"))
                .addValue("method", Objects.requireNonNullElse(str(tool, "method"), "GET"))
                .addValue("endpoint", Objects.requireNonNullElse(str(tool, "endpoint"), ""))
                .addValue("protocol", Objects.requireNonNullElse(str(tool, "protocol"), "HTTP"))
                .addValue("defaultHeaders", str(tool, "defaultHeaders"))
                .addValue("runtimeMetadata", str(tool, "runtimeMetadata"))
                .addValue("executionSpec", str(tool, "executionSpec"))
                .addValue("outputSchema", str(tool, "outputSchema"))
                .addValue("executionMode", str(tool, "executionMode"))
                .addValue("pagination", str(tool, "pagination"))
                .addValue("requiredScopes", str(tool, "requiredScopes"))
                .addValue("nextHint", str(tool, "nextHint"))
                .addValue("status", Objects.requireNonNullElse(str(tool, "status"), "ACTIVE"))
                .addValue("testStatus", str(tool, "testStatus"))
                .addValue("isActive", bool(tool, "isActive", true))
                .addValue("version", str(tool, "version"));

        jdbc.update("""
                INSERT INTO catalog.api_tools (
                    id, api_id, tool_slug, description, tool_name_id, method, endpoint,
                    protocol, default_headers, runtime_metadata, execution_spec, output_schema,
                    execution_mode, pagination, required_scopes, next_hint, status, test_status,
                    is_active, version, deprecated_at, created_at, updated_at)
                VALUES (
                    :id, :apiId, :toolSlug, :description, :toolNameId, :method, :endpoint,
                    :protocol, :defaultHeaders, :runtimeMetadata, :executionSpec::jsonb,
                    :outputSchema::jsonb, :executionMode, :pagination::jsonb,
                    :requiredScopes::jsonb, :nextHint, :status, :testStatus,
                    :isActive, :version, NULL,
                    EXTRACT(EPOCH FROM NOW()) * 1000, EXTRACT(EPOCH FROM NOW()) * 1000)
                ON CONFLICT (id) DO UPDATE SET
                    api_id = EXCLUDED.api_id,
                    tool_slug = EXCLUDED.tool_slug,
                    description = EXCLUDED.description,
                    tool_name_id = EXCLUDED.tool_name_id,
                    method = EXCLUDED.method,
                    endpoint = EXCLUDED.endpoint,
                    protocol = EXCLUDED.protocol,
                    default_headers = EXCLUDED.default_headers,
                    runtime_metadata = EXCLUDED.runtime_metadata,
                    execution_spec = EXCLUDED.execution_spec,
                    output_schema = EXCLUDED.output_schema,
                    execution_mode = EXCLUDED.execution_mode,
                    pagination = EXCLUDED.pagination,
                    required_scopes = EXCLUDED.required_scopes,
                    next_hint = EXCLUDED.next_hint,
                    status = EXCLUDED.status,
                    test_status = EXCLUDED.test_status,
                    is_active = EXCLUDED.is_active,
                    version = EXCLUDED.version,
                    deprecated_at = NULL,
                    updated_at = EXTRACT(EPOCH FROM NOW()) * 1000
                """, p);
    }

    private void replaceParameters(UUID toolId, List<Map<String, Object>> parameters) {
        jdbc.update("DELETE FROM catalog.api_tool_parameters WHERE api_tool_id = :toolId",
                new MapSqlParameterSource("toolId", toolId));
        for (Map<String, Object> param : parameters) {
            jdbc.update("""
                    INSERT INTO catalog.api_tool_parameters (
                        id, api_tool_id, parameter_type, name, data_type, is_required,
                        description, example_value, default_value, allowed_values, file_path,
                        extras, is_hidden, created_at)
                    VALUES (
                        :id, :toolId, :parameterType, :name, :dataType, :isRequired,
                        :description, :exampleValue, :defaultValue, :allowedValues, :filePath,
                        :extras::jsonb, :isHidden, EXTRACT(EPOCH FROM NOW()) * 1000)
                    """, new MapSqlParameterSource()
                    .addValue("id", UUID.fromString(str(param, "id")))
                    .addValue("toolId", toolId)
                    .addValue("parameterType", Objects.requireNonNullElse(str(param, "parameterType"), "query"))
                    .addValue("name", str(param, "name"))
                    .addValue("dataType", Objects.requireNonNullElse(str(param, "dataType"), "string"))
                    .addValue("isRequired", bool(param, "isRequired", false))
                    .addValue("description", str(param, "description"))
                    .addValue("exampleValue", str(param, "exampleValue"))
                    .addValue("defaultValue", str(param, "defaultValue"))
                    .addValue("allowedValues", str(param, "allowedValues"))
                    .addValue("filePath", str(param, "filePath"))
                    .addValue("extras", str(param, "extras"))
                    .addValue("isHidden", bool(param, "isHidden", false)));
        }
    }

    private void replaceResponses(UUID toolId, List<Map<String, Object>> responses) {
        jdbc.update("DELETE FROM catalog.tool_responses WHERE tool_id = :toolId",
                new MapSqlParameterSource("toolId", toolId));
        for (Map<String, Object> resp : responses) {
            jdbc.update("""
                    INSERT INTO catalog.tool_responses (
                        id, tool_id, name, description, schema, example, example_jsonb,
                        structure_skeleton, status_code, is_default, format, is_active,
                        created_at, updated_at, created_by)
                    VALUES (
                        :id, :toolId, :name, :description, :schema::jsonb, :example,
                        :exampleJsonb::jsonb, :structureSkeleton::jsonb, :statusCode,
                        :isDefault, :format, :isActive, NOW(), NOW(), 'SYSTEM')
                    """, new MapSqlParameterSource()
                    .addValue("id", UUID.fromString(str(resp, "id")))
                    .addValue("toolId", toolId)
                    .addValue("name", str(resp, "name"))
                    .addValue("description", str(resp, "description"))
                    .addValue("schema", str(resp, "schema"))
                    .addValue("example", Objects.requireNonNullElse(str(resp, "example"), ""))
                    .addValue("exampleJsonb", str(resp, "exampleJsonb"))
                    .addValue("structureSkeleton", str(resp, "structureSkeleton"))
                    .addValue("statusCode", integer(resp, "statusCode"))
                    .addValue("isDefault", bool(resp, "isDefault", false))
                    .addValue("format", Objects.requireNonNullElse(str(resp, "format"), "json"))
                    .addValue("isActive", bool(resp, "isActive", true)));
        }
    }

    private void replaceToolCredentials(UUID toolId, List<Map<String, Object>> toolCredentials) {
        jdbc.update("DELETE FROM catalog.tool_credentials WHERE api_tool_id = :toolId",
                new MapSqlParameterSource("toolId", toolId));
        for (Map<String, Object> tc : toolCredentials) {
            // credential_id is re-resolved locally by (name, variant) - the
            // cloud's credentials UUIDs are not authoritative on CE.
            jdbc.update("""
                    INSERT INTO catalog.tool_credentials (
                        id, api_tool_id, credential_id, credential_name, variant, is_required,
                        usage, condition, metadata, created_at, updated_at)
                    VALUES (
                        gen_random_uuid(), :toolId,
                        (SELECT c.id FROM catalog.credentials c
                          WHERE c.credential_name = :credentialName AND c.variant = :variant
                          LIMIT 1),
                        :credentialName, :variant, :isRequired, :usage, :condition::jsonb,
                        :metadata::jsonb,
                        EXTRACT(EPOCH FROM NOW()) * 1000, EXTRACT(EPOCH FROM NOW()) * 1000)
                    """, new MapSqlParameterSource()
                    .addValue("toolId", toolId)
                    .addValue("credentialName", str(tc, "credentialName"))
                    .addValue("variant", Objects.requireNonNullElse(str(tc, "variant"), "primary"))
                    .addValue("isRequired", bool(tc, "isRequired", true))
                    .addValue("usage", str(tc, "usage"))
                    .addValue("condition", str(tc, "condition"))
                    .addValue("metadata", str(tc, "metadata")));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Category resolution (slug-first, then name, then create)
    // ─────────────────────────────────────────────────────────────────────────

    private UUID resolveCategory(Map<String, Object> category) {
        String name = category == null ? null : str(category, "name");
        String slug = category == null ? null : str(category, "slug");
        if (name == null && slug == null) {
            // category_id is NOT NULL - fall back to a catch-all bucket.
            name = "Other";
            slug = "other";
        }
        UUID existing = firstUuid("""
                SELECT id FROM catalog.api_categories
                 WHERE (:slug IS NOT NULL AND slug = :slug) OR (:name IS NOT NULL AND name = :name)
                 ORDER BY CASE WHEN slug = :slug THEN 0 ELSE 1 END
                 LIMIT 1
                """, new MapSqlParameterSource().addValue("slug", slug).addValue("name", name));
        if (existing != null) return existing;

        return jdbc.queryForObject("""
                INSERT INTO catalog.api_categories (id, name, slug, is_active, sort_order, created_at, updated_at)
                VALUES (gen_random_uuid(), :name, :slug, true, 0,
                        EXTRACT(EPOCH FROM NOW()) * 1000, EXTRACT(EPOCH FROM NOW()) * 1000)
                RETURNING id
                """, new MapSqlParameterSource()
                        .addValue("name", Objects.requireNonNullElse(name, slug))
                        .addValue("slug", slug),
                UUID.class);
    }

    private UUID resolveSubcategory(UUID categoryId, Map<String, Object> subcategory) {
        String name = subcategory == null ? null : str(subcategory, "name");
        String slug = subcategory == null ? null : str(subcategory, "slug");
        if (name == null && slug == null) {
            name = "General";
            slug = "general";
        }
        UUID existing = firstUuid("""
                SELECT id FROM catalog.api_subcategories
                 WHERE category_id = :categoryId
                   AND ((:slug IS NOT NULL AND slug = :slug) OR (:name IS NOT NULL AND name = :name))
                 ORDER BY CASE WHEN slug = :slug THEN 0 ELSE 1 END
                 LIMIT 1
                """, new MapSqlParameterSource()
                        .addValue("categoryId", categoryId)
                        .addValue("slug", slug)
                        .addValue("name", name));
        if (existing != null) return existing;

        return jdbc.queryForObject("""
                INSERT INTO catalog.api_subcategories (id, category_id, name, slug, is_active, sort_order, created_at, updated_at)
                VALUES (gen_random_uuid(), :categoryId, :name, :slug, true, 0,
                        EXTRACT(EPOCH FROM NOW()) * 1000, EXTRACT(EPOCH FROM NOW()) * 1000)
                RETURNING id
                """, new MapSqlParameterSource()
                        .addValue("categoryId", categoryId)
                        .addValue("name", Objects.requireNonNullElse(name, slug))
                        .addValue("slug", slug),
                UUID.class);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Orphan sweep + credential templates (one final TX)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Soft-deprecate bundle-managed APIs absent from the bundle. The
     * {@code source IN ('import','bundle')} guard is what keeps user-created
     * (custom) APIs out of reach - they are never deprecated by a bundle.
     *
     * <p>The companion tool sweep is scoped to the APIs deprecated IN THIS
     * sweep (by their ids) - NOT to every already-deprecated bundle-managed
     * API, so tools manually resurrected on a long-deprecated API are left
     * alone.
     *
     * @return {@code {apisDeprecated, toolsDeprecated}}
     */
    private int[] deprecateOrphanApis(List<UUID> presentApiIds) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String notIn = "";
        if (!presentApiIds.isEmpty()) {
            notIn = " AND id NOT IN (:presentIds)";
            params.addValue("presentIds", presentApiIds);
        }
        List<UUID> orphanIds = jdbc.queryForList("""
                SELECT id FROM catalog.apis
                 WHERE source IN ('import','bundle')
                   AND deprecated_at IS NULL""" + notIn, params, UUID.class);
        if (orphanIds.isEmpty()) {
            return new int[]{0, 0};
        }
        MapSqlParameterSource orphanParams = new MapSqlParameterSource("orphanIds", orphanIds);
        // Repeat the SELECT's predicates so a row flipped to custom (or already
        // deprecated) between the two statements cannot be touched - same
        // defense-in-depth class as the upsert count guard.
        int apis = jdbc.update("""
                UPDATE catalog.apis
                   SET deprecated_at = NOW()
                 WHERE id IN (:orphanIds)
                   AND source IN ('import','bundle')
                   AND deprecated_at IS NULL""", orphanParams);
        // Tools of an API deprecated by THIS sweep are deprecated with it so
        // the lexical search join can filter on either side cheaply.
        int tools = jdbc.update("""
                UPDATE catalog.api_tools
                   SET deprecated_at = NOW()
                 WHERE api_id IN (:orphanIds)
                   AND deprecated_at IS NULL""", orphanParams);
        return new int[]{apis, tools};
    }

    private int upsertTemplates(List<Map<String, Object>> templateMaps) {
        int upserted = 0;
        for (Map<String, Object> t : templateMaps) {
            jdbc.update("""
                    INSERT INTO catalog.credentials (
                        id, credential_name, variant, display_name, description, credential_type,
                        auth_type, test_endpoint, documentation_url, icon_url, icon_slug,
                        properties, extends_, metadata, deprecated_at, created_at, updated_at)
                    VALUES (
                        gen_random_uuid(), :credentialName, :variant, :displayName, :description,
                        :credentialType, :authType, :testEndpoint, :documentationUrl, :iconUrl,
                        :iconSlug, COALESCE(:properties::jsonb, '{}'::jsonb),
                        COALESCE(:extends::jsonb, '{}'::jsonb),
                        COALESCE(:metadata::jsonb, '{}'::jsonb), NULL,
                        EXTRACT(EPOCH FROM NOW()) * 1000, EXTRACT(EPOCH FROM NOW()) * 1000)
                    ON CONFLICT (credential_name, variant) DO UPDATE SET
                        display_name = EXCLUDED.display_name,
                        description = EXCLUDED.description,
                        credential_type = EXCLUDED.credential_type,
                        auth_type = EXCLUDED.auth_type,
                        test_endpoint = EXCLUDED.test_endpoint,
                        documentation_url = EXCLUDED.documentation_url,
                        icon_url = EXCLUDED.icon_url,
                        icon_slug = EXCLUDED.icon_slug,
                        properties = EXCLUDED.properties,
                        extends_ = EXCLUDED.extends_,
                        metadata = EXCLUDED.metadata,
                        deprecated_at = NULL,
                        updated_at = EXTRACT(EPOCH FROM NOW()) * 1000
                    """, new MapSqlParameterSource()
                    .addValue("credentialName", str(t, "credentialName"))
                    .addValue("variant", Objects.requireNonNullElse(str(t, "variant"), "primary"))
                    .addValue("displayName", str(t, "displayName"))
                    .addValue("description", str(t, "description"))
                    .addValue("credentialType", str(t, "credentialType"))
                    .addValue("authType", str(t, "authType"))
                    .addValue("testEndpoint", str(t, "testEndpoint"))
                    .addValue("documentationUrl", str(t, "documentationUrl"))
                    .addValue("iconUrl", str(t, "iconUrl"))
                    .addValue("iconSlug", str(t, "iconSlug"))
                    .addValue("properties", str(t, "properties"))
                    .addValue("extends", str(t, "extends"))
                    .addValue("metadata", str(t, "metadata")));
            upserted++;
        }
        return upserted;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String findExistingSource(UUID apiId) {
        List<String> sources = jdbc.queryForList(
                "SELECT source FROM catalog.apis WHERE id = :id",
                new MapSqlParameterSource("id", apiId), String.class);
        return sources.isEmpty() ? null : sources.get(0);
    }

    static boolean isBundleManaged(String source) {
        return "import".equals(source) || "bundle".equals(source);
    }

    private UUID firstUuid(String sql, MapSqlParameterSource params) {
        List<UUID> found = jdbc.queryForList(sql, params, UUID.class);
        return found.isEmpty() ? null : found.get(0);
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : v.toString();
    }

    private static Boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object v = map.get(key);
        if (v == null) return fallback;
        return v instanceof Boolean b ? b : Boolean.valueOf(v.toString());
    }

    private static Integer integer(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? null : (v instanceof Number n ? n.intValue() : Integer.valueOf(v.toString()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        return v instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }
}
