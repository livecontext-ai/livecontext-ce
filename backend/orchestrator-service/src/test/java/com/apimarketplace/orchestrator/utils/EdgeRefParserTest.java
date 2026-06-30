package com.apimarketplace.orchestrator.utils;

import com.apimarketplace.orchestrator.utils.EdgeRefParser.DecisionPort;
import com.apimarketplace.orchestrator.utils.EdgeRefParser.EdgeRef;
import com.apimarketplace.orchestrator.utils.EdgeRefParser.ForkPort;
import com.apimarketplace.orchestrator.utils.EdgeRefParser.LoopPort;
import com.apimarketplace.orchestrator.utils.EdgeRefParser.SwitchPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EdgeRefParser utility class.
 *
 * This class parses V2 edge reference format with support for:
 * - 7 node type prefixes (trigger, mcp, table, agent, core, note, interface)
 * - Ports for core nodes (decision, switch, loop, fork)
 */
@DisplayName("EdgeRefParser")
class EdgeRefParserTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // parse() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("parse()")
    class ParseTests {

        @Test
        @DisplayName("Should parse simple trigger reference")
        void shouldParseSimpleTriggerRef() {
            EdgeRef ref = EdgeRefParser.parse("trigger:webhook");

            assertNotNull(ref);
            assertEquals("trigger", ref.nodeType());
            assertEquals("webhook", ref.nodeLabel());
            assertNull(ref.port());
            assertFalse(ref.hasPort());
            assertEquals("trigger:webhook", ref.getNodeKey());
        }

        @Test
        @DisplayName("Should parse mcp reference")
        void shouldParseMcpRef() {
            EdgeRef ref = EdgeRefParser.parse("mcp:api_call");

            assertNotNull(ref);
            assertEquals("mcp", ref.nodeType());
            assertEquals("api_call", ref.nodeLabel());
            assertNull(ref.port());
        }

        @Test
        @DisplayName("Should parse table reference")
        void shouldParseTableRef() {
            EdgeRef ref = EdgeRefParser.parse("table:users");

            assertNotNull(ref);
            assertEquals("table", ref.nodeType());
            assertEquals("users", ref.nodeLabel());
        }

        @Test
        @DisplayName("Should parse agent reference")
        void shouldParseAgentRef() {
            EdgeRef ref = EdgeRefParser.parse("agent:analyzer");

            assertNotNull(ref);
            assertEquals("agent", ref.nodeType());
            assertEquals("analyzer", ref.nodeLabel());
        }

        @Test
        @DisplayName("Should parse note reference")
        void shouldParseNoteRef() {
            EdgeRef ref = EdgeRefParser.parse("note:my_note");

            assertNotNull(ref);
            assertEquals("note", ref.nodeType());
            assertEquals("my_note", ref.nodeLabel());
        }

        @Test
        @DisplayName("Should parse interface reference")
        void shouldParseInterfaceRef() {
            EdgeRef ref = EdgeRefParser.parse("interface:user_form");

            assertNotNull(ref);
            assertEquals("interface", ref.nodeType());
            assertEquals("user_form", ref.nodeLabel());
        }

        @Test
        @DisplayName("Should parse core reference without port")
        void shouldParseCoreRefWithoutPort() {
            EdgeRef ref = EdgeRefParser.parse("core:check");

            assertNotNull(ref);
            assertEquals("core", ref.nodeType());
            assertEquals("check", ref.nodeLabel());
            assertNull(ref.port());
            assertFalse(ref.hasPort());
        }

        @Test
        @DisplayName("Should parse core reference with decision 'if' port")
        void shouldParseCoreRefWithIfPort() {
            EdgeRef ref = EdgeRefParser.parse("core:check:if");

            assertNotNull(ref);
            assertEquals("core", ref.nodeType());
            assertEquals("check", ref.nodeLabel());
            assertEquals("if", ref.port());
            assertTrue(ref.hasPort());
            assertEquals("core:check", ref.getNodeKey());
        }

        @Test
        @DisplayName("Should parse core reference with decision 'else' port")
        void shouldParseCoreRefWithElsePort() {
            EdgeRef ref = EdgeRefParser.parse("core:check:else");

            assertNotNull(ref);
            assertEquals("core", ref.nodeType());
            assertEquals("check", ref.nodeLabel());
            assertEquals("else", ref.port());
        }

        @Test
        @DisplayName("Should parse core reference with decision 'elseif_N' port")
        void shouldParseCoreRefWithElseifPort() {
            EdgeRef ref0 = EdgeRefParser.parse("core:check:elseif_0");
            EdgeRef ref1 = EdgeRefParser.parse("core:check:elseif_1");
            EdgeRef ref5 = EdgeRefParser.parse("core:check:elseif_5");

            assertEquals("elseif_0", ref0.port());
            assertEquals("elseif_1", ref1.port());
            assertEquals("elseif_5", ref5.port());
        }

        @Test
        @DisplayName("Should parse core reference with switch 'case_N' port")
        void shouldParseCoreRefWithCasePort() {
            EdgeRef ref0 = EdgeRefParser.parse("core:router:case_0");
            EdgeRef ref1 = EdgeRefParser.parse("core:router:case_1");

            assertEquals("case_0", ref0.port());
            assertEquals("case_1", ref1.port());
        }

        @Test
        @DisplayName("Should parse core reference with switch 'default' port")
        void shouldParseCoreRefWithDefaultPort() {
            EdgeRef ref = EdgeRefParser.parse("core:router:default");

            assertEquals("default", ref.port());
        }

        @Test
        @DisplayName("Should parse core reference with loop 'body' port")
        void shouldParseCoreRefWithBodyPort() {
            EdgeRef ref = EdgeRefParser.parse("core:loop:body");

            assertEquals("body", ref.port());
        }

        @Test
        @DisplayName("Should parse core reference with loop 'iterate' port")
        void shouldParseCoreRefWithIteratePort() {
            EdgeRef ref = EdgeRefParser.parse("core:loop:iterate");

            assertEquals("iterate", ref.port());
        }

        @Test
        @DisplayName("Should parse core reference with loop 'exit' port")
        void shouldParseCoreRefWithExitPort() {
            EdgeRef ref = EdgeRefParser.parse("core:loop:exit");

            assertEquals("exit", ref.port());
        }

        @Test
        @DisplayName("Should parse core reference with fork 'branch_N' port")
        void shouldParseCoreRefWithBranchPort() {
            EdgeRef ref0 = EdgeRefParser.parse("core:parallel:branch_0");
            EdgeRef ref1 = EdgeRefParser.parse("core:parallel:branch_1");
            EdgeRef ref2 = EdgeRefParser.parse("core:parallel:branch_2");

            assertEquals("branch_0", ref0.port());
            assertEquals("branch_1", ref1.port());
            assertEquals("branch_2", ref2.port());
        }

        @Test
        @DisplayName("Should handle label with colons for non-core nodes")
        void shouldHandleLabelWithColonsForNonCoreNodes() {
            EdgeRef ref = EdgeRefParser.parse("mcp:api:v2:call");

            assertNotNull(ref);
            assertEquals("mcp", ref.nodeType());
            assertEquals("api:v2:call", ref.nodeLabel());
            assertNull(ref.port());
        }

        @Test
        @DisplayName("Should handle label with colons for core nodes (last part is port)")
        void shouldHandleLabelWithColonsForCoreNodes() {
            EdgeRef ref = EdgeRefParser.parse("core:my:complex:label:if");

            assertNotNull(ref);
            assertEquals("core", ref.nodeType());
            assertEquals("my:complex:label", ref.nodeLabel());
            assertEquals("if", ref.port());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("Should return null for null, empty, or blank input")
        void shouldReturnNullForNullEmptyOrBlank(String input) {
            assertNull(EdgeRefParser.parse(input));
        }

        @Test
        @DisplayName("Should return null for invalid format (no colon)")
        void shouldReturnNullForInvalidFormatNoColon() {
            assertNull(EdgeRefParser.parse("invalid"));
            assertNull(EdgeRefParser.parse("nocolon"));
        }

        @Test
        @DisplayName("Should return null for unknown node type")
        void shouldReturnNullForUnknownNodeType() {
            assertNull(EdgeRefParser.parse("unknown:label"));
            assertNull(EdgeRefParser.parse("invalid:something"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // build() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("build()")
    class BuildTests {

        @Test
        @DisplayName("Should build reference without port")
        void shouldBuildRefWithoutPort() {
            assertEquals("trigger:webhook", EdgeRefParser.build("trigger", "webhook", null));
            assertEquals("mcp:api_call", EdgeRefParser.build("mcp", "api_call", null));
            assertEquals("mcp:step", EdgeRefParser.build("mcp", "step"));
        }

        @Test
        @DisplayName("Should build reference with port")
        void shouldBuildRefWithPort() {
            assertEquals("core:check:if", EdgeRefParser.build("core", "check", "if"));
            assertEquals("core:check:else", EdgeRefParser.build("core", "check", "else"));
            assertEquals("core:loop:body", EdgeRefParser.build("core", "loop", "body"));
            assertEquals("core:fork:branch_0", EdgeRefParser.build("core", "fork", "branch_0"));
        }

        @Test
        @DisplayName("Should throw for null nodeType or nodeLabel")
        void shouldThrowForNullNodeTypeOrLabel() {
            assertThrows(IllegalArgumentException.class,
                () -> EdgeRefParser.build(null, "label", null));
            assertThrows(IllegalArgumentException.class,
                () -> EdgeRefParser.build("mcp", null, null));
            assertThrows(IllegalArgumentException.class,
                () -> EdgeRefParser.build("mcp", "", null));
        }

        @Test
        @DisplayName("Should throw for invalid node type")
        void shouldThrowForInvalidNodeType() {
            assertThrows(IllegalArgumentException.class,
                () -> EdgeRefParser.build("invalid", "label", null));
        }

        @Test
        @DisplayName("Should throw when adding port to non-core node")
        void shouldThrowWhenAddingPortToNonCoreNode() {
            assertThrows(IllegalArgumentException.class,
                () -> EdgeRefParser.build("mcp", "step", "if"));
            assertThrows(IllegalArgumentException.class,
                () -> EdgeRefParser.build("trigger", "webhook", "body"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helper methods tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("hasPort()")
    class HasPortTests {

        @Test
        @DisplayName("Should return true when reference has port")
        void shouldReturnTrueWhenRefHasPort() {
            assertTrue(EdgeRefParser.hasPort("core:check:if"));
            assertTrue(EdgeRefParser.hasPort("core:loop:body"));
            assertTrue(EdgeRefParser.hasPort("core:fork:branch_0"));
        }

        @Test
        @DisplayName("Should return false when reference has no port")
        void shouldReturnFalseWhenRefHasNoPort() {
            assertFalse(EdgeRefParser.hasPort("mcp:step"));
            assertFalse(EdgeRefParser.hasPort("trigger:webhook"));
            assertFalse(EdgeRefParser.hasPort("core:merge"));
        }

        @Test
        @DisplayName("Should return false for invalid reference")
        void shouldReturnFalseForInvalidRef() {
            assertFalse(EdgeRefParser.hasPort(null));
            assertFalse(EdgeRefParser.hasPort(""));
            assertFalse(EdgeRefParser.hasPort("invalid"));
        }
    }

    @Nested
    @DisplayName("getNodeKey()")
    class GetNodeKeyTests {

        @Test
        @DisplayName("Should return key without port")
        void shouldReturnKeyWithoutPort() {
            assertEquals("core:check", EdgeRefParser.getNodeKey("core:check:if"));
            assertEquals("core:loop", EdgeRefParser.getNodeKey("core:loop:body"));
            assertEquals("core:fork", EdgeRefParser.getNodeKey("core:fork:branch_0"));
        }

        @Test
        @DisplayName("Should return same for reference without port")
        void shouldReturnSameForRefWithoutPort() {
            assertEquals("mcp:step", EdgeRefParser.getNodeKey("mcp:step"));
            assertEquals("trigger:webhook", EdgeRefParser.getNodeKey("trigger:webhook"));
        }

        @Test
        @DisplayName("Should return null for invalid reference")
        void shouldReturnNullForInvalidRef() {
            assertNull(EdgeRefParser.getNodeKey(null));
            assertNull(EdgeRefParser.getNodeKey(""));
            assertNull(EdgeRefParser.getNodeKey("invalid"));
        }
    }

    @Nested
    @DisplayName("getPort()")
    class GetPortTests {

        @Test
        @DisplayName("Should return port from reference")
        void shouldReturnPortFromRef() {
            assertEquals("if", EdgeRefParser.getPort("core:check:if"));
            assertEquals("else", EdgeRefParser.getPort("core:check:else"));
            assertEquals("body", EdgeRefParser.getPort("core:loop:body"));
            assertEquals("branch_0", EdgeRefParser.getPort("core:fork:branch_0"));
        }

        @Test
        @DisplayName("Should return null when no port")
        void shouldReturnNullWhenNoPort() {
            assertNull(EdgeRefParser.getPort("mcp:step"));
            assertNull(EdgeRefParser.getPort("trigger:webhook"));
        }

        @Test
        @DisplayName("Should return null for invalid reference")
        void shouldReturnNullForInvalidRef() {
            assertNull(EdgeRefParser.getPort(null));
            assertNull(EdgeRefParser.getPort("invalid"));
        }
    }

    @Nested
    @DisplayName("splitPort()")
    class SplitPortTests {

        @Test
        @DisplayName("Splits full node refs and bare labels using the shared valid-port set")
        void shouldSplitFullRefsAndBareLabels() {
            assertArrayEquals(new String[]{"core:check", "if"}, EdgeRefParser.splitPort("core:check:if"));
            assertArrayEquals(new String[]{"Check decision", "else"}, EdgeRefParser.splitPort("Check decision:else"));
            assertArrayEquals(new String[]{"Guardrail", "pass"}, EdgeRefParser.splitPort("Guardrail:pass"));
            assertArrayEquals(new String[]{"Approval", "approved"}, EdgeRefParser.splitPort("Approval:approved"));
            assertArrayEquals(new String[]{"Option", "choice_2"}, EdgeRefParser.splitPort("Option:choice_2"));
            // Ports the validator/exporter lists previously OMITTED (the coherence bug):
            assertArrayEquals(new String[]{"Guardrail", "fail"}, EdgeRefParser.splitPort("Guardrail:fail"));
            assertArrayEquals(new String[]{"Approval", "rejected"}, EdgeRefParser.splitPort("Approval:rejected"));
            assertArrayEquals(new String[]{"Approval", "timeout"}, EdgeRefParser.splitPort("Approval:timeout"));
            assertArrayEquals(new String[]{"agent:classify", "category_0"}, EdgeRefParser.splitPort("agent:classify:category_0"));
            assertArrayEquals(new String[]{"core:fork", "branch_1"}, EdgeRefParser.splitPort("core:fork:branch_1"));
            // A WRONG type prefix (mcp: for a fork that is really core:) still
            // splits the port - cross-prefix recovery happens downstream on the base:
            assertArrayEquals(new String[]{"mcp:my_fork", "branch_0"}, EdgeRefParser.splitPort("mcp:my_fork:branch_0"));
            // Approval CUSTOM path port (path_N) - runtime-legal, wired by ApprovalNodeWirer:
            assertArrayEquals(new String[]{"core:review", "path_0"}, EdgeRefParser.splitPort("core:review:path_0"));
            assertEquals("approval", EdgeRefParser.getPortType("path_2"));
            assertTrue(EdgeRefParser.isValidPort("path_5"));
        }

        @Test
        @DisplayName("Leaves unknown trailing segments in the base reference")
        void shouldLeaveUnknownTrailingSegmentsInBaseRef() {
            assertArrayEquals(new String[]{"mcp:api:v2:call", null}, EdgeRefParser.splitPort("mcp:api:v2:call"));
            assertArrayEquals(new String[]{"Label:custom_port", null}, EdgeRefParser.splitPort("Label:custom_port"));
            // Segments that merely LOOK like a port must NOT be split off:
            assertArrayEquals(new String[]{"trigger:my_pass", null}, EdgeRefParser.splitPort("trigger:my_pass"));
            assertArrayEquals(new String[]{"core:submit", null}, EdgeRefParser.splitPort("core:submit"));
            // A NODE whose label happens to equal a port keyword (id "core:if",
            // label "if") is a 2-segment node id, NOT base "core" + port "if":
            assertArrayEquals(new String[]{"core:if", null}, EdgeRefParser.splitPort("core:if"));
            assertArrayEquals(new String[]{"agent:pass", null}, EdgeRefParser.splitPort("agent:pass"));
            // A 3-segment ref with an UNRECOGNISED port is left intact for the validator:
            assertArrayEquals(new String[]{"core:check:bogus", null}, EdgeRefParser.splitPort("core:check:bogus"));
        }

        @Test
        @DisplayName("Handles empty and null references")
        void shouldHandleEmptyAndNullReferences() {
            assertArrayEquals(new String[]{null, null}, EdgeRefParser.splitPort(null));
            assertArrayEquals(new String[]{"", null}, EdgeRefParser.splitPort(""));
            assertArrayEquals(new String[]{"Label:", null}, EdgeRefParser.splitPort("Label:"));
        }
    }

    @Nested
    @DisplayName("isNodeType()")
    class IsNodeTypeTests {

        @Test
        @DisplayName("Should return true for matching node type")
        void shouldReturnTrueForMatchingNodeType() {
            assertTrue(EdgeRefParser.isNodeType("mcp:step", "mcp"));
            assertTrue(EdgeRefParser.isNodeType("trigger:webhook", "trigger"));
            assertTrue(EdgeRefParser.isNodeType("core:check:if", "core"));
        }

        @Test
        @DisplayName("Should return false for non-matching node type")
        void shouldReturnFalseForNonMatchingNodeType() {
            assertFalse(EdgeRefParser.isNodeType("mcp:step", "trigger"));
            assertFalse(EdgeRefParser.isNodeType("trigger:webhook", "mcp"));
        }

        @Test
        @DisplayName("Should return false for invalid reference")
        void shouldReturnFalseForInvalidRef() {
            assertFalse(EdgeRefParser.isNodeType(null, "mcp"));
            assertFalse(EdgeRefParser.isNodeType("invalid", "mcp"));
        }
    }

    @Nested
    @DisplayName("matchesNodeKey()")
    class MatchesNodeKeyTests {

        @Test
        @DisplayName("Should return true when reference matches node key")
        void shouldReturnTrueWhenRefMatchesNodeKey() {
            assertTrue(EdgeRefParser.matchesNodeKey("core:check:if", "core:check"));
            assertTrue(EdgeRefParser.matchesNodeKey("core:check:else", "core:check"));
            assertTrue(EdgeRefParser.matchesNodeKey("mcp:step", "mcp:step"));
        }

        @Test
        @DisplayName("Should return false when reference does not match node key")
        void shouldReturnFalseWhenRefDoesNotMatchNodeKey() {
            assertFalse(EdgeRefParser.matchesNodeKey("core:check:if", "core:other"));
            assertFalse(EdgeRefParser.matchesNodeKey("mcp:step", "mcp:other"));
        }

        @Test
        @DisplayName("Should return false for invalid reference")
        void shouldReturnFalseForInvalidRef() {
            assertFalse(EdgeRefParser.matchesNodeKey(null, "core:check"));
            assertFalse(EdgeRefParser.matchesNodeKey("invalid", "core:check"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Decision port parsing tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("parseDecisionPort()")
    class ParseDecisionPortTests {

        @Test
        @DisplayName("Should parse 'if' port")
        void shouldParseIfPort() {
            DecisionPort port = EdgeRefParser.parseDecisionPort("if");

            assertNotNull(port);
            assertEquals("if", port.type());
            assertEquals(-1, port.index());
        }

        @Test
        @DisplayName("Should parse 'else' port")
        void shouldParseElsePort() {
            DecisionPort port = EdgeRefParser.parseDecisionPort("else");

            assertNotNull(port);
            assertEquals("else", port.type());
            assertEquals(-1, port.index());
        }

        @Test
        @DisplayName("Should parse 'elseif_N' port")
        void shouldParseElseifPort() {
            DecisionPort port0 = EdgeRefParser.parseDecisionPort("elseif_0");
            DecisionPort port1 = EdgeRefParser.parseDecisionPort("elseif_1");
            DecisionPort port10 = EdgeRefParser.parseDecisionPort("elseif_10");

            assertNotNull(port0);
            assertEquals("elseif", port0.type());
            assertEquals(0, port0.index());

            assertNotNull(port1);
            assertEquals("elseif", port1.type());
            assertEquals(1, port1.index());

            assertNotNull(port10);
            assertEquals(10, port10.index());
        }

        @Test
        @DisplayName("Should return null for invalid port")
        void shouldReturnNullForInvalidPort() {
            assertNull(EdgeRefParser.parseDecisionPort(null));
            assertNull(EdgeRefParser.parseDecisionPort("body"));
            assertNull(EdgeRefParser.parseDecisionPort("branch_0"));
            assertNull(EdgeRefParser.parseDecisionPort("invalid"));
        }
    }

    @Nested
    @DisplayName("buildDecisionPort()")
    class BuildDecisionPortTests {

        @Test
        @DisplayName("Should build 'if' port")
        void shouldBuildIfPort() {
            assertEquals("if", EdgeRefParser.buildDecisionPort("if", -1));
        }

        @Test
        @DisplayName("Should build 'else' port")
        void shouldBuildElsePort() {
            assertEquals("else", EdgeRefParser.buildDecisionPort("else", -1));
        }

        @Test
        @DisplayName("Should build 'elseif_N' port")
        void shouldBuildElseifPort() {
            assertEquals("elseif_0", EdgeRefParser.buildDecisionPort("elseif", 0));
            assertEquals("elseif_1", EdgeRefParser.buildDecisionPort("elseif", 1));
            assertEquals("elseif_5", EdgeRefParser.buildDecisionPort("elseif", 5));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Switch port parsing tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("parseSwitchPort()")
    class ParseSwitchPortTests {

        @Test
        @DisplayName("Should parse 'case_N' port")
        void shouldParseCasePort() {
            SwitchPort port0 = EdgeRefParser.parseSwitchPort("case_0");
            SwitchPort port1 = EdgeRefParser.parseSwitchPort("case_1");

            assertNotNull(port0);
            assertEquals("case", port0.type());
            assertEquals(0, port0.index());

            assertNotNull(port1);
            assertEquals(1, port1.index());
        }

        @Test
        @DisplayName("Should parse 'default' port")
        void shouldParseDefaultPort() {
            SwitchPort port = EdgeRefParser.parseSwitchPort("default");

            assertNotNull(port);
            assertEquals("default", port.type());
            assertEquals(-1, port.index());
        }

        @Test
        @DisplayName("Should return null for invalid port")
        void shouldReturnNullForInvalidPort() {
            assertNull(EdgeRefParser.parseSwitchPort(null));
            assertNull(EdgeRefParser.parseSwitchPort("if"));
            assertNull(EdgeRefParser.parseSwitchPort("body"));
        }
    }

    @Nested
    @DisplayName("buildSwitchPort()")
    class BuildSwitchPortTests {

        @Test
        @DisplayName("Should build 'case_N' port")
        void shouldBuildCasePort() {
            assertEquals("case_0", EdgeRefParser.buildSwitchPort("case", 0));
            assertEquals("case_1", EdgeRefParser.buildSwitchPort("case", 1));
        }

        @Test
        @DisplayName("Should build 'default' port")
        void shouldBuildDefaultPort() {
            assertEquals("default", EdgeRefParser.buildSwitchPort("default", -1));
            assertEquals("default", EdgeRefParser.buildSwitchPort("other", -1));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Loop port parsing tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("parseLoopPort()")
    class ParseLoopPortTests {

        @Test
        @DisplayName("Should parse 'body' port")
        void shouldParseBodyPort() {
            assertEquals(LoopPort.BODY, EdgeRefParser.parseLoopPort("body"));
        }

        @Test
        @DisplayName("Should parse 'iterate' port")
        void shouldParseIteratePort() {
            assertEquals(LoopPort.ITERATE, EdgeRefParser.parseLoopPort("iterate"));
        }

        @Test
        @DisplayName("Should parse 'exit' port")
        void shouldParseExitPort() {
            assertEquals(LoopPort.EXIT, EdgeRefParser.parseLoopPort("exit"));
        }

        @Test
        @DisplayName("Should return null for invalid port")
        void shouldReturnNullForInvalidPort() {
            assertNull(EdgeRefParser.parseLoopPort(null));
            assertNull(EdgeRefParser.parseLoopPort("if"));
            assertNull(EdgeRefParser.parseLoopPort("branch_0"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Fork port parsing tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("parseForkPort()")
    class ParseForkPortTests {

        @Test
        @DisplayName("Should parse 'branch_N' port")
        void shouldParseBranchPort() {
            ForkPort port0 = EdgeRefParser.parseForkPort("branch_0");
            ForkPort port1 = EdgeRefParser.parseForkPort("branch_1");
            ForkPort port10 = EdgeRefParser.parseForkPort("branch_10");

            assertNotNull(port0);
            assertEquals(0, port0.index());

            assertNotNull(port1);
            assertEquals(1, port1.index());

            assertNotNull(port10);
            assertEquals(10, port10.index());
        }

        @Test
        @DisplayName("Should return null for invalid port")
        void shouldReturnNullForInvalidPort() {
            assertNull(EdgeRefParser.parseForkPort(null));
            assertNull(EdgeRefParser.parseForkPort("if"));
            assertNull(EdgeRefParser.parseForkPort("body"));
            assertNull(EdgeRefParser.parseForkPort("case_0"));
        }
    }

    @Nested
    @DisplayName("buildForkPort()")
    class BuildForkPortTests {

        @Test
        @DisplayName("Should build 'branch_N' port")
        void shouldBuildBranchPort() {
            assertEquals("branch_0", EdgeRefParser.buildForkPort(0));
            assertEquals("branch_1", EdgeRefParser.buildForkPort(1));
            assertEquals("branch_10", EdgeRefParser.buildForkPort(10));
        }

        @Test
        @DisplayName("Should throw for negative index")
        void shouldThrowForNegativeIndex() {
            assertThrows(IllegalArgumentException.class,
                () -> EdgeRefParser.buildForkPort(-1));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Port type detection tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getPortType()")
    class GetPortTypeTests {

        @Test
        @DisplayName("Should detect decision ports")
        void shouldDetectDecisionPorts() {
            assertEquals("decision", EdgeRefParser.getPortType("if"));
            assertEquals("decision", EdgeRefParser.getPortType("else"));
            assertEquals("decision", EdgeRefParser.getPortType("elseif_0"));
            assertEquals("decision", EdgeRefParser.getPortType("elseif_5"));
        }

        @Test
        @DisplayName("Should detect switch ports")
        void shouldDetectSwitchPorts() {
            assertEquals("switch", EdgeRefParser.getPortType("case_0"));
            assertEquals("switch", EdgeRefParser.getPortType("case_1"));
            assertEquals("switch", EdgeRefParser.getPortType("default"));
        }

        @Test
        @DisplayName("Should detect loop ports")
        void shouldDetectLoopPorts() {
            assertEquals("loop", EdgeRefParser.getPortType("body"));
            assertEquals("loop", EdgeRefParser.getPortType("iterate"));
            assertEquals("loop", EdgeRefParser.getPortType("exit"));
        }

        @Test
        @DisplayName("Should detect fork ports")
        void shouldDetectForkPorts() {
            assertEquals("fork", EdgeRefParser.getPortType("branch_0"));
            assertEquals("fork", EdgeRefParser.getPortType("branch_1"));
            assertEquals("fork", EdgeRefParser.getPortType("branch_10"));
        }

        @Test
        @DisplayName("Should return null for invalid ports")
        void shouldReturnNullForInvalidPorts() {
            assertNull(EdgeRefParser.getPortType(null));
            assertNull(EdgeRefParser.getPortType("invalid"));
            assertNull(EdgeRefParser.getPortType("unknown"));
        }
    }

    @Nested
    @DisplayName("isValidPort()")
    class IsValidPortTests {

        @ParameterizedTest
        @ValueSource(strings = {"if", "else", "elseif_0", "case_0", "default", "body", "iterate", "exit", "branch_0"})
        @DisplayName("Should return true for valid ports")
        void shouldReturnTrueForValidPorts(String port) {
            assertTrue(EdgeRefParser.isValidPort(port));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"invalid", "unknown", "random"})
        @DisplayName("Should return false for invalid ports")
        void shouldReturnFalseForInvalidPorts(String port) {
            assertFalse(EdgeRefParser.isValidPort(port));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EdgeRef record tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("EdgeRef record")
    class EdgeRefRecordTests {

        @Test
        @DisplayName("hasPort() should return true when port is not null or blank")
        void hasPortShouldReturnTrueWhenPortNotNull() {
            EdgeRef withPort = new EdgeRef("core", "check", "if");
            EdgeRef withoutPort = new EdgeRef("mcp", "step", null);
            EdgeRef withBlankPort = new EdgeRef("core", "check", "   ");

            assertTrue(withPort.hasPort());
            assertFalse(withoutPort.hasPort());
            assertFalse(withBlankPort.hasPort());
        }

        @Test
        @DisplayName("getNodeKey() should return nodeType:nodeLabel")
        void getNodeKeyShouldReturnConcatenation() {
            EdgeRef ref = new EdgeRef("core", "check", "if");
            assertEquals("core:check", ref.getNodeKey());

            EdgeRef ref2 = new EdgeRef("mcp", "api_call", null);
            assertEquals("mcp:api_call", ref2.getNodeKey());
        }
    }
}
