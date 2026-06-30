package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.dto.CapabilityCard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for tracking and logging search feedback.
 *
 * This implements a feedback loop that logs:
 * 1. Search queries and extracted intents
 * 2. Presented results and their scores
 * 3. Which tool the LLM actually selected
 * 4. Whether the tool execution succeeded
 *
 * This data can be used to:
 * - Fine-tune embeddings on (query, selected_tool) pairs
 * - Adjust RRF weights based on selection patterns
 * - Identify problematic queries that lead to poor selections
 * - Monitor search quality over time
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchFeedbackService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Record a search event asynchronously.
     * This method returns immediately and logs the feedback in the background.
     */
    @Async
    public void recordSearchAsync(SearchFeedback feedback) {
        try {
            recordSearch(feedback);
        } catch (Exception e) {
            log.error("Failed to record search feedback asynchronously: {}", e.getMessage());
        }
    }

    /**
     * Record a search event.
     *
     * @param feedback The search feedback to record
     * @return The ID of the created feedback record
     */
    public UUID recordSearch(SearchFeedback feedback) {
        UUID id = UUID.randomUUID();

        try {
            // Convert tool IDs to PostgreSQL array format
            String presentedToolIdsArray = feedback.presentedToolIds != null && !feedback.presentedToolIds.isEmpty()
                ? "{" + feedback.presentedToolIds.stream()
                    .map(UUID::toString)
                    .collect(Collectors.joining(",")) + "}"
                : null;

            // Convert scores to PostgreSQL array format
            String scoresArray = feedback.presentedScores != null && !feedback.presentedScores.isEmpty()
                ? "{" + feedback.presentedScores.stream()
                    .map(String::valueOf)
                    .collect(Collectors.joining(",")) + "}"
                : null;

            String sql = """
                INSERT INTO catalog.search_feedback (
                    id, query, presented_tool_ids, presented_scores,
                    selected_tool_id, selection_rank, execution_success, execution_error,
                    extracted_provider, extracted_action, extracted_resource,
                    session_id, tenant_id, search_time_ms, reranking_time_ms,
                    search_type, reranking_enabled, auto_pick_triggered
                ) VALUES (
                    ?, ?, ?::uuid[], ?::double precision[],
                    ?, ?, ?, ?,
                    ?, ?, ?,
                    ?, ?, ?, ?,
                    ?, ?, ?
                )
                """;

            jdbcTemplate.update(sql,
                id,
                feedback.query,
                presentedToolIdsArray,
                scoresArray,
                feedback.selectedToolId,
                feedback.selectionRank,
                feedback.executionSuccess,
                feedback.executionError,
                feedback.extractedProvider,
                feedback.extractedAction,
                feedback.extractedResource,
                feedback.sessionId,
                feedback.tenantId,
                feedback.searchTimeMs,
                feedback.rerankingTimeMs,
                feedback.searchType,
                feedback.rerankingEnabled,
                feedback.autoPickTriggered
            );

            log.debug("Recorded search feedback: id={}, query='{}', selectedRank={}",
                     id, truncate(feedback.query, 50), feedback.selectionRank);

            return id;

        } catch (Exception e) {
            log.error("Failed to record search feedback: {}", e.getMessage());
            throw new RuntimeException("Failed to record search feedback", e);
        }
    }


    /**
     * Helper method to create feedback from search results.
     */
    public static SearchFeedback fromSearchResults(
            String query,
            List<CapabilityCard> results,
            String provider,
            String action,
            String resource,
            long searchTimeMs,
            boolean rerankingEnabled,
            boolean autoPickTriggered) {

        List<UUID> toolIds = results.stream()
            .map(card -> UUID.fromString(card.id()))
            .collect(Collectors.toList());

        List<Double> scores = results.stream()
            .map(CapabilityCard::score)
            .collect(Collectors.toList());

        SearchFeedback feedback = new SearchFeedback();
        feedback.query = query;
        feedback.presentedToolIds = toolIds;
        feedback.presentedScores = scores;
        feedback.extractedProvider = provider;
        feedback.extractedAction = action;
        feedback.extractedResource = resource;
        feedback.searchTimeMs = (int) searchTimeMs;
        feedback.searchType = "hybrid";
        feedback.rerankingEnabled = rerankingEnabled;
        feedback.autoPickTriggered = autoPickTriggered;

        return feedback;
    }

    private String truncate(String s, int maxLen) {
        return s == null ? null : (s.length() <= maxLen ? s : s.substring(0, maxLen) + "...");
    }


    /**
     * Search feedback data structure.
     */
    public static class SearchFeedback {
        public String query;
        public List<UUID> presentedToolIds;
        public List<Double> presentedScores;
        public UUID selectedToolId;
        public Integer selectionRank;
        public Boolean executionSuccess;
        public String executionError;
        public String extractedProvider;
        public String extractedAction;
        public String extractedResource;
        public String sessionId;
        public String tenantId;
        public Integer searchTimeMs;
        public Integer rerankingTimeMs;
        public String searchType = "hybrid";
        public Boolean rerankingEnabled = false;
        public Boolean autoPickTriggered = false;
    }

}
