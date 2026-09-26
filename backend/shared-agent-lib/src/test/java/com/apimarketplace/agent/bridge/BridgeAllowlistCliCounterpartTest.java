package com.apimarketplace.agent.bridge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The inverse lookup the admin Models panel reads: given a BILLED cloud model,
 * which CLI bridge could execute it verbatim?
 *
 * <p>The panel turns a non-null answer into a one-click execution link, so a
 * false positive is not cosmetic: it would offer a link whose CLI rejects the
 * model at dispatch (Codex answers a typed 400 for an id it cannot route), and
 * every run of that model would fail until an admin noticed. These tests pin
 * both halves of the rule - the provider has a CLI, AND that CLI routes this
 * exact id.
 */
@DisplayName("BridgeAllowlist - CLI counterpart of a billed cloud model")
class BridgeAllowlistCliCounterpartTest {

    @Nested
    @DisplayName("bridgeForCloudProvider")
    class BridgeForCloudProvider {

        @Test
        @DisplayName("maps each cloud provider that has a CLI to its bridge")
        void mapsCloudProvidersWithACli() {
            assertThat(BridgeAllowlist.bridgeForCloudProvider("anthropic")).isEqualTo("claude-code");
            assertThat(BridgeAllowlist.bridgeForCloudProvider("openai")).isEqualTo("codex");
            assertThat(BridgeAllowlist.bridgeForCloudProvider("google")).isEqualTo("gemini-cli");
            assertThat(BridgeAllowlist.bridgeForCloudProvider("mistral")).isEqualTo("mistral-vibe");
        }

        @Test
        @DisplayName("a provider with no CLI, a bridge slug itself, and blank input all map to null")
        void mapsEverythingElseToNull() {
            assertThat(BridgeAllowlist.bridgeForCloudProvider("deepseek")).isNull();
            assertThat(BridgeAllowlist.bridgeForCloudProvider("openrouter")).isNull();
            // A bridge is not the cloud side of another bridge: without this the panel
            // would offer to route a claude-code row to itself.
            assertThat(BridgeAllowlist.bridgeForCloudProvider("claude-code")).isNull();
            assertThat(BridgeAllowlist.bridgeForCloudProvider("")).isNull();
            assertThat(BridgeAllowlist.bridgeForCloudProvider(null)).isNull();
        }

        @Test
        @DisplayName("tolerates the casing/whitespace a persisted provider slug can carry")
        void isLenientOnProviderInput() {
            assertThat(BridgeAllowlist.bridgeForCloudProvider("  Anthropic ")).isEqualTo("claude-code");
        }
    }

    @Nested
    @DisplayName("routesModel")
    class RoutesModel {

        @Test
        @DisplayName("accepts a curated id and a new version matching the bridge pattern")
        void acceptsCuratedAndDiscovered() {
            assertThat(BridgeAllowlist.routesModel("claude-code", "claude-opus-4-7")).isTrue();
            // Not on the curated floor, but the claude-code pattern covers new
            // versions of a routed family - the same rule the catalog sync uses.
            assertThat(BridgeAllowlist.routesModel("claude-code", "claude-opus-4-9")).isTrue();
            assertThat(BridgeAllowlist.routesModel("codex", "gpt-5.5")).isTrue();
            assertThat(BridgeAllowlist.routesModel("gemini-cli", "gemini-2.5-pro")).isTrue();
            assertThat(BridgeAllowlist.routesModel("mistral-vibe", "devstral-2")).isTrue();
        }

        @Test
        @DisplayName("is null-safe on both halves (MODELS is immutable, so get(null) would throw)")
        void isNullSafeOnBothHalves() {
            // isAllowed reads an immutable Map, whose get(null) throws rather than
            // answering "not allowed" - so the guard is what makes routesModel, and
            // every caller above it, safe on a row with a missing field.
            assertThat(BridgeAllowlist.isAllowed(null, "claude-opus-4-7")).isFalse();
            assertThat(BridgeAllowlist.isAllowed("claude-code", null)).isFalse();
            assertThat(BridgeAllowlist.isAllowed(null, null)).isFalse();
            assertThat(BridgeAllowlist.routesModel("claude-code", null)).isFalse();
            assertThat(BridgeAllowlist.routesModel(null, "claude-opus-4-7")).isFalse();
        }

