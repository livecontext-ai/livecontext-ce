package com.apimarketplace.orchestrator.services.template;

import java.util.ArrayList;
import java.util.List;

/**
 * Computes regions of a SpEL expression where identifier matches should be
 * left untouched by the template engine's variable-rewriting pre-pass.
 *
 * <p>Inside these regions identifiers refer to something other than an outer
 * workflow variable:
 * <ul>
 *   <li>String literals - {@code 'foo'} or {@code "bar"}</li>
 *   <li>Selection brackets - {@code .?[ ... ]}, {@code .^[ ... ]}, {@code .$[ ... ]}</li>
 *   <li>Projection brackets - {@code .![ ... ]}</li>
 * </ul>
 *
 * <p>All identifiers inside these regions refer to the current element
 * (SpEL {@code #this}) - rewriting them as {@code #externalVar} breaks the
 * expression.
 *
 * <p>Note that plain bracket access {@code items[idx]} and method calls
 * {@code foo(bar)} are deliberately <b>not</b> protected: inside those, bare
 * identifiers legitimately refer to outer workflow variables (e.g. an index
 * loaded from a previous step). The caller still needs to skip string
 * literals inside those constructs, which this scanner emits separately.
 *
 * <p>A single left-to-right pass maintains a small state machine that tracks:
 * <ul>
 *   <li>current quote context (single, double, none)</li>
 *   <li>nesting depth of {@code [ ]} and {@code ( )}</li>
 * </ul>
 * The returned list is sorted by start offset and never overlaps.
 *
 * <p>This is a pure function of the expression string - no reflection, no SpEL
 * evaluation. Safe to call on untrusted input.
 */
public final class SpelProtectedRegions {

    /** Inclusive start, exclusive end. */
    public record Region(int start, int end) {
        public boolean contains(int position) {
            return position >= start && position < end;
        }
    }

    private SpelProtectedRegions() {
    }

    /**
     * Scan the expression and return all protected regions.
     *
     * <p>Guarantees:
     * <ul>
     *   <li>Regions are sorted by {@code start}.</li>
     *   <li>Regions never overlap (string literals inside a bracket are merged
     *       into the bracket region rather than added separately).</li>
     *   <li>Unterminated strings / brackets are closed at end-of-input rather
     *       than throwing - the caller should not crash on malformed input.</li>
     * </ul>
     */
    public static List<Region> find(String expression) {
        List<Region> regions = new ArrayList<>();
        if (expression == null || expression.isEmpty()) {
            return regions;
        }

        int i = 0;
        int n = expression.length();
        while (i < n) {
            char c = expression.charAt(i);

            if (c == '\'' || c == '"') {
                int end = scanString(expression, i, c);
                regions.add(new Region(i, end));
                i = end;
                continue;
            }

            // Selection / projection: .?[ .^[ .$[ .![
            if (c == '.' && i + 2 < n) {
                char op = expression.charAt(i + 1);
                char br = expression.charAt(i + 2);
                if (br == '[' && (op == '?' || op == '^' || op == '$' || op == '!')) {
                    int end = scanBracket(expression, i + 2, '[', ']');
                    regions.add(new Region(i, end));
                    i = end;
                    continue;
                }
            }

            i++;
        }

        return regions;
    }

    /**
     * Check whether {@code position} falls inside any region. O(regions) -
     * fine because regions per expression are typically &lt; 10.
     */
    public static boolean isProtected(int position, List<Region> regions) {
        for (Region r : regions) {
            if (r.contains(position)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scan a string literal starting at {@code start} (points at the opening
     * quote). Handles both single-quote doubled-escape ({@code 'it''s'}) and
     * double-quote backslash escape ({@code "say \"hi\""}).
     *
     * @return exclusive end index (one past the closing quote, or {@code n} if unterminated)
     */
    private static int scanString(String s, int start, char quote) {
        int n = s.length();
        int i = start + 1;
        while (i < n) {
            char c = s.charAt(i);
            if (quote == '\'' && c == '\'') {
                // SpEL doubled-quote escape
                if (i + 1 < n && s.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            if (quote == '"') {
                if (c == '\\' && i + 1 < n) {
                    i += 2;
                    continue;
                }
                if (c == '"') {
                    return i + 1;
                }
            }
            i++;
        }
        return n;
    }

    /**
     * Scan a bracketed region ({@code [...]} or {@code (...)}) starting at
     * {@code start} (points at the open bracket). Tracks nested brackets of
     * both kinds and string literals inside, so that quoted closers do not
     * terminate the region prematurely.
     *
     * @return exclusive end index (one past the matching close bracket, or {@code n} if unterminated)
     */
    private static int scanBracket(String s, int start, char open, char close) {
        int n = s.length();
        int depthSquare = 0;
        int depthParen = 0;
        int i = start;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\'' || c == '"') {
                i = scanString(s, i, c);
                continue;
            }
            if (c == '[') {
                depthSquare++;
            } else if (c == ']') {
                depthSquare--;
            } else if (c == '(') {
                depthParen++;
            } else if (c == ')') {
                depthParen--;
            }
            i++;
            if (c == close && depthSquare == 0 && depthParen == 0) {
                return i;
            }
        }
        return n;
    }

}
