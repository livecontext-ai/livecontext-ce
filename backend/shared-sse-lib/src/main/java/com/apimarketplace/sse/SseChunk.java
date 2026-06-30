package com.apimarketplace.sse;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A single chunk extracted from a Server-Sent Events stream.
 *
 * <p>SSE lines have the form {@code data: <payload>}. This record represents the parsed
 * payload after the {@code data: } prefix has been stripped. {@link #parsedJson} is non-null
 * when the payload was successfully parsed as JSON; otherwise the caller can fall back to
 * {@link #rawData()} for plain text chunks.
 *
 * @param rawData    the raw chunk text (everything after {@code data: } on the SSE line)
 * @param parsedJson the chunk parsed as JSON, or {@code null} if the chunk was not valid JSON
 * @param eventName  optional SSE {@code event:} field associated with this chunk, or {@code null}
 */
public record SseChunk(String rawData, JsonNode parsedJson, String eventName) {

    /** True when the chunk was successfully parsed as JSON. */
    public boolean isJson() {
        return parsedJson != null;
    }
}
