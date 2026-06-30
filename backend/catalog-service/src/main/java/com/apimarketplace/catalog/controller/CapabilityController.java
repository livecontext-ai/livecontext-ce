package com.apimarketplace.catalog.controller;

import com.apimarketplace.catalog.dto.CapabilityRequest;
import com.apimarketplace.catalog.dto.CapabilityResponse;
import com.apimarketplace.catalog.dto.SynthesisDataRequest;
import com.apimarketplace.catalog.repository.LexicalSearchIndexRepository;
import com.apimarketplace.catalog.service.CapabilityService;
import com.apimarketplace.catalog.service.LexicalIndexSyncService;
import com.apimarketplace.catalog.service.LexicalIndexSyncService.EnrichedSynthesisData;
import com.apimarketplace.catalog.util.ApiScopedSearchParser;
import com.apimarketplace.catalog.util.ApiScopedSearchParser.ParsedSearch;

import java.util.UUID;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST Controller for capability search endpoints
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class CapabilityController {

    private final CapabilityService capabilityService;
    private final LexicalIndexSyncService lexicalIndexSyncService;
    private final LexicalSearchIndexRepository lexicalSearchIndexRepository;
    
    /**
     * Search for capabilities using hybrid embeddings + tsvector with RRF fusion
     * 
     * POST /api/capability_knn
     * 
     * Request body:
     * {
     *   "q": "search query",
     *   "k": 12,
     *   "hints": {
     *     "action": "get",
     *     "resource": "user",
     *     "provider": "instagram"
     *   },
     *   "useOpenAI": true
     * }
     * 
     * @param request The capability search request
     * @return Capability response with ranked tools
     */
    @PostMapping("/capability_knn")
    public ResponseEntity<CapabilityResponse> capability(
            @RequestBody @Valid CapabilityRequest request,
            @RequestHeader(value = "X-User-ID", required = false) String userId) {
        try {
            log.info("Received capability search request: {}", request);

            CapabilityResponse response = capabilityService.rank(request, userId);
            
            log.info("Capability search completed - returned {} cards", response.cards().size());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error processing capability search request: {}", request, e);
            
            CapabilityResponse errorResponse = CapabilityResponse.of(
                List.of(), 
                Map.of(
                    "error", "Internal server error: " + e.getMessage(),
                    "query", request.q(),
                    "timestamp", System.currentTimeMillis()
                )
            );
            
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }
    

    /**
     * Ultra-optimized tool search endpoint for AI agents.
     * Combines multiple search strategies for maximum quality:
     * 1. Structured query extraction (provider/action/resource detection)
     * 2. Fuzzy matching with pg_trgm (typo tolerance)
     * 3. Phrase matching boost (exact phrase sequences)
     * 4. Intelligent fallback (relaxed search if 0 results)
     *
     * GET /api/tools/search?q=send%20slack%20message&k=5
     *
     * Response format:
     * {
     *   "tools": [{ "id", "provider", "resource", "action", "score", "match_type" }],
     *   "count": N,
     *   "query": "original query",
     *   "parsed": { "provider": "slack", "action": "send" },
     *   "time_ms": 15
     * }
     *
     * @param q Search query (required)
     * @param k Max results to return (default: 10)
     * @return Ranked list of matching tools with match metadata
     */
    @GetMapping("/tools/search")
    public ResponseEntity<Map<String, Object>> searchToolsForAgent(
            @RequestParam String q,
            @RequestParam(defaultValue = "10") int k,
            @RequestParam(required = false) List<String> api,
            @RequestParam(required = false) List<String> apis,
            @RequestHeader(value = "X-User-ID", required = false) String userId) {
        long startTime = System.currentTimeMillis();

        try {
            if (q == null || q.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Query parameter 'q' is required",
                    "count", 0,
                    "tools", List.of()
                ));
            }

            ParsedSearch parsedSearch = ApiScopedSearchParser.parse(q, api, apis);
            if (parsedSearch.query() == null || parsedSearch.query().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Search keyword is required",
                    "count", 0,
                    "tools", List.of()
                ));
            }

            String query = parsedSearch.query().trim().toLowerCase();
            List<String> apiFilters = parsedSearch.apiFilters();
            log.info("AI agent tool search: q='{}', k={}, apiFilters={}", query, k, apiFilters);

            // Step 1: Extract structured hints from query
            Map<String, String> parsed = extractStructuredHints(query);
            String detectedProvider = parsed.get("provider");
            String detectedAction = parsed.get("action");

            log.debug("Parsed query hints: provider={}, action={}", detectedProvider, detectedAction);

            // Step 2: Build optimized search query with all strategies (delegated to repository)
            List<Map<String, Object>> results = new ArrayList<>(lexicalSearchIndexRepository.searchOptimizedWithScoring(
                query, detectedProvider, detectedAction, apiFilters, k, userId));

            // Step 3: If no results, try fallback strategies
            String matchType = "full_text";
            if (results.isEmpty()) {
                log.info("No results with full-text, trying fuzzy fallback for: {}", query);
                try {
                    results = new ArrayList<>(lexicalSearchIndexRepository.searchFuzzy(query, detectedProvider, apiFilters, k, userId));
                    matchType = "fuzzy";
                } catch (Exception e) {
                    log.warn("Fuzzy search failed (pg_trgm extension may not be installed): {}", e.getMessage());
                }
            }

            if (results.isEmpty() && apiFilters != null && !apiFilters.isEmpty()) {
                log.info("No results with fuzzy, trying API-only fallback: {}", apiFilters);
                results = new ArrayList<>(lexicalSearchIndexRepository.searchByApiFilters(apiFilters, k, userId));
                matchType = "api_only";
            }

            if (results.isEmpty() && detectedProvider != null) {
                log.info("No results with fuzzy, trying provider-only fallback: {}", detectedProvider);
                if (apiFilters != null && !apiFilters.isEmpty()) {
                    results = new ArrayList<>(lexicalSearchIndexRepository.searchByProvider(detectedProvider, apiFilters, k, userId));
                } else {
                    results = new ArrayList<>(lexicalSearchIndexRepository.searchByProvider(detectedProvider, k, userId));
                }
                matchType = "provider_only";
            }

            // Step 4: Build response with enriched data
            // Score normalization for lexical search:
            // - ts_rank(, 32) with normalization returns ~0.1-0.3 per matching field
            // - Weights sum: 2+2+1.5+1.2+1+1+0.8+0.8+0.8+1.5 = 12.6
            // - Realistic max per field: ~0.3, so max contribution ~3.8
            // - Provider boost +3, action boost +2 = max theoretical ~8.8
            // - Observed good matches score 6-8, so use 8.0 as max
            // - Fuzzy uses similarity() (0-1) × weights (12) ≈ max 4
            // - Provider fallback is constant 1.0
            final double maxScore = switch (matchType) {
                case "full_text" -> 8.0;    // Based on observed max scores
                case "fuzzy" -> 4.0;        // similarity (0-1) × weights (12)
                case "api_only", "provider_only" -> 1.0;
                default -> 8.0;
            };

            // Step 4b: Supplement with provider/fuzzy results if full-text returned too few
            if (results.size() < k) {
                Set<String> existingIds = results.stream()
                    .map(r -> r.get("id").toString())
                    .collect(java.util.stream.Collectors.toSet());
                int remaining = k - results.size();
                boolean hasApiFilters = apiFilters != null && !apiFilters.isEmpty();

                // Try fuzzy first
                if (!"fuzzy".equals(matchType)) {
                    try {
                        List<Map<String, Object>> fuzzyResults = lexicalSearchIndexRepository.searchFuzzy(query, detectedProvider, apiFilters, remaining + 5, userId);
                        for (Map<String, Object> row : fuzzyResults) {
                            if (results.size() >= k) break;
                            if (!existingIds.contains(row.get("id").toString())) {
                                existingIds.add(row.get("id").toString());
                                results.add(row);
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Supplemental fuzzy search failed: {}", e.getMessage());
                    }
                }

                // Then API/provider fallback
                if (results.size() < k && hasApiFilters) {
                    if (!"api_only".equals(matchType)) {
                        List<Map<String, Object>> apiResults = lexicalSearchIndexRepository.searchByApiFilters(apiFilters, remaining + 5, userId);
                        for (Map<String, Object> row : apiResults) {
                            if (results.size() >= k) break;
                            if (!existingIds.contains(row.get("id").toString())) {
                                existingIds.add(row.get("id").toString());
                                results.add(row);
                            }
                        }
                    }
                    if (results.size() < k && detectedProvider != null && !"provider_only".equals(matchType)) {
                        List<Map<String, Object>> providerResults = lexicalSearchIndexRepository.searchByProvider(detectedProvider, apiFilters, remaining + 5, userId);
                        for (Map<String, Object> row : providerResults) {
                            if (results.size() >= k) break;
                            if (!existingIds.contains(row.get("id").toString())) {
                                existingIds.add(row.get("id").toString());
                                results.add(row);
                            }
                        }
                    }
                } else if (results.size() < k && !"provider_only".equals(matchType)) {
                    // Detect provider from query terms
                    String providerHint = detectedProvider != null ? detectedProvider : query.split("\\s+")[0];
                    List<Map<String, Object>> providerResults = lexicalSearchIndexRepository.searchByProvider(providerHint, remaining + 5, userId);
                    for (Map<String, Object> row : providerResults) {
                        if (results.size() >= k) break;
                        if (!existingIds.contains(row.get("id").toString())) {
                            existingIds.add(row.get("id").toString());
                            results.add(row);
                        }
                    }
                }
            }

            // Step 5: Recalculate maxScore after supplementing
            final String finalMatchType = matchType;
            final double effectiveMaxScore;
            if (results.size() > 0) {
                double observedMax = results.stream()
                    .mapToDouble(r -> {
                        Number s = (Number) r.get("score");
                        return s != null ? s.doubleValue() : 0.0;
                    })
                    .max().orElse(1.0);
                effectiveMaxScore = Math.max(observedMax, switch (finalMatchType) {
                    case "full_text" -> 8.0;
                    case "fuzzy" -> 4.0;
                    case "api_only", "provider_only" -> 1.0;
                    default -> 8.0;
                });
            } else {
                effectiveMaxScore = maxScore;
            }

            // Filter and transform results
            // Score policy: 0.15 (floor) to 1.0 (perfect match)
            // - 0.85+ = Excellent (auto-pick candidate)
            // - 0.65+ = Good
            // - 0.45+ = Moderate
            // - 0.25+ = Weak
            // - 0.15+ = Very weak (floor)
            final double SCORE_FLOOR = 0.15;

            List<Map<String, Object>> tools = results.stream()
                .map(row -> {
                    // Handle null score gracefully
                    Number scoreNum = (Number) row.get("score");
                    double score = scoreNum != null ? scoreNum.doubleValue() : 0.0;
                    // Normalize to 0-1 range based on observed max
                    double normalizedScore = Math.min(1.0, score / effectiveMaxScore);
                    // Apply floor - if we matched at all, show at least SCORE_FLOOR
                    if (normalizedScore < SCORE_FLOOR) {
                        normalizedScore = SCORE_FLOOR;
                    }
                    Map<String, Object> tool = new java.util.LinkedHashMap<>();
                    tool.put("id", row.get("id").toString());
                    // Include tool_name as 'name' for readability
                    if (row.get("tool_name") != null) {
                        tool.put("name", row.get("tool_name"));
                    }
                    if (row.get("api_name") != null) {
                        tool.put("apiName", row.get("api_name"));
                    }
                    if (row.get("api_slug") != null) {
                        tool.put("apiSlug", row.get("api_slug"));
                    }
                    tool.put("provider", row.get("provider"));
                    tool.put("resource", row.get("resource"));
                    tool.put("action", row.get("action"));
                    tool.put("score", Math.round(normalizedScore * 100) / 100.0);
                    // Include icon_slug for UI display
                    if (row.get("icon_slug") != null) {
                        tool.put("iconSlug", row.get("icon_slug"));
                    }
                    // Include summary and parameters info
                    if (row.get("summary") != null) {
                        tool.put("description", row.get("summary"));
                    }
                    // Convert PostgreSQL arrays to Java lists (PgArray contains JDBC internals)
                    Object paramsRequired = row.get("params_required");
                    if (paramsRequired != null) {
                        tool.put("requiredParams", convertPgArrayToList(paramsRequired));
                    }
                    Object paramsOptional = row.get("params_optional");
                    if (paramsOptional != null) {
                        tool.put("optionalParams", convertPgArrayToList(paramsOptional));
                    }
                    return tool;
                })
                .toList();

            long duration = System.currentTimeMillis() - startTime;
            log.info("AI agent tool search completed: {} results in {} ms (match_type={})",
                    tools.size(), duration, matchType);

            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("tools", tools);
            response.put("count", tools.size());
            response.put("query", q);
            response.put("search_query", query);
            if (apiFilters != null && !apiFilters.isEmpty()) {
                response.put("api_filters", apiFilters);
                response.put("api_scope_source", parsedSearch.inlineScope() ? "query" : "parameter");
            }
            response.put("parsed", parsed);
            response.put("match_type", matchType);
            response.put("time_ms", duration);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error in AI agent tool search: q='{}', error={}", q, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                "error", e.getMessage(),
                "count", 0,
                "tools", List.of(),
                "query", q
            ));
        }
    }

    /**
     * Extract structured hints (provider, action) from natural language query.
     * Dynamically detects provider names by checking against known providers in the index.
     * Uses word-boundary matching and prefers longer (more specific) provider matches.
     */
    private Map<String, String> extractStructuredHints(String query) {
        Map<String, String> hints = new java.util.LinkedHashMap<>();
        try {
            List<String> providers = lexicalSearchIndexRepository.getUniqueValues("provider");
            String lowerQuery = query.toLowerCase();
            java.util.Set<String> queryWords = new java.util.LinkedHashSet<>(
                    java.util.Arrays.asList(lowerQuery.split("\\s+")));

            String bestMatch = null;
            int bestScore = 0;

            for (String provider : providers) {
                if (provider == null) continue;
                String lowerProvider = provider.toLowerCase();
                String[] providerWords = lowerProvider.split("\\s+");
                String compactProvider = lowerProvider.replaceAll("\\s+", "");

                // Strategy 1: exact compact match - "github" matches "git hub"
                if (compactProvider.length() >= 3 && queryWords.contains(compactProvider)) {
                    int score = compactProvider.length() + 1000;
                    if (score > bestScore) {
                        bestMatch = lowerProvider;
                        bestScore = score;
                    }
                    continue;
                }

                // Strategy 2: query word is prefix of compact provider - "whatsapp" matches "whatsappbusiness"
                for (String qw : queryWords) {
                    if (qw.length() >= 4 && compactProvider.startsWith(qw) && !compactProvider.equals(qw)) {
                        int score = qw.length() + 10;
                        if (score > bestScore) {
                            bestMatch = lowerProvider;
                            bestScore = score;
                        }
                        break;
                    }
                }

                // Strategy 3: word-boundary match with full-coverage bonus
                for (int len = providerWords.length; len >= 1; len--) {
                    boolean allWordsPresent = true;
                    int totalChars = 0;
                    for (int i = 0; i < len; i++) {
                        int minLen = (len >= 2) ? 2 : 3;
                        if (providerWords[i].length() < minLen || !queryWords.contains(providerWords[i])) {
                            allWordsPresent = false;
                            break;
                        }
                        totalChars += providerWords[i].length();
                    }
                    if (!allWordsPresent) continue;
                    boolean fullCoverage = (len == providerWords.length);
                    int score = totalChars + (len * 10) + (fullCoverage ? 50 : 0);
                    if (len >= 2 && score > bestScore) {
                        bestMatch = String.join(" ", java.util.Arrays.copyOf(providerWords, len));
                        bestScore = score;
                        break;
                    }
                    if (len == 1 && providerWords[0].length() >= 3 && score > bestScore) {
                        bestMatch = providerWords[0];
                        bestScore = score;
                        break;
                    }
                }
            }

            if (bestMatch != null) {
                hints.put("provider", bestMatch);
            }
        } catch (Exception e) {
            log.debug("Could not extract structured hints: {}", e.getMessage());
        }
        return hints;
    }

    /**
     * Search for capabilities using hybrid embeddings + tsvector with RRF fusion
     * 
     * POST /catalog/v1/capabilities/knn
     * 
     * This endpoint provides the same functionality as /api/capability_knn
     * but with the path structure expected by the frontend.
     * 
     * Request body:
     * {
     *   "q": "search query",
     *   "k": 12,
     *   "hints": {
     *     "action": "get",
     *     "resource": "user",
     *     "provider": "instagram"
     *   },
     *   "useOpenAI": true
     * }
     * 
     * @param request The capability search request
     * @return Capability response with ranked tools
     */
    @PostMapping("/catalog/v1/capabilities/knn")
    public ResponseEntity<CapabilityResponse> capabilityV1(
            @RequestBody @Valid CapabilityRequest request,
            @RequestHeader(value = "X-User-ID", required = false) String userId) {
        try {
            log.info("Received capability v1 search request: {}", request);

            CapabilityResponse response = capabilityService.rank(request, userId);
            
            log.info("Capability v1 search completed - returned {} cards", response.cards().size());
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("Error processing capability v1 search request: {}", request, e);
            
            CapabilityResponse errorResponse = CapabilityResponse.of(
                List.of(), 
                Map.of(
                    "error", "Internal server error: " + e.getMessage(),
                    "query", request.q(),
                    "timestamp", System.currentTimeMillis()
                )
            );
            
            return ResponseEntity.internalServerError().body(errorResponse);
        }
    }

    /**
     * Directly populate lexical_search_index with pre-computed synthesis data.
     * This bypasses AI generation and uses synthesis data from API JSON files.
     *
     * POST /api/tools/{toolId}/synthesis
     *
     * Request body: SynthesisDataRequest with provider, resource, action, keywords, etc.
     *
     * @param toolId The API tool ID
     * @param request The synthesis data
     * @return Success status
     */
    @PostMapping("/tools/{toolId}/synthesis")
    public ResponseEntity<Map<String, Object>> saveSynthesisData(
            @PathVariable UUID toolId,
            @RequestBody SynthesisDataRequest request) {
        try {
            log.info("Saving synthesis data for tool: {} - provider={}, resource={}, action={}",
                    toolId, request.provider(), request.resource(), request.action());

            if (!request.hasValidSynthesis()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "Missing required fields: provider, resource, or action",
                    "toolId", toolId.toString()
                ));
            }

            // Convert request to EnrichedSynthesisData
            EnrichedSynthesisData enrichedData = EnrichedSynthesisData.builder()
                .toolName(request.toolName())
                .provider(request.provider())
                .resource(request.resource())
                .action(request.action())
                .endpoint(request.endpoint())
                .summary(request.summary())
                .summaryExtended(request.summaryExtended())
                .keywords(request.buildKeywordsString())
                .keywordsPrimary(toList(request.keywordsPrimary()))
                .keywordsSynonyms(toList(request.keywordsSynonyms()))
                .keywordsParams(toList(request.keywordsParams()))
                .useCases(toList(request.useCases()))
                .paramsRequired(toList(request.paramsRequired()))
                .paramsOptional(toList(request.paramsOptional()))
                .paramExamples(toList(request.paramExamples()))
                .build();

            // Save directly to lexical_search_index
            lexicalIndexSyncService.syncApiToolEnriched(toolId, enrichedData);

            log.info("Successfully saved synthesis data for tool: {}", toolId);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "toolId", toolId.toString(),
                "provider", request.provider(),
                "resource", request.resource(),
                "action", request.action()
            ));

        } catch (Exception e) {
            log.error("Error saving synthesis data for tool {}: {}", toolId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "error", e.getMessage(),
                "toolId", toolId.toString()
            ));
        }
    }

    /**
     * Batch save synthesis data for multiple tools.
     *
     * POST /api/tools/synthesis/batch
     *
     * @param requests Map of toolId -> SynthesisDataRequest
     * @return Batch result with success/failure counts
     */
    @PostMapping("/tools/synthesis/batch")
    public ResponseEntity<Map<String, Object>> saveSynthesisDataBatch(
            @RequestBody Map<String, SynthesisDataRequest> requests) {
        try {
            log.info("Batch saving synthesis data for {} tools", requests.size());

            int success = 0;
            int failed = 0;

            for (Map.Entry<String, SynthesisDataRequest> entry : requests.entrySet()) {
                try {
                    UUID toolId = UUID.fromString(entry.getKey());
                    SynthesisDataRequest request = entry.getValue();

                    if (!request.hasValidSynthesis()) {
                        log.warn("Skipping tool {} - missing required synthesis fields", toolId);
                        failed++;
                        continue;
                    }

                    EnrichedSynthesisData enrichedData = EnrichedSynthesisData.builder()
                        .toolName(request.toolName())
                        .provider(request.provider())
                        .resource(request.resource())
                        .action(request.action())
                        .endpoint(request.endpoint())
                        .summary(request.summary())
                        .summaryExtended(request.summaryExtended())
                        .keywords(request.buildKeywordsString())
                        .keywordsPrimary(toList(request.keywordsPrimary()))
                        .keywordsSynonyms(toList(request.keywordsSynonyms()))
                        .keywordsParams(toList(request.keywordsParams()))
                        .useCases(toList(request.useCases()))
                        .paramsRequired(toList(request.paramsRequired()))
                        .paramsOptional(toList(request.paramsOptional()))
                        .paramExamples(toList(request.paramExamples()))
                        .build();

                    lexicalIndexSyncService.syncApiToolEnriched(toolId, enrichedData);
                    success++;

                } catch (Exception e) {
                    log.error("Error processing tool {}: {}", entry.getKey(), e.getMessage());
                    failed++;
                }
            }

            log.info("Batch synthesis save completed: {} success, {} failed", success, failed);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "processed", requests.size(),
                "successCount", success,
                "failedCount", failed
            ));

        } catch (Exception e) {
            log.error("Error in batch synthesis save: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    private List<String> toList(List<String> list) {
        if (list == null || list.isEmpty()) return List.of();
        return list;
    }

    /**
     * Converts a PostgreSQL array (java.sql.Array/PgArray) to a Java List.
     * PgArray objects contain internal JDBC state that cannot be serialized directly.
     * This method extracts just the array values.
     */
    private List<String> convertPgArrayToList(Object value) {
        if (value == null) {
            return List.of();
        }

        try {
            if (value instanceof java.sql.Array sqlArray) {
                Object array = sqlArray.getArray();
                if (array instanceof String[] stringArray) {
                    return java.util.Arrays.asList(stringArray);
                } else if (array instanceof Object[] objectArray) {
                    return java.util.Arrays.stream(objectArray)
                        .map(obj -> obj != null ? obj.toString() : null)
                        .filter(java.util.Objects::nonNull)
                        .toList();
                }
            } else if (value instanceof String[] stringArray) {
                return java.util.Arrays.asList(stringArray);
            } else if (value instanceof List<?> list) {
                return list.stream()
                    .map(obj -> obj != null ? obj.toString() : null)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            } else if (value instanceof String stringValue) {
                // Handle case where it's a comma-separated string
                if (stringValue.startsWith("{") && stringValue.endsWith("}")) {
                    // PostgreSQL array literal format: {value1,value2}
                    String content = stringValue.substring(1, stringValue.length() - 1);
                    if (content.isEmpty()) return List.of();
                    return java.util.Arrays.asList(content.split(","));
                }
                return List.of(stringValue);
            }
        } catch (Exception e) {
            log.warn("Error converting array value to list: {}", e.getMessage());
        }

        return List.of();
    }

}
