package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import com.apimarketplace.auth.credential.service.CredentialService;
import com.apimarketplace.auth.credential.service.PlatformCredentialPricingService;
import com.apimarketplace.auth.credential.service.PlatformCredentialService;
import com.fasterxml.jackson.databind.node.NullNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformCredentialsController pricing version endpoints")
class PlatformCredentialsControllerPricingVersionTest {

    @Mock
    private PlatformCredentialService service;

    @Mock
    private PlatformCredentialPricingService pricingService;

    @Mock
    private CredentialService credentialService;

    @InjectMocks
    private PlatformCredentialsController controller;

    @Test
    @DisplayName("latest pricing response strips database DECIMAL scale from defaults and overrides")
    void latestPricingResponseStripsPersistedDecimalScale() {
        UUID toolId = UUID.randomUUID();
        PlatformCredentialPricingVersion version = pricingVersion(42L, 7, new BigDecimal("0.250000"));
        when(pricingService.findLatest(42L)).thenReturn(Optional.of(version));
        when(pricingService.findOverrides(version.getId()))
                .thenReturn(Map.of(toolId, new BigDecimal("0.750000")));

        ResponseEntity<?> response = controller.getLatestPricingVersion("ADMIN", 42L);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("defaultMarkupCredits")).isEqualTo("0.25");
        @SuppressWarnings("unchecked")
        Map<String, String> overrides = (Map<String, String>) body.get("overrides");
        assertThat(overrides).containsEntry(toolId.toString(), "0.75");
    }

    @Test
    @DisplayName("latest pricing response keeps an explicit JSON null default for override-only versions")
    void latestPricingResponseKeepsExplicitNullDefault() {
        PlatformCredentialPricingVersion version = pricingVersion(42L, 8, null);
        when(pricingService.findLatest(42L)).thenReturn(Optional.of(version));
        when(pricingService.findOverrides(version.getId())).thenReturn(Map.of());

        ResponseEntity<?> response = controller.getLatestPricingVersion("ADMIN", 42L);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsKey("defaultMarkupCredits");
        assertThat(body.get("defaultMarkupCredits")).isSameAs(NullNode.getInstance());
    }

    private static PlatformCredentialPricingVersion pricingVersion(long credentialId, int versionNo, BigDecimal defaultMarkup) {
        PlatformCredentialPricingVersion version = new PlatformCredentialPricingVersion();
        version.setId(1000L + versionNo);
        version.setPlatformCredentialId(credentialId);
        version.setVersion(versionNo);
        version.setDefaultMarkupCredits(defaultMarkup);
        version.setCreatedAt(Instant.EPOCH);
        return version;
    }
}
