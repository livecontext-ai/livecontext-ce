package com.apimarketplace.agent.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the version-flexible discovery layer ({@link BridgeAllowlist#DISCOVERY_PATTERNS})
 * against drift away from the curated floor ({@link BridgeAllowlist#MODELS}).
 *
 * <p>The contract that lets us auto-discover new bridge models safely:
 * <ol>
 *   <li><b>Pattern superset</b> - every curated id of a pattern-bridge must
 *       itself match that bridge's pattern. If it didn't, a future feed sync
 *       could fail to re-derive a model that's in the seed, silently dropping
 *       it from the catalog on the next refresh.</li>
 *   <li><b>No cross-bridge match</b> - one bridge's id must never match another
 *       bridge's pattern, or a refresh would clone the model under the wrong
 *       CLI (which cannot route it).</li>
 *   <li><b>mistral-vibe has no pattern</b> - its CLI ids are config aliases that
 *       never appear in the LiteLLM feed, so it stays on the explicit list.</li>
 * </ol>
 */
@DisplayName("BridgeAllowlist discovery patterns")
class BridgeAllowlistDiscoveryPatternTest {

    // codex is NOT here: only its CODENAMED curated ids match its pattern. Its
    // pre-codename numeric ids (gpt-5.5, gpt-5.4*, ...) stay on the curated floor
    // because no pattern can tell them from the bare ids Codex refuses - see
    // codexCodenamedCuratedIdsMatchItsPattern().
    private static final Set<String> PATTERN_BRIDGES =
            Set.of("claude-code", "gemini-cli");

    @Test
    @DisplayName("Every curated id of a pattern-bridge matches that bridge's own pattern (seed ⊆ pattern)")
    void seedIdsAreASubsetOfTheirBridgePattern() {
        for (String bridge : PATTERN_BRIDGES) {
            for (String id : BridgeAllowlist.MODELS.get(bridge)) {
                assertThat(BridgeAllowlist.matchesDiscoveryPattern(bridge, id))
                        .as("curated id %s/%s must match its bridge discovery pattern", bridge, id)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("mistral-vibe is deliberately excluded from discovery - its ids are config aliases, not feed ids")
    void mistralVibeHasNoDiscoveryPattern() {
        assertThat(BridgeAllowlist.DISCOVERY_PATTERNS).doesNotContainKey("mistral-vibe");
        // Even its own curated ids must NOT be auto-discoverable.
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("mistral-vibe", "devstral-2")).isFalse();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("mistral-vibe", "devstral-small-2")).isFalse();
    }

    @Test
    @DisplayName("codex discovers the GPT-6 Sol and Luna tiers without a code change - regression for the 2026-09 gap")
    void codexDiscoversNewCodenamedTiers() {
        // The prod gap: OpenAI shipped gpt-6-sol and gpt-6-luna on 2026-09-22, the
        // openai rows appeared on the next sync, and codex stayed without them because
        // it was curated-only. Neither id is in MODELS, so only the pattern can carry them.
        assertThat(BridgeAllowlist.MODELS.get("codex")).doesNotContain("gpt-6-sol", "gpt-6-luna");
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("codex", "gpt-6-sol")).isTrue();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("codex", "gpt-6-luna")).isTrue();
        // A tier that does not exist yet is picked up the day it reaches the feed.
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("codex", "gpt-6-terra")).isTrue();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("codex", "gpt-6.1-sol")).isTrue();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("codex", "gpt-7-astra")).isTrue();
    }

    @Test
    @DisplayName("codex never discovers a bare generation id - regression for the phantom codex/gpt-5.6")
    void codexNeverDiscoversBareGenerationIds() {
        // e399615a4/V399: a numeric codex pattern auto-derived codex/gpt-5.6, a real
        // openai API id that Codex with a ChatGPT account refuses (typed 400). The
        // codename pattern must keep every bare id out, for this generation and the next.
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("codex", "gpt-5.6")).isFalse();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("codex", "gpt-6")).isFalse();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("codex", "gpt-5.5")).isFalse();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("codex", "gpt-5.4")).isFalse();
    }

    @Test
    @DisplayName("codex rejects non-codename suffixes, -pro variants and dated pins")
    void codexRejectsOtherSuffixes() {
        // gpt-5.6-cyber is in the openai feed and is not a Codex model.
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("codex", "gpt-5.6-cyber")).isFalse();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("codex", "gpt-6-sol-pro")).isFalse();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("codex", "gpt-6-sol-2026-09-22")).isFalse();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("codex", "gpt-5.4-mini")).isFalse();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("codex", "gpt-5.3-codex")).isFalse();
        // A codename without the gpt generation prefix is not an openai model id.
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("codex", "sol")).isFalse();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("codex", "openai/gpt-6-sol")).isFalse();
    }

    /**
     * Pre-codename codex ids: the only ones that legitimately live on the curated
     * floor alone. A new curated id must either match the codex pattern or be
     * added here on purpose, so a new codename cannot slip in unnoticed.
     */
    private static final Set<String> CODEX_NUMERIC_FLOOR = Set.of(
            "gpt-5.5", "gpt-5.4", "gpt-5.4-mini", "gpt-5.4-nano", "gpt-5.3-codex", "gpt-5.2");

    @Test
    @DisplayName("Every curated codex id matches the codex pattern or is a known pre-codename numeric id")
    void everyCuratedCodexIdIsPatternCoveredOrKnownNumeric() {
        for (String id : BridgeAllowlist.MODELS.get("codex")) {
            boolean covered = BridgeAllowlist.matchesDiscoveryPattern("codex", id);
            assertThat(covered || CODEX_NUMERIC_FLOOR.contains(id))
                    .as("codex id %s matches neither the pattern nor the numeric floor: a new codename"
                            + " needs adding to the pattern alternation", id)
                    .isTrue();
            assertThat(covered && CODEX_NUMERIC_FLOOR.contains(id))
                    .as("%s is in the numeric floor list but also matches the pattern", id)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("A new same-family version is discovered without a code change")
    void discoversNewSameFamilyVersions() {
        // The whole point of this feature: these ids are NOT in MODELS yet.
        assertThat(BridgeAllowlist.MODELS.get("claude-code")).doesNotContain("claude-opus-4-8");
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("claude-code", "claude-opus-4-8")).isTrue();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("claude-code", "claude-sonnet-4-7")).isTrue();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("claude-code", "claude-haiku-5-0")).isTrue();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("gemini-cli", "gemini-3.2-pro")).isTrue();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("gemini-cli", "gemini-4-flash-preview")).isTrue();
    }

    @Test
    @DisplayName("Fable family is routed (curated + discoverable); Mythos stays out - regression for claude-fable-5")
    void fableFamilyRoutedMythosExcluded() {
        // Curated floor carries the released model.
        assertThat(BridgeAllowlist.MODELS.get("claude-code")).contains("claude-fable-5");
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("claude-code", "claude-fable-5")).isTrue();
        // Future fable versions auto-discover like any routed family.
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("claude-code", "claude-fable-6")).isTrue();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("claude-code", "claude-fable-5-1")).isTrue();
        // Dated pins stay rejected (2-digit minor-version cap).
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("claude-code", "claude-fable-5-20260624")).isFalse();
        // claude-mythos-5 is gated to approved orgs (Project Glasswing): the
        // public Claude Code CLI cannot route it, so it must never leak in.
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("claude-code", "claude-mythos-5")).isFalse();
        assertThat(BridgeAllowlist.MODELS.get("claude-code")).doesNotContain("claude-mythos-5");
    }

    @Test
    @DisplayName("Unrelated, legacy and cross-bridge ids never match a pattern")
    void rejectsUnrelatedAndCrossBridgeIds() {
        // Legacy Anthropic shape (claude-3-…) is not the opus/sonnet/haiku family form.
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("claude-code", "claude-3-opus-20240229")).isFalse();
        // 3-segment dated twin must not match (deduped upstream; belt-and-braces here).
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("claude-code", "claude-opus-4-7-20260416")).isFalse();
        // 2-segment dated pins (claude-opus-4-20250514 / claude-sonnet-4-20250514) are
        // live in the feed and NOT deduped (no canonical "claude-opus-4" twin). The
        // pattern must reject them or a refresh would spawn a "Claude Opus 4.20250514"
        // bridge row. Regression guard for the audit finding.
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("claude-code", "claude-opus-4-20250514")).isFalse();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("claude-code", "claude-sonnet-4-20250514")).isFalse();
        // codex must stay tight: chat-latest / 4o / embeddings are not codex-routable.
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("codex", "gpt-5.3-chat-latest")).isFalse();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("codex", "gpt-4o")).isFalse();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("codex", "text-embedding-3-large")).isFalse();
        // gemini must exclude non pro/flash variants.
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("gemini-cli", "gemini-1.5-flash-8b")).isFalse();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("gemini-cli", "gemini-embedding-001")).isFalse();
        // Cross-bridge: a claude id must not match codex/gemini and vice-versa.
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("codex", "claude-opus-4-8")).isFalse();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("gemini-cli", "gpt-5.5")).isFalse();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("claude-code", "gpt-6-sol")).isFalse();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("gemini-cli", "gpt-6-luna")).isFalse();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("claude-code", "gemini-3.2-pro")).isFalse();
    }

    @Test
    @DisplayName("matchesDiscoveryPattern is null-safe and false for non-bridge providers")
    void nullAndNonBridgeSafe() {
        assertThat(BridgeAllowlist.matchesDiscoveryPattern(null, "x")).isFalse();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("claude-code", null)).isFalse();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("anthropic", "claude-opus-4-8")).isFalse();
    }
}
