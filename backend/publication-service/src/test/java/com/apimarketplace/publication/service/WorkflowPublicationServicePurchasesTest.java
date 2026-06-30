package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.PublicationReceiptEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * My Purchases for a cloud-linked CE: a REMOTE acquisition's receipt carries a
 * CLOUD publication id that is absent from the local catalog, so the local
 * publication lookup is null. {@code buildPurchases} must synthesize a minimal
 * publication from the cloned workflow (title) + the receipt (price) so the
 * purchase still renders, instead of being dropped by the frontend. LOCAL
 * receipts keep their real publication entity.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationService.getPurchases - cloud-sourced purchase synthesis")
class WorkflowPublicationServicePurchasesTest {

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

    private static final String TENANT = "t1";
    private static final String ORG = "org-1";
    private static final UUID LOCAL_PUB = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID REMOTE_PUB = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @BeforeEach
    void setUp() {
        service = new WorkflowPublicationService(
                publicationRepository, snapshotVersionRepository, receiptRepository, reviewRepository,
                orchestratorClient, agentClient, interfaceClient, dataSourceClient, breakdownService,
                new ObjectMapper(), snapshotCloneService, entitlementGuard, authClient);
    }

    @Test
    @DisplayName("Remote receipt → synth publication from the clone title + receipt price; local receipt keeps its entity")
    void remoteReceiptSynthesizesPublicationFromClone() {
        PublicationReceiptEntity localReceipt = new PublicationReceiptEntity(TENANT, LOCAL_PUB, 0, ORG);
        PublicationReceiptEntity remoteReceipt = new PublicationReceiptEntity(TENANT, REMOTE_PUB, 5, ORG);
        remoteReceipt.setRemoteAcquisition(true);
        when(receiptRepository.findByOrganizationId(ORG)).thenReturn(List.of(localReceipt, remoteReceipt));

        // Only the LOCAL publication exists in the local catalog.
        WorkflowPublicationEntity localEntity = new WorkflowPublicationEntity();
        localEntity.setId(LOCAL_PUB);
        localEntity.setTitle("Local App");
        when(publicationRepository.findAllById(anyList())).thenReturn(List.of(localEntity));

        // The remote acquisition's cloned workflow carries the display title.
        String cloneId = UUID.randomUUID().toString();
        Map<String, Object> clone = new HashMap<>();
        clone.put("id", cloneId);
        clone.put("sourcePublicationId", REMOTE_PUB.toString());
        clone.put("title", "Remote App Clone");
        clone.put("entryInterfaceId", "iface-entry"); // orchestrator's lean acquired-workflows payload
        when(orchestratorClient.getAcquiredWorkflows(TENANT, ORG)).thenReturn(List.of(clone));
        when(orchestratorClient.existsBySourcePublication(any(UUID.class), eq(TENANT), eq(ORG))).thenReturn(true);

        List<Map<String, Object>> purchases = service.getPurchases(TENANT, ORG);

        assertThat(purchases).hasSize(2);

        Map<String, Object> remoteItem = purchases.stream()
                .filter(p -> REMOTE_PUB.toString().equals(p.get("publicationId"))).findFirst().orElseThrow();
        Object remotePub = remoteItem.get("publication");
        assertThat(remotePub).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> synth = (Map<String, Object>) remotePub;
        assertThat(synth.get("id")).isEqualTo(REMOTE_PUB.toString());
        assertThat(synth.get("title")).isEqualTo("Remote App Clone"); // recovered from the clone
        assertThat(synth.get("creditsPerUse")).isEqualTo(5);          // price from the receipt
        assertThat(synth.get("remote")).isEqualTo(true);
        // A1 - the clone's local-render coords ride the synth so the card can fall back to the
        // acquirer's OWN clone when the cloud source is gone.
        assertThat(synth.get("localShowcase")).isEqualTo(true);
        assertThat(synth.get("acquiredWorkflowId")).isEqualTo(cloneId);
        assertThat(synth.get("showcaseInterfaceId")).isEqualTo("iface-entry");

        // The LOCAL receipt keeps its real publication entity (unchanged behavior).
        Map<String, Object> localItem = purchases.stream()
                .filter(p -> LOCAL_PUB.toString().equals(p.get("publicationId"))).findFirst().orElseThrow();
        assertThat(localItem.get("publication")).isSameAs(localEntity);
    }

    @Test
    @DisplayName("Remote clone with NO interface (no entryInterfaceId) → synth carries NO localShowcase (nothing to render locally)")
    void remoteCloneWithoutEntryInterfaceHasNoLocalShowcase() {
        PublicationReceiptEntity remoteReceipt = new PublicationReceiptEntity(TENANT, REMOTE_PUB, 0, ORG);
        remoteReceipt.setRemoteAcquisition(true);
        when(receiptRepository.findByOrganizationId(ORG)).thenReturn(List.of(remoteReceipt));
        when(publicationRepository.findAllById(anyList())).thenReturn(List.of());

        Map<String, Object> clone = new HashMap<>();
        clone.put("id", UUID.randomUUID().toString());
        clone.put("sourcePublicationId", REMOTE_PUB.toString());
        clone.put("title", "Workflow-only Clone");
        // No entryInterfaceId (the clone has no interface) -> no local render possible.
        when(orchestratorClient.getAcquiredWorkflows(TENANT, ORG)).thenReturn(List.of(clone));
        when(orchestratorClient.existsBySourcePublication(any(UUID.class), eq(TENANT), eq(ORG))).thenReturn(true);

        List<Map<String, Object>> purchases = service.getPurchases(TENANT, ORG);

        @SuppressWarnings("unchecked")
        Map<String, Object> synth = (Map<String, Object>) purchases.get(0).get("publication");
        assertThat(synth.get("title")).isEqualTo("Workflow-only Clone");
        assertThat(synth).doesNotContainKey("localShowcase");
        assertThat(synth).doesNotContainKey("acquiredWorkflowId");
    }

    @Test
    @DisplayName("Non-remote receipt with no local publication (publisher deleted it) is NOT synthesized - stays null (prior behavior)")
    void nonRemoteMissingPublicationIsNotSynthesized() {
        // A LOCAL acquisition whose publication was later deleted: receipt is NOT remote.
        PublicationReceiptEntity deletedPubReceipt = new PublicationReceiptEntity(TENANT, REMOTE_PUB, 0, ORG);
        // remoteAcquisition stays false (default).
        when(receiptRepository.findByOrganizationId(ORG)).thenReturn(List.of(deletedPubReceipt));
        when(publicationRepository.findAllById(anyList())).thenReturn(List.of()); // pub gone locally
        when(orchestratorClient.existsBySourcePublication(any(UUID.class), eq(TENANT), eq(ORG))).thenReturn(false);

        List<Map<String, Object>> purchases = service.getPurchases(TENANT, ORG);

        assertThat(purchases).hasSize(1);
        assertThat(purchases.get(0).get("publication")).isNull();
        // The remote-clone title lookup is only triggered for remote receipts, so a
        // non-remote miss never queries the orchestrator for acquired workflows.
        verify(orchestratorClient, never()).getAcquiredWorkflows(anyString(), anyString());
    }
}
