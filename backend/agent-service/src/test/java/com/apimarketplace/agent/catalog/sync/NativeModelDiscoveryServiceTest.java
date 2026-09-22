package com.apimarketplace.agent.catalog.sync;

import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.provider.OpenAICompatibleProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;

import static org.mockito.Mockito.*;

/**
 * Unit tests for the third catalog source: the per-provider {@code /models}
 * discovery pass.
 *
 * <p>The scenario these pin is the one that motivated the whole feature. On
 * 2026-08-19 Z.AI's own API served glm-5.2 / glm-5.3 / glm-5v-turbo while
 * LiteLLM's {@code zai} block still ended at glm-5.1, so no sync could ever
 * surface them. Discovery closes that gap: the vendor endpoint says which
 * models exist, OpenRouter's entry for the same id supplies the price, and the
 * row is then shaped exactly like a feed row.
 *
 * <p>The two invariants worth pinning are the provenance ({@code priceFrom} +
 * the exact {@code openRouterId}, so a rate stays traceable) and the no-donor
 * case: a model OpenRouter does not carry must land UNPRICED rather than with a
 * guessed rate, because unpriced is not free - the billing path would otherwise
 * charge it at the platform default.
 */
@DisplayName("NativeModelDiscoveryService - vendor endpoint as the existence authority")
class NativeModelDiscoveryServiceTest {

    private LLMProviderFactory providerFactory;
    private NativeModelDiscoveryService service;

    @BeforeEach
    void setUp() {
        providerFactory = mock(LLMProviderFactory.class);
        // Default: no provider registered. Each test wires the ones it needs.
        when(providerFactory.findProvider(anyString())).thenReturn(Optional.empty());
        service = new NativeModelDiscoveryService(providerFactory);
    }

    private OpenAICompatibleProvider providerServing(String name, String... modelIds) {
        OpenAICompatibleProvider provider = mock(OpenAICompatibleProvider.class);
        when(provider.listRemoteModelIds()).thenReturn(Optional.of(List.of(modelIds)));
        when(providerFactory.findProvider(name)).thenReturn(Optional.of(provider));
        return provider;
    }

    @Test
    @DisplayName("Emits the vendor ids no feed carries - the glm-5.2 / 5.3 gap")
    void emitsIdsMissingFromTheFeeds() {
        providerServing("zai", "glm-5.1", "glm-5.2", "glm-5.3", "glm-5v-turbo");

        // What LiteLLM's zai block actually carried that day.
        List<Map<String, Object>> feed = List.of(feedRow("zai", "glm-5.1"));

        var result = service.discover(feed, Set.of(), List.of(), Set.of());

        assertThat(ids(result.models())).containsExactlyInAnyOrder(
                "glm-5.2", "glm-5.3", "glm-5v-turbo");
        assertThat(result.discoveredByProvider()).containsEntry("zai", 3);
        assertThat(result.models().get(0).get("provider")).isEqualTo("zai");
        assertThat(result.models().get(0).get("source")).isEqualTo("discovery");
        assertThat(result.models().get(0).get("mode")).isEqualTo("chat");
    }

    @Test
    @DisplayName("A discovered row is priced from OpenRouter's entry for the same vendor id")
    void takesThePriceFromOpenRouter() {
        providerServing("zai", "glm-5.3");

        List<Map<String, Object>> openRouter = List.of(openRouterRow(
                "z-ai/glm-5.3", "1.4000", "4.4000"));

        var result = service.discover(List.of(), Set.of(), openRouter, Set.of());

        Map<String, Object> row = result.models().get(0);
        assertThat(row.get("priceInput")).isEqualTo("1.4000");
        assertThat(row.get("priceOutput")).isEqualTo("4.4000");
    }

    @Test
    @DisplayName("The price records exactly which OpenRouter entry it came from")
    void stampsThePriceProvenance() {
        // "Is that really OpenRouter's price?" has to stay answerable per row,
        // months later, without re-deriving anything - hence the donor's exact
        // aggregator id next to the rate it supplied.
        providerServing("zai", "glm-5.3");

        var result = service.discover(List.of(), Set.of(),
                List.of(openRouterRow("z-ai/glm-5.3", "1.4000", "4.4000")), Set.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) result.models().get(0).get("feedMetadata");
        assertThat(meta).containsEntry("priceFrom", "openrouter");
        assertThat(meta).containsEntry("openRouterId", "z-ai/glm-5.3");
    }

