package com.apimarketplace.trigger.service;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * Evaluates a single-condition filter against a row.
 *
 * <p>Filter shape: {@code {"column": "status", "operator": "=", "value": "paid"}}.
 * A null or empty filter matches everything.
 *
 * <p>Operator vocabulary (symbolic, aligned with frontend + TriggerCreator + V95 docs):
 * {@code =, !=, >, >=, <, <=, in, not_in, contains, starts_with, ends_with, is_null, is_not_null}.
 * The {@code ==} form is accepted as an alias for {@code =} (the frontend surfaces
 * {@code ==} in its operator dropdown because a single {@code =} reads as an
 * assignment; wire format from the standard form stays {@code =}, but accepting
 * {@code ==} keeps callers that mirror the UI label robust).
 * Legacy word-form aliases ({@code eq, neq, gt, gte, lt, lte}) are also accepted so
 * that older persisted subscriptions keep matching. Unknown operators return false.
 *
 * <p>Numeric comparisons ({@code >, >=, <, <=}) coerce both sides via
 * {@code Double.parseDouble}; non-numeric operands return false. String operators
 * coerce via {@code toString()}. The {@code in}/{@code not_in} operators accept a
 * {@link Collection} or comma-separated string for {@code value}.
 */
@Component
public class DatasourceFilterEvaluator {

    public boolean matches(Map<String, Object> filter, Map<String, Object> row) {
        if (filter == null || filter.isEmpty()) {
            return true;
        }
        if (row == null) {
            return false;
        }
        Object columnObj = filter.get("column");
        Object operatorObj = filter.get("operator");
        if (columnObj == null || operatorObj == null) {
            return true; // malformed filter - be permissive; validator rejects these at save time
        }
        String column = columnObj.toString();
        String operator = operatorObj.toString().trim().toLowerCase();
        Object expected = filter.get("value");
        Object actual = row.get(column);

        return switch (operator) {
            case "=", "==", "eq"    -> equalsCoerced(actual, expected);
            case "!=", "neq"        -> !equalsCoerced(actual, expected);
            case ">", "gt"          -> compareNumeric(actual, expected, c -> c >  0);
            case ">=", "gte"        -> compareNumeric(actual, expected, c -> c >= 0);
            case "<", "lt"          -> compareNumeric(actual, expected, c -> c <  0);
            case "<=", "lte"        -> compareNumeric(actual, expected, c -> c <= 0);
            case "in"               -> inList(actual, expected);
            case "not_in"           -> !inList(actual, expected);
            case "contains"         -> actual != null && asString(actual).contains(asString(expected));
            case "starts_with"      -> actual != null && asString(actual).startsWith(asString(expected));
            case "ends_with"        -> actual != null && asString(actual).endsWith(asString(expected));
            case "is_null"          -> actual == null;
            case "is_not_null"      -> actual != null;
            default                 -> false;
        };
    }

    private boolean equalsCoerced(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a.equals(b)) return true;
        // Compare stringified for mixed Integer/Long/String inputs from JSONB.
        return Objects.equals(a.toString(), b.toString());
    }

    private boolean compareNumeric(Object a, Object b, java.util.function.IntPredicate cmp) {
        Double da = toDouble(a);
        Double db = toDouble(b);
        if (da == null || db == null) return false;
        return cmp.test(Double.compare(da, db));
    }

    private boolean inList(Object actual, Object expected) {
        if (actual == null || expected == null) return false;
        if (expected instanceof Collection<?> col) {
            for (Object o : col) {
                if (equalsCoerced(actual, o)) return true;
            }
            return false;
        }
        // Accept comma-separated string for convenience ("a,b,c")
        String s = expected.toString();
        for (String part : s.split(",")) {
            if (equalsCoerced(actual, part.trim())) return true;
        }
        return false;
    }

    private Double toDouble(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(o.toString()); }
        catch (NumberFormatException e) { return null; }
    }

    private String asString(Object o) {
        return o == null ? "" : o.toString();
    }
}
