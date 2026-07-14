package com.apimarketplace.orchestrator.tools.workflow;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.ExecutionMode;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunSummaryProjection;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPinService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.orchestrator.tools.application.ApplicationShowcaseResolver;
import com.apimarketplace.orchestrator.tools.workflow.builder.AgentWorkflowFireService;
import com.apimarketplace.publication.client.PublicationClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WorkflowCrudModule} - runs and get_run actions.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowCrudModule - runs & get_run")
class WorkflowCrudModuleTest {

    @Mock WorkflowManagementService workflowService;
    @Mock WorkflowRunRepository workflowRunRepository;
    @Mock AgentWorkflowFireService agentWorkflowFireService;
    @Mock WorkflowPlanVersionService planVersionService;
    @Mock WorkflowPinService pinService;
    @Mock PublicationClient publicationClient;
    @Mock com.apimarketplace.credential.client.CredentialClient credentialClient;
    @Mock com.apimarketplace.orchestrator.repository.WorkflowRepository workflowRepository;
    @Mock com.apimarketplace.orchestrator.tools.utility.AgentCancellationProbe cancellationProbe;

    private WorkflowCrudModule module;
    private static final String TENANT_ID = "tenant-test";

    @BeforeEach
    void setUp() {
        module = new WorkflowCrudModule(workflowService, workflowRunRepository,
                agentWorkflowFireService, planVersionService, pinService, publicationClient,
                credentialClient, workflowRepository, new ApplicationShowcaseResolver(workflowRunRepository),
                cancellationProbe);
    }

    private WorkflowRunSummaryProjection mockProjection(String runId, RunStatus status, int planVersion) {
        WorkflowRunSummaryProjection p = mock(WorkflowRunSummaryProjection.class);
        lenient().when(p.getRunIdPublic()).thenReturn(runId);
        lenient().when(p.getStatus()).thenReturn(status);
        lenient().when(p.getPlanVersion()).thenReturn(planVersion);
        lenient().when(p.getStartedAt()).thenReturn(Instant.parse("2026-03-20T10:00:00Z"));
        lenient().when(p.getEndedAt()).thenReturn(Instant.parse("2026-03-20T10:01:00Z"));
        lenient().when(p.getDurationMs()).thenReturn(60000L);
        lenient().when(p.getTotalNodes()).thenReturn(5);
        lenient().when(p.getExecutionMode()).thenReturn(ExecutionMode.AUTOMATIC);
        return p;
    }

    @Nested
    @DisplayName("publish - application auto-promotion")
    class PublishAutoPromotion {

        private Map<String, Object> ifaceMap(String id, String label, boolean entry) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", id);
            m.put("label", label);
            m.put("isEntryInterface", entry);
            return m;
        }

