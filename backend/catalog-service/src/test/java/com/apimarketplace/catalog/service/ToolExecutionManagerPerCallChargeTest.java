package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.repository.ToolNextHintRepository;
import com.apimarketplace.catalog.service.billing.CatalogToolBillingService;
import com.apimarketplace.catalog.service.execution.BinaryResponseHandler;
import com.apimarketplace.catalog.service.execution.OutputProjector;
import com.apimarketplace.catalog.service.execution.ToolExecutionOrchestrator;
import com.apimarketplace.catalog.service.relay.CeCatalogCloudRelay;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.credit.SourceIdBuilder;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.PlatformCredentialLookupDto;
import com.apimarketplace.credential.client.dto.ResolvedScopeMarkupDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Every dispatched call is its own charge.
 *
 * <p><b>The defect.</b> The ledger has a unique index on {@code source_id} and
 * {@code CreditService.tryReserveMarkup} short-circuits a key it has already
 * seen with "reservation already exists, idempotent success" and a debit of
 * ZERO. The source id was built from {@code callIndex / epoch / spawn /
 * iteration / itemIndex}, and no caller in the codebase ever set any of them:
 * {@code CatalogExecuteModule} and {@code GenerationExecutionService} send the
 * three scope headers and nothing else, so all five defaulted to 0 on every
 * call. Two generations in one chat turn, or ten iterations of a
 * {@code agent:generate} node in one loop, therefore all reserved the SAME key -
 * and exactly one of them was ever charged.
 *
 * <p><b>What is asserted here.</b> Not "reserve was called N times" - it always
 * was, and every one after the first returned success while debiting nothing.
 * The fake ledger below reproduces that fast path, so these tests measure what
 * the user was actually billed. On the pre-change code they report one charge
 * for N calls.
 */
@DisplayName("ToolExecutionManager - one charge per dispatched call")
class ToolExecutionManagerPerCallChargeTest {

    private static final String TOOL_SLUG = "seedance/create-video-task";
    private static final UUID TOOL_ID = UUID.randomUUID();
    private static final UUID API_ID = UUID.randomUUID();
    private static final String USER_ID = "7";
    private static final BigDecimal VIDEO_PRICE = new BigDecimal("300");

    private ApiService apiService;
    private CredentialClient credentialClient;
    private ApiRepository apiRepository;
    private ApiToolRepository apiToolRepository;
    private CreditConsumptionClient creditClient;

    private ToolExecutionManager manager;

    /**
     * The auth-side ledger, reduced to the one behaviour that decides the money:
     * a {@code source_id} it has already seen is answered as an idempotent
     * success with a ZERO debit, exactly as {@code CreditService} does. Without
     * this the test could not tell "charged twice" from "charged once and
     * silently deduplicated".
     */
    private final Set<String> reservedSourceIds = new HashSet<>();
    private final List<String> committedSourceIds = new ArrayList<>();
    private BigDecimal totalDebited = BigDecimal.ZERO;

