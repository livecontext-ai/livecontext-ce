package com.apimarketplace.catalog.mapping.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * DeepInfra-based strict mapping generator using external AI API.
 * <p>
 * This generator uses DeepInfra's API for generating
 * strict JSONPath mappings from sample JSON data.
 */
public class DeepInfraStrictMappingGenerator implements StrictMappingGenerator {

    private static final Logger logger = LoggerFactory.getLogger(DeepInfraStrictMappingGenerator.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    // ==== Config ====
    private final String deepInfraUrl;
    private final String modelName;
    private final String apiToken;
    private final int timeoutMs;
    private final int contextWindowSize;
    private final int maxTokens;
    private final long maxFileSize;
    private final int defaultArraySampleSize;
    private final boolean preserveStructure;
    private final int maxStringLength;

    // Desactives (conserves pour compatibilite)
    private final int chunkSize;
    private final int minOccurrencesForReference;
    private final int minLengthForReference;

    // Parametres de generation AI
    private final double temperature;
    private final double topP;
    private final int topK;
    private final String stopSequences;
    private final double presencePenalty;
    private final double frequencyPenalty;
    private final long seed;
    private final int maxSteps;

    // Configuration to force JSON output
    private final boolean forceJsonResponse;
    private final boolean jsonMode;
    private final String responseFormat;
    private final boolean systemPromptOverride;

    // Limite de taille du prompt
    private final int maxPromptLength;

    public DeepInfraStrictMappingGenerator(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            @Value("${ai.mapping.deepinfra.url:https://api.deepinfra.com/v1/openai/chat/completions}") String deepInfraUrl,
            @Value("${ai.mapping.deepinfra.model:meta-llama/Meta-Llama-3-8B-Instruct}") String modelName,
            @Value("${ai.mapping.deepinfra.token:}") String apiToken,
            @Value("${ai.mapping.timeout-ms:300000000}") int timeoutMs,
            @Value("${ai.mapping.deepinfra.context.window-size:32768}") int contextWindowSize,
            @Value("${ai.mapping.deepinfra.context.max-tokens:8192}") int maxTokens,
            @Value("${ai.mapping.deepinfra.context.max-file-size:1048576}") long maxFileSize,
            @Value("${ai.mapping.deepinfra.context.chunk-size:2048}") int chunkSize,
            @Value("${ai.mapping.deepinfra.context.preserve-structure:true}") boolean preserveStructure,
            @Value("${ai.mapping.deepinfra.context.max-string-length:200}") int maxStringLength,
            @Value("${ai.mapping.deepinfra.context.array-sample-size:1}") int defaultArraySampleSize,
            @Value("${ai.mapping.deepinfra.context.min-occurrences-for-reference:3}") int minOccurrencesForReference,
            @Value("${ai.mapping.deepinfra.context.min-length-for-reference:200}") int minLengthForReference,
            @Value("${ai.mapping.deepinfra.temperature:0.1}") double temperature,
            @Value("${ai.mapping.deepinfra.top_p:0.9}") double topP,
            @Value("${ai.mapping.deepinfra.top_k:40}") int topK,
            @Value("${ai.mapping.deepinfra.stop_sequences:}") String stopSequences,
            @Value("${ai.mapping.deepinfra.presence_penalty:0.0}") double presencePenalty,
            @Value("${ai.mapping.deepinfra.frequency_penalty:0.0}") double frequencyPenalty,
            @Value("${ai.mapping.deepinfra.seed:0}") long seed,
            @Value("${ai.mapping.deepinfra.max_steps:1}") int maxSteps,
            @Value("${ai.mapping.deepinfra.force_json_response:true}") boolean forceJsonResponse,
            @Value("${ai.mapping.deepinfra.json_mode:true}") boolean jsonMode,
            @Value("${ai.mapping.deepinfra.response_format:json_object}") String responseFormat,
            @Value("${ai.mapping.deepinfra.system_prompt_override:true}") boolean systemPromptOverride,
            @Value("${ai.mapping.deepinfra.max_prompt_length:30000}") int maxPromptLength
                                          ) {
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
        this.deepInfraUrl = deepInfraUrl;
        this.modelName = modelName;
        this.apiToken = apiToken;
        this.timeoutMs = timeoutMs;
        this.contextWindowSize = contextWindowSize;
        this.maxTokens = maxTokens;
        this.maxFileSize = maxFileSize;
        this.chunkSize = chunkSize;
        this.preserveStructure = preserveStructure;
        this.maxStringLength = maxStringLength;
        this.defaultArraySampleSize = Math.max(1, defaultArraySampleSize);
        this.minOccurrencesForReference = minOccurrencesForReference;
        this.minLengthForReference = minLengthForReference;
        this.temperature = temperature;
        this.topP = topP;
        this.topK = topK;
        this.stopSequences = stopSequences;
        this.presencePenalty = presencePenalty;
        this.frequencyPenalty = frequencyPenalty;
        this.seed = seed;
        this.maxSteps = maxSteps;
        this.forceJsonResponse = forceJsonResponse;
        this.jsonMode = jsonMode;
        this.responseFormat = responseFormat;
        this.systemPromptOverride = systemPromptOverride;
        this.maxPromptLength = maxPromptLength;
    }

    private static String quote(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Override
    public String generateStrictMapping(String sampleJson, StrictMappingConstraints constraints)
            throws MappingGenerationException {

        try {
            logger.info("Generating strict mapping with DeepInfra for sample JSON ({} chars)", sampleJson.length());

            String processedJson = processLargeFile(sampleJson, constraints);

            logger.info("FINAL JSON SIZE: {} characters ({} bytes) - ready for AI processing",
                        processedJson.length(), processedJson.getBytes().length);

            String prompt = buildPrompt(processedJson, constraints);

            String aiResponse = callDeepInfraApi(prompt);

            String validatedMapping = validateAndCleanResponse(aiResponse, sampleJson, constraints);

            logger.info("Successfully generated strict mapping");
            return aiResponse;

        } catch (Exception e) {
            logger.error("Failed to generate strict mapping: {}", e.getMessage(), e);
            throw new MappingGenerationException("Failed to generate strict mapping", e);
        }
    }

    @Override
    public String generateStrictMappingWithContext(String sampleJson,
                                                   StrictMappingConstraints constraints,
                                                   String toolName,
                                                   String toolCategoryName,
                                                   String toolSubCategoryName,
                                                   String httpMethod,
                                                   String endpoint,
                                                   String toolDescription)
            throws MappingGenerationException {

        try {
            logger.info("Generating strict mapping with tool context using DeepInfra for sample JSON ({} chars)", sampleJson.length());

            String processedJson = processLargeFile(sampleJson, constraints);

            logger.info("FINAL JSON SIZE: {} characters ({} bytes) - ready for AI processing",
                        processedJson.length(), processedJson.getBytes().length);

            String prompt = buildPromptWithContext(processedJson, constraints,
                                                   toolName, toolCategoryName, toolSubCategoryName, httpMethod, endpoint, toolDescription);

            String aiResponse = callDeepInfraApi(prompt);

            String validatedMapping = validateAndCleanResponse(aiResponse, sampleJson, constraints);

            logger.info("Successfully generated strict mapping with tool context");
            return aiResponse;

        } catch (Exception e) {
            logger.error("Failed to generate strict mapping with tool context: {}", e.getMessage(), e);
            throw new MappingGenerationException("Failed to generate strict mapping with tool context", e);
        }
    }

    // =========================
    // Prompt builders
    // =========================

    @Override
    public boolean isAvailable() {
        try {
            if (apiToken == null || apiToken.trim().isEmpty()) {
                logger.debug("DeepInfra API token not configured");
                return false;
            }

            // Test simple call to check availability
            Map<String, Object> testRequest = new HashMap<>();
            testRequest.put("model", modelName);
            testRequest.put("messages", List.of(
                    Map.of("role", "user", "content", "Hello")
                                               ));
            testRequest.put("max_tokens", 1);

            webClient.post()
                     .uri(deepInfraUrl)
                     .contentType(MediaType.APPLICATION_JSON)
                     .header("Authorization", "Bearer " + apiToken)
                     .bodyValue(testRequest)
                     .retrieve()
                     .bodyToMono(String.class)
                     .timeout(Duration.ofMillis(5000))
                     .block();
            return true;
        } catch (Exception e) {
            logger.debug("DeepInfra service not available: {}", e.getMessage());
            return false;
        }
    }

    private String buildPrompt(
            String sampleJson,
            String toolSubCategoryName,
            String toolCategoryName,
            String toolName,
            String httpMethod,
            String endpoint,
            String queryParamsJson,
            String bodyParamsJson,
            String toolDescription
                              ) {

        // Deterministic, JSON-only


        final String p = "Generate a JSONPath mapping. Return ONLY valid JSON, no explanations.\n\n" +

                         // Context (do NOT echo)
                         "CONTEXT (do NOT echo): {" +
                         "\"category_name\": " + quote(toolCategoryName) + ", " +
                         "\"sub_category_name\": " + quote(toolSubCategoryName) + ", " +
                         "\"method_name\": " + quote(toolName) + ", " +
                         "\"http_method\": " + quote(httpMethod) + ", " +
                         "\"endpoint\": " + quote(endpoint) + ", " +
                         "\"query_params\": " + (queryParamsJson == null ? "{}" : queryParamsJson) + ", " +
                         "\"body_params\": " + (bodyParamsJson == null ? "{}" : bodyParamsJson) + ", " +
                         "\"description\": " + quote(toolDescription) +
                         "}\n\n" +

                         // CRITICAL RULES
                         "CRITICAL RULES:\n" +
                         "1) items_path detection:\n" +
                         "   - For ARRAYS of items: '$.data[*]', '$.items[*]', '$.results[*]', '$.data.edges[*].node'\n" +
                         "   - For SINGLE item responses: '$.data', '$.item', '$.result', '$' (if root is the item)\n" +
                         "   - NEVER use '$.0' or invalid paths\n" +
                         "2) Each field has EXACTLY 2 candidates: ['@.field', '<items_path>.field']. NO MORE.\n" +
                         "3) NO fallback candidates like '@.0', '$.0.0', etc.\n" +
                         "4) Globals section is OPTIONAL - only if pagination exists.\n\n" +
                         "COMPREHENSIVE COVERAGE RULES:\n" +
                         "5) Map ALL scalar fields in each item: IDs, names, counts, booleans, timestamps, URLs, etc.\n" +
                         "6) For nested objects: flatten with snake_case (e.g., 'owner.username' → 'owner_username')\n" +
                         "7) For arrays of scalars: use '[*]' and 'array<type>' (e.g., 'tags[*]' → 'array<string>')\n" +
                         "8) For arrays of objects: extract each scalar field as 'array<type>' (e.g., 'comments[*].text' → 'array<string>')\n" +
                         "9) Essential fields by resource type (include when present):\n" +
                         "   - IDENTIFIERS: id, uuid, pk, external_id, reference_id, handle, slug, code, token\n" +
                         "   - TIMESTAMPS: created_at, updated_at, modified_at, deleted_at, expires_at, published_at, scheduled_at\n" +
                         "   - USER/PERSON: name, username, email, first_name, last_name, display_name, avatar_url, profile_pic_url, is_verified, is_active\n" +
                         "   - CONTENT: title, name, description, content, text, body, caption, subject, message, summary\n" +
                         "   - MEDIA: url, display_url, thumbnail_url, video_url, audio_url, file_url, src, href, width, height, duration, size\n" +
                         "   - COUNTS/METRICS: count, total, likes, views, comments, followers, subscribers, members, items, pages, score, rating\n" +
                         "   - STATUS/FLAGS: status, state, type, category, priority, visibility, is_public, is_private, is_active, is_deleted, enabled\n" +
                         "   - FINANCIAL: amount, price, cost, total, subtotal, tax, discount, currency, billing_cycle\n" +
                         "   - LOCATION: address, city, country, latitude, longitude, timezone, region\n" +
                         "   - TECHNICAL: version, hash, checksum, mime_type, encoding, format, platform, device, browser\n" +
                         "10) Smart type mapping:\n" +
                         "   - IDs/UUIDs/tokens → 'string' (even if numeric)\n" +
                         "   - Timestamps/dates → 'integer' or 'long'\n" +
                         "   - Counts/quantities/metrics → 'integer'\n" +
                         "   - Prices/amounts → 'number' (for decimals) or 'integer'\n" +
                         "   - URLs/emails/text → 'string'\n" +
                         "   - Flags/booleans → 'boolean'\n" +
                         "   - Coordinates → 'number'\n" +

                         // COMPREHENSIVE EXAMPLES
                         "EXAMPLES FOR DIFFERENT RESPONSE TYPES:\n\n" +
                         "Example 1 - Array of items ($.products[*]):\n" +
                         "{\n" +
                         "  \"source\": {\"format\":\"json\",\"items_path\":\"$.products[*]\"},\n" +
                         "  \"fields\": {\n" +
                         "    \"id\": {\"candidates\": [\"@.id\", \"$.products[*].id\"], \"to\": \"string\", \"required\": false},\n" +
                         "    \"name\": {\"candidates\": [\"@.name\", \"$.products[*].name\"], \"to\": \"string\", \"required\": false}\n" +
                         "  }\n" +
                         "}\n\n" +
                         "Example 2 - Single item response ($.data):\n" +
                         "{\n" +
                         "  \"source\": {\"format\":\"json\",\"items_path\":\"$.data\"},\n" +
                         "  \"fields\": {\n" +
                         "    \"id\": {\"candidates\": [\"@.id\", \"$.data.id\"], \"to\": \"string\", \"required\": false},\n" +
                         "    \"owner_username\": {\"candidates\": [\"@.owner.username\", \"$.data.owner.username\"], \"to\": \"string\", \"required\": false},\n" +
                         "    \"video_duration\": {\"candidates\": [\"@.video_duration\", \"$.data.video_duration\"], \"to\": \"number\", \"required\": false}\n" +
                         "  },\n" +
                         "  \"globals\": {\n" +
                         "    \"status\": {\"candidates\": [\"$.status\"], \"to\": \"string\", \"required\": false}\n" +
                         "  }\n" +
                         "}\n\n" +
                         "Example 3 - Root level item ($):\n" +
                         "{\n" +
                         "  \"source\": {\"format\":\"json\",\"items_path\":\"$\"},\n" +
                         "  \"fields\": {\n" +
                         "    \"id\": {\"candidates\": [\"@.id\", \"$.id\"], \"to\": \"string\", \"required\": false}\n" +
                         "  }\n" +
                         "}\n\n" +


                         // Final instructions
                         "FINAL INSTRUCTIONS:\n" +
                         "- Analyze the ENTIRE input JSON structure thoroughly\n" +
                         "- Map EVERY useful scalar field you find in the items\n" +
                         "- Don't skip fields - be comprehensive but follow the 2-candidate rule\n" +
                         "- Prioritize important business data (IDs, counts, user info, media URLs, timestamps)\n" +
                         "- Return ONLY the JSON mapping object, no explanations\n\n" +
                         "INPUT JSON:\n" + sampleJson;

        return p;
    }


    private String buildPromptWithContext(String sampleJson,
                                          StrictMappingConstraints constraints,
                                          String toolName,
                                          String toolCategoryName,
                                          String toolSubCategoryName,
                                          String httpMethod,
                                          String endpoint,
                                          String toolDescription) {
        return buildPrompt(sampleJson,
                           toolSubCategoryName != null ? toolSubCategoryName : "",
                           toolCategoryName != null ? toolCategoryName : "",
                           toolName != null ? toolName : "",
                           httpMethod != null ? httpMethod : "GET",
                           endpoint != null ? endpoint : "",
                           null,
                           null,
                           toolDescription != null ? toolDescription : "");
    }

    private String buildPrompt(String sampleJson, StrictMappingConstraints constraints) {
        return buildPrompt(sampleJson, "", "", "", "GET", "", null, null, "");
    }

    // =========================
    // DeepInfra API call
    // =========================

    private String callDeepInfraApi(String prompt) throws MappingGenerationException {
        if (apiToken == null || apiToken.trim().isEmpty()) {
            throw new MappingGenerationException("DeepInfra API token not configured");
        }

        try {
            Map<String, Object> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", modelName);
            requestBody.put("messages", List.of(message));
            requestBody.put("max_tokens", maxTokens);
            requestBody.put("temperature", temperature);
            requestBody.put("top_p", topP);
            requestBody.put("top_k", topK);
            requestBody.put("stream", false);

            // Configuration to force JSON output
            if (forceJsonResponse || jsonMode) {
                requestBody.put("response_format", Map.of("type", responseFormat));
                logger.debug("Forcing JSON response format: {}", responseFormat);
            }

            // Parametres optionnels
            if (stopSequences != null && !stopSequences.trim().isEmpty()) {
                requestBody.put("stop", Arrays.asList(stopSequences.split(",")));
            }
            if (presencePenalty != 0.0) {
                requestBody.put("presence_penalty", presencePenalty);
            }
            if (frequencyPenalty != 0.0) {
                requestBody.put("frequency_penalty", frequencyPenalty);
            }
            if (seed > 0) {
                requestBody.put("seed", seed);
            }
            if (maxSteps > 1) {
                requestBody.put("max_steps", maxSteps);
            }

            logger.debug("Calling DeepInfra API with model: {}", modelName);
            logger.debug("Prompt length: {} characters", prompt.length());

            // Validation de la taille du prompt pour DeepInfra
            int promptLength = prompt.length();

            if (promptLength > maxPromptLength) {
                logger.error("Prompt too long for DeepInfra ({} chars, max: {}). Rejecting request.", promptLength, maxPromptLength);
                throw new MappingGenerationException(
                        String.format("Prompt too large for DeepInfra API. Current size: %d characters, maximum allowed: %d characters. Please reduce the input data size.",
                                      promptLength, maxPromptLength)
                );
            }

            String response = webClient.post()
                                       .uri(deepInfraUrl)
                                       .contentType(MediaType.APPLICATION_JSON)
                                       .header("Authorization", "Bearer " + apiToken)
                                       .header("Accept-Charset", "UTF-8")
                                       .header("Content-Type", "application/json; charset=UTF-8")
                                       .bodyValue(requestBody)
                                       .retrieve()
                                       .bodyToMono(String.class)
                                       .timeout(Duration.ofMillis(timeoutMs))
                                       .doOnSubscribe(subscription -> logger.debug("Starting DeepInfra API call with timeout: {}ms", timeoutMs))
                                       .doOnSuccess(result -> logger.debug("DeepInfra API call completed successfully"))
                                       .doOnError(error -> logger.warn("DeepInfra API call failed: {}", error.getMessage()))
                                       .block();

            if (response == null || response.trim().isEmpty()) {
                throw new MappingGenerationException("Empty response from DeepInfra API");
            }

            logger.debug("Raw DeepInfra response: {}", response);

            JsonNode responseNode = objectMapper.readTree(response);

            // Verifier si la reponse indique une erreur
            if (responseNode.has("error")) {
                String error = responseNode.get("error").asText();
                logger.error("DeepInfra returned an error: {}", error);
                throw new MappingGenerationException("DeepInfra error: " + error);
            }

            // Extraire le contenu de la reponse selon le schema DeepInfra
            if (responseNode.has("choices") && responseNode.get("choices").isArray()) {
                ArrayNode choices = (ArrayNode) responseNode.get("choices");
                if (choices.size() > 0) {
                    JsonNode firstChoice = choices.get(0);
                    if (firstChoice.has("message") && firstChoice.get("message").has("content")) {
                        String content = firstChoice.get("message").get("content").asText();
                        if (content != null && !content.trim().isEmpty()) {
                            logger.debug("Using content field (len={}): {}", content.length(),
                                         content.length() > 200 ? content.substring(0, 200) + "..." : content);

                            // Log des metadonnees de reponse
                            if (responseNode.has("usage")) {
                                JsonNode usage = responseNode.get("usage");
                                logger.debug("Token usage - prompt: {}, completion: {}, total: {}",
                                             usage.has("prompt_tokens") ? usage.get("prompt_tokens").asInt() : 0,
                                             usage.has("completion_tokens") ? usage.get("completion_tokens").asInt() : 0,
                                             usage.has("total_tokens") ? usage.get("total_tokens").asInt() : 0);
                            }

                            return content;
                        }
                    }
                }
            }

            // Fallback pour l'ancien format de reponse
            if (responseNode.has("text")) {
                String text = responseNode.get("text").asText();
                if (text != null && !text.trim().isEmpty()) {
                    logger.debug("Using text field (len={})", text.length());
                    return text;
                }
            }

            logger.error("No valid response content found. Full response: {}", response);
            throw new MappingGenerationException("No valid response content from DeepInfra API. Response was empty or invalid.");

        } catch (WebClientResponseException e) {
            logger.error("DeepInfra API error: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new MappingGenerationException("DeepInfra API error: " + e.getStatusCode(), e);
        } catch (Exception e) {
            if (e.getCause() instanceof java.util.concurrent.TimeoutException) {
                logger.error("DeepInfra API reactive timeout after {}ms: {}", timeoutMs, e.getMessage());
                throw new MappingGenerationException("DeepInfra API timeout after " + timeoutMs + "ms. The request may be too complex or the service is slow. Try with a smaller input.", e);
            } else if (e.getMessage() != null && e.getMessage().contains("TimeoutException")) {
                logger.error("DeepInfra API timeout after {}ms: {}", timeoutMs, e.getMessage());
                throw new MappingGenerationException("DeepInfra API timeout after " + timeoutMs + "ms. The request may be too complex or the service is slow. Try with a smaller input.", e);
            } else {
                logger.error("Failed to call DeepInfra API: {}", e.getMessage(), e);
                throw new MappingGenerationException("Failed to call DeepInfra API", e);
            }
        }
    }


    // =========================
    // Validation / Normalisation
    // =========================

    private String validateAndCleanResponse(String aiResponse, String sampleJson, StrictMappingConstraints constraints)
            throws MappingGenerationException {
        try {
            // 1) Parse brut + fallback nettoyage
            JsonNode raw;
            try {
                raw = objectMapper.readTree(aiResponse);
            } catch (Exception first) {
                logger.debug("First JSON parse failed, attempting to clean response: {}", first.getMessage());
                String cleaned = cleanAiResponse(aiResponse);
                if (cleaned == null || cleaned.isBlank()) {
                    throw new MappingGenerationException("AI response could not be cleaned to valid JSON");
                }
                raw = objectMapper.readTree(cleaned);
            }
            if (raw == null || !raw.isObject()) {
                throw new MappingGenerationException("AI response is not a JSON object");
            }

            // 2) Prepare sortie
            ObjectNode out = objectMapper.createObjectNode();
            ObjectNode source = objectMapper.createObjectNode();
            ObjectNode itemFields = objectMapper.createObjectNode();
            ObjectNode globalFields = objectMapper.createObjectNode();

            // 3) Source/Fields bruts si presents
            if (raw.has("source") && raw.get("source").isObject()) {
                source.setAll((ObjectNode) raw.get("source"));
            }
            if (raw.has("fields") && raw.get("fields").isObject()) {
                itemFields.setAll((ObjectNode) raw.get("fields"));
            }

            // 4) Inference items_path + root_alternatives a partir du sample
            JsonNode sampleNode = objectMapper.readTree(sampleJson);
            InferredItems ii = inferItemsPath(sampleNode);
            String inferredItemsPath = ii.itemsPath();
            List<String> inferredAlts = new ArrayList<>(ii.rootAlternatives());

            // 5) Contraintes eventuelles
            if (constraints != null && constraints.getItemsPath() != null && !constraints.getItemsPath().isBlank()) {
                inferredItemsPath = constraints.getItemsPath().trim();
                if (!inferredItemsPath.startsWith("$")) {
                    // force absolu
                    inferredItemsPath = "$" + (inferredItemsPath.startsWith(".") ? inferredItemsPath.substring(1) : "." + inferredItemsPath);
                }
                if (!inferredAlts.contains(inferredItemsPath)) inferredAlts.add(0, inferredItemsPath);
            }

            // 6) Normalize items_path (never numeric/$.null), prefer inferred fallback
            String aiItemsPath = (source.has("items_path") && source.get("items_path").isTextual())
                                 ? source.get("items_path").asText() : null;
            String finalItemsPath = sanitizeItemsPath(aiItemsPath, inferredItemsPath);

// 7) root_alternatives: remove invalids, inject items_path as first, no nulls/dups
            ArrayNode sanitizedAlts = sanitizeAlternatives(
                    (source.has("root_alternatives") && source.get("root_alternatives").isArray())
                    ? (ArrayNode) source.get("root_alternatives")
                    : null,
                    finalItemsPath
                                                          );

// 8) Final source
            source.removeAll();
            source.put("format", "json");
            source.put("items_path", finalItemsPath);
            source.set("root_alternatives", sanitizedAlts);

// 9) Collect global fields present at top-level (raw) - unchanged from your logic
//    ...

// 10) Clean item fields & auto-promote mis-placed globals
            List<String> itemKeys = new ArrayList<>();
            itemFields.fieldNames().forEachRemaining(itemKeys::add);
            for (String k : itemKeys) {
                JsonNode spec = itemFields.get(k);
                if (!looksLikeFieldSpec(spec)) {
                    itemFields.remove(k);
                    continue;
                }

                ObjectNode o = (ObjectNode) spec;
                // cleanse candidates (remove parentheses/etc.)
                if (o.has("candidates") && o.get("candidates").isArray()) {
                    ArrayNode cleaned = cleanseCandidates((ArrayNode) o.get("candidates"));
                    o.set("candidates", cleaned);
                }

                // If this field is actually global → promote
                if (isLikelyGlobal(o, finalItemsPath)) {
                    itemFields.remove(k);
                    globalFields.set(k, forceGlobalCandidates(o));
                    continue;
                }

                // Enforce exactly two item candidates: "@.<tail>" and "<items_path>.<tail>"
                if (o.has("candidates") && o.get("candidates").isArray()) {
                    ArrayNode enforced = enforceItemCandidates((ArrayNode) o.get("candidates"), finalItemsPath);
                    // After enforcement, remove any duplicates/forbidden again (safety)
                    o.set("candidates", cleanseCandidates(enforced));
                }
            }

// 11) Sort keys
            ObjectNode sortedItemFields = sortObjectNodeKeys(itemFields);
            ObjectNode sortedGlobals = sortObjectNodeKeys(globalFields);

// 12) Compose final output
            out.set("source", source);
            out.set("fields", sortedItemFields);
            Iterator<String> gIt = sortedGlobals.fieldNames();
            while (gIt.hasNext()) {
                String gk = gIt.next();
                out.set(gk, sortedGlobals.get(gk));
            }

            return objectMapper.writeValueAsString(out);

        } catch (Exception e) {
            logger.error("Failed to process AI response: {}", e.getMessage(), e);
            logger.error("Original AI response was: {}", aiResponse);
            throw new MappingGenerationException("Invalid AI response format: " + e.getMessage(), e);
        }
    }

    private boolean isValidAbsolutePath(String s) {
        if (s == null) return false;
        s = s.trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return false;
        if (!s.startsWith("$")) return false;
        // refuser parentheses
        return s.indexOf('(') < 0 && s.indexOf(')') < 0;
    }

    private ObjectNode cleanFieldSpecObject(ObjectNode spec, String itemsPath, boolean isGlobal) {
        ArrayNode in = (ArrayNode) spec.get("candidates");
        ArrayNode out = objectMapper.createArrayNode();
        LinkedHashSet<String> seen = new LinkedHashSet<>();

        for (JsonNode cand : in) {
            if (!cand.isTextual()) continue;
            String s = cand.asText().trim();
            if (s.isEmpty()) continue;
            // bannir parentheses, "null", "0", bare tokens
            if (s.equals("null") || s.equals("0")) continue;
            if (s.contains("(") || s.contains(")")) continue;
            if (!(s.startsWith("$.") || s.startsWith("@."))) continue;

            // OK
            if (seen.add(s)) out.add(s);
        }

        // si global => exactly 1 candidate absolue ; si item => max 2 candidates (mais on laisse normalizeFieldCandidates gerer le detail ensuite)
        if (isGlobal) {
            // garder la 1re absolue si dispo, sinon la 1re et forcer absolu ?
            String firstAbs = null;
            for (JsonNode c : out) {
                String s = c.asText();
                if (s.startsWith("$.")) {
                    firstAbs = s;
                    break;
                }
            }
            if (firstAbs == null) {
                // impossible de construire un global propre → abandonner ce champ
                return null;
            }
            ArrayNode one = objectMapper.createArrayNode();
            one.add(firstAbs);
            spec.set("candidates", one);
        } else {
            // item: garder au plus 2 (on laissera la normalisation externe ajuster le pattern @./items_path.)
            if (out.size() == 0) return null;
            if (out.size() > 2) {
                ArrayNode trimmed = objectMapper.createArrayNode();
                trimmed.add(out.get(0));
                trimmed.add(out.get(1));
                spec.set("candidates", trimmed);
            } else {
                spec.set("candidates", out);
            }
        }

        // 'to' / 'required' par defaut si absents
        if (!spec.has("to")) spec.put("to", "string");
        if (!spec.has("required") || !spec.get("required").isBoolean()) spec.put("required", false);

        // Nettoyage de champs inconnus optionnel (path_anyOf/default/map/max_fallbacks… si presents, on les laisse)
        return spec;
    }


    private void dropMetaCandidates(ObjectNode fields) {
        if (fields == null) return;
        final String[] banned = new String[]{"__reference__", "__truncated_string__", "__truncated__", "..."}; // prudence
        Iterator<String> fieldIterator = fields.fieldNames();
        while (fieldIterator.hasNext()) {
            String fieldName = fieldIterator.next();
            JsonNode def = fields.get(fieldName);
            if (!def.isObject()) continue;
            ObjectNode obj = (ObjectNode) def;
            if (!obj.has("candidates") || !obj.get("candidates").isArray()) continue;

            ArrayNode input = (ArrayNode) obj.get("candidates");
            ArrayNode cleaned = objectMapper.createArrayNode();
            for (JsonNode n : input) {
                if (!n.isTextual()) continue;
                String p = n.asText();
                boolean keep = true;
                for (String b : banned) {
                    if (p.contains("." + b) || p.startsWith(b)) {
                        keep = false;
                        break;
                    }
                }
                if (keep) cleaned.add(p);
            }
            obj.set("candidates", cleaned);
        }
    }

    private InferredItems inferItemsPath(JsonNode sampleRoot) {
        String itemsPath = "$";
        List<String> rootAlts = new ArrayList<>();
        String unwrapKey = null;

        if (sampleRoot == null || sampleRoot.isNull()) {
            rootAlts.add(itemsPath);
            return new InferredItems(itemsPath, rootAlts, null);
        }

        if (sampleRoot.isArray()) {
            itemsPath = "$[*]";
            rootAlts.add(itemsPath);
            return new InferredItems(itemsPath, rootAlts, null);
        }

        if (sampleRoot.isObject()) {
            JsonNode items = sampleRoot.get("items");
            if (items != null && items.isArray() && items.size() > 0) {
                JsonNode first = items.get(0);
                if (first != null && first.isObject()) {
                    // Check for single-key wrapper pattern first
                    if (first.size() == 1) {
                        String key = first.fieldNames().next();
                        JsonNode val = first.get(key);
                        if (val != null && val.isObject()) {
                            unwrapKey = key;
                            itemsPath = "$.items[*]." + key;
                            rootAlts.add(itemsPath);
                            rootAlts.add("$.items[*]");
                            return new InferredItems(itemsPath, rootAlts, unwrapKey);
                        }
                    }

                    // Check for Instagram-like pattern: items with 'media' property
                    if (first.has("media") && first.get("media").isObject()) {
                        itemsPath = "$.items[*].media";
                        rootAlts.add(itemsPath);
                        rootAlts.add("$.items[*]");
                        return new InferredItems(itemsPath, rootAlts, null);
                    }
                }
                itemsPath = "$.items[*]";
                rootAlts.add(itemsPath);
                return new InferredItems(itemsPath, rootAlts, null);
            }
        }

        rootAlts.add(itemsPath);
        return new InferredItems(itemsPath, rootAlts, null);
    }

    private void normalizeFieldCandidates(ObjectNode fields, String itemsPath, String unwrapKey) {
        if (fields == null) return;

        String absBase = (itemsPath != null && itemsPath.contains("[*]")) ? itemsPath.replace("[*]", "[0]") : null;

        Iterator<String> fieldIterator = fields.fieldNames();
        while (fieldIterator.hasNext()) {
            String fieldName = fieldIterator.next();
            JsonNode def = fields.get(fieldName);
            if (!def.isObject()) continue;
            ObjectNode obj = (ObjectNode) def;

            ArrayNode cand = obj.has("candidates") && obj.get("candidates").isArray()
                             ? (ArrayNode) obj.get("candidates")
                             : objectMapper.createArrayNode();

            // strip "@.<unwrapKey>."
            if (unwrapKey != null && cand.size() > 0) {
                for (int i = 0; i < cand.size(); i++) {
                    JsonNode n = cand.get(i);
                    if (n.isTextual()) {
                        String path = n.asText();
                        String prefix = "@." + unwrapKey + ".";
                        if (path.startsWith(prefix)) {
                            cand.set(i, objectMapper.getNodeFactory().textNode("@." + path.substring(prefix.length())));
                        }
                    }
                }
            }

            // nettoyer nuls/vides/"null"
            ArrayNode cleaned = objectMapper.createArrayNode();
            for (JsonNode n : cand) {
                if (!n.isTextual()) continue;
                String p = n.asText();
                if (p == null || p.isBlank() || "null".equalsIgnoreCase(p)) continue;
                cleaned.add(p);
            }

            // fallback absolu ancre si on a au moins une relative
            boolean hasRelative = false;
            for (JsonNode n : cleaned) {
                if (n.isTextual() && n.asText().startsWith("@.")) {
                    hasRelative = true;
                    break;
                }
            }
            if (hasRelative && absBase != null) {
                String rel = null;
                for (JsonNode n : cleaned) {
                    if (n.isTextual() && n.asText().startsWith("@.")) {
                        rel = n.asText().substring(2);
                        break;
                    }
                }
                if (rel != null) {
                    String abs = absBase + (rel.startsWith(".") ? rel : "." + rel);
                    boolean exists = false;
                    for (JsonNode n : cleaned) {
                        if (n.isTextual() && n.asText().equals(abs)) {
                            exists = true;
                            break;
                        }
                    }
                    if (!exists) cleaned.add(abs);
                }
            }

            obj.set("candidates", cleaned);

            if (!obj.has("to")) obj.put("to", "string");
            if (!obj.has("required")) obj.put("required", false);
            else if (!obj.get("required").isBoolean()) obj.put("required", false);
        }
    }

    private String cleanAiResponse(String response) {
        String cleaned = response == null ? "" : response.trim();
        if (cleaned.isEmpty()) return cleaned;

        // Clean HTML and thinking tags
        cleaned = cleaned.replaceAll("(?s)<[^>]+>", "");
        cleaned = cleaned.replaceAll("(?is)<think>.*?</think>", "");
        cleaned = cleaned.replaceAll("(?is)<thinking>.*?</thinking>", "");
        cleaned = cleaned.replaceAll("(?is)<thought>.*?</thought>", "");

        // Clean common text prefixes
        cleaned = cleaned.replaceAll("(?i)^(here( is|'s)?|the) .*?json[:\\n]*", "");
        cleaned = cleaned.replaceAll("(?i)^we need to.*?json[:\\n]*", "");
        cleaned = cleaned.replaceAll("(?i)^so .*?json[:\\n]*", "");
        cleaned = cleaned.replaceAll("(?i)^but .*?json[:\\n]*", "");
        cleaned = cleaned.replaceAll("(?i)^here is the generated json object[:\\n]*", "");
        cleaned = cleaned.replaceAll("(?i)^generated json[:\\n]*", "");
        cleaned = cleaned.replaceAll("(?i)^json response[:\\n]*", "");

        // Clean code blocks
        if (cleaned.startsWith("```json")) cleaned = cleaned.substring(7);
        if (cleaned.startsWith("```")) cleaned = cleaned.substring(3);
        if (cleaned.endsWith("```")) cleaned = cleaned.substring(0, cleaned.length() - 3);

        // Extraire le JSON en trouvant le premier { et le dernier } correspondant
        int jsonStart = cleaned.indexOf('{');
        if (jsonStart != -1) {
            int brace = 0, end = -1;
            boolean inString = false;
            boolean escaped = false;

            for (int i = jsonStart; i < cleaned.length(); i++) {
                char c = cleaned.charAt(i);

                if (escaped) {
                    escaped = false;
                    continue;
                }

                if (c == '\\') {
                    escaped = true;
                    continue;
                }

                if (c == '"' && !escaped) {
                    inString = !inString;
                    continue;
                }

                if (!inString) {
                    if (c == '{') brace++;
                    else if (c == '}') {
                        brace--;
                        if (brace == 0) {
                            end = i;
                            break;
                        }
                    }
                }
            }

            if (end != -1) {
                cleaned = cleaned.substring(jsonStart, end + 1);
            } else {
                // JSON tronque - essayer de le reparer
                logger.warn("JSON appears to be truncated, attempting to repair...");
                cleaned = repairTruncatedJson(cleaned.substring(jsonStart));
            }
        }

        return cleaned.trim();
    }

    // =========================
    // Cleaning helpers
    // =========================

    private String repairTruncatedJson(String json) {
        if (json == null || json.isEmpty()) return json;

        // Compter les accolades ouvertes et fermees
        int openBraces = 0;
        int openBrackets = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (c == '"' && !escaped) {
                inString = !inString;
                continue;
            }

            if (!inString) {
                if (c == '{') openBraces++;
                else if (c == '}') openBraces--;
                else if (c == '[') openBrackets++;
                else if (c == ']') openBrackets--;
            }
        }

        StringBuilder repaired = new StringBuilder(json);

        // Fermer les chaînes ouvertes
        if (inString) {
            repaired.append('"');
        }

        // Fermer les tableaux ouverts
        for (int i = 0; i < openBrackets; i++) {
            repaired.append(']');
        }

        // Fermer les objets ouverts
        for (int i = 0; i < openBraces; i++) {
            repaired.append('}');
        }

        logger.debug("Repaired JSON: {}", repaired);
        return repaired.toString();
    }

    private String processLargeFile(String sampleJson, StrictMappingConstraints constraints)
            throws MappingGenerationException {

        long bytes = sampleJson.getBytes().length;
        logger.debug("Processing file of size: {} bytes (max allowed: {})", bytes, maxFileSize);

        // Pour DeepInfra, etre tres agressif avec la compression
        long compressionThreshold = Math.min(maxFileSize, 15L * 1024L); // 15KB threshold
        if (bytes <= compressionThreshold) return sampleJson;

        logger.warn("Input exceeds threshold ({}B). Applying aggressive structure-preserving sampling for DeepInfra...", compressionThreshold);
        try {
            JsonNode root = objectMapper.readTree(sampleJson);

            // Clean invalid UTF-8 characters before processing
            root = cleanUtf8InJsonNode(root);

            // 1er passage : sampling tres agressif pour DeepInfra
            String sampled = writeDownsampled(root, 1, Math.max(20, maxStringLength));
            if (sampled.getBytes().length <= maxFileSize) return sampled;

            // 2e passage : encore plus agressif
            String sampled2 = writeDownsampled(root, 1, Math.max(10, maxStringLength / 2));
            if (sampled2.getBytes().length <= maxFileSize) return sampled2;

            // 3e passage : ultra agressif
            String sampled3 = writeDownsampled(root, 1, 10);
            if (sampled3.getBytes().length <= maxFileSize) return sampled3;

            // 4e passage : extreme
            String sampled4 = writeDownsampled(root, 1, 5);
            if (sampled4.getBytes().length <= maxFileSize) return sampled4;

            // fallback ultime : troncature brute
            logger.warn("Downsampled JSON still above maxFileSize; using raw truncation.");
            return safeRawTruncate(sampleJson, (int) (maxFileSize * 0.8));

        } catch (Exception e) {
            logger.warn("Failed to parse JSON for intelligent processing, using raw truncation: {}", e.getMessage());
            return safeRawTruncate(sampleJson, (int) (maxFileSize * 0.8));
        }
    }

    // =========================
    // UTF-8 Cleaning
    // =========================

    /**
     * Nettoie les caracteres UTF-8 invalides qui peuvent causer des erreurs de parsing JSON.
     * Supprime les surrogate pairs invalides et les caracteres de contrôle problematiques.
     */
    private String cleanUtf8String(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        StringBuilder cleaned = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            // Verifier si c'est un surrogate pair valide
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < input.length() && Character.isLowSurrogate(input.charAt(i + 1))) {
                    // Surrogate pair valide, le garder
                    cleaned.append(c);
                    cleaned.append(input.charAt(i + 1));
                    i++; // Skip le caractere suivant
                } else {
                    // Surrogate pair invalide, le remplacer
                    cleaned.append('?');
                }
            } else if (Character.isLowSurrogate(c)) {
                // Low surrogate isole, le remplacer
                cleaned.append('?');
            } else if (Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t') {
                // Caracteres de contrôle (sauf les whitespaces standards), les remplacer
                cleaned.append(' ');
            } else {
                // Caractere normal, le garder
                cleaned.append(c);
            }
        }

