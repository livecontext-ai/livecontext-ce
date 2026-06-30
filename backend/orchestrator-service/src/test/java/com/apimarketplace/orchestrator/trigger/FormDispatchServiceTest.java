package com.apimarketplace.orchestrator.trigger;

import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.StandaloneFormEndpointDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FormDispatchService")
class FormDispatchServiceTest {

    @Mock private TriggerClient triggerClient;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowRunRepository runRepository;
    @Mock private ReusableTriggerService triggerService;
    @Mock private ProductionRunResolver productionRunResolver;
    @Mock private CreditConsumptionClient creditClient;

    private FormDispatchService service;

    private static final UUID WORKFLOW_ID = UUID.randomUUID();
    private static final String TENANT_ID = "tenant-1";
    private static final String TOKEN = "fm_test123";
    private static final String TRIGGER_ID = "trigger:form";
    private static final String RUN_ID = "run_123";
    private static final String IP_ADDRESS = "127.0.0.1";
    private static final Map<String, Object> FORM_DATA = Map.of("field1", "value1");

    @BeforeEach
    void setUp() {
        service = new FormDispatchService(
                triggerClient, workflowRepository, runRepository,
                triggerService, productionRunResolver, creditClient,
                new com.apimarketplace.common.storage.url.PublicFileUrlBuilder("https://livecontext.ai"));
        lenient().when(creditClient.checkCredits(any())).thenReturn(true);

        // Default: ProductionRunResolver delegates to existing repo stubs so the
        // pre-existing test setup keeps working after the centralized refactor.
        lenient().when(productionRunResolver.resolve(any(), any())).thenAnswer(inv -> {
            java.util.UUID wfId = inv.getArgument(0);
            var wf = workflowRepository.findById(wfId).orElse(null);
            if (wf == null) {
                return new ProductionRunResolver.Resolution(
                    java.util.Optional.empty(), ProductionRunResolver.Outcome.WORKFLOW_MISSING, null);
            }
            Integer pinned = wf.getPinnedVersion();
            var r = pinned != null
                ? runRepository.findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(wfId, pinned)
                : runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(wfId);
            var outcome = pinned == null
                ? (r.isPresent() ? ProductionRunResolver.Outcome.FOUND : ProductionRunResolver.Outcome.NOT_PINNED)
                : (r.isPresent() ? ProductionRunResolver.Outcome.FOUND : ProductionRunResolver.Outcome.NO_PRODUCTION_RUN);
            return new ProductionRunResolver.Resolution(r, outcome, wf.getName());
        });
    }

    private StandaloneFormEndpointDto createEndpoint(String triggerId) {
        StandaloneFormEndpointDto dto = new StandaloneFormEndpointDto();
        dto.setId(UUID.randomUUID());
        dto.setName("Test Form");
        dto.setToken(TOKEN);
        dto.setWorkflowId(WORKFLOW_ID);
        dto.setIsActive(true);
        dto.setSuccessMessage("Thanks!");
        dto.setTriggerId(triggerId);
        return dto;
    }

    private WorkflowEntity createWorkflow(Integer pinnedVersion) {
        WorkflowEntity wf = new WorkflowEntity();
        wf.setId(WORKFLOW_ID);
        wf.setTenantId(TENANT_ID);
        wf.setPlan(Map.of("triggers", List.of()));
        wf.setPinnedVersion(pinnedVersion);
        return wf;
    }

    private WorkflowRunEntity createRun(RunStatus status) {
        WorkflowRunEntity run = new WorkflowRunEntity();
        run.setRunIdPublic(RUN_ID);
        run.setTenantId(TENANT_ID);
        run.setStatus(status);
        run.setPlanVersion(1);
        return run;
    }

    @Nested
    @DisplayName("getFormConfig")
    class GetFormConfigTests {

