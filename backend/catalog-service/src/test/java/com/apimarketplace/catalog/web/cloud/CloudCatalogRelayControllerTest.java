package com.apimarketplace.catalog.web.cloud;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.dto.CeLinkEntitlementsResult;
import com.apimarketplace.catalog.domain.dto.CeCatalogRelayRequest;
import com.apimarketplace.catalog.domain.dto.ToolExecutionResponse;
import com.apimarketplace.catalog.service.relay.CeCatalogRelayService;
import com.apimarketplace.catalog.service.relay.CeCatalogRelayService.PlatformInfo;
import com.apimarketplace.catalog.service.relay.CeCatalogRelayService.RelayResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Gate ordering and error-contract tests for the cloud-side CE catalog relay:
 * 401 → 403 (link) → 402 (subscription) → 429 → 400, then the typed service
 * outcomes mapped onto the frozen HTTP contract.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CloudCatalogRelayController (cloud)")
class CloudCatalogRelayControllerTest {

    private static final long CLOUD_USER_ID = 42L;
    private static final String INSTALL_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String API_SLUG = "openweather";
    private static final String TOOL_SLUG = "current-weather";

    @Mock private AuthClient authClient;
    @Mock private CeCatalogRelayService relayService;

    private CloudCatalogRelayController controller;

    @BeforeEach
    void setUp() {
        controller = new CloudCatalogRelayController(authClient, relayService);
    }

    private static CeCatalogRelayRequest request() {
        return CeCatalogRelayRequest.builder()
                .parameters(Map.of("city", "Paris"))
                .build();
    }

    private void stubActiveLink() {
        when(authClient.userOwnsActiveCeLink(String.valueOf(CLOUD_USER_ID), INSTALL_ID))
                .thenReturn(true);
    }

    private void stubSubscription(CeLinkEntitlementsResult entitlements) {
        when(authClient.ceLinkEntitlements(String.valueOf(CLOUD_USER_ID), INSTALL_ID))
                .thenReturn(entitlements);
    }

