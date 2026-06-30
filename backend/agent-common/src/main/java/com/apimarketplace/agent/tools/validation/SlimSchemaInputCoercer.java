package com.apimarketplace.agent.tools.validation;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stage 4a.1b - reverse coercion for slim-schema tool calls.
 *
 * <p><b>Why.</b> In slim mode ({@link com.apimarketplace.agent.domain.ToolDefinition}
 * with every param {@code type = "string"}) the LLM is told to pass every
 * argument as a string. But the underlying tool still expects real types -
 * {@code limit} is a {@code Long}, {@code rows} is a {@code List}, {@code filter}
 * is a {@code Map}. Without a coercion step, {@link ToolParameterValidator}
 * would reject the string {@code "10"} for an {@code integer} param and the
 * LLM would spiral into retries.
 *
 * <p><b>Placement.</b> Runs in {@code ToolsRegistrationService.executeTool()}
 * between {@link ToolParameterValidator#applyParameterAliases(Map)} and
 * {@link ToolParameterValidator#validate(String, Map)} so the validator sees
 * real-typed values. A best-effort failure (malformed JSON, un-parseable
 * number) leaves the value unchanged; the validator then returns a structured
 * error (Stage 4a.5) and the LLM calls {@code help} to recover.
 *
 * <p><b>Per-type recovery</b>:
 * <ul>
 *   <li>{@code integer} / {@code int} → {@link Long#parseLong(String)} after
 *       trimming; empty/blank passes through untouched.</li>
 *   <li>{@code number} → {@link Double#parseDouble(String)} after trimming.</li>
 *   <li>{@code boolean} → {@link Boolean#parseBoolean(String)} after trimming.
 *       Empty/blank passes through <em>unchanged</em> (as a String) - matching
 *       {@code integer}/{@code number} so the validator sees the original raw
 *       value and a required-param check can distinguish "missing" from
 *       {@code false}. Silent coercion of empty to {@code false} would mask
 *       missing required inputs.</li>
 *   <li>{@code array} → try JSON first ({@code [1,2,3]}), fall back to
 *       CSV ({@code 1,2,3}) → {@code List<String>}. Empty/blank → empty list.</li>
 *   <li>{@code object} → {@link ObjectMapper#readValue(String, Class)} into
 *       {@code Map}. Empty/blank → empty map.</li>
 *   <li>{@code string} or unknown type → value passed through unchanged.</li>
 * </ul>
 *
 * <p><b>Tolerance rules</b>: if the incoming value is not a {@link String}
 * (e.g. the LLM already sent a real number), the coercer is a no-op - the
 * validator will still enforce type. {@code null} values inside the map are
 * left as {@code null}; missing keys are not added.
 *
 * <p><b>Bail-out</b>: nested arrays-of-objects and polymorphic/oneOf schemas
 * are not attempted here. The spec requires those actions be added to the
 * full-schema exclusion list at slim time (Stage 4a.3) so the LLM never
 * receives a slim type hint for a structure the coercer can't round-trip.
 * If such a value arrives anyway (misconfiguration), the coercer's best-effort
 * attempt either succeeds (Jackson is flexible) or leaves the string in place
 * for the validator to reject.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlimSchemaInputCoercer {

    private final AgentToolRegistry registry;
    private final ObjectMapper objectMapper;

    /**
     * Return a new map with string-typed values coerced to the types declared
     * by {@code toolName}'s real (non-slim) {@link AgentToolDefinition}.
     * Unknown tools are a no-op (no registry entry → nothing to coerce
     * against). {@code null}/empty input maps pass through unchanged.
     */
    public Map<String, Object> coerce(String toolName, Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return params;
        }
        Optional<AgentToolDefinition> toolOpt = registry.getToolByName(toolName);
        if (toolOpt.isEmpty()) {
            return params;
        }
        List<ToolParameter> defs = toolOpt.get().parameters();
        if (defs == null || defs.isEmpty()) {
            return params;
        }
        Map<String, ToolParameter> byName = new HashMap<>(defs.size());
        for (ToolParameter def : defs) {
            if (def.name() != null) {
                byName.put(def.name(), def);
            }
        }

        Map<String, Object> coerced = new HashMap<>(params);
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            ToolParameter def = byName.get(entry.getKey());
            if (def == null) {
                continue;
            }
            Object coercedValue = coerceOne(def, entry.getValue());
            coerced.put(entry.getKey(), coercedValue);
        }
        return coerced;
    }

    private Object coerceOne(ToolParameter def, Object rawValue) {
        if (rawValue == null || def.type() == null) {
            return rawValue;
        }
        if (!(rawValue instanceof String s)) {
            // Real-typed value already - validator enforces type match.
            return rawValue;
        }
        String trimmed = s.trim();
        String type = def.type().toLowerCase();
        try {
            return switch (type) {
                case "string" -> rawValue;
                case "integer", "int" -> trimmed.isEmpty() ? rawValue : Long.parseLong(trimmed);
                case "number" -> trimmed.isEmpty() ? rawValue : Double.parseDouble(trimmed);
                case "boolean" -> trimmed.isEmpty() ? rawValue : Boolean.parseBoolean(trimmed);
                case "array" -> coerceArray(trimmed);
                case "object" -> coerceObject(trimmed);
                default -> rawValue;
            };
        } catch (NumberFormatException | JsonProcessingException e) {
            log.debug("Slim-coerce failed for param '{}' (type={}, value='{}'): {} - leaving as-is",
                    def.name(), type, s, e.getMessage());
            return rawValue;
        }
    }

    private List<?> coerceArray(String s) throws JsonProcessingException {
        if (s.isEmpty()) {
            return List.of();
        }
        if (s.startsWith("[")) {
            return objectMapper.readValue(s, List.class);
        }
        // Fallback: comma-separated list. Trim each item; drop empty slots
        // so "a,,b" yields ["a", "b"] instead of ["a", "", "b"].
        String[] parts = s.split(",");
        List<String> items = new ArrayList<>(parts.length);
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty()) {
                items.add(t);
            }
        }
        return items;
    }

    private Map<?, ?> coerceObject(String s) throws JsonProcessingException {
        if (s.isEmpty()) {
            return Map.of();
        }
        return objectMapper.readValue(s, Map.class);
    }
}
