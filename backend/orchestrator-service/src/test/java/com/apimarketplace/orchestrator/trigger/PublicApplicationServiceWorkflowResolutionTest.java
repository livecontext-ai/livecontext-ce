package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.InterfaceRenderService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.interfaces.InterfaceActionService;
import com.apimarketplace.publication.client.PublicationClient;
import com.apimarketplace.storage.client.StorageClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which workflow the public application API ({@code /app/public/{token}}) serves for a share link.
 *
 * <p>The API reads that workflow with no scope check of its own, so the id must come from the link's
 * publication. When the publication has no workflow id (a TABLE / INTERFACE / SKILL / AGENT
 * publication) or its lookup fails, the link's {@code resourceId} used to be taken as the workflow
 * id unchecked: a link filed before publication-service checked resource ownership could name its
 * creator's own publication plus ANY workflow id, and read that workflow's name, description and
 * interface list. The fallback now accepts only a clone of the publication the link names, held by
 * the link owner's own workspace.
 */
@DisplayName("PublicApplicationService - the workflow a share link resolves to")
class PublicApplicationServiceWorkflowResolutionTest {

    private static final String SL_TOKEN = "sl_0123456789abcdef0123456789abcdef";
    private static final UUID PUBLICATION = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private PublicationClient publicationClient;
    private WorkflowRepository workflowRepository;
    private WorkflowRunRepository runRepository;
    private PublicApplicationService service;

    @BeforeEach
    void setUp() {
        publicationClient = mock(PublicationClient.class);
        workflowRepository = mock(WorkflowRepository.class);
        runRepository = mock(WorkflowRunRepository.class);
        service = new PublicApplicationService(publicationClient, workflowRepository, runRepository,
                mock(InterfaceRenderService.class), mock(InterfaceActionService.class),
                mock(UnifiedSignalService.class), mock(ReusableTriggerService.class),
                mock(StorageClient.class), mock(WorkflowEpochService.class), mock(InterfaceClient.class));
    }

    private static final String LINK_OWNER = "202";
    private static final String LINK_ORG = "org-202";

    /** The /by-token answer for an active APPLICATION link on PUBLICATION, filed by LINK_OWNER in LINK_ORG. */
    private void linkWithResourceId(UUID resourceId) {
        linkWithResourceId(resourceId, LINK_ORG);
    }

    private void linkWithResourceId(UUID resourceId, String organizationId) {
        Map<String, Object> link = new HashMap<>();
        link.put("isActive", true);
        link.put("resourceType", "APPLICATION");
        link.put("resourceToken", PUBLICATION.toString());
        link.put("resourceId", resourceId.toString());
        link.put("tenantId", LINK_OWNER);
        link.put("organizationId", organizationId);
        link.put("metadata", Map.of());
        when(publicationClient.resolveSharedLinkByToken(SL_TOKEN)).thenReturn(link);
    }

    /** A workflow held by the link owner's workspace. */
    private static WorkflowEntity workflow(UUID id, UUID sourcePublicationId) {
        return workflow(id, sourcePublicationId, LINK_OWNER, LINK_ORG);
    }

    private static WorkflowEntity workflow(UUID id, UUID sourcePublicationId, String tenantId, String organizationId) {
        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(id);
        workflow.setName("Someone's workflow");
        workflow.setSourcePublicationId(sourcePublicationId);
        workflow.setTenantId(tenantId);
        workflow.setOrganizationId(organizationId);
        return workflow;
    }

