package com.apimarketplace.common.logging;

import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Map;
import java.util.TreeSet;

/**
 * Describes a payload for a log line WITHOUT reproducing its content.
 *
 * <p>Tool parameters and tool results carry end-user content: a Gmail message body, a
 * spreadsheet row, a document. Application logs are shipped to a central store with a long
 * retention and a much wider read audience than the data itself, so writing payloads there
 * creates a standing human-access channel over user data. Under Google's Limited Use
 * requirements, which apply to restricted scopes such as {@code gmail.readonly}, human access
 * to that data is permitted only with the user's affirmative agreement, for security, to
 * comply with law, or on aggregated anonymized data. A debug log line is none of those.
 *
 * <p>This class exists so the rule has ONE implementation. The same user payload passes
 * through several services on a single tool call (the agent interceptor, the catalog HTTP
 * execution path, the conversation tool-result path), and hardening one of them while another
 * still prints the body achieves nothing. When you add a log line that would print a payload,
 * describe it with the helpers here instead. Note that a payload can travel in a URL query
 * string as well as in a body: use {@link #describeUrl} on anything that reaches a log as a
 * request URL.
 *
 * <p>What is safe to log, and why:
 * <ul>
 *   <li><b>Keys</b> come from the tool schema, which is authored by the platform, not by the
 *       user. They identify the call shape without revealing the data.</li>
 *   <li><b>Sizes</b> distinguish an empty result from a runaway one, which is the operational
 *       question a log line is usually asked to answer.</li>
 * </ul>
 *
 * <p>Introduced for LC-009 / LC-010 / LC-034 (CASA security audit).
 */
public final class PayloadLogSafety {

    /**
     * Property that re-enables verbose payload logging for LOCAL debugging.
     *
     * <p>One switch for the whole platform on purpose: a per-service flag would let an
     * operator believe payload logging is off while another service still prints bodies.
     * It must stay false in any environment that processes real user data.
     */
    public static final String PAYLOAD_LOGGING_PROPERTY = "platform.logging.payloads";

    /** Spring placeholder for {@link #PAYLOAD_LOGGING_PROPERTY}, defaulting to disabled. */
    public static final String PAYLOAD_LOGGING_PLACEHOLDER = "${" + PAYLOAD_LOGGING_PROPERTY + ":false}";

    /**
     * Startup-resolved copy of the flag, for the classes that cannot take an injected one.
     *
     * <p>Some payload sinks are not Spring beans (an abstract provider base class, a helper
     * instantiated per request). They still need the SAME answer as the beans, because the
     * whole point of one platform-wide switch is that an operator cannot end up with payload
     * logging half-off. It is written once by {@code PayloadLoggingConfig} at context startup
     * and only read afterwards; the safe value is the initial one, so a class loaded before
     * Spring starts reads {@code false}.
     */
    private static volatile boolean payloadLoggingEnabled = false;

    private PayloadLogSafety() {
    }

    /** @return true only when an operator explicitly enabled verbose payload logging. */
    public static boolean isPayloadLoggingEnabled() {
        return payloadLoggingEnabled;
    }

    /** Package-private: only {@code PayloadLoggingConfig} sets this, once, at startup. */
    static void setPayloadLoggingEnabled(boolean enabled) {
        payloadLoggingEnabled = enabled;
    }

    /**
     * Describe a map by its keys only.
     *
     * @return e.g. {@code {maxResults,query}(2 keys)}
     */
    public static String describeKeys(Map<String, ?> payload) {
        if (payload == null) {
            return "null";
        }
        if (payload.isEmpty()) {
            return "{}";
        }
        // Sorted so the same call always yields the same line, which makes log lines
        // groupable and diffable.
        return render(new TreeSet<>(payload.keySet()), payload.size());
    }

    /**
     * Describe a JSON payload by its field names only. Arrays and scalars carry no field
     * names, so they are reduced to their kind and size.
     *
     * @return e.g. {@code {maxResults,query}(2 keys)}, {@code [array:12]}, {@code <scalar>}
     */
    public static String describeKeys(JsonNode payload) {
        if (payload == null || payload.isNull()) {
            return "null";
        }
        if (payload.isArray()) {
            return "[array:" + payload.size() + "]";
        }
        if (!payload.isObject()) {
            return "<scalar>";
        }
        if (payload.isEmpty()) {
            return "{}";
        }
        TreeSet<String> names = new TreeSet<>();
        payload.fieldNames().forEachRemaining(names::add);
        return render(names, names.size());
    }

    /**
     * Describe a payload of unknown static type, for the many call sites that hold an
     * {@code Object} (a value pulled out of a {@code Map<String, Object>} response body, for
     * instance). Dispatches to the typed helpers so a caller never has to cast just to log
     * safely, which is the kind of friction that makes people log the raw value instead.
     */
    public static String describeAny(Object payload) {
        if (payload == null) {
            return "null";
        }
        if (payload instanceof JsonNode node) {
            return describeKeys(node);
        }
        if (payload instanceof Map<?, ?> map) {
            // Deliberately NOT an unchecked cast to Map<String, ?>: a non-String-keyed map
            // would throw a ClassCastException from inside a log-argument expression, turning a
            // logging helper into a request-breaking bug. Render the keys defensively instead.
            if (map.isEmpty()) {
                return "{}";
            }
            TreeSet<String> names = new TreeSet<>();
            for (Object key : map.keySet()) {
                names.add(String.valueOf(key));
            }
            return render(names, map.size());
        }
        if (payload instanceof Collection<?> collection) {
            return "[collection:" + collection.size() + "]";
        }
        // Deliberately the TYPE, never the value: an unknown object's toString is exactly the
        // uncontrolled content this class exists to keep out of the log.
        return "<" + payload.getClass().getSimpleName() + ">";
    }

