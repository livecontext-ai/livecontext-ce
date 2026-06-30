package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.config.WebSearchConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link WebSearchToolsProvider#buildHelpModelsPayload()} - the
 * dedicated {@code help_models} action introduced to slim down the default
 * {@code help} payload AND fix the openrouter visibility regression.
 *
 * <p>Audit B M2 motivation: prior to this redesign, {@code web_search.help}
 * embedded the catalog from {@link com.apimarketplace.common.credit.PricingSnapshotClient}
 * - the auth-service pricing snapshot, which lists EVERY pricing row regardless
 * of whether the corresponding provider has a configured API key on this
 * deployment. Result: {@code openrouter} was advertised to the agent on hosts
 * where {@code OPENROUTER_API_KEY} was unset, and {@code agent_browse} would
 * then 401 on the runner side. The new {@code help_models} action sources the
 * catalog from {@link AgentClient#getModelsInfo()} - the SAME endpoint the UI
 * app-header model picker uses, with {@code !configured && !hasDbKey} already
 * filtered out. These tests pin that contract.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebSearchToolsProvider - help_models action")
class WebSearchToolsProviderHelpModelsTest {

    @Mock private WebSearchModule searchModule;
    @Mock private WebFetchModule fetchModule;
    @Mock private BrowserAgentModule browserAgentModule;
    @Mock private InterfaceClient interfaceClient;
    @Mock private WebSearchConfig config;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private AgentClient agentClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebSearchToolsProvider provider;

    @BeforeEach
    void setUp() {
        // help_models doesn't read WebSearchConfig fields, but the constructor
        // calls some wired bean's helpers via Spring metadata; lenient stubs
        // keep Mockito strict-mode happy if any other test accidentally adds
        // a config-reading section to help_models.
        lenient().when(config.getMaxParallelFetches()).thenReturn(5);
        provider = new WebSearchToolsProvider(
            searchModule, fetchModule, browserAgentModule, interfaceClient,
            config, redisTemplate, objectMapper, agentClient);
    }

    private static ToolExecutionContext ctx() {
        return ToolExecutionContext.of("tenant-x");
    }

    private static Map<String, Object> providerEntry(String name, String... modelIds) {
        List<Map<String, Object>> models = new java.util.ArrayList<>();
        int order = 1;
        for (String id : modelIds) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("displayOrder", order++);
            models.add(m);
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("name", name);
        p.put("models", models);
        return p;
    }

    private static Map<String, Object> catalog(String defaultProvider, String defaultModel,
                                               List<Map<String, Object>> providers) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("defaultProvider", defaultProvider);
        c.put("defaultModel", defaultModel);
        c.put("providers", providers);
        return c;
    }

    @Test
    @DisplayName("REGRESSION (Audit B M2 / openrouter bug): a provider absent from agentClient.getModelsInfo never appears in help_models")
    @SuppressWarnings("unchecked")
    void regressionOpenrouterAbsentWhenUnconfigured() {
        // The literal motivating bug: openrouter is in the auth-service pricing
        // snapshot but has no configured API key. agent-service's
        // ModelCatalogService.getAvailableProvidersBase() filters it out via
        // !configured && !hasDbKey. The new help_models contract: WHATEVER
        // agent-service returns is the source of truth - no second-source
        // augmentation, no fallback to the unfiltered pricing snapshot.
        when(agentClient.getModelsInfo("browser_agent")).thenReturn(catalog(
            "anthropic", "claude-sonnet-4-6",
            // openrouter intentionally NOT in the providers list, just like
            // the real getModelsInfo() response on a host without an
            // OPENROUTER_API_KEY.
            List.of(
                providerEntry("anthropic", "claude-sonnet-4-6"),
                providerEntry("openai", "gpt-5"))));

        Map<String, Object> params = Map.of("action", "help_models");
        ToolExecutionResult res = provider.execute("web_search", params, ctx());
        assertThat(res.success()).isTrue();

        Map<String, Object> data = (Map<String, Object>) res.data();
        Map<String, Object> providers = (Map<String, Object>) data.get("providers");
        List<String> pairs = (List<String>) data.get("pairs");

        // Hard guarantee: openrouter must NEVER appear in either shape, even
        // if a future refactor accidentally re-introduces a second source.
        assertThat(providers).doesNotContainKey("openrouter");
        for (String pair : pairs) {
            assertThat(pair)
                .as("openrouter must be filtered out of help_models when not configured")
                .doesNotContain("openrouter");
        }
    }

    @Test
    @DisplayName("help_models exposes top 30 by displayOrder (global priority, not per-provider)")
    @SuppressWarnings("unchecked")
    void truncatesToTop30GloballyByPriority() {
        // Build a catalog with 40 (provider, model) pairs across 3 providers.
        // The flatten+sort+truncate logic must keep the global top 30 - NOT
        // top-N-per-provider. Audit B specifically asked for this guard.
        List<Map<String, Object>> providers = new java.util.ArrayList<>();
        // 15 anthropic models, 15 openai models, 10 google models - all with
        // monotonically increasing displayOrder so the first 30 globally are
        // the lowest displayOrder values.
        List<Map<String, Object>> anthropicModels = new java.util.ArrayList<>();
        List<Map<String, Object>> openaiModels = new java.util.ArrayList<>();
        List<Map<String, Object>> googleModels = new java.util.ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            anthropicModels.add(Map.of("id", "anth-" + i, "displayOrder", i));
            openaiModels.add(Map.of("id", "oai-" + i, "displayOrder", i + 15));
        }
        for (int i = 1; i <= 10; i++) {
            googleModels.add(Map.of("id", "g-" + i, "displayOrder", i + 30));
        }
        providers.add(Map.of("name", "anthropic", "models", anthropicModels));
        providers.add(Map.of("name", "openai", "models", openaiModels));
        providers.add(Map.of("name", "google", "models", googleModels));
        when(agentClient.getModelsInfo("browser_agent")).thenReturn(catalog("anthropic", "anth-1", providers));

        Map<String, Object> params = Map.of("action", "help_models");
        ToolExecutionResult res = provider.execute("web_search", params, ctx());
        Map<String, Object> data = (Map<String, Object>) res.data();

        assertThat((Integer) data.get("total_enabled")).isEqualTo(40);
        assertThat((Integer) data.get("returned")).isEqualTo(30);
        // The 30 highest-priority pairs are anthropic 1..15 + openai 16..30.
        // Google should be entirely cut. If a future change ever truncates
        // per-provider, google would survive and this would catch it.
        Map<String, List<String>> byProvider = (Map<String, List<String>>) data.get("providers");
        assertThat(byProvider).containsOnlyKeys("anthropic", "openai");
        assertThat(byProvider.get("anthropic")).hasSize(15);
        assertThat(byProvider.get("openai")).hasSize(15);
    }

    @Test
    @DisplayName("REGRESSION (audit P1): help_models filters bridge providers from pairs[] so the LLM doesn't pick a model the runner would substitute away - bridges advertised here would trigger noisy model_substituted notices")
    @SuppressWarnings("unchecked")
    void filtersBridgeProvidersFromPairsList() {
        // Catalog has TWO providers: anthropic (direct API) and claude-code
        // (bridge - providerKind="bridge" set by markBridgeProviders). The
        // LLM-facing pairs[] must contain ONLY anthropic; claude-code is
        // hidden because agent_browse would substitute it at runtime anyway.
        Map<String, Object> claudeCode = new LinkedHashMap<>();
        claudeCode.put("name", "claude-code");
        claudeCode.put("providerKind", "bridge");
        claudeCode.put("models", List.of(Map.of("id", "claude-sonnet-4-6", "displayOrder", 1)));
        Map<String, Object> anthropic = new LinkedHashMap<>();
        anthropic.put("name", "anthropic");
        anthropic.put("models", List.of(Map.of("id", "claude-opus-4-6", "displayOrder", 2)));

        Map<String, Object> c = new LinkedHashMap<>();
        c.put("defaultProvider", "anthropic");
        c.put("defaultModel", "claude-opus-4-6");
        c.put("defaultDirectProvider", "anthropic");
        c.put("defaultDirectModel", "claude-opus-4-6");
        c.put("providers", List.of(claudeCode, anthropic));
        when(agentClient.getModelsInfo("browser_agent")).thenReturn(c);

        Map<String, Object> params = Map.of("action", "help_models");
        ToolExecutionResult res = provider.execute("web_search", params, ctx());
        Map<String, Object> data = (Map<String, Object>) res.data();

        // pairs[] excludes the bridge entry entirely.
        List<String> pairs = (List<String>) data.get("pairs");
        for (String pair : pairs) {
            assertThat(pair).doesNotContain("claude-code");
        }
        Map<String, Object> byProvider = (Map<String, Object>) data.get("providers");
        assertThat(byProvider).doesNotContainKey("claude-code");
        // total_enabled now reflects ONLY direct-API models (1 anthropic).
        assertThat((Integer) data.get("total_enabled")).isEqualTo(1);
        // Note explains the hidden bridge so the operator can tell.
        assertThat((String) data.get("note"))
                .contains("bridge model")
                .contains("hidden");
    }

    @Test
    @DisplayName("help_models surfaces the platform default in a 'default' field so the agent never has to guess")
    @SuppressWarnings("unchecked")
    void exposesPlatformDefault() {
        when(agentClient.getModelsInfo("browser_agent")).thenReturn(catalog(
            "anthropic", "claude-sonnet-4-6",
            List.of(providerEntry("anthropic", "claude-sonnet-4-6"))));

        Map<String, Object> params = Map.of("action", "help_models");
        ToolExecutionResult res = provider.execute("web_search", params, ctx());
        Map<String, Object> data = (Map<String, Object>) res.data();

        Map<String, Object> def = (Map<String, Object>) data.get("default");
        assertThat(def)
            .containsEntry("provider", "anthropic")
            .containsEntry("model", "claude-sonnet-4-6");
    }

    @Test
    @DisplayName("REGRESSION (Audit M MAJOR): help_models.default reads defaultDirectProvider/Model FIRST - must match what agent_browse actually substitutes (skipping bridges)")
    @SuppressWarnings("unchecked")
    void exposesDirectApiDefaultNotOverallDefault() {
        // Audit M MAJOR: pre-fix, this test passed via the FALLBACK branch
        // because the catalog stub set only overall defaults. The real
        // contract - "help_models.default reads defaultDirect* first, only
        // falls back to overall when direct is null" - had zero coverage.
        // Pin it explicitly: when the catalog has BOTH defaults set to
        // DIFFERENT values (overall=codex bridge, direct=anthropic), the
        // help_models payload must surface the DIRECT pair to align with
        // what BrowserAgentModule.applyDefaultLlmIfNeeded substitutes.
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("defaultProvider", "codex");
        c.put("defaultModel", "claude-sonnet-4-6-cc");
        c.put("defaultDirectProvider", "anthropic");
        c.put("defaultDirectModel", "claude-opus-4-6");
        c.put("providers", List.of(
            providerEntry("codex", "claude-sonnet-4-6-cc"),
            providerEntry("anthropic", "claude-opus-4-6")));
        when(agentClient.getModelsInfo("browser_agent")).thenReturn(c);

        Map<String, Object> params = Map.of("action", "help_models");
        ToolExecutionResult res = provider.execute("web_search", params, ctx());
        Map<String, Object> data = (Map<String, Object>) res.data();

        Map<String, Object> def = (Map<String, Object>) data.get("default");
        // Direct-API default wins - overall (codex) is intentionally bypassed.
        assertThat(def)
            .containsEntry("provider", "anthropic")
            .containsEntry("model", "claude-opus-4-6");
    }

    @Test
    @DisplayName("help_models.default falls back to overall defaults when defaultDirect* is null (CE bridges-only) - separate path from the direct-first happy case")
    @SuppressWarnings("unchecked")
    void exposesPlatformDefaultFallsBackToOverallWhenDirectIsNull() {
        // Companion to exposesDirectApiDefaultNotOverallDefault: this is the
        // CE deployment edge where ONLY bridges are configured. The catalog
        // returns null defaultDirect*. The help_models payload must surface
        // the overall default so the agent at least sees something - the
        // runner will then surface a precise bridge-incompatibility error
        // when agent_browse actually tries.
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("defaultProvider", "codex");
        c.put("defaultModel", "claude-sonnet-4-6-cc");
        c.put("defaultDirectProvider", null);
        c.put("defaultDirectModel", null);
        c.put("providers", List.of(providerEntry("codex", "claude-sonnet-4-6-cc")));
        when(agentClient.getModelsInfo("browser_agent")).thenReturn(c);

        Map<String, Object> params = Map.of("action", "help_models");
        ToolExecutionResult res = provider.execute("web_search", params, ctx());
        Map<String, Object> data = (Map<String, Object>) res.data();

        Map<String, Object> def = (Map<String, Object>) data.get("default");
        assertThat(def)
            .containsEntry("provider", "codex")
            .containsEntry("model", "claude-sonnet-4-6-cc");
    }

    @Test
    @DisplayName("help_models: CE deployment (agentClient unwired) → returns a stub payload pointing at the omit-llm fallback, never a 5xx")
    @SuppressWarnings("unchecked")
    void ceDeploymentWithoutAgentClientReturnsStub() {
        // CE-without-billing deployments don't wire AgentClient. help_models
        // must still be callable - return a textual stub so the LLM falls
        // back to omitting the llm block (which the runner resolves on its
        // side or fails closed). Audit E NIT regression.
        WebSearchToolsProvider ceProvider = new WebSearchToolsProvider(
            searchModule, fetchModule, browserAgentModule, interfaceClient,
            config, redisTemplate, objectMapper, /* agentClient = */ null);

        Map<String, Object> params = Map.of("action", "help_models");
        ToolExecutionResult res = ceProvider.execute("web_search", params, ctx());

        assertThat(res.success()).isTrue();
        Map<String, Object> data = (Map<String, Object>) res.data();
        assertThat((String) data.get("note")).containsIgnoringCase("not wired");
        // No providers / pairs leaked from a stale source - the catalog is
        // truly empty when the client is unwired (vs. attempting to fall
        // back to PricingSnapshotClient which used to leak openrouter).
        assertThat(data).doesNotContainKey("providers");
        assertThat(data).doesNotContainKey("pairs");
    }

    @Test
    @DisplayName("Cross-tool shape parity: web_search.help.available_models is a Map (not a String) - same shape as agent.help.available_models so the LLM learns one contract")
    @SuppressWarnings("unchecked")
    void defaultHelpAvailableModelsIsSlimMapForCrossToolParity() {
        // Audit E MAJOR-1 regression. The pre-fix code returned a String
        // pointer here while agent(action='help').available_models was a
        // Map{see_also, note}. An LLM that learned one shape was surprised by
        // the other. Both must now expose the same Map contract.
        // No agentClient stub - `help` (the default action) doesn't read the
        // catalog; only `help_models` does. Strict-mode Mockito rejects
        // unnecessary stubbings.
        Map<String, Object> params = Map.of("action", "help");
        ToolExecutionResult res = provider.execute("web_search", params, ctx());

        Map<String, Object> data = (Map<String, Object>) res.data();
        Object available = data.get("available_models");
        assertThat(available)
            .as("available_models must be a Map for shape parity with agent.help")
            .isInstanceOf(Map.class);
        Map<String, Object> availableMap = (Map<String, Object>) available;
        assertThat(availableMap).containsKey("see_also");
        assertThat(availableMap).containsKey("note");
        assertThat((String) availableMap.get("see_also")).contains("help_models");
        // Slim by design: the full catalog must NOT be inlined here - that's
        // the whole point of moving it to help_models.
        assertThat(availableMap).doesNotContainKey("providers");
        assertThat(availableMap).doesNotContainKey("pairs");
    }

    @Test
    @DisplayName("help_models: agent-service unreachable → returns a degraded payload with note, never a 5xx")
    @SuppressWarnings("unchecked")
    void agentServiceFailureReturnsDegradedNote() {
        when(agentClient.getModelsInfo("browser_agent")).thenThrow(new RuntimeException("agent-service down"));

        Map<String, Object> params = Map.of("action", "help_models");
        ToolExecutionResult res = provider.execute("web_search", params, ctx());
        // Help is read-only and free - must never hard-fail. The agent reads
        // the note and falls back to omitting the llm block (which the runtime
        // will resolve via the same catalog or fail closed).
        assertThat(res.success()).isTrue();
        Map<String, Object> data = (Map<String, Object>) res.data();
        assertThat((String) data.get("note")).containsIgnoringCase("could not reach");
    }
}
