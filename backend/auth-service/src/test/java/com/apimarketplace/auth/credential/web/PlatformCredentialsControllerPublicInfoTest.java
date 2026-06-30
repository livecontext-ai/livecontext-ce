package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.domain.PlatformCredentialModels.PlatformCredentialResponse;
import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.service.PlatformCredentialPricingService;
import com.apimarketplace.auth.credential.service.PlatformCredentialService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Covers {@link PlatformCredentialsController#publicInfo} - the non-admin
 * endpoint the workflow inspector uses to decide whether to show the
 * "Platform credential" source toggle on a node.
 *
 * <p>The endpoint has two modes:
 * <ol>
 *   <li>No {@code apiToolId} → integration-level "any non-zero rate" answer.</li>
 *   <li>With {@code apiToolId} → per-endpoint rate resolution (override beats
 *       default; null default + missing override = zero = no pricing).</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformCredentialsController - public-info endpoint")
class PlatformCredentialsControllerPublicInfoTest {

    @Mock
    private PlatformCredentialService service;

    @Mock
    private PlatformCredentialPricingService pricingService;

    @Mock
    private com.apimarketplace.auth.credential.service.CredentialService credentialService;

    @InjectMocks
    private PlatformCredentialsController controller;

    private static final String INTEGRATION = "gmail";

    @Test
    @DisplayName("returns available=false and no id when no credential row exists")
    void missingCredential_returnsUnavailable() {
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, null);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("integrationName")).isEqualTo(INTEGRATION);
        assertThat(body.get("available")).isEqualTo(false);
        assertThat(body.get("showUnverifiedAppWarning")).isEqualTo(false);
        assertThat(body.get("hasPricing")).isEqualTo(false);
        assertThat(body).doesNotContainKey("platformCredentialId");
        assertThat(body).doesNotContainKey("defaultMarkupCredits");
    }

    @Test
    @DisplayName("returns available=false when credential exists but is disabled")
    void disabledCredential_returnsUnavailable() {
        PlatformCredentialResponse cred = credential(false, true);
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
        when(pricingService.findLatest(cred.id())).thenReturn(Optional.empty());
        when(pricingService.hasAnyNonZeroMarkup(cred.id())).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("available")).isEqualTo(false);
        assertThat(body.get("platformCredentialId")).isEqualTo(cred.id());
        assertThat(body.get("hasPricing")).isEqualTo(false);
    }

    @Test
    @DisplayName("returns available=false when credential is enabled but has no secret")
    void enabledWithoutSecret_returnsUnavailable() {
        PlatformCredentialResponse cred = credential(true, false);
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
        when(pricingService.findLatest(cred.id())).thenReturn(Optional.empty());
        when(pricingService.hasAnyNonZeroMarkup(cred.id())).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("available")).isEqualTo(false);
    }

    @Test
    @DisplayName("no apiToolId - hasPricing reflects 'any non-zero rate on this integration'")
    void noToolId_hasPricingReflectsIntegrationWide() {
        PlatformCredentialResponse cred = credential(true, true);
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
        when(pricingService.findLatest(cred.id())).thenReturn(Optional.empty());
        when(pricingService.hasAnyNonZeroMarkup(cred.id())).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("available")).isEqualTo(true);
        assertThat(body.get("showUnverifiedAppWarning")).isEqualTo(true);
        assertThat(body.get("hasPricing")).isEqualTo(true);
        // No per-tool rate is reported when no apiToolId was supplied.
        assertThat(body).doesNotContainKey("markupCredits");
    }

    @Test
    @DisplayName("showUnverifiedAppWarning=false is exposed so the frontend can suppress the OAuth warning for verified providers")
    void publicInfo_exposesSuppressedUnverifiedWarningFlag() {
        PlatformCredentialResponse cred = credential(true, true, false);
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
        when(pricingService.findLatest(cred.id())).thenReturn(Optional.empty());
        when(pricingService.hasAnyNonZeroMarkup(cred.id())).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("available")).isEqualTo(true);
        assertThat(body.get("showUnverifiedAppWarning")).isEqualTo(false);
    }

    @Test
    @DisplayName("non-OAuth credentials never expose the OAuth unverified-app warning")
    void publicInfo_nonOAuthSuppressesUnverifiedWarningFlag() {
        PlatformCredentialResponse cred = credential("api_key", true, true, true);
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
        when(pricingService.findLatest(cred.id())).thenReturn(Optional.empty());
        when(pricingService.hasAnyNonZeroMarkup(cred.id())).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("available")).isEqualTo(true);
        assertThat(body.get("showUnverifiedAppWarning")).isEqualTo(false);
    }


    @Test
    @DisplayName("no apiToolId - returns defaultMarkupCredits and pricingVersion when a version exists")
    void noToolId_exposesVersionDefault() {
        PlatformCredentialResponse cred = credential(true, true);
        PlatformCredentialPricingVersion version = pricingVersion(cred.id(), 3, new BigDecimal("0.25"));
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
        when(pricingService.findLatest(cred.id())).thenReturn(Optional.of(version));
        when(pricingService.hasAnyNonZeroMarkup(cred.id())).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("hasPricing")).isEqualTo(true);
        assertThat(body.get("defaultMarkupCredits")).isEqualTo("0.25");
        assertThat(body.get("pricingVersion")).isEqualTo(3);
    }

    @Test
    @DisplayName("no apiToolId - omits defaultMarkupCredits when the latest version has null default")
    void noToolId_nullDefaultOmitsKey() {
        PlatformCredentialResponse cred = credential(true, true);
        PlatformCredentialPricingVersion version = pricingVersion(cred.id(), 4, null);
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
        when(pricingService.findLatest(cred.id())).thenReturn(Optional.of(version));
        when(pricingService.hasAnyNonZeroMarkup(cred.id())).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, null);

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        // null default = overrides-only pricing; no defaultMarkupCredits field
        assertThat(body).doesNotContainKey("defaultMarkupCredits");
        assertThat(body.get("pricingVersion")).isEqualTo(4);
    }

    @Test
    @DisplayName("with apiToolId - hasPricing=true and markupCredits echoes the per-tool rate")
    void withToolId_returnsPerToolRate() {
        PlatformCredentialResponse cred = credential(true, true);
        UUID toolId = UUID.randomUUID();
        PlatformCredentialPricingVersion version = pricingVersion(cred.id(), 2, new BigDecimal("0.05"));
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
        when(pricingService.findLatest(cred.id())).thenReturn(Optional.of(version));
        when(pricingService.resolveLatestMarkupForTool(cred.id(), toolId))
                .thenReturn(Optional.of(new BigDecimal("0.42")));

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, toolId.toString());

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("hasPricing")).isEqualTo(true);
        assertThat(body.get("markupCredits")).isEqualTo("0.42");
        // Version default still surfaced for admin diagnostics.
        assertThat(body.get("defaultMarkupCredits")).isEqualTo("0.05");
    }

    @Test
    @DisplayName("with apiToolId - hasPricing=false when the tool resolves to zero markup")
    void withToolId_zeroRateMeansNoPricing() {
        // This is the bug the user reported: an admin prices one endpoint only;
        // every OTHER endpoint of the same API should see hasPricing=false so
        // the inspector hides the platform/user toggle there.
        PlatformCredentialResponse cred = credential(true, true);
        UUID toolId = UUID.randomUUID();
        PlatformCredentialPricingVersion version = pricingVersion(cred.id(), 1, null);
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
        when(pricingService.findLatest(cred.id())).thenReturn(Optional.of(version));
        when(pricingService.resolveLatestMarkupForTool(cred.id(), toolId))
                .thenReturn(Optional.of(BigDecimal.ZERO));

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, toolId.toString());

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("hasPricing")).isEqualTo(false);
        assertThat(body).doesNotContainKey("markupCredits");
    }

    @Test
    @DisplayName("with apiToolId - hasPricing=false when the credential has no published version")
    void withToolId_noVersionMeansNoPricing() {
        PlatformCredentialResponse cred = credential(true, true);
        UUID toolId = UUID.randomUUID();
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
        when(pricingService.findLatest(cred.id())).thenReturn(Optional.empty());
        when(pricingService.resolveLatestMarkupForTool(cred.id(), toolId))
                .thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, toolId.toString());

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("hasPricing")).isEqualTo(false);
        assertThat(body).doesNotContainKey("markupCredits");
    }

    @Test
    @DisplayName("invalid apiToolId string falls back to integration-level hasPricing")
    void invalidToolId_fallsBackToIntegrationWide() {
        // Garbage apiToolId must not 500 the endpoint; we silently fall back
        // to the no-tool branch so the inspector still gets a sane response.
        PlatformCredentialResponse cred = credential(true, true);
        when(service.getCredential(INTEGRATION)).thenReturn(Optional.of(cred));
        when(pricingService.findLatest(cred.id())).thenReturn(Optional.empty());
        when(pricingService.hasAnyNonZeroMarkup(cred.id())).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = controller.publicInfo(INTEGRATION, "not-a-uuid");

        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("hasPricing")).isEqualTo(true);
    }

    private PlatformCredentialResponse credential(boolean enabled, boolean hasSecret) {
        return credential(enabled, hasSecret, true);
    }

    private PlatformCredentialResponse credential(boolean enabled, boolean hasSecret, boolean showUnverifiedAppWarning) {
        return credential("oauth2", enabled, hasSecret, showUnverifiedAppWarning);
    }

    private PlatformCredentialResponse credential(
            String authType,
            boolean enabled,
            boolean hasSecret,
            boolean showUnverifiedAppWarning
    ) {
        return new PlatformCredentialResponse(
                42L,
                INTEGRATION,
                "Gmail",
                authType,
                "aaaa****zzzz",
                "oauth2".equals(authType) && hasSecret, // hasClientSecret
                !"oauth2".equals(authType) && hasSecret, // hasApiKey
                false,     // hasBasicAuth
                false,     // hasCustomFields
                "https://auth",
                "https://token",
                "scope1",
                "gmail",
                "productivity",
                "Send and read Gmail",
                showUnverifiedAppWarning,
                enabled,
                BigDecimal.ZERO,
                0,
                List.of(),
                Instant.EPOCH,
                Instant.EPOCH,
                null,
                "primary"
        );
    }

    private PlatformCredentialPricingVersion pricingVersion(long credentialId, int version, BigDecimal defaultMarkup) {
        PlatformCredentialPricingVersion v = new PlatformCredentialPricingVersion();
        v.setId(version * 1000L + credentialId);
        v.setPlatformCredentialId(credentialId);
        v.setVersion(version);
        v.setDefaultMarkupCredits(defaultMarkup);
        v.setCreatedAt(Instant.EPOCH);
        return v;
    }
}
