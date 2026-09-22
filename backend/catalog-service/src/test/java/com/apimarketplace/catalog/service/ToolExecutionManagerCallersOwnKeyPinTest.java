package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.repository.ToolNextHintRepository;
import com.apimarketplace.catalog.service.billing.CatalogToolBillingService;
import com.apimarketplace.catalog.service.exception.InsufficientCreditsException;
import com.apimarketplace.catalog.service.execution.BinaryResponseHandler;
import com.apimarketplace.catalog.service.execution.OutputProjector;
import com.apimarketplace.catalog.service.execution.ToolExecutionOrchestrator;
import com.apimarketplace.catalog.service.http.CredentialModeContext;
import com.apimarketplace.catalog.service.relay.CeCatalogCloudRelay;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.PlatformCredentialLookupDto;
import com.apimarketplace.credential.client.dto.ResolvedScopeMarkupDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The relaxation that lets a caller with no credits keep working on their OWN
 * provider key, and the money hole it opened.
 *
 * <p><b>The defect.</b> When the credit reservation was refused, the pre-flight
 * asked "does this user have a credential of their own for this integration?"
 * and, if so, allowed the call unbilled with a null sourceId - so nothing was
 * held and {@code settleReservation} had nothing to commit. But which pool
 * ANSWERS is decided later and elsewhere: the agentic path tries the caller's
 * key first and falls back to the PLATFORM credential the moment that key fails,
 * stamping {@code credentialSource="platform"} on the response. A user with zero
 * credits and a revoked seedance key could therefore call
 * {@code generation(create, model='seedance-2.0', duration_seconds=10)} from
 * chat, have the reservation refused, have their own key answer 401, have the
 * PLATFORM key produce a 300-credit video, and receive it with no ledger row at
 * all. Repeatable for as long as they cared to repeat it.
 *
 * <p><b>What is asserted here.</b> Not "the relaxation branch was taken" - the
 * old tests already did that, with {@code givenOrdinaryEndpoint()} and a mocked
 * billing service, and they passed while this was broken. These tests run the
 * REAL {@link CatalogToolBillingService} and stub the provider call with the
 * REAL credential-pool rule ({@code HttpExecutionService}: the platform fallback
 * applies unless the explicit source is {@code "user"}), then assert the money
 * outcome: a platform-funded success must never come back uncharged.
 */
@DisplayName("ToolExecutionManager - a call allowed only because the caller has their own key")
class ToolExecutionManagerCallersOwnKeyPinTest {

    private static final String TOOL_SLUG = "seedance/create-video-task";
    private static final UUID TOOL_ID = UUID.randomUUID();
    private static final UUID API_ID = UUID.randomUUID();
    private static final String USER_ID = "7";
    private static final BigDecimal VIDEO_PRICE = new BigDecimal("300");

    private ToolContextService toolContextService;
    private ApiService apiService;
    private ResponseShaper responseShaper;
    private NextActionBuilder nextActionBuilder;
    private ResponseCache responseCache;
    private ToolNextHintRepository toolNextHintRepository;
    private ToolResponseService toolResponseService;
    private CredentialClient credentialClient;
    private CeCatalogCloudRelay ceCatalogCloudRelay;
    private ApiRepository apiRepository;
    private ApiToolRepository apiToolRepository;
    private CreditConsumptionClient creditClient;

    private ToolExecutionManager manager;

    /** Explicit credential source in force at the moment the provider was dispatched. */
    private final List<String> explicitSourceAtDispatch = new ArrayList<>();

