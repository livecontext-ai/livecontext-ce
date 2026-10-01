package com.apimarketplace.orchestrator.tools.workflow.builder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The canonical shape of a form trigger's {@code fields}, in ONE place: stable field ids, select
 * options as {@code [{id, label, value}]}, a default under {@code defaultValue}, and the checks
 * that refuse a field the inspector or the public form cannot render.
 *
 * <p>Every builder path that writes form fields into a plan goes through it: add_node
 * (TriggerCreator) and modify (WorkflowBuilderModifier) refuse a field with a problem, set_plan
 * (WorkflowBuilderPlanExporter) reports it, and the copy the public form renders
 * (PinAwareTriggerSyncService) logs it. Before 2026-09-29 only add_node did, so a plan imported by
 * set_plan kept string-shorthand options (rendered as empty inputs) and fields with no id
 * (inspector edits collapsing onto one sibling), and the public form page crashed on the
 * canonical options every other path wrote.
 */
public final class FormFieldCanonicalizer {

    private FormFieldCanonicalizer() {
    }

    /**
     * Valid form field types, normalized to lowercase. Validation lowercases
     * the incoming {@code field.type} before checking against this set, so
     * agents can submit any case ({@code checkboxGroup}, {@code checkboxgroup},
     * {@code SELECT}). {@link #canonicalize} then stores the one spelling the
     * frontend FieldType enum keys on: lowercase, and camelCase
     * {@code checkboxGroup}.
     */
    public static final Set<String> VALID_FIELD_TYPES = Set.of(
        "text", "email", "password", "number", "textarea", "select", "multiselect",
        "checkbox", "checkboxgroup", "radio", "date", "datetime", "time",
        "file", "url", "tel", "hidden"
    );

    public static final Map<String, String> FIELD_TYPE_ALIASES = Map.of(
        "string", "text",
        "str", "text",
        "int", "number",
        "integer", "number",
        "bool", "checkbox",
        "boolean", "checkbox",
        "phone", "tel"
    );

    /**
     * Field types whose {@code options} array must be coerced to the canonical
     * {@code [{id, label, value}]} shape. Lowercased to match the resolved
     * type post {@link #FIELD_TYPE_ALIASES} application.
     */
    static final Set<String> OPTION_BEARING_TYPES = Set.of(
        "select", "multiselect", "radio", "checkboxgroup"
    );

    /**
     * Validate form fields structure AND coerce loose shapes (string-array
     * options shorthand, missing field/option ids) into the canonical builder
     * shape so the persisted plan, the inspector, and the public form
     * renderer all read the same objects.
     *
     * <p>Replaces {@code parameters.get("fields")} with a fully-mutable,
     * canonical list. Callers must NOT cache the pre-call reference. We take
     * the {@code parameters} Map (rather than the field list directly)
     * because both the input list AND its inner field maps may be immutable
     * ({@code List.of(Map.of(...))} from JSON deserialization or test
     * fixtures); rebuilding upfront avoids scattering immutability checks.</p>
     *
     * <p>Returns what add_node refuses, one entry per problem, or an empty list when the fields
     * are canonical. The fields are rewritten either way: a field with a problem keeps whatever
     * could be made canonical (its id, its default spelling, its type alias), so a caller that
     * reports instead of refusing (set_plan, the copy for the public form) still stores the best
     * shape available. {@link #refusal} turns the list into add_node's error message.</p>
     */
    @SuppressWarnings("unchecked")
    public static List<String> canonicalize(Map<String, Object> parameters) {
        Object rawFields = parameters.get("fields");
        if (rawFields == null) {
            return List.of(); // fields are optional
        }
        if (!(rawFields instanceof List<?> rawList)) {
            return List.of("'fields' must be an array of field objects {name, type, ...}, got "
                    + rawFields.getClass().getSimpleName());
        }
        if (rawList.isEmpty()) {
            return List.of();
        }

        // Build a mutable, canonical list up-front. Inner field maps may also be immutable
        // (Map.of(...) in tests / deser), so copy each one too. An entry that is not an object is
        // kept as sent and named below: replacing it would lose what the caller wrote.
        List<Object> fields = new ArrayList<>(rawList.size());
        for (Object raw : rawList) {
            fields.add(raw instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : raw);
        }
        parameters.put("fields", fields);

        // The ids the caller wrote, so an auto-filled id never collides with one of them.
        Set<String> takenIds = new HashSet<>();
        for (Object entry : fields) {
            if (entry instanceof Map<?, ?> m && m.get("id") instanceof String id && !id.isBlank()) {
                takenIds.add(id);
            }
        }

        Set<String> seenNames = new HashSet<>();
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < fields.size(); i++) {
            if (!(fields.get(i) instanceof Map<?, ?> rawField)) {
                Object entry = fields.get(i);
                errors.add("field[" + i + "] must be an object {name, type, ...}, got "
                        + (entry == null ? "null" : entry.getClass().getSimpleName()));
                continue;
            }
            Map<String, Object> field = (Map<String, Object>) rawField;

            // name is required
            Object nameObj = field.get("name");
            if (!(nameObj instanceof String name) || name.isBlank()) {
                errors.add("field[" + i + "]: 'name' is required");
                continue;
            }

            // no duplicate names
            if (!seenNames.add(name)) {
                errors.add("field '" + name + "': duplicate name");
            }

            // type must be valid (after alias resolution)
            String resolvedType = null;
            Object typeObj = field.get("type");
            if (typeObj instanceof String fieldType) {
                String lower = fieldType.toLowerCase();
                if (!VALID_FIELD_TYPES.contains(lower) && !FIELD_TYPE_ALIASES.containsKey(lower)) {
                    errors.add("field '" + name + "': type '" + fieldType + "' is invalid. " +
                        "Valid: text, email, number, textarea, select, checkbox, date, datetime, time, " +
                        "file, url, tel, password, radio, multiselect, checkboxGroup, hidden");
                } else {
                    resolvedType = FIELD_TYPE_ALIASES.getOrDefault(lower, lower);
                    // One spelling per type: "string" -> "text", "SELECT" -> "select",
                    // "checkboxgroup" -> "checkboxGroup". The editor and both public form pages
                    // match the type exactly, so any other spelling rendered as a text box.
                    String canonicalType = "checkboxgroup".equals(resolvedType) ? "checkboxGroup" : resolvedType;
                    if (!canonicalType.equals(fieldType)) {
                        field.put("type", canonicalType);
                    }
                }
            }

            // Stable id (the inspector keys React lists on field.id; without
            // one, edits collapse onto a single sibling). Auto-fill so LLM
            // callers don't have to know about it, never onto an id already taken.
            Object idObj = field.get("id");
            if (!(idObj instanceof String idStr) || idStr.isBlank()) {
                String id = "field-" + i;
                for (int n = 1; takenIds.contains(id); n++) {
                    id = "field-" + i + "-" + n;
                }
                takenIds.add(id);
                field.put("id", id);
            }

            // `default` / `default_value` -> `defaultValue`, the key the form pre-fills from.
            FormFieldDefaults.canonicalize(field);

            // Coerce options shape for select/multiselect/radio/checkboxGroup.
            // Accept the string shorthand (["a", "b"]) and the canonical
            // [{label, value}] form. Reject anything else explicitly so the
            // agent gets a useful error instead of a silently-empty UI.
            if (resolvedType != null && OPTION_BEARING_TYPES.contains(resolvedType)) {
                String optionsError = coerceFieldOptions(field, name);
                if (optionsError != null) {
                    errors.add(optionsError);
                }
            }
        }

