package com.apimarketplace.publication.controller;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.publication.domain.PublicationReceiptEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cloud-side CE marketplace endpoints. Regression context: CE paid
 * acquisitions were impossible - this controller was never enabled
 * ({@code marketplace.ce-download.enabled} was set nowhere) and the cloud
 * gateway had no /api/ce-marketplace route. These tests pin the billing
 * contract the CE install (RemoteMarketplaceService) depends on now that the
 * endpoint is actually reachable.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CeDownloadController (cloud side of CE marketplace acquisition)")
class CeDownloadControllerTest {

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private PublicationReceiptRepository receiptRepository;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private AuthClient authClient;

    private CeDownloadController controller;

    private static final UUID PUB_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Long CLOUD_USER_ID = 42L;
    private static final String CLOUD_ORG_ID = "33333333-3333-4333-8333-333333333333";

    @BeforeEach
    void setUp() {
        controller = new CeDownloadController(
                publicationRepository, receiptRepository, creditClient, authClient);
    }

    private WorkflowPublicationEntity publication(int creditsPerUse) {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(PUB_ID);
        pub.setTitle("Paid CE Workflow");
        pub.setDescription("desc");
        pub.setPublisherName("publisher");
        pub.setStatus(PublicationStatus.ACTIVE);
        pub.setVisibility(PublicationVisibility.PUBLIC);
        pub.setCreditsPerUse(creditsPerUse);
        pub.setPlanSnapshot(Map.of("triggers", "raw"));
        return pub;
    }

    @Nested
    @DisplayName("GET /{publicationId}/snapshot")
    class GetSnapshot {

        @Test
        @DisplayName("Free publication returns the snapshot directly - free CE acquisitions need no cloud link")
        void freePublicationReturnsSnapshot() {
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(publication(0)));

            ResponseEntity<Map<String, Object>> response = controller.getSnapshot(PUB_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("planSnapshot", Map.of("triggers", "raw"));
            assertThat(response.getBody()).containsEntry("creditsPerUse", 0);
            // publicationType lets the acquiring CE dispatch its clone; defaults to WORKFLOW.
            assertThat(response.getBody()).containsEntry("publicationType", "WORKFLOW");
        }

        @Test
        @DisplayName("Free AGENT publication ships publicationType=AGENT + the agent snapshot so the CE can clone the fleet")
        void freeAgentPublicationShipsAgentSnapshot() {
            WorkflowPublicationEntity pub = publication(0);
            pub.setPublicationType(PublicationType.AGENT);
            pub.setAgentSnapshot(Map.of("agents", Map.of("a", Map.of("name", "Root"))));
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));

            ResponseEntity<Map<String, Object>> response = controller.getSnapshot(PUB_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("publicationType", "AGENT");
            assertThat(response.getBody()).containsKey("agentSnapshot");
        }

        @Test
        @DisplayName("Paid publication returns 402 with creditsPerUse + acquireEndpoint pointer (no snapshot leak)")
        void paidPublicationReturns402Pointer() {
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(publication(17)));

            ResponseEntity<Map<String, Object>> response = controller.getSnapshot(PUB_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
            assertThat(response.getBody()).containsEntry("creditsPerUse", 17);
            assertThat(response.getBody())
                    .containsEntry("acquireEndpoint", "/api/ce-marketplace/" + PUB_ID + "/acquire-with-auth");
            assertThat(response.getBody()).doesNotContainKey("planSnapshot");
        }

