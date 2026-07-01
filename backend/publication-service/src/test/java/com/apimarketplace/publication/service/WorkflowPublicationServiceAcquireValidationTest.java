package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.PublicationReviewRepository;
import com.apimarketplace.publication.repository.PublicationSnapshotVersionRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the helper-INDEPENDENT acquire guards of
 * {@link WorkflowPublicationService#acquirePublication(UUID, String, String)}
 * - the ones that run in production regardless of whether the optional
 * {@link PublicationAcquisitionHelper} bean is present, and are surfaced to the
 * client as HTTP 400 by the acquire endpoint.
 *
 * <p>The status/visibility/owner rejections are owned by
 * {@code PublicationAcquisitionHelper} (covered by
 * {@code PublicationAcquisitionHelperTest}). What that helper does NOT cover -
 * and what stays active even when the helper is wired - are:</p>
 * <ul>
 *   <li>the "already acquired" guard ({@code existsApplicationInScope}), checked
 *       by the service itself between the two helper calls; and</li>
 *   <li>{@code resolveAcquirerOrg}'s fallback throw when the request carries no
 *       organization and the caller has no resolvable default org.</li>
 * </ul>
 *
 * <p>The 13-arg constructor leaves the optional helper null, which keeps these
 * service-owned guards on the executed path without pulling in helper stubs.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationService - acquire guards (already-acquired + org resolution)")
class WorkflowPublicationServiceAcquireValidationTest {

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private PublicationSnapshotVersionRepository snapshotVersionRepository;
    @Mock private PublicationReceiptRepository receiptRepository;
    @Mock private PublicationReviewRepository reviewRepository;
    @Mock private OrchestratorInternalClient orchestratorClient;
    @Mock private AgentClient agentClient;
    @Mock private InterfaceClient interfaceClient;
    @Mock private DataSourceClient dataSourceClient;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private SnapshotCloneService snapshotCloneService;
    @Mock private EntitlementGuard entitlementGuard;
    @Mock private AuthClient authClient;

    private WorkflowPublicationService service;

    private static final UUID PUBLICATION_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
    private static final UUID WORKFLOW_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final String BUYER = "buyer-7";
    private static final String BUYER_ORG = "org-buyer";

    @BeforeEach
    void setUp() {
        service = new WorkflowPublicationService(
                publicationRepository,
                snapshotVersionRepository,
                receiptRepository,
                reviewRepository,
                orchestratorClient,
                agentClient,
                interfaceClient,
                dataSourceClient,
                breakdownService,
                new ObjectMapper(),
                snapshotCloneService,
                entitlementGuard,
                authClient);
    }

    @Test
    @DisplayName("Re-acquiring a publication already installed in the caller's scope is rejected (\"Publication already acquired\") before any clone, no receipt saved")
    void alreadyAcquiredPublicationRejected() {
        WorkflowPublicationEntity publication = publication("publisher-x", PublicationStatus.ACTIVE, PublicationVisibility.PUBLIC);
        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        // The acquirer's scope already holds an application cloned from this publication.
        when(orchestratorClient.existsBySourcePublication(PUBLICATION_ID, BUYER, BUYER_ORG)).thenReturn(true);

        assertThatThrownBy(() -> service.acquirePublication(PUBLICATION_ID, BUYER, BUYER_ORG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already acquired");

        // Guard short-circuits before recording a receipt (no double-acquire side effect).
        verify(receiptRepository, never()).save(any());
    }

    @Test
    @DisplayName("Acquire with no org header and no resolvable default organization is rejected (\"organizationId required\"), no receipt saved")
    void missingDefaultOrgRejectsAcquire() {
        WorkflowPublicationEntity publication = publication("publisher-x", PublicationStatus.ACTIVE, PublicationVisibility.PUBLIC);
        when(publicationRepository.findById(PUBLICATION_ID)).thenReturn(Optional.of(publication));
        // CE / server-to-server path: no X-Organization-ID and the user has no default org to fall back on.
        when(authClient.getDefaultOrganizationIdForUser(BUYER)).thenReturn(null);

        assertThatThrownBy(() -> service.acquirePublication(PUBLICATION_ID, BUYER, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("organizationId required");

        verify(receiptRepository, never()).save(any());
    }

    private WorkflowPublicationEntity publication(String publisherId,
                                                  PublicationStatus status,
                                                  PublicationVisibility visibility) {
        WorkflowPublicationEntity publication = new WorkflowPublicationEntity();
        publication.setId(PUBLICATION_ID);
        publication.setWorkflowId(WORKFLOW_ID);
        publication.setPublisherId(publisherId);
        publication.setStatus(status);
        publication.setVisibility(visibility);
        publication.setCreditsPerUse(0);
        Map<String, Object> plan = new HashMap<>();
        plan.put("cores", List.of());
        publication.setPlanSnapshot(plan);
        return publication;
    }
}
