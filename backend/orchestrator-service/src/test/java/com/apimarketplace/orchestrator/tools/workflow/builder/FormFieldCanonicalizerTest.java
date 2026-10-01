package com.apimarketplace.orchestrator.tools.workflow.builder;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one canonical shape of a form trigger's fields, used by add_node and modify (which refuse
 * on a problem), set_plan (which reports it) and the copy pushed to the public form (which logs
 * it). Every refusal branch, every coercion, and the two contracts the reporting callers rely on:
 * the fields are rewritten even when a problem is returned, and a second pass changes nothing.
 */
@DisplayName("FormFieldCanonicalizer")
class FormFieldCanonicalizerTest {

    private static Map<String, Object> params(Object... fields) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("fields", List.of(fields));
        return params;
    }

    private static Map<String, Object> field(Object... kv) {
        Map<String, Object> f = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) f.put((String) kv[i], kv[i + 1]);
        return f;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fieldsOf(Map<String, Object> params) {
        return (List<Map<String, Object>>) params.get("fields");
    }

    @Nested
    @DisplayName("what add_node refuses")
    class Refusals {

        @Test
        @DisplayName("a field with no name, a duplicate name and an unknown type are each one problem")
        void nameAndTypeProblems() {
            Map<String, Object> p = params(
                    field("type", "text"),
                    field("name", "a", "type", "text"),
                    field("name", "a", "type", "text"),
                    field("name", "b", "type", "colour"));

            List<String> issues = FormFieldCanonicalizer.canonicalize(p);

            assertThat(issues).containsExactly(
                    "field[0]: 'name' is required",
                    "field 'a': duplicate name",
                    "field 'b': type 'colour' is invalid. Valid: text, email, number, textarea, select, checkbox, "
                            + "date, datetime, time, file, url, tel, password, radio, multiselect, checkboxGroup, hidden");
        }

        @Test
        @DisplayName("every malformed options shape of a select-like field is named")
        void optionProblems() {
            Map<String, Object> p = params(
                    field("name", "missing", "type", "select"),
                    field("name", "scalar", "type", "radio", "options", "a,b"),
                    field("name", "empty", "type", "multiselect", "options", List.of()),
                    field("name", "blank", "type", "select", "options", List.of("")),
                    field("name", "nolabel", "type", "select", "options", List.of(Map.of("value", "v"))),
                    field("name", "novalue", "type", "checkboxGroup", "options", List.of(Map.of("label", "L"))),
                    field("name", "junk", "type", "select", "options", List.of(42)));

            List<String> issues = FormFieldCanonicalizer.canonicalize(p);

            assertThat(issues).hasSize(7);
            assertThat(issues.get(0)).startsWith("field 'missing' is select/multiselect/radio/checkboxGroup but has no 'options'");
            assertThat(issues.get(1)).isEqualTo("field 'scalar': 'options' must be an array, got String");
            assertThat(issues.get(2)).isEqualTo("field 'empty': 'options' is empty - provide at least one option.");
            assertThat(issues.get(3)).isEqualTo("field 'blank': options[0] is an empty string.");
            assertThat(issues.get(4)).isEqualTo("field 'nolabel': options[0] is missing a non-empty 'label'.");
            assertThat(issues.get(5)).isEqualTo("field 'novalue': options[0] is missing a non-empty 'value'.");
            assertThat(issues.get(6)).startsWith("field 'junk': options[0] must be a string or {label, value} object, got Integer");
        }

        @Test
        @DisplayName("refusal() is add_node's message: every problem listed, then an example")
        void refusalMessage() {
            String message = FormFieldCanonicalizer.refusal(List.of("field[0]: 'name' is required", "field 'a': duplicate name"));

            assertThat(message).startsWith("Form field validation failed:\n  - field[0]: 'name' is required\n  - field 'a': duplicate name");
            assertThat(message).contains("Example: fields: [");
        }
    }

    @Nested
    @DisplayName("what is not a list of fields")
    class NotFields {

        @Test
        @DisplayName("a `fields` that is not a list is one problem, and is left as sent")
        void fieldsNotAList() {
            Map<String, Object> p = new LinkedHashMap<>(Map.of("fields", "email,name"));

            assertThat(FormFieldCanonicalizer.canonicalize(p))
                    .containsExactly("'fields' must be an array of field objects {name, type, ...}, got String");
            assertThat(p).containsEntry("fields", "email,name");
        }

        @Test
        @DisplayName("an entry that is not an object is named and kept as sent, never replaced by an empty field")
        @SuppressWarnings("unchecked")
        void entryNotAnObject() {
            Map<String, Object> p = params(field("name", "a", "type", "text"), "email");

            assertThat(FormFieldCanonicalizer.canonicalize(p))
                    .containsExactly("field[1] must be an object {name, type, ...}, got String");
            assertThat((List<Object>) p.get("fields")).element(1).isEqualTo("email");
        }
    }

    @Nested
    @DisplayName("what it rewrites")
    class Rewrites {

        @Test
        @DisplayName("ids, string-shorthand options, the default spelling and type aliases take the canonical shape")
        void canonicalShape() {
            Map<String, Object> p = params(
                    field("name", "tier", "type", "select", "options", List.of("free", Map.of("id", "keep", "label", "Pro", "value", "pro"))),
                    field("name", "theme", "type", "string", "default", "Innovation"),
                    field("name", "phone", "type", "phone"),
                    field("name", "group", "type", "checkboxGroup", "options", List.of("x")));

            assertThat(FormFieldCanonicalizer.canonicalize(p)).isEmpty();

            List<Map<String, Object>> fields = fieldsOf(p);
            assertThat(fields).extracting(f -> f.get("id")).containsExactly("field-0", "field-1", "field-2", "field-3");
            assertThat(fields.get(0).get("options")).isEqualTo(List.of(
                    Map.of("id", "opt-0", "label", "free", "value", "free"),
                    Map.of("id", "keep", "label", "Pro", "value", "pro")));
            assertThat(fields.get(1)).containsEntry("type", "text").containsEntry("defaultValue", "Innovation")
                    .doesNotContainKey("default");
            assertThat(fields.get(2)).containsEntry("type", "tel");
            // A real type keeps the caller's case: the editor keys on camelCase checkboxGroup.
            assertThat(fields.get(3)).containsEntry("type", "checkboxGroup");
        }

        @Test
        @DisplayName("a field with a problem is still rewritten as far as it can be (the reporting callers store it)")
        void rewritesDespiteProblems() {
            Map<String, Object> p = params(
                    field("name", "tier", "type", "select", "options", List.of(Map.of("label", "", "value", "v")),
                            "default", "v"),
                    field("name", "ok", "type", "bool"));

            List<String> issues = FormFieldCanonicalizer.canonicalize(p);

            assertThat(issues).hasSize(1);
            List<Map<String, Object>> fields = fieldsOf(p);
            assertThat(fields.get(0)).containsEntry("id", "field-0").containsEntry("defaultValue", "v");
            assertThat(fields.get(1)).containsEntry("id", "field-1").containsEntry("type", "checkbox");
        }

        @Test
        @DisplayName("a second pass over canonical fields changes nothing")
        void idempotent() {
            Map<String, Object> p = params(
                    field("name", "tier", "type", "select", "options", List.of("free", "pro"), "default", "pro"),
                    field("name", "theme", "type", "text", "required", true));
            FormFieldCanonicalizer.canonicalize(p);
            List<Map<String, Object>> once = new ArrayList<>();
            fieldsOf(p).forEach(f -> once.add(new LinkedHashMap<>(f)));

            assertThat(FormFieldCanonicalizer.canonicalize(p)).isEmpty();

            assertThat(fieldsOf(p)).isEqualTo(once);
        }

        @Test
        @DisplayName("immutable input is copied, never mutated; no fields, or an empty list, is not a problem")
        void immutableInputAndNoFields() {
            Map<String, Object> immutableField = Map.of("name", "a", "type", "text", "default", "x");
            Map<String, Object> p = params(immutableField);

            assertThat(FormFieldCanonicalizer.canonicalize(p)).isEmpty();

            assertThat(immutableField).containsKey("default");
            assertThat(fieldsOf(p).get(0)).containsEntry("defaultValue", "x");
            assertThat(FormFieldCanonicalizer.canonicalize(new LinkedHashMap<>())).isEmpty();
            assertThat(FormFieldCanonicalizer.canonicalize(new LinkedHashMap<>(Map.of("fields", List.of())))).isEmpty();
        }

        @Test
        @DisplayName("a type takes the one spelling the renderers match exactly (SELECT, Text, checkboxgroup)")
        void typeSpelling() {
            Map<String, Object> p = params(
                    field("name", "a", "type", "SELECT", "options", List.of("x")),
                    field("name", "b", "type", "Text"),
                    field("name", "c", "type", "checkboxgroup", "options", List.of("y")),
                    field("name", "d", "type", "checkboxGroup", "options", List.of("z")));

            assertThat(FormFieldCanonicalizer.canonicalize(p)).isEmpty();

            assertThat(fieldsOf(p)).extracting(f -> f.get("type"))
                    .containsExactly("select", "text", "checkboxGroup", "checkboxGroup");
        }

        @Test
        @DisplayName("an auto-filled id never collides with an id the caller wrote")
        void autoIdNeverCollides() {
            Map<String, Object> p = params(
                    field("name", "a", "type", "text", "id", "field-1"),
                    field("name", "b", "type", "text"));

            FormFieldCanonicalizer.canonicalize(p);

            assertThat(fieldsOf(p)).extracting(f -> f.get("id")).containsExactly("field-1", "field-1-1");
        }
    }
}
