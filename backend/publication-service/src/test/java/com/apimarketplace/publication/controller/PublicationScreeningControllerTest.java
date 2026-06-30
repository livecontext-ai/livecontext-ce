package com.apimarketplace.publication.controller;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.screening.ImageScreeningDecisionEntity;
import com.apimarketplace.publication.screening.ImageScreeningDecisionRepository;
import com.apimarketplace.publication.screening.ImageScreeningService;
import com.apimarketplace.publication.screening.ItemDataResourceExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublicationScreeningController - Wave 2a part 2 pre-publish scan + decisions log")
class PublicationScreeningControllerTest {

    @Mock InterfaceClient interfaceClient;
    @Mock ImageScreeningDecisionRepository decisionRepository;
    @Mock CreditConsumptionClient creditConsumptionClient;
    @Mock OrchestratorInternalClient orchestratorInternalClient;

    private PublicationScreeningController controller;

    @BeforeEach
    void setUp() {
        ImageScreeningService service = new ImageScreeningService(new ItemDataResourceExtractor(new ObjectMapper()));
        controller = new PublicationScreeningController(
                service, decisionRepository, interfaceClient, creditConsumptionClient,
                orchestratorInternalClient, "http://orchestrator.test");
    }

    @Test
    @DisplayName("pre-publish-scan: templates with no media references return clean=true and empty list")
    void prePublishScanNoMedia() {
        UUID ifaceId = UUID.randomUUID();
        InterfaceDto iface = new InterfaceDto();
        // Plain text - nothing references an image, video, or audio resource
        iface.setHtmlTemplate("<div><p>Hello world</p></div>");
        when(interfaceClient.getInterface(eq(ifaceId), eq("user-42"))).thenReturn(iface);

        var req = new PublicationScreeningController.PrePublishScanRequest();
        req.interfaceId = ifaceId.toString();
        ResponseEntity<?> resp = controller.prePublishScan(req, "user-42", null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("clean", true);
        assertThat((List<?>) body.get("flagged")).isEmpty();
        assertThat(body).containsEntry("attestationTextVersion", PublicationScreeningController.CURRENT_ATTESTATION_TEXT_VERSION);
    }

    @Test
    @DisplayName("pre-publish-scan: EVERY media URL is surfaced for review - including same-origin /api/files/proxy-signed?…")
    void prePublishScanSurfacesAllResources() {
        UUID ifaceId = UUID.randomUUID();
        InterfaceDto iface = new InterfaceDto();
        // Mix of external + internal - the publisher reviews every one
        // because internal URLs can still point at illegally-sourced bytes
        // (publisher uploaded a third-party photo to their own CDN).
        iface.setHtmlTemplate(
                "<img src=\"https://A0.Muscache.com/im/pictures/abc.jpg\">"
                        + "<img src=\"/api/files/proxy-signed?fileId=xyz&token=abc\">"
                        + "<video src=\"https://cdn.livecontext.ai/intro.mp4\"></video>");
        when(interfaceClient.getInterface(any(), any())).thenReturn(iface);

        var req = new PublicationScreeningController.PrePublishScanRequest();
        req.interfaceId = ifaceId.toString();
        ResponseEntity<?> resp = controller.prePublishScan(req, "user-42", null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("clean", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flagged = (List<Map<String, Object>>) body.get("flagged");
        assertThat(flagged)
                .as("All 3 media URLs must surface - including the same-origin /api/files/… and the cdn.livecontext.ai video, because the publisher is the only one who knows whether they hold rights to the underlying bytes.")
                .hasSize(3);

        Map<String, Object> airbnb = flagged.get(0);
        assertThat(airbnb).containsEntry("url", "https://A0.Muscache.com/im/pictures/abc.jpg");
        assertThat(airbnb).containsEntry("source", "HTML");
        assertThat(airbnb).containsEntry("host", "a0.muscache.com");
        assertThat((String) airbnb.get("urlHash")).hasSize(64).matches("^[0-9a-f]{64}$");

        Map<String, Object> internal = flagged.get(1);
        assertThat(internal).containsEntry("url", "/api/files/proxy-signed?fileId=xyz&token=abc");
        assertThat(internal).containsEntry("host", "unknown"); // relative URL has no host
    }

    @Test
    @DisplayName("pre-publish-scan: a scraped CDN URL living in items[].data is flagged DATA (template hides it behind a placeholder)")
    void prePublishScanFlagsItemDataImage() {
        UUID ifaceId = UUID.randomUUID();
        InterfaceDto iface = new InterfaceDto();
        iface.setHtmlTemplate("<img src=\"{{profilePicUrl}}\">"); // only the placeholder is static
        when(interfaceClient.getInterface(eq(ifaceId), eq("user-42"))).thenReturn(iface);
        // Stub keyed to epoch=2 - if the controller forwarded a different epoch the
        // stub would miss, no items would return, and the DATA assertion below would fail.
        when(orchestratorInternalClient.renderInterfaceItemsForRun(
                eq("run_abc"), eq(ifaceId), eq(2), eq("user-42"), any()))
                .thenReturn(List.of(Map.of("data", Map.of(
                        "displayUrl", "https://scontent.cdninstagram.com/v/t51/abc_n.jpg?stp=x"))));

        var req = new PublicationScreeningController.PrePublishScanRequest();
        req.interfaceId = ifaceId.toString();
        req.runId = "run_abc";
        req.epoch = 2;
        ResponseEntity<?> resp = controller.prePublishScan(req, "user-42", null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("clean", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> flagged = (List<Map<String, Object>>) body.get("flagged");
        assertThat(flagged)
                .anySatisfy(f -> {
                    assertThat(f.get("url")).isEqualTo("https://scontent.cdninstagram.com/v/t51/abc_n.jpg?stp=x");
                    assertThat(f.get("source")).isEqualTo("DATA");
                    assertThat(f.get("host")).isEqualTo("scontent.cdninstagram.com");
                });
        // Value-level guard: the publisher's pinned epoch reaches the render client.
        verify(orchestratorInternalClient).renderInterfaceItemsForRun(
                eq("run_abc"), eq(ifaceId), eq(2), eq("user-42"), any());
    }

    @Test
    @DisplayName("pre-publish-scan: missing interface returns 404")
    void prePublishScanMissingInterface() {
        UUID ifaceId = UUID.randomUUID();
        when(interfaceClient.getInterface(any(), any())).thenReturn(null);

        var req = new PublicationScreeningController.PrePublishScanRequest();
        req.interfaceId = ifaceId.toString();
        ResponseEntity<?> resp = controller.prePublishScan(req, "user-42", null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("pre-publish-scan: missing X-User-ID returns 401")
    void prePublishScanUnauthenticated() {
        var req = new PublicationScreeningController.PrePublishScanRequest();
        req.interfaceId = UUID.randomUUID().toString();

        ResponseEntity<?> resp = controller.prePublishScan(req, null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verify(interfaceClient, never()).getInterface(any(), any());
    }

    @Test
    @DisplayName("screening-decisions: KEPT_ATTESTED row persists with hash + host + attestation_text_version pinned")
    void postDecisionAttestPersists() {
        UUID pubId = UUID.randomUUID();
        var entry = new PublicationScreeningController.DecisionEntry();
        entry.url = "https://a0.muscache.com/photo.jpg";
        entry.source = "html";
        entry.decision = "KEPT_ATTESTED";
        entry.attestationText = "I confirm I have the right to use this image";

        var req = new PublicationScreeningController.DecisionsRequest();
        req.publicationId = pubId.toString();
        req.decisions = List.of(entry);

        ResponseEntity<?> resp = controller.postDecisions(req, "user-42", "org-7", "MozillaTest/1.0");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<ImageScreeningDecisionEntity> rowCaptor = ArgumentCaptor.forClass(ImageScreeningDecisionEntity.class);
        verify(decisionRepository).save(rowCaptor.capture());
        ImageScreeningDecisionEntity saved = rowCaptor.getValue();
        assertThat(saved.getPublicationId()).isEqualTo(pubId);
        assertThat(saved.getDecidedBy()).isEqualTo("user-42");
        assertThat(saved.getOrganizationId()).isEqualTo("org-7");
        assertThat(saved.getDecision()).isEqualTo(ImageScreeningDecisionEntity.Decision.KEPT_ATTESTED);
        assertThat(saved.getImageSource()).isEqualTo(ImageScreeningDecisionEntity.ImageSource.HTML);
        assertThat(saved.getImageUrlHost()).isEqualTo("a0.muscache.com");
        assertThat(saved.getImageUrlHash()).hasSize(64);
        assertThat(saved.getAttestationText()).isEqualTo("I confirm I have the right to use this image");
        assertThat(saved.getAttestationTextVersion())
                .as("Pin the §8 wording version so we can prove what the publisher actually attested against if §8 evolves")
                .isEqualTo(PublicationScreeningController.CURRENT_ATTESTATION_TEXT_VERSION);
        assertThat(saved.getUserAgent()).isEqualTo("MozillaTest/1.0");
    }

    @Test
    @DisplayName("screening-decisions: SKIPPED row persists WITHOUT attestation_text - pins the 'logged whether or not you tick the box' contract")
    void postDecisionSkippedPersistsWithoutAttestation() {
        UUID pubId = UUID.randomUUID();
        var entry = new PublicationScreeningController.DecisionEntry();
        entry.url = "https://a0.muscache.com/photo.jpg";
        entry.source = "HTML";
        entry.decision = "SKIPPED";
        // No attestationText - publisher dismissed the warning without claiming rights

        var req = new PublicationScreeningController.DecisionsRequest();
        req.publicationId = pubId.toString();
        req.decisions = List.of(entry);

        ResponseEntity<?> resp = controller.postDecisions(req, "user-42", null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<ImageScreeningDecisionEntity> rowCaptor = ArgumentCaptor.forClass(ImageScreeningDecisionEntity.class);
        verify(decisionRepository).save(rowCaptor.capture());
        ImageScreeningDecisionEntity saved = rowCaptor.getValue();
        assertThat(saved.getDecision()).isEqualTo(ImageScreeningDecisionEntity.Decision.SKIPPED);
        assertThat(saved.getAttestationText())
                .as("SKIPPED is the 'acknowledged warning without attestation' path - text MUST be null. "
                    + "The modal footer copy explicitly promises 'your decision is logged whether or not "
                    + "you tick the box' - this test pins that contract end-to-end.")
                .isNull();
        // attestation_text_version is still pinned even for SKIPPED - so we know
        // which CGU wording the publisher saw and chose NOT to attest against.
        assertThat(saved.getAttestationTextVersion())
                .isEqualTo(PublicationScreeningController.CURRENT_ATTESTATION_TEXT_VERSION);
    }

    @Test
    @DisplayName("screening-decisions: invalid decision enum value returns 400")
    void postDecisionInvalidEnum() {
        var entry = new PublicationScreeningController.DecisionEntry();
        entry.url = "https://x.com/y.jpg";
        entry.source = "HTML";
        entry.decision = "DEFINITELY_NOT_AN_ENUM_VALUE";

        var req = new PublicationScreeningController.DecisionsRequest();
        req.publicationId = UUID.randomUUID().toString();
        req.decisions = List.of(entry);

        ResponseEntity<?> resp = controller.postDecisions(req, "user-42", null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(decisionRepository, never()).save(any());
    }

    @Test
    @DisplayName("screening-decisions: empty decisions list returns 400 (caller bug)")
    void postDecisionEmptyList() {
        var req = new PublicationScreeningController.DecisionsRequest();
        req.publicationId = UUID.randomUUID().toString();
        req.decisions = List.of();

        ResponseEntity<?> resp = controller.postDecisions(req, "user-42", null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("generate-replacement: successful Stability result is returned and billed as one image")
    void generateReplacementReturnsStorageKeyAndBillsOneImage() {
        MockRestServiceServer server = bindOrchestratorServer();
        when(creditConsumptionClient.fetchBalance("user-42")).thenReturn(BigDecimal.valueOf(5));
        server.expect(requestTo("http://orchestrator.test/api/internal/image-generation/screening-replace"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-User-ID", "user-42"))
                .andExpect(header("X-Organization-ID", "org-7"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"prompt":"replace hotel image","tenantId":"user-42"}
                        """))
                .andRespond(withSuccess("""
                        {"storageKey":"user-42/ai-generated/replacement.png","mimeType":"image/png","sizeBytes":123}
                        """, MediaType.APPLICATION_JSON));

        var req = new PublicationScreeningController.GenerateReplacementRequest();
        req.interfaceId = "iface-1";
        req.originalImageUrl = "https://images.example.com/hotel.jpg";
        req.prompt = "replace hotel image";

        ResponseEntity<?> resp = controller.generateReplacement(req, "user-42", "org-7");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body)
                .containsEntry("success", true)
                .containsEntry("storageKey", "user-42/ai-generated/replacement.png")
                .containsEntry("creditsCost", 3);
        verify(creditConsumptionClient).consumeCreditsAsync(
                eq("user-42"), eq("IMAGE_GENERATION"), startsWith("screening-replace:iface-1:"),
                eq("stability-ai"), eq("stability-core"), eq(1));
        server.verify();
    }

    @Test
    @DisplayName("generate-replacement: insufficient credits rejects before Stability call")
    void generateReplacementRejectsBeforeGenerationWhenCreditsAreInsufficient() {
        MockRestServiceServer server = bindOrchestratorServer();
        when(creditConsumptionClient.fetchBalance("user-42")).thenReturn(BigDecimal.valueOf(2));

        var req = new PublicationScreeningController.GenerateReplacementRequest();
        req.interfaceId = "iface-1";
        req.originalImageUrl = "https://images.example.com/hotel.jpg";
        req.prompt = "replace hotel image";

        ResponseEntity<?> resp = controller.generateReplacement(req, "user-42", "org-7");

        assertThat(resp.getStatusCode().value()).isEqualTo(402);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body)
                .containsEntry("error", "insufficient_credits")
                .containsEntry("creditsCost", 3)
                .containsEntry("creditsBalance", BigDecimal.valueOf(2));
        verify(creditConsumptionClient, never()).consumeCreditsAsync(any(), any(), any(), any(), any(), any());
        server.verify();
    }

    @Test
    @DisplayName("generate-replacement: organization context is required before generation")
    void generateReplacementRequiresOrganizationContext() {
        var req = new PublicationScreeningController.GenerateReplacementRequest();
        req.interfaceId = "iface-1";
        req.originalImageUrl = "https://images.example.com/hotel.jpg";
        req.prompt = "replace hotel image";

        ResponseEntity<?> resp = controller.generateReplacement(req, "user-42", null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(creditConsumptionClient, never()).fetchBalance(any());
    }

    @Test
    @DisplayName("generate-replacement: orchestrator 5xx (e.g. Stability not configured) → clean 502, never a raw 500 (regression)")
    void generateReplacementMapsUpstream5xxToCleanError() {
        MockRestServiceServer server = bindOrchestratorServer();
        when(creditConsumptionClient.fetchBalance("user-42")).thenReturn(BigDecimal.valueOf(5));
        server.expect(requestTo("http://orchestrator.test/api/internal/image-generation/screening-replace"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"generation_failed\",\"message\":\"Stability AI platform credential not configured\"}"));

        var req = new PublicationScreeningController.GenerateReplacementRequest();
        req.interfaceId = "iface-1";
        req.originalImageUrl = "https://images.example.com/hotel.jpg";
        req.prompt = "replace hotel image";

        ResponseEntity<?> resp = controller.generateReplacement(req, "user-42", "org-7");

        // Before the fix the upstream 5xx escaped the (4xx-only) catch and surfaced
        // to the publisher as a raw 500. Now it degrades to a clean 502.
        assertThat(resp.getStatusCode().value()).isEqualTo(502);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) resp.getBody();
        assertThat(body).containsEntry("error", "generation_failed");
        assertThat((String) body.get("message")).contains("Stability AI platform credential not configured");
        verify(creditConsumptionClient, never()).consumeCreditsAsync(any(), any(), any(), any(), any(), any());
        server.verify();
    }

    @Test
    @DisplayName("screening-decisions: REPLACED_UPLOAD (publisher-uploaded replacement) persists with its replacement_ref + DATA source")
    void postDecisionReplacedUploadPersists() {
        UUID pubId = UUID.randomUUID();
        var entry = new PublicationScreeningController.DecisionEntry();
        entry.url = "https://scontent.cdninstagram.com/v/t51/x_n.jpg";
        entry.source = "DATA";
        entry.decision = "replaced_upload"; // case-insensitive
        entry.replacementRef = "user-42/general/screening-replace/clip.mp4";

        var req = new PublicationScreeningController.DecisionsRequest();
        req.publicationId = pubId.toString();
        req.decisions = List.of(entry);

        ArgumentCaptor<ImageScreeningDecisionEntity> captor =
                ArgumentCaptor.forClass(ImageScreeningDecisionEntity.class);
        when(decisionRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        ResponseEntity<?> resp = controller.postDecisions(req, "user-42", null, null);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        ImageScreeningDecisionEntity saved = captor.getValue();
        assertThat(saved.getDecision()).isEqualTo(ImageScreeningDecisionEntity.Decision.REPLACED_UPLOAD);
        assertThat(saved.getReplacementRef()).isEqualTo("user-42/general/screening-replace/clip.mp4");
        assertThat(saved.getImageSource()).isEqualTo(ImageScreeningDecisionEntity.ImageSource.DATA);
    }

    private MockRestServiceServer bindOrchestratorServer() {
        RestTemplate restTemplate = (RestTemplate) ReflectionTestUtils.getField(controller, "orchestratorRestTemplate");
        return MockRestServiceServer.createServer(restTemplate);
    }
}
