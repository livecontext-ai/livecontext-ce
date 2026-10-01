package com.apimarketplace.catalog.service.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads a provider answer that the RestTemplate could not turn into an {@code Object}: the body of
 * the {@code UnknownContentTypeException} it throws when no message converter accepts the content
 * type. {@link HttpExecutionService} catches that exception on its untyped reads and asks this
 * class for the body.
 *
 * <p>Why: the catalog engine reads every untyped provider answer with
 * {@code exchange(..., Object.class)}, and a default RestTemplate only turns JSON (and, with the
 * YAML module on the classpath, {@code application/yaml}) into an {@code Object}. Every other
 * textual content type failed the whole call AFTER the provider had answered 200, with "no suitable
 * HttpMessageConverter found for response type [class java.lang.Object] and content type
 * [text/csv;charset=utf-8]" (Apify {@code get-dataset-items} with {@code format=csv}, prod 2026-09).
 *
 * <p>Why an exception handler and not a message converter: the RestTemplate is a bean, and in the
 * CE monolith several modules each declare one named {@code restTemplate}, so the instance the
 * catalog receives is shared. Adding a converter to it would change what every other module reads.
 * Catching the refusal changes nothing for any call that parses today, in any module: this class is
 * only ever handed a body that already failed.
 *
 * <p>What {@link #read} returns, decoded with the charset the response names (UTF-8 when none):
 * <ul>
 *   <li>JSON Lines ({@code ndjson}, {@code jsonl}) comes back as the list of its records, or as
 *       text when a line is not JSON;</li>
 *   <li>a textual, non-JSON body (text/*, xml, csv, {@code x-yaml} and {@code text/yaml},
 *       javascript...) comes back as a String, or as the parsed JSON when its trimmed text starts
 *       with {@code &#123;} or {@code [} and parses (JSON served as {@code text/plain},
 *       {@code text/json}, {@code text/javascript});</li>
 *   <li>{@code text/event-stream}: see {@link #jsonFromEventStream};</li>
 *   <li>NOTHING for HTML ({@code text/html}, {@code application/xhtml+xml}): a 2xx HTML page from
 *       an API is almost always a login wall, a captive portal or a proxy challenge, and reading
 *       it would report that failure as {@code success=true}. Nothing either for binary types
 *       (images, PDF, octet-stream, no content type), which belong to {@code response.type=binary}.
 *       Those keep failing exactly as before.</li>
 * </ul>
 *
 * <p>Three trade-offs, accepted knowingly:
 * <ul>
 *   <li>An endpoint that declares {@code response.type=text}, read on the TYPED path (the only
 *       one that reads that declaration), takes ANY unreadable body as text ({@link #decode}), HTML
 *       and a missing or binary content type included. If such an endpoint ever answers with
 *       binary bytes, they come back as a mangled string rather than a failure.</li>
 *   <li>A provider that reports an error in a 2xx {@code text/plain} body (instead of a 4xx/5xx
 *       status) is now a success carrying that text, where before it was an unreadable-body
 *       failure. The status code is the provider's statement and it is honoured as such.</li>
 *   <li>The catalog client never follows redirects, and the RestTemplate does not treat a 3xx as
 *       an error, so a 3xx with a textual body (a {@code text/plain} "Moved" page) is now a
 *       success carrying that text, where before it failed as unreadable. It was never a result
 *       either way; it now reaches the caller readable instead of as a converter error.</li>
 * </ul>
 */
public final class TextualResponseBody {

    /** Non-{@code text/*} subtypes that carry text. A {@code +xml} / {@code +yaml} suffix counts too. */
    private static final Set<String> TEXTUAL_APPLICATION_SUBTYPES = Set.of(
            "xml", "csv", "x-ndjson", "ndjson", "jsonl", "x-jsonlines", "yaml", "x-yaml",
            "javascript", "x-javascript", "ecmascript", "graphql", "sql", "x-www-form-urlencoded");

    /** Subtypes that are never read as a result, see the class comment. */
    private static final Set<String> HTML_SUBTYPES = Set.of("html", "xhtml+xml");

    /**
     * FAIL_ON_TRAILING_TOKENS: a body is read as JSON only when it is ONE JSON document. Without
     * it Jackson stops after the first value, so "[1] garbage" became [1] and a JSON Lines body
     * kept its first record and silently dropped the rest.
     */
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** JSON Lines media types: one JSON document per line. */
    private static final Set<String> JSON_LINES_SUBTYPES = Set.of("x-ndjson", "ndjson", "jsonl", "x-jsonlines");

    private TextualResponseBody() {
    }

    /**
     * Whether {@code mediaType} names a textual body read here: any {@code text/*} but HTML, and the
     * textual {@code application/*} types; never {@code application/json} or {@code +json}, which
     * Jackson owns (a malformed one fails there, as before).
     */
    static boolean isTextualNonJson(@Nullable MediaType mediaType) {
        if (mediaType == null) {
            return false;
        }
        String type = mediaType.getType().toLowerCase(Locale.ROOT);
        String subtype = mediaType.getSubtype().toLowerCase(Locale.ROOT);
        if ("*".equals(type) || "*".equals(subtype) || HTML_SUBTYPES.contains(subtype)) {
            return false;
        }
        if ("text".equals(type)) {
            // text/json included: Jackson's converter reads application/json only, so a JSON body
            // served as text/json reaches here and is parsed by jsonOrText.
            return true;
        }
        if (subtype.equals("json") || subtype.endsWith("+json")) {
            return false;
        }
        return "application".equals(type)
                && (TEXTUAL_APPLICATION_SUBTYPES.contains(subtype)
                    || subtype.endsWith("+xml")
                    || subtype.endsWith("+yaml"));
    }

    /**
     * The value of a textual, non-JSON, non-HTML body; empty for anything else, which the caller
     * leaves failing as it did.
     */
    static Optional<Object> read(@Nullable byte[] body, @Nullable MediaType contentType) {
        if (!isTextualNonJson(contentType)) {
            return Optional.empty();
        }
        String text = decode(body, contentType);
        if (MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType)) {
            return Optional.of(jsonFromEventStream(text));
        }
        if (JSON_LINES_SUBTYPES.contains(contentType.getSubtype().toLowerCase(Locale.ROOT))) {
            return Optional.of(jsonLinesOrText(text));
        }
        return Optional.of(jsonOrText(text));
    }

    /** The body as a String, in the charset the response names, UTF-8 when it names none. */
    static String decode(@Nullable byte[] body, @Nullable MediaType contentType) {
        Charset charset = contentType != null && contentType.getCharset() != null
                ? contentType.getCharset()
                : StandardCharsets.UTF_8;
        return body == null ? "" : new String(body, charset);
    }

    /**
     * A JSON Lines body as the list of its records, the way the catalog's format detection
     * already treats NDJSON (as JSON). Blank lines are skipped; if ANY line is not one JSON
     * document the whole text is returned as it is, so nothing is dropped.
     */
    static Object jsonLinesOrText(String text) {
        List<Object> records = new ArrayList<>();
        for (String line : text.split("\r\n|\r|\n")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                records.add(JSON.readValue(line, Object.class));
            } catch (IOException notJson) {
                return text;
            }
        }
        return new com.apimarketplace.catalog.service.execution.JsonLines(records, text);
    }

    /**
     * The parsed JSON when the text looks like ONE JSON object or array and parses entirely,
     * else the text (trailing content, a second document, or invalid JSON keep the text whole).
     */
    static Object jsonOrText(String text) {
        String trimmed = text.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return JSON.readValue(trimmed, Object.class);
            } catch (IOException notJson) {
                return text;
            }
        }
        return text;
    }

    /**
     * The JSON reply an event stream carries, or the raw text when it carries none.
     *
     * <p>Parsing follows the SSE rules: lines end with CRLF, LF or CR; a blank line ends an event;
     * an event's data is its {@code data:} lines joined by a newline; comment lines (starting with
     * {@code :}) and other fields are ignored; an event with no data is skipped.
     *
     * <p>Which event is the reply: the LAST one whose data is a JSON-RPC response (an object with
     * an {@code id} and a {@code result} or an {@code error}), since an MCP server may send
     * notifications or progress events before it. Failing that, the data of a stream holding a
     * single JSON event. Anything else (several non-response events, non-JSON data) comes back as
     * the raw text, so nothing is thrown away.
     */
    static Object jsonFromEventStream(String body) {
        List<String> events = new ArrayList<>();
        StringBuilder data = null;
        for (String line : body.split("\r\n|\r|\n", -1)) {
            if (line.isEmpty()) {
                if (data != null) {
                    events.add(data.toString());
                    data = null;
                }
                continue;
            }
            if (line.startsWith(":") || !(line.equals("data") || line.startsWith("data:"))) {
                continue;
            }
            String value = line.length() > "data:".length() ? line.substring("data:".length()) : "";
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            data = data == null ? new StringBuilder(value) : data.append('\n').append(value);
        }
        if (data != null) {
            events.add(data.toString());
        }

        List<Object> parsed = new ArrayList<>();
        Object lastRpcResponse = null;
        for (String event : events) {
            if (event.isBlank()) {
                continue;
            }
            Object json;
            try {
                json = JSON.readValue(event, Object.class);
            } catch (IOException notJson) {
                parsed.add(null);
                continue;
            }
            parsed.add(json);
            if (json instanceof Map<?, ?> map
                    && map.containsKey("id")
                    && (map.containsKey("result") || map.containsKey("error"))) {
                lastRpcResponse = json;
            }
        }
        if (lastRpcResponse != null) {
            return lastRpcResponse;
        }
        if (parsed.size() == 1 && parsed.get(0) != null) {
            return parsed.get(0);
        }
        return body;
    }
}
