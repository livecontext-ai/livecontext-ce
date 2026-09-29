package com.apimarketplace.orchestrator.services;

import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.interfaces.InterfacePlanExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SharedApplicationScopeService - what a share link's application owns")
class SharedApplicationScopeServiceTest {

    private static final String OWNER = "owner-1";
    private static final String ORG = "org-1";
    private static final UUID PUB = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID IFACE = UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private StorageRepository storageRepository;

    private SharedApplicationScopeService service;

    @BeforeEach
    void setUp() {
        service = new SharedApplicationScopeService(workflowRepository, workflowRunRepository,
                new InterfacePlanExtractor(), storageRepository);
    }

    private static WorkflowEntity workflow(UUID sourcePublicationId, UUID... interfaceIds) {
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(UUID.randomUUID());
        wf.setTenantId(OWNER);
        wf.setOrganizationId(ORG);
        wf.setSourcePublicationId(sourcePublicationId);
        wf.setPlan(Map.of("interfaces", java.util.Arrays.stream(interfaceIds)
                .map(id -> Map.of("id", id.toString()))
                .toList()));
        return wf;
    }

    // ===== interfaces =====

    @Test
    @DisplayName("interface referenced by a clone of the publication belongs to the application")
    void interfaceInApplicationPlan_allowed() {
        when(workflowRepository.findAllByOrganizationIdAndSourcePublicationId(ORG, PUB))
                .thenReturn(List.of(workflow(PUB, UUID.randomUUID()), workflow(PUB, IFACE)));

        assertThat(service.interfaceBelongsToApplication(PUB, ORG, IFACE)).isTrue();
    }

    @Test
    @DisplayName("another owner interface, referenced by no clone of the publication, is refused")
    void interfaceOutsideApplication_refused() {
        when(workflowRepository.findAllByOrganizationIdAndSourcePublicationId(ORG, PUB))
                .thenReturn(List.of(workflow(PUB, UUID.randomUUID())));

        assertThat(service.interfaceBelongsToApplication(PUB, ORG, IFACE)).isFalse();
    }

    @Test
    @DisplayName("no workspace id → refused without a lookup")
    void interfaceWithoutOrg_refused() {
        assertThat(service.interfaceBelongsToApplication(PUB, " ", IFACE)).isFalse();
        assertThat(service.interfaceBelongsToApplication(null, ORG, IFACE)).isFalse();
    }

    // ===== files =====

