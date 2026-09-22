package com.apimarketplace.agent.service;

import com.apimarketplace.agent.credential.LlmCredentialRepository;
import com.apimarketplace.agent.factory.LLMProviderFactory;
import com.apimarketplace.agent.repository.ModelCategorySettingsRepository;
import com.apimarketplace.agent.repository.ModelConfigOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The CLI bridges must not appear in a catalog a CLOUD user is shown.
 *
 * <p>Production is what makes this worth a test rather than a one-line filter: the public
 * {@code /api/v3/chat/models} answered with {@code claude-code} (9 models) and {@code codex}
 * (7 models) marked {@code configured: true}, to anyone, with no token. All four bridges share a
 * single operator subscription, and the access policy stands at {@code admin_only}, so 8 of the 11
 * accounts holding an agent on {@code claude-code} were being offered a provider that answers 403.
 *
 * <p>The three traps this pins, none of which a plain "remove the entry" would survive:
 * <ul>
 *   <li>CE must be untouched. There a bridge is the self-hoster's own CLI under their own login.</li>
 *   <li>{@code defaultProvider} pointed AT a bridge in production, so removing the entry without
 *       recomputing leaves the picker naming a provider that is no longer in the list.</li>
 *   <li>The execution links must keep working. They live in the database and route a billed pair
 *       onto a CLI at dispatch; hiding a provider from a response cannot and must not reach them.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ModelCatalogService - CLI bridges hidden from cloud users")
class ModelCatalogServiceHideBridgesTest {

    @Mock private ModelConfigOverrideRepository repository;
    @Mock private ModelCategorySettingsRepository categoryRepository;
    @Mock private LLMProviderFactory llmProviderFactory;
    @Mock private LlmCredentialRepository credentialRepository;
    @Mock private CachedModelRateLimitProvider cachedRateLimitProvider;
    @Mock private AuthPricingSyncClient authPricingSyncClient;

    private ModelCatalogService service;

    @BeforeEach
    void setUp() {
        service = new ModelCatalogService(
                repository, categoryRepository, llmProviderFactory, credentialRepository,
                cachedRateLimitProvider, "", authPricingSyncClient);
    }

    /** Cloud is the DEFAULT: auth.mode is blank there, "embedded" only on a CE install. */
    private void cloud() {
        ReflectionTestUtils.setField(service, "authMode", "");
    }

    private void selfHosted() {
        ReflectionTestUtils.setField(service, "authMode", "embedded");
    }

    @Test
    @DisplayName("cloud: every CLI bridge is dropped, the API providers are kept")
    void cloudDropsBridgesKeepsApiProviders() {
        cloud();
        Map<String, Object> catalog = productionShapedCatalog();

        service.hideBridgeProviders(catalog);

        assertThat(providerNames(catalog))
            .as("all four bridges, not only the two that happen to be wired today: a bridge that "
                + "gets a CLI installed later must not reappear in the picker on its own")
            .containsExactly("anthropic", "openai")
            .doesNotContain("claude-code", "codex", "gemini-cli", "mistral-vibe");
    }

    @Test
    @DisplayName("CE: the catalog comes back untouched, bridges included")
    void selfHostedKeepsBridges() {
        selfHosted();
        Map<String, Object> catalog = productionShapedCatalog();

        service.hideBridgeProviders(catalog);

        assertThat(providerNames(catalog))
            .as("on CE the CLI is the user's own, running under their own login - hiding it there "
                + "would remove the edition's main reason to exist")
            .contains("claude-code", "codex");
        assertThat(catalog.get("defaultProvider")).isEqualTo("claude-code");
        assertThat(catalog).containsKey("bridgeUrl");
    }

    @Test
    @DisplayName("a default pointing at a bridge is recomputed, never left dangling")
    void bridgeDefaultIsRecomputed() {
        cloud();
        Map<String, Object> catalog = productionShapedCatalog();
        assertThat(catalog.get("defaultProvider"))
            .as("the fixture must start where production starts, or this test proves nothing")
            .isEqualTo("claude-code");

        service.hideBridgeProviders(catalog);

        assertThat(catalog.get("defaultProvider"))
            .as("a picker whose default names a provider absent from the list is worse than one "
                + "showing the bridge: it has no valid selection at all")
            .isEqualTo("anthropic");
        assertThat(catalog.get("defaultModel")).isEqualTo("claude-fable-5-1");
    }

