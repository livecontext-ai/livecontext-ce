package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.config.SearchConfig;
import com.apimarketplace.catalog.domain.ToolSignalEntity;
import com.apimarketplace.catalog.dto.CapabilityCard;
import com.apimarketplace.catalog.dto.CapabilityRequest;
import com.apimarketplace.catalog.dto.CapabilityResponse;
import com.apimarketplace.catalog.repository.LexicalSearchIndexRepository;
import com.apimarketplace.catalog.repository.ToolSignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Capability Search Service using lexical (full-text) search.
 *
 * 1. Query Understanding: Automatically extracts provider/action/resource hints from natural language
 * 2. Lexical Search: BM25 full-text search on tool index
 * 3. Cross-encoder Reranking: Reranks top candidates for better precision
 * 4. Batch Loading: Eliminates N+1 queries by loading all signals/names in single queries
 * 5. Feedback Loop: Logs search events for quality analysis
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CapabilityService {

    private final LexicalSearchService lexicalSearchService;
    private final ToolSignalRepository toolSignalRepository;
    private final LexicalSearchIndexRepository lexicalSearchIndexRepository;
    private final SearchConfig searchConfig;

    private final QueryUnderstandingService queryUnderstandingService;
    private final RerankingService rerankingService;
    private final SearchFeedbackService searchFeedbackService;

    private static final int DEFAULT_TOP_K = 12;
    private static final int SEARCH_LIMIT = 100;
    private static final int RERANK_CANDIDATE_LIMIT = 50;

    /**
     * Main search method using lexical search only.
     */
    public CapabilityResponse rank(CapabilityRequest request, String tenantId) {
        long startTime = System.currentTimeMillis();
        long rerankingTime = 0;

        // === Step 1: Query Understanding ===
        QueryUnderstandingService.QueryIntent intent = null;
        Map<String, String> effectiveHints = request.hints();

        if (queryUnderstandingService != null && searchConfig.getQueryUnderstanding().isEnabled()) {
            try {
                intent = queryUnderstandingService.extractIntent(request.q());
                if (intent.hasHints() && (effectiveHints == null || effectiveHints.isEmpty())) {
                    effectiveHints = intent.toHints();
                    log.info("Query understanding extracted hints: {}", effectiveHints);
                }
            } catch (Exception e) {
                log.warn("Query understanding failed: {}", e.getMessage());
            }
        }

        // === Step 2: Lexical (Full-Text) Search ===
        Map<UUID, Double> lexicalScores = new LinkedHashMap<>();
        List<Map<String, Object>> rawLexicalResults = Collections.emptyList();

        try {
            List<Map<UUID, Double>> lexicalResults = lexicalSearchService.searchForFusion(request.q(), SEARCH_LIMIT, tenantId);
            for (Map<UUID, Double> entry : lexicalResults) {
                UUID id = entry.keySet().iterator().next();
                Double bm25 = entry.values().iterator().next();
                lexicalScores.put(id, sanitizeNumber(bm25));
            }

            rawLexicalResults = lexicalScores.entrySet().stream()
                .map(e -> Map.<String, Object>of("tool_id", e.getKey().toString(), "bm25_score", e.getValue()))
                .collect(Collectors.toList());

            log.info("Lexical search returned {} results", lexicalScores.size());
        } catch (Exception e) {
            log.error("Lexical search failed: {}", e.getMessage());
        }

        if (lexicalScores.isEmpty()) {
            log.warn("No results from lexical search for query: {}", request.q());
        }

        // === Step 3: Batch Load Metadata ===
        Set<UUID> allToolIds = lexicalScores.keySet();

        Map<UUID, ToolSignalEntity> signalMap = toolSignalRepository.findByToolIdIn(allToolIds)
            .stream()
            .collect(Collectors.toMap(ToolSignalEntity::getToolId, Function.identity()));

        Map<String, Map<String, Object>> lexicalDataMap =
            lexicalSearchIndexRepository.batchGetByToolIds(allToolIds);

        Map<String, String> toolNameMap =
            lexicalSearchIndexRepository.batchGetToolNames(allToolIds);

        // === Step 4: Build Capability Cards ===
        final Map<UUID, Double> finalLexicalScores = lexicalScores;

        List<CapabilityCard> cards = lexicalScores.entrySet().stream()
            .map(entry -> buildCapabilityCard(
                entry.getKey(),
                entry.getValue(),
                signalMap,
                lexicalDataMap,
                toolNameMap
            ))
            .filter(Objects::nonNull)
            .sorted(Comparator.comparingDouble(CapabilityCard::score).reversed())
            .limit(RERANK_CANDIDATE_LIMIT)
            .collect(Collectors.toList());

        // === Step 5: Cross-encoder Reranking ===
        int finalK = request.k() != null ? request.k() : DEFAULT_TOP_K;

        if (rerankingService != null && searchConfig.getReranking().isEnabled() && cards.size() > 1) {
            long rerankStart = System.currentTimeMillis();
            try {
                cards = rerankingService.rerank(request.q(), cards);
                rerankingTime = System.currentTimeMillis() - rerankStart;
                log.info("Reranking completed in {} ms", rerankingTime);
            } catch (Exception e) {
                log.warn("Reranking failed, using original order: {}", e.getMessage());
            }
        }

        List<CapabilityCard> finalCards = cards.stream()
            .limit(finalK)
            .collect(Collectors.toList());

        // === Step 6: Auto-pick Decision ===
        boolean autoPick = shouldAutoPick(finalCards);

        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;

        log.info("Capability search for '{}' completed in {} ms (rerank: {} ms) -> {} cards",
                 request.q(), totalTime, rerankingTime, finalCards.size());

        // === Step 7: Record Feedback (async) ===
        if (searchFeedbackService != null) {
            try {
                SearchFeedbackService.SearchFeedback feedback = SearchFeedbackService.fromSearchResults(
                    request.q(),
                    finalCards,
                    intent != null ? intent.provider() : null,
                    intent != null ? intent.action() : null,
                    intent != null ? intent.resource() : null,
                    totalTime,
                    searchConfig.getReranking().isEnabled(),
                    autoPick
                );
                searchFeedbackService.recordSearchAsync(feedback);
            } catch (Exception e) {
                log.debug("Failed to record search feedback: {}", e.getMessage());
            }
        }

        // === Build Response ===
        List<Map<String, Object>> debugLexicalResults = rawLexicalResults.isEmpty()
            ? List.of(Map.of("tool_id", "no-results", "bm25_score", 0.0, "message", "No lexical results found"))
            : rawLexicalResults;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("query", request.q());
        metadata.put("k", finalK);
        metadata.put("total_results", lexicalScores.size());
        metadata.put("filtered_results", finalCards.size());
        metadata.put("auto_pick_candidate", autoPick);
        metadata.put("processing_time_ms", totalTime);
        metadata.put("lexical_results_count", rawLexicalResults.size());
        metadata.put("reranking_enabled", searchConfig.getReranking().isEnabled());
        metadata.put("reranking_time_ms", rerankingTime);
        if (intent != null && intent.hasHints()) {
            metadata.put("extracted_hints", intent.toHints());
        }

        return CapabilityResponse.of(finalCards, metadata, List.of(), debugLexicalResults);
    }

    /** Backwards-compatible overload (public APIs only). */
    public CapabilityResponse rank(CapabilityRequest request) {
        return rank(request, null);
    }

    private CapabilityCard buildCapabilityCard(
            UUID toolId,
            Double score,
            Map<UUID, ToolSignalEntity> signalMap,
            Map<String, Map<String, Object>> lexicalDataMap,
            Map<String, String> toolNameMap) {

        String provider = null;
        String resource = null;
        String action = null;
        boolean requiresCreds = false;

        ToolSignalEntity signal = signalMap.get(toolId);
        if (signal != null) {
            provider = signal.getProvider();
            resource = signal.getResource();
            action = signal.getAction();
            requiresCreds = signal.getRequiresUserCredentials() != null && signal.getRequiresUserCredentials();
        } else {
            Map<String, Object> lexicalData = lexicalDataMap.get(toolId.toString());
            if (lexicalData != null) {
                provider = (String) lexicalData.get("provider");
                resource = (String) lexicalData.get("resource");
                action = (String) lexicalData.get("action");
            }
        }

        String toolName = toolNameMap.getOrDefault(toolId.toString(), "Unknown Tool");
        String displayName = toolName != null && !toolName.equals("Unknown Tool")
            ? toolName
            : (provider != null ? provider : "") + " " + (resource != null ? resource : "") + " " + (action != null ? action : "");

        return CapabilityCard.forRRF(
            toolId.toString(),
            displayName.trim(),
            provider != null ? provider : "N/A",
            Collections.emptyList(),
            Collections.emptyMap(),
            requiresCreds,
            score,
            false,
            true
        );
    }

    private boolean shouldAutoPick(List<CapabilityCard> cards) {
        if (cards.size() < 2) return cards.size() == 1;

        double topScore = cards.get(0).score();
        double secondScore = cards.get(1).score();

        double marginRatio = searchConfig != null
            ? searchConfig.getAutoPick().getMarginRatio() : 1.3;
        double minScore = searchConfig != null
            ? searchConfig.getAutoPick().getMinScore() : 0.015;

        if (topScore < minScore) return false;

        double ratio = secondScore > 0 ? topScore / secondScore : Double.MAX_VALUE;
        return ratio >= marginRatio;
    }

    private static double sanitizeNumber(Number n) {
        double v = (n == null) ? 0d : n.doubleValue();
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0d;
        return v;
    }
}
