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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An endpoint that DRAINS what it returns is never answered from the cache.
 *
 * <p>Driven through the real execution path rather than the predicate alone, because the
 * predicate was never the thing that was wrong: what matters is that the cache is not READ and
 * not WRITTEN for such an endpoint, and a test of the boolean would stay green if the call
 * site stopped consulting it.
 *
 * <p>The failure this pins is what Telegram's {@code getUpdates} did in production: 313 calls
 * over three days, every one answering {@code {"ok":true,"result":[]}}, most of them in under
 * 40ms without reaching Telegram at all, because the first call in each window had drained the
 * queue and every later one replayed the empty batch. A poller reporting an empty inbox
 * forever, and not one error anywhere.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolExecutionManager - an endpoint that consumes what it returns is not cached")
class ToolExecutionManagerConsumingReadTest {

    private static final String TOOL = "telegram/telegram-get-updates";
    private static final String USER = "agent-user";
    private static final String API_ID = UUID.randomUUID().toString();

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
    private final Map<String, Object> cacheStore = new HashMap<>();
    private final AtomicInteger upstreamCalls = new AtomicInteger();

    private void setUpWith(String executionSpecJson) {
        manager = new ToolExecutionManager(
                toolContextService, apiService, new ObjectMapper(), responseShaper,
                nextActionBuilder, responseCache, toolNextHintRepository, toolResponseService,
                toolExecutionOrchestrator, binaryResponseHandler, catalogBillingService,
                credentialClient, apiRepository, null);

        ToolContextService.ToolContext context = new ToolContextService.ToolContext();
        context.setApiId(API_ID);
        context.setToolName("get_updates");
        context.setExecutionSpecJson(executionSpecJson);
        when(toolContextService.loadToolContext(TOOL)).thenReturn(Optional.of(context));

        lenient().when(apiService.executeApiTool(anyString(), anyString(), any(), any(), anyString()))
                .thenAnswer(inv -> {
                    int n = upstreamCalls.incrementAndGet();
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("success", true);
                    // A real queue: the first read drains it, the second sees what arrived since.
                    result.put("data", Map.of("ok", true, "batch", n));
                    return result;
                });

        lenient().when(responseCache.get(anyString(), anyMap()))
                .thenAnswer(inv -> cacheStore.get(inv.getArgument(0, String.class)));
        lenient().doAnswer(inv -> {
            cacheStore.put(inv.getArgument(0, String.class), inv.getArgument(2));
            return null;
        }).when(responseCache).put(anyString(), anyMap(), any());

        lenient().when(responseShaper.shape(any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> new ResponseShaper.ShapingResult(
                        inv.getArgument(0), List.of(), ResponseShaper.Action.UNTOUCHED, 1, 1));
        lenient().when(nextActionBuilder.build(any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(binaryResponseHandler.dehydrateInlineBase64(any(), anyString(), anyString()))
                .thenReturn(null);
        lenient().when(credentialClient.getCredentialStateVersion(USER)).thenReturn("1:100");
    }

    private ToolExecutionResponse execute() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("limit", 20))
                .billingScopeKind("STREAM")
                .billingScopeId("stream-1")
                .build();
        return manager.executeTool(TOOL, request, USER, "org-1", "req-1");
    }

    @BeforeEach
    void resetCounters() {
        cacheStore.clear();
        upstreamCalls.set(0);
    }

    @Test
    @DisplayName("cacheable:false: every call reaches the provider, and the cache is neither read nor written")
    void consumingReadAlwaysReachesTheProvider() {
        setUpWith("{\"mode\":\"sync\",\"cacheable\":false,\"response\":{\"type\":\"json\"}}");

        ToolExecutionResponse first = execute();
        ToolExecutionResponse second = execute();

        assertThat(upstreamCalls.get())
                .as("a replayed batch hands back updates that were already consumed")
                .isEqualTo(2);
        assertThat(first.getMetadata()).doesNotContainKey("cached");
        assertThat(second.getMetadata()).doesNotContainKey("cached");
        verify(responseCache, never()).get(anyString(), anyMap());
        verify(responseCache, never()).put(anyString(), anyMap(), any());
    }

    /**
     * The control. Without it, a test that skipped the cache for every endpoint would pass the
     * one above and take the cache off the whole catalog, which is the opposite failure and a
     * far more expensive one.
     */
    @Test
    @DisplayName("the same call on an ordinary endpoint is still served from the cache")
    void ordinaryReadIsStillCached() {
        setUpWith("{\"mode\":\"sync\",\"response\":{\"type\":\"json\"}}");

        execute();
        ToolExecutionResponse second = execute();

        assertThat(upstreamCalls.get()).isEqualTo(1);
        assertThat(second.getMetadata()).containsEntry("cached", true);
    }
}
