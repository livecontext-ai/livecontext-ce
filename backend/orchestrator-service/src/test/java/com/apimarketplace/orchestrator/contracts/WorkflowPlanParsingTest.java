package com.apimarketplace.orchestrator.contracts;

import com.apimarketplace.orchestrator.domain.workflow.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that WorkflowPlan correctly parses plans created by the frontend
 * and produces expected domain objects matching the node contracts.
 *
 * This validates the Frontend → Backend flow:
 * 1. Frontend creates a plan JSON following node-contracts.schema.json
 * 2. Backend parses the JSON into WorkflowPlan
 * 3. Domain objects have correct field names and types
 */
@DisplayName("WorkflowPlan Parsing Tests - Frontend → Backend Contract")
class WorkflowPlanParsingTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // TRIGGER PARSING
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Trigger Parsing")
    class TriggerParsingTests {

        @Test
        @DisplayName("Should parse webhook trigger with correct type")
        void parseWebhookTrigger() {
            Map<String, Object> planData = createPlanWithTrigger(
                "webhook-1", "My Webhook", "webhook", "single"
            );

            WorkflowPlan plan = WorkflowPlan.fromMap(planData);

            assertEquals(1, plan.getTriggers().size());
            Trigger trigger = plan.getTriggers().get(0);

            assertEquals("webhook-1", trigger.id());
            assertEquals("my webhook", trigger.label());
            assertEquals("webhook", trigger.type());
            assertEquals("single", trigger.strategy());
            assertEquals("trigger:my_webhook", trigger.getNormalizedKey());
        }

        @Test
        @DisplayName("Should parse schedule trigger with correct type")
        void parseScheduleTrigger() {
            Map<String, Object> triggerParams = Map.of(
                "cron", "0 9 * * MON-FRI",
                "timezone", "Europe/Paris"
            );

            Map<String, Object> planData = createPlanWithTriggerAndParams(
                "schedule-1", "Daily Report", "schedule", "single", triggerParams
            );

            WorkflowPlan plan = WorkflowPlan.fromMap(planData);

            Trigger trigger = plan.getTriggers().get(0);
            assertEquals("schedule", trigger.type());
            assertEquals("trigger:daily_report", trigger.getNormalizedKey());
        }

        @Test
        @DisplayName("Should parse chat trigger with correct type")
        void parseChatTrigger() {
            Map<String, Object> planData = createPlanWithTrigger(
                "chat-1", "Chat Support", "chat", "single"
            );

            WorkflowPlan plan = WorkflowPlan.fromMap(planData);

            Trigger trigger = plan.getTriggers().get(0);
            assertEquals("chat", trigger.type());
            assertEquals("trigger:chat_support", trigger.getNormalizedKey());
        }

        @Test
        @DisplayName("Should parse datasource/table trigger with correct type")
        void parseTableTrigger() {
            Map<String, Object> planData = createPlanWithTrigger(
                "ds-123", "Customer Data", "datasource", "one_row"
            );

            WorkflowPlan plan = WorkflowPlan.fromMap(planData);

            Trigger trigger = plan.getTriggers().get(0);
            assertEquals("datasource", trigger.type());
            assertEquals("one_row", trigger.strategy());
            assertEquals("trigger:customer_data", trigger.getNormalizedKey());
        }

        @Test
        @DisplayName("Should normalize trigger labels correctly")
        void normalizeTriggerLabels() {
            // Test various label formats
            List<String[]> testCases = List.of(
                new String[]{"My Webhook", "trigger:my_webhook"},
                new String[]{"API Call", "trigger:api_call"},
                new String[]{"For Each Item", "trigger:for_each_item"},
                new String[]{"Check-Value", "trigger:check_value"}
            );

            for (String[] testCase : testCases) {
                String label = testCase[0];
                String expectedKey = testCase[1];

                Map<String, Object> planData = createPlanWithTrigger(
                    "test-id", label, "webhook", "single"
                );

                WorkflowPlan plan = WorkflowPlan.fromMap(planData);
                Trigger trigger = plan.getTriggers().get(0);

                assertEquals(expectedKey, trigger.getNormalizedKey(),
                    "Label '" + label + "' should normalize to " + expectedKey);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STEP PARSING
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Step Parsing")
    class StepParsingTests {

        @Test
        @DisplayName("Should parse basic step with params")
        void parseBasicStep() {
            Map<String, Object> stepParams = Map.of(
                "url", "https://api.example.com/data",
                "method", "GET"
            );

            Map<String, Object> planData = createPlanWithStep(
                "api-call-1", "Fetch Data", stepParams
            );

            WorkflowPlan plan = WorkflowPlan.fromMap(planData);

            assertEquals(1, plan.getMcps().size());
            Step step = plan.getMcps().get(0);

            assertEquals("Fetch Data", step.label());
            assertEquals("mcp:fetch_data", step.getNormalizedKey());
            assertEquals("https://api.example.com/data", step.params().get("url"));
        }

        @Test
        @DisplayName("Should parse transform core node")
        void parseTransformCore() {
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "transform-1");
            coreData.put("type", "transform");
            coreData.put("label", "Transform Data");
            coreData.put("transform", Map.of(
                "mappings", List.of(
                    Map.of("label", "fullName", "expression", "{{mcp:fetch.output.firstName}} {{mcp:fetch.output.lastName}}"),
                    Map.of("label", "email", "expression", "{{mcp:fetch.output.email}}")
                )
            ));

            Map<String, Object> planData = createBasePlan();
            planData.put("cores", List.of(coreData));

            WorkflowPlan plan = WorkflowPlan.fromMap(planData);

            Core core = plan.getCores().get(0);
            assertTrue(core.isTransform());
            assertNotNull(core.transformConfig());
            assertEquals(2, core.transformConfig().mappings().size());
        }

        @Test
        @DisplayName("Should parse wait core node")
        void parseWaitCore() {
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "wait-1");
            coreData.put("type", "wait");
            coreData.put("label", "Wait 5s");
            coreData.put("wait", Map.of("duration", 5000L));

            Map<String, Object> planData = createBasePlan();
            planData.put("cores", List.of(coreData));

            WorkflowPlan plan = WorkflowPlan.fromMap(planData);

            Core core = plan.getCores().get(0);
            assertTrue(core.isWait(), "Core with type 'wait' should be detected as wait node");
            assertNotNull(core.waitConfig());
            assertEquals(5000L, core.waitConfig().duration());
        }

        @Test
        @DisplayName("Should parse CRUD tables with type")
        void parseCrudTables() {
            List<String> crudTypes = List.of("crud-create-row", "crud-read-row", "crud-update-row", "crud-delete-row");

            for (String crudType : crudTypes) {
                Map<String, Object> tableData = new HashMap<>();
                tableData.put("type", crudType);
                tableData.put("label", "CRUD Op");
                tableData.put("dataSourceId", 123L);

                Map<String, Object> planData = createBasePlan();
                planData.put("tables", List.of(tableData));

                WorkflowPlan plan = WorkflowPlan.fromMap(planData);

                Step table = plan.getTables().get(0);
                assertTrue(table.isCrudStep(), crudType + " should be detected as CRUD step");
                assertEquals(crudType.substring("crud-".length()), table.getCrudOperation());
            }
        }

        @Test
        @DisplayName("Should normalize step labels correctly")
        void normalizeStepLabels() {
            List<String[]> testCases = List.of(
                new String[]{"API Call", "mcp:api_call"},
                new String[]{"Send Email", "mcp:send_email"},
                new String[]{"Process Data", "mcp:process_data"}
            );

            for (String[] testCase : testCases) {
                String label = testCase[0];
                String expectedKey = testCase[1];

                Map<String, Object> planData = createPlanWithStep(
                    "step-id", label, Map.of()
                );

                WorkflowPlan plan = WorkflowPlan.fromMap(planData);
                Step step = plan.getMcps().get(0);

                assertEquals(expectedKey, step.getNormalizedKey(),
                    "Label '" + label + "' should normalize to " + expectedKey);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AGENT PARSING
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Agent Parsing")
    class AgentParsingTests {

        @Test
        @DisplayName("Should parse agent with all parameters")
        void parseAgentWithParams() {
            Map<String, Object> agentData = new HashMap<>();
            agentData.put("id", "agent-1");
            agentData.put("label", "Data Analyzer");
            agentData.put("prompt", "Analyze the data and return insights");
            agentData.put("model", "claude-3-sonnet");
            agentData.put("provider", "anthropic");
            agentData.put("temperature", 0.7);
            agentData.put("maxIterations", 5);
            agentData.put("tools", List.of("tool-uuid-1", "tool-uuid-2"));

            Map<String, Object> planData = createBasePlan();
            planData.put("agents", List.of(agentData));

            WorkflowPlan plan = WorkflowPlan.fromMap(planData);

            assertEquals(1, plan.getAgents().size());
            Agent agent = plan.getAgents().get(0);

            assertEquals("Data Analyzer", agent.label());
            assertEquals("agent:data_analyzer", agent.getNormalizedKey());
            assertEquals("Analyze the data and return insights", agent.prompt());
            assertEquals("claude-3-sonnet", agent.model());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CORE NODE PARSING
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Core Node Parsing")
    class CoreParsingTests {

        @Test
        @DisplayName("Should parse decision core node")
        void parseDecisionNode() {
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "decision-1");
            coreData.put("label", "Check Status");
            coreData.put("type", "decision");
            coreData.put("decisionConditions", List.of(
                Map.of(
                    "branchLabel", "Success",
                    "expression", "{{mcp:api_call.output.status}} == 200"
                ),
                Map.of(
                    "branchLabel", "Error",
                    "expression", "{{mcp:api_call.output.status}} >= 400"
                )
            ));

            Map<String, Object> planData = createBasePlan();
            planData.put("cores", List.of(coreData));

            WorkflowPlan plan = WorkflowPlan.fromMap(planData);

            assertEquals(1, plan.getCores().size());
            Core cn = plan.getCores().get(0);

            assertEquals("decision", cn.type());
            assertEquals("core:check_status", cn.getNormalizedKey());
            assertNotNull(cn.decisionConditions());
            assertEquals(2, cn.decisionConditions().size());
        }

        @Test
        @DisplayName("Should parse loop core node with loopCondition")
        void parseLoopNode() {
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "loop-1");
            coreData.put("label", "Process Items");
            coreData.put("type", "loop");
            coreData.put("loopCondition", "{{core:process_items.iteration}} < 10");
            coreData.put("maxIterations", 100);

            Map<String, Object> planData = createBasePlan();
            planData.put("cores", List.of(coreData));

            WorkflowPlan plan = WorkflowPlan.fromMap(planData);

            Core cn = plan.getCores().get(0);

            assertEquals("loop", cn.type());
            assertEquals("core:process_items", cn.getNormalizedKey());
            assertEquals("{{core:process_items.iteration}} < 10", cn.loopCondition());
        }

        @Test
        @DisplayName("Should parse split core node")
        void parseSplitNode() {
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "split-1");
            coreData.put("label", "Each Customer");
            coreData.put("type", "split");
            coreData.put("list", "{{mcp:fetch.output.customers}}");

            Map<String, Object> planData = createBasePlan();
            planData.put("cores", List.of(coreData));

            WorkflowPlan plan = WorkflowPlan.fromMap(planData);

            Core cn = plan.getCores().get(0);

            assertEquals("split", cn.type());
            assertEquals("core:each_customer", cn.getNormalizedKey());
        }

        @Test
        @DisplayName("Should parse fork core node")
        void parseForkNode() {
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "fork-1");
            coreData.put("label", "Parallel Tasks");
            coreData.put("type", "fork");
            coreData.put("forkOutputs", List.of(
                Map.of("id", "branch-1", "label", "Task A"),
                Map.of("id", "branch-2", "label", "Task B"),
                Map.of("id", "branch-3", "label", "Task C")
            ));

            Map<String, Object> planData = createBasePlan();
            planData.put("cores", List.of(coreData));

            WorkflowPlan plan = WorkflowPlan.fromMap(planData);

            Core cn = plan.getCores().get(0);

            assertEquals("fork", cn.type());
            assertTrue(cn.isFork());
            assertEquals("core:parallel_tasks", cn.getNormalizedKey());
            assertNotNull(cn.forkOutputs());
            assertEquals(3, cn.forkOutputs().size());
        }

        @Test
        @DisplayName("Should parse switch core node")
        void parseSwitchNode() {
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "switch-1");
            coreData.put("label", "Route By Type");
            coreData.put("type", "switch");
            coreData.put("switchExpression", "{{trigger:webhook.payload.type}}");
            coreData.put("switchCases", List.of(
                Map.of("id", "case-1", "type", "case", "label", "Created", "value", "created"),
                Map.of("id", "case-2", "type", "case", "label", "Updated", "value", "updated"),
                Map.of("id", "case-3", "type", "default", "label", "Other")
            ));

            Map<String, Object> planData = createBasePlan();
            planData.put("cores", List.of(coreData));

            WorkflowPlan plan = WorkflowPlan.fromMap(planData);

            Core cn = plan.getCores().get(0);

            assertEquals("switch", cn.type());
            assertTrue(cn.isSwitch());
            assertEquals("core:route_by_type", cn.getNormalizedKey());
            assertEquals("{{trigger:webhook.payload.type}}", cn.switchExpression());
            assertNotNull(cn.switchCases());
            assertEquals(3, cn.switchCases().size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NOTE PARSING
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Note Parsing")
    class NoteParsingTests {

        @Test
        @DisplayName("Should parse note with all properties")
        void parseNoteWithAllProperties() {
            Map<String, Object> noteData = new HashMap<>();
            noteData.put("id", "note-1");
            noteData.put("type", "note");
            noteData.put("label", "Documentation");
            noteData.put("text", "This is a documentation note");
            noteData.put("color", "#FFFACD");
            noteData.put("borderColor", "#FFD700");
            noteData.put("textColor", "#333333");
            noteData.put("width", 200);
            noteData.put("height", 100);
            noteData.put("position", Map.of("x", 50.0, "y", 100.0));

            Map<String, Object> planData = createBasePlan();
            planData.put("notes", List.of(noteData));

            WorkflowPlan plan = WorkflowPlan.fromMap(planData);

            assertEquals(1, plan.getNotes().size());
            Note note = plan.getNotes().get(0);

            assertEquals("note-1", note.id());
            assertEquals("note", note.type());
            assertEquals("This is a documentation note", note.text());
            assertEquals("#FFFACD", note.color());
        }

        @Test
        @DisplayName("Should parse minimal note")
        void parseMinimalNote() {
            Map<String, Object> noteData = new HashMap<>();
            noteData.put("id", "note-minimal");
            noteData.put("text", "Simple note");

            Map<String, Object> planData = createBasePlan();
            planData.put("notes", List.of(noteData));

            WorkflowPlan plan = WorkflowPlan.fromMap(planData);

            assertEquals(1, plan.getNotes().size());
            Note note = plan.getNotes().get(0);

            assertEquals("note-minimal", note.id());
            assertEquals("Simple note", note.text());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE PARSING
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge Parsing")
    class EdgeParsingTests {

        @Test
        @DisplayName("Should parse simple edge")
        void parseSimpleEdge() {
            Map<String, Object> edgeData = Map.of(
                "from", "trigger:webhook",
                "to", "mcp:process"
            );

            Map<String, Object> planData = createBasePlan();
            planData.put("edges", List.of(edgeData));

            WorkflowPlan plan = WorkflowPlan.fromMap(planData);

            assertEquals(1, plan.getEdges().size());
            Edge edge = plan.getEdges().get(0);

            assertEquals("trigger:webhook", edge.from());
            assertEquals("mcp:process", edge.to());
        }

        @Test
        @DisplayName("Should parse edge with params")
        void parseEdgeWithParams() {
            Map<String, Object> edgeData = new HashMap<>();
            edgeData.put("from", "trigger:webhook");
            edgeData.put("to", "mcp:process");
            edgeData.put("params", Map.of(
                "data", "{{trigger:webhook.payload}}"
            ));

            Map<String, Object> planData = createBasePlan();
            planData.put("edges", List.of(edgeData));

            WorkflowPlan plan = WorkflowPlan.fromMap(planData);

            Edge edge = plan.getEdges().get(0);
            assertNotNull(edge.params());
            assertEquals("{{trigger:webhook.payload}}", edge.params().get("data"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // OUTPUT REFERENCE VALIDATION
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Output Reference Validation")
    class OutputReferenceTests {

        @Test
        @DisplayName("Trigger output references should use correct prefix")
        void triggerOutputReferences() {
            // Verify that trigger outputs use trigger: prefix
            Map<String, Object> stepParams = Map.of(
                "data", "{{trigger:my_webhook.payload}}",
                "timestamp", "{{trigger:my_webhook.triggeredAt}}"
            );

            Map<String, Object> planData = createPlanWithTriggerAndStep(
                "webhook-1", "My Webhook", "webhook",
                "step-1", "Process", stepParams
            );

            WorkflowPlan plan = WorkflowPlan.fromMap(planData);

            // Plan should parse correctly
            assertNotNull(plan);
            assertEquals(1, plan.getTriggers().size());
            assertEquals(1, plan.getMcps().size());

            // Params references should be preserved
            Step step = plan.getMcps().get(0);
            assertEquals("{{trigger:my_webhook.payload}}", step.params().get("data"));
        }

        @Test
        @DisplayName("Step output references should use correct prefix")
        void stepOutputReferences() {
            Map<String, Object> step2Params = Map.of(
                "params", "{{mcp:fetch_data.output.response}}",
                "status", "{{mcp:fetch_data.output.status}}"
            );

            Map<String, Object> planData = createBasePlan();
            planData.put("mcps", List.of(
                Map.of("id", "api-1", "label", "Fetch Data", "params", Map.of()),
                Map.of("id", "api-2", "label", "Process", "params", step2Params)
            ));

            WorkflowPlan plan = WorkflowPlan.fromMap(planData);

            Step step2 = plan.getMcps().get(1);
            assertEquals("{{mcp:fetch_data.output.response}}", step2.params().get("params"));
        }

        @Test
        @DisplayName("Agent output references should use correct prefix")
        void agentOutputReferences() {
            Map<String, Object> stepParams = Map.of(
                "analysis", "{{agent:analyzer.response}}",
                "tokenCount", "{{agent:analyzer.tokens_used}}"
            );

            Map<String, Object> planData = createBasePlan();
            planData.put("agents", List.of(
                Map.of("id", "agent-1", "label", "Analyzer", "prompt", "Analyze data")
            ));
            planData.put("mcps", List.of(
                Map.of("id", "step-1", "label", "Use Result", "params", stepParams)
            ));

            WorkflowPlan plan = WorkflowPlan.fromMap(planData);

            Step step = plan.getMcps().get(0);
            assertEquals("{{agent:analyzer.response}}", step.params().get("analysis"));
            assertEquals("{{agent:analyzer.tokens_used}}", step.params().get("tokenCount"));
        }

        @Test
        @DisplayName("Control flow output references should use correct prefixes")
        void controlFlowOutputReferences() {
            Map<String, Object> stepParams = Map.of(
                "branch", "{{core:check.selected_branch}}",
                "iteration", "{{core:process.iteration}}",
                "index", "{{core:items.current_index}}"
            );

            Map<String, Object> planData = createBasePlan();
            planData.put("mcps", List.of(
                Map.of("id", "step-1", "label", "Use Control Data", "params", stepParams)
            ));

            WorkflowPlan plan = WorkflowPlan.fromMap(planData);

            Step step = plan.getMcps().get(0);
            assertEquals("{{core:check.selected_branch}}", step.params().get("branch"));
            assertEquals("{{core:process.iteration}}", step.params().get("iteration"));
            assertEquals("{{core:items.current_index}}", step.params().get("index"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private Map<String, Object> createBasePlan() {
        Map<String, Object> plan = new HashMap<>();
        plan.put("id", "test-plan");
        plan.put("tenant_id", "test-tenant");
        plan.put("triggers", List.of());
        plan.put("mcps", List.of());
        plan.put("edges", List.of());
        return plan;
    }

    private Map<String, Object> createPlanWithTrigger(String id, String label, String type, String strategy) {
        Map<String, Object> plan = createBasePlan();
        plan.put("triggers", List.of(Map.of(
            "id", id,
            "label", label,
            "type", type,
            "strategy", strategy
        )));
        return plan;
    }

    private Map<String, Object> createPlanWithTriggerAndParams(String id, String label, String type,
                                                               String strategy, Map<String, Object> params) {
        Map<String, Object> plan = createBasePlan();
        Map<String, Object> trigger = new HashMap<>();
        trigger.put("id", id);
        trigger.put("label", label);
        trigger.put("type", type);
        trigger.put("strategy", strategy);
        trigger.put("params", params);
        plan.put("triggers", List.of(trigger));
        return plan;
    }

    private Map<String, Object> createPlanWithStep(String id, String label, Map<String, Object> params) {
        Map<String, Object> plan = createBasePlan();
        plan.put("mcps", List.of(Map.of(
            "id", id,
            "label", label,
            "params", params
        )));
        return plan;
    }

    private Map<String, Object> createPlanWithTriggerAndStep(String triggerId, String triggerLabel, String triggerType,
                                                              String stepId, String stepLabel, Map<String, Object> stepParams) {
        Map<String, Object> plan = createBasePlan();
        plan.put("triggers", List.of(Map.of(
            "id", triggerId,
            "label", triggerLabel,
            "type", triggerType,
            "strategy", "single"
        )));
        plan.put("mcps", List.of(Map.of(
            "id", stepId,
            "label", stepLabel,
            "params", stepParams
        )));
        plan.put("edges", List.of(Map.of(
            "from", "trigger:" + triggerLabel.toLowerCase().replace(" ", "_"),
            "to", "mcp:" + stepLabel.toLowerCase().replace(" ", "_")
        )));
        return plan;
    }
}
