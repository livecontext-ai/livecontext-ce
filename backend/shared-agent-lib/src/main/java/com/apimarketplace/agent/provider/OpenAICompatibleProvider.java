package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.*;
import com.apimarketplace.agent.domain.UsageInfo;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.*;

/**
 * Generic OpenAI-compatible LLM provider.
 * Works with any provider that exposes the OpenAI chat completions API format
 * (xAI/Grok, Perplexity, Cohere, Z.AI/GLM, OpenRouter, etc.).
 *
 * <p>Instances are created by {@link OpenAICompatibleProviderFactory} from YAML config,
 * not via Spring {@code @Component} - the factory registers them with the
 * {@link LLMProviderFactory}.
 */
@Slf4j
public class OpenAICompatibleProvider extends AbstractLLMProvider {

    private final String providerName;
    private final String apiUrl;
    private final String apiKey;
    private final List<String> models;
    private final int displayOrder;

    public OpenAICompatibleProvider(String providerName, String apiUrl, String apiKey,
                                     List<String> models, int displayOrder) {
        this.providerName = providerName;
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.models = models != null ? List.copyOf(models) : List.of();
        this.displayOrder = displayOrder;
    }

    @Override
    public int getDisplayOrder() {
        return displayOrder;
    }

    @Override
    public String getProviderName() {
        return providerName;
    }

    @Override
    public String getDefaultModel() {
        return models.isEmpty() ? null : models.get(0);
    }

    @Override
    public List<String> getSupportedModels() {
        return models;
    }

    @Override
    protected String getApiKey() {
        return apiKey;
    }

    @Override
    protected String getApiUrl() {
        return apiUrl;
    }

    // isConfigured() is intentionally NOT overridden - the parent
    // AbstractLLMProvider.isConfigured() calls resolveApiKey() which
    // checks DB-stored keys via credentialResolver, then falls back
    // to the env-injected apiKey field. Overriding here would bypass
    // DB credential resolution entirely.

