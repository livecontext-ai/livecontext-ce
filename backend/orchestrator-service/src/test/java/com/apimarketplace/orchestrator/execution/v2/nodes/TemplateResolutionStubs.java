package com.apimarketplace.orchestrator.execution.v2.nodes;

import org.mockito.stubbing.Answer;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stubs {@code V2TemplateAdapter.resolveTemplates} the way the real adapter behaves, without
 * depending on the private key a node wraps its expression under.
 *
 * <p>Every String value found in {@code byTemplate} is replaced by its mapping (which may be
 * {@code null} for a reference to nothing, or a Map / List for a structured value), maps and lists
 * are walked recursively, and anything else is returned unchanged.
 */
final class TemplateResolutionStubs {

    private TemplateResolutionStubs() {
    }

    static Answer<Map<String, Object>> resolving(Map<String, Object> byTemplate) {
        return invocation -> {
            Map<String, Object> input = invocation.getArgument(0);
            Map<String, Object> out = new HashMap<>();
            input.forEach((k, v) -> out.put(k, resolve(v, byTemplate)));
            return out;
        };
    }

    /**
     * Resolves EVERY entry of the map the node passes to {@code value}, whatever key the node
     * wraps its expression under. The drop-in for a {@code thenReturn(Map.of("__expr__", value))}
     * stub written when each node chose its own key.
     */
    static Answer<Map<String, Object>> every(Object value) {
        return invocation -> {
            Map<String, Object> input = invocation.getArgument(0);
            Map<String, Object> out = new HashMap<>();
            input.keySet().forEach(k -> out.put(k, value));
            return out;
        };
    }

    /**
     * Every entry whose value is text containing {@code {{} resolves to {@code value}; every other
     * entry (a literal, a default filename) comes back unchanged, as the real adapter would return it.
     */
    static Answer<Map<String, Object>> templatesResolveTo(Object value) {
        return invocation -> {
            Map<String, Object> in = invocation.getArgument(0);
            Map<String, Object> out = new HashMap<>();
            in.forEach((key, v) -> out.put(key, v instanceof String s && s.contains("{{") ? value : v));
            return out;
        };
    }

    @SuppressWarnings("unchecked")
    private static Object resolve(Object value, Map<String, Object> byTemplate) {
        if (value instanceof String s && byTemplate.containsKey(s)) {
            return byTemplate.get(s);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) map).forEach((k, v) -> out.put(k, resolve(v, byTemplate)));
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(v -> resolve(v, byTemplate)).toList();
        }
        return value;
    }
}