        return cleaned.toString();
    }

    /**
     * Nettoie recursivement tous les strings dans un JsonNode pour eliminer les caracteres UTF-8 invalides.
     */
    private JsonNode cleanUtf8InJsonNode(JsonNode node) {
        if (node == null) {
            return node;
        }

        if (node.isTextual()) {
            String cleanedText = cleanUtf8String(node.asText());
            return objectMapper.getNodeFactory().textNode(cleanedText);
        } else if (node.isArray()) {
            ArrayNode cleanedArray = objectMapper.createArrayNode();
            for (JsonNode child : node) {
                cleanedArray.add(cleanUtf8InJsonNode(child));
            }
            return cleanedArray;
        } else if (node.isObject()) {
            ObjectNode cleanedObject = objectMapper.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                String key = cleanUtf8String(entry.getKey());
                JsonNode value = cleanUtf8InJsonNode(entry.getValue());
                cleanedObject.set(key, value);
            });
            return cleanedObject;
        } else {
            // Nombres, booleens, null - pas de nettoyage necessaire
            return node;
        }
    }

    // =========================
    // Compression & downsampling
    // =========================

    private String writeDownsampled(JsonNode root, int maxArrayLen, int stringMaxLen) {
        JsonNode sampled = downsample(root, maxArrayLen, stringMaxLen);

        // Clean invalid UTF-8 characters before serialization
        sampled = cleanUtf8InJsonNode(sampled);

        try {
            return objectMapper.writeValueAsString(sampled); // minifie par defaut
        } catch (Exception e) {
            logger.warn("writeDownsampled failed (returning original root): {}", e.getMessage());
            try {
                // Also clean the original before returning it
                JsonNode cleanedRoot = cleanUtf8InJsonNode(root);
                return objectMapper.writeValueAsString(cleanedRoot);
            } catch (Exception ex) {
                return cleanUtf8String(root.toString());
            }
        }
    }

    /**
     * Downsample non-structurant : limite la longueur des strings & la taille des tableaux.
     */
    private JsonNode downsample(JsonNode node, int maxArrayLen, int stringMaxLen) {
        if (node == null || node.isNull()) return node;

        if (node.isArray()) {
            ArrayNode out = objectMapper.createArrayNode();
            int keep = Math.min(node.size(), Math.max(1, maxArrayLen));
            for (int i = 0; i < keep; i++) {
                out.add(downsample(node.get(i), maxArrayLen, stringMaxLen));
            }
            return out;
        } else if (node.isObject()) {
            ObjectNode out = objectMapper.createObjectNode();
            Iterator<String> it = node.fieldNames();
            while (it.hasNext()) {
                String key = it.next();
                // on ignore explicitement les cles meta existantes
                if (key.startsWith("__") || key.startsWith("...")) continue;
                out.set(key, downsample(node.get(key), maxArrayLen, stringMaxLen));
            }
            return out;
        } else if (node.isTextual()) {
            String v = node.asText();
            // Clean invalid UTF-8 characters
            v = cleanUtf8String(v);
            if (v.length() <= stringMaxLen) return objectMapper.getNodeFactory().textNode(v);
            String truncated = v.substring(0, Math.max(1, stringMaxLen)) + "…";
            return objectMapper.getNodeFactory().textNode(truncated);
        } else {
            return node; // nombres/booleans restent tels quels
        }
    }

    private boolean isValidAbsolute(String p) {
        if (p == null) return false;
        String s = p.trim();
        if (s.isEmpty()) return false;
        if ("null".equalsIgnoreCase(s) || "$.null".equalsIgnoreCase(s)) return false;
        if (!s.startsWith("$.")) return false;
        return !s.contains("(") && !s.contains(")"); // no parentheses
    }

    private String sanitizeItemsPath(String ai, String inferred) {
        if (ai == null) return inferred;
        String s = ai.trim();
        if (s.isEmpty() || "0".equals(s) || "$.null".equalsIgnoreCase(s) || "$".equals(s)) return inferred;
        if (!s.startsWith("$")) return inferred;
        if (!s.startsWith("$.")) s = "$." + (s.startsWith("$.") ? s.substring(2) : s.substring(1));
        // forbid parentheses in items_path
        if (s.contains("(") || s.contains(")")) return inferred;
        return s;
    }

    private ArrayNode sanitizeAlternatives(ArrayNode aiAlts, String itemsPath) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(itemsPath); // MUST be first
        if (aiAlts != null) {
            for (JsonNode a : aiAlts) {
                if (a.isTextual()) {
                    String s = a.asText().trim();
                    if (isValidAbsolute(s)) out.add(s);
                }
            }
        }
        ArrayNode arr = objectMapper.createArrayNode();
        for (String s : out) arr.add(s);
        return arr;
    }

    private String tailFromItems(String absFromItems, String itemsPath) {
        // absFromItems == itemsPath + "." + tail  -> return tail
        if (absFromItems == null) return null;
        String ip = itemsPath.endsWith(".") ? itemsPath : itemsPath + ".";
        if (absFromItems.startsWith(ip)) return absFromItems.substring(ip.length());
        return null;
    }

    private boolean looksLikeFieldSpec(JsonNode n) {
        return n != null && n.isObject() && n.has("candidates") && n.get("candidates").isArray() && n.has("to");
    }

    private String inferTailFromRelative(JsonNode candidates) {
        // find first "@.<tail>" candidate and return the tail
        for (JsonNode c : candidates) {
            if (c.isTextual()) {
                String s = c.asText().trim();
                if (s.startsWith("@.")) return s.substring(2); // drop "@."
            }
        }
        return null;
    }

    private String inferTailFromAbsolute(JsonNode candidates, String itemsPath) {
        for (JsonNode c : candidates) {
            if (c.isTextual()) {
                String s = c.asText().trim();
                String tail = tailFromItems(s, itemsPath);
                if (tail != null && !tail.isEmpty()) return tail;
            }
        }
        return null;
    }

    private ArrayNode enforceItemCandidates(ArrayNode current, String itemsPath) {
        // Build exactly 2 candidates: "@.<tail>" and "<items_path>.<tail>"
        String tail = inferTailFromRelative(current);
        if (tail == null) tail = inferTailFromAbsolute(current, itemsPath);
        if (tail == null) {
            // last resort: take the first text candidate and try to compute a tail
            for (JsonNode c : current) {
                if (c.isTextual()) {
                    String s = c.asText().trim();
                    if (s.startsWith("@.")) {
                        tail = s.substring(2);
                        break;
                    }
                    String t = tailFromItems(s, itemsPath);
                    if (t != null) {
                        tail = t;
                        break;
                    }
                }
            }
        }
        if (tail == null) return current; // keep as-is if we cannot infer
        ArrayNode arr = objectMapper.createArrayNode();
        arr.add("@." + tail);
        arr.add(itemsPath + "." + tail);
        return arr;
    }

    private ArrayNode cleanseCandidates(ArrayNode in) {
        ArrayNode out = objectMapper.createArrayNode();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (JsonNode c : in) {
            if (!c.isTextual()) continue;
            String s = c.asText().trim();
            if (s.isEmpty()) continue;
            if (!(s.startsWith("$.") || s.startsWith("@."))) continue;
            if (s.contains("(") || s.contains(")")) continue; // forbid parentheses
            if (seen.add(s)) out.add(s);
        }
        return out;
    }

    // Global if: no relative "@." AND no absolute starting with itemsPath
    private boolean isLikelyGlobal(ObjectNode spec, String itemsPath) {
        ArrayNode cands = (ArrayNode) spec.get("candidates");
        boolean hasRel = false, hasAbsUnderItem = false;
        for (JsonNode c : cands) {
            if (!c.isTextual()) continue;
            String s = c.asText().trim();
            if (s.startsWith("@.")) hasRel = true;
            if (s.startsWith(itemsPath + ".")) hasAbsUnderItem = true;
        }
        return !hasRel && !hasAbsUnderItem;
    }

    private ObjectNode forceGlobalCandidates(ObjectNode spec) {
        // Keep only first absolute "$.<...>"
        ArrayNode cands = (ArrayNode) spec.get("candidates");
        String firstAbs = null;
        for (JsonNode c : cands) {
            if (c.isTextual()) {
                String s = c.asText().trim();
                if (s.startsWith("$.")) {
                    firstAbs = s;
                    break;
                }
            }
        }
        if (firstAbs == null) return spec; // nothing to do
        ArrayNode one = objectMapper.createArrayNode();
        one.add(firstAbs);
        spec.set("candidates", one);
        spec.put("required", false);
        return spec;
    }

    private ObjectNode sortObjectNodeKeys(ObjectNode obj) {
        ObjectNode out = objectMapper.createObjectNode();
        List<String> keys = new ArrayList<>();
        obj.fieldNames().forEachRemaining(keys::add);
        keys.sort(String::compareTo);
        for (String k : keys) out.set(k, obj.get(k));
        return out;
    }


    /**
     * Troncature "securisee" si on n'a pas pu parser : coupe a ~targetChars et tente de finir proprement.
     */
    private String safeRawTruncate(String json, int targetChars) {
        if (json.length() <= targetChars) return json;
        String candidate = json.substring(0, Math.max(2, targetChars));
        // essaie de trouver une fermeture d'objet/tableau
        int brace = 0, bracket = 0;
        boolean inStr = false, esc = false;
        int lastSafe = -1;
        for (int i = 0; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (esc) {
                esc = false;
                continue;
            }
            if (c == '\\') {
                esc = true;
                continue;
            }
            if (c == '"' && !esc) {
                inStr = !inStr;
                continue;
            }
            if (inStr) continue;
            if (c == '{') brace++;
            else if (c == '}') {
                brace--;
                if (brace == 0 && bracket == 0) lastSafe = i;
            } else if (c == '[') bracket++;
            else if (c == ']') {
                bracket--;
                if (brace == 0 && bracket == 0) lastSafe = i;
            }
        }
        if (lastSafe > 0) candidate = candidate.substring(0, lastSafe + 1);

        // Clean invalid UTF-8 characters in the final result
        return cleanUtf8String(candidate);
    }

    private record InferredItems(String itemsPath, List<String> rootAlternatives, String unwrapKey) {
    }
}
