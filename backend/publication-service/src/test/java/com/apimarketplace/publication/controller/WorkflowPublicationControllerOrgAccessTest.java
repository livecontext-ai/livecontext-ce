package com.apimarketplace.publication.controller;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.dto.PublicationListItem;
import com.apimarketplace.publication.service.AgentPublicationService;
import com.apimarketplace.publication.service.LandingInterfaceSnapshotter;
import com.apimarketplace.publication.service.OnboardingCategoryMapper;
import com.apimarketplace.publication.service.PublicationListQueryService;
import com.apimarketplace.publication.service.PublicationReviewService;
import com.apimarketplace.publication.service.ResourcePublicationService;
import com.apimarketplace.publication.service.ShowcaseFileRefRewriter;
import com.apimarketplace.publication.service.ShowcaseSnapshotReader;
import com.apimarketplace.publication.service.WorkflowPublicationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowPublicationController org access filtering")
class WorkflowPublicationControllerOrgAccessTest {

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

    private WorkflowPublicationController controller;

    @BeforeEach
    void setUp() {
        controller = new WorkflowPublicationController(
                publicationService, agentPublicationService, listQueryService,
                reviewService, resourcePublicationService, orchestratorClient,
                landingInterfaceSnapshotter, showcaseSnapshotReader, fileRefRewriter,
                new OnboardingCategoryMapper(), orgAccessGuard);
    }

    @Test
    @DisplayName("Application list excludes member-restricted application publications")
    void applicationListExcludesMemberRestrictedApplicationPublications() {
        UUID deniedId = UUID.randomUUID();
        when(orgAccessGuard.getRestrictedResourceIds("org-1", "member-1", "application", "MEMBER"))
                .thenReturn(Set.of(deniedId.toString(), "not-a-uuid"));
        when(listQueryService.findByScope(eq("member-1"), eq("org-1"), eq(true), any()))
                .thenReturn(List.of());

        ResponseEntity<?> response = controller.getMyPublications("member-1", "org-1", "MEMBER", true);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<UUID>> excludedIds = ArgumentCaptor.forClass(Set.class);
        verify(listQueryService).findByScope(eq("member-1"), eq("org-1"), eq(true), excludedIds.capture());
        assertThat(excludedIds.getValue()).containsExactly(deniedId);
    }

