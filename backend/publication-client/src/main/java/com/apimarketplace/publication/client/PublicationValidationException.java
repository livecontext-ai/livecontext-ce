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

    /** True for {@code TABLE_COPY_FAILED}: a transient failure, the same publish can be retried. */
    public boolean isRetryable() {
        return Boolean.TRUE.equals(body.get("retryable"));
    }

    /**
     * For an MCP publish tool, which appends its OWN fix (tool actions): the refusal's
     * {@code reason} (the message without the share-modal fix sentence, else the message), and
     * for a size refusal ({@code sizeBytes}) the heaviest resources it listed.
     */
    public String reasonForAgent() {
        Object reason = body.get("reason");
        StringBuilder sb = new StringBuilder(reason != null ? reason.toString() : String.valueOf(getMessage()));
        if (body.get("sizeBytes") != null && body.get("breakdown") instanceof java.util.List<?> breakdown
                && !breakdown.isEmpty()) {
            sb.append(" Heaviest: ");
            boolean first = true;
            for (Object raw : breakdown) {
                if (!(raw instanceof Map<?, ?> b)) continue;
                if (!first) sb.append(", ");
                first = false;
                Object name = b.get("name") != null ? b.get("name") : b.get("id");
                sb.append(b.get("type")).append(" \"").append(name).append('"');
                if (b.get("items") != null) sb.append(" (").append(b.get("items")).append(" rows)");
            }
            sb.append('.');
        }
        return sb.toString();
    }

    /** The id of the table a row-limit refusal names ({@code maxTableRows} + breakdown), else null. */
    public String oversizedTableId() {
        if (body.get("maxTableRows") == null || !(body.get("breakdown") instanceof java.util.List<?> breakdown)
                || breakdown.isEmpty() || !(breakdown.get(0) instanceof Map<?, ?> entry) || entry.get("id") == null) {
            return null;
        }
        return entry.get("id").toString();
    }

    /** The name of the first table ({@code datasource} entry) the refusal's breakdown lists, else null. */
    public String firstTableName() {
        if (!(body.get("breakdown") instanceof java.util.List<?> breakdown)) {
            return null;
        }
        for (Object raw : breakdown) {
            if (raw instanceof Map<?, ?> entry && "datasource".equals(entry.get("type")) && entry.get("name") != null) {
                return entry.get("name").toString();
            }
        }
        return null;
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
