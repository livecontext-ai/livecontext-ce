package com.apimarketplace.publication.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Client-side view of publication-service's structured 422 publish refusal
 * ({@code {error, message, ...details}} body - e.g. grant=all violations or a
 * snapshot size cap). Lets callers (notably the MCP publish tool) render an
 * actionable message from {@link #getBody()} instead of an opaque HTTP string.
 */
public class PublicationValidationException extends RuntimeException {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String errorCode;
    private final transient Map<String, Object> body;

    public PublicationValidationException(String errorCode, String message, Map<String, Object> body, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.body = body != null ? body : Map.of();
    }

    /** Stable error code from the response body's {@code error} field (may be null). */
    public String getErrorCode() {
        return errorCode;
    }

    /** The full parsed response body: {@code error}, {@code message} + detail fields. */
    public Map<String, Object> getBody() {
        return body;
    }

    /**
     * Parse a 422 response body into a typed exception. Falls back to the raw
     * body string as message when the body is not the expected JSON shape.
     */
    public static PublicationValidationException fromResponseBody(String rawBody, Throwable cause) {
        try {
            Map<String, Object> parsed = MAPPER.readValue(rawBody, new TypeReference<Map<String, Object>>() {});
            Object code = parsed.get("error");
            Object message = parsed.get("message");
            return new PublicationValidationException(
                    code != null ? code.toString() : null,
                    message != null ? message.toString() : rawBody,
                    parsed, cause);
        } catch (Exception parseError) {
            return new PublicationValidationException(null, rawBody, Map.of(), cause);
        }
    }
}
