package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.config.WebSearchConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Billing-path tests for {@link WebFetchModule}. Companion to
 * {@link WebSearchModuleBillingTest}, mirrored for the WEB_FETCH debit path.
 *
 * <p>WebFetch differs from WebSearch in transport: it submits a job via
 * {@code POST /jobs/submit} (returns {@code job_id}) then BLPOPs the result
 * off Redis ({@link WebJobModule#submitAndAwait}). The post-success debit
 * ({@link WebFetchModule#chargeForFetch}) and the sourceId scope resolver
 * ({@link WebFetchModule#resolveBillingSourceId}) are the focus here.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebFetchModule billing")
class WebFetchModuleBillingTest {

    @Mock RestTemplate restTemplate;
    @Mock WebSearchConfig config;
    @Mock StringRedisTemplate redisTemplate;
    @Mock(answer = org.mockito.Answers.RETURNS_DEEP_STUBS) ListOperations<String, String> listOps;
    @Mock CreditConsumptionClient creditClient;

    @Mock ToolExecutionContext context;

    // Real ObjectMapper: the await step parses the Redis result JSON into a Map,
    // so a stubbed mapper would not exercise the real success payload.
    private final ObjectMapper objectMapper = new ObjectMapper();

    private WebFetchModule module;

    private static final String TENANT = "42";
    private static final String SERVICE_URL = "http://websearch:8085";
    private static final String JOB_ID = "job-123";
    private static final String RESULT_KEY = "fetch:result:" + JOB_ID;

    @BeforeEach
    void setUp() {
        lenient().when(config.getServiceUrl()).thenReturn(SERVICE_URL);
        lenient().when(config.getBlpopTimeout()).thenReturn(30);
        lenient().when(config.getMaxParallelFetches()).thenReturn(5);
        module = new WebFetchModule(restTemplate, config, redisTemplate, objectMapper, creditClient);
    }

    /**
     * Wire the full submit→await happy path: the submit POST returns a job_id,
     * and the Redis BLPOP returns {@code resultJson} for that job's result key.
     */
    private void stubSuccessfulFetch(String resultJson) {
        when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), any(), eq(Map.class)))
                .thenReturn(Map.of("job_id", JOB_ID));
        when(redisTemplate.opsForList()).thenReturn(listOps);
        when(listOps.leftPop(eq(RESULT_KEY), any(Duration.class))).thenReturn(resultJson);
    }

    @Nested
    @DisplayName("Happy path with billing")
    class HappyPath {

        @Test
        @DisplayName("successful fetch posts WEB_FETCH async debit with chat-scope sourceId")
        void postsAsyncDebitOnSuccessChatScope() {
            stubSuccessfulFetch("{\"content\":\"hello\"}");
            when(context.credentials()).thenReturn(Map.of(
                    "__streamId__", "stream-7",
                    "__toolCallId__", "tool-call-9"));

            Optional<ToolExecutionResult> out = module.execute(
                    "fetch", Map.of("url", "https://example.com"), TENANT, context);

            assertThat(out).isPresent();
            assertThat(out.get().success()).isTrue();
            verify(creditClient).consumeCreditsAsync(
                    eq(TENANT), eq("WEB_FETCH"),
                    eq("web-fetch:CHAT:stream-7:tool-call-9:0"),
                    eq("websearch"), eq("default"),
                    eq(0), eq(0));
        }

        @Test
        @DisplayName("workflow-scope sourceId when __workflowRunId__ + __toolCallId__ present")
        void postsAsyncDebitWorkflowScope() {
            stubSuccessfulFetch("{\"content\":\"hi\"}");
            when(context.credentials()).thenReturn(Map.of(
                    "__workflowRunId__", "run-abc",
                    "__toolCallId__", "tc-1"));

            module.execute("fetch", Map.of("url", "https://example.com"), TENANT, context);

            verify(creditClient).consumeCreditsAsync(
                    eq(TENANT), eq("WEB_FETCH"),
                    eq("web-fetch:RUN:run-abc:step:tc-1:0"),
                    eq("websearch"), eq("default"),
                    eq(0), eq(0));
        }

        @Test
        @DisplayName("missing identifiers → fallback UUID sourceId (debit still fired)")
        void fallbackUuidWhenIdsMissing() {
            stubSuccessfulFetch("{\"content\":\"x\"}");
            when(context.credentials()).thenReturn(Map.of()); // empty

            module.execute("fetch", Map.of("url", "https://example.com"), TENANT, context);

            verify(creditClient).consumeCreditsAsync(
                    eq(TENANT), eq("WEB_FETCH"),
                    argThat(s -> s != null && s.startsWith("web-fetch:FALLBACK:")),
                    eq("websearch"), eq("default"), eq(0), eq(0));
        }

        @Test
        @DisplayName("billing enqueue failure does not mask successful fetch result")
        void billingFailureDoesNotMaskSuccessfulFetch() {
            stubSuccessfulFetch("{\"content\":\"ok\"}");
            when(context.credentials()).thenReturn(Map.of(
                    "__streamId__", "stream-7",
                    "__toolCallId__", "tool-call-9"));
            doThrow(new IllegalStateException("missing org context")).when(creditClient)
                    .consumeCreditsAsync(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt());

            Optional<ToolExecutionResult> out = module.execute(
                    "fetch", Map.of("url", "https://example.com"), TENANT, context);

            // The RuntimeException from the debit is swallowed; the fetch result survives.
            assertThat(out).isPresent();
            assertThat(out.get().success()).isTrue();
            assertThat(out.get().data()).isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) out.get().data();
            assertThat(data).containsEntry("content", "ok");
        }
    }

    @Nested
    @DisplayName("Failure paths skip billing")
    class FailureSkipsBilling {

        @Test
        @DisplayName("unhandled action → Optional.empty, no submit, no debit")
        void unhandledActionSkipsEverything() {
            Optional<ToolExecutionResult> out = module.execute(
                    "search", Map.of("url", "https://example.com"), TENANT, context);

            assertThat(out).isEmpty();
            verifyNoInteractions(restTemplate);
            verifyNoInteractions(redisTemplate);
            verifyNoInteractions(creditClient);
        }

        @Test
        @DisplayName("submit returns no job_id → EXECUTION_FAILED, no debit")
        void noJobIdSkipsBilling() {
            when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), any(), eq(Map.class)))
                    .thenReturn(Map.of("unexpected", "shape"));

            Optional<ToolExecutionResult> out = module.execute(
                    "fetch", Map.of("url", "https://example.com"), TENANT, context);

            assertThat(out).isPresent();
            assertThat(out.get().success()).isFalse();
            assertThat(out.get().errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            verify(creditClient, never()).consumeCreditsAsync(
                    anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("BLPOP times out (null result) → EXECUTION_FAILED, no debit")
        void timeoutSkipsBilling() {
            when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), any(), eq(Map.class)))
                    .thenReturn(Map.of("job_id", JOB_ID));
            when(redisTemplate.opsForList()).thenReturn(listOps);
            when(listOps.leftPop(eq(RESULT_KEY), any(Duration.class))).thenReturn(null);

            Optional<ToolExecutionResult> out = module.execute(
                    "fetch", Map.of("url", "https://example.com"), TENANT, context);

            assertThat(out.get().success()).isFalse();
            assertThat(out.get().errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            verify(creditClient, never()).consumeCreditsAsync(
                    anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("result payload carries an error key → EXECUTION_FAILED, no debit")
        void errorPayloadSkipsBilling() {
            stubSuccessfulFetch("{\"error\":\"page blocked\"}");

            Optional<ToolExecutionResult> out = module.execute(
                    "fetch", Map.of("url", "https://example.com"), TENANT, context);

            assertThat(out.get().success()).isFalse();
            assertThat(out.get().errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            verify(creditClient, never()).consumeCreditsAsync(
                    anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("submit throws → EXECUTION_FAILED (caught), no debit")
        void submitExceptionSkipsBilling() {
            when(restTemplate.postForObject(eq(SERVICE_URL + "/jobs/submit"), any(), eq(Map.class)))
                    .thenThrow(new RestClientException("connection refused"));

            Optional<ToolExecutionResult> out = module.execute(
                    "fetch", Map.of("url", "https://example.com"), TENANT, context);

            assertThat(out.get().success()).isFalse();
            assertThat(out.get().errorCode()).isEqualTo(ToolErrorCode.EXECUTION_FAILED);
            verify(creditClient, never()).consumeCreditsAsync(
                    anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt());
        }
    }

    @Nested
    @DisplayName("Tenant resolution")
    class TenantResolution {

        @Test
        @DisplayName("null tenantId argument falls back to context tenantId and still bills")
        void nullTenantArgumentUsesContextTenantForDebit() {
            when(context.tenantId()).thenReturn(TENANT);
            when(context.credentials()).thenReturn(Map.of(
                    "__streamId__", "stream-7",
                    "__toolCallId__", "tool-call-9"));
            stubSuccessfulFetch("{\"content\":\"x\"}");

            Optional<ToolExecutionResult> out = module.execute(
                    "fetch", Map.of("url", "https://example.com"), null, context);

            assertThat(out.get().success()).isTrue();
            verify(creditClient).consumeCreditsAsync(
                    eq(TENANT), eq("WEB_FETCH"),
                    eq("web-fetch:CHAT:stream-7:tool-call-9:0"),
                    eq("websearch"), eq("default"),
                    eq(0), eq(0));
        }

        @Test
        @DisplayName("missing tenant in both argument and context skips debit but keeps result")
        void missingTenantSkipsDebit() {
            when(context.tenantId()).thenReturn(null);
            stubSuccessfulFetch("{\"content\":\"x\"}");

            Optional<ToolExecutionResult> out = module.execute(
                    "fetch", Map.of("url", "https://example.com"), null, context);

            assertThat(out.get().success()).isTrue();
            verify(creditClient, never()).consumeCreditsAsync(
                    anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt());
        }
    }

    @Nested
    @DisplayName("resolveBillingSourceId helper")
    class ResolveSourceId {

        @Test
        @DisplayName("workflow scope wins over chat scope when both identifier sets present")
        void workflowScopeTakesPrecedence() {
            String id = WebFetchModule.resolveBillingSourceId(Map.of(
                    "__streamId__", "s",
                    "__toolCallId__", "t",
                    "__workflowRunId__", "r"));
            assertThat(id).isEqualTo("web-fetch:RUN:r:step:t:0");
        }

        @Test
        @DisplayName("chat scope when only __streamId__ + __toolCallId__ present")
        void chatScopeWhenNoRunId() {
            String id = WebFetchModule.resolveBillingSourceId(Map.of(
                    "__streamId__", "s",
                    "__toolCallId__", "t"));
            assertThat(id).isEqualTo("web-fetch:CHAT:s:t:0");
        }

        @Test
        @DisplayName("null credentials → fallback UUID sourceId")
        void nullCredentials() {
            String id = WebFetchModule.resolveBillingSourceId(null);
            assertThat(id).startsWith("web-fetch:FALLBACK:");
        }

        @Test
        @DisplayName("toolCallId present but neither streamId nor runId → fallback")
        void partialIdentifiersFallBack() {
            String id = WebFetchModule.resolveBillingSourceId(Map.of("__toolCallId__", "t"));
            assertThat(id).startsWith("web-fetch:FALLBACK:");
        }
    }
}
