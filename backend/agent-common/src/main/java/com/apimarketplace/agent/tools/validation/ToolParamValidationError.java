package com.apimarketplace.agent.tools.validation;

import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Stage 4a.5 - compact, structured error the LLM receives when it tries to
 * call a tool whose full schema is not currently loaded (slim mode). The
 * error is deliberately minimal: enough to steer the LLM into calling
 * {@code <tool>(action='help', topic='<action>')} on the next turn, but
 * far smaller than shipping the full schema unconditionally.
 *
 * <p><b>Why not just send the full schema?</b> Shipping every tool's full
 * schema on every turn costs thousands of tokens for modern agent loops;
 * under slim routing (Stage 4a.2) the LLM only receives names. When it
 * guesses at an action whose schema it hasn't seen, this error buys the
 * re-injection with a ~200-token round-trip instead of a ~5k one.
 *
 * <p><b>Shape is fixed for the LLM.</b> Field order, field names, and
 * value shape are part of the interface: the LLM is steered by prompts
 * that reference these field names. Adding/renaming fields is a breaking
 * change and must be mirrored in the system prompts.
 *
 * <p><b>Token budget.</b> Serialised JSON must stay under ~500 tokens for
 * the worst-case action (see {@code ToolParamValidationErrorTokenBudgetTest}).
 * The factory enforces this by truncating {@link #oneLineExample} first
 * when the required-params list is long - the example is the only field
 * that grows unboundedly with schema size.
 *
 * @param errorCode       stable machine-readable code, e.g.
 *                        {@code "SCHEMA_NOT_LOADED_FOR_ACTION"}; never
 *                        localised
 * @param tool            the tool name, exactly as registered
 * @param action          the action the LLM attempted; {@code null} or
 *                        blank if the tool has no action dimension
 * @param helpTopic       the exact {@code topic} string the LLM must pass
 *                        to the help action to re-inject this schema
 * @param requiredParams  required parameter names for this
 *                        {@code (tool, action)} pair - NAMES ONLY, no
 *                        types or descriptions (types come from the help
 *                        response, not here)
 * @param oneLineExample  one-line Python-style call illustrating the
 *                        canonical happy-path form, e.g.
 *                        {@code agent(action='create', name='foo')}
 * @param nextAction      templated instruction to the LLM naming the
 *                        exact help call to make before retrying
 */
public record ToolParamValidationError(
        String errorCode,
        String tool,
        String action,
        String helpTopic,
        List<String> requiredParams,
        String oneLineExample,
        String nextAction
) {

    /** Stable error code for "the action's schema hasn't been loaded yet". */
    public static final String CODE_SCHEMA_NOT_LOADED = "SCHEMA_NOT_LOADED_FOR_ACTION";

    /**
     * Soft cap on the length of {@link #oneLineExample} measured in
     * characters. The factory truncates longer examples with an ellipsis.
     * Sized so that even a maximally-padded error under Claude's tokenizer
     * stays comfortably below the 500-token budget.
     */
    public static final int ONE_LINE_EXAMPLE_MAX_CHARS = 240;

    public ToolParamValidationError {
        Objects.requireNonNull(errorCode, "errorCode");
        Objects.requireNonNull(tool, "tool");
        // action, helpTopic, requiredParams, oneLineExample, nextAction may be null/empty
        // so a tool without an action dimension or without any required params
        // still yields a valid error.
        requiredParams = requiredParams == null
                ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(requiredParams));
    }

    /**
     * Build a "schema not loaded" error from the current {@link ToolDefinition}.
     * The caller provides the action the LLM attempted; the factory extracts
     * required-param names from the definition, builds a canonical one-line
     * example, and trims it if over the soft cap.
     *
     * <p>Truncation rule: {@link #ONE_LINE_EXAMPLE_MAX_CHARS} characters
     * keeping as many required params as fit, suffixed with {@code ", ...)"}
     * when at least one param was dropped. This keeps the example
     * recognisable (the verb + the first N args) rather than a chunk of
     * mid-string garbage.
     */
    public static ToolParamValidationError schemaNotLoaded(String toolName,
                                                           String action,
                                                           ToolDefinition definition) {
        Objects.requireNonNull(toolName, "toolName");
        List<String> requiredNames = extractRequiredNames(definition);
        String normalisedAction = action == null ? "" : action;
        String topic = normalisedAction.isBlank() ? toolName : normalisedAction;
        String example = buildOneLineExample(toolName, normalisedAction, requiredNames);
        String next = normalisedAction.isBlank()
                ? "Call `" + toolName + "(action='help')` before retrying."
                : "Call `" + toolName + "(action='help', topic='" + normalisedAction + "')` before retrying.";
        return new ToolParamValidationError(
                CODE_SCHEMA_NOT_LOADED,
                toolName,
                normalisedAction.isBlank() ? null : normalisedAction,
                topic,
                requiredNames,
                example,
                next
        );
    }

    private static List<String> extractRequiredNames(ToolDefinition definition) {
        if (definition == null) return List.of();
        List<String> required = definition.requiredParameters();
        if (required != null && !required.isEmpty()) {
            // Copy-and-trim nulls defensively.
            List<String> clean = new ArrayList<>(required.size());
            for (String name : required) {
                if (name != null && !name.isBlank()) clean.add(name);
            }
            return clean;
        }
        // Fall back to ToolParameter list when requiredParameters is not populated.
        List<ToolParameter> params = definition.parameters();
        if (params == null || params.isEmpty()) return List.of();
        List<String> fallback = new ArrayList<>();
        for (ToolParameter p : params) {
            if (Boolean.TRUE.equals(p.required()) && p.name() != null && !p.name().isBlank()) {
                fallback.add(p.name());
            }
        }
        return fallback;
    }

    private static String buildOneLineExample(String toolName, String action, List<String> requiredNames) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(toolName).append('(');
        boolean firstArg = true;
        if (!action.isBlank()) {
            sb.append("action='").append(action).append('\'');
            firstArg = false;
        }
        int dropped = 0;
        for (String name : requiredNames) {
            if (name.equals("action")) continue; // already emitted
            // Projected size if we append this arg.
            int projected = sb.length()
                    + (firstArg ? 0 : 2)              // ", "
                    + name.length()
                    + 5                                // ="...
                    + placeholderFor(name).length()
                    + 1;                               // closing '
            if (projected > ONE_LINE_EXAMPLE_MAX_CHARS - 6) {  // reserve for ", ...)"
                dropped++;
                continue;
            }
            if (!firstArg) sb.append(", ");
            sb.append(name).append("='").append(placeholderFor(name)).append('\'');
            firstArg = false;
        }
        if (dropped > 0) {
            // Omit the leading ", " if no prior arg was emitted, so an
            // extreme-edge case (very long tool name, no action, everything
            // truncated) still yields "tool(...)" rather than "tool(, ...)".
            sb.append(firstArg ? "..." : ", ...");
        }
        sb.append(')');
        return sb.toString();
    }

    /**
     * Heuristic placeholder for a parameter value in the one-line example.
     * Keeps the example visually informative without bloating its length.
     */
    private static String placeholderFor(String paramName) {
        // Most-specific first so a name like "userIdEmail" picks <email>, not <id>.
        String lower = paramName.toLowerCase(Locale.ROOT);
        if (lower.contains("email")) return "<email>";
        if (lower.contains("url")) return "<url>";
        if (lower.contains("type")) return "<type>";
        if (lower.contains("name")) return "<name>";
        if (lower.contains("id")) return "<id>";
        return "<value>";
    }
}
