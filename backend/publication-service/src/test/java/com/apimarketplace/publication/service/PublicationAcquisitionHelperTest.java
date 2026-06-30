package com.apimarketplace.publication.service;

import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.auth.client.entitlement.ResourceType;
import com.apimarketplace.publication.domain.PublicationReceiptEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.OwnerType;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PublicationAcquisitionHelper")
class PublicationAcquisitionHelperTest {

    private static final String PUBLISHER = "publisher-tenant";
    private static final String ACQUIRER = "acquirer-tenant";
    private static final String ACQUIRER_ORG = "acquirer-org-uuid";

    @Mock private WorkflowPublicationRepository publicationRepository;
    @Mock private PublicationReceiptRepository receiptRepository;
    @Mock private EntitlementGuard entitlementGuard;

    private PublicationAcquisitionHelper helper;

    @BeforeEach
    void setUp() {
        helper = new PublicationAcquisitionHelper(publicationRepository, receiptRepository, entitlementGuard);
    }

    @Test
    @DisplayName("validateAndCheckEntitlement rejects private publications for first-time acquirers")
    void validateAndCheckEntitlementRejectsPrivatePublicationWithoutReceipt() {
        WorkflowPublicationEntity publication = publication(PublicationVisibility.PRIVATE, PublicationStatus.ACTIVE);
        when(receiptRepository.existsByOrganizationIdAndPublicationId(ACQUIRER_ORG, publication.getId())).thenReturn(false);

        assertThatThrownBy(() -> helper.validateAndCheckEntitlement(publication, ACQUIRER, ACQUIRER_ORG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private");

        verify(entitlementGuard, never()).check(anyString(), any(), any());
    }

    @Test
    @DisplayName("validateAndCheckEntitlement allows private publications for receipt holders")
    void validateAndCheckEntitlementAllowsPrivatePublicationWithReceipt() {
        WorkflowPublicationEntity publication = publication(PublicationVisibility.PRIVATE, PublicationStatus.ACTIVE);
        when(receiptRepository.existsByOrganizationIdAndPublicationId(ACQUIRER_ORG, publication.getId())).thenReturn(true);

        boolean alreadyPaid = helper.validateAndCheckEntitlement(publication, ACQUIRER, ACQUIRER_ORG);

        assertThat(alreadyPaid).isTrue();
        verify(entitlementGuard, never()).check(anyString(), any(), any());
    }

    @Test
    @DisplayName("validateAndCheckEntitlement blocks rejected publications even for receipt holders")
    void validateAndCheckEntitlementBlocksRejectedPublicationWithReceipt() {
        WorkflowPublicationEntity publication = publication(PublicationVisibility.PUBLIC, PublicationStatus.REJECTED);
        when(receiptRepository.existsByOrganizationIdAndPublicationId(ACQUIRER_ORG, publication.getId())).thenReturn(true);

        assertThatThrownBy(() -> helper.validateAndCheckEntitlement(publication, ACQUIRER, ACQUIRER_ORG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not available");

        verify(entitlementGuard, never()).check(anyString(), any(), any());
    }

    @Test
    @DisplayName("validateAndCheckEntitlement rejects acquisition from the publication owner organization")
    void validateAndCheckEntitlementRejectsOwnerOrganization() {
        WorkflowPublicationEntity publication = publication(PublicationVisibility.PUBLIC, PublicationStatus.ACTIVE);
        publication.setOwnerType(OwnerType.ORG);
        publication.setOwnerId(ACQUIRER_ORG);

        assertThatThrownBy(() -> helper.validateAndCheckEntitlement(publication, ACQUIRER, ACQUIRER_ORG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("own publication");

        verify(receiptRepository, never()).existsByOrganizationIdAndPublicationId(anyString(), any());
        verify(entitlementGuard, never()).check(anyString(), any(), any());
    }

    @Test
    @DisplayName("validateAndCheckEntitlement allows inactive publications for receipt holders")
    void validateAndCheckEntitlementAllowsInactivePublicationWithReceipt() {
        WorkflowPublicationEntity publication = publication(PublicationVisibility.PUBLIC, PublicationStatus.INACTIVE);
        when(receiptRepository.existsByOrganizationIdAndPublicationId(ACQUIRER_ORG, publication.getId())).thenReturn(true);

        boolean alreadyPaid = helper.validateAndCheckEntitlement(publication, ACQUIRER, ACQUIRER_ORG);

        assertThat(alreadyPaid).isTrue();
        verify(entitlementGuard, never()).check(anyString(), any(), any());
    }

    @Test
    @DisplayName("validateAndCheckEntitlement rejects pending-review publications for first-time acquirers")
    void validateAndCheckEntitlementRejectsPendingReviewPublicationWithoutReceipt() {
        WorkflowPublicationEntity publication = publication(PublicationVisibility.PUBLIC, PublicationStatus.PENDING_REVIEW);
        when(receiptRepository.existsByOrganizationIdAndPublicationId(ACQUIRER_ORG, publication.getId())).thenReturn(false);

        assertThatThrownBy(() -> helper.validateAndCheckEntitlement(publication, ACQUIRER, ACQUIRER_ORG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not active");

        verify(entitlementGuard, never()).check(anyString(), any(), any());
    }

    @Test
    @DisplayName("validateAndCheckEntitlement rejects inactive publications for first-time acquirers")
    void validateAndCheckEntitlementRejectsInactivePublicationWithoutReceipt() {
        WorkflowPublicationEntity publication = publication(PublicationVisibility.PUBLIC, PublicationStatus.INACTIVE);
        when(receiptRepository.existsByOrganizationIdAndPublicationId(ACQUIRER_ORG, publication.getId())).thenReturn(false);

        assertThatThrownBy(() -> helper.validateAndCheckEntitlement(publication, ACQUIRER, ACQUIRER_ORG))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not active");

        verify(entitlementGuard, never()).check(anyString(), any(), any());
    }

    @Test
    @DisplayName("validateAndCheckEntitlement enforces the APPLICATION quota on first-time acquisition")
    void validateAndCheckEntitlementEnforcesQuotaOnFirstTimeAcquisition() {
        WorkflowPublicationEntity publication = publication(PublicationVisibility.PUBLIC, PublicationStatus.ACTIVE);
        when(receiptRepository.existsByOrganizationIdAndPublicationId(ACQUIRER_ORG, publication.getId())).thenReturn(false);

        boolean alreadyPaid = helper.validateAndCheckEntitlement(publication, ACQUIRER, ACQUIRER_ORG);

        assertThat(alreadyPaid).isFalse();
        verify(entitlementGuard).check(eq(ACQUIRER), eq(ResourceType.APPLICATION), any());
    }

    @Test
    @DisplayName("hasReceipt fails closed with a V261 message when organizationId is null")
    void hasReceiptRejectsNullOrganizationIdPostV261() {
        UUID publicationId = UUID.randomUUID();

        assertThatThrownBy(() -> helper.hasReceipt(ACQUIRER, publicationId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("organizationId required");

        verify(receiptRepository, never()).existsByOrganizationIdAndPublicationId(anyString(), any());
    }

    @Test
    @DisplayName("hasReceipt fails closed with a V261 message when organizationId is blank")
    void hasReceiptRejectsBlankOrganizationIdPostV261() {
        UUID publicationId = UUID.randomUUID();

        assertThatThrownBy(() -> helper.hasReceipt(ACQUIRER, publicationId, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("organizationId required");

        verify(receiptRepository, never()).existsByOrganizationIdAndPublicationId(anyString(), any());
    }

    @Test
    @DisplayName("enforceEntitlementIfFirstTime fails closed via countReceiptsInScope when organizationId is null")
    void enforceEntitlementFirstTimeRejectsNullOrganizationIdViaCountReceiptsInScope() {
        // alreadyPaid=false routes into entitlementGuard.check, which invokes the LongSupplier
        // (countReceiptsInScope) only when the plan limit is finite. Drive that here so the supplier
        // runs and enforces the post-V261 non-blank organizationId contract instead of silently scoping null.
        doAnswer(inv -> {
            inv.getArgument(2, java.util.function.LongSupplier.class).getAsLong();
            return null;
        }).when(entitlementGuard).check(eq(ACQUIRER), eq(ResourceType.APPLICATION),
                any(java.util.function.LongSupplier.class));

        assertThatThrownBy(() -> helper.enforceEntitlementIfFirstTime(ACQUIRER, null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("organizationId required");

        // Prove the path actually routed through the guard's count supplier (not short-circuited),
        // and that it failed at the org guard before ever counting receipts.
        verify(entitlementGuard).check(eq(ACQUIRER), eq(ResourceType.APPLICATION),
                any(java.util.function.LongSupplier.class));
        verify(receiptRepository, never()).countByOrganizationId(anyString());
    }

    @Test
    @DisplayName("recordAcquisition fails closed with a V261 message when organizationId is null on first acquisition")
    void recordAcquisitionRejectsNullOrganizationIdPostV261() {
        WorkflowPublicationEntity publication = publication(PublicationVisibility.PUBLIC, PublicationStatus.ACTIVE);

        assertThatThrownBy(() -> helper.recordAcquisition(publication, ACQUIRER, null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("organizationId required");

        verify(receiptRepository, never()).save(any());
        verify(publicationRepository, never()).incrementUsage(any());
    }

    @Test
    @DisplayName("recordAcquisition fails closed with a V261 message when organizationId is blank on first acquisition")
    void recordAcquisitionRejectsBlankOrganizationIdPostV261() {
        WorkflowPublicationEntity publication = publication(PublicationVisibility.PUBLIC, PublicationStatus.ACTIVE);

        assertThatThrownBy(() -> helper.recordAcquisition(publication, ACQUIRER, "   ", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("organizationId required");

        verify(receiptRepository, never()).save(any());
        verify(publicationRepository, never()).incrementUsage(any());
    }

    @Test
    @DisplayName("recordAcquisition creates a scoped receipt and increments usage on first acquisition")
    void recordAcquisitionCreatesScopedReceiptAndIncrementsUsageOnFirstAcquisition() {
        WorkflowPublicationEntity publication = publication(PublicationVisibility.PUBLIC, PublicationStatus.ACTIVE);
        publication.setCreditsPerUse(7);

        helper.recordAcquisition(publication, ACQUIRER, ACQUIRER_ORG, false);

        ArgumentCaptor<PublicationReceiptEntity> receiptCaptor = ArgumentCaptor.forClass(PublicationReceiptEntity.class);
        verify(receiptRepository).save(receiptCaptor.capture());
        assertThat(receiptCaptor.getValue().getTenantId()).isEqualTo(ACQUIRER);
        assertThat(receiptCaptor.getValue().getOrganizationId()).isEqualTo(ACQUIRER_ORG);
        assertThat(receiptCaptor.getValue().getPublicationId()).isEqualTo(publication.getId());
        assertThat(receiptCaptor.getValue().getCreditsPaid()).isEqualTo(7);
        verify(publicationRepository).incrementUsage(publication.getId());
    }

    @Test
    @DisplayName("recordAcquisition skips receipt and usage mutations on re-acquisition")
    void recordAcquisitionSkipsReceiptAndUsageForReacquisition() {
        WorkflowPublicationEntity publication = publication(PublicationVisibility.PUBLIC, PublicationStatus.INACTIVE);

        helper.recordAcquisition(publication, ACQUIRER, ACQUIRER_ORG, true);

        verify(receiptRepository, never()).save(any());
        verify(publicationRepository, never()).incrementUsage(any());
    }

    private static WorkflowPublicationEntity publication(PublicationVisibility visibility,
                                                         PublicationStatus status) {
        WorkflowPublicationEntity publication = new WorkflowPublicationEntity();
        publication.setId(UUID.randomUUID());
        publication.setPublisherId(PUBLISHER);
        publication.setVisibility(visibility);
        publication.setStatus(status);
        return publication;
    }
}
