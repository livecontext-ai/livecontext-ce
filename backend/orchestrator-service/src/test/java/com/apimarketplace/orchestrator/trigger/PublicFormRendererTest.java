package com.apimarketplace.orchestrator.trigger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class PublicFormRendererTest {

    private final PublicFormRenderer renderer = new PublicFormRenderer();

    @Test
    @DisplayName("Renders label, input, and POST URL for a simple text field")
    void rendersSimpleField() {
        Map<String, Object> config = Map.of(
                "name", "Greet",
                "description", "Enter your name",
                "isActive", true,
                "successMessage", "Thanks!",
                "formConfig", List.of(Map.of(
                        "name", "username",
                        "label", "Your name",
                        "type", "text",
                        "required", true
                ))
        );

        String html = renderer.renderPage("fm_TOKEN", config);

        assertThat(html).contains("<h1>Greet</h1>");
        assertThat(html).contains("Enter your name");
        assertThat(html).contains("id=\"f_username\"");
        assertThat(html).contains("name=\"username\"");
        assertThat(html).contains("type=\"text\"");
        assertThat(html).contains(" required");
        assertThat(html).contains("submitUrl = \"/form/fm_TOKEN\"");
        assertThat(html).contains("successMessage = \"Thanks!\"");
    }

    @Test
    @DisplayName("Escapes HTML metacharacters in name, description, and field label to prevent XSS")
    void escapesHtmlInUntrustedFields() {
        Map<String, Object> config = Map.of(
                "name", "<script>alert(1)</script>",
                "description", "desc \"with\" & <b>html</b>",
                "isActive", true,
                "successMessage", "ok",
                "formConfig", List.of(Map.of(
                        "name", "field1",
                        "label", "<img onerror=x>",
                        "type", "text"
                ))
        );

        String html = renderer.renderPage("tok", config);

        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(html).contains("&lt;img onerror=x&gt;");
        assertThat(html).contains("&quot;with&quot;");
        assertThat(html).contains("&amp;");
    }

    @Test
    @DisplayName("Escapes JS-dangerous characters in token and successMessage inside inline <script>")
    void escapesJsContext() {
        Map<String, Object> config = Map.of(
                "name", "F",
                "description", "",
                "isActive", true,
                "successMessage", "Thanks</script><script>alert(1)</script>",
                "formConfig", List.of()
        );

        String html = renderer.renderPage("tok\"evil", config);

        assertThat(html).doesNotContain("</script><script>alert(1)</script>");
        assertThat(html).contains("\\u003c/script\\u003e");
        assertThat(html).contains("tok\\\"evil");
    }

    @Test
    @DisplayName("Shows inactive banner and disables submit button when form is deactivated")
    void inactiveFormShowsBannerAndDisablesSubmit() {
        Map<String, Object> config = Map.of(
                "name", "Form",
                "description", "",
                "isActive", false,
                "successMessage", "ok",
                "formConfig", List.of(Map.of("name", "x", "label", "X", "type", "text"))
        );

        String html = renderer.renderPage("tok", config);

        assertThat(html).contains("This form is currently inactive.");
        assertThat(html).contains("<button type=\"submit\" disabled>");
    }

    @Test
    @DisplayName("Maps type=textarea to <textarea> element with rows=4")
    void rendersTextareaForTextareaType() {
        Map<String, Object> config = Map.of(
                "name", "F",
                "description", "",
                "isActive", true,
                "successMessage", "ok",
                "formConfig", List.of(Map.of(
                        "name", "bio",
                        "label", "Biography",
                        "type", "textarea"
                ))
        );

        String html = renderer.renderPage("tok", config);

        assertThat(html).contains("<textarea id=\"f_bio\"");
        assertThat(html).contains("rows=\"4\"");
        assertThat(html).doesNotContain("<input id=\"f_bio\"");
    }

    @Test
    @DisplayName("Maps semantic types to matching HTML5 input types (email, number, date, tel)")
    void mapsSemanticInputTypes() {
        Map<String, Object> config = Map.of(
                "name", "F", "description", "", "isActive", true, "successMessage", "ok",
                "formConfig", List.of(
                        Map.of("name", "em", "label", "Email", "type", "email"),
                        Map.of("name", "nm", "label", "Count", "type", "number"),
                        Map.of("name", "dt", "label", "Date", "type", "date"),
                        Map.of("name", "ph", "label", "Phone", "type", "phone")
                )
        );

        String html = renderer.renderPage("tok", config);

        assertThat(html).contains("id=\"f_em\" name=\"em\" type=\"email\"");
        assertThat(html).contains("id=\"f_nm\" name=\"nm\" type=\"number\"");
        assertThat(html).contains("id=\"f_dt\" name=\"dt\" type=\"date\"");
        assertThat(html).contains("id=\"f_ph\" name=\"ph\" type=\"tel\"");
    }

    @Test
    @DisplayName("Omits description paragraph when description is empty")
    void omitsEmptyDescription() {
        Map<String, Object> config = Map.of(
                "name", "F", "description", "", "isActive", true, "successMessage", "ok",
                "formConfig", List.of()
        );

        String html = renderer.renderPage("tok", config);

        assertThat(html).doesNotContain("<p class=\"description\">");
    }

    @Test
    @DisplayName("renderNotFound returns a standalone HTML page with 'Form not found' heading")
    void renderNotFoundReturnsStandalonePage() {
        String html = renderer.renderNotFound();

        assertThat(html).contains("<!DOCTYPE html>");
        assertThat(html).contains("<h1>Form not found</h1>");
        assertThat(html).doesNotContain("<form");
    }

    @Test
    @DisplayName("Skips fields with missing name rather than emitting a malformed <input>")
    void skipsFieldsWithoutName() {
        Map<String, Object> config = Map.of(
                "name", "F", "description", "", "isActive", true, "successMessage", "ok",
                "formConfig", List.of(
                        Map.of("label", "Orphan", "type", "text"),
                        Map.of("name", "ok", "label", "Valid", "type", "text")
                )
        );

        String html = renderer.renderPage("tok", config);

        assertThat(html).contains("id=\"f_ok\"");
        assertThat(html).doesNotContain("name=\"\"");
        assertThat(html).doesNotContain(">Orphan<");
    }

    @Test
    @DisplayName("User-controlled name='{{FIELDS}}' does NOT re-expand into rendered field HTML")
    void preventsTemplateReExpansion() {
        Map<String, Object> config = Map.of(
                "name", "{{FIELDS}}",
                "description", "",
                "isActive", true,
                "successMessage", "{{INACTIVE_BANNER}}",
                "formConfig", List.of(Map.of("name", "x", "label", "X", "type", "text"))
        );

        String html = renderer.renderPage("tok", config);

        assertThat(html).contains("<h1>{{FIELDS}}</h1>");
        assertThat(html).contains("successMessage = \"{{INACTIVE_BANNER}}\"");
        // There is exactly ONE field block - the marker did not pull in a second copy.
        assertThat(countOccurrences(html, "id=\"f_x\"")).isEqualTo(1);
    }

    @Test
    @DisplayName("Null formConfig is treated as no fields and renders cleanly")
    void nullFormConfigRendersEmptyFields() {
        Map<String, Object> config = new HashMap<>();
        config.put("name", "F");
        config.put("description", "");
        config.put("isActive", true);
        config.put("successMessage", "ok");
        config.put("formConfig", null);

        String html = renderer.renderPage("tok", config);

        assertThat(html).contains("<h1>F</h1>");
        assertThat(html).contains("<form id=\"public-form\" novalidate>");
        assertThat(html).doesNotContain("id=\"f_");
    }

    @Test
    @DisplayName("Non-Map entries in formConfig are skipped rather than crashing")
    void skipsNonMapFieldEntries() {
        Map<String, Object> config = Map.of(
                "name", "F", "description", "", "isActive", true, "successMessage", "ok",
                "formConfig", Arrays.asList(
                        "not-a-map",
                        Map.of("name", "ok", "label", "Valid", "type", "text"),
                        42
                )
        );

        assertThatCode(() -> renderer.renderPage("tok", config)).doesNotThrowAnyException();
        String html = renderer.renderPage("tok", config);
        assertThat(html).contains("id=\"f_ok\"");
    }

    @Test
    @DisplayName("Placeholder attribute is rendered and HTML-escaped")
    void placeholderIsRenderedAndEscaped() {
        Map<String, Object> config = Map.of(
                "name", "F", "description", "", "isActive", true, "successMessage", "ok",
                "formConfig", List.of(Map.of(
                        "name", "nick",
                        "label", "Nick",
                        "type", "text",
                        "placeholder", "e.g. <bob> & \"alice\""
                ))
        );

        String html = renderer.renderPage("tok", config);

        assertThat(html).contains("placeholder=\"e.g. &lt;bob&gt; &amp; &quot;alice&quot;\"");
        assertThat(html).doesNotContain("e.g. <bob>");
    }

    @Test
    @DisplayName("Submit URL is emitted as exactly /form/<escaped-token> inside a JS string")
    void submitUrlFormatIsStable() {
        Map<String, Object> config = Map.of(
                "name", "F", "description", "", "isActive", true, "successMessage", "ok",
                "formConfig", List.of()
        );

        String html = renderer.renderPage("fm_abc123", config);
        assertThat(html).contains("submitUrl = \"/form/fm_abc123\"");

        String evilHtml = renderer.renderPage("tok\"</script>", config);
        assertThat(evilHtml).contains("submitUrl = \"/form/tok\\\"\\u003c/script\\u003e\"");
        assertThat(evilHtml).doesNotContain("</script>\";");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    /**
     * V161 hardening: pre-fix, {@code select / multiselect / radio /
     * checkboxGroup} fields all fell through {@code mapInputType} to
     * {@code <input type="text">}, so the public form was unusable for the
     * model-picker workflow that opened this bug. These tests lock in the new
     * render branches and the tolerance for both canonical {label, value}
     * objects and the legacy string-array shorthand.
     */
    @Nested
    @DisplayName("V161 - select/multiselect/radio/checkboxGroup rendering")
    class V161OptionBearingFieldRendering {

        private Map<String, Object> formConfig(List<Map<String, Object>> fields) {
            Map<String, Object> cfg = new HashMap<>();
            cfg.put("name", "Test Form");
            cfg.put("description", "");
            cfg.put("isActive", true);
            cfg.put("successMessage", "ok");
            cfg.put("formConfig", fields);
            return cfg;
        }

        @Test
        @DisplayName("select renders a real <select> with one <option> per canonical {label, value}")
        void rendersSelectWithCanonicalOptions() {
            Map<String, Object> field = Map.of(
                    "name", "model",
                    "type", "select",
                    "label", "Model",
                    "required", true,
                    "options", List.of(
                            Map.of("label", "GPT Image 2", "value", "gpt-image-2"),
                            Map.of("label", "GPT Image 1.5", "value", "gpt-image-1-5")
                    )
            );

            String html = renderer.renderPage("tok", formConfig(List.of(field)));

            assertThat(html)
                    .contains("<select id=\"f_model\" name=\"model\" required>")
                    .contains("<option value=\"gpt-image-2\">GPT Image 2</option>")
                    .contains("<option value=\"gpt-image-1-5\">GPT Image 1.5</option>");
            // Pre-V161 fallthrough emitted <input id="f_model" ... type="text" />
            assertThat(html).doesNotContain("<input id=\"f_model\"");
        }

        @Test
        @DisplayName("select tolerates string-array shorthand from pre-coercion legacy plans")
        void rendersSelectFromStringArrayShorthand() {
            Map<String, Object> field = Map.of(
                    "name", "tier",
                    "type", "select",
                    "label", "Tier",
                    "options", List.of("free", "pro")
            );

            String html = renderer.renderPage("tok", formConfig(List.of(field)));

            assertThat(html)
                    .contains("<option value=\"free\">free</option>")
                    .contains("<option value=\"pro\">pro</option>");
        }

        @Test
        @DisplayName("Malformed options (empty string, missing value, wrong type) are dropped - no <option>undefined</option>")
        void dropsMalformedOptions() {
            List<Object> options = new ArrayList<>();
            options.add(Map.of("label", "Good", "value", "good"));
            options.add("");                              // empty string
            options.add(Map.of("label", "Missing value")); // no value key
            options.add(123);                              // wrong type

            Map<String, Object> field = new LinkedHashMap<>();
            field.put("name", "model");
            field.put("type", "select");
            field.put("label", "Model");
            field.put("options", options);

            String html = renderer.renderPage("tok", formConfig(List.of(field)));

            assertThat(html).contains("<option value=\"good\">Good</option>");
            assertThat(html).doesNotContain("undefined");
            assertThat(html).doesNotContain(">Missing value<");
        }

        @Test
        @DisplayName("multiselect renders <select multiple> without the empty placeholder option")
        void multiselectIsMulti() {
            Map<String, Object> field = Map.of(
                    "name", "tags",
                    "type", "multiselect",
                    "label", "Tags",
                    "options", List.of(
                            Map.of("label", "A", "value", "a"),
                            Map.of("label", "B", "value", "b")
                    )
            );

            String html = renderer.renderPage("tok", formConfig(List.of(field)));

            assertThat(html).contains("<select id=\"f_tags\" name=\"tags\" multiple size=\"4\">");
            assertThat(html).contains("<option value=\"a\">A</option>");
        }

        @Test
        @DisplayName("radio renders one <input type=radio> per option")
        void radioRenders() {
            Map<String, Object> field = Map.of(
                    "name", "size",
                    "type", "radio",
                    "label", "Size",
                    "required", true,
                    "options", List.of(
                            Map.of("label", "S", "value", "s"),
                            Map.of("label", "M", "value", "m")
                    )
            );

            String html = renderer.renderPage("tok", formConfig(List.of(field)));

            assertThat(html).contains("type=\"radio\" value=\"s\"");
            assertThat(html).contains("type=\"radio\" value=\"m\"");
        }

        @Test
        @DisplayName("checkboxGroup uses name[] suffix so client serializes selected values to an array")
        void checkboxGroupUsesArraySuffix() {
            Map<String, Object> field = Map.of(
                    "name", "tags",
                    "type", "checkboxGroup",
                    "label", "Tags",
                    "options", List.of(
                            Map.of("label", "A", "value", "a"),
                            Map.of("label", "B", "value", "b")
                    )
            );

            String html = renderer.renderPage("tok", formConfig(List.of(field)));

            assertThat(html).contains("name=\"tags[]\"");
            assertThat(html).contains("type=\"checkbox\" value=\"a\"");
            assertThat(html).contains("type=\"checkbox\" value=\"b\"");
        }

        @Test
        @DisplayName("Submit JS folds repeated FormData entries (name[]) into an array on the payload key")
        void submitJsFoldsRepeatedKeysIntoArrays() {
            String html = renderer.renderPage("tok", formConfig(List.of()));

            // The hand-rolled fd.forEach loop now strips trailing "[]" and collects
            // multi-value entries into a JS array on the payload object.
            assertThat(html).contains("key.endsWith('[]') ? key.slice(0, -2) : key");
            assertThat(html).contains("payload[realKey] = [payload[realKey]];");
        }
    }

    /**
     * 2026-09-29: the builder promises that a form field's default "pre-fills the public form", but
     * this page (the formUrl every form endpoint hands out) rendered no value at all, whatever the
     * key was spelled. The Next /f and /s pages did; this one never had.
     */
    @Nested
    @DisplayName("Field defaults are pre-filled")
    class DefaultPrefill {

        private String render(Map<String, Object> field) {
            return renderer.renderPage("tok", Map.of("name", "F", "isActive", true, "formConfig", List.of(field)));
        }

        private Map<String, Object> field(Object... kv) {
            Map<String, Object> f = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) f.put((String) kv[i], kv[i + 1]);
            return f;
        }

        @Test
        @DisplayName("text, number and date inputs carry the default as their value, HTML-escaped")
        void inputsCarryTheDefault() {
            assertThat(render(field("name", "theme", "type", "text", "defaultValue", "Innovation & \"Futur\"")))
                    .contains("name=\"theme\" type=\"text\" value=\"Innovation &amp; &quot;Futur&quot;\"");
            assertThat(render(field("name", "n", "type", "number", "defaultValue", 2000)))
                    .contains("type=\"number\" value=\"2000\"");
            assertThat(render(field("name", "d", "type", "date", "defaultValue", "2026-09-29")))
                    .contains("type=\"date\" value=\"2026-09-29\"");
        }

        @Test
        @DisplayName("the `default` alias an older plan carries is honoured the same way")
        void aliasIsHonoured() {
            assertThat(render(field("name", "theme", "type", "text", "default", "Innovation")))
                    .contains("value=\"Innovation\"");
        }

        @Test
        @DisplayName("a textarea holds the default as its content, escaped")
        void textareaContent() {
            assertThat(render(field("name", "bio", "type", "textarea", "defaultValue", "<b>hi</b>")))
                    .contains("rows=\"4\">&lt;b&gt;hi&lt;/b&gt;</textarea>");
        }

        @Test
        @DisplayName("a select marks the default option selected, and the required placeholder is no longer selected")
        void selectMarksTheDefault() {
            String html = render(field("name", "tier", "type", "select", "required", true, "defaultValue", "pro",
                    "options", List.of(Map.of("label", "Free", "value", "free"), Map.of("label", "Pro", "value", "pro"))));

            assertThat(html).contains("<option value=\"\" disabled>");
            assertThat(html).contains("<option value=\"pro\" selected>Pro</option>");
            assertThat(html).contains("<option value=\"free\">Free</option>");
        }

        @Test
        @DisplayName("a default that matches no option leaves the required placeholder selected")
        void unmatchedDefaultKeepsThePlaceholder() {
            String html = render(field("name", "tier", "type", "select", "required", true, "defaultValue", "gold",
                    "options", List.of("free", "pro")));

            assertThat(html).contains("<option value=\"\" disabled selected>");
            assertThat(html).doesNotContain("\" selected>free").doesNotContain("\" selected>pro");
        }

        @Test
        @DisplayName("a multiselect and a checkbox group select every value of a list default")
        void listDefaults() {
            String multi = render(field("name", "tags", "type", "multiselect", "defaultValue", List.of("a", "c"),
                    "options", List.of("a", "b", "c")));
            assertThat(multi).contains("<option value=\"a\" selected>").contains("<option value=\"b\">")
                    .contains("<option value=\"c\" selected>");

            String group = render(field("name", "days", "type", "checkboxGroup", "defaultValue", List.of("mon"),
                    "options", List.of("mon", "tue")));
            assertThat(group).contains("value=\"mon\" checked").doesNotContain("value=\"tue\" checked");
        }

        @Test
        @DisplayName("a radio group checks the default; a checkbox is checked by a true default only")
        void radioAndCheckbox() {
            assertThat(render(field("name", "size", "type", "radio", "defaultValue", "m", "options", List.of("s", "m"))))
                    .contains("value=\"m\" checked").doesNotContain("value=\"s\" checked");
            assertThat(render(field("name", "ok", "type", "checkbox", "defaultValue", true)))
                    .contains("type=\"checkbox\" value=\"true\" checked");
            assertThat(render(field("name", "ok", "type", "checkbox", "defaultValue", false)))
                    .doesNotContain("checked");
        }

        @Test
        @DisplayName("a file input never carries a value, and a field with no default renders as before")
        void fileAndNoDefault() {
            assertThat(render(field("name", "doc", "type", "file", "defaultValue", "x.pdf")))
                    .doesNotContain("value=\"x.pdf\"");
            assertThat(render(field("name", "theme", "type", "text")))
                    .contains("name=\"theme\" type=\"text\" />")
                    .doesNotContain("value=");
        }
    }
}

