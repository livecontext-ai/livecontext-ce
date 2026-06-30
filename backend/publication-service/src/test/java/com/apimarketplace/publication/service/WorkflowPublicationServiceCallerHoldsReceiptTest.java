package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WorkflowPublicationService#callerHoldsReceipt}.
 *
 * <p>This is the local signal that lets the publication-detail visibility gate
 * keep an acquirer's installed-application view readable after the publisher
 * unpublishes (status -&gt; INACTIVE) or privatises (visibility -&gt; PRIVATE)
 * the source publication. The method must (a) resolve receipts in the caller's
 * workspace scope and (b) be a defensive no-op (false, no DB touch) when any
 * required identifier is missing, since it runs on a read path.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationService.callerHoldsReceipt - acquirer detection")
class WorkflowPublicationServiceCallerHoldsReceiptTest {

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

    private static final UUID PUBLICATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String TENANT_ID = "tenant-001";
    private static final String ORG_ID = "org-acme";

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
    @DisplayName("returns true and queries receipts by the caller's org scope when a receipt exists")
    void returnsTrueWhenReceiptExistsInScope() {
        when(receiptRepository.existsByOrganizationIdAndPublicationId(ORG_ID, PUBLICATION_ID)).thenReturn(true);

        boolean result = service.callerHoldsReceipt(PUBLICATION_ID, TENANT_ID, ORG_ID);

        assertThat(result).isTrue();
        verify(receiptRepository).existsByOrganizationIdAndPublicationId(ORG_ID, PUBLICATION_ID);
    }

    @Test
    @DisplayName("returns false when no receipt exists in the caller's org scope")
    void returnsFalseWhenNoReceiptInScope() {
        when(receiptRepository.existsByOrganizationIdAndPublicationId(ORG_ID, PUBLICATION_ID)).thenReturn(false);

        boolean result = service.callerHoldsReceipt(PUBLICATION_ID, TENANT_ID, ORG_ID);

        assertThat(result).isFalse();
        verify(receiptRepository).existsByOrganizationIdAndPublicationId(ORG_ID, PUBLICATION_ID);
    }

    @Test
    @DisplayName("returns false without touching the repository when publicationId is null")
    void returnsFalseWhenPublicationIdNull() {
        boolean result = service.callerHoldsReceipt(null, TENANT_ID, ORG_ID);

        assertThat(result).isFalse();
        verifyNoInteractions(receiptRepository);
    }

    @Test
    @DisplayName("returns false without touching the repository when tenantId is null (anonymous /by-id path)")
    void returnsFalseWhenTenantIdNull() {
        boolean result = service.callerHoldsReceipt(PUBLICATION_ID, null, ORG_ID);

        assertThat(result).isFalse();
        verifyNoInteractions(receiptRepository);
    }

    @Test
    @DisplayName("returns false without touching the repository when the workspace scope is blank")
    void returnsFalseWhenOrgScopeBlank() {
        boolean result = service.callerHoldsReceipt(PUBLICATION_ID, TENANT_ID, "   ");

        assertThat(result).isFalse();
        verify(receiptRepository, never()).existsByOrganizationIdAndPublicationId(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("returns false without touching the repository when the workspace scope is null")
    void returnsFalseWhenOrgScopeNull() {
        boolean result = service.callerHoldsReceipt(PUBLICATION_ID, TENANT_ID, null);

        assertThat(result).isFalse();
        verifyNoInteractions(receiptRepository);
    }
}
