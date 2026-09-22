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
import com.apimarketplace.catalog.service.relay.CeCatalogCloudRelay;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.PlatformCredentialLookupDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The reservation has to happen BEFORE the provider is called, or it is not a
 * guard at all.
 *
 * <p>The bug these tests pin: billing ran only after the upstream HTTP call
 * returned, so a refusal was recorded on a generation that had already been
 * produced, stored and handed to the customer. The platform owner paid the
 * third-party provider and charged nothing. Every test here fails on that
 * version of the code, because none of the calls it asserts on existed.
 *
 * <p>Second concern, equally important: the 1000+ ordinary catalog endpoints
 * carry no published platform price. They must keep behaving exactly as before,
 * so "no price resolved" is asserted to be a plain pass-through with nothing
 * reserved, nothing committed and nothing released.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ToolExecutionManager - reserve / commit / release lifecycle")
class ToolExecutionManagerBillingLifecycleTest {

    private static final String TOOL_SLUG = "seedance/create-video-task";
    private static final UUID TOOL_ID = UUID.randomUUID();
    private static final UUID API_ID = UUID.randomUUID();
    private static final String USER_ID = "7";
    private static final String SOURCE_ID = "markup_debit:run-1:step-1:0:0:0:0:0";
    private static final BigDecimal RESERVED = new BigDecimal("600");

    @Mock private ToolContextService toolContextService;
    @Mock private ApiService apiService;
    @Mock private ResponseShaper responseShaper;
    @Mock private NextActionBuilder nextActionBuilder;
    @Mock private ResponseCache responseCache;
    @Mock private ToolNextHintRepository toolNextHintRepository;
    @Mock private ToolResponseService toolResponseService;
    @Mock private CredentialClient credentialClient;
    @Mock private CeCatalogCloudRelay ceCatalogCloudRelay;
    @Mock private CatalogToolBillingService billingService;
    @Mock private ApiRepository apiRepository;
    @Mock private ApiToolRepository apiToolRepository;

    private ToolExecutionManager executionManager;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        executionManager = new ToolExecutionManager(
                toolContextService, apiService, objectMapper, responseShaper, nextActionBuilder,
                responseCache, toolNextHintRepository, toolResponseService,
                new ToolExecutionOrchestrator(new OutputProjector(objectMapper)),
                new BinaryResponseHandler(objectMapper),
                billingService, credentialClient, apiRepository, ceCatalogCloudRelay);
        // Field-injected (@Autowired(required=false)) rather than constructor
        // injected; without it the composite slug cannot be built and no scope
        // would ever be produced.
        ReflectionTestUtils.setField(executionManager, "apiToolRepoForSlug", apiToolRepository);

        ToolContextService.ToolContext context = mock(ToolContextService.ToolContext.class);
        lenient().when(context.getToolId()).thenReturn(TOOL_ID.toString());
        lenient().when(context.getApiId()).thenReturn(API_ID.toString());
        lenient().when(context.getToolName()).thenReturn("create_video_task");
        lenient().when(context.getEndpoint()).thenReturn("/api/v3/contents/generations/tasks");
        lenient().when(context.getHttpMethod()).thenReturn("POST");
        lenient().when(context.getAllowedParameterNames()).thenReturn(Set.of());
        lenient().when(toolContextService.loadToolContext(TOOL_SLUG)).thenReturn(Optional.of(context));