    @Test
    @DisplayName("Cache rates and tier travel with the price, so the row lands in the right bucket")
    void carriesTheDerivedPricingFields() {
        providerServing("zai", "glm-5.3");

        Map<String, Object> donor = openRouterRow("z-ai/glm-5.3", "1.4000", "4.4000");
        donor.put("priceFloorInput", "1.4000");
        donor.put("priceFloorOutput", "4.4000");
        donor.put("priceCacheRead", "0.1100");
        donor.put("supportsPromptCaching", true);
        donor.put("tier", "mid");

        var result = service.discover(List.of(), Set.of(), List.of(donor), Set.of());

        Map<String, Object> row = result.models().get(0);
        assertThat(row.get("priceCacheRead")).isEqualTo("0.1100");
        assertThat(row.get("supportsPromptCaching")).isEqualTo(true);
        // tier is a deterministic function of the output price; leaving it
        // behind would file a 4.40 model under "unknown".
        assertThat(row.get("tier")).isEqualTo("mid");
    }

    @Test
    @DisplayName("No OpenRouter entry: the row lands unpriced rather than with a guessed rate")
    void staysUnpricedWhenOpenRouterHasNoEntry() {
        // The unpriced-enable guard then keeps it out of the picker until an
        // admin prices it. Unpriced is not free - ModelPricingService would
        // otherwise bill it at the platform default rate.
        providerServing("minimax", "MiniMax-M4");

        var result = service.discover(List.of(), Set.of(), List.of(), Set.of());

        Map<String, Object> row = result.models().get(0);
        assertThat(row.get("modelId")).isEqualTo("MiniMax-M4");
        assertThat(row).doesNotContainKeys("priceInput", "priceOutput", "tier", "contextWindow");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) row.get("feedMetadata");
        assertThat(meta).containsEntry("priceFrom", "none");
        assertThat(meta).containsEntry("capabilitiesFrom", "none");
    }

    @Test
    @DisplayName("Capabilities ARE borrowed from the aggregator row for the same vendor id")
    void copiesCapabilitiesFromTheOpenRouterTwin() {
        providerServing("moonshot", "kimi-k3");

        Map<String, Object> donor = openRouterRow("moonshotai/kimi-k3", "0.6000", "2.5000");
        donor.put("contextWindow", 262144);
        donor.put("supportsTools", true);
        donor.put("supportsVision", true);
        donor.put("supportsReasoning", true);

        var result = service.discover(List.of(), Set.of(), List.of(donor), Set.of());

        Map<String, Object> row = result.models().get(0);
        assertThat(row.get("modelId")).isEqualTo("kimi-k3");
        assertThat(row.get("contextWindow")).isEqualTo(262144);
        assertThat(row.get("supportsTools")).isEqualTo(true);
        assertThat(row.get("supportsVision")).isEqualTo(true);
        // The same donor supplies the price, so a discovered row is complete
        // enough to be indistinguishable from a feed row downstream.
        assertThat(row.get("priceInput")).isEqualTo("0.6000");
        assertThat(row.get("priceOutput")).isEqualTo("2.5000");
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) row.get("feedMetadata");
        assertThat(meta).containsEntry("capabilitiesFrom", "openrouter");
    }

    @Test
    @DisplayName("Gap-fill only: a model a feed already covers is left to the feed")
    void suppressesIdsTheFeedAlreadyCovers() {
        providerServing("zai", "glm-4.7", "glm-5.1");

        var result = service.discover(
                List.of(feedRow("zai", "glm-4.7"), feedRow("zai", "glm-5.1")),
                Set.of(), List.of(), Set.of());

        assertThat(result.models()).isEmpty();
        assertThat(result.discoveredByProvider()).isEmpty();
    }

    @Test
    @DisplayName("Gap-fill only: a model already in the catalog is left alone")
    void suppressesIdsAlreadyInTheCatalog() {
        providerServing("zai", "glm-5.2");

        // An admin already added and priced it by hand - discovery must not
        // re-emit it, or the merge would overwrite that curated row with a
        // price-free one.
        var result = service.discover(List.of(), Set.of(NativeModelDiscoveryService.key("zai", "glm-5.2")), List.of(), Set.of());

        assertThat(result.models()).isEmpty();
    }

    @Test
    @DisplayName("A provider with no key is skipped and reported, not failed")
    void skipsUnconfiguredProviders() {
        OpenAICompatibleProvider unkeyed = mock(OpenAICompatibleProvider.class);
        when(unkeyed.listRemoteModelIds()).thenReturn(Optional.empty());
        when(providerFactory.findProvider("qwen")).thenReturn(Optional.of(unkeyed));

        var result = service.discover(List.of(), Set.of(), List.of(), Set.of());

        assertThat(result.models()).isEmpty();
        assertThat(result.skippedProviders()).contains("qwen");
    }

    @Test
    @DisplayName("One vendor being down does not stop the others")
    void oneBrokenProviderDoesNotBlockTheRest() {
        OpenAICompatibleProvider broken = mock(OpenAICompatibleProvider.class);
        when(broken.listRemoteModelIds()).thenReturn(Optional.empty());
        when(providerFactory.findProvider("zai")).thenReturn(Optional.of(broken));
        providerServing("moonshot", "kimi-k3");

        var result = service.discover(List.of(), Set.of(), List.of(), Set.of());

        assertThat(ids(result.models())).containsExactly("kimi-k3");
        assertThat(result.skippedProviders()).contains("zai");
        assertThat(result.skippedProviders()).doesNotContain("moonshot");
    }

    @Test
    @DisplayName("A vendor with its own provider class is discovered - the deepseek-flash gap")
    void discoversAVendorThatHasItsOwnProviderClass() {
        // DeepSeek speaks the OpenAI dialect but has a dedicated provider class
        // rather than being an OpenAICompatibleProvider. Discovery used to gate
        // on that class, so this vendor was never asked and deepseek-flash
        // could not reach the catalog through any refresh. Mocking the
        // INTERFACE is the point of this test: a provider qualifies by being
        // able to answer, not by its type.
        LLMProvider deepseek = mock(LLMProvider.class);
        when(deepseek.listRemoteModelIds()).thenReturn(Optional.of(List.of("deepseek-flash")));
        when(providerFactory.findProvider("deepseek")).thenReturn(Optional.of(deepseek));

        var result = service.discover(List.of(), Set.of(), List.of(), Set.of());

        assertThat(ids(result.models())).containsExactly("deepseek-flash");
        assertThat(result.discoveredByProvider()).containsEntry("deepseek", 1);
    }

    @Test
    @DisplayName("A provider that cannot be asked is REPORTED, so 'never asked' stops looking like 'found nothing'")
    void reportsProvidersItCouldNotAsk() {
        // findProvider returns empty for everything by default. Previously a
        // provider that could not be probed was dropped with a bare `continue`,
        // which is exactly what hid the deepseek gap: the result was
        // indistinguishable from a vendor that genuinely serves nothing new.
        var result = service.discover(List.of(), Set.of(), List.of(), Set.of());

        assertThat(result.models()).isEmpty();
        assertThat(result.skippedProviders()).contains("deepseek", "anthropic", "openai", "google");
    }

    @Test
    @DisplayName("The aggregator is never probed - its listing IS the feed we already parse")
    void neverProbesTheAggregator() {
        // openrouter IS relay-executable and WOULD be probed by the plain loop.
        // Its /models listing is the OpenRouter feed, which the sync already
        // fetches through a parser that drops :free / :nitro duplicates and
        // unpriced rows. Probing it here would re-import the same catalogue
        // with none of those filters.
        service.discover(List.of(), Set.of(), List.of(), Set.of());

        verify(providerFactory, never()).findProvider("openrouter");
        // Every OTHER relay provider IS asked, so this is a real exclusion
        // rather than a loop that happens to be empty.
        verify(providerFactory).findProvider("deepseek");
    }

    @Test
    @DisplayName("An id the feed hid as a dated twin is not re-emitted under its dated form")
    void doesNotReadmitDatedAliases() {
        // Anthropic's own listing returns dated ids, and the feed keeps only
        // the canonical twin. Without the feed's record of what it hid, every
        // sync with an Anthropic key would add a duplicate row per model.
        LLMProvider anthropic = mock(LLMProvider.class);
        when(anthropic.listRemoteModelIds()).thenReturn(Optional.of(List.of(
                "claude-sonnet-4-5", "claude-sonnet-4-5-20250929")));
        when(providerFactory.findProvider("anthropic")).thenReturn(Optional.of(anthropic));

        var result = service.discover(
                List.of(feedRow("anthropic", "claude-sonnet-4-5")),
                Set.of(), List.of(),
                Set.of(NativeModelDiscoveryService.key("anthropic", "claude-sonnet-4-5-20250929")));

        assertThat(ids(result.models())).isEmpty();
    }

    @Test
    @DisplayName("Budget exhausted: the remaining vendors are 'not asked', never reported as having failed")
    void budgetExhaustionIsReportedApartFromFailures() {
        providerServing("zai", "glm-6");
        // -1, not 0: the guard is strictly-greater, so a zero budget only
        // trips because building the probe order burns more than clock
        // granularity. Negative makes it unconditional.
        service.discoveryBudgetNanos = -1;

        var result = service.discover(List.of(), Set.of(), List.of(), Set.of());

        assertThat(result.models()).isEmpty();
        assertThat(result.notAskedProviders()).contains("zai", "deepseek", "anthropic");
        // The distinction that matters: nobody called these, so none of them
        // failed. Collapsing the two rebuilds the ambiguity this class exists
        // to remove, and tells an operator to go debug a healthy vendor.
        assertThat(result.skippedProviders()).isEmpty();
        verify(providerFactory, never()).findProvider("zai");
    }

    @Test
    @DisplayName("A vendor starved by the budget is asked FIRST on the next pass")
    void aStarvedVendorGoesFirstNextTime() {
        // Order is alphabetical, so exhaustion always drops the same tail:
        // perplexity, qwen, xai, zai. Those are exactly the vendors whose
        // models the mirrors lag on, so a tail that stays starved would aim
        // the budget at the feature's own purpose.
        assertThat(service.probeOrder()).startsWith("anthropic");

        service.owedFromLastPass = List.of("zai", "qwen");

        assertThat(service.probeOrder()).startsWith("zai", "qwen", "anthropic");
        // Owing a turn re-orders the pass, it never shortens it.
        assertThat(service.probeOrder())
                .containsExactlyInAnyOrderElementsOf(NativeModelDiscoveryService.sortedRelayProviders());
    }

    @Test
    @DisplayName("A pass that finishes owes nothing, so the next one is plain alphabetical again")
    void aCompletedPassClearsTheDebt() {
        service.owedFromLastPass = List.of("zai");

        service.discover(List.of(), Set.of(), List.of(), Set.of());

        assertThat(service.owedFromLastPass).isEmpty();
        assertThat(service.probeOrder()).startsWith("anthropic");
    }

    @Test
    @DisplayName("A provider that left the relay set is not resurrected by an old debt")
    void staleDebtDoesNotResurrectAnUnknownProvider() {
        service.owedFromLastPass = List.of("some-retired-vendor");

        assertThat(service.probeOrder())
                .doesNotContain("some-retired-vendor")
                .containsExactlyInAnyOrderElementsOf(NativeModelDiscoveryService.sortedRelayProviders());
    }

    @Test
    @DisplayName("What the feed declined does not walk back in through the discovery door")
    void doesNotReadmitWhatTheFeedDeclined() {
        // A vendor listing states neither a mode nor a tool-calling capability
        // nor a price: OpenAI's /models returns embeddings and image endpoints
        // next to chat models, and gpt-5-chat next to gpt-5.4. Each exclusion
        // below is a decision the feed already took, so the filter rests on a
        // stated fact rather than on pattern-matching the id.
        LLMProvider openai = mock(LLMProvider.class);
        when(openai.listRemoteModelIds()).thenReturn(Optional.of(List.of(
                "gpt-9-preview",      // unknown to every source: the point of discovery
                "text-embedding-4",   // declined: wrong mode
                "gpt-5-chat",         // declined: no tool-calling
                "gpt-9-free")));      // declined: no real price
        when(providerFactory.findProvider("openai")).thenReturn(Optional.of(openai));

        var result = service.discover(List.of(), Set.of(), List.of(),
                Set.of(NativeModelDiscoveryService.key("openai", "text-embedding-4"),
                       NativeModelDiscoveryService.key("openai", "gpt-5-chat"),
                       NativeModelDiscoveryService.key("openai", "gpt-9-free")));

        assertThat(ids(result.models())).containsExactly("gpt-9-preview");
        assertThat(result.discoveredByProvider()).containsEntry("openai", 1);
    }

    @Test
    @DisplayName("A tenant's own fine-tune is never published into the shared catalog")
    void skipsFineTuneIds() {
        // OpenAI's listing returns the CALLING organisation's fine-tunes next
        // to the base models. They are real and callable - for that one tenant.
        // Publishing one advertises a model every other tenant would 404 on,
        // and no feed can warn us because no feed has ever seen it.
        LLMProvider openai = mock(LLMProvider.class);
        when(openai.listRemoteModelIds()).thenReturn(Optional.of(List.of(
                "gpt-9-preview", "ft:gpt-4o-mini:acme::a1b2c3")));
        when(providerFactory.findProvider("openai")).thenReturn(Optional.of(openai));

        var result = service.discover(List.of(), Set.of(), List.of(), Set.of());

        assertThat(ids(result.models())).containsExactly("gpt-9-preview");
    }

    @Test
    @DisplayName("A namespaced id is never emitted as a native row - it would break row identity")
    void skipsNamespacedIds() {
        LLMProvider provider = mock(LLMProvider.class);
        when(provider.listRemoteModelIds()).thenReturn(Optional.of(List.of(
                "glm-6", "some-vendor/glm-6")));
        when(providerFactory.findProvider("zai")).thenReturn(Optional.of(provider));

        var result = service.discover(List.of(), Set.of(), List.of(), Set.of());

        assertThat(ids(result.models())).containsExactly("glm-6");
    }

    @Test
    @DisplayName("The donor namespaces added with the widened provider set actually match")
    void findsDonorsForTheNewlyProbedVendors() {
        // These five namespaces were added when discovery stopped being gated
        // on the provider class. An entry that does not match leaves the row
        // unpriced, which is safe but silent - so pin that they resolve.
        LLMProvider openai = mock(LLMProvider.class);
        when(openai.listRemoteModelIds()).thenReturn(Optional.of(List.of("gpt-9-preview")));
        when(providerFactory.findProvider("openai")).thenReturn(Optional.of(openai));
        LLMProvider perplexity = mock(LLMProvider.class);
        when(perplexity.listRemoteModelIds()).thenReturn(Optional.of(List.of("sonar-5")));
        when(providerFactory.findProvider("perplexity")).thenReturn(Optional.of(perplexity));

        var result = service.discover(List.of(), Set.of(), List.of(
                openRouterRow("openai/gpt-9-preview", "3.0000", "12.0000"),
                openRouterRow("perplexity/sonar-5", "1.0000", "3.0000")), Set.of());

        Map<String, Object> gpt = result.models().stream()
                .filter(m -> "gpt-9-preview".equals(m.get("modelId"))).findFirst().orElseThrow();
        Map<String, Object> sonar = result.models().stream()
                .filter(m -> "sonar-5".equals(m.get("modelId"))).findFirst().orElseThrow();
        assertThat(gpt.get("priceInput")).isEqualTo("3.0000");
        assertThat(sonar.get("priceOutput")).isEqualTo("3.0000");
    }

    @Test
    @DisplayName("The remaining new namespaces resolve too - anthropic, google, cohere")
    void findsDonorsForTheRemainingNewVendors() {
        LLMProvider anthropic = mock(LLMProvider.class);
        when(anthropic.listRemoteModelIds()).thenReturn(Optional.of(List.of("claude-opus-9")));
        when(providerFactory.findProvider("anthropic")).thenReturn(Optional.of(anthropic));
        LLMProvider google = mock(LLMProvider.class);
        when(google.listRemoteModelIds()).thenReturn(Optional.of(List.of("gemini-4-pro")));
        when(providerFactory.findProvider("google")).thenReturn(Optional.of(google));
        LLMProvider cohere = mock(LLMProvider.class);
        when(cohere.listRemoteModelIds()).thenReturn(Optional.of(List.of("command-a-2")));
        when(providerFactory.findProvider("cohere")).thenReturn(Optional.of(cohere));

        var result = service.discover(List.of(), Set.of(), List.of(
                openRouterRow("anthropic/claude-opus-9", "5.0000", "25.0000"),
                openRouterRow("google/gemini-4-pro", "2.0000", "8.0000"),
                openRouterRow("cohere/command-a-2", "0.5000", "1.5000")), Set.of());

        Map<String, String> pricedInputs = new LinkedHashMap<>();
        for (Map<String, Object> row : result.models()) {
            pricedInputs.put((String) row.get("modelId"), (String) row.get("priceInput"));
        }
        assertThat(pricedInputs)
                .containsEntry("claude-opus-9", "5.0000")
                .containsEntry("gemini-4-pro", "2.0000")
                .containsEntry("command-a-2", "0.5000");
    }

    @Test
    @DisplayName("The non-chat filter is scoped per provider, never across vendors")
    void nonChatFilterDoesNotLeakAcrossProviders() {
        // Two vendors can ship the same id under different modes. Excluding
        // "embed-v1" for one must not silence the other's chat model.
        LLMProvider openai = mock(LLMProvider.class);
        when(openai.listRemoteModelIds()).thenReturn(Optional.of(List.of("embed-v1")));
        when(providerFactory.findProvider("openai")).thenReturn(Optional.of(openai));
        providerServing("zai", "embed-v1");

        var result = service.discover(List.of(), Set.of(), List.of(),
                Set.of(NativeModelDiscoveryService.key("openai", "embed-v1")));

        assertThat(result.discoveredByProvider()).containsEntry("zai", 1);
        assertThat(result.discoveredByProvider()).doesNotContainKey("openai");
    }

    @Test
    @DisplayName("A vendor listing the same id twice yields one row")
    void deduplicatesWithinAProviderListing() {
        providerServing("zai", "glm-5.3", "glm-5.3");

        var result = service.discover(List.of(), Set.of(), List.of(), Set.of());

        assertThat(result.models()).hasSize(1);
        assertThat(result.discoveredByProvider()).containsEntry("zai", 1);
    }

    @Test
    @DisplayName("Blank ids in a sloppy vendor response are dropped")
    void ignoresBlankIds() {
        OpenAICompatibleProvider provider = mock(OpenAICompatibleProvider.class);
        when(provider.listRemoteModelIds())
                .thenReturn(Optional.of(Arrays.asList("glm-5.3", "", "   ")));
        when(providerFactory.findProvider("zai")).thenReturn(Optional.of(provider));

        var result = service.discover(List.of(), Set.of(), List.of(), Set.of());

        assertThat(ids(result.models())).containsExactly("glm-5.3");
    }

    @Test
    @DisplayName("The aggregator namespace map does not confuse two vendors")
    void doesNotBorrowCapabilitiesAcrossVendors() {
        providerServing("minimax", "glm-5.3");

        // A z-ai row must never donate to a minimax model of the same id.
        Map<String, Object> donor = openRouterRow("z-ai/glm-5.3", "1.4", "4.4");
        donor.put("contextWindow", 999999);

        var result = service.discover(List.of(), Set.of(), List.of(donor), Set.of());

        assertThat(result.models().get(0)).doesNotContainKey("contextWindow");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static List<String> ids(List<Map<String, Object>> models) {
        return models.stream().map(m -> (String) m.get("modelId")).toList();
    }

    private static Map<String, Object> feedRow(String provider, String modelId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", provider);
        m.put("modelId", modelId);
        m.put("priceInput", "1.0000");
        m.put("priceOutput", "2.0000");
        return m;
    }

    private static Map<String, Object> openRouterRow(String fullId, String in, String out) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", "openrouter");
        m.put("modelId", fullId);
        m.put("priceInput", in);
        m.put("priceOutput", out);
        return m;
    }
}
