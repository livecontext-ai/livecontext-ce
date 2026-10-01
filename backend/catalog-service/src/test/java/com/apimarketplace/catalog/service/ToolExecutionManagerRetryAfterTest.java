package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ToolNextHintRepository;
import com.apimarketplace.catalog.service.billing.CatalogToolBillingService;
import com.apimarketplace.catalog.service.execution.BinaryResponseHandler;
import com.apimarketplace.catalog.service.execution.ToolExecutionOrchestrator;
import com.apimarketplace.credential.client.CredentialClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * The platform never re-sends a refused provider call, so how long the provider asked the caller to
 * wait is the one thing the caller needs back: a workflow node's retry and an agent's wait read it
 * from {@code metadata.retryAfterSeconds}. Without this forwarding the value dies inside the catalog.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolExecutionManager - forwards the provider's Retry-After to the caller")
class ToolExecutionManagerRetryAfterTest {

    private static final String TOOL = "openai-admin-cost/openai-admin-cost-get-costs";
    private static final String USER = "user-1";

    @Mock private ToolContextService toolContextService;
    @Mock private ApiService apiService;
    @Mock private ResponseShaper responseShaper;
    @Mock private NextActionBuilder nextActionBuilder;
    @Mock private ResponseCache responseCache;
    @Mock private ToolNextHintRepository toolNextHintRepository;
    @Mock private ToolResponseService toolResponseService;
    @Mock private ToolExecutionOrchestrator toolExecutionOrchestrator;
    @Mock private BinaryResponseHandler binaryResponseHandler;
    @Mock private CatalogToolBillingService catalogBillingService;
    @Mock private CredentialClient credentialClient;
    @Mock private ApiRepository apiRepository;

    private ToolExecutionManager manager;

    @BeforeEach
    void setUp() {
        manager = new ToolExecutionManager(
                toolContextService, apiService, new ObjectMapper(), responseShaper,
                nextActionBuilder, responseCache, toolNextHintRepository, toolResponseService,
                toolExecutionOrchestrator, binaryResponseHandler, catalogBillingService,
                credentialClient, apiRepository, /* ceCatalogCloudRelay */ null);

        ToolContextService.ToolContext context = new ToolContextService.ToolContext();
        context.setApiId(UUID.randomUUID().toString());
        context.setToolName("get_costs");
        when(toolContextService.loadToolContext(TOOL)).thenReturn(Optional.of(context));

        lenient().when(responseShaper.shape(any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> new ResponseShaper.ShapingResult(
                        inv.getArgument(0), List.of(), ResponseShaper.Action.UNTOUCHED, 1, 1));
        lenient().when(nextActionBuilder.build(any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(binaryResponseHandler.dehydrateInlineBase64(any(), anyString(), anyString()))
                .thenReturn(null);
    }

    private ToolExecutionResponse executeAnswering(Map<String, Object> providerResult) {
        when(apiService.executeApiTool(anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(providerResult);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("start_time", 1))
                .build();
        return manager.executeTool(TOOL, request, USER, "org-1", "req-1");
    }

    private static Map<String, Object> refusal(int status, Long retryAfterSeconds) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", false);
        result.put("status", status);
        result.put("httpStatus", Map.of("code", status, "error", "slow down"));
        result.put("data", Map.of());
        result.put("error", "slow down");
        if (retryAfterSeconds != null) {
            result.put("retryAfterSeconds", retryAfterSeconds);
        }
        return result;
    }

    @Test
    @DisplayName("a 429 with a Retry-After reaches the caller as metadata.retryAfterSeconds")
    void forwardsTheWait() {
        ToolExecutionResponse response = executeAnswering(refusal(429, 42L));

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMetadata()).containsEntry("retryAfterSeconds", 42L);
    }

    @Test
    @DisplayName("a refusal without a Retry-After carries no invented wait")
    void noHeaderNoWait() {
        ToolExecutionResponse response = executeAnswering(refusal(429, null));

        assertThat(response.getMetadata()).doesNotContainKey("retryAfterSeconds");
    }
}