        @Test
        @DisplayName("Inactive publication returns 410 GONE")
        void inactivePublicationReturns410() {
            WorkflowPublicationEntity pub = publication(0);
            pub.setStatus(PublicationStatus.INACTIVE);
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));

            ResponseEntity<Map<String, Object>> response = controller.getSnapshot(PUB_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        }

        @Test
        @DisplayName("Unknown publication returns 404 NOT_FOUND")
        void unknownPublicationReturns404() {
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.empty());

            ResponseEntity<Map<String, Object>> response = controller.getSnapshot(PUB_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Free publication with null snapshot/description/nodeIcons still returns 200 with those keys present as null")
        void freePublicationWithNullFieldsKeepsNullEntries() {
            // buildSnapshotResponse uses a HashMap (not Map.of), so null field
            // values are kept rather than throwing. The CE consumer must receive
            // the keys with null values, not a 500. Only id + creditsPerUse must
            // be non-null (getId().toString() + the creditsPerUse > 0 branch).
            WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
            pub.setId(PUB_ID);
            pub.setStatus(PublicationStatus.ACTIVE);
            pub.setVisibility(PublicationVisibility.PUBLIC);
            pub.setCreditsPerUse(0);
            // title, description, nodeIcons, publisherName, planSnapshot, agentSnapshot left null
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));

            ResponseEntity<Map<String, Object>> response = controller.getSnapshot(PUB_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsKey("planSnapshot");
            assertThat(response.getBody().get("planSnapshot")).isNull();
            assertThat(response.getBody()).containsKey("nodeIcons");
            assertThat(response.getBody().get("nodeIcons")).isNull();
            assertThat(response.getBody()).containsKey("description");
            assertThat(response.getBody().get("description")).isNull();
            // publicationType defaults to WORKFLOW even when the entity field is null.
            assertThat(response.getBody()).containsEntry("publicationType", "WORKFLOW");
            // A null agentSnapshot is omitted entirely (not stored as a null entry).
            assertThat(response.getBody()).doesNotContainKey("agentSnapshot");
        }
    }

    @Nested
    @DisplayName("POST /{publicationId}/acquire-with-auth")
    class AcquireWithAuth {

        @Test
        @DisplayName("Paid acquisition bills MARKETPLACE_PURCHASE (consumeFixedCredits) on the linked cloud user and persists an org-stamped receipt")
        void paidAcquisitionChargesLinkedCloudUser() {
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(publication(17)));
            when(authClient.getDefaultOrganizationIdForUser(CLOUD_USER_ID.toString())).thenReturn(CLOUD_ORG_ID);
            when(receiptRepository.existsByOrganizationIdAndPublicationId(CLOUD_ORG_ID, PUB_ID)).thenReturn(false);
            when(creditClient.consumeFixedCredits(CLOUD_USER_ID.toString(), PUB_ID.toString(), 17))
                    .thenReturn(Map.of("success", true));

            ResponseEntity<Map<String, Object>> response = controller.acquireWithAuth(PUB_ID, CLOUD_USER_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("creditsPaid", 17);
            assertThat(response.getBody()).containsEntry("planSnapshot", Map.of("triggers", "raw"));
            verify(creditClient).consumeFixedCredits(CLOUD_USER_ID.toString(), PUB_ID.toString(), 17);

            // Receipt is RESERVED (saveAndFlush) before charging, and kept on success.
            ArgumentCaptor<PublicationReceiptEntity> receipt =
                    ArgumentCaptor.forClass(PublicationReceiptEntity.class);
            verify(receiptRepository).saveAndFlush(receipt.capture());
            verify(receiptRepository, never()).delete(any());
            assertThat(receipt.getValue().getTenantId()).isEqualTo(CLOUD_USER_ID.toString());
            assertThat(receipt.getValue().getPublicationId()).isEqualTo(PUB_ID);
            assertThat(receipt.getValue().getCreditsPaid()).isEqualTo(17);
            assertThat(receipt.getValue().getOrganizationId()).isEqualTo(CLOUD_ORG_ID);
        }

        @Test
        @DisplayName("Concurrent acquire loses the unique-index race: returns alreadyOwned and is NEVER charged (double-charge regression)")
        void concurrentAcquireDoesNotDoubleCharge() {
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(publication(17)));
            when(authClient.getDefaultOrganizationIdForUser(CLOUD_USER_ID.toString())).thenReturn(CLOUD_ORG_ID);
            // Fast-path existsBy misses (first receipt not yet committed), so both requests
            // proceed to reserve; this one loses the unique (organization_id, publication_id) race.
            // The re-check inside the catch then confirms the row now exists -> already owned.
            when(receiptRepository.existsByOrganizationIdAndPublicationId(CLOUD_ORG_ID, PUB_ID))
                    .thenReturn(false, true);
            when(receiptRepository.saveAndFlush(any(PublicationReceiptEntity.class)))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key uq_publication_receipts_org_scope"));

            ResponseEntity<Map<String, Object>> response = controller.acquireWithAuth(PUB_ID, CLOUD_USER_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("alreadyOwned", true);
            // The loser must NOT be charged, and must NOT delete the winner's row.
            verify(creditClient, never()).consumeFixedCredits(anyString(), anyString(), anyInt());
            verify(receiptRepository, never()).delete(any());
        }

        @Test
        @DisplayName("A non-duplicate integrity violation is NOT masked as already-owned - it surfaces (never a free grant)")
        void unrelatedIntegrityViolationIsRethrown() {
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(publication(17)));
            when(authClient.getDefaultOrganizationIdForUser(CLOUD_USER_ID.toString())).thenReturn(CLOUD_ORG_ID);
            // existsBy is false both on the fast path AND in the catch re-check: the violation
            // was something OTHER than the org-scope unique row, so it must propagate.
            when(receiptRepository.existsByOrganizationIdAndPublicationId(CLOUD_ORG_ID, PUB_ID))
                    .thenReturn(false, false);
            when(receiptRepository.saveAndFlush(any(PublicationReceiptEntity.class)))
                    .thenThrow(new org.springframework.dao.DataIntegrityViolationException("some other constraint"));

            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> controller.acquireWithAuth(PUB_ID, CLOUD_USER_ID))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

            verify(creditClient, never()).consumeFixedCredits(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("Insufficient cloud credits surfaces as 402 with the required amount - and no receipt is written")
        void insufficientCreditsReturns402WithoutReceipt() {
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(publication(17)));
            when(authClient.getDefaultOrganizationIdForUser(CLOUD_USER_ID.toString())).thenReturn(CLOUD_ORG_ID);
            when(receiptRepository.existsByOrganizationIdAndPublicationId(CLOUD_ORG_ID, PUB_ID)).thenReturn(false);
            when(creditClient.consumeFixedCredits(CLOUD_USER_ID.toString(), PUB_ID.toString(), 17))
                    .thenReturn(Map.of("success", false, "error", "402 Insufficient credits"));

            ResponseEntity<Map<String, Object>> response = controller.acquireWithAuth(PUB_ID, CLOUD_USER_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
            assertThat(response.getBody()).containsEntry("required", 17);
            // The reservation is rolled back on a failed charge, so no receipt survives.
            verify(receiptRepository).saveAndFlush(any());
            verify(receiptRepository).delete(any());
        }

        @Test
        @DisplayName("Re-download by an owner is free - returns the snapshot with alreadyOwned=true and never re-bills")
        void reDownloadDoesNotDoubleCharge() {
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(publication(17)));
            when(authClient.getDefaultOrganizationIdForUser(CLOUD_USER_ID.toString())).thenReturn(CLOUD_ORG_ID);
            when(receiptRepository.existsByOrganizationIdAndPublicationId(CLOUD_ORG_ID, PUB_ID)).thenReturn(true);

            ResponseEntity<Map<String, Object>> response = controller.acquireWithAuth(PUB_ID, CLOUD_USER_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("alreadyOwned", true);
            verify(creditClient, never()).consumeFixedCredits(anyString(), anyString(), anyInt());
            // Fast path returns before any reservation attempt.
            verify(receiptRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("Free publication via acquire-with-auth saves a 0-credit receipt without calling the credit client")
        void freePublicationSkipsBilling() {
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(publication(0)));
            when(authClient.getDefaultOrganizationIdForUser(CLOUD_USER_ID.toString())).thenReturn(CLOUD_ORG_ID);
            when(receiptRepository.existsByOrganizationIdAndPublicationId(CLOUD_ORG_ID, PUB_ID)).thenReturn(false);

            ResponseEntity<Map<String, Object>> response = controller.acquireWithAuth(PUB_ID, CLOUD_USER_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(creditClient, never()).consumeFixedCredits(anyString(), anyString(), anyInt());
            verify(receiptRepository).saveAndFlush(any(PublicationReceiptEntity.class));
        }

        @Test
        @DisplayName("Cloud user without a default organization gets 409 CONFLICT and is never billed")
        void missingDefaultOrgReturns409WithoutBilling() {
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(publication(17)));
            when(authClient.getDefaultOrganizationIdForUser(CLOUD_USER_ID.toString())).thenReturn(null);

            ResponseEntity<Map<String, Object>> response = controller.acquireWithAuth(PUB_ID, CLOUD_USER_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            verify(creditClient, never()).consumeFixedCredits(anyString(), anyString(), anyInt());
            verify(receiptRepository, never()).save(any());
        }

        @Test
        @DisplayName("Blank (non-null) default-org string is treated the same as null - 409 CONFLICT, never billed, no receipt")
        void blankDefaultOrgReturns409WithoutBilling() {
            // The guard is `cloudOrgId == null || cloudOrgId.isBlank()`. A blank
            // string slips past the null check, so without the isBlank() branch the
            // controller would persist a receipt with an empty organization_id and
            // violate the V261 NOT NULL constraint. Pin that an empty string still
            // short-circuits to 409 before any billing or receipt persist.
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(publication(17)));
            when(authClient.getDefaultOrganizationIdForUser(CLOUD_USER_ID.toString())).thenReturn("   ");

            ResponseEntity<Map<String, Object>> response = controller.acquireWithAuth(PUB_ID, CLOUD_USER_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            // The org was never resolved, so the receipt-existence check must not run either.
            verify(receiptRepository, never()).existsByOrganizationIdAndPublicationId(anyString(), any());
            verify(creditClient, never()).consumeFixedCredits(anyString(), anyString(), anyInt());
            verify(receiptRepository, never()).save(any());
        }

        @Test
        @DisplayName("Private publication is 403 even for an authenticated cloud user")
        void privatePublicationReturns403() {
            WorkflowPublicationEntity pub = publication(17);
            pub.setVisibility(PublicationVisibility.PRIVATE);
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));

            ResponseEntity<Map<String, Object>> response = controller.acquireWithAuth(PUB_ID, CLOUD_USER_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verify(creditClient, never()).consumeFixedCredits(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("Unknown publication returns 404")
        void unknownPublicationReturns404() {
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.empty());

            ResponseEntity<Map<String, Object>> response = controller.acquireWithAuth(PUB_ID, CLOUD_USER_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("Inactive publication is 410 GONE even for an authenticated cloud user - never resolves org or bills")
        void inactivePublicationReturns410() {
            WorkflowPublicationEntity pub = publication(17);
            pub.setStatus(PublicationStatus.PENDING_REVIEW);
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(pub));

            ResponseEntity<Map<String, Object>> response = controller.acquireWithAuth(PUB_ID, CLOUD_USER_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
            // The status gate precedes org resolution + billing, so neither runs.
            verify(authClient, never()).getDefaultOrganizationIdForUser(anyString());
            verify(creditClient, never()).consumeFixedCredits(anyString(), anyString(), anyInt());
            verify(receiptRepository, never()).save(any());
        }

        @Test
        @DisplayName("A non-402/non-Insufficient credit error (e.g. service timeout) surfaces as 500 - and no receipt is written")
        void unmappedCreditErrorReturns500WithoutReceipt() {
            // deductCredits only maps the message to 402 when it contains "402" or
            // "Insufficient"; any other failure (timeout, downstream outage) is a
            // generic 500 with no receipt. Pin that fallback so a billing outage is
            // never silently treated as a successful acquisition.
            when(publicationRepository.findById(PUB_ID)).thenReturn(Optional.of(publication(17)));
            when(authClient.getDefaultOrganizationIdForUser(CLOUD_USER_ID.toString())).thenReturn(CLOUD_ORG_ID);
            when(receiptRepository.existsByOrganizationIdAndPublicationId(CLOUD_ORG_ID, PUB_ID)).thenReturn(false);
            when(creditClient.consumeFixedCredits(CLOUD_USER_ID.toString(), PUB_ID.toString(), 17))
                    .thenReturn(Map.of("success", false, "error", "Service timeout"));

            ResponseEntity<Map<String, Object>> response = controller.acquireWithAuth(PUB_ID, CLOUD_USER_ID);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).containsEntry("error", "Purchase processing failed");
            assertThat(response.getBody()).doesNotContainKey("required");
            // Reservation rolled back on the unmapped failure, so no receipt survives.
            verify(receiptRepository).saveAndFlush(any());
            verify(receiptRepository).delete(any());
        }
    }
}
