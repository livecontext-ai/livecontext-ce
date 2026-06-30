package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.config.SearchConfig;
import com.apimarketplace.catalog.dto.CapabilityCard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for reranking search results using cross-encoder or LLM-based scoring.
 *
 * Cross-encoder reranking provides more accurate relevance scores than bi-encoder
 * (embedding) approaches because it processes query and document together, enabling
 * deeper semantic understanding.
 *
 * This service supports multiple reranking strategies:
 * 1. OpenAI-based: Uses GPT to score query-document relevance
 * 2. Cohere Rerank: Uses Cohere's dedicated reranking API (if configured)
 * 3. Local cross-encoder: Future support for local models
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RerankingService {

    private final SearchConfig searchConfig;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    @Value("${openai.api-key:}")
    private String openaiApiKey;

    @Value("${cohere.api-key:}")
    private String cohereApiKey;

    @Value("${ai.mapping.deepinfra.token:}")
    private String deepinfraToken;

    private static final String OPENAI_CHAT_URL = "https://api.openai.com/v1/chat/completions";
    private static final String COHERE_RERANK_URL = "https://api.cohere.ai/v1/rerank";
    private static final String DEEPINFRA_URL = "https://api.deepinfra.com/v1/openai/chat/completions";

    /**
     * Rerank capability cards based on query relevance.
     *
     * @param query The search query
     * @param cards List of capability cards to rerank
     * @return Reranked list of capability cards with updated scores
     */
    public List<CapabilityCard> rerank(String query, List<CapabilityCard> cards) {
        if (!searchConfig.getReranking().isEnabled()) {
            log.debug("Reranking disabled, returning original order");
            return cards;
        }

        if (cards.isEmpty() || cards.size() == 1) {
            return cards;
        }

        int topK = Math.min(cards.size(), searchConfig.getReranking().getTopK());
        List<CapabilityCard> toRerank = cards.subList(0, topK);
        List<CapabilityCard> remaining = cards.size() > topK ? cards.subList(topK, cards.size()) : List.of();

        log.info("Reranking top {} candidates for query: '{}'", topK, query);

        try {
            // Try Cohere first (best for reranking), then DeepInfra, then OpenAI
            List<CapabilityCard> reranked;
            if (cohereApiKey != null && !cohereApiKey.isEmpty()) {
                reranked = rerankWithCohere(query, toRerank);
            } else if (deepinfraToken != null && !deepinfraToken.isEmpty()) {
                reranked = rerankWithLLM(query, toRerank, DEEPINFRA_URL, deepinfraToken, "Qwen/Qwen2.5-72B-Instruct");
            } else if (openaiApiKey != null && !openaiApiKey.isEmpty()) {
                reranked = rerankWithLLM(query, toRerank, OPENAI_CHAT_URL, openaiApiKey, "gpt-4o-mini");
            } else {
                log.warn("No API key available for reranking, using original scores");
                return cards;
            }

            // Combine reranked results with remaining
            List<CapabilityCard> result = new ArrayList<>(reranked);
            result.addAll(remaining);

            log.info("Reranking completed. Top result: {}", result.isEmpty() ? "none" : result.get(0).name());
            return result;

        } catch (Exception e) {
            log.error("Reranking failed, returning original order: {}", e.getMessage());
            return cards;
        }
    }

    /**
     * Rerank using Cohere's dedicated reranking API.
     * Cohere Rerank is optimized for this task and provides excellent results.
     */
    private List<CapabilityCard> rerankWithCohere(String query, List<CapabilityCard> cards) {
        log.debug("Reranking with Cohere Rerank API");

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(cohereApiKey);

            // Build documents list
            List<String> documents = cards.stream()
                .map(this::buildDocumentText)
                .collect(Collectors.toList());

            Map<String, Object> requestBody = Map.of(
                "model", "rerank-english-v3.0",
                "query", query,
                "documents", documents,
                "top_n", cards.size(),
                "return_documents", false
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                COHERE_RERANK_URL,
                HttpMethod.POST,
                request,
                JsonNode.class
            );

            if (response.getBody() != null && response.getBody().has("results")) {
                JsonNode results = response.getBody().get("results");
                List<CapabilityCard> reranked = new ArrayList<>();

                for (JsonNode result : results) {
                    int index = result.get("index").asInt();
                    double relevanceScore = result.get("relevance_score").asDouble();

                    CapabilityCard original = cards.get(index);
                    // Create new card with reranked score
                    reranked.add(CapabilityCard.forRRF(
                        original.id(),
                        original.name(),
                        original.prov(),
                        original.needs(),
                        original.supp(),
                        original.auth(),
                        relevanceScore, // Use reranking score
                        true, // hasKnnMatch - preserve original
                        true  // hasLexicalMatch - preserve original
                    ));
                }

                return reranked;
            }

        } catch (Exception e) {
            log.error("Cohere reranking failed: {}", e.getMessage());
        }

        return cards;
    }

    /**
     * Rerank using LLM-based scoring (OpenAI or DeepInfra).
     * Uses the LLM to score each query-document pair for relevance.
     */
    private List<CapabilityCard> rerankWithLLM(String query, List<CapabilityCard> cards,
                                                String apiUrl, String apiKey, String model) {
        log.debug("Reranking with LLM: {}", model);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            // Build the scoring prompt
            StringBuilder docsBuilder = new StringBuilder();
            for (int i = 0; i < cards.size(); i++) {
                docsBuilder.append(String.format("[%d] %s\n", i, buildDocumentText(cards.get(i))));
            }

            String systemPrompt = """
                You are a search relevance expert. Given a query and a list of API tools,
                rank them by relevance to the query. Return ONLY a JSON array of indices
                in order of relevance (most relevant first).

                Example output: [3, 0, 2, 1, 4]
                """;

            String userPrompt = String.format("""
                Query: %s

                Tools:
                %s

                Return the indices ordered by relevance (most relevant first).
                Output ONLY the JSON array, nothing else.
                """, query, docsBuilder.toString());

            Map<String, Object> requestBody = Map.of(
                "model", model,
                "messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)
                ),
                "temperature", 0.0,
                "max_tokens", 100
            );

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                apiUrl,
                HttpMethod.POST,
                request,
                JsonNode.class
            );

            if (response.getBody() != null) {
                String content = response.getBody()
                    .path("choices").path(0)
                    .path("message").path("content")
                    .asText();

                // Parse the JSON array of indices
                JsonNode indices = objectMapper.readTree(content);
                if (indices.isArray()) {
                    List<CapabilityCard> reranked = new ArrayList<>();
                    double score = 1.0;
                    double decay = 0.9;

                    for (JsonNode indexNode : indices) {
                        int index = indexNode.asInt();
                        if (index >= 0 && index < cards.size()) {
                            CapabilityCard original = cards.get(index);
                            reranked.add(CapabilityCard.forRRF(
                                original.id(),
                                original.name(),
                                original.prov(),
                                original.needs(),
                                original.supp(),
                                original.auth(),
                                score,
                                true, // hasKnnMatch
                                true  // hasLexicalMatch
                            ));
                            score *= decay;
                        }
                    }

                    // Add any cards that weren't in the LLM response
                    Set<Integer> usedIndices = new HashSet<>();
                    for (JsonNode indexNode : indices) {
                        usedIndices.add(indexNode.asInt());
                    }
                    for (int i = 0; i < cards.size(); i++) {
                        if (!usedIndices.contains(i)) {
                            reranked.add(cards.get(i));
                        }
                    }

                    return reranked;
                }
            }

        } catch (Exception e) {
            log.error("LLM reranking failed: {}", e.getMessage());
        }

        return cards;
    }

    /**
     * Build a text representation of a capability card for reranking.
     */
    private String buildDocumentText(CapabilityCard card) {
        StringBuilder sb = new StringBuilder();
        sb.append(card.name());
        if (card.prov() != null && !card.prov().equals("N/A")) {
            sb.append(" (").append(card.prov()).append(")");
        }
        if (card.needs() != null && !card.needs().isEmpty()) {
            sb.append(": ").append(String.join(", ", card.needs()));
        }
        return sb.toString();
    }
}
