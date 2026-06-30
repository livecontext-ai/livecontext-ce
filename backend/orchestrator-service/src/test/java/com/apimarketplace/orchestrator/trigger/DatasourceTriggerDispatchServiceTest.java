package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.services.triggers.TriggerUserResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Production-pin invariant + payload-shape contract for
 * {@link DatasourceTriggerDispatchService}.
 *
 * <p>These tests cover every gate that protects the production-pin invariant:
 * malformed input, unpinned workflow, no run at pinned version, terminal run
 * status, insufficient credits, engine failure, and the happy path. They also
 * lock the shape of the payload handed to {@code ReusableTriggerService} since
 * {@code TableTriggerNode} reads specific keys from it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DatasourceTriggerDispatchService - production-pin gate + payload shape")
class DatasourceTriggerDispatchServiceTest {

    @Mock private ProductionRunResolver productionRunResolver;
    @Mock private ReusableTriggerService triggerService;
    @Mock private CreditConsumptionClient creditClient;
    @Mock private TriggerUserResolver triggerUserResolver;

    private DatasourceTriggerDispatchService service;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String TRIGGER_ID = "trigger:on_order_change";
    private static final String TENANT_ID = "user-test";
    private static final Long DS_ID = 42L;
    private static final Long ROW_ID = 1000L;
    private static final Instant TRIGGERED_AT = Instant.parse("2026-04-17T12:00:00Z");

    @BeforeEach
    void setUp() {
        service = new DatasourceTriggerDispatchService(productionRunResolver, triggerService, creditClient, triggerUserResolver);
    }

