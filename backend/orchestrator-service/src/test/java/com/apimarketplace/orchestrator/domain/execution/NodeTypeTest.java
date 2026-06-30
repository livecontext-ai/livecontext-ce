package com.apimarketplace.orchestrator.domain.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("NodeType")
class NodeTypeTest {

    @Nested
    @DisplayName("getPrefix()")
    class GetPrefixTests {

        @Test
        @DisplayName("TRIGGER should have prefix 'trigger'")
        void triggerPrefix() {
            assertEquals("trigger", NodeType.TRIGGER.getPrefix());
        }

        @Test
        @DisplayName("MCP should have prefix 'mcp'")
        void mcpPrefix() {
            assertEquals("mcp", NodeType.MCP.getPrefix());
        }

        @Test
        @DisplayName("AGENT should have prefix 'agent'")
        void agentPrefix() {
            assertEquals("agent", NodeType.AGENT.getPrefix());
        }

        @Test
        @DisplayName("INTERFACE should have prefix 'interface'")
        void interfacePrefix() {
            assertEquals("interface", NodeType.INTERFACE.getPrefix());
        }

        @Test
        @DisplayName("CRUD node types should all have prefix 'table'")
        void crudNodeTypes() {
            assertEquals("table", NodeType.INSERT_ROW.getPrefix());
            assertEquals("table", NodeType.GET_ROWS.getPrefix());
            assertEquals("table", NodeType.UPDATE_ROW.getPrefix());
            assertEquals("table", NodeType.DELETE_ROW.getPrefix());
            assertEquals("table", NodeType.CREATE_COLUMN.getPrefix());
        }

        @Test
        @DisplayName("Core control flow nodes should all have prefix 'core'")
        void coreControlFlowNodeTypes() {
            assertEquals("core", NodeType.DECISION.getPrefix());
            assertEquals("core", NodeType.SWITCH.getPrefix());
            assertEquals("core", NodeType.LOOP_CONTROLLER.getPrefix());
            assertEquals("core", NodeType.SPLIT_CONTROLLER.getPrefix());
            assertEquals("core", NodeType.MERGE.getPrefix());
            assertEquals("core", NodeType.FORK.getPrefix());
            assertEquals("core", NodeType.AGGREGATE.getPrefix());
            assertEquals("core", NodeType.OPTION.getPrefix());
        }

        @Test
        @DisplayName("Core data processing nodes should all have prefix 'core'")
        void coreDataProcessingNodeTypes() {
            assertEquals("core", NodeType.TRANSFORM.getPrefix());
            assertEquals("core", NodeType.FILTER.getPrefix());
            assertEquals("core", NodeType.SORT.getPrefix());
            assertEquals("core", NodeType.LIMIT.getPrefix());
            assertEquals("core", NodeType.REMOVE_DUPLICATES.getPrefix());
            assertEquals("core", NodeType.SUMMARIZE.getPrefix());
            assertEquals("core", NodeType.COMPARE_DATASETS.getPrefix());
        }

        @Test
        @DisplayName("Core I/O nodes should all have prefix 'core'")
        void coreIoNodeTypes() {
            assertEquals("core", NodeType.FIND.getPrefix());
            assertEquals("core", NodeType.HTTP_REQUEST.getPrefix());
            assertEquals("core", NodeType.DOWNLOAD_FILE.getPrefix());
            assertEquals("core", NodeType.CONVERT_TO_FILE.getPrefix());
            assertEquals("core", NodeType.EXTRACT_FROM_FILE.getPrefix());
            assertEquals("core", NodeType.RSS.getPrefix());
            assertEquals("core", NodeType.XML.getPrefix());
            assertEquals("core", NodeType.COMPRESSION.getPrefix());
            assertEquals("core", NodeType.SEND_EMAIL.getPrefix());
            assertEquals("core", NodeType.CODE.getPrefix());
            assertEquals("core", NodeType.SUB_WORKFLOW.getPrefix());
        }

        @Test
        @DisplayName("Core security/encoding nodes should have prefix 'core'")
        void coreSecurityNodeTypes() {
            assertEquals("core", NodeType.CRYPTO_JWT.getPrefix());
            assertEquals("core", NodeType.DATE_TIME.getPrefix());
        }

