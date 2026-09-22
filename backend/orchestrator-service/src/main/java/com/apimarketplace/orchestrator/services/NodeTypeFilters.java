package com.apimarketplace.orchestrator.services;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Parsing, matching and faceting for the node-type list filter.
 *
 * <p>Shared by the REST list endpoints and the MCP list actions so both read
 * the request the same way: an agent passing {@code node_types=['mcp:gmail']}
 * and a user ticking "Gmail" in the picker must select the same rows.
 *
 * <p>Tokens are produced by {@link WorkflowNodeTypeExtractor} - see that class
 * for the grammar.
 */
public final class NodeTypeFilters {

    /**
     * Upper bound on how many tokens one request may filter on. Well past any
     * real picker selection (the whole vocabulary of a workspace is smaller),
     * and it keeps a hand-rolled query string from turning into an unbounded
     * scan of comparisons per row.
     */
    public static final int MAX_TOKENS = 50;

    private NodeTypeFilters() {}

    /**
     * Parse a request value into the set of tokens to match.
     *
     * <p>Accepts what the two callers actually send: a comma-separated string
     * (REST query param) or a list of strings (MCP JSON parameter). Blank
     * entries are dropped and tokens are lowercased, so {@code "MCP:Gmail"}
     * and {@code "mcp:gmail"} select the same rows. Order is preserved for
     * readable logs and error messages.
     *
     * @return the requested tokens, empty when no filter was requested
     */
    public static Set<String> parse(Object raw) {
        if (raw == null) return Set.of();

        List<String> parts = new ArrayList<>();
        if (raw instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (item != null) parts.add(String.valueOf(item));
            }
        } else {
            for (String part : String.valueOf(raw).split(",")) {
                parts.add(part);
            }
        }

        Set<String> tokens = new LinkedHashSet<>();
        for (String part : parts) {
            String token = part.trim().toLowerCase(Locale.ROOT);
            if (!token.isEmpty()) tokens.add(token);
            if (tokens.size() >= MAX_TOKENS) break;
        }
        return tokens;
    }

    /**
     * Read a ROW's own token list out of a JSON map, unchanged.
     *
     * <p>Deliberately not {@link #parse(Object)}: that one caps the token count,
     * which is right for a request (it bounds the work a caller can ask for) and
     * wrong for data. Truncating a row's own list would drop its tail - and
     * since the stored list is sorted, the tail is the {@code table:} and
     * {@code trigger:} tokens - so the row would stop matching a filter it
     * genuinely satisfies, with no error to show for it.
     *
     * @return the row's tokens, empty when it has none
     */
    public static List<String> tokensOf(Object raw) {
        if (!(raw instanceof Collection<?> collection)) return List.of();
        List<String> tokens = new ArrayList<>(collection.size());
        for (Object item : collection) {
            if (item == null) continue;
            String token = String.valueOf(item).trim();
            if (!token.isEmpty()) tokens.add(token);
        }
        return tokens;
    }

    /**
     * Does this row satisfy the filter?
     *
     * <p>ANY-of semantics: a row matches when it carries at least one of the
     * requested tokens. That is what a multi-select picker means - ticking
     * Gmail and Slack asks for "workflows touching either", not "workflows
     * touching both", which would return almost nothing.
     *
     * <p>An empty request matches everything, so callers can apply this
     * unconditionally.
     */
    public static boolean matches(Collection<String> rowTypes, Set<String> requested) {
        if (requested.isEmpty()) return true;
        if (rowTypes == null || rowTypes.isEmpty()) return false;
        for (String type : rowTypes) {
            if (type != null && requested.contains(type.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /**
     * Count how many rows carry each token, for the picker.
     *
     * <p>The picker offers exactly the types present in the caller's own
     * workspace, with their counts, rather than the ~60 types the product
     * supports. A list of options that can only ever return results is both
     * shorter to search and impossible to get an empty page from.
     *
     * <p>Ordered by descending count, then alphabetically, so the most useful
     * options surface first and the order is stable between two identical
     * requests.
     *
     * @param rows the node-type list of each row the filter would apply to
     * @return {@code [{value, count}]}, ready to serialize
     */
    public static List<Map<String, Object>> facets(Collection<? extends Collection<String>> rows) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Collection<String> rowTypes : rows) {
            if (rowTypes == null) continue;
            // One row counts once per distinct token, even if it holds three
            // Gmail steps: the facet answers "how many workflows would this
            // option show me", not "how many nodes are there".
            for (String type : new LinkedHashSet<>(rowTypes)) {
                if (type == null || type.isBlank()) continue;
                counts.merge(type.toLowerCase(Locale.ROOT), 1, Integer::sum);
            }
        }

        List<Map.Entry<String, Integer>> ordered = new ArrayList<>(counts.entrySet());
        ordered.sort((a, b) -> {
            int byCount = Integer.compare(b.getValue(), a.getValue());
            return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
        });

        List<Map<String, Object>> facets = new ArrayList<>(ordered.size());
        for (Map.Entry<String, Integer> entry : ordered) {
            Map<String, Object> facet = new LinkedHashMap<>();
            facet.put("value", entry.getKey());
            facet.put("count", entry.getValue());
            facets.add(facet);
        }
        return facets;
    }
}
