package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.service.CredentialService;
import com.apimarketplace.auth.credential.service.InternalCredentialService;
import com.apimarketplace.auth.credential.service.PlatformCredentialPricingService;
import com.apimarketplace.auth.credential.service.PlatformCredentialService;
import com.apimarketplace.auth.credential.service.PricingVersionService;
import com.apimarketplace.common.security.CredentialEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code GET /api/internal/credentials/state-version} - the endpoint
 * credential-client polls so catalog's agent response cache can key on the
 * credential state (2026-06-11 "set as default ignored by the chat agent" fix).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalCredentialController - /state-version")
class InternalCredentialControllerStateVersionTest {

    @Mock private InternalCredentialService internalCredentialService;
    @Mock private CredentialService credentialService;
    @Mock private PlatformCredentialService platformCredentialService;
    @Mock private PlatformCredentialPricingService pricingService;
    @Mock private PricingVersionService pricingVersionService;
    @Mock private CredentialEncryptionService encryptionService;

    private InternalCredentialController controller;

    @BeforeEach
    void setUp() {
        controller = new InternalCredentialController(
                internalCredentialService, credentialService, platformCredentialService,
                pricingService, pricingVersionService, encryptionService);
    }

    @Test
    @DisplayName("returns the opaque version computed for (userId, X-Organization-ID)")
    void returnsVersionForUserAndOrg() {
        when(credentialService.getCredentialStateVersion("tenant-5", "org-1")).thenReturn("3:1700000000000");

        ResponseEntity<Map<String, Object>> response =
                controller.getCredentialStateVersion("tenant-5", "org-1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsEntry("version", "3:1700000000000");
        verify(credentialService).getCredentialStateVersion("tenant-5", "org-1");
    }

    @Test
    @DisplayName("works without an org header (personal/tenant-only scope)")
    void worksWithoutOrgHeader() {
        when(credentialService.getCredentialStateVersion("tenant-5", null)).thenReturn("2:42");

        ResponseEntity<Map<String, Object>> response =
                controller.getCredentialStateVersion("tenant-5", null);

        assertThat(response.getBody()).containsEntry("version", "2:42");
    }

    @Test
    @DisplayName("route GET /api/internal/credentials/state-version binds userId and the optional X-Organization-ID header")
    void routeBindsParamsAndOptionalHeader() throws Exception {
        var mockMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller).build();

        when(credentialService.getCredentialStateVersion("tenant-5", "org-1")).thenReturn("3:99");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/internal/credentials/state-version")
                        .queryParam("userId", "tenant-5")
                        .header("X-Organization-ID", "org-1"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.version").value("3:99"));

        // Header absent → org resolves to null (does not 400).
        when(credentialService.getCredentialStateVersion("tenant-5", null)).thenReturn("2:42");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/internal/credentials/state-version")
                        .queryParam("userId", "tenant-5"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.version").value("2:42"));
    }
}
