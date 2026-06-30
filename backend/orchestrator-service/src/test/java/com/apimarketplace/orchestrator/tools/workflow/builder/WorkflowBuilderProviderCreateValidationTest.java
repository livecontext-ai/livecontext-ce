package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.service.NodeParamsValidator;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.services.NodeTypeSearchService;
import com.apimarketplace.orchestrator.services.WorkflowExecutionService;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowManagementService.SaveResult;
import com.apimarketplace.orchestrator.tools.workflow.WorkflowHelpProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for the workflow.builder.allow-create-without-validation config property.
 * Verifies that when false (default), creation is blocked on validation errors,
 * and when true, creation proceeds despite validation errors.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderProvider - allow-create-without-validation config")
class WorkflowBuilderProviderCreateValidationTest {

    // Core dependencies
    @Mock private WorkflowBuilderSessionManager sessionManager;
    @Mock private WorkflowBuilderResultEnricher resultEnricher;
    @Mock private WorkflowDraftAutoSaver draftAutoSaver;
    @Mock private WorkflowBuilderToolDefinitionFactory toolDefinitionFactory;
    @Mock private WorkflowBuilderLogger buildLogger;

    // Domain services
    @Mock private WorkflowManagementService workflowService;
    @Mock private InterfaceClient interfaceClient;
    @Mock private NodeTypeSearchService nodeTypeSearchService;
    @Mock private NodeLibraryService nodeLibraryService;
    @Mock private NodeParamsValidator nodeParamsValidator;
    @Mock private WorkflowHelpProvider workflowHelpProvider;

    // Delegated handlers
    @Mock private WorkflowBuilderCreator creator;
    @Mock private WorkflowBuilderConnectionManager connectionManager;
    @Mock private WorkflowBuilderModifier modifier;
    @Mock private WorkflowBuilderViewer viewer;
    @Mock private WorkflowBuilderLoader loader;
    @Mock private WorkflowBuilderTableOperations tableOperations;
    @Mock private WorkflowBuilderPlanExporter planExporter;

    // For execute action
    @Mock private WorkflowExecutionService executionService;
    @Mock private WorkflowRunRepository workflowRunRepository;

    // Per-turn resource cap config (real instance so finish path resolves the default = 5)
    @Spy private com.apimarketplace.orchestrator.config.AgentDefaultsConfig agentDefaults =
            new com.apimarketplace.orchestrator.config.AgentDefaultsConfig();

    @Mock private WorkflowBuilderSession session;

    @InjectMocks
    private WorkflowBuilderProvider provider;

    private ToolExecutionContext ctx;

    @BeforeEach
    void setUp() {
        ctx = ToolExecutionContext.of("tenant-1");
    }

