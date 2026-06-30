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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Credential-state versioning of the agent response-cache key.
 *
 * <p>Regression for the 2026-06-11 prod bug: a user connected a new Gmail
 * account and set it as default, then re-asked the chat agent within the
 * 5-minute cache TTL - the agent was served the OLD account's cached
 * response ({@code cached=true}) with no credential resolution at all,
 * making "set as default" look ignored. The fix keys the cache on an opaque
 * credential-state version fetched from auth-service, so any credential
 * mutation lands subsequent calls on a fresh key.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolExecutionManager - credential-state-versioned cache key")
class ToolExecutionManagerCacheKeyTest {

    private static final String TOOL = "gmail/gmail-get-profile";
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

    /** In-memory stand-in for Redis so cache hit/miss behavior is real per key. */
    private final Map<String, Object> cacheStore = new HashMap<>();
    /** Counts real upstream executions (cache misses). */
    private final AtomicInteger upstreamCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        manager = new ToolExecutionManager(
                toolContextService, apiService, new ObjectMapper(), responseShaper,
                nextActionBuilder, responseCache, toolNextHintRepository, toolResponseService,
                toolExecutionOrchestrator, binaryResponseHandler, catalogBillingService,
                credentialClient, apiRepository);

        ToolContextService.ToolContext context = new ToolContextService.ToolContext();
        context.setApiId(API_ID);
        context.setToolName("get_profile");
        when(toolContextService.loadToolContext(TOOL)).thenReturn(Optional.of(context));

        lenient().when(apiService.executeApiTool(anyString(), anyString(), any(), any(), anyString()))
                .thenAnswer(inv -> {
                    upstreamCalls.incrementAndGet();
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("success", true);
                    result.put("data", Map.of("emailAddress", "account-" + upstreamCalls.get() + "@gmail.com"));
                    return result;
                });

        // Redis stand-in: same (key) → same entry; params are identical across calls.
        lenient().when(responseCache.get(anyString(), anyMap()))
                .thenAnswer(inv -> cacheStore.get(inv.getArgument(0, String.class)));
        lenient().doAnswer(inv -> {
            cacheStore.put(inv.getArgument(0, String.class), inv.getArgument(2));
            return null;
        }).when(responseCache).put(anyString(), anyMap(), any());

        lenient().when(responseShaper.shape(any(), any(), any(), any()))
                .thenAnswer(inv -> new ResponseShaper.ShapingResult(
                        inv.getArgument(0), List.of(), ResponseShaper.Action.UNTOUCHED, 1, 1));
        lenient().when(nextActionBuilder.build(any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(binaryResponseHandler.dehydrateInlineBase64(any(), anyString(), anyString()))
                .thenReturn(null);
    }

    private ToolExecutionResponse execute(String stateVersion) {
        lenient().when(credentialClient.getCredentialStateVersion(USER)).thenReturn(stateVersion);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .parameters(Map.of("userId", "me"))
                .billingScopeKind("STREAM")
                .billingScopeId("stream-1")
                .build();
        return manager.executeTool(TOOL, request, USER, "org-1", "req-1");
    }

    @Test
    @DisplayName("REGRESSION 2026-06-11: a credential mutation (state-version change) within the TTL busts the cache - the agent gets a fresh execution, not the old account's response")
    void credentialSwitchWithinTtlForcesFreshExecution() {
        // Same credentials state twice → second call is a legitimate cache hit.
        ToolExecutionResponse first = execute("2:1000");
        ToolExecutionResponse second = execute("2:1000");
        assertThat(upstreamCalls.get()).isEqualTo(1);
        assertThat(first.getMetadata()).doesNotContainKey("cached");
        assertThat(second.getMetadata()).containsEntry("cached", true);

        // User connects a new account / sets a new default → version moves.
        // Pre-fix the key was toolId+userId only, so this call returned the
        // cached OLD-account response; post-fix it must re-execute upstream.
        ToolExecutionResponse afterSwitch = execute("3:2000");
        assertThat(upstreamCalls.get())
                .as("a credential-state change must invalidate the agent response cache")
                .isEqualTo(2);
        assertThat(afterSwitch.getMetadata()).doesNotContainKey("cached");
    }

    @Test
    @DisplayName("cache key embeds the credential-state version (distinct versions → distinct keys, both sides of the switch stay independently cached)")
    void distinctVersionsUseDistinctKeys() {
        execute("1:100");
        execute("9:900");

        assertThat(cacheStore.keySet())
                .containsExactlyInAnyOrder(
                        TOOL + ":" + USER + ":1:100",
                        TOOL + ":" + USER + ":9:900");
    }

    @Test
    @DisplayName("workflow callers (RUN scope) bypass the cache and pay no state-version lookup")
    void workflowCallersSkipStateVersionLookup() {
        ToolExecutionRequest runRequest = ToolExecutionRequest.builder()
                .parameters(Map.of("userId", "me"))
                .billingScopeKind("RUN")
                .billingScopeId("run-1")
                .build();
        manager.executeTool(TOOL, runRequest, USER, "org-1", "req-1");

        verify(credentialClient, never()).getCredentialStateVersion(anyString());
        verify(responseCache, never()).get(anyString(), anyMap());
        verify(responseCache, never()).put(anyString(), anyMap(), any());
        assertThat(upstreamCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("auth-service outage (version=na) fails open: caching keeps working under the stable fallback key")
    void unavailableVersionKeepsCacheFunctional() {
        execute(CredentialClient.STATE_VERSION_UNAVAILABLE);
        ToolExecutionResponse second = execute(CredentialClient.STATE_VERSION_UNAVAILABLE);

        assertThat(upstreamCalls.get()).isEqualTo(1);
        assertThat(second.getMetadata()).containsEntry("cached", true);
        verify(responseCache, times(2)).get(anyString(), anyMap());
    }
}
