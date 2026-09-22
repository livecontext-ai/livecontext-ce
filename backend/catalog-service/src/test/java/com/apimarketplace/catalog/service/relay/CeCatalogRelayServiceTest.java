package com.apimarketplace.catalog.service.relay;

import com.apimarketplace.catalog.domain.ApiEntity;
import com.apimarketplace.catalog.domain.ApiToolEntity;
import com.apimarketplace.catalog.domain.dto.CeCatalogRelayRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.repository.ApiRepository;
import com.apimarketplace.catalog.repository.ApiToolRepository;
import com.apimarketplace.catalog.service.CatalogV1Service;
import com.apimarketplace.catalog.service.http.CredentialModeContext;
import com.apimarketplace.catalog.service.http.ProviderRetryContext;
import com.apimarketplace.catalog.service.relay.CeCatalogRelayService.PlatformInfo;
import com.apimarketplace.catalog.service.relay.CeCatalogRelayService.RelayResult;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.common.credit.CreditConsumptionClient.ScopeReserveResult;
import com.apimarketplace.common.credit.SourceIdBuilder;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.FrozenMarkupDto;
import com.apimarketplace.credential.client.dto.PlatformCredentialLookupDto;
import com.apimarketplace.credential.client.dto.PricingVersionDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Reserve → execute → commit/release lifecycle and fail-closed refusals of the
 * CE catalog relay execution service.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CeCatalogRelayService")
class CeCatalogRelayServiceTest {

    private static final long CLOUD_USER_ID = 42L;
    private static final String INSTALL_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String API_SLUG = "openweather";
    private static final String TOOL_SLUG = "current-weather";
    private static final UUID API_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID TOOL_ID = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final long CREDENTIAL_ID = 77L;
    private static final long PRICING_VERSION_ID = 5L;
    private static final BigDecimal MARKUP = new BigDecimal("0.25");

    @Mock private ApiRepository apiRepository;
    @Mock private ApiToolRepository apiToolRepository;
    @Mock private CredentialClient credentialClient;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private CatalogV1Service catalogV1Service;

    private CeCatalogRelayService service;

    @BeforeEach
    void setUp() {
        service = new CeCatalogRelayService(apiRepository, apiToolRepository,
                credentialClient, creditClient, catalogV1Service,
                new ObjectMapper(), 10, 120);
    }

    @AfterEach
    void clearThreadLocals() {
        CredentialModeContext.clear();
        ProviderRetryContext.clear();
    }

    private ApiEntity api(String authType) {
        ApiEntity api = new ApiEntity();
        api.setId(API_ID);
        api.setApiName("OpenWeather");
        api.setApiSlug(API_SLUG);
        api.setAuthType(authType);
        api.setIsActive(true);
        api.setPlatformCredentialName("openweather");
        return api;
    }

    private ApiToolEntity tool() {
        return tool(null);
    }

    /** @param generationSpec a descriptor to make this endpoint a generation, or null for an ordinary tool. */
    private ApiToolEntity tool(String generationSpec) {
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(TOOL_ID);
        tool.setToolSlug(TOOL_SLUG);
        tool.setIsActive(true);
        if (generationSpec != null) {
            tool.setGenerationSpec(generationSpec);
        }
        return tool;
    }

    private static PlatformCredentialLookupDto credential(String providerKind) {
        PlatformCredentialLookupDto dto = new PlatformCredentialLookupDto();
        dto.setFound(true);
        dto.setId(CREDENTIAL_ID);
        dto.setIntegrationName("openweather");
        dto.setProviderKind(providerKind);
        return dto;
    }

    private static PricingVersionDto pricingVersion(BigDecimal defaultMarkup) {
        PricingVersionDto dto = new PricingVersionDto();
        dto.setFound(true);
        dto.setPricingVersionId(PRICING_VERSION_ID);
        dto.setCredentialId(CREDENTIAL_ID);
        dto.setDefaultMarkupCredits(defaultMarkup);
        return dto;
    }

    /** A price the owner published FOR THIS ENDPOINT, which is the ordinary case. */
    private static FrozenMarkupDto frozenMarkup(BigDecimal markup) {
        FrozenMarkupDto dto = new FrozenMarkupDto();
        dto.setFound(true);
        dto.setPricingVersionId(PRICING_VERSION_ID);
        dto.setEffectiveMarkup(markup);
        dto.setPricedByPublishedRow(true);
        return dto;
    }

    private static CeCatalogRelayRequest relayRequest() {
        return CeCatalogRelayRequest.builder()
                .parameters(Map.of("city", "Paris"))
                .build();
    }

    private void stubResolvedApiAndTool(String authType) {
        stubResolvedApiAndTool(authType, null);
    }

    private void stubResolvedApiAndTool(String authType, String generationSpec) {
        ApiToolEntity resolved = tool(generationSpec);
        when(apiRepository.findByApiSlug(API_SLUG)).thenReturn(Optional.of(api(authType)));
        when(apiToolRepository.findByApiIdAndToolSlug(API_ID, TOOL_SLUG))
                .thenReturn(Optional.of(resolved));
        // The price guard re-reads the tool by id to ask whether it is a
        // generation, so both lookups must answer with the SAME endpoint.
        lenient().when(apiToolRepository.findById(TOOL_ID)).thenReturn(Optional.of(resolved));
    }