    @Test
    @DisplayName("Non-application publication list does not query application restrictions")
    void nonApplicationListDoesNotQueryApplicationRestrictions() {
        when(listQueryService.findByScope(eq("member-1"), eq("org-1"), eq(false), any()))
                .thenReturn(List.of());

        ResponseEntity<?> response = controller.getMyPublications("member-1", "org-1", "MEMBER", false);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(orgAccessGuard, never()).getRestrictedResourceIds(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Paged application list passes exclusions into the paged query")
    void pagedApplicationListPassesExclusionsIntoPagedQuery() {
        UUID deniedId = UUID.randomUUID();
        when(orgAccessGuard.getRestrictedResourceIds("org-1", "member-1", "application", "MEMBER"))
                .thenReturn(Set.of(deniedId.toString()));
        when(listQueryService.findByScopePaged(
                eq("member-1"), eq("org-1"), eq(true), eq("invoice"), eq(0), eq(25), any()))
                .thenReturn(new PageImpl<>(List.of()));

        ResponseEntity<?> response = controller.getMyPublicationsPaged(
                "member-1", "org-1", "MEMBER", true, "invoice", 0, 25);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<UUID>> excludedIds = ArgumentCaptor.forClass(Set.class);
        verify(listQueryService).findByScopePaged(
                eq("member-1"), eq("org-1"), eq(true), eq("invoice"), eq(0), eq(25), excludedIds.capture());
        assertThat(excludedIds.getValue()).containsExactly(deniedId);
    }

    // ---- Acquired-application list filtering (keyed on the SOURCE publication id) ----
    // The published-as-application list (getMyPublications) was already filtered above; the
    // ACQUIRED list was not, so a member restricted from an application still saw the acquired
    // instance on /app/applications. buildAcquiredItems now drops them too.

    private static Map<String, Object> acquiredWf(UUID sourcePublicationId, String title) {
        Map<String, Object> wf = new java.util.HashMap<>();
        wf.put("id", UUID.randomUUID().toString());
        wf.put("sourcePublicationId", sourcePublicationId.toString());
        wf.put("title", title);
        return wf;
    }

    @Test
    @DisplayName("Acquired application list excludes member-restricted applications (by source publication id)")
    void acquiredListExcludesMemberRestricted() {
        UUID allowedPub = UUID.randomUUID();
        UUID deniedPub = UUID.randomUUID();
        when(publicationService.getAcquiredWorkflows("member-1", "org-1"))
                .thenReturn(List.of(acquiredWf(allowedPub, "Allowed App"), acquiredWf(deniedPub, "Denied App")));
        when(orgAccessGuard.getRestrictedResourceIds("org-1", "member-1", "application", "MEMBER"))
                .thenReturn(Set.of(deniedPub.toString()));
        when(listQueryService.findByIdsIncludingInactive(any())).thenReturn(List.of());

        ResponseEntity<?> response = controller.getAcquiredApplications("member-1", "org-1", "MEMBER");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> apps = (List<Map<String, Object>>) body.get("applications");
        assertThat(apps).hasSize(1);
        assertThat(apps.get(0).get("sourcePublicationId")).isEqualTo(allowedPub.toString());
    }

    @Test
    @DisplayName("Paged acquired application list also excludes member-restricted applications")
    void pagedAcquiredListExcludesMemberRestricted() {
        UUID allowedPub = UUID.randomUUID();
        UUID deniedPub = UUID.randomUUID();
        when(publicationService.getAcquiredWorkflows("member-1", "org-1"))
                .thenReturn(List.of(acquiredWf(allowedPub, "Allowed App"), acquiredWf(deniedPub, "Denied App")));
        when(orgAccessGuard.getRestrictedResourceIds("org-1", "member-1", "application", "MEMBER"))
                .thenReturn(Set.of(deniedPub.toString()));
        when(listQueryService.findByIdsIncludingInactive(any())).thenReturn(List.of());

        ResponseEntity<?> response = controller.getAcquiredApplicationsPaged(
                "member-1", "org-1", "MEMBER", null, 0, 25);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        assertThat(items).hasSize(1);
        assertThat(body.get("totalCount")).isEqualTo(1);
        assertThat(items.get(0).get("sourcePublicationId")).isEqualTo(allowedPub.toString());
    }

    @Test
    @DisplayName("Cloud-sourced acquisition (no local publication) synthesizes a minimal publication so the app still renders")
    void acquiredRemoteSynthesizesPublication() {
        UUID cloudPub = UUID.randomUUID();
        when(publicationService.getAcquiredWorkflows("member-1", "org-1"))
                .thenReturn(List.of(acquiredWf(cloudPub, "Cloud App Clone")));
        when(orgAccessGuard.getRestrictedResourceIds("org-1", "member-1", "application", "MEMBER"))
                .thenReturn(Set.of());
        // The cloud publication id is absent from the LOCAL catalog (remote acquisition).
        when(listQueryService.findByIdsIncludingInactive(any())).thenReturn(List.of());

        ResponseEntity<?> response = controller.getAcquiredApplications("member-1", "org-1", "MEMBER");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> apps = (List<Map<String, Object>>) body.get("applications");
        assertThat(apps).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> pub = (Map<String, Object>) apps.get(0).get("publication");
        // Was null pre-fix (→ dropped by the frontend); now a minimal synth from the clone.
        assertThat(pub).isNotNull();
        assertThat(pub.get("id")).isEqualTo(cloudPub.toString());
        assertThat(pub.get("title")).isEqualTo("Cloud App Clone");
        assertThat(pub.get("displayMode")).isEqualTo("APPLICATION");
        assertThat(pub.get("remote")).isEqualTo(true);
    }

    @Test
    @DisplayName("GET /purchases preserves the service-synthesized publication for a cloud acquisition (light-pub lookup must not overwrite it to null)")
    void purchasesPreservesRemoteSynthesizedPublication() {
        UUID remotePub = UUID.randomUUID();
        Map<String, Object> synth = new java.util.HashMap<>();
        synth.put("id", remotePub.toString());
        synth.put("title", "Cloud Purchase");
        synth.put("remote", true);
        Map<String, Object> serviceItem = new java.util.HashMap<>();
        serviceItem.put("publicationId", remotePub.toString());
        serviceItem.put("publication", synth);
        when(publicationService.getPurchases("member-1", "org-1")).thenReturn(List.of(serviceItem));
        // The cloud publication is absent from the local light catalog.
        when(listQueryService.findByIdsIncludingInactive(any())).thenReturn(List.of());

        ResponseEntity<?> response = controller.getPurchases("member-1", "org-1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> purchases = (List<Map<String, Object>>) body.get("purchases");
        assertThat(purchases).hasSize(1);
        // Pre-fix this was overwritten to null (→ dropped by the frontend); now preserved.
        assertThat(purchases.get(0).get("publication")).isEqualTo(synth);
    }

    @Test
    @DisplayName("Personal scope (no org) does not query application restrictions for acquired apps")
    void acquiredPersonalScopeSkipsRestrictionCheck() {
        when(publicationService.getAcquiredWorkflows("owner-1", null))
                .thenReturn(List.of(acquiredWf(UUID.randomUUID(), "App")));
        when(listQueryService.findByIdsIncludingInactive(any())).thenReturn(List.of());

        ResponseEntity<?> response = controller.getAcquiredApplications("owner-1", null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(orgAccessGuard, never()).getRestrictedResourceIds(anyString(), anyString(), anyString(), anyString());
    }

    // ---- (C) write-block enforcement on application/publication mutations ----
    // A member who is READ-restricted (or DENY'd) on an application must not be able to
    // update / delete / unpublish it, mirroring the canWrite enforcement on the canonical
    // resource types (workflow, agent, datasource, interface, project, file).

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final UUID PUBLICATION_ID = UUID.randomUUID();

    @Test
    @DisplayName("Restricted member cannot UPDATE a restricted application (403, service untouched)")
    void restrictedMemberCannotUpdateApplication() {
        when(orgAccessGuard.canWrite("org-1", "member-1", "application", PUBLICATION_ID.toString(), "MEMBER"))
                .thenReturn(false);

        ResponseEntity<?> response = controller.updatePublication(
                "member-1", "org-1", "MEMBER", PUBLICATION_ID.toString(), null);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        // update resolves the publication straight from the path id, so the service is never touched.
        verifyNoInteractions(publicationService);
    }

    @Test
    @DisplayName("Restricted member cannot DELETE a restricted application (403, delete never called)")
    void restrictedMemberCannotDeleteApplication() {
        WorkflowPublicationEntity pub = mock(WorkflowPublicationEntity.class);
        when(pub.getId()).thenReturn(PUBLICATION_ID);
        when(publicationService.getPublicationByWorkflowId(WORKFLOW_ID)).thenReturn(Optional.of(pub));
        when(orgAccessGuard.canWrite("org-1", "member-1", "application", PUBLICATION_ID.toString(), "MEMBER"))
                .thenReturn(false);

        ResponseEntity<?> response = controller.deletePublication(
                "member-1", "org-1", "MEMBER", WORKFLOW_ID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(publicationService, never()).deletePublication(any(), any(), any());
    }

    @Test
    @DisplayName("Restricted member cannot UNPUBLISH a restricted application (403, unpublish never called)")
    void restrictedMemberCannotUnpublishApplication() {
        WorkflowPublicationEntity pub = mock(WorkflowPublicationEntity.class);
        when(pub.getId()).thenReturn(PUBLICATION_ID);
        when(publicationService.getPublicationByWorkflowId(WORKFLOW_ID)).thenReturn(Optional.of(pub));
        when(orgAccessGuard.canWrite("org-1", "member-1", "application", PUBLICATION_ID.toString(), "MEMBER"))
                .thenReturn(false);

        ResponseEntity<?> response = controller.unpublishWorkflow(
                "member-1", "org-1", "MEMBER", WORKFLOW_ID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(publicationService, never()).unpublishWorkflow(any(), any(), any());
    }

    @Test
    @DisplayName("Unrestricted member CAN delete an application (canWrite passes, service called)")
    void unrestrictedMemberCanDeleteApplication() {
        WorkflowPublicationEntity pub = mock(WorkflowPublicationEntity.class);
        when(pub.getId()).thenReturn(PUBLICATION_ID);
        when(publicationService.getPublicationByWorkflowId(WORKFLOW_ID)).thenReturn(Optional.of(pub));
        when(orgAccessGuard.canWrite("org-1", "member-1", "application", PUBLICATION_ID.toString(), "MEMBER"))
                .thenReturn(true);

        ResponseEntity<?> response = controller.deletePublication(
                "member-1", "org-1", "MEMBER", WORKFLOW_ID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(publicationService).deletePublication(WORKFLOW_ID, "member-1", "org-1");
    }

    @Test
    @DisplayName("VIEWER is blocked before the restriction check (403, canWrite never consulted)")
    void viewerBlockedBeforeRestrictionCheck() {
        ResponseEntity<?> response = controller.deletePublication(
                "viewer-1", "org-1", "VIEWER", WORKFLOW_ID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(orgAccessGuard);
        verify(publicationService, never()).deletePublication(any(), any(), any());
    }

    @Test
    @DisplayName("Personal scope (no org) skips the member-restriction check entirely")
    void personalScopeSkipsRestrictionCheck() {
        ResponseEntity<?> response = controller.deletePublication(
                "owner-1", null, null, WORKFLOW_ID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verifyNoInteractions(orgAccessGuard);
        // Personal scope short-circuits before resolving the publication - no wasted lookup.
        verify(publicationService, never()).getPublicationByWorkflowId(any());
        verify(publicationService).deletePublication(WORKFLOW_ID, "owner-1", null);
    }

    @Test
    @DisplayName("Restricted member cannot RE-PUBLISH (overwrite) a restricted application (403, publish never called)")
    void restrictedMemberCannotRepublishApplication() {
        WorkflowPublicationEntity pub = mock(WorkflowPublicationEntity.class);
        when(pub.getId()).thenReturn(PUBLICATION_ID);
        when(publicationService.getPublicationByWorkflowId(WORKFLOW_ID)).thenReturn(Optional.of(pub));
        when(orgAccessGuard.canWrite("org-1", "member-1", "application", PUBLICATION_ID.toString(), "MEMBER"))
                .thenReturn(false);

        WorkflowPublicationController.PublishWorkflowRequest req = new WorkflowPublicationController.PublishWorkflowRequest();
        req.workflowId = WORKFLOW_ID.toString();

        ResponseEntity<?> response = controller.publishWorkflow("member-1", "org-1", "MEMBER", req);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        // The only service touch is resolving the publication id; the re-publish itself never runs.
        verify(publicationService).getPublicationByWorkflowId(WORKFLOW_ID);
        verifyNoMoreInteractions(publicationService);
    }

    @Test
    @DisplayName("No existing publication for the workflow → guard is a no-op (write proceeds)")
    void missingPublicationSkipsRestrictionCheck() {
        when(publicationService.getPublicationByWorkflowId(WORKFLOW_ID)).thenReturn(Optional.empty());

        ResponseEntity<?> response = controller.deletePublication(
                "member-1", "org-1", "MEMBER", WORKFLOW_ID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        // No publication row → null id → canWrite is never consulted and the write proceeds
        // (covers the first-publish / already-removed no-op branch of the by-workflow guard).
        verifyNoInteractions(orgAccessGuard);
        verify(publicationService).deletePublication(WORKFLOW_ID, "member-1", "org-1");
    }

    @Test
    @DisplayName("ADMIN bypasses the restriction (canWrite passes) and can delete the application")
    void adminBypassesRestrictionOnDelete() {
        WorkflowPublicationEntity pub = mock(WorkflowPublicationEntity.class);
        when(pub.getId()).thenReturn(PUBLICATION_ID);
        when(publicationService.getPublicationByWorkflowId(WORKFLOW_ID)).thenReturn(Optional.of(pub));
        when(orgAccessGuard.canWrite("org-1", "admin-1", "application", PUBLICATION_ID.toString(), "ADMIN"))
                .thenReturn(true);

        ResponseEntity<?> response = controller.deletePublication(
                "admin-1", "org-1", "ADMIN", WORKFLOW_ID.toString());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(publicationService).deletePublication(WORKFLOW_ID, "admin-1", "org-1");
    }

    // ---- Standalone resource (TABLE/INTERFACE/SKILL) + agent publication gating ----
    // Same bug class as applications, previously left ungated on the public controller.
    // Resource restrictions key on the UNDERLYING resource: TABLE -> "datasource",
    // INTERFACE -> "interface", SKILL -> "skill", agent pubs -> "agent".

    @Test
    @DisplayName("VIEWER cannot unpublish a resource (403 before any restriction check)")
    void viewerCannotUnpublishResource() {
        ResponseEntity<?> response = controller.unpublishResource("viewer-1", "org-1", "VIEWER", "TABLE", "ds-1");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(orgAccessGuard);
        verify(resourcePublicationService, never()).unpublishResource(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Write-restricted member cannot UNPUBLISH a TABLE resource (403, keyed on the datasource)")
    void restrictedMemberCannotUnpublishResource() {
        when(orgAccessGuard.canWrite("org-1", "member-1", "datasource", "ds-1", "MEMBER")).thenReturn(false);

        ResponseEntity<?> response = controller.unpublishResource("member-1", "org-1", "MEMBER", "TABLE", "ds-1");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(resourcePublicationService, never()).unpublishResource(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Unrestricted member CAN unpublish a resource (canWrite passes, service called)")
    void unrestrictedMemberCanUnpublishResource() {
        when(orgAccessGuard.canWrite("org-1", "member-1", "datasource", "ds-1", "MEMBER")).thenReturn(true);

        ResponseEntity<?> response = controller.unpublishResource("member-1", "org-1", "MEMBER", "TABLE", "ds-1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(resourcePublicationService).unpublishResource(
                WorkflowPublicationEntity.PublicationType.TABLE, "ds-1", "member-1", "org-1");
    }

    @Test
    @DisplayName("Write-restricted member cannot PUBLISH a TABLE resource (403, keyed on the datasource)")
    void restrictedMemberCannotPublishResource() {
        when(orgAccessGuard.canWrite("org-1", "member-1", "datasource", "ds-1", "MEMBER")).thenReturn(false);

        ResponseEntity<?> response = controller.publishResource(
                "member-1", "org-1", "MEMBER", Map.of("type", "TABLE", "resourceId", "ds-1"));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(resourcePublicationService, never()).publishResource(any(), any(), any());
    }

    @Test
    @DisplayName("Write-restricted member cannot UNPUBLISH an agent publication (403, keyed on the agent)")
    void restrictedMemberCannotUnpublishAgent() {
        String agentConfigId = UUID.randomUUID().toString();
        when(orgAccessGuard.canWrite("org-1", "member-1", "agent", agentConfigId, "MEMBER")).thenReturn(false);

        ResponseEntity<?> response = controller.unpublishAgent("member-1", "org-1", "MEMBER", agentConfigId);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(agentPublicationService, never()).unpublishAgent(any(), any(), any());
    }

    @Test
    @DisplayName("VIEWER cannot acquire a resource publication (403, clone-into-workspace is a write)")
    void viewerCannotAcquireResource() {
        ResponseEntity<?> response =
                controller.acquireResource("viewer-1", "org-1", "VIEWER", UUID.randomUUID().toString());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(resourcePublicationService, never()).acquireResource(any(), any(), any());
    }

    @Test
    @DisplayName("Personal-scope resource unpublish skips the member-restriction check")
    void personalScopeResourceUnpublishSkipsCheck() {
        ResponseEntity<?> response = controller.unpublishResource("member-1", null, "MEMBER", "TABLE", "ds-1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verifyNoInteractions(orgAccessGuard);
        verify(resourcePublicationService).unpublishResource(
                WorkflowPublicationEntity.PublicationType.TABLE, "ds-1", "member-1", null);
    }

    @Test
    @DisplayName("Write-restricted member cannot UNPUBLISH an INTERFACE resource (403, keyed on \"interface\")")
    void restrictedMemberCannotUnpublishInterfaceResource() {
        when(orgAccessGuard.canWrite("org-1", "member-1", "interface", "if-1", "MEMBER")).thenReturn(false);

        ResponseEntity<?> response = controller.unpublishResource("member-1", "org-1", "MEMBER", "INTERFACE", "if-1");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(resourcePublicationService, never()).unpublishResource(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Write-restricted member cannot UNPUBLISH a SKILL resource (403, keyed on \"skill\")")
    void restrictedMemberCannotUnpublishSkillResource() {
        when(orgAccessGuard.canWrite("org-1", "member-1", "skill", "sk-1", "MEMBER")).thenReturn(false);

        ResponseEntity<?> response = controller.unpublishResource("member-1", "org-1", "MEMBER", "SKILL", "sk-1");

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(resourcePublicationService, never()).unpublishResource(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Write-restricted member cannot PUBLISH an agent (403, keyed on \"agent\")")
    void restrictedMemberCannotPublishAgent() {
        String agentConfigId = UUID.randomUUID().toString();
        when(orgAccessGuard.canWrite("org-1", "member-1", "agent", agentConfigId, "MEMBER")).thenReturn(false);

        ResponseEntity<?> response = controller.publishAgent(
                "member-1", "org-1", "MEMBER", Map.of("agentConfigId", agentConfigId));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(agentPublicationService, never()).publishAgent(any(), any(), any());
    }

    @Test
    @DisplayName("VIEWER cannot acquire an agent publication (403)")
    void viewerCannotAcquireAgent() {
        ResponseEntity<?> response =
                controller.acquireAgent("viewer-1", "org-1", "VIEWER", UUID.randomUUID().toString());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(agentPublicationService, never()).acquireAgentPublication(any(), any(), any());
    }

    @Test
    @DisplayName("VIEWER cannot acquire a workflow/application publication (403, same gate as resource/agent acquire)")
    void viewerCannotAcquireWorkflowPublication() {
        ResponseEntity<?> response =
                controller.acquirePublication("viewer-1", "org-1", "VIEWER", UUID.randomUUID().toString());

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(publicationService, never()).acquirePublication(any(), any(), any());
    }
}
