package com.apimarketplace.orchestrator.tools.workflow.builder;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The nodes a {{...}} expression references, for the builder's reference warnings.
 *
 * <p>The body of {{...}} is SpEL: {@code 'Analyse : ' + core:a.output.x},
 * {@code int(core:a.output.n) > 10 ? 'x' : core:b.output.y}. Reading the node as "everything
 * left of the first '.'" named the operand before the reference as the node, so the builder
 * warned about the exact shape its own EXPRESSION_NOT_EVALUATED fix asks for (2026-09-29).
 */
public final class ExpressionNodeReferences {

    /**
     * SpEL string literals. SpEL escapes a quote by doubling it ('it''s') and has no backslash
     * escape, so 'C:\' is a complete literal.
     */
    private static final Pattern STRING_LITERAL = Pattern.compile("'(?:[^']|'')*'|\"(?:[^\"]|\"\")*\"");

    /**
     * {@code <prefix>:<node>.<path>}, or {@code <prefix>:<node>?.<path>} (safe navigation). The
     * node part stops at any SpEL operator, so it may hold spaces (an un-normalized label is still
     * reported) but never swallows a neighbouring operand. A ':' may precede the prefix, so the
     * compact ternary {@code ok?x:core:a.output.y} still yields core:a.
     */
    private static final Pattern NODE_REFERENCE = Pattern.compile(
            "(?<![A-Za-z0-9_$.])([A-Za-z_][A-Za-z0-9_]*):([^.:'\"()\\[\\]{}+\\-*/%<>=!?&|,]+?)\\s*\\??\\.");

    private ExpressionNodeReferences() {
    }

    /**
     * Distinct node ids ({@code core:a}, {@code mcp:b}...) referenced by the inner text of a
     * {{...}}, in order. Text inside string literals is never read as a reference, and
     * {@code vars:name} (a workflow variable) is not a node.
     */
    public static List<String> of(String expression) {
        List<String> nodes = new ArrayList<>();
        if (expression == null) return nodes;
        String withoutLiterals = STRING_LITERAL.matcher(expression).replaceAll("''");
        Matcher matcher = NODE_REFERENCE.matcher(withoutLiterals);
        while (matcher.find()) {
            String prefix = matcher.group(1);
            if ("vars".equals(prefix)) continue;
            String node = prefix + ":" + matcher.group(2).trim();
            if (!nodes.contains(node)) nodes.add(node);
        }
        return nodes;
    }
}