    private void stubCredentialAndPricing() {
        when(credentialClient.findPlatformCredentialByName("openweather"))
                .thenReturn(Optional.of(credential("cloud")));
        when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID))
                .thenReturn(Optional.of(pricingVersion(MARKUP)));
        when(credentialClient.resolveFrozenMarkup(PRICING_VERSION_ID, TOOL_ID, null, null, null))
                .thenReturn(Optional.of(frozenMarkup(MARKUP)));
    }

    private void stubSuccessfulReserve() {
        when(creditClient.scopeReserve(anyLong(), anyString(), anyString(), anyString(),
                any(), isNull(), anyInt(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new ScopeReserveResult(true, null, false, BigDecimal.TEN));
    }

    /**
     * A descriptor that actually PARSES, with a model, a price unit and a
     * measuring parameter.
     *
     * <p>Every other generation fixture in this file is {@code {"kind":"video"}},
     * which {@code GenerationSpec.parse} rejects (no assetPath, no models). A
     * rejected descriptor makes the service measure NOTHING, so those fixtures
     * exercise the unmeasured branch only, whatever they are named. The whole
     * measured path, which is what decides the amount a self-hosted install is
     * charged, needs a descriptor the parser accepts.
     */
    private static final String REAL_VIDEO_SPEC = """
            {
              "kind": "video",
              "assetPath": "content.video_url",
              "modelParam": "model",
              "paramMap": {"prompt": "prompt", "duration_seconds": "duration"},
              "models": [
                {"id": "vid-fast", "upstream": "vendor-fast", "label": "Fast",
                 "capabilities": ["prompt", "duration_seconds"], "required": ["prompt"],
                 "price": {"unit": "second", "baseCredits": 0, "unitCredits": 60}}
              ]
            }
            """;

    /** A body a real install would relay: it names the model and its size. */
    private static CeCatalogRelayRequest measurableRequest(int durationSeconds) {
        return CeCatalogRelayRequest.builder()
                .parameters(Map.of("model", "vendor-fast", "prompt", "a cat",
                        "duration", durationSeconds))
                .build();
    }

    /** The same endpoint, on a model whose RESOLUTION is priced rather than free. */
    private static final String MODULATED_VIDEO_SPEC = """
            {
              "kind": "video",
              "assetPath": "content.video_url",
              "modelParam": "model",
              "paramMap": {"prompt": "prompt", "duration_seconds": "duration",
                           "resolution": "resolution"},
              "models": [
                {"id": "vid-fast", "upstream": "vendor-fast", "label": "Fast",
                 "capabilities": ["prompt", "duration_seconds", "resolution"],
                 "required": ["prompt", "resolution"],
                 "constraints": {"resolution": {"allowed": ["480p", "720p"]}},
                 "price": {"unit": "second", "baseCredits": 0, "unitCredits": 60,
                           "modifiers": [{"param": "resolution",
                                          "multiply": {"480p": 1, "720p": 2}}]}}
              ]
            }
            """;

    /** A relayed body that asks for the dearer tier. */
    private static CeCatalogRelayRequest modulatedRequest(int durationSeconds, String resolution) {
        return CeCatalogRelayRequest.builder()
                .parameters(Map.of("model", "vendor-fast", "prompt", "a cat",
                        "duration", durationSeconds, "resolution", resolution))
                .build();
    }

    @Nested
    @DisplayName("what the relayed call's own choices cost, also measured by the cloud")
    class MeasuredPriceFactor {

        @Test
        @DisplayName("the factor is read out of the body and is what the price is resolved with")
        void theFactorReachesThePriceResolver() {
            // Same reason the SIZE is measured here: an install that declared its own factor could
            // declare it as 1 and pay the base rate for a call the platform owner is charged extra
            // for. Asserted on the ARGUMENT, because that is where the reading becomes money.
            stubResolvedApiAndTool("api_key", MODULATED_VIDEO_SPEC);
            when(credentialClient.findPlatformCredentialByName("openweather"))
                    .thenReturn(Optional.of(credential("cloud")));
            when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID))
                    .thenReturn(Optional.of(pricingVersion(MARKUP)));
            FrozenMarkupDto perSecond = frozenMarkup(new BigDecimal("1200"));
            perSecond.setPriceUnit("second");
            when(credentialClient.resolveFrozenMarkup(
                    eq(PRICING_VERSION_ID), eq(TOOL_ID), eq("vid-fast"),
                    argThat(q -> q != null && q.compareTo(BigDecimal.TEN) == 0),
                    argThat(f -> f != null && f.compareTo(new BigDecimal("2")) == 0)))
                    .thenReturn(Optional.of(perSecond));
            stubSuccessfulReserve();
            when(catalogV1Service.executeTool(anyString(), any(), anyString(), isNull(), anyString()))
                    .thenReturn(ToolExecutionResponse.builder().success(true).build());

            RelayResult result = service.execute(
                    CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, modulatedRequest(10, "720p"));

            // The stub answers ONLY for the (size, factor) pair above: any other reading leaves the
            // price unresolved and the relay refuses.
            assertThat(result.status()).isEqualTo(RelayResult.Status.OK);
            verify(credentialClient).resolveFrozenMarkup(
                    eq(PRICING_VERSION_ID), eq(TOOL_ID), eq("vid-fast"),
                    argThat(q -> q != null && q.compareTo(BigDecimal.TEN) == 0),
                    argThat(f -> f != null && f.compareTo(new BigDecimal("2")) == 0));
        }

        @Test
        @DisplayName("a call at the reference tier resolves at the published rate")
        void theReferenceTierIsTheRate() {
            stubResolvedApiAndTool("api_key", MODULATED_VIDEO_SPEC);
            when(credentialClient.findPlatformCredentialByName("openweather"))
                    .thenReturn(Optional.of(credential("cloud")));
            when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID))
                    .thenReturn(Optional.of(pricingVersion(MARKUP)));
            FrozenMarkupDto perSecond = frozenMarkup(new BigDecimal("600"));
            perSecond.setPriceUnit("second");
            when(credentialClient.resolveFrozenMarkup(
                    eq(PRICING_VERSION_ID), eq(TOOL_ID), eq("vid-fast"), any(),
                    argThat(f -> f != null && f.compareTo(BigDecimal.ONE) == 0)))
                    .thenReturn(Optional.of(perSecond));
            stubSuccessfulReserve();
            when(catalogV1Service.executeTool(anyString(), any(), anyString(), isNull(), anyString()))
                    .thenReturn(ToolExecutionResponse.builder().success(true).build());

            RelayResult result = service.execute(
                    CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, modulatedRequest(10, "480p"));

            assertThat(result.status()).isEqualTo(RelayResult.Status.OK);
        }

        @Test
        @DisplayName("a model with no declared modifiers carries no factor at all")
        void anUnmodulatedModelIsUnchanged() {
            // The property that keeps every existing relayed call byte-identical.
            stubResolvedApiAndTool("api_key", REAL_VIDEO_SPEC);
            when(credentialClient.findPlatformCredentialByName("openweather"))
                    .thenReturn(Optional.of(credential("cloud")));
            when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID))
                    .thenReturn(Optional.of(pricingVersion(MARKUP)));
            FrozenMarkupDto perSecond = frozenMarkup(new BigDecimal("600"));
            perSecond.setPriceUnit("second");
            when(credentialClient.resolveFrozenMarkup(
                    eq(PRICING_VERSION_ID), eq(TOOL_ID), eq("vid-fast"), any(), isNull()))
                    .thenReturn(Optional.of(perSecond));
            stubSuccessfulReserve();
            when(catalogV1Service.executeTool(anyString(), any(), anyString(), isNull(), anyString()))
                    .thenReturn(ToolExecutionResponse.builder().success(true).build());

            RelayResult result = service.execute(
                    CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, measurableRequest(10));

            assertThat(result.status()).isEqualTo(RelayResult.Status.OK);
        }

        @Test
        @DisplayName("the read-only probe prices with the factor it is asked about")
        void theProbeCarriesTheFactor() {
            // The probe is what the install QUOTES from. Without the factor it answers the
            // published rate while the executing path above charges the multiplied one, and the
            // two are only ever compared by the customer, after the fact.
            // The probe reaches the endpoint by the CREDENTIAL's name, not by the api/tool slugs the
            // executing path walks, so it is stubbed the way its neighbours are.
            when(apiRepository.findByPlatformCredentialName("seedance"))
                    .thenReturn(Optional.of(api("api_key")));
            lenient().when(apiToolRepository.findById(TOOL_ID))
                    .thenReturn(Optional.of(tool(MODULATED_VIDEO_SPEC)));
            when(credentialClient.findPlatformCredentialByName("seedance"))
                    .thenReturn(Optional.of(credential("cloud")));
            when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID))
                    .thenReturn(Optional.of(pricingVersion(MARKUP)));
            FrozenMarkupDto perSecond = frozenMarkup(new BigDecimal("1200"));
            perSecond.setPriceUnit("second");
            // Resolvable ONLY when the factor travels with the lookup.
            when(credentialClient.resolveFrozenMarkup(
                    eq(PRICING_VERSION_ID), eq(TOOL_ID), eq("vid-fast"), any(),
                    argThat(f -> f != null && f.compareTo(new BigDecimal("2")) == 0)))
                    .thenReturn(Optional.of(perSecond));

            CeCatalogRelayService.PlatformInfo info = service.platformInfo(
                    "seedance", TOOL_ID, "vid-fast", BigDecimal.TEN, new BigDecimal("2"));

            assertThat(info.hasPricing()).isTrue();
            assertThat(new BigDecimal(info.markupCredits())).isEqualByComparingTo("1200");
        }
    }

    @Nested
    @DisplayName("a relayed generation is priced on what the CLOUD measures in the body")
    class MeasuredGeneration {

        @Test
        @DisplayName("the model and the size read out of the body are what the price is resolved for")
        void theMeasurementReachesThePriceResolver() {
            // THE POINT OF THIS TEST. The install never declares its own size:
            // if it could, a ten second video would be reported as one second
            // and billed as one. The cloud reads both facts back out of the
            // provider-shaped body using the same descriptor that produced it.
            //
            // Asserted on the ARGUMENTS the resolver receives, because that is
            // the only place the measurement becomes money. Stubbing (null,
            // null) here, as every neighbouring fixture does, would pass with
            // the measurement deleted outright.
            stubResolvedApiAndTool("api_key", REAL_VIDEO_SPEC);
            when(credentialClient.findPlatformCredentialByName("openweather"))
                    .thenReturn(Optional.of(credential("cloud")));
            when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID))
                    .thenReturn(Optional.of(pricingVersion(MARKUP)));
            FrozenMarkupDto perSecond = frozenMarkup(new BigDecimal("600"));
            perSecond.setPriceUnit("second");
            when(credentialClient.resolveFrozenMarkup(
                    eq(PRICING_VERSION_ID), eq(TOOL_ID), eq("vid-fast"),
                    argThat(q -> q != null && q.compareTo(BigDecimal.TEN) == 0), isNull()))
                    .thenReturn(Optional.of(perSecond));
            stubSuccessfulReserve();
            when(catalogV1Service.executeTool(anyString(), any(), anyString(), isNull(), anyString()))
                    .thenReturn(ToolExecutionResponse.builder().success(true).build());

            RelayResult result = service.execute(
                    CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, measurableRequest(10));

            assertThat(result.status()).isEqualTo(RelayResult.Status.OK);
            verify(credentialClient).resolveFrozenMarkup(
                    eq(PRICING_VERSION_ID), eq(TOOL_ID), eq("vid-fast"),
                    argThat(q -> q != null && q.compareTo(BigDecimal.TEN) == 0), isNull());
        }

        @Test
        @DisplayName("a size of ZERO is refused, not floored: the install never stated how big this is")
        void aZeroSizeIsRefusedRatherThanClampedToTheFloor() {
            // Read as a real measurement, a 0 makes the amount base + rate x 0,
            // which the floor then lifts to minCredits: the install pays a
            // floor for a size it never gave, while the provider bills the
            // platform owner for whatever it auto-selected. The refusal comes
            // from the per-unit filter, which can only fire when the quantity
            // is absent.
            stubResolvedApiAndTool("api_key", REAL_VIDEO_SPEC);
            when(credentialClient.findPlatformCredentialByName("openweather"))
                    .thenReturn(Optional.of(credential("cloud")));
            when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID))
                    .thenReturn(Optional.of(pricingVersion(MARKUP)));
            FrozenMarkupDto perUnitWithoutQuantity = frozenMarkup(new BigDecimal("60"));
            perUnitWithoutQuantity.setPriceUnit("second");
            perUnitWithoutQuantity.setUnitCredits(new BigDecimal("60"));
            when(credentialClient.resolveFrozenMarkup(
                    eq(PRICING_VERSION_ID), eq(TOOL_ID), eq("vid-fast"), isNull(), isNull()))
                    .thenReturn(Optional.of(perUnitWithoutQuantity));

            RelayResult result = service.execute(
                    CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, measurableRequest(0));

            assertThat(result.status()).isNotEqualTo(RelayResult.Status.OK);
            verifyNoInteractions(catalogV1Service);
        }

        @Test
        @DisplayName("a price published per IMAGE cannot bill a call measured in SECONDS")
        void aRateOfTheWrongDimensionIsRefused() {
            // Publishing arms its own guard only on a RE-publish, so a FIRST
            // per-image row on a per-second model reaches here unchallenged.
            // Both halves look ordinary alone: a rate, and a bare 10.
            // Multiplied, a ten second clip costs ten times the per-image rate.
            stubResolvedApiAndTool("api_key", REAL_VIDEO_SPEC);
            when(credentialClient.findPlatformCredentialByName("openweather"))
                    .thenReturn(Optional.of(credential("cloud")));
            when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID))
                    .thenReturn(Optional.of(pricingVersion(MARKUP)));
            FrozenMarkupDto perImage = frozenMarkup(new BigDecimal("600"));
            perImage.setPriceUnit("image");
            when(credentialClient.resolveFrozenMarkup(
                    eq(PRICING_VERSION_ID), eq(TOOL_ID), eq("vid-fast"), any(), any()))
                    .thenReturn(Optional.of(perImage));

            RelayResult result = service.execute(
                    CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, measurableRequest(10));

            assertThat(result.status()).isNotEqualTo(RelayResult.Status.OK);
            verifyNoInteractions(catalogV1Service);
        }

        @Test
        @DisplayName("a rate of the SAME dimension still relays, so the guard is not anti-generation")
        void aRateOfTheSameDimensionStillRelays() {
            // The negative half. Without it, refusing everything would score as
            // a pass on the three tests above.
            stubResolvedApiAndTool("api_key", REAL_VIDEO_SPEC);
            when(credentialClient.findPlatformCredentialByName("openweather"))
                    .thenReturn(Optional.of(credential("cloud")));
            when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID))
                    .thenReturn(Optional.of(pricingVersion(MARKUP)));
            FrozenMarkupDto perMinute = frozenMarkup(new BigDecimal("100"));
            // Published per MINUTE against a call measured in SECONDS: one
            // dimension at two scales, which the published row converts.
            perMinute.setPriceUnit("minute");
            when(credentialClient.resolveFrozenMarkup(
                    eq(PRICING_VERSION_ID), eq(TOOL_ID), eq("vid-fast"), any(), any()))
                    .thenReturn(Optional.of(perMinute));
            stubSuccessfulReserve();
            when(catalogV1Service.executeTool(anyString(), any(), anyString(), isNull(), anyString()))
                    .thenReturn(ToolExecutionResponse.builder().success(true).build());

            RelayResult result = service.execute(
                    CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, measurableRequest(10));

            assertThat(result.status()).isEqualTo(RelayResult.Status.OK);
        }
    }

    @Nested
    @DisplayName("fail-closed refusals before any upstream call")
    class Refusals {

        @Test
        @DisplayName("unknown api slug yields TOOL_NOT_FOUND without touching credentials or billing")
        void unknownApiIsToolNotFound() {
            when(apiRepository.findByApiSlug(API_SLUG)).thenReturn(Optional.empty());

            RelayResult result = service.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, relayRequest());

            assertThat(result.status()).isEqualTo(RelayResult.Status.TOOL_NOT_FOUND);
            verifyNoInteractions(credentialClient, creditClient, catalogV1Service);
        }

        @Test
        @DisplayName("inactive tool yields TOOL_NOT_FOUND")
        void inactiveToolIsToolNotFound() {
            when(apiRepository.findByApiSlug(API_SLUG)).thenReturn(Optional.of(api("api_key")));
            ApiToolEntity inactive = tool();
            inactive.setIsActive(false);
            when(apiToolRepository.findByApiIdAndToolSlug(API_ID, TOOL_SLUG))
                    .thenReturn(Optional.of(inactive));

            RelayResult result = service.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, relayRequest());

            assertThat(result.status()).isEqualTo(RelayResult.Status.TOOL_NOT_FOUND);
            verifyNoInteractions(catalogV1Service);
        }

        @Test
        @DisplayName("oauth2 integrations are rejected (user consent cannot be relayed)")
        void oauthIsRejected() {
            stubResolvedApiAndTool("oauth2");

            RelayResult result = service.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, relayRequest());

            assertThat(result.status()).isEqualTo(RelayResult.Status.OAUTH_NOT_RELAYABLE);
            verifyNoInteractions(credentialClient, creditClient, catalogV1Service);
        }

        @Test
        @DisplayName("missing platform credential yields PLATFORM_NOT_AVAILABLE")
        void missingPlatformCredentialIsRejected() {
            stubResolvedApiAndTool("api_key");
            when(credentialClient.findPlatformCredentialByName("openweather"))
                    .thenReturn(Optional.empty());

            RelayResult result = service.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, relayRequest());

            assertThat(result.status()).isEqualTo(RelayResult.Status.PLATFORM_NOT_AVAILABLE);
            verifyNoInteractions(creditClient, catalogV1Service);
        }

        @Test
        @DisplayName("bridge providerKind yields PLATFORM_NOT_AVAILABLE (bridge does its own accounting)")
        void bridgeCredentialIsRejected() {
            stubResolvedApiAndTool("api_key");
            when(credentialClient.findPlatformCredentialByName("openweather"))
                    .thenReturn(Optional.of(credential("bridge")));

            RelayResult result = service.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, relayRequest());

            assertThat(result.status()).isEqualTo(RelayResult.Status.PLATFORM_NOT_AVAILABLE);
            verifyNoInteractions(creditClient, catalogV1Service);
        }

        @Test
        @DisplayName("missing pricing version refuses the call - no free ride unlike local execution")
        void missingPricingIsRejected() {
            stubResolvedApiAndTool("api_key");
            when(credentialClient.findPlatformCredentialByName("openweather"))
                    .thenReturn(Optional.of(credential("cloud")));
            when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID))
                    .thenReturn(Optional.empty());

            RelayResult result = service.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, relayRequest());

            assertThat(result.status()).isEqualTo(RelayResult.Status.PLATFORM_NOT_AVAILABLE);
            verifyNoInteractions(creditClient, catalogV1Service);
        }

        @Test
        @DisplayName("a generation priced only by the credential-wide default is refused")
        void generationOnTheVersionDefaultIsRefused() {
            // Positive amount, flat shape, every other guard satisfied. What is
            // missing is a decision: a catch-all authored for the ordinary
            // endpoints of an API would sell a video for the price of a lookup.
            stubResolvedApiAndTool("api_key", "{\"kind\":\"video\"}");
            when(credentialClient.findPlatformCredentialByName("openweather"))
                    .thenReturn(Optional.of(credential("cloud")));
            when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID))
                    .thenReturn(Optional.of(pricingVersion(MARKUP)));
            FrozenMarkupDto fromDefault = frozenMarkup(MARKUP);
            fromDefault.setPricedByPublishedRow(false);
            when(credentialClient.resolveFrozenMarkup(PRICING_VERSION_ID, TOOL_ID, null, null, null))
                    .thenReturn(Optional.of(fromDefault));

            RelayResult result = service.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, relayRequest());

            assertThat(result.status()).isEqualTo(RelayResult.Status.PLATFORM_NOT_AVAILABLE);
            verifyNoInteractions(creditClient, catalogV1Service);
        }

        @Test
        @DisplayName("an ORDINARY endpoint on that same default still relays, which is what a default is for")
        void ordinaryToolOnTheVersionDefaultStillRelays() {
            // The stricter rule applies to generations only. Catching every tool
            // priced by the default would break the relayed traffic the default
            // was published for.
            stubResolvedApiAndTool("api_key");
            when(credentialClient.findPlatformCredentialByName("openweather"))
                    .thenReturn(Optional.of(credential("cloud")));
            when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID))
                    .thenReturn(Optional.of(pricingVersion(MARKUP)));
            FrozenMarkupDto fromDefault = frozenMarkup(MARKUP);
            fromDefault.setPricedByPublishedRow(false);
            when(credentialClient.resolveFrozenMarkup(PRICING_VERSION_ID, TOOL_ID, null, null, null))
                    .thenReturn(Optional.of(fromDefault));
            stubSuccessfulReserve();
            when(catalogV1Service.executeTool(anyString(), any(), anyString(), isNull(), anyString()))
                    .thenReturn(ToolExecutionResponse.builder().success(true).build());

            RelayResult result = service.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, relayRequest());

            assertThat(result.status()).isEqualTo(RelayResult.Status.OK);
        }

        @Test
        @DisplayName("a generation whose answer never mentions the flag still relays, so a rolling "
                + "deploy does not become an outage")
        void generationWithASilentAnswerStillRelays() {
            stubResolvedApiAndTool("api_key", "{\"kind\":\"video\"}");
            when(credentialClient.findPlatformCredentialByName("openweather"))
                    .thenReturn(Optional.of(credential("cloud")));
            when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID))
                    .thenReturn(Optional.of(pricingVersion(MARKUP)));
            FrozenMarkupDto silent = frozenMarkup(MARKUP);
            silent.setPricedByPublishedRow(null);
            when(credentialClient.resolveFrozenMarkup(PRICING_VERSION_ID, TOOL_ID, null, null, null))
                    .thenReturn(Optional.of(silent));
            stubSuccessfulReserve();
            when(catalogV1Service.executeTool(anyString(), any(), anyString(), isNull(), anyString()))
                    .thenReturn(ToolExecutionResponse.builder().success(true).build());

            RelayResult result = service.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, relayRequest());

            assertThat(result.status()).isEqualTo(RelayResult.Status.OK);
        }

        @Test
        @DisplayName("a generation priced FOR ITS OWN endpoint relays, so the guard is not anti-generation")
        void generationWithItsOwnPublishedPriceRelays() {
            stubResolvedApiAndTool("api_key", "{\"kind\":\"video\"}");
            when(credentialClient.findPlatformCredentialByName("openweather"))
                    .thenReturn(Optional.of(credential("cloud")));
            when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID))
                    .thenReturn(Optional.of(pricingVersion(MARKUP)));
            FrozenMarkupDto published = frozenMarkup(MARKUP);
            published.setPricedByPublishedRow(true);
            when(credentialClient.resolveFrozenMarkup(PRICING_VERSION_ID, TOOL_ID, null, null, null))
                    .thenReturn(Optional.of(published));
            stubSuccessfulReserve();
            when(catalogV1Service.executeTool(anyString(), any(), anyString(), isNull(), anyString()))
                    .thenReturn(ToolExecutionResponse.builder().success(true).build());

            RelayResult result = service.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, relayRequest());

            assertThat(result.status()).isEqualTo(RelayResult.Status.OK);
            assertThat(result.billedCredits()).isEqualByComparingTo(MARKUP);
        }

        @Test
        @DisplayName("a per-unit price refuses the call, because this path cannot measure it")
        void perUnitPriceIsRejected() {
            // This path carries no quantity, so a per-second row resolves to the
            // price of ONE second. Charging it would sell a ten second video for
            // a tenth of its price, silently. An amount nobody could measure is
            // not a price, so refuse and let the owner publish a flat rate or
            // route the model through the generation surface, which measures.
            stubResolvedApiAndTool("api_key");
            when(credentialClient.findPlatformCredentialByName("openweather"))
                    .thenReturn(Optional.of(credential("cloud")));
            when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID))
                    .thenReturn(Optional.of(pricingVersion(new BigDecimal("60"))));
            FrozenMarkupDto perSecond = frozenMarkup(new BigDecimal("60"));
            perSecond.setPriceUnit("second");
            perSecond.setUnitCredits(new BigDecimal("60"));
            when(credentialClient.resolveFrozenMarkup(PRICING_VERSION_ID, TOOL_ID, null, null, null))
                    .thenReturn(Optional.of(perSecond));

            RelayResult result = service.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, relayRequest());

            assertThat(result.status()).isEqualTo(RelayResult.Status.PLATFORM_NOT_AVAILABLE);
            verifyNoInteractions(creditClient, catalogV1Service);
        }

        @Test
        @DisplayName("a FLAT price still relays, so the refusal above is not a blanket block")
        void flatPriceStillRelays() {
            // The guard must fire on the SHAPE of the row, not on any row that
            // carries a unit field. A flat price is the normal case; if it were
            // caught too, every relayed call would break.
            stubResolvedApiAndTool("api_key");
            when(credentialClient.findPlatformCredentialByName("openweather"))
                    .thenReturn(Optional.of(credential("cloud")));
            when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID))
                    .thenReturn(Optional.of(pricingVersion(MARKUP)));
            FrozenMarkupDto flat = frozenMarkup(MARKUP);
            flat.setPriceUnit("call");
            flat.setUnitCredits(BigDecimal.ZERO);
            when(credentialClient.resolveFrozenMarkup(PRICING_VERSION_ID, TOOL_ID, null, null, null))
                    .thenReturn(Optional.of(flat));
            stubSuccessfulReserve();
            when(catalogV1Service.executeTool(anyString(), any(), anyString(), isNull(), anyString()))
                    .thenReturn(ToolExecutionResponse.builder().success(true).build());

            RelayResult result = service.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, relayRequest());

            assertThat(result.status()).isEqualTo(RelayResult.Status.OK);
            assertThat(result.billedCredits()).isEqualByComparingTo(MARKUP);
        }

        @Test
        @DisplayName("zero markup refuses the call - no free ride")
        void zeroMarkupIsRejected() {
            stubResolvedApiAndTool("api_key");
            when(credentialClient.findPlatformCredentialByName("openweather"))
                    .thenReturn(Optional.of(credential("cloud")));
            when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID))
                    .thenReturn(Optional.of(pricingVersion(BigDecimal.ZERO)));
            when(credentialClient.resolveFrozenMarkup(PRICING_VERSION_ID, TOOL_ID, null, null, null))
                    .thenReturn(Optional.of(frozenMarkup(BigDecimal.ZERO)));

            RelayResult result = service.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, relayRequest());

            assertThat(result.status()).isEqualTo(RelayResult.Status.PLATFORM_NOT_AVAILABLE);
            verifyNoInteractions(creditClient, catalogV1Service);
        }

        @Test
        @DisplayName("refused reservation yields INSUFFICIENT_CREDITS with the delinquent flag and NO upstream execution")
        void refusedReserveNeverExecutesUpstream() {
            stubResolvedApiAndTool("api_key");
            stubCredentialAndPricing();
            when(creditClient.scopeReserve(anyLong(), anyString(), anyString(), anyString(),
                    any(), isNull(), anyInt(), anyString(), anyString(), anyBoolean()))
                    .thenReturn(new ScopeReserveResult(false, "account delinquent", true, BigDecimal.ZERO));

            RelayResult result = service.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, relayRequest());

            assertThat(result.status()).isEqualTo(RelayResult.Status.INSUFFICIENT_CREDITS);
            assertThat(result.delinquent()).isTrue();
            assertThat(result.error()).isEqualTo("account delinquent");
            verifyNoInteractions(catalogV1Service);
        }
    }

    @Nested
    @DisplayName("reserve → execute → commit/release lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("successful upstream call commits exactly the reserved markup amount")
        void successCommitsReservedAmount() {
            stubResolvedApiAndTool("api_key");
            stubCredentialAndPricing();
            stubSuccessfulReserve();
            ToolExecutionResponse upstream = ToolExecutionResponse.builder().success(true).build();
            when(catalogV1Service.executeTool(anyString(), any(), anyString(), isNull(), anyString()))
                    .thenReturn(upstream);

            RelayResult result = service.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, relayRequest());

            assertThat(result.status()).isEqualTo(RelayResult.Status.OK);
            assertThat(result.response()).isSameAs(upstream);
            assertThat(result.billedCredits()).isEqualByComparingTo(MARKUP);
            ArgumentCaptor<String> sourceId = ArgumentCaptor.forClass(String.class);
            verify(creditClient).scopeCommit(sourceId.capture(), eq(MARKUP), eq("OpenWeather"), eq(TOOL_SLUG));
            verify(creditClient, never()).scopeRelease(anyString(), anyString());
            assertThat(sourceId.getValue())
                    .startsWith(SourceIdBuilder.MARKUP_DEBIT_PREFIX + ":CE:");
        }

        @Test
        @DisplayName("upstream failure releases the reservation and never commits - the CE user is not billed")
        void upstreamFailureReleases() {
            stubResolvedApiAndTool("api_key");
            stubCredentialAndPricing();
            stubSuccessfulReserve();
            ToolExecutionResponse upstream = ToolExecutionResponse.builder()
                    .success(false).error("401 from upstream").build();
            when(catalogV1Service.executeTool(anyString(), any(), anyString(), isNull(), anyString()))
                    .thenReturn(upstream);

            RelayResult result = service.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, relayRequest());

            // Upstream error is still an OK relay outcome (200 + success=false).
            assertThat(result.status()).isEqualTo(RelayResult.Status.OK);
            assertThat(result.response().isSuccess()).isFalse();
            assertThat(result.billedCredits()).isEqualByComparingTo(BigDecimal.ZERO);
            verify(creditClient).scopeRelease(anyString(), anyString());
            verify(creditClient, never()).scopeCommit(anyString(), any(), anyString(), anyString());
        }

        @Test
        @DisplayName("execution-layer exception releases the reservation and relays a failed result")
        void executionExceptionReleases() {
            stubResolvedApiAndTool("api_key");
            stubCredentialAndPricing();
            stubSuccessfulReserve();
            when(catalogV1Service.executeTool(anyString(), any(), anyString(), isNull(), anyString()))
                    .thenThrow(new IllegalStateException("boom"));

            RelayResult result = service.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, relayRequest());

            assertThat(result.status()).isEqualTo(RelayResult.Status.OK);
            assertThat(result.response().isSuccess()).isFalse();
            verify(creditClient).scopeRelease(anyString(), anyString());
            verify(creditClient, never()).scopeCommit(anyString(), any(), anyString(), anyString());
            assertThat(CredentialModeContext.getExplicitSource()).isNull();
        }

        @Test
        @DisplayName("reserve sourceId is server-generated: markup prefix + :CE:, unique per call, never derived from CE input")
        void sourceIdIsServerGenerated() {
            stubResolvedApiAndTool("api_key");
            stubCredentialAndPricing();
            stubSuccessfulReserve();
            when(catalogV1Service.executeTool(anyString(), any(), anyString(), isNull(), anyString()))
                    .thenReturn(ToolExecutionResponse.builder().success(true).build());
            CeCatalogRelayRequest request = CeCatalogRelayRequest.builder()
                    .parameters(Map.of("attacker_key", "replay-me"))
                    .build();

            service.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, request);
            service.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, request);

            ArgumentCaptor<String> sourceIds = ArgumentCaptor.forClass(String.class);
            verify(creditClient, org.mockito.Mockito.times(2)).scopeReserve(
                    eq(CLOUD_USER_ID), sourceIds.capture(), eq("OpenWeather"), eq(TOOL_SLUG),
                    eq(MARKUP), isNull(), eq(10),
                    eq(CeCatalogRelayService.CE_RELAY_SCOPE_KIND), eq(INSTALL_ID), eq(false));
            assertThat(sourceIds.getAllValues()).hasSize(2);
            assertThat(sourceIds.getAllValues().get(0))
                    .isNotEqualTo(sourceIds.getAllValues().get(1));
            for (String id : sourceIds.getAllValues()) {
                assertThat(id).startsWith(SourceIdBuilder.MARKUP_DEBIT_PREFIX + ":CE:");
                assertThat(id).doesNotContain("replay-me");
                assertThat(id).doesNotContain(INSTALL_ID);
            }
        }

        @Test
        @DisplayName("execution request forces platform source with the server-resolved credential and carries no billing scope")
        void executionRequestForcesPlatformSource() {
            stubResolvedApiAndTool("api_key");
            stubCredentialAndPricing();
            stubSuccessfulReserve();
            ArgumentCaptor<ToolExecutionRequest> requestCaptor =
                    ArgumentCaptor.forClass(ToolExecutionRequest.class);
            when(catalogV1Service.executeTool(eq(API_SLUG + "/" + TOOL_SLUG), requestCaptor.capture(),
                    eq(String.valueOf(CLOUD_USER_ID)), isNull(), anyString()))
                    .thenReturn(ToolExecutionResponse.builder().success(true).build());

            service.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, relayRequest());

            ToolExecutionRequest forwarded = requestCaptor.getValue();
            assertThat(forwarded.getCredentialSource()).isEqualTo("platform");
            assertThat(forwarded.getPlatformCredentialId()).isEqualTo(CREDENTIAL_ID);
            assertThat(forwarded.getParameters()).containsEntry("city", "Paris");
            assertThat(forwarded.getSelectedCredentialId()).isNull();
            assertThat(forwarded.getBillingScopeKind()).isNull();
            assertThat(forwarded.getBillingScopeId()).isNull();
            assertThat(forwarded.getBillingStepId()).isNull();
            // ...and says so explicitly, rather than leaving the absent scope to
            // be read as "nobody bills this call". Without it the execution layer
            // refuses a resold generation this service has already reserved.
            assertThat(forwarded.getBillingOwnedByCaller()).isTrue();
        }

        @Test
        @DisplayName("CredentialModeContext is platform-forced during execution and cleared afterwards")
        void credentialModeContextIsSetThenCleared() {
            stubResolvedApiAndTool("api_key");
            stubCredentialAndPricing();
            stubSuccessfulReserve();
            AtomicReference<String> sourceDuringExecution = new AtomicReference<>();
            AtomicReference<Long> selectedDuringExecution = new AtomicReference<>(0L);
            when(catalogV1Service.executeTool(anyString(), any(), anyString(), isNull(), anyString()))
                    .thenAnswer(invocation -> {
                        sourceDuringExecution.set(CredentialModeContext.getExplicitSource());
                        selectedDuringExecution.set(CredentialModeContext.getSelectedCredentialId());
                        return ToolExecutionResponse.builder().success(true).build();
                    });

            service.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, relayRequest());

            assertThat(sourceDuringExecution.get()).isEqualTo("platform");
            assertThat(selectedDuringExecution.get()).isNull();
            assertThat(CredentialModeContext.getExplicitSource()).isNull();
            assertThat(CredentialModeContext.getSelectedCredentialId()).isNull();
            assertThat(CredentialModeContext.getOverride()).isNull();
        }

        @Test
        @DisplayName("the install's provider-retry budget reaches the execution, so a relayed step "
                + "can still say 'do not retry underneath me'")
        void providerRetryBudgetIsForwarded() {
            // The relay is the SECOND entry point into the execution funnel. Without this the
            // feature works in the cloud edition and silently does nothing for a self-hosted
            // install: a CE author who paces their own loop has the cloud multiply their requests
            // to a provider that asked them to slow down.
            stubResolvedApiAndTool("api_key");
            stubCredentialAndPricing();
            stubSuccessfulReserve();
            ArgumentCaptor<ToolExecutionRequest> requestCaptor =
                    ArgumentCaptor.forClass(ToolExecutionRequest.class);
            when(catalogV1Service.executeTool(anyString(), requestCaptor.capture(), anyString(),
                    isNull(), anyString()))
                    .thenReturn(ToolExecutionResponse.builder().success(true).build());

            service.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG,
                    CeCatalogRelayRequest.builder()
                            .parameters(Map.of("city", "Paris"))
                            .providerRetryMaxWaitSeconds(0)
                            .build());

            assertThat(requestCaptor.getValue().getProviderRetryMaxWaitSeconds())
                    .as("0 is the whole point of the field and the value a null-ish copy would lose")
                    .isEqualTo(0);
        }

        @Test
        @DisplayName("a relayed call that says nothing leaves the cloud's own budget in place")
        void anAbsentBudgetIsNotInvented() {
            stubResolvedApiAndTool("api_key");
            stubCredentialAndPricing();
            stubSuccessfulReserve();
            ArgumentCaptor<ToolExecutionRequest> requestCaptor =
                    ArgumentCaptor.forClass(ToolExecutionRequest.class);
            when(catalogV1Service.executeTool(anyString(), requestCaptor.capture(), anyString(),
                    isNull(), anyString()))
                    .thenReturn(ToolExecutionResponse.builder().success(true).build());

            service.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, relayRequest());

            assertThat(requestCaptor.getValue().getProviderRetryMaxWaitSeconds()).isNull();
        }

        @Test
        @DisplayName("ProviderRetryContext is opened for the call and cleared afterwards, so a "
                + "pooled thread cannot carry a count into the next tenant's request")
        void providerRetryContextIsOpenedThenCleared() {
            // The count does not self-heal the way the budget does: every entry point sets a budget,
            // nothing resets a count. One left behind is reported on the NEXT request through that
            // thread as a re-send that never happened, on a step whose only evidence of a wait is
            // that number.
            stubResolvedApiAndTool("api_key");
            stubCredentialAndPricing();
            stubSuccessfulReserve();
            ProviderRetryContext.recordRetry();
            AtomicReference<Long> budgetDuringExecution = new AtomicReference<>(-1L);
            AtomicReference<Integer> countDuringExecution = new AtomicReference<>(-1);
            when(catalogV1Service.executeTool(anyString(), any(), anyString(), isNull(), anyString()))
                    .thenAnswer(invocation -> {
                        budgetDuringExecution.set(ProviderRetryContext.getMaxWaitMs());
                        countDuringExecution.set(ProviderRetryContext.getRetries());
                        return ToolExecutionResponse.builder().success(true).build();
                    });

            service.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG,
                    CeCatalogRelayRequest.builder()
                            .parameters(Map.of("city", "Paris"))
                            .providerRetryMaxWaitSeconds(0)
                            .build());

            assertThat(budgetDuringExecution.get())
                    .as("the budget is bound to the thread for the duration of the call")
                    .isEqualTo(0L);
            assertThat(countDuringExecution.get())
                    .as("the stale count from before this call was reset on the way in")
                    .isZero();
            assertThat(ProviderRetryContext.getMaxWaitMs())
                    .as("and nothing is left on the thread when it goes back to the pool")
                    .isNull();
            assertThat(ProviderRetryContext.getRetries()).isZero();
        }
    }

    @Nested
    @DisplayName("platform-info probe")
    class PlatformInfoProbe {

        @Test
        @DisplayName("unknown integration returns the available=false shape, never an error")
        void unknownIntegrationIsUnavailable() {
            when(apiRepository.findByPlatformCredentialName("nope")).thenReturn(Optional.empty());
            when(credentialClient.findPlatformCredentialByName("nope")).thenReturn(Optional.empty());

            PlatformInfo info = service.platformInfo("nope", null);

            assertThat(info.available()).isFalse();
            assertThat(info.platformCredentialId()).isNull();
            assertThat(info.hasPricing()).isFalse();
            assertThat(info.markupCredits()).isNull();
            assertThat(info.relayEligible()).isFalse();
        }

        @Test
        @DisplayName("per-tool markup is surfaced when apiToolId is given")
        void perToolMarkupSurfaced() {
            when(apiRepository.findByPlatformCredentialName("openweather"))
                    .thenReturn(Optional.of(api("api_key")));
            when(credentialClient.findPlatformCredentialByName("openweather"))
                    .thenReturn(Optional.of(credential("cloud")));
            when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID))
                    .thenReturn(Optional.of(pricingVersion(MARKUP)));
            when(credentialClient.resolveFrozenMarkup(PRICING_VERSION_ID, TOOL_ID, null, null, null))
                    .thenReturn(Optional.of(frozenMarkup(new BigDecimal("0.50"))));

            PlatformInfo info = service.platformInfo("openweather", TOOL_ID);

            assertThat(info.available()).isTrue();
            assertThat(info.platformCredentialId()).isEqualTo(CREDENTIAL_ID);
            assertThat(info.hasPricing()).isTrue();
            assertThat(info.markupCredits()).isEqualTo("0.50");
            assertThat(info.relayEligible()).isTrue();
        }

        @Test
        @DisplayName("a generation is quoted for the MODEL asked about, not for an endpoint row it does not have")
        void quotesThePerModelRow() {
            // Every seeded generation is priced per model, so a probe that
            // resolved only an endpoint-wide row answered "nothing published"
            // for a model the relay then executed and charged at its real rate.
            // A self-hosted install read "not sold on the platform key" beside
            // a button that billed the customer.
            when(apiRepository.findByPlatformCredentialName("seedance"))
                    .thenReturn(Optional.of(api("api_key")));
            when(credentialClient.findPlatformCredentialByName("seedance"))
                    .thenReturn(Optional.of(credential("cloud")));
            when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID))
                    .thenReturn(Optional.of(pricingVersion(MARKUP)));
            // Resolvable ONLY when the model and the size travel with the lookup.
            when(credentialClient.resolveFrozenMarkup(
                    PRICING_VERSION_ID, TOOL_ID, "seedance-2.0", new BigDecimal("10"), null))
                    .thenReturn(Optional.of(frozenMarkup(new BigDecimal("600"))));

            PlatformInfo info = service.platformInfo(
                    "seedance", TOOL_ID, "seedance-2.0", new BigDecimal("10"));

            assertThat(info.hasPricing()).isTrue();
            assertThat(info.markupCredits()).isEqualTo("600");
        }

        @Test
        @DisplayName("an ordinary endpoint is still quoted with no model and no size, exactly as before")
        void anOrdinaryEndpointIsUnchanged() {
            // The two extra arguments are optional and must stay a no-op for
            // the 1000+ endpoints that carry no generation descriptor.
            when(apiRepository.findByPlatformCredentialName("openweather"))
                    .thenReturn(Optional.of(api("api_key")));
            when(credentialClient.findPlatformCredentialByName("openweather"))
                    .thenReturn(Optional.of(credential("cloud")));
            when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID))
                    .thenReturn(Optional.of(pricingVersion(MARKUP)));
            when(credentialClient.resolveFrozenMarkup(PRICING_VERSION_ID, TOOL_ID, null, null, null))
                    .thenReturn(Optional.of(frozenMarkup(new BigDecimal("0.50"))));

            PlatformInfo info = service.platformInfo("openweather", TOOL_ID);

            assertThat(info.markupCredits()).isEqualTo("0.50");
        }

        @Test
        @DisplayName("oauth2 integration reports relayEligible=false even with a credential configured")
        void oauthIntegrationNotRelayEligible() {
            when(apiRepository.findByPlatformCredentialName("openweather"))
                    .thenReturn(Optional.of(api("oauth2")));
            when(credentialClient.findPlatformCredentialByName("openweather"))
                    .thenReturn(Optional.of(credential("cloud")));
            when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID))
                    .thenReturn(Optional.of(pricingVersion(MARKUP)));

            PlatformInfo info = service.platformInfo("openweather", null);

            assertThat(info.available()).isTrue();
            assertThat(info.hasPricing()).isTrue();
            assertThat(info.markupCredits()).isEqualTo(MARKUP.toPlainString());
            assertThat(info.relayEligible()).isFalse();
        }

        @Test
        @DisplayName("bridge credential reports available=false")
        void bridgeCredentialUnavailable() {
            when(apiRepository.findByPlatformCredentialName("openweather"))
                    .thenReturn(Optional.of(api("api_key")));
            when(credentialClient.findPlatformCredentialByName("openweather"))
                    .thenReturn(Optional.of(credential("bridge")));

            PlatformInfo info = service.platformInfo("openweather", null);

            assertThat(info.available()).isFalse();
            assertThat(info.relayEligible()).isTrue();
        }
    }

    @Nested
    @DisplayName("guards used by the controller")
    class Guards {

        @Test
        @DisplayName("rate limiter allows up to the per-minute limit and refuses the next call")
        void rateLimiterEnforcesWindow() {
            CeCatalogRelayService limited = new CeCatalogRelayService(apiRepository, apiToolRepository,
                    credentialClient, creditClient, catalogV1Service, new ObjectMapper(), 10, 2);

            assertThat(limited.tryAcquire(INSTALL_ID)).isTrue();
            assertThat(limited.tryAcquire(INSTALL_ID)).isTrue();
            assertThat(limited.tryAcquire(INSTALL_ID)).isFalse();
            // A different install has its own window.
            assertThat(limited.tryAcquire("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")).isTrue();
        }

        @Test
        @DisplayName("rate limit of 0 disables the limiter")
        void zeroRateLimitDisables() {
            CeCatalogRelayService unlimited = new CeCatalogRelayService(apiRepository, apiToolRepository,
                    credentialClient, creditClient, catalogV1Service, new ObjectMapper(), 10, 0);

            for (int i = 0; i < 500; i++) {
                assertThat(unlimited.tryAcquire(INSTALL_ID)).isTrue();
            }
        }

        @Test
        @DisplayName("parameters over 512 KB are flagged too large; small or absent parameters are not")
        void parametersSizeGuard() {
            assertThat(service.parametersTooLarge(null)).isFalse();
            assertThat(service.parametersTooLarge(Map.of())).isFalse();
            assertThat(service.parametersTooLarge(Map.of("q", "small"))).isFalse();

            Map<String, Object> huge = new HashMap<>();
            huge.put("blob", "x".repeat(CeCatalogRelayService.MAX_PARAMETERS_BYTES + 1));
            assertThat(service.parametersTooLarge(huge)).isTrue();
        }
    }
}
