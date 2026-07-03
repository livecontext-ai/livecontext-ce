package com.apimarketplace.orchestrator.services.template;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes the two author-facing workflow-variable forms to the single
 * internal one before any SpEL transform runs:
 *
 * <ul>
 *   <li>{@code $vars.name} (canonical, n8n-style) - the leading {@code $} is
 *       not a SpEL identifier character, so without normalization the token
 *       scan would split it into a literal {@code $} + identifier and produce
 *       invalid SpEL.</li>
 *   <li>{@code vars:name} (alias, matches the {@code type:label} grammar).</li>
 * </ul>
 *
 * Both become {@code vars.name}, which the namespace path resolves via
 * NamespaceResolver's {@code vars} branch and the Map path navigates through
 * the {@code vars} bundle entry of the eval context.
 *
 * <p>Occurrences inside SpEL protected regions (string literals,
 * selection/projection brackets) are left untouched - a literal
 * {@code '$vars.x'} inside a string argument is data, not a reference.
 */
public final class VarsSyntaxNormalizer {

    /** Reserved namespace both forms collapse to. */
    public static final String VARS_NAMESPACE = "vars";

    // $vars.name  OR  vars:name (not preceded by a word char or ':', so
    // core:vars... and envvars:... never match). Both require a following
    // identifier start so a bare "$vars" stays untouched.
    private static final Pattern VARS_FORMS = Pattern.compile(
            "\\$vars\\.(?=[a-zA-Z_])|(?<![\\w:$])vars:(?=[a-zA-Z_])");

    private VarsSyntaxNormalizer() {
    }

    /**
     * Rewrites every unprotected {@code $vars.} / {@code vars:} occurrence to
     * {@code vars.}. Returns the input unchanged (same instance) when it
     * contains neither form.
     */
    public static String normalize(String expression) {
        if (expression == null
                || (!expression.contains("$vars.") && !expression.contains("vars:"))) {
            return expression;
        }
        List<SpelProtectedRegions.Region> protectedRegions = SpelProtectedRegions.find(expression);
        Matcher matcher = VARS_FORMS.matcher(expression);
        StringBuilder result = new StringBuilder(expression.length());
        int lastEnd = 0;
        while (matcher.find()) {
            result.append(expression, lastEnd, matcher.start());
            if (SpelProtectedRegions.isProtected(matcher.start(), protectedRegions)) {
                result.append(matcher.group());
            } else {
                result.append("vars.");
            }
            lastEnd = matcher.end();
        }
        result.append(expression, lastEnd, expression.length());
        return result.toString();
    }
}
