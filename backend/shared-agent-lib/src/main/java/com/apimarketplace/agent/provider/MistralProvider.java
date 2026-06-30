package com.apimarketplace.agent.provider;

import com.apimarketplace.agent.domain.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Mistral AI LLM Provider implementation.
 * Uses OpenAI-compatible API format.
 */
@Slf4j
@Component("sharedAgentMistralProvider")
public class MistralProvider extends AbstractLLMProvider {

    @Value("${ai.agent.providers.mistral.api-key:${MISTRAL_API_KEY:}}")
    private String apiKey;

    @Value("${ai.agent.providers.mistral.api-url:https://api.mistral.ai/v1/chat/completions}")
    private String apiUrl;

    @Value("${ai.agent.providers.mistral.models:mistral-large-latest,mistral-medium-latest,mistral-small-latest}")
    private List<String> configuredModels;

    @Value("${ai.agent.providers.mistral.display-order:4}")
    private int displayOrder;

    @Override
    public int getDisplayOrder() {
        return displayOrder;
    }

    @Override
    public String getProviderName() {
        return "mistral";
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
        return apiUrl;
    }

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

        // Default temperature to 0.7 to prevent infinite tool call loops
        body.put("temperature", request.temperature() != null ? request.temperature() : 0.7);
        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        }
        if (request.topP() != null) {
            body.put("top_p", request.topP());
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
            return CompletionResponse.error("No response from Mistral");
        }

        Map<String, Object> choice = choices.get(0);
        Map<String, Object> message = (Map<String, Object>) choice.get("message");
        String finishReason = (String) choice.get("finish_reason");

        String content = message != null ? (String) message.get("content") : null;
        List<Map<String, Object>> toolCalls = message != null ?
            (List<Map<String, Object>>) message.get("tool_calls") : null;

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
    protected void addStreamingRequestOptions(Map<String, Object> body) {
        // Request usage stats in the final streaming chunk
        body.put("stream_options", Map.of("include_usage", true));
    }

    @Override
    protected String processStreamingLine(String line) {
        // Mistral uses OpenAI-compatible format
        if (!line.startsWith("data: ")) {
            return null;
        }

        String data = line.substring(6).trim();
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
                    // Guard against a JSON null content delta: asText() on a NullNode
                    // returns the literal "null" string, which would otherwise be
                    // streamed straight into the response text.
                    if (contentNode != null && !contentNode.isNull()) {
                        String content = contentNode.asText();
                        if (content != null && !content.isEmpty()) {
                            return content;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error parsing Mistral streaming line: {}", e.getMessage());
        }

        return null;
    }

    @Override
    protected UsageInfo extractStreamingUsage(String line) {
        // Mistral uses OpenAI-compatible format for streaming usage
        if (!line.startsWith("data: ")) {
            return null;
        }

        String data = line.substring(6).trim();
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
                    log.debug("📊 [MISTRAL USAGE] streaming: prompt={}, completion={}, total={}",
                        promptTokens, completionTokens, totalTokens);
                    return UsageInfo.builder()
                        .promptTokens(promptTokens)
                        .completionTokens(completionTokens)
                        .totalTokens(totalTokens)
                        .build();
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting Mistral streaming usage: {}", e.getMessage());
        }

        return null;
    }

    @Override
    protected void accumulateStreamingToolCalls(String line, Map<Integer, StreamingToolCallAccumulator> accumulators) {
        // Mistral uses OpenAI-compatible format for tool calls
        if (!line.startsWith("data: ")) {
            return;
        }

        String data = line.substring(6).trim();
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
            log.debug("Error accumulating Mistral streaming tool calls: {}", e.getMessage());
        }
    }
}
