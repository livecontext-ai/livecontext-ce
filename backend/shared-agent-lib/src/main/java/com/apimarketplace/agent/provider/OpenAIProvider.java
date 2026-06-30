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
 * OpenAI (GPT) LLM Provider implementation.
 * Supports both synchronous and streaming completions.
 */
@Slf4j
@Component("sharedAgentOpenAIProvider")
public class OpenAIProvider extends AbstractLLMProvider {

    @Value("${ai.agent.providers.openai.api-key:${OPENAI_API_KEY:}}")
    private String apiKey;

    @Value("${ai.agent.providers.openai.api-url:https://api.openai.com/v1/chat/completions}")
    private String apiUrl;

    // Read models from YAML configuration, with fallback to common models
    /**
     * Configured list. No hardcoded fallback - if YAML omits the key, this provider reports
     * zero models and is dropped from the catalog. Fail-closed, so a misconfiguration never
     * resurrects stale 2024 IDs.
     */
    @Value("${ai.agent.providers.openai.models:}")
    private List<String> configuredModels;

    @Value("${ai.agent.providers.openai.display-order:1}")
    private int displayOrder;

    // Stage 1a.8 - inline-attachment byte cap. See AttachmentSizeGuard#DEFAULT_MAX_INLINE_BYTES
    // for the canonical constant; keep the three provider @Value defaults in lockstep with it.
    @Value("${ai.attachments.max-inline-bytes:262144}")
    private int maxInlineAttachmentBytes;

    // Images are tokenised by dimensions, not bytes - a larger cap (aligned with the
    // producer's files.view.image-inline-max-bytes) keeps real screenshots/photos visible.
    @Value("${ai.attachments.image-max-inline-bytes:3600000}")
    private int maxInlineImageBytes;

    @Override
    public int getDisplayOrder() {
        return displayOrder;
    }

    /**
     * Check if a model is a "reasoning" model with limited parameter support.
     * These models (o1, o3, gpt-5 series) don't support temperature, top_p, etc.
     */
    private boolean isReasoningModel(String model) {
        if (model == null) {
            return false;
        }
        String lowerModel = model.toLowerCase();
        return lowerModel.startsWith("o1") ||
               lowerModel.startsWith("o3") ||
               lowerModel.startsWith("gpt-5");
    }

