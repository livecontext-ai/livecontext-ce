package com.apimarketplace.publication.service;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.dto.PublisherProfileDto;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.OwnerType;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.service.resource.ResourcePublicationStrategy;
import com.apimarketplace.publication.service.resource.ResourcePublicationStrategy.ResourceMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResourcePublicationServiceTest {

    private static final String TENANT_ID = "publisher-user";
    private static final String ORGANIZATION_ID = "11111111-1111-4111-8111-111111111111";

    @Mock
    private WorkflowPublicationRepository publicationRepository;

    @Mock
    private PublicationReceiptRepository receiptRepository;

    @Mock
    private OrchestratorInternalClient orchestratorClient;

    @Mock
    private EntitlementGuard entitlementGuard;

    @Mock
    private LandingInterfaceSnapshotter landingInterfaceSnapshotter;

    @Mock
    private WorkflowPublicationService workflowPublicationService;

    @Mock
    private AuthClient authClient;

    @Mock
    private ResourcePublicationStrategy strategy;

    @Test
    @DisplayName("Passes active organization to landing snapshot when publishing org-scoped skills")
    void passesActiveOrganizationToLandingSnapshotWhenPublishingOrgScopedSkill() {
        UUID landingInterfaceId = UUID.randomUUID();
        String skillId = UUID.randomUUID().toString();
        Map<String, Object> landingSnapshot = Map.of(
                "interfaceId", landingInterfaceId.toString(),
                "name", "Org Landing");
        Map<String, Object> resourceSnapshot = new LinkedHashMap<>(Map.of(
                "name", "Skill",
                "description", "Skill description"));

        ResourcePublicationService service = newService();
        when(publicationRepository.findByPublicationTypeAndResourceId(PublicationType.SKILL, skillId))
                .thenReturn(Optional.empty());
        when(strategy.fetchOwnedResource(skillId, TENANT_ID))
                .thenReturn(new ResourceMetadata("Skill", "Skill description"));
        when(workflowPublicationService.isCallerInOwnerScope(
                any(WorkflowPublicationEntity.class), eq(TENANT_ID), eq(ORGANIZATION_ID)))
                .thenReturn(true);
        when(strategy.buildSnapshot(skillId, TENANT_ID)).thenReturn(resourceSnapshot);
        when(landingInterfaceSnapshotter.parseInterfaceId(landingInterfaceId.toString()))
                .thenReturn(landingInterfaceId);
        when(landingInterfaceSnapshotter.buildSnapshot(landingInterfaceId, TENANT_ID, ORGANIZATION_ID))
                .thenReturn(landingSnapshot);
        when(publicationRepository.save(any(WorkflowPublicationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WorkflowPublicationEntity publication = service.publishResource(
                publishSkillRequest(skillId, landingInterfaceId), TENANT_ID, ORGANIZATION_ID);

        verify(landingInterfaceSnapshotter).buildSnapshot(landingInterfaceId, TENANT_ID, ORGANIZATION_ID);
        verify(landingInterfaceSnapshotter, never()).buildSnapshot(landingInterfaceId, TENANT_ID);
        assertThat(publication.getOwnerType()).isEqualTo(OwnerType.ORG);
        assertThat(publication.getOwnerId()).isEqualTo(ORGANIZATION_ID);
        assertThat(publication.getPlanSnapshot()).containsEntry("landingInterface", landingSnapshot);
    }

    @Test
    @DisplayName("unpublishResource throws IllegalStateException (→409 at the controller) for a pending-review resource")
    void unpublishResourcePendingReviewThrowsIllegalState() {
        String skillId = UUID.randomUUID().toString();
        ResourcePublicationService service = newService();
        WorkflowPublicationEntity pending = ownedSkillPublication(skillId, PublicationStatus.PENDING_REVIEW);
        when(publicationRepository.findByPublicationTypeAndResourceId(PublicationType.SKILL, skillId))
                .thenReturn(Optional.of(pending));
        when(workflowPublicationService.isCallerInOwnerScope(pending, TENANT_ID, null)).thenReturn(true);

        assertThatThrownBy(() -> service.unpublishResource(PublicationType.SKILL, skillId, TENANT_ID, null))
                .isInstanceOf(PublicationPendingReviewException.class)
                .hasMessageContaining("pending review");

        verify(publicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("publishResource throws IllegalStateException (→409 at the controller) when re-publishing a pending-review resource")
    void publishResourceRepublishWhilePendingThrowsIllegalState() {
        UUID landingInterfaceId = UUID.randomUUID();
        String skillId = UUID.randomUUID().toString();
        ResourcePublicationService service = newService();
        WorkflowPublicationEntity pending = ownedSkillPublication(skillId, PublicationStatus.PENDING_REVIEW);
        when(landingInterfaceSnapshotter.parseInterfaceId(landingInterfaceId.toString()))
                .thenReturn(landingInterfaceId);
        when(strategy.fetchOwnedResource(skillId, TENANT_ID))
                .thenReturn(new ResourceMetadata("Skill", "Skill description"));
        when(publicationRepository.findByPublicationTypeAndResourceId(PublicationType.SKILL, skillId))
                .thenReturn(Optional.of(pending));
        when(workflowPublicationService.isCallerInOwnerScope(pending, TENANT_ID, null)).thenReturn(true);

        assertThatThrownBy(() -> service.publishResource(
                publishSkillRequest(skillId, landingInterfaceId), TENANT_ID, null))
                .isInstanceOf(PublicationPendingReviewException.class)
                .hasMessageContaining("pending review");

        verify(publicationRepository, never()).save(any());
    }

    @Test
    @DisplayName("getResourcePublicationStatuses folds repo rows into a (resourceId → {status, rejectionReason?}) batch map")
    void getResourcePublicationStatusesFoldsRows() {
        ResourcePublicationService service = newService();
        when(publicationRepository.findResourcePublicationStatusesByTypeAndResourceIds(
                eq(PublicationType.TABLE), any()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{"10", PublicationStatus.ACTIVE, null},
                        new Object[]{"11", PublicationStatus.REJECTED, "off-topic"}));

        Map<String, Map<String, String>> statuses =
                service.getResourcePublicationStatuses(PublicationType.TABLE, List.of("10", "11", "12"));

        assertThat(statuses.get("10")).containsEntry("status", "ACTIVE");
        assertThat(statuses.get("10")).doesNotContainKey("rejectionReason"); // reason only on REJECTED
        assertThat(statuses.get("11")).containsEntry("status", "REJECTED").containsEntry("rejectionReason", "off-topic");
        // id 12 has no shared publication → absent from the map (the caller reads it as "private").
        assertThat(statuses).doesNotContainKey("12");
    }

    @Test
    @DisplayName("getResourcePublicationStatuses short-circuits on empty input without hitting the repository")
    void getResourcePublicationStatusesEmptyInput() {
        ResourcePublicationService service = newService();

        assertThat(service.getResourcePublicationStatuses(PublicationType.TABLE, List.of())).isEmpty();

        verify(publicationRepository, never())
                .findResourcePublicationStatusesByTypeAndResourceIds(any(), any());
    }

    @Test
    @DisplayName("getResourcePublicationStatuses for AGENT keys on agentConfigId (UUID), never the resource_id query")
    void getResourcePublicationStatusesAgentUsesConfigIdQuery() {
        ResourcePublicationService service = newService();
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();
        when(publicationRepository.findAgentPublicationStatusesByConfigIds(any()))
                .thenReturn(List.<Object[]>of(
                        new Object[]{a1, PublicationStatus.ACTIVE, null},
                        new Object[]{a2, PublicationStatus.REJECTED, "off-topic"}));

        Map<String, Map<String, String>> statuses =
                service.getResourcePublicationStatuses(PublicationType.AGENT, List.of(a1.toString(), a2.toString()));

        assertThat(statuses.get(a1.toString())).containsEntry("status", "ACTIVE");
        assertThat(statuses.get(a1.toString())).doesNotContainKey("rejectionReason"); // reason only on REJECTED
        assertThat(statuses.get(a2.toString()))
                .containsEntry("status", "REJECTED").containsEntry("rejectionReason", "off-topic");
        // AGENT rows have resource_id = null, so the resource_id query would never match - it must not run.
        verify(publicationRepository).findAgentPublicationStatusesByConfigIds(any());
        verify(publicationRepository, never()).findResourcePublicationStatusesByTypeAndResourceIds(any(), any());
    }

    @Test
    @DisplayName("getResourcePublicationStatuses for AGENT parses only valid UUIDs into the config-id query")
    @SuppressWarnings("unchecked")
    void getResourcePublicationStatusesAgentParsesValidUuidsOnly() {
        ResourcePublicationService service = newService();
        UUID valid = UUID.randomUUID();
        when(publicationRepository.findAgentPublicationStatusesByConfigIds(any())).thenReturn(List.of());

        service.getResourcePublicationStatuses(PublicationType.AGENT, List.of(valid.toString(), "not-a-uuid"));

        ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(publicationRepository).findAgentPublicationStatusesByConfigIds(captor.capture());
        // The malformed id is dropped; only the parseable UUID reaches the query.
        assertThat(captor.getValue()).containsExactly(valid);
    }

    @Test
    @DisplayName("getResourcePublicationStatuses for AGENT short-circuits to empty when no id parses as a UUID")
    void getResourcePublicationStatusesAgentAllNonUuidShortCircuits() {
        ResourcePublicationService service = newService();

        assertThat(service.getResourcePublicationStatuses(PublicationType.AGENT, List.of("not-a-uuid", "also-bad")))
                .isEmpty();

        // Nothing parsed → no query of either shape.
        verify(publicationRepository, never()).findAgentPublicationStatusesByConfigIds(any());
        verify(publicationRepository, never()).findResourcePublicationStatusesByTypeAndResourceIds(any(), any());
    }

    private static WorkflowPublicationEntity ownedSkillPublication(String skillId, PublicationStatus status) {
        WorkflowPublicationEntity pub = new WorkflowPublicationEntity();
        pub.setId(UUID.randomUUID());
        pub.setPublisherId(TENANT_ID);
        pub.setPublicationType(PublicationType.SKILL);
        pub.setResourceId(skillId);
        pub.setStatus(status);
        // Personal owner scope so the re-publish scope-change guard passes.
        pub.setOwnerType(OwnerType.USER);
        pub.setOwnerId(TENANT_ID);
        return pub;
    }

    private ResourcePublicationService newService() {
        when(strategy.getPublicationType()).thenReturn(PublicationType.SKILL);
        lenient().when(strategy.getDisplayMode()).thenReturn(DisplayMode.SKILL);
        // publishResource → applyPublisherInfo → AuthClient.getPublisherProfile.
        // Lenient because the constructor itself never triggers this call.
        lenient().when(authClient.getPublisherProfile(any()))
                .thenReturn(new PublisherProfileDto(TENANT_ID, "Test Publisher", "test@publisher.com", "test-avatar-uuid"));
        return new ResourcePublicationService(
                publicationRepository,
                receiptRepository,
                orchestratorClient,
                entitlementGuard,
                new ObjectMapper(),
                landingInterfaceSnapshotter,
                workflowPublicationService,
                List.of(strategy),
                authClient);
    }

    private static Map<String, Object> publishSkillRequest(String skillId, UUID landingInterfaceId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", "SKILL");
        request.put("resourceId", skillId);
        request.put("interfaceId", landingInterfaceId.toString());
        request.put("title", "Skill");
        request.put("description", "Skill description");
        request.put("visibility", "PUBLIC");
        request.put("publisherName", "Publisher");
        request.put("publisherEmail", "publisher@example.com");
        return request;
    }
}
