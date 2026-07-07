package com.apimarketplace.orchestrator.services;

import com.apimarketplace.agent.cloud.CeWebSearchRelayGate;
import com.apimarketplace.orchestrator.services.OptionalFeatureCapabilityService.FeatureCapabilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OptionalFeatureCapabilityServiceTest {

    private static final String TENANT = "tenant-1";

    private OptionalFeatureCapabilityService service(String rendererUrl, boolean webSearchAvailable) {
        CeWebSearchRelayGate gate = mock(CeWebSearchRelayGate.class);
        when(gate.isWebSearchAvailable(anyString())).thenReturn(webSearchAvailable);
        return new OptionalFeatureCapabilityService(rendererUrl, gate);
    }

    @Test
    @DisplayName("blank renderer URL reports screenshotRenderer=false (CE default: profile not enabled)")
    void blankRendererUrlReportsUnavailable() {
        FeatureCapabilities caps = service("", true).resolve(TENANT);

        assertThat(caps.screenshotRenderer()).isFalse();
    }

    @Test
    @DisplayName("null renderer URL reports screenshotRenderer=false")
    void nullRendererUrlReportsUnavailable() {
        FeatureCapabilities caps = service(null, true).resolve(TENANT);

        assertThat(caps.screenshotRenderer()).isFalse();
    }

    @Test
    @DisplayName("configured renderer URL reports screenshotRenderer=true")
    void configuredRendererUrlReportsAvailable() {
        FeatureCapabilities caps = service("http://screenshot-renderer:8094", false).resolve(TENANT);

        assertThat(caps.screenshotRenderer()).isTrue();
    }

    @Test
    @DisplayName("browserAgent and webSearch both mirror the CeWebSearchRelayGate per-tenant verdict")
    void browsingCapabilitiesMirrorTheGate() {
        FeatureCapabilities available = service("", true).resolve(TENANT);
        FeatureCapabilities unavailable = service("", false).resolve(TENANT);

        assertThat(available.browserAgent()).isTrue();
        assertThat(available.webSearch()).isTrue();
        assertThat(unavailable.browserAgent()).isFalse();
        assertThat(unavailable.webSearch()).isFalse();
    }

    @Test
    @DisplayName("null tenant is passed through to the gate (which fail-closes) without throwing")
    void nullTenantDoesNotThrow() {
        CeWebSearchRelayGate gate = mock(CeWebSearchRelayGate.class);
        when(gate.isWebSearchAvailable(null)).thenReturn(false);
        OptionalFeatureCapabilityService service = new OptionalFeatureCapabilityService("", gate);

        FeatureCapabilities caps = service.resolve(null);

        assertThat(caps.browserAgent()).isFalse();
        assertThat(caps.webSearch()).isFalse();
    }
}