    /** Build the canonical "finish" call params. */
    private Map<String, Object> finishParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", "finish");
        return params;
    }

    /** Legacy 'create' alias - used to verify back-compat. */
    private Map<String, Object> createAliasParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("action", "create");
        return params;
    }

    /** @deprecated kept for legacy tests below; new tests should call {@link #finishParams()}. */
    @Deprecated
    private Map<String, Object> createParams() {
        return finishParams();
    }

    private void stubSessionFound() {
        var sessionResult = new WorkflowBuilderSessionManager.SessionResult(session, null);
        when(sessionManager.getSession(anyMap(), eq("tenant-1"), any())).thenReturn(sessionResult);
    }

    private ToolExecutionResult validationFailed() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("can_create", false);
        data.put("errors", List.of(Map.of("type", "MISSING_REQUIRED_PARAMS", "message", "Missing params")));
        data.put("message", "1 error(s) must be fixed before creating workflow.");
        return ToolExecutionResult.success(data);
    }

    private ToolExecutionResult validationPassed() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("can_create", true);
        data.put("errors", List.of());
        data.put("message", "No issues found! Workflow is valid.");
        return ToolExecutionResult.success(data);
    }

    /**
     * Make resultEnricher.addSessionSnapshot pass through the result unchanged.
     */
    private void stubEnricherPassthrough() {
        when(resultEnricher.addSessionSnapshot(any(ToolExecutionResult.class), anyMap(), anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ==================== allowCreateWithoutValidation = false ====================

    @Nested
    @DisplayName("When allow-create-without-validation = false (default)")
    class ValidationRequired {

        @BeforeEach
        void setUp() {
            ReflectionTestUtils.setField(provider, "allowCreateWithoutValidation", false);
        }

        @Test
        @DisplayName("Blocks creation when validation fails")
        void blocksCreationOnValidationFailure() {
            stubSessionFound();
            stubEnricherPassthrough();
            when(viewer.executeValidate(session)).thenReturn(validationFailed());

            ToolExecutionResult result = provider.execute("workflow", createParams(), ctx);

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Cannot finish workflow");
            assertThat(result.error()).contains("validation errors");
            verify(workflowService, never()).saveWorkflow(any(), anyMap(), any(), any());
        }

        @Test
        @DisplayName("Legacy alias: action='create' still routes to the same handler")
        void legacyCreateAliasStillWorks() {
            stubSessionFound();
            stubEnricherPassthrough();
            when(viewer.executeValidate(session)).thenReturn(validationFailed());

            ToolExecutionResult result = provider.execute("workflow", createAliasParams(), ctx);

            // Same failure path as 'finish' - proves the alias dispatches correctly
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("Cannot finish workflow");
        }

        @Test
        @DisplayName("New 'finish' action returns explicit STOP signal in success payload")
        void finishReturnsExplicitStopSignal() {
            stubSessionFound();
            stubEnricherPassthrough();
            when(viewer.executeValidate(session)).thenReturn(validationPassed());
            when(session.getLoadedWorkflowId()).thenReturn(null);
            when(session.buildPlanMap()).thenReturn(Map.of("name", "Test", "triggers", List.of(), "mcps", List.of()));
            when(session.getWorkflowName()).thenReturn("Test Workflow");

            var workflow = mock(WorkflowEntity.class);
            when(workflow.getId()).thenReturn(UUID.randomUUID());
            var saveResult = mock(SaveResult.class);
            when(saveResult.getWorkflow()).thenReturn(workflow);
            when(workflowService.saveWorkflow(any(WorkflowPlan.class), anyMap(), any(UUID.class), isNull()))
                    .thenReturn(saveResult);

            ToolExecutionResult result = provider.execute("workflow", finishParams(), ctx);

            assertThat(result.success()).isTrue();
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) result.data();
            assertThat(data.get("status")).isEqualTo("FINISHED");
            assertThat(data.get("session_state")).isEqualTo("CLOSED");
            assertThat(data).containsKey("STOP");
            assertThat(data.get("STOP").toString()).contains("Do NOT call");
            assertThat(data).containsKey("to_run_now");
            assertThat(data).containsKey("to_edit");
        }

        @Test
        @DisplayName("Allows creation when validation passes")
        void allowsCreationOnValidationSuccess() {
            stubSessionFound();
            stubEnricherPassthrough();
            when(viewer.executeValidate(session)).thenReturn(validationPassed());
            when(session.getLoadedWorkflowId()).thenReturn(null);
            when(session.buildPlanMap()).thenReturn(Map.of("name", "Test", "triggers", List.of(), "mcps", List.of()));
            when(session.getWorkflowName()).thenReturn("Test Workflow");

            // Mock saveWorkflow to return a result
            var workflow = mock(WorkflowEntity.class);
            when(workflow.getId()).thenReturn(UUID.randomUUID());
            var saveResult = mock(SaveResult.class);
            when(saveResult.getWorkflow()).thenReturn(workflow);
            when(workflowService.saveWorkflow(any(WorkflowPlan.class), anyMap(), any(UUID.class), isNull()))
                    .thenReturn(saveResult);

            ToolExecutionResult result = provider.execute("workflow", createParams(), ctx);

            assertThat(result.success()).isTrue();
            verify(workflowService).saveWorkflow(any(WorkflowPlan.class), anyMap(), any(UUID.class), isNull());
        }
    }

    // ==================== allowCreateWithoutValidation = true ====================

    @Nested
    @DisplayName("When allow-create-without-validation = true")
    class ValidationSkippable {

        @BeforeEach
        void setUp() {
            ReflectionTestUtils.setField(provider, "allowCreateWithoutValidation", true);
        }

        @Test
        @DisplayName("Allows creation even when validation fails")
        void allowsCreationDespiteValidationFailure() {
            stubSessionFound();
            stubEnricherPassthrough();
            when(viewer.executeValidate(session)).thenReturn(validationFailed());
            when(session.getLoadedWorkflowId()).thenReturn(null);
            when(session.buildPlanMap()).thenReturn(Map.of("name", "Test", "triggers", List.of(), "mcps", List.of()));
            when(session.getWorkflowName()).thenReturn("Test Workflow");

            var workflow = mock(WorkflowEntity.class);
            when(workflow.getId()).thenReturn(UUID.randomUUID());
            var saveResult = mock(SaveResult.class);
            when(saveResult.getWorkflow()).thenReturn(workflow);
            when(workflowService.saveWorkflow(any(WorkflowPlan.class), anyMap(), any(UUID.class), isNull()))
                    .thenReturn(saveResult);

            ToolExecutionResult result = provider.execute("workflow", createParams(), ctx);

            assertThat(result.success()).isTrue();
            verify(workflowService).saveWorkflow(any(WorkflowPlan.class), anyMap(), any(UUID.class), isNull());
        }

        @Test
        @DisplayName("Still allows creation when validation passes (no regression)")
        void allowsCreationOnValidationSuccess() {
            stubSessionFound();
            stubEnricherPassthrough();
            when(viewer.executeValidate(session)).thenReturn(validationPassed());
            when(session.getLoadedWorkflowId()).thenReturn(null);
            when(session.buildPlanMap()).thenReturn(Map.of("name", "Test", "triggers", List.of(), "mcps", List.of()));
            when(session.getWorkflowName()).thenReturn("Test Workflow");

            var workflow = mock(WorkflowEntity.class);
            when(workflow.getId()).thenReturn(UUID.randomUUID());
            var saveResult = mock(SaveResult.class);
            when(saveResult.getWorkflow()).thenReturn(workflow);
            when(workflowService.saveWorkflow(any(WorkflowPlan.class), anyMap(), any(UUID.class), isNull()))
                    .thenReturn(saveResult);

            ToolExecutionResult result = provider.execute("workflow", createParams(), ctx);

            assertThat(result.success()).isTrue();
            verify(workflowService).saveWorkflow(any(WorkflowPlan.class), anyMap(), any(UUID.class), isNull());
        }
    }

    // ==================== Config Value Verification ====================

    @Nested
    @DisplayName("Config value behavior")
    class ConfigValueBehavior {

        @Test
        @DisplayName("Default value is false (validation enforced)")
        void defaultValueIsFalse() {
            // Field default from @Value annotation: false
            // When not explicitly set, @InjectMocks leaves it as Java default (false)
            // This test verifies the behavior matches the expected default
            boolean fieldValue = (boolean) ReflectionTestUtils.getField(provider, "allowCreateWithoutValidation");
            assertThat(fieldValue).isFalse();
        }

        @Test
        @DisplayName("Validation is still executed even when bypassed")
        void validationStillRunsWhenBypassed() {
            ReflectionTestUtils.setField(provider, "allowCreateWithoutValidation", true);
            stubSessionFound();
            stubEnricherPassthrough();
            when(viewer.executeValidate(session)).thenReturn(validationFailed());
            when(session.getLoadedWorkflowId()).thenReturn(null);
            when(session.buildPlanMap()).thenReturn(Map.of("name", "Test", "triggers", List.of(), "mcps", List.of()));
            when(session.getWorkflowName()).thenReturn("Test Workflow");

            var workflow = mock(WorkflowEntity.class);
            when(workflow.getId()).thenReturn(UUID.randomUUID());
            var saveResult = mock(SaveResult.class);
            when(saveResult.getWorkflow()).thenReturn(workflow);
            when(workflowService.saveWorkflow(any(WorkflowPlan.class), anyMap(), any(UUID.class), isNull()))
                    .thenReturn(saveResult);

            provider.execute("workflow", createParams(), ctx);

            // Validation still runs even when bypassed
            verify(viewer).executeValidate(session);
        }
    }

    // ==========================================================================
    // V100: unified per-resource per-turn cap resolution for workflow creation
    // ==========================================================================
    @Nested
    @DisplayName("resolveMaxPerResourcePerTurn (V100 unified per-resource cap)")
    class ResolveMaxPerResourcePerTurn {

        @Test
        @DisplayName("Returns YAML default (5) when no override credential is present")
        void fallsBackToYamlDefault() {
            ToolExecutionContext context = ToolExecutionContext.of("tenant-1");
            assertThat(provider.resolveMaxPerResourcePerTurn(context)).isEqualTo(5);
        }

        @Test
        @DisplayName("Uses __chatMaxPerResourcePerTurn__ credential override when positive")
        void usesCredentialOverride() {
            ToolExecutionContext context = new ToolExecutionContext(
                "tenant-1",
                Map.of("__chatMaxPerResourcePerTurn__", 12, "turnId", "turn-x"),
                Map.of(), null, null, null, null, null);
            assertThat(provider.resolveMaxPerResourcePerTurn(context)).isEqualTo(12);
        }

        @Test
        @DisplayName("Falls back to YAML default when credential override is zero or negative")
        void fallsBackWhenCredentialNonPositive() {
            ToolExecutionContext context = new ToolExecutionContext(
                "tenant-1",
                Map.of("__chatMaxPerResourcePerTurn__", 0, "turnId", "turn-x"),
                Map.of(), null, null, null, null, null);
            assertThat(provider.resolveMaxPerResourcePerTurn(context)).isEqualTo(5);
        }

        @Test
        @DisplayName("Returns YAML default when context is null")
        void handlesNullContext() {
            assertThat(provider.resolveMaxPerResourcePerTurn(null)).isEqualTo(5);
        }

        @Test
        @DisplayName("Picks up YAML override from agent defaults config")
        void honorsYamlOverride() {
            agentDefaults.setMaxPerResourcePerTurn(3);
            ToolExecutionContext context = ToolExecutionContext.of("tenant-1");
            assertThat(provider.resolveMaxPerResourcePerTurn(context)).isEqualTo(3);
        }
    }
}