    private ResponseEntity<?> execute(CeCatalogRelayRequest body) {
        return controller.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, body);
    }

    @Nested
    @DisplayName("execute gates")
    class ExecuteGates {

        @Test
        @DisplayName("missing user id yields 401 AUTHENTICATION_REQUIRED before any other check")
        void missingUserIdIs401() {
            ResponseEntity<?> response =
                    controller.execute(null, INSTALL_ID, API_SLUG, TOOL_SLUG, request());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).isEqualTo(Map.of("error", "AUTHENTICATION_REQUIRED"));
            verifyNoInteractions(authClient, relayService);
        }

        @Test
        @DisplayName("install not owned/active yields 403 CE_LINK_NOT_ACTIVE and never reaches the service")
        void inactiveLinkIs403() {
            when(authClient.userOwnsActiveCeLink(String.valueOf(CLOUD_USER_ID), INSTALL_ID))
                    .thenReturn(false);

            ResponseEntity<?> response = execute(request());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isEqualTo(Map.of("error", "CE_LINK_NOT_ACTIVE"));
            verifyNoInteractions(relayService);
        }

        @Test
        @DisplayName("__NONE__ plan yields 402 SUBSCRIPTION_REQUIRED and never reaches the service")
        void noSubscriptionIs402() {
            stubActiveLink();
            stubSubscription(CeLinkEntitlementsResult.none());

            ResponseEntity<?> response = execute(request());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
            assertThat(response.getBody()).isEqualTo(Map.of("error", "SUBSCRIPTION_REQUIRED"));
            verifyNoInteractions(relayService);
        }

        @Test
        @DisplayName("FREE plan yields 402 SUBSCRIPTION_REQUIRED (free is not a paid subscription)")
        void freePlanIs402() {
            stubActiveLink();
            stubSubscription(new CeLinkEntitlementsResult("FREE", false));

            ResponseEntity<?> response = execute(request());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
            assertThat(response.getBody()).isEqualTo(Map.of("error", "SUBSCRIPTION_REQUIRED"));
            verifyNoInteractions(relayService);
        }

        @Test
        @DisplayName("exhausted rate window yields 429 RATE_LIMITED and never executes")
        void rateLimitedIs429() {
            stubActiveLink();
            stubSubscription(new CeLinkEntitlementsResult("PRO", true));
            when(relayService.tryAcquire(INSTALL_ID)).thenReturn(false);

            ResponseEntity<?> response = execute(request());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(response.getBody()).isEqualTo(Map.of("error", "RATE_LIMITED"));
            verify(relayService, never()).execute(anyLong(), anyString(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("missing body yields 400 INVALID_RELAY_REQUEST")
        void missingBodyIs400() {
            stubActiveLink();
            stubSubscription(new CeLinkEntitlementsResult("PRO", true));
            when(relayService.tryAcquire(INSTALL_ID)).thenReturn(true);

            ResponseEntity<?> response = execute(null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isEqualTo(Map.of("error", "INVALID_RELAY_REQUEST"));
            verify(relayService, never()).execute(anyLong(), anyString(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("blank slugs yield 400 INVALID_RELAY_REQUEST")
        void blankSlugIs400() {
            stubActiveLink();
            stubSubscription(new CeLinkEntitlementsResult("PRO", true));
            when(relayService.tryAcquire(INSTALL_ID)).thenReturn(true);

            ResponseEntity<?> response =
                    controller.execute(CLOUD_USER_ID, INSTALL_ID, "  ", TOOL_SLUG, request());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(relayService, never()).execute(anyLong(), anyString(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("oversized parameters yield 400 INVALID_RELAY_REQUEST")
        void oversizedParametersAre400() {
            stubActiveLink();
            stubSubscription(new CeLinkEntitlementsResult("PRO", true));
            when(relayService.tryAcquire(INSTALL_ID)).thenReturn(true);
            when(relayService.parametersTooLarge(any())).thenReturn(true);

            ResponseEntity<?> response = execute(request());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isEqualTo(Map.of("error", "INVALID_RELAY_REQUEST"));
            verify(relayService, never()).execute(anyLong(), anyString(), anyString(), anyString(), any());
        }
    }

    @Nested
    @DisplayName("execute outcome mapping")
    class ExecuteOutcomes {

        private void openAllGates() {
            stubActiveLink();
            stubSubscription(new CeLinkEntitlementsResult("PRO", true));
            when(relayService.tryAcquire(INSTALL_ID)).thenReturn(true);
            when(relayService.parametersTooLarge(any())).thenReturn(false);
        }

        private void stubOutcome(RelayResult result) {
            when(relayService.execute(CLOUD_USER_ID, INSTALL_ID, API_SLUG, TOOL_SLUG, request()))
                    .thenReturn(result);
        }

        @Test
        @DisplayName("successful upstream response is passed through as 200")
        void successPassesThrough() {
            openAllGates();
            ToolExecutionResponse upstream = ToolExecutionResponse.builder()
                    .success(true).result(Map.of("temp", 21)).build();
            stubOutcome(new RelayResult(RelayResult.Status.OK, upstream, null, false, new BigDecimal("0.25")));

            ResponseEntity<?> response = execute(request());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isSameAs(upstream);
        }

        @Test
        @DisplayName("upstream success=false is STILL a 200 pass-through - an upstream error is a valid relayed result")
        void upstreamFailurePassesThroughAs200() {
            openAllGates();
            ToolExecutionResponse upstream = ToolExecutionResponse.builder()
                    .success(false).error("upstream 500").build();
            stubOutcome(new RelayResult(RelayResult.Status.OK, upstream, null, false, BigDecimal.ZERO));

            ResponseEntity<?> response = execute(request());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isSameAs(upstream);
        }

        @Test
        @DisplayName("TOOL_NOT_FOUND maps to 404")
        void toolNotFoundMapsTo404() {
            openAllGates();
            stubOutcome(new RelayResult(RelayResult.Status.TOOL_NOT_FOUND, null, null, false, BigDecimal.ZERO));

            ResponseEntity<?> response = execute(request());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isEqualTo(Map.of("error", "TOOL_NOT_FOUND"));
        }

        @Test
        @DisplayName("OAUTH_NOT_RELAYABLE maps to 403")
        void oauthMapsTo403() {
            openAllGates();
            stubOutcome(new RelayResult(RelayResult.Status.OAUTH_NOT_RELAYABLE, null, null, false, BigDecimal.ZERO));

            ResponseEntity<?> response = execute(request());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isEqualTo(Map.of("error", "OAUTH_NOT_RELAYABLE"));
        }

        @Test
        @DisplayName("PLATFORM_NOT_AVAILABLE maps to 403")
        void platformNotAvailableMapsTo403() {
            openAllGates();
            stubOutcome(new RelayResult(RelayResult.Status.PLATFORM_NOT_AVAILABLE, null, null, false, BigDecimal.ZERO));

            ResponseEntity<?> response = execute(request());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isEqualTo(Map.of("error", "PLATFORM_NOT_AVAILABLE"));
        }

        @Test
        @DisplayName("INSUFFICIENT_CREDITS maps to 402 with the delinquent flag")
        void insufficientCreditsMapsTo402WithDelinquentFlag() {
            openAllGates();
            stubOutcome(new RelayResult(RelayResult.Status.INSUFFICIENT_CREDITS, null,
                    "account delinquent", true, BigDecimal.ZERO));

            ResponseEntity<?> response = execute(request());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
            assertThat(response.getBody()).isEqualTo(
                    Map.of("error", "INSUFFICIENT_CREDITS", "delinquent", true));
        }
    }

    @Nested
    @DisplayName("platform-info")
    class PlatformInfoEndpoint {

        @Test
        @DisplayName("requires authentication and an active link, like execute")
        void requiresAuthAndLink() {
            ResponseEntity<Map<String, Object>> unauthenticated =
                    controller.platformInfo(null, INSTALL_ID, "openweather", null, null, null, null);
            assertThat(unauthenticated.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            when(authClient.userOwnsActiveCeLink(String.valueOf(CLOUD_USER_ID), INSTALL_ID))
                    .thenReturn(false);
            ResponseEntity<Map<String, Object>> unlinked =
                    controller.platformInfo(CLOUD_USER_ID, INSTALL_ID, "openweather", null, null, null, null);
            assertThat(unlinked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verifyNoInteractions(relayService);
        }

        @Test
        @DisplayName("regression: exhausted rate window yields 429 RATE_LIMITED and never reaches the platform-info lookup")
        void platformInfoRateLimitedIs429() {
            stubActiveLink();
            when(relayService.tryAcquire(INSTALL_ID)).thenReturn(false);

            ResponseEntity<Map<String, Object>> response =
                    controller.platformInfo(CLOUD_USER_ID, INSTALL_ID, "openweather", null, null, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(response.getBody()).isEqualTo(Map.of("error", "RATE_LIMITED"));
            verify(relayService, never()).platformInfo(anyString(), any());
        }

        @Test
        @DisplayName("no subscription gate: a FREE-plan account still gets 200 with subscriptionActive=false for CE upsell")
        void freePlanGets200WithSubscriptionActiveFalse() {
            stubActiveLink();
            stubSubscription(new CeLinkEntitlementsResult("FREE", false));
            when(relayService.tryAcquire(INSTALL_ID)).thenReturn(true);
            when(relayService.platformInfo("openweather", null, null, null, null))
                    .thenReturn(new PlatformInfo("openweather", true, 77L, true, "0.25", true));

            ResponseEntity<Map<String, Object>> response =
                    controller.platformInfo(CLOUD_USER_ID, INSTALL_ID, "openweather", null, null, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .containsEntry("integrationName", "openweather")
                    .containsEntry("available", true)
                    .containsEntry("platformCredentialId", 77L)
                    .containsEntry("hasPricing", true)
                    .containsEntry("markupCredits", "0.25")
                    .containsEntry("subscriptionActive", false)
                    .containsEntry("relayEligible", true);
        }

        @Test
        @DisplayName("an absurd price factor is DROPPED, so the install sees the published rate")
        void anAbsurdFactorIsDroppedRatherThanRefused() {
            // Dropped, not refused. This door only READS: a malformed factor must leave the
            // install showing the published rate, which is the true price of a call carrying no
            // surcharge, rather than turning a price panel into an error nobody can act on. And
            // unsanitised, a non-positive value reached the auth leg, which answers 400 - an error
            // on a read, for a value the executing leg would simply have ignored.
            stubActiveLink();
            stubSubscription(new CeLinkEntitlementsResult("PRO", true));
            when(relayService.tryAcquire(INSTALL_ID)).thenReturn(true);
            when(relayService.platformInfo(anyString(), any(), any(), any(), any()))
                    .thenReturn(new PlatformInfo("seedance", true, 7L, true, "100", true));

            ResponseEntity<Map<String, Object>> response = controller.platformInfo(
                    CLOUD_USER_ID, INSTALL_ID, "seedance", null, "seedance-2.0",
                    new BigDecimal("10"), new BigDecimal("-3"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            // The SIZE still travels. Only the factor was dropped, so the quote is the one this
            // path resolved before factors existed rather than no quote at all.
            verify(relayService).platformInfo(
                    "seedance", null, "seedance-2.0", new BigDecimal("10"), null);
        }

        @Test
        @DisplayName("a factor above the descriptor ceiling is dropped for the same reason")
        void aFactorAboveTheCeilingIsDropped() {
            // The ceiling is the descriptor parser's own, and the parser enforces it on all of a
            // model's modifiers TOGETHER, so a factor above it cannot have come from any seed this
            // platform accepts. Quoting it would show an install an amount no descriptor here can
            // produce.
            stubActiveLink();
            stubSubscription(new CeLinkEntitlementsResult("PRO", true));
            when(relayService.tryAcquire(INSTALL_ID)).thenReturn(true);
            when(relayService.platformInfo(anyString(), any(), any(), any(), any()))
                    .thenReturn(new PlatformInfo("seedance", true, 7L, true, "100", true));

            controller.platformInfo(CLOUD_USER_ID, INSTALL_ID, "seedance", null, "seedance-2.0",
                    null, new BigDecimal("101"));

            verify(relayService).platformInfo("seedance", null, "seedance-2.0", null, null);
        }

        @Test
        @DisplayName("ECHOES the factor it quoted with, which a self-hosted reader has no other way to learn")
        void theQuotedFactorIsEchoedBack() {
            // Every other quote door echoes it, and the surfaces gate their entire explanation on
            // that echo: the badge, the "includes Resolution x2" sentence, the note in the
            // parameters menu. This door did not, so on a linked self-hosted install the factor was
            // applied and charged while the reader watched the price change with nothing saying
            // why - the failure this feature exists to remove, surviving on the one edition that
            // cannot read the cloud's logs.
            stubActiveLink();
            stubSubscription(new CeLinkEntitlementsResult("PRO", true));
            when(relayService.tryAcquire(INSTALL_ID)).thenReturn(true);
            when(relayService.platformInfo(anyString(), any(), any(), any(), any()))
                    .thenReturn(new PlatformInfo("seedance", true, 7L, true, "240", true));

            ResponseEntity<Map<String, Object>> response = controller.platformInfo(
                    CLOUD_USER_ID, INSTALL_ID, "seedance", null, "seedance-2.0",
                    new BigDecimal("10"), new BigDecimal("1.2"));

            assertThat(response.getBody()).containsEntry("priceMultiplier", new BigDecimal("1.2"));
        }

        @Test
        @DisplayName("echoes NOTHING for a call at the published rate, so no badge is drawn for a x1")
        void noFactorIsEchoedAtTheBaseRate() {
            stubActiveLink();
            stubSubscription(new CeLinkEntitlementsResult("PRO", true));
            when(relayService.tryAcquire(INSTALL_ID)).thenReturn(true);
            when(relayService.platformInfo(anyString(), any(), any(), any(), any()))
                    .thenReturn(new PlatformInfo("seedance", true, 7L, true, "200", true));

            ResponseEntity<Map<String, Object>> plain = controller.platformInfo(
                    CLOUD_USER_ID, INSTALL_ID, "seedance", null, "seedance-2.0",
                    new BigDecimal("10"), null);
            ResponseEntity<Map<String, Object>> one = controller.platformInfo(
                    CLOUD_USER_ID, INSTALL_ID, "seedance", null, "seedance-2.0",
                    new BigDecimal("10"), BigDecimal.ONE);

            assertThat(plain.getBody()).doesNotContainKey("priceMultiplier");
            assertThat(one.getBody()).doesNotContainKey("priceMultiplier");
        }

        @Test
        @DisplayName("echoes what it USED, not what it was asked: an absurd factor is not reflected")
        void anAbsurdFactorIsNotEchoed() {
            // The echo has to describe the quote, or a surface would explain a surcharge the
            // amount beside it does not contain - the same lie the badge's own gate prevents.
            stubActiveLink();
            stubSubscription(new CeLinkEntitlementsResult("PRO", true));
            when(relayService.tryAcquire(INSTALL_ID)).thenReturn(true);
            when(relayService.platformInfo(anyString(), any(), any(), any(), any()))
                    .thenReturn(new PlatformInfo("seedance", true, 7L, true, "200", true));

            ResponseEntity<Map<String, Object>> response = controller.platformInfo(
                    CLOUD_USER_ID, INSTALL_ID, "seedance", null, "seedance-2.0",
                    new BigDecimal("10"), new BigDecimal("1000000"));

            assertThat(response.getBody()).doesNotContainKey("priceMultiplier");
        }

        @Test
        @DisplayName("a factor a descriptor CAN produce is passed through untouched")
        void anOrdinaryFactorSurvives() {
            // The other half, and the one that pays: sanitising must not become discarding. A
            // relayed 1080p render quoted without its factor states one amount and is billed
            // another, which is the disagreement this whole parameter exists to remove.
            stubActiveLink();
            stubSubscription(new CeLinkEntitlementsResult("PRO", true));
            when(relayService.tryAcquire(INSTALL_ID)).thenReturn(true);
            when(relayService.platformInfo(anyString(), any(), any(), any(), any()))
                    .thenReturn(new PlatformInfo("seedance", true, 7L, true, "240", true));

            controller.platformInfo(CLOUD_USER_ID, INSTALL_ID, "seedance", null, "seedance-2.0",
                    new BigDecimal("10"), new BigDecimal("1.2"));

            verify(relayService).platformInfo(
                    "seedance", null, "seedance-2.0", new BigDecimal("10"), new BigDecimal("1.2"));
        }

        @Test
        @DisplayName("unknown integration returns the available=false shape with null fields, never 404")
        void unknownIntegrationIs200Unavailable() {
            stubActiveLink();
            stubSubscription(new CeLinkEntitlementsResult("PRO", true));
            when(relayService.tryAcquire(INSTALL_ID)).thenReturn(true);
            when(relayService.platformInfo("nope", null, null, null, null))
                    .thenReturn(new PlatformInfo("nope", false, null, false, null, false));

            ResponseEntity<Map<String, Object>> response =
                    controller.platformInfo(CLOUD_USER_ID, INSTALL_ID, "nope", null, null, null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .containsEntry("available", false)
                    .containsEntry("platformCredentialId", null)
                    .containsEntry("markupCredits", null)
                    .containsEntry("subscriptionActive", true)
                    .containsEntry("relayEligible", false);
        }
    }
}
