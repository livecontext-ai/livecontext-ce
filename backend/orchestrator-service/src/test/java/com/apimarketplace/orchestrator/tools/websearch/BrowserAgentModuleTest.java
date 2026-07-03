package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentObservabilityRequest;
import com.apimarketplace.agent.resolver.LlmCredentialResolver;
import com.apimarketplace.common.credit.PricingSnapshotClient;
import com.apimarketplace.common.credit.PricingSnapshotClient.PricingRates;
import com.apimarketplace.orchestrator.config.WebSearchConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.mock.env.MockEnvironment;

import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("BrowserAgentModule")
class BrowserAgentModuleTest {

    @Mock private RestTemplate restTemplate;
    @Mock private WebSearchConfig config;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ListOperations<String, String> listOps;
    @Mock private org.springframework.data.redis.core.ValueOperations<String, String> valueOps;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SERVICE_URL = "http://websearch-host:8085";

    private BrowserAgentModule module;

    @BeforeEach
    void setUp() {
        lenient().when(config.getServiceUrl()).thenReturn(SERVICE_URL);
        module = new BrowserAgentModule(restTemplate, config, redisTemplate, objectMapper);
    }

    @Test
    @DisplayName("agent_browse from a workflow-hosted agent: meta hash records runType=workflow + "
            + "workflowRunId + hostNodeId, and workflow WINS over the agent-entity conversationId")
    @SuppressWarnings("unchecked")
    void agentBrowseWorkflowHostedMetaHash() throws Exception {
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(config.getCallbackBaseUrl()).thenReturn("http://orchestrator:8099");
        when(redisTemplate.opsForList()).thenReturn(listOps);
        org.springframework.data.redis.core.HashOperations<String, Object, Object> hashOps =
                mock(org.springframework.data.redis.core.HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn((org.springframework.data.redis.core.HashOperations) hashOps);
        when(listOps.leftPop(eq("agent:browser:result:job-wf"), any(Duration.class)))
                .thenReturn("{\"final_result\":\"done\",\"stop_reason\":\"COMPLETED\"}");
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), any(), eq(Map.class)))
                .thenReturn(Map.of("job_id", "job-wf"));

        // A generic workflow agent node: has BOTH an agent-entity conversation
        // (nobody watches it during the run) and the hosting workflow pair.
        ToolExecutionContext context = new ToolExecutionContext(
                "user-1",
                Map.of(
                        "__streamId__", "stream-1",
                        "__toolCallId__", "call-1",
                        "conversationId", "conv-agent-entity",
                        "__workflowRunId__", "wf-run-9",
                        "__workflowNodeId__", "agent_1"),
                Map.of(), java.util.Set.of(), null, null, null, null);

        module.execute("agent_browse",
                Map.of("task", "x", "llm", Map.of("provider", "openai", "model", "gpt-4o")),
                null, context);

        ArgumentCaptor<Map<Object, Object>> fieldsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(hashOps).putAll(eq("agent:browse:meta:stream-1:call-1"), fieldsCaptor.capture());
        Map<Object, Object> fields = fieldsCaptor.getValue();
        assertThat(fields)
                .containsEntry("runType", "workflow")
                .containsEntry("workflowRunId", "wf-run-9")
                .containsEntry("hostNodeId", "agent_1")
                .containsEntry("userId", "user-1")
                .doesNotContainKey("conversationId");
    }

    @Test
    @DisplayName("agent_browse from plain chat: meta hash keeps the conversationId chat routing")
    @SuppressWarnings("unchecked")
    void agentBrowseChatMetaHashUnchanged() throws Exception {
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(config.getCallbackBaseUrl()).thenReturn("http://orchestrator:8099");
        when(redisTemplate.opsForList()).thenReturn(listOps);
        org.springframework.data.redis.core.HashOperations<String, Object, Object> hashOps =
                mock(org.springframework.data.redis.core.HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn((org.springframework.data.redis.core.HashOperations) hashOps);
        when(listOps.leftPop(eq("agent:browser:result:job-chat"), any(Duration.class)))
                .thenReturn("{\"final_result\":\"done\",\"stop_reason\":\"COMPLETED\"}");
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), any(), eq(Map.class)))
                .thenReturn(Map.of("job_id", "job-chat"));

        ToolExecutionContext context = new ToolExecutionContext(
                "user-1",
                Map.of(
                        "__streamId__", "stream-1",
                        "__toolCallId__", "call-1",
                        "conversationId", "conv-chat"),
                Map.of(), java.util.Set.of(), null, null, null, null);

        module.execute("agent_browse",
                Map.of("task", "x", "llm", Map.of("provider", "openai", "model", "gpt-4o")),
                null, context);

        ArgumentCaptor<Map<Object, Object>> fieldsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(hashOps).putAll(eq("agent:browse:meta:stream-1:call-1"), fieldsCaptor.capture());
        assertThat(fieldsCaptor.getValue())
                .containsEntry("conversationId", "conv-chat")
                .doesNotContainKey("runType");
    }

    @Test
    @DisplayName("canHandle covers all five browser actions and only those")
    void canHandleSet() {
        assertThat(module.canHandle("agent_browse")).isTrue();
        assertThat(module.canHandle("browse_status")).isTrue();
        assertThat(module.canHandle("browse_intervene")).isTrue();
        assertThat(module.canHandle("browse_abort")).isTrue();
        assertThat(module.canHandle("browse_screenshot")).isTrue();

        assertThat(module.canHandle("fetch")).isFalse();
        assertThat(module.canHandle("search")).isFalse();
        assertThat(module.canHandle("unknown")).isFalse();
    }

    @Test
    @DisplayName("agent_browse: forwards task / start_url / llm / interaction_mode / domains / schema / screenshot_policy / session as job parameters")
    @SuppressWarnings("unchecked")
    void agentBrowseBuildsJobParameters() throws Exception {
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(eq("agent:browser:result:job-1"), any(Duration.class)))
            .thenReturn("{\"final_result\":\"done\",\"stop_reason\":\"COMPLETED\"}");

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), bodyCaptor.capture(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-1"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "Open the billing page");
        params.put("start_url", "https://example.com/login");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o", "max_steps", 25));
        params.put("interaction_mode", "autonomous");
        params.put("domain_allowlist", List.of("example.com"));
        params.put("domain_denylist", List.of("ads.example.com"));
        params.put("expected_output_schema", Map.of("type", "object"));
        params.put("screenshot_policy", "on_change");
        params.put("session", Map.of("headless", true, "timeout_seconds", 300));

        Optional<ToolExecutionResult> result = module.execute("agent_browse", params, null, null);

        assertThat(result).isPresent();
        assertThat(result.get().success()).isTrue();

        Map<String, Object> body = bodyCaptor.getValue();
        assertThat(body).containsEntry("action", "agent_browse");
        Map<String, Object> jobParams = (Map<String, Object>) body.get("parameters");
        assertThat(jobParams)
            .containsEntry("task", "Open the billing page")
            .containsEntry("start_url", "https://example.com/login")
            .containsEntry("interaction_mode", "autonomous")
            .containsEntry("screenshot_policy", "on_change")
            .containsKey("llm")
            .containsKey("domain_allowlist")
            .containsKey("domain_denylist")
            .containsKey("expected_output_schema")
            .containsKey("session");
    }

    @Test
    @DisplayName("agent_browse: awaitJobResult uses 'agent:result:{jobId}' key (NOT 'fetch:result:')")
    @SuppressWarnings("unchecked")
    void agentBrowseUsesAgentResultKey() throws Exception {
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(eq("agent:browser:result:job-42"), eq(Duration.ofSeconds(150))))
            .thenReturn("{\"final_result\":\"OK\",\"stop_reason\":\"COMPLETED\"}");
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-42"));

        ToolExecutionResult res = module.execute("agent_browse", Map.of("task", "x", "llm", Map.of("provider", "openai", "model", "gpt-4o")), null, null).orElseThrow();

        assertThat(res.success()).isTrue();
        Map<String, Object> data = (Map<String, Object>) res.data();
        assertThat(data).containsEntry("final_result", "OK");
        // The fetch keyspace must not be touched.
        verify(listOps).leftPop(eq("agent:browser:result:job-42"), any(Duration.class));
    }

    @Test
    @DisplayName("agent_browse: postProcess strips inline base64 screenshots, preserves screenshot_key array")
    @SuppressWarnings("unchecked")
    void agentBrowsePostProcessStripsScreenshots() throws Exception {
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"done\",\"stop_reason\":\"COMPLETED\",\"screenshots\":[\"BIGB64...\"],\"steps\":[]}");
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-2"));

        ToolExecutionResult res = module.execute("agent_browse", Map.of("task", "x", "llm", Map.of("provider", "openai", "model", "gpt-4o")), null, null).orElseThrow();

        Map<String, Object> data = (Map<String, Object>) res.data();
        assertThat(data).doesNotContainKey("screenshots");
        assertThat(data).containsEntry("final_result", "done");
    }

    @Test
    @DisplayName("agent_browse failure: failedError() uses browser-specific wording, NOT the generic 'Web fetch …' string")
    void agentBrowseFailureWording() throws Exception {
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"error\":\"runner crashed\"}");
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-3"));

        ToolExecutionResult res = module.execute("agent_browse", Map.of("task", "x", "llm", Map.of("provider", "openai", "model", "gpt-4o")), null, null).orElseThrow();

        assertThat(res.success()).isFalse();
        assertThat(res.error())
            .contains("Browser session failed: runner crashed")
            .contains("Do NOT retry immediately");
    }

    @Test
    @DisplayName("concurrencyError: tells the agent capacity is full and to wait")
    void concurrencyErrorWording() {
        // Drain the only permit so the next call hits the gate.
        try {
            java.lang.reflect.Field f = WebJobModule.class.getDeclaredField("concurrencyGate");
            f.setAccessible(true);
            ((java.util.concurrent.Semaphore) f.get(module)).acquire();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        ToolExecutionResult res = module.execute("agent_browse", Map.of("task", "x", "llm", Map.of("provider", "openai", "model", "gpt-4o")), null, null).orElseThrow();

        assertThat(res.success()).isFalse();
        assertThat(res.errorCode()).isEqualTo(ToolErrorCode.RATE_LIMITED);
        assertThat(res.error())
            .contains("Browser agent capacity full")
            .contains("at most 1");
    }

    @Test
    @DisplayName("browse_status: hits POST /agent/sessions/{id}/status, NOT the /jobs/submit pipeline")
    @SuppressWarnings("unchecked")
    void browseStatusHitsSyncEndpoint() {
        when(restTemplate.postForObject(
                eq(SERVICE_URL + "/agent/sessions/sess-7/status"),
                any(),
                eq(Map.class)))
            .thenReturn(Map.of("status", "running", "current_step", 5));

        ToolExecutionResult res = module.execute(
            "browse_status",
            Map.of("session_id", "sess-7"),
            null, null).orElseThrow();

        assertThat(res.success()).isTrue();
        Map<String, Object> data = (Map<String, Object>) res.data();
        assertThat(data)
            .containsEntry("status", "running")
            .containsEntry("current_step", 5);
    }

    @Test
    @DisplayName("browse_intervene: forwards 'hint' in the request body")
    void browseInterveneForwardsHint() {
        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(restTemplate.postForObject(
                eq(SERVICE_URL + "/agent/sessions/sess-9/intervene"),
                bodyCaptor.capture(),
                eq(Map.class)))
            .thenReturn(Map.of("ack", true));

        ToolExecutionResult res = module.execute(
            "browse_intervene",
            Map.of("session_id", "sess-9", "hint", "click the cookie banner first"),
            null, null).orElseThrow();

        assertThat(res.success()).isTrue();
        assertThat(bodyCaptor.getValue())
            .containsEntry("hint", "click the cookie banner first");
    }

    @Test
    @DisplayName("browse_status without session_id: MISSING_PARAMETER failure")
    void browseStatusMissingSessionId() {
        ToolExecutionResult res = module.execute(
            "browse_status",
            Map.of(),
            null, null).orElseThrow();

        assertThat(res.success()).isFalse();
        assertThat(res.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        assertThat(res.error()).contains("session_id is required");
    }

    @Test
    @DisplayName("browse_abort: empty body is acceptable (no hint)")
    void browseAbortEmptyBody() {
        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(restTemplate.postForObject(
                eq(SERVICE_URL + "/agent/sessions/sess-1/abort"),
                bodyCaptor.capture(),
                eq(Map.class)))
            .thenReturn(Map.of("aborted", true));

        ToolExecutionResult res = module.execute(
            "browse_abort",
            Map.of("session_id", "sess-1"),
            null, null).orElseThrow();

        assertThat(res.success()).isTrue();
        assertThat(bodyCaptor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("session-control sync call throws: failure carries verb-specific message")
    void browseScreenshotRestThrows() {
        when(restTemplate.postForObject(
                anyString(), any(), eq(Map.class)))
            .thenThrow(new RuntimeException("connection refused"));

        ToolExecutionResult res = module.execute(
            "browse_screenshot",
            Map.of("session_id", "sess-2"),
            null, null).orElseThrow();

        assertThat(res.success()).isFalse();
        assertThat(res.error())
            .contains("Browser session screenshot failed")
            .contains("connection refused");
    }

    @Test
    @DisplayName("execute: returns Optional.empty() for unhandled actions")
    void unhandledActionReturnsEmpty() {
        Optional<ToolExecutionResult> res = module.execute(
            "fetch", Map.of("url", "x"), null, null);
        assertThat(res).isEmpty();
    }

    @Test
    @DisplayName("agent_browse: resolves api_key from platform credentials and injects it into the llm block (provider=openai)")
    @SuppressWarnings("unchecked")
    void agentBrowseInjectsLlmApiKeyForDirectProvider() throws Exception {
        LlmCredentialResolver credentialResolver = org.mockito.Mockito.mock(LlmCredentialResolver.class);
        when(credentialResolver.resolveApiKey((String) null, "openai"))
            .thenReturn(Optional.of("sk-resolved-openai-key"));

        BrowserAgentModule moduleWithCreds = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper, null, null, credentialResolver);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\"}");
        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), bodyCaptor.capture(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-llm-1"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o", "max_steps", 10));

        moduleWithCreds.execute("agent_browse", params, null, null).orElseThrow();

        Map<String, Object> jobParams = (Map<String, Object>) bodyCaptor.getValue().get("parameters");
        Map<String, Object> resolvedLlm = (Map<String, Object>) jobParams.get("llm");
        assertThat(resolvedLlm).containsEntry("api_key", "sk-resolved-openai-key");
        assertThat(resolvedLlm).containsEntry("provider", "openai");
        assertThat(resolvedLlm).containsEntry("model", "gpt-4o");
        verify(credentialResolver).resolveApiKey((String) null, "openai");
    }

    @Test
    @DisplayName("agent_browse: provider_kind='bridge' skips credential lookup - bridge owns its own auth, never short-circuit billing")
    @SuppressWarnings("unchecked")
    void agentBrowseBridgeKindSkipsCredentialLookup() throws Exception {
        LlmCredentialResolver credentialResolver = org.mockito.Mockito.mock(LlmCredentialResolver.class);

        BrowserAgentModule moduleWithCreds = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper, null, null, credentialResolver);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\"}");
        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), bodyCaptor.capture(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-llm-2"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of(
            "provider", "anthropic",
            "provider_kind", "bridge",
            "model", "claude-sonnet-4-6",
            "max_steps", 10));

        moduleWithCreds.execute("agent_browse", params, null, null).orElseThrow();

        Map<String, Object> jobParams = (Map<String, Object>) bodyCaptor.getValue().get("parameters");
        Map<String, Object> resolvedLlm = (Map<String, Object>) jobParams.get("llm");
        assertThat(resolvedLlm).doesNotContainKey("api_key");
        assertThat(resolvedLlm).containsEntry("provider_kind", "bridge");
        org.mockito.Mockito.verifyNoInteractions(credentialResolver);
    }

    @Test
    @DisplayName("agent_browse: explicit api_key in the llm block is preserved, no platform lookup performed")
    @SuppressWarnings("unchecked")
    void agentBrowseExplicitApiKeyIsPreserved() throws Exception {
        LlmCredentialResolver credentialResolver = org.mockito.Mockito.mock(LlmCredentialResolver.class);

        BrowserAgentModule moduleWithCreds = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper, null, null, credentialResolver);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\"}");
        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), bodyCaptor.capture(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-llm-3"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of(
            "provider", "openai",
            "model", "gpt-4o",
            "api_key", "sk-caller-supplied",
            "max_steps", 10));

        moduleWithCreds.execute("agent_browse", params, null, null).orElseThrow();

        Map<String, Object> jobParams = (Map<String, Object>) bodyCaptor.getValue().get("parameters");
        Map<String, Object> resolvedLlm = (Map<String, Object>) jobParams.get("llm");
        assertThat(resolvedLlm).containsEntry("api_key", "sk-caller-supplied");
        org.mockito.Mockito.verifyNoInteractions(credentialResolver);
    }

    @Test
    @DisplayName("agent_browse: missing platform credential leaves the block as-is so the runner returns LLM_FAILED with the upstream provider's auth error")
    @SuppressWarnings("unchecked")
    void agentBrowseMissingCredentialForwardsAsIs() throws Exception {
        LlmCredentialResolver credentialResolver = org.mockito.Mockito.mock(LlmCredentialResolver.class);
        when(credentialResolver.resolveApiKey((String) null, "google"))
            .thenReturn(Optional.empty());

        BrowserAgentModule moduleWithCreds = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper, null, null, credentialResolver);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\"}");
        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), bodyCaptor.capture(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-llm-4"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "google", "model", "gemini-2.5-flash", "max_steps", 10));

        moduleWithCreds.execute("agent_browse", params, null, null).orElseThrow();

        Map<String, Object> jobParams = (Map<String, Object>) bodyCaptor.getValue().get("parameters");
        Map<String, Object> resolvedLlm = (Map<String, Object>) jobParams.get("llm");
        assertThat(resolvedLlm).doesNotContainKey("api_key");
        assertThat(resolvedLlm).containsEntry("provider", "google");
        verify(credentialResolver).resolveApiKey((String) null, "google");
    }

    @Test
    @DisplayName("agent_browse: HTTP exception in credentialResolver is swallowed; block forwarded without api_key (auth-service flake doesn't break submit)")
    @SuppressWarnings("unchecked")
    void agentBrowseCredentialLookupExceptionIsSwallowed() throws Exception {
        LlmCredentialResolver credentialResolver = org.mockito.Mockito.mock(LlmCredentialResolver.class);
        when(credentialResolver.resolveApiKey((String) null, "openai"))
            .thenThrow(new RuntimeException("auth-service connection refused"));

        BrowserAgentModule moduleWithCreds = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper, null, null, credentialResolver);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\"}");
        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), bodyCaptor.capture(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-llm-5"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o", "max_steps", 10));

        ToolExecutionResult res = moduleWithCreds.execute("agent_browse", params, null, null).orElseThrow();

        assertThat(res.success()).isTrue();
        Map<String, Object> jobParams = (Map<String, Object>) bodyCaptor.getValue().get("parameters");
        Map<String, Object> resolvedLlm = (Map<String, Object>) jobParams.get("llm");
        assertThat(resolvedLlm).doesNotContainKey("api_key");
        assertThat(resolvedLlm).containsEntry("provider", "openai");
    }

    @Test
    @DisplayName("agent_browse: provider_kind matching is case-insensitive - 'BRIDGE' (uppercase) also skips lookup")
    @SuppressWarnings("unchecked")
    void agentBrowseBridgeKindUppercaseSkipsLookup() throws Exception {
        LlmCredentialResolver credentialResolver = org.mockito.Mockito.mock(LlmCredentialResolver.class);

        BrowserAgentModule moduleWithCreds = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper, null, null, credentialResolver);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\"}");
        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), bodyCaptor.capture(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-llm-6"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        // Uppercase + surrounding whitespace - must still match the "bridge" branch.
        params.put("llm", Map.of(
            "provider", "anthropic",
            "provider_kind", "  BRIDGE  ",
            "model", "claude-sonnet-4-6",
            "max_steps", 10));

        moduleWithCreds.execute("agent_browse", params, null, null).orElseThrow();

        Map<String, Object> jobParams = (Map<String, Object>) bodyCaptor.getValue().get("parameters");
        Map<String, Object> resolvedLlm = (Map<String, Object>) jobParams.get("llm");
        assertThat(resolvedLlm).doesNotContainKey("api_key");
        org.mockito.Mockito.verifyNoInteractions(credentialResolver);
    }

    @Test
    @DisplayName("agent_browse: bridge with caller-supplied api_key - bridge passthrough token preserved verbatim, no platform lookup")
    @SuppressWarnings("unchecked")
    void agentBrowseBridgeKindWithExplicitApiKeyPreserved() throws Exception {
        LlmCredentialResolver credentialResolver = org.mockito.Mockito.mock(LlmCredentialResolver.class);

        BrowserAgentModule moduleWithCreds = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper, null, null, credentialResolver);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\"}");
        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), bodyCaptor.capture(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-llm-7"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of(
            "provider", "anthropic",
            "provider_kind", "bridge",
            "model", "claude-sonnet-4-6",
            "api_key", "bridge-passthrough-token",
            "max_steps", 10));

        moduleWithCreds.execute("agent_browse", params, null, null).orElseThrow();

        Map<String, Object> jobParams = (Map<String, Object>) bodyCaptor.getValue().get("parameters");
        Map<String, Object> resolvedLlm = (Map<String, Object>) jobParams.get("llm");
        assertThat(resolvedLlm).containsEntry("api_key", "bridge-passthrough-token");
        org.mockito.Mockito.verifyNoInteractions(credentialResolver);
    }

    @Test
    @DisplayName("agent_browse: Spring config fallback - DB miss falls through to ai.agent.providers.<provider>.api-key (matches AbstractLLMProvider)")
    @SuppressWarnings("unchecked")
    void agentBrowseFallsBackToSpringConfigWhenDbMisses() throws Exception {
        LlmCredentialResolver credentialResolver = org.mockito.Mockito.mock(LlmCredentialResolver.class);
        when(credentialResolver.resolveApiKey((String) null, "google"))
            .thenReturn(Optional.empty()); // DB miss

        MockEnvironment env = new MockEnvironment();
        env.setProperty("ai.agent.providers.google.api-key", "AIza-yaml-fallback-key");

        BrowserAgentModule moduleWithFallback = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper, null, null, credentialResolver, env);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\"}");
        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), bodyCaptor.capture(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-llm-fallback"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "google", "model", "gemini-1.5-flash", "max_steps", 10));

        moduleWithFallback.execute("agent_browse", params, null, null).orElseThrow();

        Map<String, Object> jobParams = (Map<String, Object>) bodyCaptor.getValue().get("parameters");
        Map<String, Object> resolvedLlm = (Map<String, Object>) jobParams.get("llm");
        assertThat(resolvedLlm).containsEntry("api_key", "AIza-yaml-fallback-key");
        verify(credentialResolver).resolveApiKey((String) null, "google");
    }

    @Test
    @DisplayName("agent_browse: Spring config empty string (YAML default ${VAR:} when env var unset) is treated as a miss, not a silent empty injection")
    @SuppressWarnings("unchecked")
    void agentBrowseSpringConfigEmptyStringIsTreatedAsMiss() throws Exception {
        LlmCredentialResolver credentialResolver = org.mockito.Mockito.mock(LlmCredentialResolver.class);
        when(credentialResolver.resolveApiKey((String) null, "openai"))
            .thenReturn(Optional.empty());

        MockEnvironment env = new MockEnvironment();
        // Mirrors what Spring resolves when the YAML default `${OPENAI_API_KEY:}`
        // collides with an unset env var: the property exists but is the empty string.
        env.setProperty("ai.agent.providers.openai.api-key", "");

        BrowserAgentModule moduleWithEmpty = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper, null, null, credentialResolver, env);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\"}");
        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), bodyCaptor.capture(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-llm-empty"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o", "max_steps", 10));

        moduleWithEmpty.execute("agent_browse", params, null, null).orElseThrow();

        Map<String, Object> jobParams = (Map<String, Object>) bodyCaptor.getValue().get("parameters");
        Map<String, Object> resolvedLlm = (Map<String, Object>) jobParams.get("llm");
        assertThat(resolvedLlm).doesNotContainKey("api_key");
    }

    @Test
    @DisplayName("agent_browse: bridge skips lookup even when a DB credential WOULD have been returned - proves billing safety, not just unwired mock")
    @SuppressWarnings("unchecked")
    void agentBrowseBridgeNeverLeaksPrimedDbCredential() throws Exception {
        LlmCredentialResolver credentialResolver = org.mockito.Mockito.mock(LlmCredentialResolver.class);
        // Prime the mock so we can prove the LOOKUP IS NOT INVOKED (rather than
        // just returning empty). If the order of checks were reversed, this
        // direct upstream key would leak into the bridge passthrough payload.
        org.mockito.Mockito.lenient()
            .when(credentialResolver.resolveApiKey((String) null, "anthropic"))
            .thenReturn(Optional.of("sk-direct-anthropic-WOULD-LEAK"));

        BrowserAgentModule moduleWithCreds = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper, null, null, credentialResolver);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\"}");
        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), bodyCaptor.capture(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-llm-bridge-primed"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of(
            "provider", "anthropic",
            "provider_kind", "bridge",
            "model", "claude-sonnet-4-6",
            "max_steps", 10));

        moduleWithCreds.execute("agent_browse", params, null, null).orElseThrow();

        Map<String, Object> jobParams = (Map<String, Object>) bodyCaptor.getValue().get("parameters");
        Map<String, Object> resolvedLlm = (Map<String, Object>) jobParams.get("llm");
        assertThat(resolvedLlm).doesNotContainKey("api_key");
        org.mockito.Mockito.verifyNoInteractions(credentialResolver);
    }

    @Test
    @DisplayName("agent_browse: DB credential takes priority over Spring config fallback when both are set")
    @SuppressWarnings("unchecked")
    void agentBrowseDbWinsOverSpringConfig() throws Exception {
        LlmCredentialResolver credentialResolver = org.mockito.Mockito.mock(LlmCredentialResolver.class);
        when(credentialResolver.resolveApiKey((String) null, "google"))
            .thenReturn(Optional.of("AIza-db-key")); // DB hit

        MockEnvironment env = new MockEnvironment();
        env.setProperty("ai.agent.providers.google.api-key", "AIza-yaml-key"); // also set

        BrowserAgentModule moduleWithBoth = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper, null, null, credentialResolver, env);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\"}");
        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), bodyCaptor.capture(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-llm-priority"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "google", "model", "gemini-1.5-flash", "max_steps", 10));

        moduleWithBoth.execute("agent_browse", params, null, null).orElseThrow();

        Map<String, Object> jobParams = (Map<String, Object>) bodyCaptor.getValue().get("parameters");
        Map<String, Object> resolvedLlm = (Map<String, Object>) jobParams.get("llm");
        assertThat(resolvedLlm).containsEntry("api_key", "AIza-db-key");
    }

    @Test
    @DisplayName("agent_browse: non-Map llm value is forwarded verbatim so the runner returns a precise LlmConfigError")
    @SuppressWarnings("unchecked")
    void agentBrowseNonMapLlmIsForwardedVerbatim() throws Exception {
        LlmCredentialResolver credentialResolver = org.mockito.Mockito.mock(LlmCredentialResolver.class);

        BrowserAgentModule moduleWithCreds = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper, null, null, credentialResolver);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\"}");
        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), bodyCaptor.capture(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-llm-8"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        // Agent malformed the block as a String. We forward verbatim so the
        // runner surfaces the precise error instead of swallowing it.
        params.put("llm", "not-a-map");

        moduleWithCreds.execute("agent_browse", params, null, null).orElseThrow();

        Map<String, Object> jobParams = (Map<String, Object>) bodyCaptor.getValue().get("parameters");
        assertThat(jobParams).containsEntry("llm", "not-a-map");
        org.mockito.Mockito.verifyNoInteractions(credentialResolver);
    }

    @Test
    @DisplayName("agent_browse: workflow path threads context.tenantId() into resolveApiKey(userId, provider) - workflow-thread user-cred resolution works (closes 2026-05-28 audit C2)")
    @SuppressWarnings("unchecked")
    void agentBrowseThreadsUserIdFromContextOnWorkflowThread() throws Exception {
        // Regression: pre-fix, the workflow-execution thread had no servlet
        // request bound, so TenantResolver.currentRequestUserId() returned null
        // and the user-cred lookup was silently skipped. The fix threads
        // context.tenantId() (populated by BrowserAgentNode from the workflow
        // run) into resolveApiKey(userId, provider) explicitly so the user's
        // saved llm_<provider> credential is honored on the workflow path
        // (the primary BrowserAgent caller).
        LlmCredentialResolver credentialResolver =
            org.mockito.Mockito.mock(LlmCredentialResolver.class);
        when(credentialResolver.resolveApiKey("workflow-user-77", "openai"))
            .thenReturn(Optional.of("sk-workflow-user-key"));

        BrowserAgentModule moduleWithCreds = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper, null, null, credentialResolver);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\"}");
        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), bodyCaptor.capture(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-workflow-user"));

        // Simulate the workflow path: BrowserAgentNode constructs a
        // ToolExecutionContext whose tenantId() = current workflow's owner.
        ToolExecutionContext workflowCtx = new ToolExecutionContext(
            "workflow-user-77",  // tenantId - populated by BrowserAgentNode from workflow run
            Map.of(),
            Map.of(), java.util.Set.of(),
            null, null, null, null);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o", "max_steps", 10));

        moduleWithCreds.execute("agent_browse", params, "workflow-user-77", workflowCtx).orElseThrow();

        Map<String, Object> jobParams = (Map<String, Object>) bodyCaptor.getValue().get("parameters");
        Map<String, Object> resolvedLlm = (Map<String, Object>) jobParams.get("llm");
        assertThat(resolvedLlm).containsEntry("api_key", "sk-workflow-user-key");
        // The user-aware 2-arg overload MUST be the one called - not the
        // single-arg (which would read from RequestContextHolder, empty on
        // workflow threads).
        verify(credentialResolver).resolveApiKey("workflow-user-77", "openai");
    }

    // ── Pricing / cost_usd tests ───────────────────────────────────────

    /**
     * Build a job-result payload mimicking what the Python runner emits:
     * the placeholder {@code cost_usd=0.0} the Java side must overwrite,
     * plus a populated {@code by_model} block.
     */
    private static String resultWithCost(String model, long promptTokens, long completionTokens) {
        return "{"
            + "\"final_result\":\"ok\","
            + "\"stop_reason\":\"COMPLETED\","
            + "\"cost\":{"
            + "  \"tokens_in\":" + promptTokens + ","
            + "  \"tokens_out\":" + completionTokens + ","
            + "  \"llm_calls\":1,"
            + "  \"browser_seconds\":1.0,"
            + "  \"cost_usd\":0.0,"
            + "  \"by_model\":{\"" + model + "\":{"
            + "      \"prompt_tokens\":" + promptTokens + ","
            + "      \"completion_tokens\":" + completionTokens + ","
            + "      \"cache_read_tokens\":0,"
            + "      \"cache_creation_tokens\":0,"
            + "      \"image_input_tokens\":0,"
            + "      \"invocations\":1}}"
            + "}}";
    }

    @Test
    @DisplayName("postProcess: cost_usd computed from by_model + pricing snapshot rates (openai/gpt-4o-mini)")
    @SuppressWarnings("unchecked")
    void postProcessComputesCostUsdForOpenai() throws Exception {
        PricingSnapshotClient pricingClient = org.mockito.Mockito.mock(PricingSnapshotClient.class);
        when(pricingClient.getRates("openai", "gpt-4o-mini"))
            .thenReturn(java.util.Optional.of(new PricingRates(
                new BigDecimal("0.15"),  // input USD per 1M
                new BigDecimal("0.60"),  // output USD per 1M
                BigDecimal.ZERO)));

        BrowserAgentModule moduleWithPricing = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, pricingClient);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn(resultWithCost("gpt-4o-mini", 210586L, 510L));
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-cost-1"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o-mini", "max_steps", 10));

        ToolExecutionResult res = moduleWithPricing.execute("agent_browse", params, null, null).orElseThrow();

        assertThat(res.success()).isTrue();
        Map<String, Object> data = (Map<String, Object>) res.data();
        Map<String, Object> cost = (Map<String, Object>) data.get("cost");
        // Expected: (210586 × 0.15 + 510 × 0.60) / 1_000_000
        //         = (31587.9 + 306) / 1_000_000
        //         = 0.0318939
        double computed = ((Number) cost.get("cost_usd")).doubleValue();
        assertThat(computed).isCloseTo(0.031894, org.assertj.core.data.Offset.offset(0.000001));
    }

    @Test
    @DisplayName("postProcess: cost_usd computed for google/gemini-2.5-flash with realistic token counts")
    @SuppressWarnings("unchecked")
    void postProcessComputesCostUsdForGoogle() throws Exception {
        PricingSnapshotClient pricingClient = org.mockito.Mockito.mock(PricingSnapshotClient.class);
        when(pricingClient.getRates("google", "gemini-2.5-flash"))
            .thenReturn(java.util.Optional.of(new PricingRates(
                new BigDecimal("0.30"),
                new BigDecimal("2.50"),
                BigDecimal.ZERO)));

        BrowserAgentModule moduleWithPricing = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, pricingClient);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn(resultWithCost("gemini-2.5-flash", 51118L, 2035L));
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-cost-2"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "google", "model", "gemini-2.5-flash", "max_steps", 10));

        ToolExecutionResult res = moduleWithPricing.execute("agent_browse", params, null, null).orElseThrow();

        Map<String, Object> data = (Map<String, Object>) res.data();
        Map<String, Object> cost = (Map<String, Object>) data.get("cost");
        // (51118 × 0.30 + 2035 × 2.50) / 1_000_000 = (15335.4 + 5087.5) / 1_000_000 = 0.020423
        double computed = ((Number) cost.get("cost_usd")).doubleValue();
        assertThat(computed).isCloseTo(0.020423, org.assertj.core.data.Offset.offset(0.000001));
    }

    @Test
    @DisplayName("postProcess: bridge model with rate=0 in snapshot keeps cost_usd=0 (Bridges - never short-circuit billing)")
    @SuppressWarnings("unchecked")
    void postProcessBridgeRateZeroKeepsCostZero() throws Exception {
        PricingSnapshotClient pricingClient = org.mockito.Mockito.mock(PricingSnapshotClient.class);
        // V128 bridge rows: input_rate=0, output_rate=0 - billing happens via
        // internal credits, not via the cost block.
        when(pricingClient.getRates("claude-code", "claude-sonnet-4-6"))
            .thenReturn(java.util.Optional.of(new PricingRates(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));

        BrowserAgentModule moduleWithPricing = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, pricingClient);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn(resultWithCost("claude-sonnet-4-6", 100000L, 5000L));
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-cost-bridge"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of(
            "provider", "claude-code",
            "provider_kind", "bridge",
            "model", "claude-sonnet-4-6",
            "max_steps", 5));

        ToolExecutionResult res = moduleWithPricing.execute("agent_browse", params, null, null).orElseThrow();

        Map<String, Object> data = (Map<String, Object>) res.data();
        Map<String, Object> cost = (Map<String, Object>) data.get("cost");
        assertThat(((Number) cost.get("cost_usd")).doubleValue()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("postProcess: model not in pricing snapshot (snapshot unhealthy) → cost_usd left as runner default (0), warn logged, no NPE")
    @SuppressWarnings("unchecked")
    void postProcessUnknownModelLeavesCostUntouched() throws Exception {
        PricingSnapshotClient pricingClient = org.mockito.Mockito.mock(PricingSnapshotClient.class);
        when(pricingClient.getRates(anyString(), anyString()))
            .thenReturn(java.util.Optional.empty());
        // Explicit: snapshot is unhealthy so the pre-flight validation
        // fails open (allow). Without this stub the test would still pass
        // (Mockito's default boolean is false), but stubbing makes the
        // contract intent visible.
        when(pricingClient.isHealthy()).thenReturn(false);

        BrowserAgentModule moduleWithPricing = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, pricingClient);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn(resultWithCost("gemini-99-future-model", 1000L, 100L));
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-cost-unknown"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "google", "model", "gemini-99-future-model", "max_steps", 5));

        ToolExecutionResult res = moduleWithPricing.execute("agent_browse", params, null, null).orElseThrow();
        assertThat(res.success()).isTrue();

        Map<String, Object> data = (Map<String, Object>) res.data();
        Map<String, Object> cost = (Map<String, Object>) data.get("cost");
        assertThat(((Number) cost.get("cost_usd")).doubleValue()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("postProcess: PricingSnapshotClient null (test path) → no NPE, cost_usd remains 0")
    @SuppressWarnings("unchecked")
    void postProcessNullPricingClientIsNoOp() throws Exception {
        // Default module setup uses 4-arg ctor - pricingSnapshotClient is null.
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn(resultWithCost("gpt-4o", 1000L, 100L));
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-cost-null"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o", "max_steps", 5));

        ToolExecutionResult res = module.execute("agent_browse", params, null, null).orElseThrow();
        assertThat(res.success()).isTrue();
        Map<String, Object> data = (Map<String, Object>) res.data();
        Map<String, Object> cost = (Map<String, Object>) data.get("cost");
        assertThat(((Number) cost.get("cost_usd")).doubleValue()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("postProcess: missing provider in llm block → cost_usd not computed (provider is the lookup key)")
    @SuppressWarnings("unchecked")
    void postProcessMissingProviderSkipsPricing() throws Exception {
        PricingSnapshotClient pricingClient = org.mockito.Mockito.mock(PricingSnapshotClient.class);

        BrowserAgentModule moduleWithPricing = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, pricingClient);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn(resultWithCost("gpt-4o", 1000L, 100L));
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-cost-no-provider"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        // llm block missing 'provider' key entirely
        params.put("llm", Map.of("model", "gpt-4o", "max_steps", 5));

        moduleWithPricing.execute("agent_browse", params, null, null).orElseThrow();

        // Verify pricing lookup never happens when provider is absent.
        org.mockito.Mockito.verifyNoInteractions(pricingClient);
    }

    @Test
    @DisplayName("postProcess: multi-model by_model - cost_usd sums across each model's contribution (one hit + one snapshot miss)")
    @SuppressWarnings("unchecked")
    void postProcessMultiModelSumsAcrossEntries() throws Exception {
        PricingSnapshotClient pricingClient = org.mockito.Mockito.mock(PricingSnapshotClient.class);
        // gpt-4o has rates; gpt-4o-fallback misses the snapshot - the loop
        // must accumulate the first contribution and skip the second without
        // short-circuiting.
        when(pricingClient.getRates("openai", "gpt-4o"))
            .thenReturn(java.util.Optional.of(new PricingRates(
                new BigDecimal("2.50"),  // $2.50 / 1M input
                new BigDecimal("10.00"), // $10.00 / 1M output
                BigDecimal.ZERO)));
        when(pricingClient.getRates("openai", "gpt-4o-retry"))
            .thenReturn(java.util.Optional.empty()); // future model, no rate yet

        BrowserAgentModule moduleWithPricing = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, pricingClient);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        // Two-entry by_model - primary model + retry model in same session.
        String result = "{\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\","
            + "\"cost\":{\"tokens_in\":11000,\"tokens_out\":2000,\"llm_calls\":2,"
            + "\"browser_seconds\":1.0,\"cost_usd\":0.0,\"by_model\":{"
            + "  \"gpt-4o\":         {\"prompt_tokens\":10000,\"completion_tokens\":1500,\"invocations\":1},"
            + "  \"gpt-4o-retry\":   {\"prompt_tokens\":1000, \"completion_tokens\":500, \"invocations\":1}"
            + "}}}";
        when(listOps.leftPop(anyString(), any(Duration.class))).thenReturn(result);
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-multi"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o", "max_steps", 5));

        ToolExecutionResult res = moduleWithPricing.execute("agent_browse", params, null, null).orElseThrow();
        Map<String, Object> data = (Map<String, Object>) res.data();
        Map<String, Object> cost = (Map<String, Object>) data.get("cost");
        // Only gpt-4o contributes: (10000 × 2.50 + 1500 × 10.00) / 1M
        //                       = (25000 + 15000) / 1M = 0.040000
        // gpt-4o-retry contributes 0 (snapshot miss → skipped, NOT short-circuit).
        double computed = ((Number) cost.get("cost_usd")).doubleValue();
        assertThat(computed).isCloseTo(0.040000, org.assertj.core.data.Offset.offset(0.000001));
        // Both models were looked up - proves loop did NOT short-circuit on miss.
        // Note: gpt-4o is queried twice - once by the pre-flight model
        // validation in execute(), once by postProcess() - so we use
        // atLeastOnce(). gpt-4o-retry only appears in postProcess (the
        // pre-flight only checks the configured llm.model = "gpt-4o").
        verify(pricingClient, org.mockito.Mockito.atLeastOnce()).getRates("openai", "gpt-4o");
        verify(pricingClient).getRates("openai", "gpt-4o-retry");
    }

    @Test
    @DisplayName("postProcess: fixedCost > 0 in pricing rates is added per-call (matches ModelCostCalculator formula)")
    @SuppressWarnings("unchecked")
    void postProcessFixedCostIncludedInTotal() throws Exception {
        PricingSnapshotClient pricingClient = org.mockito.Mockito.mock(PricingSnapshotClient.class);
        // Hypothetical row with non-zero fixedCost (e.g. $0.001 per call flat
        // fee - no current row uses this but the formula must support it).
        when(pricingClient.getRates("openai", "gpt-4o"))
            .thenReturn(java.util.Optional.of(new PricingRates(
                new BigDecimal("2.50"),
                new BigDecimal("10.00"),
                new BigDecimal("0.001"))));

        BrowserAgentModule moduleWithPricing = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, pricingClient);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn(resultWithCost("gpt-4o", 10000L, 1500L));
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-fixed"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o", "max_steps", 5));

        ToolExecutionResult res = moduleWithPricing.execute("agent_browse", params, null, null).orElseThrow();
        Map<String, Object> data = (Map<String, Object>) res.data();
        Map<String, Object> cost = (Map<String, Object>) data.get("cost");
        // (10000 × 2.50 + 1500 × 10.00) / 1M + 0.001 = 0.040 + 0.001 = 0.041
        double computed = ((Number) cost.get("cost_usd")).doubleValue();
        assertThat(computed).isCloseTo(0.041000, org.assertj.core.data.Offset.offset(0.000001));
    }

    @Test
    @DisplayName("postProcess: pricing client throwing during getRates → result still returned, runner cost_usd=0 preserved (defense in depth)")
    @SuppressWarnings("unchecked")
    void postProcessPricingExceptionFallsBackGracefully() throws Exception {
        PricingSnapshotClient pricingClient = org.mockito.Mockito.mock(PricingSnapshotClient.class);
        when(pricingClient.getRates(anyString(), anyString()))
            .thenThrow(new RuntimeException("snapshot service unreachable"));

        BrowserAgentModule moduleWithPricing = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, pricingClient);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn(resultWithCost("gpt-4o", 1000L, 100L));
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-pricing-throws"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o", "max_steps", 5));

        // Result must still come through - a pricing failure must never
        // swallow the user's task output.
        ToolExecutionResult res = moduleWithPricing.execute("agent_browse", params, null, null).orElseThrow();
        assertThat(res.success()).isTrue();
        Map<String, Object> data = (Map<String, Object>) res.data();
        Map<String, Object> cost = (Map<String, Object>) data.get("cost");
        assertThat(((Number) cost.get("cost_usd")).doubleValue()).isEqualTo(0.0);
    }

    // ── Observability tests (F3) ──────────────────────────────────────

    @Test
    @DisplayName("postProcess: posts AgentObservabilityRequest to agent-service when context.tenantId is set (chat tool path)")
    @SuppressWarnings("unchecked")
    void postProcessRecordsObservabilityForChatTool() throws Exception {
        AgentClient agentClient = org.mockito.Mockito.mock(AgentClient.class);

        BrowserAgentModule moduleWithObs = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, null, agentClient);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn(resultWithCost("gpt-4o", 5000L, 250L));
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-obs-1"));

        ToolExecutionContext ctx = new ToolExecutionContext(
            "tenant-abc",
            Map.of(),
            Map.of(), java.util.Set.of(),
            null, null, null, null
        );

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o", "max_steps", 5));

        moduleWithObs.execute("agent_browse", params, "tenant-abc", ctx).orElseThrow();

        ArgumentCaptor<AgentObservabilityRequest> reqCaptor =
            ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient).recordObservability(reqCaptor.capture());
        AgentObservabilityRequest req = reqCaptor.getValue();
        assertThat(req.getTenantId()).isEqualTo("tenant-abc");
        assertThat(req.getAgentType()).isEqualTo("browser_agent");
        assertThat(req.getProvider()).isEqualTo("openai");
        assertThat(req.getModel()).isEqualTo("gpt-4o");
        assertThat(req.getStatus()).isEqualTo("COMPLETED");
        assertThat(req.getStopReason()).isEqualTo("COMPLETED");
        assertThat(req.getPromptTokens()).isEqualTo(5000L);
        assertThat(req.getCompletionTokens()).isEqualTo(250L);
        assertThat(req.getTotalTokens()).isEqualTo(5250L);
        assertThat(req.getSource()).isEqualTo("chat_tool");
    }

    @Test
    @DisplayName("postProcess: agentClient null (test path) → no NPE, no observability call")
    @SuppressWarnings("unchecked")
    void postProcessNullAgentClientIsNoOp() throws Exception {
        // Default 4-arg ctor leaves agentClient null.
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn(resultWithCost("gpt-4o", 1000L, 100L));
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-obs-null"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o", "max_steps", 5));

        ToolExecutionResult res = module.execute("agent_browse", params, null, null).orElseThrow();
        assertThat(res.success()).isTrue();
        // No mock to verify; the test passes if no NPE occurred.
    }

    @Test
    @DisplayName("postProcess: missing context.tenantId → observability skipped (no anonymous billing)")
    @SuppressWarnings("unchecked")
    void postProcessMissingTenantSkipsObservability() throws Exception {
        AgentClient agentClient = org.mockito.Mockito.mock(AgentClient.class);

        BrowserAgentModule moduleWithObs = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, null, agentClient);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn(resultWithCost("gpt-4o", 1000L, 100L));
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-obs-no-tenant"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o", "max_steps", 5));

        // No ToolExecutionContext (null) - auth gateway hasn't populated tenant.
        moduleWithObs.execute("agent_browse", params, null, null).orElseThrow();

        // Narrowed from verifyNoInteractions: agent_browse now also calls
        // agentClient.getModelsInfo("browser_agent") for the llm default-fallback resolution
        // (mirrors the agent-create path). The observability-skip semantics
        // covered by THIS test are unrelated to catalog lookup.
        verify(agentClient, never()).recordObservability(any());
    }

    @Test
    @DisplayName("postProcess: failed run (stop_reason=MAX_STEPS) → status=FAILED, stopReason=MAX_ITERATIONS canonical mapping")
    @SuppressWarnings("unchecked")
    void postProcessFailedRunMapsStopReason() throws Exception {
        AgentClient agentClient = org.mockito.Mockito.mock(AgentClient.class);

        BrowserAgentModule moduleWithObs = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, null, agentClient);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        // Failed result with MAX_STEPS stop reason and zero cost.
        String failedResult = "{\"final_result\":\"reached max_steps=50\","
            + "\"stop_reason\":\"MAX_STEPS\","
            + "\"cost\":{\"tokens_in\":0,\"tokens_out\":0,\"llm_calls\":0,"
            + "\"browser_seconds\":3.0,\"cost_usd\":0.0,\"by_model\":{}}}";
        when(listOps.leftPop(anyString(), any(Duration.class))).thenReturn(failedResult);
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-obs-fail"));

        ToolExecutionContext ctx = new ToolExecutionContext(
            "tenant-fail",
            Map.of(),
            Map.of(), java.util.Set.of(),
            null, null, null, null
        );

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "google", "model", "gemini-2.5-flash", "max_steps", 5));

        // The submit pipeline maps stop_reason MAX_STEPS to a failure result;
        // the observability call must still fire with mapped values.
        moduleWithObs.execute("agent_browse", params, "tenant-fail", ctx).orElseThrow();

        ArgumentCaptor<AgentObservabilityRequest> reqCaptor =
            ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient).recordObservability(reqCaptor.capture());
        AgentObservabilityRequest req = reqCaptor.getValue();
        assertThat(req.getStatus()).isEqualTo("FAILED");
        assertThat(req.getStopReason()).isEqualTo("MAX_ITERATIONS");
    }

    @Test
    @DisplayName("postProcess: __skipObservability__=true in credentials → no observability call (mutual exclusion with BrowserAgentNode)")
    @SuppressWarnings("unchecked")
    void postProcessSkipsObservabilityWhenFlagSet() throws Exception {
        AgentClient agentClient = org.mockito.Mockito.mock(AgentClient.class);

        BrowserAgentModule moduleWithObs = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, null, agentClient);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn(resultWithCost("gpt-4o", 5000L, 250L));
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-skip-obs"));

        // BrowserAgentNode populates __skipObservability__=true so its own
        // recordObservability() call (with richer context) is the sole writer.
        // Without this flag the workflow path would double-bill.
        // (We omit __streamId__/__toolCallId__ here to keep the test focused
        // on the observability-skip semantic - the callback URL builder is
        // exercised by `agentBrowseAttachesCallbackUrl` separately.)
        ToolExecutionContext ctx = new ToolExecutionContext(
            "tenant-workflow",
            Map.of("__skipObservability__", "true"),
            Map.of(), java.util.Set.of(),
            null, null, null, null
        );

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o", "max_steps", 5));

        moduleWithObs.execute("agent_browse", params, "tenant-workflow", ctx).orElseThrow();

        // Narrowed from verifyNoInteractions: see postProcessMissingTenantSkipsObservability.
        verify(agentClient, never()).recordObservability(any());
    }

    @Test
    @DisplayName("postProcess: cache + duration + iteration + tool call mappings are populated correctly")
    @SuppressWarnings("unchecked")
    void postProcessPopulatesCacheDurationAndIterationFields() throws Exception {
        AgentClient agentClient = org.mockito.Mockito.mock(AgentClient.class);

        BrowserAgentModule moduleWithObs = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, null, agentClient);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        // Realistic browser-agent result: 4 steps, 7 LLM calls, cache hits,
        // 12.5s wall-clock. Pins every field the chat-tool observability
        // record must carry.
        String richResult = "{"
            + "\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\","
            + "\"steps\":[{\"step_index\":1},{\"step_index\":2},{\"step_index\":3},{\"step_index\":4}],"
            + "\"cost\":{"
            + "  \"tokens_in\":50000,\"tokens_out\":1500,"
            + "  \"cache_read_tokens\":12000,"
            + "  \"cache_creation_tokens\":3000,"
            + "  \"image_input_tokens\":2048,"
            + "  \"llm_calls\":7,"
            + "  \"browser_seconds\":12.5,"
            + "  \"cost_usd\":0.0,"
            + "  \"by_model\":{\"gpt-4o\":{\"prompt_tokens\":50000,\"completion_tokens\":1500,\"invocations\":7}}"
            + "}}";
        when(listOps.leftPop(anyString(), any(Duration.class))).thenReturn(richResult);
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-rich"));

        ToolExecutionContext ctx = new ToolExecutionContext(
            "tenant-rich",
            Map.of(),
            Map.of(), java.util.Set.of(),
            null, null, null, null
        );

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o", "max_steps", 10));

        moduleWithObs.execute("agent_browse", params, "tenant-rich", ctx).orElseThrow();

        ArgumentCaptor<AgentObservabilityRequest> reqCaptor =
            ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient).recordObservability(reqCaptor.capture());
        AgentObservabilityRequest req = reqCaptor.getValue();
        assertThat(req.getCacheReadTokens()).isEqualTo(12000L);
        assertThat(req.getCacheCreationTokens()).isEqualTo(3000L);
        assertThat(req.getDurationMs()).isEqualTo(12500L); // browser_seconds × 1000
        assertThat(req.getIterationCount()).isEqualTo(4);   // 4 steps
        assertThat(req.getTotalToolCalls()).isEqualTo(7);   // llm_calls
    }

    @Test
    @DisplayName("postProcess: builds conversation timeline (USER + ASSISTANT × N + final ASSISTANT) so Agent Performance side panel can replay the run")
    @SuppressWarnings("unchecked")
    void postProcessBuildsConversationTimeline() throws Exception {
        AgentClient agentClient = org.mockito.Mockito.mock(AgentClient.class);

        BrowserAgentModule moduleWithObs = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, null, agentClient);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        // Realistic browser-agent result with extracted_data as the agent
        // narrates progress, then a final_result distinct from the last step.
        String result = "{"
            + "\"final_result\":\"The H1 text is 'Example Domain'.\","
            + "\"stop_reason\":\"COMPLETED\","
            + "\"extracted_data\":["
            + "  \"\\u200d Navigated to https://example.com\","
            + "  \"<url>https://example.com</url><result>Example Domain</result>\""
            + "],"
            + "\"cost\":{\"tokens_in\":100,\"tokens_out\":20,\"llm_calls\":2,"
            + "\"browser_seconds\":1.0,\"cost_usd\":0.0,\"by_model\":{}}}";
        when(listOps.leftPop(anyString(), any(Duration.class))).thenReturn(result);
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-conv"));

        ToolExecutionContext ctx = new ToolExecutionContext(
            "tenant-conv", Map.of(), Map.of(), java.util.Set.of(),
            null, null, null, null);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "Open example.com and read the H1");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o", "max_steps", 5));

        moduleWithObs.execute("agent_browse", params, "tenant-conv", ctx).orElseThrow();

        ArgumentCaptor<AgentObservabilityRequest> reqCaptor =
            ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient).recordObservability(reqCaptor.capture());
        java.util.List<AgentObservabilityRequest.MessageData> msgs =
            reqCaptor.getValue().getMessages();
        // Expect 4 messages: 1 USER + 2 ASSISTANT (extracted_data) + 1 ASSISTANT (final_result).
        assertThat(msgs).hasSize(4);
        assertThat(msgs.get(0).getRole()).isEqualTo("USER");
        assertThat(msgs.get(0).getContent()).isEqualTo("Open example.com and read the H1");
        assertThat(msgs.get(1).getRole()).isEqualTo("ASSISTANT");
        assertThat(msgs.get(1).getContent()).contains("Navigated to https://example.com");
        assertThat(msgs.get(2).getRole()).isEqualTo("ASSISTANT");
        assertThat(msgs.get(2).getContent()).contains("Example Domain");
        assertThat(msgs.get(3).getRole()).isEqualTo("ASSISTANT");
        assertThat(msgs.get(3).getContent()).isEqualTo("The H1 text is 'Example Domain'.");
    }

    @Test
    @DisplayName("postProcess: final_result identical to last extracted_data entry is NOT duplicated in the timeline")
    @SuppressWarnings("unchecked")
    void postProcessConversationDeduplicatesFinalResult() throws Exception {
        AgentClient agentClient = org.mockito.Mockito.mock(AgentClient.class);

        BrowserAgentModule moduleWithObs = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, null, agentClient);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        // browser-use sometimes echoes the last extracted_data entry into final_result.
        String result = "{"
            + "\"final_result\":\"Example Domain\","
            + "\"stop_reason\":\"COMPLETED\","
            + "\"extracted_data\":[\"Example Domain\"],"
            + "\"cost\":{\"tokens_in\":50,\"tokens_out\":10,\"llm_calls\":1,"
            + "\"browser_seconds\":1.0,\"cost_usd\":0.0,\"by_model\":{}}}";
        when(listOps.leftPop(anyString(), any(Duration.class))).thenReturn(result);
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-dedup"));

        ToolExecutionContext ctx = new ToolExecutionContext(
            "tenant-dedup", Map.of(), Map.of(), java.util.Set.of(),
            null, null, null, null);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "Read H1");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o", "max_steps", 5));

        moduleWithObs.execute("agent_browse", params, "tenant-dedup", ctx).orElseThrow();

        ArgumentCaptor<AgentObservabilityRequest> reqCaptor =
            ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient).recordObservability(reqCaptor.capture());
        // 2 messages only: USER + 1 ASSISTANT - the final_result was identical
        // to the last extracted_data entry so no duplicate.
        assertThat(reqCaptor.getValue().getMessages()).hasSize(2);
    }

    // ── Direct unit tests on buildConversationMessages ─────────────────

    @Test
    @DisplayName("buildConversationMessages: empty extracted_data → only USER + final ASSISTANT (2 messages)")
    void buildConvEmptyExtractedData() {
        Map<String, Object> params = Map.of("task", "Read H1");
        Map<String, Object> response = Map.of(
            "final_result", "Example Domain",
            "extracted_data", java.util.List.of()
        );
        java.util.List<AgentObservabilityRequest.MessageData> msgs =
            BrowserAgentModule.buildConversationMessages(params, response);
        assertThat(msgs).hasSize(2);
        assertThat(msgs.get(0).getRole()).isEqualTo("USER");
        assertThat(msgs.get(0).getContent()).isEqualTo("Read H1");
        assertThat(msgs.get(1).getRole()).isEqualTo("ASSISTANT");
        assertThat(msgs.get(1).getContent()).isEqualTo("Example Domain");
    }

    @Test
    @DisplayName("buildConversationMessages: missing task (null) → only ASSISTANT messages, no USER prefix")
    void buildConvMissingTask() {
        Map<String, Object> params = Map.of(); // no task
        Map<String, Object> response = Map.of(
            "final_result", "Done",
            "extracted_data", java.util.List.of("step 1")
        );
        java.util.List<AgentObservabilityRequest.MessageData> msgs =
            BrowserAgentModule.buildConversationMessages(params, response);
        assertThat(msgs).hasSize(2);
        assertThat(msgs).noneMatch(m -> "USER".equals(m.getRole()));
        assertThat(msgs.get(0).getRole()).isEqualTo("ASSISTANT");
        assertThat(msgs.get(0).getContent()).isEqualTo("step 1");
        assertThat(msgs.get(1).getContent()).isEqualTo("Done");
    }

    @Test
    @DisplayName("buildConversationMessages: null response → empty list (defensive guard)")
    void buildConvNullResponse() {
        Map<String, Object> params = Map.of("task", "Anything");
        java.util.List<AgentObservabilityRequest.MessageData> msgs =
            BrowserAgentModule.buildConversationMessages(params, null);
        // task still produces USER, but no ASSISTANT side at all (no response).
        assertThat(msgs).hasSize(1);
        assertThat(msgs.get(0).getRole()).isEqualTo("USER");
    }

    @Test
    @DisplayName("buildConversationMessages: blank entries inside extracted_data are skipped (no empty ASSISTANT messages)")
    void buildConvSkipsBlankEntries() {
        Map<String, Object> params = Map.of("task", "Read");
        Map<String, Object> response = Map.of(
            "final_result", "OK",
            "extracted_data", java.util.Arrays.asList("step 1", "", "   ", null, "step 2")
        );
        java.util.List<AgentObservabilityRequest.MessageData> msgs =
            BrowserAgentModule.buildConversationMessages(params, response);
        // 1 USER + 2 valid ASSISTANT (step 1, step 2) + 1 ASSISTANT final = 4
        // (the empty string, whitespace-only, and null entries are dropped).
        assertThat(msgs).hasSize(4);
        assertThat(msgs.get(0).getRole()).isEqualTo("USER");
        assertThat(msgs.get(1).getContent()).isEqualTo("step 1");
        assertThat(msgs.get(2).getContent()).isEqualTo("step 2");
        assertThat(msgs.get(3).getContent()).isEqualTo("OK");
    }

    @Test
    @DisplayName("buildConversationMessages: monotonic sequence numbers + null params is no-op safe")
    void buildConvMonotonicSequenceAndNullParams() {
        Map<String, Object> response = Map.of(
            "final_result", "Done",
            "extracted_data", java.util.List.of("a", "b", "c")
        );
        java.util.List<AgentObservabilityRequest.MessageData> msgs =
            BrowserAgentModule.buildConversationMessages(null, response);
        // No NPE; no USER prefix (params null); 3 ASSISTANT + 1 final = 4
        assertThat(msgs).hasSize(4);
        // Sequence numbers must be strictly monotonic [0, N-1].
        for (int i = 0; i < msgs.size(); i++) {
            assertThat(msgs.get(i).getSequenceNumber()).isEqualTo(i);
        }
    }

    @Test
    @DisplayName("awaitJobResult: stop_reason != COMPLETED injects 'error' into payload → tool result is FAILURE (node ends FAILED, not COMPLETED)")
    @SuppressWarnings("unchecked")
    void awaitJobResultMapsStopReasonToFailure() throws Exception {
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        // Runner emitted LLM_FAILED - no top-level "error" key - but the
        // base WebJobModule was previously routing this to success(). Our
        // BrowserAgentModule.awaitJobResult override must inject the error
        // so the workflow node ends FAILED and not COMPLETED.
        String failedPayload = "{"
            + "\"stop_reason\":\"LLM_FAILED\","
            + "\"final_result\":\"llm config must be a dict, got str\","
            + "\"cost\":{\"tokens_in\":0,\"tokens_out\":0,\"llm_calls\":0,"
            + "\"browser_seconds\":0.03,\"cost_usd\":0.0,\"by_model\":{}}}";
        when(listOps.leftPop(anyString(), any(Duration.class))).thenReturn(failedPayload);
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-llmfail"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o", "max_steps", 5));

        ToolExecutionResult res = module.execute("agent_browse", params, null, null).orElseThrow();

        assertThat(res.success()).isFalse();
        assertThat(res.error())
            .as("failure message should preserve the runner's final_result diagnostic")
            .contains("llm config must be a dict, got str");
    }

    @Test
    @DisplayName("awaitJobResult: stop_reason=COMPLETED does NOT inject error (happy path stays success)")
    @SuppressWarnings("unchecked")
    void awaitJobResultCompletedStaysSuccess() throws Exception {
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"done\",\"stop_reason\":\"COMPLETED\","
                + "\"cost\":{\"tokens_in\":0,\"tokens_out\":0,\"llm_calls\":0,"
                + "\"browser_seconds\":1.0,\"cost_usd\":0.0,\"by_model\":{}}}");
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-ok"));

        ToolExecutionResult res = module.execute("agent_browse", Map.of("task", "x", "llm", Map.of("provider", "openai", "model", "gpt-4o")), null, null).orElseThrow();
        assertThat(res.success()).isTrue();
    }

    @Test
    @DisplayName("awaitJobResult: existing top-level 'error' key is preserved as-is (no double-wrap)")
    @SuppressWarnings("unchecked")
    void awaitJobResultPreExistingErrorKeyPreserved() throws Exception {
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"error\":\"runner crashed before stop_reason\","
                + "\"stop_reason\":\"ERROR\"}");
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-prerr"));

        ToolExecutionResult res = module.execute("agent_browse", Map.of("task", "x", "llm", Map.of("provider", "openai", "model", "gpt-4o")), null, null).orElseThrow();
        assertThat(res.success()).isFalse();
        assertThat(res.error()).contains("runner crashed before stop_reason");
    }

    @Test
    @DisplayName("buildJobParameters: stringified llm (legacy persisted plan) is parsed back to a Map before forward")
    @SuppressWarnings("unchecked")
    void buildJobParametersParsesStringifiedLlm() throws Exception {
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\",\"cost\":{}}");

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), bodyCaptor.capture(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-stringllm"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        // Pre-fix persisted plans shipped this as a String - defensive
        // parse must hydrate it back to a Map before forwarding.
        params.put("llm", "{\"provider\":\"anthropic\",\"model\":\"claude-haiku-4-5\",\"max_steps\":5}");

        module.execute("agent_browse", params, null, null).orElseThrow();

        Map<String, Object> jobParams = (Map<String, Object>) bodyCaptor.getValue().get("parameters");
        Object forwardedLlm = jobParams.get("llm");
        assertThat(forwardedLlm)
            .as("stringified llm must be hydrated to a Map for the runner's isinstance(dict) check")
            .isInstanceOf(Map.class);
        Map<String, Object> llmMap = (Map<String, Object>) forwardedLlm;
        assertThat(llmMap)
            .containsEntry("provider", "anthropic")
            .containsEntry("model", "claude-haiku-4-5");
    }

    @Test
    @DisplayName("buildJobParameters: malformed JSON in llm string is forwarded verbatim (runner surfaces a precise LlmConfigError)")
    @SuppressWarnings("unchecked")
    void buildJobParametersMalformedLlmStringForwardedVerbatim() throws Exception {
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\",\"cost\":{}}");

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), bodyCaptor.capture(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-bad"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", "{abc"); // looks like JSON but isn't

        module.execute("agent_browse", params, null, null).orElseThrow();

        Map<String, Object> jobParams = (Map<String, Object>) bodyCaptor.getValue().get("parameters");
        // Forwarded as String → runner surfaces a precise LlmConfigError
        // rather than us swallowing it. Better than crashing in the parse.
        assertThat(jobParams.get("llm")).isEqualTo("{abc");
    }

    @Test
    @DisplayName("buildJobParameters: stringified session and expected_output_schema are also hydrated")
    @SuppressWarnings("unchecked")
    void buildJobParametersParsesStringifiedSessionAndSchema() throws Exception {
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\",\"cost\":{}}");

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), bodyCaptor.capture(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-sschema"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o", "max_steps", 5));
        // Pre-fix plans shipped these as Strings too - defensive parse
        // must hydrate them symmetrically with llm.
        params.put("session", "{\"headless\":true,\"timeout_seconds\":120}");
        params.put("expected_output_schema", "{\"type\":\"object\",\"properties\":{\"x\":{\"type\":\"string\"}}}");

        module.execute("agent_browse", params, null, null).orElseThrow();

        Map<String, Object> jobParams = (Map<String, Object>) bodyCaptor.getValue().get("parameters");
        assertThat(jobParams.get("session")).isInstanceOf(Map.class);
        assertThat(((Map<String, Object>) jobParams.get("session")))
            .containsEntry("headless", Boolean.TRUE);
        assertThat(jobParams.get("expected_output_schema")).isInstanceOf(Map.class);
        assertThat(((Map<String, Object>) jobParams.get("expected_output_schema")))
            .containsEntry("type", "object");
    }

    @Test
    @DisplayName("awaitJobResult: missing/null stop_reason fails closed (no silent success on malformed runner payload)")
    @SuppressWarnings("unchecked")
    void awaitJobResultNullStopReasonFailsClosed() throws Exception {
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        // Runner emitted a payload without stop_reason - this should NOT
        // be silently treated as success. fail-closed routes to failure.
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"\",\"cost\":{}}");
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-nostop"));

        ToolExecutionResult res = module.execute("agent_browse", Map.of("task", "x", "llm", Map.of("provider", "openai", "model", "gpt-4o")), null, null).orElseThrow();
        assertThat(res.success()).isFalse();
        assertThat(res.error()).contains("without a stop_reason");
    }

    @Test
    @DisplayName("awaitJobResult: stop_reason=MAX_STEPS routes to failure with the runner's final_result diagnostic")
    @SuppressWarnings("unchecked")
    void awaitJobResultMaxStepsRoutesToFailure() throws Exception {
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"reached max_steps=10\","
                + "\"stop_reason\":\"MAX_STEPS\",\"cost\":{}}");
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-maxsteps"));

        ToolExecutionResult res = module.execute("agent_browse", Map.of("task", "x", "llm", Map.of("provider", "openai", "model", "gpt-4o")), null, null).orElseThrow();
        assertThat(res.success()).isFalse();
        assertThat(res.error()).contains("reached max_steps=10");
    }

    @Test
    @DisplayName("awaitJobResult: non-String final_result is preserved via String.valueOf (no info-loss on structured errors)")
    @SuppressWarnings("unchecked")
    void awaitJobResultNonStringFinalResultPreserved() throws Exception {
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        // Runner returned final_result as a structured object (e.g. for
        // schema validation errors). Without preservation we'd surface a
        // generic "Browser agent stopped with reason X" and lose the
        // diagnostic. With String.valueOf the agent sees the toString() of
        // the structured error.
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":{\"code\":\"BAD_SCHEMA\",\"missing\":\"amount\"},"
                + "\"stop_reason\":\"SCHEMA_MISMATCH\",\"cost\":{}}");
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-struct"));

        ToolExecutionResult res = module.execute("agent_browse", Map.of("task", "x", "llm", Map.of("provider", "openai", "model", "gpt-4o")), null, null).orElseThrow();
        assertThat(res.success()).isFalse();
        assertThat(res.error())
            .as("structured final_result must be stringified into the surfaced error, not dropped")
            .contains("BAD_SCHEMA")
            .contains("amount");
    }

    @Test
    @DisplayName("postProcess runs on FAILURE payloads too - agentClient.recordObservability is still called when stop_reason=LLM_FAILED (regression guard for the WebJobModule reorder)")
    @SuppressWarnings("unchecked")
    void postProcessRunsOnFailurePayloads() throws Exception {
        AgentClient agentClient = org.mockito.Mockito.mock(AgentClient.class);

        BrowserAgentModule moduleWithObs = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, null, agentClient);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"llm config must be a dict, got str\","
                + "\"stop_reason\":\"LLM_FAILED\",\"cost\":{\"tokens_in\":0,\"tokens_out\":0,"
                + "\"llm_calls\":0,\"browser_seconds\":0.03,\"cost_usd\":0.0,\"by_model\":{}}}");
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-failobs"));

        ToolExecutionContext ctx = new ToolExecutionContext(
            "tenant-fail-obs", Map.of(), Map.of(), java.util.Set.of(),
            null, null, null, null);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "google", "model", "gemini-2.5-flash"));

        ToolExecutionResult res = moduleWithObs.execute("agent_browse", params, "tenant-fail-obs", ctx).orElseThrow();
        assertThat(res.success()).isFalse();

        // Critical contract: postProcess runs BEFORE the failure routing in
        // WebJobModule.submitAndAwait, so observability is still recorded.
        // Without this, every failed browser_agent run is invisible in
        // Agent Performance and Usage History. The reorder is the entire
        // reason this test exists - it pins the contract so a future
        // refactor that restores the original order trips the build.
        ArgumentCaptor<AgentObservabilityRequest> reqCaptor =
            ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient).recordObservability(reqCaptor.capture());
        AgentObservabilityRequest req = reqCaptor.getValue();
        assertThat(req.getStatus()).isEqualTo("FAILED");
        assertThat(req.getStopReason()).isEqualTo("ERROR"); // mapped LLM_FAILED → ERROR
    }

    @Test
    @DisplayName("postProcess: agentClient.recordObservability throwing does not break the result delivery")
    @SuppressWarnings("unchecked")
    void postProcessObservabilityExceptionFallsBackGracefully() throws Exception {
        AgentClient agentClient = org.mockito.Mockito.mock(AgentClient.class);
        org.mockito.Mockito.doThrow(new RuntimeException("agent-service down"))
            .when(agentClient).recordObservability(any(AgentObservabilityRequest.class));

        BrowserAgentModule moduleWithObs = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, null, agentClient);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn(resultWithCost("gpt-4o", 1000L, 100L));
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-obs-throws"));

        ToolExecutionContext ctx = new ToolExecutionContext(
            "tenant-x",
            Map.of(),
            Map.of(), java.util.Set.of(),
            null, null, null, null
        );

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o", "max_steps", 5));

        // Result must come through despite agent-service being down.
        ToolExecutionResult res = moduleWithObs.execute("agent_browse", params, "tenant-x", ctx).orElseThrow();
        assertThat(res.success()).isTrue();
    }

    @Test
    @DisplayName("postProcess: builds callback URL when streamId + toolCallId are present")
    @SuppressWarnings("unchecked")
    void agentBrowseAttachesCallbackUrl() throws Exception {
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(config.getCallbackBaseUrl()).thenReturn("http://app-host:8099");
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\"}");

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), bodyCaptor.capture(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-cb"));

        ToolExecutionContext ctx = new ToolExecutionContext(
            "tenant-a",
            Map.of("__streamId__", "stream-1", "__toolCallId__", "tool-1"),
            Map.of(), java.util.Set.of(),
            null, null, null, null
        );

        module.execute("agent_browse", Map.of("task", "x", "llm", Map.of("provider", "openai", "model", "gpt-4o")), null, ctx).orElseThrow();

        Map<String, Object> jobParams = (Map<String, Object>) bodyCaptor.getValue().get("parameters");
        String callbackUrl = (String) jobParams.get("callback_url");
        assertThat(callbackUrl)
            .isNotNull()
            .startsWith("http://app-host:8099/api/internal/websearch/callback/step")
            .contains("streamId=stream-1")
            .contains("toolId=tool-1");
    }

    // ── Pre-flight LLM-model validation ──────────────────────────────
    // Mirrors the agent path's checkChatBudget fail-closed contract:
    // the runner has no model catalog, so an unrecognised model would
    // otherwise burn ~6 retries × upstream-404 before terminating
    // LLM_FAILED. These tests pin the validation policy.

    @Test
    @DisplayName("execute: unknown model + healthy snapshot → INVALID_PARAMETER_VALUE rejection BEFORE submit (no Chromium spawn, no 404 burn)")
    void executeRejectsUnknownModelWhenSnapshotHealthy() {
        PricingSnapshotClient pricingClient = org.mockito.Mockito.mock(PricingSnapshotClient.class);
        when(pricingClient.getRates("google", "gemini-2.5-flash-preview-04-17"))
            .thenReturn(java.util.Optional.empty());
        when(pricingClient.isHealthy()).thenReturn(true);

        BrowserAgentModule moduleWithPricing = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, pricingClient);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of(
            "provider", "google",
            "model", "gemini-2.5-flash-preview-04-17",
            "max_steps", 10));

        ToolExecutionResult res = moduleWithPricing.execute("agent_browse", params, null, null).orElseThrow();

        assertThat(res.success()).isFalse();
        assertThat(res.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
        assertThat(res.error())
            .contains("gemini-2.5-flash-preview-04-17")
            .contains("google")
            .contains("Do NOT retry");

        // Crucial: the runner is never asked. No /jobs/submit, no BLPOP.
        org.mockito.Mockito.verifyNoInteractions(restTemplate, redisTemplate);
    }

    @Test
    @DisplayName("execute: known model in snapshot → pre-flight passes, request reaches the runner")
    @SuppressWarnings("unchecked")
    void executeAllowsKnownModelWhenSnapshotHealthy() throws Exception {
        PricingSnapshotClient pricingClient = org.mockito.Mockito.mock(PricingSnapshotClient.class);
        when(pricingClient.getRates("google", "gemini-2.5-flash"))
            .thenReturn(java.util.Optional.of(new PricingRates(
                new BigDecimal("0.30"),
                new BigDecimal("2.50"),
                BigDecimal.ZERO)));
        // isHealthy() never consulted on the happy path - getRates is enough.

        BrowserAgentModule moduleWithPricing = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, pricingClient);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\"}");
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-known"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "google", "model", "gemini-2.5-flash", "max_steps", 5));

        ToolExecutionResult res = moduleWithPricing.execute("agent_browse", params, null, null).orElseThrow();
        assertThat(res.success()).isTrue();
    }

    @Test
    @DisplayName("execute: unknown model + unhealthy snapshot → fail-OPEN, request reaches the runner (auth-service outage must not reject valid runs)")
    @SuppressWarnings("unchecked")
    void executeAllowsUnknownModelWhenSnapshotUnhealthy() throws Exception {
        PricingSnapshotClient pricingClient = org.mockito.Mockito.mock(PricingSnapshotClient.class);
        when(pricingClient.getRates(anyString(), anyString()))
            .thenReturn(java.util.Optional.empty());
        when(pricingClient.isHealthy()).thenReturn(false);

        BrowserAgentModule moduleWithPricing = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, pricingClient);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\"}");
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-unhealthy"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "google", "model", "future-model", "max_steps", 5));

        ToolExecutionResult res = moduleWithPricing.execute("agent_browse", params, null, null).orElseThrow();
        assertThat(res.success()).isTrue();
    }

    @Test
    @DisplayName("execute: bridge model - pre-flight short-circuits on provider_kind=bridge, snapshot is never queried (bridges manage their own catalog/billing)")
    @SuppressWarnings("unchecked")
    void executeBridgeProviderSkipsPreflightLookup() throws Exception {
        PricingSnapshotClient pricingClient = org.mockito.Mockito.mock(PricingSnapshotClient.class);

        BrowserAgentModule moduleWithPricing = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, pricingClient);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        // No cost block - postProcess.applyPricingToResponse early-returns
        // on `cost` missing, so getRates is exercised ONLY by the pre-flight.
        // We use that to assert the pre-flight short-circuited on
        // provider_kind=bridge.
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\"}");
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-bridge"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of(
            "provider", "claude-code",
            "provider_kind", "bridge",
            "model", "claude-sonnet-4-6",
            "max_steps", 5));

        ToolExecutionResult res = moduleWithPricing.execute("agent_browse", params, null, null).orElseThrow();
        assertThat(res.success()).isTrue();

        // Pre-flight must short-circuit on provider_kind=bridge BEFORE
        // calling getRates - proves bridges aren't gated by the catalog.
        org.mockito.Mockito.verify(pricingClient, org.mockito.Mockito.never())
            .getRates(anyString(), anyString());
    }

    @Test
    @DisplayName("execute: missing provider in llm block → pre-flight skips, runner surfaces its own LlmConfigError")
    @SuppressWarnings("unchecked")
    void executeMissingProviderSkipsPreflight() throws Exception {
        PricingSnapshotClient pricingClient = org.mockito.Mockito.mock(PricingSnapshotClient.class);

        BrowserAgentModule moduleWithPricing = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, pricingClient);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\"}");
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-noprov"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        // Missing 'provider' - pre-flight must NOT fabricate a rejection;
        // letting it through means the runner produces a precise field-level
        // LlmConfigError ("provider is required") rather than a vague
        // catalog-miss message.
        params.put("llm", Map.of("model", "gpt-4o", "max_steps", 5));

        ToolExecutionResult res = moduleWithPricing.execute("agent_browse", params, null, null).orElseThrow();
        assertThat(res.success()).isTrue();

        // Pre-flight never queried the snapshot for this run (no provider key).
        org.mockito.Mockito.verify(pricingClient, org.mockito.Mockito.never())
            .getRates(anyString(), anyString());
    }

    @Test
    @DisplayName("execute: pricing snapshot throwing during pre-flight → fail-OPEN (defense in depth, never reject what we cannot verify)")
    @SuppressWarnings("unchecked")
    void executePricingExceptionDuringPreflightAllowsThrough() throws Exception {
        PricingSnapshotClient pricingClient = org.mockito.Mockito.mock(PricingSnapshotClient.class);
        when(pricingClient.getRates(anyString(), anyString()))
            .thenThrow(new RuntimeException("snapshot service unreachable"));

        BrowserAgentModule moduleWithPricing = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, pricingClient);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"ok\",\"stop_reason\":\"COMPLETED\"}");
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-throw"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o", "max_steps", 5));

        // Even though getRates throws, execute() must not propagate the
        // exception - the run continues and the runner's outcome is
        // returned. (The same exception will be re-thrown inside
        // postProcess.applyPricingToResponse but that path is also
        // wrapped → cost_usd stays at the runner default.)
        ToolExecutionResult res = moduleWithPricing.execute("agent_browse", params, null, null).orElseThrow();
        assertThat(res.success()).isTrue();
    }

    // ── applyDefaultLlmIfNeeded - Audit M1 regression coverage ──────────────
    // The four documented branches of the new fallback resolver. Each test
    // builds a module wired with a mock AgentClient (the catalog source) and
    // asserts both the mutation on `parameters.llm` AND the returned
    // ModelSubstitution descriptor - the pair the agent_browse caller will see
    // as `model_substituted` in its result.

    private BrowserAgentModule moduleWithAgentClient(AgentClient agentClient) {
        return new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, null, agentClient);
    }

    private static Map<String, Object> catalogWithDefault(String defaultProvider, String defaultModel,
                                                          List<Map<String, Object>> providers) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("defaultProvider", defaultProvider);
        c.put("defaultModel", defaultModel);
        c.put("providers", providers);
        return c;
    }

    private static Map<String, Object> providerEntry(String name, String... modelIds) {
        List<Map<String, Object>> models = new java.util.ArrayList<>();
        for (String id : modelIds) models.add(Map.of("id", id, "displayOrder", 1));
        return Map.of("name", name, "models", models);
    }

    @Test
    @DisplayName("applyDefaultLlmIfNeeded: llm absent → injects platform default + returns ModelSubstitution")
    void applyDefaultLlmIfNeeded_missingBlock() {
        AgentClient agentClient = org.mockito.Mockito.mock(AgentClient.class);
        when(agentClient.getModelsInfo("browser_agent")).thenReturn(catalogWithDefault(
            "anthropic", "claude-sonnet-4-6",
            List.of(providerEntry("anthropic", "claude-sonnet-4-6"), providerEntry("openai", "gpt-5"))));

        BrowserAgentModule m = moduleWithAgentClient(agentClient);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");

        Optional<BrowserAgentModule.ModelSubstitution> sub = m.applyDefaultLlmIfNeeded(params);

        assertThat(sub).isPresent();
        assertThat(sub.get().requestedProvider()).isEqualTo("(none)");
        assertThat(sub.get().actualProvider()).isEqualTo("anthropic");
        assertThat(sub.get().actualModel()).isEqualTo("claude-sonnet-4-6");
        @SuppressWarnings("unchecked")
        Map<String, Object> llm = (Map<String, Object>) params.get("llm");
        assertThat(llm).containsEntry("provider", "anthropic").containsEntry("model", "claude-sonnet-4-6");
    }

    @Test
    @DisplayName("applyDefaultLlmIfNeeded: bridge provider_kind → never substituted, returns empty Optional, parameters.llm rewritten as Map")
    void applyDefaultLlmIfNeeded_bridgePassthrough() {
        AgentClient agentClient = org.mockito.Mockito.mock(AgentClient.class);
        BrowserAgentModule m = moduleWithAgentClient(agentClient);

        Map<String, Object> params = new LinkedHashMap<>();
        // Pre-fix legacy plans shipped llm as a JSON string. Verify that the
        // bridge fast-path still re-writes parameters.llm as a Map (Audit B
        // m2: "doesn't write back" - fix asserts consistency with every other
        // branch).
        params.put("llm", "{\"provider_kind\":\"bridge\",\"provider\":\"claude-code\",\"model\":\"claude-sonnet-4-6-cc\"}");

        Optional<BrowserAgentModule.ModelSubstitution> sub = m.applyDefaultLlmIfNeeded(params);

        assertThat(sub).isEmpty();
        assertThat(params.get("llm")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> llm = (Map<String, Object>) params.get("llm");
        assertThat(llm).containsEntry("provider", "claude-code").containsEntry("model", "claude-sonnet-4-6-cc");
        // Bridge path must NOT call agent-service at all - the bridge has its
        // own auth/billing/catalog.
        verify(agentClient, never()).getModelsInfo("browser_agent");
    }

    @Test
    @DisplayName("applyDefaultLlmIfNeeded: unknown (provider, model) pair → swapped to default + ModelSubstitution emitted")
    void applyDefaultLlmIfNeeded_unknownPairSubstituted() {
        AgentClient agentClient = org.mockito.Mockito.mock(AgentClient.class);
        when(agentClient.getModelsInfo("browser_agent")).thenReturn(catalogWithDefault(
            "anthropic", "claude-sonnet-4-6",
            List.of(providerEntry("anthropic", "claude-sonnet-4-6"))));

        BrowserAgentModule m = moduleWithAgentClient(agentClient);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("llm", new LinkedHashMap<>(Map.of("provider", "openai", "model", "gpt-99-imaginary")));

        Optional<BrowserAgentModule.ModelSubstitution> sub = m.applyDefaultLlmIfNeeded(params);

        assertThat(sub).isPresent();
        assertThat(sub.get().requestedProvider()).isEqualTo("openai");
        assertThat(sub.get().requestedModel()).isEqualTo("gpt-99-imaginary");
        assertThat(sub.get().actualProvider()).isEqualTo("anthropic");
        assertThat(sub.get().actualModel()).isEqualTo("claude-sonnet-4-6");
        @SuppressWarnings("unchecked")
        Map<String, Object> llm = (Map<String, Object>) params.get("llm");
        assertThat(llm).containsEntry("provider", "anthropic").containsEntry("model", "claude-sonnet-4-6");
    }

    @Test
    @DisplayName("applyDefaultLlmIfNeeded: valid (provider, model) pair → no substitution, exactly ONE getModelsInfo call (catalog membership check, no fallback lookup)")
    void applyDefaultLlmIfNeeded_validPairUntouched() {
        AgentClient agentClient = org.mockito.Mockito.mock(AgentClient.class);
        when(agentClient.getModelsInfo("browser_agent")).thenReturn(catalogWithDefault(
            "anthropic", "claude-sonnet-4-6",
            List.of(providerEntry("openai", "gpt-5"), providerEntry("anthropic", "claude-sonnet-4-6"))));

        BrowserAgentModule m = moduleWithAgentClient(agentClient);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("llm", new LinkedHashMap<>(Map.of("provider", "openai", "model", "gpt-5", "max_steps", 7)));

        Optional<BrowserAgentModule.ModelSubstitution> sub = m.applyDefaultLlmIfNeeded(params);

        assertThat(sub).isEmpty();
        @SuppressWarnings("unchecked")
        Map<String, Object> llm = (Map<String, Object>) params.get("llm");
        // Original block preserved including caller-provided max_steps.
        assertThat(llm).containsEntry("provider", "openai").containsEntry("model", "gpt-5").containsEntry("max_steps", 7);
        // Pin the HTTP-roundtrip claim from the DisplayName: exactly ONE call
        // to getModelsInfo (catalog membership check), no second call for the
        // fallback default (the fast path returned before that branch).
        verify(agentClient, org.mockito.Mockito.times(1)).getModelsInfo("browser_agent");
    }

    @Test
    @DisplayName("applyDefaultLlmIfNeeded: agent-service unreachable → fail-OPEN (no substitution), block stays as-is")
    void applyDefaultLlmIfNeeded_failOpenOnInfra() {
        AgentClient agentClient = org.mockito.Mockito.mock(AgentClient.class);
        // Simulate agent-service hiccup on the catalog read for the substitution path.
        when(agentClient.getModelsInfo("browser_agent")).thenThrow(new RuntimeException("agent-service down"));

        BrowserAgentModule m = moduleWithAgentClient(agentClient);
        Map<String, Object> params = new LinkedHashMap<>();
        // No llm block → the resolver MUST hit getModelsInfo for the default,
        // catch the throw, and leave params.llm as an empty Map (no Optional
        // populated, no infinite retry).
        params.put("task", "x");

        Optional<BrowserAgentModule.ModelSubstitution> sub = m.applyDefaultLlmIfNeeded(params);

        assertThat(sub).isEmpty();
        // The empty Map plumbed into params is harmless - pre-flight validation
        // (validateLlmModelOrNull) decides whether to fail or pass it through;
        // applyDefaultLlmIfNeeded explicitly does not.
    }

    private static Map<String, Object> catalogWithBridgeDefault(String defaultProvider, String defaultModel,
                                                                String bridgeUrl,
                                                                List<Map<String, Object>> providers) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("defaultProvider", defaultProvider);
        c.put("defaultModel", defaultModel);
        c.put("bridgeUrl", bridgeUrl);
        c.put("providers", providers);
        return c;
    }

    private static Map<String, Object> bridgeProviderEntry(String name, String... modelIds) {
        Map<String, Object> p = new LinkedHashMap<>(providerEntry(name, modelIds));
        p.put("providerKind", "bridge");
        return p;
    }

    @Test
    @DisplayName("REGRESSION: applyDefaultLlmIfNeeded SKIPS bridge providers and substitutes the direct-API default - bridges can't serve per-step chat completions for browser-use (404 on /v1/chat/completions)")
    @SuppressWarnings("unchecked")
    void applyDefaultLlmIfNeeded_skipsBridgesUsesDirectApiDefault() {
        // Reproduces the user-reported live failure: codex (CLI bridge) was
        // ranked #1 globally. The pre-fix substitution injected codex as the
        // llm, the runner POSTed to {bridge_url}/v1/chat/completions which the
        // bridge doesn't expose (its real endpoint is /api/bridge/execute and
        // accepts a full-session DTO, not per-step chat completions). The
        // bridge replied 404 → repeated failures → LLM_FAILED. Architecturally
        // bridges are full-CLI agent sessions, not chat-completion APIs;
        // browser-use needs the latter. The catalog now exposes a separate
        // `defaultDirectProvider`/`defaultDirectModel` that excludes bridges,
        // and BrowserAgentModule uses it for substitution. Bridges remain the
        // overall #1 for chat / agent.create which work with the bridge
        // session-per-call contract.
        AgentClient agentClient = org.mockito.Mockito.mock(AgentClient.class);
        Map<String, Object> catalog = new LinkedHashMap<>();
        // codex is the GLOBAL #1 (defaultProvider/defaultModel), but
        // anthropic is the direct-API #1 (defaultDirectProvider/Model).
        catalog.put("defaultProvider", "codex");
        catalog.put("defaultModel", "claude-sonnet-4-6-cc");
        catalog.put("defaultDirectProvider", "anthropic");
        catalog.put("defaultDirectModel", "claude-opus-4-6");
        catalog.put("bridgeUrl", "http://websearch-host:8093");
        catalog.put("providers", List.of(
            bridgeProviderEntry("codex", "claude-sonnet-4-6-cc"),
            providerEntry("anthropic", "claude-opus-4-6")));
        when(agentClient.getModelsInfo("browser_agent")).thenReturn(catalog);

        BrowserAgentModule m = moduleWithAgentClient(agentClient);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");

        Optional<BrowserAgentModule.ModelSubstitution> sub = m.applyDefaultLlmIfNeeded(params);

        assertThat(sub).isPresent();
        // anthropic (direct-API default) wins over codex (overall default).
        assertThat(sub.get().actualProvider()).isEqualTo("anthropic");
        assertThat(sub.get().actualModel()).isEqualTo("claude-opus-4-6");
        Map<String, Object> llm = (Map<String, Object>) params.get("llm");
        assertThat(llm).containsEntry("provider", "anthropic");
        assertThat(llm).containsEntry("model", "claude-opus-4-6");
        // No bridge metadata leaked - anthropic is direct-API.
        assertThat(llm).doesNotContainKey("provider_kind");
        assertThat(llm).doesNotContainKey("bridge_url");
        // The substitution notice tells the agent WHY a bridge was skipped.
        assertThat(sub.get().toResponseMap().get("reason"))
            .containsIgnoringCase("bridge")
            .containsIgnoringCase("per-step");
    }

    @Test
    @DisplayName("applyDefaultLlmIfNeeded: when no direct-API default exists (CE with bridges only), falls back to overall default - runner surfaces the precise error rather than silently misrouting")
    @SuppressWarnings("unchecked")
    void applyDefaultLlmIfNeeded_fallsBackToOverallDefaultWhenNoDirectAvailable() {
        // CE deployment edge: only bridges configured, no direct-API provider
        // at all. defaultDirectProvider/defaultDirectModel are null. We fall
        // back to the overall defaults so the runner gets SOME llm block -
        // it will then surface the precise "bridge can't do per-step" error
        // rather than us emitting a vague "no llm" failure here.
        AgentClient agentClient = org.mockito.Mockito.mock(AgentClient.class);
        Map<String, Object> catalog = new LinkedHashMap<>();
        catalog.put("defaultProvider", "codex");
        catalog.put("defaultModel", "claude-sonnet-4-6-cc");
        catalog.put("defaultDirectProvider", null);  // no direct-API option
        catalog.put("defaultDirectModel", null);
        catalog.put("bridgeUrl", "http://websearch-host:8093");
        catalog.put("providers", List.of(bridgeProviderEntry("codex", "claude-sonnet-4-6-cc")));
        when(agentClient.getModelsInfo("browser_agent")).thenReturn(catalog);

        BrowserAgentModule m = moduleWithAgentClient(agentClient);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");

        Optional<BrowserAgentModule.ModelSubstitution> sub = m.applyDefaultLlmIfNeeded(params);

        assertThat(sub).isPresent();
        assertThat(sub.get().actualProvider()).isEqualTo("codex");
        Map<String, Object> llm = (Map<String, Object>) params.get("llm");
        assertThat(llm).containsEntry("provider", "codex");
    }

    @Test
    @DisplayName("applyDefaultLlmIfNeeded: catalog returns no defaultProvider/defaultModel → no substitution, fail-OPEN")
    void applyDefaultLlmIfNeeded_emptyCatalogDefault() {
        AgentClient agentClient = org.mockito.Mockito.mock(AgentClient.class);
        // Catalog responded but no default is set (CE without billing, or
        // admin disabled every provider).
        when(agentClient.getModelsInfo("browser_agent")).thenReturn(Map.of("providers", List.of()));

        BrowserAgentModule m = moduleWithAgentClient(agentClient);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");

        Optional<BrowserAgentModule.ModelSubstitution> sub = m.applyDefaultLlmIfNeeded(params);

        assertThat(sub).isEmpty();
    }

    // ── execute() integration: CE guardrail + model_substituted plumbing ──
    // Audit B MAJOR test gaps. The 6 resolver-isolation tests above only cover
    // applyDefaultLlmIfNeeded; nothing was asserting that execute() actually
    // (a) fails loudly on CE when llm is missing AND agentClient is null, and
    // (b) plumbs the substitution descriptor into the result data. Both are
    // first-pass blockers per Audit B; CLAUDE.md mandates a regression guard.

    @Test
    @DisplayName("CE symmetry: agent_browse WITH explicit llm AND agentClient unwired → guardrail does NOT fire, run proceeds normally")
    @SuppressWarnings("unchecked")
    void agentBrowseSucceedsOnCeWithExplicitLlm() throws Exception {
        // Sister of agentBrowseFailsLoudOnCeWithoutLlm: when the LLM provides
        // an explicit llm block on a CE deployment (agentClient null), the
        // CE guardrail must NOT misfire - the runner-side resolution still
        // works because the (provider, model) pair is fully specified upfront.
        BrowserAgentModule ce = new BrowserAgentModule(restTemplate, config, redisTemplate, objectMapper);
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"done\",\"stop_reason\":\"COMPLETED\"}");
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-ce-explicit"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "Open the page");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o"));

        ToolExecutionResult res = ce.execute("agent_browse", params, null, null).orElseThrow();

        assertThat(res.success()).isTrue();
        // No model_substituted notice since the resolver short-circuited
        // (agentClient null returns Optional.empty() with no work done).
        Map<String, Object> data = (Map<String, Object>) res.data();
        assertThat(data).doesNotContainKey("model_substituted");
    }

    @Test
    @DisplayName("CE guardrail: agent_browse without llm AND agentClient unwired → MISSING_PARAMETER with actionable message")
    void agentBrowseFailsLoudOnCeWithoutLlm() {
        // Use the simple constructor - agentClient is null, mirroring CE-without-billing.
        BrowserAgentModule ce = new BrowserAgentModule(restTemplate, config, redisTemplate, objectMapper);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "Open the page");
        // No "llm" - the bug the guardrail protects against. Without it, the
        // Python runner crashes generically; the guardrail must intercept BEFORE
        // submit/await with a precise actionable error.

        ToolExecutionResult res = ce.execute("agent_browse", params, null, null).orElseThrow();

        assertThat(res.success()).isFalse();
        assertThat(res.errorCode()).isEqualTo(com.apimarketplace.agent.tools.ToolErrorCode.MISSING_PARAMETER);
        String err = res.error();
        // The message must steer the agent toward the fix, not toward
        // /settings/ai-providers or any admin UI.
        assertThat(err).contains("'llm' block");
        assertThat(err).contains("provider");
        assertThat(err).contains("model");
        // Belt-and-suspenders: the agent must NEVER have hit Redis or the
        // websearch service when the guardrail fires.
        org.mockito.Mockito.verifyNoInteractions(restTemplate);
    }

    @Test
    @DisplayName("execute(agent_browse) plumbs model_substituted into the result data when the resolver substituted the llm block")
    @SuppressWarnings("unchecked")
    void agentBrowseEmitsModelSubstitutedOnSwap() throws Exception {
        // Setup: agent omits llm, agentClient returns a default → resolver
        // substitutes anthropic/claude-sonnet-4-6 → execute() must surface that
        // swap as `model_substituted` in the runner's result so the LLM caller
        // can see what was actually used.
        AgentClient agentClient = org.mockito.Mockito.mock(AgentClient.class);
        when(agentClient.getModelsInfo("browser_agent")).thenReturn(catalogWithDefault(
            "anthropic", "claude-sonnet-4-6",
            List.of(providerEntry("anthropic", "claude-sonnet-4-6"))));

        BrowserAgentModule m = moduleWithAgentClient(agentClient);
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"done\",\"stop_reason\":\"COMPLETED\"}");
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-sub"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        // No llm - triggers the resolver substitution path.

        ToolExecutionResult res = m.execute("agent_browse", params, null, null).orElseThrow();

        assertThat(res.success()).isTrue();
        Map<String, Object> data = (Map<String, Object>) res.data();
        Map<String, String> sub = (Map<String, String>) data.get("model_substituted");
        assertThat(sub)
            .as("execute() must inject the substitution descriptor returned by applyDefaultLlmIfNeeded")
            .isNotNull();
        assertThat(sub.get("requested")).isEqualTo("(none)/(none)");
        assertThat(sub.get("actual")).isEqualTo("anthropic/claude-sonnet-4-6");
        assertThat(sub.get("reason")).contains("not available");
    }

    @Test
    @DisplayName("execute(agent_browse) does NOT inject model_substituted when the caller passed a valid pair (no swap happened)")
    @SuppressWarnings("unchecked")
    void agentBrowseOmitsModelSubstitutedWhenNoSwap() throws Exception {
        // Symmetric counterpart: when the resolver returns Optional.empty()
        // (caller passed a valid pair), the result must NOT carry a
        // model_substituted key. Otherwise consumers reading the field would
        // see a phantom swap and misreport "the platform changed your model".
        AgentClient agentClient = org.mockito.Mockito.mock(AgentClient.class);
        when(agentClient.getModelsInfo("browser_agent")).thenReturn(catalogWithDefault(
            "anthropic", "claude-sonnet-4-6",
            List.of(providerEntry("openai", "gpt-5"), providerEntry("anthropic", "claude-sonnet-4-6"))));

        BrowserAgentModule m = moduleWithAgentClient(agentClient);
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"done\",\"stop_reason\":\"COMPLETED\"}");
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-nosub"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", new LinkedHashMap<>(Map.of("provider", "openai", "model", "gpt-5")));

        ToolExecutionResult res = m.execute("agent_browse", params, null, null).orElseThrow();

        assertThat(res.success()).isTrue();
        Map<String, Object> data = (Map<String, Object>) res.data();
        assertThat(data).doesNotContainKey("model_substituted");
    }

    // ── Top-level `max_steps` forwarding regression (Bug C from runner audit) ──
    // Pre-fix: schema declared `max_steps` as a top-level override but
    // buildJobParameters never forwarded it. The agent saw the doc, sent
    // max_steps=80, the runner used its 50-step default, the agent saw
    // (since-fixed) misclassified MAX_STEPS error, retried with the same
    // bump, same false signal - frustration loop. Pin the forwarding here
    // so a future refactor cannot silently regress it.

    @Test
    @DisplayName("agent_browse: top-level `max_steps` override is forwarded into the runner job parameters")
    @SuppressWarnings("unchecked")
    void agentBrowseForwardsTopLevelMaxStepsOverride() throws Exception {
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(eq("agent:browser:result:job-ms"), any(Duration.class)))
            .thenReturn("{\"final_result\":\"done\",\"stop_reason\":\"COMPLETED\"}");

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), bodyCaptor.capture(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-ms"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "Look up X");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o"));
        params.put("max_steps", 80);

        ToolExecutionResult res = module.execute("agent_browse", params, null, null).orElseThrow();
        assertThat(res.success()).isTrue();

        Map<String, Object> body = bodyCaptor.getValue();
        Map<String, Object> jobParams = (Map<String, Object>) body.get("parameters");
        assertThat(jobParams)
            .as("top-level max_steps must be forwarded so the runner overrides its 50-step default")
            .containsEntry("max_steps", 80);
    }

    @Test
    @DisplayName("agent_browse: top-level max_steps wins over llm.max_steps (legacy plans that double-supplied)")
    @SuppressWarnings("unchecked")
    void agentBrowseTopLevelMaxStepsWinsOverLlmBlock() throws Exception {
        // Audit G NIT - pin the precedence contract. Legacy plans sometimes
        // shipped both. The runner reads top-level only (llm.max_steps is
        // dead syntax since it's never read), so top-level always wins by
        // construction. Pin both halves of that contract: jobParams.max_steps
        // == top-level; jobParams.llm.max_steps is forwarded verbatim but the
        // runner ignores it.
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(eq("agent:browser:result:job-prec"), any(Duration.class)))
            .thenReturn("{\"final_result\":\"done\",\"stop_reason\":\"COMPLETED\"}");

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), bodyCaptor.capture(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-prec"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("max_steps", 30);
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o", "max_steps", 5));

        module.execute("agent_browse", params, null, null).orElseThrow();

        Map<String, Object> body = bodyCaptor.getValue();
        Map<String, Object> jobParams = (Map<String, Object>) body.get("parameters");
        assertThat(jobParams).containsEntry("max_steps", 30);
        // llm.max_steps stays in the block (untouched passthrough) - runner
        // simply ignores it. Documenting this so a future "let's clean the
        // llm block" refactor doesn't break legacy persisted plans.
        Map<String, Object> llm = (Map<String, Object>) jobParams.get("llm");
        assertThat(llm).containsEntry("max_steps", 5);
    }

    @Test
    @DisplayName("agent_browse: top-level max_steps tolerates Integer / Long / String shapes (Jackson polymorphism)")
    @SuppressWarnings("unchecked")
    void agentBrowseForwardsMaxStepsAcrossTypes() throws Exception {
        // Audit G MINOR - type robustness. forwardIfPresent is type-agnostic
        // by contract. If a future MCP client serializes max_steps as a Long
        // (Jackson does this for >Int.MAX) or a String "80" (some weak LLMs
        // wrap numerics in quotes), the runner's int(...) coerces - but the
        // forward must not drop the value en route. Parameterize over the
        // three realistic shapes.
        for (Object value : new Object[]{Integer.valueOf(40), Long.valueOf(45L), "50"}) {
            org.mockito.Mockito.reset(restTemplate, redisTemplate, listOps);
            when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(listOps.leftPop(anyString(), any(Duration.class)))
                .thenReturn("{\"final_result\":\"done\",\"stop_reason\":\"COMPLETED\"}");
            ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
            when(restTemplate.postForObject(anyString(), bodyCaptor.capture(), eq(Map.class)))
                .thenReturn(Map.of("job_id", "job-types-" + value));

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("task", "x");
            params.put("llm", Map.of("provider", "openai", "model", "gpt-4o"));
            params.put("max_steps", value);

            module.execute("agent_browse", params, null, null).orElseThrow();
            Map<String, Object> jobParams = (Map<String, Object>) bodyCaptor.getValue().get("parameters");
            assertThat(jobParams)
                .as("type %s must round-trip through forwardIfPresent", value.getClass().getSimpleName())
                .containsEntry("max_steps", value);
        }
    }

    @Test
    @DisplayName("agent_browse: explicit null max_steps is skipped (forwardIfPresent treats null as absent)")
    @SuppressWarnings("unchecked")
    void agentBrowseSkipsExplicitNullMaxSteps() throws Exception {
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(anyString(), any(Duration.class)))
            .thenReturn("{\"final_result\":\"done\",\"stop_reason\":\"COMPLETED\"}");
        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(restTemplate.postForObject(anyString(), bodyCaptor.capture(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-null"));

        // Some MCP clients normalize missing fields to explicit null. The
        // forward must skip null so the runner's `parameters.get("max_steps")
        // or DEFAULT_MAX_STEPS` short-circuit picks the default cleanly,
        // rather than receiving a stale null and crashing on int(None).
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o"));
        params.put("max_steps", null);

        module.execute("agent_browse", params, null, null).orElseThrow();
        Map<String, Object> jobParams = (Map<String, Object>) bodyCaptor.getValue().get("parameters");
        assertThat(jobParams).doesNotContainKey("max_steps");
    }

    @Test
    @DisplayName("agent_browse: when no top-level max_steps is passed, jobParams.max_steps is absent (runner default applies)")
    @SuppressWarnings("unchecked")
    void agentBrowseOmitsMaxStepsWhenNotProvided() throws Exception {
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(150);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(eq("agent:browser:result:job-noms"), any(Duration.class)))
            .thenReturn("{\"final_result\":\"done\",\"stop_reason\":\"COMPLETED\"}");

        ArgumentCaptor<Map> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), bodyCaptor.capture(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-noms"));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", Map.of("provider", "openai", "model", "gpt-4o"));
        // No max_steps - symmetry test: forwardIfPresent must NOT inject a
        // null/0 value that would confuse the runner.

        module.execute("agent_browse", params, null, null).orElseThrow();
        Map<String, Object> body = bodyCaptor.getValue();
        Map<String, Object> jobParams = (Map<String, Object>) body.get("parameters");
        assertThat(jobParams).doesNotContainKey("max_steps");
    }

    // ── Regression: BLPOP-timeout cleanup hook (onSubmitTimeout) ────────────────
    //
    // Bug: client-side per-tool timeout (120 s) used to fire before the
    // orchestrator's BLPOP (150 s). The agent gave up but the runner kept
    // holding the per-user concurrent slot until its own ~600 s internal
    // timeout, locking the user out. Subsequent retries hit "in-flight=1",
    // and the agent had no session_id to call browse_status/browse_abort.
    // Fix: BrowserAgentModule overrides getBlpopTimeoutSeconds() to ~110 s
    // (so the orchestrator times out FIRST) and onSubmitTimeout() to
    // (a) drain late-arriving results, (b) abort the runner session,
    // (c) LREM the per-user slot ourselves, (d) DEL the job→session mapping.

    @Test
    @DisplayName("BLPOP timeout: drains late LPUSH, returns SUCCESS, short-circuits cleanup (no abort, no LREM, no DEL)")
    @SuppressWarnings("unchecked")
    void onTimeoutDrainsLateResultReturnsSuccess() throws Exception {
        // Main BLPOP returns null (timeout). drainLateResult LPOP (1 s) finds a
        // result the runner LPUSHed during the timeout window - must return
        // success with that payload AND skip all cleanup (no abort POST, no
        // LREM, no DEL of mapping). Aborting a session that just succeeded
        // would discard the very result we just recovered.
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(205);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        // Main BLPOP at 100 s → null (timeout)
        when(listOps.leftPop(eq("agent:browser:result:job-late"), eq(Duration.ofSeconds(205))))
            .thenReturn(null);
        // drainLateResult 1 s LPOP → finds the runner's late result (COMPLETED)
        when(listOps.leftPop(eq("agent:browser:result:job-late"), eq(Duration.ofSeconds(1))))
            .thenReturn("{\"final_result\":\"raced through\",\"stop_reason\":\"COMPLETED\"}");
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-late"));

        ToolExecutionResult res = module.execute("agent_browse",
            Map.of("task", "x", "llm", Map.of("provider", "openai", "model", "gpt-4o")),
            null, null).orElseThrow();

        assertThat(res.success()).as("late-arriving result must NOT be reported as timeout").isTrue();
        Map<String, Object> data = (Map<String, Object>) res.data();
        assertThat(data).containsEntry("final_result", "raced through");
        // Critical: race-recovery must short-circuit ALL cleanup steps -
        // aborting a session that just succeeded discards the result we
        // just returned to the user.
        verify(restTemplate, never()).postForObject(
            org.mockito.ArgumentMatchers.contains("/agent/sessions/"), any(), eq(Map.class));
        verify(listOps, never()).remove(anyString(), eq(1L), anyString());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("BLPOP timeout: race-recovered late result with non-COMPLETED stop_reason routes to FAILURE (fail-closed)")
    @SuppressWarnings("unchecked")
    void onTimeoutDrainNonCompletedRoutesToFailure() throws Exception {
        // Mirrors the fail-closed guard in awaitJobResult: a late LPUSHed
        // payload with stop_reason=ERROR/MAX_STEPS/etc. must NOT be surfaced
        // as a successful agent_browse - final_result becomes the error msg.
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(205);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(eq("agent:browser:result:job-bad"), eq(Duration.ofSeconds(205))))
            .thenReturn(null);
        when(listOps.leftPop(eq("agent:browser:result:job-bad"), eq(Duration.ofSeconds(1))))
            .thenReturn("{\"final_result\":\"reached MAX_STEPS\",\"stop_reason\":\"MAX_STEPS\"}");
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-bad"));

        ToolExecutionResult res = module.execute("agent_browse",
            Map.of("task", "x", "llm", Map.of("provider", "openai", "model", "gpt-4o")),
            null, null).orElseThrow();

        assertThat(res.success()).as("non-COMPLETED late result must route to failure (fail-closed)").isFalse();
        assertThat(res.error())
            .contains("Browser session failed")
            .contains("reached MAX_STEPS");
    }

    @Test
    @DisplayName("BLPOP timeout: aborts runner session + LREMs slot + DELs mapping when session_id resolvable")
    void onTimeoutFullCleanupWhenSessionIdKnown() throws Exception {
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(205);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        // Main BLPOP times out
        when(listOps.leftPop(eq("agent:browser:result:job-clean"), eq(Duration.ofSeconds(205))))
            .thenReturn(null);
        // No late result, neither in round 1 nor round 2 (post-cleanup re-poll)
        when(listOps.leftPop(eq("agent:browser:result:job-clean"), eq(Duration.ofSeconds(1))))
            .thenReturn(null);
        // Runner published the session_id mapping
        when(valueOps.get(eq("agent:browser:job:job-clean"))).thenReturn("sess-xyz");
        // LREM removes the entry → slot_released should report true
        when(listOps.remove(eq("agent:browser:user:user-7:concurrent"), eq(1L), eq("sess-xyz")))
            .thenReturn(1L);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-clean"));

        // Build a context with tenantId so resolveUserId returns "user-7" → LREM is exercised
        ToolExecutionContext ctx = mock(ToolExecutionContext.class);
        lenient().when(ctx.tenantId()).thenReturn("user-7");
        lenient().when(ctx.credentials()).thenReturn(Map.of());

        ToolExecutionResult res = module.execute("agent_browse",
            Map.of("task", "x", "llm", Map.of("provider", "openai", "model", "gpt-4o")),
            "user-7", ctx).orElseThrow();

        // (1) Abort POST hit the session-control endpoint
        verify(restTemplate).postForObject(
            eq(SERVICE_URL + "/agent/sessions/sess-xyz/abort"), any(), eq(Map.class));
        // (2) Authoritative LREM on the per-user concurrent slot
        verify(listOps).remove(eq("agent:browser:user:user-7:concurrent"), eq(1L), eq("sess-xyz"));
        // (3) Job→session mapping deleted
        verify(redisTemplate).delete(eq("agent:browser:job:job-clean"));
        // (4) Failure carries session_id for agent-side recovery
        assertThat(res.success()).isFalse();
        assertThat(res.error())
            .contains("Browser agent_browse timed out after 205s")
            .contains("Slot released")
            .contains("Do NOT retry")
            .contains("session_id=sess-xyz");
        assertThat(res.metadata())
            .containsEntry("session_id", "sess-xyz")
            .containsEntry("auto_aborted", true)
            .containsEntry("slot_released", true)
            .containsEntry("timeout_seconds", 205);
    }

    @Test
    @DisplayName("BLPOP timeout: tenantId arg is used for LREM when context.tenantId() is null (legacy path)")
    void onTimeoutUsesTenantIdArgWhenContextHasNone() throws Exception {
        // Regression: prior to threading tenantId through the hook, this
        // path skipped LREM and let the slot leak. Now it must release.
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(205);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(listOps.leftPop(eq("agent:browser:result:job-legacy"), eq(Duration.ofSeconds(205))))
            .thenReturn(null);
        when(listOps.leftPop(eq("agent:browser:result:job-legacy"), eq(Duration.ofSeconds(1))))
            .thenReturn(null);
        when(valueOps.get(eq("agent:browser:job:job-legacy"))).thenReturn("sess-leg");
        when(listOps.remove(eq("agent:browser:user:user-legacy:concurrent"), eq(1L), eq("sess-leg")))
            .thenReturn(1L);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-legacy"));

        // Context.tenantId() returns null - only the tenantId arg has the user.
        ToolExecutionContext ctx = mock(ToolExecutionContext.class);
        lenient().when(ctx.tenantId()).thenReturn(null);
        lenient().when(ctx.credentials()).thenReturn(Map.of());

        ToolExecutionResult res = module.execute("agent_browse",
            Map.of("task", "x", "llm", Map.of("provider", "openai", "model", "gpt-4o")),
            "user-legacy", ctx).orElseThrow();

        // LREM keyed on the tenantId ARG, not the (null) context value.
        verify(listOps).remove(eq("agent:browser:user:user-legacy:concurrent"), eq(1L), eq("sess-leg"));
        assertThat(res.metadata()).containsEntry("slot_released", true);
    }

    @Test
    @DisplayName("BLPOP timeout: missing session_id mapping (cold-start crash) → no abort POST, no LREM, still returns clean failure")
    void onTimeoutGracefulWhenSessionIdMissing() throws Exception {
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(205);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(listOps.leftPop(eq("agent:browser:result:job-coldstart"), eq(Duration.ofSeconds(205))))
            .thenReturn(null);
        when(listOps.leftPop(eq("agent:browser:result:job-coldstart"), eq(Duration.ofSeconds(1))))
            .thenReturn(null);
        // Mapping never published across all 3 retries - runner crashed
        // during bootstrap (LlmConfigError, etc.)
        when(valueOps.get(eq("agent:browser:job:job-coldstart"))).thenReturn(null);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-coldstart"));

        ToolExecutionContext ctx = mock(ToolExecutionContext.class);
        lenient().when(ctx.tenantId()).thenReturn("user-9");
        lenient().when(ctx.credentials()).thenReturn(Map.of());

        ToolExecutionResult res = module.execute("agent_browse",
            Map.of("task", "x", "llm", Map.of("provider", "openai", "model", "gpt-4o")),
            "user-9", ctx).orElseThrow();

        // No abort POST (no session_id to address)
        verify(restTemplate, never()).postForObject(
            org.mockito.ArgumentMatchers.contains("/agent/sessions/"), any(), eq(Map.class));
        // No LREM (we don't know which value to remove)
        verify(listOps, never()).remove(anyString(), eq(1L), anyString());
        // Still cleans up the job mapping (best-effort even if mapping is null)
        verify(redisTemplate).delete(eq("agent:browser:job:job-coldstart"));
        // Failure carries the timeout context but no session_id field
        assertThat(res.success()).isFalse();
        assertThat(res.error())
            .contains("Browser agent_browse timed out after 205s")
            .doesNotContain("session_id=");
        assertThat(res.metadata())
            .containsEntry("auto_aborted", false)
            .containsEntry("slot_released", false)
            .doesNotContainKey("session_id");
    }

    @Test
    @DisplayName("BLPOP timeout: transient Redis error on first GET retry doesn't abort the retry loop")
    void onTimeoutResolveSessionIdRetriesAfterTransientError() throws Exception {
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(205);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(listOps.leftPop(eq("agent:browser:result:job-jitter"), eq(Duration.ofSeconds(205))))
            .thenReturn(null);
        when(listOps.leftPop(eq("agent:browser:result:job-jitter"), eq(Duration.ofSeconds(1))))
            .thenReturn(null);
        // Two attempts throw, third returns the sid - mimics a Lettuce reconnect blip.
        when(valueOps.get(eq("agent:browser:job:job-jitter")))
            .thenThrow(new RuntimeException("Lettuce reconnect"))
            .thenThrow(new RuntimeException("Lettuce reconnect"))
            .thenReturn("sess-jitter");
        when(listOps.remove(eq("agent:browser:user:user-jit:concurrent"), eq(1L), eq("sess-jitter")))
            .thenReturn(1L);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-jitter"));

        ToolExecutionContext ctx = mock(ToolExecutionContext.class);
        lenient().when(ctx.tenantId()).thenReturn("user-jit");
        lenient().when(ctx.credentials()).thenReturn(Map.of());

        ToolExecutionResult res = module.execute("agent_browse",
            Map.of("task", "x", "llm", Map.of("provider", "openai", "model", "gpt-4o")),
            "user-jit", ctx).orElseThrow();

        // Despite 2 transient failures, the 3rd attempt succeeded → cleanup ran fully.
        verify(restTemplate).postForObject(
            eq(SERVICE_URL + "/agent/sessions/sess-jitter/abort"), any(), eq(Map.class));
        verify(listOps).remove(eq("agent:browser:user:user-jit:concurrent"), eq(1L), eq("sess-jitter"));
        assertThat(res.metadata())
            .containsEntry("session_id", "sess-jitter")
            .containsEntry("slot_released", true);
    }

    @Test
    @DisplayName("BLPOP timeout: cleanup is best-effort - abort POST throwing does NOT prevent slot release")
    void onTimeoutAbortFailureStillReleasesSlot() throws Exception {
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(205);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(listOps.leftPop(eq("agent:browser:result:job-flaky"), eq(Duration.ofSeconds(205))))
            .thenReturn(null);
        when(listOps.leftPop(eq("agent:browser:result:job-flaky"), eq(Duration.ofSeconds(1))))
            .thenReturn(null);
        when(valueOps.get(eq("agent:browser:job:job-flaky"))).thenReturn("sess-flaky");
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-flaky"));
        // Abort POST throws (e.g. network blip - ResourceAccessException
        // covered explicitly by tryAbortSession's WARN-log branch).
        when(restTemplate.postForObject(
                eq(SERVICE_URL + "/agent/sessions/sess-flaky/abort"), any(), eq(Map.class)))
            .thenThrow(new org.springframework.web.client.ResourceAccessException("Connection refused"));

        ToolExecutionContext ctx = mock(ToolExecutionContext.class);
        lenient().when(ctx.tenantId()).thenReturn("user-12");
        lenient().when(ctx.credentials()).thenReturn(Map.of());

        ToolExecutionResult res = module.execute("agent_browse",
            Map.of("task", "x", "llm", Map.of("provider", "openai", "model", "gpt-4o")),
            "user-12", ctx).orElseThrow();

        // Authoritative LREM ran even though the abort POST failed - this is
        // the whole point of doing slot release in Java instead of trusting
        // the runner's own finally-block.
        verify(listOps).remove(eq("agent:browser:user:user-12:concurrent"), eq(1L), eq("sess-flaky"));
        verify(redisTemplate).delete(eq("agent:browser:job:job-flaky"));
        // Result is still a structured failure - never propagates the abort exception.
        assertThat(res.success()).isFalse();
        assertThat(res.error())
            .contains("Browser agent_browse timed out after 205s")
            .contains("session_id=sess-flaky");
    }

    @Test
    @DisplayName("BLPOP timeout: race window 2 - runner LPUSHes during cleanup, post-cleanup re-poll returns SUCCESS")
    @SuppressWarnings("unchecked")
    void onTimeoutPostCleanupRePollRecoversLateResult() throws Exception {
        // First drain (round 1) returns null. Cleanup proceeds: abort, LREM,
        // DEL. Then the post-cleanup drain (round 2) finds a result the
        // runner LPUSHed mid-cleanup - must surface success despite having
        // already aborted.
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(205);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(listOps.leftPop(eq("agent:browser:result:job-window2"), eq(Duration.ofSeconds(205))))
            .thenReturn(null);
        // Round 1 (pre-cleanup, 1 s LPOP): null.
        when(listOps.leftPop(eq("agent:browser:result:job-window2"), eq(Duration.ofSeconds(1))))
            .thenReturn(null);
        // Round 2 (post-cleanup, 10 s LPOP) finds the runner's late COMPLETED result.
        when(listOps.leftPop(eq("agent:browser:result:job-window2"), eq(Duration.ofSeconds(10))))
            .thenReturn("{\"final_result\":\"finished mid-cleanup\",\"stop_reason\":\"COMPLETED\"}");
        when(valueOps.get(eq("agent:browser:job:job-window2"))).thenReturn("sess-window2");
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-window2"));

        ToolExecutionContext ctx = mock(ToolExecutionContext.class);
        lenient().when(ctx.tenantId()).thenReturn("user-w2");
        lenient().when(ctx.credentials()).thenReturn(Map.of());

        ToolExecutionResult res = module.execute("agent_browse",
            Map.of("task", "x", "llm", Map.of("provider", "openai", "model", "gpt-4o")),
            "user-w2", ctx).orElseThrow();

        // Cleanup ran (abort + LREM) - that's expected, the round-1 drain
        // came up empty so we couldn't have known the result was about to land.
        verify(listOps).remove(eq("agent:browser:user:user-w2:concurrent"), eq(1L), eq("sess-window2"));
        // Post-cleanup re-poll caught the late result → success returned.
        assertThat(res.success()).as("post-cleanup re-poll must surface late result").isTrue();
        Map<String, Object> data = (Map<String, Object>) res.data();
        assertThat(data).containsEntry("final_result", "finished mid-cleanup");
    }

    @Test
    @DisplayName("BLPOP timeout: LREM returning 0 (runner finally beat us) reports slot_released=false honestly")
    void onTimeoutLremRemovedZeroReportsSlotReleasedFalse() throws Exception {
        // Honesty contract: slot_released=true means WE LREM'd the entry.
        // If the runner's own finally-block already removed it (LREM count==0),
        // we should report false so observability dashboards distinguish
        // "we authoritatively released" from "we tried but it was already gone".
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(205);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(listOps.leftPop(eq("agent:browser:result:job-zero"), eq(Duration.ofSeconds(205))))
            .thenReturn(null);
        when(listOps.leftPop(eq("agent:browser:result:job-zero"), eq(Duration.ofSeconds(1))))
            .thenReturn(null);
        when(valueOps.get(eq("agent:browser:job:job-zero"))).thenReturn("sess-zero");
        // Runner's finally beat us → LREM finds nothing to remove
        when(listOps.remove(eq("agent:browser:user:user-z:concurrent"), eq(1L), eq("sess-zero")))
            .thenReturn(0L);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-zero"));

        ToolExecutionContext ctx = mock(ToolExecutionContext.class);
        lenient().when(ctx.tenantId()).thenReturn("user-z");
        lenient().when(ctx.credentials()).thenReturn(Map.of());

        ToolExecutionResult res = module.execute("agent_browse",
            Map.of("task", "x", "llm", Map.of("provider", "openai", "model", "gpt-4o")),
            "user-z", ctx).orElseThrow();

        assertThat(res.metadata())
            .as("LREM count==0 means runner already released - must NOT claim slot_released=true")
            .containsEntry("slot_released", false)
            .containsEntry("auto_aborted", true)
            .containsEntry("session_id", "sess-zero");
    }

    @Test
    @DisplayName("BLPOP timeout: runner reacts to ABORT and pushes CANCELLED recap → failure surfaces steps/pages/extracted_data to agent")
    @SuppressWarnings("unchecked")
    void onTimeoutCancelledRecapEnrichesFailure() throws Exception {
        // After our abort POST, the runner's _AbortRequested handler builds a
        // CANCELLED result with full session state and push_results it. The
        // round-2 drain (10 s) catches it and we surface a RICH failure
        // including step count, pages visited, last URL, and partial
        // extracted_data - so the agent knows what was attempted instead of
        // a flat "timed out, do not retry."
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(205);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(listOps.leftPop(eq("agent:browser:result:job-recap"), eq(Duration.ofSeconds(205))))
            .thenReturn(null);
        when(listOps.leftPop(eq("agent:browser:result:job-recap"), eq(Duration.ofSeconds(1))))
            .thenReturn(null);
        // Round 2 catches the runner's CANCELLED blob with rich session state
        when(listOps.leftPop(eq("agent:browser:result:job-recap"), eq(Duration.ofSeconds(10))))
            .thenReturn("{\"final_result\":\"Session aborted by user\","
                + "\"stop_reason\":\"CANCELLED\","
                + "\"final_url\":\"https://orizons.io/booking\","
                + "\"pages_visited\":[\"https://orizons.io\",\"https://orizons.io/booking\"],"
                + "\"steps\":[{\"step_index\":1},{\"step_index\":2},{\"step_index\":3}],"
                + "\"extracted_data\":{\"date_selected\":\"2026-04-30\",\"slot_text\":\"16h00\"}}");
        when(valueOps.get(eq("agent:browser:job:job-recap"))).thenReturn("sess-recap");
        when(listOps.remove(eq("agent:browser:user:user-r:concurrent"), eq(1L), eq("sess-recap")))
            .thenReturn(1L);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-recap"));

        ToolExecutionContext ctx = mock(ToolExecutionContext.class);
        lenient().when(ctx.tenantId()).thenReturn("user-r");
        lenient().when(ctx.credentials()).thenReturn(Map.of());

        ToolExecutionResult res = module.execute("agent_browse",
            Map.of("task", "x", "llm", Map.of("provider", "openai", "model", "gpt-4o")),
            "user-r", ctx).orElseThrow();

        // Failure (CANCELLED ≠ COMPLETED) - but enriched with the recap
        assertThat(res.success()).isFalse();
        assertThat(res.error())
            .contains("Browser agent_browse timed out after 205s")
            .contains("3 step(s) completed")
            .contains("2 page(s) visited")
            .contains("https://orizons.io/booking")
            .contains("Partial extracted_data available")
            .contains("session_id=sess-recap");
        assertThat(res.metadata())
            .containsEntry("recap_available", true)
            .containsEntry("recap_stop_reason", "CANCELLED")
            .containsEntry("steps_completed", 3)
            .containsEntry("pages_visited_count", 2)
            .containsEntry("final_url", "https://orizons.io/booking")
            .containsEntry("session_id", "sess-recap")
            .containsEntry("slot_released", true)
            .containsKey("partial_extracted_data")
            .containsKey("pages_visited");
        Map<String, Object> partial = (Map<String, Object>) res.metadata().get("partial_extracted_data");
        assertThat(partial)
            .containsEntry("date_selected", "2026-04-30")
            .containsEntry("slot_text", "16h00");
    }

    @Test
    @DisplayName("BLPOP timeout: no recap available (runner unresponsive to ABORT) → basic failure with recap_available=false")
    void onTimeoutNoRecapMarksRecapUnavailable() throws Exception {
        // Round 2 returns null → runner didn't react in time. We fall back
        // to the basic failure but explicitly mark recap_available=false so
        // observability can distinguish "rich failure" from "we tried but
        // got nothing back."
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(205);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(listOps.leftPop(eq("agent:browser:result:job-norecap"), eq(Duration.ofSeconds(205))))
            .thenReturn(null);
        when(listOps.leftPop(eq("agent:browser:result:job-norecap"), eq(Duration.ofSeconds(1))))
            .thenReturn(null);
        when(listOps.leftPop(eq("agent:browser:result:job-norecap"), eq(Duration.ofSeconds(10))))
            .thenReturn(null);  // runner unresponsive
        when(valueOps.get(eq("agent:browser:job:job-norecap"))).thenReturn("sess-norecap");
        when(listOps.remove(eq("agent:browser:user:user-nr:concurrent"), eq(1L), eq("sess-norecap")))
            .thenReturn(1L);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-norecap"));

        ToolExecutionContext ctx = mock(ToolExecutionContext.class);
        lenient().when(ctx.tenantId()).thenReturn("user-nr");
        lenient().when(ctx.credentials()).thenReturn(Map.of());

        ToolExecutionResult res = module.execute("agent_browse",
            Map.of("task", "x", "llm", Map.of("provider", "openai", "model", "gpt-4o")),
            "user-nr", ctx).orElseThrow();

        assertThat(res.success()).isFalse();
        assertThat(res.error())
            .contains("Browser agent_browse timed out after 205s")
            .contains("Slot released")
            .contains("session_id=sess-norecap")
            .doesNotContain("step(s) completed")
            .doesNotContain("page(s) visited");
        assertThat(res.metadata())
            .containsEntry("recap_available", false)
            .containsEntry("session_id", "sess-norecap")
            .containsEntry("slot_released", true)
            .doesNotContainKey("steps_completed")
            .doesNotContainKey("partial_extracted_data");
    }

    @Test
    @DisplayName("BLPOP timeout: recap path posts AgentObservabilityRequest with token cost so timed-out browser sessions still bill (regression: silent revenue leak)")
    @SuppressWarnings("unchecked")
    void onTimeoutCancelledRecapRecordsObservability() throws Exception {
        // Regression for the prod incident where 6/7 browser_agent calls
        // for tenant=1 timed out at 205s, all returned a CANCELLED recap
        // with full cost.by_model data, yet auth.credit_ledger had ZERO
        // BROWSER_AGENT_EXECUTION rows. Root cause: WebJobModule.submitAndAwait
        // returns the onSubmitTimeout result directly without invoking
        // postProcess, so applyPricingToResponse + recordObservabilityFromResult
        // were never called on the timeout path. This test pins the fix:
        // onSubmitTimeout MUST run both pricing and observability on the
        // recap blob before handing it to buildRecapFailure.
        AgentClient agentClient = org.mockito.Mockito.mock(AgentClient.class);
        BrowserAgentModule moduleWithObs = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, null, agentClient);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(205);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(listOps.leftPop(eq("agent:browser:result:job-bill"), eq(Duration.ofSeconds(205))))
            .thenReturn(null);
        when(listOps.leftPop(eq("agent:browser:result:job-bill"), eq(Duration.ofSeconds(1))))
            .thenReturn(null);
        // Round-2 drain returns a CANCELLED recap with cost.by_model populated
        // - the runner already burned 12k prompt + 800 completion tokens on
        // gpt-5.4 before our abort POST landed. Without the fix, those tokens
        // are billed at $0.
        when(listOps.leftPop(eq("agent:browser:result:job-bill"), eq(Duration.ofSeconds(10))))
            .thenReturn("{"
                + "\"final_result\":\"Session aborted by user\","
                + "\"stop_reason\":\"CANCELLED\","
                + "\"steps\":[{\"step_index\":1},{\"step_index\":2}],"
                + "\"cost\":{"
                + "  \"tokens_in\":12000,"
                + "  \"tokens_out\":800,"
                + "  \"llm_calls\":3,"
                + "  \"browser_seconds\":205.0,"
                + "  \"cost_usd\":0.0,"
                + "  \"by_model\":{\"gpt-5.4\":{"
                + "    \"prompt_tokens\":12000,"
                + "    \"completion_tokens\":800,"
                + "    \"cache_read_tokens\":0,"
                + "    \"cache_creation_tokens\":0,"
                + "    \"invocations\":3}}"
                + "}}");
        when(valueOps.get(eq("agent:browser:job:job-bill"))).thenReturn("sess-bill");
        when(listOps.remove(eq("agent:browser:user:tenant-1:concurrent"), eq(1L), eq("sess-bill")))
            .thenReturn(1L);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-bill"));

        ToolExecutionContext ctx = new ToolExecutionContext(
            "tenant-1",
            Map.of(),
            Map.of(), java.util.Set.of(),
            null, null, null, null
        );

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", new LinkedHashMap<>(Map.of("provider", "openai", "model", "gpt-5.4")));

        ToolExecutionResult res = moduleWithObs.execute("agent_browse",
            params, "tenant-1", ctx).orElseThrow();

        // Failure result still flows back to the agent (timeout-recap shape)
        assertThat(res.success()).isFalse();
        assertThat(res.metadata())
            .containsEntry("recap_available", true)
            .containsEntry("recap_stop_reason", "CANCELLED");

        // CRITICAL: observability MUST have been posted with the runner's
        // real token usage so agent-service writes a BROWSER_AGENT_EXECUTION
        // ledger row for the burned tokens.
        ArgumentCaptor<AgentObservabilityRequest> reqCaptor =
            ArgumentCaptor.forClass(AgentObservabilityRequest.class);
        verify(agentClient).recordObservability(reqCaptor.capture());
        AgentObservabilityRequest req = reqCaptor.getValue();
        assertThat(req.getTenantId()).isEqualTo("tenant-1");
        assertThat(req.getAgentType()).isEqualTo("browser_agent");
        assertThat(req.getProvider()).isEqualTo("openai");
        assertThat(req.getModel()).isEqualTo("gpt-5.4");
        assertThat(req.getPromptTokens()).isEqualTo(12000L);
        assertThat(req.getCompletionTokens()).isEqualTo(800L);
        assertThat(req.getTotalTokens()).isEqualTo(12800L);
        // CANCELLED recap maps to non-COMPLETED status (timeout-cancelled run).
        assertThat(req.getStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("BLPOP timeout: no recap → no observability call (nothing to bill, runner never reported tokens)")
    @SuppressWarnings("unchecked")
    void onTimeoutNoRecapDoesNotRecordObservability() throws Exception {
        // Counterpart to onTimeoutCancelledRecapRecordsObservability: when
        // the runner never publishes a recap (round-2 drain returns null),
        // there's no token data to bill. We must NOT post a fabricated
        // observability row - that would write a 0-token ledger entry that
        // looks like "successful free run" in the audit trail.
        AgentClient agentClient = org.mockito.Mockito.mock(AgentClient.class);
        BrowserAgentModule moduleWithObs = new BrowserAgentModule(
            restTemplate, config, redisTemplate, objectMapper,
            null, null, null, null, null, agentClient);

        when(config.getBrowserAgentBlpopTimeout()).thenReturn(205);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(listOps.leftPop(eq("agent:browser:result:job-norecap2"), eq(Duration.ofSeconds(205))))
            .thenReturn(null);
        when(listOps.leftPop(eq("agent:browser:result:job-norecap2"), eq(Duration.ofSeconds(1))))
            .thenReturn(null);
        when(listOps.leftPop(eq("agent:browser:result:job-norecap2"), eq(Duration.ofSeconds(10))))
            .thenReturn(null);
        when(valueOps.get(eq("agent:browser:job:job-norecap2"))).thenReturn("sess-norecap2");
        when(listOps.remove(eq("agent:browser:user:tenant-1:concurrent"), eq(1L), eq("sess-norecap2")))
            .thenReturn(1L);
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-norecap2"));

        ToolExecutionContext ctx = new ToolExecutionContext(
            "tenant-1",
            Map.of(),
            Map.of(), java.util.Set.of(),
            null, null, null, null
        );

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("task", "x");
        params.put("llm", new LinkedHashMap<>(Map.of("provider", "openai", "model", "gpt-5.4")));

        moduleWithObs.execute("agent_browse", params, "tenant-1", ctx).orElseThrow();

        // No recap = no token data = no ledger row.
        verify(agentClient, never()).recordObservability(any());
    }

    @Test
    @DisplayName("getBlpopTimeoutSeconds returns browserAgentBlpopTimeout (NOT generic blpopTimeout) - proves the timeout-ladder fix")
    void agentBrowseUsesShorterTimeoutOverride() throws Exception {
        // Verifies the timeout-ladder fix: orchestrator BLPOP MUST be < agent
        // client timeout (120 s on web_search). The default 150 s would let
        // the client give up first and orphan the slot.
        when(config.getBrowserAgentBlpopTimeout()).thenReturn(205);
        // Stub generic getBlpopTimeout to a sentinel value AND verify it's
        // never read - guards against silent regression where someone
        // re-introduces config.getBlpopTimeout() in the agent_browse path.
        lenient().when(config.getBlpopTimeout()).thenReturn(999);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(eq("agent:browser:result:job-cap"), eq(Duration.ofSeconds(205))))
            .thenReturn("{\"final_result\":\"OK\",\"stop_reason\":\"COMPLETED\"}");
        when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
            .thenReturn(Map.of("job_id", "job-cap"));

        ToolExecutionResult res = module.execute("agent_browse",
            Map.of("task", "x", "llm", Map.of("provider", "openai", "model", "gpt-4o")),
            null, null).orElseThrow();

        assertThat(res.success()).isTrue();
        // Critical: BLPOP was called with 100 s (override), NOT 150 s (generic).
        verify(listOps).leftPop(eq("agent:browser:result:job-cap"), eq(Duration.ofSeconds(205)));
        verify(listOps, never()).leftPop(anyString(), eq(Duration.ofSeconds(999)));
    }
}
