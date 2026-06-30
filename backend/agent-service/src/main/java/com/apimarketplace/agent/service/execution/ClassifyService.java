package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.bridge.BridgeAccessDeniedException;
import com.apimarketplace.agent.client.dto.execution.ClassifyRequestDto;
import com.apimarketplace.agent.client.dto.execution.ClassifyResponseDto;
import com.apimarketplace.agent.client.dto.execution.ConversationMessageDto;
import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.UsageInfo;
import com.apimarketplace.agent.loop.AgentLoopContext;
import com.apimarketplace.agent.loop.AgentLoopResult;
import com.apimarketplace.agent.loop.AgentLoopService;
import com.apimarketplace.agent.loop.CallPurpose;
import com.apimarketplace.agent.loop.PreIterationGuard;
import com.apimarketplace.agent.service.budget.GuardChainFactory;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classification execution - delegates to {@link AgentLoopService} in single-shot mode
 * (no tools, 1 iteration) so that budget guards, token tracking, and observability
 * are centralized with the general agent pipeline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClassifyService {

    static final String SYSTEM_PROMPT = """
        You are a classification assistant. Your ONLY task is to categorize content \
        into one of the provided categories.

        OUTPUT FORMAT - MANDATORY:
        You MUST respond with EXACTLY one raw JSON object. Nothing else.
        No preamble, no explanation, no markdown fences, no trailing text.
        Any output that is not a single valid JSON object is a fatal error.

        JSON schema (strict):
        {
          "selected_category": "category_label",
          "confidence": 0.95,
          "reasoning": "Brief explanation of why this category was chosen"
        }

        Constraints:
        1. selected_category MUST be exactly one of the provided category labels (case-sensitive)
        2. confidence MUST be a number between 0.0 and 1.0
        3. reasoning MUST be a concise single sentence
        4. Output MUST start with { and end with } - no other characters allowed
        """;

    private final AgentLoopService agentLoopService;
    private final GuardChainFactory guardChainFactory;
    private final ObjectMapper objectMapper;
    private final BridgeLoopDispatcher bridgeDispatcher;
    private final com.apimarketplace.agent.service.ModelCatalogService modelCatalogService;

    /**
     * Back-compat overload - async paths (queue worker) call without an inbound
     * role context. Falls through to {@link #execute(ClassifyRequestDto, String)}
     * with null roles; downstream the bridge guard treats null as USER.
     */
    public ClassifyResponseDto execute(ClassifyRequestDto request) {
        return execute(request, null);
    }

    public ClassifyResponseDto execute(ClassifyRequestDto request, String userRoles) {
        long startTime = System.currentTimeMillis();
        // Normalise provider against the catalog FIRST: a bridge (CLI) model
        // stored as provider="anthropic" (frontend heuristic / LLM-authored
        // plan) must resolve to its bridge slug so it dispatches via the bridge
        // AND passes through BridgeAccessGuard - identical to the chat path.
        String providerName = modelCatalogService.resolveProvider(request.provider(), request.model());

        String userPrompt = buildPrompt(request);

        try {
            PreIterationGuard guard = guardChainFactory.forAgent(
                request.tenantId(), request.agentEntityId(),
                providerName, request.model());

            AgentLoopContext context = AgentLoopContext.builder()
                .provider(providerName)
                .model(request.model())
                .systemPrompt(SYSTEM_PROMPT)
                .userPrompt(userPrompt)
                .tools(null)
                .autoDiscoverTools(false)
                .maxIterations(1)
                .temperature(request.temperature() != null ? request.temperature() : 0.1)
                .maxTokens(request.maxTokens() != null ? request.maxTokens() : 500)
                .tenantId(request.tenantId())
                .userRoles(userRoles)
                .agentId(request.agentEntityId())
                .preIterationGuard(guard)
                .purpose(CallPurpose.CLASSIFY)
                .build();

            boolean useBridge = bridgeDispatcher.shouldDispatch(providerName);
            log.info("Executing classify via {}: provider={}, model={}, categories={}",
                useBridge ? "bridge" : "agent loop",
                providerName, context.model(),
                request.categories() != null ? request.categories().size() : 0);

            AgentLoopResult result = useBridge
                ? bridgeDispatcher.execute(context)
                : agentLoopService.execute(context, null);

            return parseResponse(result, System.currentTimeMillis() - startTime, providerName,
                SYSTEM_PROMPT, userPrompt, result.conversationHistory());

        } catch (BridgeAccessDeniedException e) {
            // Propagate so GlobalExceptionHandler maps reason → 403/429. Must come
            // before the Exception catch, which would otherwise squash the denial
            // into a generic 200/FAILED response body.
            log.warn("Classify denied by bridge guard: provider={} reason={}",
                e.getProviderName(), e.getReason());
            throw e;
        } catch (Exception e) {
            log.error("Classification failed: {}", e.getMessage(), e);
            return new ClassifyResponseDto(false, null, 0, null,
                "Classification error: " + e.getMessage(),
                System.currentTimeMillis() - startTime, providerName, null, 0, 0, 0,
                null, null, userPrompt);
        }
    }

    private String buildPrompt(ClassifyRequestDto request) {
        StringBuilder sb = new StringBuilder();
        // Use prompt as the primary classification instruction (may already include the content
        // via resolved templates). Only fall back to content if prompt is absent.
        if (request.prompt() != null && !request.prompt().isBlank()) {
            sb.append("## Classification Instruction\n").append(request.prompt()).append("\n\n");
        } else if (request.content() != null && !request.content().isBlank()) {
            sb.append("## Content to Classify\n").append(request.content()).append("\n\n");
        }
        sb.append("## Available Categories\n");
        if (request.categories() != null) {
            for (ClassifyRequestDto.CategoryDto category : request.categories()) {
                sb.append("- **").append(category.label()).append("**: ");
                sb.append(category.description() != null ? category.description() : "No description");
                sb.append("\n");
            }
        }
        sb.append("\nClassify the content into ONE of the above categories.");
        return sb.toString();
    }

    private ClassifyResponseDto parseResponse(AgentLoopResult result, long duration, String provider,
                                                String systemPrompt, String userPrompt,
                                                List<Message> conversationHistory) {
        String content = result.content();
        UsageInfo usage = result.usage();
        int tokensUsed = usage != null ? usage.getTotal() : 0;
        int promptTokens = usage != null && usage.promptTokens() != null ? usage.promptTokens() : 0;
        int completionTokens = usage != null && usage.completionTokens() != null ? usage.completionTokens() : 0;
        String model = result.model();
        List<ConversationMessageDto> messages = toConversationMessages(conversationHistory);

        if (!result.success()) {
            return new ClassifyResponseDto(false, null, 0, null,
                result.error(), duration, provider, model, tokensUsed, promptTokens, completionTokens,
                systemPrompt, messages, userPrompt);
        }

        if (content == null || content.isBlank()) {
            return new ClassifyResponseDto(false, null, 0, null,
                "Empty response from LLM", duration, provider, model, tokensUsed, promptTokens, completionTokens,
                systemPrompt, messages, userPrompt);
        }
        try {
            String jsonContent = LlmJsonExtractor.extractJson(content);
            Map<String, Object> parsed = objectMapper.readValue(jsonContent, new TypeReference<>() {});
            String selectedCategory = (String) parsed.get("selected_category");
            Number confidenceNum = (Number) parsed.get("confidence");
            String reasoning = (String) parsed.get("reasoning");
            if (selectedCategory == null || selectedCategory.isBlank()) {
                return new ClassifyResponseDto(false, null, 0, null,
                    "No category selected in response", duration, provider, model,
                    tokensUsed, promptTokens, completionTokens, systemPrompt, messages, userPrompt);
            }
            double confidence = confidenceNum != null ? confidenceNum.doubleValue() : 0.5;
            confidence = Math.max(0.0, Math.min(1.0, confidence));
            return new ClassifyResponseDto(true, selectedCategory, confidence, reasoning,
                null, duration, provider, model, tokensUsed, promptTokens, completionTokens,
                systemPrompt, messages, userPrompt);
        } catch (Exception e) {
            log.warn("Failed to parse classify response as JSON, trying plain text: {}", e.getMessage());
            return parseFromPlainText(content, duration, provider, model,
                tokensUsed, promptTokens, completionTokens, systemPrompt, userPrompt, messages);
        }
    }

    private ClassifyResponseDto parseFromPlainText(String content, long duration,
                                                     String provider, String model,
                                                     int tokensUsed, int promptTokens,
                                                     int completionTokens,
                                                     String systemPrompt, String userPrompt,
                                                     List<ConversationMessageDto> messages) {
        Pattern pattern = Pattern.compile(
            "(?:category|selected|classification)[:\\s]+[\"']?([\\w\\s-]+)[\"']?",
            Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(content);
        if (matcher.find()) {
            String category = matcher.group(1).trim();
            return new ClassifyResponseDto(true, category, 0.5,
                "Extracted from plain text response", null, duration, provider, model,
                tokensUsed, promptTokens, completionTokens, systemPrompt, messages, userPrompt);
        }
        return new ClassifyResponseDto(false, null, 0, null,
            "Could not parse classification response", duration, provider, model,
            tokensUsed, promptTokens, completionTokens, systemPrompt, messages, userPrompt);
    }

    /**
     * Convert agent loop conversation history to lightweight DTOs for transport.
     */
    static List<ConversationMessageDto> toConversationMessages(List<Message> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        return history.stream()
            .map(m -> new ConversationMessageDto(
                m.role() != null ? m.role().name() : "USER",
                m.content(),
                m.toolCallId(),
                m.toolName()))
            .toList();
    }
}