        private void stubWorkflowPlan(UUID workflowId, List<Map<String, Object>> interfaces) {
            WorkflowEntity wf = mock(WorkflowEntity.class);
            Map<String, Object> plan = new HashMap<>();
            plan.put("interfaces", interfaces);
            lenient().when(wf.getPlan()).thenReturn(plan);
            when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(wf));
        }

        private void stubLatestShowcaseRun(UUID workflowId, String runId) {
            WorkflowRunEntity run = mock(WorkflowRunEntity.class);
            lenient().when(run.getRunIdPublic()).thenReturn(runId);
            lenient().when(run.getStatus()).thenReturn(RunStatus.COMPLETED);
            lenient().when(run.isStepByStepMode()).thenReturn(false);
            lenient().when(run.getSource()).thenReturn("execute");
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDescPageable(eq(workflowId), any()))
                .thenReturn(new PageImpl<>(List.of(run)));
        }

        private ArgumentCaptor<Map<String, Object>> publishCaptor;

        @SuppressWarnings("unchecked")
        private ToolExecutionResult doPublish(Map<String, Object> params) {
            publishCaptor = ArgumentCaptor.forClass(Map.class);
            when(publicationClient.publishWorkflow(publishCaptor.capture(), eq(TENANT_ID), any()))
                .thenReturn(Map.<String, Object>of("id", "pub-1", "status", "ACTIVE"));
            var result = module.execute("publish", params, TENANT_ID, null);
            assertThat(result).isPresent();
            return result.get();
        }

        private Map<String, Object> publishedRequest() {
            return publishCaptor.getValue();
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> responseData(ToolExecutionResult result) {
            return (Map<String, Object>) result.data();
        }

        @Test
        @DisplayName("Workflow with interfaces + no interface_id: auto-selects the entry interface, sets display_mode=APPLICATION, auto-picks the showcase run - and surfaces all of it in the response")
        void autoPromotesInterfaceBearingWorkflow() {
            UUID workflowId = UUID.randomUUID();
            stubWorkflowPlan(workflowId, List.of(
                ifaceMap("iface-a", "Call Placed", false),
                ifaceMap("iface-entry", "Transcript", true)));
            stubLatestShowcaseRun(workflowId, "run-showcase-1");

            ToolExecutionResult result = doPublish(
                Map.of("workflow_id", workflowId.toString(), "title", "AI Phone Caller"));
            assertThat(result.success()).isTrue();

            // The request sent to publication-service became an application.
            Map<String, Object> request = publishedRequest();
            assertThat(request.get("showcaseInterfaceId")).isEqualTo("iface-entry");
            assertThat(request.get("displayMode")).isEqualTo("APPLICATION");
            assertThat(request.get("showcaseRunId")).isEqualTo("run-showcase-1");

            // The agent-readable response surfaces the auto-promotion.
            Map<String, Object> data = responseData(result);
            assertThat(data).containsEntry("display_mode", "APPLICATION");
            assertThat(data).containsEntry("showcase_interface_id", "iface-entry");
            assertThat(data).containsEntry("showcase_run_id", "run-showcase-1");
            assertThat(data.get("message").toString()).contains("Published as an application");
            @SuppressWarnings("unchecked")
            var notes = (List<String>) data.get("auto_application");
            assertThat(String.join(" ", notes)).contains("Transcript").contains("latest successful run");
        }

        @Test
        @DisplayName("Workflow WITHOUT interfaces stays a plain WORKFLOW publication (no showcase, no display_mode) - regression guard")
        void plainWorkflowUnchanged() {
            UUID workflowId = UUID.randomUUID();
            stubWorkflowPlan(workflowId, List.of());

            ToolExecutionResult result = doPublish(
                Map.of("workflow_id", workflowId.toString(), "title", "Nightly ETL"));

            Map<String, Object> request = publishedRequest();
            assertThat(request).doesNotContainKey("showcaseInterfaceId");
            assertThat(request).doesNotContainKey("displayMode");
            assertThat(responseData(result)).doesNotContainKey("display_mode");
            assertThat(responseData(result).get("message").toString()).doesNotContain("Published as an application");
            verify(workflowRunRepository, never())
                .findByWorkflowIdOrderByStartedAtDescPageable(any(), any());
        }

        @Test
        @DisplayName("Unparseable / missing plan falls back to a plain WORKFLOW publication (no auto-promotion)")
        void missingPlanFallsBackToPlainWorkflow() {
            UUID workflowId = UUID.randomUUID();
            WorkflowEntity wf = mock(WorkflowEntity.class);
            when(wf.getPlan()).thenReturn(null); // no saved plan -> loadPlanForShowcase returns null
            when(workflowRepository.findById(workflowId)).thenReturn(Optional.of(wf));

            ToolExecutionResult result = doPublish(
                Map.of("workflow_id", workflowId.toString(), "title", "Draft"));

            assertThat(result.success()).isTrue();
            assertThat(publishedRequest()).doesNotContainKey("showcaseInterfaceId");
            assertThat(publishedRequest()).doesNotContainKey("displayMode");
            verify(workflowRunRepository, never())
                .findByWorkflowIdOrderByStartedAtDescPageable(any(), any());
        }

        @Test
        @DisplayName("Explicit interface_id is respected, auto-resolves the run, flags display_mode=APPLICATION, and skips entry auto-detect")
        void explicitInterfaceIdPromotesToApplication() {
            UUID workflowId = UUID.randomUUID();
            stubLatestShowcaseRun(workflowId, "run-showcase-2");

            doPublish(Map.of(
                "workflow_id", workflowId.toString(), "title", "Picked",
                "interface_id", "explicit-iface"));

            Map<String, Object> request = publishedRequest();
            assertThat(request.get("showcaseInterfaceId")).isEqualTo("explicit-iface");
            assertThat(request.get("displayMode")).isEqualTo("APPLICATION");
            assertThat(request.get("showcaseRunId")).isEqualTo("run-showcase-2"); // auto-resolved
            verify(workflowRepository, never()).findById(any()); // no entry auto-detect
        }

        @Test
        @DisplayName("Explicit interface_id AND showcase_run_id are both passed through verbatim - no auto-resolution overrides them")
        void explicitInterfaceAndRunPassthrough() {
            UUID workflowId = UUID.randomUUID();

            doPublish(Map.of(
                "workflow_id", workflowId.toString(), "title", "Pinned",
                "interface_id", "explicit-iface", "showcase_run_id", "run-pinned"));

            Map<String, Object> request = publishedRequest();
            assertThat(request.get("showcaseInterfaceId")).isEqualTo("explicit-iface");
            assertThat(request.get("showcaseRunId")).isEqualTo("run-pinned");
            assertThat(request.get("displayMode")).isEqualTo("APPLICATION");
            verify(workflowRepository, never()).findById(any());
            verify(workflowRunRepository, never())
                .findByWorkflowIdOrderByStartedAtDescPageable(any(), any());
        }

        @Test
        @DisplayName("Interface-bearing workflow with no showcaseable run: still an application, but warns the preview is empty until a run exists")
        void appWithoutRunWarns() {
            UUID workflowId = UUID.randomUUID();
            stubWorkflowPlan(workflowId, List.of(ifaceMap("iface-only", "Dashboard", true)));
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDescPageable(eq(workflowId), any()))
                .thenReturn(new PageImpl<>(List.of()));

            ToolExecutionResult result = doPublish(
                Map.of("workflow_id", workflowId.toString(), "title", "Runless"));
            assertThat(result.success()).isTrue();

            Map<String, Object> request = publishedRequest();
            assertThat(request.get("showcaseInterfaceId")).isEqualTo("iface-only");
            assertThat(request.get("displayMode")).isEqualTo("APPLICATION");
            assertThat(request).doesNotContainKey("showcaseRunId");

            @SuppressWarnings("unchecked")
            var notes = (List<String>) responseData(result).get("auto_application");
            assertThat(notes).isNotEmpty();
            assertThat(String.join(" ", notes)).contains("No successful run");
        }
    }

    @Nested
    @DisplayName("executeRuns")
    class RunsTests {

        /** Stubs getWorkflow so the tenant/org scope check passes for the caller's TENANT_ID. */
        private void stubInScopeWorkflow(UUID workflowId) {
            WorkflowEntity wf = new WorkflowEntity();
            wf.setId(workflowId);
            wf.setTenantId(TENANT_ID);
            when(workflowService.getWorkflow(workflowId)).thenReturn(Optional.of(wf));
        }

        @Test
        @DisplayName("cross-tenant: runs for a workflow owned by another tenant returns WORKFLOW_NOT_FOUND (IDOR fix)")
        void runs_crossTenant_notFound() {
            UUID workflowId = UUID.randomUUID();
            WorkflowEntity foreign = new WorkflowEntity();
            foreign.setId(workflowId);
            foreign.setTenantId("other-tenant");
            when(workflowService.getWorkflow(workflowId)).thenReturn(Optional.of(foreign));

            var result = module.execute("runs",
                    Map.of("workflow_id", workflowId.toString()), TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("Workflow not found");
            // Must NOT read the foreign tenant's run history.
            verify(workflowRunRepository, never()).findRunSummariesByWorkflowId(any(), any());
        }

        @Test
        @DisplayName("unknown workflow: runs returns WORKFLOW_NOT_FOUND without touching run history")
        void runs_unknownWorkflow_notFound() {
            UUID workflowId = UUID.randomUUID();
            when(workflowService.getWorkflow(workflowId)).thenReturn(Optional.empty());

            var result = module.execute("runs",
                    Map.of("workflow_id", workflowId.toString()), TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            verify(workflowRunRepository, never()).findRunSummariesByWorkflowId(any(), any());
        }

        @Test
        @DisplayName("allow-list: runs on a workflow_id NOT in allowedWorkflowIds is denied (run-history read leak)")
        void runs_outOfAllowList_denied() {
            ToolExecutionContext ctx = new ToolExecutionContext(TENANT_ID,
                    Map.of("allowedWorkflowIds", List.of(UUID.randomUUID().toString())),
                    Map.of(), java.util.Set.of(), null, null, null, null);

            var result = module.execute("runs",
                    Map.of("workflow_id", UUID.randomUUID().toString()), TENANT_ID, ctx);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("approved workflow list");
            verify(workflowRunRepository, never()).findRunSummariesByWorkflowId(any(), any());
        }

        @Test
        @DisplayName("returns paged results with mapped fields")
        void runs_returnsPagedResults() {
            UUID workflowId = UUID.randomUUID();
            stubInScopeWorkflow(workflowId);
            var p1 = mockProjection("run-1", RunStatus.COMPLETED, 1);
            var p2 = mockProjection("run-2", RunStatus.WAITING_TRIGGER, 2);

            Page<WorkflowRunSummaryProjection> page = new PageImpl<>(
                    List.of(p1, p2), PageRequest.of(0, 20), 2);
            when(workflowRunRepository.findRunSummariesByWorkflowId(eq(workflowId), any()))
                    .thenReturn(page);

            var result = module.execute("runs",
                    Map.of("workflow_id", workflowId.toString()), TENANT_ID, null);

            assertThat(result).isPresent();
            ToolExecutionResult r = result.get();
            assertThat(r.success()).isTrue();

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) r.data();
            @SuppressWarnings("unchecked")
            var runs = (List<Map<String, Object>>) data.get("runs");
            assertThat(runs).hasSize(2);
            assertThat(runs.get(0).get("run_id")).isEqualTo("run-1");
            assertThat(runs.get(0).get("status")).isEqualTo("COMPLETED");
            assertThat(runs.get(0).get("plan_version")).isEqualTo(1);
            assertThat(runs.get(0).get("duration_ms")).isEqualTo(60000L);
            assertThat(runs.get(0).get("total_nodes")).isEqualTo(5);
            assertThat(runs.get(0).get("execution_mode")).isEqualTo("AUTOMATIC");
            assertThat(data.get("total")).isEqualTo(2L);
        }

        @Test
        @DisplayName("fails without workflow_id")
        void runs_failsWithoutWorkflowId() {
            var result = module.execute("runs", Map.of(), TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
        }

        @Test
        @DisplayName("returns empty list when no runs exist")
        void runs_emptyWhenNoRuns() {
            UUID workflowId = UUID.randomUUID();
            stubInScopeWorkflow(workflowId);
            Page<WorkflowRunSummaryProjection> emptyPage = new PageImpl<>(
                    List.of(), PageRequest.of(0, 20), 0);
            when(workflowRunRepository.findRunSummariesByWorkflowId(eq(workflowId), any()))
                    .thenReturn(emptyPage);

            var result = module.execute("runs",
                    Map.of("workflow_id", workflowId.toString()), TENANT_ID, null);

            assertThat(result).isPresent();
            ToolExecutionResult r = result.get();
            assertThat(r.success()).isTrue();

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) r.data();
            @SuppressWarnings("unchecked")
            var runs = (List<Map<String, Object>>) data.get("runs");
            assertThat(runs).isEmpty();
            assertThat(data.get("total")).isEqualTo(0L);
        }

        @Test
        @DisplayName("honors arbitrary (non-aligned) offset - regression on the silent-snap bug")
        @SuppressWarnings("unchecked")
        void runs_honorsArbitraryOffset() {
            // The 2026-05-15 audit found `PageRequest.of(0, 20)` hardcoded - limit/offset
            // params were ignored entirely. Now the helper threads them through an
            // OffsetLimitPageable that overrides getOffset() so Hibernate calls
            // setFirstResult(33) with the agent's literal value, not the page-aligned 25.
            UUID workflowId = UUID.randomUUID();
            stubInScopeWorkflow(workflowId);
            var p1 = mockProjection("run-x", RunStatus.COMPLETED, 1);

            ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
            when(workflowRunRepository.findRunSummariesByWorkflowId(
                    eq(workflowId), pageableCaptor.capture()))
                .thenReturn(new PageImpl<>(List.of(p1), PageRequest.of(0, 10), 50));

            module.execute("runs",
                    Map.of("workflow_id", workflowId.toString(), "offset", 33, "limit", 10),
                    TENANT_ID, null);

            Pageable captured = pageableCaptor.getValue();
            // Stock PageRequest.of(33/10, 10) would compute offset=30 here (silent snap).
            // With OffsetLimitPageable, getOffset() returns 33 verbatim.
            assertThat(captured.getOffset()).isEqualTo(33L);
            assertThat(captured.getPageSize()).isEqualTo(10);
        }

        @Test
        @DisplayName("emits canonical envelope keys (kind/offset/limit/hint) via AgentListEnvelope")
        @SuppressWarnings("unchecked")
        void runs_emitsCanonicalEnvelope() {
            UUID workflowId = UUID.randomUUID();
            stubInScopeWorkflow(workflowId);
            var p1 = mockProjection("r", RunStatus.COMPLETED, 1);
            when(workflowRunRepository.findRunSummariesByWorkflowId(eq(workflowId), any()))
                .thenReturn(new PageImpl<>(List.of(p1), PageRequest.of(0, 20), 1));

            var result = module.execute("runs",
                    Map.of("workflow_id", workflowId.toString()), TENANT_ID, null);

            Map<String, Object> data = (Map<String, Object>) result.get().data();
            assertThat(data).containsKeys("status", "kind", "runs", "count", "total",
                    "offset", "limit", "hasMore");
            assertThat(data.get("kind")).isEqualTo("runs");
            // Per-action context still surfaces alongside the envelope.
            assertThat(data).containsKeys("workflowId", "pinned_version", "is_production",
                    "latest_version");
        }

        @Test
        @DisplayName("caps limit at 100 (LARGE.maxLimit) - workflow.runs has the highest cap of the 3 buckets")
        void runs_capsAtLargeMax() {
            UUID workflowId = UUID.randomUUID();
            stubInScopeWorkflow(workflowId);
            ArgumentCaptor<Pageable> cap = ArgumentCaptor.forClass(Pageable.class);
            when(workflowRunRepository.findRunSummariesByWorkflowId(
                    eq(workflowId), cap.capture()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

            module.execute("runs",
                    Map.of("workflow_id", workflowId.toString(), "limit", 999),
                    TENANT_ID, null);

            assertThat(cap.getValue().getPageSize()).isEqualTo(100);  // clamped from 999
        }
    }

    @Nested
    @DisplayName("executeGetRun")
    class GetRunTests {

        @Test
        @DisplayName("allow-list: get_run for a run whose workflow is NOT in allowedWorkflowIds is denied")
        void getRun_outOfAllowList_denied() {
            String runId = "run-restricted";
            WorkflowEntity workflow = new WorkflowEntity();
            workflow.setId(UUID.randomUUID());
            workflow.setTenantId(TENANT_ID);
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.setRunIdPublic(runId);
            run.setTenantId(TENANT_ID);
            run.setWorkflow(workflow);
            when(workflowRunRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(run));

            ToolExecutionContext ctx = new ToolExecutionContext(TENANT_ID,
                    Map.of("allowedWorkflowIds", List.of(UUID.randomUUID().toString())),
                    Map.of(), java.util.Set.of(), null, null, null, null);

            var result = module.execute("get_run", Map.of("run_id", runId), TENANT_ID, ctx);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("approved workflow list");
            verifyNoInteractions(agentWorkflowFireService);
        }

        @Test
        @DisplayName("returns macro report when no epoch param")
        void getRun_returnsFullReport() {
            String runId = "run-abc-123";
            WorkflowEntity workflow = new WorkflowEntity();
            workflow.setId(UUID.randomUUID());
            workflow.setTenantId(TENANT_ID);
            workflow.setPlan(Map.of("triggers", List.of()));

            WorkflowRunEntity run = new WorkflowRunEntity();
            run.setRunIdPublic(runId);
            run.setTenantId(TENANT_ID);
            run.setStatus(RunStatus.COMPLETED);
            run.setPlanVersion(3);
            run.setWorkflow(workflow);

            when(workflowRunRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(run));

            // Mock the macro report from AgentWorkflowFireService
            Map<String, Object> expectedReport = new LinkedHashMap<>();
            expectedReport.put("run_id", runId);
            expectedReport.put("status", "COMPLETED");
            expectedReport.put("total_epochs", 1);
            when(agentWorkflowFireService.buildRunMacroReport(eq(run), any(), eq(TENANT_ID)))
                    .thenReturn(expectedReport);

            var result = module.execute("get_run",
                    Map.of("run_id", runId), TENANT_ID, null);

            assertThat(result).isPresent();
            ToolExecutionResult r = result.get();
            assertThat(r.success()).isTrue();

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) r.data();
            assertThat(data.get("run_id")).isEqualTo(runId);
            assertThat(data.get("status")).isEqualTo("COMPLETED");
            verify(agentWorkflowFireService).buildRunMacroReport(eq(run), any(), eq(TENANT_ID));
            verify(agentWorkflowFireService, never()).buildEpochDetailReport(any(), any(), anyInt(), any());
        }

        @Test
        @DisplayName("returns epoch detail report when epoch param provided")
        void getRun_withEpoch_callsDetailReport() {
            String runId = "run-epoch-detail";
            WorkflowEntity workflow = new WorkflowEntity();
            workflow.setId(UUID.randomUUID());
            workflow.setTenantId(TENANT_ID);
            workflow.setPlan(Map.of("triggers", List.of()));

            WorkflowRunEntity run = new WorkflowRunEntity();
            run.setRunIdPublic(runId);
            run.setTenantId(TENANT_ID);
            run.setStatus(RunStatus.WAITING_TRIGGER);
            run.setPlanVersion(2);
            run.setWorkflow(workflow);

            when(workflowRunRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(run));

            Map<String, Object> detailReport = new LinkedHashMap<>();
            detailReport.put("run_id", runId);
            detailReport.put("epoch", 2);
            detailReport.put("outputs", List.of(Map.of("node_id", "mcp:final", "status", "COMPLETED")));
            when(agentWorkflowFireService.buildEpochDetailReport(eq(run), any(), eq(2), eq(TENANT_ID)))
                    .thenReturn(detailReport);

            var result = module.execute("get_run",
                    Map.of("run_id", runId, "epoch", 2), TENANT_ID, null);

            assertThat(result).isPresent();
            ToolExecutionResult r = result.get();
            assertThat(r.success()).isTrue();

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) r.data();
            assertThat(data.get("epoch")).isEqualTo(2);
            verify(agentWorkflowFireService).buildEpochDetailReport(eq(run), any(), eq(2), eq(TENANT_ID));
            verify(agentWorkflowFireService, never()).buildRunMacroReport(any(), any(), any());
        }

        @Test
        @DisplayName("fails without run_id")
        void getRun_failsWithoutRunId() {
            var result = module.execute("get_run", Map.of(), TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("run_id is required");
        }

        @Test
        @DisplayName("fails when run not found")
        void getRun_failsWhenNotFound() {
            when(workflowRunRepository.findByRunIdPublic("nonexistent")).thenReturn(Optional.empty());

            var result = module.execute("get_run",
                    Map.of("run_id", "nonexistent"), TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("Run not found");
        }

        @Test
        @DisplayName("fails when tenant mismatch")
        void getRun_failsWhenTenantMismatch() {
            WorkflowRunEntity run = new WorkflowRunEntity();
            run.setRunIdPublic("run-123");
            run.setTenantId("other-tenant");

            when(workflowRunRepository.findByRunIdPublic("run-123")).thenReturn(Optional.of(run));

            var result = module.execute("get_run",
                    Map.of("run_id", "run-123"), TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("Run not found");
        }

        @Test
        @DisplayName("epoch param accepts integer 0")
        void getRun_withEpochZero() {
            String runId = "run-epoch-zero";
            WorkflowEntity workflow = new WorkflowEntity();
            workflow.setId(UUID.randomUUID());
            workflow.setTenantId(TENANT_ID);
            workflow.setPlan(Map.of("triggers", List.of()));

            WorkflowRunEntity run = new WorkflowRunEntity();
            run.setRunIdPublic(runId);
            run.setTenantId(TENANT_ID);
            run.setStatus(RunStatus.COMPLETED);
            run.setPlanVersion(1);
            run.setWorkflow(workflow);

            when(workflowRunRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(run));
            when(agentWorkflowFireService.buildEpochDetailReport(eq(run), any(), eq(0), eq(TENANT_ID)))
                    .thenReturn(Map.of("run_id", runId, "epoch", 0));

            var result = module.execute("get_run",
                    Map.of("run_id", runId, "epoch", 0), TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            verify(agentWorkflowFireService).buildEpochDetailReport(eq(run), any(), eq(0), eq(TENANT_ID));
        }

        @Test
        @DisplayName("uses versioned plan when available")
        void getRun_usesVersionedPlan() {
            String runId = "run-versioned";
            UUID workflowId = UUID.randomUUID();
            WorkflowEntity workflow = new WorkflowEntity();
            workflow.setId(workflowId);
            workflow.setTenantId(TENANT_ID);
            workflow.setPlan(Map.of("triggers", List.of()));

            WorkflowRunEntity run = new WorkflowRunEntity();
            run.setRunIdPublic(runId);
            run.setTenantId(TENANT_ID);
            run.setStatus(RunStatus.COMPLETED);
            run.setPlanVersion(5);
            run.setWorkflow(workflow);

            when(workflowRunRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(run));

            // Mock versioned plan
            var versionEntity = mock(com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity.class);
            when(versionEntity.getPlan()).thenReturn(Map.of("triggers", List.of(), "mcps", List.of()));
            when(planVersionService.getVersion(workflowId, 5)).thenReturn(Optional.of(versionEntity));

            when(agentWorkflowFireService.buildRunMacroReport(eq(run), any(), eq(TENANT_ID)))
                    .thenReturn(Map.of("run_id", runId, "status", "COMPLETED"));

            var result = module.execute("get_run",
                    Map.of("run_id", runId), TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();

            // Verify versioned plan was looked up
            verify(planVersionService).getVersion(workflowId, 5);
        }
    }

    @Nested
    @DisplayName("executeGetNodeOutput")
    class GetNodeOutputTests {

        private WorkflowRunEntity buildRun(String runId, String tenantId) {
            WorkflowEntity workflow = new WorkflowEntity();
            workflow.setId(UUID.randomUUID());
            workflow.setTenantId(tenantId);
            workflow.setPlan(Map.of("triggers", List.of()));

            WorkflowRunEntity run = new WorkflowRunEntity();
            run.setRunIdPublic(runId);
            run.setTenantId(tenantId);
            run.setStatus(RunStatus.COMPLETED);
            run.setPlanVersion(1);
            run.setWorkflow(workflow);
            return run;
        }

        @Test
        @DisplayName("allow-list: get_node_output for a run whose workflow is NOT in allowedWorkflowIds is denied")
        void getNodeOutput_outOfAllowList_denied() {
            String runId = "run-restricted-node";
            WorkflowRunEntity run = buildRun(runId, TENANT_ID);
            when(workflowRunRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(run));

            ToolExecutionContext ctx = new ToolExecutionContext(TENANT_ID,
                    Map.of("allowedWorkflowIds", List.of(UUID.randomUUID().toString())),
                    Map.of(), java.util.Set.of(), null, null, null, null);

            var result = module.execute("get_node_output",
                    Map.of("run_id", runId, "epoch", 0, "node_id", "mcp:step"), TENANT_ID, ctx);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("approved workflow list");
            verifyNoInteractions(agentWorkflowFireService);
        }

        @Test
        @DisplayName("delegates to AgentWorkflowFireService.buildNodeOutputReport")
        void getNodeOutput_delegates() {
            String runId = "run-node-output";
            WorkflowRunEntity run = buildRun(runId, TENANT_ID);
            when(workflowRunRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(run));

            Map<String, Object> nodeReport = new LinkedHashMap<>();
            nodeReport.put("run_id", runId);
            nodeReport.put("node_id", "mcp:step");
            nodeReport.put("status", "COMPLETED");
            nodeReport.put("output", Map.of("result", "success"));
            // Module now delegates to the split-aware 8-arg overload - the
            // last three params (item_index / iteration / spawn) are forwarded
            // from the request and may be null when the agent doesn't target a
            // specific row.
            when(agentWorkflowFireService.buildNodeOutputReport(
                        eq(run), any(), eq(0), eq("mcp:step"), eq(TENANT_ID),
                        isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                    .thenReturn(nodeReport);

            var result = module.execute("get_node_output",
                    Map.of("run_id", runId, "epoch", 0, "node_id", "mcp:step"), TENANT_ID, null);

            assertThat(result).isPresent();
            ToolExecutionResult r = result.get();
            assertThat(r.success()).isTrue();

            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) r.data();
            assertThat(data.get("node_id")).isEqualTo("mcp:step");
            assertThat(data.get("status")).isEqualTo("COMPLETED");
            verify(agentWorkflowFireService).buildNodeOutputReport(
                    eq(run), any(), eq(0), eq("mcp:step"), eq(TENANT_ID),
                    isNull(), isNull(), isNull(), isNull(), isNull(), isNull());
        }

        @Test
        @DisplayName("fails without run_id")
        void getNodeOutput_failsWithoutRunId() {
            var result = module.execute("get_node_output",
                    Map.of("epoch", 0, "node_id", "mcp:step"), TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("run_id is required");
        }

        @Test
        @DisplayName("forwards item_index / iteration / spawn to the service for targeted zoom")
        void getNodeOutput_forwardsItemFilters() {
            String runId = "run-zoom";
            WorkflowRunEntity run = buildRun(runId, TENANT_ID);
            when(workflowRunRepository.findByRunIdPublic(runId)).thenReturn(Optional.of(run));

            Map<String, Object> nodeReport = Map.of("run_id", runId, "node_id", "mcp:step", "item_index", 7);
            when(agentWorkflowFireService.buildNodeOutputReport(
                        eq(run), any(), eq(2), eq("mcp:step"), eq(TENANT_ID),
                        eq(7), eq(3), eq(1), isNull(), isNull(), isNull()))
                    .thenReturn(nodeReport);

            var result = module.execute("get_node_output",
                    Map.of("run_id", runId, "epoch", 2, "node_id", "mcp:step",
                           "item_index", 7, "iteration", 3, "spawn", 1),
                    TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            verify(agentWorkflowFireService).buildNodeOutputReport(
                    eq(run), any(), eq(2), eq("mcp:step"), eq(TENANT_ID),
                    eq(7), eq(3), eq(1), isNull(), isNull(), isNull());
        }

        @Test
        @DisplayName("fails without epoch")
        void getNodeOutput_failsWithoutEpoch() {
            var result = module.execute("get_node_output",
                    Map.of("run_id", "run-1", "node_id", "mcp:step"), TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("epoch is required");
        }

        @Test
        @DisplayName("fails without node_id")
        void getNodeOutput_failsWithoutNodeId() {
            var result = module.execute("get_node_output",
                    Map.of("run_id", "run-1", "epoch", 0), TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("node_id is required");
        }

        @Test
        @DisplayName("fails when run not found")
        void getNodeOutput_failsWhenRunNotFound() {
            when(workflowRunRepository.findByRunIdPublic("nonexistent")).thenReturn(Optional.empty());

            var result = module.execute("get_node_output",
                    Map.of("run_id", "nonexistent", "epoch", 0, "node_id", "mcp:step"), TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("Run not found");
        }

        @Test
        @DisplayName("fails when tenant mismatch")
        void getNodeOutput_failsTenantMismatch() {
            WorkflowRunEntity run = buildRun("run-1", "other-tenant");
            when(workflowRunRepository.findByRunIdPublic("run-1")).thenReturn(Optional.of(run));

            var result = module.execute("get_node_output",
                    Map.of("run_id", "run-1", "epoch", 0, "node_id", "mcp:step"), TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("Run not found");
            // The 5-arg overload is delegate-only; the module always invokes the
            // 8-arg variant. Use verifyNoInteractions to assert "no service call
            // at all on tenant mismatch" without coupling to a specific overload.
            verifyNoInteractions(agentWorkflowFireService);
        }

        @Test
        @DisplayName("canHandle returns true for get_node_output")
        void canHandle_getNodeOutput() {
            assertThat(module.canHandle("get_node_output")).isTrue();
        }

        @Test
        @DisplayName("canHandle returns false for unknown action")
        void canHandle_unknownAction() {
            assertThat(module.canHandle("unknown_action")).isFalse();
        }
    }

    @Nested
    @DisplayName("executePin / executeUnpin")
    class PinUnpinTests {

        @Test
        @DisplayName("canHandle routes pin and unpin")
        void canHandle_pinUnpin() {
            assertThat(module.canHandle("pin")).isTrue();
            assertThat(module.canHandle("unpin")).isTrue();
        }

        @Test
        @DisplayName("pin fails without workflow_id")
        void pin_requiresWorkflowId() {
            var result = module.execute("pin", Map.of("version", 2), TENANT_ID, null);
            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("workflow_id");
        }

        @Test
        @DisplayName("pin fails without version (unpin path requires explicit action)")
        void pin_requiresVersion() {
            var result = module.execute("pin",
                    Map.of("workflow_id", UUID.randomUUID().toString()), TENANT_ID, null);
            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("version");
        }

        @Test
        @DisplayName("pin rejects version <= 0")
        void pin_rejectsNonPositiveVersion() {
            var result = module.execute("pin",
                    Map.of("workflow_id", UUID.randomUUID().toString(), "version", 0),
                    TENANT_ID, null);
            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("positive");
        }

        @Test
        @DisplayName("pin delegates to service and maps Success")
        void pin_success() {
            UUID workflowId = UUID.randomUUID();
            when(pinService.pin(eq(workflowId), eq(TENANT_ID), eq((String) null), eq(3)))
                    .thenReturn(new WorkflowPinService.PinResult.Success(3, null));

            var result = module.execute("pin",
                    Map.of("workflow_id", workflowId.toString(), "version", 3),
                    TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) result.get().data();
            assertThat(data.get("pinned_version")).isEqualTo(3);
            assertThat(data.get("is_production")).isEqualTo(true);
            assertThat(data.get("status")).isEqualTo("PINNED");
        }

        @Test
        @DisplayName("pin maps NotFound to workflow-not-found failure")
        void pin_notFound() {
            UUID workflowId = UUID.randomUUID();
            when(pinService.pin(eq(workflowId), eq(TENANT_ID), eq((String) null), eq(1)))
                    .thenReturn(new WorkflowPinService.PinResult.NotFound());

            var result = module.execute("pin",
                    Map.of("workflow_id", workflowId.toString(), "version", 1),
                    TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("not found");
        }

        @Test
        @DisplayName("pin maps VersionNotFound with the offending version")
        void pin_versionNotFound() {
            UUID workflowId = UUID.randomUUID();
            when(pinService.pin(eq(workflowId), eq(TENANT_ID), eq((String) null), eq(9)))
                    .thenReturn(new WorkflowPinService.PinResult.VersionNotFound(9));

            var result = module.execute("pin",
                    Map.of("workflow_id", workflowId.toString(), "version", 9),
                    TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("Version 9");
        }

        @Test
        @DisplayName("pin maps NoSuccessfulRun with guidance to execute first")
        void pin_noSuccessfulRun() {
            UUID workflowId = UUID.randomUUID();
            when(pinService.pin(eq(workflowId), eq(TENANT_ID), eq((String) null), eq(2)))
                    .thenReturn(new WorkflowPinService.PinResult.NoSuccessfulRun(2));

            var result = module.execute("pin",
                    Map.of("workflow_id", workflowId.toString(), "version", 2),
                    TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("execute");
            assertThat(result.get().error()).contains("v2");
        }

        @Test
        @DisplayName("unpin fails without workflow_id")
        void unpin_requiresWorkflowId() {
            var result = module.execute("unpin", Map.of(), TENANT_ID, null);
            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("workflow_id");
        }

        @Test
        @DisplayName("unpin delegates to service with null version and maps Success")
        void unpin_success() {
            UUID workflowId = UUID.randomUUID();
            when(pinService.pin(eq(workflowId), eq(TENANT_ID), eq((String) null), eq((Integer) null)))
                    .thenReturn(new WorkflowPinService.PinResult.Success(null, null));

            var result = module.execute("unpin",
                    Map.of("workflow_id", workflowId.toString()), TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            @SuppressWarnings("unchecked")
            var data = (Map<String, Object>) result.get().data();
            assertThat(data.get("pinned_version")).isNull();
            assertThat(data.get("is_production")).isEqualTo(false);
            assertThat(data.get("status")).isEqualTo("UNPINNED");
        }

        @Test
        @DisplayName("unpin treats Forbidden as not-found (no tenant leak)")
        void unpin_forbiddenHiddenAsNotFound() {
            UUID workflowId = UUID.randomUUID();
            when(pinService.pin(eq(workflowId), eq(TENANT_ID), eq((String) null), eq((Integer) null)))
                    .thenReturn(new WorkflowPinService.PinResult.Forbidden());

            var result = module.execute("unpin",
                    Map.of("workflow_id", workflowId.toString()), TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("not found");
        }

        @Test
        @DisplayName("pin accepts 'id' alias for workflow_id")
        void pin_acceptsIdAlias() {
            UUID workflowId = UUID.randomUUID();
            when(pinService.pin(eq(workflowId), eq(TENANT_ID), eq((String) null), eq(4)))
                    .thenReturn(new WorkflowPinService.PinResult.Success(4, null));

            var result = module.execute("pin",
                    Map.of("id", workflowId.toString(), "version", 4),
                    TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
        }

        @Test
        @DisplayName("pin threads the caller's active org from context to the pin service (org-scoped pin)")
        void pin_passesOrgFromContext() {
            UUID workflowId = UUID.randomUUID();
            String orgId = "org-acme-42";
            ToolExecutionContext ctx = new ToolExecutionContext(
                    TENANT_ID, Map.of(), Map.of(), Set.of(), null, null, orgId, "ADMIN");
            when(pinService.pin(eq(workflowId), eq(TENANT_ID), eq(orgId), eq(5)))
                    .thenReturn(new WorkflowPinService.PinResult.Success(5, null));

            var result = module.execute("pin",
                    Map.of("workflow_id", workflowId.toString(), "version", 5),
                    TENANT_ID, ctx);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            // Regression: before the fix, executePin passed orgId=null via the
            // 3-arg overload, so an org-tagged workflow failed strict scope and
            // was masked as "Workflow not found". Verify the active org reaches
            // the service.
            verify(pinService).pin(eq(workflowId), eq(TENANT_ID), eq(orgId), eq(5));
        }

        @Test
        @DisplayName("unpin threads the caller's active org from context to the pin service")
        void unpin_passesOrgFromContext() {
            UUID workflowId = UUID.randomUUID();
            String orgId = "org-acme-42";
            ToolExecutionContext ctx = new ToolExecutionContext(
                    TENANT_ID, Map.of(), Map.of(), Set.of(), null, null, orgId, "ADMIN");
            when(pinService.pin(eq(workflowId), eq(TENANT_ID), eq(orgId), eq((Integer) null)))
                    .thenReturn(new WorkflowPinService.PinResult.Success(null, null));

            var result = module.execute("unpin",
                    Map.of("workflow_id", workflowId.toString()), TENANT_ID, ctx);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            verify(pinService).pin(eq(workflowId), eq(TENANT_ID), eq(orgId), eq((Integer) null));
        }
    }

    @Nested
    @DisplayName("publish / unpublish - marketplace listing lifecycle")
    class PublishTests {

        private final UUID workflowId = UUID.randomUUID();
        private final UUID interfaceId = UUID.randomUUID();
        private final UUID publicationId = UUID.randomUUID();

        @Test
        @DisplayName("publish echoes server status (PENDING_REVIEW for PUBLIC) + workflowId + showcaseInterfaceId + visibility")
        void publish_happyPath() {
            when(publicationClient.publishWorkflow(any(), eq(TENANT_ID), isNull()))
                    .thenReturn(Map.of("id", publicationId.toString(), "title", "My Wf",
                            "visibility", "PUBLIC", "status", "PENDING_REVIEW"));
            // An explicit interface_id makes this an application, so publish now resolves a showcase
            // run (none available here -> stays unset). Stub the lookup so it returns an empty page.
            when(workflowRunRepository.findByWorkflowIdOrderByStartedAtDescPageable(eq(workflowId), any()))
                    .thenReturn(new PageImpl<>(List.of()));

            var result = module.execute("publish", Map.of(
                    "workflow_id", workflowId.toString(),
                    "title", "My Wf",
                    "interface_id", interfaceId.toString(),
                    "visibility", "public",
                    "credits_per_use", 0
            ), TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            assertThat(data).containsEntry("status", "PENDING_REVIEW");
            assertThat(data.get("message").toString()).contains("submitted for review");
            assertThat(data).containsEntry("publication_id", publicationId.toString());

            org.mockito.ArgumentCaptor<Map<String, Object>> captor = org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(publicationClient).publishWorkflow(captor.capture(), eq(TENANT_ID), isNull());
            assertThat(captor.getValue()).containsEntry("workflowId", workflowId.toString());
            assertThat(captor.getValue()).containsEntry("showcaseInterfaceId", interfaceId.toString());
            // A showcase interface means this listing IS an application.
            assertThat(captor.getValue()).containsEntry("displayMode", "APPLICATION");
            assertThat(captor.getValue()).containsEntry("visibility", "PUBLIC");
            assertThat(captor.getValue()).containsEntry("creditsPerUse", 0);
        }

        @Test
        @DisplayName("publish defaults visibility to PRIVATE and echoes ACTIVE status for non-review visibilities")
        void publish_defaultsVisibility() {
            when(publicationClient.publishWorkflow(any(), eq(TENANT_ID), isNull()))
                    .thenReturn(Map.of("id", publicationId.toString(), "status", "ACTIVE"));

            var result = module.execute("publish", Map.of(
                    "workflow_id", workflowId.toString(),
                    "title", "X"
            ), TENANT_ID, null);

            org.mockito.ArgumentCaptor<Map<String, Object>> captor = org.mockito.ArgumentCaptor.forClass(Map.class);
            verify(publicationClient).publishWorkflow(captor.capture(), eq(TENANT_ID), isNull());
            assertThat(captor.getValue()).containsEntry("visibility", "PRIVATE");
            assertThat(captor.getValue()).doesNotContainKey("showcaseInterfaceId"); // optional for workflow

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.get().data();
            assertThat(data).containsEntry("status", "ACTIVE");
            assertThat(data.get("message").toString()).contains("Workflow published");
        }

        @Test
        @DisplayName("publish rejects invalid visibility before calling publication-service")
        void publish_rejectsInvalidVisibility() {
            var result = module.execute("publish", Map.of(
                    "workflow_id", workflowId.toString(),
                    "title", "X",
                    "visibility", "BOGUS"
            ), TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("Invalid visibility");
            verify(publicationClient, never()).publishWorkflow(any(), any(), any());
        }

        @Test
        @DisplayName("publish fails without workflow_id")
        void publish_missingWorkflowId() {
            var result = module.execute("publish", Map.of("title", "X"), TENANT_ID, null);
            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("workflow_id is required");
        }

        @Test
        @DisplayName("publish fails without title")
        void publish_missingTitle() {
            var result = module.execute("publish",
                    Map.of("workflow_id", workflowId.toString()), TENANT_ID, null);
            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("title is required");
        }

        @Test
        @DisplayName("publish surfaces backend error message")
        void publish_surfacesBackendError() {
            when(publicationClient.publishWorkflow(any(), eq(TENANT_ID), isNull()))
                    .thenThrow(new RuntimeException("Failed to publish workflow: PUBLIC publications must charge credits"));

            var result = module.execute("publish", Map.of(
                    "workflow_id", workflowId.toString(),
                    "title", "X",
                    "visibility", "PUBLIC"
            ), TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("PUBLIC publications must charge credits");
        }

        @Test
        @DisplayName("unpublish checks isWorkflowPublished before delegating")
        void unpublish_happyPath() {
            when(publicationClient.isWorkflowPublished(workflowId, TENANT_ID)).thenReturn(true);

            var result = module.execute("unpublish",
                    Map.of("workflow_id", workflowId.toString()), TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isTrue();
            verify(publicationClient).unpublishByWorkflowId(workflowId, TENANT_ID);
        }

        @Test
        @DisplayName("unpublish returns failure when workflow not published - never calls unpublish")
        void unpublish_notPublished() {
            when(publicationClient.isWorkflowPublished(workflowId, TENANT_ID)).thenReturn(false);

            var result = module.execute("unpublish",
                    Map.of("workflow_id", workflowId.toString()), TENANT_ID, null);

            assertThat(result).isPresent();
            assertThat(result.get().success()).isFalse();
            assertThat(result.get().error()).contains("Resource not published");
            verify(publicationClient, never()).unpublishByWorkflowId(any(), any());
        }
    }

    @Nested
    @DisplayName("executeList - slim + enrich")
    class ListTests {

        private WorkflowEntity workflow(UUID id, String name, String description, Integer pinnedVersion) {
            WorkflowEntity w = new WorkflowEntity();
            w.setId(id);
            w.setTenantId(TENANT_ID);
            w.setName(name);
            w.setDescription(description);
            w.setPinnedVersion(pinnedVersion);
            w.setCreatedAt(Instant.parse("2026-04-01T00:00:00Z"));
            w.setUpdatedAt(Instant.parse("2026-04-02T00:00:00Z"));
            return w;
        }

        private Map<String, Object> planWithTriggers(String... types) {
            List<Map<String, Object>> triggers = new ArrayList<>();
            for (String t : types) {
                triggers.add(Map.of("id", t, "label", t, "type", t,
                        "strategy", "single", "params", Map.of()));
            }
            return Map.of(
                    "id", "wf",
                    "tenantId", TENANT_ID,
                    "triggers", triggers,
                    "tools", List.of(),
                    "edges", List.of()
            );
        }

        @Test
        @DisplayName("query filters workflows by name OR description (case-insensitive) before pagination")
        @SuppressWarnings("unchecked")
        void queryFiltersByNameAndDescription() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            UUID c = UUID.randomUUID();
            when(workflowService.listWorkflows(TENANT_ID, null, null))
                    .thenReturn(List.of(
                            workflow(a, "Invoice Sync", null, null),
                            workflow(b, "Order Export", "handles invoices too", null),
                            workflow(c, "Weather Bot", "forecasts", null)));
            when(planVersionService.getCurrentVersion(any(UUID.class))).thenReturn(1);
            when(publicationClient.findActivePublicationIdsByWorkflowIds(anyList(), eq(TENANT_ID)))
                    .thenReturn(Map.of());
            when(workflowRunRepository.findFirstByWorkflowIdOrderByStartedAtDesc(any(UUID.class)))
                    .thenReturn(Optional.empty());

            Map<String, Object> data = (Map<String, Object>) module
                    .execute("list", Map.of("query", "invoice"), TENANT_ID, null).get().data();
            List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("workflows");
            // "Invoice Sync" matches on name, "Order Export" matches on description;
            // "Weather Bot" matches neither field and is excluded.
            assertThat(items).extracting(m -> m.get("id"))
                    .containsExactlyInAnyOrder(a.toString(), b.toString());
            assertThat(data.get("total")).isEqualTo(2L);
            assertThat(data.get("count")).isEqualTo(2);
        }

        @Test
        @DisplayName("query with no matches returns empty set + broaden hint (total/hasMore reflect the filtered set)")
        @SuppressWarnings("unchecked")
        void queryNoMatchReturnsEmpty() {
            when(workflowService.listWorkflows(TENANT_ID, null, null))
                    .thenReturn(List.of(workflow(UUID.randomUUID(), "Invoice Sync", null, null)));

            Map<String, Object> data = (Map<String, Object>) module
                    .execute("list", Map.of("query", "zzz-no-such"), TENANT_ID, null).get().data();
            assertThat(data.get("count")).isEqualTo(0);
            assertThat(data.get("total")).isEqualTo(0L);
            Map<String, Object> hint = (Map<String, Object>) data.get("hint");
            assertThat(hint.get("action")).isEqualTo("broaden");
        }

        @Test
        @DisplayName("blank/whitespace query is treated as no filter and returns all workflows")
        @SuppressWarnings("unchecked")
        void blankQueryReturnsAll() {
            when(workflowService.listWorkflows(TENANT_ID, null, null))
                    .thenReturn(List.of(workflow(UUID.randomUUID(), "Invoice Sync", null, null),
                                        workflow(UUID.randomUUID(), "Weather Bot", null, null)));
            when(planVersionService.getCurrentVersion(any(UUID.class))).thenReturn(1);
            when(publicationClient.findActivePublicationIdsByWorkflowIds(anyList(), eq(TENANT_ID)))
                    .thenReturn(Map.of());
            when(workflowRunRepository.findFirstByWorkflowIdOrderByStartedAtDesc(any(UUID.class)))
                    .thenReturn(Optional.empty());

            Map<String, Object> data = (Map<String, Object>) module
                    .execute("list", Map.of("query", "   "), TENANT_ID, null).get().data();
            assertThat(data.get("count")).isEqualTo(2);
            assertThat(data.get("total")).isEqualTo(2L);
        }

        @Test
        @DisplayName("drops tenantId from each summary (always equals caller, was noise)")
        @SuppressWarnings("unchecked")
        void dropsTenantIdFromSummary() {
            UUID wfId = UUID.randomUUID();
            WorkflowEntity wf = workflow(wfId, "Wf", null, null);
            when(workflowService.listWorkflows(TENANT_ID, null, null)).thenReturn(List.of(wf));
            when(planVersionService.getCurrentVersion(wfId)).thenReturn(1);
            when(publicationClient.findActivePublicationIdsByWorkflowIds(anyList(), eq(TENANT_ID)))
                    .thenReturn(Map.of());
            when(workflowRunRepository.findFirstByWorkflowIdOrderByStartedAtDesc(wfId))
                    .thenReturn(Optional.empty());

            Map<String, Object> data = (Map<String, Object>) module
                    .execute("list", Map.of(), TENANT_ID, null).get().data();
            List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("workflows");
            assertThat(items).hasSize(1);
            assertThat(items.get(0)).doesNotContainKey("tenantId");
            // Keepers stay
            assertThat(items.get(0)).containsKeys("id", "name", "createdAt", "updatedAt",
                    "pinned_version", "is_production", "latest_version");
        }

        @Test
        @DisplayName("adds description when non-blank, omits otherwise")
        @SuppressWarnings("unchecked")
        void addsDescriptionWhenPresent() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            when(workflowService.listWorkflows(TENANT_ID, null, null))
                    .thenReturn(List.of(workflow(a, "A", "What A does", null),
                                        workflow(b, "B", "  ", null)));
            when(planVersionService.getCurrentVersion(any(UUID.class))).thenReturn(1);
            when(publicationClient.findActivePublicationIdsByWorkflowIds(anyList(), eq(TENANT_ID)))
                    .thenReturn(Map.of());
            when(workflowRunRepository.findFirstByWorkflowIdOrderByStartedAtDesc(any(UUID.class)))
                    .thenReturn(Optional.empty());

            Map<String, Object> data = (Map<String, Object>) module
                    .execute("list", Map.of(), TENANT_ID, null).get().data();
            List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("workflows");
            Map<String, Object> aMap = items.stream()
                    .filter(m -> a.toString().equals(m.get("id"))).findFirst().orElseThrow();
            Map<String, Object> bMap = items.stream()
                    .filter(m -> b.toString().equals(m.get("id"))).findFirst().orElseThrow();
            assertThat(aMap.get("description")).isEqualTo("What A does");
            assertThat(bMap).doesNotContainKey("description");
        }

        @Test
        @DisplayName("adds has_application + application_id when batch lookup returns a publication for the workflow")
        @SuppressWarnings("unchecked")
        void addsHasApplicationFlag() {
            UUID published = UUID.randomUUID();
            UUID notPublished = UUID.randomUUID();
            UUID appId = UUID.randomUUID();
            when(workflowService.listWorkflows(TENANT_ID, null, null))
                    .thenReturn(List.of(workflow(published, "Pub", null, null),
                                        workflow(notPublished, "NotPub", null, null)));
            when(planVersionService.getCurrentVersion(any(UUID.class))).thenReturn(1);
            when(publicationClient.findActivePublicationIdsByWorkflowIds(anyList(), eq(TENANT_ID)))
                    .thenReturn(Map.of(published, appId));
            when(workflowRunRepository.findFirstByWorkflowIdOrderByStartedAtDesc(any(UUID.class)))
                    .thenReturn(Optional.empty());

            Map<String, Object> data = (Map<String, Object>) module
                    .execute("list", Map.of(), TENANT_ID, null).get().data();
            List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("workflows");

            Map<String, Object> pubItem = items.stream()
                    .filter(m -> published.toString().equals(m.get("id")))
                    .findFirst().orElseThrow();
            // application_id is embedded directly - agent can hop to
            // application(action='get'/'execute') without an extra `my` round-trip.
            assertThat(pubItem.get("has_application")).isEqualTo(true);
            assertThat(pubItem.get("application_id")).isEqualTo(appId.toString());

            Map<String, Object> notPubItem = items.stream()
                    .filter(m -> notPublished.toString().equals(m.get("id")))
                    .findFirst().orElseThrow();
            assertThat(notPubItem.get("has_application")).isEqualTo(false);
            assertThat(notPubItem).doesNotContainKey("application_id");
        }

        @Test
        @DisplayName("adds trigger_types parsed from the plan (fireable only - skips workflow/error)")
        @SuppressWarnings("unchecked")
        void addsTriggerTypesFromPlan() {
            UUID wfId = UUID.randomUUID();
            WorkflowEntity wf = workflow(wfId, "Wf", null, null);
            wf.setPlan(planWithTriggers("webhook", "schedule", "workflow"));
            when(workflowService.listWorkflows(TENANT_ID, null, null)).thenReturn(List.of(wf));
            when(planVersionService.getCurrentVersion(wfId)).thenReturn(1);
            when(publicationClient.findActivePublicationIdsByWorkflowIds(anyList(), eq(TENANT_ID)))
                    .thenReturn(Map.of());
            when(workflowRunRepository.findFirstByWorkflowIdOrderByStartedAtDesc(wfId))
                    .thenReturn(Optional.empty());

            Map<String, Object> data = (Map<String, Object>) module
                    .execute("list", Map.of(), TENANT_ID, null).get().data();
            Map<String, Object> item = ((List<Map<String, Object>>) data.get("workflows")).get(0);
            assertThat((List<String>) item.get("trigger_types"))
                    .containsExactlyInAnyOrder("webhook", "schedule");
        }

        @Test
        @DisplayName("PR4 - emits `requirements` block when nodeIcons + plan carry MCP integrations or sub-workflows")
        @SuppressWarnings("unchecked")
        void emitsRequirementsBlock() {
            UUID wfId = UUID.randomUUID();
            WorkflowEntity wf = workflow(wfId, "FlyFinder", null, null);
            // pre-computed nodeIcons (set at workflow save) - the helper reads these
            // directly without walking the plan a second time.
            wf.setNodeIcons(List.of(
                Map.of("isMcp", true, "iconSlug", "serpapi"),
                Map.of("nodeId", "form-trigger", "nodeKind", "entry")
            ));
            wf.setPlan(planWithTriggers("form"));
            when(workflowService.listWorkflows(TENANT_ID, null, null)).thenReturn(List.of(wf));
            when(planVersionService.getCurrentVersion(wfId)).thenReturn(1);
            when(publicationClient.findActivePublicationIdsByWorkflowIds(anyList(), eq(TENANT_ID)))
                    .thenReturn(Map.of());
            when(workflowRunRepository.findFirstByWorkflowIdOrderByStartedAtDesc(wfId))
                    .thenReturn(Optional.empty());
            // Tenant has NO credential for serpapi → ready=false, blocker emitted.
            when(credentialClient.getConfiguredIntegrations(TENANT_ID)).thenReturn(Set.of());

            Map<String, Object> data = (Map<String, Object>) module
                    .execute("list", Map.of(), TENANT_ID, null).get().data();
            Map<String, Object> item = ((List<Map<String, Object>>) data.get("workflows")).get(0);
            Map<String, Object> req = (Map<String, Object>) item.get("requirements");
            assertThat(req).isNotNull();
            assertThat(req.get("ready")).isEqualTo(false);
            List<Map<String, Object>> ints = (List<Map<String, Object>>) req.get("integrations");
            assertThat(ints).hasSize(1);
            assertThat(ints.get(0).get("name")).isEqualTo("serpapi");
            assertThat(ints.get(0).get("configured")).isEqualTo(false);
            List<String> blockers = (List<String>) req.get("blockers");
            assertThat(blockers).contains("integration:serpapi not configured");
        }

        @Test
        @DisplayName("emits canonical AgentListEnvelope keys (kind/offset/limit/hasMore) - PR2 migration")
        @SuppressWarnings("unchecked")
        void emitsCanonicalEnvelope() {
            UUID wfId = UUID.randomUUID();
            WorkflowEntity wf = workflow(wfId, "Wf", null, null);
            when(workflowService.listWorkflows(TENANT_ID, null, null)).thenReturn(List.of(wf));
            when(planVersionService.getCurrentVersion(wfId)).thenReturn(1);
            when(publicationClient.findActivePublicationIdsByWorkflowIds(anyList(), eq(TENANT_ID)))
                    .thenReturn(Map.of());
            when(workflowRunRepository.findFirstByWorkflowIdOrderByStartedAtDesc(wfId))
                    .thenReturn(Optional.empty());

            Map<String, Object> data = (Map<String, Object>) module
                    .execute("list", Map.of(), TENANT_ID, null).get().data();

            // Canonical envelope keys (post-PR2 migration through AgentListEnvelope)
            assertThat(data).containsKeys(
                    "status", "kind", "workflows", "count", "total",
                    "offset", "limit", "hasMore", "NEXT_OPTIONS");
            assertThat(data.get("kind")).isEqualTo("workflows");
            assertThat(data.get("offset")).isEqualTo(0);
            assertThat(data.get("limit")).isEqualTo(25);  // STANDARD.defaultLimit
            // No `message` field - replaced by structured `hint` (or absent when complete).
            assertThat(data).doesNotContainKey("message");
        }

        @Test
        @DisplayName("adds last_run {status, at} when a run exists")
        @SuppressWarnings("unchecked")
        void addsLastRunWhenPresent() {
            UUID wfId = UUID.randomUUID();
            WorkflowEntity wf = workflow(wfId, "Wf", null, null);
            when(workflowService.listWorkflows(TENANT_ID, null, null)).thenReturn(List.of(wf));
            when(planVersionService.getCurrentVersion(wfId)).thenReturn(1);
            when(publicationClient.findActivePublicationIdsByWorkflowIds(anyList(), eq(TENANT_ID)))
                    .thenReturn(Map.of());

            WorkflowRunEntity run = new WorkflowRunEntity();
            run.setStatus(RunStatus.COMPLETED);
            run.setStartedAt(Instant.parse("2026-05-10T12:00:00Z"));
            when(workflowRunRepository.findFirstByWorkflowIdOrderByStartedAtDesc(wfId))
                    .thenReturn(Optional.of(run));

            Map<String, Object> data = (Map<String, Object>) module
                    .execute("list", Map.of(), TENANT_ID, null).get().data();
            Map<String, Object> item = ((List<Map<String, Object>>) data.get("workflows")).get(0);
            Map<String, Object> lastRun = (Map<String, Object>) item.get("last_run");
            assertThat(lastRun).isNotNull();
            assertThat(lastRun.get("status")).isEqualTo("COMPLETED");
            assertThat(lastRun.get("at")).isEqualTo("2026-05-10T12:00:00Z");
        }

        @Test
        @DisplayName("keeps is_production + createdAt (user-chosen keepers, not noise)")
        @SuppressWarnings("unchecked")
        void keepsIsProductionAndCreatedAt() {
            UUID wfId = UUID.randomUUID();
            WorkflowEntity wf = workflow(wfId, "Wf", null, 3);
            when(workflowService.listWorkflows(TENANT_ID, null, null)).thenReturn(List.of(wf));
            when(planVersionService.getCurrentVersion(wfId)).thenReturn(5);
            when(publicationClient.findActivePublicationIdsByWorkflowIds(anyList(), eq(TENANT_ID)))
                    .thenReturn(Map.of());
            when(workflowRunRepository.findFirstByWorkflowIdOrderByStartedAtDesc(wfId))
                    .thenReturn(Optional.empty());

            Map<String, Object> data = (Map<String, Object>) module
                    .execute("list", Map.of(), TENANT_ID, null).get().data();
            Map<String, Object> item = ((List<Map<String, Object>>) data.get("workflows")).get(0);
            assertThat(item.get("is_production")).isEqualTo(true);
            assertThat(item.get("pinned_version")).isEqualTo(3);
            assertThat(item.get("latest_version")).isEqualTo(5);
            assertThat(item.get("createdAt")).isEqualTo("2026-04-01T00:00:00Z");
        }

        @Test
        @DisplayName("publication-service failure on has_application enrichment falls back silently (false)")
        @SuppressWarnings("unchecked")
        void publicationClientFailureFallsBack() {
            UUID wfId = UUID.randomUUID();
            WorkflowEntity wf = workflow(wfId, "Wf", null, null);
            when(workflowService.listWorkflows(TENANT_ID, null, null)).thenReturn(List.of(wf));
            when(planVersionService.getCurrentVersion(wfId)).thenReturn(1);
            when(publicationClient.findActivePublicationIdsByWorkflowIds(anyList(), eq(TENANT_ID)))
                    .thenThrow(new RuntimeException("publication-service down"));
            when(workflowRunRepository.findFirstByWorkflowIdOrderByStartedAtDesc(wfId))
                    .thenReturn(Optional.empty());

            ToolExecutionResult result = module.execute("list", Map.of(), TENANT_ID, null).get();
            assertThat(result.success()).isTrue();
            Map<String, Object> item = ((List<Map<String, Object>>)
                    ((Map<String, Object>) result.data()).get("workflows")).get(0);
            assertThat(item.get("has_application")).isEqualTo(false);
        }
    }

    /**
     * Regression for the prod bug observed 2026-05-22 08:18:36 - agent
     * tenant=5 executed workflow.get on a workflow that lived in the SAME
     * organization (00000000) but was created by a different tenant (1).
     * Pre-fix strict-tenant predicate at WorkflowCrudModule:319 returned
     * WORKFLOW_NOT_FOUND. Post-fix uses ScopeGuard.isInStrictScope which
     * lets any member of the org load the workflow.
     */
    @Nested
    @DisplayName("executeGet - org-aware scope check (regression: prod 2026-05-22)")
    class GetScopeTests {

        private static final String CALLER_TENANT = "tenant-5";
        private static final String CALLER_ORG = "00000000-0000-0000-0000-000000000000";
        private static final String CREATOR_TENANT = "tenant-1";

        private WorkflowEntity workflowInOrg(UUID id, String creatorTenant, String orgId) {
            WorkflowEntity w = new WorkflowEntity();
            w.setId(id);
            w.setTenantId(creatorTenant);
            w.setOrganizationId(orgId);
            w.setName("Airbnb Search");
            w.setPlan(Map.of("id", id.toString(), "tenantId", creatorTenant,
                    "triggers", List.of(), "tools", List.of(), "edges", List.of()));
            w.setCreatedAt(Instant.parse("2026-05-15T10:11:26Z"));
            w.setUpdatedAt(Instant.parse("2026-05-15T16:38:45Z"));
            return w;
        }

        private com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext orgContext(
                String tenantId, String orgId) {
            return new com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext(
                    tenantId, Map.of(), Map.of(), Set.of(),
                    null, null, orgId, null);
        }

        private com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext orgContextWithCredentials(
                String tenantId, String orgId, Map<String, Object> credentials) {
            return new com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext(
                    tenantId, credentials, Map.of(), Set.of(),
                    null, null, orgId, null);
        }

        @Test
        @DisplayName("Same-org different-tenant: returns workflow (was WORKFLOW_NOT_FOUND pre-fix - the prod bug)")
        @SuppressWarnings("unchecked")
        void sameOrgDifferentTenantReturnsWorkflow() {
            UUID wfId = UUID.randomUUID();
            WorkflowEntity wf = workflowInOrg(wfId, CREATOR_TENANT, CALLER_ORG);
            when(workflowService.getWorkflow(wfId)).thenReturn(Optional.of(wf));
            when(planVersionService.getCurrentVersion(wfId)).thenReturn(1);

            ToolExecutionResult result = module.execute("get",
                    Map.of("workflow_id", wfId.toString()),
                    CALLER_TENANT, orgContext(CALLER_TENANT, CALLER_ORG)).get();

            assertThat(result.success()).isTrue();
            assertThat(((Map<String, Object>) result.data()).get("id")).isEqualTo(wfId.toString());
        }

        @Test
        @DisplayName("Namespaced allowedWorkflowIds restriction is enforced before workflow lookup")
        void namespacedAllowedWorkflowIdsBlocksGet() {
            UUID wfId = UUID.randomUUID();

            ToolExecutionResult result = module.execute("get",
                    Map.of("workflow_id", wfId.toString()),
                    CALLER_TENANT,
                    orgContextWithCredentials(CALLER_TENANT, CALLER_ORG,
                            Map.of("__allowedWorkflowIds__", List.of("other-workflow")))).get();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(
                    com.apimarketplace.agent.tools.ToolErrorCode.PERMISSION_DENIED);
            verify(workflowService, never()).getWorkflow(any(UUID.class));
        }

        @Test
        @DisplayName("Different org: returns WORKFLOW_NOT_FOUND - strict-isolation across workspaces still enforced")
        void differentOrgReturnsNotFound() {
            UUID wfId = UUID.randomUUID();
            WorkflowEntity wf = workflowInOrg(wfId, CREATOR_TENANT, "other-org-uuid");
            when(workflowService.getWorkflow(wfId)).thenReturn(Optional.of(wf));

            ToolExecutionResult result = module.execute("get",
                    Map.of("workflow_id", wfId.toString()),
                    CALLER_TENANT, orgContext(CALLER_TENANT, CALLER_ORG)).get();

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(
                    com.apimarketplace.agent.tools.ToolErrorCode.WORKFLOW_NOT_FOUND);
        }

        @Test
        @DisplayName("Personal scope (no orgId in context) still allows owner - legacy fallback path")
        @SuppressWarnings("unchecked")
        void personalScopeOwnerCanGet() {
            UUID wfId = UUID.randomUUID();
            // Personal workflow: caller owns it AND organization_id is null.
            WorkflowEntity wf = workflowInOrg(wfId, CALLER_TENANT, null);
            when(workflowService.getWorkflow(wfId)).thenReturn(Optional.of(wf));
            when(planVersionService.getCurrentVersion(wfId)).thenReturn(1);

            ToolExecutionResult result = module.execute("get",
                    Map.of("workflow_id", wfId.toString()),
                    CALLER_TENANT, orgContext(CALLER_TENANT, null)).get();

            assertThat(result.success()).isTrue();
            assertThat(((Map<String, Object>) result.data()).get("id")).isEqualTo(wfId.toString());
        }
    }
}
