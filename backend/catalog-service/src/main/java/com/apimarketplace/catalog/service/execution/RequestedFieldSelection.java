package com.apimarketplace.catalog.service.execution;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether a call explicitly asked the provider for fields beyond its default answer.
 *
 * <p>Most APIs let the caller widen the response with a selection parameter: X
 * {@code tweet.fields} / {@code expansions}, Graph {@code $select} / {@code $expand}, Jira
 * {@code expand}, Stripe {@code expand[]}, Google {@code fields}, Asana {@code opt_fields},
 * HubSpot {@code properties}. The provider then returns those fields, and {@link OutputProjector}
 * used to drop every one the seed's {@code outputSchema} did not declare: the call was green and
 * the fields the caller named were simply absent (about 1,800 endpoints across 260 seeds carry
 * such a parameter). When this answers true, the projection keeps undeclared fields.
 *
 * <p>Only the parameters the CALLER passed count (what an agent or a workflow node sends), not a
 * default the seed applies later: a call that did not ask projects exactly as before. A parameter
 * counts when its name (after the last {@code .}, ignoring a leading {@code $} and any
 * {@code [...]} suffix) is a selection name ({@code fields}, {@code expand}, YouTube
 * {@code part}, Google {@code readMask}...), or ends in {@code fields} / {@code field_ids} and
 * none of the words in front of that ending (split on {@code _}, {@code -} and camelCase humps)
 * inverts or repurposes it ({@code exclude_fields} asks for less, {@code additional_sort_fields}
 * sorts) unless another word says it selects ({@code custom_output_fields}); and its value is a
 * non-blank string or a non-empty list of scalars. An object value
 * is a request BODY (HubSpot {@code properties} on a create, {@code custom_fields} on an update),
 * not a selection, so it never counts. A false positive only keeps a few extra fields; a false
 * negative is the silent drop this exists to end.
 */
public final class RequestedFieldSelection {

    private static final Set<String> SELECTION_NAMES = Set.of(
            "fields", "expansions", "expand", "include", "select", "embed", "properties",
            "part", "readmask", "projection", "columns", "attributes",
            "export_columns", "propertieswithhistory");

    /**
     * A word in front of {@code fields} that makes the name something other than "give me these":
     * {@code exclude_fields} asks for LESS (widening the output would invert it), and the others
     * filter, sort, rank, merge or write fields rather than select them. Checked as whole words
     * anywhere before {@code fields}, so {@code additional_sort_fields} and
     * {@code reportCustomFields} are caught as well.
     */
    private static final Set<String> NOT_A_SELECTION_WORDS = Set.of(
            "exclude", "excluded", "search", "sort", "rank", "merge", "custom", "required", "dynamic",
            "hidden", "unique", "index", "group", "filter", "omit", "updated");

    /** Words that make a name a selection even next to one of the words above. */
    private static final Set<String> SELECTION_WORDS = Set.of("output", "return", "response", "select", "include");

    /** Name endings that select, in any spelling: fields, field_ids / fieldIds (lower-cased). */
    private static final List<String> SELECTION_SUFFIXES = List.of("fields", "field_ids", "fieldids");

    private RequestedFieldSelection() {
    }

    public static boolean isRequested(Map<String, Object> callerParameters) {
        if (callerParameters == null || callerParameters.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, Object> e : callerParameters.entrySet()) {
            if (isSelectionName(e.getKey()) && isSelectionValue(e.getValue())) {
                return true;
            }
        }
        return false;
    }

    static boolean isSelectionName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String raw = name.trim();
        // expand[] (Stripe) and fields[articles] (JSON:API sparse fieldsets): the bracket is
        // an index or a type name, never part of what the parameter means.
        int bracket = raw.indexOf('[');
        if (bracket > 0) {
            raw = raw.substring(0, bracket);
        }
        int dot = raw.lastIndexOf('.');
        if (dot >= 0) {
            raw = raw.substring(dot + 1);
        }
        if (raw.startsWith("$")) {
            raw = raw.substring(1);
        }
        String n = raw.toLowerCase(Locale.ROOT);
        if (SELECTION_NAMES.contains(n)) {
            return true;
        }
        String suffix = null;
        for (String s : SELECTION_SUFFIXES) {
            if (n.endsWith(s)) {
                suffix = s;
                break;
            }
        }
        if (suffix == null) {
            return false;
        }
        // The words in front of the suffix, split on _ - and camelCase humps, BEFORE lower-casing.
        String head = raw.substring(0, raw.length() - suffix.length());
        List<String> words = List.of(head.split("[_\\-]+|(?<=[a-z0-9])(?=[A-Z])"));
        boolean selects = false;
        boolean repurposed = false;
        for (String w : words) {
            String word = w.toLowerCase(Locale.ROOT);
            selects |= SELECTION_WORDS.contains(word);
            repurposed |= NOT_A_SELECTION_WORDS.contains(word);
        }
        return selects || !repurposed;
    }

    private static boolean isSelectionValue(Object value) {
        if (value instanceof CharSequence s) {
            return !s.toString().isBlank();
        }
        if (value instanceof Collection<?> c) {
            return !c.isEmpty() && c.stream().allMatch(v -> v instanceof CharSequence || v instanceof Number);
        }
        return false;
    }
}
