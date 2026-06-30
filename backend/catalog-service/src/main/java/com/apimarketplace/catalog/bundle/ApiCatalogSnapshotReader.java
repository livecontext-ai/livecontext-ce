package com.apimarketplace.catalog.bundle;

import com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload.ApiRow;
import com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload.CredentialTemplateRow;
import com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload.ParameterRow;
import com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload.ResponseRow;
import com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload.ToolCredentialRow;
import com.apimarketplace.catalog.bundle.ApiCatalogBundlePayload.ToolRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cloud-side: read the bundle-eligible slice of {@code catalog.*} into the
 * payload row model.
 *
 * <p><b>Snapshot scope:</b> {@code apis} rows with
 * {@code source IN ('import','bundle') AND deprecated_at IS NULL} (custom /
 * tenant-created APIs - {@code source='custom'} - never ship), their
 * non-deprecated tools plus parameters / responses / tool-credential links,
 * and the credential templates that back a snapshotted API (matched by
 * {@code platform_credential_name}) or belong to the native core-node set
 * ({@code smtp}, {@code imap} - configurable templates with no backing API).
 *
 * <p>Reads run through plain SQL (one query per table, joined in memory) so a
 * 600-API snapshot is 6 queries, not 600×N. JSONB columns come back as their
 * raw text - the payload ships them verbatim.
 */
@Component
@RequiredArgsConstructor
public class ApiCatalogSnapshotReader {

    /** Mirrors {@code CredentialTemplateController.NATIVE_CORE_CREDENTIAL_NAMES}. */
    static final List<String> NATIVE_CORE_CREDENTIAL_NAMES = List.of("smtp", "imap");

    private final JdbcTemplate jdbcTemplate;

    public record Snapshot(List<ApiRow> apis, List<CredentialTemplateRow> credentialTemplates) {
        public int apiCount() { return apis.size(); }
        public int toolCount() {
            return apis.stream().mapToInt(a -> a.tools() == null ? 0 : a.tools().size()).sum();
        }
    }

