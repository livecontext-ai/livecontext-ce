package com.apimarketplace.publication.controller;

import com.apimarketplace.auth.client.entitlement.LimitExceededError;
import com.apimarketplace.auth.client.entitlement.LimitExceededException;
import com.apimarketplace.auth.client.entitlement.LimitExceededExceptionHandler;
import com.apimarketplace.auth.client.entitlement.ResourceType;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.ResourcePublicationService;
import com.apimarketplace.publication.service.ShowcaseFileNamespaceRepairService;
import com.apimarketplace.publication.service.ShowcaseSnapshotBackfillService;
import com.apimarketplace.publication.service.WorkflowPublicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The internal acquire answers a plan's applications quota as the platform-wide 409, so callers
 * (PublicationClient, auth-service's partner offer delivery) can tell "the plan is full" from a
 * server failure. It used to fall into the generic catch and answer 500.
 */
@DisplayName("InternalPublicationController - acquire refused by the plan's apps quota is a 409")
class InternalPublicationControllerAcquireLimitTest {

    private static final UUID PUB = UUID.fromString("6f1c0d2e-0000-4000-8000-00000000000a");

    private WorkflowPublicationService publications;
    private MockMvc http;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        publications = mock(WorkflowPublicationService.class);
        InternalPublicationController controller = new InternalPublicationController(
                mock(WorkflowPublicationRepository.class), publications, mock(AgentPublicationService.class),
                mock(ResourcePublicationService.class), mock(OrchestratorInternalClient.class),
                mock(ShowcaseSnapshotBackfillService.class), mock(ShowcaseFileNamespaceRepairService.class),
                mock(ObjectProvider.class));
        http = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new LimitExceededExceptionHandler()).build();
    }

    @Test
    @DisplayName("regression: the quota refusal is a 409 carrying PLAN_RESOURCE_LIMIT_EXCEEDED, never a 500")
    void quotaIs409() throws Exception {
        when(publications.acquirePublication(eq(PUB), eq("7"), any())).thenThrow(new LimitExceededException(
                LimitExceededError.of(ResourceType.APPLICATION, "FREE", 3, 3, "Upgrade to install more apps")));

        http.perform(post("/api/internal/publications/" + PUB + "/acquire").header("X-User-ID", "7"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(LimitExceededError.ERROR_CODE));
    }

    @Test
    @DisplayName("the other refusals keep their answers: 400 with the reason, 500 for a failure")
    void otherRefusals() throws Exception {
        when(publications.acquirePublication(eq(PUB), eq("7"), any())).thenThrow(new IllegalArgumentException("Publication already acquired"));
        http.perform(post("/api/internal/publications/" + PUB + "/acquire").header("X-User-ID", "7"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Publication already acquired"));

        when(publications.acquirePublication(eq(PUB), eq("8"), any())).thenThrow(new IllegalStateException("clone failed"));
        http.perform(post("/api/internal/publications/" + PUB + "/acquire").header("X-User-ID", "8"))
                .andExpect(status().isInternalServerError());
    }
}
