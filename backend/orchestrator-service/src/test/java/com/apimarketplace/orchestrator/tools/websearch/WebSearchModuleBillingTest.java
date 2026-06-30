package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.config.WebSearchConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Billing-path tests for {@link WebSearchModule}. Companion to
 * {@link WebSearchModuleTimeRangeTest} which covers the param normaliser.
 *
 * <p>Focus: post-success async debit and sourceId scope resolution for the
 * existing web-search tool.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WebSearchModule billing")
class WebSearchModuleBillingTest {

    @Mock RestTemplate restTemplate;
    @Mock WebSearchConfig config;
    @Mock CreditConsumptionClient creditClient;

    @Mock ToolExecutionContext context;

    private WebSearchModule module;

    @BeforeEach
    void setUp() {
        // Spring's @Component requires a real RestTemplate; mocking is fine because
        // the module never inspects the interceptor list outside the constructor log.
        when(restTemplate.getInterceptors()).thenReturn(List.of());
        lenient().when(config.getServiceUrl()).thenReturn("http://websearch:8085");
        module = new WebSearchModule(restTemplate, config, creditClient);
        // Drop the constructor's getInterceptors() call so per-test
        // verifyNoInteractions(restTemplate) is meaningful.
        clearInvocations(restTemplate);
    }

    private static final String TENANT = "42";

    @Nested
    @DisplayName("No model-pricing pre-flight")
    class PricingGate {

        @Test
        @DisplayName("webSearchCallsServiceWithoutModelPricingGate")
        void webSearchCallsServiceWithoutModelPricingGate() {
            when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                    .thenReturn(Map.of("results", List.of()));
            when(context.credentials()).thenReturn(Map.of(
                    "__streamId__", "stream-7",
                    "__toolCallId__", "tool-call-9"));

            Optional<ToolExecutionResult> out = module.execute(
                    "search", Map.of("query", "anything"), TENANT, context);

            assertThat(out).isPresent();
            ToolExecutionResult r = out.get();
            assertThat(r.success()).isTrue();
            verify(creditClient, never()).hasPricing(anyString(), anyString());
            verify(restTemplate).postForObject(eq("http://websearch:8085/search"), any(), eq(Map.class));
        }
    }

    @Nested
    @DisplayName("Happy path with billing")
    class HappyPath {

        @Test
        @DisplayName("successful search posts WEB_SEARCH async debit with chat-scope sourceId")
        void postsAsyncDebitOnSuccessChatScope() {
            when(restTemplate.postForObject(
                    eq("http://websearch:8085/search"), any(), eq(Map.class)))
                    .thenReturn(Map.of("results", List.of()));
            when(context.credentials()).thenReturn(Map.of(
                    "__streamId__", "stream-7",
                    "__toolCallId__", "tool-call-9"));

            Optional<ToolExecutionResult> out = module.execute(
                    "search", Map.of("query", "java"), TENANT, context);

            assertThat(out).isPresent();
            assertThat(out.get().success()).isTrue();
            verify(creditClient).consumeCreditsAsync(
                    eq(TENANT), eq("WEB_SEARCH"),
                    eq("web-search:CHAT:stream-7:tool-call-9:0"),
                    eq("websearch"), eq("default"),
                    eq(0), eq(0));
        }

        @Test
        @DisplayName("workflow-scope sourceId when only __workflowRunId__ + __toolCallId__ present")
        void postsAsyncDebitWorkflowScope() {
            when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                    .thenReturn(Map.of("results", List.of()));
            when(context.credentials()).thenReturn(Map.of(
                    "__workflowRunId__", "run-abc",
                    "__toolCallId__", "tc-1"));

            module.execute("search", Map.of("query", "java"), TENANT, context);

            verify(creditClient).consumeCreditsAsync(
                    eq(TENANT), eq("WEB_SEARCH"),
                    eq("web-search:RUN:run-abc:step:tc-1:0"),
                    eq("websearch"), eq("default"),
                    eq(0), eq(0));
        }

        @Test
        @DisplayName("missing identifiers → fallback UUID sourceId (logged WARN, debit still fired)")
        void fallbackUuidWhenIdsMissing() {
            when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                    .thenReturn(Map.of("results", List.of()));
            when(context.credentials()).thenReturn(Map.of()); // empty

            module.execute("search", Map.of("query", "x"), TENANT, context);

            verify(creditClient).consumeCreditsAsync(
                    eq(TENANT), eq("WEB_SEARCH"),
                    argThat(s -> s != null && s.startsWith("web-search:FALLBACK:")),
                    eq("websearch"), eq("default"), eq(0), eq(0));
        }

