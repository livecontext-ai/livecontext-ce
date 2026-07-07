package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.resolver.LlmCredentialResolver;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.orchestrator.config.WebSearchConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The browser agent's LLM stepping runs inside the websearch runner against a
 * directly-resolved provider key, so it never crosses the agent-service link
 * chokepoint. These tests pin the orchestrator-side reroute that closes that
 * bypass: {@code maybeApplyExecutionLink} swaps {@code llm.provider}/{@code
 * llm.model} to the linked direct-API target BEFORE the key lookup, while the
 * caller's original parameters (what observability reads) keep the billed pair.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BrowserAgentModule - model execution links")
class BrowserAgentModuleExecutionLinkTest {

    @Mock private RestTemplate restTemplate;
    @Mock private WebSearchConfig config;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private LlmCredentialResolver credentialResolver;
    @Mock private AgentClient agentClient;

    private BrowserAgentModule module;

    @BeforeEach
    void setUp() {
        lenient().when(config.getServiceUrl()).thenReturn("http://websearch-host:8085");
        module = new BrowserAgentModule(restTemplate, config, redisTemplate, new ObjectMapper(),
            null, null, credentialResolver, null, null, agentClient);
    }

    @Test
    @DisplayName("a linked billed pair is swapped to the execution target in the runner's llm block")
    void linkSwapsProviderAndModel() {
        when(agentClient.resolveExecutionLinkApiTarget("anthropic", "claude-opus-4-8"))
            .thenReturn(Optional.of(new AgentClient.ExecutionLinkTarget("openrouter", "anthropic/claude-3.5-sonnet")));
        Map<String, Object> llm = new LinkedHashMap<>(Map.of(
            "provider", "anthropic", "model", "claude-opus-4-8"));

        module.maybeApplyExecutionLink(llm);

        assertThat(llm)
            .containsEntry("provider", "openrouter")
            .containsEntry("model", "anthropic/claude-3.5-sonnet");
    }

    @Test
    @DisplayName("an unlinked pair is left untouched")
    void unlinkedPairUntouched() {
        when(agentClient.resolveExecutionLinkApiTarget("openai", "gpt-4o")).thenReturn(Optional.empty());
        Map<String, Object> llm = new LinkedHashMap<>(Map.of("provider", "openai", "model", "gpt-4o"));

        module.maybeApplyExecutionLink(llm);

        assertThat(llm).containsEntry("provider", "openai").containsEntry("model", "gpt-4o");
    }

    @Test
    @DisplayName("a bridge-routed block (CE cloud relay / explicit bridge session) never consults links")
    void bridgeKindSkipsLinkResolution() {
        Map<String, Object> llm = new LinkedHashMap<>(Map.of(
            "provider", "anthropic", "model", "claude-opus-4-8", "provider_kind", "bridge"));

        module.maybeApplyExecutionLink(llm);

        verify(agentClient, never()).resolveExecutionLinkApiTarget(any(), any());
        assertThat(llm).containsEntry("provider", "anthropic");
    }

    @Test
    @DisplayName("an explicit caller-supplied api_key pins the pair - links are not consulted")
    void explicitApiKeySkipsLinkResolution() {
        Map<String, Object> llm = new LinkedHashMap<>(Map.of(
            "provider", "anthropic", "model", "claude-opus-4-8", "api_key", "sk-explicit"));

        module.maybeApplyExecutionLink(llm);

        verify(agentClient, never()).resolveExecutionLinkApiTarget(any(), any());
    }

    @Test
    @DisplayName("a block missing provider or model never consults links (the runner reports the precise config error)")
    void incompleteBlockSkipsLinkResolution() {
        Map<String, Object> noModel = new LinkedHashMap<>(Map.of("provider", "anthropic"));
        Map<String, Object> noProvider = new LinkedHashMap<>(Map.of("model", "claude-opus-4-8"));

        module.maybeApplyExecutionLink(noModel);
        module.maybeApplyExecutionLink(noProvider);

        verify(agentClient, never()).resolveExecutionLinkApiTarget(any(), any());
    }

    @Test
    @DisplayName("a module without an AgentClient (test wiring) is a no-op, not an NPE")
    void nullAgentClientIsNoOp() {
        BrowserAgentModule bare = new BrowserAgentModule(
            restTemplate, config, redisTemplate, new ObjectMapper());
        Map<String, Object> llm = new LinkedHashMap<>(Map.of("provider", "openai", "model", "gpt-4o"));

        bare.maybeApplyExecutionLink(llm);

        assertThat(llm).containsEntry("provider", "openai");
    }