        lenient().when(ceCatalogCloudRelay.tryRelay(anyString(), any(), any(), any()))
                .thenReturn(Optional.empty());
        lenient().when(credentialClient.getCredentialStateVersion(anyString()))
                .thenReturn(CredentialClient.STATE_VERSION_UNAVAILABLE);
        lenient().when(responseShaper.shape(any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(inv -> new ResponseShaper.ShapingResult(
                        inv.getArgument(0), java.util.List.of(),
                        ResponseShaper.Action.UNTOUCHED, 0, 0));
        lenient().when(nextActionBuilder.build(any(), any(), any())).thenReturn(Optional.empty());

        ApiEntity api = new ApiEntity();
        api.setId(API_ID);
        api.setApiSlug("seedance");
        api.setApiName("Seedance");
        api.setPlatformCredentialName("seedance");
        lenient().when(apiRepository.findById(API_ID)).thenReturn(Optional.of(api));
        lenient().when(apiRepository.findByApiSlug("seedance")).thenReturn(Optional.of(api));

        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(TOOL_ID);
        tool.setApiId(API_ID);
        tool.setToolSlug("create-video-task");
        tool.setGenerationSpec("{\"kind\":\"video\"}");
        lenient().when(apiToolRepository.findById(TOOL_ID)).thenReturn(Optional.of(tool));
        // Slug -> UUID resolution, used only by the real billing service wired
        // up in NoBillingScopeEndToEnd; harmless for the mocked-billing tests.
        lenient().when(apiToolRepository.findByApiIdAndToolSlug(API_ID, "create-video-task"))
                .thenReturn(Optional.of(tool));

        PlatformCredentialLookupDto credential = new PlatformCredentialLookupDto();
        credential.setFound(true);
        credential.setId(42L);
        credential.setProviderKind("cloud");
        lenient().when(credentialClient.findPlatformCredentialByName("seedance"))
                .thenReturn(Optional.of(credential));
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** A workflow-scoped call on the platform key: the shape that gets billed. */
    private ToolExecutionRequest platformRequest() {
        return ToolExecutionRequest.builder()
                .parameters(Map.of("prompt", "a cat"))
                .credentialSource("platform")
                .platformCredentialId(42L)
                .billingScopeKind("RUN")
                .billingScopeId("run-1")
                .billingStepId("step-1")
                .build();
    }

    private void givenReservationTaken() {
        when(billingService.preflightReserve(any()))
                .thenReturn(CatalogToolBillingService.PreflightDecision
                        .allowedWithReservation(SOURCE_ID, RESERVED));
    }

    private void givenNothingToBill() {
        when(billingService.preflightReserve(any()))
                .thenReturn(CatalogToolBillingService.PreflightDecision.allowedWithoutBilling());
    }

    /**
     * Replace the shared fixture's endpoint with one that carries NO generation
     * descriptor.
     *
     * <p>The class-wide fixture is a seedance video endpoint, so every call it
     * serves is a generation. A test that says "an ordinary tool" has to say so
     * to the catalog as well, or it is asserting ordinary-tool behaviour while
     * the code correctly sees a generation.
     */
    /**
     * A REAL descriptor on the fixture's endpoint, so the body can be measured.
     *
     * <p>The class-wide fixture ships `{"kind":"video"}`, which parses but declares no model: the
     * measurement finds nothing to price and falls back to what the request carried, which is the
     * right behaviour and the wrong fixture for testing that the body wins.
     */
    private void givenAGenerationEndpoint() {
        ApiToolEntity tiered = new ApiToolEntity();
        tiered.setId(TOOL_ID);
        tiered.setApiId(API_ID);
        tiered.setToolSlug("create-video-task");
        tiered.setGenerationSpec("""
                {
                  "kind": "video", "modelParam": "model", "assetPath": "content.video_url",
                  "paramMap": { "prompt": "content[0].text", "duration_seconds": "duration",
                                "resolution": "resolution" },
                  "models": [{
                    "id": "vid-tiered", "upstream": "vendor-tiered", "label": "Tiered",
                    "capabilities": ["prompt", "duration_seconds", "resolution"],
                    "required": ["resolution"],
                    "constraints": { "resolution": { "allowed": ["720p", "1080p"] } },
                    "price": { "unit": "second", "unitCredits": 60,
                               "modifiers": [{ "param": "resolution",
                                               "multiply": { "720p": 1, "1080p": 2 } }] }
                  }]
                }
                """);
        when(apiToolRepository.findById(TOOL_ID)).thenReturn(Optional.of(tiered));
    }

    private void givenAnOrdinaryEndpoint() {
        ApiToolEntity ordinary = new ApiToolEntity();
        ordinary.setId(TOOL_ID);
        ordinary.setApiId(API_ID);
        ordinary.setToolSlug("current-weather");
        when(apiToolRepository.findById(TOOL_ID)).thenReturn(Optional.of(ordinary));
    }

    private void givenUpstreamReturns(Map<String, Object> result) {
        when(apiService.executeApiTool(anyString(), anyString(), any(JsonNode.class), anySet(), anyString()))
                .thenReturn(result);
    }

    private static Map<String, Object> upstreamSuccess(String credentialSource) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("data", Map.of("task_id", "t-1"));
        if (credentialSource != null) {
            result.put("credentialSource", credentialSource);
        }
        return result;
    }

    private ToolExecutionResponse execute() {
        return executionManager.executeTool(TOOL_SLUG, platformRequest(), USER_ID, null, "req-1");
    }

    // ── the defect ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("a refused reservation")
    class RefusedReservation {

        @Test
        @DisplayName("never calls the provider, so nothing is generated that cannot be billed")
        void neverCallsTheProvider() {
            when(billingService.preflightReserve(any()))
                    .thenReturn(CatalogToolBillingService.PreflightDecision
                            .refused("Insufficient credits", false));

            assertThatThrownBy(this::executeRefused)
                    .isInstanceOf(InsufficientCreditsException.class)
                    .hasMessageContaining("Insufficient credits");

            // The whole point of the change: pre-fix, the provider had already
            // produced (and charged for) the asset by the time billing ran.
            verify(apiService, never()).executeApiTool(anyString(), anyString(),
                    any(JsonNode.class), anySet(), anyString());
            verify(billingService, never()).commitOnSuccess(any(), any(), any(), any());
            verify(billingService, never()).releaseOnFailure(any(), any());
            // A refusal must not be served out of the response cache either.
            verify(responseCache, never()).get(anyString(), anyMap());
        }

        @Test
        @DisplayName("carries the delinquent flag so the surface can say 'top up to resume'")
        void carriesTheDelinquentFlag() {
            when(billingService.preflightReserve(any()))
                    .thenReturn(CatalogToolBillingService.PreflightDecision
                            .refused("account delinquent - top up to resume", true));

            assertThatThrownBy(this::executeRefused)
                    .isInstanceOf(InsufficientCreditsException.class)
                    .extracting(e -> ((InsufficientCreditsException) e).isDelinquent())
                    .isEqualTo(true);
        }

        private void executeRefused() {
            execute();
        }
    }

    @Nested
    @DisplayName("a successful call")
    class SuccessfulCall {

        @Test
        @DisplayName("reserves BEFORE the provider is called, then commits once")
        void reservesThenCommitsOnce() {
            givenReservationTaken();
            givenUpstreamReturns(upstreamSuccess("platform"));

            ToolExecutionResponse response = execute();

            assertThat(response.isSuccess()).isTrue();
            InOrder order = inOrder(billingService, apiService);
            order.verify(billingService).preflightReserve(any());
            order.verify(apiService).executeApiTool(anyString(), anyString(),
                    any(JsonNode.class), anySet(), anyString());
            order.verify(billingService).commitOnSuccess(eq(SOURCE_ID), eq(RESERVED),
                    eq("Seedance"), eq("create_video_task"));
            verify(billingService, times(1)).commitOnSuccess(any(), any(), any(), any());
            verify(billingService, never()).releaseOnFailure(any(), any());
        }

        @Test
        @DisplayName("reserves with a TTL that outlives the async-poll ceiling, or the credits vanish mid-generation")
        void reservesWithATtlThatCoversALongGeneration() {
            givenReservationTaken();
            givenUpstreamReturns(upstreamSuccess("platform"));

            execute();

            ArgumentCaptor<CatalogToolBillingService.BillingScope> scope =
                    ArgumentCaptor.forClass(CatalogToolBillingService.BillingScope.class);
            verify(billingService).preflightReserve(scope.capture());
            // 10 min upstream read timeout + 10 min AsyncPollExecutor ceiling is
            // the worst case a single call can burn; a shorter TTL would let the
            // sweeper release the reservation while the video is still rendering
            // and the commit would then find nothing to commit.
            assertThat(scope.getValue().ttlMinutes())
                    .isEqualTo(ToolExecutionManager.RESERVE_TTL_MINUTES)
                    .isGreaterThan(20);
            assertThat(scope.getValue().toolSlug()).isEqualTo(TOOL_SLUG);
            assertThat(scope.getValue().credentialSource()).isEqualTo("PLATFORM");
        }

        @Test
        @DisplayName("the size of the call and the unit it is counted in reach billing intact")
        void theMeasuredCallReachesBillingUnchanged() {
            // THE JOINT, which was pinned at both ends and at neither middle.
            // The header-to-DTO leg has a test, the module-to-header leg has a
            // test, and every guard is tested against a BillingScope built BY
            // HAND with the values already in it. The one line that copies them
            // from the request into the scope had none, and three separate
            // mutations of it left the whole suite green:
            //
            //   quantity -> ONE   a 10 second video billed as 1 second, and a
            //                     100 image batch as 1 image. Silent, on every
            //                     surface, in the undercharging direction.
            //   quantity -> null  every per-unit generation refused
            //                     GENERATION_SIZE_UNKNOWN: total outage.
            //   unit -> null      the dimension guard reads no unit, fails open,
            //                     and a per-image rate multiplies a count of
            //                     seconds.
            //
            // Asserted on what the billing service actually RECEIVES, because
            // that is the only place these values become money.
            givenReservationTaken();
            givenUpstreamReturns(upstreamSuccess("platform"));

            ToolExecutionRequest measured = platformRequest();
            measured.setGenerationModelId("seedance-2.0");
            measured.setGenerationQuantity(new BigDecimal("10"));
            measured.setGenerationQuantityUnit("second");
            // Deliberately a different number from the quantity. Both are BigDecimal and they sit
            // two apart in a sixteen-argument positional call, so a swap COMPILES: with 10 in both
            // places the scope would read correctly whichever slot each landed in, and the test
            // would pass on the wiring it exists to check.
            measured.setGenerationPriceMultiplier(new BigDecimal("2.4"));

            executionManager.executeTool(TOOL_SLUG, measured, USER_ID, null, "req-measured");

            ArgumentCaptor<CatalogToolBillingService.BillingScope> scope =
                    ArgumentCaptor.forClass(CatalogToolBillingService.BillingScope.class);
            verify(billingService).preflightReserve(scope.capture());
            assertThat(scope.getValue().generationModelId())
                    .as("which published row prices this call")
                    .isEqualTo("seedance-2.0");
            assertThat(scope.getValue().generationQuantity())
                    .as("a 10 second clip must not be billed as one")
                    .isEqualByComparingTo("10");
            assertThat(scope.getValue().generationQuantityUnit())
                    .as("without the unit, a per-image rate can be multiplied by a count of seconds")
                    .isEqualTo("second");
            assertThat(scope.getValue().generationPriceMultiplier())
                    .as("dropped here, a 1080p render is billed at the 720p rate on every call")
                    .isEqualByComparingTo("2.4");
            // And the two stay APART. Ten seconds is what was produced; 2.4 is what it cost. Folded
            // together, the run reports a duration nobody asked for and no player would show.
            assertThat(scope.getValue().generationQuantity()).isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("a FORGED cheap factor is overruled by what the body actually says")
        void aForgedFactorCannotBuyADiscount() {
            // The hole: the size, the unit, the model and the factor all decide what a call costs
            // and all four arrived as headers. They are stripped at the gateway and at the CE
            // monolith, and stripping happens at an EDGE - so anything reaching catalog-service
            // without traversing one believed them. `X-Lc-Generation-Multiplier: 0.001` turned a
            // 4K render into a rounding error, and `billed_quantity` came back as the forged value,
            // so nothing on the invoice looked wrong.
            //
            // The cloud already refuses to believe a linked install and re-derives from the body.
            // The body is here too, so the same reading applies and the headers stop deciding money.
            givenReservationTaken();
            givenUpstreamReturns(upstreamSuccess("platform"));
            givenAGenerationEndpoint();

            ToolExecutionRequest forged = platformRequest();
            forged.setParameters(Map.of(
                    "model", "vendor-tiered",
                    "resolution", "1080p",
                    "duration", 10,
                    "content", java.util.List.of(Map.of("type", "text", "text", "a cat"))));
            forged.setGenerationModelId("vid-tiered");
            forged.setGenerationQuantity(new BigDecimal("1"));       // claims a one second clip
            forged.setGenerationQuantityUnit("second");
            forged.setGenerationPriceMultiplier(new BigDecimal("0.001"));  // claims a 1000x discount

            executionManager.executeTool(TOOL_SLUG, forged, USER_ID, null, "req-forged");

            ArgumentCaptor<CatalogToolBillingService.BillingScope> scope =
                    ArgumentCaptor.forClass(CatalogToolBillingService.BillingScope.class);
            verify(billingService).preflightReserve(scope.capture());
            assertThat(scope.getValue().generationPriceMultiplier())
                    .as("the body asks for 1080p, which this model prices at twice its rate")
                    .isEqualByComparingTo("2");
            assertThat(scope.getValue().generationQuantity())
                    .as("the body carries ten seconds however few the header claimed")
                    .isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("a body the descriptor cannot read leaves the request's own values alone")
        void anUnreadableBodyFallsBackToTheRequest() {
            // The half that keeps every pre-existing caller working: an ordinary endpoint, a body
            // naming no model this descriptor knows, or a descriptor that will not parse. Measuring
            // must never be able to blank a value the caller legitimately supplied.
            givenReservationTaken();
            givenUpstreamReturns(upstreamSuccess("platform"));
            givenAGenerationEndpoint();

            ToolExecutionRequest plain = platformRequest();
            plain.setParameters(Map.of("model", "a-model-this-descriptor-never-heard-of"));
            plain.setGenerationModelId("seedance-2.0");
            plain.setGenerationQuantity(new BigDecimal("10"));
            plain.setGenerationQuantityUnit("second");

            executionManager.executeTool(TOOL_SLUG, plain, USER_ID, null, "req-unreadable");

            ArgumentCaptor<CatalogToolBillingService.BillingScope> scope =
                    ArgumentCaptor.forClass(CatalogToolBillingService.BillingScope.class);
            verify(billingService).preflightReserve(scope.capture());
            assertThat(scope.getValue().generationQuantity()).isEqualByComparingTo("10");
            assertThat(scope.getValue().generationModelId()).isEqualTo("seedance-2.0");
        }

        @Test
        @DisplayName("a generation with no factor reaches billing carrying none, not a 1")
        void aPlainGenerationCarriesNoFactor() {
            // Most models declare no modifiers at all. "No factor" has to arrive as absent rather
            // than as a neutral 1, because absent is what every pricing path resolved before
            // factors existed: it is the guarantee that this feature changed nothing for them.
            givenReservationTaken();
            givenUpstreamReturns(upstreamSuccess("platform"));

            ToolExecutionRequest plain = platformRequest();
            plain.setGenerationModelId("seedance-2.0");
            plain.setGenerationQuantity(new BigDecimal("10"));
            plain.setGenerationQuantityUnit("second");

            executionManager.executeTool(TOOL_SLUG, plain, USER_ID, null, "req-plain");

            ArgumentCaptor<CatalogToolBillingService.BillingScope> scope =
                    ArgumentCaptor.forClass(CatalogToolBillingService.BillingScope.class);
            verify(billingService).preflightReserve(scope.capture());
            assertThat(scope.getValue().generationPriceMultiplier()).isNull();
        }

        @Test
        @DisplayName("an ordinary tool still reaches billing with no generation context at all")
        void anOrdinaryCallCarriesNoGenerationContext() {
            // The negative half. Hard-coding the three values would satisfy the
            // test above while charging every ordinary endpoint as a generation.
            givenReservationTaken();
            givenUpstreamReturns(upstreamSuccess("platform"));

            execute();

            ArgumentCaptor<CatalogToolBillingService.BillingScope> scope =
                    ArgumentCaptor.forClass(CatalogToolBillingService.BillingScope.class);
            verify(billingService).preflightReserve(scope.capture());
            assertThat(scope.getValue().generationModelId()).isNull();
            assertThat(scope.getValue().generationQuantity()).isNull();
            assertThat(scope.getValue().generationQuantityUnit()).isNull();
        }

        @Test
        @DisplayName("only a GENERATION call may hold its expand past the response budget")
        void onlyAGenerationCallKeepsItsExpandPastTheBudget() {
            // The shaper caps an AGENT response at 64 KB, and an expand set that
            // survives that cap lifts the ceiling on whatever it names. `expand`
            // is chosen by the CALLER on an ordinary tool call, so granting it
            // there would let an agent pull a multi-megabyte payload into its own
            // context by naming the subtree it lives in. The privilege is tied to
            // the generation model id because that field is @JsonIgnore, is set
            // only from a header this service adds on its own generation path,
            // and is stripped at both edges.
            //
            // Untested, the gate could be deleted and every other test would
            // still pass: the generation path keeps working and the hole simply
            // reopens.
            givenReservationTaken();
            givenUpstreamReturns(upstreamSuccess("platform"));

            ToolExecutionRequest ordinary = platformRequest();
            ordinary.setExpand(java.util.List.of("data"));
            executionManager.executeTool(TOOL_SLUG, ordinary, USER_ID, null, "req-caller-expand");

            verify(responseShaper).shape(any(), any(), any(), any(), eq(false));

            ToolExecutionRequest generation = platformRequest();
            generation.setExpand(java.util.List.of("data"));
            generation.setGenerationModelId("seedance-2.0");
            generation.setGenerationQuantity(new BigDecimal("1"));
            generation.setGenerationQuantityUnit("image");
            executionManager.executeTool(TOOL_SLUG, generation, USER_ID, null, "req-generation-expand");

            verify(responseShaper).shape(any(), any(), any(), any(), eq(true));
        }
    }

    @Nested
    @DisplayName("a call that produced nothing")
    class FailedCall {

        @Test
        @DisplayName("releases the reservation on an upstream failure, so the customer is not charged")
        void upstreamFailureReleases() {
            givenReservationTaken();
            givenUpstreamReturns(Map.of("success", false, "error", "provider returned 500"));

            ToolExecutionResponse response = execute();

            assertThat(response.isSuccess()).isFalse();
            verify(billingService).releaseOnFailure(eq(SOURCE_ID), contains("upstream"));
            verify(billingService, never()).commitOnSuccess(any(), any(), any(), any());
        }

        @Test
        @DisplayName("releases when an exception lands BETWEEN the reserve and the commit, so no reservation dangles")
        void exceptionBetweenReserveAndCommitReleases() {
            givenReservationTaken();
            givenUpstreamReturns(upstreamSuccess("platform"));
            // Anything that throws after the provider answered and before the
            // settle: shaping, projection, dehydration, a plain bug.
            when(responseShaper.shape(any(), any(), any(), any(), anyBoolean()))
                    .thenThrow(new IllegalStateException("shaper blew up"));

            ToolExecutionResponse response = execute();

            assertThat(response.isSuccess()).isFalse();
            verify(billingService).releaseOnFailure(eq(SOURCE_ID), contains("aborted before settlement"));
            verify(billingService, never()).commitOnSuccess(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a commit that throws is chased by a release, and the two together never double-settle")
        void commitFailureFallsBackToRelease() {
            givenReservationTaken();
            givenUpstreamReturns(upstreamSuccess("platform"));
            when(billingService.commitOnSuccess(any(), any(), any(), any()))
                    .thenThrow(new IllegalStateException("auth unreachable"));

            execute();

            verify(billingService, times(1)).commitOnSuccess(any(), any(), any(), any());
            verify(billingService, times(1)).releaseOnFailure(eq(SOURCE_ID), any());
        }
    }

    @Nested
    @DisplayName("the paths that must NOT change")
    class Unaffected {

        @Test
        @DisplayName("an ordinary tool with no resolvable price proceeds and is neither reserved nor committed")
        void zeroPricedToolIsUntouched() {
            // This is what the 1000+ catalog endpoints look like: preflightReserve
            // resolves no positive markup and answers allowedWithoutBilling.
            givenNothingToBill();
            givenUpstreamReturns(upstreamSuccess("user"));

            ToolExecutionResponse response = executionManager.executeTool(
                    TOOL_SLUG,
                    ToolExecutionRequest.builder().parameters(Map.of("q", "x")).build(),
                    USER_ID, null, "req-free");

            assertThat(response.isSuccess()).isTrue();
            verify(apiService).executeApiTool(anyString(), anyString(),
                    any(JsonNode.class), anySet(), anyString());
            verify(billingService, never()).commitOnSuccess(any(), any(), any(), any());
            verify(billingService, never()).releaseOnFailure(any(), any());
        }

        @Test
        @DisplayName("a user's own key is never charged: the reservation taken on the agentic path is released")
        void byokIsReleasedNotCommitted() {
            // The agentic path (no explicit credentialSource) cannot know which
            // pool will answer, so it reserves and corrects here. Committing on
            // a call the user paid for upstream would bill them twice.
            givenReservationTaken();
            givenUpstreamReturns(upstreamSuccess("user"));

            executionManager.executeTool(
                    TOOL_SLUG,
                    ToolExecutionRequest.builder()
                            .parameters(Map.of("prompt", "a cat"))
                            .billingScopeKind("RUN")
                            .billingScopeId("run-1")
                            .build(),
                    USER_ID, null, "req-byok");

            verify(billingService).releaseOnFailure(eq(SOURCE_ID), contains("BYOK"));
            verify(billingService, never()).commitOnSuccess(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a cache HIT is billed exactly as before: reserved and committed, provider not called")
        void cacheHitIsStillBilled() {
            // The cache rule is about ORDINARY endpoints; the shared fixture is a
            // generation, which is exempt.
            givenAnOrdinaryEndpoint();
            givenReservationTaken();
            when(responseCache.get(anyString(), anyMap())).thenReturn(Map.of("task_id", "t-cached"));

            ToolExecutionResponse response = executionManager.executeTool(
                    TOOL_SLUG,
                    ToolExecutionRequest.builder()
                            .parameters(Map.of("prompt", "a cat"))
                            .credentialSource("platform")
                            .billingScopeKind("STREAM")
                            .billingScopeId("stream-1")
                            .build(),
                    USER_ID, null, "req-cache");

            assertThat(response.isSuccess()).isTrue();
            assertThat(response.getMetadata()).containsEntry("cached", true);
            verify(apiService, never()).executeApiTool(anyString(), anyString(),
                    any(JsonNode.class), anySet(), anyString());
            verify(billingService).commitOnSuccess(eq(SOURCE_ID), eq(RESERVED), any(), any());
            verify(billingService, never()).releaseOnFailure(any(), any());
        }

        @Test
        @DisplayName("a GENERATION is never served from the cache, because a cache hit would charge "
                + "again for an asset nobody made")
        void aGenerationIsNeverCached() {
            // The sibling above is the rule for reads: serving the same answer
            // twice costs nobody anything, so it is billed like any other call.
            // A generation is a purchase, and the two halves of that rule meet
            // here. The reservation is taken BEFORE the cache is read and
            // committed after, so a hit takes the money without asking the
            // provider for anything: the customer pays twice for one asset, and
            // gets back the previous run's signed URL, which the provider time
            // limits, so the second charge can resolve to nothing at all.
            givenReservationTaken();
            givenUpstreamReturns(upstreamSuccess("platform"));

            ToolExecutionResponse response = executionManager.executeTool(
                    TOOL_SLUG,
                    ToolExecutionRequest.builder()
                            .parameters(Map.of("prompt", "a cat"))
                            .credentialSource("platform")
                            .billingScopeKind("STREAM")
                            .billingScopeId("stream-1")
                            .generationModelId("seedance-2.0")
                            .build(),
                    USER_ID, null, "req-gen");

            assertThat(response.isSuccess()).isTrue();
            // The cache is not consulted at all: not a miss, never asked.
            verify(responseCache, never()).get(anyString(), anyMap());
            verify(apiService).executeApiTool(anyString(), anyString(),
                    any(JsonNode.class), anySet(), anyString());
        }

        @Test
        @DisplayName("a generation reached by RAW SLUG is not cached either, though it names no model")
        void aGenerationReachedByRawSlugIsNeverCached() {
            // Two callers reach the same endpoint and only one names a model.
            // catalog(action='execute', tool_id='<api>/<tool>') sends none, so
            // the first version of this guard let it through: with a flat
            // published price every billing refusal stays silent, the second
            // identical call inside the window hits the cache, and the customer
            // is charged again for an asset nobody made. The endpoint's own
            // descriptor is the only signal that caller offers.
            givenReservationTaken();
            givenUpstreamReturns(upstreamSuccess("platform"));
            ApiToolEntity generationTool = new ApiToolEntity();
            generationTool.setId(TOOL_ID);
            generationTool.setToolSlug("create-video-task");
            generationTool.setGenerationSpec("{\"kind\":\"video\"}");
            when(apiToolRepository.findById(TOOL_ID)).thenReturn(Optional.of(generationTool));

            executionManager.executeTool(
                    TOOL_SLUG,
                    ToolExecutionRequest.builder()
                            .parameters(Map.of("prompt", "a cat"))
                            .credentialSource("platform")
                            .billingScopeKind("STREAM")
                            .billingScopeId("stream-1")
                            .build(),
                    USER_ID, null, "req-rawslug");

            verify(responseCache, never()).get(anyString(), anyMap());
        }

        @Test
        @DisplayName("an ordinary STREAM tool still uses the cache, so the exemption is about "
                + "generations and not about chat")
        void anOrdinaryStreamToolStillUsesTheCache() {
            givenAnOrdinaryEndpoint();
            givenReservationTaken();
            when(responseCache.get(anyString(), anyMap())).thenReturn(Map.of("task_id", "t-cached"));

            executionManager.executeTool(
                    TOOL_SLUG,
                    ToolExecutionRequest.builder()
                            .parameters(Map.of("prompt", "a cat"))
                            .credentialSource("platform")
                            .billingScopeKind("STREAM")
                            .billingScopeId("stream-1")
                            .build(),
                    USER_ID, null, "req-ordinary");

            verify(responseCache).get(anyString(), anyMap());
        }

    }

    /**
     * An auth-service outage is ONE event with TWO correct answers, and the
     * previous single test asserted the wrong one for the expensive half: it
     * used this very seedance generation fixture to pin "a reserve that throws
     * proceeds unbilled" as intended behaviour. That made the suite's contract
     * read "a resold generation goes out free when the ledger is unreachable",
     * which is the outcome the whole feature exists to prevent.
     *
     * <p>It was also inconsistent with itself: a transport failure INSIDE
     * {@code CreditConsumptionClient.scopeReserve} comes back as
     * {@code allowed=false} and refuses, so the same outage refused or allowed
     * depending only on which frame it surfaced in.
     */
    @Nested
    @DisplayName("a reserve that throws (auth-service outage)")
    class ReserveThrows {

        @Test
        @DisplayName("REFUSES a resold generation and never calls the provider, because the provider bills "
                + "the platform owner whether or not the ledger answered")
        void resoldGenerationIsRefused() {
            when(billingService.preflightReserve(any()))
                    .thenThrow(new IllegalStateException("auth-service unreachable"));
            when(billingService.requiresBillingToProceed(any())).thenReturn(true);

            assertThatThrownBy(ToolExecutionManagerBillingLifecycleTest.this::execute)
                    .isInstanceOf(InsufficientCreditsException.class)
                    .hasMessageContaining("PLATFORM_NOT_AVAILABLE");

            verify(apiService, never()).executeApiTool(anyString(), anyString(),
                    any(JsonNode.class), anySet(), anyString());
            verify(billingService, never()).commitOnSuccess(any(), any(), any(), any());
            // Nothing was reserved, so there is nothing to release either.
            verify(billingService, never()).releaseOnFailure(any(), any());
        }

        @Test
        @DisplayName("still runs an ordinary tool unbilled, because an unreachable ledger must not take the "
                + "1000+ catalog endpoints down with it")
        void ordinaryToolStillRuns() {
            when(billingService.preflightReserve(any()))
                    .thenThrow(new IllegalStateException("auth-service unreachable"));
            when(billingService.requiresBillingToProceed(any())).thenReturn(false);
            givenUpstreamReturns(upstreamSuccess("platform"));

            ToolExecutionResponse response = execute();

            assertThat(response.isSuccess()).isTrue();
            verify(apiService).executeApiTool(anyString(), anyString(),
                    any(JsonNode.class), anySet(), anyString());
            // The verdict is asked per call rather than hardcoded: that is the
            // difference between "fails open for ordinary tools" and "fails
            // open for everything", which is what the code used to do.
            verify(billingService).requiresBillingToProceed(any());
            verify(billingService, never()).commitOnSuccess(any(), any(), any(), any());
            verify(billingService, never()).releaseOnFailure(any(), any());
        }
    }

    /**
     * End-to-end over the REAL billing service, because the hole was invisible
     * from a mocked one: nothing in this class could tell that a call with no
     * run and no chat stream (an {@code lc_live_} API key on the MCP surface)
     * silently skipped every billing branch.
     */
    @Nested
    @DisplayName("a call with no billing scope, against the real billing service")
    class NoBillingScopeEndToEnd {

        private CreditConsumptionClient creditClient;
        private ToolExecutionManager manager;

        @BeforeEach
        void wireRealBillingService() {
            creditClient = mock(CreditConsumptionClient.class);
            CatalogToolBillingService realBilling = new CatalogToolBillingService(
                    creditClient, credentialClient, apiToolRepository, apiRepository, true);
            ObjectMapper objectMapper = new ObjectMapper();
            manager = new ToolExecutionManager(
                    toolContextService, apiService, objectMapper, responseShaper, nextActionBuilder,
                    responseCache, toolNextHintRepository, toolResponseService,
                    new ToolExecutionOrchestrator(new OutputProjector(objectMapper)),
                    new BinaryResponseHandler(objectMapper),
                    realBilling, credentialClient, apiRepository, ceCatalogCloudRelay);
            ReflectionTestUtils.setField(manager, "apiToolRepoForSlug", apiToolRepository);
        }

        /** No billingScopeKind and no billingScopeId: the MCP-surface shape. */
        private ToolExecutionResponse executeWithoutScope() {
            return manager.executeTool(TOOL_SLUG,
                    ToolExecutionRequest.builder()
                            .parameters(Map.of("prompt", "a cat"))
                            .credentialSource("platform")
                            .platformCredentialId(42L)
                            .build(),
                    USER_ID, null, "req-noscope");
        }

        @Test
        @DisplayName("a resold generation is refused and the provider is never called")
        void resoldGenerationIsRefused() {
            // Pinned to the exact refusal, not to the shared
            // PLATFORM_NOT_AVAILABLE code. Two guards produce that code, one
            // here and one in ToolExecutionManager, so asserting the code alone
            // let either of them be DELETED with the suite still green - which
            // is how this class certified a guard it was not exercising.
            assertThatThrownBy(this::executeWithoutScope)
                    .isInstanceOf(InsufficientCreditsException.class)
                    .hasMessage(CatalogToolBillingService.UNBILLABLE_GENERATION);

            verify(apiService, never()).executeApiTool(anyString(), anyString(),
                    any(JsonNode.class), anySet(), anyString());
            verify(creditClient, never()).scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean());
        }

        /**
         * The other guard, which no test reached: the one that fires when no
         * billing context could be BUILT at all.
         *
         * <p>Every rule in the billing service reads a scope. When
         * {@code buildBillingScope} answers null - here because the account
         * behind the call is not a numeric user id, the same way an unresolvable
         * endpoint slug does it - the service is never consulted, so its
         * fail-closed rule cannot run and the generation would be dispatched on
         * the platform's provider key with no reservation and no ledger row.
         */
        @Test
        @DisplayName("a generation whose call cannot be identified at all is refused before dispatch")
        void generationWithNoBuildableScopeIsRefused() {
            assertThatThrownBy(() -> manager.executeTool(TOOL_SLUG,
                    ToolExecutionRequest.builder()
                            .parameters(Map.of("prompt", "a cat"))
                            .credentialSource("platform")
                            .platformCredentialId(42L)
                            .generationModelId("seedance-2.0")
                            .billingScopeKind("RUN")
                            .billingScopeId("run-1")
                            .build(),
                    // Not a number: parseUserIdSafe answers null and no scope
                    // can be built, however complete the rest of the call is.
                    "anonymous", null, "req-nouser"))
                    .isInstanceOf(InsufficientCreditsException.class)
                    .hasMessage(CatalogToolBillingService.UNBILLABLE_GENERATION_UNIDENTIFIED_CALL);

            verify(apiService, never()).executeApiTool(anyString(), anyString(),
                    any(JsonNode.class), anySet(), anyString());
            verify(creditClient, never()).scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("an ORDINARY endpoint whose call cannot be identified still runs, unbilled")
        void ordinaryEndpointWithNoBuildableScopeStillRuns() {
            // The same unidentifiable call on an endpoint that resells nothing.
            // The guard must not turn a missing billing context into a refusal
            // for the 1000+ catalog endpoints that were never going to be billed.
            ApiToolEntity ordinary = new ApiToolEntity();
            ordinary.setId(TOOL_ID);
            ordinary.setApiId(API_ID);
            ordinary.setToolSlug("create-video-task");
            when(apiToolRepository.findById(TOOL_ID)).thenReturn(Optional.of(ordinary));
            givenUpstreamReturns(upstreamSuccess("platform"));

            ToolExecutionResponse response = manager.executeTool(TOOL_SLUG,
                    ToolExecutionRequest.builder()
                            .parameters(Map.of("prompt", "a cat"))
                            .credentialSource("platform")
                            .platformCredentialId(42L)
                            .build(),
                    "anonymous", null, "req-nouser-ordinary");

            assertThat(response.isSuccess()).isTrue();
            verify(apiService).executeApiTool(anyString(), anyString(),
                    any(JsonNode.class), anySet(), anyString());
            verify(creditClient, never()).scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("the same call on an ordinary endpoint runs exactly as before")
        void ordinaryEndpointStillRuns() {
            ApiToolEntity ordinary = new ApiToolEntity();
            ordinary.setId(TOOL_ID);
            ordinary.setApiId(API_ID);
            ordinary.setToolSlug("create-video-task");
            // No generation descriptor: nothing is resold, so nothing to guard.
            when(apiToolRepository.findById(TOOL_ID)).thenReturn(Optional.of(ordinary));
            when(apiToolRepository.findByApiIdAndToolSlug(API_ID, "create-video-task"))
                    .thenReturn(Optional.of(ordinary));
            givenUpstreamReturns(upstreamSuccess("platform"));

            ToolExecutionResponse response = executeWithoutScope();

            assertThat(response.isSuccess()).isTrue();
            verify(apiService).executeApiTool(anyString(), anyString(),
                    any(JsonNode.class), anySet(), anyString());
            verify(creditClient, never()).scopeReserve(any(), any(), any(), any(), any(), any(),
                    anyInt(), any(), any(), anyBoolean());
        }
    }
}
