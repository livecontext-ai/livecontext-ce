package com.apimarketplace.orchestrator.tools.workflow.builder;

import java.util.List;
import java.util.Map;

/**
 * The default value of a form trigger field, in ONE place.
 *
 * <p>The canonical key is {@code defaultValue}: it is what the public form and the builder
 * inspector pre-fill from. Agents also write {@code default} or {@code default_value}; stored as
 * is, those were kept in the plan and read by nothing, so the field looked configured and was
 * never pre-filled. Authoring paths call {@link #canonicalize} (through FormFieldCanonicalizer), and
 * readers call {@link #of} so a plan saved before the canonicalization still resolves its defaults.
 */
public final class FormFieldDefaults {

    public static final String KEY = "defaultValue";
    private static final List<String> ALIASES = List.of("default", "default_value");

    private FormFieldDefaults() {
    }

    /**
     * Moves an alias onto {@code defaultValue} and drops it. A non-blank {@code defaultValue} wins;
     * a blank one counts as absent, the same rule {@link #of} reads with.
     */
    public static void canonicalize(Map<String, Object> field) {
        for (String alias : ALIASES) {
            if (!field.containsKey(alias)) continue;
            Object value = field.remove(alias);
            if (isBlank(field.get(KEY)) && !isBlank(value)) {
                field.put(KEY, value);
            }
        }
    }

    /** The field's default, or {@code null} when it has none (a blank string counts as none). */
    public static Object of(Map<?, ?> field) {
        Object value = field.get(KEY);
        for (int i = 0; isBlank(value) && i < ALIASES.size(); i++) {
            value = field.get(ALIASES.get(i));
        }
        return isBlank(value) ? null : value;
    }

    public static boolean isBlank(Object value) {
        return value == null || (value instanceof String s && s.isBlank());
    }
}
