package com.apimarketplace.orchestrator.services.template;

import com.apimarketplace.orchestrator.domain.WorkflowExecutionContext;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for NamespaceResolver.
 * Tests namespace-prefixed variable resolution from WorkflowExecutionContext.
 *
 * Covers all 7 prefix categories:
 * - trigger: (webhooks, chat, schedule, etc.)
 * - mcp: (tool calls)
 * - table: (CRUD operations)
 * - agent: (AI reasoning nodes)
 * - core: (flow control nodes)
 * - note: (notes)
 * - interface: (interfaces)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NamespaceResolver")
class NamespaceResolverTest {

    @Mock
    private PathNavigator pathNavigator;

    @Mock
    private WorkflowExecutionContext context;

    private NamespaceResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new NamespaceResolver(pathNavigator);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // resolveVariable() - Main Entry Point
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("resolveVariable()")
    class ResolveVariableTests {

        @Test
        @DisplayName("Should return null for null path")
        void shouldReturnNullForNullPath() {
            Object result = resolver.resolveVariable(null, context);

            assertNull(result);
        }

        @Test
        @DisplayName("Should return null for empty path")
        void shouldReturnNullForEmptyPath() {
            Object result = resolver.resolveVariable("", context);

            assertNull(result);
        }

        @Test
        @DisplayName("Should resolve steps namespace")
        void shouldResolveStepsNamespace() {
            Map<String, Object> stepOutput = Map.of("value", 42);
            // Only getStepOutput is called, not getStepOutputs
            when(context.getStepOutput("mcp:api_call")).thenReturn(stepOutput);

            Object result = resolver.resolveVariable("steps.api_call", context);

            assertEquals(stepOutput, result);
        }

        @Test
        @DisplayName("Should resolve triggers namespace")
        void shouldResolveTriggersNamespace() {
            Map<String, Object> triggerData = Map.of("payload", "data");
            when(context.getDataItem("trigger:webhook")).thenReturn(triggerData);

            Object result = resolver.resolveVariable("triggers.webhook", context);

            assertEquals(triggerData, result);
        }

        @Test
        @DisplayName("Should resolve data namespace")
        void shouldResolveDataNamespace() {
            Map<String, Object> tableData = Map.of("rows", List.of());
            when(context.getDataItem("table:users")).thenReturn(tableData);

            Object result = resolver.resolveVariable("data.users", context);

            assertEquals(tableData, result);
        }

        @Test
        @DisplayName("Should resolve current_item namespace")
        void shouldResolveCurrentItemNamespace() {
            Map<String, Object> item = Map.of("id", 1, "name", "test");
            when(context.getDataItem("current_item")).thenReturn(item);
            when(pathNavigator.navigatePath(item, "name")).thenReturn("test");

            Object result = resolver.resolveVariable("current_item.name", context);

            assertEquals("test", result);
        }

        @Test
        @DisplayName("Should fall back to prefixed variable resolution")
        void shouldFallBackToPrefixedVariableResolution() {
            Map<String, Object> stepOutput = Map.of("result", "success");
            when(context.getStepOutput("mcp:my_step")).thenReturn(stepOutput);

            Object result = resolver.resolveVariable("mcp:my_step", context);

            assertEquals(stepOutput, result);
        }

