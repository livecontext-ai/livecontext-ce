package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ToolSignalEntity;
import com.apimarketplace.catalog.dto.LexicalCard;
import com.apimarketplace.catalog.dto.LexicalRequest;
import com.apimarketplace.catalog.dto.LexicalResponse;
import com.apimarketplace.catalog.repository.LexicalSearchIndexRepository;
import com.apimarketplace.catalog.repository.ToolSignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service for lexical search operations.
 * Optimized to avoid N+1 queries using batch loading.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LexicalSearchService {

    private final LexicalSearchIndexRepository lexicalSearchIndexRepository;
    private final ToolSignalRepository toolSignalRepository;

    private static final int DEFAULT_TOP_K = 12;

    /**
     * Perform lexical search and return formatted results.
     * Uses batch loading to avoid N+1 query problem.
     */
    public LexicalResponse search(LexicalRequest request, String tenantId) {
        long startTime = System.currentTimeMillis();

        List<Map<String, Object>> rawResults = Collections.emptyList();
        try {
            rawResults = lexicalSearchIndexRepository.searchWithFilters(
                request.q(),
                request.getProvider(),
                request.getResource(),
                request.getAction(),
                request.k() != null ? request.k() : DEFAULT_TOP_K,
                tenantId
            );
            log.info("Lexical search returned {} results.", rawResults.size());
        } catch (Exception e) {
            log.error("Failed to perform lexical search: {}", e.getMessage());
        }

        if (rawResults.isEmpty()) {
            return LexicalResponse.of(Collections.emptyList(), buildMeta(request, 0, 0, startTime));
        }

        // Extract all tool IDs for batch loading
        Set<UUID> toolIds = rawResults.stream()
                .map(row -> UUID.fromString(row.get("tool_id").toString()))
                .collect(Collectors.toSet());

        // Batch load tool signals (1 query instead of N)
        Map<UUID, ToolSignalEntity> signalsByToolId = toolSignalRepository.findByToolIdIn(toolIds)
                .stream()
                .collect(Collectors.toMap(ToolSignalEntity::getToolId, Function.identity()));

        // Build cards using batch-loaded data
        List<LexicalCard> cards = rawResults.stream()
                .map(row -> buildCard(row, signalsByToolId, request))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(LexicalCard::bm25Score).reversed())
                .collect(Collectors.toList());

        long endTime = System.currentTimeMillis();
        log.info("Lexical search for '{}' in {} ms → {} cards",
                request.q(), (endTime - startTime), cards.size());

        return LexicalResponse.of(cards, buildMeta(request, rawResults.size(), cards.size(), startTime));
    }

    /**
     * Build a LexicalCard from a search result row.
     * Uses pre-loaded signal data to avoid per-row queries.
     */
    private LexicalCard buildCard(Map<String, Object> row,
                                   Map<UUID, ToolSignalEntity> signalsByToolId,
                                   LexicalRequest request) {
        UUID toolId = UUID.fromString(row.get("tool_id").toString());
        double bm25Score = sanitizeNumber((Number) row.get("bm25"));

        // Get signal from batch-loaded map (no query)
        ToolSignalEntity signal = signalsByToolId.get(toolId);

        String provider, resource, action;
        boolean requiresCredentials = false;

        if (signal != null) {
            provider = signal.getProvider();
            resource = signal.getResource();
            action = signal.getAction();
            requiresCredentials = Boolean.TRUE.equals(signal.getRequiresUserCredentials());

            // Apply hints filtering
            if (!matchesHints(request, provider, resource, action)) {
                return null;
            }
        } else {
            // Fallback: use data from lexical index
            log.debug("No signal found for tool ID: {}, using lexical index data", toolId);
            provider = (String) row.get("provider");
            resource = (String) row.get("resource");
            action = (String) row.get("action");

            if (!matchesHints(request, provider, resource, action)) {
                return null;
            }
        }

        // Get tool_name directly from search result (added in optimized query)
        String toolName = row.get("tool_name") != null ? row.get("tool_name").toString() : null;

        // Build display name
        String displayName = (toolName != null && !toolName.equals("Unknown Tool"))
            ? toolName
            : String.format("%s %s %s",
                provider != null ? provider : "N/A",
                resource != null ? resource : "N/A",
                action != null ? action : "N/A");

        return LexicalCard.of(
                toolId.toString(),
                displayName,
                provider != null ? provider : "N/A",
                resource != null ? resource : "N/A",
                action != null ? action : "N/A",
                Collections.emptyList(),
                Collections.emptyMap(),
                requiresCredentials,
                bm25Score
        );
    }

    /**
     * Check if signal matches request hints.
     */
    private boolean matchesHints(LexicalRequest request, String provider, String resource, String action) {
        if (request.hints() == null) {
            return true;
        }
        // Only apply hints if not already filtered by optimized search
        if (request.getProvider() == null || request.getResource() == null || request.getAction() == null) {
            if (request.hints().containsKey("action") &&
                !request.hints().get("action").equalsIgnoreCase(action)) {
                return false;
            }
            if (request.hints().containsKey("resource") &&
                !request.hints().get("resource").equalsIgnoreCase(resource)) {
                return false;
            }
            if (request.hints().containsKey("provider") &&
                !request.hints().get("provider").equalsIgnoreCase(provider)) {
                return false;
            }
        }
        return true;
    }

    /** Backwards-compatible overload (public APIs only). */
    public LexicalResponse search(LexicalRequest request) {
        return search(request, null);
    }

    /**
     * Search method for CapabilityService fusion - returns ordered list of tool scores.
     */
    public List<Map<UUID, Double>> searchForFusion(String query, int limit, String tenantId) {
        List<Map<String, Object>> rows = lexicalSearchIndexRepository.searchOptimized(query, limit, tenantId);
        return rows.stream()
                   .map(row -> Map.of(
                           UUID.fromString(row.get("tool_id").toString()),
                           sanitizeNumber((Number) row.get("bm25"))
                   ))
                   .collect(Collectors.toList());
    }

    /** Backwards-compatible overload (public APIs only). */
    public List<Map<UUID, Double>> searchForFusion(String query, int limit) {
        return searchForFusion(query, limit, null);
    }

    /**
     * Build metadata map for response.
     */
    private Map<String, Object> buildMeta(LexicalRequest request, int rawCount, int filteredCount, long startTime) {
        return Map.of(
                "query", request.q(),
                "k", request.k() != null ? request.k() : DEFAULT_TOP_K,
                "total_results", rawCount,
                "filtered_results", filteredCount,
                "processing_time_ms", System.currentTimeMillis() - startTime,
                "search_type", "lexical",
                "timestamp", System.currentTimeMillis()
        );
    }

    /**
     * Sanitize numeric values (handle null, NaN, Infinity).
     */
    private static double sanitizeNumber(Number n) {
        if (n == null) return 0d;
        double v = n.doubleValue();
        return (Double.isNaN(v) || Double.isInfinite(v)) ? 0d : v;
    }
}
