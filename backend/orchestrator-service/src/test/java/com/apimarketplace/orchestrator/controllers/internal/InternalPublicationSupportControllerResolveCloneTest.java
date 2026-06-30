package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.services.ApplicationLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Characterization test for {@code findBySourcePublication}, which resolves the
 * caller-org's acquired {@code APPLICATION} clone for a publication. The lookup
 * was strangled onto {@link ApplicationLifecycleService#resolveClone}; this pins
 * that the endpoint still delegates with the request's {@code organizationId}
 * verbatim and maps found / not-found to 200 / 404 unchanged.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalPublicationSupportController.findBySourcePublication - resolveClone delegation")
class InternalPublicationSupportControllerResolveCloneTest {

    @Mock private ApplicationLifecycleService applicationLifecycleService;

    private InternalPublicationSupportController controller;

    private static final String TENANT = "tenant-1";
    private static final String ORG_ID = "org-xyz";
    private static final UUID PUB_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // 20-arg constructor; only applicationLifecycleService (#20) is exercised
        // by this endpoint - the rest are null since the tested method ignores them.
        controller = new InternalPublicationSupportController(
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, applicationLifecycleService);
    }

    @Test
    @DisplayName("Found: delegates to resolveClone with the request org, returns 200 with the clone fields")
    void foundReturnsOkWithCloneFields() {
        UUID cloneId = UUID.randomUUID();
        WorkflowEntity clone = new WorkflowEntity();
        clone.setId(cloneId);
        clone.setTenantId(TENANT);
        clone.setOrganizationId(ORG_ID);
        clone.setName("Acquired App");
        when(applicationLifecycleService.resolveClone(eq(ORG_ID), eq(PUB_ID)))
                .thenReturn(Optional.of(clone));

        ResponseEntity<?> response = controller.findBySourcePublication(PUB_ID, TENANT, ORG_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("id", cloneId.toString());
        assertThat(body).containsEntry("tenantId", TENANT);
        assertThat(body).containsEntry("organizationId", ORG_ID);
        assertThat(body).containsEntry("title", "Acquired App");
        // Routes through the facade with the request org verbatim (never a
        // re-derived or collapsed org source).
        verify(applicationLifecycleService).resolveClone(eq(ORG_ID), eq(PUB_ID));
    }

    @Test
    @DisplayName("Not found: 404 when the org has no clone for the publication")
    void notFoundReturns404() {
        when(applicationLifecycleService.resolveClone(eq(ORG_ID), eq(PUB_ID)))
                .thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.findBySourcePublication(PUB_ID, TENANT, ORG_ID);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
