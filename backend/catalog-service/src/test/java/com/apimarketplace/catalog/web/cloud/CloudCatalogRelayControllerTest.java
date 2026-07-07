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
                    controller.platformInfo(null, INSTALL_ID, "openweather", null);
            assertThat(unauthenticated.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

            when(authClient.userOwnsActiveCeLink(String.valueOf(CLOUD_USER_ID), INSTALL_ID))
                    .thenReturn(false);
            ResponseEntity<Map<String, Object>> unlinked =
                    controller.platformInfo(CLOUD_USER_ID, INSTALL_ID, "openweather", null);
            assertThat(unlinked.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verifyNoInteractions(relayService);
        }

        @Test
        @DisplayName("regression: exhausted rate window yields 429 RATE_LIMITED and never reaches the platform-info lookup")
        void platformInfoRateLimitedIs429() {
            stubActiveLink();
            when(relayService.tryAcquire(INSTALL_ID)).thenReturn(false);

            ResponseEntity<Map<String, Object>> response =
                    controller.platformInfo(CLOUD_USER_ID, INSTALL_ID, "openweather", null);

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
            when(relayService.platformInfo("openweather", null))
                    .thenReturn(new PlatformInfo("openweather", true, 77L, true, "0.25", true));

            ResponseEntity<Map<String, Object>> response =
                    controller.platformInfo(CLOUD_USER_ID, INSTALL_ID, "openweather", null);

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
        @DisplayName("unknown integration returns the available=false shape with null fields, never 404")
        void unknownIntegrationIs200Unavailable() {
            stubActiveLink();
            stubSubscription(new CeLinkEntitlementsResult("PRO", true));
            when(relayService.tryAcquire(INSTALL_ID)).thenReturn(true);
            when(relayService.platformInfo("nope", null))
                    .thenReturn(new PlatformInfo("nope", false, null, false, null, false));

            ResponseEntity<Map<String, Object>> response =
                    controller.platformInfo(CLOUD_USER_ID, INSTALL_ID, "nope", null);

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
