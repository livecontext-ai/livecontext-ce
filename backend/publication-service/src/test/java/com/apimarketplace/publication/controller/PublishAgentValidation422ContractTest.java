package com.apimarketplace.publication.controller;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.service.OnboardingCategoryMapper;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.LandingInterfaceSnapshotter;
import com.apimarketplace.publication.service.PublicationListQueryService;
import com.apimarketplace.publication.service.PublicationReviewService;
import com.apimarketplace.publication.service.PublicationValidationException;
import com.apimarketplace.publication.service.ResourcePublicationService;
import com.apimarketplace.publication.service.ShowcaseFileRefRewriter;
import com.apimarketplace.publication.service.ShowcaseSnapshotBackfillService;
import com.apimarketplace.publication.service.ShowcaseSnapshotReader;
import com.apimarketplace.publication.service.WorkflowPublicationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * WIRE CONTRACT of the structured publish-agent refusal: the exact 422 body
 * shape {@code {error, message, ...details}} that PublicationClient (MCP path)
 * and apiClient/publishAgentError.ts (web path) parse. Pins {@code toBody()}
 * AND both controllers, so a shape drift in any of them fails here instead of
 * silently breaking a downstream renderer.
 *
 * <p>Each controller test fails on the pre-fix controllers (the generic catch
 * turned the refusal into an opaque 500).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Publish-agent 422 validation refusal - wire contract")
class PublishAgentValidation422ContractTest {

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private WorkflowPublicationService publicationService;
    @Mock private AgentPublicationService agentPublicationService;
    @Mock private PublicationListQueryService listQueryService;
    @Mock private PublicationReviewService reviewService;
    @Mock private ResourcePublicationService resourcePublicationService;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private LandingInterfaceSnapshotter landingInterfaceSnapshotter;
    @Mock private ShowcaseSnapshotReader showcaseSnapshotReader;
    @Mock private ShowcaseFileRefRewriter fileRefRewriter;
    @Mock private OrgAccessGuard orgAccessGuard;
    @Mock private ShowcaseSnapshotBackfillService backfillService;

    private static final String TENANT = "103";

    private static PublicationValidationException allAccessRefusal() {
        Map<String, Object> violation = new LinkedHashMap<>();
        violation.put("agentId", "11111111-1111-1111-1111-111111111111");
        violation.put("agentName", "Support Copilot");
        violation.put("root", true);
        violation.put("families", List.of("tables", "interfaces"));
        return new PublicationValidationException(
                PublicationValidationException.AGENT_ALL_ACCESS_NOT_PUBLISHABLE,
                "This agent cannot be published because it has 'All' access on some resource types.",
                Map.of("violations", List.of(violation)));
    }

    @Test
    @DisplayName("toBody() merges {error, message} with the detail fields, error code first")
    void toBodyMergesCodeMessageAndDetails() {
        PublicationValidationException e = allAccessRefusal();

        Map<String, Object> body = e.toBody();

        assertThat(body).containsEntry("error", "AGENT_ALL_ACCESS_NOT_PUBLISHABLE");
        assertThat(body).containsEntry("message",
                "This agent cannot be published because it has 'All' access on some resource types.");
        assertThat(body).containsKey("violations");
        // Exactly the three top-level keys for this code: error, message, violations.
        assertThat(body).containsOnlyKeys("error", "message", "violations");
        // Null details are tolerated (body stays {error, message}).
        assertThat(new PublicationValidationException("X", "m", null).toBody())
                .containsOnlyKeys("error", "message");
    }

    @Test
    @DisplayName("INTERNAL controller /publish-agent maps the refusal to HTTP 422 with the toBody() shape (MCP path)")
    void internalControllerReturns422WithStructuredBody() {
        when(agentPublicationService.publishAgent(any(), any(), any())).thenThrow(allAccessRefusal());
        InternalPublicationController controller = new InternalPublicationController(
                publicationRepository, publicationService, agentPublicationService,
                resourcePublicationService, orchestratorClient, backfillService,
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class));

        ResponseEntity<?> response = controller.publishAgent(
                TENANT, null, Map.of("agentConfigId", UUID.randomUUID().toString()));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        Map<String, Object> body = asBody(response);
        assertThat(body).containsEntry("error", "AGENT_ALL_ACCESS_NOT_PUBLISHABLE");
        assertThat(body.get("message")).asString().contains("'All' access");
        assertThat(body.get("violations")).isInstanceOf(List.class);
    }

    @Test
    @DisplayName("PUBLIC controller /publish-agent maps the refusal to HTTP 422 with the toBody() shape (web modal path)")
    void publicControllerReturns422WithStructuredBody() {
        when(agentPublicationService.publishAgent(any(), any(), any())).thenThrow(allAccessRefusal());
        WorkflowPublicationController controller = new WorkflowPublicationController(
                publicationService, agentPublicationService, listQueryService,
                reviewService, resourcePublicationService, orchestratorClient,
                landingInterfaceSnapshotter, showcaseSnapshotReader, fileRefRewriter,
                new OnboardingCategoryMapper(), orgAccessGuard);

        ResponseEntity<?> response = controller.publishAgent(
                TENANT, null, null, Map.of("agentConfigId", UUID.randomUUID().toString()));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        Map<String, Object> body = asBody(response);
        assertThat(body).containsEntry("error", "AGENT_ALL_ACCESS_NOT_PUBLISHABLE");
        assertThat(body.get("violations")).isInstanceOf(List.class);
    }

    @Test
    @DisplayName("too-large refusal keeps its detail fields (sizeBytes/maxBytes/breakdown) through toBody()")
    void tooLargeRefusalKeepsDetailFields() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sizeBytes", 34_000_000L);
        details.put("maxBytes", 15_728_640L);
        details.put("breakdown", List.of(Map.of("type", "datasource", "id", "142", "items", 82000)));
        PublicationValidationException e = new PublicationValidationException(
                PublicationValidationException.AGENT_SNAPSHOT_TOO_LARGE,
                "Publication snapshot is 32.4 MB (max 15.0 MB).", details);

        Map<String, Object> body = e.toBody();

        assertThat(body).containsOnlyKeys("error", "message", "sizeBytes", "maxBytes", "breakdown");
        assertThat(body).containsEntry("error", "AGENT_SNAPSHOT_TOO_LARGE");
        assertThat(body).containsEntry("sizeBytes", 34_000_000L);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asBody(ResponseEntity<?> response) {
        return (Map<String, Object>) response.getBody();
    }
}