    @Test
    @DisplayName("the internal bridge address stops being served")
    void bridgeUrlIsDropped() {
        cloud();
        Map<String, Object> catalog = productionShapedCatalog();

        service.hideBridgeProviders(catalog);

        assertThat(catalog)
            .as("a LAN address this endpoint serves unauthenticated, and that addresses nothing "
                + "reachable once the bridges are gone")
            .doesNotContainKey("bridgeUrl");
    }

    @Test
    @DisplayName("the bridge address is dropped even when no bridge was listed")
    void bridgeUrlGoesEvenWithNoBridgeInTheList() {
        cloud();
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("providers", new ArrayList<>(List.of(
                apiProvider("anthropic", "claude-fable-5-1", 1))));
        catalog.put("defaultProvider", "anthropic");
        catalog.put("defaultModel", "claude-fable-5-1");
        catalog.put("bridgeUrl", "http://10.0.0.4:8093");

        service.hideBridgeProviders(catalog);

        // bridgeUrl is written whenever conversation.bridge.url is set, independently of whether
        // any bridge survived the availability filter. So the state where every CLI is unverified
        // and none is listed is precisely the one where nothing else would have removed the
        // address - and it was still being served to an anonymous caller.
        assertThat(catalog).doesNotContainKey("bridgeUrl");
        assertThat(providerNames(catalog))
            .as("and the rest of the catalogue is untouched")
            .containsExactly("anthropic");
    }

    @Test
    @DisplayName("a catalog with no bridge is returned as-is, defaults included")
    void noBridgeMeansNoRewrite() {
        cloud();
        // TWO providers, and the stored default is deliberately NOT the lowest displayOrder: with
        // a single provider a recompute lands on the same answer arithmetically, so the assertion
        // below would hold whether or not the method recomputed. That is exactly the vacuous shape
        // this fixture used to have.
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("providers", new ArrayList<>(List.of(
                apiProvider("anthropic", "claude-fable-5-1", 1),
                apiProvider("deepseek", "deepseek-v4-pro", 5))));
        catalog.put("defaultProvider", "deepseek");
        catalog.put("defaultModel", "deepseek-v4-pro");
        catalog.put("bridgeUrl", "http://10.0.0.4:8093");

        service.hideBridgeProviders(catalog);

        // Recomputing here would be a behaviour change on every CE and cloud catalog that never
        // had a bridge, for no reason: the admin's chosen default must survive untouched.
        assertThat(catalog.get("defaultProvider"))
            .as("anthropic sorts first, so a recompute would answer anthropic - this passing on "
                + "deepseek is what proves no recompute happened")
            .isEqualTo("deepseek");
        assertThat(catalog.get("defaultModel")).isEqualTo("deepseek-v4-pro");
        // bridgeUrl is NOT expected to survive: it goes unconditionally on a hosted install, even
        // when no bridge was listed, which is the case bridgeUrlGoesEvenWithNoBridgeInTheList
        // covers. What this test pins is narrower and unrelated: that the DEFAULTS are left alone.
        assertThat(catalog).doesNotContainKey("bridgeUrl");
    }

    @Test
    @DisplayName("cloud: the platform default is never a bridge, whatever the ranking says")
    void cloudDefaultIsNeverABridge() {
        cloud();
        Map<String, Object> catalog = productionShapedCatalog();

        service.recalculateDefaults(catalog);

        // THE root cause, and the one this whole change exists for. In production claude-code was
        // ranked first, so defaultProvider WAS claude-code - and every caller that omits a
        // provider inherits the default: the agent MCP tool substitutes it and then persists it.
        // That is how non-admin accounts ended up owning agents on a CLI they never chose. Hiding
        // the provider from a response does nothing about this; only the default does.
        assertThat(catalog.get("defaultProvider")).isEqualTo("anthropic");
        assertThat(catalog.get("defaultModel")).isEqualTo("claude-fable-5-1");
    }

