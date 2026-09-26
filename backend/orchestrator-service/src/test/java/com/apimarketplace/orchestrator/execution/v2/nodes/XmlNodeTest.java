package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for XmlNode.
 * XmlNode parses XML to JSON and builds XML from JSON.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("XmlNode")
class XmlNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        Map<String, Object> triggerData = new HashMap<>();
        triggerData.put("name", "John");
        triggerData.put("age", "30");

        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            triggerData,
            mockPlan
        );
    }

    // ===============================================================
    // Constructor tests
    // ===============================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create XmlNode with nodeId and config")
        void shouldCreateXmlNodeWithNodeIdAndConfig() {
            Core.XmlConfig config = new Core.XmlConfig("xmlToJson", "<root/>", "root", false);
            XmlNode node = new XmlNode("core:xml", config);

            assertEquals("core:xml", node.getNodeId());
            assertEquals(NodeType.XML, node.getType());
            assertNotNull(node.getXmlConfig());
            assertEquals("xmlToJson", node.getXmlConfig().operation());
        }

        @Test
        @DisplayName("Should handle null config")
        void shouldHandleNullConfig() {
            XmlNode node = new XmlNode("core:xml", null);

            assertEquals("core:xml", node.getNodeId());
            assertNull(node.getXmlConfig());
        }

        @Test
        @DisplayName("Should default operation to xmlToJson when null")
        void shouldDefaultOperationToXmlToJsonWhenNull() {
            Core.XmlConfig config = new Core.XmlConfig(null, "<root/>", null, false);

            assertEquals("xmlToJson", config.operation());
            // rootElement is NOT defaulted in the record; it stays null.
            // The default "root" is applied at execution time in executeJsonToXml().
            assertNull(config.rootElement());
        }

        @Test
        @DisplayName("Should create XmlNode using builder")
        void shouldCreateXmlNodeUsingBuilder() {
            Core.XmlConfig config = new Core.XmlConfig("jsonToXml", null, "data", true);

            XmlNode node = XmlNode.builder()
                .nodeId("core:my_xml")
                .xmlConfig(config)
                .build();

            assertEquals("core:my_xml", node.getNodeId());
            assertEquals("jsonToXml", node.getXmlConfig().operation());
            assertEquals("data", node.getXmlConfig().rootElement());
            assertTrue(node.getXmlConfig().preserveAttributes());
        }
    }

    // ===============================================================
    // execute() - xmlToJson operation tests
    // ===============================================================

    @Nested
    @DisplayName("execute() - xmlToJson")
    class ExecuteXmlToJsonTests {

        @Test
        @DisplayName("Should parse simple XML to JSON map")
        void shouldParseSimpleXmlToJsonMap() {
            String xml = "<person><name>John</name><age>30</age></person>";
            Core.XmlConfig config = new Core.XmlConfig("xmlToJson", xml, null, false);

            XmlNode node = new XmlNode("core:xml", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("xmlToJson", result.output().get("operation"));
            assertEquals(true, result.output().get("success"));

            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = (Map<String, Object>) result.output().get("result");
            assertNotNull(parsed);
            assertEquals("John", parsed.get("name"));
            assertEquals("30", parsed.get("age"));
        }

        @Test
        @DisplayName("Should parse nested XML to JSON map")
        void shouldParseNestedXmlToJsonMap() {
            String xml = "<root><person><name>John</name><address><city>NYC</city></address></person></root>";
            Core.XmlConfig config = new Core.XmlConfig("xmlToJson", xml, null, false);

            XmlNode node = new XmlNode("core:xml", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());

            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = (Map<String, Object>) result.output().get("result");
            assertNotNull(parsed);

            @SuppressWarnings("unchecked")
            Map<String, Object> person = (Map<String, Object>) parsed.get("person");
            assertNotNull(person);
            assertEquals("John", person.get("name"));

            @SuppressWarnings("unchecked")
            Map<String, Object> address = (Map<String, Object>) person.get("address");
            assertNotNull(address);
            assertEquals("NYC", address.get("city"));
        }

        @Test
        @DisplayName("Should parse XML with repeated elements as list")
        void shouldParseXmlWithRepeatedElementsAsList() {
            String xml = "<root><item>A</item><item>B</item><item>C</item></root>";
            Core.XmlConfig config = new Core.XmlConfig("xmlToJson", xml, null, false);

            XmlNode node = new XmlNode("core:xml", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());

            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = (Map<String, Object>) result.output().get("result");
            assertNotNull(parsed);

            Object items = parsed.get("item");
            assertInstanceOf(List.class, items);
            @SuppressWarnings("unchecked")
            List<Object> itemList = (List<Object>) items;
            assertEquals(3, itemList.size());
            assertEquals("A", itemList.get(0));
            assertEquals("B", itemList.get(1));
            assertEquals("C", itemList.get(2));
        }

        @Test
        @DisplayName("Should preserve XML attributes when enabled")
        void shouldPreserveXmlAttributesWhenEnabled() {
            String xml = "<person id=\"123\" type=\"employee\"><name>John</name></person>";
            Core.XmlConfig config = new Core.XmlConfig("xmlToJson", xml, null, true);

            XmlNode node = new XmlNode("core:xml", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());

            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = (Map<String, Object>) result.output().get("result");
            assertNotNull(parsed);

            @SuppressWarnings("unchecked")
            Map<String, String> attributes = (Map<String, String>) parsed.get("@attributes");
            assertNotNull(attributes);
            assertEquals("123", attributes.get("id"));
            assertEquals("employee", attributes.get("type"));
            assertEquals("John", parsed.get("name"));
        }

        @Test
        @DisplayName("Should not include attributes when preserveAttributes is false")
        void shouldNotIncludeAttributesWhenDisabled() {
            String xml = "<person id=\"123\"><name>John</name></person>";
            Core.XmlConfig config = new Core.XmlConfig("xmlToJson", xml, null, false);

            XmlNode node = new XmlNode("core:xml", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());

            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = (Map<String, Object>) result.output().get("result");
            assertNotNull(parsed);
            assertNull(parsed.get("@attributes"));
        }

        @Test
        @DisplayName("Should fail for null XML input")
        void shouldFailForNullXmlInput() {
            Core.XmlConfig config = new Core.XmlConfig("xmlToJson", null, null, false);

            XmlNode node = new XmlNode("core:xml", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }

        @Test
        @DisplayName("Should fail for empty XML input")
        void shouldFailForEmptyXmlInput() {
            Core.XmlConfig config = new Core.XmlConfig("xmlToJson", "", null, false);

            XmlNode node = new XmlNode("core:xml", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }

        @Test
        @DisplayName("Should fail for invalid XML")
        void shouldFailForInvalidXml() {
            Core.XmlConfig config = new Core.XmlConfig("xmlToJson", "<root><unclosed>", null, false);

            XmlNode node = new XmlNode("core:xml", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }

        @Test
        @DisplayName("Should parse XML with CDATA sections")
        void shouldParseXmlWithCdataSections() {
            String xml = "<root><content><![CDATA[Some <b>HTML</b> content]]></content></root>";
            Core.XmlConfig config = new Core.XmlConfig("xmlToJson", xml, null, false);

            XmlNode node = new XmlNode("core:xml", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());

            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = (Map<String, Object>) result.output().get("result");
            assertNotNull(parsed);
            assertEquals("Some <b>HTML</b> content", parsed.get("content"));
        }
    }

    // ===============================================================
    // execute() - jsonToXml operation tests
    // ===============================================================

    @Nested
    @DisplayName("execute() - jsonToXml")
    class ExecuteJsonToXmlTests {

        private ExecutionContext jsonToXmlContext;

        @BeforeEach
        void setUpJsonToXmlContext() {
            // jsonToXml reads from stepOutputs (fallback: uses all step outputs as JSON data).
            // Put the data as a step output so the node can find it.
            ExecutionContext base = ExecutionContext.create(
                "run-1", "workflow-run-1", "tenant-1", "item-1", 0,
                new HashMap<>(), mockPlan
            );
            jsonToXmlContext = base.withStepOutput("mcp:previous_step",
                Map.of("name", "John", "age", "30"));
        }

        @Test
        @DisplayName("Should build XML from step outputs")
        void shouldBuildXmlFromStepOutputs() {
            Core.XmlConfig config = new Core.XmlConfig("jsonToXml", null, "person", false);

            XmlNode node = new XmlNode("core:xml", config);
            NodeExecutionResult result = node.execute(jsonToXmlContext);

            assertTrue(result.isSuccess());
            assertEquals("jsonToXml", result.output().get("operation"));
            assertEquals(true, result.output().get("success"));

            String xmlOutput = (String) result.output().get("result");
            assertNotNull(xmlOutput);
            assertTrue(xmlOutput.contains("<person>"));
            // The step output is a nested Map under "mcp:previous_step" key,
            // so the XML will contain <mcp_previous_step> with nested elements.
            // The fallback uses ALL stepOutputs as the JSON data map.
            assertTrue(xmlOutput.contains("John"));
            assertTrue(xmlOutput.contains("30"));
        }

        @Test
        @DisplayName("Should use custom root element name")
        void shouldUseCustomRootElementName() {
            Core.XmlConfig config = new Core.XmlConfig("jsonToXml", null, "employee", false);

            XmlNode node = new XmlNode("core:xml", config);
            NodeExecutionResult result = node.execute(jsonToXmlContext);

            assertTrue(result.isSuccess());

            String xmlOutput = (String) result.output().get("result");
            assertNotNull(xmlOutput);
            assertTrue(xmlOutput.contains("<employee>"));
            assertTrue(xmlOutput.contains("</employee>"));
        }

        @Test
        @DisplayName("Should default root element to 'root'")
        void shouldDefaultRootElementToRoot() {
            Core.XmlConfig config = new Core.XmlConfig("jsonToXml", null, null, false);

            XmlNode node = new XmlNode("core:xml", config);
            NodeExecutionResult result = node.execute(jsonToXmlContext);

            assertTrue(result.isSuccess());

            String xmlOutput = (String) result.output().get("result");
            assertNotNull(xmlOutput);
            assertTrue(xmlOutput.contains("<root>"));
        }

        @Test
        @DisplayName("Honors a JSON object literal value and does NOT dump the execution context")
        void shouldBuildXmlFromJsonObjectLiteralValueNotContext() {
            // jsonToXmlContext carries a step output mcp:previous_step={name:John, age:30}.
            // Pre-fix, a configured JSON value was ignored and the WHOLE context was serialized.
            // The value must now be honored and the context must NOT leak into the result.
            Core.XmlConfig config = new Core.XmlConfig("jsonToXml", "{\"a\":1,\"b\":\"two\"}", "root", false);

            XmlNode node = new XmlNode("core:xml", config);
            NodeExecutionResult result = node.execute(jsonToXmlContext);

            assertTrue(result.isSuccess());
            String xml = (String) result.output().get("result");
            assertNotNull(xml);
            assertTrue(xml.contains("<a>1</a>"), xml);
            assertTrue(xml.contains("<b>two</b>"), xml);
            assertFalse(xml.contains("John"), "context step output must not leak into jsonToXml result: " + xml);
            assertFalse(xml.contains("previous_step"), xml);
        }

        @Test
        @DisplayName("Honors a JSON array literal value (wrapped as repeated <item>)")
        void shouldBuildXmlFromJsonArrayLiteralValue() {
            Core.XmlConfig config = new Core.XmlConfig("jsonToXml", "[10,20]", "root", false);

            XmlNode node = new XmlNode("core:xml", config);
            NodeExecutionResult result = node.execute(jsonToXmlContext);

            assertTrue(result.isSuccess());
            String xml = (String) result.output().get("result");
            assertNotNull(xml);
            assertTrue(xml.contains("<item>10</item>"), xml);
            assertTrue(xml.contains("<item>20</item>"), xml);
            assertFalse(xml.contains("John"), xml);
        }

        @Test
        @DisplayName("Still resolves a bare step-output key value (back-compat)")
        void shouldBuildXmlFromBareStepOutputKeyValue() {
            Core.XmlConfig config = new Core.XmlConfig("jsonToXml", "mcp:previous_step", "person", false);

            XmlNode node = new XmlNode("core:xml", config);
            NodeExecutionResult result = node.execute(jsonToXmlContext);

            assertTrue(result.isSuccess());
            String xml = (String) result.output().get("result");
            assertNotNull(xml);
            assertTrue(xml.contains("<person>"), xml);
            assertTrue(xml.contains("John"), xml);
            assertTrue(xml.contains("30"), xml);
        }

        @Test
        @DisplayName("Honors a value resolved by the template adapter to a Map (primary runtime path: {{expr}} -> Map)")
        void shouldBuildXmlFromMapResolvedByTemplateAdapter() {
            // Primary production path: a single {{expr}} preserves its typed (Map) result.
            V2TemplateAdapter adapter = mock(V2TemplateAdapter.class);
            when(adapter.resolveTemplates(anyMap(), any()))
                .thenAnswer(TemplateResolutionStubs.templatesResolveTo(Map.of("a", 1, "b", "two")));

            Core.XmlConfig config = new Core.XmlConfig("jsonToXml", "{{mcp:previous_step.output}}", "root", false);
            XmlNode node = new XmlNode("core:xml", config);
            node.setTemplateAdapter(adapter);

            NodeExecutionResult result = node.execute(jsonToXmlContext);

            assertTrue(result.isSuccess());
            String xml = (String) result.output().get("result");
            assertNotNull(xml);
            assertTrue(xml.contains("<a>1</a>"), xml);
            assertTrue(xml.contains("<b>two</b>"), xml);
            assertFalse(xml.contains("John"), "must serialize the resolved Map, not the context: " + xml);
        }

        @Test
        @DisplayName("Honors a value resolved by the template adapter to a List (wrapped as <item>)")
        void shouldBuildXmlFromListResolvedByTemplateAdapter() {
            V2TemplateAdapter adapter = mock(V2TemplateAdapter.class);
            when(adapter.resolveTemplates(anyMap(), any()))
                .thenAnswer(TemplateResolutionStubs.templatesResolveTo(List.of(10, 20)));

            Core.XmlConfig config = new Core.XmlConfig("jsonToXml", "{{mcp:previous_step.output.items}}", "root", false);
            XmlNode node = new XmlNode("core:xml", config);
            node.setTemplateAdapter(adapter);

            NodeExecutionResult result = node.execute(jsonToXmlContext);

            assertTrue(result.isSuccess());
            String xml = (String) result.output().get("result");
            assertNotNull(xml);
            assertTrue(xml.contains("<item>10</item>"), xml);
            assertTrue(xml.contains("<item>20</item>"), xml);
        }

        @Test
        @DisplayName("A malformed JSON-looking value falls back to the step-output context (documented legacy)")
        void shouldFallBackToContextForMalformedJsonValue() {
            // No template adapter -> the raw "{not json" string flows through; it is neither valid
            // JSON nor a known step-output key, so the documented legacy fallback applies.
            Core.XmlConfig config = new Core.XmlConfig("jsonToXml", "{not json", "root", false);

            XmlNode node = new XmlNode("core:xml", config);
            NodeExecutionResult result = node.execute(jsonToXmlContext);

            assertTrue(result.isSuccess());
            String xml = (String) result.output().get("result");
            assertNotNull(xml);
            assertTrue(xml.contains("John"), "malformed value should fall back to context: " + xml);
        }
    }

    // ===============================================================
    // execute() - Unknown operation
    // ===============================================================

    @Nested
    @DisplayName("execute() - Unknown operation")
    class ExecuteUnknownOperationTests {

        @Test
        @DisplayName("Should fail for unknown operation")
        void shouldFailForUnknownOperation() {
            Core.XmlConfig config = new Core.XmlConfig("invalidOp", "<root/>", null, false);

            XmlNode node = new XmlNode("core:xml", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
        }
    }

    // ===============================================================
    // execute() - Null config
    // ===============================================================

    @Nested
    @DisplayName("execute() - Null config")
    class ExecuteNullConfigTests {

        @Test
        @DisplayName("Should fail when config is null and operation defaults to xmlToJson with no value")
        void shouldFailWhenConfigIsNullAndNoValue() {
            XmlNode node = new XmlNode("core:xml", null);
            NodeExecutionResult result = node.execute(context);

            // Defaults to xmlToJson but value is null, so should fail
            assertTrue(result.isFailure());
        }
    }

    // ===============================================================
    // execute() - Metadata tests
    // ===============================================================

    @Nested
    @DisplayName("execute() - Metadata")
    class ExecuteMetadataTests {

        @Test
        @DisplayName("Should include mandatory metadata fields")
        void shouldIncludeMandatoryMetadataFields() {
            String xml = "<root><key>value</key></root>";
            Core.XmlConfig config = new Core.XmlConfig("xmlToJson", xml, null, false);

            XmlNode node = new XmlNode("core:xml", config);
            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("XML", result.output().get("node_type"));
            assertEquals(0, result.output().get("item_index"));
            assertEquals(0, result.output().get("itemIndex"));
            assertEquals("item-1", result.output().get("item_id"));
            assertNotNull(result.output().get("resolved_params"));
        }

        @Test
        @DisplayName("Should include operation in output")
        void shouldIncludeOperationInOutput() {
            String xml = "<root><key>value</key></root>";
            Core.XmlConfig config = new Core.XmlConfig("xmlToJson", xml, null, false);

            XmlNode node = new XmlNode("core:xml", config);
            NodeExecutionResult result = node.execute(context);

            assertEquals("xmlToJson", result.output().get("operation"));
            assertEquals(true, result.output().get("success"));
        }
    }

    // ===============================================================
    // getNextNodes() tests
    // ===============================================================

    @Nested
    @DisplayName("getNextNodes()")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return all successors on success")
        void shouldReturnAllSuccessorsOnSuccess() {
            Core.XmlConfig config = new Core.XmlConfig("xmlToJson", "<r/>", null, false);
            XmlNode node = new XmlNode("core:xml", config);

            ExecutionNode successor1 = createMockNode("mcp:next1");
            ExecutionNode successor2 = createMockNode("mcp:next2");
            node.addSuccessor(successor1);
            node.addSuccessor(successor2);

            NodeExecutionResult result = NodeExecutionResult.success("core:xml", Map.of());

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertEquals(2, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty list on failure")
        void shouldReturnEmptyListOnFailure() {
            Core.XmlConfig config = new Core.XmlConfig("xmlToJson", "<r/>", null, false);
            XmlNode node = new XmlNode("core:xml", config);

            ExecutionNode successor = createMockNode("mcp:next");
            node.addSuccessor(successor);

            NodeExecutionResult result = NodeExecutionResult.failure("core:xml", "Error");

            List<ExecutionNode> nextNodes = node.getNextNodes(result);
            assertTrue(nextNodes.isEmpty());
        }
    }

    // ===============================================================
    // onComplete() tests
    // ===============================================================

    @Nested
    @DisplayName("onComplete()")
    class OnCompleteTests {

        @Test
        @DisplayName("Should not throw exception on success result")
        void shouldNotThrowExceptionOnSuccessResult() {
            Core.XmlConfig config = new Core.XmlConfig("xmlToJson", "<r/>", null, false);
            XmlNode node = new XmlNode("core:xml", config);
            NodeExecutionResult result = NodeExecutionResult.success("core:xml", Map.of());
            assertDoesNotThrow(() -> node.onComplete(context, result));
        }

        @Test
        @DisplayName("Should not throw exception on failure result")
        void shouldNotThrowExceptionOnFailureResult() {
            Core.XmlConfig config = new Core.XmlConfig("xmlToJson", "<r/>", null, false);
            XmlNode node = new XmlNode("core:xml", config);
            NodeExecutionResult result = NodeExecutionResult.failure("core:xml", "Error");
            assertDoesNotThrow(() -> node.onComplete(context, result));
        }
    }

    // ===============================================================
    // Helper methods
    // ===============================================================

    private ExecutionNode createMockNode(String nodeId) {
        return new BaseNode(nodeId, NodeType.MCP) {
            @Override
            public NodeExecutionResult execute(ExecutionContext context) {
                return NodeExecutionResult.success(nodeId, Map.of());
            }
        };
    }

    @Nested
    @DisplayName("what the Params column says this node ran with")
    class ReportedConfiguration {

        @SuppressWarnings("unchecked")
        private Map<String, Object> paramsOf(NodeExecutionResult result) {
            return (Map<String, Object>) result.output().get("resolved_params");
        }

        @Test
        @DisplayName("a {{...}} rootElement is resolved: it names the document's root and is what is reported")
        void resolvesAndReportsTheRootElement() {
            // executeJsonToXml used to name the root from the CONFIGURED string, so a
            // `{{trigger:start.root}}` root reached the XML builder literally and was rejected
            // as an invalid element name, while `value` beside it resolved.
            V2TemplateAdapter adapter = mock(V2TemplateAdapter.class);
            when(adapter.resolveTemplates(anyMap(), any())).thenAnswer(invocation -> {
                Map<String, Object> in = invocation.getArgument(0);
                Map<String, Object> out = new java.util.HashMap<>();
                in.forEach((k, v) -> out.put(k, "{{trigger:start.root}}".equals(v) ? "invoice" : v));
                return out;
            });
            Core.XmlConfig config = new Core.XmlConfig(
                "jsonToXml", "{\"a\":\"1\"}", "{{trigger:start.root}}", false);
            XmlNode node = new XmlNode("core:xml", config);
            node.setTemplateAdapter(adapter);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess(), String.valueOf(result.errorMessage()));
            assertTrue(((String) result.output().get("result")).contains("<invoice>"));
            assertEquals("invoice", paramsOf(result).get("rootElement"));
        }

        @Test
        @DisplayName("a {{$vars.root}} rootElement names the document root but is withheld in Params")
        void workspaceVariableRootElementIsWithheld() {
            V2TemplateAdapter adapter = mock(V2TemplateAdapter.class);
            when(adapter.resolveTemplates(anyMap(), any()))
                .thenAnswer(TemplateResolutionStubs.resolving(Map.of("{{$vars.root}}", "s3cr3troot")));
            Core.XmlConfig config = new Core.XmlConfig(
                "jsonToXml", "{\"a\":\"1\"}", "{{$vars.root}}", false);
            XmlNode node = new XmlNode("core:xml", config);
            node.setTemplateAdapter(adapter);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess(), String.valueOf(result.errorMessage()));
            assertTrue(((String) result.output().get("result")).contains("<s3cr3troot>"));
            assertEquals(com.apimarketplace.orchestrator.services.template.ReportedParams.WITHHELD_WORKSPACE_VARIABLE,
                paramsOf(result).get("rootElement"));
        }

        @Test
        @DisplayName("`value` is the document the node WORKED on, the same meaning that key has on its sibling nodes")
        void reportsTheDocumentItConverted() {
            // One key, one meaning: `value` is the resolved data here, on ConvertToFileNode
            // and on CompressionNode. Reporting the template on one and the data on another
            // is the drift this alignment exists to remove.
            Core.XmlConfig config = new Core.XmlConfig("xmlToJson", "<root><a>1</a></root>", null, false);
            XmlNode node = new XmlNode("core:xml", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isSuccess());
            assertEquals("<root><a>1</a></root>", paramsOf(result).get("value"));
        }

        @Test
        @DisplayName("a FAILURE reports the document the node tried to parse, not the expression")
        void reportsTheDocumentOnFailure() {
            // Malformed XML fails in the parser, after `value` resolved. The failure row is
            // the one a reader opens, and it must say what was parsed.
            Core.XmlConfig config = new Core.XmlConfig("xmlToJson", "<root><unclosed>", null, false);
            XmlNode node = new XmlNode("core:xml", config);

            NodeExecutionResult result = node.execute(context);

            assertTrue(result.isFailure());
            assertEquals("<root><unclosed>", paramsOf(result).get("value"));
        }

        @Test
        @DisplayName("a document too large for a step row is described instead of copied")
        void boundsAnOversizedDocument() {
            String hugeXml = "<root>" + "<a>x</a>".repeat(5_000) + "</root>";
            Core.XmlConfig config = new Core.XmlConfig("xmlToJson", hugeXml, null, false);
            XmlNode node = new XmlNode("core:xml", config);

            String reported = String.valueOf(paramsOf(node.execute(context)).get("value"));

            assertTrue(reported.length() < 300, "persisted per step row: " + reported.length() + " chars");
            assertTrue(reported.contains("chars"), "and the reader is told what was cut: " + reported);
        }
    }

    @Nested
    @DisplayName("templated preserveAttributes")
    class TemplatedPreserveAttributes {

        private XmlNode templatedNode(Object resolvedFlag) {
            Core.XmlConfig config = new Core.XmlConfig(
                "xmlToJson", "<person id=\"123\"><name>John</name></person>", null, false);
            XmlNode node = new XmlNode("core:xml", config);
            V2TemplateAdapter adapter = org.mockito.Mockito.mock(V2TemplateAdapter.class);
            org.mockito.Mockito.when(adapter.resolveTemplates(org.mockito.ArgumentMatchers.anyMap(),
                    org.mockito.ArgumentMatchers.any()))
                .thenAnswer(TemplateResolutionStubs.resolving(Map.of("{{core:x.output.flag}}", resolvedFlag)));
            node.setTemplateAdapter(adapter);
            node.setDeferredScalars(Map.of("xml", Map.of("preserveAttributes", "{{core:x.output.flag}}")));
            return node;
        }

        @Test
        @DisplayName("regression: a {{...}} preserveAttributes is resolved; it used to drop the whole xml config")
        @SuppressWarnings("unchecked")
        void templatedFlagIsResolved() {
            NodeExecutionResult result = templatedNode(true).execute(context);

            assertTrue(result.isSuccess(), String.valueOf(result.errorMessage()));
            Map<String, Object> parsed = (Map<String, Object>) result.output().get("result");
            assertNotNull(parsed.get("@attributes"), "attributes kept because the flag resolved to true");
            Map<String, Object> params = (Map<String, Object>) result.output().get("resolved_params");
            assertEquals(true, params.get("preserveAttributes"));
        }

        @Test
        @DisplayName("a {{...}} preserveAttributes resolving to a non-boolean fails, naming the config")
        void templatedFlagNotABooleanFails() {
            NodeExecutionResult result = templatedNode("perhaps").execute(context);

            assertFalse(result.isSuccess());
            assertTrue(result.errorMessage().orElse("").contains("xml"), result.errorMessage().orElse(""));
        }
    }
}