        @Test
        @DisplayName("rejects ids the CLI cannot route, and any non-bridge provider")
        void rejectsUnroutableIds() {
            assertThat(BridgeAllowlist.routesModel("claude-code", "claude-3-opus-20240229")).isFalse();
            // A bare gpt-5.6 is a real OpenAI API id that the Codex CLI refuses with
            // a ChatGPT account, so codex discovers codenamed tiers only.
            assertThat(BridgeAllowlist.routesModel("codex", "gpt-5.6")).isFalse();
            assertThat(BridgeAllowlist.routesModel("gemini-cli", "gemini-1.5-flash-8b")).isFalse();
            assertThat(BridgeAllowlist.routesModel("anthropic", "claude-opus-4-7")).isFalse();
            assertThat(BridgeAllowlist.routesModel(null, null)).isFalse();
        }
    }

    @Nested
    @DisplayName("CLI vs API parity, per provider")
    class CliApiParity {

        @Test
        @DisplayName("codex routes the codenamed tiers it ships, and NOT the bare generation id")
        void codexRoutesCodenamedTiersOnly() {
            // A bare generation id is a real OpenAI API model that Codex refuses with a
            // ChatGPT account (typed 400), and that is precisely what a numeric rule
            // derived (e399615a4/V399). The codex pattern names the codenames instead,
            // so the new tiers route with no allow-list edit and "gpt-6" never does.
            assertThat(BridgeAllowlist.routesModel("codex", "gpt-6-astra")).isTrue();
            assertThat(BridgeAllowlist.routesModel("codex", "gpt-5.6-sol")).isTrue();
            assertThat(BridgeAllowlist.routesModel("codex", "gpt-6-sol")).isTrue();
            assertThat(BridgeAllowlist.routesModel("codex", "gpt-6-luna")).isTrue();
            assertThat(BridgeAllowlist.routesModel("codex", "gpt-6")).isFalse();
        }