        @Test
        @DisplayName("Should return config for valid token")
        void shouldReturnConfig() {
            StandaloneFormEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            endpoint.setDescription("Test description");
            endpoint.setFormConfig(List.of(Map.of("name", "field1", "type", "text")));

            when(triggerClient.findFormEndpointByToken(TOKEN)).thenReturn(endpoint);

            Map<String, Object> config = service.getFormConfig(TOKEN);

            assertThat(config.get("name")).isEqualTo("Test Form");
            assertThat(config.get("description")).isEqualTo("Test description");
            assertThat(config.get("formConfig")).isNotNull();
            assertThat(config.get("successMessage")).isEqualTo("Thanks!");
            assertThat(config.get("isActive")).isEqualTo(true);
        }

        @Test
        @DisplayName("Should throw when token not found")
        void shouldThrowWhenTokenNotFound() {
            when(triggerClient.findFormEndpointByToken(TOKEN)).thenReturn(null);

            assertThatThrownBy(() -> service.getFormConfig(TOKEN))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Form endpoint not found");
        }
    }

    @Nested
    @DisplayName("submitForm - dispatch to workflow")
    class SubmitFormTests {

        @Test
        @DisplayName("Should dispatch successfully with stored triggerId")
        void shouldDispatchWithTriggerId() {
            StandaloneFormEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);