        @Test
        @DisplayName("Core interaction nodes should have prefix 'core'")
        void coreInteractionNodeTypes() {
            assertEquals("core", NodeType.DATA_INPUT.getPrefix());
            assertEquals("core", NodeType.APPROVAL.getPrefix());
            assertEquals("core", NodeType.RESPOND_TO_WEBHOOK.getPrefix());
            assertEquals("core", NodeType.RESPONSE.getPrefix());
        }

        @Test
        @DisplayName("Core flow control nodes should have prefix 'core'")
        void coreFlowControlNodeTypes() {
            assertEquals("core", NodeType.WAIT.getPrefix());
            assertEquals("core", NodeType.EXIT.getPrefix());
            assertEquals("core", NodeType.END.getPrefix());
        }

        @ParameterizedTest
        @EnumSource(NodeType.class)
        @DisplayName("Every NodeType should have a non-null, non-empty prefix")
        void everyNodeTypeShouldHavePrefix(NodeType nodeType) {
            String prefix = nodeType.getPrefix();
            assertNotNull(prefix, "Prefix for " + nodeType + " should not be null");
            assertFalse(prefix.isEmpty(), "Prefix for " + nodeType + " should not be empty");
            // Prefix must be one of the known prefixes
            assertTrue(
                prefix.equals("trigger") || prefix.equals("mcp") || prefix.equals("agent") ||
                prefix.equals("core") || prefix.equals("table") || prefix.equals("interface"),
                "Prefix for " + nodeType + " should be one of: trigger, mcp, agent, core, table, interface. Got: " + prefix
            );
        }
    }

    @Nested
    @DisplayName("fromNodeId()")
    class FromNodeIdTests {

        @ParameterizedTest
        @CsvSource({
            "trigger:start, TRIGGER",
            "mcp:api_call, MCP",
            "agent:assistant, AGENT",
            "core:decision1, DECISION",
            "interface:search_page, INTERFACE"
        })
        @DisplayName("Should parse node ID prefix correctly")
        void shouldParseCorrectly(String nodeId, String expectedType) {
            assertEquals(NodeType.valueOf(expectedType), NodeType.fromNodeId(nodeId));
        }

        @Test
        @DisplayName("table: prefix should return GET_ROWS (default CRUD type)")
        void tablePrefixShouldReturnGetRows() {
            assertEquals(NodeType.GET_ROWS, NodeType.fromNodeId("table:users"));
            assertEquals(NodeType.GET_ROWS, NodeType.fromNodeId("table:orders"));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("Should return MCP for null and empty")
        void shouldReturnMcpForNullAndEmpty(String nodeId) {
            assertEquals(NodeType.MCP, NodeType.fromNodeId(nodeId));
        }

        @Test
        @DisplayName("Should return MCP for blank")
        void shouldReturnMcpForBlank() {
            assertEquals(NodeType.MCP, NodeType.fromNodeId("   "));
        }

        @Test
        @DisplayName("Should return MCP for unknown prefix")
        void shouldReturnMcpForUnknownPrefix() {
            assertEquals(NodeType.MCP, NodeType.fromNodeId("unknown:step"));
        }
    }

    @Nested
    @DisplayName("fromCoreType()")
    class FromCoreTypeTests {

        @ParameterizedTest
        @CsvSource({
            "decision, DECISION",
            "switch, SWITCH",
            "loop, LOOP_CONTROLLER",
            "split, SPLIT_CONTROLLER",
            "merge, MERGE",
            "fork, FORK",
            "aggregate, AGGREGATE",
            "option, OPTION"
        })
        @DisplayName("Should return correct NodeType for core control flow types")
        void shouldReturnCorrectControlFlowType(String coreType, String expectedType) {
            assertEquals(NodeType.valueOf(expectedType), NodeType.fromCoreType(coreType));
        }