    /**
     * Describe an already-serialized payload by its size.
     *
     * @return e.g. {@code <418 chars>}
     */
    public static String describeSize(String serialized) {
        if (serialized == null || "null".equals(serialized)) {
            return "<null>";
        }
        return "<" + serialized.length() + " chars>";
    }

    /**
     * Free text that IS payload (a request body, a model completion, a raw stream line): its
     * size by default, and its first {@code cap} characters only when an operator enabled
     * {@link #PAYLOAD_LOGGING_PROPERTY} for local debugging. This is the helper for call sites
     * that used to print a {@code substring(0, N)} preview of such text.
     *
     * @return e.g. {@code <418 chars>}, or the capped text when payload logging is enabled
     */
    public static String describeText(String text, int cap) {
        if (text == null) {
            return "<null>";
        }
        if (isPayloadLoggingEnabled()) {
            return capMessage(text, cap);
        }
        return describeSize(text);
    }

    /**
     * Describe an object by the size it WOULD serialize to, without serializing it when the
     * answer is not needed.
     *
     * @param serializer produces the serialized form; only called when {@code payload} is
     *                   non-null. Callers pass their own configured ObjectMapper.
     */
    public static String describeSize(Object payload, java.util.function.Function<Object, String> serializer) {
        if (payload == null) {
            return "<null>";
        }
        return describeSize(serializer.apply(payload));
    }

    /**
     * Describe an outbound URL by its ORIGIN only.
     *
     * <p><b>Do not assume only the query carries data.</b> Catalog endpoints interpolate values
     * into the PATH as well: {@code /users/{userId}/messages/{id}} puts the mailbox owner and
     * the message id there, and {@code /v2/{apiKey}} (alchemy, ankr and friends) puts the
     * user's API KEY there. Cutting at the {@code ?} would leave both in the log. So the path
     * is dropped too, and callers that want to record WHICH endpoint ran should log the
     * endpoint template instead, via {@link #describeEndpoint}.
     *
     * @return e.g. {@code https://gmail.googleapis.com/<redacted>}
     */
    public static String describeUrl(String url) {
        if (url == null) {
            return "null";
        }
        String candidate = url.trim();
        // Parsed lexically rather than with java.net.URI on purpose: a real outbound URL can
        // carry characters URI rejects (an unencoded space in a query, for instance), and
        // throwing there would turn every such call into "<unparseable url>", losing even the
        // host. This never throws, and on anything it cannot read it says nothing at all.
        int schemeEnd = candidate.indexOf("://");
        if (schemeEnd <= 0 || !isSchemeToken(candidate.substring(0, schemeEnd))) {
            return "<relative url>";
        }
        int authorityStart = schemeEnd + 3;
        int authorityEnd = candidate.length();
        for (int i = authorityStart; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                authorityEnd = i;
                break;
            }
        }
        String authority = candidate.substring(authorityStart, authorityEnd);
        // Userinfo is a credential, and it is exactly what a phishing URL puts before the host.
        int userInfo = authority.lastIndexOf('@');
        if (userInfo >= 0) {
            authority = authority.substring(userInfo + 1);
        }
        if (authority.isEmpty()) {
            return "<relative url>";
        }
        String origin = candidate.substring(0, authorityStart) + authority;
        String remainder = candidate.substring(authorityEnd);
        boolean hasMore = !remainder.isEmpty() && !"/".equals(remainder);
        return hasMore ? origin + "/<redacted>" : origin;
    }

    /** RFC 3986 scheme: ALPHA *( ALPHA / DIGIT / "+" / "-" / "." ). */
    private static boolean isSchemeToken(String value) {
        if (value.isEmpty() || !Character.isLetter(value.charAt(0))) {
            return false;
        }
        for (int i = 1; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '+' && c != '-' && c != '.') {
                return false;
            }
        }
        return true;
    }

    /**
     * Describe which endpoint was called, using the platform-authored TEMPLATE rather than the
     * interpolated URL. The template still contains its {@code {placeholders}}, so it names the
     * operation without reproducing a single user value.
     *
     * @param baseUrl          the API base URL (platform configuration, not user input).
     * @param endpointTemplate the tool's endpoint pattern, e.g. {@code /users/{userId}/messages}.
     */
    public static String describeEndpoint(String baseUrl, String endpointTemplate) {
        String base = baseUrl == null ? "" : baseUrl;
        String endpoint = endpointTemplate == null ? "" : endpointTemplate;
        if (base.isEmpty() && endpoint.isEmpty()) {
            return "<unknown endpoint>";
        }
        return describeUrl(base).replace("/<redacted>", "") + endpoint;
    }

    /**
     * Redact a free-text message that may quote user content.
     *
     * <p>Used for tool ERROR strings. Those look developer-authored but are not always: an
     * HTTP execution path that cannot classify a provider failure falls back to returning the
     * provider's raw response body, and a JSON parse error quotes the offending source. The
     * message is kept because it is what makes a failure diagnosable, but it is capped hard so
     * a body echoed back by a provider cannot be reconstructed from the log.
     *
     * @param maxLength characters to keep; the remainder is replaced by a count.
     */
    public static String capMessage(String message, int maxLength) {
        if (message == null) {
            return null;
        }
        if (message.length() <= maxLength) {
            return message;
        }
        return message.substring(0, maxLength) + "...[+" + (message.length() - maxLength) + " chars]";
    }

    private static String render(Collection<String> keys, int count) {
        return "{" + String.join(",", keys) + "}(" + count + " keys)";
    }
}
