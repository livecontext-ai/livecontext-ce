package com.apimarketplace.conversation.service.ai.schema;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.HashSet;
import java.util.Set;

/**
 * Stage 4a.3 - config for the security-sensitive schema-slim exclusion list.
 * Read by {@link SchemaSlimExclusionPolicy} to force {@link SchemaMode#FULL}
 * for tools/actions where serving a slim prefix would risk the LLM
 * hallucinating a required parameter or skipping a safety check.
 *
 * <p><b>Layout</b> (application.yml):
 * <pre>
 * conversation:
 *   jit:
 *     exclusions:
 *       always-full-tools:             # entire tool - any action → FULL
 *         - request_credential
 *       always-full-tool-actions:      # per-action - "tool:action"
 *         - agent:publish
 *         - skill:publish
 *         - interface:publish
 *         - table:publish
 *         - application:publish
 *         - workflow:pin
 *         - credential:force_replace
 *         - credential:rotate
 *         - task:approve
 *         - task:reject_review
 *       positive-list-pattern:         # regex auto-exclusion (R25)
 *         "(?i)\\b(publish|credential|secret|api[_-]?key|revoke|force|rotate|production|execute.*prod|merge|approve|pay|charge|delete[_-]*all)\\b"
 * </pre>
 *
 * <p><b>Why two layers.</b> The manual list pins the known-sensitive surface
 * at the cost of one config line per action - explicit and grep-able. The
 * positive-list regex catches <em>new</em> sensitive actions before anyone
 * remembers to add them to the manual list: any action whose name matches
 * {@code publish}, {@code credential}, {@code approve}, {@code rotate},
 * etc. auto-routes to FULL. The {@code SecuritySensitiveActionInventoryTest}
 * (Stage 4a.7) enforces that the manual set is a superset of the regex hits
 * - so you can't silently remove a manual entry without either losing regex
 * coverage or explicitly opting out.
 *
 * <p><b>Fail-safe posture.</b> {@link SchemaSlimExclusionPolicy#isExcluded}
 * returns {@code true} on a policy match or any parsing error. A
 * misconfigured regex yields FULL, not SLIM - wrong guess toward FULL just
 * wastes tokens; wrong guess toward SLIM risks the LLM skipping a publish
 * confirmation or fabricating a credential name.
 *
 * <p><b>Known expressiveness gap.</b> The policy keys on
 * {@code (toolName, action)} only. Predicates on other request parameters -
 * notably {@code workflow(action='execute') where environment='production'}
 * - cannot be expressed here. The regex will catch the word
 * {@code production} when it appears in the action string itself, but not
 * when it is a separate parameter value. If that distinction becomes
 * load-bearing, either promote such calls to dedicated actions
 * (e.g. {@code workflow:execute_production}) or add a second policy layer
 * that inspects the full parameter payload.
 */
@Configuration
@ConfigurationProperties(prefix = "conversation.jit.exclusions")
public class JitExclusionProperties {

    /**
     * Tool names where <em>every</em> action must receive the full schema.
     * Matched exact-case against {@code ToolDefinition.name()}. Typical
     * entries: {@code request_credential} (entire tool is credential-adjacent).
     */
    private Set<String> alwaysFullTools = new HashSet<>();

    /**
     * Specific {@code tool:action} pairs that must receive the full schema.
     * Format: lowercased tool name, colon, lowercased action string - e.g.
     * {@code agent:publish}. The colon separator is mandatory; a missing
     * colon treats the whole string as a no-op entry.
     */
    private Set<String> alwaysFullToolActions = new HashSet<>();

    /**
     * Regex whose pattern is matched against the tool name AND the action
     * string (separately). A hit in either → FULL. Default pattern covers
     * the marketplace-risk vocabulary spelled out in the plan (R25).
     *
     * <p>Kept as a plain string (not {@code java.util.regex.Pattern}) so
     * the config surface is trivially YAML-editable; compiled once in the
     * policy constructor.
     */
    private String positiveListPattern =
            "(?i)\\b(publish|credential|secret|api[_-]?key|revoke|force|rotate|production|execute.*prod|merge|approve|pay|charge|delete[_-]*all)\\b";

    public Set<String> getAlwaysFullTools() {
        return alwaysFullTools;
    }

    public void setAlwaysFullTools(Set<String> alwaysFullTools) {
        this.alwaysFullTools = alwaysFullTools != null ? alwaysFullTools : new HashSet<>();
    }

    public Set<String> getAlwaysFullToolActions() {
        return alwaysFullToolActions;
    }

    public void setAlwaysFullToolActions(Set<String> alwaysFullToolActions) {
        this.alwaysFullToolActions = alwaysFullToolActions != null ? alwaysFullToolActions : new HashSet<>();
    }

    public String getPositiveListPattern() {
        return positiveListPattern;
    }

    public void setPositiveListPattern(String positiveListPattern) {
        this.positiveListPattern = positiveListPattern;
    }
}
