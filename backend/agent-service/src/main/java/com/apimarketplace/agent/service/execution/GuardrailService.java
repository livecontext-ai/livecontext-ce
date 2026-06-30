package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.bridge.BridgeAccessDeniedException;
import com.apimarketplace.agent.client.dto.execution.ConversationMessageDto;
import com.apimarketplace.agent.client.dto.execution.GuardrailRequestDto;
import com.apimarketplace.agent.client.dto.execution.GuardrailResponseDto;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Guardrail validation - delegates to {@link AgentLoopService} in single-shot mode
 * (no tools, 1 iteration) so that budget guards, token tracking, and observability
 * are centralized with the general agent pipeline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GuardrailService {

    static final String SYSTEM_PROMPT = """
        You are a content moderation assistant. Your ONLY task is to evaluate content \
        against specified safety rules.

        OUTPUT FORMAT - MANDATORY:
        You MUST respond with EXACTLY one raw JSON object. Nothing else.
        No preamble, no explanation, no markdown fences, no trailing text.
        Any output that is not a single valid JSON object is a fatal error.

        JSON schema (strict):
        {
          "passed": true/false,
          "violations": ["rule_id_1", "rule_id_2"],
          "details": {
            "rule_id_1": {
              "violated": true/false,
              "severity": "low/medium/high/critical",
              "explanation": "Why this rule was violated",
              "matched_content": "The specific content that violated the rule"
            }
          },
          "sanitized": "Content with violations [REDACTED]"
        }

        Constraints:
        1. passed MUST be true ONLY if NO rules are violated
        2. violations MUST list rule IDs that were violated (empty array if none)
        3. details MUST include evaluation info for ALL rules checked
        4. sanitized MUST contain the content with violations replaced by [REDACTED]
        5. Output MUST start with { and end with } - no other characters allowed
        """;

    private final AgentLoopService agentLoopService;
    private final GuardChainFactory guardChainFactory;
    private final ObjectMapper objectMapper;
    private final BridgeLoopDispatcher bridgeDispatcher;
    private final com.apimarketplace.agent.service.ModelCatalogService modelCatalogService;

    /**
     * Back-compat overload - async paths (queue worker) call without an inbound
     * role context. Falls through to {@link #execute(GuardrailRequestDto, String)}
     * with null roles; downstream the bridge guard treats null as USER.
     */
    public GuardrailResponseDto execute(GuardrailRequestDto request) {
        return execute(request, null);
    }

    public GuardrailResponseDto execute(GuardrailRequestDto request, String userRoles) {
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
                .temperature(request.temperature() != null ? request.temperature() : 0.0)
                .maxTokens(request.maxTokens() != null ? request.maxTokens() : 1000)
                .tenantId(request.tenantId())
                .userRoles(userRoles)
                .agentId(request.agentEntityId())
                .preIterationGuard(guard)
                .purpose(CallPurpose.GUARDRAIL)
                .build();

            boolean useBridge = bridgeDispatcher.shouldDispatch(providerName);
            log.info("Executing guardrail via {}: provider={}, model={}, rules={}",
                useBridge ? "bridge" : "agent loop",
                providerName, context.model(),
                request.rules() != null ? request.rules().size() : 0);

            AgentLoopResult result = useBridge
                ? bridgeDispatcher.execute(context)
                : agentLoopService.execute(context, null);

            return parseResponse(result, request, System.currentTimeMillis() - startTime, providerName,
                SYSTEM_PROMPT, userPrompt, result.conversationHistory());

        } catch (BridgeAccessDeniedException e) {
            // Propagate so GlobalExceptionHandler maps reason → 403/429. Must come
            // before the Exception catch, which would otherwise squash the denial.
            log.warn("Guardrail denied by bridge guard: provider={} reason={}",
                e.getProviderName(), e.getReason());
            throw e;
        } catch (Exception e) {
            log.error("Guardrail validation failed: {}", e.getMessage(), e);
            return new GuardrailResponseDto(false, false, List.of(), Map.of(), null,
                "Guardrail error: " + e.getMessage(),
                System.currentTimeMillis() - startTime, providerName, null, 0, 0, 0,
                null, null, userPrompt);
        }
    }

    private String buildPrompt(GuardrailRequestDto request) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Content to Validate\n");
        sb.append("```\n").append(request.content()).append("\n```\n\n");
        if (request.prompt() != null && !request.prompt().isBlank()) {
            sb.append("## Additional Instructions\n").append(request.prompt()).append("\n\n");
        }
        sb.append("## Rules to Check\n");
        if (request.rules() != null) {
            for (GuardrailRequestDto.RuleDto rule : request.rules()) {
                sb.append("- **").append(rule.id()).append("**: ");
                sb.append(rule.description() != null ? rule.description() : "Check for this issue");
                sb.append("\n");
            }
        }
        String action = request.action() != null ? request.action() : "flag";
        sb.append("\n## Action Mode\nAction: **").append(action).append("**\n");
        switch (action.toLowerCase()) {
            case "block" -> sb.append("If any rule is violated, the content should be blocked entirely.\n");
            case "redact" -> sb.append("Redact (replace with [REDACTED]) any content that violates rules.\n");
            default -> sb.append("Flag violations but do not modify content.\n");
        }
        sb.append("\nEvaluate ALL rules and provide the complete analysis.");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private GuardrailResponseDto parseResponse(AgentLoopResult result,
                                                 GuardrailRequestDto request,
                                                 long duration, String provider,
                                                 String systemPrompt, String userPrompt,
                                                 List<Message> conversationHistory) {
        String content = result.content();
        UsageInfo usage = result.usage();
        int tokensUsed = usage != null ? usage.getTotal() : 0;
        int promptTokens = usage != null && usage.promptTokens() != null ? usage.promptTokens() : 0;
        int completionTokens = usage != null && usage.completionTokens() != null ? usage.completionTokens() : 0;
        String model = result.model();
        List<ConversationMessageDto> messages = ClassifyService.toConversationMessages(conversationHistory);

        if (!result.success()) {
            return new GuardrailResponseDto(false, false, List.of(), Map.of(), null,
                result.error(), duration, provider, model, tokensUsed, promptTokens, completionTokens,
                systemPrompt, messages, userPrompt);
        }

        if (content == null || content.isBlank()) {
            return new GuardrailResponseDto(false, false, List.of(), Map.of(), null,
                "Empty response from LLM", duration, provider, model, tokensUsed, promptTokens, completionTokens,
                systemPrompt, messages, userPrompt);
        }
        try {
            String jsonContent = LlmJsonExtractor.extractJson(content);
            Map<String, Object> parsed = objectMapper.readValue(jsonContent, new TypeReference<>() {});
            Boolean passed = (Boolean) parsed.get("passed");
            List<String> violations = (List<String>) parsed.getOrDefault("violations", List.of());
            Map<String, Object> details = (Map<String, Object>) parsed.getOrDefault("details", Map.of());
            String sanitized = (String) parsed.get("sanitized");
            if (passed == null) {
                passed = violations == null || violations.isEmpty();
            }
            if (sanitized == null && "redact".equalsIgnoreCase(request.action())) {
                sanitized = passed ? request.content() : "[CONTENT BLOCKED]";
            }
            return new GuardrailResponseDto(true, passed,
                violations != null ? violations : List.of(),
                details != null ? details : Map.of(),
                sanitized, null, duration, provider, model,
                tokensUsed, promptTokens, completionTokens, systemPrompt, messages, userPrompt);
        } catch (Exception e) {
            log.warn("Failed to parse guardrail response as JSON: {}", e.getMessage());
            return parseFromPlainText(content, request, duration, provider, model,
                tokensUsed, promptTokens, completionTokens, systemPrompt, userPrompt, messages);
        }
    }

    private GuardrailResponseDto parseFromPlainText(String content, GuardrailRequestDto request,
                                                      long duration, String provider, String model,
                                                      int tokensUsed, int promptTokens,
                                                      int completionTokens,
                                                      String systemPrompt, String userPrompt,
                                                      List<ConversationMessageDto> messages) {
        String lowerContent = content.toLowerCase();
        boolean passed = !lowerContent.contains("violat") &&
                         !lowerContent.contains("fail") &&
                         !lowerContent.contains("block") &&
                         !lowerContent.contains("reject");
        List<String> violations = new ArrayList<>();
        if (!passed && request.rules() != null) {
            for (GuardrailRequestDto.RuleDto rule : request.rules()) {
                if (lowerContent.contains(rule.id().toLowerCase())) {
                    violations.add(rule.id());
                }
            }
        }
        return new GuardrailResponseDto(true, passed, violations, Map.of(),
            passed ? request.content() : "[CONTENT FLAGGED]",
            null, duration, provider, model, tokensUsed, promptTokens, completionTokens,
            systemPrompt, messages, userPrompt);
    }
}
