package com.apimarketplace.orchestrator.domain.workflow;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WorkflowPlanParser")
class WorkflowPlanParserTest {

    @Nested
    @DisplayName("parse()")
    class ParseTests {

        @Test
        @DisplayName("Should parse minimal plan with triggers and steps")
        void shouldParseMinimalPlan() {
            Map<String, Object> planData = new HashMap<>();
            planData.put("triggers", List.of(
                Map.of("id", "t1", "label", "My Trigger", "type", "datasource")
            ));
            planData.put("mcps", List.of(
                Map.of("id", "tool-1", "label", "My Step")
            ));
            planData.put("edges", List.of(
                Map.of("from", "trigger:my_trigger", "to", "mcp:my_step")
            ));

            WorkflowPlan plan = WorkflowPlanParser.parse(planData, "tenant-1");

            assertNotNull(plan);
            assertNotNull(plan.getId());
            assertEquals("tenant-1", plan.getTenantId());
            assertEquals(1, plan.getTriggers().size());
            assertEquals(1, plan.getMcps().size());
            assertEquals(1, plan.getEdges().size());
        }

        @Test
        @DisplayName("Should generate UUID when id is null")
        void shouldGenerateUuidWhenIdNull() {
            Map<String, Object> planData = new HashMap<>();
            planData.put("triggers", List.of());

            WorkflowPlan plan = WorkflowPlanParser.parse(planData, null, "tenant-1");

            assertNotNull(plan.getId());
            assertDoesNotThrow(() -> UUID.fromString(plan.getId()));
        }

        @Test
        @DisplayName("Should generate UUID when id is blank")
        void shouldGenerateUuidWhenIdBlank() {
            Map<String, Object> planData = new HashMap<>();
            planData.put("triggers", List.of());

            WorkflowPlan plan = WorkflowPlanParser.parse(planData, "  ", "tenant-1");

            assertNotNull(plan.getId());
            assertDoesNotThrow(() -> UUID.fromString(plan.getId()));
        }

        @Test
        @DisplayName("Should generate UUID when id is not valid UUID")
        void shouldGenerateUuidWhenInvalidUuid() {
            Map<String, Object> planData = new HashMap<>();
            planData.put("triggers", List.of());

            WorkflowPlan plan = WorkflowPlanParser.parse(planData, "not-a-uuid", "tenant-1");

            assertNotNull(plan.getId());
            assertNotEquals("not-a-uuid", plan.getId());
        }

        @Test
        @DisplayName("Should preserve valid UUID id")
        void shouldPreserveValidUuid() {
            String validUuid = UUID.randomUUID().toString();
            Map<String, Object> planData = new HashMap<>();
            planData.put("triggers", List.of());

            WorkflowPlan plan = WorkflowPlanParser.parse(planData, validUuid, "tenant-1");

            assertEquals(validUuid, plan.getId());
        }

        @Test
        @DisplayName("Should parse empty plan data")
        void shouldParseEmptyPlanData() {
            Map<String, Object> planData = new HashMap<>();

            WorkflowPlan plan = WorkflowPlanParser.parse(planData, "tenant-1");

            assertNotNull(plan);
            assertTrue(plan.getTriggers().isEmpty());
            assertTrue(plan.getMcps().isEmpty());
            assertTrue(plan.getEdges().isEmpty());
        }

        @Test
        @DisplayName("Should parse plan with all sections")
        void shouldParsePlanWithAllSections() {
            Map<String, Object> planData = new HashMap<>();
            planData.put("triggers", List.of(Map.of("id", "t1", "label", "Trigger")));
            planData.put("mcps", List.of(Map.of("id", "tool-1", "label", "Step")));
            planData.put("tables", List.of(Map.of("id", "table-1", "label", "Table", "type", "crud-read-row")));
            planData.put("agents", List.of(Map.of("label", "Agent", "type", "agent")));
            planData.put("cores", List.of(Map.of("id", "core-1", "type", "decision", "label", "Check")));
            planData.put("edges", List.of(Map.of("from", "trigger:trigger", "to", "mcp:step")));
            planData.put("notes", List.of(Map.of("id", "note-1", "text", "A note")));

            WorkflowPlan plan = WorkflowPlanParser.parse(planData, "tenant-1");

            assertEquals(1, plan.getTriggers().size());
            assertEquals(1, plan.getMcps().size());
            assertEquals(1, plan.getEdges().size());
        }
    }

    @Nested
    @DisplayName("normalizeStepId()")
    class NormalizeStepIdTests {

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNull() {
            assertNull(WorkflowPlanParser.normalizeStepId(null));
        }

        @ParameterizedTest
        @DisplayName("Should return null for empty or blank input")
        @ValueSource(strings = {"", "   "})
        void shouldReturnNullForEmpty(String input) {
            assertNull(WorkflowPlanParser.normalizeStepId(input));
        }

        @Test
        @DisplayName("Should preserve valid prefixed step ID")
        void shouldPreserveValidPrefixed() {
            assertEquals("mcp:my_step", WorkflowPlanParser.normalizeStepId("mcp:my_step"));
            assertEquals("trigger:start", WorkflowPlanParser.normalizeStepId("trigger:start"));
            assertEquals("core:decision", WorkflowPlanParser.normalizeStepId("core:decision"));
            assertEquals("agent:classifier", WorkflowPlanParser.normalizeStepId("agent:classifier"));
            assertEquals("table:users", WorkflowPlanParser.normalizeStepId("table:users"));
            assertEquals("note:info", WorkflowPlanParser.normalizeStepId("note:info"));
            assertEquals("interface:form", WorkflowPlanParser.normalizeStepId("interface:form"));
        }