            when(triggerClient.findFormEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.FORM), any()))
                    .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.FORM, Set.of(), 1));

            Map<String, Object> result = service.submitForm(TOKEN, FORM_DATA, IP_ADDRESS);

            assertThat(result.get("status")).isEqualTo("triggered");
            assertThat(result.get("workflowsTriggered")).isEqualTo(1);
            assertThat(result.get("successMessage")).isEqualTo("Thanks!");
            verify(triggerService).executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.FORM), any());
        }

        @Test
        @DisplayName("Should return accepted when triggerId is null")
        void shouldReturnAcceptedWhenNoTriggerId() {
            StandaloneFormEndpointDto endpoint = createEndpoint(null);
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);

            when(triggerClient.findFormEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));

            Map<String, Object> result = service.submitForm(TOKEN, FORM_DATA, IP_ADDRESS);

            assertThat(result.get("status")).isEqualTo("accepted");
            assertThat(result.get("workflowsTriggered")).isEqualTo(0);
            verify(triggerService, never()).executeTrigger(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should return accepted when workflow not found")
        void shouldReturnAcceptedWhenNoWorkflow() {
            StandaloneFormEndpointDto endpoint = createEndpoint(TRIGGER_ID);

            when(triggerClient.findFormEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.empty());

            Map<String, Object> result = service.submitForm(TOKEN, FORM_DATA, IP_ADDRESS);

            assertThat(result.get("status")).isEqualTo("accepted");
            assertThat(result.get("workflowsTriggered")).isEqualTo(0);
            verify(triggerService, never()).executeTrigger(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should return accepted when pinned but no production run exists")
        void shouldReturnAcceptedWhenNoRun() {
            // PINNED (version 1) but no production run yet -> NO_PRODUCTION_RUN, which still
            // returns "accepted" (only NOT_PINNED now refuses - see F8 regression test). The
            // pinned-version run lookup returns Optional.empty() by Mockito default.
            StandaloneFormEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            WorkflowEntity workflow = createWorkflow(1);

            when(triggerClient.findFormEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));

            Map<String, Object> result = service.submitForm(TOKEN, FORM_DATA, IP_ADDRESS);

            assertThat(result.get("status")).isEqualTo("accepted");
            assertThat(result.get("workflowsTriggered")).isEqualTo(0);
        }

        @Test
        @DisplayName("Should return accepted when run is terminal")
        void shouldReturnAcceptedWhenRunTerminal() {
            StandaloneFormEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.CANCELLED);

            when(triggerClient.findFormEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));

            Map<String, Object> result = service.submitForm(TOKEN, FORM_DATA, IP_ADDRESS);

            assertThat(result.get("status")).isEqualTo("accepted");
            assertThat(result.get("workflowsTriggered")).isEqualTo(0);
            verify(triggerService, never()).executeTrigger(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should return accepted when insufficient credits")
        void shouldReturnAcceptedWhenInsufficientCredits() {
            StandaloneFormEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);

            when(triggerClient.findFormEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));
            when(creditClient.checkCredits(TENANT_ID)).thenReturn(false);

            Map<String, Object> result = service.submitForm(TOKEN, FORM_DATA, IP_ADDRESS);

            assertThat(result.get("status")).isEqualTo("accepted");
            assertThat(result.get("workflowsTriggered")).isEqualTo(0);
            verify(triggerService, never()).executeTrigger(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should use pinned version for run lookup")
        void shouldUsePinnedVersionForRunLookup() {
            StandaloneFormEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            WorkflowEntity workflow = createWorkflow(5);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);

            when(triggerClient.findFormEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(WORKFLOW_ID, 5))
                    .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.FORM), any()))
                    .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.FORM, Set.of(), 1));

            Map<String, Object> result = service.submitForm(TOKEN, FORM_DATA, IP_ADDRESS);

            assertThat(result.get("status")).isEqualTo("triggered");
            verify(runRepository).findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(WORKFLOW_ID, 5);
            verify(runRepository, never()).findFirstByWorkflowIdOrderByStartedAtDesc(any());
        }

        @Test
        @DisplayName("Should return accepted when pinned run not found")
        void shouldReturnAcceptedWhenPinnedRunNotFound() {
            StandaloneFormEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            WorkflowEntity workflow = createWorkflow(5);

            when(triggerClient.findFormEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdAndPlanVersionOrderByStartedAtDesc(WORKFLOW_ID, 5))
                    .thenReturn(Optional.empty());

            Map<String, Object> result = service.submitForm(TOKEN, FORM_DATA, IP_ADDRESS);

            assertThat(result.get("status")).isEqualTo("accepted");
            assertThat(result.get("workflowsTriggered")).isEqualTo(0);
        }

        @Test
        @DisplayName("Should throw when endpoint is inactive")
        void shouldThrowWhenEndpointInactive() {
            StandaloneFormEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            endpoint.setIsActive(false);

            when(triggerClient.findFormEndpointByToken(TOKEN)).thenReturn(endpoint);

            assertThatThrownBy(() -> service.submitForm(TOKEN, FORM_DATA, IP_ADDRESS))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Form endpoint is not active");

            verify(triggerClient).logFormSubmission(endpoint.getId(), FORM_DATA, "inactive", 0, IP_ADDRESS);
        }

        @Test
        @DisplayName("F8 regression: no pinned production version -> clear refusal, NOT a silent green success")
        void noPinnedVersionRefusesInsteadOfSilentSuccess() {
            // Pre-fix: a NOT_PINNED workflow logged a warning and fell through to a
            // success-shaped response (status:"accepted" + successMessage, HTTP 200),
            // so the public form rendered a green "submitted" screen while an empty
            // epoch ran nothing. Now it MUST refuse via the 409 path (IllegalStateException
            // -> PublicFormController 409 -> the form shows the error).
            StandaloneFormEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            WorkflowEntity workflow = createWorkflow(null); // null pinned version
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER); // a run EXISTS...

            when(triggerClient.findFormEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            // ...but with no pinned production version the REAL resolver returns NOT_PINNED
            // regardless of any runs (ProductionRunResolver short-circuits before the run
            // lookup). Stub that outcome explicitly (run present) so the test pins the real
            // contract - the refusal is driven by the NOT_PINNED outcome, not the absence
            // of a run - rather than leaning on the fixture's run-based shortcut.
            when(productionRunResolver.resolve(eq(WORKFLOW_ID), any())).thenReturn(
                    new ProductionRunResolver.Resolution(
                            Optional.of(run), ProductionRunResolver.Outcome.NOT_PINNED, "Test Form"));

            assertThatThrownBy(() -> service.submitForm(TOKEN, FORM_DATA, IP_ADDRESS))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no published production version");

            // Logged as a distinct outcome, and NEVER dispatched (no empty-epoch run).
            verify(triggerClient).logFormSubmission(endpoint.getId(), FORM_DATA, "no_pinned_version", 0, IP_ADDRESS);
            verify(triggerService, never()).executeTrigger(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should return accepted when trigger execution fails")
        void shouldReturnAcceptedWhenExecutionFails() {
            StandaloneFormEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);

            when(triggerClient.findFormEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.FORM), any()))
                    .thenReturn(TriggerExecutionResult.failure(RUN_ID, TRIGGER_ID, TriggerType.FORM, "boom"));

            Map<String, Object> result = service.submitForm(TOKEN, FORM_DATA, IP_ADDRESS);

            assertThat(result.get("status")).isEqualTo("accepted");
            assertThat(result.get("workflowsTriggered")).isEqualTo(0);
        }

        @Test
        @DisplayName("Should log submission with triggered status on success")
        void shouldLogTriggeredOnSuccess() {
            StandaloneFormEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);

            when(triggerClient.findFormEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.FORM), any()))
                    .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.FORM, Set.of(), 1));

            service.submitForm(TOKEN, FORM_DATA, IP_ADDRESS);

            verify(triggerClient).logFormSubmission(endpoint.getId(), FORM_DATA, "triggered", 1, IP_ADDRESS);
        }

        @Test
        @DisplayName("Should log no_waiting_run when pinned but no production run fires")
        void shouldLogNoWaitingRunWhenNotTriggered() {
            // PINNED (version 1), no production run -> NO_PRODUCTION_RUN -> falls through to
            // the no_waiting_run log (NOT_PINNED would instead refuse - see F8 regression).
            StandaloneFormEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            WorkflowEntity workflow = createWorkflow(1);

            when(triggerClient.findFormEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));

            service.submitForm(TOKEN, FORM_DATA, IP_ADDRESS);

            verify(triggerClient).logFormSubmission(endpoint.getId(), FORM_DATA, "no_waiting_run", 0, IP_ADDRESS);
        }

        @Test
        @DisplayName("Should include form metadata in trigger payload")
        void shouldIncludeFormMetadataInPayload() {
            StandaloneFormEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);

            when(triggerClient.findFormEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.FORM), any()))
                    .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.FORM, Set.of(), 1));

            service.submitForm(TOKEN, FORM_DATA, IP_ADDRESS);

            verify(triggerService).executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.FORM), argThat(payload -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) payload;
                return "form_endpoint".equals(p.get("_source"))
                        && p.get("_formEndpointId") != null
                        && "Test Form".equals(p.get("_formEndpointName"))
                        && "value1".equals(p.get("field1"));
            }));
        }

        @Test
        @DisplayName("Should return accepted when workflow plan is null")
        void shouldReturnAcceptedWhenPlanNull() {
            StandaloneFormEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            WorkflowEntity workflow = createWorkflow(null);
            workflow.setPlan(null);

            when(triggerClient.findFormEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));

            Map<String, Object> result = service.submitForm(TOKEN, FORM_DATA, IP_ADDRESS);

            assertThat(result.get("status")).isEqualTo("accepted");
            assertThat(result.get("workflowsTriggered")).isEqualTo(0);
        }

        /**
         * Regression: user form field named 'submission_id' previously silently overwrote the
         * engine-generated UUID because formData was put into the map first and then engine fields
         * were applied on top - but the raw HashMap copy put user fields at root, not via putIfAbsent.
         * Engine keys must win; user value must be dropped and a WARN must be logged.
         */
        @Test
        @DisplayName("reservedFormFieldCollisionLogsWarnAndDrops")
        void reservedFormFieldCollisionLogsWarnAndDrops() {
            Map<String, Object> collidingData = new java.util.HashMap<>();
            collidingData.put("name", "Alice");
            collidingData.put("submission_id", "user-provided-id");  // reserved key

            StandaloneFormEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);

            when(triggerClient.findFormEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.FORM), any()))
                    .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.FORM, Set.of(), 1));

            service.submitForm(TOKEN, collidingData, IP_ADDRESS);

            verify(triggerService).executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.FORM), argThat(rawPayload -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) rawPayload;
                // Engine UUID must be present (not the user-provided string)
                Object sid = p.get("submission_id");
                if (!(sid instanceof String engineUuid)) return false;
                if ("user-provided-id".equals(engineUuid)) return false;  // user value must be dropped
                // Must be a valid UUID
                try { UUID.fromString(engineUuid); } catch (IllegalArgumentException e) { return false; }
                // Non-colliding user field must still be present
                return "Alice".equals(p.get("name"));
            }));
        }

        /**
         * Regression: a form field whose value is a plain Map (no _type key) was previously treated
         * as a FileRef by isFileRef() only if all of path/name/mimeType were Strings - the missing
         * _type caused isFileRef to return false, so the raw Map leaked to root as user data. This
         * is the correct behavior, but must be asserted explicitly to guard against future changes.
         */
        @Test
        @DisplayName("fileRefWithoutTypeIsNotFlattened")
        void fileRefWithoutTypeIsNotFlattened() {
            Map<String, Object> noTypeMap = new java.util.HashMap<>();
            noTypeMap.put("path", "t1/wf1/run1/form/doc.pdf");
            noTypeMap.put("name", "doc.pdf");
            // no _type key

            Map<String, Object> formWithNoType = new java.util.HashMap<>();
            formWithNoType.put("attachment", noTypeMap);

            StandaloneFormEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);

            when(triggerClient.findFormEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.FORM), any()))
                    .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.FORM, Set.of(), 1));

            service.submitForm(TOKEN, formWithNoType, IP_ADDRESS);

            verify(triggerService).executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.FORM), argThat(rawPayload -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) rawPayload;
                // The raw Map must still be present at the field key - NOT flattened to attachment_file_url etc.
                if (!(p.get("attachment") instanceof Map)) return false;
                // No flattened keys must have been generated
                return !p.containsKey("attachment_file_url")
                        && !p.containsKey("attachment_file_name");
            }));
        }

        /**
         * Regression: FormDispatchService previously passed raw formData as the trigger payload,
         * without submission_id, submitted_at, or form_data. customTransform invented them at
         * persistence time, so runtime SpEL templates resolved null and form_data was always empty.
         * FileRef fields were also only flattened at persistence, not at runtime.
         */
        @Test
        @DisplayName("dispatchPayloadIncludesPersistedShape")
        void dispatchPayloadIncludesPersistedShape() {
            Map<String, Object> fileRef = new java.util.HashMap<>();
            fileRef.put("_type", "file");
            fileRef.put("path", "t1/wf1/run1/form/upload.pdf");
            fileRef.put("name", "upload.pdf");
            fileRef.put("mimeType", "application/pdf");
            fileRef.put("size", 4096);
            fileRef.put("id", "abcd1234-0000-0000-0000-000000000001");

            Map<String, Object> richFormData = new java.util.HashMap<>();
            richFormData.put("name", "Alice");
            richFormData.put("resume", fileRef);

            StandaloneFormEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);

            when(triggerClient.findFormEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.FORM), any()))
                    .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.FORM, Set.of(), 1));

            service.submitForm(TOKEN, richFormData, IP_ADDRESS);

            verify(triggerService).executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.FORM), argThat(rawPayload -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> p = (Map<String, Object>) rawPayload;

                // submission_id must be a non-null UUID string
                if (!(p.get("submission_id") instanceof String sid) || sid.isEmpty()) return false;
                // submitted_at must be a non-null ISO string
                if (!(p.get("submitted_at") instanceof String sat) || sat.isEmpty()) return false;
                // triggered_at must equal submitted_at
                if (!sat.equals(p.get("triggered_at"))) return false;
                // form_data must be populated with the submitted fields, not empty
                if (!(p.get("form_data") instanceof Map<?, ?> fd) || fd.isEmpty()) return false;
                if (!"Alice".equals(fd.get("name"))) return false;
                // FileRef field must be flattened at root level (for runtime SpEL) - opaque by-id url
                // (no s3 key / tenant id), built from the FileRef's storage id.
                if (!(p.get("resume_file_url") instanceof String url)
                        || !url.contains("/api/proxy/files/by-id/abcd1234-0000-0000-0000-000000000001/raw")) return false;
                if (!"upload.pdf".equals(p.get("resume_file_name"))) return false;
                if (!Integer.valueOf(4096).equals(p.get("resume_file_size"))) return false;
                if (!"application/pdf".equals(p.get("resume_content_type"))) return false;
                return true;
            }));
        }
    }

    @Nested
    @DisplayName("PR22c R3 - workspace-scope guard regression (R2 convergent must-fix A+B+C)")
    class WorkspaceScopeGuardTests {

        private static final String ORG_ID = "org-acme";
        private static final String OTHER_ORG_ID = "org-other";

        @Test
        @DisplayName("Refuses fire when endpoint org != run org - convergent R2 must-fix")
        void refusesFireOnWorkspaceMismatch() {
            // Endpoint tagged for org-acme, but the pinned run is tagged for org-other.
            // The guard at FormDispatchService:120 MUST short-circuit BEFORE executeTrigger.
            // Without this regression test, the load-bearing R2.4 fix has no enforcement.
            StandaloneFormEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            endpoint.setOrganizationId(ORG_ID);
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);
            run.setOrganizationId(OTHER_ORG_ID);

            when(triggerClient.findFormEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));

            Map<String, Object> result = service.submitForm(TOKEN, FORM_DATA, IP_ADDRESS);

            assertThat(result.get("status")).isEqualTo("accepted");
            assertThat(result.get("workflowsTriggered")).isEqualTo(0);
            verify(triggerService, never()).executeTrigger(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Fires normally when endpoint org == run org")
        void firesOnWorkspaceMatch() {
            StandaloneFormEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            endpoint.setOrganizationId(ORG_ID);
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);
            run.setOrganizationId(ORG_ID);

            when(triggerClient.findFormEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.FORM), any()))
                    .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.FORM, Set.of(), 1));

            Map<String, Object> result = service.submitForm(TOKEN, FORM_DATA, IP_ADDRESS);

            assertThat(result.get("status")).isEqualTo("triggered");
            verify(triggerService).executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.FORM), any());
        }

        @Test
        @DisplayName("Fires normally when both org IDs are null (personal scope)")
        void firesOnBothNullPersonalScope() {
            StandaloneFormEndpointDto endpoint = createEndpoint(TRIGGER_ID);
            endpoint.setOrganizationId(null);
            WorkflowEntity workflow = createWorkflow(null);
            WorkflowRunEntity run = createRun(RunStatus.WAITING_TRIGGER);
            run.setOrganizationId(null);

            when(triggerClient.findFormEndpointByToken(TOKEN)).thenReturn(endpoint);
            when(workflowRepository.findById(WORKFLOW_ID)).thenReturn(Optional.of(workflow));
            when(runRepository.findFirstByWorkflowIdOrderByStartedAtDesc(WORKFLOW_ID))
                    .thenReturn(Optional.of(run));
            when(triggerService.executeTrigger(eq(run), eq(TRIGGER_ID), eq(TriggerType.FORM), any()))
                    .thenReturn(TriggerExecutionResult.success(RUN_ID, TRIGGER_ID, TriggerType.FORM, Set.of(), 1));

            Map<String, Object> result = service.submitForm(TOKEN, FORM_DATA, IP_ADDRESS);

            assertThat(result.get("status")).isEqualTo("triggered");
        }
    }
}