    @BeforeEach
    void setUp() {
        ToolContextService toolContextService = mock(ToolContextService.class);
        apiService = mock(ApiService.class);
        ResponseShaper responseShaper = mock(ResponseShaper.class);
        NextActionBuilder nextActionBuilder = mock(NextActionBuilder.class);
        ResponseCache responseCache = mock(ResponseCache.class);
        ToolNextHintRepository toolNextHintRepository = mock(ToolNextHintRepository.class);
        ToolResponseService toolResponseService = mock(ToolResponseService.class);
        credentialClient = mock(CredentialClient.class);
        CeCatalogCloudRelay ceCatalogCloudRelay = mock(CeCatalogCloudRelay.class);
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
        // The response cache is off for RUN scope and would otherwise serve the
        // second chat call from the first - the charge, not the cache, is what
        // is under test here.
        lenient().when(responseCache.get(anyString(), any())).thenReturn(null);

        ApiEntity api = new ApiEntity();
        api.setId(API_ID);
        api.setApiSlug("seedance");
        api.setApiName("Seedance");
        api.setIconSlug("seedance");
        api.setPlatformCredentialName("seedance");
        lenient().when(apiRepository.findById(API_ID)).thenReturn(Optional.of(api));
        lenient().when(apiRepository.findByApiSlug("seedance")).thenReturn(Optional.of(api));

        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(TOOL_ID);
        tool.setApiId(API_ID);
        tool.setToolSlug("create-video-task");
        tool.setGenerationSpec("{\"kind\":\"video\"}");
        lenient().when(apiToolRepository.findById(TOOL_ID)).thenReturn(Optional.of(tool));
        lenient().when(apiToolRepository.findByApiIdAndToolSlug(API_ID, "create-video-task"))
                .thenReturn(Optional.of(tool));

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

        wireFakeLedger();

        lenient().when(apiService.executeApiTool(anyString(), anyString(), any(JsonNode.class),
                        anySet(), anyString()))
                .thenAnswer(inv -> {
                    Map<String, Object> success = new LinkedHashMap<>();
                    success.put("success", true);
                    success.put("data", Map.of("task_id", UUID.randomUUID().toString()));
                    success.put("credentialSource", "platform");
                    return success;
                });

        ObjectMapper objectMapper = new ObjectMapper();
        CatalogToolBillingService realBilling = new CatalogToolBillingService(
                creditClient, credentialClient, apiToolRepository, apiRepository, true);
        manager = new ToolExecutionManager(
                toolContextService, apiService, objectMapper, responseShaper,
                nextActionBuilder, responseCache, toolNextHintRepository, toolResponseService,
                new ToolExecutionOrchestrator(new OutputProjector(objectMapper)),
                new BinaryResponseHandler(objectMapper),
                realBilling, credentialClient, apiRepository, ceCatalogCloudRelay);
        ReflectionTestUtils.setField(manager, "apiToolRepoForSlug", apiToolRepository);
    }

