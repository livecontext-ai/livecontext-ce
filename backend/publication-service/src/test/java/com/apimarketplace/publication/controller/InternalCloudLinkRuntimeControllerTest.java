package com.apimarketplace.publication.controller;

import com.apimarketplace.agent.cloud.CloudLlmSource;
import com.apimarketplace.publication.service.CloudLinkService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InternalCloudLinkRuntimeController")
class InternalCloudLinkRuntimeControllerTest {

    @Mock
    private CloudLinkService cloudLinkService;

    @Test
    @DisplayName("Returns the selected LLM source only for the authenticated tenant")
    void returnsSourceForAuthenticatedTenant() {
        InternalCloudLinkRuntimeController controller = new InternalCloudLinkRuntimeController(cloudLinkService);
        when(cloudLinkService.getLlmSource(42L)).thenReturn(CloudLlmSource.CLOUD);

        ResponseEntity<Map<String, Object>> response = controller.source(42L, 42L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("source", "CLOUD");
    }

    @Test
    @DisplayName("Refuses cross-tenant LLM source access")
    void refusesCrossTenantSourceAccess() {
        InternalCloudLinkRuntimeController controller = new InternalCloudLinkRuntimeController(cloudLinkService);

        ResponseEntity<Map<String, Object>> response = controller.source(7L, 42L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "TENANT_MISMATCH");
        verify(cloudLinkService, never()).getLlmSource(42L);
    }

    @Test
    @DisplayName("Returns runtime credentials only for the authenticated tenant")
    void returnsRuntimeCredentialsForAuthenticatedTenant() {
        InternalCloudLinkRuntimeController controller = new InternalCloudLinkRuntimeController(cloudLinkService);
        when(cloudLinkService.getCloudRuntimeStatus(42L)).thenReturn(new CloudLinkService.CloudRuntimeStatus(
                CloudLlmSource.CLOUD,
                true,
                "access-token",
                "install-1",
                "https://livecontext.ai/api"
        ));

        ResponseEntity<Map<String, Object>> response = controller.runtime(42L, 42L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("source", "CLOUD")
                .containsEntry("cloudReady", true)
                .containsEntry("accessToken", "access-token")
                .containsEntry("installId", "install-1")
                .containsEntry("cloudApiUrl", "https://livecontext.ai/api");
    }

    @Test
    @DisplayName("Refuses cross-tenant runtime credential access")
    void refusesCrossTenantRuntimeAccess() {
        InternalCloudLinkRuntimeController controller = new InternalCloudLinkRuntimeController(cloudLinkService);

        ResponseEntity<Map<String, Object>> response = controller.runtime(7L, 42L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "TENANT_MISMATCH");
        verify(cloudLinkService, never()).getCloudRuntimeStatus(42L);
    }

    @Test
    @DisplayName("active-runtime returns the install-global link runtime (no tenant scope, no X-User-ID match)")
    void activeRuntimeReturnsInstallGlobalStatus() {
        InternalCloudLinkRuntimeController controller = new InternalCloudLinkRuntimeController(cloudLinkService);
        when(cloudLinkService.getActiveInstallRuntime()).thenReturn(new CloudLinkService.CloudRuntimeStatus(
                CloudLlmSource.BYOK, true, "tok", "install-9", "https://livecontext.ai/api"));

        ResponseEntity<Map<String, Object>> response = controller.activeRuntime();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("cloudReady", true)
                .containsEntry("accessToken", "tok")
                .containsEntry("installId", "install-9")
                .containsEntry("cloudApiUrl", "https://livecontext.ai/api");
    }

    @Test
    @DisplayName("active-runtime: not-linked install → cloudReady=false with blank creds (sync skips)")
    void activeRuntimeNotLinked() {
        InternalCloudLinkRuntimeController controller = new InternalCloudLinkRuntimeController(cloudLinkService);
        when(cloudLinkService.getActiveInstallRuntime()).thenReturn(new CloudLinkService.CloudRuntimeStatus(
                CloudLlmSource.BYOK, false, null, null, null));

        ResponseEntity<Map<String, Object>> response = controller.activeRuntime();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("cloudReady", false)
                .containsEntry("accessToken", "")
                .containsEntry("installId", "");
    }
}