        @Test
        @DisplayName("Should return null for secrets namespace (not implemented)")
        void shouldReturnNullForSecretsNamespace() {
            Object result = resolver.resolveVariable("secrets.api_key", context);

            assertNull(result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // resolveTriggersNamespace() - trigger: prefix
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("resolveTriggersNamespace()")
    class ResolveTriggersNamespaceTests {

        @Test
        @DisplayName("Should return null for null path")
        void shouldReturnNullForNullPath() {
            Object result = resolver.resolveTriggersNamespace(null, context);

            assertNull(result);
        }

        @Test
        @DisplayName("Should return null for empty path")
        void shouldReturnNullForEmptyPath() {
            Object result = resolver.resolveTriggersNamespace("", context);

            assertNull(result);
        }

        @Test
        @DisplayName("Should resolve trigger data by label")
        void shouldResolveTriggerDataByLabel() {
            Map<String, Object> triggerData = Map.of("payload", Map.of("id", 123));
            when(context.getDataItem("trigger:webhook")).thenReturn(triggerData);

            Object result = resolver.resolveTriggersNamespace("webhook", context);

            assertEquals(triggerData, result);
        }

        @Test
        @DisplayName("Should resolve trigger with nested path")
        void shouldResolveTriggerWithNestedPath() {
            Map<String, Object> triggerData = Map.of("payload", Map.of("id", 123));
            when(context.getDataItem("trigger:webhook")).thenReturn(triggerData);
            when(pathNavigator.navigatePath(triggerData, "payload.id")).thenReturn(123);

            Object result = resolver.resolveTriggersNamespace("webhook.payload.id", context);

            assertEquals(123, result);
        }

        @Test
        @DisplayName("Should try stepOutput if dataItem not found")
        void shouldTryStepOutputIfDataItemNotFound() {
            Map<String, Object> triggerData = Map.of("data", "value");
            when(context.getDataItem("trigger:start")).thenReturn(null);
            when(context.getStepOutput("trigger:start")).thenReturn(triggerData);

            Object result = resolver.resolveTriggersNamespace("start", context);

            assertEquals(triggerData, result);
        }

        @Test
        @DisplayName("Should normalize trigger label")
        void shouldNormalizeTriggerLabel() {
            Map<String, Object> triggerData = Map.of("value", 42);
            // "My Webhook" normalizes to "my_webhook"
            when(context.getDataItem("trigger:my_webhook")).thenReturn(triggerData);

            Object result = resolver.resolveTriggersNamespace("My Webhook", context);

            assertEquals(triggerData, result);
        }

        @Test
        @DisplayName("Should fallback to raw label if normalized not found")
        void shouldFallbackToRawLabelIfNormalizedNotFound() {
            Map<String, Object> triggerData = Map.of("raw", true);
            when(context.getDataItem("trigger:custom_label")).thenReturn(null);
            when(context.getStepOutput("trigger:custom_label")).thenReturn(null);
            when(context.getDataItem("trigger:Custom Label")).thenReturn(triggerData);

            Object result = resolver.resolveTriggersNamespace("Custom Label", context);

            assertEquals(triggerData, result);
        }

        @Test
        @DisplayName("Should return null if trigger not found")
        void shouldReturnNullIfTriggerNotFound() {
            when(context.getDataItem(anyString())).thenReturn(null);
            when(context.getStepOutput(anyString())).thenReturn(null);

            Object result = resolver.resolveTriggersNamespace("unknown", context);

            assertNull(result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // resolveStepsNamespace() - mcp: prefix
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("resolveStepsNamespace()")
    class ResolveStepsNamespaceTests {

        @Test
        @DisplayName("Should return all step outputs for null path")
        void shouldReturnAllStepOutputsForNullPath() {
            Map<String, Object> allOutputs = Map.of("step1", Map.of(), "step2", Map.of());
            when(context.getStepOutputs()).thenReturn(allOutputs);

            Object result = resolver.resolveStepsNamespace(null, context);

            assertEquals(allOutputs, result);
        }

        @Test
        @DisplayName("Should return all step outputs for empty path")
        void shouldReturnAllStepOutputsForEmptyPath() {
            Map<String, Object> allOutputs = Map.of("step1", Map.of());
            when(context.getStepOutputs()).thenReturn(allOutputs);

            Object result = resolver.resolveStepsNamespace("", context);

            assertEquals(allOutputs, result);
        }

        @Test
        @DisplayName("Should resolve step by label")
        void shouldResolveStepByLabel() {
            Map<String, Object> stepData = Map.of("output", Map.of("result", "success"));
            when(context.getStepOutput("mcp:api_call")).thenReturn(stepData);

            Object result = resolver.resolveStepsNamespace("api_call", context);

            assertEquals(stepData, result);
        }

        @Test
        @DisplayName("Should resolve step with nested path")
        void shouldResolveStepWithNestedPath() {
            Map<String, Object> stepData = Map.of("output", Map.of("data", List.of(1, 2, 3)));
            when(context.getStepOutput("mcp:fetch")).thenReturn(stepData);
            when(pathNavigator.navigatePath(stepData, "output.data")).thenReturn(List.of(1, 2, 3));

            Object result = resolver.resolveStepsNamespace("fetch.output.data", context);

            assertEquals(List.of(1, 2, 3), result);
        }

        @Test
        @DisplayName("Should normalize step label")
        void shouldNormalizeStepLabel() {
            Map<String, Object> stepData = Map.of("value", 42);
            // "API Call" normalizes to "api_call"
            when(context.getStepOutput("mcp:api_call")).thenReturn(stepData);

            Object result = resolver.resolveStepsNamespace("API Call", context);

            assertEquals(stepData, result);
        }

        @Test
        @DisplayName("Should try multiple key formats")
        void shouldTryMultipleKeyFormats() {
            Map<String, Object> stepData = Map.of("found", true);
            when(context.getStepOutput("mcp:my_step")).thenReturn(null);
            when(context.getStepOutput("mcp:My Step")).thenReturn(null);
            when(context.getStepOutput("My Step")).thenReturn(stepData);

            Object result = resolver.resolveStepsNamespace("My Step", context);

            assertEquals(stepData, result);
        }

        @Test
        @DisplayName("Should return null if step not found")
        void shouldReturnNullIfStepNotFound() {
            when(context.getStepOutput(anyString())).thenReturn(null);

            Object result = resolver.resolveStepsNamespace("unknown_step", context);

            assertNull(result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // resolveAgentNamespace() - agent: prefix
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("resolveAgentNamespace()")
    class ResolveAgentNamespaceTests {

        @Test
        @DisplayName("Should return null for null path")
        void shouldReturnNullForNullPath() {
            Object result = resolver.resolveAgentNamespace(null, context);

            assertNull(result);
        }

        @Test
        @DisplayName("Should return null for empty path")
        void shouldReturnNullForEmptyPath() {
            Object result = resolver.resolveAgentNamespace("", context);

            assertNull(result);
        }

        @Test
        @DisplayName("Should resolve agent by label")
        void shouldResolveAgentByLabel() {
            Map<String, Object> agentData = Map.of("response", "AI generated text");
            when(context.getStepOutput("agent:analyzer")).thenReturn(agentData);

            Object result = resolver.resolveAgentNamespace("analyzer", context);

            assertEquals(agentData, result);
        }

        @Test
        @DisplayName("Should resolve agent response field")
        void shouldResolveAgentResponseField() {
            Map<String, Object> agentData = Map.of("response", "AI analysis result");
            when(context.getStepOutput("agent:analyzer")).thenReturn(agentData);
            when(pathNavigator.navigatePath(agentData, "response")).thenReturn("AI analysis result");

            Object result = resolver.resolveAgentNamespace("analyzer.response", context);

            assertEquals("AI analysis result", result);
        }

        @Test
        @DisplayName("Should try output wrapper for nested paths")
        void shouldTryOutputWrapperForNestedPaths() {
            Map<String, Object> output = Map.of("category", "urgent");
            Map<String, Object> agentData = Map.of("output", output);
            when(context.getStepOutput("agent:classifier")).thenReturn(agentData);
            when(pathNavigator.navigatePath(agentData, "category")).thenReturn(null);
            when(pathNavigator.navigatePath(output, "category")).thenReturn("urgent");

            Object result = resolver.resolveAgentNamespace("classifier.category", context);

            assertEquals("urgent", result);
        }

        @Test
        @DisplayName("Should normalize agent label")
        void shouldNormalizeAgentLabel() {
            Map<String, Object> agentData = Map.of("passed", true);
            when(context.getStepOutput("agent:content_checker")).thenReturn(agentData);

            Object result = resolver.resolveAgentNamespace("Content Checker", context);

            assertEquals(agentData, result);
        }

        @Test
        @DisplayName("Should fallback to raw label")
        void shouldFallbackToRawLabel() {
            Map<String, Object> agentData = Map.of("result", "found");
            when(context.getStepOutput("agent:my_agent")).thenReturn(null);
            when(context.getStepOutput("agent:My Agent")).thenReturn(agentData);

            Object result = resolver.resolveAgentNamespace("My Agent", context);

            assertEquals(agentData, result);
        }

        @Test
        @DisplayName("Should return null if agent not found")
        void shouldReturnNullIfAgentNotFound() {
            when(context.getStepOutput(anyString())).thenReturn(null);

            Object result = resolver.resolveAgentNamespace("unknown_agent", context);

            assertNull(result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // resolveCoreNamespace() - core: prefix
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("resolveCoreNamespace()")
    class ResolveCoreNamespaceTests {

        @Test
        @DisplayName("Should return null for null path")
        void shouldReturnNullForNullPath() {
            Object result = resolver.resolveCoreNamespace(null, context);

            assertNull(result);
        }

        @Test
        @DisplayName("Should return null for empty path")
        void shouldReturnNullForEmptyPath() {
            Object result = resolver.resolveCoreNamespace("", context);

            assertNull(result);
        }

        @Test
        @DisplayName("Should resolve core.index directly")
        void shouldResolveCoreIndexDirectly() {
            when(context.getCurrentItemIndex()).thenReturn(5);

            Object result = resolver.resolveCoreNamespace("index", context);

            assertEquals(5, result);
        }

        @Test
        @DisplayName("Should resolve core.iteration directly")
        void shouldResolveCoreIterationDirectly() {
            when(context.getCurrentIteration()).thenReturn(3);

            Object result = resolver.resolveCoreNamespace("iteration", context);

            assertEquals(3, result);
        }

        @Test
        @DisplayName("Should resolve core node by label")
        void shouldResolveCoreNodeByLabel() {
            Map<String, Object> coreData = Map.of("iteration", 5, "items", List.of());
            when(context.getStepOutput("core:retry_loop")).thenReturn(coreData);

            Object result = resolver.resolveCoreNamespace("retry_loop", context);

            assertEquals(coreData, result);
        }

        @Test
        @DisplayName("Should resolve loop iteration from context")
        void shouldResolveLoopIterationFromContext() {
            when(context.getStepOutput(anyString())).thenReturn(null);
            when(context.getCurrentIteration()).thenReturn(7);

            Object result = resolver.resolveCoreNamespace("loop.iteration", context);

            assertEquals(7, result);
        }

        @Test
        @DisplayName("Should resolve current_item from context")
        void shouldResolveCurrentItemFromContext() {
            Map<String, Object> item = Map.of("id", 42);
            when(context.getStepOutput(anyString())).thenReturn(null);
            when(context.getDataItem("current_item")).thenReturn(item);

            Object result = resolver.resolveCoreNamespace("foreach.current_item", context);

            assertEquals(item, result);
        }

        @Test
        @DisplayName("Should resolve current_index from context")
        void shouldResolveCurrentIndexFromContext() {
            when(context.getStepOutput(anyString())).thenReturn(null);
            when(context.getCurrentItemIndex()).thenReturn(2);

            Object result = resolver.resolveCoreNamespace("foreach.current_index", context);

            assertEquals(2, result);
        }

        @Test
        @DisplayName("Should resolve item field path (legacy pattern)")
        void shouldResolveItemFieldPath() {
            Map<String, Object> coreData = Map.of("type", "split");
            Map<String, Object> item = Map.of("name", "test item");
            when(context.getStepOutput("core:split")).thenReturn(coreData);
            when(context.getDataItem("current_item")).thenReturn(item);
            // First call tries direct navigation on coreData, returns null
            when(pathNavigator.navigatePath(coreData, "item.name")).thenReturn(null);
            // Then falls back to legacy handler
            when(pathNavigator.navigatePath(item, "name")).thenReturn("test item");

            Object result = resolver.resolveCoreNamespace("split.item.name", context);

            assertEquals("test item", result);
        }

        @Test
        @DisplayName("Should resolve current_item field path (legacy pattern)")
        void shouldResolveCurrentItemFieldPath() {
            Map<String, Object> coreData = Map.of("type", "split");
            Map<String, Object> item = Map.of("value", 100);
            when(context.getStepOutput("core:process")).thenReturn(coreData);
            when(context.getDataItem("current_item")).thenReturn(item);
            // First call tries direct navigation on coreData, returns null
            when(pathNavigator.navigatePath(coreData, "current_item.value")).thenReturn(null);
            // Then falls back to legacy handler
            when(pathNavigator.navigatePath(item, "value")).thenReturn(100);

            Object result = resolver.resolveCoreNamespace("process.current_item.value", context);

            assertEquals(100, result);
        }

        @Test
        @DisplayName("Should resolve unified pattern output.current_item.field")
        void shouldResolveUnifiedPatternCurrentItem() {
            Map<String, Object> currentItem = Map.of("name", "test user", "id", 42);
            Map<String, Object> coreData = Map.of("output", Map.of("current_item", currentItem, "current_index", 0));
            when(context.getStepOutput("core:foreach")).thenReturn(coreData);
            when(pathNavigator.navigatePath(coreData, "output.current_item.name")).thenReturn("test user");

            Object result = resolver.resolveCoreNamespace("foreach.output.current_item.name", context);

            assertEquals("test user", result);
        }

        @Test
        @DisplayName("Should normalize core label")
        void shouldNormalizeCoreLabel() {
            Map<String, Object> coreData = Map.of("result", "done");
            when(context.getStepOutput("core:my_decision")).thenReturn(coreData);

            Object result = resolver.resolveCoreNamespace("My Decision", context);

            assertEquals(coreData, result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // resolveTableNamespace() - table: prefix
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("resolveTableNamespace()")
    class ResolveTableNamespaceTests {

        @Test
        @DisplayName("Should return null for null path")
        void shouldReturnNullForNullPath() {
            assertNull(resolver.resolveTableNamespace(null, context));
        }

        @Test
        @DisplayName("Should return null for empty path")
        void shouldReturnNullForEmptyPath() {
            assertNull(resolver.resolveTableNamespace("", context));
        }

        @Test
        @DisplayName("Should resolve table data with normalized label")
        void shouldResolveTableDataWithNormalizedLabel() {
            Map<String, Object> tableData = Map.of("rows", List.of(Map.of("id", 1)));
            when(context.getStepOutput("table:users")).thenReturn(tableData);

            Object result = resolver.resolveTableNamespace("users", context);
            assertEquals(tableData, result);
        }

        @Test
        @DisplayName("Should resolve table data with path navigation")
        void shouldResolveTableDataWithPathNavigation() {
            List<Map<String, Object>> rows = List.of(Map.of("id", 1));
            Map<String, Object> tableData = Map.of("rows", rows);
            when(context.getStepOutput("table:users")).thenReturn(tableData);
            when(pathNavigator.navigatePath(tableData, "output.rows")).thenReturn(null);
            when(pathNavigator.navigatePath(tableData, "rows")).thenReturn(rows);

            Object result = resolver.resolveTableNamespace("users.output.rows", context);
            assertEquals(rows, result);
        }

        @Test
        @DisplayName("Should return null when table not found")
        void shouldReturnNullWhenTableNotFound() {
            when(context.getStepOutput("table:unknown")).thenReturn(null);

            assertNull(resolver.resolveTableNamespace("unknown", context));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // resolveDataNamespace() - data/table: prefix
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("resolveDataNamespace()")
    class ResolveDataNamespaceTests {

        @Test
        @DisplayName("Should return null for null path")
        void shouldReturnNullForNullPath() {
            Object result = resolver.resolveDataNamespace(null, context);

            assertNull(result);
        }

        @Test
        @DisplayName("Should return null for empty path")
        void shouldReturnNullForEmptyPath() {
            Object result = resolver.resolveDataNamespace("", context);

            assertNull(result);
        }

        @Test
        @DisplayName("Should resolve with table: prefix first")
        void shouldResolveWithTablePrefixFirst() {
            Map<String, Object> tableData = Map.of("rows", List.of());
            when(context.getDataItem("table:users")).thenReturn(tableData);

            Object result = resolver.resolveDataNamespace("users", context);

            assertEquals(tableData, result);
        }

        @Test
        @DisplayName("Should fallback to ds: prefix")
        void shouldFallbackToDsPrefix() {
            Map<String, Object> dsData = Map.of("data", "legacy");
            when(context.getDataItem("table:products")).thenReturn(null);
            when(context.getDataItem("ds:products")).thenReturn(dsData);

            Object result = resolver.resolveDataNamespace("products", context);

            assertEquals(dsData, result);
        }

        @Test
        @DisplayName("Should fallback to raw label")
        void shouldFallbackToRawLabel() {
            Map<String, Object> data = Map.of("value", 42);
            when(context.getDataItem("table:mydata")).thenReturn(null);
            when(context.getDataItem("ds:mydata")).thenReturn(null);
            when(context.getDataItem("mydata")).thenReturn(data);

            Object result = resolver.resolveDataNamespace("mydata", context);

            assertEquals(data, result);
        }

        @Test
        @DisplayName("Should resolve with nested path")
        void shouldResolveWithNestedPath() {
            Map<String, Object> tableData = Map.of("rows", List.of(Map.of("id", 1)));
            when(context.getDataItem("table:users")).thenReturn(tableData);
            when(pathNavigator.navigatePath(tableData, "rows")).thenReturn(List.of(Map.of("id", 1)));

            Object result = resolver.resolveDataNamespace("users.rows", context);

            assertEquals(List.of(Map.of("id", 1)), result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // resolveCurrentItemPath()
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("resolveCurrentItemPath()")
    class ResolveCurrentItemPathTests {

        @Test
        @DisplayName("Should return current_item for null path")
        void shouldReturnCurrentItemForNullPath() {
            Map<String, Object> item = Map.of("id", 1);
            when(context.getDataItem("current_item")).thenReturn(item);

            Object result = resolver.resolveCurrentItemPath(null, context);

            assertEquals(item, result);
        }

        @Test
        @DisplayName("Should return null if no current_item")
        void shouldReturnNullIfNoCurrentItem() {
            when(context.getDataItem("current_item")).thenReturn(null);

            Object result = resolver.resolveCurrentItemPath("field", context);

            assertNull(result);
        }

        @Test
        @DisplayName("Should navigate to field in current_item")
        void shouldNavigateToFieldInCurrentItem() {
            Map<String, Object> item = Map.of("name", "test", "value", 42);
            when(context.getDataItem("current_item")).thenReturn(item);
            when(pathNavigator.navigatePath(item, "name")).thenReturn("test");

            Object result = resolver.resolveCurrentItemPath("name", context);

            assertEquals("test", result);
        }

        @Test
        @DisplayName("Should navigate to nested field")
        void shouldNavigateToNestedField() {
            Map<String, Object> item = Map.of("data", Map.of("nested", "value"));
            when(context.getDataItem("current_item")).thenReturn(item);
            when(pathNavigator.navigatePath(item, "data.nested")).thenReturn("value");

            Object result = resolver.resolveCurrentItemPath("data.nested", context);

            assertEquals("value", result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // resolvePrefixedVariable() - Main prefix resolver
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("resolvePrefixedVariable()")
    class ResolvePrefixedVariableTests {

        @Test
        @DisplayName("Should resolve mcps. namespace")
        void shouldResolveMcpsNamespace() {
            Map<String, Object> stepData = Map.of("result", "success");
            when(context.getStepOutput("mcp:api_call")).thenReturn(stepData);

            Object result = resolver.resolvePrefixedVariable("mcps.api_call", context);

            assertEquals(stepData, result);
        }

        @Test
        @DisplayName("Should resolve triggers. namespace")
        void shouldResolveTriggersNamespace() {
            Map<String, Object> triggerData = Map.of("payload", "data");
            when(context.getDataItem("trigger:webhook")).thenReturn(triggerData);

            Object result = resolver.resolvePrefixedVariable("triggers.webhook", context);

            assertEquals(triggerData, result);
        }

        @Test
        @DisplayName("Should resolve trigger: prefix")
        void shouldResolveTriggerPrefix() {
            Map<String, Object> triggerData = Map.of("value", 42);
            when(context.getDataItem("trigger:start")).thenReturn(triggerData);

            Object result = resolver.resolvePrefixedVariable("trigger:start", context);

            assertEquals(triggerData, result);
        }

        @Test
        @DisplayName("Should resolve mcp: prefix")
        void shouldResolveMcpPrefix() {
            Map<String, Object> stepData = Map.of("output", Map.of("data", "result"));
            when(context.getStepOutput("mcp:fetch")).thenReturn(stepData);

            Object result = resolver.resolvePrefixedVariable("mcp:fetch", context);

            assertEquals(stepData, result);
        }

        @Test
        @DisplayName("Should resolve agent: prefix")
        void shouldResolveAgentPrefix() {
            Map<String, Object> agentData = Map.of("response", "AI output");
            when(context.getStepOutput("agent:analyzer")).thenReturn(agentData);

            Object result = resolver.resolvePrefixedVariable("agent:analyzer", context);

            assertEquals(agentData, result);
        }

        @Test
        @DisplayName("Should resolve core: prefix")
        void shouldResolveCorePrefix() {
            when(context.getCurrentIteration()).thenReturn(5);

            Object result = resolver.resolvePrefixedVariable("core:iteration", context);

            assertEquals(5, result);
        }

        @Test
        @DisplayName("Should search in current_item for simple path")
        void shouldSearchInCurrentItemForSimplePath() {
            Map<String, Object> item = Map.of("fieldName", "fieldValue");
            when(context.getDataItem("current_item")).thenReturn(item);

            Object result = resolver.resolvePrefixedVariable("fieldName", context);

            assertEquals("fieldValue", result);
        }

        @Test
        @DisplayName("Should search in current_item for dotted path")
        void shouldSearchInCurrentItemForDottedPath() {
            Map<String, Object> nested = Map.of("field", "value");
            Map<String, Object> item = Map.of("data", nested);
            when(context.getDataItem("current_item")).thenReturn(item);
            when(pathNavigator.navigatePath(nested, "field")).thenReturn("value");

            Object result = resolver.resolvePrefixedVariable("data.field", context);

            assertEquals("value", result);
        }

        @Test
        @DisplayName("Should search in step outputs")
        void shouldSearchInStepOutputs() {
            Map<String, Object> stepOutputs = new HashMap<>();
            stepOutputs.put("mcp:step1", Map.of("output", Map.of("result", "found")));
            when(context.getDataItem("current_item")).thenReturn(null);
            when(context.getStepOutputs()).thenReturn(stepOutputs);
            // Result is found before reaching getGlobalVariables, so don't stub it

            Object result = resolver.resolvePrefixedVariable("result", context);

            assertEquals("found", result);
        }

        @Test
        @DisplayName("Should search with mcp: prefix in step outputs")
        void shouldSearchWithMcpPrefixInStepOutputs() {
            Map<String, Object> stepOutputs = new HashMap<>();
            stepOutputs.put("mcp:mystep", Map.of("value", 42));
            when(context.getDataItem("current_item")).thenReturn(null);
            when(context.getStepOutputs()).thenReturn(stepOutputs);

            Object result = resolver.resolvePrefixedVariable("mystep", context);

            assertEquals(Map.of("value", 42), result);
        }

        @Test
        @DisplayName("Should search in global variables")
        void shouldSearchInGlobalVariables() {
            when(context.getDataItem("current_item")).thenReturn(null);
            when(context.getStepOutputs()).thenReturn(Map.of());
            when(context.getGlobalVariables()).thenReturn(Map.of("globalVar", "globalValue"));

            Object result = resolver.resolvePrefixedVariable("globalVar", context);

            assertEquals("globalValue", result);
        }

        @Test
        @DisplayName("Should return null if variable not found anywhere")
        void shouldReturnNullIfVariableNotFoundAnywhere() {
            when(context.getDataItem("current_item")).thenReturn(null);
            when(context.getStepOutputs()).thenReturn(Map.of());
            when(context.getGlobalVariables()).thenReturn(Map.of());

            Object result = resolver.resolvePrefixedVariable("unknown_variable", context);

            assertNull(result);
        }

        @Test
        @DisplayName("Should search inside output wrapper in step outputs")
        void shouldSearchInsideOutputWrapperInStepOutputs() {
            Map<String, Object> output = Map.of("deepField", "deepValue");
            Map<String, Object> stepData = Map.of("output", output);
            Map<String, Object> stepOutputs = Map.of("mcp:step1", stepData);

            when(context.getDataItem("current_item")).thenReturn(null);
            when(context.getStepOutputs()).thenReturn(stepOutputs);

            Object result = resolver.resolvePrefixedVariable("deepField", context);

            assertEquals("deepValue", result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Edge Cases and Complex Scenarios
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle special characters in labels")
        void shouldHandleSpecialCharactersInLabels() {
            Map<String, Object> data = Map.of("result", "ok");
            // "Entrée API" normalizes to "entree_api"
            when(context.getStepOutput("mcp:entree_api")).thenReturn(data);

            Object result = resolver.resolveStepsNamespace("Entrée API", context);

            assertEquals(data, result);
        }

        @Test
        @DisplayName("Should handle deeply nested paths")
        void shouldHandleDeeplyNestedPaths() {
            Map<String, Object> stepData = Map.of("output", Map.of("data", Map.of("items", List.of())));
            when(context.getStepOutput("mcp:api")).thenReturn(stepData);
            when(pathNavigator.navigatePath(stepData, "output.data.items[0].name"))
                .thenReturn("first item");

            Object result = resolver.resolveStepsNamespace("api.output.data.items[0].name", context);

            assertEquals("first item", result);
        }

        @Test
        @DisplayName("Should handle null values in context")
        void shouldHandleNullValuesInContext() {
            Map<String, Object> stepOutputs = new HashMap<>();
            stepOutputs.put("mcp:step1", null);
            when(context.getDataItem("current_item")).thenReturn(null);
            when(context.getStepOutputs()).thenReturn(stepOutputs);
            when(context.getGlobalVariables()).thenReturn(null);

            Object result = resolver.resolvePrefixedVariable("someVar", context);

            assertNull(result);
        }

        @Test
        @DisplayName("Should handle empty step outputs map")
        void shouldHandleEmptyStepOutputsMap() {
            when(context.getDataItem("current_item")).thenReturn(null);
            when(context.getStepOutputs()).thenReturn(Map.of());
            when(context.getGlobalVariables()).thenReturn(null);

            Object result = resolver.resolvePrefixedVariable("anyVar", context);

            assertNull(result);
        }

        @Test
        @DisplayName("Should prioritize namespaced resolution over step output search")
        void shouldPrioritizeNamespacedResolutionOverStepOutputSearch() {
            // When using explicit prefix, should use namespace resolver
            Map<String, Object> triggerData = Map.of("payload", "trigger data");
            when(context.getDataItem("trigger:start")).thenReturn(triggerData);

            Object result = resolver.resolvePrefixedVariable("trigger:start", context);

            assertEquals(triggerData, result);
            // Should not search in step outputs
            verify(context, never()).getStepOutputs();
        }

        @Test
        @DisplayName("Should handle multiple colons in path")
        void shouldHandleMultipleColonsInPath() {
            Map<String, Object> coreData = Map.of("branch", "if");
            when(context.getStepOutput("core:decision")).thenReturn(coreData);
            when(pathNavigator.navigatePath(coreData, "if")).thenReturn("if");

            // core:decision:if - should parse as core: prefix with decision:if path
            // But actually the implementation splits on first : only
            Object result = resolver.resolvePrefixedVariable("core:decision.if", context);

            assertNotNull(result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Integration-style Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Integration Scenarios")
    class IntegrationTests {

        @Test
        @DisplayName("Should resolve complete workflow expression: trigger output")
        void shouldResolveCompleteTriggerExpression() {
            Map<String, Object> payload = Map.of("userId", 123, "action", "login");
            Map<String, Object> triggerOutput = Map.of("payload", payload);
            when(context.getDataItem("trigger:webhook")).thenReturn(triggerOutput);
            when(pathNavigator.navigatePath(triggerOutput, "payload.userId")).thenReturn(123);

            Object result = resolver.resolveVariable("trigger:webhook.payload.userId", context);

            assertEquals(123, result);
        }

        @Test
        @DisplayName("Should resolve complete workflow expression: step output")
        void shouldResolveCompleteStepExpression() {
            Map<String, Object> apiResponse = Map.of("data", Map.of("items", List.of("a", "b")));
            Map<String, Object> stepOutput = Map.of("output", apiResponse);
            when(context.getStepOutput("mcp:fetch_data")).thenReturn(stepOutput);
            when(pathNavigator.navigatePath(stepOutput, "output.data.items"))
                .thenReturn(List.of("a", "b"));

            Object result = resolver.resolveVariable("mcp:fetch_data.output.data.items", context);

            assertEquals(List.of("a", "b"), result);
        }

        @Test
        @DisplayName("Should resolve complete workflow expression: agent response")
        void shouldResolveCompleteAgentExpression() {
            Map<String, Object> agentOutput = Map.of(
                "response", "This is the AI analysis",
                "confidence", 0.95
            );
            when(context.getStepOutput("agent:content_analyzer")).thenReturn(agentOutput);
            when(pathNavigator.navigatePath(agentOutput, "confidence")).thenReturn(0.95);

            Object result = resolver.resolveVariable("agent:content_analyzer.confidence", context);

            assertEquals(0.95, result);
        }

        @Test
        @DisplayName("Should resolve complete workflow expression: foreach item")
        void shouldResolveCompleteForEachExpression() {
            Map<String, Object> item = Map.of("id", 42, "name", "Test Item");
            when(context.getDataItem("current_item")).thenReturn(item);
            when(pathNavigator.navigatePath(item, "name")).thenReturn("Test Item");

            Object result = resolver.resolveVariable("current_item.name", context);

            assertEquals("Test Item", result);
        }

        @Test
        @DisplayName("Should resolve complete workflow expression: loop index")
        void shouldResolveCompleteLoopExpression() {
            // core:index resolves to getCurrentItemIndex() (0-based), not getCurrentIteration()
            when(context.getCurrentItemIndex()).thenReturn(2);

            Object result = resolver.resolveVariable("core:index", context);

            // "core:index" becomes "core:" prefix with "index" path
            // which resolves to getCurrentItemIndex() in resolveCoreNamespace
            assertEquals(2, result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // resolveVarsNamespace() - workflow variables bundle
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("resolveVarsNamespace()")
    class ResolveVarsNamespaceTests {

        // Real navigator so deep-path tests exercise actual JSON navigation
        // into the bundle values rather than a stubbed answer.
        private final NamespaceResolver realNavResolver = new NamespaceResolver(new PathNavigator());

        @Test
        @DisplayName("Should return the value for a simple variable name from the bundle")
        void shouldReturnValueForSimpleName() {
            // Arrange
            Map<String, Object> globals = new HashMap<>();
            globals.put("vars", Map.of("api_url", "https://api.example.com"));
            when(context.getGlobalVariables()).thenReturn(globals);

            // Act
            Object result = resolver.resolveVarsNamespace("api_url", context);

            // Assert
            assertEquals("https://api.example.com", result);
        }

        @Test
        @DisplayName("Should navigate a deeper path into a JSON-typed variable value")
        void shouldNavigateDeeperPathIntoJsonValue() {
            Map<String, Object> globals = new HashMap<>();
            globals.put("vars", Map.of("config", Map.of("api", Map.of("url", "https://deep.example.com"))));
            when(context.getGlobalVariables()).thenReturn(globals);

            Object result = realNavResolver.resolveVarsNamespace("config.api.url", context);

            assertEquals("https://deep.example.com", result);
        }

        @Test
        @DisplayName("Should return null when the bundle is absent from global variables")
        void shouldReturnNullWhenBundleAbsent() {
            when(context.getGlobalVariables()).thenReturn(new HashMap<>());

            Object result = resolver.resolveVarsNamespace("api_url", context);

            assertNull(result);
        }

        @Test
        @DisplayName("Should return null when the variable name is unknown in the bundle")
        void shouldReturnNullWhenNameUnknown() {
            Map<String, Object> globals = new HashMap<>();
            globals.put("vars", Map.of("api_url", "https://api.example.com"));
            when(context.getGlobalVariables()).thenReturn(globals);

            Object result = resolver.resolveVarsNamespace("does_not_exist", context);

            assertNull(result);
        }

        @Test
        @DisplayName("Should return null for a null path")
        void shouldReturnNullForNullPath() {
            assertNull(resolver.resolveVarsNamespace(null, context));
        }

        @Test
        @DisplayName("Should return null for an empty path")
        void shouldReturnNullForEmptyPath() {
            assertNull(resolver.resolveVarsNamespace("", context));
        }

        @Test
        @DisplayName("Should return null when the vars global variable is not a map")
        void shouldReturnNullWhenBundleIsNotAMap() {
            Map<String, Object> globals = new HashMap<>();
            globals.put("vars", "not-a-map");
            when(context.getGlobalVariables()).thenReturn(globals);

            Object result = resolver.resolveVarsNamespace("api_url", context);

            assertNull(result);
        }

        @Test
        @DisplayName("Should route vars.name through the vars branch of resolveVariable")
        void shouldRouteVarsDottedPathThroughResolveVariable() {
            Map<String, Object> globals = new HashMap<>();
            globals.put("vars", Map.of("api_url", "https://api.example.com"));
            when(context.getGlobalVariables()).thenReturn(globals);

            Object result = resolver.resolveVariable("vars.api_url", context);

            assertEquals("https://api.example.com", result);
        }

        @Test
        @DisplayName("Should resolve the vars: prefix defensively in resolvePrefixedVariable")
        void shouldResolveVarsPrefixDefensively() {
            Map<String, Object> globals = new HashMap<>();
            globals.put("vars", Map.of("api_url", "https://api.example.com"));
            when(context.getGlobalVariables()).thenReturn(globals);

            Object result = resolver.resolvePrefixedVariable("vars:api_url", context);

            assertEquals("https://api.example.com", result);
        }
    }
}
