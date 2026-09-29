package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.service.ByokDeleteService;
import com.apimarketplace.auth.credential.service.CredentialService;
import com.apimarketplace.auth.credential.service.InternalCredentialService;
import com.apimarketplace.auth.credential.service.OAuth2Service;
import com.apimarketplace.auth.credential.service.PlatformCredentialPricingService;
import com.apimarketplace.auth.credential.service.PlatformCredentialService;
import com.apimarketplace.auth.credential.util.RequestParameterExtractor;
import com.apimarketplace.common.web.TenantResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc proof, with the REAL TenantResolver, that the auth-service credential writes read
 * the {@code X-Organization-Role} header and refuse a VIEWER: credential create / delete /
 * set-default, OAuth2 initiate, and the workspace BYOK OAuth app save / delete.
 */
@DisplayName("auth-service writes bind X-Organization-Role and refuse VIEWER")
class AuthWritesViewerGateWebMvcTest {

    private CredentialService credentialService;
    private OAuth2Service oAuth2Service;
    private PlatformCredentialService platformService;
    private ByokDeleteService byokDeleteService;
    private MockMvc mockMvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        credentialService = mock(CredentialService.class);
        oAuth2Service = mock(OAuth2Service.class);
        platformService = mock(PlatformCredentialService.class);
        byokDeleteService = mock(ByokDeleteService.class);
        TenantResolver resolver = new TenantResolver();
        mockMvc = MockMvcBuilders.standaloneSetup(
                new CredentialController(credentialService, resolver, new RequestParameterExtractor()),
                new OAuth2Controller(oAuth2Service, resolver, mock(InternalCredentialService.class)),
                new PlatformCredentialsController(platformService, mock(PlatformCredentialPricingService.class),
                        byokDeleteService, resolver, mock(ObjectProvider.class)))
                .build();
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String role) {
        return b.header("X-User-ID", "42").header("X-Organization-ID", "org-c")
                .header("X-Organization-Role", role).header("X-Authenticated", "true")
                .contentType(MediaType.APPLICATION_JSON);
    }

    @Test
    @DisplayName("VIEWER header: every credential / OAuth / BYOK write is 403 and reaches no service")
    void viewerRefused() throws Exception {
        mockMvc.perform(as(post("/api/credentials"), "VIEWER").content("{\"name\":\"k\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(delete("/api/credentials/7"), "VIEWER")).andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/credentials/7/set-default"), "VIEWER")).andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/credentials/7/clear-default"), "VIEWER")).andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/credentials/oauth2/initiate"), "VIEWER")
                .content("{\"credentialTemplateId\":\"t\"}")).andExpect(status().isForbidden());
        mockMvc.perform(as(post("/api/platform-credentials/my"), "VIEWER")
                .content("{\"integrationName\":\"github\"}")).andExpect(status().isForbidden());
        mockMvc.perform(as(delete("/api/platform-credentials/my/github"), "VIEWER"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(credentialService, oAuth2Service, platformService, byokDeleteService);
    }

    @Test
    @DisplayName("MEMBER header: a credential delete reaches the service (the gate is VIEWER-only)")
    void memberProceeds() throws Exception {
        mockMvc.perform(as(delete("/api/credentials/7"), "MEMBER"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isNotEqualTo(403));
        verify(credentialService).deleteCredentialForScope(7L, "42", "org-c");
    }
}
