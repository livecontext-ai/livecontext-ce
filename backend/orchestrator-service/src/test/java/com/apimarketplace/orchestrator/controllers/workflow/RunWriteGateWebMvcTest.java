package com.apimarketplace.orchestrator.controllers.workflow;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.auth.client.access.OrgAccessGuardImpl;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.controllers.interfaces.InterfaceActionController;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.execution.v2.services.SignalResumeService;
import com.apimarketplace.orchestrator.execution.v2.services.UnifiedSignalService;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.interfaces.InterfaceActionService;
import com.apimarketplace.orchestrator.services.resume.WorkflowResumeService;
import com.apimarketplace.orchestrator.trigger.ReusableTriggerService;
import com.apimarketplace.orchestrator.trigger.TriggerController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end (MockMvc, real {@link OrgAccessGuardImpl}) proof that every endpoint that DRIVES
 * a run binds the {@code X-Organization-Role} header and goes through {@link RunWriteGate}:
 * trigger fires (whose body can also rewrite the plan), step-by-step execution, signal
 * resolution, interface actions, plan edits, cancel and reactivate. Also pins that a cloud
 * share-link visitor (owner identity, NO role header) still fires an application trigger.
 */
@DisplayName("Run write gate - header binding across every run-driving endpoint")
class RunWriteGateWebMvcTest {

    private static final String CALLER = "user-7";
    private static final String OWNER = "owner-1";
    private static final String ORG = "org-7";
    private static final String RUN_ID = "run_<id>";
    private static final UUID WORKFLOW_ID = UUID.fromString("5b0f5c1e-4d3a-4c55-9d7e-3f6f0a2b9c11");

    private AuthClient authClient;
    private WorkflowRunRepository runRepository;
    private WorkflowResumeService resumeService;
    private ReusableTriggerService triggerService;
    private UnifiedSignalService signalService;
    private InterfaceActionService interfaceActionService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        authClient = mock(AuthClient.class);
        OrgAccessGuard guard = new OrgAccessGuardImpl(authClient);
        runRepository = mock(WorkflowRunRepository.class);
        resumeService = mock(WorkflowResumeService.class);
        triggerService = mock(ReusableTriggerService.class);
        signalService = mock(UnifiedSignalService.class);
        interfaceActionService = mock(InterfaceActionService.class);

        WorkflowRunController runController = new WorkflowRunController();
        ReflectionTestUtils.setField(runController, "workflowRunRepository", runRepository);
        ReflectionTestUtils.setField(runController, "resumeService", resumeService);
        ReflectionTestUtils.setField(runController, "orgAccessGuard", guard);

        StepByStepController sbsController = new StepByStepController();
        ReflectionTestUtils.setField(sbsController, "runRepository", runRepository);
        ReflectionTestUtils.setField(sbsController, "resumeService", resumeService);
        ReflectionTestUtils.setField(sbsController, "orgAccessGuard", guard);

        mockMvc = MockMvcBuilders.standaloneSetup(
                runController,
                sbsController,
                new TriggerController(runRepository, triggerService, resumeService, guard),
                new WorkflowSignalController(signalService, mock(SignalResumeService.class), runRepository, guard),
                new InterfaceActionController(signalService, interfaceActionService, runRepository,
                        mock(InterfaceClient.class), guard))
                .build();

