package com.apimarketplace.orchestrator.services.template;

import java.util.Collection;
import java.util.Map;

/**
 * One bounded line describing what an expression resolved to.
 *
 * <p>A node that reports the VALUE of a collection expression cannot copy it into
 * its parameters: a split's list is the whole dataset, an interface variable is a
 * page of rows, and {@code resolved_params} is persisted per step row and rendered
 * in a panel. What the reader needs is not the data - it is already in the Output
 * column - but its SHAPE, which is what tells a wrapper apart from the array it
 * wraps:
 *
 * <pre>
 *   Map(keys=[result])      the double-`result` wrapper, visible at a glance
 *   List(size=0)            the expression resolved, and there was nothing in it
 *   null                    it resolved to nothing at all
 * </pre>
 *
 * <p>Every rendering here is capped, so a description is safe to persist whatever
 * the value weighs.
 *
 * <p>The same rendering feeds the render service's per-variable log line, so the
 * log and the panel describe one value the same way. Two spellings of one fact is
 * how a reader ends up comparing them instead of reading them.
 */
public final class ResolvedValuePreview {

    /** Beyond this a rendered scalar is truncated and its real length named. */
    static final int MAX_SCALAR_CHARS = 120;

    /** How many of a Map's keys are named before the list is elided. */
    static final int MAX_KEYS = 20;

    /** Per-key cap, so 20 keys cannot add up to an unbounded line. */
    static final int MAX_KEY_CHARS = 40;

    private ResolvedValuePreview() {
    }

    /**
     * Describes {@code value} in one bounded line.
     *
     * <p>Never throws: a value whose {@code toString} fails is described by its type,
     * because this is diagnostic output and a diagnosis that breaks the run it
     * explains is worse than no diagnosis.
     */
    public static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            if (value instanceof CharSequence text) {
                return "\"" + truncate(text.toString()) + "\"";
            }
            if (value instanceof Boolean) {
                return String.valueOf(value);
            }
            // Truncated like any other scalar: a BigInteger or a BigDecimal is as long as
            // it wants to be, and "every rendering here is capped" has to hold for the type
            // one would never think to check.
            if (value instanceof Number) {
                return truncate(String.valueOf(value));
            }
            if (value instanceof Collection<?> collection) {
                return "List(size=" + collection.size() + ")";
            }
            if (value instanceof Map<?, ?> map) {
                return "Map(keys=[" + describeKeys(map) + "])";
            }
            if (value.getClass().isArray()) {
                return "Array(size=" + java.lang.reflect.Array.getLength(value) + ")";
            }
            return value.getClass().getSimpleName() + "(" + truncate(String.valueOf(value)) + ")";
        } catch (Exception e) {
            return value.getClass().getSimpleName();
        }
    }

    /**
     * Describes a value whose real size is known from elsewhere.
     *
     * <p>For a caller holding a PAGE of a collection rather than the collection: the render
     * service returns one row plus a {@code __total} companion, and describing what it holds
     * would report the page as the whole. The known size wins for a collection; any other
     * value falls through to {@link #describe}, because a size that does not belong to a
     * collection is bookkeeping about something else.
     */
    public static String describeWithKnownSize(Object value, long knownSize) {
        if (value instanceof Collection<?> || (value != null && value.getClass().isArray())) {
            return (value instanceof Collection<?> ? "List" : "Array") + "(size=" + knownSize + ")";
        }
        return describe(value);
    }

    /**
     * One bounded line of free text, for a reason or an error message that is reported
     * beside a value. A SpEL or JDBC message is not short, and it is persisted on the step
     * row like everything else here.
     */
    public static String shorten(String text) {
        return text == null ? null : truncate(text);
    }

    private static String describeKeys(Map<?, ?> map) {
        StringBuilder keys = new StringBuilder();
        int shown = 0;
        for (Object key : map.keySet()) {
            if (shown == MAX_KEYS) {
                keys.append(", … ").append(map.size() - MAX_KEYS).append(" more");
                break;
            }
            if (shown > 0) {
                keys.append(", ");
            }
            // A key is author-given and can be as long as any other string. Capping the
            // COUNT alone left the line unbounded, which is what this class exists to
            // prevent - 20 keys of 4 KB is an 80 KB "description" on every step row.
            keys.append(truncate(String.valueOf(key), MAX_KEY_CHARS));
            shown++;
        }
        return keys.toString();
    }

    private static String truncate(String text) {
        return truncate(text, MAX_SCALAR_CHARS);
    }

    private static String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "… (" + text.length() + " chars)";
    }
}
