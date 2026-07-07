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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(TOOL_ID);
        tool.setToolSlug(TOOL_SLUG);
        tool.setIsActive(true);
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

    private static FrozenMarkupDto frozenMarkup(BigDecimal markup) {
        FrozenMarkupDto dto = new FrozenMarkupDto();
        dto.setFound(true);
        dto.setPricingVersionId(PRICING_VERSION_ID);
        dto.setEffectiveMarkup(markup);
        return dto;
    }

    private static CeCatalogRelayRequest relayRequest() {
        return CeCatalogRelayRequest.builder()
                .parameters(Map.of("city", "Paris"))
                .build();
    }

    private void stubResolvedApiAndTool(String authType) {
        when(apiRepository.findByApiSlug(API_SLUG)).thenReturn(Optional.of(api(authType)));
        when(apiToolRepository.findByApiIdAndToolSlug(API_ID, TOOL_SLUG))
                .thenReturn(Optional.of(tool()));
    }

    private void stubCredentialAndPricing() {
        when(credentialClient.findPlatformCredentialByName("openweather"))
                .thenReturn(Optional.of(credential("cloud")));
        when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID))
                .thenReturn(Optional.of(pricingVersion(MARKUP)));
        when(credentialClient.resolveFrozenMarkup(PRICING_VERSION_ID, TOOL_ID))
                .thenReturn(Optional.of(frozenMarkup(MARKUP)));
    }

    private void stubSuccessfulReserve() {
        when(creditClient.scopeReserve(anyLong(), anyString(), anyString(), anyString(),
                any(), isNull(), anyInt(), anyString(), anyString(), anyBoolean()))
                .thenReturn(new ScopeReserveResult(true, null, false, BigDecimal.TEN));
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
        @DisplayName("zero markup refuses the call - no free ride")
        void zeroMarkupIsRejected() {
            stubResolvedApiAndTool("api_key");
            when(credentialClient.findPlatformCredentialByName("openweather"))
                    .thenReturn(Optional.of(credential("cloud")));
            when(credentialClient.getLatestPricingVersion(CREDENTIAL_ID))
                    .thenReturn(Optional.of(pricingVersion(BigDecimal.ZERO)));
            when(credentialClient.resolveFrozenMarkup(PRICING_VERSION_ID, TOOL_ID))
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
            when(credentialClient.resolveFrozenMarkup(PRICING_VERSION_ID, TOOL_ID))
                    .thenReturn(Optional.of(frozenMarkup(new BigDecimal("0.50"))));

            PlatformInfo info = service.platformInfo("openweather", TOOL_ID);

            assertThat(info.available()).isTrue();
            assertThat(info.platformCredentialId()).isEqualTo(CREDENTIAL_ID);
            assertThat(info.hasPricing()).isTrue();
            assertThat(info.markupCredits()).isEqualTo("0.50");
            assertThat(info.relayEligible()).isTrue();
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
