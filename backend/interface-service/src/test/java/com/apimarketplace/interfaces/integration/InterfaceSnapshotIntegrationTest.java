package com.apimarketplace.interfaces.integration;

import com.apimarketplace.interfaces.domain.InterfaceEntity;
import com.apimarketplace.interfaces.repository.InterfaceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@IntegrationTest
@AutoConfigureMockMvc
@Import(IntegrationTestConfig.class)
class InterfaceSnapshotIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private InterfaceRepository interfaceRepository;

    private static final String TENANT = "snapshot-tenant";
    private static final String INTERNAL_API = "/api/internal/interfaces";

    private UUID interfaceId;
    private UUID workflowRunId;

    @BeforeEach
    void setUp() {
        workflowRunId = UUID.randomUUID();

        // Create a test interface directly via repo
        InterfaceEntity entity = new InterfaceEntity();
        entity.setTenantId(TENANT);
        // V263 OrgScopedEntity: stamp org-id before persist (NOT NULL after V261)
        entity.setOrganizationId(TENANT);
        entity.setName("Snapshot Test");
        entity.setHtmlTemplate("<div>{{greeting}}</div>");
        entity.setCssTemplate(".c { color: red; }");
        entity.setJsTemplate("console.log('hi');");
        entity.setIsPublic(false);
        entity.setIsActive(true);
        entity = interfaceRepository.save(entity);
        interfaceId = entity.getId();
    }

    @Test
    void shouldCreateSnapshot() throws Exception {
        String body = String.format(
                "{\"interface_id\":\"%s\",\"workflow_run_id\":\"%s\"}", interfaceId, workflowRunId);

        mockMvc.perform(post(INTERNAL_API + "/snapshots")
                        .header("X-User-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Snapshot Test"))
                .andExpect(jsonPath("$.htmlTemplate").value("<div>{{greeting}}</div>"))
                .andExpect(jsonPath("$.interfaceId").value(interfaceId.toString()))
                .andExpect(jsonPath("$.workflowRunId").value(workflowRunId.toString()));
    }

    @Test
    void shouldBeIdempotent() throws Exception {
        String body = String.format(
                "{\"interface_id\":\"%s\",\"workflow_run_id\":\"%s\"}", interfaceId, workflowRunId);

        // First call
        mockMvc.perform(post(INTERNAL_API + "/snapshots")
                        .header("X-User-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Second call (idempotent)
        mockMvc.perform(post(INTERNAL_API + "/snapshots")
                        .header("X-User-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Snapshot Test"));
    }

    @Test
    void shouldCreateSnapshotWithMappings() throws Exception {
        String body = String.format(
                "{\"interface_id\":\"%s\",\"workflow_run_id\":\"%s\"," +
                "\"variable_mappings\":{\"greeting\":\"trigger:start.hello\"}," +
                "\"action_mappings\":{\"submit\":\"core:process\"}}",
                interfaceId, workflowRunId);

        mockMvc.perform(post(INTERNAL_API + "/snapshots")
                        .header("X-User-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.variableMappings.greeting").value("trigger:start.hello"))
                .andExpect(jsonPath("$.actionMappings.submit").value("core:process"));
    }

    @Test
    void shouldStripQuotesFromActionMappingKeys() throws Exception {
        String body = String.format(
                "{\"interface_id\":\"%s\",\"workflow_run_id\":\"%s\"," +
                "\"action_mappings\":{\"'submit'\":\"core:process\",\"\\\"cancel\\\"\":\"core:cancel\"}}",
                interfaceId, workflowRunId);

        mockMvc.perform(post(INTERNAL_API + "/snapshots")
                        .header("X-User-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionMappings.submit").value("core:process"))
                .andExpect(jsonPath("$.actionMappings.cancel").value("core:cancel"));
    }

    @Test
    void shouldFindSnapshot() throws Exception {
        // Create snapshot first
        String body = String.format(
                "{\"interface_id\":\"%s\",\"workflow_run_id\":\"%s\"}", interfaceId, workflowRunId);
        mockMvc.perform(post(INTERNAL_API + "/snapshots")
                        .header("X-User-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Find it
        mockMvc.perform(get(INTERNAL_API + "/snapshots/find")
                        .param("interfaceId", interfaceId.toString())
                        .param("workflowRunId", workflowRunId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Snapshot Test"));
    }

    @Test
    void shouldGetSnapshotsForRun() throws Exception {
        // Create snapshot
        String body = String.format(
                "{\"interface_id\":\"%s\",\"workflow_run_id\":\"%s\"}", interfaceId, workflowRunId);
        mockMvc.perform(post(INTERNAL_API + "/snapshots")
                        .header("X-User-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Get for run
        mockMvc.perform(get(INTERNAL_API + "/snapshots/by-run/{runId}", workflowRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    @Test
    void shouldDeleteSnapshotsForRun() throws Exception {
        // Create snapshot
        String body = String.format(
                "{\"interface_id\":\"%s\",\"workflow_run_id\":\"%s\"}", interfaceId, workflowRunId);
        mockMvc.perform(post(INTERNAL_API + "/snapshots")
                        .header("X-User-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // Delete
        mockMvc.perform(delete(INTERNAL_API + "/snapshots/by-run/{runId}", workflowRunId))
                .andExpect(status().isNoContent());

        // Verify deleted
        mockMvc.perform(get(INTERNAL_API + "/snapshots/by-run/{runId}", workflowRunId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void shouldReturn404WhenInterfaceNotFoundForSnapshot() throws Exception {
        UUID fakeId = UUID.randomUUID();
        String body = String.format(
                "{\"interface_id\":\"%s\",\"workflow_run_id\":\"%s\"}", fakeId, workflowRunId);

        mockMvc.perform(post(INTERNAL_API + "/snapshots")
                        .header("X-User-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    /**
     * REGRESSION: Verify snapshot response JSON uses camelCase.
     */
    @Test
    void shouldReturnCamelCaseSnapshotJson() throws Exception {
        String body = String.format(
                "{\"interface_id\":\"%s\",\"workflow_run_id\":\"%s\"," +
                "\"variable_mappings\":{\"x\":\"y\"},\"action_mappings\":{\"a\":\"b\"}}",
                interfaceId, workflowRunId);

        MvcResult result = mockMvc.perform(post(INTERNAL_API + "/snapshots")
                        .header("X-User-ID", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();

        assertThat(json).contains("\"htmlTemplate\"");
        assertThat(json).contains("\"variableMappings\"");
        assertThat(json).contains("\"actionMappings\"");
        assertThat(json).contains("\"workflowRunId\"");
        assertThat(json).contains("\"interfaceId\"");

        assertThat(json).doesNotContain("\"html_template\"");
        assertThat(json).doesNotContain("\"variable_mappings\"");
        assertThat(json).doesNotContain("\"action_mappings\"");
        assertThat(json).doesNotContain("\"workflow_run_id\"");
        assertThat(json).doesNotContain("\"interface_id\"");
    }
}