    public Snapshot snapshot() {
        // 1. Bundle-managed, live APIs with categories resolved to name+slug.
        List<Map<String, Object>> apiRows = jdbcTemplate.queryForList("""
                SELECT a.id, a.api_name, a.api_slug, a.description, a.base_url,
                       a.healthcheck_endpoint, a.auth_type, a.auth_header_name, a.auth_header_value,
                       a.visibility, a.is_public, a.is_active, a.is_local, a.pricing_model,
                       a.status, a.version, a.icon_slug, a.platform_credential_name,
                       a.icon_url, a.api_version, a.documentation, a.rate_limits::text AS rate_limits,
                       c.name AS category_name, c.slug AS category_slug,
                       s.name AS subcategory_name, s.slug AS subcategory_slug
                  FROM catalog.apis a
                  LEFT JOIN catalog.api_categories c ON c.id = a.category_id
                  LEFT JOIN catalog.api_subcategories s ON s.id = a.subcategory_id
                 WHERE a.source IN ('import','bundle')
                   AND a.deprecated_at IS NULL
                """);
        if (apiRows.isEmpty()) {
            return new Snapshot(List.of(), List.of());
        }

        // 2. Their live tools.
        List<Map<String, Object>> toolRows = jdbcTemplate.queryForList("""
                SELECT t.id, t.api_id, t.tool_slug, t.description, t.tool_name_id, t.method,
                       t.endpoint, t.protocol, t.default_headers, t.runtime_metadata,
                       t.execution_spec::text AS execution_spec, t.output_schema::text AS output_schema,
                       t.execution_mode, t.pagination::text AS pagination,
                       t.required_scopes::text AS required_scopes, t.next_hint, t.status,
                       t.test_status, t.is_active, t.version
                  FROM catalog.api_tools t
                  JOIN catalog.apis a ON a.id = t.api_id
                 WHERE a.source IN ('import','bundle')
                   AND a.deprecated_at IS NULL
                   AND t.deprecated_at IS NULL
                """);

        // 3-5. Children of those tools.
        List<Map<String, Object>> paramRows = jdbcTemplate.queryForList("""
                SELECT p.id, p.api_tool_id, p.parameter_type, p.name, p.data_type, p.is_required,
                       p.description, p.example_value, p.default_value, p.allowed_values,
                       p.file_path, p.extras, p.is_hidden
                  FROM catalog.api_tool_parameters p
                  JOIN catalog.api_tools t ON t.id = p.api_tool_id
                  JOIN catalog.apis a ON a.id = t.api_id
                 WHERE a.source IN ('import','bundle')
                   AND a.deprecated_at IS NULL
                   AND t.deprecated_at IS NULL
                """);
        List<Map<String, Object>> responseRows = jdbcTemplate.queryForList("""
                SELECT r.id, r.tool_id, r.name, r.description, r.schema::text AS schema,
                       r.example, r.example_jsonb::text AS example_jsonb,
                       r.structure_skeleton::text AS structure_skeleton, r.status_code,
                       r.is_default, r.format, r.is_active
                  FROM catalog.tool_responses r
                  JOIN catalog.api_tools t ON t.id = r.tool_id
                  JOIN catalog.apis a ON a.id = t.api_id
                 WHERE a.source IN ('import','bundle')
                   AND a.deprecated_at IS NULL
                   AND t.deprecated_at IS NULL
                """);
        List<Map<String, Object>> toolCredRows = jdbcTemplate.queryForList("""
                SELECT tc.api_tool_id, tc.credential_name, tc.variant, tc.is_required, tc.usage,
                       tc.condition::text AS condition, tc.metadata::text AS metadata
                  FROM catalog.tool_credentials tc
                  JOIN catalog.api_tools t ON t.id = tc.api_tool_id
                  JOIN catalog.apis a ON a.id = t.api_id
                 WHERE a.source IN ('import','bundle')
                   AND a.deprecated_at IS NULL
                   AND t.deprecated_at IS NULL
                """);

        // 6. Credential templates backing a snapshotted API, plus the native
        //    core-node templates. Custom-API templates never ship.
        String nativeInList = "'" + String.join("','", NATIVE_CORE_CREDENTIAL_NAMES) + "'";
        List<Map<String, Object>> templateRows = jdbcTemplate.queryForList("""
                SELECT c.credential_name, c.variant, c.display_name, c.description,
                       c.credential_type, c.auth_type, c.test_endpoint, c.documentation_url,
                       c.icon_url, c.icon_slug, c.properties::text AS properties,
                       c.extends_::text AS extends_, c.metadata::text AS metadata
                  FROM catalog.credentials c
                 WHERE c.deprecated_at IS NULL
                   AND (EXISTS (SELECT 1 FROM catalog.apis a
                                 WHERE a.platform_credential_name = c.credential_name
                                   AND a.source IN ('import','bundle')
                                   AND a.deprecated_at IS NULL)
                        OR c.credential_name IN (%s))
                """.formatted(nativeInList));

        // Assemble in memory: tool children keyed by tool id, tools by api id.
        Map<UUID, List<ParameterRow>> paramsByTool = new HashMap<>();
        for (Map<String, Object> r : paramRows) {
            paramsByTool.computeIfAbsent(uuid(r, "api_tool_id"), k -> new ArrayList<>())
                    .add(new ParameterRow(
                            uuid(r, "id"), str(r, "parameter_type"), str(r, "name"),
                            str(r, "data_type"), bool(r, "is_required"), str(r, "description"),
                            str(r, "example_value"), str(r, "default_value"), str(r, "allowed_values"),
                            str(r, "file_path"), str(r, "extras"), bool(r, "is_hidden")));
        }
        Map<UUID, List<ResponseRow>> responsesByTool = new HashMap<>();
        for (Map<String, Object> r : responseRows) {
            responsesByTool.computeIfAbsent(uuid(r, "tool_id"), k -> new ArrayList<>())
                    .add(new ResponseRow(
                            uuid(r, "id"), str(r, "name"), str(r, "description"), str(r, "schema"),
                            str(r, "example"), str(r, "example_jsonb"), str(r, "structure_skeleton"),
                            integer(r, "status_code"), bool(r, "is_default"), str(r, "format"),
                            bool(r, "is_active")));
        }
        Map<UUID, List<ToolCredentialRow>> credsByTool = new HashMap<>();
        for (Map<String, Object> r : toolCredRows) {
            credsByTool.computeIfAbsent(uuid(r, "api_tool_id"), k -> new ArrayList<>())
                    .add(new ToolCredentialRow(
                            str(r, "credential_name"), str(r, "variant"), bool(r, "is_required"),
                            str(r, "usage"), str(r, "condition"), str(r, "metadata")));
        }
        Map<UUID, List<ToolRow>> toolsByApi = new HashMap<>();
        for (Map<String, Object> r : toolRows) {
            UUID toolId = uuid(r, "id");
            toolsByApi.computeIfAbsent(uuid(r, "api_id"), k -> new ArrayList<>())
                    .add(new ToolRow(
                            toolId, str(r, "tool_slug"), str(r, "description"), str(r, "tool_name_id"),
                            str(r, "method"), str(r, "endpoint"), str(r, "protocol"),
                            str(r, "default_headers"), str(r, "runtime_metadata"),
                            str(r, "execution_spec"), str(r, "output_schema"), str(r, "execution_mode"),
                            str(r, "pagination"), str(r, "required_scopes"), str(r, "next_hint"),
                            str(r, "status"), str(r, "test_status"), bool(r, "is_active"),
                            str(r, "version"),
                            paramsByTool.getOrDefault(toolId, List.of()),
                            responsesByTool.getOrDefault(toolId, List.of()),
                            credsByTool.getOrDefault(toolId, List.of())));
        }

        List<ApiRow> apis = new ArrayList<>(apiRows.size());
        for (Map<String, Object> r : apiRows) {
            UUID apiId = uuid(r, "id");
            apis.add(new ApiRow(
                    apiId, str(r, "api_name"), str(r, "api_slug"), str(r, "description"),
                    str(r, "base_url"), str(r, "healthcheck_endpoint"),
                    str(r, "category_name"), str(r, "category_slug"),
                    str(r, "subcategory_name"), str(r, "subcategory_slug"),
                    str(r, "auth_type"), str(r, "auth_header_name"), str(r, "auth_header_value"),
                    str(r, "visibility"), bool(r, "is_public"), bool(r, "is_active"),
                    bool(r, "is_local"), str(r, "pricing_model"), str(r, "status"),
                    str(r, "version"), str(r, "icon_slug"), str(r, "platform_credential_name"),
                    str(r, "icon_url"), str(r, "api_version"), str(r, "documentation"),
                    str(r, "rate_limits"),
                    toolsByApi.getOrDefault(apiId, List.of())));
        }

        List<CredentialTemplateRow> templates = new ArrayList<>(templateRows.size());
        for (Map<String, Object> r : templateRows) {
            templates.add(new CredentialTemplateRow(
                    str(r, "credential_name"), str(r, "variant"), str(r, "display_name"),
                    str(r, "description"), str(r, "credential_type"), str(r, "auth_type"),
                    str(r, "test_endpoint"), str(r, "documentation_url"), str(r, "icon_url"),
                    str(r, "icon_slug"), str(r, "properties"), str(r, "extends_"),
                    str(r, "metadata")));
        }

        return new Snapshot(apis, templates);
    }

    private static UUID uuid(Map<String, Object> row, String key) {
        Object v = row.get(key);
        if (v == null) return null;
        return v instanceof UUID u ? u : UUID.fromString(v.toString());
    }

    private static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : v.toString();
    }

    private static Boolean bool(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : (v instanceof Boolean b ? b : Boolean.valueOf(v.toString()));
    }

    private static Integer integer(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : (v instanceof Number n ? n.intValue() : Integer.valueOf(v.toString()));
    }
}
