package com.apimarketplace.interfaces.controller;

import com.apimarketplace.interfaces.client.dto.*;
import com.apimarketplace.interfaces.domain.InterfaceEntity;
import com.apimarketplace.interfaces.domain.InterfaceRunSnapshotEntity;
import com.apimarketplace.interfaces.repository.InterfaceRepository;
import com.apimarketplace.interfaces.service.InterfaceDtoMapper;
import com.apimarketplace.interfaces.service.InterfaceService;
import com.apimarketplace.interfaces.service.InterfaceSnapshotService;
import com.apimarketplace.interfaces.service.InterfaceVariableExtractor;
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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class InternalInterfaceControllerTest {

    @Mock private InterfaceService interfaceService;
    @Mock private InterfaceSnapshotService snapshotService;
    @Mock private InterfaceVariableExtractor variableExtractor;
    @Mock private InterfaceRepository interfaceRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final InterfaceDtoMapper mapper = new InterfaceDtoMapper();

    private static final String TENANT = "tenant-1";
    private static final String BASE = "/api/internal/interfaces";

    @BeforeEach
    void setUp() {
        InternalInterfaceController controller = new InternalInterfaceController(
                interfaceService, snapshotService, variableExtractor, mapper, interfaceRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Nested
    class GetInterface {
        @Test
        void shouldGetWithTenant() throws Exception {
            InterfaceEntity entity = createEntity();
            // #150 - internal controller now threads the optional
            // X-Organization-ID through. Without the header, orgId is null
            // and the personal-strict path applies.
            when(interfaceService.getInterface(entity.getId(), TENANT, "org-x"))
                    .thenReturn(Optional.of(entity));

            mockMvc.perform(get(BASE + "/{id}", entity.getId())
                            .header("X-User-ID", TENANT)
                            .header("X-Organization-ID", "org-x"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Test"));
        }

        @Test
        void shouldRouteOrgIdWhenInternalHeaderPresent() throws Exception {
            // #150 - orchestrator/publication callers that already have an
            // active-org context propagate it via X-Organization-ID.
            InterfaceEntity entity = createEntity();
            when(interfaceService.getInterface(entity.getId(), TENANT, "org-x"))
                    .thenReturn(Optional.of(entity));

            mockMvc.perform(get(BASE + "/{id}", entity.getId())
                            .header("X-User-ID", TENANT)
                            .header("X-Organization-ID", "org-x"))
                    .andExpect(status().isOk());

            verify(interfaceService).getInterface(entity.getId(), TENANT, "org-x");
        }

        @Test
        void shouldGetWithoutTenant() throws Exception {
            InterfaceEntity entity = createEntity();
            when(interfaceService.getInterfaceInternal(entity.getId()))
                    .thenReturn(Optional.of(entity));

            mockMvc.perform(get(BASE + "/{id}/template", entity.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Test"));
        }

        @Test
        void shouldReturn404WhenNotFound() throws Exception {
            UUID id = UUID.randomUUID();
            when(interfaceService.getInterfaceInternal(id)).thenReturn(Optional.empty());

            mockMvc.perform(get(BASE + "/{id}/template", id))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    class ListInterfaces {
        @Test
        void shouldListAll() throws Exception {
            when(interfaceService.listInterfaces(eq(TENANT), isNull(), isNull(), isNull()))
                    .thenReturn(List.of(createEntity()));

            mockMvc.perform(get(BASE)
                            .header("X-User-ID", TENANT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }

        @Test
        void shouldListByType() throws Exception {
            when(interfaceService.listInterfacesByType(eq(TENANT), eq("html"), any(), any()))
                    .thenReturn(List.of(createEntity()));

            mockMvc.perform(get(BASE)
                            .header("X-User-ID", TENANT)
                            .param("type", "html"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }
    }

    @Nested
    class CreateInterface {
        @Test
        void shouldCreate() throws Exception {
            InterfaceEntity entity = createEntity();
            when(interfaceService.createInterface(eq(TENANT), eq("New"), isNull(), eq("<p>hi</p>"),
                    isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                    .thenReturn(entity);

            String body = "{\"name\":\"New\",\"html_template\":\"<p>hi</p>\"}";

            mockMvc.perform(post(BASE)
                            .header("X-User-ID", TENANT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Test"));
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

            String body = "{\"name\":\"Updated\"}";

            mockMvc.perform(put(BASE + "/{id}", entity.getId())
                            .header("X-User-ID", TENANT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }

        @Test
        void shouldThreadOrgHeadersThroughOnUpdate() throws Exception {
            // #150 - internal callers (e.g. orchestrator forwarding a user
            // mutation) can propagate their org context.
            InterfaceEntity entity = createEntity();
            when(interfaceService.updateInterface(eq(entity.getId()), eq(TENANT), eq("org-x"), eq("MEMBER"),
                    eq("Updated"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(entity);

            mockMvc.perform(put(BASE + "/{id}", entity.getId())
                            .header("X-User-ID", TENANT)
                            .header("X-Organization-ID", "org-x")
                            .header("X-Organization-Role", "MEMBER")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"Updated\"}"))
                    .andExpect(status().isOk());

            verify(interfaceService).updateInterface(eq(entity.getId()), eq(TENANT), eq("org-x"), eq("MEMBER"),
                    eq("Updated"), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Nested
    class DeleteInterface {
        @Test
        void shouldDelete() throws Exception {
            UUID id = UUID.randomUUID();

            mockMvc.perform(delete(BASE + "/{id}", id)
                            .header("X-User-ID", TENANT))
                    .andExpect(status().isNoContent());

            verify(interfaceService).deleteInterface(id, TENANT, null, null);
        }
    }

    @Nested
    class CloneInterface {
        @Test
        void shouldClone() throws Exception {
            InterfaceEntity entity = createEntity();
            entity.setName("Test (Copy)");
            when(interfaceService.cloneInterface(entity.getId(), TENANT, null, null)).thenReturn(entity);

            mockMvc.perform(post(BASE + "/{id}/clone", entity.getId())
                            .header("X-User-ID", TENANT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Test (Copy)"));
        }
    }

    @Nested
    class AgentBrowse {
        @Test
        void shouldCreateAgentBrowse() throws Exception {
            InterfaceEntity entity = createEntity();
            entity.setInterfaceType("agent_browse");
            when(interfaceService.createOrUpdateAgentBrowseInterface(
                    eq(TENANT), eq("conv-1"), eq("msg-1"), eq("agent-1"),
                    eq("browse"), any(), isNull()))
                    .thenReturn(entity);

            String body = "{\"name\":\"browse\",\"conversation_id\":\"conv-1\"," +
                    "\"message_id\":\"msg-1\",\"agent_id\":\"agent-1\",\"data\":{\"action\":\"agent_browse\"}}";

            mockMvc.perform(post(BASE + "/agent-browse")
                            .header("X-User-ID", TENANT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }

        @Test
        void shouldUpdateWebSearchScreenshotLegacyEndpoint() throws Exception {
            // The legacy /web-search-screenshot path is kept dormant for
            // back-compat with the disabled websearch screenshot callback
            // (commit f600c8885). Still routable; the underlying service
            // call is a no-op against a non-existing row after the V279 + prod purge.
            UUID id = UUID.randomUUID();

            mockMvc.perform(put(BASE + "/{id}/web-search-screenshot", id)
                            .header("X-User-ID", TENANT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"result_url\":\"https://x.com\",\"screenshot_key\":\"key-1\"}"))
                    .andExpect(status().isOk());

            verify(interfaceService).updateWebSearchScreenshot(id, "https://x.com", "key-1");
        }
    }

    @Nested
    class Slides {
        @Test
        void shouldCreateSlide() throws Exception {
            InterfaceEntity entity = createEntity();
            entity.setInterfaceType("slide");
            when(interfaceService.createSlideInterface(eq(TENANT), eq("My Deck"), isNull(), any()))
                    .thenReturn(entity);

            String body = "{\"name\":\"My Deck\",\"slide_data\":{\"slides\":[]}}";

            mockMvc.perform(post(BASE + "/slide")
                            .header("X-User-ID", TENANT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());
        }

        @Test
        void shouldUpdateSlideData() throws Exception {
            InterfaceEntity entity = createEntity();
            when(interfaceService.updateSlideData(eq(entity.getId()), eq(TENANT), isNull(), isNull(), any()))
                    .thenReturn(entity);

            mockMvc.perform(put(BASE + "/{id}/slide-data", entity.getId())
                            .header("X-User-ID", TENANT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"slides\":[{\"layout\":\"title\"}]}"))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    class Snapshots {
        @Test
        void shouldCreateSnapshot() throws Exception {
            InterfaceEntity iface = createEntity();
            UUID runId = UUID.randomUUID();
            InterfaceRunSnapshotEntity snapshot = InterfaceRunSnapshotEntity.fromInterface(iface, runId);
            when(snapshotService.createSnapshot(eq(iface.getId()), eq(runId), any(), any(), eq(TENANT)))
                    .thenReturn(snapshot);

            String body = String.format(
                    "{\"interface_id\":\"%s\",\"workflow_run_id\":\"%s\"}", iface.getId(), runId);

            mockMvc.perform(post(BASE + "/snapshots")
                            .header("X-User-ID", TENANT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Test"));
        }

        @Test
        void shouldReturn404WhenSnapshotCreationReturnsNull() throws Exception {
            UUID ifaceId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            when(snapshotService.createSnapshot(eq(ifaceId), eq(runId), any(), any(), eq(TENANT)))
                    .thenReturn(null);

            String body = String.format(
                    "{\"interface_id\":\"%s\",\"workflow_run_id\":\"%s\"}", ifaceId, runId);

            mockMvc.perform(post(BASE + "/snapshots")
                            .header("X-User-ID", TENANT)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isNotFound());
        }

        @Test
        void shouldFindSnapshot() throws Exception {
            UUID ifaceId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            InterfaceRunSnapshotEntity snapshot = InterfaceRunSnapshotEntity.fromInterface(createEntity(), runId);
            // post-V263 hardening: internal /snapshots/find now reads X-Organization-ID and passes
            // it through; absent header → null orgId → service falls back to legacy unscoped path.
            when(snapshotService.getSnapshot(ifaceId, runId, null)).thenReturn(Optional.of(snapshot));

            mockMvc.perform(get(BASE + "/snapshots/find")
                            .param("interfaceId", ifaceId.toString())
                            .param("workflowRunId", runId.toString()))
                    .andExpect(status().isOk());
        }

        @Test
        void shouldGetSnapshotsForRun() throws Exception {
            UUID runId = UUID.randomUUID();
            when(snapshotService.getSnapshotsForRun(runId, null)).thenReturn(List.of());

            mockMvc.perform(get(BASE + "/snapshots/by-run/{runId}", runId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        void shouldDeleteSnapshotsForRun() throws Exception {
            UUID runId = UUID.randomUUID();

            mockMvc.perform(delete(BASE + "/snapshots/by-run/{runId}", runId))
                    .andExpect(status().isNoContent());

            verify(snapshotService).deleteSnapshotsForRun(runId);
        }

        @Test
        void shouldRefreshSnapshotsFromLive() throws Exception {
            // Endpoint added 2026-05-14 to support the prod fix where long-running
            // WAITING_TRIGGER runs were stuck with stale interface snapshots after the
            // agent corrected the underlying HTML/JS. Mirrors the plan-refresh idiom on
            // the workflow side; called by ReusableTriggerService on each refire.
            UUID runId = UUID.randomUUID();
            // Post-V263 refresh hardening: controller now reads X-Organization-ID and forwards
            // it to the 2-arg service overload. Absent header → orgId=null → legacy unscoped path.
            when(snapshotService.refreshSnapshotsFromLiveInterface(runId, null))
                    .thenReturn(new InterfaceSnapshotService.RefreshResult(2, 1, 1, 0));

            mockMvc.perform(post(BASE + "/snapshots/refresh-from-live/{runId}", runId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.refreshed").value(2))
                    .andExpect(jsonPath("$.unchanged").value(1))
                    .andExpect(jsonPath("$.missing").value(1))
                    .andExpect(jsonPath("$.errors").value(0))
                    .andExpect(jsonPath("$.total").value(4));

            verify(snapshotService).refreshSnapshotsFromLiveInterface(runId, null);
        }

        @Test
        void shouldForwardOrganizationIdHeaderToRefreshService() throws Exception {
            UUID runId = UUID.randomUUID();
            String orgId = "org-A";
            when(snapshotService.refreshSnapshotsFromLiveInterface(runId, orgId))
                    .thenReturn(new InterfaceSnapshotService.RefreshResult(1, 0, 0, 0));

            mockMvc.perform(post(BASE + "/snapshots/refresh-from-live/{runId}", runId)
                            .header("X-Organization-ID", orgId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.refreshed").value(1));

            verify(snapshotService).refreshSnapshotsFromLiveInterface(runId, orgId);
            verify(snapshotService, never()).refreshSnapshotsFromLiveInterface(any(UUID.class));
        }
    }

    @Nested
    class Variables {
        @Test
        void shouldExtractVariables() throws Exception {
            when(variableExtractor.extractTemplateVariables(anyString()))
                    .thenReturn(List.of("name", "email"));

            mockMvc.perform(post(BASE + "/extract-variables")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"html_template\":\"{{name}} {{email}}\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(2)))
                    .andExpect(jsonPath("$[0]").value("name"));
        }

        @Test
        void shouldExtractFormFields() throws Exception {
            when(variableExtractor.extractFormFields(anyString()))
                    .thenReturn(List.of("username"));

            mockMvc.perform(post(BASE + "/extract-form-fields")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"html_template\":\"<input name='username' />\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0]").value("username"));
        }
    }

    @Nested
    class Projects {
        @Test
        void shouldAssignToProject() throws Exception {
            UUID id = UUID.randomUUID();
            UUID projectId = UUID.randomUUID();

            mockMvc.perform(put(BASE + "/{id}/project/{projectId}", id, projectId)
                            .header("X-User-ID", TENANT))
                    .andExpect(status().isOk());

            verify(interfaceService).assignToProject(id, projectId, TENANT, null);
        }

        @Test
        void shouldRemoveFromProject() throws Exception {
            UUID id = UUID.randomUUID();
            UUID projectId = UUID.randomUUID();

            mockMvc.perform(delete(BASE + "/{id}/project/{projectId}", id, projectId)
                            .header("X-User-ID", TENANT))
                    .andExpect(status().isOk());

            verify(interfaceService).removeFromProject(id, projectId, TENANT, null);
        }

        @Test
        void shouldCountByProject() throws Exception {
            UUID projectId = UUID.randomUUID();
            when(interfaceService.countByProject(projectId, null)).thenReturn(3L);

            mockMvc.perform(get(BASE + "/count-by-project/{projectId}", projectId))
                    .andExpect(status().isOk())
                    .andExpect(content().string("3"));
        }

        @Test
        void shouldGetByProject() throws Exception {
            UUID projectId = UUID.randomUUID();
            when(interfaceService.getByProject(projectId, null)).thenReturn(List.of(createEntity()));

            mockMvc.perform(get(BASE + "/by-project/{projectId}", projectId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }

        @Test
        void shouldUnassignAllFromProject() throws Exception {
            UUID projectId = UUID.randomUUID();

            mockMvc.perform(delete(BASE + "/unassign-project/{projectId}", projectId))
                    .andExpect(status().isNoContent());

            verify(interfaceService).unassignAllFromProject(projectId, null);
        }
    }

    @Nested
    class BatchAndBulk {
        @Test
        void shouldBatchFetch() throws Exception {
            UUID id1 = UUID.randomUUID();
            when(interfaceService.getInterfacesByIds(anyList())).thenReturn(List.of(createEntity()));

            mockMvc.perform(post(BASE + "/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[\"" + id1 + "\"]"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }

        @Test
        void shouldReturnEmptyForEmptyBatch() throws Exception {
            mockMvc.perform(post(BASE + "/batch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("[]"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        void shouldFindBySourceWorkflow() throws Exception {
            UUID workflowId = UUID.randomUUID();
            when(interfaceService.findBySourceWorkflowId(workflowId)).thenReturn(List.of(createEntity()));

            mockMvc.perform(get(BASE + "/by-source-workflow/{id}", workflowId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)));
        }

        @Test
        void shouldDeleteBySourceWorkflow() throws Exception {
            UUID workflowId = UUID.randomUUID();

            mockMvc.perform(delete(BASE + "/by-source-workflow/{id}", workflowId))
                    .andExpect(status().isNoContent());

            verify(interfaceService).deleteBySourceWorkflowId(workflowId);
        }

        @Test
        void shouldCountByTenant() throws Exception {
            when(interfaceService.countByTenant(TENANT)).thenReturn(7L);

            mockMvc.perform(get(BASE + "/count")
                            .header("X-User-ID", TENANT))
                    .andExpect(status().isOk())
                    .andExpect(content().string("7"));
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
