package com.apimarketplace.catalog.repository;

import com.apimarketplace.catalog.util.ApiScopedSearchParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

/**
 * Repository for optimized lexical search using the dedicated lexical_search_index table.
 *
 * Optimization notes:
 * - Uses 'english' tokenizer for stemming (send=sending, create=creates)
 * - Uses single tsv_combined with embedded weights (no manual multipliers)
 * - Includes tool_name in all queries to avoid extra lookups
 *
 * Every search query also filters {@code deprecated_at IS NULL} on both the
 * tool and its API (V331 API-catalog bundle soft-delete): bundle-deprecated
 * rows disappear from search but stay executable by UUID.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class LexicalSearchIndexRepository {

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private static final List<String> EMPTY_API_FILTERS = List.of();

    /**
     * Visibility filter: public APIs visible to all, private APIs visible only to their creator.
     * When tenantId is null, only public APIs are shown (system/internal queries).
     */
    private static String visibilityFilter(String tenantId) {
        if (tenantId == null) {
            return " AND a.visibility = 'public'";
        }
        return " AND (a.visibility = 'public' OR a.created_by = :tenantId)";
    }

    private static void addTenantParam(MapSqlParameterSource params, String tenantId) {
        if (tenantId != null) {
            params.addValue("tenantId", tenantId);
        }
    }

    private static List<String> cleanApiFilters(Collection<String> apiFilters) {
        if (apiFilters == null || apiFilters.isEmpty()) {
            return EMPTY_API_FILTERS;
        }
        return apiFilters.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .distinct()
            .limit(10)
            .toList();
    }

    private static void addApiFilterParams(MapSqlParameterSource params, Collection<String> apiFilters) {
        List<String> cleaned = cleanApiFilters(apiFilters);
        if (cleaned.isEmpty()) {
            return;
        }
        params.addValue("apiFiltersLower", cleaned.stream()
            .map(s -> s.toLowerCase(Locale.ROOT))
            .toList());
        List<String> compactFilters = cleaned.stream()
            .map(ApiScopedSearchParser::compactIdentifier)
            .filter(s -> !s.isBlank())
            .toList();
        params.addValue("apiFiltersCompact", compactFilters.isEmpty() ? List.of("__nomatch__") : compactFilters);
    }

    private static String apiFilterClause(Collection<String> apiFilters) {
        if (cleanApiFilters(apiFilters).isEmpty()) {
            return "";
        }
        return """
             AND (
                LOWER(a.api_name) IN (:apiFiltersLower)
                OR LOWER(COALESCE(a.api_slug, '')) IN (:apiFiltersLower)
                OR LOWER(COALESCE(a.icon_slug, '')) IN (:apiFiltersLower)
                OR LOWER(COALESCE(a.platform_credential_name, '')) IN (:apiFiltersLower)
                OR LOWER(COALESCE(lsi.provider, '')) IN (:apiFiltersLower)
                OR REGEXP_REPLACE(LOWER(COALESCE(a.api_name, '')), '[^a-z0-9]', '', 'g') IN (:apiFiltersCompact)
                OR REGEXP_REPLACE(LOWER(COALESCE(a.api_slug, '')), '[^a-z0-9]', '', 'g') IN (:apiFiltersCompact)
                OR REGEXP_REPLACE(LOWER(COALESCE(a.icon_slug, '')), '[^a-z0-9]', '', 'g') IN (:apiFiltersCompact)
                OR REGEXP_REPLACE(LOWER(COALESCE(a.platform_credential_name, '')), '[^a-z0-9]', '', 'g') IN (:apiFiltersCompact)
                OR REGEXP_REPLACE(LOWER(COALESCE(lsi.provider, '')), '[^a-z0-9]', '', 'g') IN (:apiFiltersCompact)
              )
            """;
    }

    /**
     * Build a brand-normalized tsquery (OR-joined) from a free-text user query.
     *
     * <p>The lexical_search_index stores brand-normalized tokens (V159 trigger):
     * {@code "Open Ai" → 'openai'}, {@code "create_image" → 'createimage'}.
     * Those tokens are indexed via the {@code simple} parser (no stemming).</p>
     *
     * <p>This method derives an OR-joined {@code to_tsquery} input from the user
     * query: each whitespace-separated word is lower-cased, stripped of every
     * non-alphanumeric character, joined with {@code |}. Tokens shorter than 2
     * chars are dropped (avoids matching the universe via {@code 'a'}).</p>
     *
     * <p>Returned string is always safe to feed into
     * {@code to_tsquery('simple', ?)}; when no usable token survives, a sentinel
     * that cannot match any row is returned.</p>
     */
    static String buildCompactTsQuery(String query) {
        if (query == null || query.isBlank()) {
            return "__nomatch__";
        }
        String[] words = query.toLowerCase(java.util.Locale.ROOT).split("\\s+");
        java.util.List<String> tokens = new java.util.ArrayList<>(words.length);
        for (String word : words) {
            String stripped = word.replaceAll("[^a-z0-9]", "");
            if (stripped.length() >= 2) {
                tokens.add(stripped);
            }
        }
        if (tokens.isEmpty()) {
            return "__nomatch__";
        }
        return String.join(" | ", tokens);
    }

    /**
     * Primary search method - uses optimized tsv_combined with embedded weights.
     * Includes tool_name to avoid N+1 query for display name.
     */
    public List<Map<String, Object>> searchOptimized(String query, int limit, String tenantId) {
        String sql = """
            SELECT
                lsi.api_tool_id as tool_id,
                lsi.tool_name,
                lsi.provider,
                lsi.resource,
                lsi.action,
                lsi.endpoint,
                COALESCE(lsi.summary_extended, lsi.summary) as summary,
                (
                    ts_rank(lsi.tsv_combined, plainto_tsquery('english', :query))
                    + ts_rank(lsi.tsv_combined, to_tsquery('simple', :compactQuery)) * 1.5
                ) AS bm25
            FROM catalog.lexical_search_index lsi
            JOIN catalog.api_tools at ON lsi.api_tool_id = at.id
            JOIN catalog.apis a ON at.api_id = a.id
            WHERE at.is_active = true AND a.is_active = true
              AND at.deprecated_at IS NULL AND a.deprecated_at IS NULL
              AND (
                lsi.tsv_combined @@ plainto_tsquery('english', :query)
                OR lsi.tsv_combined @@ to_tsquery('simple', :compactQuery)
              )
            """ + visibilityFilter(tenantId) + """
            ORDER BY bm25 DESC
            LIMIT :limit
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("query", query)
            .addValue("compactQuery", buildCompactTsQuery(query))
            .addValue("limit", limit);
        addTenantParam(params, tenantId);

        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> results = namedJdbcTemplate.queryForList(sql, params);
        long t1 = System.currentTimeMillis();
        log.debug("Lexical search took {} ms, rows={}", (t1 - t0), results.size());
        return results;
    }

    /** Backwards-compatible overload (public APIs only). */
    public List<Map<String, Object>> searchOptimized(String query, int limit) {
        return searchOptimized(query, limit, null);
    }

    /**
     * Search with exact filters on provider, resource, action.
     * Includes tool_name to avoid extra lookup.
     */
    public List<Map<String, Object>> searchWithFilters(String query, String provider, String resource, String action, int limit, String tenantId) {
        StringBuilder sql = new StringBuilder();
        sql.append("""
            SELECT
                lsi.api_tool_id as tool_id,
                lsi.tool_name,
                lsi.provider,
                lsi.resource,
                lsi.action,
                lsi.endpoint,
                COALESCE(lsi.summary_extended, lsi.summary) as summary,
                (
                    ts_rank(lsi.tsv_combined, plainto_tsquery('english', :query))
                    + ts_rank(lsi.tsv_combined, to_tsquery('simple', :compactQuery)) * 1.5
                ) AS bm25
            FROM catalog.lexical_search_index lsi
            JOIN catalog.api_tools at ON lsi.api_tool_id = at.id
            JOIN catalog.apis a ON at.api_id = a.id
            WHERE at.is_active = true AND a.is_active = true
              AND at.deprecated_at IS NULL AND a.deprecated_at IS NULL
              AND (
                lsi.tsv_combined @@ plainto_tsquery('english', :query)
                OR lsi.tsv_combined @@ to_tsquery('simple', :compactQuery)
              )
            """);
        sql.append(visibilityFilter(tenantId));

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("query", query)
            .addValue("compactQuery", buildCompactTsQuery(query))
            .addValue("limit", limit);
        addTenantParam(params, tenantId);

        if (provider != null && !provider.trim().isEmpty()) {
            sql.append(" AND provider = :provider");
            params.addValue("provider", provider);
        }
        if (resource != null && !resource.trim().isEmpty()) {
            sql.append(" AND resource = :resource");
            params.addValue("resource", resource);
        }
        if (action != null && !action.trim().isEmpty()) {
            sql.append(" AND action = :action");
            params.addValue("action", action);
        }

        sql.append(" ORDER BY bm25 DESC LIMIT :limit");

        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> results = namedJdbcTemplate.queryForList(sql.toString(), params);
        long t1 = System.currentTimeMillis();
        log.debug("Filtered lexical search took {} ms, rows={}", (t1 - t0), results.size());
        return results;
    }

    /** Backwards-compatible overload (public APIs only). */
    public List<Map<String, Object>> searchWithFilters(String query, String provider, String resource, String action, int limit) {
        return searchWithFilters(query, provider, resource, action, limit, null);
    }

    /**
     * Search optimized for RRF fusion - returns scores for hybrid search.
     */
    public List<Map<String, Object>> searchForRRFFusion(String query, int limit, String tenantId) {
        String sql = """
            SELECT
                lsi.api_tool_id as tool_id,
                lsi.tool_name,
                lsi.provider,
                lsi.resource,
                lsi.action,
                COALESCE(lsi.summary_extended, lsi.summary) as summary,
                (
                    ts_rank(lsi.tsv_combined, plainto_tsquery('english', :query))
                    + ts_rank(lsi.tsv_combined, to_tsquery('simple', :compactQuery)) * 1.5
                ) AS bm25_score
            FROM catalog.lexical_search_index lsi
            JOIN catalog.api_tools at ON lsi.api_tool_id = at.id
            JOIN catalog.apis a ON at.api_id = a.id
            WHERE at.is_active = true AND a.is_active = true
              AND at.deprecated_at IS NULL AND a.deprecated_at IS NULL
              AND (
                lsi.tsv_combined @@ plainto_tsquery('english', :query)
                OR lsi.tsv_combined @@ to_tsquery('simple', :compactQuery)
              )
            """ + visibilityFilter(tenantId) + """
            ORDER BY bm25_score DESC
            LIMIT :limit
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("query", query)
            .addValue("compactQuery", buildCompactTsQuery(query))
            .addValue("limit", limit);
        addTenantParam(params, tenantId);

        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> results = namedJdbcTemplate.queryForList(sql, params);
        long t1 = System.currentTimeMillis();
        log.debug("RRF fusion search took {} ms, rows={}", (t1 - t0), results.size());
        return results;
    }

    /** Backwards-compatible overload (public APIs only). */
    public List<Map<String, Object>> searchForRRFFusion(String query, int limit) {
        return searchForRRFFusion(query, limit, null);
    }

    /**
     * Get statistics about the lexical search index.
     */
    public Map<String, Object> getIndexStats() {
        String sql = """
            SELECT
                COUNT(*) as total_entries,
                COUNT(DISTINCT provider) as unique_providers,
                COUNT(DISTINCT resource) as unique_resources,
                COUNT(DISTINCT action) as unique_actions,
                AVG(LENGTH(COALESCE(summary_extended, summary))) as avg_summary_length,
                MAX(updated_at) as last_updated
            FROM catalog.lexical_search_index
            """;

        return jdbcTemplate.queryForMap(sql);
    }

    /**
     * Get all unique values for a specific field.
     */
    public List<String> getUniqueValues(String field) {
        // Validate field to prevent SQL injection
        String allowedFields = "provider,resource,action,category,subcategory";
        if (!allowedFields.contains(field.toLowerCase())) {
            throw new IllegalArgumentException("Invalid field: " + field);
        }
        String sql = String.format("SELECT DISTINCT %s FROM catalog.lexical_search_index WHERE %s IS NOT NULL ORDER BY %s", field, field, field);
        return jdbcTemplate.queryForList(sql, String.class);
    }

    /**
     * Get lexical data by tool ID for fallback purposes.
     */
    public Optional<Map<String, Object>> getByToolId(UUID toolId) {
        String sql = """
            SELECT tool_name, provider, resource, action, endpoint,
                   COALESCE(summary_extended, summary) as summary
            FROM catalog.lexical_search_index
            WHERE api_tool_id = :toolId
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("toolId", toolId);

        List<Map<String, Object>> results = namedJdbcTemplate.queryForList(sql, params);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Batch load lexical data for multiple tool IDs.
     * Returns map keyed by String (tool_id.toString()) for compatibility.
     */
    public Map<String, Map<String, Object>> batchGetByToolIds(Collection<UUID> toolIds) {
        if (toolIds == null || toolIds.isEmpty()) {
            return Collections.emptyMap();
        }

        String sql = """
            SELECT api_tool_id as tool_id, tool_name, provider, resource, action, endpoint,
                   COALESCE(summary_extended, summary) as summary
            FROM catalog.lexical_search_index
            WHERE api_tool_id IN (:toolIds)
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("toolIds", toolIds);

        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> results = namedJdbcTemplate.queryForList(sql, params);
        long t1 = System.currentTimeMillis();
        log.debug("Batch lexical load for {} IDs took {} ms", toolIds.size(), (t1 - t0));

        Map<String, Map<String, Object>> resultMap = new HashMap<>();
        for (Map<String, Object> row : results) {
            Object toolIdObj = row.get("tool_id");
            if (toolIdObj != null) {
                resultMap.put(toolIdObj.toString(), row);
            }
        }
        return resultMap;
    }

    /**
     * Batch load tool names for multiple tool IDs.
     * Returns map keyed by String (tool_id.toString()).
     */
    public Map<String, String> batchGetToolNames(Collection<UUID> toolIds) {
        if (toolIds == null || toolIds.isEmpty()) {
            return Collections.emptyMap();
        }

        String sql = """
            SELECT api_tool_id as tool_id, tool_name
            FROM catalog.lexical_search_index
            WHERE api_tool_id IN (:toolIds)
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("toolIds", toolIds);

        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> results = namedJdbcTemplate.queryForList(sql, params);
        long t1 = System.currentTimeMillis();
        log.debug("Batch tool names load for {} IDs took {} ms", toolIds.size(), (t1 - t0));

        Map<String, String> resultMap = new HashMap<>();
        for (Map<String, Object> row : results) {
            Object toolIdObj = row.get("tool_id");
            Object toolNameObj = row.get("tool_name");
            if (toolIdObj != null) {
                resultMap.put(toolIdObj.toString(), toolNameObj != null ? toolNameObj.toString() : "Unknown Tool");
            }
        }
        return resultMap;
    }

    /**
     * Check if a tool exists in the lexical index.
     */
    public boolean existsByToolId(UUID toolId) {
        String sql = "SELECT EXISTS(SELECT 1 FROM catalog.lexical_search_index WHERE api_tool_id = :toolId)";
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("toolId", toolId);
        return Boolean.TRUE.equals(namedJdbcTemplate.queryForObject(sql, params, Boolean.class));
    }

    // ========== AI Agent Search Methods ==========

    /**
     * Execute optimized full-text search with all quality boosters.
     * Uses tsv_combined which contains all fields weighted by the DB trigger.
     *
     * @param query The search query
     * @param provider Optional provider hint for boosting
     * @param action Optional action hint for boosting
     * @param limit Max results to return
     * @return List of matching tools with scores
     */
    public List<Map<String, Object>> searchOptimizedWithScoring(String query, String provider, String action, int limit, String tenantId) {
        return searchOptimizedWithScoring(query, provider, action, EMPTY_API_FILTERS, limit, tenantId);
    }

    public List<Map<String, Object>> searchOptimizedWithScoring(String query, String provider, String action,
                                                                 Collection<String> apiFilters, int limit, String tenantId) {
        StringBuilder sql = new StringBuilder();
        MapSqlParameterSource params = new MapSqlParameterSource();

        sql.append("""
            SELECT
                lsi.api_tool_id as id,
                lsi.tool_name,
                a.api_name,
                a.api_slug,
                lsi.provider,
                lsi.resource,
                lsi.action,
                lsi.summary,
                lsi.params_required,
                lsi.params_optional,
                COALESCE(a.icon_slug, 'mcp') as icon_slug,
                (
                    COALESCE(ts_rank(tsv_combined, plainto_tsquery('english', :query), 32), 0) * 10.0 +
                    COALESCE(ts_rank(tsv_combined, phraseto_tsquery('english', :queryPhrase), 32), 0) * 5.0 +
                    COALESCE(ts_rank(tsv_combined, to_tsquery('simple', :compactQuery), 32), 0) * 8.0
            """);

        params.addValue("query", query);
        params.addValue("queryPhrase", query);

        if (provider != null) {
            sql.append(" + CASE WHEN LOWER(provider) = :providerHint OR LOWER(REPLACE(provider, ' ', '')) = REPLACE(:providerHint, ' ', '') THEN 3.0 ELSE 0 END");
            params.addValue("providerHint", provider);
        }

        if (action != null) {
            sql.append(" + CASE WHEN LOWER(action) = :actionHint THEN 2.0 ELSE 0 END");
            params.addValue("actionHint", action);
        }

        sql.append("""
                ) AS score
            FROM catalog.lexical_search_index lsi
            JOIN catalog.api_tools at ON lsi.api_tool_id = at.id
            JOIN catalog.apis a ON at.api_id = a.id
            WHERE at.is_active = true AND a.is_active = true
              AND at.deprecated_at IS NULL AND a.deprecated_at IS NULL
              AND (
                lsi.tsv_combined @@ plainto_tsquery('english', :queryFilter)
                OR lsi.tsv_combined @@ to_tsquery('simple', :compactQueryFilter)
              )
            """);
        sql.append(visibilityFilter(tenantId));
        sql.append(apiFilterClause(apiFilters));
        sql.append("""
            ORDER BY score DESC
            LIMIT :limit
            """);

        String compactQuery = buildCompactTsQuery(query);
        params.addValue("queryFilter", query);
        params.addValue("compactQuery", compactQuery);
        params.addValue("compactQueryFilter", compactQuery);
        params.addValue("limit", limit);
        addTenantParam(params, tenantId);
        addApiFilterParams(params, apiFilters);

        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> results = namedJdbcTemplate.queryForList(sql.toString(), params);
        long t1 = System.currentTimeMillis();
        log.debug("Optimized search took {} ms, rows={}", (t1 - t0), results.size());
        return results;
    }

    /** Backwards-compatible overload (public APIs only). */
    public List<Map<String, Object>> searchOptimizedWithScoring(String query, String provider, String action, int limit) {
        return searchOptimizedWithScoring(query, provider, action, EMPTY_API_FILTERS, limit, null);
    }

    /**
     * Fuzzy fallback using pg_trgm similarity for typo tolerance.
     * Uses lower thresholds (0.1) to catch more partial matches.
     *
     * @param query The search query
     * @param provider Optional provider filter
     * @param limit Max results to return
     * @return List of matching tools with fuzzy scores
     */
    public List<Map<String, Object>> searchFuzzy(String query, String provider, int limit, String tenantId) {
        return searchFuzzy(query, provider, EMPTY_API_FILTERS, limit, tenantId);
    }

    public List<Map<String, Object>> searchFuzzy(String query, String provider, Collection<String> apiFilters,
                                                  int limit, String tenantId) {
        StringBuilder sql = new StringBuilder();
        MapSqlParameterSource params = new MapSqlParameterSource();

        sql.append("""
            SELECT
                lsi.api_tool_id as id,
                lsi.tool_name,
                a.api_name,
                a.api_slug,
                lsi.provider,
                lsi.resource,
                lsi.action,
                lsi.summary,
                lsi.params_required,
                lsi.params_optional,
                COALESCE(a.icon_slug, 'mcp') as icon_slug,
                (
                    COALESCE(similarity(LOWER(lsi.provider), :q1), 0) * 4.0 +
                    COALESCE(similarity(LOWER(lsi.tool_name), :q2), 0) * 4.0 +
                    COALESCE(similarity(LOWER(lsi.resource), :q3), 0) * 3.0 +
                    COALESCE(similarity(LOWER(lsi.action), :q4), 0) * 2.0 +
                    COALESCE(similarity(LOWER(lsi.subcategory), :q5), 0) * 3.0
                ) AS score
            FROM catalog.lexical_search_index lsi
            JOIN catalog.api_tools at ON lsi.api_tool_id = at.id
            JOIN catalog.apis a ON at.api_id = a.id
            WHERE at.is_active = true AND a.is_active = true
              AND at.deprecated_at IS NULL AND a.deprecated_at IS NULL
              AND (
                similarity(LOWER(lsi.provider), :q6) > 0.1
                OR similarity(LOWER(lsi.tool_name), :q7) > 0.1
                OR similarity(LOWER(lsi.resource), :q8) > 0.1
                OR similarity(LOWER(lsi.subcategory), :q9) > 0.1
                OR similarity(LOWER(lsi.action), :q10) > 0.1
                OR LOWER(lsi.summary) LIKE '%' || LOWER(:q11) || '%'
              )
            """);
        sql.append(visibilityFilter(tenantId));
        sql.append(apiFilterClause(apiFilters));

        for (int i = 1; i <= 11; i++) {
            params.addValue("q" + i, query);
        }
        addTenantParam(params, tenantId);
        addApiFilterParams(params, apiFilters);

        if (provider != null) {
            sql.append(" AND similarity(LOWER(lsi.provider), :providerFilter) > 0.2");
            params.addValue("providerFilter", provider);
        }

        sql.append(" ORDER BY score DESC LIMIT :limit");
        params.addValue("limit", limit);

        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> results = namedJdbcTemplate.queryForList(sql.toString(), params);
        long t1 = System.currentTimeMillis();
        log.debug("Fuzzy search took {} ms, rows={}", (t1 - t0), results.size());
        return results;
    }

    /** Backwards-compatible overload (public APIs only). */
    public List<Map<String, Object>> searchFuzzy(String query, String provider, int limit) {
        return searchFuzzy(query, provider, EMPTY_API_FILTERS, limit, null);
    }

    /**
     * Last resort: search by provider only.
     *
     * @param provider The provider name
     * @param limit Max results to return
     * @return List of tools from the provider
     */
    public List<Map<String, Object>> searchByProvider(String provider, int limit, String tenantId) {
        return searchByProvider(provider, EMPTY_API_FILTERS, limit, tenantId);
    }

    public List<Map<String, Object>> searchByProvider(String provider, Collection<String> apiFilters, int limit, String tenantId) {
        String sql = """
            SELECT
                lsi.api_tool_id as id,
                lsi.tool_name,
                a.api_name,
                a.api_slug,
                lsi.provider,
                lsi.resource,
                lsi.action,
                lsi.summary,
                lsi.params_required,
                lsi.params_optional,
                COALESCE(a.icon_slug, 'mcp') as icon_slug,
                1.0 AS score
            FROM catalog.lexical_search_index lsi
            JOIN catalog.api_tools at ON lsi.api_tool_id = at.id
            JOIN catalog.apis a ON at.api_id = a.id
            WHERE at.is_active = true AND a.is_active = true
              AND at.deprecated_at IS NULL AND a.deprecated_at IS NULL
              AND LOWER(lsi.provider) LIKE '%' || :provider || '%'
            """ + visibilityFilter(tenantId) + apiFilterClause(apiFilters) + """
            ORDER BY lsi.resource, lsi.action
            LIMIT :limit
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("provider", provider)
            .addValue("limit", limit);
        addTenantParam(params, tenantId);
        addApiFilterParams(params, apiFilters);

        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> results = namedJdbcTemplate.queryForList(sql, params);
        long t1 = System.currentTimeMillis();
        log.debug("Provider search took {} ms, rows={}", (t1 - t0), results.size());
        return results;
    }

    public List<Map<String, Object>> searchByApiFilters(Collection<String> apiFilters, int limit, String tenantId) {
        List<String> cleaned = cleanApiFilters(apiFilters);
        if (cleaned.isEmpty()) {
            return List.of();
        }

        String sql = """
            SELECT
                lsi.api_tool_id as id,
                lsi.tool_name,
                a.api_name,
                a.api_slug,
                lsi.provider,
                lsi.resource,
                lsi.action,
                lsi.summary,
                lsi.params_required,
                lsi.params_optional,
                COALESCE(a.icon_slug, 'mcp') as icon_slug,
                1.0 AS score
            FROM catalog.lexical_search_index lsi
            JOIN catalog.api_tools at ON lsi.api_tool_id = at.id
            JOIN catalog.apis a ON at.api_id = a.id
            WHERE at.is_active = true AND a.is_active = true
              AND at.deprecated_at IS NULL AND a.deprecated_at IS NULL
            """ + visibilityFilter(tenantId) + apiFilterClause(cleaned) + """
            ORDER BY lsi.resource, lsi.action
            LIMIT :limit
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
            .addValue("limit", limit);
        addTenantParam(params, tenantId);
        addApiFilterParams(params, cleaned);

        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> results = namedJdbcTemplate.queryForList(sql, params);
        long t1 = System.currentTimeMillis();
        log.debug("API-filter search took {} ms, rows={}", (t1 - t0), results.size());
        return results;
    }

    /** Backwards-compatible overload (public APIs only). */
    public List<Map<String, Object>> searchByProvider(String provider, int limit) {
        return searchByProvider(provider, EMPTY_API_FILTERS, limit, null);
    }
}
