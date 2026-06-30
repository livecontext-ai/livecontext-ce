package com.apimarketplace.orchestrator.services.interfaces;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mirrors the {@code {{var|default}}} substitution convention used by the interface frontend
 * templating (see {@code interfaceHtmlUtils.ts}). NOT Handlebars: only simple identifier names,
 * one optional pipe-default, no expressions, no helpers.
 *
 * <p>Single source of truth for backend-side variable resolution into interface templates. Used by:
 * <ul>
 *   <li>{@link InterfaceScreenshotServiceImpl} - to produce the HTML that ships to the Playwright
 *       sidecar so the captured PNG matches the iframe.</li>
 *   <li>{@code InterfaceNode#resolveRenderedSource} - to produce the {@code rendered_html} /
 *       {@code rendered_css} / {@code rendered_js} outputs so downstream nodes consume the same
 *       resolved strings the user sees in the iframe.</li>
 * </ul>
 */
public final class InterfaceTemplateDefaults {

    // Captures: 1=var name, 2=optional default.
    private static final Pattern TEMPLATE_VAR =
        Pattern.compile("\\{\\{\\s*([a-zA-Z_][\\w]*)\\s*(?:\\|([^}]*))?\\}\\}");

    private InterfaceTemplateDefaults() {
        // util
    }

    /**
     * Substitute every {@code {{name|default}}} placeholder in {@code template} with the
     * stringified value from {@code vars.get(name)}, falling back to the inline default (or
     * empty string if no default). Null/empty template returns the empty string so callers can
     * always concatenate the result safely.
     */
    public static String apply(String template, Map<String, Object> vars) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        Matcher m = TEMPLATE_VAR.matcher(template);
        StringBuilder out = new StringBuilder(template.length());
        while (m.find()) {
            String name = m.group(1);
            String dflt = m.group(2);
            Object value = vars != null ? vars.get(name) : null;
            String replacement = (value != null)
                ? stringifyValue(value)
                : (dflt != null ? dflt : "");
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Stringify a resolved variable for inline substitution. Detects FileRef-shaped maps (with
     * {@code path} and {@code mimeType} keys, mirroring {@code FileRef.java}) and renders just
     * the storage path so downstream {@code <img src="...">} attributes get a valid URL fragment
     * instead of the raw {@code Map.toString()} dump. NOT a full mirror of
     * {@code interfaceHtmlUtils.injectFileProxyToken} - true tokenization stays a frontend
     * concern; the substituted text shows the raw path which is good enough for most
     * preview/screenshot/agent-input use cases.
     */
    static String stringifyValue(Object value) {
        if (value instanceof Map<?, ?> map && map.containsKey("path") && map.containsKey("mimeType")) {
            Object path = map.get("path");
            return path != null ? String.valueOf(path) : "";
        }
        return String.valueOf(value);
    }
}
