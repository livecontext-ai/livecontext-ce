package com.apimarketplace.orchestrator.services;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.access.OrgAccessDeniedException;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.domain.*;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.ProjectDataSourcesPreviewDto;
import com.apimarketplace.orchestrator.repository.*;
import com.apimarketplace.publication.client.PublicationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for ProjectService - CRUD and resource assignment.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectService")
class ProjectServiceTest {

    @Mock private ProjectRepository projectRepository;
    @Mock private OrgAccessGuard orgAccessService;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private InterfaceClient interfaceClient;
    @Mock private AgentClient agentClient;
    @Mock private DataSourceClient dataSourceClient;
    @Mock private PublicationClient publicationClient;
    @Mock private StorageService storageService;

    private ProjectService service;

    private static final String OWNER_ID = "owner-user";
    private static final String OTHER_ID = "other-user";
    private static final UUID PROJECT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ProjectService(
            projectRepository, orgAccessService,
            workflowRepository, interfaceClient, agentClient,
            dataSourceClient, publicationClient, storageService
        );
        lenient().when(orgAccessService.canWrite(any(), any(), any(), any(), any())).thenReturn(true);
    }

    // ===================== Factory helpers =====================

    private ProjectEntity createProject(UUID id, String name, String ownerId) {
        ProjectEntity p = new ProjectEntity(name, name.toLowerCase().replace(" ", "_"), ownerId);
        p.setId(id);
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return p;
    }

    private WorkflowEntity createWorkflow(UUID id, String tenantId, UUID projectId) {
        WorkflowEntity w = new WorkflowEntity();
        w.setId(id);
        w.setTenantId(tenantId);
        w.setProjectId(projectId);
        return w;
    }

    private WorkflowEntity createWorkflow(UUID id, String tenantId, UUID projectId, String organizationId) {
        WorkflowEntity w = createWorkflow(id, tenantId, projectId);
        w.setOrganizationId(organizationId);
        return w;
    }

    // ===================== CRUD Tests =====================

    @Nested
    @DisplayName("createProject")
    class CreateProjectTests {

        @Test
        @DisplayName("Should create project without member creation")
        void shouldCreateProject() {
            when(projectRepository.existsByOwnerIdAndSlug(eq(OWNER_ID), any())).thenReturn(false);
            when(projectRepository.save(any(ProjectEntity.class))).thenAnswer(inv -> {
                ProjectEntity p = inv.getArgument(0);
                p.setId(PROJECT_ID);
                return p;
            });

            ProjectEntity result = service.createProject(OWNER_ID, "My Project", "Description", "#ff0000", "folder", null);

            assertThat(result.getName()).isEqualTo("My Project");
            assertThat(result.getOwnerId()).isEqualTo(OWNER_ID);
            assertThat(result.getColor()).isEqualTo("#ff0000");
            assertThat(result.getIcon()).isEqualTo("folder");
            verify(projectRepository).save(any(ProjectEntity.class));
        }

        @Test
        @DisplayName("Should set organizationId when provided")
        void shouldSetOrganizationId() {
            when(projectRepository.existsByOwnerIdAndSlug(eq(OWNER_ID), any())).thenReturn(false);
            when(projectRepository.save(any(ProjectEntity.class))).thenAnswer(inv -> {
                ProjectEntity p = inv.getArgument(0);
                p.setId(PROJECT_ID);
                return p;
            });

            ProjectEntity result = service.createProject(OWNER_ID, "Test", null, null, null, "org-123");

            assertThat(result.getOrganizationId()).isEqualTo("org-123");
        }

        @Test
        @DisplayName("Should generate unique slug when duplicate exists")
        void shouldGenerateUniqueSlug() {
            when(projectRepository.existsByOwnerIdAndSlug(OWNER_ID, "my_project")).thenReturn(true);
            when(projectRepository.existsByOwnerIdAndSlug(OWNER_ID, "my_project_1")).thenReturn(true);
            when(projectRepository.existsByOwnerIdAndSlug(OWNER_ID, "my_project_2")).thenReturn(false);
            when(projectRepository.save(any())).thenAnswer(inv -> {
                ProjectEntity p = inv.getArgument(0);
                p.setId(PROJECT_ID);
                return p;
            });

            ProjectEntity result = service.createProject(OWNER_ID, "My Project", null, null, null, null);

            assertThat(result.getSlug()).isEqualTo("my_project_2");
        }
    }

    @Nested
    @DisplayName("getProject")
    class GetProjectTests {

        @Test
        @DisplayName("Should return project when user is owner")
        void shouldReturnWhenOwner() {
            ProjectEntity project = createProject(PROJECT_ID, "Test", OWNER_ID);
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

            Optional<ProjectEntity> result = service.getProject(PROJECT_ID, OWNER_ID);

            assertThat(result).isPresent();
            assertThat(result.get().getName()).isEqualTo("Test");
        }

        @Test
        @DisplayName("Should return empty when user is not owner")
        void shouldReturnEmptyWhenNotOwner() {
            ProjectEntity project = createProject(PROJECT_ID, "Test", OWNER_ID);
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

            Optional<ProjectEntity> result = service.getProject(PROJECT_ID, OTHER_ID);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return project for org member via org-aware method")
        void shouldReturnForOrgMember() {
            ProjectEntity project = createProject(PROJECT_ID, "Test", OWNER_ID);
            project.setOrganizationId("org-1");
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(orgAccessService.canAccess("org-1", OTHER_ID, "project", PROJECT_ID.toString(), "MEMBER"))
                .thenReturn(true);

            Optional<ProjectEntity> result = service.getProject(PROJECT_ID, OTHER_ID, "org-1", "MEMBER");

            assertThat(result).isPresent();
        }

        @Test
        @DisplayName("Should return empty for an org member DENY-restricted from the project (canAccess=false)")
        void shouldReturnEmptyForDeniedOrgMember() {
            ProjectEntity project = createProject(PROJECT_ID, "Test", OWNER_ID);
            project.setOrganizationId("org-1");
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(orgAccessService.canAccess("org-1", OTHER_ID, "project", PROJECT_ID.toString(), "MEMBER"))
                .thenReturn(false);

            Optional<ProjectEntity> result = service.getProject(PROJECT_ID, OTHER_ID, "org-1", "MEMBER");

            // DENY → not-found (no info leak), the read gate's negative branch.
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Should return empty when project not found")
        void shouldReturnEmptyWhenNotFound() {
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

            Optional<ProjectEntity> result = service.getProject(PROJECT_ID, OWNER_ID);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getUserProjects")
    class GetUserProjectsTests {

        @Test
        @DisplayName("Should return owned projects when no org context")
        void shouldReturnOwnedOnly() {
            ProjectEntity p1 = createProject(UUID.randomUUID(), "Proj 1", OWNER_ID);
            when(projectRepository.findByOwnerIdAndIsArchivedFalseOrderByUpdatedAtDesc(OWNER_ID))
                .thenReturn(List.of(p1));

            List<ProjectEntity> result = service.getUserProjects(OWNER_ID);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getName()).isEqualTo("Proj 1");
        }

        @Test
        @DisplayName("In an org workspace, drops the member's DENY-restricted projects via filterAccessible")
        void shouldApplyOrgDenyFilter() {
            ProjectEntity p1 = createProject(UUID.randomUUID(), "Allowed", OWNER_ID);
            ProjectEntity p2 = createProject(UUID.randomUUID(), "Denied", OWNER_ID);
            when(projectRepository.findByOrganizationOrOwner("org-1", OTHER_ID)).thenReturn(List.of(p1, p2));
            when(orgAccessService.filterAccessible(eq(List.of(p1, p2)), eq("org-1"), eq(OTHER_ID),
                    eq("project"), eq("MEMBER"), any())).thenReturn(List.of(p1));

            List<ProjectEntity> result = service.getUserProjects(OTHER_ID, "org-1", "MEMBER");

            assertThat(result).extracting(ProjectEntity::getName).containsExactly("Allowed");
        }
    }

    @Nested
    @DisplayName("updateProject")
    class UpdateProjectTests {

        @Test
        @DisplayName("Should update fields when user is owner")
        void shouldUpdateFields() {
            ProjectEntity project = createProject(PROJECT_ID, "Old Name", OWNER_ID);
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(projectRepository.existsByOwnerIdAndSlug(eq(OWNER_ID), any())).thenReturn(false);
            when(projectRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Map<String, Object> updates = Map.of(
                "name", "New Name",
                "description", "New desc",
                "color", "#00ff00"
            );

            Optional<ProjectEntity> result = service.updateProject(PROJECT_ID, OWNER_ID, updates);

            assertThat(result).isPresent();
            assertThat(result.get().getName()).isEqualTo("New Name");
            assertThat(result.get().getDescription()).isEqualTo("New desc");
            assertThat(result.get().getColor()).isEqualTo("#00ff00");
        }

        @Test
        @DisplayName("Should return empty when user is not owner")
        void shouldReturnEmptyWhenNotOwner() {
            ProjectEntity project = createProject(PROJECT_ID, "Test", OWNER_ID);
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

            Optional<ProjectEntity> result = service.updateProject(PROJECT_ID, OTHER_ID, Map.of("name", "X"));

            assertThat(result).isEmpty();
            verify(projectRepository, never()).save(any());
        }

        @Test
        @DisplayName("Throws OrgAccessDenied (403) when a member can READ the project but is write-restricted")
        void shouldThrowAccessDeniedWhenReadableProjectIsWriteRestricted() {
            ProjectEntity project = createProject(PROJECT_ID, "Test", OWNER_ID);
            project.setOrganizationId("org-acme");
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(orgAccessService.canAccess("org-acme", OTHER_ID, "project", PROJECT_ID.toString(), "MEMBER"))
                    .thenReturn(true);
            when(orgAccessService.canWrite("org-acme", OTHER_ID, "project", PROJECT_ID.toString(), "MEMBER"))
                    .thenReturn(false);

            // The project IS visible to the member (canAccess=true), so a write-restriction
            // must surface as a clean 403 - NOT a misleading Optional.empty → 404.
            assertThatThrownBy(() -> service.updateProject(
                    PROJECT_ID, OTHER_ID, "org-acme", "MEMBER", Map.of("name", "Blocked")))
                    .isInstanceOf(OrgAccessDeniedException.class)
                    .satisfies(ex -> assertThat(((OrgAccessDeniedException) ex).getResourceType()).isEqualTo("project"));
            verify(projectRepository, never()).save(any());
        }

        @Test
        @DisplayName("Returns empty (404, no throw) for a genuinely not-found / out-of-scope project")
        void shouldReturnEmptyForGenuinelyNotFoundProject() {
            // getProject sees nothing (row absent OR out of strict scope) → must stay
            // Optional.empty (→ 404), never an OrgAccessDeniedException (no info leak).
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

            Optional<ProjectEntity> result = service.updateProject(
                    PROJECT_ID, OTHER_ID, "org-acme", "MEMBER", Map.of("name", "Blocked"));

            assertThat(result).isEmpty();
            verify(orgAccessService, never()).canWrite(any(), any(), eq("project"), any(), any());
            verify(projectRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("deleteProject")
    class DeleteProjectTests {

        @Test
        @DisplayName("Should delete when user is owner")
        void shouldDeleteWhenOwner() {
            ProjectEntity project = createProject(PROJECT_ID, "Test", OWNER_ID);
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(workflowRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of());
            when(dataSourceClient.findByProjectId(PROJECT_ID, OWNER_ID)).thenReturn(List.of());

            boolean result = service.deleteProject(PROJECT_ID, OWNER_ID);

            assertThat(result).isTrue();
            verify(projectRepository).delete(project);
        }

        @Test
        @DisplayName("Should unassign all resources before deleting")
        void shouldUnassignResources() {
            ProjectEntity project = createProject(PROJECT_ID, "Test", OWNER_ID);
            WorkflowEntity wf = createWorkflow(UUID.randomUUID(), OWNER_ID, PROJECT_ID);

            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(workflowRepository.findByProjectId(PROJECT_ID)).thenReturn(List.of(wf));
            when(dataSourceClient.findByProjectId(PROJECT_ID, OWNER_ID)).thenReturn(List.of());

            service.deleteProject(PROJECT_ID, OWNER_ID);

            verify(workflowRepository).save(argThat(w -> w.getProjectId() == null));
            verify(agentClient).unassignAllFromProject(PROJECT_ID, OWNER_ID);
            verify(publicationClient).unassignAllFromProject(PROJECT_ID, OWNER_ID);
        }

        @Test
        @DisplayName("Should not delete when user is not owner")
        void shouldNotDeleteForNonOwner() {
            ProjectEntity project = createProject(PROJECT_ID, "Test", OWNER_ID);
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

            boolean result = service.deleteProject(PROJECT_ID, OTHER_ID);

            assertThat(result).isFalse();
            verify(projectRepository, never()).delete(any());
        }
    }

    // ===================== Resource Assignment Tests =====================

    @Nested
    @DisplayName("assignResource")
    class AssignResourceTests {

        @Test
        @DisplayName("Throws OrgAccessDenied (403) when assigning to a readable but write-restricted project")
        void shouldThrowAccessDeniedWhenAssigningToWriteRestrictedProject() {
            UUID agentId = UUID.randomUUID();
            ProjectEntity project = createProject(PROJECT_ID, "Test", OWNER_ID);
            project.setOrganizationId("org-acme");
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(orgAccessService.canAccess("org-acme", OTHER_ID, "project", PROJECT_ID.toString(), "MEMBER"))
                    .thenReturn(true);
            when(orgAccessService.canWrite("org-acme", OTHER_ID, "project", PROJECT_ID.toString(), "MEMBER"))
                    .thenReturn(false);

            // Project visible (canAccess=true) but write-restricted → clean 403, not a 400 from `false`.
            assertThatThrownBy(() -> service.assignResource(
                    PROJECT_ID, "agent", agentId.toString(), OTHER_ID, "org-acme", "MEMBER"))
                    .isInstanceOf(OrgAccessDeniedException.class)
                    .satisfies(ex -> assertThat(((OrgAccessDeniedException) ex).getResourceType()).isEqualTo("project"));
            verify(agentClient, never()).assignToProject(any(), any(), any());
        }

        @Test
        @DisplayName("Returns false (400, no throw) when assigning to a genuinely not-found project")
        void shouldReturnFalseForGenuinelyNotFoundProjectOnAssign() {
            UUID agentId = UUID.randomUUID();
            // getProject sees nothing → stays false (→ 400), never OrgAccessDeniedException.
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.empty());

            boolean result = service.assignResource(
                    PROJECT_ID, "agent", agentId.toString(), OTHER_ID, "org-acme", "MEMBER");

            assertThat(result).isFalse();
            verify(orgAccessService, never()).canWrite(any(), any(), eq("project"), any(), any());
            verify(agentClient, never()).assignToProject(any(), any(), any());
        }

        @Test
        @DisplayName("Does not assign a read-only child resource to a writable project")
        void shouldNotAssignReadOnlyChildResource() {
            UUID agentId = UUID.randomUUID();
            ProjectEntity project = createProject(PROJECT_ID, "Test", OWNER_ID);
            project.setOrganizationId("org-acme");
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(orgAccessService.canAccess("org-acme", OTHER_ID, "project", PROJECT_ID.toString(), "MEMBER"))
                    .thenReturn(true);
            when(orgAccessService.canWrite("org-acme", OTHER_ID, "agent", agentId.toString(), "MEMBER"))
                    .thenReturn(false);

            boolean result = service.assignResource(
                    PROJECT_ID, "agent", agentId.toString(), OTHER_ID, "org-acme", "MEMBER");

            assertThat(result).isFalse();
            verify(agentClient, never()).assignToProject(any(), any(), any());
        }

        @Test
        @DisplayName("Should assign workflow when user is owner")
        void shouldAssignWorkflow() {
            UUID wfId = UUID.randomUUID();
            ProjectEntity project = createProject(PROJECT_ID, "Test", OWNER_ID);
            WorkflowEntity workflow = createWorkflow(wfId, OWNER_ID, null);

            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(workflowRepository.findById(wfId)).thenReturn(Optional.of(workflow));
            when(workflowRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            boolean result = service.assignResource(PROJECT_ID, "workflow", wfId.toString(), OWNER_ID);

            assertThat(result).isTrue();
            verify(workflowRepository).save(argThat(w -> PROJECT_ID.equals(w.getProjectId())));
        }

        @Test
        @DisplayName("Org project rejects same-owner workflow from another workspace")
        void orgProjectRejectsSameOwnerWorkflowFromAnotherWorkspace() {
            UUID wfId = UUID.randomUUID();
            ProjectEntity project = createProject(PROJECT_ID, "Test", OWNER_ID);
            project.setOrganizationId("org-acme");
            WorkflowEntity workflow = createWorkflow(wfId, OWNER_ID, null, "org-personal");

            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(orgAccessService.canAccess("org-acme", OWNER_ID, "project", PROJECT_ID.toString(), "OWNER"))
                .thenReturn(true);
            when(workflowRepository.findById(wfId)).thenReturn(Optional.of(workflow));

            boolean result = service.assignResource(
                    PROJECT_ID, "workflow", wfId.toString(), OWNER_ID, "org-acme", "OWNER");

            assertThat(result).isFalse();
            verify(workflowRepository, never()).save(any());
        }

        @Test
        @DisplayName("Non-owner cannot assign resources")
        void nonOwnerCannotAssign() {
            ProjectEntity project = createProject(PROJECT_ID, "Test", OWNER_ID);
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

            boolean result = service.assignResource(PROJECT_ID, "workflow", UUID.randomUUID().toString(), OTHER_ID);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Should return false for unknown resource type")
        void shouldReturnFalseForUnknownType() {
            ProjectEntity project = createProject(PROJECT_ID, "Test", OWNER_ID);
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));

            boolean result = service.assignResource(PROJECT_ID, "unknown", "123", OWNER_ID);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("Assigns a file to the project through common storage when the member can access it")
        void shouldAssignFileWhenAccessible() {
            UUID fileId = UUID.randomUUID();
            ProjectEntity project = createProject(PROJECT_ID, "Test", OWNER_ID);
            project.setOrganizationId("org-acme");
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(orgAccessService.canAccess("org-acme", OWNER_ID, "project", PROJECT_ID.toString(), "OWNER"))
                    .thenReturn(true);
            when(orgAccessService.canWrite("org-acme", OWNER_ID, "file", fileId.toString(), "OWNER"))
                    .thenReturn(true);
            when(storageService.assignFileToProjectForScope(fileId, "org-acme", PROJECT_ID)).thenReturn(true);

            boolean result = service.assignResource(
                    PROJECT_ID, "file", fileId.toString(), OWNER_ID, "org-acme", "OWNER");

            assertThat(result).isTrue();
            verify(storageService).assignFileToProjectForScope(fileId, "org-acme", PROJECT_ID);
        }

        @Test
        @DisplayName("Does not assign a restricted file to the project")
        void shouldNotAssignRestrictedFile() {
            UUID fileId = UUID.randomUUID();
            ProjectEntity project = createProject(PROJECT_ID, "Test", OWNER_ID);
            project.setOrganizationId("org-acme");
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(orgAccessService.canAccess("org-acme", OWNER_ID, "project", PROJECT_ID.toString(), "MEMBER"))
                    .thenReturn(true);
            when(orgAccessService.canWrite("org-acme", OWNER_ID, "file", fileId.toString(), "MEMBER"))
                    .thenReturn(false);

            boolean result = service.assignResource(
                    PROJECT_ID, "file", fileId.toString(), OWNER_ID, "org-acme", "MEMBER");

            assertThat(result).isFalse();
            verify(storageService, never()).assignFileToProjectForScope(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("removeResource")
    class RemoveResourceTests {

        @Test
        @DisplayName("Removes a file from the project through common storage when the member can access it")
        void shouldRemoveFileWhenAccessible() {
            UUID fileId = UUID.randomUUID();
            ProjectEntity project = createProject(PROJECT_ID, "Test", OWNER_ID);
            project.setOrganizationId("org-acme");
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(orgAccessService.canAccess("org-acme", OWNER_ID, "project", PROJECT_ID.toString(), "OWNER"))
                    .thenReturn(true);
            when(orgAccessService.canWrite("org-acme", OWNER_ID, "file", fileId.toString(), "OWNER"))
                    .thenReturn(true);
            when(storageService.removeFileFromProjectForScope(fileId, "org-acme", PROJECT_ID)).thenReturn(true);

            boolean result = service.removeResource(
                    PROJECT_ID, "file", fileId.toString(), OWNER_ID, "org-acme", "OWNER");

            assertThat(result).isTrue();
            verify(storageService).removeFileFromProjectForScope(fileId, "org-acme", PROJECT_ID);
        }

        @Test
        @DisplayName("Does not remove a restricted file from the project")
        void shouldNotRemoveRestrictedFile() {
            UUID fileId = UUID.randomUUID();
            ProjectEntity project = createProject(PROJECT_ID, "Test", OWNER_ID);
            project.setOrganizationId("org-acme");
            when(projectRepository.findById(PROJECT_ID)).thenReturn(Optional.of(project));
            when(orgAccessService.canAccess("org-acme", OWNER_ID, "project", PROJECT_ID.toString(), "MEMBER"))
                    .thenReturn(true);
            when(orgAccessService.canWrite("org-acme", OWNER_ID, "file", fileId.toString(), "MEMBER"))
                    .thenReturn(false);

            boolean result = service.removeResource(
                    PROJECT_ID, "file", fileId.toString(), OWNER_ID, "org-acme", "MEMBER");

            assertThat(result).isFalse();
            verify(storageService, never()).removeFileFromProjectForScope(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("getResourceCounts")
    class GetResourceCountsTests {

        @Test
        @DisplayName("Should return counts for all resource types")
        void shouldReturnAllCounts() {
            when(workflowRepository.countByProjectId(PROJECT_ID)).thenReturn(5L);
            when(interfaceClient.countByProject(PROJECT_ID, OWNER_ID)).thenReturn(3L);
            when(agentClient.countByProjectId(PROJECT_ID, OWNER_ID)).thenReturn(1L);
            when(dataSourceClient.countByProjectId(PROJECT_ID, OWNER_ID)).thenReturn(2);
            when(publicationClient.countByProjectId(PROJECT_ID, OWNER_ID)).thenReturn(1L);

            Map<String, Long> counts = service.getResourceCounts(PROJECT_ID, OWNER_ID);

            assertThat(counts).hasSize(6);
            assertThat(counts.get("workflows")).isEqualTo(5L);
            assertThat(counts.get("interfaces")).isEqualTo(3L);
            assertThat(counts.get("agents")).isEqualTo(1L);
            assertThat(counts.get("datasources")).isEqualTo(2L);
            assertThat(counts.get("applications")).isEqualTo(1L);
            assertThat(counts.get("files")).isZero();
        }

        @Test
        @DisplayName("Org resource counts use current workspace scope")
        void orgResourceCountsUseCurrentWorkspaceScope() {
            WorkflowEntity first = createWorkflow(UUID.randomUUID(), OWNER_ID, PROJECT_ID, "org-acme");
            WorkflowEntity second = createWorkflow(UUID.randomUUID(), OWNER_ID, PROJECT_ID, "org-acme");
            StorageEntity file = new StorageEntity();
            file.setId(UUID.randomUUID());
            when(workflowRepository.findByProjectIdAndOrganizationId(PROJECT_ID, "org-acme"))
                    .thenReturn(List.of(first, second));
            when(interfaceClient.getByProject(PROJECT_ID, OWNER_ID)).thenReturn(List.of());
            when(agentClient.findByProjectId(PROJECT_ID, OWNER_ID)).thenReturn(List.of());
            when(dataSourceClient.findByProjectIdWithPreview(PROJECT_ID, OWNER_ID))
                    .thenReturn(ProjectDataSourcesPreviewDto.empty());
            when(publicationClient.findByProjectId(PROJECT_ID, OWNER_ID)).thenReturn(List.of());
            when(storageService.findFilesByProjectForScope(PROJECT_ID, "org-acme")).thenReturn(List.of(file));
            doAnswer(inv -> inv.getArgument(0)).when(orgAccessService)
                    .filterAccessible(any(), eq("org-acme"), eq(OWNER_ID), any(), any(), any());

            Map<String, Long> counts = service.getResourceCounts(PROJECT_ID, OWNER_ID, "org-acme");

            assertThat(counts.get("workflows")).isEqualTo(2L);
            assertThat(counts.get("files")).isEqualTo(1L);
            verify(workflowRepository).findByProjectIdAndOrganizationId(PROJECT_ID, "org-acme");
            verify(workflowRepository, never()).countByProjectId(PROJECT_ID);
        }
    }

    @Nested
    @DisplayName("getProjectResources")
    class GetProjectResourcesTests {

        @Test
        @DisplayName("Org project details use current workspace scope")
        void orgProjectDetailsUseCurrentWorkspaceScope() {
            WorkflowEntity workflow = createWorkflow(UUID.randomUUID(), OWNER_ID, PROJECT_ID, "org-acme");
            when(workflowRepository.findByProjectIdAndOrganizationId(PROJECT_ID, "org-acme"))
                    .thenReturn(List.of(workflow));
            when(interfaceClient.getByProject(PROJECT_ID, OWNER_ID)).thenReturn(List.of());
            when(agentClient.findByProjectId(PROJECT_ID, OWNER_ID)).thenReturn(List.of());
            when(dataSourceClient.findByProjectIdWithPreview(PROJECT_ID, OWNER_ID))
                    .thenReturn(ProjectDataSourcesPreviewDto.empty());
            when(publicationClient.findByProjectId(PROJECT_ID, OWNER_ID)).thenReturn(List.of());
            when(storageService.findFilesByProjectForScope(PROJECT_ID, "org-acme")).thenReturn(List.of());
            doAnswer(inv -> inv.getArgument(0)).when(orgAccessService)
                    .filterAccessible(any(), eq("org-acme"), eq(OWNER_ID), any(), any(), any());

            Map<String, Object> resources = service.getProjectResources(PROJECT_ID, OWNER_ID, "org-acme");

            @SuppressWarnings("unchecked")
            List<WorkflowEntity> workflows = (List<WorkflowEntity>) resources.get("workflows");
            assertThat(workflows).containsExactly(workflow);
            verify(workflowRepository).findByProjectIdAndOrganizationId(PROJECT_ID, "org-acme");
            verify(workflowRepository, never()).findByProjectId(PROJECT_ID);
        }

        @Test
        @DisplayName("Project details include files assigned in the current workspace")
        void orgProjectDetailsIncludeFiles() {
            StorageEntity file = new StorageEntity();
            UUID fileId = UUID.randomUUID();
            file.setId(fileId);
            file.setFileName("report.pdf");
            file.setMimeType("application/pdf");
            file.setSizeBytes(1234);
            file.setProjectId(PROJECT_ID);
            file.setCreatedAt(Instant.parse("2026-05-29T10:00:00Z"));
            when(workflowRepository.findByProjectIdAndOrganizationId(PROJECT_ID, "org-acme"))
                    .thenReturn(List.of());
            when(interfaceClient.getByProject(PROJECT_ID, OWNER_ID)).thenReturn(List.of());
            when(agentClient.findByProjectId(PROJECT_ID, OWNER_ID)).thenReturn(List.of());
            when(dataSourceClient.findByProjectIdWithPreview(PROJECT_ID, OWNER_ID))
                    .thenReturn(ProjectDataSourcesPreviewDto.empty());
            when(publicationClient.findByProjectId(PROJECT_ID, OWNER_ID)).thenReturn(List.of());
            when(storageService.findFilesByProjectForScope(PROJECT_ID, "org-acme")).thenReturn(List.of(file));
            doAnswer(inv -> inv.getArgument(0)).when(orgAccessService)
                    .filterAccessible(any(), eq("org-acme"), eq(OWNER_ID), any(), any(), any());

            Map<String, Object> resources = service.getProjectResources(PROJECT_ID, OWNER_ID, "org-acme");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files = (List<Map<String, Object>>) resources.get("files");
            assertThat(files).hasSize(1);
            assertThat(files.get(0))
                    .containsEntry("id", fileId)
                    .containsEntry("name", "report.pdf")
                    .containsEntry("fileName", "report.pdf")
                    .containsEntry("projectId", PROJECT_ID)
                    .containsEntry("mimeType", "application/pdf")
                    .containsEntry("sizeBytes", 1234);
        }

        @Test
        @DisplayName("Project details filter child resources with org restrictions")
        void orgProjectDetailsFilterChildResources() {
            WorkflowEntity allowedWorkflow = createWorkflow(UUID.randomUUID(), OWNER_ID, PROJECT_ID, "org-acme");
            WorkflowEntity deniedWorkflow = createWorkflow(UUID.randomUUID(), OWNER_ID, PROJECT_ID, "org-acme");
            when(workflowRepository.findByProjectIdAndOrganizationId(PROJECT_ID, "org-acme"))
                    .thenReturn(List.of(allowedWorkflow, deniedWorkflow));
            when(interfaceClient.getByProject(PROJECT_ID, OWNER_ID)).thenReturn(List.of());
            when(agentClient.findByProjectId(PROJECT_ID, OWNER_ID)).thenReturn(List.of());
            when(dataSourceClient.findByProjectIdWithPreview(PROJECT_ID, OWNER_ID))
                    .thenReturn(ProjectDataSourcesPreviewDto.empty());
            when(publicationClient.findByProjectId(PROJECT_ID, OWNER_ID)).thenReturn(List.of());
            when(storageService.findFilesByProjectForScope(PROJECT_ID, "org-acme")).thenReturn(List.of());
            doAnswer(inv -> {
                @SuppressWarnings("unchecked")
                List<WorkflowEntity> workflows = (List<WorkflowEntity>) inv.getArgument(0);
                return workflows.stream()
                        .filter(workflow -> !workflow.getId().equals(deniedWorkflow.getId()))
                        .toList();
            }).when(orgAccessService)
                    .filterAccessible(any(), eq("org-acme"), eq(OWNER_ID), eq("workflow"), eq("MEMBER"), any());
            doAnswer(inv -> inv.getArgument(0)).when(orgAccessService)
                    .filterAccessible(any(), eq("org-acme"), eq(OWNER_ID), argThat(type -> !"workflow".equals(type)), eq("MEMBER"), any());

            Map<String, Object> resources = service.getProjectResources(PROJECT_ID, OWNER_ID, "org-acme", "MEMBER");

            @SuppressWarnings("unchecked")
            List<WorkflowEntity> workflows = (List<WorkflowEntity>) resources.get("workflows");
            assertThat(workflows).containsExactly(allowedWorkflow);
        }

        @Test
        @DisplayName("A member-restricted application is dropped from the project Applications tab - and the deny-list is keyed on the publication id")
        void orgProjectDetailsFilterRestrictedApplication() {
            // Two project applications: the member is denied the second one. The summary map's "id"
            // IS the publication id (InternalPublicationController.toSummaryMap), which is the canonical
            // "application" org-access resource id the per-member deny-list is keyed on.
            String allowedPubId = UUID.randomUUID().toString();
            String deniedPubId = UUID.randomUUID().toString();
            Map<String, Object> allowedApp = Map.of("id", allowedPubId, "title", "Allowed App");
            Map<String, Object> deniedApp = Map.of("id", deniedPubId, "title", "Denied App");

            when(workflowRepository.findByProjectIdAndOrganizationId(PROJECT_ID, "org-acme")).thenReturn(List.of());
            when(interfaceClient.getByProject(PROJECT_ID, OWNER_ID)).thenReturn(List.of());
            when(agentClient.findByProjectId(PROJECT_ID, OWNER_ID)).thenReturn(List.of());
            when(dataSourceClient.findByProjectIdWithPreview(PROJECT_ID, OWNER_ID))
                    .thenReturn(ProjectDataSourcesPreviewDto.empty());
            when(publicationClient.findByProjectId(PROJECT_ID, OWNER_ID)).thenReturn(List.of(allowedApp, deniedApp));
            when(storageService.findFilesByProjectForScope(PROJECT_ID, "org-acme")).thenReturn(List.of());
            // Application filter: invoke the REAL projection lambda (arg 5) to resolve each app's id,
            // then drop the denied publication id. This pins BOTH behaviours - the deny-list is applied
            // on the "application" resource type, AND it is keyed on application.get("id") (the publication
            // id). A regression to a different projection key (e.g. workflowId) would leak the denied app
            // here yet leave the generic-pass-through workflow test green.
            doAnswer(inv -> {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> apps = (List<Map<String, Object>>) inv.getArgument(0);
                @SuppressWarnings("unchecked")
                java.util.function.Function<Map<String, Object>, String> idOf = inv.getArgument(5);
                return apps.stream().filter(a -> !deniedPubId.equals(idOf.apply(a))).toList();
            }).when(orgAccessService)
                    .filterAccessible(any(), eq("org-acme"), eq(OWNER_ID), eq("application"), eq("MEMBER"), any());
            // Every other resource type passes through unchanged.
            doAnswer(inv -> inv.getArgument(0)).when(orgAccessService)
                    .filterAccessible(any(), eq("org-acme"), eq(OWNER_ID), argThat(type -> !"application".equals(type)), eq("MEMBER"), any());

            Map<String, Object> resources = service.getProjectResources(PROJECT_ID, OWNER_ID, "org-acme", "MEMBER");

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> apps = (List<Map<String, Object>>) resources.get("applications");
            assertThat(apps).hasSize(1);
            assertThat(apps.get(0)).containsEntry("id", allowedPubId);
        }

        @Test
        @DisplayName("exposes the datasource preview (rowCounts + sampleRows) so the Tables tab renders the /app/tables card")
        void exposesDatasourcePreviewMaps() {
            Map<String, Long> rowCounts = Map.of("10", 2L);
            Map<String, List<Map<String, Object>>> sampleRows =
                    Map.of("10", List.of(Map.of("title", "Alice")));
            when(workflowRepository.findByProjectIdAndOrganizationId(PROJECT_ID, "org-acme"))
                    .thenReturn(List.of());
            when(interfaceClient.getByProject(PROJECT_ID, OWNER_ID)).thenReturn(List.of());
            when(agentClient.findByProjectId(PROJECT_ID, OWNER_ID)).thenReturn(List.of());
            when(dataSourceClient.findByProjectIdWithPreview(PROJECT_ID, OWNER_ID))
                    .thenReturn(new ProjectDataSourcesPreviewDto(List.of(), rowCounts, sampleRows));
            when(publicationClient.findByProjectId(PROJECT_ID, OWNER_ID)).thenReturn(List.of());
            when(storageService.findFilesByProjectForScope(PROJECT_ID, "org-acme")).thenReturn(List.of());
            doAnswer(inv -> inv.getArgument(0)).when(orgAccessService)
                    .filterAccessible(any(), eq("org-acme"), eq(OWNER_ID), any(), any(), any());

            Map<String, Object> resources = service.getProjectResources(PROJECT_ID, OWNER_ID, "org-acme");

            // The preview maps ride alongside the datasources so the frontend card looks them up by id.
            assertThat(resources.get("datasourceRowCounts")).isEqualTo(rowCounts);
            assertThat(resources.get("datasourceSampleRows")).isEqualTo(sampleRows);
        }
    }
}
