package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredentialResponse;
import com.apimarketplace.auth.credential.service.CredentialService;
import com.apimarketplace.auth.credential.service.PlatformCredentialPricingService;
import com.apimarketplace.auth.credential.service.PlatformCredentialService;
import com.apimarketplace.common.credential.CloudPlatformCredentialInfoAccess;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Covers the CE cloud-relay delegation half of
 * {@link PlatformCredentialsController#publicInfo}: when NO local platform credential row
 * exists, the controller may consult the cloud's platform info over the install's cloud
 * link ({@link CloudPlatformCredentialInfoAccess}, bean absent on the cloud deployment).
 * A local credential always wins; every delegation failure falls back to the legacy
 * not-found shape.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformCredentialsController - public-info CE cloud-relay delegation")
class PlatformCredentialsControllerPublicInfoCloudRelayTest {

    private static final String INTEGRATION = "telegram";

    @Mock
    private PlatformCredentialService service;
    @Mock
    private PlatformCredentialPricingService pricingService;
    @Mock
    private CredentialService credentialService;
    @Mock
    private TenantResolver tenantResolver;
    @Mock
    private ObjectProvider<CloudPlatformCredentialInfoAccess> cloudInfoProvider;
    @Mock
    private CloudPlatformCredentialInfoAccess cloudInfoAccess;

    private PlatformCredentialsController controller;

    @BeforeEach
    void setUp() {
        controller = new PlatformCredentialsController(
                service, pricingService, credentialService, tenantResolver, cloudInfoProvider);
    }

    private Map<String, Object> cloudInfo(boolean available, boolean subscriptionActive,
                                          boolean relayEligible, Long platformCredentialId,
                                          boolean hasPricing, String markupCredits) {
        Map<String, Object> info = new HashMap<>();
        info.put("integrationName", INTEGRATION);
        info.put("available", available);
        info.put("subscriptionActive", subscriptionActive);
        info.put("relayEligible", relayEligible);
        info.put("platformCredentialId", platformCredentialId);
        info.put("hasPricing", hasPricing);
        info.put("markupCredits", markupCredits);
        return info;
    }

    @Test
    @DisplayName("local credential present - delegation is never consulted (local always wins)")
    void localCredentialWins() {
        PlatformCredentialResponse cred = localCredential();
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
        when(pricingService.findLatest(cred.id())).thenReturn(Optional.empty());
        when(pricingService.hasAnyNonZeroMarkup(cred.id())).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, null);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("available")).isEqualTo(true);
        assertThat(response.getBody()).doesNotContainKey("cloudRelay");
        verifyNoInteractions(cloudInfoProvider, cloudInfoAccess);
    }

    @Test
    @DisplayName("local absent + no delegation bean - legacy not-found shape unchanged")
    void noBeanKeepsLegacyShape() {
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.empty());
        when(cloudInfoProvider.getIfAvailable()).thenReturn(null);

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("available")).isEqualTo(false);
        assertThat(body.get("hasPricing")).isEqualTo(false);
        assertThat(body.get("showUnverifiedAppWarning")).isEqualTo(false);
        assertThat(body).doesNotContainKey("cloudRelay");
        assertThat(body).doesNotContainKey("platformCredentialId");
    }

    @Test
    @DisplayName("delegation available + subscription + relay-eligible - available:true with cloudRelay:true and pricing passthrough")
    void cloudAvailableWithSubscriptionUnlocksToggle() {
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.empty());
        when(cloudInfoProvider.getIfAvailable()).thenReturn(cloudInfoAccess);
        when(cloudInfoAccess.fetchPlatformInfo(INTEGRATION, "tool-1"))
                .thenReturn(Optional.of(cloudInfo(true, true, true, 42L, true, "0.05")));

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, "tool-1");

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        // The builder toggle gate is `available && platformCredentialId != null && hasPricing`.
        assertThat(body.get("available")).isEqualTo(true);
        assertThat(body.get("platformCredentialId")).isEqualTo(42L);
        assertThat(body.get("hasPricing")).isEqualTo(true);
        assertThat(body.get("markupCredits")).isEqualTo("0.05");
        assertThat(body.get("showUnverifiedAppWarning")).isEqualTo(false);
        assertThat(body.get("cloudRelay")).isEqualTo(true);
    }

    @Test
    @DisplayName("delegation success without markupCredits - key omitted (null never serialized)")
    void nullMarkupCreditsOmitted() {
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.empty());
        when(cloudInfoProvider.getIfAvailable()).thenReturn(cloudInfoAccess);
        when(cloudInfoAccess.fetchPlatformInfo(INTEGRATION, null))
                .thenReturn(Optional.of(cloudInfo(true, true, true, 42L, false, null)));

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("available")).isEqualTo(true);
        assertThat(body.get("hasPricing")).isEqualTo(false);
        assertThat(body).doesNotContainKey("markupCredits");
    }

    @Test
    @DisplayName("delegation available but NO active subscription - available:false + subscriptionRequired:true upsell hook")
    void cloudAvailableWithoutSubscriptionStaysLockedWithUpsell() {
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.empty());
        when(cloudInfoProvider.getIfAvailable()).thenReturn(cloudInfoAccess);
        when(cloudInfoAccess.fetchPlatformInfo(INTEGRATION, null))
                .thenReturn(Optional.of(cloudInfo(true, false, true, 42L, true, "0.05")));

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("available")).isEqualTo(false);
        assertThat(body.get("hasPricing")).isEqualTo(false);
        assertThat(body.get("cloudRelay")).isEqualTo(true);
        assertThat(body.get("subscriptionRequired")).isEqualTo(true);
    }

    @Test
    @DisplayName("delegation says unavailable - legacy not-found shape (no cloudRelay key)")
    void cloudUnavailableFallsThroughToLegacyShape() {
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.empty());
        when(cloudInfoProvider.getIfAvailable()).thenReturn(cloudInfoAccess);
        when(cloudInfoAccess.fetchPlatformInfo(INTEGRATION, null))
                .thenReturn(Optional.of(cloudInfo(false, true, true, null, false, null)));

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("available")).isEqualTo(false);
        assertThat(body).doesNotContainKey("cloudRelay");
        assertThat(body).doesNotContainKey("subscriptionRequired");
    }

    @Test
    @DisplayName("delegation available but NOT relay-eligible - legacy not-found shape (toggle stays hidden)")
    void notRelayEligibleFallsThroughToLegacyShape() {
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.empty());
        when(cloudInfoProvider.getIfAvailable()).thenReturn(cloudInfoAccess);
        when(cloudInfoAccess.fetchPlatformInfo(INTEGRATION, null))
                .thenReturn(Optional.of(cloudInfo(true, true, false, 42L, true, "0.05")));

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("available")).isEqualTo(false);
        assertThat(body).doesNotContainKey("cloudRelay");
    }

    @Test
    @DisplayName("delegation returns empty - legacy not-found shape")
    void emptyDelegationFallsThroughToLegacyShape() {
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.empty());
        when(cloudInfoProvider.getIfAvailable()).thenReturn(cloudInfoAccess);
        when(cloudInfoAccess.fetchPlatformInfo(INTEGRATION, null)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("available")).isEqualTo(false);
        assertThat(body).doesNotContainKey("cloudRelay");
    }

    @Test
    @DisplayName("delegation throws - legacy not-found shape, never a 500")
    void delegationFailureFallsThroughToLegacyShape() {
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.empty());
        when(cloudInfoProvider.getIfAvailable()).thenReturn(cloudInfoAccess);
        when(cloudInfoAccess.fetchPlatformInfo(anyString(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("cloud unreachable"));

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("available")).isEqualTo(false);
        assertThat(body).doesNotContainKey("cloudRelay");
    }

    private PlatformCredentialResponse localCredential() {
        return new PlatformCredentialResponse(
                7L,
                INTEGRATION,
                "Telegram",
                "api_key",
                "aaaa****zzzz",
                false,     // hasClientSecret
                true,      // hasApiKey
                false,     // hasBasicAuth
                false,     // hasCustomFields
                null,
                null,
                null,
                "telegram",
                "messaging",
                "Send Telegram messages",
                false,
                true,      // enabled
                BigDecimal.ZERO,
                0,
                List.of(),
                Instant.EPOCH,
                Instant.EPOCH,
                null,
                "primary"
        );
    }
}
