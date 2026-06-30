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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for {@code workflow(action='set_plan')} edge import.
 *
 * <p>Bug: {@link WorkflowBuilderPlanExporter}'s edge importer used
 * {@code findNodeByLabel} (which only matches a node's raw display label) and,
 * on a miss, FABRICATED an id as {@code "mcp:" + normalize(ref)}. Edge
 * endpoints written in the canonical node-id form - {@code trigger:sms_form},
 * {@code mcp:send_sms}, {@code core:check:if} - are accepted by the plan
 * VALIDATOR ({@code resolveEdgeLabel}) but were not resolved by the IMPORTER,
 * so they were silently rewritten to {@code mcp:trigger_sms_form},
 * {@code mcp:mcp_send_sms}, … . {@code set_plan} reported success, then the
 * next {@code validate()} failed with INVALID_EDGE_SOURCE / INVALID_EDGE_TARGET.
 *
 * <p>Fix: the importer now delegates to {@code session.resolveNodeReference}
 * (the same resolver {@code connect}/{@code modify}/{@code validate} use) and
 * never fabricates an {@code mcp:} id. These tests are RED pre-fix, GREEN
 * post-fix.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WorkflowBuilderPlanExporter - set_plan edge import resolves every documented endpoint form")
class WorkflowBuilderPlanExporterEdgeImportTest {

    private static final String SEND_SMS_TOOL_ID = "e44e883a-2822-428e-9ace-8064d17d8a14";

    @Mock
    private WorkflowBuilderSessionStore sessionStore;
    @Mock
    private ToolSchemaFetcher toolSchemaFetcher;

    private WorkflowBuilderPlanExporter exporter;

    @BeforeEach
    void setUp() {
        exporter = new WorkflowBuilderPlanExporter(sessionStore, toolSchemaFetcher);
        // Every MCP node in these plans references a real catalog tool.
        lenient().when(toolSchemaFetcher.checkToolExists(anyString()))
                .thenReturn(ToolSchemaFetcher.ToolExistence.EXISTS);
    }

    private WorkflowBuilderSession newSession() {
        return WorkflowBuilderSession.builder()
                .sessionId("test-session")
                .tenantId("test-tenant")
                .workflowName("Send SMS via Twilio")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    private Map<String, Object> formTrigger() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("label", "sms_form");
        t.put("type", "form");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("formTitle", "Send SMS");
        params.put("fields", List.of(
                Map.of("name", "to", "label", "To", "type", "text", "required", true),
                Map.of("name", "from", "label", "From", "type", "text", "required", true),
                Map.of("name", "body", "label", "Body", "type", "text", "required", true)));
        t.put("params", params);
        return t;
    }

    private Map<String, Object> sendSmsMcp() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", SEND_SMS_TOOL_ID);
        m.put("label", "send_sms");
        m.put("params", new LinkedHashMap<>(Map.of(
                "To", "{{trigger:sms_form.output.to}}",
                "From", "{{trigger:sms_form.output.from}}",
                "Body", "{{trigger:sms_form.output.body}}")));
        return m;
    }

    private Map<String, Object> setPlan(WorkflowBuilderSession session, Map<String, Object> plan) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("plan", plan);
        ToolExecutionResult result = exporter.executeSetPlan(session, params);
        assertThat(result.success())
                .as("set_plan should import successfully (validation accepts these endpoint forms)")
                .isTrue();
        // Return the single imported edge for assertions.
        assertThat(session.getEdges()).hasSize(1);
        return session.getEdges().get(0);
    }

    @Test
    @DisplayName("fully-qualified node-id endpoints (trigger:x / mcp:y) keep their real ids - not mcp:trigger_x")
    void fullyQualifiedNodeIdEdgesResolveToRealNodes() {
        WorkflowBuilderSession session = newSession();
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(formTrigger())));
        plan.put("mcps", new ArrayList<>(List.of(sendSmsMcp())));
        plan.put("edges", new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("from", "trigger:sms_form", "to", "mcp:send_sms")))));

        Map<String, Object> edge = setPlan(session, plan);

        assertThat(edge.get("from"))
                .as("trigger:sms_form must resolve to itself, NOT be mangled to mcp:trigger_sms_form")
                .isEqualTo("trigger:sms_form");
        assertThat(edge.get("to"))
                .as("mcp:send_sms must resolve to itself, NOT be mangled to mcp:mcp_send_sms")
                .isEqualTo("mcp:send_sms");
    }

    @Test
    @DisplayName("raw display-label endpoints still resolve to the real node ids")
    void rawLabelEdgesStillResolve() {
        WorkflowBuilderSession session = newSession();
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(formTrigger())));
        plan.put("mcps", new ArrayList<>(List.of(sendSmsMcp())));
        plan.put("edges", new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("from", "sms_form", "to", "send_sms")))));

        Map<String, Object> edge = setPlan(session, plan);

        assertThat(edge.get("from")).isEqualTo("trigger:sms_form");
        assertThat(edge.get("to")).isEqualTo("mcp:send_sms");
    }

    @Test
    @DisplayName("type-prefixed endpoint WITH a port (core:check:if) keeps the port and the real base id")
    void typePrefixedPortEdgeResolves() {
        WorkflowBuilderSession session = newSession();

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("label", "Check");
        decision.put("type", "decision");
        decision.put("decisionConditions", List.of(
                new LinkedHashMap<>(Map.of("id", "check-if", "type", "if", "label", "Yes", "expression", "true")),
                new LinkedHashMap<>(Map.of("id", "check-else", "type", "else", "label", "No", "expression", "default"))));

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(formTrigger())));
        plan.put("mcps", new ArrayList<>(List.of(sendSmsMcp())));
        plan.put("cores", new ArrayList<>(List.of(decision)));
        plan.put("edges", new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("from", "core:check:if", "to", "mcp:send_sms")))));

        Map<String, Object> edge = setPlan(session, plan);

        assertThat(edge.get("from"))
                .as("core:check:if must keep its base id + port, NOT become mcp:core_check:if")
                .isEqualTo("core:check:if");
        assertThat(edge.get("to")).isEqualTo("mcp:send_sms");
    }

    @Test
    @DisplayName("no imported edge endpoint is ever a fabricated mcp:trigger_* / mcp:mcp_* id")
    void noFabricatedMcpEndpointsAfterImport() {
        WorkflowBuilderSession session = newSession();
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(formTrigger())));
        plan.put("mcps", new ArrayList<>(List.of(sendSmsMcp())));
        plan.put("edges", new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("from", "trigger:sms_form", "to", "mcp:send_sms")))));

        Map<String, Object> edge = setPlan(session, plan);

        // Direct guard against the exact corruption strings the agent observed.
        assertThat((String) edge.get("from")).doesNotStartWith("mcp:trigger_").doesNotStartWith("mcp:mcp_");
        assertThat((String) edge.get("to")).doesNotStartWith("mcp:trigger_").doesNotStartWith("mcp:mcp_");
    }

    /**
     * Port coherence: validation, import and export must all recognise the SAME
     * port set (via EdgeRefParser). The validator's port list used to omit
     * approval ports (approved/rejected/timeout), so a valid approval-port edge
     * was rejected as "references unknown node core:review:approved" before it
     * could ever be imported. RED pre-fix (set_plan fails validation), GREEN now.
     */
    @Test
    @DisplayName("approval-port edge (core:review:approved) passes validation AND imports with the port preserved")
    void approvalPortEdgeValidatesAndImports() {
        WorkflowBuilderSession session = newSession();
        Map<String, Object> approval = new LinkedHashMap<>();
        approval.put("label", "review");
        approval.put("type", "approval");

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("triggers", new ArrayList<>(List.of(formTrigger())));
        plan.put("mcps", new ArrayList<>(List.of(sendSmsMcp())));
        plan.put("cores", new ArrayList<>(List.of(approval)));
        plan.put("edges", new ArrayList<>(List.of(
                new LinkedHashMap<>(Map.of("from", "trigger:sms_form", "to", "core:review")),
                new LinkedHashMap<>(Map.of("from", "core:review:approved", "to", "mcp:send_sms")))));

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("plan", plan);
        ToolExecutionResult result = exporter.executeSetPlan(session, params);

        assertThat(result.success())
                .as("approval-port edge must pass validation now (was rejected as unknown node 'core:review:approved')")
                .isTrue();
        assertThat(session.getEdges()).hasSize(2);
        Map<String, Object> portEdge = session.getEdges().stream()
                .filter(e -> "mcp:send_sms".equals(e.get("to")))
                .findFirst().orElseThrow();
        assertThat(portEdge.get("from"))
                .as("the :approved port must be preserved on the imported edge")
                .isEqualTo("core:review:approved");
    }
}
