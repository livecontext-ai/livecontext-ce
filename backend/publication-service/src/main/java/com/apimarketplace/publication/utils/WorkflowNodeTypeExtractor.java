package com.apimarketplace.publication.utils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Extracts the set of node-type tokens used by a workflow plan.
 *
 * <p>The output is stored in the {@code node_types} column (unindexed - the
 * applications list is scope-bounded and filters in memory) so that list can
 * filter by node type without loading a plan snapshot, which its queries
 * deliberately never select.
 *
 * <p>It walks exactly the same plan branches as {@link WorkflowIconExtractor} -
 * the two are read together on every card (icons draw the glyph, types drive
 * the filter), so a token that has no matching icon would surface a filter
 * value the user never sees on a card.
 *
 * <h2>Token grammar</h2>
 * <pre>
 *   trigger:&lt;type&gt;   manual, webhook, schedule, datasource, chat, form, workflow, error
 *   mcp:&lt;slug&gt;       the integration slug, e.g. mcp:gmail - resolved exactly as the
 *                    icon is (explicit iconSlug, else the apiSlug prefix of the id)
 *   core:&lt;type&gt;      loop, transform, code, http_request, ...
 *   agent:&lt;type&gt;     agent, guardrail, classify, browser_agent, generate
 *   table:&lt;type&gt;     create-row, find, ... ("crud-" stripped, as for icons)
 *   interface        interface nodes carry no subtype
 * </pre>
 *
 * <p>Tokens are lowercased and deduplicated, and the list is returned in a
 * stable (sorted) order, so two reads of the same plan compare equal and a
 * stored copy is never rewritten for a difference nobody made.
 *
 * <p>Notes ({@code note:}) are deliberately absent: they are canvas
 * annotations, not steps, and filtering a list by "has a sticky note" has no
 * meaning for the user.
 *
 * <p>Twin: {@code orchestrator-service} {@code WorkflowNodeTypeExtractor}
 * produces the same tokens for the workflows list, deriving them from the plan
 * it has already loaded rather than storing them. Both copies must produce
 * identical tokens - pinned by {@code WorkflowNodeTypeExtractorParityTest} in
 * each module, which compares the two class bodies byte for byte.
 */
public final class WorkflowNodeTypeExtractor {

    /**
     * Sentinel the catalog substitutes when an API has no icon of its own.
     * Mirrors {@link WorkflowIconExtractor}: it is non-blank, so it has to be
     * rejected explicitly or every unbranded API would collapse into a single
     * {@code mcp:mcp} bucket instead of its real integration slug.
     */
    private static final String UNRESOLVED_ICON_SLUG = "mcp";

    private WorkflowNodeTypeExtractor() {}

    /**
     * Extract the deduplicated, sorted node-type tokens of a workflow plan.
     *
     * @param plan the raw workflow plan map (may be null)
     * @return the tokens, never null - an empty list for a null/empty plan
     */
    @SuppressWarnings("unchecked")
    public static List<String> extractNodeTypes(Map<String, Object> plan) {
        if (plan == null) return List.of();

        Set<String> tokens = new LinkedHashSet<>();

        for (Map<String, Object> trigger : entries(plan, "triggers")) {
            add(tokens, "trigger:", text(trigger.get("type")));
        }

        for (Map<String, Object> mcp : entries(plan, "mcps")) {
            String id = text(mcp.get("id"));
            if (id == null || id.isBlank()) continue;
            String explicitIconSlug = text(mcp.get("iconSlug"));
            String apiSlug = id.contains("/") ? id.substring(0, id.indexOf('/')) : id;
            add(tokens, "mcp:", isResolvedIconSlug(explicitIconSlug) ? explicitIconSlug : apiSlug);
        }

        for (Map<String, Object> core : entries(plan, "cores")) {
            add(tokens, "core:", text(core.get("type")));
        }

        for (Map<String, Object> agent : entries(plan, "agents")) {
            String type = text(agent.get("type"));
            add(tokens, "agent:", type == null || type.isBlank() ? "agent" : type);
        }

        for (Map<String, Object> table : entries(plan, "tables")) {
            String type = text(table.get("type"));
            if (type == null) continue;
            // Trim BEFORE stripping: " crud-create-row" must give the same token
            // as "crud-create-row", or a stray space in a hand-written plan
            // silently files the node under its own separate option.
            String trimmed = type.trim();
            // Strip "crud-" so the token matches the frontend node registry key
            // (and the nodeId the icon extractor emits for the same node).
            add(tokens, "table:", trimmed.startsWith("crud-") ? trimmed.substring(5) : trimmed);
        }

        // Any NON-EMPTY interfaces array, whatever its elements look like. That is
        // the condition WorkflowIconExtractor draws the interface glyph on, and the
        // two have to agree: a card showing the glyph for a workflow the
        // "interface" option cannot find is the exact mismatch this class exists
        // to avoid. Every other branch reads a field off each element, so those
        // do skip a non-map; this one reads nothing.
        if (plan.get("interfaces") instanceof List<?> interfaces && !interfaces.isEmpty()) {
            tokens.add("interface");
        }

        List<String> sorted = new ArrayList<>(tokens);
        sorted.sort(String::compareTo);
        return sorted;
    }

    /**
     * Read one plan branch as a list of node maps. A missing branch, a branch
     * that is not an array, or a non-map element yields nothing rather than
     * throwing: this runs on every save, and a malformed plan must not block
     * the write.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Map<String, Object> plan, String key) {
        if (!(plan.get(key) instanceof List<?> raw)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (item instanceof Map) out.add((Map<String, Object>) item);
        }
        return out;
    }

    /**
     * Read a plan field as text, or {@code null} when it is absent or not a
     * string.
     *
     * <p>A plain cast would be enough for any plan the builder produces, and
     * would throw {@link ClassCastException} on one that came from anywhere else
     * (a hand-written {@code set_plan}, an imported publication). This runs on
     * the READ path of the workflow list, so that exception would not fail one
     * save - it would take the whole list down for the tenant holding the odd
     * plan.
     */
    private static String text(Object value) {
        return value instanceof String s ? s : null;
    }

    private static void add(Set<String> tokens, String prefix, String value) {
        if (value == null) return;
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return;
        tokens.add(prefix + normalized);
    }

    private static boolean isResolvedIconSlug(String slug) {
        return slug != null && !slug.isBlank()
            && !UNRESOLVED_ICON_SLUG.equalsIgnoreCase(slug.trim());
    }
}
