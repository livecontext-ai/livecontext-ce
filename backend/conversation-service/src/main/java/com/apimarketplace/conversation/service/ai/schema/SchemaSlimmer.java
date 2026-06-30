package com.apimarketplace.conversation.service.ai.schema;

import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolParameter;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 4a.1 - minimization primitive: rewrite a {@link ToolDefinition} to its
 * "names-only" slim form so the tools prefix shipped to the LLM stays small
 * for top/mid-tier models that can bootstrap via {@code help} calls.
 *
 * <p><b>Slim rules</b> (applied to every non-excluded {@link ToolParameter}):
 * <ul>
 *   <li><b>type → "string"</b> - every parameter's type is coerced to string
 *   so OpenAI {@code strict:true} never rejects a replay with type
 *   mismatches (and because the LLM is told to pass everything as string in
 *   slim mode; a later coercer reverses the string back to the real type
 *   before the underlying tool executes).</li>
 *   <li><b>description, enum, default, pattern, min/max - all stripped</b> -
 *   the LLM has no reason to see per-param guidance in slim mode; it calls
 *   {@code help} to get the full schema when it needs one.</li>
 *   <li><b>nested {@code properties} - stripped</b> - the whole point of
 *   slim mode is that object params become opaque JSON strings.</li>
 *   <li><b>action enum preserved</b> - the {@code action} parameter keeps
 *   its enum values at full cardinality, since the LLM needs to know which
 *   actions the unified facade exposes (otherwise it cannot route).
 *   "action" is recognised by exact name match - case-sensitive, lowercase,
 *   matching the {@code action} string the facade tools use today.</li>
 * </ul>
 *
 * <p><b>Tool-level rewrite</b>: the tool's {@code description} becomes a
 * single directive telling the LLM to call {@code <tool>(action='help',
 * topic='<action>')} before first use of an action. This replaces verbose
 * per-tool overview paragraphs with one pointer that costs ~100 bytes.
 *
 * <p><b>Preserved invariants</b>: {@code id}, {@code name}, {@code apiSlug},
 * {@code toolSlug}, {@code requiredParameters}, {@code relevanceScore},
 * {@code metadata}, {@code timeoutMs}, and parameter {@code name}/{@code required}
 * flags are passed through untouched - the slim view must not change
 * routing, required-param enforcement, or per-tool timeouts.
 *
 * <p><b>Null tolerance</b>: {@code null} input returns {@code null}; missing
 * {@code parameters} list yields an empty list on the slim output (never
 * {@code null}) so downstream serialization is uniform.
 *
 * <p><b>Purity</b>: this is a pure function with no side effects, no logging,
 * no Spring wiring. Kept static so callers (CoreToolsProvider wrapping, tests,
 * tier mapper) can use it without DI friction.
 */
public final class SchemaSlimmer {

    /**
     * Parameter name whose enum values are preserved in slim mode. The
     * unified-facade tools in this project all use a parameter literally
     * named {@code "action"} to select between sub-behaviours (create, get,
     * list, help, …). The LLM needs the enum to pick a valid value, so we
     * leave it intact even though every other parameter loses its enum.
     */
    private static final String ACTION_PARAM_NAME = "action";

    private SchemaSlimmer() {
        // utility
    }

    /**
     * Return a slim copy of {@code tool}, or {@code null} if {@code tool}
     * itself is {@code null}. Idempotent: slimming an already-slim definition
     * yields an equal definition.
     */
    public static ToolDefinition minimize(ToolDefinition tool) {
        if (tool == null) {
            return null;
        }
        return ToolDefinition.builder()
                .id(tool.id())
                .name(tool.name())
                .description(slimToolDescription(tool.name()))
                .apiSlug(tool.apiSlug())
                .toolSlug(tool.toolSlug())
                .parameters(slimParameters(tool.parameters()))
                .requiredParameters(tool.requiredParameters())
                .relevanceScore(tool.relevanceScore())
                .metadata(tool.metadata())
                .timeoutMs(tool.timeoutMs())
                .build();
    }

    /**
     * Tool-level description directing the LLM to the {@code help} action.
     * Uses {@code <action>} as a literal placeholder the LLM fills in - not
     * a template variable to expand here - because the tool-level description
     * is emitted once, not per-action.
     */
    static String slimToolDescription(String toolName) {
        String safeName = toolName != null ? toolName : "tool";
        return "Call `" + safeName + "(action='help', topic='<action>')` before first use of any action.";
    }

    private static List<ToolParameter> slimParameters(List<ToolParameter> params) {
        if (params == null || params.isEmpty()) {
            return List.of();
        }
        List<ToolParameter> slimmed = new ArrayList<>(params.size());
        for (ToolParameter p : params) {
            slimmed.add(slimOne(p));
        }
        return slimmed;
    }

    private static ToolParameter slimOne(ToolParameter p) {
        if (p == null) {
            return null;
        }
        boolean isActionEnum = ACTION_PARAM_NAME.equals(p.name());
        return ToolParameter.builder()
                .name(p.name())
                .type("string")
                .description(null)
                .required(p.required())
                .defaultValue(null)
                .enumValues(isActionEnum ? p.enumValues() : null)
                .properties(null)
                .minLength(null)
                .maxLength(null)
                .minimum(null)
                .maximum(null)
                .pattern(null)
                .build();
    }
}
