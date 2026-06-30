package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.trigger.client.TriggerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pins the defense-in-depth APPLICATION-immutability guard in
 * {@link WorkflowBuilderLoader#executeSave(WorkflowBuilderSession)} added 2026-05-15.
 *
 * <p>Context: APPLICATION-type workflows are frozen acquired marketplace clones -
 * their plan is the contract the acquirer received, restored only via
 * {@code POST /workflows/{id}/reset-plan}. The primary gate lives in
 * {@code WorkflowBuilderProvider.execute} using the session-cached
 * {@code loadedWorkflowIsApplication} flag. This loader-side check is a second line
 * of defense against:
 *
 * <ul>
 *   <li>a session restored from disk with a stale {@code false} flag while the
 *       workflow type was flipped to APPLICATION between load and save;</li>
 *   <li>any future code path that builds + saves a session without going
 *       through the dispatch guard.</li>
 * </ul>
 *
 * <p>The validator stub is left unconfigured because executeSave aborts BEFORE
 * the validation step when the loaded workflow is APPLICATION - proving the guard
 * fires early.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderLoader - APPLICATION immutability defense-in-depth")
class WorkflowBuilderLoaderApplicationImmutabilityTest {

    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private WorkflowManagementService workflowService;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowBuilderLogger buildLogger;
    @Mock private WorkflowBuilderValidator validator;
    @Mock private DataSourceClient dataSourceClient;
    @Mock private ToolSchemaFetcher toolSchemaFetcher;
    @Mock private NodeLibraryService nodeLibraryService;
    @Mock private TriggerClient triggerClient;
    @Mock private WorkflowPlanVersionService versionService;
    @Mock private AgentWorkflowFireService agentFireService;

    private WorkflowBuilderLoader loader;

    private static final UUID APP_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        loader = new WorkflowBuilderLoader(
            sessionStore, workflowService, workflowRepository,
            buildLogger, validator, dataSourceClient,
            toolSchemaFetcher, nodeLibraryService,
            new ObjectMapper(), triggerClient, versionService,
            agentFireService);
        ReflectionTestUtils.setField(loader, "allowSaveWithoutValidation", false);
    }

    private WorkflowBuilderSession sessionLoadedFromWorkflow(UUID workflowId) {
        WorkflowBuilderSession s = WorkflowBuilderSession.builder()
            .sessionId("s")
            .tenantId("t")
            .workflowName("Acquired App")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
        s.setLoadedWorkflowId(workflowId.toString());
        return s;
    }

    @Test
    @DisplayName("executeSave on APPLICATION-type workflow returns RESOURCE_CONFLICT - primary guard bypassed reproduction")
    void rejectsSaveOnApplicationWorkflow() {
        WorkflowEntity app = new WorkflowEntity();
        ReflectionTestUtils.setField(app, "id", APP_ID);
        app.setTenantId("t");
        app.setWorkflowType(WorkflowEntity.WorkflowType.APPLICATION);
        when(workflowRepository.findById(APP_ID)).thenReturn(Optional.of(app));

        ToolExecutionResult result = loader.executeSave(sessionLoadedFromWorkflow(APP_ID));

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_CONFLICT);
        assertThat(result.error())
            .contains("APPLICATION")
            .contains("workflow(action='discard')")
            .doesNotContain("POST /")
            .doesNotContain("reset-plan");
        assertThat(result.metadata())
            .containsEntry("code", "APPLICATION_PLAN_IMMUTABLE")
            .containsEntry("workflow_id", APP_ID.toString());

        // Side-effect: validator never consulted (guard fires upstream of validation).
        // Required so a malformed APPLICATION plan can't be persisted EVEN under
        // allow-create-without-validation=true.
        verify(validator, never()).validate(any());
        verify(workflowService, never()).saveWorkflow(any(), anyMap(), any());
        verify(versionService, never()).createVersion(any(), anyMap(), anyString(), anyString());
    }

    @Test
    @DisplayName("executeSave on regular WORKFLOW-type workflow proceeds past the immutability guard")
    void allowsSaveOnRegularWorkflow() {
        WorkflowEntity regular = new WorkflowEntity();
        ReflectionTestUtils.setField(regular, "id", APP_ID);
        regular.setTenantId("t");
        regular.setWorkflowType(WorkflowEntity.WorkflowType.WORKFLOW);
        when(workflowRepository.findById(APP_ID)).thenReturn(Optional.of(regular));

        // Validator is stubbed with NOT-AN-APPLICATION semantics so the call reaches
        // validation. We don't care about the validator result - only that the
        // immutability guard didn't short-circuit. An NPE inside the save proper is
        // tolerated (we're proving the guard is NOT the failure point).
        try {
            loader.executeSave(sessionLoadedFromWorkflow(APP_ID));
        } catch (Exception ignored) {
            // expected: downstream NPE on unstubbed workflowService.saveWorkflow
        }

        // Guard did not return early - validator was called once we reached its line.
        verify(validator).validate(any());
    }
}
