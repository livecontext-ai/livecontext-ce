package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import com.apimarketplace.orchestrator.services.interfaces.ToolsGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BaseNode abstract class.
 * Tests common functionality shared by all node types.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BaseNode")
class BaseNodeTest {

    @Mock
    private ToolsGateway mockToolsGateway;

    @Mock
    private V2TemplateAdapter mockTemplateAdapter;

    @Mock
    private WorkflowPlan mockPlan;

    private TestableBaseNode node;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        node = new TestableBaseNode("test:node1", NodeType.MCP);
        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            Map.of("key", "value"),
            mockPlan
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor and basic getters
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Constructor and Getters")
    class ConstructorAndGettersTests {

        @Test
        @DisplayName("Should initialize with nodeId and type")
        void shouldInitializeWithNodeIdAndType() {
            assertEquals("test:node1", node.getNodeId());
            assertEquals(NodeType.MCP, node.getType());
        }

        @Test
        @DisplayName("Should initialize with empty successors list")
        void shouldInitializeWithEmptySuccessorsList() {
            assertTrue(node.getSuccessors().isEmpty());
        }

        @Test
        @DisplayName("Should initialize with empty predecessorIds list")
        void shouldInitializeWithEmptyPredecessorIdsList() {
            assertTrue(node.getPredecessorIds().isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Service injection
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Service Injection")
    class ServiceInjectionTests {

        @Test
        @DisplayName("Should set ToolsGateway")
        void shouldSetToolsGateway() {
            node.setToolsGateway(mockToolsGateway);
            assertNotNull(node.toolsGateway);
            assertEquals(mockToolsGateway, node.toolsGateway);
        }

        @Test
        @DisplayName("Should set TemplateAdapter")
        void shouldSetTemplateAdapter() {
            node.setTemplateAdapter(mockTemplateAdapter);
            assertNotNull(node.templateAdapter);
            assertEquals(mockTemplateAdapter, node.templateAdapter);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Predecessor management
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Predecessor Management")
    class PredecessorManagementTests {

        @Test
        @DisplayName("Should add predecessor")
        void shouldAddPredecessor() {
            node.addPredecessor("mcp:step1");

            assertEquals(1, node.getPredecessorIds().size());
            assertTrue(node.getPredecessorIds().contains("mcp:step1"));
        }

        @Test
        @DisplayName("Should not add duplicate predecessor")
        void shouldNotAddDuplicatePredecessor() {
            node.addPredecessor("mcp:step1");
            node.addPredecessor("mcp:step1");

            assertEquals(1, node.getPredecessorIds().size());
        }

        @Test
        @DisplayName("Should add multiple predecessors")
        void shouldAddMultiplePredecessors() {
            node.addPredecessor("mcp:step1");
            node.addPredecessor("mcp:step2");
            node.addPredecessor("mcp:step3");

            assertEquals(3, node.getPredecessorIds().size());
        }

        @Test
        @DisplayName("Should set all predecessors at once")
        void shouldSetAllPredecessorsAtOnce() {
            node.addPredecessor("mcp:old");
            node.setPredecessors(List.of("mcp:new1", "mcp:new2"));

            assertEquals(2, node.getPredecessorIds().size());
            assertTrue(node.getPredecessorIds().contains("mcp:new1"));
            assertTrue(node.getPredecessorIds().contains("mcp:new2"));
            assertFalse(node.getPredecessorIds().contains("mcp:old"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Implicit merge detection
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Implicit Merge Detection")
    class ImplicitMergeDetectionTests {

        @Test
        @DisplayName("isImplicitMerge() should return false with no predecessors")
        void isImplicitMergeShouldReturnFalseWithNoPredecessors() {
            assertFalse(node.isImplicitMerge());
        }

        @Test
        @DisplayName("isImplicitMerge() should return false with one predecessor")
        void isImplicitMergeShouldReturnFalseWithOnePredecessor() {
            node.addPredecessor("mcp:step1");
            assertFalse(node.isImplicitMerge());
        }

        @Test
        @DisplayName("isImplicitMerge() should return true with multiple predecessors")
        void isImplicitMergeShouldReturnTrueWithMultiplePredecessors() {
            node.addPredecessor("mcp:step1");
            node.addPredecessor("mcp:step2");
            assertTrue(node.isImplicitMerge());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Successor management
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Successor Management")
    class SuccessorManagementTests {

        @Test
        @DisplayName("Should add successor")
        void shouldAddSuccessor() {
            ExecutionNode successor = new TestableBaseNode("mcp:next", NodeType.MCP);
            node.addSuccessor(successor);

            assertEquals(1, node.getSuccessors().size());
            assertEquals("mcp:next", node.getSuccessors().get(0).getNodeId());
        }

        @Test
        @DisplayName("Should add multiple successors")
        void shouldAddMultipleSuccessors() {
            node.addSuccessor(new TestableBaseNode("mcp:step1", NodeType.MCP));
            node.addSuccessor(new TestableBaseNode("mcp:step2", NodeType.MCP));

            assertEquals(2, node.getSuccessors().size());
        }

        @Test
        @DisplayName("Should set all successors at once")
        void shouldSetAllSuccessorsAtOnce() {
            node.addSuccessor(new TestableBaseNode("mcp:old", NodeType.MCP));

            List<ExecutionNode> newSuccessors = List.of(
                new TestableBaseNode("mcp:new1", NodeType.MCP),
                new TestableBaseNode("mcp:new2", NodeType.MCP)
            );
            node.setSuccessors(newSuccessors);

            assertEquals(2, node.getSuccessors().size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // canExecute() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("canExecute()")
    class CanExecuteTests {

        @Test
        @DisplayName("Should return true when no dependencies")
        void shouldReturnTrueWhenNoDependencies() {
            assertTrue(node.canExecute(context));
        }

        @Test
        @DisplayName("Should return false when dependencies not completed")
        void shouldReturnFalseWhenDependenciesNotCompleted() {
            node.addPredecessor("mcp:step1");
            // step1 is not completed in context
            assertFalse(node.canExecute(context));
        }

        @Test
        @DisplayName("Should return true when all dependencies completed")
        void shouldReturnTrueWhenAllDependenciesCompleted() {
            node.addPredecessor("mcp:step1");

            // Mark step1 as completed
            NodeExecutionResult result = NodeExecutionResult.success("mcp:step1", Map.of("data", "value"));
            ExecutionContext updatedContext = context.withResult("mcp:step1", result);

            assertTrue(node.canExecute(updatedContext));
        }

        @Test
        @DisplayName("Should return true when all multiple dependencies completed")
        void shouldReturnTrueWhenAllMultipleDependenciesCompleted() {
            node.addPredecessor("mcp:step1");
            node.addPredecessor("mcp:step2");

            // Mark both steps as completed
            NodeExecutionResult result1 = NodeExecutionResult.success("mcp:step1", Map.of());
            NodeExecutionResult result2 = NodeExecutionResult.success("mcp:step2", Map.of());

            ExecutionContext updatedContext = context
                .withResult("mcp:step1", result1)
                .withResult("mcp:step2", result2);

            assertTrue(node.canExecute(updatedContext));
        }

        @Test
        @DisplayName("Should return false when one of multiple dependencies not completed")
        void shouldReturnFalseWhenOneOfMultipleDependenciesNotCompleted() {
            node.addPredecessor("mcp:step1");
            node.addPredecessor("mcp:step2");

            // Only mark step1 as completed
            NodeExecutionResult result1 = NodeExecutionResult.success("mcp:step1", Map.of());
            ExecutionContext updatedContext = context.withResult("mcp:step1", result1);

            assertFalse(node.canExecute(updatedContext));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getNextNodes() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getNextNodes()")
    class GetNextNodesTests {

        @Test
        @DisplayName("Should return all successors on success")
        void shouldReturnAllSuccessorsOnSuccess() {
            ExecutionNode successor1 = new TestableBaseNode("mcp:next1", NodeType.MCP);
            ExecutionNode successor2 = new TestableBaseNode("mcp:next2", NodeType.MCP);
            node.addSuccessor(successor1);
            node.addSuccessor(successor2);

            NodeExecutionResult successResult = NodeExecutionResult.success(node.getNodeId(), Map.of());
            List<ExecutionNode> nextNodes = node.getNextNodes(successResult);

            assertEquals(2, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty list on failure")
        void shouldReturnEmptyListOnFailure() {
            ExecutionNode successor = new TestableBaseNode("mcp:next", NodeType.MCP);
            node.addSuccessor(successor);

            NodeExecutionResult failureResult = NodeExecutionResult.failure(node.getNodeId(), "Error");
            List<ExecutionNode> nextNodes = node.getNextNodes(failureResult);

            assertTrue(nextNodes.isEmpty());
        }

        @Test
        @DisplayName("Should return successors when result is null")
        void shouldReturnSuccessorsWhenResultIsNull() {
            ExecutionNode successor = new TestableBaseNode("mcp:next", NodeType.MCP);
            node.addSuccessor(successor);

            List<ExecutionNode> nextNodes = node.getNextNodes(null);

            assertEquals(1, nextNodes.size());
        }

        @Test
        @DisplayName("Should return empty when no successors")
        void shouldReturnEmptyWhenNoSuccessors() {
            NodeExecutionResult successResult = NodeExecutionResult.success(node.getNodeId(), Map.of());
            List<ExecutionNode> nextNodes = node.getNextNodes(successResult);

            assertTrue(nextNodes.isEmpty());
        }

        @Test
        @DisplayName("Should pass through a null successor on success without filtering or NPE")
        void shouldPassThroughNullSuccessorOnSuccess() {
            // ArrayList-backed successors accept null; getNextNodes does not filter on success.
            node.addSuccessor(new TestableBaseNode("mcp:real", NodeType.MCP));
            node.addSuccessor(null);

            NodeExecutionResult successResult = NodeExecutionResult.success(node.getNodeId(), Map.of());

            List<ExecutionNode> nextNodes = assertDoesNotThrow(() -> node.getNextNodes(successResult));

            // The null is NOT filtered out: both entries are returned, one of them null.
            assertEquals(2, nextNodes.size());
            assertNull(nextNodes.get(1));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // getMetadata() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("getMetadata()")
    class GetMetadataTests {

        @Test
        @DisplayName("Should return metadata with nodeId, type, and successorCount")
        void shouldReturnMetadata() {
            Map<String, Object> metadata = node.getMetadata();

            assertEquals("test:node1", metadata.get("nodeId"));
            assertEquals("MCP", metadata.get("type"));
            assertEquals(0, metadata.get("successorCount"));
        }

        @Test
        @DisplayName("Should reflect correct successor count in metadata")
        void shouldReflectCorrectSuccessorCountInMetadata() {
            node.addSuccessor(new TestableBaseNode("mcp:next1", NodeType.MCP));
            node.addSuccessor(new TestableBaseNode("mcp:next2", NodeType.MCP));

            Map<String, Object> metadata = node.getMetadata();
            assertEquals(2, metadata.get("successorCount"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // onComplete() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("onComplete()")
    class OnCompleteTests {

        @Test
        @DisplayName("Should not throw on success result")
        void shouldNotThrowOnSuccessResult() {
            NodeExecutionResult result = NodeExecutionResult.success(node.getNodeId(), Map.of());
            assertDoesNotThrow(() -> node.onComplete(context, result));
        }

        @Test
        @DisplayName("Should not throw on failure result")
        void shouldNotThrowOnFailureResult() {
            NodeExecutionResult result = NodeExecutionResult.failure(node.getNodeId(), "Error");
            assertDoesNotThrow(() -> node.onComplete(context, result));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Test implementation of BaseNode
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Concrete implementation of BaseNode for testing purposes.
     */
    // ═══════════════════════════════════════════════════════════════════════════
    // Template resolution: one resolver for every field of every node
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Template resolution")
    class TemplateResolutionTests {

        private void resolvesTo(Object value) {
            org.mockito.Mockito.when(mockTemplateAdapter.resolveTemplates(
                    org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    Map<String, Object> out = new java.util.HashMap<>();
                    out.put("__v__", value);
                    return out;
                });
            node.setTemplateAdapter(mockTemplateAdapter);
        }

        @Test
        @DisplayName("a reference that resolves to nothing is null, never the configured {{...}} text")
        void referenceToNothingIsNullNotTheTemplate() {
            resolvesTo(null);

            assertNull(node.resolveTemplateString("{{core:skipped.output.email}}", context));
        }

        @Test
        @DisplayName("a reference to an object is its JSON, never Java's {a=1} that reads as unresolved")
        void objectIsJsonNotJavaToString() {
            Map<String, Object> obj = new java.util.LinkedHashMap<>();
            obj.put("a", 1);
            obj.put("b", "x");
            resolvesTo(obj);

            assertEquals("{\"a\":1,\"b\":\"x\"}", node.resolveTemplateString("{{core:up.output.obj}}", context));
        }

        @Test
        @DisplayName("a reference to a list is its JSON array, never [a, b]")
        void listIsJsonArray() {
            resolvesTo(List.of("a@x.io", "b@y.io"));

            assertEquals("[\"a@x.io\",\"b@y.io\"]", node.resolveTemplateString("{{core:up.output.to}}", context));
        }

        @Test
        @DisplayName("the typed resolver keeps the referenced value's own type")
        void typedResolverKeepsType() {
            Map<String, Object> obj = Map.of("k", 2);
            resolvesTo(obj);

            assertSame(obj, node.resolveTemplateValue("{{core:up.output.obj}}", context));
        }

        @Test
        @DisplayName("a resolution that throws fails with the expression, instead of running with the raw template")
        void failureThrowsWithTheExpression() {
            org.mockito.Mockito.when(mockTemplateAdapter.resolveTemplates(
                    org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalArgumentException("json() could not parse"));
            node.setTemplateAdapter(mockTemplateAdapter);

            IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> node.resolveTemplateString("{{json(core:up.output.text)}}", context));
            assertTrue(e.getMessage().contains("{{json(core:up.output.text)}}"), e.getMessage());
            assertTrue(e.getMessage().contains("json() could not parse"), e.getMessage());
        }

        @Test
        @DisplayName("a plain string and a blank value pass through unchanged")
        void plainAndBlankPassThrough() {
            resolvesTo("literal");
            assertEquals("literal", node.resolveTemplateString("literal", context));
            assertEquals("  ", node.resolveTemplateString("  ", context));
            assertNull(node.resolveTemplateString(null, context));
        }

        @Test
        @DisplayName("a map is resolved leaf by leaf: an author's own `template` key is kept, and so is key order")
        @SuppressWarnings("unchecked")
        void mapIsResolvedLeafByLeaf() {
            // Handed whole to the adapter, a map carrying a `template` key was taken for a template
            // SPEC and collapsed to that one value, in an unordered copy.
            org.mockito.Mockito.when(mockTemplateAdapter.resolveTemplates(
                    org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(TemplateResolutionStubs.templatesResolveTo("R"));
            node.setTemplateAdapter(mockTemplateAdapter);
            Map<String, Object> configured = new java.util.LinkedHashMap<>();
            configured.put("z", "{{core:a.output.x}}");
            configured.put("template", "invoice-v2");
            configured.put("nested", List.of("{{core:b.output.y}}", 3));

            Map<String, Object> resolved = (Map<String, Object>) node.resolveTemplateValue(configured, context);

            assertEquals(List.of("z", "template", "nested"), List.copyOf(resolved.keySet()));
            assertEquals("R", resolved.get("z"));
            assertEquals("invoice-v2", resolved.get("template"));
            assertEquals(List.of("R", 3), resolved.get("nested"));
        }

        @Test
        @DisplayName("without a template adapter the configured value is returned as-is")
        void noAdapterReturnsConfigured() {
            assertEquals("{{core:x.output.y}}", node.resolveTemplateString("{{core:x.output.y}}", context));
        }
    }

    @Nested
    @DisplayName("Deferred scalars (numeric / boolean fields written as {{...}})")
    class DeferredScalarTests {

        private void resolvesEveryTemplateTo(Object value) {
            org.mockito.Mockito.when(mockTemplateAdapter.resolveTemplates(
                    org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(TemplateResolutionStubs.templatesResolveTo(value));
            node.setTemplateAdapter(mockTemplateAdapter);
        }

        @Test
        @DisplayName("the typed config is rebuilt with the resolved value; the other fields keep theirs")
        void rebuildsTheConfigWithTheResolvedValue() {
            resolvesEveryTemplateTo(25);
            node.setDeferredScalars(Map.of("limit", Map.of("count", "{{core:cfg.output.n}}")));

            com.apimarketplace.orchestrator.domain.workflow.Core.LimitConfig cfg = node.withDeferredScalars("limit",
                new com.apimarketplace.orchestrator.domain.workflow.Core.LimitConfig(10, null, 3, null),
                com.apimarketplace.orchestrator.domain.workflow.Core.LimitConfig.class, context);

            assertEquals(25, cfg.count());
            assertEquals(3, cfg.offset());
        }

        @Test
        @DisplayName("a template resolving to numeric TEXT is read as the number")
        void numericTextIsCoerced() {
            resolvesEveryTemplateTo(" 7 ");
            node.setDeferredScalars(Map.of("limit", Map.of("count", "{{core:cfg.output.n}}")));

            assertEquals(7, node.withDeferredScalars("limit",
                new com.apimarketplace.orchestrator.domain.workflow.Core.LimitConfig(10, null, 0, null),
                com.apimarketplace.orchestrator.domain.workflow.Core.LimitConfig.class, context).count());
        }

        @Test
        @DisplayName("a template resolving to something that is not a number fails naming the config")
        void nonNumberFails() {
            resolvesEveryTemplateTo("abc");
            node.setDeferredScalars(Map.of("limit", Map.of("count", "{{core:cfg.output.n}}")));

            IllegalStateException e = assertThrows(IllegalStateException.class, () -> node.withDeferredScalars("limit",
                new com.apimarketplace.orchestrator.domain.workflow.Core.LimitConfig(10, null, 0, null),
                com.apimarketplace.orchestrator.domain.workflow.Core.LimitConfig.class, context));
            assertTrue(e.getMessage().contains("limit.count"), e.getMessage());
        }

        @Test
        @DisplayName("a template resolving to nothing fails naming the field, never runs on the default")
        void nothingFails() {
            resolvesEveryTemplateTo(null);
            node.setDeferredScalars(Map.of("limit", Map.of("count", "{{core:skipped.output.n}}")));

            IllegalStateException e = assertThrows(IllegalStateException.class, () -> node.withDeferredScalars("limit",
                new com.apimarketplace.orchestrator.domain.workflow.Core.LimitConfig(10, null, 0, null),
                com.apimarketplace.orchestrator.domain.workflow.Core.LimitConfig.class, context));
            assertTrue(e.getMessage().contains("limit.count"), e.getMessage());
        }

        @Test
        @DisplayName("without a deferred template the config is returned as-is")
        void noTemplateReturnsConfig() {
            com.apimarketplace.orchestrator.domain.workflow.Core.LimitConfig config =
                new com.apimarketplace.orchestrator.domain.workflow.Core.LimitConfig(10, null, 0, null);

            assertSame(config, node.withDeferredScalars("limit", config,
                com.apimarketplace.orchestrator.domain.workflow.Core.LimitConfig.class, context));
        }

        @Test
        @DisplayName("a boolean component named isX survives the rebuild when ANOTHER field is templated")
        void isPrefixedBooleanSurvives() {
            // Serialized, `isHtml` comes back as `html` and the rebuilt config read false.
            resolvesEveryTemplateTo(99L);
            node.setDeferredScalars(Map.of("sendEmail", Map.of("credentialId", "{{core:cfg.output.id}}")));
            com.apimarketplace.orchestrator.domain.workflow.Core.SendEmailConfig config =
                new com.apimarketplace.orchestrator.domain.workflow.Core.SendEmailConfig(
                    null, 587, null, null, true, null, null, "a@x.io", null, null,
                    "Subject", "Body", true, null, null, null, null);

            com.apimarketplace.orchestrator.domain.workflow.Core.SendEmailConfig cfg = node.withDeferredScalars(
                "sendEmail", config, com.apimarketplace.orchestrator.domain.workflow.Core.SendEmailConfig.class, context);

            assertTrue(cfg.isHtml(), "isHtml must keep its configured true");
            assertEquals(99L, cfg.credentialId());
        }

        @Test
        @DisplayName("a fractional value for an integer field is refused, never truncated to its whole part")
        void fractionForIntegerFieldIsRefused() {
            resolvesEveryTemplateTo(5.7);
            node.setDeferredScalars(Map.of("limit", Map.of("count", "{{core:cfg.output.n}}")));

            IllegalStateException e = assertThrows(IllegalStateException.class, () -> node.withDeferredScalars("limit",
                new com.apimarketplace.orchestrator.domain.workflow.Core.LimitConfig(10, null, 0, null),
                com.apimarketplace.orchestrator.domain.workflow.Core.LimitConfig.class, context));
            assertTrue(e.getMessage().contains("limit.count"), e.getMessage());
        }

        @Test
        @DisplayName("one shared node, two concurrent contexts: each execution gets its OWN resolved value")
        void sharedNodeIsNotMutatedAcrossContexts() {
            // Nodes are shared by concurrent split items; the rebuilt config must never leak
            // from one execution into another through the node.
            org.mockito.Mockito.when(mockTemplateAdapter.resolveTemplates(
                    org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    ExecutionContext ctx = inv.getArgument(1);
                    Map<String, Object> out = new java.util.HashMap<>();
                    out.put("__v__", ctx.itemIndex() == 0 ? 3 : 8);
                    return out;
                });
            node.setTemplateAdapter(mockTemplateAdapter);
            node.setDeferredScalars(Map.of("limit", Map.of("count", "{{item.n}}")));
            com.apimarketplace.orchestrator.domain.workflow.Core.LimitConfig shared =
                new com.apimarketplace.orchestrator.domain.workflow.Core.LimitConfig(10, null, 0, null);
            ExecutionContext second = ExecutionContext.create("run-1", "workflow-run-1", "tenant-1", "item-2", 1,
                Map.of(), mockPlan);

            int a = node.withDeferredScalars("limit", shared,
                com.apimarketplace.orchestrator.domain.workflow.Core.LimitConfig.class, context).count();
            int b = node.withDeferredScalars("limit", shared,
                com.apimarketplace.orchestrator.domain.workflow.Core.LimitConfig.class, second).count();

            assertEquals(3, a);
            assertEquals(8, b);
            assertEquals(10, shared.count(), "the shared config is untouched");
        }

        @Test
        @DisplayName("resolveDeferredLong refuses a fractional value")
        void longRefusesFraction() {
            resolvesEveryTemplateTo(2.5);

            IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> node.resolveDeferredLong("wait", "duration", "{{core:cfg.output.d}}", context));
            assertTrue(e.getMessage().contains("whole number"), e.getMessage());
        }
    }

    private static class TestableBaseNode extends BaseNode {

        public TestableBaseNode(String nodeId, NodeType type) {
            super(nodeId, type);
        }

        @Override
        public NodeExecutionResult execute(ExecutionContext context) {
            return NodeExecutionResult.success(nodeId, Map.of("test", "result"));
        }
    }
}