    @Test
    @DisplayName("CE: the default may be a bridge - the CLI there is the install's own")
    void selfHostedDefaultMayBeABridge() {
        selfHosted();
        Map<String, Object> catalog = productionShapedCatalog();

        service.recalculateDefaults(catalog);

        // A self-hosted install that ranked its local CLI first meant it. Refusing that default
        // would silently re-point every omitted model_provider on every CE install.
        assertThat(catalog.get("defaultProvider")).isEqualTo("claude-code");
        assertThat(catalog.get("defaultModel")).isEqualTo("claude-fable-5");
    }

    @Test
    @DisplayName("cloud with ONLY bridges configured: no default at all, rather than a bridge one")
    void cloudWithOnlyBridgesHasNoDefault() {
        cloud();
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("providers", new ArrayList<>(List.of(
                bridgeProvider("claude-code", "claude-fable-5", 1))));

        service.recalculateDefaults(catalog);

        // The deliberate trade-off, stated because it is a real behaviour change: a cloud install
        // with no API provider configured now reports NO default instead of defaulting everyone
        // onto the shared subscription. An absent default surfaces as an explicit "pick a model";
        // the previous answer silently spent one account's quota on every user who omitted one.
        // On CE the same catalogue keeps its bridge default (selfHostedDefaultMayBeABridge).
        assertThat(catalog.get("defaultProvider")).isNull();
        assertThat(catalog.get("defaultModel")).isNull();
        assertThat(catalog.get("defaultDirectProvider")).isNull();
    }

    @Test
    @DisplayName("the direct-API default stays bridge-free in both editions")
    void directDefaultNeverPicksABridge() {
        for (Runnable edition : List.<Runnable>of(this::cloud, this::selfHosted)) {
            edition.run();
            Map<String, Object> catalog = productionShapedCatalog();

            service.recalculateDefaults(catalog);

            // browser_agent needs atomic completions, which a CLI cannot serve. This held before
            // the change and must keep holding: the cloud rule widens which default excludes
            // bridges, it must not narrow this one.
            assertThat(catalog.get("defaultDirectProvider")).isEqualTo("anthropic");
        }
    }

    @Test
    @DisplayName("an immutable providers list is filtered, not thrown on")
    void immutableProvidersListIsHandled() {
        cloud();
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("providers", List.of(
                apiProvider("anthropic", "claude-fable-5-1", 10),
                bridgeProvider("claude-code", "claude-fable-5", 1)));
        catalog.put("defaultProvider", "claude-code");
        catalog.put("defaultModel", "claude-fable-5");

        service.hideBridgeProviders(catalog);

        // List.of is immutable, so a removeIf here would throw UnsupportedOperationException from
        // inside a read endpoint - a 500 on the public catalogue. The method builds a new list.
        assertThat(providerNames(catalog)).containsExactly("anthropic");
    }

    @Test
    @DisplayName("the bridge address goes even on the providers-less path")
    void bridgeUrlGoesOnTheProvidersLessPath() {
        cloud();
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("bridgeUrl", "http://10.0.0.4:8093");

        // getModelsForCategory writes bridgeUrl and THEN returns early on a missing provider
        // list ("the defensive providers==null path"), so an early return here that skips the
        // removal republishes the address on exactly that path.
        assertThat(service.hideBridgeProviders(catalog)).doesNotContainKey("bridgeUrl");
    }

    @Test
    @DisplayName("an immutable catalogue map is copied, not thrown on")
    void immutableMapIsCopiedForTheBridgeUrl() {
        cloud();
        Map<String, Object> catalog = Map.of(
            "providers", List.of(apiProvider("anthropic", "claude-fable-5-1", 1)),
            "bridgeUrl", "http://10.0.0.4:8093");

        // Map.of throws from remove() unconditionally; this endpoint has 500'd on exactly that
        // class of mutation before. The result must be a copy without the address.
        Map<String, Object> out = service.hideBridgeProviders(catalog);

        assertThat(out).doesNotContainKey("bridgeUrl");
        assertThat(providerNames(out)).containsExactly("anthropic");
    }

