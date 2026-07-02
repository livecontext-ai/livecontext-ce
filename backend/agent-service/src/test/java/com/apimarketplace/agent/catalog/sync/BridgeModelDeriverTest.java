package com.apimarketplace.agent.catalog.sync;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behaviour of {@link BridgeModelDeriver}: curated-floor derivation (unchanged)
 * plus the pattern-based auto-discovery layer that lets a brand-new same-family
 * model surface under its bridge on a feed sync, with no code change.
 */
@DisplayName("BridgeModelDeriver - curated floor + pattern discovery")
class BridgeModelDeriverTest {

    private final BridgeModelDeriver deriver = new BridgeModelDeriver();

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A parsed-feed cloud row, in the shape LiteLlmFeedParser emits. */
    private static Map<String, Object> cloud(String provider, String modelId,
                                             String priceIn, String priceOut) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", provider);
        m.put("modelId", modelId);
        m.put("priceInput", priceIn);
        m.put("priceOutput", priceOut);
        m.put("mode", "chat");
        m.put("supportsTools", Boolean.TRUE);
        m.put("contextWindow", 1_000_000);
        return m;
    }

    private static Map<String, Object> find(List<Map<String, Object>> rows,
                                            String provider, String modelId) {
        return rows.stream()
                .filter(r -> provider.equals(r.get("provider")) && modelId.equals(r.get("modelId")))
                .findFirst().orElse(null);
    }

    private static long count(List<Map<String, Object>> rows, String provider, String modelId) {
        return rows.stream()
                .filter(r -> provider.equals(r.get("provider")) && modelId.equals(r.get("modelId")))
                .count();
    }

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Curated-floor id is derived and stamped as a bridge row (price copied)")
    void derivesCuratedFloorRow() {
        List<Map<String, Object>> feed = List.of(cloud("anthropic", "claude-opus-4-7", "5", "25"));

        List<Map<String, Object>> rows = deriver.derive(feed);

        Map<String, Object> row = find(rows, "claude-code", "claude-opus-4-7");
        assertThat(row).as("claude-opus-4-7 derived under claude-code").isNotNull();
        assertThat(row.get("providerKind")).isEqualTo("bridge");
        assertThat(row.get("source")).isEqualTo("litellm");
        assertThat(row.get("displayName")).isEqualTo("Claude Opus 4.7");
        assertThat(row.get("priceInput")).isEqualTo("5");
        assertThat(row.get("priceOutput")).isEqualTo("25");
    }

    @Test
    @DisplayName("A new Claude version not in the allowlist is auto-discovered from the feed (claude-opus-4-8)")
    void autoDiscoversNewClaudeVersion() {
        // claude-opus-4-8 is intentionally absent from BridgeAllowlist.MODELS.
        List<Map<String, Object>> feed = List.of(cloud("anthropic", "claude-opus-4-8", "5", "25"));

        List<Map<String, Object>> rows = deriver.derive(feed);

        Map<String, Object> row = find(rows, "claude-code", "claude-opus-4-8");
        assertThat(row).as("claude-opus-4-8 auto-discovered under claude-code").isNotNull();
        assertThat(row.get("providerKind")).isEqualTo("bridge");
        assertThat(row.get("source")).isEqualTo("litellm");
        assertThat(row.get("displayName")).isEqualTo("Claude Opus 4.8");
        assertThat(row.get("priceInput")).isEqualTo("5");
    }

    @Test
    @DisplayName("New codex and gemini family versions are auto-discovered too")
    void autoDiscoversCodexAndGemini() {
        List<Map<String, Object>> feed = List.of(
                cloud("openai", "gpt-5.5", "3", "12"),
                cloud("google", "gemini-3.2-pro", "2", "10"));

        List<Map<String, Object>> rows = deriver.derive(feed);

        assertThat(find(rows, "codex", "gpt-5.5")).as("gpt-5.5 under codex").isNotNull();
        assertThat(find(rows, "gemini-cli", "gemini-3.2-pro")).as("gemini-3.2-pro under gemini-cli").isNotNull();
    }

    @Test
    @DisplayName("A seed id that also matches its pattern is emitted exactly once")
    void noDoubleDeriveOnSeedPatternOverlap() {
        // claude-opus-4-7 is both in MODELS and matches the claude-code pattern.
        List<Map<String, Object>> feed = List.of(cloud("anthropic", "claude-opus-4-7", "5", "25"));

        List<Map<String, Object>> rows = deriver.derive(feed);

        assertThat(count(rows, "claude-code", "claude-opus-4-7"))
                .as("no duplicate row from seed+pattern overlap").isEqualTo(1);
    }

    @Test
    @DisplayName("mistral-vibe is never auto-discovered from the feed; its alias id still derives via lookup")
    void mistralNotAutoDiscoveredButAliasStillWorks() {
        List<Map<String, Object>> feed = List.of(
                // The feed carries dated / -latest ids, never the vibe alias.
                cloud("mistral", "devstral-2512", "1", "3"),
                cloud("mistral", "devstral-latest", "1", "3"));

        List<Map<String, Object>> rows = deriver.derive(feed);

        // The raw feed id must NOT become a bridge model (CLI can't route it).
        assertThat(find(rows, "mistral-vibe", "devstral-2512"))
                .as("feed id devstral-2512 must not be auto-derived").isNull();
        // The curated alias devstral-2 still derives via LITELLM_LOOKUP_ALIAS → devstral-latest.
        assertThat(find(rows, "mistral-vibe", "devstral-2"))
                .as("curated alias devstral-2 derived via lookup").isNotNull();
    }

    @Test
    @DisplayName("Codex pattern stays tight - non-codex OpenAI models are not derived")
    void codexPatternRejectsNonCodexOpenAiModels() {
        List<Map<String, Object>> feed = List.of(
                cloud("openai", "gpt-5.3-chat-latest", "3", "12"),
                cloud("openai", "gpt-4o", "5", "15"));

        List<Map<String, Object>> rows = deriver.derive(feed);

        assertThat(find(rows, "codex", "gpt-5.3-chat-latest")).isNull();
        assertThat(find(rows, "codex", "gpt-4o")).isNull();
    }

    @Test
    @DisplayName("claude-fable-5 (new fable family) derives under claude-code; mythos never does")
    void derivesFableFiveButNeverMythos() {
        List<Map<String, Object>> feed = List.of(
                cloud("anthropic", "claude-fable-5", "10", "50"),
                cloud("anthropic", "claude-mythos-5", "10", "50"));

        List<Map<String, Object>> rows = deriver.derive(feed);

        Map<String, Object> fable = find(rows, "claude-code", "claude-fable-5");
        assertThat(fable).as("claude-fable-5 derived under claude-code").isNotNull();
        assertThat(fable.get("providerKind")).isEqualTo("bridge");
        assertThat(fable.get("displayName")).isEqualTo("Claude Fable 5");
        assertThat(fable.get("priceInput")).isEqualTo("10");
        assertThat(fable.get("priceOutput")).isEqualTo("50");
        // Approved-orgs-only model must not become a bridge row (CLI can't route it).
        assertThat(find(rows, "claude-code", "claude-mythos-5"))
                .as("claude-mythos-5 must not be derived").isNull();
    }

    @Test
    @DisplayName("Two-segment dated Anthropic pins are not auto-derived (no 'Claude Opus 4.20250514' row)")
    void datedPinNotAutoDiscovered() {
        // claude-opus-4-20250514 is live in the feed and not deduped (no canonical twin).
        List<Map<String, Object>> feed = List.of(cloud("anthropic", "claude-opus-4-20250514", "5", "25"));

        List<Map<String, Object>> rows = deriver.derive(feed);

        assertThat(find(rows, "claude-code", "claude-opus-4-20250514"))
                .as("dated pin must not become a bridge model").isNull();
    }

    @Test
    @DisplayName("Curated gpt-5.3-codex derives via alias lookup; the chat-latest feed id is not itself exposed")
    void codexAliasModelDerivesFromChatLatest() {
        // gpt-5.3-codex is mode=responses → dropped by the parser; LITELLM_LOOKUP_ALIAS
        // maps it to gpt-5.3-chat-latest for metadata enrichment (curated-floor path).
        List<Map<String, Object>> feed = List.of(cloud("openai", "gpt-5.3-chat-latest", "3", "12"));

        List<Map<String, Object>> rows = deriver.derive(feed);

        assertThat(find(rows, "codex", "gpt-5.3-codex"))
                .as("gpt-5.3-codex derived via alias lookup").isNotNull();
        assertThat(find(rows, "codex", "gpt-5.3-chat-latest"))
                .as("the enrichment-source id is never exposed as a codex model").isNull();
    }

    @Test
    @DisplayName("In one feed, curated-floor rows are emitted before pattern-discovered rows (drives append-at-bottom ranking)")
    void curatedRowsOrderedBeforeDiscovered() {
        List<Map<String, Object>> feed = List.of(
                cloud("anthropic", "claude-opus-4-7", "5", "25"),   // curated floor
                cloud("anthropic", "claude-opus-4-8", "5", "25"));  // pattern-discovered

        List<Map<String, Object>> rows = deriver.derive(feed);

        assertThat(find(rows, "claude-code", "claude-opus-4-7")).isNotNull();
        assertThat(find(rows, "claude-code", "claude-opus-4-8")).isNotNull();
        // Curated before discovered: the merge layer assigns sequential rankings
        // in this order, so a newly-discovered model lands BELOW the curated ones
        // in the picker (the "append at bottom, don't reshuffle" contract).
        List<String> claudeIds = rows.stream()
                .filter(r -> "claude-code".equals(r.get("provider")))
                .map(r -> (String) r.get("modelId"))
                .toList();
        assertThat(claudeIds.indexOf("claude-opus-4-7"))
                .as("curated claude-opus-4-7 emitted before discovered claude-opus-4-8")
                .isLessThan(claudeIds.indexOf("claude-opus-4-8"));
    }

    @Test
    @DisplayName("Empty feed yields no bridge rows")
    void emptyFeedYieldsNothing() {
        assertThat(deriver.derive(List.of())).isEmpty();
        assertThat(deriver.derive(new ArrayList<>())).isEmpty();
    }
}
