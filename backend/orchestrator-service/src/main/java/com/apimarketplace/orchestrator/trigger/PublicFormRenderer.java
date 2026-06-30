package com.apimarketplace.orchestrator.trigger;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a self-contained HTML page for a public form-trigger endpoint.
 *
 * The page is served at GET /form/{token} so the share URL can be opened
 * directly in a browser. The page fetches nothing external: field schema is
 * inlined at render time, submission is a fetch() to POST /form/{token}.
 */
@Component
public class PublicFormRenderer {

    public String renderPage(String token, Map<String, Object> config) {
        String name = asString(config.get("name"), "Form");
        String description = asString(config.get("description"), "");
        String successMessage = asString(config.get("successMessage"),
                "Thanks - your submission was received.");
        boolean isActive = Boolean.TRUE.equals(config.get("isActive"));
        List<Map<String, Object>> fields = asFieldList(config.get("formConfig"));

        StringBuilder fieldsHtml = new StringBuilder();
        for (Map<String, Object> field : fields) {
            if (field == null) continue;
            fieldsHtml.append(renderField(field));
        }

        String inactiveBanner = isActive ? "" :
                "<div class=\"banner banner-warn\">This form is currently inactive.</div>";

        Map<String, String> values = new HashMap<>();
        values.put("TITLE", escapeHtml(name));
        values.put("NAME", escapeHtml(name));
        values.put("DESCRIPTION", description.isEmpty() ? "" :
                "<p class=\"description\">" + escapeHtml(description) + "</p>");
        values.put("FIELDS", fieldsHtml.toString());
        values.put("SUBMIT_URL", "/form/" + escapeJs(token));
        values.put("SUCCESS_MESSAGE", escapeJs(successMessage));
        values.put("INACTIVE_BANNER", inactiveBanner);
        values.put("SUBMIT_DISABLED", isActive ? "" : "disabled");

        return applyTemplate(BASE_TEMPLATE, values);
    }