    @Override
    protected HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(resolveApiKey());
        return headers;
    }

    @Override
    protected Map<String, Object> buildRequestBody(CompletionRequest request) {
        Map<String, Object> body = new HashMap<>();

        body.put("model", request.model() != null ? request.model() : getDefaultModel());
        body.put("messages", buildMessages(request));

        body.put("temperature", request.temperature() != null ? request.temperature() : 0.7);
        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        }
        if (request.topP() != null) {
            body.put("top_p", request.topP());
        }
        if (request.frequencyPenalty() != null) {
            body.put("frequency_penalty", request.frequencyPenalty());
        }
        if (request.presencePenalty() != null) {
            body.put("presence_penalty", request.presencePenalty());
        }

        if (request.tools() != null && !request.tools().isEmpty()) {
            body.put("tools", buildOpenAITools(request.tools()));
            body.put("tool_choice", "auto");
        }

        return body;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected CompletionResponse parseResponse(Map<String, Object> response) {
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");

        if (choices == null || choices.isEmpty()) {
            return CompletionResponse.error("No response from " + providerName);
        }

        Map<String, Object> choice = choices.get(0);
        Map<String, Object> message = (Map<String, Object>) choice.get("message");
        String finishReason = (String) choice.get("finish_reason");

        String content = message != null ? (String) message.get("content") : null;
        List<Map<String, Object>> toolCalls = message != null
            ? (List<Map<String, Object>>) message.get("tool_calls") : null;

        Map<String, Object> usage = (Map<String, Object>) response.get("usage");
        String model = (String) response.get("model");

        return CompletionResponse.builder()
            .content(content != null ? content : "")
            .toolCalls(parseOpenAIToolCalls(toolCalls))
            .finishReason(finishReason)
            .usage(parseUsageInfo(usage))
            .model(model)
            .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    protected UsageInfo parseUsageInfo(Map<String, Object> usage) {
        if (usage == null) {
            return null;
        }

        UsageInfo.UsageInfoBuilder builder = UsageInfo.builder()
            .promptTokens(getIntValue(usage, "prompt_tokens"))
            .completionTokens(getIntValue(usage, "completion_tokens"))
            .totalTokens(getIntValue(usage, "total_tokens"));

        Object promptDetails = usage.get("prompt_tokens_details");
        if (promptDetails instanceof Map) {
            Map<String, Object> details = (Map<String, Object>) promptDetails;
            builder.cachedTokens(getIntValue(details, "cached_tokens"));
        }

        Object completionDetails = usage.get("completion_tokens_details");
        if (completionDetails instanceof Map) {
            Map<String, Object> details = (Map<String, Object>) completionDetails;
            builder.reasoningTokens(getIntValue(details, "reasoning_tokens"));
        }

        return builder.build();
    }

    @Override
    protected void addStreamingRequestOptions(Map<String, Object> body) {
        body.put("stream_options", Map.of("include_usage", true));
    }

    @Override
    protected UsageInfo extractStreamingUsage(String line) {
        String data;
        if (line.startsWith("data: ")) {
            data = line.substring(6).trim();
        } else if (line.trim().startsWith("{")) {
            data = line.trim();
        } else {
            return null;
        }
        if (data.equals("[DONE]") || data.isEmpty()) {
            return null;
        }

        try {
            JsonNode node = objectMapper.readTree(data);
            JsonNode usageNode = node.get("usage");
            if (usageNode != null && !usageNode.isNull()) {
                Integer promptTokens = usageNode.has("prompt_tokens") ? usageNode.get("prompt_tokens").asInt() : null;
                Integer completionTokens = usageNode.has("completion_tokens") ? usageNode.get("completion_tokens").asInt() : null;
                Integer totalTokens = usageNode.has("total_tokens") ? usageNode.get("total_tokens").asInt() : null;
                if (promptTokens != null || completionTokens != null) {
                    UsageInfo.UsageInfoBuilder builder = UsageInfo.builder()
                        .promptTokens(promptTokens)
                        .completionTokens(completionTokens)
                        .totalTokens(totalTokens);

                    JsonNode promptDetails = usageNode.get("prompt_tokens_details");
                    if (promptDetails != null && !promptDetails.isNull() && promptDetails.has("cached_tokens")) {
                        builder.cachedTokens(promptDetails.get("cached_tokens").asInt());
                    }
                    JsonNode completionDetails = usageNode.get("completion_tokens_details");
                    if (completionDetails != null && !completionDetails.isNull() && completionDetails.has("reasoning_tokens")) {
                        builder.reasoningTokens(completionDetails.get("reasoning_tokens").asInt());
                    }

                    return builder.build();
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting {} streaming usage: {}", providerName, e.getMessage());
        }

        return null;
    }

    @Override
    protected void accumulateStreamingToolCalls(String line, Map<Integer, StreamingToolCallAccumulator> accumulators) {
        String data;
        if (line.startsWith("data: ")) {
            data = line.substring(6).trim();
        } else if (line.trim().startsWith("{")) {
            data = line.trim();
        } else {
            return;
        }
        if (data.equals("[DONE]") || data.isEmpty()) {
            return;
        }

        try {
            JsonNode node = objectMapper.readTree(data);
            JsonNode choices = node.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode delta = choices.get(0).get("delta");
                if (delta != null && delta.has("tool_calls")) {
                    JsonNode toolCallsNode = delta.get("tool_calls");
                    if (toolCallsNode.isArray()) {
                        for (JsonNode tc : toolCallsNode) {
                            int index = tc.has("index") ? tc.get("index").asInt() : 0;

                            StreamingToolCallAccumulator acc = accumulators.computeIfAbsent(
                                index, k -> new StreamingToolCallAccumulator());

                            if (tc.has("id")) {
                                acc.id = tc.get("id").asText();
                            }

                            JsonNode function = tc.get("function");
                            if (function != null) {
                                if (function.has("name")) {
                                    acc.name = function.get("name").asText();
                                }
                                if (function.has("arguments")) {
                                    acc.arguments.append(function.get("arguments").asText());
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error accumulating {} streaming tool calls: {}", providerName, e.getMessage());
        }
    }

    @Override
    protected String processStreamingLine(String line) {
        String data;
        if (line.startsWith("data: ")) {
            data = line.substring(6).trim();
        } else if (line.trim().startsWith("{")) {
            data = line.trim();
        } else {
            return null;
        }
        if (data.equals("[DONE]") || data.isEmpty()) {
            return null;
        }

        try {
            JsonNode node = objectMapper.readTree(data);
            JsonNode choices = node.get("choices");
            if (choices != null && choices.isArray() && choices.size() > 0) {
                JsonNode delta = choices.get(0).get("delta");
                if (delta != null && delta.has("content")) {
                    JsonNode contentNode = delta.get("content");
                    if (contentNode != null && !contentNode.isNull()) {
                        String content = contentNode.asText();
                        if (content != null && !content.isEmpty()) {
                            return content;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.trace("Failed to parse streaming line from {}: {}", providerName, e.getMessage());
        }

        return null;
    }
}
