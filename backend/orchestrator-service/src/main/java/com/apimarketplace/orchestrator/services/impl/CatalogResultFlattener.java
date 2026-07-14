package com.apimarketplace.orchestrator.services.impl;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared mapping from a catalog-service execution response ({@code success, result,
 * metadata}) to the orchestrator's flat node-output map. Extracted from
 * {@code CatalogToolsGateway.executeTool} so the per-node mock path
 * ({@code CatalogMockClient}) produces a byte-identically shaped output: same
 * canonicalization ({@link McpResultNormalizer}), same flattening, same envelope
 * keys ({@code tool_id, execution, metadata, message, http_status}).
 */
final class CatalogResultFlattener {

    private CatalogResultFlattener() {}

    /**
     * Builds the node output map from a catalog execution result.
     *
     * @param toolId     the tool id as referenced by the workflow step
     * @param rawResult  the {@code result} field of the catalog response
     * @param metadata   the {@code metadata} field of the catalog response
     * @param httpStatus resolved HTTP status code (nullable)
     * @param message    the {@code message} envelope value
     */
    static Map<String, Object> flatten(String toolId,
                                       Object rawResult,
                                       Map<String, Object> metadata,
                                       Integer httpStatus,
                                       String message) {
        Map<String, Object> output = new HashMap<>();
        output.put("tool_id", toolId);
        output.put("execution", true);

        // Normalize the tool result into its canonical single-level shape
        // BEFORE flattening, so {{mcp:label.output.<field>}} resolves the same
        // way for API typed-execution AND bridge (REMOTE_MCP) tools. Without
        // this, a redundant `output` wrapper (mis-schematized API tools) or
        // the MCP JSON-RPC content/structuredContent envelope (bridge) leaks a
        // double `output.output.<field>` path that downstream nodes mis-read.
        Object result = McpResultNormalizer.canonicalize(rawResult);
        if (result instanceof Map<?, ?> resultMap) {
            for (Map.Entry<?, ?> entry : resultMap.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    output.put(key, entry.getValue());
                }
            }
            // Back-compat (DEPRECATED): when we unwrapped a redundant single
            // `output` wrapper, also keep the legacy nested form addressable so
            // workflows authored against `.output.output.<field>` keep
            // resolving during the deprecation window. New workflows should use
            // the canonical `.output.<field>`. Remove this alias after N releases.
            if (result != rawResult && rawResult instanceof Map<?, ?> rawMap
                    && rawMap.size() == 1 && rawMap.get("output") instanceof Map<?, ?> legacy) {
                output.put("output", legacy);
            }
        } else if (result != null) {
            output.put("result", result);
        }

        output.put("metadata", metadata);
        output.put("message", message);

        if (httpStatus != null) {
            output.put("http_status", httpStatus);
        }
        return output;
    }
}
