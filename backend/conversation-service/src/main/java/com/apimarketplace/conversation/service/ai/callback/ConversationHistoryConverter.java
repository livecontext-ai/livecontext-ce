package com.apimarketplace.conversation.service.ai.callback;

import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.conversation.dto.ChatRequest;
import com.apimarketplace.conversation.entity.ToolResult;
import com.apimarketplace.conversation.service.ToolResultService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts conversation history between ChatRequest format and shared-agent-lib Message format.
 * Joins tool calls with their results from tool_results table for complete LLM context.
 *
 * Features:
 * - Progressive masking: HOT (full), WARM (summary), COLD (minimal) zones
 * - Help deduplication: second identical help call gets a back-reference
 * - Structured truncation: informative summary of dropped messages
 *
 * Single Responsibility: Handle all conversation history transformations.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationHistoryConverter {

    private final ToolResultService toolResultService;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // History limits
    static final int MAX_HISTORY_TOKENS = 45000;          // Masking compensates; better context retention
    static final int MAX_CONTENT_PREVIEW = 3000;           // Max chars for HOT zone content
    static final int WARM_CONTENT_PREVIEW = 200;           // Outcome summary limit for WARM zone
    private static final int CHARS_PER_TOKEN = 4;          // Rough estimation

    // Progressive masking zones (counted from end of message list)
    static final int HOT_ZONE_SIZE = 6;                    // Last 6 messages: full content
    static final int WARM_ZONE_SIZE = 8;                   // Messages 7-14: summary content

    // Masking levels for progressive context reduction
    enum MaskingLevel { FULL, SUMMARY, MINIMAL }

    // Patterns for parsing tool markers from message content
    private static final Pattern TOOL_CALL_PATTERN = Pattern.compile("\\[tool_call:([^\\]]+)\\]");
    private static final Pattern VISUALIZE_PATTERN = Pattern.compile("\\[visualize:([^:]+):([^:]+):?([^\\]]*)]");

    /**
     * Convert ChatRequest history to shared-agent-lib Message format.
     * Backward compatible version without tool result lookup.
     */
    public List<Message> convert(List<ChatRequest.ChatMessage> history) {
        return convert(history, null, null);
    }

    /**
     * Convert ChatRequest history to shared-agent-lib Message format.
     * Pipeline: build messages → dedup help → progressive masking → token limit.
     */
    public List<Message> convert(List<ChatRequest.ChatMessage> history, String conversationId, String tenantId) {
        if (history == null || history.isEmpty()) {
            return new ArrayList<>();
        }

        // Load tool results for this conversation and create lookup map
        Map<String, ToolResult> toolResultsMap = loadToolResultsMap(conversationId, tenantId);

        log.info("Converting {} messages from history (loaded {} tool results)", history.size(), toolResultsMap.size());

        // Step 1: Build Message list from history
        List<Message> messages = buildMessages(history, toolResultsMap);

        log.info("Conversion complete: {} input → {} output messages", history.size(), messages.size());

        // Step 2: Deduplicate help results
        deduplicateHelpResults(messages, toolResultsMap);

        // Step 3: Apply progressive masking
        applyProgressiveMasking(messages);

        // Step 4: Apply token limit with structured truncation
        return applyTokenLimit(messages);
    }

    /**
     * Build Message list from raw ChatRequest history, joining tool results.
     */
    private List<Message> buildMessages(List<ChatRequest.ChatMessage> history,
                                         Map<String, ToolResult> toolResultsMap) {
        List<Message> messages = new ArrayList<>();

        for (int idx = 0; idx < history.size(); idx++) {
            ChatRequest.ChatMessage msg = history.get(idx);
            Message.Role role = parseRole(msg.getRole());
            String content = msg.getContent();
            String toolCalls = msg.getToolCalls();

            log.debug("[{}] Processing: role={}, hasContent={}, hasToolCalls={}",
                idx, role,
                content != null && !content.isBlank(),
                toolCalls != null && !toolCalls.isBlank() && !"[]".equals(toolCalls));

            // TOOL messages already contain the summary (persisted by AgentStreamingCallbackFactory)
            if (role == Message.Role.TOOL) {
                if (content != null && !content.isBlank()) {
                    log.debug("[{}] → TOOL added as ASSISTANT", idx);
                    messages.add(Message.builder()
                        .role(Message.Role.ASSISTANT)
                        .content(content)
                        .build());
                } else {
                    log.debug("[{}] → TOOL SKIPPED (no content)", idx);
                }
                continue;
            }

            // Convert ASSISTANT messages with tool_calls to readable action summaries
            if (role == Message.Role.ASSISTANT) {
                boolean hasContent = content != null && !content.isBlank();
                boolean hasToolCalls = toolCalls != null && !toolCalls.isBlank() && !"[]".equals(toolCalls);

                if (!hasContent && hasToolCalls) {
                    String toolCallsSummary = convertToolCallsToText(toolCalls, toolResultsMap, MaskingLevel.FULL);
                    if (toolCallsSummary != null && !toolCallsSummary.isBlank()) {
                        log.debug("[{}] → ASSISTANT (tool-only) added with summary", idx);
                        messages.add(Message.builder()
                            .role(Message.Role.ASSISTANT)
                            .content(toolCallsSummary)
                            .build());
                    } else {
                        log.debug("[{}] → ASSISTANT (tool-only) SKIPPED (blank summary)", idx);
                    }
                    continue;
                }

                if (hasContent) {
                    content = enhanceWithToolSummary(content);
                }
            }

            if (content == null || content.isBlank()) {
                log.debug("[{}] → SKIPPED (no content, role={})", idx, role);
                continue;
            }

            log.debug("[{}] → ADDED: role={}", idx, role);
            messages.add(Message.builder()
                .role(role)
                .content(content)
                .build());
        }

        return messages;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // HELP DEDUPLICATION
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Deduplicate help results across the conversation. Second occurrence of the same
     * help call gets replaced with a back-reference.
     */
    void deduplicateHelpResults(List<Message> messages, Map<String, ToolResult> toolResultsMap) {
        Set<String> seenHelpKeys = new HashSet<>();
        int dedupCount = 0;

        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            if (msg.role() != Message.Role.ASSISTANT || msg.content() == null) continue;

            String content = msg.content();
            // Check if this message contains a help tool call
            // Help tool results typically contain help documentation patterns
            String helpKey = detectHelpKeyInContent(content);
            if (helpKey == null) continue;

            if (seenHelpKeys.contains(helpKey)) {
                // Replace with back-reference
                String toolName = helpKey.split(":")[0];
                messages.set(i, Message.builder()
                    .role(Message.Role.ASSISTANT)
                    .content("[Help already loaded for " + toolName + " - see earlier in conversation. Skipping duplicate.]")
                    .build());
                dedupCount++;
            } else {
                seenHelpKeys.add(helpKey);
            }
        }

        if (dedupCount > 0) {
            log.info("Deduplicated {} help results", dedupCount);
        }
    }

    /**
     * Detect if message content represents a help result and return its dedup key.
     * Help results are identified by their content patterns:
     * - "[Tool: workflow(action='help'..." or "[Tool: interface(action='help'..."
     * - Content containing help documentation markers
     */
    private String detectHelpKeyInContent(String content) {
        if (content == null) return null;

        // Pattern: [Tool: toolName(action='help'...
        Pattern helpToolPattern = Pattern.compile("\\[Tool:\\s*(\\w+)\\(action='help'");
        Matcher matcher = helpToolPattern.matcher(content);
        if (matcher.find()) {
            String toolName = matcher.group(1);
            // Check for topics
            Pattern topicsPattern = Pattern.compile("topics=\\[([^\\]]+)\\]");
            Matcher topicsMatcher = topicsPattern.matcher(content);
            if (topicsMatcher.find()) {
                return toolName + ":help:" + topicsMatcher.group(1).replaceAll("[\"' ]", "");
            }
            return toolName + ":help";
        }

        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // PROGRESSIVE MASKING
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Apply progressive masking to messages based on their position.
     * HOT (last 6): full content preserved.
     * WARM (7-14 from end): tool results replaced with outcome summaries.
     * COLD (15+ from end): tool results minimal, user text truncated.
     */
    void applyProgressiveMasking(List<Message> messages) {
        int size = messages.size();
        if (size <= HOT_ZONE_SIZE) return; // All messages in HOT zone

        int maskedCount = 0;
        for (int i = 0; i < size; i++) {
            MaskingLevel level = getMaskingLevel(i, size);
            if (level == MaskingLevel.FULL) continue;

            Message msg = messages.get(i);
            if (msg.content() == null) continue;

            String masked = applyMaskToMessage(msg, level);
            if (!masked.equals(msg.content())) {
                messages.set(i, Message.builder()
                    .role(msg.role())
                    .content(masked)
                    .build());
                maskedCount++;
            }
        }

        if (maskedCount > 0) {
            log.info("Progressive masking applied: {} messages masked (HOT={}, WARM={}, COLD={})",
                maskedCount, Math.min(size, HOT_ZONE_SIZE),
                Math.min(Math.max(0, size - HOT_ZONE_SIZE), WARM_ZONE_SIZE),
                Math.max(0, size - HOT_ZONE_SIZE - WARM_ZONE_SIZE));
        }
    }

    /**
     * Determine masking level based on message position relative to end.
     */
    MaskingLevel getMaskingLevel(int index, int totalSize) {
        int fromEnd = totalSize - 1 - index;
        if (fromEnd < HOT_ZONE_SIZE) return MaskingLevel.FULL;
        if (fromEnd < HOT_ZONE_SIZE + WARM_ZONE_SIZE) return MaskingLevel.SUMMARY;
        return MaskingLevel.MINIMAL;
    }

    /**
     * Apply masking to a single message based on its level.
     */
    private String applyMaskToMessage(Message msg, MaskingLevel level) {
        String content = msg.content();

        if (msg.role() == Message.Role.ASSISTANT) {
            // Check if this is a tool call result (starts with "[Tool:")
            if (content.startsWith("[Tool:")) {
                return maskToolContent(content, level);
            }
        }

        if (msg.role() == Message.Role.USER && level == MaskingLevel.MINIMAL) {
            // Truncate user text in COLD zone
            if (content.length() > 200) {
                return content.substring(0, 200) + "... [truncated]";
            }
        }

        // WARM zone: truncate long assistant text too
        if (msg.role() == Message.Role.ASSISTANT && level == MaskingLevel.SUMMARY) {
            if (content.length() > WARM_CONTENT_PREVIEW) {
                return content.substring(0, WARM_CONTENT_PREVIEW) + "... [summarized]";
            }
        }

        return content;
    }

    /**
     * Mask tool call content based on masking level.
     */
    private String maskToolContent(String content, MaskingLevel level) {
        if (level == MaskingLevel.SUMMARY) {
            // Extract tool name and key info, truncate result content
            return truncateToolContent(content, WARM_CONTENT_PREVIEW);
        }

        if (level == MaskingLevel.MINIMAL) {
            // Minimal one-liner
            return extractMinimalToolSummary(content);
        }

        return content;
    }

    /**
     * Truncate tool content to a max length, keeping the tool header.
     */
    private String truncateToolContent(String content, int maxLength) {
        if (content.length() <= maxLength) return content;

        // Keep the first line (tool header) + truncation marker
        int firstNewline = content.indexOf('\n');
        if (firstNewline > 0 && firstNewline < maxLength) {
            return content.substring(0, Math.min(content.length(), maxLength)) + "... [content summarized]";
        }
        return content.substring(0, maxLength) + "... [content summarized]";
    }

    /**
     * Extract a minimal one-line summary from tool content for COLD zone.
     * Input:  "[Tool: workflow(action='help', topics=['triggers']) → ✅ (200ms)\n  Content: ..."
     * Output: "[Tool: workflow(action='help') → OK]"
     */
    private String extractMinimalToolSummary(String content) {
        // Extract tool name and action from first line
        int firstNewline = content.indexOf('\n');
        String firstLine = firstNewline > 0 ? content.substring(0, firstNewline) : content;

        // Determine status
        boolean success = firstLine.contains("✅") || !firstLine.contains("❌");
        String status = success ? "OK" : "ERROR";

        // Extract tool name and action
        Pattern toolActionPattern = Pattern.compile("\\[Tool:\\s*(\\w+)\\(([^)]{0,60})");
        Matcher matcher = toolActionPattern.matcher(firstLine);
        if (matcher.find()) {
            String toolName = matcher.group(1);
            String args = matcher.group(2);
            // Keep only action param
            Pattern actionOnly = Pattern.compile("action='([^']+)'");
            Matcher actionMatcher = actionOnly.matcher(args);
            String actionStr = actionMatcher.find() ? "action='" + actionMatcher.group(1) + "'" : args;
            return "[Tool: " + toolName + "(" + actionStr + ") → " + status + "]";
        }

        // Fallback: just truncate
        return firstLine.length() > 80 ? firstLine.substring(0, 77) + "...]" : firstLine;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // TOKEN LIMIT & STRUCTURED TRUNCATION
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Apply token limit to messages, keeping recent messages intact.
     * Uses structured truncation summary for dropped messages.
     */
    List<Message> applyTokenLimit(List<Message> messages) {
        if (messages.size() <= HOT_ZONE_SIZE) {
            return messages;
        }

        int totalTokens = estimateTotalTokens(messages);
        if (totalTokens <= MAX_HISTORY_TOKENS) {
            return messages;
        }

        log.info("History exceeds token limit ({} > {}), truncating older messages",
            totalTokens, MAX_HISTORY_TOKENS);

        int splitIndex = messages.size() - HOT_ZONE_SIZE;
        List<Message> olderMessages = new ArrayList<>(messages.subList(0, splitIndex));
        List<Message> recentMessages = messages.subList(splitIndex, messages.size());

        int recentTokens = estimateTotalTokens(recentMessages);
        int availableForOlder = MAX_HISTORY_TOKENS - recentTokens;

        List<Message> truncatedOlder = truncateMessagesToFit(olderMessages, availableForOlder);

        List<Message> result = new ArrayList<>(truncatedOlder);
        result.addAll(recentMessages);

        int finalTokens = estimateTotalTokens(result);
        log.info("History truncated: {} messages ({} tokens) → {} messages ({} tokens)",
            messages.size(), totalTokens, result.size(), finalTokens);

        return result;
    }

    /**
     * Truncate a list of messages to fit within a token budget.
     * Uses structured truncation summary for dropped messages.
     */
    private List<Message> truncateMessagesToFit(List<Message> messages, int maxTokens) {
        if (maxTokens <= 0) {
            return List.of(Message.builder()
                .role(Message.Role.SYSTEM)
                .content(buildStructuredTruncationSummary(messages))
                .build());
        }

        List<Message> result = new ArrayList<>();
        int currentTokens = 0;

        // Process from newest to oldest (reverse), then reverse result
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            int msgTokens = estimateTokens(msg.content());

            if (currentTokens + msgTokens <= maxTokens) {
                result.add(0, msg);
                currentTokens += msgTokens;
            } else {
                break;
            }
        }

        // If we dropped messages, add a structured summary
        if (result.size() < messages.size()) {
            List<Message> droppedMessages = messages.subList(0, messages.size() - result.size());
            result.add(0, Message.builder()
                .role(Message.Role.SYSTEM)
                .content(buildStructuredTruncationSummary(droppedMessages))
                .build());
        }

        return result;
    }

    /**
     * Build a structured truncation summary from dropped messages.
     * Extracts user intents, tool action counts, and key artifacts.
     */
    String buildStructuredTruncationSummary(List<Message> droppedMessages) {
        StringBuilder summary = new StringBuilder();
        summary.append("[Conversation context - ").append(droppedMessages.size()).append(" earlier messages omitted]\n");

        // Collect user intents (first 100 chars of each USER message)
        List<String> userIntents = new ArrayList<>();
        // Collect tool action counts
        Map<String, Integer> toolActionCounts = new LinkedHashMap<>();
        // Collect artifact names/IDs from mutation results
        List<String> artifacts = new ArrayList<>();

        for (Message msg : droppedMessages) {
            if (msg.content() == null) continue;

            if (msg.role() == Message.Role.USER) {
                String intent = msg.content().length() > 100
                    ? msg.content().substring(0, 100) + "..."
                    : msg.content();
                userIntents.add(intent.replaceAll("\\s+", " ").trim());
            }

            if (msg.role() == Message.Role.ASSISTANT && msg.content().startsWith("[Tool:")) {
                extractToolActionsFromContent(msg.content(), toolActionCounts, artifacts);
            }
        }

        if (!userIntents.isEmpty()) {
            summary.append("Topics discussed: ");
            summary.append(String.join("; ", userIntents.subList(0, Math.min(3, userIntents.size()))));
            summary.append("\n");
        }

        if (!toolActionCounts.isEmpty()) {
            summary.append("Tools used: ");
            List<String> entries = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : toolActionCounts.entrySet()) {
                entries.add(entry.getKey() + (entry.getValue() > 1 ? " x" + entry.getValue() : ""));
            }
            summary.append(String.join(", ", entries));
            summary.append("\n");
        }

        if (!artifacts.isEmpty()) {
            summary.append("Key artifacts: ");
            summary.append(String.join(", ", artifacts.subList(0, Math.min(5, artifacts.size()))));
            summary.append("\n");
        }

        return summary.toString().trim();
    }

    /**
     * Extract tool action counts and artifact info from tool result content.
     */
    private void extractToolActionsFromContent(String content, Map<String, Integer> toolActionCounts,
                                                List<String> artifacts) {
        // Parse "[Tool: name(action='xxx'..." patterns
        Pattern pattern = Pattern.compile("\\[Tool:\\s*(\\w+)\\(action='(\\w+)'");
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String toolName = matcher.group(1);
            String action = matcher.group(2);
            String key = toolName + " " + action;
            toolActionCounts.merge(key, 1, Integer::sum);

            // For mutations, try to extract artifact name
            if ("create".equals(action) || "init".equals(action)) {
                Pattern namePattern = Pattern.compile("name='([^']+)'");
                Matcher nameMatcher = namePattern.matcher(content);
                if (nameMatcher.find()) {
                    artifacts.add(toolName + " '" + nameMatcher.group(1) + "'");
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // TOOL RESULTS MAP
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Load lightweight tool result previews (content_full excluded from query).
     * Uses content_preview (~3KB) instead of content_full (~100KB+) per result.
     */
    private Map<String, ToolResult> loadToolResultsMap(String conversationId, String tenantId) {
        if (conversationId == null || tenantId == null) {
            return new HashMap<>();
        }

        try {
            List<ToolResult> results = toolResultService.getPreviewsByConversation(conversationId, tenantId);
            Map<String, ToolResult> map = new HashMap<>();
            for (ToolResult result : results) {
                if (result.getToolCallId() != null) {
                    map.put(result.getToolCallId(), result);
                }
            }
            return map;
        } catch (Exception e) {
            log.warn("Failed to load tool results for conversation {}: {}", conversationId, e.getMessage());
            return new HashMap<>();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // TOKEN ESTIMATION
    // ═══════════════════════════════════════════════════════════════════════════════

    int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return text.length() / CHARS_PER_TOKEN;
    }

    private int estimateTotalTokens(List<Message> messages) {
        return messages.stream()
            .mapToInt(m -> estimateTokens(m.content()))
            .sum();
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // PUBLIC UTILITIES
    // ═══════════════════════════════════════════════════════════════════════════════

    public boolean isNewConversation(List<Message> history) {
        return history.isEmpty() ||
            (history.size() == 1 && Message.Role.USER == history.get(0).role());
    }

    public void logHistory(List<Message> history) {
        if (history.isEmpty()) {
            log.debug("Conversation history is empty");
            return;
        }

        log.info("=== CONVERSATION HISTORY ({} messages, chronological order) ===", history.size());
        for (int i = 0; i < history.size(); i++) {
            Message msg = history.get(i);
            String preview = msg.content() != null
                ? msg.content().substring(0, Math.min(80, msg.content().length())) + "..."
                : "(null)";
            log.info("  [{}] {} - {}", i + 1, msg.role(), preview);
        }
        log.info("================================================================");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // ROLE PARSING & TOOL SUMMARY
    // ═══════════════════════════════════════════════════════════════════════════════

    private Message.Role parseRole(String role) {
        return switch (role.toLowerCase()) {
            case "user" -> Message.Role.USER;
            case "assistant" -> Message.Role.ASSISTANT;
            case "system" -> Message.Role.SYSTEM;
            case "tool" -> Message.Role.TOOL;
            default -> Message.Role.USER;
        };
    }

    private String enhanceWithToolSummary(String content) {
        if (content == null) return null;

        List<String> toolActions = new ArrayList<>();
        extractToolCalls(content, toolActions);
        extractVisualizations(content, toolActions);

        if (toolActions.isEmpty()) {
            return content;
        }

        StringBuilder enhanced = new StringBuilder(content);
        enhanced.append("\n\n[PREVIOUS ACTIONS IN THIS MESSAGE:");
        for (String action : toolActions) {
            enhanced.append("\n- ").append(action);
        }
        enhanced.append("]");
        return enhanced.toString();
    }

    private void extractToolCalls(String content, List<String> actions) {
        Matcher matcher = TOOL_CALL_PATTERN.matcher(content);
        while (matcher.find()) {
            actions.add("Called tool: " + matcher.group(1));
        }
    }

    private void extractVisualizations(String content, List<String> actions) {
        Matcher matcher = VISUALIZE_PATTERN.matcher(content);
        while (matcher.find()) {
            String type = matcher.group(1);
            String id = matcher.group(2);
            String name = matcher.groupCount() >= 3 ? matcher.group(3) : "";
            String nameStr = (name != null && !name.isEmpty()) ? " (" + name + ")" : "";
            actions.add("Displayed " + type + " with ID: " + id + nameStr);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // TOOL CALL TEXT CONVERSION
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Convert tool_calls JSON array to readable text summary.
     * Masking level controls how much detail to include.
     */
    private String convertToolCallsToText(String toolCallsJson, Map<String, ToolResult> toolResultsMap,
                                           MaskingLevel level) {
        if (toolCallsJson == null || toolCallsJson.isBlank()) {
            return null;
        }

        try {
            JsonNode toolCallsArray = objectMapper.readTree(toolCallsJson);
            if (!toolCallsArray.isArray() || toolCallsArray.isEmpty()) {
                return null;
            }

            StringBuilder summary = new StringBuilder();
            for (JsonNode toolCall : toolCallsArray) {
                String name = toolCall.has("toolName") ? toolCall.get("toolName").asText() : null;
                if (name == null || name.startsWith("_")) continue;
                String arguments = toolCall.has("arguments") ? toolCall.get("arguments").asText() : "{}";
                String toolCallId = toolCall.has("id") ? toolCall.get("id").asText() : null;

                ToolResult result = toolCallId != null ? toolResultsMap.get(toolCallId) : null;

                // Extract useful metadata (displayToolName, httpStatus) - never tenant
                String displayName = extractMetaString(result, "toolName");
                String httpStatus = extractMetaString(result, "httpStatus");

                if (level == MaskingLevel.MINIMAL) {
                    // One-liner for COLD zone - displayName after status
                    String status = result != null ? (result.isSuccess() ? "OK" : "ERROR") : "?";
                    String keyParams = ToolCallClassifier.extractKeyParams(name, arguments);
                    summary.append("[Tool: ").append(name).append("(").append(keyParams).append(") → ").append(status);
                    if (httpStatus != null) summary.append(" HTTP:").append(httpStatus);
                    if (displayName != null && !displayName.equals(name)) {
                        summary.append(" (").append(displayName).append(")");
                    }
                    summary.append("]\n");
                    continue;
                }

                if (level == MaskingLevel.SUMMARY) {
                    // Outcome summary for WARM zone - displayName after summary
                    String resultContent = result != null
                        ? (result.isSuccess() ? result.getContentForHistory() : result.getErrorMessage())
                        : null;
                    boolean success = result != null && result.isSuccess();
                    String outcomeSummary = ToolCallClassifier.extractOutcomeSummary(
                        name, arguments, resultContent, success);
                    summary.append("[Tool: ").append(outcomeSummary);
                    if (httpStatus != null) summary.append(" | HTTP:").append(httpStatus);
                    if (displayName != null && !displayName.equals(name)) {
                        summary.append(" (").append(displayName).append(")");
                    }
                    summary.append("]\n");
                    continue;
                }

                // FULL level - uses content_preview (hot tail, ~3KB max)
                String readableArgs = formatArguments(arguments);
                if (readableArgs.length() > MAX_CONTENT_PREVIEW) {
                    readableArgs = readableArgs.substring(0, MAX_CONTENT_PREVIEW) + "...";
                }

                summary.append("[Tool: ").append(name).append("(").append(readableArgs).append(")");

                if (result != null) {
                    String statusIcon = result.isSuccess() ? "✅" : "❌";
                    summary.append(" → ").append(statusIcon);

                    if (result.getDurationMs() != null) {
                        summary.append(" (").append(result.getDurationMs()).append("ms)");
                    }
                    if (httpStatus != null) {
                        summary.append(" HTTP:").append(httpStatus);
                    }
                    if (displayName != null && !displayName.equals(name)) {
                        summary.append(" (").append(displayName).append(")");
                    }
                    summary.append("\n");

                    // Use preview content (already truncated at save time with get_tool_result hint)
                    String rawContent = result.isSuccess() ? result.getContentForHistory() : result.getErrorMessage();
                    if (rawContent != null && !rawContent.isEmpty()) {
                        summary.append("  ").append(result.isSuccess() ? "Content" : "Error").append(": ")
                               .append(rawContent).append("\n");

                        // If preview was already truncated (contains the hint), no extra hint needed.
                        // If content fits in preview but exceeds display limit, add hint.
                        if (rawContent.length() > MAX_CONTENT_PREVIEW && toolCallId != null
                                && !rawContent.contains("get_tool_result")) {
                            summary.append("  → Use get_tool_result(tool_call_id=\"")
                                   .append(toolCallId)
                                   .append("\") for full content\n");
                        }
                    }
                } else {
                    summary.append(" → (result not found)\n");
                }
            }

            return summary.toString().trim();
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse tool_calls JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extract a metadata string value from a ToolResult, or null if absent.
     * Handles nested objects (e.g., httpStatus map → extracts "code").
     */
    private String extractMetaString(ToolResult result, String key) {
        if (result == null || result.getMetadata() == null) return null;
        Object val = result.getMetadata().get(key);
        if (val == null) return null;
        if (val instanceof Map) {
            // httpStatus is typically {"code": 200, "error": null}
            Object code = ((Map<?, ?>) val).get("code");
            return code != null ? code.toString() : null;
        }
        String str = val.toString();
        return str.isBlank() ? null : str;
    }

    private String formatArguments(String argumentsJson) {
        try {
            JsonNode args = objectMapper.readTree(argumentsJson);
            if (!args.isObject()) {
                return argumentsJson;
            }

            StringBuilder sb = new StringBuilder();
            Iterator<Map.Entry<String, JsonNode>> fields = args.fields();
            boolean first = true;

            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!first) sb.append(", ");
                first = false;

                String key = field.getKey();
                JsonNode value = field.getValue();

                sb.append(key).append("=");

                if (value.isTextual()) {
                    String text = value.asText();
                    if (text.length() > 50) {
                        sb.append("'").append(text.substring(0, 47)).append("...'");
                    } else {
                        sb.append("'").append(text).append("'");
                    }
                } else if (value.isObject() || value.isArray()) {
                    String json = value.toString();
                    if (json.length() > 60) {
                        sb.append(json.substring(0, 57)).append("...");
                    } else {
                        sb.append(json);
                    }
                } else {
                    sb.append(value.asText());
                }
            }

            return sb.toString();
        } catch (JsonProcessingException e) {
            return argumentsJson;
        }
    }
}