    private WorkflowRunEntity runEntity(RunStatus status) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic("run-" + UUID.randomUUID());
        run.setTenantId(TENANT_ID);
        run.setStatus(status);
        return run;
    }

    private ProductionRunResolver.Resolution foundResolution(WorkflowRunEntity run) {
        return new ProductionRunResolver.Resolution(
                Optional.of(run), ProductionRunResolver.Outcome.FOUND, "Test Workflow");
    }

    private Map<String, Object> sampleRow() {
        return Map.of("id", ROW_ID, "status", "paid", "amount", 150);
    }

    // ==================== Malformed input ====================

    @Nested
    @DisplayName("malformed input short-circuits before touching resolver or credits")
    class MalformedInput {

        @Test
        @DisplayName("null workflowId → malformed, no resolver/credit/engine calls")
        void nullWorkflowId() {
            DatasourceTriggerDispatchService.DispatchResult result = service.dispatch(
                    null, TRIGGER_ID, "row_created", DS_ID, ROW_ID, sampleRow(), null, TRIGGERED_AT);

            assertThat(result.success()).isFalse();
            assertThat(result.status()).isEqualTo("malformed");
            verify(productionRunResolver, never()).resolve(any(), any());
            verify(triggerService, never()).executeTrigger(any(), anyString(), any(), any());
        }

        @Test
        @DisplayName("null triggerId → malformed")
        void nullTriggerId() {
            DatasourceTriggerDispatchService.DispatchResult result = service.dispatch(
                    WORKFLOW_ID, null, "row_created", DS_ID, ROW_ID, sampleRow(), null, TRIGGERED_AT);

            assertThat(result.status()).isEqualTo("malformed");
        }

        @Test
        @DisplayName("null eventType → malformed")
        void nullEventType() {
            DatasourceTriggerDispatchService.DispatchResult result = service.dispatch(
                    WORKFLOW_ID, TRIGGER_ID, null, DS_ID, ROW_ID, sampleRow(), null, TRIGGERED_AT);

            assertThat(result.status()).isEqualTo("malformed");
        }
    }

    // ==================== Pin gate ====================

    @Nested
    @DisplayName("production-pin gate")
    class PinGate {

        @Test
        @DisplayName("Unpinned workflow → status=not_pinned, no engine call")
        void unpinnedWorkflowRefused() {
            when(productionRunResolver.resolve(eq(WORKFLOW_ID), any())).thenReturn(
                    new ProductionRunResolver.Resolution(
                            Optional.empty(), ProductionRunResolver.Outcome.NOT_PINNED, "Test Workflow"));

            DatasourceTriggerDispatchService.DispatchResult result = service.dispatch(
                    WORKFLOW_ID, TRIGGER_ID, "row_created", DS_ID, ROW_ID, sampleRow(), null, TRIGGERED_AT);

            assertThat(result.success()).isFalse();
            assertThat(result.status()).isEqualTo("not_pinned");
            verify(triggerService, never()).executeTrigger(any(), anyString(), any(), any());
        }

        @Test
        @DisplayName("Pinned but no run at pinned version → status=no_run, no engine call")
        void noMatchingRunRefused() {
            when(productionRunResolver.resolve(eq(WORKFLOW_ID), any())).thenReturn(
                    new ProductionRunResolver.Resolution(
                            Optional.empty(), ProductionRunResolver.Outcome.NO_PRODUCTION_RUN, "Test Workflow"));

            DatasourceTriggerDispatchService.DispatchResult result = service.dispatch(
                    WORKFLOW_ID, TRIGGER_ID, "row_created", DS_ID, ROW_ID, sampleRow(), null, TRIGGERED_AT);

            assertThat(result.status()).isEqualTo("no_run");
            verify(triggerService, never()).executeTrigger(any(), anyString(), any(), any());
        }
    }

    // ==================== Terminal run ====================

    @Nested
    @DisplayName("terminal run gate")
    class TerminalRun {

        @Test
        @DisplayName("Run in CANCELLED state → status=run_terminal, no engine call")
        void cancelledRunRefused() {
            WorkflowRunEntity run = runEntity(RunStatus.CANCELLED);
            when(productionRunResolver.resolve(eq(WORKFLOW_ID), any())).thenReturn(foundResolution(run));

            DatasourceTriggerDispatchService.DispatchResult result = service.dispatch(
                    WORKFLOW_ID, TRIGGER_ID, "row_created", DS_ID, ROW_ID, sampleRow(), null, TRIGGERED_AT);

            assertThat(result.status()).isEqualTo("run_terminal");
            verify(creditClient, never()).checkCredits(anyString());
            verify(triggerService, never()).executeTrigger(any(), anyString(), any(), any());
        }

        @Test
        @DisplayName("Run in TIMEOUT state → status=run_terminal")
        void timeoutRunRefused() {
            WorkflowRunEntity run = runEntity(RunStatus.TIMEOUT);
            when(productionRunResolver.resolve(eq(WORKFLOW_ID), any())).thenReturn(foundResolution(run));

            DatasourceTriggerDispatchService.DispatchResult result = service.dispatch(
                    WORKFLOW_ID, TRIGGER_ID, "row_created", DS_ID, ROW_ID, sampleRow(), null, TRIGGERED_AT);

            assertThat(result.status()).isEqualTo("run_terminal");
        }

        @Test
        @DisplayName("Every isTerminal() status is rejected - FAILED, COMPLETED, PARTIAL_SUCCESS, SKIPPED added")
        void allTerminalStatusesRefused() {
            // Regression for prod 2026-05-07 12:40 UTC: WorkflowTriggerDispatchService and
            // DatasourceTriggerDispatchService used to accept FAILED/COMPLETED runs because
            // the original gate only listed CANCELLED|TIMEOUT. After a JVM crash mid-cycle,
            // the run stayed FAILED and these dispatchers kept reopening epochs - same root
            // cause as the WebhookDispatchService incident on run_<id>.
            for (RunStatus terminal : new RunStatus[]{
                    RunStatus.FAILED, RunStatus.COMPLETED,
                    RunStatus.PARTIAL_SUCCESS, RunStatus.SKIPPED}) {
                WorkflowRunEntity run = runEntity(terminal);
                when(productionRunResolver.resolve(eq(WORKFLOW_ID), any())).thenReturn(foundResolution(run));

                DatasourceTriggerDispatchService.DispatchResult result = service.dispatch(
                        WORKFLOW_ID, TRIGGER_ID, "row_created", DS_ID, ROW_ID, sampleRow(), null, TRIGGERED_AT);

                assertThat(result.status())
                    .as("status %s must be rejected as run_terminal", terminal)
                    .isEqualTo("run_terminal");
            }
            verify(triggerService, never()).executeTrigger(any(), anyString(), any(), any());
        }
    }

    // ==================== Credit gate ====================

    @Test
    @DisplayName("Tenant out of credits → status=insufficient_credits, no engine call")
    void insufficientCreditsRefused() {
        WorkflowRunEntity run = runEntity(RunStatus.WAITING_TRIGGER);
        when(productionRunResolver.resolve(eq(WORKFLOW_ID), any())).thenReturn(foundResolution(run));
        when(creditClient.checkCredits(TENANT_ID)).thenReturn(false);

        DatasourceTriggerDispatchService.DispatchResult result = service.dispatch(
                WORKFLOW_ID, TRIGGER_ID, "row_created", DS_ID, ROW_ID, sampleRow(), null, TRIGGERED_AT);

        assertThat(result.status()).isEqualTo("insufficient_credits");
        verify(triggerService, never()).executeTrigger(any(), anyString(), any(), any());
    }

    // ==================== Happy path ====================

    @Nested
    @DisplayName("happy path - engine called with TriggerType.DATASOURCE and correct payload")
    class HappyPath {

        @SuppressWarnings("unchecked")
        @Test
        @DisplayName("row_created event with a row fires the engine, returns fired/runId")
        void rowCreatedFires() {
            WorkflowRunEntity run = runEntity(RunStatus.WAITING_TRIGGER);
            when(productionRunResolver.resolve(eq(WORKFLOW_ID), any())).thenReturn(foundResolution(run));
            when(creditClient.checkCredits(TENANT_ID)).thenReturn(true);
            when(triggerUserResolver.resolveDisplayName(TENANT_ID)).thenReturn("Test User");
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.DATASOURCE), any()))
                    .thenReturn(TriggerExecutionResult.success(run.getRunIdPublic(), TRIGGER_ID,
                            TriggerType.DATASOURCE, Set.of("mcp:step1"), 1));

            DatasourceTriggerDispatchService.DispatchResult result = service.dispatch(
                    WORKFLOW_ID, TRIGGER_ID, "row_created", DS_ID, ROW_ID, sampleRow(), null, TRIGGERED_AT);

            assertThat(result.success()).isTrue();
            assertThat(result.status()).isEqualTo("fired");
            assertThat(result.runId()).isEqualTo(run.getRunIdPublic());

            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass((Class) Map.class);
            verify(triggerService).executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.DATASOURCE),
                    payloadCaptor.capture());
            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload)
                    .containsEntry("event_type", "row_created")
                    .containsEntry("datasource_id", DS_ID)
                    .containsEntry("row_id", ROW_ID)
                    .containsEntry("triggered_at", TRIGGERED_AT.toString())
                    .containsEntry("row", sampleRow())
                    .containsEntry("previous_row", null);
            // Flattened columns on root for {{trigger.column}} templating
            assertThat(payload).containsEntry("status", "paid");
            assertThat(payload).containsEntry("amount", 150);
            // triggered_by must always be present - regression: pre-fix it was missing from buildPayload
            assertThat(payload).containsKey("triggered_by");
            assertThat(payload.get("triggered_by")).isNotNull();
        }

        @Test
        @DisplayName("triggered_by is resolved via TriggerUserResolver - owner display name, consistent with all other trigger resolvers")
        void triggeredByResolvedViaUserResolver() {
            WorkflowRunEntity run = runEntity(RunStatus.WAITING_TRIGGER);
            when(productionRunResolver.resolve(eq(WORKFLOW_ID), any())).thenReturn(foundResolution(run));
            when(creditClient.checkCredits(TENANT_ID)).thenReturn(true);
            when(triggerUserResolver.resolveDisplayName(TENANT_ID)).thenReturn("Alice");
            when(triggerService.executeTrigger(any(), anyString(), any(), any()))
                    .thenReturn(TriggerExecutionResult.success(run.getRunIdPublic(), TRIGGER_ID,
                            TriggerType.DATASOURCE, Set.of(), 1));

            service.dispatch(WORKFLOW_ID, TRIGGER_ID, "row_deleted", DS_ID, ROW_ID, null, null, TRIGGERED_AT);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass((Class) Map.class);
            verify(triggerService).executeTrigger(any(), anyString(), any(), payloadCaptor.capture());
            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload).containsKey("triggered_by");
            assertThat(payload.get("triggered_by"))
                    .as("triggered_by must be the workflow owner's display name from TriggerUserResolver")
                    .isEqualTo("Alice");
        }

        @Test
        @DisplayName("triggered_by falls back to empty string when resolver returns empty - same contract as other trigger resolvers")
        void triggeredByEmptyStringWhenResolverReturnsEmpty() {
            WorkflowRunEntity run = runEntity(RunStatus.WAITING_TRIGGER);
            when(productionRunResolver.resolve(eq(WORKFLOW_ID), any())).thenReturn(foundResolution(run));
            when(creditClient.checkCredits(TENANT_ID)).thenReturn(true);
            when(triggerUserResolver.resolveDisplayName(TENANT_ID)).thenReturn("");
            when(triggerService.executeTrigger(any(), anyString(), any(), any()))
                    .thenReturn(TriggerExecutionResult.success(run.getRunIdPublic(), TRIGGER_ID,
                            TriggerType.DATASOURCE, Set.of(), 1));

            service.dispatch(WORKFLOW_ID, TRIGGER_ID, "row_deleted", DS_ID, ROW_ID, null, null, TRIGGERED_AT);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass((Class) Map.class);
            verify(triggerService).executeTrigger(any(), anyString(), any(), payloadCaptor.capture());
            Map<String, Object> payload = payloadCaptor.getValue();
            assertThat(payload).containsKey("triggered_by");
            assertThat(payload.get("triggered_by"))
                    .as("empty string when resolver cannot find the user - never null")
                    .isEqualTo("");
        }

        @Test
        @DisplayName("eventType is lowercased in the payload regardless of caller casing")
        void eventTypeNormalized() {
            WorkflowRunEntity run = runEntity(RunStatus.WAITING_TRIGGER);
            when(productionRunResolver.resolve(eq(WORKFLOW_ID), any())).thenReturn(foundResolution(run));
            when(creditClient.checkCredits(TENANT_ID)).thenReturn(true);
            when(triggerService.executeTrigger(any(), anyString(), any(), any()))
                    .thenReturn(TriggerExecutionResult.success(run.getRunIdPublic(), TRIGGER_ID,
                            TriggerType.DATASOURCE, Set.of(), 1));

            service.dispatch(WORKFLOW_ID, TRIGGER_ID, "ROW_UPDATED", DS_ID, ROW_ID,
                    sampleRow(), Map.of("status", "pending"), TRIGGERED_AT);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass((Class) Map.class);
            verify(triggerService).executeTrigger(any(), anyString(), any(), payloadCaptor.capture());
            assertThat(payloadCaptor.getValue().get("event_type")).isEqualTo("row_updated");
            assertThat(payloadCaptor.getValue().get("previous_row"))
                    .isEqualTo(Map.of("status", "pending"));
        }

        @Test
        @DisplayName("null triggered_at falls back to Instant.now() rather than null")
        void nullTriggeredAtFallsBackToNow() {
            WorkflowRunEntity run = runEntity(RunStatus.WAITING_TRIGGER);
            when(productionRunResolver.resolve(eq(WORKFLOW_ID), any())).thenReturn(foundResolution(run));
            when(creditClient.checkCredits(TENANT_ID)).thenReturn(true);
            when(triggerService.executeTrigger(any(), anyString(), any(), any()))
                    .thenReturn(TriggerExecutionResult.success(run.getRunIdPublic(), TRIGGER_ID,
                            TriggerType.DATASOURCE, Set.of(), 1));

            service.dispatch(WORKFLOW_ID, TRIGGER_ID, "row_deleted", DS_ID, ROW_ID,
                    sampleRow(), null, null);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass((Class) Map.class);
            verify(triggerService).executeTrigger(any(), anyString(), any(), payloadCaptor.capture());
            Object ts = payloadCaptor.getValue().get("triggered_at");
            assertThat(ts).isInstanceOf(String.class).asString().isNotBlank();
        }

        @Test
        @DisplayName("engine reports failure → dispatch result status=error")
        void engineFailureSurfacesAsError() {
            WorkflowRunEntity run = runEntity(RunStatus.WAITING_TRIGGER);
            when(productionRunResolver.resolve(eq(WORKFLOW_ID), any())).thenReturn(foundResolution(run));
            when(creditClient.checkCredits(TENANT_ID)).thenReturn(true);
            when(triggerService.executeTrigger(any(), anyString(), any(), any()))
                    .thenReturn(TriggerExecutionResult.failure(run.getRunIdPublic(), TRIGGER_ID,
                            TriggerType.DATASOURCE, "engine rejected"));

            DatasourceTriggerDispatchService.DispatchResult result = service.dispatch(
                    WORKFLOW_ID, TRIGGER_ID, "row_created", DS_ID, ROW_ID, sampleRow(), null, TRIGGERED_AT);

            assertThat(result.success()).isFalse();
            assertThat(result.status()).isEqualTo("error");
        }

        @Test
        @DisplayName("engine throws → caught and reported as error, not propagated")
        void engineExceptionSurfacesAsError() {
            WorkflowRunEntity run = runEntity(RunStatus.WAITING_TRIGGER);
            when(productionRunResolver.resolve(eq(WORKFLOW_ID), any())).thenReturn(foundResolution(run));
            when(creditClient.checkCredits(TENANT_ID)).thenReturn(true);
            when(triggerService.executeTrigger(any(), anyString(), any(), any()))
                    .thenThrow(new RuntimeException("boom"));

            DatasourceTriggerDispatchService.DispatchResult result = service.dispatch(
                    WORKFLOW_ID, TRIGGER_ID, "row_created", DS_ID, ROW_ID, sampleRow(), null, TRIGGERED_AT);

            assertThat(result.success()).isFalse();
            assertThat(result.status()).isEqualTo("error");
            assertThat(result.message()).contains("boom");
        }
    }
}
