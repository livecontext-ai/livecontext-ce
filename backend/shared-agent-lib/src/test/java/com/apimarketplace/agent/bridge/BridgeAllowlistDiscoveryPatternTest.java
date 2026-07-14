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

    // codex is intentionally NOT here: OpenAI's Codex-routable set is irregular
    // (codenamed gpt-5.6 tiers + unroutable bare gpt-5.x in the feed), so codex
    // is curated-only like mistral-vibe - see codexHasNoDiscoveryPattern().
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
    @DisplayName("codex is curated-only - no discovery pattern, and the phantom bare gpt-5.6 is never auto-derived")
    void codexHasNoDiscoveryPattern() {
        // Regression for the prod bug: a codex discovery pattern
        // (^gpt-5\.\d+(-mini|-codex)?$) auto-derived a phantom codex/gpt-5.6 that
        // Codex with a ChatGPT account cannot route (typed 400). OpenAI's
        // Codex-routable set is irregular, so codex now ships fully curated.
        assertThat(BridgeAllowlist.DISCOVERY_PATTERNS).doesNotContainKey("codex");
        // The bare gpt-5.6 (a real openai API id, but not codex-routable) and
        // any other feed gpt-5.x must never be auto-discovered under codex.
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("codex", "gpt-5.6")).isFalse();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("codex", "gpt-5.5")).isFalse();
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("codex", "gpt-5.6-sol")).isFalse();
        // Even its curated ids are not discoverable - they ship via MODELS + migration.
        assertThat(BridgeAllowlist.matchesDiscoveryPattern("codex", "gpt-5.4")).isFalse();
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