        WorkflowEntity workflow = new WorkflowEntity();
        workflow.setId(WORKFLOW_ID);
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setWorkflow(workflow);
        run.setRunIdPublic(RUN_ID);
        run.setTenantId(OWNER);
        run.setOrganizationId(ORG);
        run.setStatus(RunStatus.WAITING_TRIGGER);
        run.setPublicationId("pub-token-1");
        when(runRepository.findByRunIdPublic(RUN_ID)).thenReturn(Optional.of(run));
        when(signalService.getActiveSignals(RUN_ID)).thenReturn(List.of());
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, String role) {
        b.header("X-User-ID", CALLER).header("X-Organization-ID", ORG).contentType(MediaType.APPLICATION_JSON);
        return role == null ? b : b.header("X-Organization-Role", role);
    }

    private static final String PLAN_BODY = "{\"plan\":{\"triggers\":[],\"edges\":[]}}";

    @Nested
    @DisplayName("the VIEWER role header is refused on every run-driving endpoint")
    class Viewer {

        @Test
        @DisplayName("trigger fires (manual, chat, form, datasource, specific) with a plan body are 403, nothing runs")
        void triggerFires() throws Exception {
            for (String path : List.of("/api/v2/workflows/runs/" + RUN_ID + "/trigger/manual",
                    "/api/v2/workflows/runs/" + RUN_ID + "/trigger/datasource",
                    "/api/v2/workflows/runs/" + RUN_ID + "/trigger/manual/trigger:start")) {
                mockMvc.perform(as(post(path), "VIEWER").content(PLAN_BODY)).andExpect(status().isForbidden());
            }
            mockMvc.perform(as(post("/api/v2/workflows/runs/" + RUN_ID + "/trigger/chat"), "VIEWER")
                    .content("{\"message\":\"hi\"}")).andExpect(status().isForbidden());
            mockMvc.perform(as(post("/api/v2/workflows/runs/" + RUN_ID + "/trigger/form"), "VIEWER")
                    .content("{\"a\":1}")).andExpect(status().isForbidden());
            verify(resumeService, never()).updateRunPlan(anyString(), any());
            verifyNoInteractions(triggerService);
        }

        @Test
        @DisplayName("step-by-step execution, mode switch and start are 403")
        void stepByStep() throws Exception {
            mockMvc.perform(as(post("/api/v2/workflows/dag/runs/" + RUN_ID + "/step/mcp:a/execute"), "VIEWER"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(as(post("/api/v2/workflows/dag/runs/" + RUN_ID + "/execution-mode"), "VIEWER")
                    .content("{\"mode\":\"automatic\"}")).andExpect(status().isForbidden());
            mockMvc.perform(as(post("/api/v2/workflows/dag/runs/" + RUN_ID + "/start-step-by-step"), "VIEWER")
                    .content(PLAN_BODY)).andExpect(status().isForbidden());
            mockMvc.perform(as(post("/api/v2/workflows/dag/runs/" + RUN_ID + "/step-by-step/mcp:a/execute"), "VIEWER")
                    .content("{}")).andExpect(status().isForbidden());
            verifyNoInteractions(resumeService);
        }

        @Test
        @DisplayName("signal resolve / resolve-all / cancel and interface fire are 403")
        void signalsAndInterfaces() throws Exception {
            String base = "/api/v2/workflows/dag/runs/" + RUN_ID;
            mockMvc.perform(as(post(base + "/signals/core:ok/resolve"), "VIEWER")
                    .content("{\"resolution\":\"APPROVED\"}")).andExpect(status().isForbidden());
            mockMvc.perform(as(post(base + "/signals/core:ok/resolve-all"), "VIEWER")
                    .content("{\"resolution\":\"APPROVED\"}")).andExpect(status().isForbidden());
            mockMvc.perform(as(delete(base + "/signals/core:ok"), "VIEWER")).andExpect(status().isForbidden());
            mockMvc.perform(as(post(base + "/interface-actions/interface:f/fire"), "VIEWER")
                    .content("{\"actionKey\":\"go\"}")).andExpect(status().isForbidden());
            verifyNoInteractions(signalService, interfaceActionService);
        }

        @Test
        @DisplayName("PUT /runs/{id}/plan is 403")
        void planEdit() throws Exception {
            mockMvc.perform(as(put("/api/v2/workflows/dag/runs/" + RUN_ID + "/plan"), "VIEWER").content(PLAN_BODY))
                    .andExpect(status().isForbidden());
            verifyNoInteractions(resumeService);
        }
    }

    @Nested
    @DisplayName("the per-member deny-list on the run's workflow applies too")
    class DenyList {

        @BeforeEach
        void restricted() {
            when(authClient.getWriteRestrictedResourceIds(ORG, CALLER, "workflow"))
                    .thenReturn(Set.of(WORKFLOW_ID.toString()));
        }

        @Test
        @DisplayName("cancel and reactivate by a restricted MEMBER are 403")
        void cancelAndReactivate() throws Exception {
            String base = "/api/v2/workflows/dag/runs/" + RUN_ID;
            mockMvc.perform(as(post(base + "/cancel"), "MEMBER")).andExpect(status().isForbidden());
            mockMvc.perform(as(post(base + "/reactivate"), "MEMBER")).andExpect(status().isForbidden());
            verifyNoInteractions(resumeService);
        }

        @Test
        @DisplayName("a trigger fire and a signal resolve by a restricted MEMBER are 403")
        void triggerAndSignal() throws Exception {
            mockMvc.perform(as(post("/api/v2/workflows/runs/" + RUN_ID + "/trigger/manual"), "MEMBER"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(as(post("/api/v2/workflows/dag/runs/" + RUN_ID + "/signals/core:ok/resolve"), "MEMBER")
                    .content("{\"resolution\":\"APPROVED\"}")).andExpect(status().isForbidden());
            mockMvc.perform(as(post("/api/v2/workflows/dag/runs/" + RUN_ID + "/interface-actions/interface:f/fire"),
                    "MEMBER").content("{\"actionKey\":\"go\"}")).andExpect(status().isForbidden());
            verifyNoInteractions(triggerService, interfaceActionService);
        }
    }

    @Nested
    @DisplayName("an auth-service failure during the deny-list lookup")
    class GuardFailure {

        @BeforeEach
        void authServiceDown() {
            when(authClient.getWriteRestrictedResourceIds(anyString(), anyString(), anyString()))
                    .thenThrow(new IllegalStateException("Failed to query org write restrictions"));
        }

        @Test
        @DisplayName("is a 503 with a neutral message on every endpoint family, never the internal text, nothing runs")
        void mapsTo503() throws Exception {
            String base = "/api/v2/workflows/dag/runs/" + RUN_ID;
            List<MockHttpServletRequestBuilder> calls = List.of(
                    as(post(base + "/signals/core:ok/resolve"), "MEMBER").content("{\"resolution\":\"APPROVED\"}"),
                    as(post("/api/v2/workflows/runs/" + RUN_ID + "/trigger/manual"), "MEMBER"),
                    as(post(base + "/step/mcp:a/execute"), "MEMBER"),
                    as(post(base + "/interface-actions/interface:f/fire"), "MEMBER").content("{\"actionKey\":\"go\"}"),
                    as(post(base + "/cancel"), "MEMBER"));
            for (MockHttpServletRequestBuilder call : calls) {
                mockMvc.perform(call)
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                                .contains("temporarily unavailable")
                                .doesNotContain("Failed to query"));
            }
            verifyNoInteractions(triggerService, interfaceActionService);
            verify(signalService, never()).getActiveSignals(RUN_ID);
        }

        @Test
        @DisplayName("a share visitor (X-Share-Context) skips the lookup entirely and is not refused")
        void shareVisitorSkipsLookup() throws Exception {
            // Same request as shareVisitorStillFires, but with auth-service down: before the
            // skip, the lookup ran for the owner identity and its failure refused the visitor.
            mockMvc.perform(post("/api/v2/workflows/runs/" + RUN_ID + "/trigger/manual")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("X-User-ID", OWNER)
                            .header("X-Organization-ID", ORG)
                            .header("X-Share-Context", "true")
                            .header("X-Share-Resource-Type", "APPLICATION")
                            .header("X-Share-Resource-Token", "pub-token-1"))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).isNotIn(403, 503));
            verify(authClient, never()).getWriteRestrictedResourceIds(anyString(), anyString(), anyString());
        }
    }

    @Test
    @DisplayName("an unrestricted MEMBER goes past the gate (signal lookup reached, no 403)")
    void memberPasses() throws Exception {
        when(authClient.getWriteRestrictedResourceIds(ORG, CALLER, "workflow")).thenReturn(Set.of());

        mockMvc.perform(as(post("/api/v2/workflows/dag/runs/" + RUN_ID + "/signals/core:ok/resolve"), "MEMBER")
                        .content("{\"resolution\":\"APPROVED\"}"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isNotEqualTo(403));
        verify(signalService).getActiveSignals(RUN_ID);
    }

    @Test
    @DisplayName("cloud share visitor (owner identity, NO role header) still fires an application trigger")
    void shareVisitorStillFires() throws Exception {
        when(authClient.getWriteRestrictedResourceIds(ORG, OWNER, "workflow")).thenReturn(Set.of());

        mockMvc.perform(post("/api/v2/workflows/runs/" + RUN_ID + "/trigger/manual")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-ID", OWNER)
                        .header("X-Organization-ID", ORG)
                        .header("X-Share-Context", "true")
                        .header("X-Share-Resource-Type", "APPLICATION")
                        .header("X-Share-Resource-Token", "pub-token-1"))
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isNotEqualTo(403));
    }
}
