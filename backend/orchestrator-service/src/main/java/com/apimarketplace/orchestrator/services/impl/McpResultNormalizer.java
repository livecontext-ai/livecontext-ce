package com.apimarketplace.orchestrator.services.impl;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * Normalizes a tool-execution {@code result} payload into its canonical
 * single-level shape BEFORE {@link CatalogToolsGateway} flattens it into the
 * node {@code output}.
 *
 * <p><b>Why.</b> The workflow runtime addresses a node's result as
 * {@code {{mcp:label.output.<field>}}}: {@code ExecutionContext} wraps the
 * gateway output once under {@code output}, and the gateway flattens the tool
 * result's top-level keys into that same map. The canonical, deterministic
 * path is therefore {@code label.output.<field>}.
 *
 * <p>Two producers break that invariant by handing the gateway an extra
 * envelope layer, yielding the infamous double {@code label.output.output.<field>}:
 * <ul>
 *   <li><b>API typed-execution</b> - a tool whose {@code output_schema} declares
 *       a redundant root field literally named {@code output} (common in
 *       serpapi/scrapingbee-style imports that mirror the upstream {@code output}
 *       key). {@code result = {output:{...}}}.</li>
 *   <li><b>Bridge (REMOTE_MCP)</b> - the MCP JSON-RPC {@code tools/call} envelope
 *       always wraps the payload as {@code {content:[{type:"text",text:"{...}"}],
 *       structuredContent:{...}}}. Passed through verbatim, this flattens to
 *       {@code output.content} / {@code output.structuredContent} instead of the
 *       real fields.</li>
 * </ul>
 *
 * <p>{@link #canonicalize(Object)} unwraps exactly one such redundant layer so
 * both modes converge on {@code label.output.<field>}. It is intentionally
 * conservative: anything that is not one of the two recognized envelopes is
 * returned unchanged.
 */
final class McpResultNormalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpResultNormalizer() {
    }

    /**
     * Return the canonical single-level payload for {@code result}, unwrapping at
     * most one redundant envelope. Returns the argument unchanged when it is not
     * a recognized envelope (so callers can detect "did we unwrap?" via identity).
     */
    static Object canonicalize(Object result) {
        if (!(result instanceof Map<?, ?> m) || m.isEmpty()) {
            return result;
        }

        // 1) Bridge MCP JSON-RPC tools/call envelope.
        //    structuredContent is the typed payload and takes precedence.
        Object structured = m.get("structuredContent");
        if (structured instanceof Map<?, ?> sc && !sc.isEmpty()) {
            return sc;
        }
        if (m.containsKey("content")) {
            Object unwrapped = unwrapMcpContent(m.get("content"));
            if (unwrapped != null) {
                return unwrapped;
            }
        }

        // 2) Redundant single {output:{...}} wrapper (mis-schematized API tools
        //    and some REMOTE_MCP responses). Only unwrap when it is the SOLE key,
        //    so a legitimate result that merely contains an `output` field
        //    alongside others is left intact.
        if (m.size() == 1 && m.get("output") instanceof Map<?, ?> inner) {
            return inner;
        }

        return result;
    }

    /**
     * Parse the first usable MCP {@code content} block. Text blocks holding a JSON
     * object/array are decoded into a Map/List; a plain text block is surfaced as
     * {@code {text:<value>}}. Returns null when no usable block is found (caller
     * then leaves the original envelope untouched).
     */
    private static Object unwrapMcpContent(Object content) {
        if (!(content instanceof List<?> blocks) || blocks.isEmpty()) {
            return null;
        }
        for (Object block : blocks) {
            if (!(block instanceof Map<?, ?> b)) {
                continue;
            }
            Object type = b.get("type");
            if (type != null && !"text".equals(type)) {
                continue;
            }
            Object text = b.get("text");
            if (text instanceof String s && !s.isBlank()) {
                String trimmed = s.trim();
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    try {
                        return MAPPER.readValue(trimmed, Object.class);
                    } catch (Exception ignore) {
                        // Not valid JSON after all - fall through to plain-text shape.
                    }
                }
                return Map.of("text", s);
            }
        }
        return null;
    }
}
