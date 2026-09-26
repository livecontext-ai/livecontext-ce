package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Step;
import com.apimarketplace.orchestrator.services.template.ReportedParams;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The table CRUD numbers ({@code limit}, {@code offset}, {@code similarity.topK},
 * {@code similarity.threshold}) an author wrote as {@code {{...}}} templates.
 *
 * <p>The plan parser cannot hold a template in an Integer, so it sets it aside in
 * {@link Step.CrudConfig#deferredScalars()} and the typed field stays null: a read then ran on
 * the executor's default (500 rows) as if nothing had been configured. The two nodes that build
 * a CRUD call (StepNode, FindNode) put the template back into the map they resolve, and coerce
 * the resolved value to the number the executor accepts, failing on anything else.
 */
final class CrudDeferredScalars {

    private CrudDeferredScalars() {
    }

    /** Puts every set-aside template into the crud map about to be resolved. */
    @SuppressWarnings("unchecked")
    static void putTemplates(Map<String, Object> crudMap, Step.CrudConfig crud) {
        if (crud == null || crud.deferredScalars().isEmpty()) {
            return;
        }
        Map<String, String> templates = crud.deferredScalars();
        putIfTemplated(crudMap, "limit", templates);
        putIfTemplated(crudMap, "offset", templates);
        if (templates.containsKey("topK") || templates.containsKey("threshold")) {
            Object existing = crudMap.get("similarity");
            Map<String, Object> similarity = existing instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map)
                : new LinkedHashMap<>();
            putIfTemplated(similarity, "topK", templates);
            putIfTemplated(similarity, "threshold", templates);
            crudMap.put("similarity", similarity);
        }
    }

    private static void putIfTemplated(Map<String, Object> target, String field, Map<String, String> templates) {
        String template = templates.get(field);
        if (template != null) {
            target.put(field, template);
        }
    }

    /**
     * {@code resolvedInput} with each templated CRUD number coerced to what the executor
     * accepts: an Integer for limit / offset / topK, a Double for threshold.
     *
     * @throws IllegalStateException when a template resolved to nothing or to a non-number
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> coerce(Map<String, Object> resolvedInput, Step.CrudConfig crud) {
        if (crud == null || crud.deferredScalars().isEmpty() || resolvedInput == null
                || !(resolvedInput.get("crud") instanceof Map<?, ?> rawCrud)) {
            return resolvedInput;
        }
        Map<String, String> templates = crud.deferredScalars();
        Map<String, Object> crudMap = new LinkedHashMap<>((Map<String, Object>) rawCrud);
        coerceInto(crudMap, "limit", templates, false, 0);
        coerceInto(crudMap, "offset", templates, false, 0);
        if (crudMap.get("similarity") instanceof Map<?, ?> rawSimilarity
                && (templates.containsKey("topK") || templates.containsKey("threshold"))) {
            Map<String, Object> similarity = new LinkedHashMap<>((Map<String, Object>) rawSimilarity);
            coerceInto(similarity, "topK", templates, false, 1);
            coerceInto(similarity, "threshold", templates, true, 0);
            crudMap.put("similarity", similarity);
        }
        Map<String, Object> result = new LinkedHashMap<>(resolvedInput);
        result.put("crud", crudMap);
        return result;
    }

    private static void coerceInto(Map<String, Object> target, String field, Map<String, String> templates,
                                   boolean decimal, int min) {
        String template = templates.get(field);
        if (template == null || !target.containsKey(field)) {
            // putTemplates always writes the key, so it is present after a real resolution;
            // a caller probing one field (FindNode's cap) passes only that one.
            return;
        }
        Object value = target.get(field);
        if (value instanceof String text) {
            value = text.trim();
        }
        if (value == null || (value instanceof String text && (text.isEmpty() || text.contains("{{")))) {
            throw new IllegalStateException("crud." + field + " '" + template
                + "' resolved to nothing. Check that the referenced node ran and that the path exists.");
        }
        try {
            BigDecimal number = new BigDecimal(String.valueOf(value));
            if (decimal) {
                target.put(field, number.doubleValue());
                return;
            }
            int whole = number.intValueExact();
            if (whole < min) {
                throw new IllegalStateException("crud." + field + " '" + template + "' resolved to " + whole
                    + ": it must be at least " + min);
            }
            target.put(field, whole);
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalStateException("crud." + field + " '" + template + "' must resolve to "
                + (decimal ? "a number" : "a whole number") + ", got '" + value + "'");
        }
    }

    /**
     * {@code input} as it may be REPORTED: each templated CRUD number goes through the
     * workspace-variable rule, so a {@code {{$vars.x}}} limit is used but never printed.
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> reportable(Map<String, Object> input, Step.CrudConfig crud) {
        if (crud == null || crud.deferredScalars().isEmpty() || input == null
                || !(input.get("crud") instanceof Map<?, ?> rawCrud)) {
            return input;
        }
        Map<String, String> templates = crud.deferredScalars();
        Map<String, Object> crudMap = new LinkedHashMap<>((Map<String, Object>) rawCrud);
        reportInto(crudMap, "limit", templates);
        reportInto(crudMap, "offset", templates);
        if (crudMap.get("similarity") instanceof Map<?, ?> rawSimilarity) {
            Map<String, Object> similarity = new LinkedHashMap<>((Map<String, Object>) rawSimilarity);
            reportInto(similarity, "topK", templates);
            reportInto(similarity, "threshold", templates);
            crudMap.put("similarity", similarity);
        }
        Map<String, Object> result = new LinkedHashMap<>(input);
        result.put("crud", crudMap);
        return result;
    }

    private static void reportInto(Map<String, Object> target, String field, Map<String, String> templates) {
        String template = templates.get(field);
        if (template != null && target.containsKey(field)) {
            target.put(field, ReportedParams.valueFrom(template, target.get(field)));
        }
    }

    /** The resolved, coerced limit of {@code resolvedInput}, or {@code null}. */
    static Integer limitOf(Map<String, Object> resolvedInput) {
        if (resolvedInput != null && resolvedInput.get("crud") instanceof Map<?, ?> crudMap
                && crudMap.get("limit") instanceof Integer limit) {
            return limit;
        }
        return null;
    }
}