        @Test
        @DisplayName("billing enqueue failure does not mask successful search result")
        void billingFailureDoesNotMaskSuccessfulSearch() {
            when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                    .thenReturn(Map.of("results", List.of(Map.of("title", "ok"))));
            when(context.credentials()).thenReturn(Map.of(
                    "__streamId__", "stream-7",
                    "__toolCallId__", "tool-call-9"));
            doThrow(new IllegalStateException("missing org context")).when(creditClient)
                    .consumeCreditsAsync(anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt());

            Optional<ToolExecutionResult> out = module.execute(
                    "search", Map.of("query", "java"), TENANT, context);

            assertThat(out).isPresent();
            assertThat(out.get().success()).isTrue();
            assertThat(out.get().data()).isEqualTo(Map.of("results", List.of(Map.of("title", "ok"))));
        }
    }

    @Nested
    @DisplayName("Failure paths skip billing")
    class FailureSkipsBilling {

        @Test
        @DisplayName("SearXNG throws → EXTERNAL_SERVICE_ERROR, no debit")
        void searxngExceptionSkipsBilling() {
            when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                    .thenThrow(new RestClientException("connection refused"));

            Optional<ToolExecutionResult> out = module.execute(
                    "search", Map.of("query", "x"), TENANT, context);

            assertThat(out).isPresent();
            assertThat(out.get().success()).isFalse();
            assertThat(out.get().errorCode()).isEqualTo(ToolErrorCode.EXTERNAL_SERVICE_ERROR);
            verify(creditClient, never()).consumeCreditsAsync(
                    anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("SearXNG returns null body → EXECUTION_FAILED, no debit")
        void searxngNullSkipsBilling() {
            when(restTemplate.postForObject(anyString(), any(), eq(Map.class))).thenReturn(null);

            Optional<ToolExecutionResult> out = module.execute(
                    "search", Map.of("query", "x"), TENANT, context);

            assertThat(out.get().success()).isFalse();
            verify(creditClient, never()).consumeCreditsAsync(
                    anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("missing query param → MISSING_PARAMETER, no pricing call, no debit")
        void missingQuerySkipsEverything() {
            Optional<ToolExecutionResult> out = module.execute(
                    "search", Map.of(), TENANT, context);

            assertThat(out.get().success()).isFalse();
            assertThat(out.get().errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
            verifyNoInteractions(creditClient);
            verifyNoInteractions(restTemplate);
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
            when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                    .thenReturn(Map.of("results", List.of()));

            Optional<ToolExecutionResult> out = module.execute(
                    "search", Map.of("query", "x"), null, context);

            assertThat(out.get().success()).isTrue();
            verify(creditClient).consumeCreditsAsync(
                    eq(TENANT), eq("WEB_SEARCH"),
                    eq("web-search:CHAT:stream-7:tool-call-9:0"),
                    eq("websearch"), eq("default"),
                    eq(0), eq(0));
        }

        @Test
        @DisplayName("missing tenant in argument and context skips debit")
        void missingTenantSkipsDebit() {
            when(restTemplate.postForObject(anyString(), any(), eq(Map.class)))
                    .thenReturn(Map.of("results", List.of()));

            Optional<ToolExecutionResult> out = module.execute(
                    "search", Map.of("query", "x"), null, context);

            assertThat(out.get().success()).isTrue();
            verify(creditClient, never()).consumeCreditsAsync(
                    anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt());
        }
    }

    @Nested
    @DisplayName("resolveBillingSourceId helper")
    class ResolveSourceId {

        @Test
        @DisplayName("workflow scope wins over chat scope when both present")
        void workflowScopeTakesPrecedence() {
            String id = WebSearchModule.resolveBillingSourceId(Map.of(
                    "__streamId__", "s",
                    "__toolCallId__", "t",
                    "__workflowRunId__", "r"));
            assertThat(id).isEqualTo("web-search:RUN:r:step:t:0");
        }

        @Test
        @DisplayName("null credentials → fallback")
        void nullCredentials() {
            String id = WebSearchModule.resolveBillingSourceId(null);
            assertThat(id).startsWith("web-search:FALLBACK:");
        }
    }
}