    private void wireFakeLedger() {
        lenient().when(creditClient.scopeReserve(any(), anyString(), any(), any(), any(), any(),
                        anyInt(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> {
                    String sourceId = inv.getArgument(1);
                    BigDecimal projected = inv.getArgument(4);
                    if (!reservedSourceIds.add(sourceId)) {
                        // The idempotency fast-path: success, zero debit. This is
                        // what turned every generation after the first into a
                        // free one.
                        return new CreditConsumptionClient.ScopeReserveResult(true, null, false, null);
                    }
                    totalDebited = totalDebited.add(projected);
                    return new CreditConsumptionClient.ScopeReserveResult(true, null, false, null);
                });
        lenient().when(creditClient.scopeCommit(anyString(), any(), any(), any()))
                .thenAnswer(inv -> {
                    String sourceId = inv.getArgument(0);
                    if (committedSourceIds.contains(sourceId)) {
                        return "ALREADY_COMMITTED";
                    }
                    committedSourceIds.add(sourceId);
                    return "COMMITTED";
                });
    }

    // ── surfaces ────────────────────────────────────────────────────────────

    /** One {@code generation(create)} from chat: STREAM scope, no step id. */
    private void chatCreate() {
        manager.executeTool(TOOL_SLUG,
                ToolExecutionRequest.builder()
                        .parameters(Map.of("prompt", "a cat", "duration_seconds", 10))
                        .credentialSource("platform")
                        .platformCredentialId(42L)
                        .billingScopeKind("STREAM")
                        .billingScopeId("stream-1")
                        .build(),
                USER_ID, null, "req-" + UUID.randomUUID());
    }

    /** One execution of a {@code agent:generate} node: RUN scope, same step id every time. */
    private void loopIteration() {
        manager.executeTool(TOOL_SLUG,
                ToolExecutionRequest.builder()
                        .parameters(Map.of("prompt", "a cat", "duration_seconds", 10))
                        .credentialSource("platform")
                        .platformCredentialId(42L)
                        .billingScopeKind("RUN")
                        .billingScopeId("run-1")
                        .billingStepId("agent:generate")
                        .build(),
                USER_ID, null, "req-" + UUID.randomUUID());
    }

    // ── the defect ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("two generations in one chat turn")
    class TwoCreatesInOneStream {

        @Test
        @DisplayName("reserve under DIFFERENT source ids, and BOTH are debited")
        void bothAreCharged() {
            chatCreate();
            chatCreate();

            // The money: pre-change this pair cost 300 in total, because the
            // second reserve hit the ledger's "already exists" fast path and
            // debited nothing.
            assertThat(totalDebited).isEqualByComparingTo(VIDEO_PRICE.multiply(BigDecimal.valueOf(2)));
            // And the reason: two distinct keys, not one key reserved twice.
            assertThat(reservedSourceIds).hasSize(2);
            assertThat(committedSourceIds).hasSize(2).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("keep the chat key shape, so the run and stream families still never collide")
        void keepsTheStreamShape() {
            chatCreate();

            String sourceId = reservedSourceIds.iterator().next();
            assertThat(sourceId).startsWith(
                    SourceIdBuilder.MARKUP_DEBIT_PREFIX + ":STREAM:stream-1:" + TOOL_SLUG + ":");
            assertThat(SourceIdBuilder.isMarkupDebit(sourceId)).isTrue();
        }
    }

    @Nested
    @DisplayName("a workflow loop running the same node ten times")
    class LoopIterations {

        @Test
        @DisplayName("charges ten times, not once: the run and step are identical every iteration, "
                + "so only the per-call reference separates them")
        void tenIterationsAreTenCharges() {
            for (int i = 0; i < 10; i++) {
                loopIteration();
            }

            assertThat(totalDebited).isEqualByComparingTo(VIDEO_PRICE.multiply(BigDecimal.valueOf(10)));
            assertThat(reservedSourceIds).hasSize(10);
            assertThat(committedSourceIds).hasSize(10).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("keeps the workflow key shape, so per-run ledger aggregation still matches on prefix")
        void keepsTheRunShape() {
            loopIteration();

            String sourceId = reservedSourceIds.iterator().next();
            assertThat(sourceId).startsWith(
                    SourceIdBuilder.MARKUP_DEBIT_PREFIX + ":RUN:run-1:step:agent:generate:");
            assertThat(SourceIdBuilder.isMarkupDebit(sourceId)).isTrue();
        }
    }

    @Nested
    @DisplayName("the per-call reference")
    class CallReference {

        /**
         * It decides whether a call is charged, so it belongs in the same class
         * as the generation quantity: derived on this side, never accepted from
         * the wire. The DTO fields that used to carry it were plain
         * deserializable JSON.
         */
        @Test
        @DisplayName("cannot be pinned by the caller: two calls sending an identical body still reserve "
                + "under different ids")
        void isNotCallerControlled() {
            ToolExecutionRequest identicalBody = ToolExecutionRequest.builder()
                    .parameters(Map.of("prompt", "a cat", "duration_seconds", 10))
                    .credentialSource("platform")
                    .platformCredentialId(42L)
                    .billingScopeKind("RUN")
                    .billingScopeId("run-1")
                    .billingStepId("agent:generate")
                    .build();

            // Same request instance, same X-Request-Id, twice: nothing the
            // caller controls can make the two collide.
            manager.executeTool(TOOL_SLUG, identicalBody, USER_ID, null, "req-fixed");
            manager.executeTool(TOOL_SLUG, identicalBody, USER_ID, null, "req-fixed");

            assertThat(totalDebited).isEqualByComparingTo(VIDEO_PRICE.multiply(BigDecimal.valueOf(2)));
            assertThat(reservedSourceIds).hasSize(2);
        }
    }
}
