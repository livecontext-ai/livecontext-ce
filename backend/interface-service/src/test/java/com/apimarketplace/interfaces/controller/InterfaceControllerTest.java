package com.apimarketplace.interfaces.controller;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.interfaces.domain.InterfaceEntity;
import com.apimarketplace.interfaces.domain.InterfaceRunSnapshotEntity;
import com.apimarketplace.interfaces.service.InterfaceDtoMapper;
import com.apimarketplace.interfaces.service.InterfaceService;
import com.apimarketplace.interfaces.service.InterfaceSnapshotService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.*;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class InterfaceControllerTest {

    @Mock private InterfaceService interfaceService;
    @Mock private InterfaceSnapshotService snapshotService;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InterfaceDtoMapper mapper = new InterfaceDtoMapper();
    private final TenantResolver tenantResolver = new TenantResolver();

    private static final String TENANT = "tenant-1";

    @BeforeEach
    void setUp() {
        InterfaceController controller = new InterfaceController(
                interfaceService, snapshotService, mapper, tenantResolver);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Nested
    class CreateInterface {
        @Test
        void shouldCreateWithCamelCaseBody() throws Exception {
            InterfaceEntity entity = createEntity();
            when(interfaceService.createInterface(
                    eq(TENANT), eq("Test"), isNull(), eq("<div>{{title}}</div>"),
                    eq(".c{}"), eq("js"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                    .thenReturn(entity);

            Map<String, Object> body = Map.of(
                    "name", "Test",
                    "htmlTemplate", "<div>{{title}}</div>",
                    "cssTemplate", ".c{}",
                    "jsTemplate", "js");

            mockMvc.perform(post("/api/interfaces")
                            .header("X-User-ID", TENANT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Test"))
                    .andExpect(jsonPath("$.htmlTemplate").value("<div>{{title}}</div>"));
        }

        @Test
        void wrongTypedNumericField_returns400_not500() throws Exception {
            // Regression: a non-numeric dataSourceId used to hit ((Number) v).longValue()
            // -> uncaught ClassCastException -> HTTP 500. Now a clean 400.
            Map<String, Object> body = Map.of(
                    "name", "Test", "htmlTemplate", "<div>hi</div>", "dataSourceId", "not-a-number");

            mockMvc.perform(post("/api/interfaces")
                            .header("X-User-ID", TENANT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void wrongTypedBooleanField_returns400_not500() throws Exception {
            Map<String, Object> body = Map.of(
                    "name", "Test", "htmlTemplate", "<div>hi</div>", "isPublic", "maybe");

            mockMvc.perform(post("/api/interfaces")
                            .header("X-User-ID", TENANT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void numericStringField_isAccepted() throws Exception {
            // A numeric STRING must still be accepted (defensive parse), mirroring the internal path.
            InterfaceEntity entity = createEntity();
            when(interfaceService.createInterface(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(entity);

            Map<String, Object> body = Map.of(
                    "name", "Test", "htmlTemplate", "<div>hi</div>", "dataSourceId", "5");

            mockMvc.perform(post("/api/interfaces")
                            .header("X-User-ID", TENANT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }

        @Test
        void shouldCreateWithSnakeCaseBody() throws Exception {
            InterfaceEntity entity = createEntity();
            when(interfaceService.createInterface(
                    eq(TENANT), eq("Test"), isNull(), eq("<div>hi</div>"),
                    isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                    .thenReturn(entity);

            Map<String, Object> body = Map.of(
                    "name", "Test",
                    "html_template", "<div>hi</div>");

            mockMvc.perform(post("/api/interfaces")
                            .header("X-User-ID", TENANT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }

        /**
         * REGRESSION: Verify response JSON uses camelCase, not snake_case.
         */
        @Test
        void createInterface_responseCamelCaseJsonFormat() throws Exception {
            InterfaceEntity entity = createEntity();
            when(interfaceService.createInterface(any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(entity);

            Map<String, Object> body = Map.of("name", "Test", "htmlTemplate", "<div>hi</div>");

            String responseJson = mockMvc.perform(post("/api/interfaces")
                            .header("X-User-ID", TENANT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.htmlTemplate").exists())
                    .andReturn().getResponse().getContentAsString();

            org.assertj.core.api.Assertions.assertThat(responseJson).contains("\"htmlTemplate\"");
            org.assertj.core.api.Assertions.assertThat(responseJson).doesNotContain("\"html_template\"");
            org.assertj.core.api.Assertions.assertThat(responseJson).contains("\"cssTemplate\"");
            org.assertj.core.api.Assertions.assertThat(responseJson).doesNotContain("\"css_template\"");
        }

        @Test
        void viewerCannotCreateWorkspaceInterface() throws Exception {
            mockMvc.perform(post("/api/interfaces")
                            .header("X-User-ID", TENANT)
                            .header("X-Organization-ID", "org-1")
                            .header("X-Organization-Role", "VIEWER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "name", "Forbidden",
                                    "htmlTemplate", "<main>Forbidden</main>"))))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(interfaceService);
        }
    }

    @Nested
    class ListInterfaces {
        @Test
        void shouldListAllForTenant() throws Exception {
            when(interfaceService.listInterfaces(eq(TENANT), isNull(), isNull(), isNull()))
                    .thenReturn(List.of(createEntity()));

            mockMvc.perform(get("/api/interfaces")
                            .header("X-User-ID", TENANT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }

        @Test
        void shouldListByType() throws Exception {
            when(interfaceService.listInterfacesByType(eq(TENANT), eq("slide"), any(), any()))
                    .thenReturn(List.of(createEntity()));

            mockMvc.perform(get("/api/interfaces")
                            .header("X-User-ID", TENANT)
                            .param("type", "slide"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }

        @Test
        void pagedListShouldOmitTemplatesWhenRequested() throws Exception {
            InterfaceEntity entity = createEntity();
            when(interfaceService.listInterfacesPaged(eq(TENANT), eq("html"), isNull(), isNull(),
                    isNull(), isNull(), eq(0), eq(25), isNull(), isNull()))
                    .thenReturn(new InterfaceService.InterfacePage(List.of(entity), 1, 0, 25, java.util.Map.of()));

            mockMvc.perform(get("/api/interfaces/paged")
                            .header("X-User-ID", TENANT)
                            .param("type", "html")
                            .param("includeTemplates", "false"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items", hasSize(1)))
                    .andExpect(jsonPath("$.items[0].id").value(entity.getId().toString()))
                    .andExpect(jsonPath("$.items[0].name").value("Test"))
                    .andExpect(jsonPath("$.items[0].htmlTemplate").doesNotExist())
                    .andExpect(jsonPath("$.items[0].cssTemplate").doesNotExist())
                    .andExpect(jsonPath("$.items[0].jsTemplate").doesNotExist())
                    .andExpect(jsonPath("$.items[0].templateVariables").doesNotExist());
        }
    }

    @Nested
    class GetInterface {
        @Test
        void shouldReturnInterface() throws Exception {
            InterfaceEntity entity = createEntity();
            // #150 - controller threads orgId (null in this fixture) through.
            when(interfaceService.getInterface(entity.getId(), TENANT, null))
                    .thenReturn(Optional.of(entity));

            mockMvc.perform(get("/api/interfaces/{id}", entity.getId())
                            .header("X-User-ID", TENANT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Test"));
        }

        @Test
        void shouldReturn404WhenNotFound() throws Exception {
            UUID id = UUID.randomUUID();
            when(interfaceService.getInterface(id, TENANT, null)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/interfaces/{id}", id)
                            .header("X-User-ID", TENANT))
                    .andExpect(status().isNotFound());
        }

        @Test
        void shouldRouteOrgIdThroughWhenHeaderPresent() throws Exception {
            // #150 - closes the TEAM-workspace 404 bug from the issue report.
            InterfaceEntity entity = createEntity();
            when(interfaceService.getInterface(entity.getId(), TENANT, "org-1"))
                    .thenReturn(Optional.of(entity));

            mockMvc.perform(get("/api/interfaces/{id}", entity.getId())
                            .header("X-User-ID", TENANT)
                            .header("X-Organization-ID", "org-1"))
                    .andExpect(status().isOk());

            verify(interfaceService).getInterface(entity.getId(), TENANT, "org-1");
        }
    }

    @Nested
    class UpdateInterface {
        @Test
        void shouldUpdate() throws Exception {
            InterfaceEntity entity = createEntity();
            when(interfaceService.updateInterface(eq(entity.getId()), eq(TENANT), isNull(), isNull(),
                    eq("Updated"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(entity);

            Map<String, Object> body = Map.of("name", "Updated");

            mockMvc.perform(put("/api/interfaces/{id}", entity.getId())
                            .header("X-User-ID", TENANT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(body)))
                    .andExpect(status().isOk());
        }

        @Test
        void shouldThreadOrgHeadersThroughToService() throws Exception {
            // #150 - verifies the controller picks up X-Organization-ID and
            // X-Organization-Role and threads them into the scope-aware overload.
            InterfaceEntity entity = createEntity();
            when(interfaceService.updateInterface(eq(entity.getId()), eq(TENANT), eq("org-1"), eq("MEMBER"),
                    eq("Updated"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(entity);

            mockMvc.perform(put("/api/interfaces/{id}", entity.getId())
                            .header("X-User-ID", TENANT)
                            .header("X-Organization-ID", "org-1")
                            .header("X-Organization-Role", "MEMBER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "Updated"))))
                    .andExpect(status().isOk());

            verify(interfaceService).updateInterface(eq(entity.getId()), eq(TENANT), eq("org-1"), eq("MEMBER"),
                    eq("Updated"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        void viewerCannotUpdateWorkspaceInterface() throws Exception {
            UUID id = UUID.randomUUID();

            mockMvc.perform(put("/api/interfaces/{id}", id)
                            .header("X-User-ID", TENANT)
                            .header("X-Organization-ID", "org-1")
                            .header("X-Organization-Role", "VIEWER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("name", "Forbidden"))))
                    .andExpect(status().isForbidden());

            verifyNoInteractions(interfaceService);
        }
    }

    @Nested
    class CloneInterface {
        @Test
        void shouldClone() throws Exception {
            InterfaceEntity entity = createEntity();
            InterfaceEntity cloned = createEntity();
            cloned.setName("Test (Copy)");
            when(interfaceService.cloneInterface(entity.getId(), TENANT, null, null)).thenReturn(cloned);

            mockMvc.perform(post("/api/interfaces/{id}/clone", entity.getId())
                            .header("X-User-ID", TENANT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Test (Copy)"));
        }
    }

    @Nested
    class DeleteInterface {
        @Test
        void shouldDelete() throws Exception {
            UUID id = UUID.randomUUID();

            mockMvc.perform(delete("/api/interfaces/{id}", id)
                            .header("X-User-ID", TENANT))
                    .andExpect(status().isNoContent());

            verify(interfaceService).deleteInterface(id, TENANT, null, null);
        }

        @Test
        void shouldThreadOrgHeadersOnDelete() throws Exception {
            UUID id = UUID.randomUUID();

            mockMvc.perform(delete("/api/interfaces/{id}", id)
                            .header("X-User-ID", TENANT)
                            .header("X-Organization-ID", "org-1")
                            .header("X-Organization-Role", "OWNER"))
                    .andExpect(status().isNoContent());

            verify(interfaceService).deleteInterface(id, TENANT, "org-1", "OWNER");
        }
    }

    @Nested
    class Snapshots {
        @Test
        void shouldGetSnapshot() throws Exception {
            UUID ifaceId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            InterfaceEntity iface = createEntity();
            InterfaceRunSnapshotEntity snapshot = InterfaceRunSnapshotEntity.fromInterface(iface, runId);
            // #150 - controller pre-checks visibility of the parent interface
            // before reaching the snapshot service. Personal scope (null orgId)
            // is the default for tests with no X-Organization-ID header.
            when(interfaceService.getInterface(ifaceId, TENANT, null))
                    .thenReturn(Optional.of(iface));
            when(snapshotService.getSnapshot(ifaceId, runId)).thenReturn(Optional.of(snapshot));

            mockMvc.perform(get("/api/interfaces/{id}/snapshot", ifaceId)
                            .header("X-User-ID", TENANT)
                            .param("workflowRunId", runId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Test"));
        }

        @Test
        void shouldReturn404WhenSnapshotNotFound() throws Exception {
            UUID ifaceId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            when(interfaceService.getInterface(ifaceId, TENANT, null))
                    .thenReturn(Optional.of(createEntity()));
            when(snapshotService.getSnapshot(ifaceId, runId)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/interfaces/{id}/snapshot", ifaceId)
                            .header("X-User-ID", TENANT)
                            .param("workflowRunId", runId.toString()))
                    .andExpect(status().isNotFound());
        }

        @Test
        void shouldReturn404WhenSnapshotParentInterfaceOutOfScope() throws Exception {
            // #150 - guards against the pre-existing snapshot leak: anyone
            // who guessed an interface id + workflow run id could read the
            // frozen HTML. Now the controller refuses to even look up the
            // snapshot if the parent interface is invisible from the
            // caller's scope.
            UUID ifaceId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            when(interfaceService.getInterface(ifaceId, TENANT, null)).thenReturn(Optional.empty());

            mockMvc.perform(get("/api/interfaces/{id}/snapshot", ifaceId)
                            .header("X-User-ID", TENANT)
                            .param("workflowRunId", runId.toString()))
                    .andExpect(status().isNotFound());

            // Snapshot service is NEVER consulted when the parent is
            // out-of-scope - the controller short-circuits.
            verify(snapshotService, org.mockito.Mockito.never()).getSnapshot(any(), any());
        }

        @Test
        void shouldGetSnapshotsForRunFilteredByScope() throws Exception {
            UUID runId = UUID.randomUUID();
            InterfaceEntity iface = createEntity();
            InterfaceRunSnapshotEntity snapshot = InterfaceRunSnapshotEntity.fromInterface(iface, runId);
            // Make the snapshot's parent interface visible (personal scope).
            when(snapshotService.getSnapshotsForRun(runId)).thenReturn(List.of(snapshot));
            when(interfaceService.findInScope(eq(snapshot.getInterfaceId()), eq(TENANT), isNull()))
                    .thenReturn(Optional.of(iface));

            mockMvc.perform(get("/api/interfaces/snapshots")
                            .header("X-User-ID", TENANT)
                            .param("workflowRunId", runId.toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }
    }

    private InterfaceEntity createEntity() {
        InterfaceEntity entity = new InterfaceEntity();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(TENANT);
        entity.setName("Test");
        entity.setDescription("Desc");
        entity.setHtmlTemplate("<div>{{title}}</div>");
        entity.setCssTemplate(".c{}");
        entity.setJsTemplate("console.log('hi');");
        entity.setIsPublic(false);
        entity.setIsActive(true);
        entity.setInterfaceType("html");
        entity.setTemplateVariables(List.of("title"));
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return entity;
    }
}