    @Test
    @DisplayName("a null or provider-less payload is tolerated, not thrown on")
    void degenerateInputsAreTolerated() {
        cloud();

        assertThat(service.hideBridgeProviders(null)).isNull();

        Map<String, Object> empty = new LinkedHashMap<>();
        assertThat(service.hideBridgeProviders(empty)).isSameAs(empty);
    }

    /**
     * The shape production actually serves, read from {@code /api/v3/chat/models} on
     * livecontext.ai: a bridge first in display order and carrying the platform default, with the
     * direct-API alternative already computed alongside it.
     */
    @Test
    @DisplayName("providersOfferingModels: a provider with no model left is not named, and a CLI bridge never is")
    void providersOfferingModelsNamesOnlyWhatAKeyWouldServe() {
        cloud();
        // What the own-keys panel must decide from: Mistral exposes nothing because an admin
        // switched its models off, so the user must not be invited to paste a Mistral key and
        // must not learn that Mistral is a thing here at all. The bridges are the operator's
        // own CLI subscription: no key to paste, and not an end user's business.
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("providers", new ArrayList<>(List.of(
                apiProvider("Anthropic", "claude-fable-5-1", 10),
                emptyProvider("mistral"),
                bridgeProvider("claude-code", "claude-fable-5", 1))));
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(catalog);
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        List<String> offering = service.providersOfferingModels();

        // Lower-cased: the catalogue does not promise a case and the panel matches on it.
        assertThat(offering).containsExactly("anthropic");
    }

    @Test
    @DisplayName("providersOfferingModels: an empty catalogue names nobody rather than failing")
    void providersOfferingModelsSurvivesAnEmptyCatalogue() {
        cloud();
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("providers", new ArrayList<Map<String, Object>>());
        when(llmProviderFactory.getAllModelsInfoAdmin()).thenReturn(catalog);
        when(repository.findAllByOrderByRankingAsc()).thenReturn(List.of());

        assertThat(service.providersOfferingModels()).isEmpty();
    }

    /** A provider row an admin has emptied: present in the catalogue, serving nothing. */
    private Map<String, Object> emptyProvider(String name) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("configured", true);
        p.put("models", new ArrayList<Map<String, Object>>());
        return p;
    }

    private Map<String, Object> productionShapedCatalog() {
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("providers", new ArrayList<>(List.of(
                apiProvider("anthropic", "claude-fable-5-1", 10),
                apiProvider("openai", "gpt-5.5", 20),
                bridgeProvider("claude-code", "claude-fable-5", 1),
                bridgeProvider("codex", "gpt-6-astra", 2))));
        catalog.put("defaultProvider", "claude-code");
        catalog.put("defaultModel", "claude-fable-5");
        catalog.put("defaultDirectProvider", "anthropic");
        catalog.put("defaultDirectModel", "claude-fable-5-1");
        catalog.put("bridgeUrl", "http://10.0.0.4:8093");
        catalog.put("llmSource", "BYOK");
        return catalog;
    }

    private Map<String, Object> apiProvider(String name, String modelId, int displayOrder) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("configured", true);
        p.put("models", new ArrayList<>(List.of(model(modelId, displayOrder))));
        return p;
    }

    /**
     * {@code providerKind=bridge} is what {@code recalculateDefaults} reads to keep a bridge out
     * of the direct-API default, so the fixture carries it exactly as {@code markBridgeProviders}
     * stamps it. Without it the recompute assertion would pass for the wrong reason.
     */
    private Map<String, Object> bridgeProvider(String name, String modelId, int displayOrder) {
        Map<String, Object> p = apiProvider(name, modelId, displayOrder);
        p.put("providerKind", "bridge");
        return p;
    }

    private Map<String, Object> model(String id, int displayOrder) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("displayOrder", displayOrder);
        return m;
    }

    @SuppressWarnings("unchecked")
    private List<String> providerNames(Map<String, Object> catalog) {
        return ((List<Map<String, Object>>) catalog.get("providers")).stream()
                .map(p -> (String) p.get("name"))
                .toList();
    }
}
