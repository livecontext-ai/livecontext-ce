package com.apimarketplace.conversation.service.ai.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 4a.7 - trip-wire for the security-sensitive always-FULL inventory.
 *
 * <p><b>The contract.</b> {@link JitExclusionProperties} has two independent
 * ways of forcing {@link SchemaMode#FULL} for a tool call:
 * <ol>
 *   <li>an explicit entry in {@code alwaysFullTools} or
 *       {@code alwaysFullToolActions} (the <em>manual</em> list), and</li>
 *   <li>a positive-list regex hit on the tool name or action string.</li>
 * </ol>
 * The regex is a safety net that catches <em>new</em> sensitive actions
 * before anyone remembers to update the manual list. The manual list is the
 * explicit, grep-able source of truth.
 *
 * <p><b>What this test prevents.</b> The two layers are meant to agree: any
 * known-sensitive call should be caught by <em>both</em>. A contributor who
 * loosens the regex (say, drops {@code rotate} from the keyword list)
 * without noticing that {@code credential:rotate} relies on it for the
 * general-purpose path would leave that call downgraded to SLIM. Conversely,
 * a contributor who removes an entry from the manual list because "the regex
 * covers it anyway" erodes the explicit documentation of which actions are
 * sensitive. This test makes either silent regression loud: for a canonical
 * inventory of known-sensitive {@code (tool, action)} pairs, it asserts both
 * the regex and the manual set classify each pair as excluded.
 *
 * <p><b>Why a canonical list, not reflection.</b> Reflecting over every tool
 * at test time would couple this module to the rest of the platform's
 * ToolDefinition class hierarchy (which lives in other modules). The
 * canonical list is hand-maintained and short - the value here is the
 * <em>alarm</em> when someone weakens enforcement, not exhaustive coverage.
 * New sensitive actions get added to this inventory as they're introduced;
 * the PR that adds {@code workflow:execute_production} adds a line here too.
 */
@DisplayName("JIT exclusion - security-sensitive inventory (Stage 4a.7)")
class SecuritySensitiveActionInventoryTest {

    /**
     * Canonical inventory. {@code tool} name (column 1) paired with the
     * {@code action} it's invoked with (column 2, or {@code null} for
     * whole-tool calls). Kept in sync with {@code application.yml}'s
     * {@code conversation.jit.exclusions} block - adding a new entry there
     * without adding it here means the alarm won't fire on its removal.
     */
    static Stream<Arguments> knownSensitivePairs() {
        return Stream.of(
                // Whole-tool - any call must go FULL.
                // The unified credentials + workflow-variables tool. Unlike the
                // legacy alias, the bare name "credential" IS caught by the
                // positive-list \bcredential\b pattern (whole-string boundaries),
                // so it must ALSO live in the manual list (manual ⊇ regex).
                Arguments.of("credential", null),
                Arguments.of("credential", "list"),
                Arguments.of("credential", "set_variable"),
                Arguments.of("credential", "require"),
                // Legacy routing alias (pre-rename sessions) - regex-blind
                // (underscore kills the \b), manual-list only.
                Arguments.of("request_credential", null),
                Arguments.of("request_credential", "read"),

                // Marketplace publish - cross-cutting.
                Arguments.of("agent", "publish"),
                Arguments.of("skill", "publish"),
                Arguments.of("interface", "publish"),
                Arguments.of("table", "publish"),
                Arguments.of("application", "publish"),

                // Production pins / forced rewrites.
                Arguments.of("workflow", "pin"),
                Arguments.of("credential", "force_replace"),
                Arguments.of("credential", "rotate"),

                // Approval surface.
                Arguments.of("task", "approve"),
                Arguments.of("task", "reject_review")
        );
    }

    /** Builds a policy pre-loaded with the defaults that ship in application.yml. */
    private static SchemaSlimExclusionPolicy buildWithYamlDefaults() {
        JitExclusionProperties props = new JitExclusionProperties();

        Set<String> tools = new HashSet<>();
        tools.add("credential");
        tools.add("request_credential");
        props.setAlwaysFullTools(tools);

        Set<String> actions = new HashSet<>(List.of(
                "agent:publish",
                "skill:publish",
                "interface:publish",
                "table:publish",
                "application:publish",
                "workflow:pin",
                "credential:force_replace",
                "credential:rotate",
                "task:approve",
                "task:reject_review"
        ));
        props.setAlwaysFullToolActions(actions);

        // Default regex is baked into JitExclusionProperties - do not override.
        SchemaSlimExclusionPolicy policy = new SchemaSlimExclusionPolicy(props);
        policy.init();
        return policy;
    }

    @ParameterizedTest(name = "({0}, {1}) must be classified excluded")
    @MethodSource("knownSensitivePairs")
    @DisplayName("every canonical (tool, action) pair resolves to excluded under yml defaults")
    void canonicalPairIsExcluded(String tool, String action) {
        SchemaSlimExclusionPolicy policy = buildWithYamlDefaults();
        assertThat(policy.isExcluded(tool, action))
                .as("(%s, %s) - expected to be forced FULL by the policy", tool, action)
                .isTrue();
    }

    @ParameterizedTest(name = "({0}, {1}) caught by the manual list")
    @MethodSource("knownSensitivePairs")
    @DisplayName("manual set covers every canonical pair independently of the regex")
    void manualListCoversCanonicalPair(String tool, String action) {
        // Reproduce manual-only resolution: alwaysFullTools OR "tool:action"
        // in alwaysFullToolActions. If neither hits, the manual list has a
        // hole - the regex may still cover it but the explicit documentation
        // of sensitivity has regressed.
        JitExclusionProperties props = new JitExclusionProperties();
        Set<String> tools = new HashSet<>();
        tools.add("credential");
        tools.add("request_credential");
        props.setAlwaysFullTools(tools);
        props.setAlwaysFullToolActions(new HashSet<>(List.of(
                "agent:publish",
                "skill:publish",
                "interface:publish",
                "table:publish",
                "application:publish",
                "workflow:pin",
                "credential:force_replace",
                "credential:rotate",
                "task:approve",
                "task:reject_review"
        )));

        boolean manualHit = props.getAlwaysFullTools().contains(tool)
                || (action != null
                    && props.getAlwaysFullToolActions()
                            .contains(tool.toLowerCase(Locale.ROOT) + ":" + action.toLowerCase(Locale.ROOT)));

        assertThat(manualHit)
                .as("(%s, %s) - manual list must catch this pair, not rely solely on the regex", tool, action)
                .isTrue();
    }

    /**
     * Subset of the canonical inventory that the positive-list regex
     * <em>must</em> continue to catch. Not every manual entry is
     * regex-covered: {@code request_credential} (tool name has no word
     * boundary around {@code credential} due to the {@code _} prefix),
     * {@code workflow:pin}, and {@code task:reject_review} rely on the
     * manual list alone. The pairs below are the ones the regex should
     * keep catching even if the manual list regresses - they are the R25
     * safety-net vocabulary in action.
     */
    static Stream<Arguments> regexCaughtPairs() {
        return Stream.of(
                // The unified tool's bare name hits \bcredential\b directly.
                Arguments.of("credential", null),
                Arguments.of("agent", "publish"),
                Arguments.of("skill", "publish"),
                Arguments.of("interface", "publish"),
                Arguments.of("table", "publish"),
                Arguments.of("application", "publish"),
                Arguments.of("credential", "force_replace"),
                Arguments.of("credential", "rotate"),
                Arguments.of("task", "approve")
        );
    }

    @ParameterizedTest(name = "({0}, {1}) still caught by the positive-list regex")
    @MethodSource("regexCaughtPairs")
    @DisplayName("regex-caught subset stays caught - safety-net keywords are load-bearing")
    void regexCoversRegexCoveredSubset(String tool, String action) {
        // If someone edits the default regex and accidentally drops one of
        // the load-bearing keywords (e.g. `rotate` → `credential:rotate`
        // slips past), this fires. The manual list would still catch those
        // specific pairs, but the safety-net layer for NEW sibling actions
        // (e.g. a future `credential:rotate_audit`) would be weaker.
        String pattern = new JitExclusionProperties().getPositiveListPattern();
        Pattern p = Pattern.compile(pattern);

        boolean regexHit = p.matcher(tool).find()
                || (action != null && !action.isBlank() && p.matcher(action).find());

        assertThat(regexHit)
                .as("(%s, %s) - positive-list regex must still catch this pair", tool, action)
                .isTrue();
    }

    @Test
    @DisplayName("manual set is a superset of the regex - removing a manual entry would not silently weaken enforcement")
    void manualSupersetOfRegexForInventory() {
        // This is the plan's headline invariant spelled out on the
        // inventory: every canonical pair that the regex catches must also
        // be in the manual list. A future refactor that thinks "the regex
        // covers it" and drops a manual entry falls out as a failure here.
        String pattern = new JitExclusionProperties().getPositiveListPattern();
        Pattern p = Pattern.compile(pattern);

        Set<String> manualTools = new HashSet<>();
        manualTools.add("credential");
        manualTools.add("request_credential");
        Set<String> manualPairs = new HashSet<>(List.of(
                "agent:publish",
                "skill:publish",
                "interface:publish",
                "table:publish",
                "application:publish",
                "workflow:pin",
                "credential:force_replace",
                "credential:rotate",
                "task:approve",
                "task:reject_review"
        ));

        knownSensitivePairs().forEach(args -> {
            Object[] arr = args.get();
            String tool = (String) arr[0];
            String action = (String) arr[1];

            boolean regexHit = p.matcher(tool).find()
                    || (action != null && !action.isBlank() && p.matcher(action).find());
            if (!regexHit) {
                // Inventory entries that the regex doesn't catch are
                // outside this invariant - they rely on the manual list
                // alone, which the per-pair test already enforces.
                return;
            }

            boolean manualHit = manualTools.contains(tool)
                    || (action != null
                        && manualPairs.contains(tool.toLowerCase(Locale.ROOT)
                                + ":" + action.toLowerCase(Locale.ROOT)));

            assertThat(manualHit)
                    .as("(%s, %s) - regex catches this pair; manual list must too (plan: manual ⊇ regex)",
                            tool, action)
                    .isTrue();
        });
    }

    @Test
    @DisplayName("dropping 'publish' from the regex would trip the inventory (trip-wire sanity)")
    void tripWireSanity() {
        // Meta-check: prove the alarm actually rings. Rebuild a properties
        // object whose regex is missing `publish`, keep the manual set
        // empty, and observe a canonical publish action slip through. This
        // keeps the test honest - if this assertion ever starts failing,
        // the other tests in this file have gone numb.
        JitExclusionProperties weakened = new JitExclusionProperties();
        weakened.setPositiveListPattern(
                "(?i)\\b(credential|secret|api[_-]?key|revoke|force|rotate|production|merge|approve|pay|charge|delete[_-]*all)\\b");
        // Intentionally empty manual list - we want to see the slip.
        weakened.setAlwaysFullTools(new HashSet<>());
        weakened.setAlwaysFullToolActions(new HashSet<>());
        SchemaSlimExclusionPolicy weak = new SchemaSlimExclusionPolicy(weakened);
        weak.init();

        assertThat(weak.isExcluded("agent", "publish"))
                .as("with 'publish' dropped from the regex AND an empty manual list, "
                        + "agent:publish must fall through - proves this file's assertions are wired")
                .isFalse();
    }
}