    /**
     * Single-pass marker substitution. Any {{MARKER}} sequence that survives in
     * a substituted value (e.g. a user-controlled "name" field set to "{{FIELDS}}")
     * is left literal instead of re-expanding in a subsequent pass.
     */
    static String applyTemplate(String template, Map<String, String> values) {
        Matcher m = MARKER_PATTERN.matcher(template);
        StringBuilder out = new StringBuilder(template.length());
        while (m.find()) {
            String key = m.group(1);
            String replacement = values.getOrDefault(key, m.group());
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static final Pattern MARKER_PATTERN = Pattern.compile("\\{\\{([A-Z_]+)\\}\\}");

    public String renderNotFound() {
        return NOT_FOUND_TEMPLATE;
    }

    private String renderField(Map<String, Object> field) {
        String fieldName = asString(field.get("name"), "");
        if (fieldName.isEmpty()) {
            return "";
        }
        String label = asString(field.get("label"), fieldName);
        String type = asString(field.get("type"), "text").toLowerCase();
        boolean required = Boolean.TRUE.equals(field.get("required"));
        String placeholder = asString(field.get("placeholder"), "");

        String inputHtml = switch (type) {
            case "textarea" -> "<textarea id=\"f_" + escapeHtml(fieldName) +
                    "\" name=\"" + escapeHtml(fieldName) + "\"" +
                    (required ? " required" : "") +
                    (placeholder.isEmpty() ? "" : " placeholder=\"" + escapeHtml(placeholder) + "\"") +
                    " rows=\"4\"></textarea>";
            case "select" -> renderSelect(fieldName, field, required, placeholder, false);
            case "multiselect" -> renderSelect(fieldName, field, required, placeholder, true);
            case "radio" -> renderChoiceGroup(fieldName, field, required, "radio");
            case "checkboxgroup" -> renderChoiceGroup(fieldName, field, required, "checkbox");
            case "checkbox" -> "<label class=\"inline\"><input id=\"f_" + escapeHtml(fieldName) +
                    "\" name=\"" + escapeHtml(fieldName) + "\" type=\"checkbox\" value=\"true\" />" +
                    (placeholder.isEmpty() ? "" : " " + escapeHtml(placeholder)) + "</label>";
            default -> "<input id=\"f_" + escapeHtml(fieldName) +
                    "\" name=\"" + escapeHtml(fieldName) + "\"" +
                    " type=\"" + escapeHtml(mapInputType(type)) + "\"" +
                    (required ? " required" : "") +
                    (placeholder.isEmpty() ? "" : " placeholder=\"" + escapeHtml(placeholder) + "\"") +
                    " />";
        };

        return "<div class=\"field\">" +
                "<label for=\"f_" + escapeHtml(fieldName) + "\">" + escapeHtml(label) +
                (required ? " <span class=\"req\">*</span>" : "") + "</label>" +
                inputHtml +
                "</div>";
    }

    /**
     * Render a {@code <select>} (or {@code <select multiple>}) populated from
     * {@code field.options}. Tolerant of both the canonical {@code [{label,
     * value}]} shape AND the string-shorthand {@code ["a","b"]} that
     * pre-V160-fix plans may still carry - the latter is treated as
     * {@code label = value = string}. Drops malformed entries silently rather
     * than emitting {@code <option>undefined</option>}.
     */
    private String renderSelect(String fieldName, Map<String, Object> field,
                                boolean required, String placeholder, boolean multiple) {
        StringBuilder html = new StringBuilder();
        html.append("<select id=\"f_").append(escapeHtml(fieldName))
            .append("\" name=\"").append(escapeHtml(fieldName)).append("\"");
        if (required) html.append(" required");
        if (multiple) html.append(" multiple size=\"4\"");
        html.append(">");
        if (!multiple) {
            String firstLabel = placeholder.isEmpty() ? "Select…" : placeholder;
            html.append("<option value=\"\"")
                .append(required ? " disabled selected" : "")
                .append(">").append(escapeHtml(firstLabel)).append("</option>");
        }
        for (Map<String, Object> opt : asOptionList(field.get("options"))) {
            String value = asString(opt.get("value"), "");
            String optLabel = asString(opt.get("label"), value);
            if (value.isEmpty()) continue;
            html.append("<option value=\"").append(escapeHtml(value)).append("\">")
                .append(escapeHtml(optLabel)).append("</option>");
        }
        html.append("</select>");
        return html.toString();
    }

    /**
     * Render a list of {@code <input type="radio|checkbox">} backed by
     * {@code field.options}. Same shape tolerance as {@link #renderSelect}.
     * For checkbox-groups the inputs share a {@code name[]} so the runtime
     * receives an array under the field name.
     */
    private String renderChoiceGroup(String fieldName, Map<String, Object> field,
                                     boolean required, String inputType) {
        StringBuilder html = new StringBuilder();
        html.append("<div class=\"choice-group\">");
        boolean first = true;
        boolean multi = "checkbox".equals(inputType);
        String submitName = multi ? fieldName + "[]" : fieldName;
        for (Map<String, Object> opt : asOptionList(field.get("options"))) {
            String value = asString(opt.get("value"), "");
            String optLabel = asString(opt.get("label"), value);
            if (value.isEmpty()) continue;
            String optId = "f_" + fieldName + "_" + value;
            html.append("<label class=\"inline\"><input id=\"").append(escapeHtml(optId))
                .append("\" name=\"").append(escapeHtml(submitName))
                .append("\" type=\"").append(escapeHtml(inputType))
                .append("\" value=\"").append(escapeHtml(value)).append("\"")
                .append(required && first && !multi ? " required" : "")
                .append(" /> ").append(escapeHtml(optLabel)).append("</label>");
            first = false;
        }
        html.append("</div>");
        return html.toString();
    }

    /**
     * Coerce a raw {@code options} value (from the plan params JSONB) into a
     * normalized list of {@code {label, value}} maps. Mirrors the frontend
     * {@code normalizeFieldOptions} so the public form always sees the same
     * canonical shape regardless of how the plan was authored.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asOptionList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new java.util.ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) {
                result.add(Map.of("label", s, "value", s));
            } else if (item instanceof Map<?, ?> m) {
                result.add((Map<String, Object>) m);
            }
        }
        return result;
    }

    private String mapInputType(String type) {
        return switch (type.toLowerCase()) {
            case "email" -> "email";
            case "number" -> "number";
            case "url" -> "url";
            case "tel", "phone" -> "tel";
            case "date" -> "date";
            case "datetime", "datetime-local" -> "datetime-local";
            case "time" -> "time";
            case "file" -> "file";
            case "password" -> "password";
            case "hidden" -> "hidden";
            default -> "text";
        };
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asFieldList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new java.util.ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                result.add((Map<String, Object>) m);
            }
        }
        return result;
    }

    private String asString(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    static String escapeHtml(String input) {
        if (input == null) return "";
        StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    static String escapeJs(String input) {
        if (input == null) return "";
        StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\'' -> out.append("\\'");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
                case '&' -> out.append("\\u0026");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static final String BASE_TEMPLATE = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>{{TITLE}}</title>
              <style>
                :root { color-scheme: light dark; }
                body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                  max-width: 560px; margin: 3rem auto; padding: 0 1.25rem; color: #111827;
                  background-color: #f9fafb; }
                @media (prefers-color-scheme: dark) {
                  body { color: #e5e7eb; background-color: #0f172a; }
                  .card { background: #1e293b; border-color: #334155; }
                  input, textarea { background: #0f172a; color: #e5e7eb; border-color: #334155; }
                }
                h1 { font-size: 1.5rem; margin-bottom: .5rem; }
                .description { color: #6b7280; margin: 0 0 1.5rem; }
                .card { background: #fff; border: 1px solid #e5e7eb; border-radius: 12px;
                  padding: 1.5rem; box-shadow: 0 1px 2px rgba(0,0,0,0.04); }
                .field { margin-bottom: 1rem; }
                label { display: block; font-weight: 600; font-size: .9rem; margin-bottom: .35rem; }
                .req { color: #dc2626; }
                input, textarea, select { width: 100%; box-sizing: border-box; padding: .6rem .75rem;
                  border: 1px solid #d1d5db; border-radius: 8px; font-size: 1rem; font-family: inherit; }
                input:focus, textarea:focus, select:focus { outline: 2px solid #3b82f6; outline-offset: 0; border-color: transparent; }
                input[type="checkbox"], input[type="radio"] { width: auto; }
                .choice-group { display: flex; flex-direction: column; gap: .4rem; }
                label.inline { display: inline-flex; align-items: center; gap: .5rem; font-weight: 400; margin-bottom: 0; }
                button { background: #2563eb; color: #fff; border: 0; border-radius: 8px;
                  padding: .7rem 1.25rem; font-size: 1rem; font-weight: 600; cursor: pointer; }
                button[disabled] { background: #9ca3af; cursor: not-allowed; }
                .banner { padding: .75rem 1rem; border-radius: 8px; margin-bottom: 1rem; font-size: .9rem; }
                .banner-warn { background: #fef3c7; color: #92400e; }
                .banner-ok { background: #d1fae5; color: #065f46; }
                .banner-err { background: #fee2e2; color: #991b1b; }
              </style>
            </head>
            <body>
              <h1>{{NAME}}</h1>
              {{DESCRIPTION}}
              {{INACTIVE_BANNER}}
              <div class="card">
                <div id="status" role="status" aria-live="polite"></div>
                <form id="public-form" novalidate>
                  {{FIELDS}}
                  <button type="submit" {{SUBMIT_DISABLED}}>Submit</button>
                </form>
              </div>
              <script>
                (function() {
                  var form = document.getElementById('public-form');
                  var status = document.getElementById('status');
                  var submitUrl = "{{SUBMIT_URL}}";
                  var successMessage = "{{SUCCESS_MESSAGE}}";

                  form.addEventListener('submit', function(ev) {
                    ev.preventDefault();
                    status.innerHTML = '';
                    var btn = form.querySelector('button[type=submit]');
                    btn.disabled = true;
                    var payload = {};
                    var fd = new FormData(form);
                    var seenKeys = {};
                    fd.forEach(function(value, key) {
                      // Strip the trailing "[]" suffix used to mark
                      // checkbox-group fields server-side, then collect
                      // multiple values under a single array.
                      var realKey = key.endsWith('[]') ? key.slice(0, -2) : key;
                      if (seenKeys[realKey]) {
                        if (!Array.isArray(payload[realKey])) {
                          payload[realKey] = [payload[realKey]];
                        }
                        payload[realKey].push(value);
                      } else {
                        payload[realKey] = value;
                        seenKeys[realKey] = true;
                      }
                    });

                    fetch(submitUrl, {
                      method: 'POST',
                      headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
                      body: JSON.stringify(payload)
                    }).then(function(res) {
                      return res.json().then(function(body) { return { ok: res.ok, body: body }; });
                    }).then(function(result) {
                      if (result.ok && result.body && result.body.status !== 'error') {
                        status.innerHTML = '<div class="banner banner-ok"></div>';
                        status.firstChild.textContent = successMessage;
                        form.reset();
                      } else {
                        var msg = (result.body && (result.body.error || result.body.message)) || 'Submission failed.';
                        status.innerHTML = '<div class="banner banner-err"></div>';
                        status.firstChild.textContent = msg;
                      }
                    }).catch(function() {
                      status.innerHTML = '<div class="banner banner-err">Network error - please try again.</div>';
                    }).finally(function() {
                      btn.disabled = false;
                    });
                  });
                })();
              </script>
            </body>
            </html>
            """;

    private static final String NOT_FOUND_TEMPLATE = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="UTF-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>Form not found</title>
              <style>
                body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                  max-width: 420px; margin: 4rem auto; text-align: center; color: #111827; }
                h1 { font-size: 1.5rem; }
                p { color: #6b7280; }
              </style>
            </head>
            <body>
              <h1>Form not found</h1>
              <p>This link is no longer valid or the form has been removed.</p>
            </body>
            </html>
            """;
}
