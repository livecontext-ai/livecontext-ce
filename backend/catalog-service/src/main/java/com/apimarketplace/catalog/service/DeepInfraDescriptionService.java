package com.apimarketplace.catalog.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeepInfraDescriptionService {

    private final RestTemplate restTemplate;

    @Value("${ai.mapping.deepinfra.token:}")
    private String deepInfraApiKey;
    @Value("${ai.mapping.deepinfra.url:https://api.deepinfra.com/v1/openai/chat/completions}")
    private String deepInfraBaseUrl;
    @Value("${ai.mapping.deepinfra.model:Qwen/Qwen2.5-72B-Instruct}")
    private String modelName;
    @Value("${ai.mapping.deepinfra.context.max-tokens:40096}")
    private int maxTokens;
    @Value("${ai.mapping.deepinfra.temperature:0.1}")
    private double temperature;
    @Value("${ai.mapping.deepinfra.top_p:0.95}")
    private double topP;
    @Value("${ai.mapping.deepinfra.top_k:50}")
    private int topK;
    @Value("${ai.mapping.deepinfra.presence_penalty:0.0}")
    private double presencePenalty;
    @Value("${ai.mapping.deepinfra.frequency_penalty:0.02}")
    private double frequencyPenalty;
    @Value("${ai.mapping.deepinfra.seed:0}")
    private long seed;
    @Value("${ai.mapping.deepinfra.force_json_response:true}")
    private boolean forceJsonResponse;
    @Value("${ai.mapping.deepinfra.json_mode:true}")
    private boolean jsonMode;
    @Value("${ai.mapping.deepinfra.response_format:json_object}")
    private String responseFormat;

    /**
     * Genere une description optimisee via DeepInfra avec reponse JSON forcee
     * Retourne la reponse JSON brute de DeepInfra au lieu d'extraire le contenu
     */
    public String generateOptimizedDescription(String prompt) {
        try {
            log.info("Generating optimized JSON description via DeepInfra");

            if (deepInfraApiKey == null || deepInfraApiKey.isEmpty()) {
                log.error("DeepInfra API key not configured");
                throw new RuntimeException("DeepInfra API key not configured. Please set DEEPINFRA_TOKEN environment variable.");
            }

            // Le prompt contient deja les instructions pour le JSON structure
            String jsonPrompt = prompt;

            // Preparer la requete pour DeepInfra
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelName);
            requestBody.put("messages", List.of(
                    Map.of("role", "user", "content", jsonPrompt)
                                               ));
            requestBody.put("max_tokens", maxTokens);
            requestBody.put("temperature", temperature);
            requestBody.put("top_p", topP);
            requestBody.put("top_k", topK);
            requestBody.put("stream", false);

            // Configuration to force JSON output
            if (forceJsonResponse || jsonMode) {
                requestBody.put("response_format", Map.of("type", responseFormat));
                log.debug("Forcing JSON response format: {}", responseFormat);
            }

            // Parametres optionnels
            if (presencePenalty != 0.0) {
                requestBody.put("presence_penalty", presencePenalty);
            }
            if (frequencyPenalty != 0.0) {
                requestBody.put("frequency_penalty", frequencyPenalty);
            }
            if (seed > 0) {
                requestBody.put("seed", seed);
            }

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + deepInfraApiKey);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            log.debug("Calling DeepInfra API with model: {}", modelName);
            log.debug("Prompt length: {} characters", jsonPrompt.length());

            ResponseEntity<Map> response = restTemplate.exchange(
                    deepInfraBaseUrl,
                    HttpMethod.POST,
                    entity,
                    Map.class
                                                                );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> responseBody = (Map<String, Object>) response.getBody();

                // Extraire la reponse du modele
                if (responseBody.containsKey("choices") && responseBody.get("choices") instanceof java.util.List) {
                    @SuppressWarnings("unchecked")
                    java.util.List<Map<String, Object>> choices = (java.util.List<Map<String, Object>>) responseBody.get("choices");
                    if (!choices.isEmpty()) {
                        Map<String, Object> choice = choices.get(0);
                        if (choice.containsKey("message") && choice.get("message") instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> message = (Map<String, Object>) choice.get("message");
                            if (message.containsKey("content")) {
                                String rawContent = message.get("content").toString().trim();
                                log.info("Raw JSON response from DeepInfra: {}", rawContent.substring(0, Math.min(300, rawContent.length())) + "...");

                                // Clean the JSON (remove markdown code blocks if present)
                                String cleanedJson = cleanJsonResponse(rawContent);

                                // Valider que c'est du JSON valide
                                if (isValidJson(cleanedJson)) {
                                    log.info("Successfully received valid JSON from DeepInfra");
                                    return cleanedJson;
                                } else {
                                    log.error("DeepInfra returned invalid JSON: {}", cleanedJson.substring(0, Math.min(500, cleanedJson.length())));
                                    throw new RuntimeException("DeepInfra returned invalid JSON. Response: " + cleanedJson.substring(0, Math.min(200, cleanedJson.length())));
                                }
                            }
                        }
                    }
                }
            }

            log.error("Unexpected response format from DeepInfra");
            throw new RuntimeException("Unexpected response format from DeepInfra API");

        } catch (RuntimeException e) {
            // Re-lancer les RuntimeException (nos erreurs intentionnelles)
            throw e;
        } catch (Exception e) {
            log.error("Error calling DeepInfra API: {}", e.getMessage(), e);
            throw new RuntimeException("DeepInfra API call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Nettoie la reponse JSON en enlevant les markdown code blocks
     */
    private String cleanJsonResponse(String rawContent) {
        String cleaned = rawContent.trim();

        // Enlever les markdown code blocks
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        }
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }

        return cleaned.trim();
    }

    /**
     * Valide que la chaîne est du JSON valide
     */
    private boolean isValidJson(String jsonString) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            mapper.readTree(jsonString);
            return true;
        } catch (Exception e) {
            log.debug("JSON validation failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Genere une synthese enrichie via l'IA pour optimiser la recherche RRF
     * Retourne un JSON avec action, summary, keywords categorises et use cases
     */
    public String generateAISummaryAndAction(UUID toolId, String enrichedDescription) {
        try {
            log.info("Generating enriched AI synthesis for tool: {}", toolId);

            String prompt = String.format("""
                Analyze this API endpoint and generate a comprehensive search profile optimized for tool discovery.

                [ENDPOINT DETAILS]
                %s

                Generate a JSON response with these fields:

                1. "action": Choose the MOST SPECIFIC action from this list based on what the endpoint does:
                   CORE DATA: list, get, create, update, delete
                   SEARCH: search, filter, browse
                   CONTENT: upload, download, export, import
                   SOCIAL: follow, unfollow, like, share, comment, react, pin, block
                   COMMUNICATION: send, receive, broadcast, invite
                   WORKFLOW: manage, assign, approve, moderate, audit
                   BUSINESS: order, pay, refund, invoice, checkout, fulfill
                   SCHEDULING: schedule, cancel, reschedule
                   ANALYSIS: analyze, generate, calculate, aggregate
                   VALIDATION: validate, verify, check
                   SYNC: sync, backup, restore
                   MEDIA: stream, record, play
                   COLLABORATION: join, leave, collaborate
                   AUTH: login, logout, authorize
                   MONITORING: monitor, track, watch

                2. "summary": Write 150-200 words describing:
                   - What this endpoint does (primary purpose in 1-2 sentences)
                   - What data it returns (structure/format)
                   - When to use it (2-3 specific scenarios)
                   - Key parameters and their effect on results

                3. "keywords_primary": Array of 3-5 exact action phrases users would search for
                   Example: ["get user stories", "fetch instagram stories", "list user stories", "retrieve stories by user"]

                4. "keywords_synonyms": Array of 3-5 alternative ways to describe this functionality
                   Example: ["ephemeral content", "temporary posts", "24h content", "disappearing media", "story highlights"]

                5. "keywords_params": Array of parameter names with their purpose (format: "param_name:description")
                   Example: ["user_id:instagram user identifier", "limit:maximum items to return", "cursor:pagination token"]

                6. "use_cases": Array of 2-4 concrete scenarios where this tool is useful
                   Example: ["social media monitoring", "content backup before expiration", "competitor analysis", "engagement tracking"]

                Return ONLY valid JSON in this exact format:
                {
                  "action": "list",
                  "summary": "Detailed 150-200 word description...",
                  "keywords_primary": ["phrase1", "phrase2", "phrase3"],
                  "keywords_synonyms": ["synonym1", "synonym2", "synonym3"],
                  "keywords_params": ["param1:description1", "param2:description2"],
                  "use_cases": ["use case 1", "use case 2", "use case 3"]
                }
                """, enrichedDescription);

            return generateOptimizedDescription(prompt);

        } catch (Exception e) {
            log.error("Error generating AI synthesis for tool {}: {}", toolId, e.getMessage(), e);
            throw new RuntimeException("Failed to generate AI synthesis for tool " + toolId + ": " + e.getMessage(), e);
        }
    }
}