    @BeforeEach
    void setUp() {
        toolContextService = mock(ToolContextService.class);
        apiService = mock(ApiService.class);
        responseShaper = mock(ResponseShaper.class);
        nextActionBuilder = mock(NextActionBuilder.class);
        responseCache = mock(ResponseCache.class);
        toolNextHintRepository = mock(ToolNextHintRepository.class);
        toolResponseService = mock(ToolResponseService.class);
        credentialClient = mock(CredentialClient.class);
        ceCatalogCloudRelay = mock(CeCatalogCloudRelay.class);
        apiRepository = mock(ApiRepository.class);
        apiToolRepository = mock(ApiToolRepository.class);
        creditClient = mock(CreditConsumptionClient.class);

        ToolContextService.ToolContext context = mock(ToolContextService.ToolContext.class);
        lenient().when(context.getToolId()).thenReturn(TOOL_ID.toString());
        lenient().when(context.getApiId()).thenReturn(API_ID.toString());
        lenient().when(context.getToolName()).thenReturn("create_video_task");
        lenient().when(context.getAllowedParameterNames()).thenReturn(Set.of());
        lenient().when(toolContextService.loadToolContext(TOOL_SLUG)).thenReturn(Optional.of(context));

        lenient().when(ceCatalogCloudRelay.tryRelay(anyString(), any(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(credentialClient.getCredentialStateVersion(anyString()))
                .thenReturn(CredentialClient.STATE_VERSION_UNAVAILABLE);
        lenient().when(responseShaper.shape(any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> new ResponseShaper.ShapingResult(
                        inv.getArgument(0), List.of(), ResponseShaper.Action.UNTOUCHED, 0, 0));
        lenient().when(nextActionBuilder.build(any(), any(), any())).thenReturn(Optional.empty());

        ApiEntity api = new ApiEntity();
        api.setId(API_ID);
        api.setApiSlug("seedance");
        api.setApiName("Seedance");
        api.setIconSlug("seedance");
        api.setPlatformCredentialName("seedance");
        lenient().when(apiRepository.findById(API_ID)).thenReturn(Optional.of(api));
        lenient().when(apiRepository.findByApiSlug("seedance")).thenReturn(Optional.of(api));

        givenResoldGenerationEndpoint();

        PlatformCredentialLookupDto credential = new PlatformCredentialLookupDto();
        credential.setFound(true);
        credential.setId(42L);
        credential.setProviderKind("cloud");
        lenient().when(credentialClient.findPlatformCredentialByName("seedance"))
                .thenReturn(Optional.of(credential));

        ResolvedScopeMarkupDto price = new ResolvedScopeMarkupDto();
        price.setFound(true);
        price.setPinId(1L);
        price.setPricingVersionId(1L);
        price.setEffectiveMarkup(VIDEO_PRICE);
        lenient().when(credentialClient.resolveScopeMarkupRate(anyString(), anyString(), anyLong(),
                anyLong(), any(UUID.class), any(), any(), any())).thenReturn(Optional.of(price));

        ObjectMapper objectMapper = new ObjectMapper();
        CatalogToolBillingService realBilling = new CatalogToolBillingService(
                creditClient, credentialClient, apiToolRepository, apiRepository, true);
        manager = new ToolExecutionManager(
                toolContextService, apiService, objectMapper, responseShaper, nextActionBuilder,
                responseCache, toolNextHintRepository, toolResponseService,
                new ToolExecutionOrchestrator(new OutputProjector(objectMapper)),
                new BinaryResponseHandler(objectMapper),
                realBilling, credentialClient, apiRepository, ceCatalogCloudRelay);
        ReflectionTestUtils.setField(manager, "apiToolRepoForSlug", apiToolRepository);

        CredentialModeContext.clear();
    }

    @AfterEach
    void clearThreadState() {
        CredentialModeContext.clear();
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private void givenResoldGenerationEndpoint() {
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(TOOL_ID);
        tool.setApiId(API_ID);
        tool.setToolSlug("create-video-task");
        tool.setGenerationSpec("{\"kind\":\"video\"}");
        lenient().when(apiToolRepository.findById(TOOL_ID)).thenReturn(Optional.of(tool));
        lenient().when(apiToolRepository.findByApiIdAndToolSlug(API_ID, "create-video-task"))
                .thenReturn(Optional.of(tool));
    }

    private void givenOrdinaryEndpoint() {
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(TOOL_ID);
        tool.setApiId(API_ID);
        tool.setToolSlug("create-video-task");
        // No generation descriptor: one of the 1000+ ordinary catalog endpoints.
        when(apiToolRepository.findById(TOOL_ID)).thenReturn(Optional.of(tool));
        when(apiToolRepository.findByApiIdAndToolSlug(API_ID, "create-video-task"))
                .thenReturn(Optional.of(tool));
    }

    private void givenReservationRefused() {
        when(creditClient.scopeReserve(any(), any(), any(), any(), any(), any(),
                anyInt(), any(), any(), anyBoolean()))
                .thenReturn(new CreditConsumptionClient.ScopeReserveResult(
                        false, "Insufficient credits", false, BigDecimal.ZERO));
    }

    private void givenCallerHasTheirOwnSeedanceKey() {
        when(credentialClient.getConfiguredIntegrations(USER_ID)).thenReturn(Set.of("seedance"));
    }

    /**
     * The provider stub, wired to the REAL credential-pool rule rather than a
     * fixed answer. {@code HttpExecutionService} resolves the caller's
     * credential first and falls back to the platform's unless the explicit
     * source pins it to {@code "user"} (see {@code tryGetCredentialResolution}
     * and the 401-retry's {@code platformFallbackAllowed}). This models the
     * caller's key being present but REVOKED, which is the ordinary way to end
     * up on the platform pool without asking for it.
     */
    private void givenTheCallersKeyIsRevokedAndThePlatformKeyWorks() {
        when(apiService.executeApiTool(anyString(), anyString(), any(JsonNode.class), anySet(), anyString()))
                .thenAnswer(inv -> {
                    String explicit = CredentialModeContext.getExplicitSource();
                    explicitSourceAtDispatch.add(explicit);
                    if ("user".equals(explicit)) {
                        // No platform fallback is permitted: the revoked key is
                        // the final answer and nothing is produced.
                        Map<String, Object> failure = new LinkedHashMap<>();
                        failure.put("success", false);
                        failure.put("status", 401);
                        failure.put("error", "Authentication expired for seedance. Please reconnect your account.");
                        return failure;
                    }
                    // Agentic fallback: the PLATFORM key answers and a paid
                    // video is produced on the platform owner's provider quota.
                    Map<String, Object> success = new LinkedHashMap<>();
                    success.put("success", true);
                    success.put("data", Map.of("task_id", "t-1", "video_url", "https://cdn/video.mp4"));
                    success.put("credentialSource", "platform");
                    return success;
                });
    }

    private void givenTheCallersOwnKeyWorks() {
        when(apiService.executeApiTool(anyString(), anyString(), any(JsonNode.class), anySet(), anyString()))
                .thenAnswer(inv -> {
                    explicitSourceAtDispatch.add(CredentialModeContext.getExplicitSource());
                    Map<String, Object> success = new LinkedHashMap<>();
                    success.put("success", true);
                    success.put("data", Map.of("task_id", "t-1"));
                    success.put("credentialSource", "user");
                    return success;
                });
    }

    /** A chat-scoped generation with no explicit credential choice: the agentic shape. */
    private ToolExecutionResponse executeAgentic() {
        return manager.executeTool(TOOL_SLUG,
                ToolExecutionRequest.builder()
                        .parameters(Map.of("prompt", "a cat", "duration_seconds", 10))
                        .billingScopeKind("STREAM")
                        .billingScopeId("stream-1")
                        .build(),
                USER_ID, null, "req-1");
    }

    /**
     * The invariant the whole feature exists for, stated once: nothing that the
     * PLATFORM key paid for may come back without a committed charge.
     */
    private void assertNoPlatformFundedFreebie(ToolExecutionResponse response) {
        boolean platformAnswered = response.getMetadata() != null
                && "platform".equals(response.getMetadata().get("credentialSource"));
        boolean charged = org.mockito.Mockito.mockingDetails(creditClient).getInvocations().stream()
                .anyMatch(i -> "scopeCommit".equals(i.getMethod().getName()));
        assertThat(response.isSuccess() && platformAnswered && !charged)
                .as("a platform-funded asset was delivered with no ledger row")
                .isFalse();
    }

    // ── the defect ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("a resold generation whose reservation was refused")
    class ResoldGeneration {

        @Test
        @DisplayName("does NOT fall through to the platform key when the caller's own key fails: "
                + "no video is produced, and none is given away")
        void revokedOwnKeyDoesNotBecomeAFreePlatformVideo() {
            givenReservationRefused();
            givenCallerHasTheirOwnSeedanceKey();
            givenTheCallersKeyIsRevokedAndThePlatformKeyWorks();

            ToolExecutionResponse response = executeAgentic();

            // The money first: on the pre-change code this call came back
            // success=true with credentialSource=platform and no commit - a
            // 300-credit video handed over for nothing.
            assertNoPlatformFundedFreebie(response);
            verify(creditClient, never()).scopeCommit(any(), any(), any(), any());
            // Why it cannot happen: the dispatch went out with the caller's
            // pool pinned, so the platform credential was not reachable at all.
            assertThat(explicitSourceAtDispatch).containsExactly("user");
            assertThat(response.isSuccess()).isFalse();
            assertThat(response.getError()).contains("Authentication expired");
        }

        @Test
        @DisplayName("still runs unbilled when the caller's own key works, which is the whole point "
                + "of the relaxation")
        void theirOwnWorkingKeyStillRunsFree() {
            givenReservationRefused();
            givenCallerHasTheirOwnSeedanceKey();
            givenTheCallersOwnKeyWorks();

            ToolExecutionResponse response = executeAgentic();

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getMetadata()).containsEntry("credentialSource", "user");
            verify(creditClient, never()).scopeCommit(any(), any(), any(), any());
            assertNoPlatformFundedFreebie(response);
        }

        @Test
        @DisplayName("is refused outright when the caller has NO key of their own, so the platform key "
                + "is never reached")
        void noOwnKeyIsStillARefusal() {
            givenReservationRefused();
            when(credentialClient.getConfiguredIntegrations(USER_ID)).thenReturn(Set.of("gmail"));

            assertThatThrownBy(ToolExecutionManagerCallersOwnKeyPinTest.this::executeAgentic)
                    .isInstanceOf(InsufficientCreditsException.class)
                    .hasMessageContaining("Insufficient credits");

            verify(apiService, never()).executeApiTool(anyString(), anyString(),
                    any(JsonNode.class), anySet(), anyString());
        }
    }

    @Nested
    @DisplayName("the same relaxation on an ordinary endpoint")
    class OrdinaryEndpoint {

        /**
         * The hole is not specific to generations - it is the same mechanism on
         * every one of the 1000+ endpoints that carry a published markup, just
         * cheaper per occurrence. Fixing only the expensive half would leave the
         * next report to be the same bug wearing a different hat.
         */
        @Test
        @DisplayName("is pinned to the caller's key too, so a markup-priced call never lands on the "
                + "platform credential unbilled")
        void ordinaryEndpointIsPinnedAsWell() {
            givenOrdinaryEndpoint();
            givenReservationRefused();
            givenCallerHasTheirOwnSeedanceKey();
            givenTheCallersKeyIsRevokedAndThePlatformKeyWorks();

            ToolExecutionResponse response = executeAgentic();

            assertNoPlatformFundedFreebie(response);
            assertThat(explicitSourceAtDispatch).containsExactly("user");
            assertThat(response.isSuccess()).isFalse();
        }

        @Test
        @DisplayName("is untouched when the reservation SUCCEEDS: no pin, the agentic fallback stays "
                + "available and the call is billed")
        void aSuccessfulReservationIsNotPinned() {
            givenOrdinaryEndpoint();
            when(creditClient.scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean()))
                    .thenReturn(new CreditConsumptionClient.ScopeReserveResult(true, null, false, null));
            givenTheCallersKeyIsRevokedAndThePlatformKeyWorks();

            ToolExecutionResponse response = executeAgentic();

            // No pin: the platform fallback is exactly as available as before.
            assertThat(explicitSourceAtDispatch).containsExactly((String) null);
            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getMetadata()).containsEntry("credentialSource", "platform");
            verify(creditClient).scopeCommit(any(), any(), any(), any());
            assertNoPlatformFundedFreebie(response);
        }
    }

    @Nested
    @DisplayName("the pin itself")
    class PinHygiene {

        /**
         * The holder is thread-bound and the thread returns to a pool. A pin
         * left behind would silently deny the platform credential to whatever
         * call landed on that thread next - a different, quieter bug.
         */
        @Test
        @DisplayName("does not outlive the call that asked for it")
        void doesNotLeakToTheNextCallOnThisThread() {
            givenReservationRefused();
            givenCallerHasTheirOwnSeedanceKey();
            givenTheCallersKeyIsRevokedAndThePlatformKeyWorks();

            executeAgentic();

            assertThat(CredentialModeContext.getExplicitSource()).isNull();
        }

        @Test
        @DisplayName("is not applied when the caller explicitly asked for the platform key: that call is "
                + "refused, not quietly rerouted")
        void anExplicitPlatformChoiceIsRefusedNotRerouted() {
            givenReservationRefused();
            givenCallerHasTheirOwnSeedanceKey();

            assertThatThrownBy(() -> manager.executeTool(TOOL_SLUG,
                    ToolExecutionRequest.builder()
                            .parameters(Map.of("prompt", "a cat"))
                            .credentialSource("platform")
                            .platformCredentialId(42L)
                            .billingScopeKind("STREAM")
                            .billingScopeId("stream-1")
                            .build(),
                    USER_ID, null, "req-explicit"))
                    .isInstanceOf(InsufficientCreditsException.class);

            verify(apiService, never()).executeApiTool(anyString(), anyString(),
                    any(JsonNode.class), anySet(), anyString());
        }
    }
}