        @ParameterizedTest
        @CsvSource({
            "transform, TRANSFORM",
            "filter, FILTER",
            "sort, SORT",
            "limit, LIMIT",
            "remove_duplicates, REMOVE_DUPLICATES",
            "summarize, SUMMARIZE",
            "compare_datasets, COMPARE_DATASETS",
            "html_extract, HTML_EXTRACT"
        })
        @DisplayName("Should return correct NodeType for core data processing types")
        void shouldReturnCorrectDataProcessingType(String coreType, String expectedType) {
            assertEquals(NodeType.valueOf(expectedType), NodeType.fromCoreType(coreType));
        }

        @ParameterizedTest
        @CsvSource({
            "find, FIND",
            "http_request, HTTP_REQUEST",
            "download_file, DOWNLOAD_FILE",
            "convert_to_file, CONVERT_TO_FILE",
            "extract_from_file, EXTRACT_FROM_FILE",
            "rss, RSS",
            "xml, XML",
            "compression, COMPRESSION",
            "send_email, SEND_EMAIL",
            "code, CODE",
            "sub_workflow, SUB_WORKFLOW"
        })
        @DisplayName("Should return correct NodeType for core I/O types")
        void shouldReturnCorrectIoType(String coreType, String expectedType) {
            assertEquals(NodeType.valueOf(expectedType), NodeType.fromCoreType(coreType));
        }

        @ParameterizedTest
        @CsvSource({
            "crypto_jwt, CRYPTO_JWT",
            "date_time, DATE_TIME"
        })
        @DisplayName("Should return correct NodeType for core security types")
        void shouldReturnCorrectSecurityType(String coreType, String expectedType) {
            assertEquals(NodeType.valueOf(expectedType), NodeType.fromCoreType(coreType));
        }

        @ParameterizedTest
        @CsvSource({
            "wait, WAIT",
            "end, END",
            "data_input, DATA_INPUT",
            "approval, APPROVAL",
            "respond_to_webhook, RESPOND_TO_WEBHOOK",
            "response, RESPONSE"
        })
        @DisplayName("Should return correct NodeType for core interaction/flow types")
        void shouldReturnCorrectInteractionType(String coreType, String expectedType) {
            assertEquals(NodeType.valueOf(expectedType), NodeType.fromCoreType(coreType));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("Should return DECISION for null and empty")
        void shouldReturnDecisionForNullAndEmpty(String coreType) {
            assertEquals(NodeType.DECISION, NodeType.fromCoreType(coreType));
        }

        @Test
        @DisplayName("Should return DECISION for blank")
        void shouldReturnDecisionForBlank() {
            assertEquals(NodeType.DECISION, NodeType.fromCoreType("  "));
        }

        @Test
        @DisplayName("Should return DECISION for unknown type")
        void shouldReturnDecisionForUnknown() {
            assertEquals(NodeType.DECISION, NodeType.fromCoreType("unknown"));
        }

        @Test
        @DisplayName("Should be case-insensitive")
        void shouldBeCaseInsensitive() {
            assertEquals(NodeType.DECISION, NodeType.fromCoreType("DECISION"));
            assertEquals(NodeType.LOOP_CONTROLLER, NodeType.fromCoreType("Loop"));
            assertEquals(NodeType.FILTER, NodeType.fromCoreType("FILTER"));
            assertEquals(NodeType.SEND_EMAIL, NodeType.fromCoreType("Send_Email"));
        }
    }

    @Nested
    @DisplayName("Prefix coherence - fromNodeId → getPrefix round-trip")
    class PrefixCoherenceTests {

        @ParameterizedTest
        @CsvSource({
            "trigger:start, trigger",
            "mcp:api_call, mcp",
            "agent:assistant, agent",
            "core:decision1, core",
            "table:users, table",
            "interface:page, interface"
        })
        @DisplayName("fromNodeId result's getPrefix should match the original prefix")
        void fromNodeIdShouldProducePrefixCoherentResult(String nodeId, String expectedPrefix) {
            NodeType type = NodeType.fromNodeId(nodeId);
            assertEquals(expectedPrefix, type.getPrefix(),
                "NodeType from '" + nodeId + "' is " + type + " with prefix '" + type.getPrefix() +
                "' but expected '" + expectedPrefix + "'");
        }
    }
}