        return errors;
    }

    /** add_node's refusal for the problems {@link #canonicalize} returned. */
    public static String refusal(List<String> issues) {
        return "Form field validation failed:\n  - " + String.join("\n  - ", issues) +
            "\n\nExample: fields: [{name: 'email', type: 'email', label: 'Email', required: true}, " +
            "{name: 'tier', type: 'select', label: 'Tier', required: true, " +
            "options: [{label: 'Free', value: 'free'}, {label: 'Pro', value: 'pro'}]}]";
    }

    /**
     * Coerce {@code field.options} for select-like fields into the canonical
     * {@code [{id, label, value}]} shape and mutate the field map in place.
     *
     * <p>Accepts:
     * <ul>
     *   <li>String shorthand: {@code options: ["a", "b"]} →
     *       {@code [{id:"opt-0", label:"a", value:"a"}, ...]}</li>
     *   <li>Object form: {@code [{label, value}]} (and {@code id} when present)</li>
     * </ul>
     * Rejects mixed-shape arrays with empty {@code label} or {@code value} so
     * the LLM gets a clear error instead of a UI that silently drops options.</p>
     *
     * @return an error message string for {@link #canonicalize} to report,
     *         or {@code null} when the field is valid (and now canonical).
     */
    private static String coerceFieldOptions(Map<String, Object> field, String fieldName) {
        Object opts = field.get("options");
        if (opts == null) {
            return "field '" + fieldName + "' is select/multiselect/radio/checkboxGroup but has no 'options'. " +
                "Provide an array - strings or {label, value} objects both accepted.";
        }
        if (!(opts instanceof List<?> rawList)) {
            return "field '" + fieldName + "': 'options' must be an array, got " + opts.getClass().getSimpleName();
        }
        if (rawList.isEmpty()) {
            return "field '" + fieldName + "': 'options' is empty - provide at least one option.";
        }

        List<Map<String, Object>> coerced = new ArrayList<>(rawList.size());
        for (int j = 0; j < rawList.size(); j++) {
            Object item = rawList.get(j);
            if (item instanceof String s) {
                if (s.isBlank()) {
                    return "field '" + fieldName + "': options[" + j + "] is an empty string.";
                }
                Map<String, Object> normalized = new LinkedHashMap<>();
                normalized.put("id", "opt-" + j);
                normalized.put("label", s);
                normalized.put("value", s);
                coerced.add(normalized);
            } else if (item instanceof Map<?, ?> rawMap) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) rawMap;
                Object label = m.get("label");
                Object value = m.get("value");
                if (!(label instanceof String labelStr) || labelStr.isBlank()) {
                    return "field '" + fieldName + "': options[" + j + "] is missing a non-empty 'label'.";
                }
                if (!(value instanceof String valueStr) || valueStr.isBlank()) {
                    return "field '" + fieldName + "': options[" + j + "] is missing a non-empty 'value'.";
                }
                Map<String, Object> normalized = new LinkedHashMap<>();
                Object existingId = m.get("id");
                normalized.put("id",
                    (existingId instanceof String idStr && !idStr.isBlank()) ? idStr : "opt-" + j);
                normalized.put("label", labelStr);
                normalized.put("value", valueStr);
                coerced.add(normalized);
            } else {
                return "field '" + fieldName + "': options[" + j + "] must be a string or {label, value} object, " +
                    "got " + (item == null ? "null" : item.getClass().getSimpleName());
            }
        }

        field.put("options", coerced);
        return null;
    }
}