        @Test
        @DisplayName("Should lowercase step IDs")
        void shouldLowercaseStepIds() {
            assertEquals("mcp:my_step", WorkflowPlanParser.normalizeStepId("MCP:My_Step"));
            assertEquals("trigger:start", WorkflowPlanParser.normalizeStepId("Trigger:Start"));
        }

        @Test
        @DisplayName("Should add mcp: prefix to unprefixed IDs")
        void shouldAddMcpPrefix() {
            assertEquals("mcp:mystep", WorkflowPlanParser.normalizeStepId("mystep"));
        }

        @Test
        @DisplayName("Should add mcp: prefix to unknown prefix")
        void shouldAddMcpPrefixForUnknown() {
            assertEquals("mcp:unknown:mystep", WorkflowPlanParser.normalizeStepId("unknown:mystep"));
        }

        @Test
        @DisplayName("Should return null for empty identifier after prefix")
        void shouldReturnNullForEmptyIdentifier() {
            assertNull(WorkflowPlanParser.normalizeStepId("mcp:"));
        }

        @Test
        @DisplayName("Should trim whitespace")
        void shouldTrimWhitespace() {
            assertEquals("mcp:step", WorkflowPlanParser.normalizeStepId("  mcp:step  "));
        }
    }

    @Nested
    @DisplayName("normalizeEdgeRef()")
    class NormalizeEdgeRefTests {

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNull() {
            assertNull(WorkflowPlanParser.normalizeEdgeRef(null));
        }

        @ParameterizedTest
        @DisplayName("Should return null for blank input")
        @ValueSource(strings = {"", "   "})
        void shouldReturnNullForBlank(String input) {
            assertNull(WorkflowPlanParser.normalizeEdgeRef(input));
        }

        @Test
        @DisplayName("Should return null for single segment (no colon)")
        void shouldReturnNullForSingleSegment() {
            assertNull(WorkflowPlanParser.normalizeEdgeRef("nocolon"));
        }

        @Test
        @DisplayName("Should normalize simple edge refs")
        void shouldNormalizeSimpleRefs() {
            assertNotNull(WorkflowPlanParser.normalizeEdgeRef("trigger:start"));
            assertNotNull(WorkflowPlanParser.normalizeEdgeRef("mcp:my_step"));
        }

        @Test
        @DisplayName("Should normalize core edge refs with ports")
        void shouldNormalizeCoreWithPorts() {
            String result = WorkflowPlanParser.normalizeEdgeRef("core:My Decision:if");
            assertNotNull(result);
            assertTrue(result.startsWith("core:"));
            assertTrue(result.endsWith(":if"));
        }

        @Test
        @DisplayName("Should add mcp: prefix for unknown node type")
        void shouldAddMcpPrefixForUnknown() {
            String result = WorkflowPlanParser.normalizeEdgeRef("custom:something");
            assertNotNull(result);
            assertTrue(result.startsWith("mcp:"));
        }
    }

    @Nested
    @DisplayName("parseTriggers()")
    class ParseTriggersTests {

