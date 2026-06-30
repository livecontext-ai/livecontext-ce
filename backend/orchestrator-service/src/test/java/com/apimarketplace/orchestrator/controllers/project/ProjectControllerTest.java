package com.apimarketplace.orchestrator.controllers.project;

import com.apimarketplace.orchestrator.domain.*;
import com.apimarketplace.orchestrator.services.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ProjectController - REST endpoint behavior.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectController")
class ProjectControllerTest {

    @Mock private ProjectService projectService;

    private ProjectController controller;

    private static final String USER_ID = "user-123";
    private static final UUID PROJECT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        controller = new ProjectController(projectService);
    }

    // ===================== Factory helpers =====================

    private ProjectEntity createProject(UUID id, String name, String ownerId) {
        ProjectEntity p = new ProjectEntity(name, name.toLowerCase().replace(" ", "_"), ownerId);
        p.setId(id);
        p.setCreatedAt(Instant.now());
        p.setUpdatedAt(Instant.now());
        return p;
    }

    // ===================== CRUD Tests =====================

    @Nested
    @DisplayName("createProject")
    class CreateProjectTests {

        @Test
        @DisplayName("Should return 200 with project data")
        void shouldCreateProject() {
            ProjectEntity project = createProject(PROJECT_ID, "My Project", USER_ID);
            when(projectService.createProject(eq(USER_ID), eq("My Project"), any(), any(), any(), any()))
                .thenReturn(project);

            Map<String, Object> body = new HashMap<>();
            body.put("name", "My Project");
            body.put("description", "desc");

            ResponseEntity<?> response = controller.createProject(USER_ID, null, body);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = (Map<String, Object>) response.getBody();
            assertThat(responseBody).containsEntry("name", "My Project");
            assertThat(responseBody).containsEntry("currentUserRole", "OWNER");
        }

        @Test
        @DisplayName("Should return 400 when name is missing")
        void shouldReturn400WithoutName() {
            Map<String, Object> body = new HashMap<>();
            body.put("description", "no name");

            ResponseEntity<?> response = controller.createProject(USER_ID, null, body);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("Should return 400 when name is blank")
        void shouldReturn400WithBlankName() {
            Map<String, Object> body = new HashMap<>();
            body.put("name", "   ");

            ResponseEntity<?> response = controller.createProject(USER_ID, null, body);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("listProjects")
    class ListProjectsTests {

        @Test
        @DisplayName("Should return list of projects with role and resource counts")
        void shouldReturnProjects() {
            ProjectEntity p1 = createProject(PROJECT_ID, "Proj 1", USER_ID);
            when(projectService.getUserProjects(USER_ID, null, null)).thenReturn(List.of(p1));
            when(projectService.getResourceCounts(PROJECT_ID, USER_ID, null, null)).thenReturn(Map.of("workflows", 2L));

            ResponseEntity<?> response = controller.listProjects(USER_ID, null, null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("count", 1);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> projects = (List<Map<String, Object>>) body.get("projects");
            assertThat(projects).hasSize(1);
            assertThat(projects.get(0)).containsEntry("name", "Proj 1");
            assertThat(projects.get(0)).containsEntry("currentUserRole", "OWNER");
        }

        @Test
        @DisplayName("Should return empty list when no projects")
        void shouldReturnEmptyList() {
            when(projectService.getUserProjects(USER_ID, null, null)).thenReturn(List.of());

            ResponseEntity<?> response = controller.listProjects(USER_ID, null, null);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("count", 0);
        }
    }

    @Nested
    @DisplayName("getProject")
    class GetProjectTests {

        @Test
        @DisplayName("Should return 200 with project details")
        void shouldReturnProject() {
            ProjectEntity project = createProject(PROJECT_ID, "Test", USER_ID);
            when(projectService.getProject(PROJECT_ID, USER_ID, null, null)).thenReturn(Optional.of(project));
            when(projectService.getResourceCounts(PROJECT_ID, USER_ID, null, null)).thenReturn(Map.of());

            ResponseEntity<?> response = controller.getProject(USER_ID, null, null, PROJECT_ID);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("name", "Test");
            assertThat(body).containsKey("resourceCounts");
        }

        @Test
        @DisplayName("Should return 404 when project not found")
        void shouldReturn404WhenNotFound() {
            when(projectService.getProject(PROJECT_ID, USER_ID, null, null)).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.getProject(USER_ID, null, null, PROJECT_ID);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("updateProject")
    class UpdateProjectTests {

        @Test
        @DisplayName("Should return 200 on successful update")
        void shouldUpdateProject() {
            ProjectEntity updated = createProject(PROJECT_ID, "Updated", USER_ID);
            when(projectService.updateProject(eq(PROJECT_ID), eq(USER_ID), any(), any(), any())).thenReturn(Optional.of(updated));

            Map<String, Object> body = Map.of("name", "Updated");
            ResponseEntity<?> response = controller.updateProject(USER_ID, null, null, PROJECT_ID, body);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("Should return 404 when cannot update")
        void shouldReturn404WhenCannotUpdate() {
            when(projectService.updateProject(eq(PROJECT_ID), eq(USER_ID), any(), any(), any())).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.updateProject(USER_ID, null, null, PROJECT_ID, Map.of("name", "X"));

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }
    }

    @Nested
    @DisplayName("deleteProject")
    class DeleteProjectTests {

        @Test
        @DisplayName("Should return 200 on successful deletion")
        void shouldDeleteProject() {
            when(projectService.deleteProject(PROJECT_ID, USER_ID, null, null)).thenReturn(true);

            ResponseEntity<?> response = controller.deleteProject(USER_ID, null, null, PROJECT_ID);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("Should return 404 when cannot delete")
        void shouldReturn404WhenCannotDelete() {
            when(projectService.deleteProject(PROJECT_ID, USER_ID, null, null)).thenReturn(false);

            ResponseEntity<?> response = controller.deleteProject(USER_ID, null, null, PROJECT_ID);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }
    }

    // ===================== Resource Management Tests =====================

    @Nested
    @DisplayName("assignResource")
    class AssignResourceTests {

        @Test
        @DisplayName("Should return 200 when resource assigned")
        void shouldAssignResource() {
            when(projectService.assignResource(PROJECT_ID, "workflow", "wf-id", USER_ID, null, null)).thenReturn(true);

            ResponseEntity<?> response = controller.assignResource(
                USER_ID, null, null, PROJECT_ID, Map.of("resourceType", "workflow", "resourceId", "wf-id")
            );

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("Should return 400 when required fields missing")
        void shouldReturn400WithoutFields() {
            ResponseEntity<?> response = controller.assignResource(USER_ID, null, null, PROJECT_ID, Map.of());

            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }
    }

    @Nested
    @DisplayName("removeResource")
    class RemoveResourceTests {

        @Test
        @DisplayName("Should return 200 when resource removed")
        void shouldRemoveResource() {
            when(projectService.removeResource(PROJECT_ID, "workflow", "wf-id", USER_ID, null, null)).thenReturn(true);

            ResponseEntity<?> response = controller.removeResource(USER_ID, null, null, PROJECT_ID, "workflow", "wf-id");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }
    }
}