    @Override
    public String getProviderName() {
        return "openai";
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

        String model = request.model() != null ? request.model() : getDefaultModel();
        body.put("model", model);
        body.put("messages", buildOpenAIMessages(request));

        boolean isReasoning = isReasoningModel(model);

        // Reasoning models don't support temperature, top_p, etc.
        // Default temperature to 0.7 to prevent infinite tool call loops
        if (!isReasoning) {
            body.put("temperature", request.temperature() != null ? request.temperature() : 0.7);
        }
        if (request.maxTokens() != null) {
            if (isReasoning) {
                body.put("max_completion_tokens", request.maxTokens());
            } else {
                body.put("max_tokens", request.maxTokens());
            }
        }
        if (!isReasoning && request.topP() != null) {
            body.put("top_p", request.topP());
        }
        if (!isReasoning && request.frequencyPenalty() != null) {
            body.put("frequency_penalty", request.frequencyPenalty());
        }
        if (!isReasoning && request.presencePenalty() != null) {
            body.put("presence_penalty", request.presencePenalty());
        }

        // Tools
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
            return CompletionResponse.error("No response from OpenAI");
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
    @SuppressWarnings("unchecked")
    protected UsageInfo parseUsageInfo(Map<String, Object> usage) {
        if (usage == null) {
            return null;
        }

        UsageInfo.UsageInfoBuilder builder = UsageInfo.builder()
            .promptTokens(getIntValue(usage, "prompt_tokens"))
            .completionTokens(getIntValue(usage, "completion_tokens"))
            .totalTokens(getIntValue(usage, "total_tokens"));

        // Extract prompt_tokens_details.cached_tokens
        Object promptDetails = usage.get("prompt_tokens_details");
        if (promptDetails instanceof Map) {
            Map<String, Object> details = (Map<String, Object>) promptDetails;
            builder.cachedTokens(getIntValue(details, "cached_tokens"));
        }

        // Extract completion_tokens_details.reasoning_tokens
        Object completionDetails = usage.get("completion_tokens_details");
        if (completionDetails instanceof Map) {
            Map<String, Object> details = (Map<String, Object>) completionDetails;
            builder.reasoningTokens(getIntValue(details, "reasoning_tokens"));
        }

        return builder.build();
    }

    @Override
    protected void addStreamingRequestOptions(Map<String, Object> body) {
        // Request usage stats in the final streaming chunk
        body.put("stream_options", Map.of("include_usage", true));
    }

    @Override
    protected String processStreamingLine(String line) {
        // OpenAI format: data: {"choices":[{"delta":{"content":"..."}}]}
        // Also handle raw JSON format from WebClient reactive streaming (no "data: " prefix)
        String data;
        if (line.startsWith("data: ")) {
            // SSE format from HttpURLConnection
            data = line.substring(6).trim();
        } else if (line.trim().startsWith("{")) {
            // Raw JSON from WebClient reactive streaming
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
                JsonNode finishReason = choices.get(0).get("finish_reason");
                
                if (delta != null && delta.has("content")) {
                    JsonNode contentNode = delta.get("content");
                    // Check if content is not null (JSON null becomes "null" string with asText())
                    if (contentNode != null && !contentNode.isNull()) {
                        String content = contentNode.asText();
                        if (content != null && !content.isEmpty()) {
                            return content;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error parsing OpenAI streaming line: {}", e.getMessage());
        }

        return null;
    }

    @Override
    protected void accumulateStreamingToolCalls(String line, Map<Integer, StreamingToolCallAccumulator> accumulators) {
        // Handle both SSE format (data: {...}) and raw JSON format ({...})
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
                            
                            // Get or create accumulator for this index
                            StreamingToolCallAccumulator acc = accumulators.computeIfAbsent(
                                index, k -> new StreamingToolCallAccumulator());
                            
                            // First chunk has id and function name
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
            log.debug("Error accumulating OpenAI streaming tool calls: {}", e.getMessage());
        }
    }
    
    @Override
    protected List<ToolCall> parseStreamingToolCalls(String line) {
        // This is now handled by accumulateStreamingToolCalls
        return null;
    }

    @Override
    protected UsageInfo extractStreamingUsage(String line) {
        // OpenAI sends usage in the final chunk when stream_options.include_usage=true
        // Format: data: {"id":"...","choices":[],"usage":{"prompt_tokens":N,"completion_tokens":N,"total_tokens":N}}
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

                    // Extract prompt_tokens_details.cached_tokens
                    JsonNode promptDetails = usageNode.get("prompt_tokens_details");
                    if (promptDetails != null && !promptDetails.isNull() && promptDetails.has("cached_tokens")) {
                        builder.cachedTokens(promptDetails.get("cached_tokens").asInt());
                    }

                    // Extract completion_tokens_details.reasoning_tokens
                    JsonNode completionDetails = usageNode.get("completion_tokens_details");
                    if (completionDetails != null && !completionDetails.isNull() && completionDetails.has("reasoning_tokens")) {
                        builder.reasoningTokens(completionDetails.get("reasoning_tokens").asInt());
                    }

                    log.debug("📊 [OPENAI USAGE] streaming: prompt={}, completion={}, total={}",
                        promptTokens, completionTokens, totalTokens);
                    return builder.build();
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting OpenAI streaming usage: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Build OpenAI messages with multimodal support.
     * OpenAI supports images via image_url content type.
     */
    private List<Map<String, Object>> buildOpenAIMessages(CompletionRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();

        // Add system message if present. OpenAI has no per-block prompt cache control,
        // so we fold systemBlocks (when provided) into a single concatenated string via
        // effectiveSystemPrompt(); callers that still pass legacy systemPrompt() fall
        // through the same helper.
        String systemInstruction = request.effectiveSystemPrompt();
        if (systemInstruction != null && !systemInstruction.isEmpty()) {
            messages.add(Map.of("role", "system", "content", systemInstruction));
        }

        // Add conversation history with attachment support
        if (request.conversationHistory() != null) {
            for (Message msg : request.conversationHistory()) {
                Map<String, Object> converted = convertOpenAIMessage(msg);
                if (converted != null) {
                    messages.add(converted);
                }
            }
        }

        // Add current user prompt
        if (request.userPrompt() != null && !request.userPrompt().isEmpty()) {
            messages.add(Map.of("role", "user", "content", request.userPrompt()));
        }

        return messages;
    }

    /**
     * Convert a Message to OpenAI format with multimodal support.
     */
    private Map<String, Object> convertOpenAIMessage(Message message) {
        Map<String, Object> msg = new HashMap<>();

        switch (message.role()) {
            case USER -> {
                msg.put("role", "user");
                if (message.hasAttachments()) {
                    msg.put("content", buildOpenAIMultimodalContent(message));
                } else {
                    msg.put("content", message.content() != null ? message.content() : "");
                }
            }
            case ASSISTANT -> {
                msg.put("role", "assistant");
                msg.put("content", message.content() != null ? message.content() : "");
                // Add tool_calls if present
                if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                    List<Map<String, Object>> toolCallsList = new ArrayList<>();
                    for (ToolCall tc : message.toolCalls()) {
                        String argsJson = "{}";
                        if (tc.arguments() != null) {
                            try {
                                argsJson = objectMapper.writeValueAsString(tc.arguments());
                            } catch (Exception e) {
                                log.warn("Failed to serialize tool call arguments: {}", e.getMessage());
                            }
                        }
                        toolCallsList.add(Map.of(
                            "id", tc.id(),
                            "type", "function",
                            "function", Map.of(
                                "name", tc.toolName(),
                                "arguments", argsJson
                            )
                        ));
                    }
                    msg.put("tool_calls", toolCallsList);
                }
            }
            case TOOL -> {
                msg.put("role", "tool");
                msg.put("content", message.content() != null ? message.content() : "");
                msg.put("tool_call_id", message.toolCallId());
            }
            case SYSTEM -> {
                msg.put("role", "system");
                msg.put("content", message.content() != null ? message.content() : "");
            }
        }

        return msg;
    }

    /**
     * Build multimodal content array for OpenAI API.
     * OpenAI supports: text, image_url (base64 data URLs)
     * PDFs and text files are converted to text content.
     */
    private List<Map<String, Object>> buildOpenAIMultimodalContent(Message message) {
        List<Map<String, Object>> contentParts = new ArrayList<>();

        // Add attachments - Stage 1a.8 size cap applies to every multimodal provider
        // (same CoreToolsProvider blob feeds everyone); oversized base64 data URLs
        // would otherwise dominate input tokens on gpt-4o vision calls.
        for (MessageAttachment attachment : message.attachments()) {
            MessageAttachment guarded = com.apimarketplace.agent.attachment.AttachmentSizeGuard
                    .enforceSizeCap(attachment, maxInlineAttachmentBytes, maxInlineImageBytes, "openai");
            Map<String, Object> part = buildOpenAIAttachment(guarded);
            if (part != null) {
                contentParts.add(part);
            }
        }

        // Add text content last
        if (message.content() != null && !message.content().isBlank()) {
            contentParts.add(Map.of("type", "text", "text", message.content()));
        }

        return contentParts;
    }

    /**
     * Build an OpenAI attachment block based on type.
     * Only images are supported natively; PDFs and text use text fallback.
     */
    private Map<String, Object> buildOpenAIAttachment(MessageAttachment attachment) {
        return switch (attachment.type()) {
            case IMAGE -> Map.of(
                "type", "image_url",
                "image_url", Map.of(
                    "url", "data:" + attachment.mimeType() + ";base64," +
                           Base64.getEncoder().encodeToString(attachment.data())
                )
            );
            case PDF -> Map.of(
                "type", "text",
                "text", "[PDF: " + attachment.fileName() + "]\n" +
                        (attachment.extractedText() != null ? attachment.extractedText() :
                         "[PDF content extraction not available]")
            );
            case TEXT -> Map.of(
                "type", "text",
                "text", "[File: " + attachment.fileName() + "]\n" + attachment.getTextContent()
            );
            case OTHER -> Map.of(
                "type", "text",
                "text", "[Attachment: " + attachment.fileName() + " - unsupported format]"
            );
        };
    }
}