    @Test
    @DisplayName("linked run pricing: by_model keyed by the EXECUTION model is priced at the BILLED model's rate (cost_usd non-zero, no skipped contribution)")
    void linkedRunPricesTokensAtBilledModelRate() throws Exception {
        com.apimarketplace.common.credit.PricingSnapshotClient pricingClient =
            org.mockito.Mockito.mock(com.apimarketplace.common.credit.PricingSnapshotClient.class);
        // Only the BILLED pair has a rate; the runner reports the EXECUTION model key.
        // (No stub for the exec-model key: a linked entry is priced at the billed
        // rate directly and never looks the exec model up.)
        when(pricingClient.getRates("anthropic", "claude-opus-4-8"))
            .thenReturn(Optional.of(new com.apimarketplace.common.credit.PricingSnapshotClient.PricingRates(
                new java.math.BigDecimal("15.00"),  // input USD per 1M
                new java.math.BigDecimal("75.00"),  // output USD per 1M
                java.math.BigDecimal.ZERO)));
        BrowserAgentModule moduleWithPricing = new BrowserAgentModule(
            restTemplate, config, redisTemplate, new ObjectMapper(),
            null, null, credentialResolver, null, pricingClient, agentClient);

        when(agentClient.resolveExecutionLinkApiTarget("anthropic", "claude-opus-4-8"))
            .thenReturn(Optional.of(new AgentClient.ExecutionLinkTarget("openrouter", "anthropic/claude-3.5-sonnet")));
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        org.springframework.data.redis.core.ListOperations<String, String> listOps =
            org.mockito.Mockito.mock(org.springframework.data.redis.core.ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        // The runner ran the EXECUTION model, so by_model is keyed by it.
        when(listOps.leftPop(org.mockito.ArgumentMatchers.anyString(), any(java.time.Duration.class)))
            .thenReturn("{"
                + "\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\","
                + "\"cost\":{\"tokens_in\":1000000,\"tokens_out\":100000,\"llm_calls\":1,\"cost_usd\":0.0,"
                + "\"by_model\":{\"anthropic/claude-3.5-sonnet\":{\"prompt_tokens\":1000000,\"completion_tokens\":100000}}}}");
        when(restTemplate.postForObject(org.mockito.ArgumentMatchers.anyString(), any(), org.mockito.ArgumentMatchers.eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-linked-cost"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", new LinkedHashMap<>(Map.of("provider", "anthropic", "model", "claude-opus-4-8")));

        var result = moduleWithPricing.execute("agent_browse", params, null, null).orElseThrow();

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        @SuppressWarnings("unchecked")
        Map<String, Object> cost = (Map<String, Object>) data.get("cost");
        // (1_000_000 × 15.00 + 100_000 × 75.00) / 1_000_000 = 15.0 + 7.5 = 22.5 USD,
        // the BILLED price - the user pays the billed pair regardless of the exec target.
        assertThat(((Number) cost.get("cost_usd")).doubleValue())
            .isCloseTo(22.5, org.assertj.core.data.Offset.offset(0.000001));
    }

    @Test
    @DisplayName("linked run pricing: the BILLED rate wins even when the EXECUTION model has its own rate row (display price = what the user pays)")
    void linkedRunBilledRateWinsOverRatedExecutionModel() throws Exception {
        com.apimarketplace.common.credit.PricingSnapshotClient pricingClient =
            org.mockito.Mockito.mock(com.apimarketplace.common.credit.PricingSnapshotClient.class);
        // BOTH pairs are rated: the cheap execution-model row is the bait a
        // lookup-by-runner-key implementation would consume.
        lenient().when(pricingClient.getRates("anthropic", "anthropic/claude-3.5-sonnet"))
            .thenReturn(Optional.of(new com.apimarketplace.common.credit.PricingSnapshotClient.PricingRates(
                new java.math.BigDecimal("3.00"), new java.math.BigDecimal("15.00"), java.math.BigDecimal.ZERO)));
        when(pricingClient.getRates("anthropic", "claude-opus-4-8"))
            .thenReturn(Optional.of(new com.apimarketplace.common.credit.PricingSnapshotClient.PricingRates(
                new java.math.BigDecimal("15.00"), new java.math.BigDecimal("75.00"), java.math.BigDecimal.ZERO)));
        BrowserAgentModule moduleWithPricing = new BrowserAgentModule(
            restTemplate, config, redisTemplate, new ObjectMapper(),
            null, null, credentialResolver, null, pricingClient, agentClient);

        when(agentClient.resolveExecutionLinkApiTarget("anthropic", "claude-opus-4-8"))
            .thenReturn(Optional.of(new AgentClient.ExecutionLinkTarget("openrouter", "anthropic/claude-3.5-sonnet")));
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        org.springframework.data.redis.core.ListOperations<String, String> listOps =
            org.mockito.Mockito.mock(org.springframework.data.redis.core.ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(org.mockito.ArgumentMatchers.anyString(), any(java.time.Duration.class)))
            .thenReturn("{"
                + "\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\","
                + "\"cost\":{\"tokens_in\":1000000,\"tokens_out\":100000,\"llm_calls\":1,\"cost_usd\":0.0,"
                + "\"by_model\":{\"anthropic/claude-3.5-sonnet\":{\"prompt_tokens\":1000000,\"completion_tokens\":100000}}}}");
        when(restTemplate.postForObject(org.mockito.ArgumentMatchers.anyString(), any(), org.mockito.ArgumentMatchers.eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-linked-cost-2"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", new LinkedHashMap<>(Map.of("provider", "anthropic", "model", "claude-opus-4-8")));

        var result = moduleWithPricing.execute("agent_browse", params, null, null).orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> cost = (Map<String, Object>) ((Map<String, Object>) result.data()).get("cost");
        // Billed rates (15/75) → 22.5 USD, NOT the execution model's 3/15 → 4.5 USD.
        assertThat(((Number) cost.get("cost_usd")).doubleValue())
            .isCloseTo(22.5, org.assertj.core.data.Offset.offset(0.000001));
    }

    @Test
    @DisplayName("unlinked run pricing: an unrated model in by_model is still skipped with a WARN (the billed-rate fallback fires ONLY for a link's execution model)")
    void unlinkedUnratedModelStillSkipped() throws Exception {
        com.apimarketplace.common.credit.PricingSnapshotClient pricingClient =
            org.mockito.Mockito.mock(com.apimarketplace.common.credit.PricingSnapshotClient.class);
        when(pricingClient.getRates("openai", "gpt-4o-unknown")).thenReturn(Optional.empty());
        lenient().when(pricingClient.getRates("openai", "gpt-4o"))
            .thenReturn(Optional.of(new com.apimarketplace.common.credit.PricingSnapshotClient.PricingRates(
                new java.math.BigDecimal("2.50"), new java.math.BigDecimal("10.00"), java.math.BigDecimal.ZERO)));
        BrowserAgentModule moduleWithPricing = new BrowserAgentModule(
            restTemplate, config, redisTemplate, new ObjectMapper(),
            null, null, credentialResolver, null, pricingClient, agentClient);

        // The pair is NOT linked: neither the job build nor the pricing fallback finds a route.
        when(agentClient.resolveExecutionLinkApiTarget("openai", "gpt-4o")).thenReturn(Optional.empty());
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        org.springframework.data.redis.core.ListOperations<String, String> listOps =
            org.mockito.Mockito.mock(org.springframework.data.redis.core.ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        // The runner reports an UNRELATED unrated model key (internal retry/fallback).
        when(listOps.leftPop(org.mockito.ArgumentMatchers.anyString(), any(java.time.Duration.class)))
            .thenReturn("{"
                + "\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\","
                + "\"cost\":{\"tokens_in\":1000,\"tokens_out\":100,\"llm_calls\":1,\"cost_usd\":0.0,"
                + "\"by_model\":{\"gpt-4o-unknown\":{\"prompt_tokens\":1000,\"completion_tokens\":100}}}}");
        when(restTemplate.postForObject(org.mockito.ArgumentMatchers.anyString(), any(), org.mockito.ArgumentMatchers.eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-unlinked-cost"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", new LinkedHashMap<>(Map.of("provider", "openai", "model", "gpt-4o")));

        var result = moduleWithPricing.execute("agent_browse", params, null, null).orElseThrow();

        assertThat(result.success()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.data();
        @SuppressWarnings("unchecked")
        Map<String, Object> cost = (Map<String, Object>) data.get("cost");
        // No contribution priced: the unrated key must NOT be silently billed at gpt-4o rates.
        assertThat(((Number) cost.get("cost_usd")).doubleValue()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("end-to-end job build: the runner gets the EXECUTION pair + the EXECUTION provider's key, while the caller's original parameters (observability source) keep the BILLED pair")
    void buildJobParametersResolvesKeyForExecutionProviderAndKeepsBilledParams() {
        when(agentClient.resolveExecutionLinkApiTarget("anthropic", "claude-opus-4-8"))
            .thenReturn(Optional.of(new AgentClient.ExecutionLinkTarget("openrouter", "anthropic/claude-3.5-sonnet")));
        // The key lookup must target the EXECUTION provider - the whole point of the
        // link is to stop hitting the billed provider's (e.g. depleted) key.
        when(credentialResolver.resolveApiKey("user-1", "openrouter")).thenReturn(Optional.of("sk-or-key"));

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("task", "compare prices");
        parameters.put("llm", new LinkedHashMap<>(Map.of(
            "provider", "anthropic", "model", "claude-opus-4-8")));
        ToolExecutionContext context = new ToolExecutionContext(
            "user-1", Map.of(), Map.of(), java.util.Set.of(), null, null, null, null);

        Map<String, Object> jobParams = module.buildJobParameters(parameters, context);

        @SuppressWarnings("unchecked")
        Map<String, Object> runnerLlm = (Map<String, Object>) jobParams.get("llm");
        assertThat(runnerLlm)
            .containsEntry("provider", "openrouter")
            .containsEntry("model", "anthropic/claude-3.5-sonnet")
            .containsEntry("api_key", "sk-or-key");
        verify(credentialResolver, never()).resolveApiKey("user-1", "anthropic");
        // The caller's parameters are NOT mutated: recordObservabilityFromResult reads
        // them, so the observability row bills/displays the billed pair.
        @SuppressWarnings("unchecked")
        Map<String, Object> originalLlm = (Map<String, Object>) parameters.get("llm");
        assertThat(originalLlm)
            .containsEntry("provider", "anthropic")
            .containsEntry("model", "claude-opus-4-8")
            .doesNotContainKey("api_key");
    }
}
