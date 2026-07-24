package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.orchestrator.services.OptionalFeatureCapabilityService;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OptionalComponentValidator")
class OptionalComponentValidatorTest {

    private static final String TENANT = "tenant-1";

    @Mock
    private OptionalFeatureCapabilityService capabilityService;

    private OptionalComponentValidator validator;
    private WorkflowBuilderSession session;
    private ValidationResult result;

    @BeforeEach
    void setUp() {
        validator = new OptionalComponentValidator(capabilityService);
        session = WorkflowBuilderSession.builder()
                .sessionId("s")
                .tenantId(TENANT)
                .workflowName("Test")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        result = ValidationResult.builder().build();
    }

    private Map<String, Object> browserAgentNode(String label) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "id-" + label);
        node.put("type", "browser_agent");
        node.put("label", label);
        node.put("isAgent", true);
        return node;
    }

    private Map<String, Object> regularAgentNode() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "id-agent");
        node.put("type", "agent");
        node.put("label", "Helper");
        node.put("isAgent", true);
        return node;
    }

    private Map<String, Object> interfaceNode(String label, Boolean screenshot, Boolean pdf) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "id-" + label);
        node.put("label", label);
        if (screenshot != null) node.put("generateScreenshot", screenshot);
        if (pdf != null) node.put("generatePdf", pdf);
        return node;
    }

    private Map<String, Object> mediaNode(String label) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "id-" + label);
        node.put("type", "media");
        node.put("label", label);
        node.put("params", Map.of("operation", "mux_audio"));
        return node;
    }

    @Test
    @DisplayName("media node + renderer unavailable → MEDIA_RENDERER_UNAVAILABLE warning saying the node WILL fail")
    void mediaNodeWithoutRendererWarns() {
        session.getCores().add(mediaNode("Add Music"));
        when(capabilityService.isScreenshotRendererAvailable()).thenReturn(false);

        validator.validate(session, result);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getWarnings()).hasSize(1);
        assertThat(result.getWarnings().get(0).code()).isEqualTo("MEDIA_RENDERER_UNAVAILABLE");
        assertThat(result.getWarnings().get(0).nodeId()).isEqualTo("core:add_music");
        // Unlike the best-effort interface renders, this must announce a run-time FAILURE.
        assertThat(result.getWarnings().get(0).message()).contains("WILL fail at run time");
    }

    @Test
    @DisplayName("media node + renderer available → no warning")
    void mediaNodeWithRendererIsSilent() {
        session.getCores().add(mediaNode("Add Music"));
        when(capabilityService.isScreenshotRendererAvailable()).thenReturn(true);

        validator.validate(session, result);

        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("non-media core node alone → renderer verdict never consulted (no wasted resolution)")
    void nonMediaCoreSkipsResolution() {
        Map<String, Object> transform = new LinkedHashMap<>();
        transform.put("id", "id-t");
        transform.put("type", "transform");
        transform.put("label", "Shape");
        session.getCores().add(transform);

        validator.validate(session, result);

        assertThat(result.getWarnings()).isEmpty();
        verify(capabilityService, never()).isScreenshotRendererAvailable();
    }

    @Test
    @DisplayName("media node AND interface toggle + renderer unavailable → one warning EACH with distinct codes")
    void mediaAndInterfaceWarnSeparately() {
        session.getCores().add(mediaNode("Add Music"));
        session.getInterfaces().add(interfaceNode("Results UI", true, null));
        when(capabilityService.isScreenshotRendererAvailable()).thenReturn(false);

        validator.validate(session, result);

        assertThat(result.getWarnings()).extracting(w -> w.code()).containsExactlyInAnyOrder(
                "INTERFACE_RENDERER_UNAVAILABLE",
                "MEDIA_RENDERER_UNAVAILABLE");
    }

    @Test
    @DisplayName("browser_agent node + browsing unavailable → one WARNING addressed by the normalized agent key, never an error")
    void browserAgentUnavailableWarns() {
        session.getMcps().add(browserAgentNode("Browse Site"));
        when(capabilityService.isBrowsingAvailable(TENANT)).thenReturn(false);

        validator.validate(session, result);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getWarnings()).hasSize(1);
        assertThat(result.getWarnings().get(0).code()).isEqualTo("BROWSER_AGENT_COMPONENT_UNAVAILABLE");
        // Same addressable dialect as the sibling sub-validators (agent:<normalized>).
        assertThat(result.getWarnings().get(0).nodeId()).isEqualTo("agent:browse_site");
        assertThat(result.getWarnings().get(0).message()).contains("fail at run time");
    }

    @Test
    @DisplayName("browser_agent node + browsing available → no warning, renderer verdict never consulted")
    void browserAgentAvailableIsSilent() {
        session.getMcps().add(browserAgentNode("Browse Site"));
        when(capabilityService.isBrowsingAvailable(TENANT)).thenReturn(true);

        validator.validate(session, result);

        assertThat(result.getWarnings()).isEmpty();
        verify(capabilityService, never()).isScreenshotRendererAvailable();
    }

    @Test
    @DisplayName("interface with generateScreenshot + renderer unavailable → one WARNING with the normalized interface key")
    void interfaceScreenshotWithoutRendererWarns() {
        session.getInterfaces().add(interfaceNode("Results UI", true, null));
        when(capabilityService.isScreenshotRendererAvailable()).thenReturn(false);

        validator.validate(session, result);

        assertThat(result.getWarnings()).hasSize(1);
        assertThat(result.getWarnings().get(0).code()).isEqualTo("INTERFACE_RENDERER_UNAVAILABLE");
        assertThat(result.getWarnings().get(0).nodeId()).isEqualTo("interface:results_ui");
    }

    @Test
    @DisplayName("interface with generatePdf + renderer unavailable → one WARNING")
    void interfacePdfWithoutRendererWarns() {
        session.getInterfaces().add(interfaceNode("Report UI", null, true));
        when(capabilityService.isScreenshotRendererAvailable()).thenReturn(false);

        validator.validate(session, result);

        assertThat(result.getWarnings()).hasSize(1);
        assertThat(result.getWarnings().get(0).code()).isEqualTo("INTERFACE_RENDERER_UNAVAILABLE");
    }

    @Test
    @DisplayName("interface with generateVideo + renderer unavailable → one WARNING (video rides the same renderer capability)")
    void interfaceVideoWithoutRendererWarns() {
        Map<String, Object> node = interfaceNode("Clip UI", null, null);
        node.put("generateVideo", true);
        session.getInterfaces().add(node);
        when(capabilityService.isScreenshotRendererAvailable()).thenReturn(false);

        validator.validate(session, result);

        assertThat(result.getWarnings()).hasSize(1);
        assertThat(result.getWarnings().get(0).code()).isEqualTo("INTERFACE_RENDERER_UNAVAILABLE");
        assertThat(result.getWarnings().get(0).nodeId()).isEqualTo("interface:clip_ui");
    }

    @Test
    @DisplayName("interface with string 'true' generateVideo → NO warning (strict Boolean.TRUE match, aligned with parser)")
    void interfaceVideoStringTrueDoesNotWarn() {
        Map<String, Object> node = interfaceNode("Clip UI", null, null);
        node.put("generateVideo", "true");
        session.getInterfaces().add(node);

        validator.validate(session, result);

        assertThat(result.getWarnings()).isEmpty();
        verify(capabilityService, never()).isScreenshotRendererAvailable();
    }

    @Test
    @DisplayName("interface with render toggle + renderer AVAILABLE → no warning, browsing verdict never consulted (no HTTP)")
    void interfaceToggleWithRendererAvailableIsSilent() {
        session.getInterfaces().add(interfaceNode("Results UI", true, null));
        when(capabilityService.isScreenshotRendererAvailable()).thenReturn(true);

        validator.validate(session, result);

        assertThat(result.getWarnings()).isEmpty();
        verify(capabilityService, never()).isBrowsingAvailable(anyString());
    }

    @Test
    @DisplayName("interface with NO render toggle → no warning AND no capability resolution")
    void interfaceWithoutTogglesIsSilent() {
        session.getInterfaces().add(interfaceNode("Plain UI", null, null));

        validator.validate(session, result);

        assertThat(result.getWarnings()).isEmpty();
        verify(capabilityService, never()).isScreenshotRendererAvailable();
        verify(capabilityService, never()).isBrowsingAvailable(anyString());
    }

    @Test
    @DisplayName("plan without affected nodes → capability service is never called (no wasted HTTP)")
    void noAffectedNodesSkipsResolution() {
        session.getMcps().add(regularAgentNode());
        session.getInterfaces().add(interfaceNode("Plain UI", false, false));

        validator.validate(session, result);

        assertThat(result.getWarnings()).isEmpty();
        verify(capabilityService, never()).isScreenshotRendererAvailable();
        verify(capabilityService, never()).isBrowsingAvailable(anyString());
    }

    @Test
    @DisplayName("capability resolution failure → no warning, no exception (unknown must not fail validation)")
    void resolutionFailureIsSilent() {
        session.getMcps().add(browserAgentNode("Browse Site"));
        when(capabilityService.isBrowsingAvailable(TENANT))
                .thenThrow(new IllegalStateException("publication-service down"));

        validator.validate(session, result);

        assertThat(result.getErrors()).isEmpty();
        assertThat(result.getWarnings()).isEmpty();
    }

    @Test
    @DisplayName("mixed plan: one warning per affected node, unaffected nodes silent")
    void mixedPlanWarnsPerAffectedNode() {
        session.getMcps().add(browserAgentNode("Browse A"));
        session.getMcps().add(browserAgentNode("Browse B"));
        session.getMcps().add(regularAgentNode());
        session.getInterfaces().add(interfaceNode("Results UI", true, null));
        session.getInterfaces().add(interfaceNode("Plain UI", null, null));
        when(capabilityService.isBrowsingAvailable(TENANT)).thenReturn(false);
        when(capabilityService.isScreenshotRendererAvailable()).thenReturn(false);

        validator.validate(session, result);

        assertThat(result.getWarnings()).hasSize(3);
        assertThat(result.getWarnings()).extracting(w -> w.code()).containsExactlyInAnyOrder(
                "BROWSER_AGENT_COMPONENT_UNAVAILABLE",
                "BROWSER_AGENT_COMPONENT_UNAVAILABLE",
                "INTERFACE_RENDERER_UNAVAILABLE");
    }

    @Test
    @DisplayName("string 'true' toggle does NOT warn - the runtime parser treats non-Boolean as false, so no render is attempted at all")
    void stringTrueToggleDoesNotCountAsEnabled() {
        Map<String, Object> iface = interfaceNode("Results UI", null, null);
        iface.put("generateScreenshot", "true");
        session.getInterfaces().add(iface);

        validator.validate(session, result);

        // Warning "renderer unavailable" would state the wrong cause: with a string
        // toggle the output is absent because the toggle is effectively OFF
        // (WorkflowPlanParser only honours Boolean true), renderer or not.
        assertThat(result.getWarnings()).isEmpty();
        verify(capabilityService, never()).isScreenshotRendererAvailable();
    }
}
