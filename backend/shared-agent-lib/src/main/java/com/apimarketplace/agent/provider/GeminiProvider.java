package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.*;
import com.apimarketplace.agent.gemini.cache.GeminiCachedContentManager;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

import java.util.*;

/**
 * Google (Gemini) LLM Provider implementation.
 * Supports both synchronous and streaming completions.
 */
@Slf4j
@Component("sharedAgentGeminiProvider")
public class GeminiProvider extends AbstractLLMProvider {

    @Value("${ai.agent.providers.google.api-key:${GOOGLE_API_KEY:}}")
    private String apiKey;

    @Value("${ai.agent.providers.google.api-url:https://generativelanguage.googleapis.com/v1beta/models}")
    private String apiBaseUrl;

    /**
     * Configured list. No hardcoded fallback - if YAML omits the key, this provider reports
     * zero models and is dropped from the catalog. Fail-closed, so a misconfiguration never
     * resurrects stale 2024 IDs.
     */
    @Value("${ai.agent.providers.google.models:}")
    private List<String> configuredModels;

    @Value("${ai.agent.providers.google.display-order:3}")
    private int displayOrder;

    // Guards against a Gemini blank-line flood (its "EMPTY RESPONSE / thinking-only"
    // misbehavior sometimes streams thousands of consecutive empty SSE lines). Normal SSE
    // event separators are a handful of blank lines, never 100 in a row - so a run this long
    // means the stream is spinning with no useful payload and we abort instead of looping.
    private static final int MAX_CONSECUTIVE_EMPTY_LINES = 100;

    // Stage 1a.8 - inline-attachment byte cap. See AttachmentSizeGuard#DEFAULT_MAX_INLINE_BYTES
    // for the canonical constant; keep the three provider @Value defaults in lockstep with it.
    @Value("${ai.attachments.max-inline-bytes:262144}")
    private int maxInlineAttachmentBytes;

    // Images are tokenised by dimensions, not bytes - a larger cap (aligned with the
    // producer's files.view.image-inline-max-bytes) keeps real screenshots/photos visible.
    @Value("${ai.attachments.image-max-inline-bytes:3600000}")
    private int maxInlineImageBytes;

    // Stage 2 - optional manual cachedContent manager. When the feature flag
    // ai.agent.providers.google.cache.enabled=true and the static prefix
    // (systemInstruction + tools) clears the API minimum-token floor, we
    // swap systemInstruction+tools for a cachedContent reference. Null in
    // non-Spring contexts and in the default (disabled) configuration.
    private GeminiCachedContentManager cachedContentManager;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCachedContentManager(GeminiCachedContentManager cachedContentManager) {
        this.cachedContentManager = cachedContentManager;
    }

    @Override
    public int getDisplayOrder() {
        return displayOrder;
    }

    @Override
    public String getProviderName() {
        return "google";
    }

    @Override
    public String getDefaultModel() {
        return configuredModels != null && !configuredModels.isEmpty()
            ? configuredModels.get(0)
            : null;
    }

    @Override
    public List<String> getSupportedModels() {
        return configuredModels != null && !configuredModels.isEmpty()
            ? configuredModels
            : List.of();
    }

    @Override
    protected String getApiKey() {
        return apiKey;
    }

    @Override
    protected String getApiUrl() {
        return apiBaseUrl;
    }

    private String getCleanApiKey() {
        String key = resolveApiKey();
        if (key == null) return "";
        // Trim whitespace and remove any invisible characters
        return key.trim().replaceAll("[\\p{Cntrl}\\p{Zs}]", "");
    }

    private String getApiUrlForModel(String model) {
        return apiBaseUrl + "/" + model + ":generateContent?key=" + getCleanApiKey();
    }

    private String getStreamingUrlForModel(String model) {
        return apiBaseUrl + "/" + model + ":streamGenerateContent?alt=sse&key=" + getCleanApiKey();
    }

    // Track current model for streaming (thread-local to handle concurrent requests)
    private final ThreadLocal<String> currentStreamingModel = new ThreadLocal<>();

    @Override
    protected HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @Override
    public CompletionResponse complete(CompletionRequest request) {
        if (!isConfigured()) {
            throw new LLMProviderException(getProviderName(),
                "Provider is not configured. API key is missing.");
        }

        try {
            String model = request.model() != null ? request.model() : getDefaultModel();
            Map<String, Object> requestBody = buildRequestBody(request);
            HttpHeaders headers = buildHeaders();
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            log.debug("Sending request to Gemini with model {}", model);

            @SuppressWarnings("unchecked")
            ResponseEntity<Map> response = restTemplate.exchange(
                getApiUrlForModel(model),
                HttpMethod.POST,
                entity,
                Map.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = response.getBody();
                return parseResponse(body);
            } else {
                throw new LLMProviderException(getProviderName(),
                    "Unexpected response status: " + response.getStatusCode());
            }

        } catch (HttpClientErrorException e) {
            return handleHttpError(e);
        } catch (LLMProviderException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error calling Gemini API: {}", e.getMessage(), e);
            throw new LLMProviderException(getProviderName(),
                "Error calling API: " + e.getMessage(), e);
        }
    }