        @Test
        @DisplayName("Should return empty list for null")
        void shouldReturnEmptyForNull() {
            List<Trigger> result = WorkflowPlanParser.parseTriggers(null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should parse trigger with all fields")
        void shouldParseTriggerWithAllFields() {
            Map<String, Object> triggerData = new HashMap<>();
            triggerData.put("id", "ds-1");
            triggerData.put("label", "Users Source");
            triggerData.put("strategy", "batch");
            triggerData.put("type", "datasource");
            triggerData.put("params", Map.of("key", "value"));

            List<Trigger> result = WorkflowPlanParser.parseTriggers(List.of(triggerData));

            assertEquals(1, result.size());
            Trigger trigger = result.get(0);
            assertEquals("ds-1", trigger.id());
            assertEquals("users source", trigger.label());
            assertEquals("batch", trigger.strategy());
            assertEquals("datasource", trigger.type());
        }

        @Test
        @DisplayName("Should generate ID when missing")
        void shouldGenerateIdWhenMissing() {
            Map<String, Object> triggerData = new HashMap<>();
            triggerData.put("label", "Source");

            List<Trigger> result = WorkflowPlanParser.parseTriggers(List.of(triggerData));

            assertEquals(1, result.size());
            assertNotNull(result.get(0).id());
        }

        @Test
        @DisplayName("Should default strategy to 'single'")
        void shouldDefaultStrategy() {
            Map<String, Object> triggerData = new HashMap<>();
            triggerData.put("id", "t1");

            List<Trigger> result = WorkflowPlanParser.parseTriggers(List.of(triggerData));

            assertEquals("single", result.get(0).strategy());
        }

        @Test
        @DisplayName("Should default type to 'datasource'")
        void shouldDefaultType() {
            Map<String, Object> triggerData = new HashMap<>();
            triggerData.put("id", "t1");

            List<Trigger> result = WorkflowPlanParser.parseTriggers(List.of(triggerData));

            assertEquals("datasource", result.get(0).type());
        }

        @Test
        @DisplayName("Should parse chat trigger with chatMatch")
        void shouldParseChatTriggerWithChatMatch() {
            Map<String, Object> triggerData = new HashMap<>();
            triggerData.put("id", "t1");
            triggerData.put("type", "chat");
            triggerData.put("chatMatch", Map.of("type", "STARTS_WITH", "value", "/help"));

            List<Trigger> result = WorkflowPlanParser.parseTriggers(List.of(triggerData));

            assertEquals(1, result.size());
            assertEquals("chat", result.get(0).type());
            assertNotNull(result.get(0).chatMatch());
        }

        @Test
        @DisplayName("Should use 'input' as legacy key for params")
        void shouldUseLegacyInputKey() {
            Map<String, Object> triggerData = new HashMap<>();
            triggerData.put("id", "t1");
            triggerData.put("input", Map.of("legacy", "param"));

            List<Trigger> result = WorkflowPlanParser.parseTriggers(List.of(triggerData));

            assertEquals(1, result.size());
            assertTrue(result.get(0).params().containsKey("legacy"));
        }
    }

    @Nested
    @DisplayName("parseSteps()")
    class ParseStepsTests {

        @Test
        @DisplayName("Should return empty list for null")
        void shouldReturnEmptyForNull() {
            List<Step> result = WorkflowPlanParser.parseSteps(null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should parse step with standard fields")
        void shouldParseStep() {
            Map<String, Object> stepData = new HashMap<>();
            stepData.put("id", "tool-1");
            stepData.put("label", "API Call");
            stepData.put("params", Map.of("url", "https://api.example.com"));

            List<Step> result = WorkflowPlanParser.parseSteps(List.of(stepData));

            assertEquals(1, result.size());
            Step step = result.get(0);
            assertEquals("tool-1", step.id());
            assertEquals("API Call", step.label());
        }

        @Test
        @DisplayName("Should fallback to alias for label")
        void shouldFallbackToAlias() {
            Map<String, Object> stepData = new HashMap<>();
            stepData.put("id", "tool-1");
            stepData.put("alias", "My Alias");

            List<Step> result = WorkflowPlanParser.parseSteps(List.of(stepData));

            assertEquals("My Alias", result.get(0).label());
        }

        @Test
        @DisplayName("Should fallback to tool_id for id")
        void shouldFallbackToToolId() {
            Map<String, Object> stepData = new HashMap<>();
            stepData.put("tool_id", "tool-legacy");
            stepData.put("label", "Step");

            List<Step> result = WorkflowPlanParser.parseSteps(List.of(stepData));

            assertEquals("tool-legacy", result.get(0).id());
        }

        @Test
        @DisplayName("Should default type to 'mcp'")
        void shouldDefaultTypeToMcp() {
            Map<String, Object> stepData = new HashMap<>();
            stepData.put("id", "tool-1");
            stepData.put("label", "Step");

            List<Step> result = WorkflowPlanParser.parseSteps(List.of(stepData));

            assertEquals("mcp", result.get(0).type());
        }
    }

    @Nested
    @DisplayName("parseEdges()")
    class ParseEdgesTests {

        @Test
        @DisplayName("Should return empty list for null")
        void shouldReturnEmptyForNull() {
            List<Edge> result = WorkflowPlanParser.parseEdges(null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should parse valid edge")
        void shouldParseValidEdge() {
            Map<String, Object> edgeData = new HashMap<>();
            edgeData.put("from", "trigger:start");
            edgeData.put("to", "mcp:step1");

            List<Edge> result = WorkflowPlanParser.parseEdges(List.of(edgeData));

            assertEquals(1, result.size());
            assertNotNull(result.get(0).from());
            assertNotNull(result.get(0).to());
        }

        @Test
        @DisplayName("Should filter edges with null from")
        void shouldFilterNullFrom() {
            Map<String, Object> edgeData = new HashMap<>();
            edgeData.put("to", "mcp:step1");

            List<Edge> result = WorkflowPlanParser.parseEdges(List.of(edgeData));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should parse edge with params")
        void shouldParseEdgeWithParams() {
            Map<String, Object> edgeData = new HashMap<>();
            edgeData.put("from", "trigger:start");
            edgeData.put("to", "mcp:step1");
            edgeData.put("params", Map.of("key", "value"));

            List<Edge> result = WorkflowPlanParser.parseEdges(List.of(edgeData));

            assertEquals(1, result.size());
            assertNotNull(result.get(0).params());
        }
    }

    @Nested
    @DisplayName("parseCores()")
    class ParseCoresTests {

        @Test
        @DisplayName("Should return empty list for null")
        void shouldReturnEmptyForNull() {
            List<Core> result = WorkflowPlanParser.parseCores(null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should parse decision core")
        void shouldParseDecisionCore() {
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "core-1");
            coreData.put("type", "decision");
            coreData.put("label", "Check Status");
            coreData.put("decisionConditions", List.of(
                Map.of("id", "cond-1", "type", "if", "label", "if", "expression", "status == 200")
            ));

            List<Core> result = WorkflowPlanParser.parseCores(List.of(coreData));

            assertEquals(1, result.size());
            assertEquals("decision", result.get(0).type());
            assertNotNull(result.get(0).decisionConditions());
        }

        @Test
        @DisplayName("Should parse loop core")
        void shouldParseLoopCore() {
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "core-1");
            coreData.put("type", "loop");
            coreData.put("label", "Repeat");
            coreData.put("loopCondition", "iteration < 10");
            coreData.put("maxIterations", 10);
            coreData.put("strategy", "stop-on-error");

            List<Core> result = WorkflowPlanParser.parseCores(List.of(coreData));

            assertEquals(1, result.size());
            assertEquals("loop", result.get(0).type());
            assertEquals("iteration < 10", result.get(0).loopCondition());
            assertEquals(10, result.get(0).maxIterations());
            assertEquals("stop-on-error", result.get(0).strategy());
        }

        @Test
        @DisplayName("Should parse split core")
        void shouldParseSplitCore() {
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "core-1");
            coreData.put("type", "split");
            coreData.put("label", "Split Items");
            coreData.put("list", "{{trigger:start.items}}");
            coreData.put("maxItems", 50);

            List<Core> result = WorkflowPlanParser.parseCores(List.of(coreData));

            assertEquals(1, result.size());
            assertEquals("split", result.get(0).type());
            assertEquals("{{trigger:start.items}}", result.get(0).list());
            assertEquals(50, result.get(0).maxItems());
        }

        @Test
        @DisplayName("Should skip core with missing id")
        void shouldSkipCoreMissingId() {
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("type", "decision");

            List<Core> result = WorkflowPlanParser.parseCores(List.of(coreData));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should skip core with missing type")
        void shouldSkipCoreMissingType() {
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "core-1");

            List<Core> result = WorkflowPlanParser.parseCores(List.of(coreData));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should parse loop core with legacy maxIteration key")
        void shouldParseLegacyMaxIteration() {
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "core-1");
            coreData.put("type", "loop");
            coreData.put("label", "Loop");
            coreData.put("maxIteration", 5);

            List<Core> result = WorkflowPlanParser.parseCores(List.of(coreData));

            assertEquals(1, result.size());
            assertEquals(5, result.get(0).maxIterations());
        }

        @Test
        @DisplayName("Should parse split core with legacy listExpression key")
        void shouldParseLegacyListExpression() {
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "core-1");
            coreData.put("type", "split");
            coreData.put("label", "Split");
            coreData.put("listExpression", "{{data.items}}");

            List<Core> result = WorkflowPlanParser.parseCores(List.of(coreData));

            assertEquals(1, result.size());
            assertEquals("{{data.items}}", result.get(0).list());
        }

        @Test
        @DisplayName("Should parse transform core")
        void shouldParseTransformCore() {
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "core-1");
            coreData.put("type", "transform");
            coreData.put("label", "Transform");
            coreData.put("transform", Map.of(
                "mappings", List.of(
                    Map.of("label", "output", "expression", "input.name")
                )
            ));

            List<Core> result = WorkflowPlanParser.parseCores(List.of(coreData));

            assertEquals(1, result.size());
            assertNotNull(result.get(0).transformConfig());
        }

        @Test
        @DisplayName("Should parse wait core")
        void shouldParseWaitCore() {
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "core-1");
            coreData.put("type", "wait");
            coreData.put("label", "Wait");
            coreData.put("wait", Map.of("duration", 5000));

            List<Core> result = WorkflowPlanParser.parseCores(List.of(coreData));

            assertEquals(1, result.size());
            assertNotNull(result.get(0).waitConfig());
        }
    }

    @Nested
    @DisplayName("parseAgents()")
    class ParseAgentsTests {

        @Test
        @DisplayName("Should return empty list for null")
        void shouldReturnEmptyForNull() {
            List<Agent> result = WorkflowPlanParser.parseAgents(null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should parse agent with standard fields")
        void shouldParseAgent() {
            Map<String, Object> agentData = new HashMap<>();
            agentData.put("label", "My Agent");
            agentData.put("type", "agent");
            agentData.put("provider", "openai");
            agentData.put("model", "gpt-4");
            agentData.put("systemPrompt", "You are helpful");
            agentData.put("prompt", "Analyze this");
            agentData.put("temperature", 0.7);
            agentData.put("maxTokens", 1000);

            List<Agent> result = WorkflowPlanParser.parseAgents(List.of(agentData));

            assertEquals(1, result.size());
            assertEquals("My Agent", result.get(0).label());
        }

        @Test
        @DisplayName("Should skip agent with missing label")
        void shouldSkipMissingLabel() {
            Map<String, Object> agentData = new HashMap<>();
            agentData.put("type", "agent");

            List<Agent> result = WorkflowPlanParser.parseAgents(List.of(agentData));

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("parseNotes()")
    class ParseNotesTests {

        @Test
        @DisplayName("Should return empty list for null")
        void shouldReturnEmptyForNull() {
            List<Note> result = WorkflowPlanParser.parseNotes(null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should parse note with all fields")
        void shouldParseNote() {
            Map<String, Object> noteData = new HashMap<>();
            noteData.put("id", "note-1");
            noteData.put("text", "Important note");
            noteData.put("label", "Note Label");
            noteData.put("color", "#FFFF00");
            noteData.put("width", 200);
            noteData.put("height", 100);

            List<Note> result = WorkflowPlanParser.parseNotes(List.of(noteData));

            assertEquals(1, result.size());
            assertEquals("note-1", result.get(0).id());
            assertEquals("Important note", result.get(0).text());
        }

        @Test
        @DisplayName("Should skip note with missing id")
        void shouldSkipMissingId() {
            Map<String, Object> noteData = new HashMap<>();
            noteData.put("text", "A note");

            List<Note> result = WorkflowPlanParser.parseNotes(List.of(noteData));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should default text to empty string when null")
        void shouldDefaultTextToEmpty() {
            Map<String, Object> noteData = new HashMap<>();
            noteData.put("id", "note-1");

            List<Note> result = WorkflowPlanParser.parseNotes(List.of(noteData));

            assertEquals(1, result.size());
            assertEquals("", result.get(0).text());
        }
    }

    @Nested
    @DisplayName("parseTables()")
    class ParseTablesTests {

        @Test
        @DisplayName("Should return empty list for null")
        void shouldReturnEmptyForNull() {
            List<Step> result = WorkflowPlanParser.parseTables(null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should parse table with type")
        void shouldParseTable() {
            Map<String, Object> tableData = new HashMap<>();
            tableData.put("id", "table-1");
            tableData.put("label", "Users");
            tableData.put("type", "crud-read-row");

            List<Step> result = WorkflowPlanParser.parseTables(List.of(tableData));

            assertEquals(1, result.size());
            assertEquals("crud-read-row", result.get(0).type());
        }

        @Test
        @DisplayName("Should skip table with missing label")
        void shouldSkipMissingLabel() {
            Map<String, Object> tableData = new HashMap<>();
            tableData.put("type", "crud-read-row");

            List<Step> result = WorkflowPlanParser.parseTables(List.of(tableData));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should skip table with missing type")
        void shouldSkipMissingType() {
            Map<String, Object> tableData = new HashMap<>();
            tableData.put("label", "Users");

            List<Step> result = WorkflowPlanParser.parseTables(List.of(tableData));

            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("parseTables() - CRUD rows extraction")
    class ParseTablesCrudRowsTests {

        /**
         * Reproduces the bug where agent-generated plans put rows inside "params"
         * instead of a top-level "crud" block. Before the fix, CrudConfig.rows was
         * empty because the fallback only triggered on "where" or "limit" presence.
         */
        @Test
        @DisplayName("Should extract rows from params when no crud block exists (agent plan format)")
        void shouldExtractRowsFromParams() {
            Map<String, Object> tableData = new HashMap<>();
            tableData.put("id", "crud/create-row");
            tableData.put("type", "crud-create-row");
            tableData.put("label", "Save Result");
            tableData.put("dataSourceId", 2);
            tableData.put("params", Map.of(
                "dataSourceId", 2,
                "rows", List.of(Map.of("columns", Map.of(
                    "message", "{{core:split.output.current_item.message}}",
                    "category", "{{agent:classify.output.selected_category}}"
                )))
            ));

            List<Step> result = WorkflowPlanParser.parseTables(List.of(tableData));

            assertEquals(1, result.size());
            Step step = result.get(0);
            assertNotNull(step.crud(), "CrudConfig should not be null when rows are in params");
            assertEquals(1, step.crud().rows().size(), "Should have 1 row");
            assertTrue(step.crud().rows().get(0).columns().containsKey("message"));
            assertTrue(step.crud().rows().get(0).columns().containsKey("category"));
        }

        @Test
        @DisplayName("Should extract rows from explicit crud block (frontend format)")
        void shouldExtractRowsFromCrudBlock() {
            Map<String, Object> tableData = new HashMap<>();
            tableData.put("id", "crud/create-row");
            tableData.put("type", "crud-create-row");
            tableData.put("label", "Save Result");
            tableData.put("dataSourceId", 2);
            tableData.put("crud", Map.of(
                "rows", List.of(Map.of("columns", Map.of("name", "test")))
            ));

            List<Step> result = WorkflowPlanParser.parseTables(List.of(tableData));

            assertEquals(1, result.size());
            assertNotNull(result.get(0).crud());
            assertEquals(1, result.get(0).crud().rows().size());
            assertEquals("test", result.get(0).crud().rows().get(0).columns().get("name"));
        }

        @Test
        @DisplayName("Should extract rows from top-level fields (legacy format)")
        void shouldExtractRowsFromTopLevel() {
            Map<String, Object> tableData = new HashMap<>();
            tableData.put("id", "crud/create-row");
            tableData.put("type", "crud-create-row");
            tableData.put("label", "Save Result");
            tableData.put("dataSourceId", 2);
            tableData.put("rows", List.of(Map.of("columns", Map.of("col1", "val1"))));

            List<Step> result = WorkflowPlanParser.parseTables(List.of(tableData));

            assertEquals(1, result.size());
            assertNotNull(result.get(0).crud());
            assertEquals(1, result.get(0).crud().rows().size());
        }

        @Test
        @DisplayName("Should extract where from params when no crud block exists")
        void shouldExtractWhereFromParams() {
            Map<String, Object> tableData = new HashMap<>();
            tableData.put("id", "crud/read-row");
            tableData.put("type", "crud-read-row");
            tableData.put("label", "Read Users");
            tableData.put("dataSourceId", 2);
            tableData.put("params", Map.of(
                "where", Map.of("column", "id", "operator", "=", "value", "123"),
                "limit", 10
            ));

            List<Step> result = WorkflowPlanParser.parseTables(List.of(tableData));

            assertEquals(1, result.size());
            assertNotNull(result.get(0).crud());
            assertNotNull(result.get(0).crud().where());
            assertEquals("id", result.get(0).crud().where().column());
            assertEquals("=", result.get(0).crud().where().operator());
            assertEquals("123", result.get(0).crud().where().value());
            assertEquals(10, result.get(0).crud().limit());
        }

        @Test
        @DisplayName("Should prefer crud block over params when both exist")
        void shouldPreferCrudBlockOverParams() {
            Map<String, Object> tableData = new HashMap<>();
            tableData.put("id", "crud/create-row");
            tableData.put("type", "crud-create-row");
            tableData.put("label", "Save");
            tableData.put("dataSourceId", 2);
            tableData.put("crud", Map.of(
                "rows", List.of(Map.of("columns", Map.of("from_crud", "true")))
            ));
            tableData.put("params", Map.of(
                "rows", List.of(Map.of("columns", Map.of("from_params", "true")))
            ));

            List<Step> result = WorkflowPlanParser.parseTables(List.of(tableData));

            assertEquals(1, result.size());
            assertNotNull(result.get(0).crud());
            assertTrue(result.get(0).crud().rows().get(0).columns().containsKey("from_crud"),
                "crud block should take precedence over params");
        }

        @Test
        @DisplayName("Should handle multiple rows with template expressions from params")
        void shouldHandleMultipleRowsFromParams() {
            Map<String, Object> row1 = Map.of("columns", Map.of(
                "message", "{{core:split.output.current_item.message}}",
                "passed", "{{agent:guardrail.output.passed}}"
            ));
            Map<String, Object> row2 = Map.of("columns", Map.of(
                "message", "second row",
                "passed", "true"
            ));

            Map<String, Object> tableData = new HashMap<>();
            tableData.put("id", "crud/create-row");
            tableData.put("type", "crud-create-row");
            tableData.put("label", "Save Batch");
            tableData.put("dataSourceId", 2);
            tableData.put("params", Map.of("dataSourceId", 2, "rows", List.of(row1, row2)));

            List<Step> result = WorkflowPlanParser.parseTables(List.of(tableData));

            assertEquals(1, result.size());
            assertNotNull(result.get(0).crud());
            assertEquals(2, result.get(0).crud().rows().size());
        }

        @Test
        @DisplayName("CrudConfig should be null when no crud fields exist anywhere")
        void shouldReturnNullCrudWhenNoFields() {
            Map<String, Object> tableData = new HashMap<>();
            tableData.put("id", "crud/read-row");
            tableData.put("type", "crud-read-row");
            tableData.put("label", "Read All");
            tableData.put("dataSourceId", 2);

            List<Step> result = WorkflowPlanParser.parseTables(List.of(tableData));

            assertEquals(1, result.size());
            assertNull(result.get(0).crud(), "CrudConfig should be null when no crud fields exist");
        }

        @Test
        @DisplayName("Should extract set from params for update operations")
        void shouldExtractSetFromParams() {
            Map<String, Object> tableData = new HashMap<>();
            tableData.put("id", "crud/update-row");
            tableData.put("type", "crud-update-row");
            tableData.put("label", "Update Status");
            tableData.put("dataSourceId", 2);
            tableData.put("params", Map.of(
                "where", Map.of("column", "id", "operator", "=", "value", "1"),
                "set", Map.of("status", "active")
            ));

            List<Step> result = WorkflowPlanParser.parseTables(List.of(tableData));

            assertEquals(1, result.size());
            assertNotNull(result.get(0).crud());
            assertEquals("active", result.get(0).crud().set().get("status"));
        }
    }

    @Nested
    @DisplayName("parseCores() - Config deserialization via parseConfigSafe")
    class ParseCoresConfigTests {

        @Test
        @DisplayName("Should parse CodeConfig from core definition")
        void shouldParseCodeConfig() {
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "core-1");
            coreData.put("type", "code");
            coreData.put("label", "Run Code");
            coreData.put("code", Map.of("language", "javascript", "code", "console.log('hi');", "timeoutSeconds", 15));

            List<Core> result = WorkflowPlanParser.parseCores(List.of(coreData));

            assertEquals(1, result.size());
            assertNotNull(result.get(0).codeConfig(), "CodeConfig should not be null after parseConfigSafe");
            assertEquals("javascript", result.get(0).codeConfig().language());
            assertEquals("console.log('hi');", result.get(0).codeConfig().code());
            assertEquals(15, result.get(0).codeConfig().timeoutSeconds());
        }

        @Test
        @DisplayName("Should parse SendEmailConfig from core definition")
        void shouldParseSendEmailConfig() {
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "core-1");
            coreData.put("type", "send_email");
            coreData.put("label", "Send Email");
            coreData.put("sendEmail", Map.of(
                "toEmail", "user@example.com",
                "subject", "Hello",
                "body", "World",
                "isHtml", false
            ));

            List<Core> result = WorkflowPlanParser.parseCores(List.of(coreData));

            assertEquals(1, result.size());
            assertNotNull(result.get(0).sendEmailConfig(), "SendEmailConfig should not be null after parseConfigSafe");
            assertEquals("user@example.com", result.get(0).sendEmailConfig().toEmail());
            assertEquals("Hello", result.get(0).sendEmailConfig().subject());
        }

        @Test
        @DisplayName("Should parse FilterConfig from core definition")
        void shouldParseFilterConfig() {
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "core-1");
            coreData.put("type", "filter");
            coreData.put("label", "Filter Items");
            coreData.put("filter", Map.of(
                "conditions", List.of(Map.of("field", "status", "operator", "equals", "value", "active")),
                "logic", "AND"
            ));

            List<Core> result = WorkflowPlanParser.parseCores(List.of(coreData));

            assertEquals(1, result.size());
            assertNotNull(result.get(0).filterConfig(), "FilterConfig should not be null after parseConfigSafe");
        }

        @Test
        @DisplayName("Should return null config when key is absent")
        void shouldReturnNullConfigWhenKeyAbsent() {
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "core-1");
            coreData.put("type", "code");
            coreData.put("label", "Empty Code");
            // No "code" key

            List<Core> result = WorkflowPlanParser.parseCores(List.of(coreData));

            assertEquals(1, result.size());
            assertNull(result.get(0).codeConfig(), "CodeConfig should be null when key is absent");
        }

        @Test
        @DisplayName("Should return null config when value is not a map")
        void shouldReturnNullConfigWhenValueNotMap() {
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "core-1");
            coreData.put("type", "code");
            coreData.put("label", "Bad Code");
            coreData.put("code", "not-a-map");

            List<Core> result = WorkflowPlanParser.parseCores(List.of(coreData));

            assertEquals(1, result.size());
            assertNull(result.get(0).codeConfig(), "CodeConfig should be null when value is not a Map");
        }

        @Test
        @DisplayName("Should parse SubWorkflowConfig from core definition")
        void shouldParseSubWorkflowConfig() {
            Map<String, Object> coreData = new HashMap<>();
            coreData.put("id", "core-1");
            coreData.put("type", "sub_workflow");
            coreData.put("label", "Call Sub");
            coreData.put("subWorkflow", Map.of(
                "workflowId", "11111111-1111-1111-1111-111111111111",
                "timeoutSeconds", 120,
                "maxDepth", 3
            ));

            List<Core> result = WorkflowPlanParser.parseCores(List.of(coreData));

            assertEquals(1, result.size());
            assertNotNull(result.get(0).subWorkflowConfig(), "SubWorkflowConfig should not be null");
            assertEquals("11111111-1111-1111-1111-111111111111", result.get(0).subWorkflowConfig().workflowId());
            assertEquals(120, result.get(0).subWorkflowConfig().timeoutSeconds());
        }
    }

    @Nested
    @DisplayName("parseInterfaces()")
    class ParseInterfacesTests {

        @Test
        @DisplayName("Should return empty list for null")
        void shouldReturnEmptyForNull() {
            List<InterfaceDef> result = WorkflowPlanParser.parseInterfaces(null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should parse interface with all fields")
        void shouldParseInterface() {
            Map<String, Object> ifaceData = new HashMap<>();
            ifaceData.put("id", "uuid-123");
            ifaceData.put("label", "My Form");
            ifaceData.put("actionMapping", Map.of("#submit", "trigger:form_submit"));
            ifaceData.put("variableMapping", Map.of("name", "{{trigger:start.name}}"));
            ifaceData.put("showPreview", true);
            ifaceData.put("position", Map.of("x", 100, "y", 200));

            List<InterfaceDef> result = WorkflowPlanParser.parseInterfaces(List.of(ifaceData));

            assertEquals(1, result.size());
            InterfaceDef def = result.get(0);
            assertEquals("uuid-123", def.id());
            assertEquals("My Form", def.label());
            assertEquals("trigger:form_submit", def.actionMapping().get("#submit"));
            assertEquals("{{trigger:start.name}}", def.variableMapping().get("name"));
            assertTrue(def.showPreview());
        }

        @Test
        @DisplayName("Should parse interface with minimal fields (id and label)")
        void shouldParseInterfaceWithMinimalFields() {
            Map<String, Object> ifaceData = new HashMap<>();
            ifaceData.put("id", "uuid-123");
            ifaceData.put("label", "Display");

            List<InterfaceDef> result = WorkflowPlanParser.parseInterfaces(List.of(ifaceData));

            assertEquals(1, result.size());
            assertEquals("uuid-123", result.get(0).id());
            assertEquals("Display", result.get(0).label());
        }

        @Test
        @DisplayName("Should skip interface with missing label")
        void shouldSkipMissingLabel() {
            Map<String, Object> ifaceData = new HashMap<>();
            ifaceData.put("id", "uuid-123");

            List<InterfaceDef> result = WorkflowPlanParser.parseInterfaces(List.of(ifaceData));

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should handle empty action and variable mappings")
        void shouldHandleEmptyMappings() {
            Map<String, Object> ifaceData = new HashMap<>();
            ifaceData.put("id", "uuid-123");
            ifaceData.put("label", "Simple");

            List<InterfaceDef> result = WorkflowPlanParser.parseInterfaces(List.of(ifaceData));

            assertEquals(1, result.size());
            assertTrue(result.get(0).actionMapping().isEmpty());
            assertTrue(result.get(0).variableMapping().isEmpty());
        }

        @Test
        @DisplayName("Should parse isEntryInterface flag")
        void shouldParseIsEntryInterfaceFlag() {
            Map<String, Object> ifaceData = new HashMap<>();
            ifaceData.put("id", "uuid-123");
            ifaceData.put("label", "Entry Form");
            ifaceData.put("isEntryInterface", true);

            List<InterfaceDef> result = WorkflowPlanParser.parseInterfaces(List.of(ifaceData));

            assertEquals(1, result.size());
            assertTrue(result.get(0).isEntryInterface());
        }

        @Test
        @DisplayName("Should default isEntryInterface to false when missing")
        void shouldDefaultIsEntryInterfaceToFalse() {
            Map<String, Object> ifaceData = new HashMap<>();
            ifaceData.put("id", "uuid-123");
            ifaceData.put("label", "Regular Form");

            List<InterfaceDef> result = WorkflowPlanParser.parseInterfaces(List.of(ifaceData));

            assertEquals(1, result.size());
            assertFalse(result.get(0).isEntryInterface());
        }

        @Test
        @DisplayName("Should parse generateScreenshot toggle")
        void shouldParseGenerateScreenshotFlag() {
            Map<String, Object> ifaceData = new HashMap<>();
            ifaceData.put("id", "uuid-123");
            ifaceData.put("label", "Form With Capture");
            ifaceData.put("generateScreenshot", true);

            List<InterfaceDef> result = WorkflowPlanParser.parseInterfaces(List.of(ifaceData));

            assertEquals(1, result.size());
            assertTrue(result.get(0).generateScreenshot());
        }

        @Test
        @DisplayName("Should default generateScreenshot to false when missing (back-compat for pre-PR2 plans)")
        void shouldDefaultGenerateScreenshotToFalse() {
            Map<String, Object> ifaceData = new HashMap<>();
            ifaceData.put("id", "uuid-123");
            ifaceData.put("label", "Pre-PR2 Form");

            List<InterfaceDef> result = WorkflowPlanParser.parseInterfaces(List.of(ifaceData));

            assertEquals(1, result.size());
            assertFalse(result.get(0).generateScreenshot());
        }

        @Test
        @DisplayName("Should parse exposeRenderedSource toggle")
        void shouldParseExposeRenderedSourceFlag() {
            Map<String, Object> ifaceData = new HashMap<>();
            ifaceData.put("id", "uuid-456");
            ifaceData.put("label", "Form With Source");
            ifaceData.put("exposeRenderedSource", true);

            List<InterfaceDef> result = WorkflowPlanParser.parseInterfaces(List.of(ifaceData));

            assertEquals(1, result.size());
            assertTrue(result.get(0).exposeRenderedSource());
        }

        @Test
        @DisplayName("Should default exposeRenderedSource to false when missing (back-compat for pre-PR3 plans)")
        void shouldDefaultExposeRenderedSourceToFalse() {
            Map<String, Object> ifaceData = new HashMap<>();
            ifaceData.put("id", "uuid-789");
            ifaceData.put("label", "Pre-PR3 Form");

            List<InterfaceDef> result = WorkflowPlanParser.parseInterfaces(List.of(ifaceData));

            assertEquals(1, result.size());
            assertFalse(result.get(0).exposeRenderedSource());
        }

        @Test
        @DisplayName("Both toggles parsed independently when both set in plan JSON")
        void shouldParseBothTogglesIndependently() {
            Map<String, Object> ifaceData = new HashMap<>();
            ifaceData.put("id", "uuid-both");
            ifaceData.put("label", "Both Toggles");
            ifaceData.put("generateScreenshot", true);
            ifaceData.put("exposeRenderedSource", true);

            List<InterfaceDef> result = WorkflowPlanParser.parseInterfaces(List.of(ifaceData));

            assertEquals(1, result.size());
            assertTrue(result.get(0).generateScreenshot());
            assertTrue(result.get(0).exposeRenderedSource());
        }

        @Test
        @DisplayName("Should parse generatePdf toggle + pdfFormat/pdfLandscape options")
        void shouldParsePdfFieldsFromPlan() {
            Map<String, Object> ifaceData = new HashMap<>();
            ifaceData.put("id", "uuid-pdf");
            ifaceData.put("label", "Form With Pdf");
            ifaceData.put("generatePdf", true);
            ifaceData.put("pdfFormat", "Letter");
            ifaceData.put("pdfLandscape", true);

            List<InterfaceDef> result = WorkflowPlanParser.parseInterfaces(List.of(ifaceData));

            assertEquals(1, result.size());
            assertTrue(result.get(0).generatePdf());
            assertEquals("Letter", result.get(0).pdfFormat());
            assertTrue(result.get(0).pdfLandscape());
        }

        @Test
        @DisplayName("Should default PDF fields when missing (back-compat: generatePdf=false, format=null, landscape=false)")
        void shouldDefaultPdfFieldsWhenMissing() {
            Map<String, Object> ifaceData = new HashMap<>();
            ifaceData.put("id", "uuid-nopdf");
            ifaceData.put("label", "Pre-Pdf Form");

            List<InterfaceDef> result = WorkflowPlanParser.parseInterfaces(List.of(ifaceData));

            assertEquals(1, result.size());
            assertFalse(result.get(0).generatePdf());
            assertNull(result.get(0).pdfFormat());
            assertFalse(result.get(0).pdfLandscape());
        }

        @Test
        @DisplayName("Should parse guardrail rules from Map format (builder tool)")
        void shouldParseGuardrailRulesFromMapFormat() {
            // Builder agent generates guardrailRules as Map<key, description>
            // Parser should convert to List<Map> with id, type, description fields
            Map<String, Object> planData = new HashMap<>();
            planData.put("triggers", List.of(
                Map.of("id", "t1", "label", "Start", "type", "manual")
            ));
            Map<String, Object> agentData = new HashMap<>();
            agentData.put("id", "a1");
            agentData.put("label", "My Guard");
            agentData.put("agentType", "guardrail");
            // Map format from builder tool
            agentData.put("guardrailRules", Map.of(
                "pii", "Block PII data",
                "toxicity", "Block offensive content"
            ));
            planData.put("agents", List.of(agentData));
            planData.put("mcps", List.of());
            planData.put("edges", List.of(
                Map.of("from", "trigger:start", "to", "agent:my_guard")
            ));

            WorkflowPlan plan = WorkflowPlanParser.parse(planData, "tenant-1");

            assertNotNull(plan);
            assertEquals(1, plan.getAgents().size());
            List<Map<String, Object>> rules = plan.getAgents().get(0).guardrailRules();
            assertNotNull(rules);
            assertEquals(2, rules.size());
            // Each rule should have id, type, and description
            for (Map<String, Object> rule : rules) {
                assertNotNull(rule.get("id"));
                assertNotNull(rule.get("type"));
                assertNotNull(rule.get("description"));
                // id and type should be the same (key from the map)
                assertEquals(rule.get("id"), rule.get("type"));
            }
        }

        @Test
        @DisplayName("Should parse guardrail rules from List format (frontend)")
        void shouldParseGuardrailRulesFromListFormat() {
            Map<String, Object> planData = new HashMap<>();
            planData.put("triggers", List.of(
                Map.of("id", "t1", "label", "Start", "type", "manual")
            ));
            Map<String, Object> agentData = new HashMap<>();
            agentData.put("id", "a1");
            agentData.put("label", "My Guard");
            agentData.put("agentType", "guardrail");
            // List format from frontend
            agentData.put("guardrailRules", List.of(
                Map.of("type", "pii", "description", "Block PII")
            ));
            planData.put("agents", List.of(agentData));
            planData.put("mcps", List.of());
            planData.put("edges", List.of(
                Map.of("from", "trigger:start", "to", "agent:my_guard")
            ));

            WorkflowPlan plan = WorkflowPlanParser.parse(planData, "tenant-1");

            assertNotNull(plan);
            List<Map<String, Object>> rules = plan.getAgents().get(0).guardrailRules();
            assertNotNull(rules);
            assertEquals(1, rules.size());
            assertEquals("pii", rules.get(0).get("type"));
        }

        @Test
        @DisplayName("Should parse interfaces in full plan")
        void shouldParseInterfacesInFullPlan() {
            Map<String, Object> planData = new HashMap<>();
            planData.put("triggers", List.of(
                Map.of("id", "t1", "label", "Start", "type", "manual")
            ));
            planData.put("mcps", List.of());
            planData.put("edges", List.of(
                Map.of("from", "trigger:start", "to", "interface:my_form")
            ));
            planData.put("interfaces", List.of(
                Map.of("id", "uuid-123", "label", "My Form")
            ));

            WorkflowPlan plan = WorkflowPlanParser.parse(planData, "tenant-1");

            assertNotNull(plan);
            assertEquals(1, plan.getInterfaces().size());
            assertEquals("My Form", plan.getInterfaces().get(0).label());
        }
    }
}
