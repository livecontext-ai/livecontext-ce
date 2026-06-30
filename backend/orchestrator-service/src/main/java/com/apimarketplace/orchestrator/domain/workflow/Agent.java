package com.apimarketplace.orchestrator.domain.workflow;

import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * AI Agent node in the workflow.
 * Represents an autonomous agent that uses LLM with tool-calling capabilities.
 */
public record Agent(
        String id,
        String type,  // "agent", "guardrail", "classify"
        String label,
        String agentConfigId,   // UUID of AgentEntity (for type="agent" only)
        Boolean withMemory,      // Use agent conversation as context (default: true)
        String provider,
        String model,
        String systemPrompt,
        String prompt,
        Double temperature,
        Integer maxTokens,
        Integer maxIterations,
        Integer maxTools,
        List<String> tools,
        String parentLoopId,
        Map<String, Object> params,
        // Classify-specific fields
        List<Map<String, Object>> classifyCategories,
        String classifyParams,
        // Guardrail-specific fields
        List<Map<String, Object>> guardrailRules,
        String guardrailParams,
        String graphNodeId) {

    public Agent {
        id = normalizeNullable(id);
        type = type != null ? type.trim().toLowerCase(Locale.ROOT) : "agent";
        label = normalizeMandatory(label, "agent label");
        agentConfigId = normalizeNullable(agentConfigId);
        withMemory = withMemory != null ? withMemory : true;
        provider = provider != null ? provider.trim() : null;
        model = model != null ? model.trim() : null;
        systemPrompt = systemPrompt != null ? systemPrompt.trim() : null;
        prompt = prompt != null ? prompt.trim() : null;
        temperature = temperature != null ? temperature : 0.7;
        maxTokens = maxTokens != null ? maxTokens : 4096;
        maxIterations = maxIterations != null ? maxIterations : 10;
        maxTools = maxTools != null ? maxTools : 5;
        tools = tools == null
            ? List.of()
            : Collections.unmodifiableList(
                tools.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList())
            );
        parentLoopId = normalizeNullable(parentLoopId);
        params = params == null ? Map.of() : Map.copyOf(params);
        // Classify-specific fields
        classifyCategories = classifyCategories == null ? List.of() : List.copyOf(classifyCategories);
        classifyParams = classifyParams != null ? classifyParams.trim() : null;
        // Guardrail-specific fields
        guardrailRules = guardrailRules == null ? List.of() : List.copyOf(guardrailRules);
        guardrailParams = guardrailParams != null ? guardrailParams.trim() : null;
    }

    public String normalizedLabel() {
        return LabelNormalizer.normalizeLabel(label);
    }

    public String getNormalizedKey() {
        String normalized = LabelNormalizer.normalizeLabel(label);
        return normalized != null ? "agent:" + normalized : null;
    }

    private static String normalizeMandatory(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException("Missing " + field);
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Empty " + field);
        }
        return trimmed;
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    public Agent withParams(Map<String, Object> newParams) {
        return new Agent(id, type, label, agentConfigId, withMemory,
                        provider, model, systemPrompt, prompt,
                        temperature, maxTokens, maxIterations, maxTools,
                        tools, parentLoopId, newParams,
                        classifyCategories, classifyParams, guardrailRules, guardrailParams,
                        graphNodeId);
    }
}