    private void assertRefused() {
        assertThatThrownBy(() -> service.getStatus(SL_TOKEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Application resource not found");
        verify(runRepository, never()).findFirstByWorkflowIdAndSourceAndPublicationIdOrderByStartedAtDesc(
                any(), anyString(), anyString());
    }

    @Test
    @DisplayName("ANOTHER acquirer's clone of the named publication is refused: it is someone else's workflow and run")
    void anotherWorkspacesCloneOfNamedPublicationIsRefused() {
        UUID acquirersClone = UUID.randomUUID();
        linkWithResourceId(acquirersClone);
        when(publicationClient.getPublicationById(PUBLICATION)).thenReturn(null); // lookup failed
        when(workflowRepository.findById(acquirersClone))
                .thenReturn(Optional.of(workflow(acquirersClone, PUBLICATION, "303", "org-303")));

        assertRefused();
    }

    @Test
    @DisplayName("a link with no workspace (filed before workspaces) resolves only a clone its own creator holds")
    void legacyLinkWithoutWorkspaceIsBoundToItsCreator() {
        UUID othersClone = UUID.randomUUID();
        linkWithResourceId(othersClone, "");
        when(publicationClient.getPublicationById(PUBLICATION)).thenReturn(null);
        when(workflowRepository.findById(othersClone))
                .thenReturn(Optional.of(workflow(othersClone, PUBLICATION, "303", null)));

        assertRefused();
    }

    @Test
    @DisplayName("a link with no workspace still resolves its creator's own clone")
    void legacyLinkResolvesItsCreatorsClone() {
        UUID ownClone = UUID.randomUUID();
        linkWithResourceId(ownClone, "");
        when(publicationClient.getPublicationById(PUBLICATION)).thenReturn(null);
        when(workflowRepository.findById(ownClone))
                .thenReturn(Optional.of(workflow(ownClone, PUBLICATION, LINK_OWNER, null)));

        assertThat(service.getStatus(SL_TOKEN)).containsEntry("runStatus", "NO_RUN");
    }

    @Test
    @DisplayName("a resourceId naming no workflow at all is refused")
    void unknownWorkflowIdIsRefused() {
        UUID missing = UUID.randomUUID();
        linkWithResourceId(missing);
        when(publicationClient.getPublicationById(PUBLICATION)).thenReturn(null);
        when(workflowRepository.findById(missing)).thenReturn(Optional.empty());

        assertRefused();
    }

    @Test
    @DisplayName("a resourceId that is not a clone of the named publication is refused, and that workflow is never served")
    void foreignWorkflowIdIsRefused() {
        UUID foreignWorkflow = UUID.randomUUID();
        linkWithResourceId(foreignWorkflow);
        // The link's own publication has no workflow (e.g. a TABLE publication), so the fallback is reached.
        when(publicationClient.getPublicationById(PUBLICATION)).thenReturn(Map.of("id", PUBLICATION.toString()));
        // A source workflow of another user: no source publication at all.
        when(workflowRepository.findById(foreignWorkflow)).thenReturn(Optional.of(workflow(foreignWorkflow, null)));

        assertThatThrownBy(() -> service.getStatus(SL_TOKEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Application resource not found");
        verify(runRepository, never()).findFirstByWorkflowIdAndSourceAndPublicationIdOrderByStartedAtDesc(
                any(), anyString(), anyString());
    }

    @Test
    @DisplayName("a clone of ANOTHER publication is refused the same way")
    void cloneOfAnotherPublicationIsRefused() {
        UUID otherClone = UUID.randomUUID();
        linkWithResourceId(otherClone);
        when(publicationClient.getPublicationById(PUBLICATION)).thenReturn(null); // lookup failed
        when(workflowRepository.findById(otherClone)).thenReturn(Optional.of(workflow(otherClone, UUID.randomUUID())));

        assertThatThrownBy(() -> service.getStatus(SL_TOKEN))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Application resource not found");
    }

    @Test
    @DisplayName("a clone of the named publication is still served through the fallback")
    void cloneOfNamedPublicationIsServed() {
        UUID clone = UUID.randomUUID();
        linkWithResourceId(clone);
        when(publicationClient.getPublicationById(PUBLICATION)).thenReturn(null); // lookup failed
        when(workflowRepository.findById(clone)).thenReturn(Optional.of(workflow(clone, PUBLICATION)));

        Map<String, Object> status = service.getStatus(SL_TOKEN);

        assertThat(status).containsEntry("runStatus", "NO_RUN");
        verify(runRepository).findFirstByWorkflowIdAndSourceAndPublicationIdOrderByStartedAtDesc(
                eq(clone), eq("application"), eq(PUBLICATION.toString()));
    }

    @Test
    @DisplayName("the publication's own workflow still wins over the resourceId, unchanged")
    void publicationWorkflowIsUsedFirst() {
        UUID sourceWorkflow = UUID.randomUUID();
        linkWithResourceId(UUID.randomUUID());
        when(publicationClient.getPublicationById(PUBLICATION))
                .thenReturn(Map.of("id", PUBLICATION.toString(), "workflowId", sourceWorkflow.toString()));
        when(workflowRepository.findById(sourceWorkflow)).thenReturn(Optional.of(workflow(sourceWorkflow, null)));

        assertThat(service.getStatus(SL_TOKEN)).containsEntry("runStatus", "NO_RUN");
        verify(runRepository).findFirstByWorkflowIdAndSourceAndPublicationIdOrderByStartedAtDesc(
                eq(sourceWorkflow), eq("application"), eq(PUBLICATION.toString()));
    }
}
