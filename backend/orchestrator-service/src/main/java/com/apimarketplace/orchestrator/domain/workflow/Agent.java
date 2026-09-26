package com.apimarketplace.orchestrator.domain.workflow;

import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * AI node in the workflow.
 *
 * <p>Covers every node the builder files under AI, which is not only the
 * conversational agent: {@code agent}, {@code browser_agent}, {@code classify},
 * {@code guardrail} and {@code generate} all live here and all key off the
 * {@code agent:} prefix. What separates them is the execution node they are
 * turned into, not the array they are stored in.
 *
 * <p>{@code generate} is the one that uses none of the LLM fields below: its
 * whole configuration (the generation model id, the unified parameters, which
 * key pays) travels in {@code params}, because those are decided by the
 * generation catalog rather than by this record. The LLM defaults the compact
 * constructor fills in are inert for it.
 */
public record Agent(
        String id,
        String type,  // "agent", "browser_agent", "guardrail", "classify", "generate"
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
        String graphNodeId,
        // temperature / maxTokens / maxIterations / maxTools written as {{...}} templates, by field
        // name. The typed fields fall back to their defaults (0.7 / 4096 / 10 / 5) and the node
        // resolves these at run time. Never serialized: the raw plan stays the source of truth.
        @com.fasterxml.jackson.annotation.JsonIgnore
        Map<String, String> deferredScalars) {

    /** The constructor before {@link #deferredScalars}: an agent with no templated number. */
    public Agent(String id, String type, String label, String agentConfigId, Boolean withMemory,
                 String provider, String model, String systemPrompt, String prompt,
                 Double temperature, Integer maxTokens, Integer maxIterations, Integer maxTools,
                 List<String> tools, String parentLoopId, Map<String, Object> params,
                 List<Map<String, Object>> classifyCategories, String classifyParams,
                 List<Map<String, Object>> guardrailRules, String guardrailParams, String graphNodeId) {
        this(id, type, label, agentConfigId, withMemory, provider, model, systemPrompt, prompt,
            temperature, maxTokens, maxIterations, maxTools, tools, parentLoopId, params,
            classifyCategories, classifyParams, guardrailRules, guardrailParams, graphNodeId, Map.of());
    }

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
        deferredScalars = deferredScalars == null ? Map.of() : Map.copyOf(deferredScalars);
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
                        graphNodeId, deferredScalars);
    }

    /** This agent with its numeric settings as resolved for one execution, nothing left deferred. */
    public Agent withNumbers(Double newTemperature, Integer newMaxTokens, Integer newMaxIterations, Integer newMaxTools) {
        return new Agent(id, type, label, agentConfigId, withMemory,
                        provider, model, systemPrompt, prompt,
                        newTemperature, newMaxTokens, newMaxIterations, newMaxTools,
                        tools, parentLoopId, params,
                        classifyCategories, classifyParams, guardrailRules, guardrailParams,
                        graphNodeId, Map.of());
    }
}
