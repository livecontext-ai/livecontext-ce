package com.apimarketplace.orchestrator.services.state.patch;

import java.util.Arrays;
import java.util.Objects;

/**
 * Single Postgres {@code jsonb_set} operation: write the value at {@code path}.
 *
 * <p>Two op-kinds, distinguished at the SQL level by the {@link OpKind}:
 *
 * <ul>
 *   <li>{@link OpKind#ASSIGN}: {@code jsonValue} is a literal JSON encoding
 *       (e.g. {@code ["a","b"]} for a Set, {@code 42} for an int, a full
 *       Jackson-serialized record). Cast as {@code :v::jsonb} in the SQL.
 *       Two same-path patches cannot merge - force-flush the older one.</li>
 *   <li>{@link OpKind#COMMUTATIVE_DELTA}: {@code jsonValue} is a numeric
 *       delta as a decimal string (e.g. {@code "1"}, {@code "-1"}, {@code "50"}).
 *       The executor composes {@code to_jsonb(((state_snapshot#>>path)::int + :v::int))}
 *       so the read-modify-write happens INSIDE the SQL statement. Two
 *       same-path patches CAN merge (sum the deltas). Caller MUST ensure
 *       the existing value at {@code path} is a valid integer.</li>
 * </ul>
 *
 * <p>The path is a list of keys/indices (no dot notation), serialized into
 * Postgres {@code text[]} format on the SQL side. JSON object keys with special
 * characters (e.g. {@code "trigger:webhook"}) are passed verbatim - Postgres
 * matches keys as opaque strings, so the only forbidden character in a path
 * element is {@code ,} (the array separator).
 *
 * <p>Use {@link #assignment} or {@link #commutativeDelta} factories rather than
 * the canonical constructor to make the op-kind obvious at the call site.
 *
 * @param path     ordered path elements (e.g. {@code ["dags", "trigger:webhook", "epochs", "5", "completedNodeIds"]})
 * @param jsonValue for ASSIGN: a JSON-encoded value to set at the path.
 *                  For COMMUTATIVE_DELTA: a decimal integer string for the delta.
 * @param opKind    SQL composition mode (plan v4 §1.4 + §3 DELTA merge)
 */
public record JsonbPatch(String[] path, String jsonValue, OpKind opKind) {

    /** Plan v4 §1.4 - patch composition kind. Mirrors {@link PatchClass.OpKind}. */
    public enum OpKind { ASSIGN, COMMUTATIVE_DELTA }

    public JsonbPatch {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(jsonValue, "jsonValue must not be null");
        Objects.requireNonNull(opKind, "opKind must not be null");
        if (path.length == 0) {
            throw new IllegalArgumentException("path must not be empty");
        }
        for (String element : path) {
            if (element == null) {
                throw new IllegalArgumentException("path elements must not be null");
            }
            if (element.indexOf(',') >= 0) {
                throw new IllegalArgumentException(
                        "path element must not contain ',' (Postgres text[] separator): " + element);
            }
        }
        if (opKind == OpKind.COMMUTATIVE_DELTA) {
            // Validate that jsonValue parses as a signed integer (no decimal point,
            // no leading zeros allowed by Long.parseLong for negative numbers).
            try {
                Long.parseLong(jsonValue);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(
                        "COMMUTATIVE_DELTA jsonValue must be a decimal integer: '" + jsonValue + "'", ex);
            }
        }
    }

    /**
     * Back-compat constructor for callers that don't yet specify opKind -
     * defaults to ASSIGN (matches pre-2b behavior of every existing builder).
     */
    public JsonbPatch(String[] path, String jsonValue) {
        this(path, jsonValue, OpKind.ASSIGN);
    }

    /** Factory - ASSIGN op: write the literal JSON value at the path. */
    public static JsonbPatch assignment(String[] path, String jsonValue) {
        return new JsonbPatch(path, jsonValue, OpKind.ASSIGN);
    }

    /**
     * Factory - COMMUTATIVE_DELTA op: SQL-side read-modify-write of an integer
     * at the path. {@code delta} can be negative.
     */
    public static JsonbPatch commutativeDelta(String[] path, long delta) {
        return new JsonbPatch(path, Long.toString(delta), OpKind.COMMUTATIVE_DELTA);
    }

    /**
     * Render the path as a Postgres {@code text[]} literal.
     *
     * <p>Format: {@code {a,b,c}} where each element is the raw key (no quoting
     * needed by Postgres for {@code text[]} unless the key contains characters
     * that interfere with array parsing - we already reject {@code ,}, and
     * {@code "}, {@code \}, {@code {}}, {@code }} require quoting). For
     * defense, we wrap each element in double quotes and escape backslashes
     * and quotes within.
     */
    public String toPostgresArrayLiteral() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < path.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"');
            for (int j = 0; j < path[i].length(); j++) {
                char c = path[i].charAt(j);
                if (c == '\\' || c == '"') sb.append('\\');
                sb.append(c);
            }
            sb.append('"');
        }
        sb.append('}');
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JsonbPatch other)) return false;
        return Arrays.equals(path, other.path)
                && jsonValue.equals(other.jsonValue)
                && opKind == other.opKind;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Arrays.hashCode(path) + jsonValue.hashCode()) + opKind.hashCode();
    }

    @Override
    public String toString() {
        return "JsonbPatch{path=" + Arrays.toString(path)
                + ", jsonValue=" + jsonValue + ", opKind=" + opKind + "}";
    }
}
