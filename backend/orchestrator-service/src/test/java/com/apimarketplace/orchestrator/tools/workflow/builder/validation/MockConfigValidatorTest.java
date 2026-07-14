package com.apimarketplace.orchestrator.tools.workflow.builder.validation;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderValidator.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * Tests for {@link MockConfigValidator}: every node's {@code mock} block is
 * re-validated against the node's REAL current type/ports by parsing a
 * single-node mini plan through {@code WorkflowPlanParser}. Invalid blocks are
 * ERRORS (they fail every run's plan parse); mocks on triggers/notes are
 * WARNINGS (silently ignored at run time).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MockConfigValidator")
class MockConfigValidatorTest {

    private static final String TENANT = "tenant-1";

    @Mock
    private WorkflowBuilderSession session;

    private MockConfigValidator validator;

    @BeforeEach
    void setUp() {
        validator = new MockConfigValidator();
        lenient().when(session.getTenantId()).thenReturn(TENANT);
        lenient().when(session.getMcps()).thenReturn(List.of());
        lenient().when(session.getCores()).thenReturn(List.of());
        lenient().when(session.getTables()).thenReturn(List.of());
        lenient().when(session.getInterfaces()).thenReturn(List.of());
        lenient().when(session.getTriggers()).thenReturn(List.of());
        lenient().when(session.getNotes()).thenReturn(List.of());
    }

    private ValidationResult validate() {
        ValidationResult result = ValidationResult.builder().build();
        validator.validate(session, result);
        return result;
    }

    // ── node map helpers ────────────────────────────────────────────────

    private static Map<String, Object> mcpNode(Object mock) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", "gmail/gmail-list-messages");
        node.put("label", "Fetch Emails");
        if (mock != null) node.put("mock", mock);
        return node;
    }

    private static Map<String, Object> decisionNode(Object mock) {
        Map<String, Object> node = new HashMap<>();
        node.put("id", "decision-1");
        node.put("type", "decision");
        node.put("label", "Check Status");
        node.put("decisionConditions", List.of(
                new HashMap<>(Map.of("id", "c1", "type", "if", "expression", "x > 1")),
                new HashMap<>(Map.of("id", "c2", "type", "else"))));
        if (mock != null) node.put("mock", mock);
        return node;
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Valid mocks produce no findings")
    class ValidMocks {

        @Test
        @DisplayName("A static output mock on an mcp node passes with zero errors and zero warnings")
        void staticMcpMockPasses() {
            lenient().when(session.getMcps()).thenReturn(List.of(
                    mcpNode(Map.of("output", Map.of("messages", List.of(), "result_count", 0)))));

            ValidationResult result = validate();

            assertThat(result.getErrors()).isEmpty();
            assertThat(result.getWarnings()).isEmpty();
        }

        @Test
        @DisplayName("A decision mock with a valid port passes")
        void decisionPortMockPasses() {
            lenient().when(session.getCores()).thenReturn(List.of(decisionNode(Map.of("port", "if"))));

            assertThat(validate().getErrors()).isEmpty();
        }

        @Test
        @DisplayName("A node WITHOUT a mock block is skipped entirely")
        void nodeWithoutMockSkipped() {
            lenient().when(session.getMcps()).thenReturn(List.of(mcpNode(null)));

            ValidationResult result = validate();

            assertThat(result.getErrors()).isEmpty();
            assertThat(result.getWarnings()).isEmpty();
        }

        @Test
        @DisplayName("A node whose mock key holds NULL is skipped")
        void nullMockValueSkipped() {
            Map<String, Object> node = mcpNode(null);
            node.put("mock", null);
            lenient().when(session.getMcps()).thenReturn(List.of(node));

            assertThat(validate().getErrors()).isEmpty();
        }

        @Test
        @DisplayName("An EMPTY mock block (the documented 'clear' form) parses as no mock and passes")
        void emptyMockBlockPasses() {
            lenient().when(session.getMcps()).thenReturn(List.of(mcpNode(Map.of())));

            assertThat(validate().getErrors()).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Invalid mocks become MOCK_INVALID errors with the parser's message + remediation hint")
    class InvalidMocks {

        @Test
        @DisplayName("port on an mcp tool node -> MOCK_INVALID under mcp:<label> with the modify remediation")
        void portOnMcpRejected() {
            lenient().when(session.getMcps()).thenReturn(List.of(mcpNode(Map.of("port", "if"))));

            ValidationResult result = validate();

            assertThat(result.getErrors()).hasSize(1);
            var error = result.getErrors().get(0);
            assertThat(error.code()).isEqualTo("MOCK_INVALID");
            assertThat(error.nodeId()).isEqualTo("mcp:fetch_emails");
            assertThat(error.field()).isEqualTo("mock");
            assertThat(error.message())
                    .contains("port")
                    .contains("workflow(action='modify'")
                    .contains("topics=['mocking']");
        }

        @Test
        @DisplayName("decision mock without a port -> MOCK_INVALID naming the core and 'must select a branch'")
        void decisionWithoutPortRejected() {
            lenient().when(session.getCores()).thenReturn(List.of(
                    decisionNode(Map.of("output", Map.of()))));

            ValidationResult result = validate();

            assertThat(result.getErrors()).hasSize(1);
            var error = result.getErrors().get(0);
            assertThat(error.code()).isEqualTo("MOCK_INVALID");
            assertThat(error.nodeId()).isEqualTo("core:check_status");
            assertThat(error.message()).contains("must select a branch");
        }

        @Test
        @DisplayName("catalog_example on a table node -> MOCK_INVALID under table:<label>")
        void catalogExampleOnTableRejected() {
            Map<String, Object> node = new HashMap<>();
            node.put("type", "crud-find");
            node.put("label", "Find Rows");
            node.put("dataSourceId", "1");
            node.put("mock", Map.of("source", "catalog_example"));
            lenient().when(session.getTables()).thenReturn(List.of(node));

            ValidationResult result = validate();

            assertThat(result.getErrors()).hasSize(1);
            var error = result.getErrors().get(0);
            assertThat(error.code()).isEqualTo("MOCK_INVALID");
            assertThat(error.nodeId()).isEqualTo("table:find_rows");
            assertThat(error.message()).contains("catalog_example");
        }

        @Test
        @DisplayName("port on an interface node -> MOCK_INVALID under interface:<label>")
        void portOnInterfaceRejected() {
            Map<String, Object> node = new HashMap<>();
            node.put("id", "interface-1");
            node.put("label", "Product Card");
            node.put("mock", Map.of("port", "if"));
            lenient().when(session.getInterfaces()).thenReturn(List.of(node));

            ValidationResult result = validate();

            assertThat(result.getErrors()).hasSize(1);
            var error = result.getErrors().get(0);
            assertThat(error.code()).isEqualTo("MOCK_INVALID");
            assertThat(error.nodeId()).isEqualTo("interface:product_card");
            assertThat(error.message()).contains("port");
        }

        @Test
        @DisplayName("a static mock with neither output nor port -> MOCK_INVALID (authoring mistake)")
        void outputlessStaticRejected() {
            lenient().when(session.getMcps()).thenReturn(List.of(mcpNode(Map.of("enabled", true))));

            ValidationResult result = validate();

            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors().get(0).message()).contains("output");
        }

        @Test
        @DisplayName("a node failing section parsing for a NON-mock reason is skipped, not reported as MOCK_INVALID")
        void nonMockParseCrashSwallowed() {
            // decisionConditions holding a String makes parseCores throw a
            // ClassCastException (not IllegalArgumentException) - another
            // validator's finding, the mock check must not crash nor misreport.
            Map<String, Object> node = decisionNode(Map.of("port", "if"));
            node.put("decisionConditions", "not-a-list");
            lenient().when(session.getCores()).thenReturn(List.of(node));

            ValidationResult result = validate();

            assertThat(result.getErrors()).isEmpty();
            assertThat(result.getWarnings()).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Agents live in the session's mcps list - split to their real plan section")
    class AgentSplitting {

        private Map<String, Object> classifyNode(Object mock) {
            Map<String, Object> node = new HashMap<>();
            node.put("id", "a-1");
            node.put("type", "classify");
            node.put("label", "Sort Email");
            node.put("classifyCategories", List.of(
                    Map.of("name", "urgent"), Map.of("name", "normal")));
            if (mock != null) node.put("mock", mock);
            return node;
        }

        @Test
        @DisplayName("A classify node in the mcps list with a valid category port passes (validated as an AGENT, where port is legal)")
        void classifyInMcpsList_portMockValid() {
            lenient().when(session.getMcps()).thenReturn(List.of(classifyNode(Map.of("port", "category_0"))));

            ValidationResult result = validate();

            // If the node had been parsed under "mcps", port would be rejected
            // ("an mcp tool node has no ports") - no error proves the split.
            assertThat(result.getErrors()).isEmpty();
        }

        @Test
        @DisplayName("A classify node in the mcps list with a static-only mock errors under agent:<label>, not mcp:<label>")
        void classifyInMcpsList_errorUsesAgentPrefix() {
            lenient().when(session.getMcps()).thenReturn(List.of(classifyNode(Map.of("output", Map.of("x", 1)))));

            ValidationResult result = validate();

            assertThat(result.getErrors()).hasSize(1);
            var error = result.getErrors().get(0);
            assertThat(error.nodeId()).isEqualTo("agent:sort_email");
            assertThat(error.message()).contains("category_N");
        }

        @Test
        @DisplayName("A plain 'agent' node in the mcps list with a static output mock passes (agents section rules)")
        void plainAgentInMcpsList_staticMockValid() {
            Map<String, Object> node = new HashMap<>();
            node.put("id", "a-2");
            node.put("type", "agent");
            node.put("label", "Research Agent");
            node.put("mock", Map.of("output", Map.of("response", "mocked")));
            lenient().when(session.getMcps()).thenReturn(List.of(node));

            assertThat(validate().getErrors()).isEmpty();
        }

        @Test
        @DisplayName("A guardrail node in the mcps list is also routed to the agents section")
        void guardrailInMcpsList_routedToAgents() {
            Map<String, Object> node = new HashMap<>();
            node.put("id", "g-1");
            node.put("type", "guardrail");
            node.put("label", "Safety Check");
            node.put("mock", Map.of("port", "category_0")); // port on non-classify agent = agent-section error
            lenient().when(session.getMcps()).thenReturn(List.of(node));

            ValidationResult result = validate();

            assertThat(result.getErrors()).hasSize(1);
            var error = result.getErrors().get(0);
            assertThat(error.nodeId()).isEqualTo("agent:safety_check");
            assertThat(error.message()).contains("classify");
        }

        @Test
        @DisplayName("A plain mcp tool node (no agent type) stays in the mcps section: port errors under mcp:<label>")
        void plainMcpStaysInMcpsSection() {
            lenient().when(session.getMcps()).thenReturn(List.of(mcpNode(Map.of("port", "if"))));

            ValidationResult result = validate();

            assertThat(result.getErrors()).hasSize(1);
            assertThat(result.getErrors().get(0).nodeId()).isEqualTo("mcp:fetch_emails");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("Mocks on triggers and notes warn (ignored at run time), never error")
    class TriggerAndNoteWarnings {

        @Test
        @DisplayName("A non-empty mock on a TRIGGER warns MOCK_IGNORED_ON_TRIGGER, pointing at data_inputs")
        void triggerMockWarns() {
            Map<String, Object> node = new HashMap<>();
            node.put("id", "t-1");
            node.put("type", "webhook");
            node.put("label", "My Webhook");
            node.put("mock", Map.of("output", Map.of("x", 1)));
            lenient().when(session.getTriggers()).thenReturn(List.of(node));

            ValidationResult result = validate();

            assertThat(result.getErrors()).isEmpty();
            assertThat(result.getWarnings()).hasSize(1);
            var warning = result.getWarnings().get(0);
            assertThat(warning.code()).isEqualTo("MOCK_IGNORED_ON_TRIGGER");
            assertThat(warning.nodeId()).isEqualTo("trigger:my_webhook");
            assertThat(warning.message()).contains("data_inputs").contains("downstream");
        }

        @Test
        @DisplayName("A non-empty mock on a NOTE warns MOCK_IGNORED_ON_NOTE")
        void noteMockWarns() {
            Map<String, Object> node = new HashMap<>();
            node.put("id", "n-1");
            node.put("label", "Reminder");
            node.put("mock", Map.of("output", Map.of("x", 1)));
            lenient().when(session.getNotes()).thenReturn(List.of(node));

            ValidationResult result = validate();

            assertThat(result.getWarnings()).hasSize(1);
            assertThat(result.getWarnings().get(0).code()).isEqualTo("MOCK_IGNORED_ON_NOTE");
            assertThat(result.getWarnings().get(0).nodeId()).isEqualTo("note:reminder");
        }

        @Test
        @DisplayName("An EMPTY (cleared) mock block on a trigger does NOT warn")
        void clearedTriggerMockSilent() {
            Map<String, Object> node = new HashMap<>();
            node.put("id", "t-1");
            node.put("type", "webhook");
            node.put("label", "My Webhook");
            node.put("mock", Map.of());
            lenient().when(session.getTriggers()).thenReturn(List.of(node));

            assertThat(validate().getWarnings()).isEmpty();
        }

        @Test
        @DisplayName("A trigger without a mock block does not warn")
        void triggerWithoutMockSilent() {
            Map<String, Object> node = new HashMap<>();
            node.put("id", "t-1");
            node.put("type", "webhook");
            node.put("label", "My Webhook");
            lenient().when(session.getTriggers()).thenReturn(List.of(node));

            assertThat(validate().getWarnings()).isEmpty();
        }

        @Test
        @DisplayName("A trigger node without a label falls back to its id as the warning nodeId")
        void triggerWithoutLabelUsesId() {
            Map<String, Object> node = new HashMap<>();
            node.put("id", "t-9");
            node.put("type", "webhook");
            node.put("mock", Map.of("output", Map.of("x", 1)));
            lenient().when(session.getTriggers()).thenReturn(List.of(node));

            ValidationResult result = validate();

            assertThat(result.getWarnings()).hasSize(1);
            assertThat(result.getWarnings().get(0).nodeId()).isEqualTo("t-9");
        }
    }
}
