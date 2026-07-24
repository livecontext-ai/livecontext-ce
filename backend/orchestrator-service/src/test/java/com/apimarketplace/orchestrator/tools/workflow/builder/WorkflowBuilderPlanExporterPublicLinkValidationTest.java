package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for {@code workflow(action='set_plan')} validation of the
 * {@code public_link} core type.
 *
 * <p>Bug: {@link WorkflowBuilderPlanExporter#executeSetPlan}'s {@code validateCoreType}
 * switch had no {@code case "public_link"}, so it fell through to the default branch and
 * EVERY plan containing a public_link core was rejected with "Unknown type 'public_link'",
 * even though add_node could create the node and the execution engine runs it. The fix
 * adds the case (requiring a non-blank {@code params.file}) and lists public_link in the
 * default "Unknown type" message. These tests are RED pre-fix, GREEN post-fix.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderPlanExporter - set_plan validation of the public_link core type")
class WorkflowBuilderPlanExporterPublicLinkValidationTest {

    @Mock
    private WorkflowBuilderSessionStore sessionStore;
    @Mock
    private ToolSchemaFetcher toolSchemaFetcher;

    private WorkflowBuilderPlanExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new WorkflowBuilderPlanExporter(sessionStore, toolSchemaFetcher);
    }

    private WorkflowBuilderSession newSession() {
        return WorkflowBuilderSession.builder()
                .sessionId("test-session")
                .tenantId("test-tenant")
                .workflowName("Share a rendered clip")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private Map<String, Object> manualTrigger() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("label", "start");
        t.put("type", "manual");
        return t;
    }

    private Map<String, Object> publicLinkCore(Map<String, Object> params) {
        Map<String, Object> core = new LinkedHashMap<>();
        core.put("id", "pl1");
        core.put("type", "public_link");
        core.put("label", "Share Video");
        if (params != null) {
            core.put("params", new LinkedHashMap<>(params));
        }
        return core;
    }

    private ToolExecutionResult setPlan(WorkflowBuilderSession session, List<Map<String, Object>> cores) {
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(manualTrigger())));
        plan.put("cores", new ArrayList<>(cores));
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("plan", plan);
        return exporter.executeSetPlan(session, parameters);
    }

    @Test
    @DisplayName("public_link core with params.file passes validation (set_plan used to reject EVERY public_link plan as Unknown type)")
    void publicLinkWithFileImportsSuccessfully() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(publicLinkCore(Map.of("file", "{{core:dl.output.file}}", "ttl_minutes", 240))));

        assertThat(result.success())
                .as("a well-formed public_link core must be accepted, got: " + result.error())
                .isTrue();
        assertThat(session.getCores()).hasSize(1);
        Map<String, Object> imported = session.getCores().get(0);
        assertThat(imported.get("type")).isEqualTo("public_link");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) imported.get("params");
        assertThat(params).containsEntry("file", "{{core:dl.output.file}}");
    }

    @Test
    @DisplayName("public_link core WITHOUT params.file is rejected with the dedicated 'params.file is required' error")
    void publicLinkWithoutFileRejectedWithDedicatedError() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(publicLinkCore(Map.of("ttl_minutes", 240))));

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .as("the failure must be the targeted params.file error, not the old Unknown type rejection")
                .contains("'params.file' is required for public_link")
                .doesNotContain("Unknown type 'public_link'");
    }

    @Test
    @DisplayName("public_link core with a params map but a BLANK file is rejected the same way")
    void publicLinkWithBlankFileRejected() {
        WorkflowBuilderSession session = newSession();

        ToolExecutionResult result = setPlan(session,
                List.of(publicLinkCore(Map.of("file", "   "))));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("'params.file' is required for public_link");
    }

    @Test
    @DisplayName("the default Unknown type message now lists public_link among the expected core types")
    void unknownTypeMessageListsPublicLink() {
        WorkflowBuilderSession session = newSession();
        Map<String, Object> bogus = new LinkedHashMap<>();
        bogus.put("id", "b1");
        bogus.put("type", "bogus");
        bogus.put("label", "Broken");

        ToolExecutionResult result = setPlan(session, List.of(bogus));

        assertThat(result.success()).isFalse();
        assertThat(result.error())
                .contains("Unknown type 'bogus'")
                .as("public_link must be advertised as a valid core type in the expected-types list")
                .contains("public_link");
    }
}
