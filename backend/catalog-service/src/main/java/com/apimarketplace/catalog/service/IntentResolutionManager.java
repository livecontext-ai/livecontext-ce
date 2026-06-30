package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.dto.IntentResolutionResponse;
import com.apimarketplace.catalog.domain.dto.ToolCandidate;
import com.apimarketplace.catalog.service.CatalogToolQueryService.CatalogToolSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class IntentResolutionManager {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private final CatalogToolQueryService catalogToolQueryService;

    public IntentResolutionResponse resolve(String query, int limit) {
        int sanitizedLimit = sanitizeLimit(limit);
        String normalizedQuery = normalize(query);

        List<CatalogToolSummary> tools = catalogToolQueryService.findAllActiveTools();
        List<ToolCandidate> candidates = new ArrayList<>();

        for (CatalogToolSummary summary : tools) {
            CandidateScore score = score(summary, normalizedQuery);
            if (score == null) {
                continue;
            }
            candidates.add(ToolCandidate.builder()
                    .toolId(summary.toolId().toString())
                    .name(summary.name())
                    .description(summary.description())
                    .category(summary.categoryName())
                    .confidence(score.confidence)
                    .reason(score.reason)
                    .metadata(Map.of(
                            "categorySlug", summary.categorySlug(),
                            "toolCard", summary.card()))
                    .build());
        }

        candidates.sort(Comparator.comparingDouble(ToolCandidate::getConfidence).reversed()
                .thenComparing(ToolCandidate::getName, Comparator.nullsLast(String::compareToIgnoreCase)));

        List<ToolCandidate> page = candidates.stream()
                .limit(sanitizedLimit)
                .toList();

        return IntentResolutionResponse.builder()
                .query(query)
                .candidates(page)
                .totalCandidates(page.size())
                .error(page.isEmpty() ? "No tools matched the provided query" : null)
                .build();
    }

    private int sanitizeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private CandidateScore score(CatalogToolSummary summary, String query) {
        double confidence = 0.0;
        List<String> reasons = new ArrayList<>();

        if (query == null || query.isBlank()) {
            confidence = 0.4;
            reasons.add("Default ordering");
        } else {
            String name = normalize(summary.name());
            String description = normalize(summary.description());
            String category = normalize(summary.categoryName());

            if (name != null && name.contains(query)) {
                confidence += 0.55;
                reasons.add("Name matched the query");
            }
            if (description != null && description.contains(query)) {
                confidence += 0.30;
                reasons.add("Description matched the query");
            }
            if (category != null && category.contains(query)) {
                confidence += 0.15;
                reasons.add("Category matched the query");
            }
        }

        confidence = Math.min(1.0, confidence);
        if (confidence == 0.0) {
            return null;
        }

        String reason = String.join(", ", reasons);
        return new CandidateScore(confidence, reason.isBlank() ? "Recommended" : reason);
    }

    private String normalize(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private record CandidateScore(double confidence, String reason) { }
}