    private static WorkflowRunEntity run(String publicationId, String tenant, String org) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setPublicationId(publicationId);
        run.setTenantId(tenant);
        run.setOrganizationId(org);
        return run;
    }

    @Test
    @DisplayName("file of a run started for the publication belongs to the application")
    void fileOfApplicationRun_allowed() {
        when(workflowRunRepository.findByRunIdPublic("run_app"))
                .thenReturn(Optional.of(run(PUB.toString(), OWNER, ORG)));

        assertThat(service.fileBelongsToApplication(PUB, OWNER, ORG, "run_app", null, null)).isTrue();
    }

    @Test
    @DisplayName("file of another owner run (other or no publication) is refused")
    void fileOfOtherRun_refused() {
        when(workflowRunRepository.findByRunIdPublic("run_other"))
                .thenReturn(Optional.of(run(null, OWNER, ORG)));
        when(workflowRunRepository.findByRunIdPublic("run_other_pub"))
                .thenReturn(Optional.of(run(UUID.randomUUID().toString(), OWNER, ORG)));

        assertThat(service.fileBelongsToApplication(PUB, OWNER, ORG, "run_other", null, null)).isFalse();
        assertThat(service.fileBelongsToApplication(PUB, OWNER, ORG, "run_other_pub", null, null)).isFalse();
    }

    @Test
    @DisplayName("file of an application run in ANOTHER workspace is refused")
    void fileOfRunOutsideScope_refused() {
        when(workflowRunRepository.findByRunIdPublic("run_app"))
                .thenReturn(Optional.of(run(PUB.toString(), "someone-else", "org-2")));

        assertThat(service.fileBelongsToApplication(PUB, OWNER, ORG, "run_app", null, null)).isFalse();
    }

    @Test
    @DisplayName("file tagged with a clone workflow of the publication belongs to the application")
    void fileOfApplicationWorkflow_allowed() {
        WorkflowEntity wf = workflow(PUB);
        when(workflowRepository.findById(wf.getId())).thenReturn(Optional.of(wf));

        assertThat(service.fileBelongsToApplication(PUB, OWNER, ORG, null, wf.getId().toString(), null)).isTrue();
    }

    @Test
    @DisplayName("file tagged with another owner workflow is refused; untagged file is refused")
    void fileOfOtherWorkflowOrUntagged_refused() {
        WorkflowEntity wf = workflow(null);
        when(workflowRepository.findById(wf.getId())).thenReturn(Optional.of(wf));

        assertThat(service.fileBelongsToApplication(PUB, OWNER, ORG, null, wf.getId().toString(), null)).isFalse();
        assertThat(service.fileBelongsToApplication(PUB, OWNER, ORG, null, "iface-not-a-uuid", null)).isFalse();
        assertThat(service.fileBelongsToApplication(PUB, OWNER, ORG, null, null, null)).isFalse();
    }

    // ===== untagged files reached through the application run outputs =====

    private static final UUID CATALOG_FILE = UUID.fromString("55555555-5555-5555-5555-555555555555");

    @Test
    @DisplayName("untagged catalog-tool file referenced by an output of an application run is allowed")
    void untaggedFileReferencedByApplicationRun_allowed() {
        // A catalog binary response is uploaded with NO run/workflow tag; the step output that
        // carries its FileRef is written under the application run.
        when(workflowRunRepository.findRunIdsPublicByPublicationIdAndOrganizationId(PUB.toString(), ORG))
                .thenReturn(List.of("run_app_1", "run_app_2"));
        when(storageRepository.existsRunRowReferencing(List.of("run_app_1", "run_app_2"), CATALOG_FILE.toString()))
                .thenReturn(true);

        assertThat(service.fileBelongsToApplication(PUB, OWNER, ORG, null, null, CATALOG_FILE)).isTrue();
    }

    @Test
    @DisplayName("an ALLOW verdict is cached per (publication, workspace, file): repeated image loads scan once")
    void outputScanVerdictIsCached() {
        when(workflowRunRepository.findRunIdsPublicByPublicationIdAndOrganizationId(PUB.toString(), ORG))
                .thenReturn(List.of("run_app_1"));
        when(storageRepository.existsRunRowReferencing(List.of("run_app_1"), CATALOG_FILE.toString()))
                .thenReturn(true);

        for (int i = 0; i < 5; i++) {
            assertThat(service.fileBelongsToApplication(PUB, OWNER, ORG, null, null, CATALOG_FILE)).isTrue();
        }

        org.mockito.Mockito.verify(storageRepository, org.mockito.Mockito.times(1))
                .existsRunRowReferencing(List.of("run_app_1"), CATALOG_FILE.toString());
        // A different workspace is a different key: never answered from the cache above.
        when(workflowRunRepository.findRunIdsPublicByPublicationIdAndOrganizationId(PUB.toString(), "org-2"))
                .thenReturn(List.of());
        assertThat(service.fileBelongsToApplication(PUB, OWNER, "org-2", null, null, CATALOG_FILE)).isFalse();
    }

    @Test
    @DisplayName("a refusal is NOT cached: once the step output referencing the file is saved, the next load is allowed")
    void refusalIsRecheckedOnNextLoad() {
        // The viewer can hold the file id before the producing step's output row is saved.
        when(workflowRunRepository.findRunIdsPublicByPublicationIdAndOrganizationId(PUB.toString(), ORG))
                .thenReturn(List.of("run_app_1"));
        when(storageRepository.existsRunRowReferencing(List.of("run_app_1"), CATALOG_FILE.toString()))
                .thenReturn(false, false, true);

        assertThat(service.fileBelongsToApplication(PUB, OWNER, ORG, null, null, CATALOG_FILE)).isFalse();
        assertThat(service.fileBelongsToApplication(PUB, OWNER, ORG, null, null, CATALOG_FILE)).isFalse();
        assertThat(service.fileBelongsToApplication(PUB, OWNER, ORG, null, null, CATALOG_FILE)).isTrue();
        assertThat(service.fileBelongsToApplication(PUB, OWNER, ORG, null, null, CATALOG_FILE)).isTrue();

        // Three scans (each refusal re-checked), then the allow is served from the cache.
        org.mockito.Mockito.verify(storageRepository, org.mockito.Mockito.times(3))
                .existsRunRowReferencing(List.of("run_app_1"), CATALOG_FILE.toString());
    }

    @Test
    @DisplayName("foreign untagged file id, referenced by no application run output, is refused")
    void foreignUntaggedFile_refused() {
        UUID foreign = UUID.randomUUID();
        when(workflowRunRepository.findRunIdsPublicByPublicationIdAndOrganizationId(PUB.toString(), ORG))
                .thenReturn(List.of("run_app_1"));
        when(storageRepository.existsRunRowReferencing(List.of("run_app_1"), foreign.toString()))
                .thenReturn(false);

        assertThat(service.fileBelongsToApplication(PUB, OWNER, ORG, null, null, foreign)).isFalse();
    }

    @Test
    @DisplayName("publication with no run in the workspace: untagged file refused without scanning storage")
    void noApplicationRun_refusedWithoutScan() {
        when(workflowRunRepository.findRunIdsPublicByPublicationIdAndOrganizationId(PUB.toString(), ORG))
                .thenReturn(List.of());

        assertThat(service.fileBelongsToApplication(PUB, OWNER, ORG, null, null, CATALOG_FILE)).isFalse();
        org.mockito.Mockito.verifyNoInteractions(storageRepository);
    }

    @Test
    @DisplayName("no workspace id: the output lookup is not attempted")
    void untaggedFileWithoutOrg_refused() {
        assertThat(service.fileBelongsToApplication(PUB, OWNER, " ", null, null, CATALOG_FILE)).isFalse();
        org.mockito.Mockito.verifyNoInteractions(storageRepository);
    }
}