    @Override
    public void completeStreaming(CompletionRequest request, com.apimarketplace.agent.streaming.StreamingCallback callback) {
        if (!isConfigured()) {
            callback.onError("Provider is not configured. API key is missing.");
            return;
        }

        String model = request.model() != null ? request.model() : getDefaultModel();
        currentStreamingModel.set(model);

        String requestJson = null; // Keep for error logging
        int responseCode = -1;
        Map<String, List<String>> responseHeaders = null;

        try {
            // Build streaming request
            Map<String, Object> requestBody = buildRequestBody(request);
            // Note: Gemini doesn't need stream:true in body, it's controlled by endpoint

            // Create connection to streaming endpoint
            String streamUrl = getStreamingUrlForModel(model);
            log.debug("Gemini streaming URL: {}", streamUrl.replaceAll("key=.*", "key=***"));
            java.net.URL url = new java.net.URL(streamUrl);
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
            setupStreamingConnection(connection);

            // Send request
            requestJson = objectMapper.writeValueAsString(requestBody);
            log.info("Sending Gemini streaming request - model: {}, body length: {} chars",
                model, requestJson.length());

            // Log conversation history size for diagnosis
            if (request.conversationHistory() != null) {
                log.info("📋 [GEMINI] Request context - history: {} messages, tools: {}",
                    request.conversationHistory().size(),
                    request.tools() != null ? request.tools().size() : 0);
            }

            connection.getOutputStream().write(requestJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Check response code
            responseCode = connection.getResponseCode();
            responseHeaders = connection.getHeaderFields();

            // Log response headers for diagnosis
            log.debug("📋 [GEMINI] Response code: {}", responseCode);
            if (responseHeaders != null) {
                responseHeaders.forEach((key, values) -> {
                    if (key != null && (key.toLowerCase().contains("x-") ||
                                        key.toLowerCase().contains("rate") ||
                                        key.toLowerCase().contains("retry") ||
                                        key.toLowerCase().contains("content"))) {
                        log.debug("📋 [GEMINI] Header {}: {}", key, values);
                    }
                });
            }

            if (responseCode >= 400) {
                String errorMessage = readErrorStream(connection);
                log.error("🚨 [GEMINI] HTTP Error {} - {}", responseCode, errorMessage);
                log.error("🚨 [GEMINI] Request body that caused error ({} chars):\n{}",
                    requestJson.length(),
                    requestJson.length() > 2000 ? requestJson.substring(0, 2000) + "..." : requestJson);
                callback.onError("HTTP " + responseCode + ": " + errorMessage);
                return;
            }

            // Process streaming response with Gemini-specific handling
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(connection.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                processGeminiStreamingResponse(reader, callback, requestJson, responseCode, responseHeaders);
            }

        } catch (Exception e) {
            log.error("🚨 [GEMINI] Exception during streaming: {}", e.getMessage(), e);
            log.error("🚨 [GEMINI] HTTP response code was: {}", responseCode);
            if (requestJson != null) {
                log.error("🚨 [GEMINI] Request body ({} chars):\n{}",
                    requestJson.length(),
                    requestJson.length() > 2000 ? requestJson.substring(0, 2000) + "..." : requestJson);
            }
            callback.onError("Streaming error: " + e.getMessage());
        } finally {
            currentStreamingModel.remove();
        }
    }

    /**
     * Gemini-specific streaming response processor.
     * Handles text content, thinking content, AND function calls (tool calls).
     * Gemini sends content and finishReason in the same line, so we must extract content first.
     */
    // Package-private for unit tests that drive the SSE accumulation path directly.
    void processGeminiStreamingResponse(java.io.BufferedReader reader,
            com.apimarketplace.agent.streaming.StreamingCallback callback,
            String requestJson,
            int httpResponseCode,
            Map<String, List<String>> responseHeaders) throws Exception {
        String line;
        String lastLine = null;
        String lastNonEmptyLine = null;
        boolean explicitEnd = false;
        StringBuilder fullContent = new StringBuilder();
        StringBuilder fullThinking = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        UsageInfo streamUsage = null;
        List<String> allLines = new ArrayList<>(); // Keep all lines for diagnosis
        int lineCount = 0;
        int emptyLineCount = 0;
        int consecutiveEmpty = 0;

        while ((line = reader.readLine()) != null) {
            // F1.1 mirror - honor STOP signal mid-stream like AbstractLLMProvider does.
            // Without this, Gemini conversation streaming ignores the user's STOP
            // entirely and runs to natural completion (same bug pattern as the
            // pre-fix DeepSeek path).
            if (callback.shouldStop()) {
                log.info("⏹️ [GEMINI] Stop signal detected mid-stream, breaking (content={} chars, thinking={} chars)",
                    fullContent.length(), fullThinking.length());
                break;
            }

            lineCount++;
            lastLine = line; // Track last line for debugging
            allLines.add(line); // Store for diagnosis

            if (line.trim().isEmpty()) {
                emptyLineCount++;
                consecutiveEmpty++;
                // Backpressure against Gemini's blank-line flood: a runaway of consecutive
                // empty lines means the stream is spinning with no payload. Abort instead of
                // iterating (and re-polling shouldStop) thousands of times.
                if (consecutiveEmpty >= MAX_CONSECUTIVE_EMPTY_LINES) {
                    log.warn("⚠️ [GEMINI] Empty-line flood - aborting stream after {} consecutive blank lines (content={} chars, thinking={} chars, tools={})",
                        consecutiveEmpty, fullContent.length(), fullThinking.length(), toolCalls.size());
                    break;
                }
            } else {
                lastNonEmptyLine = line;
                consecutiveEmpty = 0;
            }

            // Debug: Log raw line (truncated for readability)
            String debugLine = line.length() > 200 ? line.substring(0, 200) + "..." : line;
            log.debug("📥 [GEMINI RAW {}] {}", lineCount, debugLine);

            // Process thinking content (for Gemini 2.5+/3 thinking models)
            String thinking = parseGeminiThinking(line);
            if (thinking != null && !thinking.isEmpty()) {
                fullThinking.append(thinking);
                log.info("🧠 [GEMINI] Thinking chunk: {} chars", thinking.length());
                callback.onThinking(thinking);
            }

            // Process text content
            String chunk = processStreamingLine(line);
            if (chunk != null && !chunk.isEmpty()) {
                fullContent.append(chunk);
                log.info("📨 [GEMINI] Content chunk: {} chars", chunk.length());
                callback.onChunk(chunk);
            }

            // Process function calls (tool calls)
            List<ToolCall> lineToolCalls = parseGeminiToolCalls(line);
            if (lineToolCalls != null && !lineToolCalls.isEmpty()) {
                for (ToolCall tc : lineToolCalls) {
                    log.info("🔧 [GEMINI] Tool call: {} with args: {}", tc.toolName(), tc.arguments());
                    toolCalls.add(tc);
                    callback.onToolCall(tc);
                }
            }

            // Extract usage from streaming line. Gemini's SSE reports CUMULATIVE
            // usageMetadata on each chunk that carries it, so replace-with-latest
            // yields the final total. Summing would triple-count tokens when the
            // stream emits usage on multiple chunks.
            UsageInfo lineUsage = extractStreamingUsage(line);
            if (lineUsage != null) {
                streamUsage = lineUsage;
            }

            // Check for end of stream (after content/tools are extracted)
            if (isEndOfStream(line)) {
                String actualFinishReason = extractFinishReason(line);
                log.info("✅ [GEMINI] End of stream - finishReason: {}, thinking: {} chars, content: {} chars, tools: {}",
                    actualFinishReason, fullThinking.length(), fullContent.length(), toolCalls.size());
                if ("MALFORMED_FUNCTION_CALL".equals(actualFinishReason)) {
                    log.warn("⚠️ [GEMINI] MALFORMED_FUNCTION_CALL - Model failed to format tool calls properly. " +
                        "Proceeding with {} tool call(s) extracted before the malformed line. " +
                        "This is a model-side issue (not necessarily Gemini-specific).", toolCalls.size());
                }
                explicitEnd = true;
                break;
            }
        }

        // Warn if stream ended without explicit finishReason (connection closed unexpectedly)
        if (!explicitEnd) {
            log.error("🚨 [GEMINI] ═══════════════════════════════════════════════════════════");
            log.error("🚨 [GEMINI] ABNORMAL STREAM END - No explicit finishReason!");
            log.error("🚨 [GEMINI] ═══════════════════════════════════════════════════════════");
            log.error("🚨 [GEMINI] HTTP Response Code: {}", httpResponseCode);
            log.error("🚨 [GEMINI] Total lines received: {}, empty lines: {}", lineCount, emptyLineCount);
            log.error("🚨 [GEMINI] Last line (raw): '{}'", lastLine);
            log.error("🚨 [GEMINI] Last non-empty line: '{}'",
                lastNonEmptyLine != null ? (lastNonEmptyLine.length() > 500 ? lastNonEmptyLine.substring(0, 500) + "..." : lastNonEmptyLine) : "null");
            log.error("🚨 [GEMINI] Thinking content collected: {} chars", fullThinking.length());
            if (fullThinking.length() > 0) {
                String thinkingPreview = fullThinking.length() > 1000
                    ? fullThinking.substring(0, 1000) + "..."
                    : fullThinking.toString();
                log.error("🚨 [GEMINI] Thinking preview: {}", thinkingPreview);
            }
            log.error("🚨 [GEMINI] Content collected: {} chars", fullContent.length());
            log.error("🚨 [GEMINI] Tool calls collected: {}", toolCalls.size());

            // Log response headers
            if (responseHeaders != null) {
                log.error("🚨 [GEMINI] Response headers:");
                responseHeaders.forEach((key, values) -> {
                    if (key != null) {
                        log.error("🚨 [GEMINI]   {}: {}", key, values);
                    }
                });
            }

            // Log last 5 lines for context
            int startIdx = Math.max(0, allLines.size() - 5);
            log.error("🚨 [GEMINI] Last {} lines of stream:", allLines.size() - startIdx);
            for (int i = startIdx; i < allLines.size(); i++) {
                String l = allLines.get(i);
                String truncated = l.length() > 300 ? l.substring(0, 300) + "..." : l;
                log.error("🚨 [GEMINI]   Line {}: '{}'", i + 1, truncated);
            }

            // Log request body for diagnosis
            if (requestJson != null) {
                log.error("🚨 [GEMINI] Request body that caused issue ({} chars):", requestJson.length());
                if (requestJson.length() > 3000) {
                    log.error("🚨 [GEMINI] Request (first 3000 chars): {}", requestJson.substring(0, 3000));
                } else {
                    log.error("🚨 [GEMINI] Request: {}", requestJson);
                }
            }
            log.error("🚨 [GEMINI] ═══════════════════════════════════════════════════════════");
        }

        log.info("📊 [GEMINI] Stream finished - {} lines, {} thinking chars, {} content chars, {} tool calls",
            lineCount, fullThinking.length(), fullContent.length(), toolCalls.size());

        // Warn if no content and no tool calls (unusual)
        if (fullContent.length() == 0 && toolCalls.isEmpty()) {
            log.error("🚨 [GEMINI] ───────────────────────────────────────────────────────────");
            log.error("🚨 [GEMINI] EMPTY RESPONSE - No content AND no tool calls!");
            log.error("🚨 [GEMINI] This is abnormal - Gemini should produce either content or tool calls");
            log.error("🚨 [GEMINI] HTTP Response Code: {}", httpResponseCode);
            log.error("🚨 [GEMINI] Thinking only response: {} chars", fullThinking.length());
            if (fullThinking.length() > 0) {
                log.error("🚨 [GEMINI] Full thinking content:\n{}", fullThinking.toString());
            }
            // Log request body for diagnosis
            if (requestJson != null) {
                log.error("🚨 [GEMINI] Request body ({} chars):", requestJson.length());
                if (requestJson.length() > 3000) {
                    log.error("🚨 [GEMINI] Request (first 3000 chars): {}", requestJson.substring(0, 3000));
                } else {
                    log.error("🚨 [GEMINI] Request: {}", requestJson);
                }
            }
            log.error("🚨 [GEMINI] ───────────────────────────────────────────────────────────");
        }

        if (streamUsage != null) {
            log.info("📊 [GEMINI USAGE] captured: promptTokens={}, completionTokens={}, total={}",
                streamUsage.promptTokens(), streamUsage.completionTokens(), streamUsage.getTotal());
        }

        // Build final response
        CompletionResponse.CompletionResponseBuilder responseBuilder = CompletionResponse.builder()
            .content(fullContent.toString())
            .finishReason(toolCalls.isEmpty() ? "stop" : "tool_calls")
            .usage(streamUsage);

        if (!toolCalls.isEmpty()) {
            responseBuilder.toolCalls(toolCalls);
        }

        callback.onComplete(responseBuilder.build());
    }

    /**
     * Parse Gemini thinking/reasoning content from a streaming line.
     * Thinking content appears in parts as "thought" or "thinking" fields.
     * Package-private for same-package unit tests of the null-text guard.
     */
    String parseGeminiThinking(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }

        String data = line.trim();
        if (data.startsWith("data: ")) {
            data = data.substring(6).trim();
        }
        if (data.isEmpty() || data.startsWith("[") || data.startsWith("]")) {
            return null;
        }

        try {
            JsonNode node = objectMapper.readTree(data);
            JsonNode candidates = node.get("candidates");
            if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
                return null;
            }

            JsonNode content = candidates.get(0).get("content");
            if (content == null) {
                return null;
            }

            JsonNode parts = content.get("parts");
            if (parts == null || !parts.isArray()) {
                return null;
            }

            StringBuilder thinking = new StringBuilder();
            for (JsonNode part : parts) {
                // Check for "thought" boolean marker AND "text" field
                // Gemini 2.5+/3 thinking models mark thinking parts with thought=true
                if (part.has("thought") && part.get("thought").asBoolean()) {
                    // If thought=true, the text in this part is thinking content.
                    // Guard JSON null: asText() on a NullNode returns the literal "null".
                    JsonNode textNode = part.get("text");
                    if (textNode != null && !textNode.isNull()) {
                        String thinkingText = textNode.asText();
                        log.debug("🧠 [GEMINI] Found thinking text with thought=true: {} chars", thinkingText.length());
                        thinking.append(thinkingText);
                    }
                }
                // Also check for "thinking" field (alternative format)
                else if (part.has("thinking")) {
                    JsonNode thinkingNode = part.get("thinking");
                    if (thinkingNode != null && !thinkingNode.isNull()) {
                        String thinkingText = thinkingNode.asText();
                        log.debug("🧠 [GEMINI] Found thinking via 'thinking' field: {} chars", thinkingText.length());
                        thinking.append(thinkingText);
                    }
                }
            }

            return thinking.length() > 0 ? thinking.toString() : null;
        } catch (Exception e) {
            log.debug("Error parsing Gemini thinking: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Parse Gemini function calls from a streaming line.
     */
    @SuppressWarnings("unchecked")
    private List<ToolCall> parseGeminiToolCalls(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }

        String data = line.trim();
        if (data.startsWith("data: ")) {
            data = data.substring(6).trim();
        }
        if (data.isEmpty() || data.startsWith("[") || data.startsWith("]")) {
            return null;
        }

        try {
            JsonNode node = objectMapper.readTree(data);
            JsonNode candidates = node.get("candidates");
            if (candidates == null || !candidates.isArray() || candidates.isEmpty()) {
                return null;
            }

            JsonNode content = candidates.get(0).get("content");
            if (content == null) {
                return null;
            }

            JsonNode parts = content.get("parts");
            if (parts == null || !parts.isArray()) {
                return null;
            }

            List<ToolCall> toolCalls = new ArrayList<>();
            for (int i = 0; i < parts.size(); i++) {
                JsonNode part = parts.get(i);
                JsonNode functionCall = part.get("functionCall");
                if (functionCall != null) {
                    String name = functionCall.has("name") ? functionCall.get("name").asText() : null;
                    Map<String, Object> args = new HashMap<>();

                    if (functionCall.has("args")) {
                        args = objectMapper.convertValue(functionCall.get("args"), Map.class);
                    }

                    if (name != null) {
                        toolCalls.add(ToolCall.builder()
                            .id("call_gemini_" + System.currentTimeMillis() + "_" + i)
                            .toolName(name)
                            .arguments(args)
                            .index(i)
                            .build());
                    }
                }
            }

            return toolCalls.isEmpty() ? null : toolCalls;
        } catch (Exception e) {
            log.debug("Error parsing Gemini tool calls: {}", e.getMessage());
            return null;
        }
    }

    @Override
    protected Map<String, Object> buildRequestBody(CompletionRequest request) {
        Map<String, Object> body = new HashMap<>();

        body.put("contents", buildGeminiContents(request));

        // Stage 1a.1 - Gemini has no per-block cache control; fold the layered
        // blocks (or the legacy string) into a single concatenated
        // systemInstruction text. effectiveSystemPrompt() returns the legacy
        // string when systemBlocks is unset, so existing callers are unaffected.
        String systemInstruction = request.effectiveSystemPrompt();
        if (systemInstruction != null && !systemInstruction.isEmpty()) {
            body.put("systemInstruction", Map.of(
                "parts", List.of(Map.of("text", systemInstruction))
            ));
        }

        Map<String, Object> generationConfig = new HashMap<>();
        // Default temperature to 0.7 to prevent infinite tool call loops
        generationConfig.put("temperature", request.temperature() != null ? request.temperature() : 0.7);
        if (request.maxTokens() != null) {
            generationConfig.put("maxOutputTokens", request.maxTokens());
        }
        if (request.topP() != null) {
            generationConfig.put("topP", request.topP());
        }
        body.put("generationConfig", generationConfig);

        if (request.tools() != null && !request.tools().isEmpty()) {
            body.put("tools", List.of(Map.of(
                "functionDeclarations", buildGeminiTools(request.tools())
            )));
        }

        // Thinking config for Gemini 3+ and 2.5 models.
        // Stage 1a.3: includeThoughts defaults to FALSE - thoughts are billed as a
        // separate output counter (thoughtsTokenCount) and are almost never consumed.
        // Callers that actually need the reasoning payload set
        // CompletionRequest.includeThoughts=true to opt in.
        // Stage 1b.1: thinkingBudget caps (or disables) reasoning-token spend. null =
        // Gemini dynamic default; 0 = disabled; positive = hard cap. Pass-through to
        // the API only when the caller specified it - omitting the key preserves
        // Gemini's default behavior for unspecified budgets.
        String modelName = request.model() != null ? request.model() : getDefaultModel();
        if (modelName.contains("gemini-3") || modelName.contains("gemini-2.5")) {
            Map<String, Object> thinkingConfig = new HashMap<>();
            thinkingConfig.put("includeThoughts", request.wantsThoughts());
            // Stage 1b.1: explicit thinkingBudget wins; thinkingLevel is the coarse
            // fallback (LOW=128, MEDIUM=1024, HIGH=8192). Both null → omit the key
            // so Gemini keeps its dynamic default.
            Integer effectiveBudget = request.thinkingBudget();
            if (effectiveBudget == null && request.thinkingLevel() != null) {
                effectiveBudget = request.thinkingLevel().budgetTokens();
            }
            if (effectiveBudget != null) {
                thinkingConfig.put("thinkingBudget", effectiveBudget);
            }
            body.put("generationConfig", mergeMaps(
                (Map<String, Object>) body.getOrDefault("generationConfig", new HashMap<>()),
                Map.of("thinkingConfig", thinkingConfig)
            ));
            log.debug("Gemini thinkingConfig: includeThoughts={} thinkingBudget={} thinkingLevel={} for model {}",
                request.wantsThoughts(), request.thinkingBudget(), request.thinkingLevel(), modelName);
        }

        // Stage 2 - attach manual cachedContent when eligible. When the manager
        // returns a cache name, Google expects systemInstruction + tools to be
        // omitted from the request body (they're already in the cache); sending
        // both causes a 400. When the manager returns empty (disabled, gate
        // rejected, or create failed) we leave the body untouched.
        attachCachedContentIfEligible(body, request, modelName, systemInstruction);

        return body;
    }

    private void attachCachedContentIfEligible(Map<String, Object> body,
                                               CompletionRequest request,
                                               String modelName,
                                               String systemInstruction) {
        if (cachedContentManager == null) return;
        try {
            int prefixTokenCount = estimatePrefixTokens(systemInstruction, request.tools());
            java.util.Optional<String> cacheName = cachedContentManager.getOrCreate(
                    getCleanApiKey(),
                    modelName,
                    systemInstruction == null ? "" : systemInstruction,
                    request.tools(),
                    prefixTokenCount);
            cacheName.ifPresent(name -> {
                body.put("cachedContent", name);
                body.remove("systemInstruction");
                body.remove("tools");
                log.debug("Gemini request attached cachedContent={} (systemInstruction+tools omitted)", name);
            });
        } catch (Exception e) {
            // Cache attach is a cost optimization; never break the main request.
            log.warn("Gemini cachedContent attach failed, continuing without cache: {}", e.getMessage());
        }
    }

    private int estimatePrefixTokens(String systemInstruction, List<ToolDefinition> tools) {
        int chars = systemInstruction == null ? 0 : systemInstruction.length();
        if (tools != null) {
            for (ToolDefinition t : tools) {
                if (t == null) continue;
                if (t.name() != null) chars += t.name().length();
                if (t.description() != null) chars += t.description().length();
                if (t.parameters() != null) {
                    for (ToolParameter p : t.parameters()) {
                        if (p == null) continue;
                        if (p.name() != null) chars += p.name().length();
                        if (p.description() != null) chars += p.description().length();
                        // 12 chars of JSON overhead per parameter (quotes, colons, braces).
                        chars += 12;
                    }
                }
            }
        }
        // Heuristic chars/4; matches the fallback in AbstractLLMProvider when
        // no TokenEstimator bean is wired. The gate only compares against the
        // Gemini API floor (1024 Flash / 4096 Pro) so slight over/under-shoot
        // is acceptable - Google enforces the real floor server-side.
        return chars / 4;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeMaps(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> result = new HashMap<>(base);
        result.putAll(override);
        return result;
    }

    private List<Map<String, Object>> buildGeminiContents(CompletionRequest request) {
        List<Map<String, Object>> contents = new ArrayList<>();

        if (request.conversationHistory() != null) {
            for (Message msg : request.conversationHistory()) {
                if (msg.role() != Message.Role.SYSTEM) {
                    contents.add(convertGeminiMessage(msg));
                }
            }
        }

        if (request.userPrompt() != null) {
            contents.add(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", request.userPrompt()))
            ));
        }

        return contents;
    }

    private Map<String, Object> convertGeminiMessage(Message message) {
        String role = switch (message.role()) {
            case USER -> "user";
            case ASSISTANT -> "model";
            case TOOL -> "function";
            default -> "user";
        };

        // Tool result (function response)
        if (message.role() == Message.Role.TOOL) {
            return Map.of(
                "role", "function",
                "parts", List.of(Map.of(
                    "functionResponse", Map.of(
                        "name", message.toolName(),
                        "response", Map.of("output", message.content())
                    )
                ))
            );
        }

        // User message WITH attachments - multimodal content
        if (message.role() == Message.Role.USER && message.hasAttachments()) {
            List<Map<String, Object>> parts = buildGeminiMultimodalParts(message);
            return Map.of("role", "user", "parts", parts);
        }

        // Assistant message WITH tool calls - must include functionCall parts
        if (message.role() == Message.Role.ASSISTANT && message.toolCalls() != null && !message.toolCalls().isEmpty()) {
            List<Map<String, Object>> parts = new ArrayList<>();

            // Add text content if present
            if (message.content() != null && !message.content().isEmpty()) {
                parts.add(Map.of("text", message.content()));
            }

            // Add function calls
            for (ToolCall tc : message.toolCalls()) {
                parts.add(Map.of(
                    "functionCall", Map.of(
                        "name", tc.toolName(),
                        "args", tc.arguments() != null ? tc.arguments() : Map.of()
                    )
                ));
            }

            log.debug("Converting ASSISTANT message with {} tool calls to Gemini format", message.toolCalls().size());
            return Map.of("role", "model", "parts", parts);
        }

        // Regular text message
        return Map.of(
            "role", role,
            "parts", List.of(Map.of("text", message.content() != null ? message.content() : ""))
        );
    }

    /**
     * Build multimodal parts array for Gemini API.
     * Gemini supports: text, inlineData (images, PDFs, audio, video)
     */
    private List<Map<String, Object>> buildGeminiMultimodalParts(Message message) {
        List<Map<String, Object>> parts = new ArrayList<>();

        // Add attachments first - enforce 1a.8 size cap so oversized inlineData
        // (multi-MB PDFs/images) is rewritten to text before it blows out input tokens.
        for (MessageAttachment attachment : message.attachments()) {
            MessageAttachment guarded = com.apimarketplace.agent.attachment.AttachmentSizeGuard
                    .enforceSizeCap(attachment, maxInlineAttachmentBytes, maxInlineImageBytes, "google");
            Map<String, Object> part = buildGeminiAttachment(guarded);
            if (part != null) {
                parts.add(part);
            }
        }

        // Add text content last
        if (message.content() != null && !message.content().isBlank()) {
            parts.add(Map.of("text", message.content()));
        }

        return parts;
    }

    /**
     * Build a Gemini attachment part based on type.
     * Gemini supports images and PDFs natively via inlineData.
     */
    private Map<String, Object> buildGeminiAttachment(MessageAttachment attachment) {
        return switch (attachment.type()) {
            case IMAGE, PDF -> Map.of(
                "inlineData", Map.of(
                    "mimeType", attachment.mimeType(),
                    "data", Base64.getEncoder().encodeToString(attachment.data())
                )
            );
            case TEXT -> Map.of(
                "text", "[File: " + attachment.fileName() + "]\n" + attachment.getTextContent()
            );
            case OTHER -> Map.of(
                "text", "[Attachment: " + attachment.fileName() + " - unsupported format]"
            );
        };
    }

    private List<Map<String, Object>> buildGeminiTools(List<ToolDefinition> tools) {
        List<Map<String, Object>> result = new ArrayList<>();

        // Stage 1a.1: sort by tool name and build each tool as a LinkedHashMap so pod
        // restarts (ConcurrentHashMap iteration in CoreToolsProvider, HashMap rehash
        // across JVMs) do not shuffle the serialized functionDeclarations array. Byte-
        // stable ordering is a prerequisite for the Gemini cachedContent prefix (Stage 2)
        // - one reordered tool or one swapped JSON key invalidates the cache.
        List<ToolDefinition> sorted = tools.stream()
            .sorted(Comparator.comparing(ToolDefinition::name,
                    Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

        for (ToolDefinition tool : sorted) {
            Map<String, Object> functionDecl = new LinkedHashMap<>();
            functionDecl.put("name", tool.name());
            functionDecl.put("description", tool.description());
            functionDecl.put("parameters", buildParametersSchema(tool));
            result.add(functionDecl);
        }

        return result;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected CompletionResponse parseResponse(Map<String, Object> response) {
        List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
        Map<String, Object> usageMetadata = (Map<String, Object>) response.get("usageMetadata");

        if (candidates == null || candidates.isEmpty()) {
            return CompletionResponse.error("No response from Gemini");
        }

        Map<String, Object> candidate = candidates.get(0);
        Map<String, Object> content = (Map<String, Object>) candidate.get("content");
        String finishReason = (String) candidate.get("finishReason");

        StringBuilder textContent = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();

        if (content != null) {
            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
            if (parts != null) {
                for (int i = 0; i < parts.size(); i++) {
                    Map<String, Object> part = parts.get(i);

                    // Skip thinking parts (gemini-2.5/3 models) - they appear before the
                    // actual answer and must not be mixed into the text content.
                    if (Boolean.TRUE.equals(part.get("thought"))) {
                        continue;
                    }

                    if (part.containsKey("text")) {
                        textContent.append(part.get("text"));
                    } else if (part.containsKey("functionCall")) {
                        Map<String, Object> functionCall = (Map<String, Object>) part.get("functionCall");
                        String name = (String) functionCall.get("name");
                        Map<String, Object> args = (Map<String, Object>) functionCall.get("args");

                        toolCalls.add(ToolCall.builder()
                            .id("call_" + i)
                            .toolName(name)
                            .arguments(args != null ? args : Map.of())
                            .index(i)
                            .build());
                    }
                }
            }
        }

        return CompletionResponse.builder()
            .content(textContent.toString())
            .toolCalls(toolCalls.isEmpty() ? null : toolCalls)
            .finishReason(finishReason != null ? finishReason.toLowerCase() : "stop")
            .usage(parseGeminiUsage(usageMetadata))
            .model(getDefaultModel())
            .build();
    }

    private UsageInfo parseGeminiUsage(Map<String, Object> usage) {
        if (usage == null) {
            return null;
        }

        Integer prompt = getIntValue(usage, "promptTokenCount");
        Integer completion = getIntValue(usage, "candidatesTokenCount");
        Integer total = getIntValue(usage, "totalTokenCount");
        Integer thoughts = getIntValue(usage, "thoughtsTokenCount");
        Integer cachedContent = getIntValue(usage, "cachedContentTokenCount");

        return UsageInfo.builder()
            .promptTokens(prompt)
            .completionTokens(completion)
            .totalTokens(total)
            .thoughtsTokenCount(thoughts)
            // Mirror thoughts into the generic reasoning counter: it is the field the
            // observability DTOs carry to billing, where the GOOGLE family bills it as
            // ADDITIVE output (thoughts are NOT included in candidatesTokenCount).
            .reasoningTokens(thoughts)
            .cachedContentTokenCount(cachedContent)
            // Same for cached content: the generic cachedTokens counter is what flows
            // to billing (subset of promptTokenCount, billed at the cached discount).
            .cachedTokens(cachedContent)
            .build();
    }

    @Override
    protected UsageInfo extractStreamingUsage(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }

        String data = line.trim();
        if (data.startsWith("data: ")) {
            data = data.substring(6).trim();
        }
        if (data.isEmpty() || data.startsWith("[") || data.startsWith("]")) {
            return null;
        }

        try {
            JsonNode node = objectMapper.readTree(data);
            JsonNode usageMetadata = node.get("usageMetadata");
            if (usageMetadata != null) {
                Integer promptTokens = usageMetadata.has("promptTokenCount")
                    ? usageMetadata.get("promptTokenCount").asInt() : null;
                Integer completionTokens = usageMetadata.has("candidatesTokenCount")
                    ? usageMetadata.get("candidatesTokenCount").asInt() : null;
                Integer totalTokens = usageMetadata.has("totalTokenCount")
                    ? usageMetadata.get("totalTokenCount").asInt() : null;
                Integer thoughtsTokens = usageMetadata.has("thoughtsTokenCount")
                    ? usageMetadata.get("thoughtsTokenCount").asInt() : null;
                Integer cachedContentTokens = usageMetadata.has("cachedContentTokenCount")
                    ? usageMetadata.get("cachedContentTokenCount").asInt() : null;
                if (promptTokens != null || completionTokens != null) {
                    log.debug("📊 [GEMINI USAGE] streaming: prompt={}, completion={}, total={}, thoughts={}, cachedContent={}",
                        promptTokens, completionTokens, totalTokens, thoughtsTokens, cachedContentTokens);
                    return UsageInfo.builder()
                        .promptTokens(promptTokens)
                        .completionTokens(completionTokens)
                        .totalTokens(totalTokens)
                        .thoughtsTokenCount(thoughtsTokens)
                        // See parseGeminiUsage: mirror Gemini-specific counters into the
                        // generic reasoning/cached fields that flow to billing.
                        .reasoningTokens(thoughtsTokens)
                        .cachedContentTokenCount(cachedContentTokens)
                        .cachedTokens(cachedContentTokens)
                        .build();
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting Gemini streaming usage: {}", e.getMessage());
        }

        return null;
    }

    @Override
    protected boolean isEndOfStream(String line) {
        // Gemini SSE uses finishReason: "STOP" to indicate end, not "data: [DONE]"
        if (line == null || line.isEmpty()) {
            return false;
        }
        // Check for finishReason in the line
        return line.contains("\"finishReason\"") &&
               (line.contains("\"STOP\"") || line.contains("\"MAX_TOKENS\"") || line.contains("\"SAFETY\"") ||
                line.contains("\"RECITATION\"") || line.contains("\"OTHER\"") ||
                line.contains("\"MALFORMED_FUNCTION_CALL\""));
    }

    /**
     * Extract the actual finishReason from a Gemini response line.
     */
    private String extractFinishReason(String line) {
        if (line == null) return "unknown";

        // Look for common finish reasons
        if (line.contains("\"STOP\"")) return "STOP";
        if (line.contains("\"MAX_TOKENS\"")) return "MAX_TOKENS";
        if (line.contains("\"SAFETY\"")) return "SAFETY";
        if (line.contains("\"RECITATION\"")) return "RECITATION";
        if (line.contains("\"MALFORMED_FUNCTION_CALL\"")) return "MALFORMED_FUNCTION_CALL";
        if (line.contains("\"OTHER\"")) return "OTHER";

        // Try to extract from JSON
        try {
            String data = line.trim();
            if (data.startsWith("data: ")) {
                data = data.substring(6).trim();
            }
            JsonNode node = objectMapper.readTree(data);
            JsonNode candidates = node.get("candidates");
            if (candidates != null && candidates.isArray() && !candidates.isEmpty()) {
                JsonNode finishReason = candidates.get(0).get("finishReason");
                if (finishReason != null) {
                    return finishReason.asText();
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse finishReason from line: {}", e.getMessage());
        }

        return "unknown";
    }

    @Override
    protected String processStreamingLine(String line) {
        // Gemini SSE format: data: {"candidates":[{"content":{"parts":[{"text":"..."}]}}]}
        if (line == null || line.isEmpty() || line.startsWith("[") || line.startsWith("]")) {
            return null;
        }

        // Strip "data: " prefix if present (SSE format)
        String data = line.trim();
        if (data.startsWith("data: ")) {
            data = data.substring(6).trim();
        }

        // Remove leading comma if present (from JSON array streaming)
        if (data.startsWith(",")) {
            data = data.substring(1).trim();
        }

        try {
            JsonNode node = objectMapper.readTree(data);
            JsonNode candidates = node.get("candidates");
            if (candidates != null && candidates.isArray() && candidates.size() > 0) {
                JsonNode content = candidates.get(0).get("content");
                if (content != null) {
                    JsonNode parts = content.get("parts");
                    if (parts != null && parts.isArray()) {
                        // Collect text ONLY from non-thinking parts
                        // Thinking parts (thought=true or "thinking" field) are sent via onThinking callback separately
                        // and should NOT be included in the content stream to avoid duplication
                        StringBuilder contentText = new StringBuilder();
                        for (JsonNode part : parts) {
                            // Skip thinking parts - they are handled by parseGeminiThinking()
                            // Case 1: thought=true boolean marker
                            if (part.has("thought") && part.get("thought").asBoolean()) {
                                continue;
                            }
                            // Case 2: "thinking" field (alternative format)
                            if (part.has("thinking")) {
                                continue;
                            }
                            JsonNode textNode = part.get("text");
                            // Guard JSON null: asText() on a NullNode returns the literal "null".
                            if (textNode != null && !textNode.isNull()) {
                                contentText.append(textNode.asText());
                            }
                        }
                        return contentText.length() > 0 ? contentText.toString() : null;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error parsing Gemini streaming line: {}", e.getMessage());
        }

        return null;
    }
}