        @Test
        @DisplayName("codex lists every tier of a generation it routes, mini and nano included")
        void codexListsTheWholeRoutedGeneration() {
            // A snapshot, not a measurement: nothing here reads the openai catalog, so
            // this cannot catch the NEXT tier shipping without a codex entry. What it
            // does pin is the 5.4 generation staying whole, which is how the gap showed
            // up: nano was in no list at all, while mini was allow-listed AND seeded
            // (V128:75, priced by V130, updated against production by V441) and merely
            // switched off, which is an operator decision this must never "fix".
            for (String id : new String[] {"gpt-5.4", "gpt-5.4-mini", "gpt-5.4-nano"}) {
                assertThat(BridgeAllowlist.routesModel("codex", id))
                        .as("%s is offered by the openai catalog, so codex must list it too", id)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("claude-code auto-covers every routable Anthropic family, current and next")
        void claudeCodeCoversTheAnthropicFamilies() {
            // No hand-maintained list needed on this side: the pattern is what keeps the
            // CLI level with the API, which is why claude-opus-5 and claude-sonnet-5
            // appeared on their own while codex stayed frozen.
            for (String id : new String[] {
                    "claude-opus-5", "claude-sonnet-5", "claude-opus-4-8", "claude-fable-5-1",
                    "claude-haiku-4-5", "claude-opus-6", "claude-sonnet-6-1"}) {
                assertThat(BridgeAllowlist.routesModel("claude-code", id))
                        .as("%s belongs to a family the Claude Code CLI routes", id)
                        .isTrue();
            }
        }

        @Test
        @DisplayName("mythos stays OUT of claude-code: the API grants it, the CLI subscription does not")
        void mythosIsNeverRoutedByTheCli() {
            // The single deliberate CLI/API difference on the Anthropic side, and until now
            // only a comment: claude-mythos-* is gated to approved orgs (Project Glasswing),
            // so an API key can carry it while the CLI subscription cannot run it. Widening
            // the family pattern to "fix" the gap would ship a model that fails at dispatch,
            // and bridge turns bill at the full per-token rate, so each failure is paid for.
            // Provenance: the DISCOVERY_PATTERNS docblock in BridgeAllowlist, unverified
            // here. An operator who confirms the CLI DOES route it deletes this test and
            // ships the two ids the way codex just did (MODELS + both YAMLs + a migration),
            // never by widening the pattern, which would auto-adopt unverified versions.
            assertThat(BridgeAllowlist.routesModel("claude-code", "claude-mythos-5")).isFalse();
            assertThat(BridgeAllowlist.routesModel("claude-code", "claude-mythos-5-1")).isFalse();
            assertThat(BridgeAllowlist.cliCounterpart("anthropic", "claude-mythos-5")).isNull();
        }
    }

    @Nested
    @DisplayName("cliCounterpart")
    class CliCounterpart {

        @Test
        @DisplayName("returns the CLI when the provider has one AND it routes this exact id")
        void returnsTheCliForARoutablePair() {
            assertThat(BridgeAllowlist.cliCounterpart("anthropic", "claude-opus-4-7")).isEqualTo("claude-code");
            assertThat(BridgeAllowlist.cliCounterpart("anthropic", "claude-sonnet-4-6")).isEqualTo("claude-code");
            assertThat(BridgeAllowlist.cliCounterpart("openai", "gpt-5.5")).isEqualTo("codex");
            assertThat(BridgeAllowlist.cliCounterpart("openai", "gpt-6-sol")).isEqualTo("codex");
            assertThat(BridgeAllowlist.cliCounterpart("google", "gemini-2.5-pro")).isEqualTo("gemini-cli");
        }

        @Test
        @DisplayName("returns null when the CLI exists but cannot route that model")
        void returnsNullWhenTheCliCannotRouteTheModel() {
            // The whole point of the second half of the rule: openai HAS a CLI, but
            // Codex rejects these ids, so no button may be offered for them.
            assertThat(BridgeAllowlist.cliCounterpart("openai", "gpt-5.6")).isNull();
            assertThat(BridgeAllowlist.cliCounterpart("openai", "gpt-5.6-cyber")).isNull();
            assertThat(BridgeAllowlist.cliCounterpart("openai", "gpt-4o")).isNull();
            assertThat(BridgeAllowlist.cliCounterpart("anthropic", "claude-3-opus-20240229")).isNull();
            // mistral-vibe's ids are ~/.vibe config aliases, NOT mistral API ids, so a
            // real mistral catalog row has no counterpart.
            assertThat(BridgeAllowlist.cliCounterpart("mistral", "devstral-2512")).isNull();
        }

        @Test
        @DisplayName("the mistral hop is real but unreachable: the alias would map, the catalog never carries it")
        void mistralMapsOnlyItsCliLocalAliases() {
            // Documented in three places as "matches nothing today", which is a claim about
            // the CATALOG, not about the map: the alias itself resolves fine, so if a row
            // for it ever appeared the button would be correct rather than absent.
            assertThat(BridgeAllowlist.cliCounterpart("mistral", "devstral-2")).isEqualTo("mistral-vibe");
            assertThat(BridgeAllowlist.cliCounterpart("mistral", "devstral-small-2")).isEqualTo("mistral-vibe");
        }

        @Test
        @DisplayName("returns null when the provider has no CLI at all")
        void returnsNullForAProviderWithoutACli() {
            assertThat(BridgeAllowlist.cliCounterpart("deepseek", "deepseek-chat")).isNull();
            assertThat(BridgeAllowlist.cliCounterpart("openrouter", "anthropic/claude-opus-4-7")).isNull();
        }

        @Test
        @DisplayName("null-safe on both halves")
        void isNullSafe() {
            assertThat(BridgeAllowlist.cliCounterpart(null, "claude-opus-4-7")).isNull();
            assertThat(BridgeAllowlist.cliCounterpart("anthropic", null)).isNull();
            assertThat(BridgeAllowlist.cliCounterpart("anthropic", "  ")).isNull();
        }
    }
}
