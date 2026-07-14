package com.apimarketplace.publication.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publish-time validation failure with a STRUCTURED, machine-readable body.
 *
 * <p>Thrown when a publication request is well-formed but not publishable as-is
 * (as opposed to {@link IllegalArgumentException} = malformed request). Mapped
 * to HTTP 422 by the controllers; the body always carries {@code error} (stable
 * code), {@code message} (human-readable summary) plus code-specific detail
 * fields so every surface (modal, MCP tool, API caller) can render an
 * actionable explanation instead of an opaque string:
 *
 * <ul>
 *   <li>{@link #AGENT_ALL_ACCESS_NOT_PUBLISHABLE} - the published agent (or a
 *       sub-agent of its closure) has a per-family grant of {@code "all"}.
 *       Details: {@code violations[]} of
 *       {@code {agentId, agentName, root, referencedVia?, families[]}}.</li>
 *   <li>{@link #AGENT_SNAPSHOT_TOO_LARGE} - the built snapshot exceeds a size
 *       cap (total serialized bytes, or rows of a single table). Details:
 *       {@code sizeBytes?/maxBytes?} and {@code breakdown[]} of
 *       {@code {type, id, name?, items?, approxBytes?}} sorted heaviest-first.</li>
 * </ul>
 */
public class PublicationValidationException extends RuntimeException {

    public static final String AGENT_ALL_ACCESS_NOT_PUBLISHABLE = "AGENT_ALL_ACCESS_NOT_PUBLISHABLE";
    public static final String AGENT_SNAPSHOT_TOO_LARGE = "AGENT_SNAPSHOT_TOO_LARGE";

    private final String errorCode;
    private final Map<String, Object> details;

    public PublicationValidationException(String errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details != null ? details : Map.of();
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    /** Full HTTP response body: {@code {error, message, ...details}}. */
    public Map<String, Object> toBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", errorCode);
        body.put("message", getMessage());
        body.putAll(details);
        return body;
    }
}
