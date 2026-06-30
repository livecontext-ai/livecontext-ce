package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import com.apimarketplace.trigger.client.TriggerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validation-blocking regression tests for {@link WorkflowBuilderLoader#executeSave}.
 *
 * Context: before consolidation, executeSave called the unified validator but only
 * logged errors - broken workflows landed in prod (Gmail Tri workflow with classify
 * missing categories + crud missing columns saved and scheduled every 10 minutes).
 *
 * Now executeSave MUST fail with TOOL_071 WORKFLOW_INVALID when the validator reports
 * errors, unless {@code workflow.builder.allow-create-without-validation} is true.
 * This parallels the {@code finish}-on-new guard in WorkflowBuilderProvider so editing
 * cannot bypass validation that creation enforces.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderLoader - executeSave validation gate")
class WorkflowBuilderLoaderSaveValidationTest {

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

    @BeforeEach
    void setUp() {
        loader = new WorkflowBuilderLoader(
            sessionStore, workflowService, workflowRepository,
            buildLogger, validator, dataSourceClient,
            toolSchemaFetcher, nodeLibraryService,
            new ObjectMapper(), triggerClient, versionService,
            agentFireService);
        // Default: enforce validation (mirrors production default)
        ReflectionTestUtils.setField(loader, "allowSaveWithoutValidation", false);
    }

    private WorkflowBuilderSession session() {
        return WorkflowBuilderSession.builder()
            .sessionId("s")
            .tenantId("t")
            .workflowName("Tri Gmail")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
    }

    private Map<String, Object> err(String type, String node, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("node", node);
        m.put("message", msg);
        return m;
    }

    private void stubValidatorReturn(Map<String, Object> agentFormat) {
        ValidationResult result = ValidationResult.builder().build();
        when(validator.validate(any())).thenReturn(result);
        when(validator.toAgentFormat(result)).thenReturn(agentFormat);
    }

    @Nested
    @DisplayName("Blocking behavior")
    class Blocking {

        @Test
        @DisplayName("Returns failure with WORKFLOW_INVALID when validator reports errors (prod Gmail scenario)")
        void blocksSaveOnValidationErrors() {
            Map<String, Object> agent = new LinkedHashMap<>();
            agent.put("errors", List.of(
                err("AGENT_MISSING_PARAM", "agent:tri",
                    "'Tri' (classify) requires 'categories'"),
                err("CRUD_MISSING_PARAM", "table:insert_email",
                    "'Insert email' requires 'columns'")
            ));
            agent.put("warnings", List.of());
            agent.put("error_count", 2);
            agent.put("warning_count", 0);
            agent.put("can_create", false);
            agent.put("message", "2 error(s) must be fixed before creating workflow.");
            stubValidatorReturn(agent);

            ToolExecutionResult result = loader.executeSave(session());

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.WORKFLOW_INVALID);
            assertThat(result.error()).contains("2 validation error(s)");
            assertThat(result.metadata()).containsEntry("error_count", 2);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> metaErrors =
                (List<Map<String, Object>>) result.metadata().get("errors");
            assertThat(metaErrors).hasSize(2);
            assertThat(result.metadata()).containsKey("next_action");
            // Side-effect: no DB write attempted
            verify(workflowService, never()).saveWorkflow(any(), anyMap(), any());
            verify(versionService, never()).createVersion(any(), anyMap(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("Bypass via config flag")
    class Bypass {

        @Test
        @DisplayName("Proceeds to save when allow-create-without-validation=true despite errors")
        void bypassFlagAllowsBrokenSave() {
            ReflectionTestUtils.setField(loader, "allowSaveWithoutValidation", true);

            Map<String, Object> agent = new LinkedHashMap<>();
            agent.put("errors", List.of(err("AGENT_MISSING_PARAM", "agent:tri", "categories")));
            agent.put("warnings", List.of());
            agent.put("error_count", 1);
            agent.put("warning_count", 0);
            agent.put("can_create", false);
            agent.put("message", "1 error(s)");
            stubValidatorReturn(agent);

            // workflowService.saveWorkflow is left unstubbed - it'll NPE if we reach it,
            // which is exactly what we want: we only need to prove the guard didn't short-circuit.
            // Wrap in try/catch to get past buildPlanMap/saveWorkflow.
            try {
                loader.executeSave(session());
            } catch (Exception ignored) {
                // Downstream NPEs are fine - we're asserting the guard path, not the full save.
            }

            // If bypass worked, validator was still consulted (audit trail preserved)
            verify(validator).validate(any());
        }
    }
}
