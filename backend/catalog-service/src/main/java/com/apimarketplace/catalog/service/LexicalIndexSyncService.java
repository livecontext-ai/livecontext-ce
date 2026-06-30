package com.apimarketplace.catalog.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Service for synchronizing lexical search index.
 * Consolidated to single sync method to avoid DRY violations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LexicalIndexSyncService {

    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final JdbcTemplate jdbcTemplate;

    private static final int MAX_TOOL_NAME = 255;
    private static final int MAX_PROVIDER = 100;
    private static final int MAX_RESOURCE = 100;
    private static final int MAX_ACTION = 50;
    private static final int MAX_SUMMARY = 2000;
    private static final int MAX_SUMMARY_EXTENDED = 4000;
    private static final int MAX_KEYWORDS = 1000;

    /**
     * Sync data record - single structure for all sync operations.
     */
    public record SyncData(
        String toolName,
        String provider,
        String resource,
        String action,
        String endpoint,
        List<String> paramsRequired,
        List<String> paramsOptional,
        List<String> paramExamples,
        String summary,
        String summaryExtended,
        String keywords,
        List<String> keywordsPrimary,
        List<String> keywordsSynonyms,
        List<String> keywordsParams,
        List<String> useCases,
        String category,
        String subcategory
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String toolName;
            private String provider;
            private String resource;
            private String action;
            private String endpoint;
            private List<String> paramsRequired;
            private List<String> paramsOptional;
            private List<String> paramExamples;
            private String summary;
            private String summaryExtended;
            private String keywords;
            private List<String> keywordsPrimary;
            private List<String> keywordsSynonyms;
            private List<String> keywordsParams;
            private List<String> useCases;
            private String category;
            private String subcategory;

            public Builder toolName(String v) { this.toolName = v; return this; }
            public Builder provider(String v) { this.provider = v; return this; }
            public Builder resource(String v) { this.resource = v; return this; }
            public Builder action(String v) { this.action = v; return this; }
            public Builder endpoint(String v) { this.endpoint = v; return this; }
            public Builder paramsRequired(List<String> v) { this.paramsRequired = v; return this; }
            public Builder paramsOptional(List<String> v) { this.paramsOptional = v; return this; }
            public Builder paramExamples(List<String> v) { this.paramExamples = v; return this; }
            public Builder summary(String v) { this.summary = v; return this; }
            public Builder summaryExtended(String v) { this.summaryExtended = v; return this; }
            public Builder keywords(String v) { this.keywords = v; return this; }
            public Builder keywordsPrimary(List<String> v) { this.keywordsPrimary = v; return this; }
            public Builder keywordsSynonyms(List<String> v) { this.keywordsSynonyms = v; return this; }
            public Builder keywordsParams(List<String> v) { this.keywordsParams = v; return this; }
            public Builder useCases(List<String> v) { this.useCases = v; return this; }
            public Builder category(String v) { this.category = v; return this; }
            public Builder subcategory(String v) { this.subcategory = v; return this; }

            public SyncData build() {
                return new SyncData(toolName, provider, resource, action, endpoint,
                    paramsRequired, paramsOptional, paramExamples,
                    summary, summaryExtended, keywords,
                    keywordsPrimary, keywordsSynonyms, keywordsParams, useCases,
                    category, subcategory);
            }
        }
    }

    /**
     * Main sync method - single entry point for all sync operations.
     * Uses UPSERT to insert or update lexical index entry.
     */
    @Transactional
    public void sync(UUID apiToolId, SyncData data) {
        try {
            log.info("Syncing lexical index for tool: {} ({})", apiToolId, data.toolName());

            String sql = """
                INSERT INTO catalog.lexical_search_index
                (api_tool_id, tool_name, provider, resource, action, endpoint,
                 params_required, params_optional, param_examples,
                 summary, summary_extended, keywords,
                 keywords_primary, keywords_synonyms, keywords_params, use_cases,
                 category, subcategory)
                VALUES (:apiToolId, :toolName, :provider, :resource, :action, :endpoint,
                        :paramsRequired, :paramsOptional, :paramExamples,
                        :summary, :summaryExtended, :keywords,
                        :keywordsPrimary, :keywordsSynonyms, :keywordsParams, :useCases,
                        :category, :subcategory)
                ON CONFLICT (api_tool_id)
                DO UPDATE SET
                    tool_name = EXCLUDED.tool_name,
                    provider = EXCLUDED.provider,
                    resource = EXCLUDED.resource,
                    action = EXCLUDED.action,
                    endpoint = EXCLUDED.endpoint,
                    params_required = EXCLUDED.params_required,
                    params_optional = EXCLUDED.params_optional,
                    param_examples = EXCLUDED.param_examples,
                    summary = EXCLUDED.summary,
                    summary_extended = EXCLUDED.summary_extended,
                    keywords = EXCLUDED.keywords,
                    keywords_primary = EXCLUDED.keywords_primary,
                    keywords_synonyms = EXCLUDED.keywords_synonyms,
                    keywords_params = EXCLUDED.keywords_params,
                    use_cases = EXCLUDED.use_cases,
                    category = EXCLUDED.category,
                    subcategory = EXCLUDED.subcategory,
                    updated_at = NOW()
                """;

            MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("apiToolId", apiToolId)
                .addValue("toolName", truncate(data.toolName(), MAX_TOOL_NAME))
                .addValue("provider", truncate(data.provider(), MAX_PROVIDER))
                .addValue("resource", truncate(data.resource(), MAX_RESOURCE))
                .addValue("action", truncate(data.action(), MAX_ACTION))
                .addValue("endpoint", data.endpoint())
                .addValue("paramsRequired", toArray(data.paramsRequired()))
                .addValue("paramsOptional", toArray(data.paramsOptional()))
                .addValue("paramExamples", toArray(data.paramExamples()))
                .addValue("summary", truncate(data.summary(), MAX_SUMMARY))
                .addValue("summaryExtended", truncate(data.summaryExtended(), MAX_SUMMARY_EXTENDED))
                .addValue("keywords", truncate(data.keywords(), MAX_KEYWORDS))
                .addValue("keywordsPrimary", toArray(data.keywordsPrimary()))
                .addValue("keywordsSynonyms", toArray(data.keywordsSynonyms()))
                .addValue("keywordsParams", toArray(data.keywordsParams()))
                .addValue("useCases", toArray(data.useCases()))
                .addValue("category", data.category())
                .addValue("subcategory", data.subcategory());

            int rowsAffected = namedJdbcTemplate.update(sql, params);
            log.info("Synced lexical index for tool: {} (rows: {})", apiToolId, rowsAffected);

        } catch (Exception e) {
            log.error("Failed to sync lexical index for tool {}: {}", apiToolId, e.getMessage(), e);
            throw new RuntimeException("Failed to sync lexical index: " + apiToolId, e);
        }
    }

    /**
     * Convenience method for enriched synthesis data (from API import).
     * Resolves toolName from DB if not provided.
     */
    @Transactional
    public void syncApiToolEnriched(UUID apiToolId, EnrichedSynthesisData data) {
        String toolName = data.toolName();
        if (toolName == null || toolName.isBlank()) {
            toolName = resolveToolName(apiToolId);
        }
        sync(apiToolId, SyncData.builder()
            .toolName(toolName)
            .provider(data.provider())
            .resource(data.resource())
            .action(data.action())
            .endpoint(data.endpoint())
            .paramsRequired(data.paramsRequired())
            .paramsOptional(data.paramsOptional())
            .paramExamples(data.paramExamples())
            .summary(data.summary())
            .summaryExtended(data.summaryExtended())
            .keywords(data.keywords())
            .keywordsPrimary(data.keywordsPrimary())
            .keywordsSynonyms(data.keywordsSynonyms())
            .keywordsParams(data.keywordsParams())
            .useCases(data.useCases())
            .build());
    }

    /**
     * Remove a tool from lexical search index.
     */
    @Transactional
    public void remove(UUID apiToolId) {
        String sql = "DELETE FROM catalog.lexical_search_index WHERE api_tool_id = :apiToolId";
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("apiToolId", apiToolId);
        int rowsAffected = namedJdbcTemplate.update(sql, params);
        log.info("Removed tool {} from lexical index (rows: {})", apiToolId, rowsAffected);
    }

    /**
     * Get statistics about the lexical search index.
     */
    public LexicalIndexStats getStats() {
        try {
            String sql = """
                SELECT COUNT(*) as total,
                       COUNT(DISTINCT provider) as providers,
                       COUNT(DISTINCT resource) as resources
                FROM catalog.lexical_search_index
                """;
            var result = namedJdbcTemplate.queryForMap(sql, new MapSqlParameterSource());
            return new LexicalIndexStats(
                ((Number) result.get("total")).intValue(),
                ((Number) result.get("providers")).intValue(),
                ((Number) result.get("resources")).intValue()
            );
        } catch (Exception e) {
            log.error("Failed to get lexical index stats: {}", e.getMessage());
            return new LexicalIndexStats(0, 0, 0);
        }
    }

    /**
     * Statistics record for lexical index.
     */
    public record LexicalIndexStats(int totalEntries, int uniqueProviders, int uniqueResources) {}

    /**
     * Get JdbcTemplate for custom queries (legacy compatibility).
     */
    public JdbcTemplate getJdbcTemplate() {
        return jdbcTemplate;
    }

    /**
     * Sync all tools by triggering tsvector rebuild (legacy compatibility).
     * The DB trigger will regenerate tsv_combined with 'english' tokenizer.
     */
    @Transactional
    public void syncAllTools() {
        log.info("Starting bulk sync of lexical search index");
        String sql = "UPDATE catalog.lexical_search_index SET updated_at = NOW()";
        int rowsAffected = jdbcTemplate.update(sql);
        log.info("Bulk sync completed - {} rows refreshed", rowsAffected);
    }

    /**
     * Legacy record for backward compatibility with existing callers.
     */
    public record EnrichedSynthesisData(
        String toolName,
        String provider,
        String resource,
        String action,
        String endpoint,
        List<String> paramsRequired,
        List<String> paramsOptional,
        List<String> paramExamples,
        String summary,
        String summaryExtended,
        String keywords,
        List<String> keywordsPrimary,
        List<String> keywordsSynonyms,
        List<String> keywordsParams,
        List<String> useCases
    ) {
        public static Builder builder() {
            return new Builder();
        }

        public static class Builder {
            private String toolName;
            private String provider;
            private String resource;
            private String action;
            private String endpoint;
            private List<String> paramsRequired = List.of();
            private List<String> paramsOptional = List.of();
            private List<String> paramExamples = List.of();
            private String summary;
            private String summaryExtended;
            private String keywords;
            private List<String> keywordsPrimary = List.of();
            private List<String> keywordsSynonyms = List.of();
            private List<String> keywordsParams = List.of();
            private List<String> useCases = List.of();

            public Builder toolName(String v) { this.toolName = v; return this; }
            public Builder provider(String v) { this.provider = v; return this; }
            public Builder resource(String v) { this.resource = v; return this; }
            public Builder action(String v) { this.action = v; return this; }
            public Builder endpoint(String v) { this.endpoint = v; return this; }
            public Builder paramsRequired(List<String> v) { this.paramsRequired = v; return this; }
            public Builder paramsOptional(List<String> v) { this.paramsOptional = v; return this; }
            public Builder paramExamples(List<String> v) { this.paramExamples = v; return this; }
            public Builder summary(String v) { this.summary = v; return this; }
            public Builder summaryExtended(String v) { this.summaryExtended = v; return this; }
            public Builder keywords(String v) { this.keywords = v; return this; }
            public Builder keywordsPrimary(List<String> v) { this.keywordsPrimary = v; return this; }
            public Builder keywordsSynonyms(List<String> v) { this.keywordsSynonyms = v; return this; }
            public Builder keywordsParams(List<String> v) { this.keywordsParams = v; return this; }
            public Builder useCases(List<String> v) { this.useCases = v; return this; }

            public EnrichedSynthesisData build() {
                return new EnrichedSynthesisData(toolName, provider, resource, action, endpoint,
                    paramsRequired, paramsOptional, paramExamples,
                    summary, summaryExtended, keywords,
                    keywordsPrimary, keywordsSynonyms, keywordsParams, useCases);
            }
        }
    }

    // ========== Helper methods ==========

    /**
     * Resolve tool name from tool_names table via api_tools.tool_name_id.
     */
    private String resolveToolName(UUID apiToolId) {
        try {
            String sql = """
                SELECT tn.name
                FROM catalog.api_tools at
                JOIN catalog.tool_names tn ON tn.id::text = at.tool_name_id
                WHERE at.id = :apiToolId
                """;
            MapSqlParameterSource params = new MapSqlParameterSource().addValue("apiToolId", apiToolId);
            return namedJdbcTemplate.queryForObject(sql, params, String.class);
        } catch (Exception e) {
            log.warn("Could not resolve tool name for {}: {}", apiToolId, e.getMessage());
            return null;
        }
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        log.warn("Truncating text from {} to {} chars", text.length(), maxLength);
        return text.substring(0, maxLength - 3) + "...";
    }

    private String[] toArray(List<String> list) {
        if (list == null || list.isEmpty()) return null;
        List<String> filtered = list.stream()
            .filter(s -> s != null && !s.trim().isEmpty())
            .toList();
        return filtered.isEmpty() ? null : filtered.toArray(new String[0]);
    }
}
