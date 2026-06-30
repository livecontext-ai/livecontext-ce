package com.apimarketplace.orchestrator.execution.v2.template;

import com.apimarketplace.orchestrator.domain.WorkflowExecutionContext;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.state.ExecutionState;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.expression.JsonParseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for V2TemplateAdapter.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("V2TemplateAdapter")
class V2TemplateAdapterTest {

    @Mock private TemplateEngine mockTemplateEngine;
    @Mock private WorkflowPlan mockPlan;

    private V2TemplateAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new V2TemplateAdapter(mockTemplateEngine);
    }

    private ExecutionContext createContext(
            Map<String, Object> triggerData,
            Map<String, Object> stepOutputs) {
        ExecutionState state = ExecutionState.create();
        return new ExecutionContext("run-1", "wr-1", "tenant-1", "item-0", 0,
            null, 0, 0, triggerData, stepOutputs, state, mockPlan);
    }

    private ExecutionContext createContextNoPlan(
            Map<String, Object> triggerData,
            Map<String, Object> stepOutputs) {
        ExecutionState state = ExecutionState.create();
        return new ExecutionContext("run-1", "wr-1", "tenant-1", "item-0", 0,
            null, 0, 0, triggerData, stepOutputs, state, null);
    }

    @Nested
    @DisplayName("resolveTemplates")
    class ResolveTemplates {

        @Test
        @DisplayName("should return null for null input")
        void shouldReturnNullForNull() {
            ExecutionContext context = createContextNoPlan(Map.of(), Map.of());
            assertNull(adapter.resolveTemplates(null, context));
        }

        @Test
        @DisplayName("should return empty map for empty input")
        void shouldReturnEmptyForEmpty() {
            ExecutionContext context = createContextNoPlan(Map.of(), Map.of());
            Map<String, Object> result = adapter.resolveTemplates(Map.of(), context);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should resolve string templates")
        void shouldResolveStringTemplates() {
            when(mockPlan.getTriggers()).thenReturn(List.of());
            when(mockPlan.getId()).thenReturn("plan-1");

            ExecutionContext context = createContext(Map.of(), Map.of());

            when(mockTemplateEngine.evaluateTemplate(eq("{{mcp:step1.output.value}}"), any(WorkflowExecutionContext.class)))
                .thenReturn("resolved_value");

            Map<String, Object> input = Map.of("field", "{{mcp:step1.output.value}}");
            Map<String, Object> result = adapter.resolveTemplates(input, context);

            assertEquals("resolved_value", result.get("field"));
        }

        @Test
        @DisplayName("should keep non-string values unchanged")
        void shouldKeepNonStringValuesUnchanged() {
            when(mockPlan.getTriggers()).thenReturn(List.of());
            when(mockPlan.getId()).thenReturn("plan-1");

            ExecutionContext context = createContext(Map.of(), Map.of());

            Map<String, Object> input = Map.of(
                "count", 42,
                "flag", true
            );
            Map<String, Object> result = adapter.resolveTemplates(input, context);

            assertEquals(42, result.get("count"));
            assertEquals(true, result.get("flag"));
        }

        @Test
        @DisplayName("should resolve nested map templates")
        void shouldResolveNestedMapTemplates() {
            when(mockPlan.getTriggers()).thenReturn(List.of());
            when(mockPlan.getId()).thenReturn("plan-1");

            ExecutionContext context = createContext(Map.of(), Map.of());

            when(mockTemplateEngine.evaluateTemplate(eq("inner_val"), any(WorkflowExecutionContext.class)))
                .thenReturn("resolved_inner");

            Map<String, Object> inner = Map.of("nested_field", "inner_val");
            Map<String, Object> input = Map.of("outer", inner);
            Map<String, Object> result = adapter.resolveTemplates(input, context);

            @SuppressWarnings("unchecked")
            Map<String, Object> resultInner = (Map<String, Object>) result.get("outer");
            assertNotNull(resultInner);
            assertEquals("resolved_inner", resultInner.get("nested_field"));
        }

        @Test
        @DisplayName("should resolve template spec maps with 'template' key")
        void shouldResolveTemplateSpec() {
            when(mockPlan.getTriggers()).thenReturn(List.of());
            when(mockPlan.getId()).thenReturn("plan-1");

            ExecutionContext context = createContext(Map.of(), Map.of());

            when(mockTemplateEngine.evaluateTemplate(eq("{{trigger:webhook.output.data}}"), any(WorkflowExecutionContext.class)))
                .thenReturn("webhook_data");

            Map<String, Object> templateSpec = new HashMap<>();
            templateSpec.put("template", "{{trigger:webhook.output.data}}");
            templateSpec.put("required", true);

            Map<String, Object> input = Map.of("body", templateSpec);
            Map<String, Object> result = adapter.resolveTemplates(input, context);

            assertEquals("webhook_data", result.get("body"));
        }
    }

    @Nested
    @DisplayName("evaluateCondition")
    class EvaluateCondition {

        @Test
        @DisplayName("should return true for null condition")
        void shouldReturnTrueForNull() {
            ExecutionContext context = createContextNoPlan(Map.of(), Map.of());
            assertTrue(adapter.evaluateCondition(null, context));
        }

        @Test
        @DisplayName("should return true for empty condition")
        void shouldReturnTrueForEmpty() {
            ExecutionContext context = createContextNoPlan(Map.of(), Map.of());
            assertTrue(adapter.evaluateCondition("", context));
        }

        @Test
        @DisplayName("should delegate to template engine for non-empty condition")
        void shouldDelegateToTemplateEngine() {
            when(mockPlan.getTriggers()).thenReturn(List.of());
            when(mockPlan.getId()).thenReturn("plan-1");

            ExecutionContext context = createContext(Map.of(), Map.of());

            when(mockTemplateEngine.evaluateCondition(eq("x > 5"), any(WorkflowExecutionContext.class)))
                .thenReturn(true);

            assertTrue(adapter.evaluateCondition("x > 5", context));
        }

        @Test
        @DisplayName("should return false when condition evaluates to false")
        void shouldReturnFalseWhenFalse() {
            when(mockPlan.getTriggers()).thenReturn(List.of());
            when(mockPlan.getId()).thenReturn("plan-1");

            ExecutionContext context = createContext(Map.of(), Map.of());

            when(mockTemplateEngine.evaluateCondition(eq("x < 0"), any(WorkflowExecutionContext.class)))
                .thenReturn(false);

            assertFalse(adapter.evaluateCondition("x < 0", context));
        }
    }

    @Nested
    @DisplayName("evaluateConditionWithDetails")
    class EvaluateConditionWithDetails {

        @Test
        @DisplayName("should return default result for null condition")
        void shouldReturnDefaultForNull() {
            ExecutionContext context = createContextNoPlan(Map.of(), Map.of());
            TemplateEngine.ConditionEvaluationResult result = adapter.evaluateConditionWithDetails(null, context);

            assertNotNull(result);
            assertTrue(result.result());
        }

        @Test
        @DisplayName("should return default result for empty condition")
        void shouldReturnDefaultForEmpty() {
            ExecutionContext context = createContextNoPlan(Map.of(), Map.of());
            TemplateEngine.ConditionEvaluationResult result = adapter.evaluateConditionWithDetails("", context);

            assertNotNull(result);
            assertTrue(result.result());
        }

        @Test
        @DisplayName("should delegate to template engine for non-empty condition")
        void shouldDelegateToEngine() {
            when(mockPlan.getTriggers()).thenReturn(List.of());
            when(mockPlan.getId()).thenReturn("plan-1");

            ExecutionContext context = createContext(Map.of(), Map.of());
            TemplateEngine.ConditionEvaluationResult mockResult =
                new TemplateEngine.ConditionEvaluationResult("x > 5", "10 > 5", true, null);

            when(mockTemplateEngine.evaluateConditionWithDetails(eq("x > 5"), any(WorkflowExecutionContext.class)))
                .thenReturn(mockResult);

            TemplateEngine.ConditionEvaluationResult result = adapter.evaluateConditionWithDetails("x > 5", context);

            assertNotNull(result);
            assertTrue(result.result());
        }
    }

    @Nested
    @DisplayName("evaluateTemplate")
    class EvaluateTemplate {

        @Test
        @DisplayName("should return null for null template")
        void shouldReturnNullForNull() {
            ExecutionContext context = createContextNoPlan(Map.of(), Map.of());
            assertNull(adapter.evaluateTemplate(null, context));
        }

        @Test
        @DisplayName("should return empty string for empty template")
        void shouldReturnEmptyForEmpty() {
            ExecutionContext context = createContextNoPlan(Map.of(), Map.of());
            assertEquals("", adapter.evaluateTemplate("", context));
        }

        @Test
        @DisplayName("should delegate to template engine for non-empty template")
        void shouldDelegateToEngine() {
            when(mockPlan.getTriggers()).thenReturn(List.of());
            when(mockPlan.getId()).thenReturn("plan-1");

            ExecutionContext context = createContext(Map.of(), Map.of());

            when(mockTemplateEngine.evaluateTemplate(eq("{{mcp:step.output.val}}"), any(WorkflowExecutionContext.class)))
                .thenReturn(42);

            Object result = adapter.evaluateTemplate("{{mcp:step.output.val}}", context);
            assertEquals(42, result);
        }
    }

    @Nested
    @DisplayName("hasUnresolvedTemplates")
    class HasUnresolvedTemplates {

        @Test
        @DisplayName("should return false for null input")
        void shouldReturnFalseForNull() {
            ExecutionContext context = createContextNoPlan(Map.of(), Map.of());
            assertFalse(adapter.hasUnresolvedTemplates(null, context));
        }

        @Test
        @DisplayName("should return false for empty input")
        void shouldReturnFalseForEmpty() {
            ExecutionContext context = createContextNoPlan(Map.of(), Map.of());
            assertFalse(adapter.hasUnresolvedTemplates(Map.of(), context));
        }

        @Test
        @DisplayName("should return false when all templates resolved")
        void shouldReturnFalseWhenResolved() {
            when(mockPlan.getTriggers()).thenReturn(List.of());
            when(mockPlan.getId()).thenReturn("plan-1");

            ExecutionContext context = createContext(Map.of(), Map.of());

            when(mockTemplateEngine.evaluateTemplate(eq("hello"), any(WorkflowExecutionContext.class)))
                .thenReturn("hello");

            assertFalse(adapter.hasUnresolvedTemplates(Map.of("msg", "hello"), context));
        }

        @Test
        @DisplayName("should return true when templates remain unresolved")
        void shouldReturnTrueWhenUnresolved() {
            when(mockPlan.getTriggers()).thenReturn(List.of());
            when(mockPlan.getId()).thenReturn("plan-1");

            ExecutionContext context = createContext(Map.of(), Map.of());

            when(mockTemplateEngine.evaluateTemplate(eq("{{missing}}"), any(WorkflowExecutionContext.class)))
                .thenReturn("${__UNRESOLVED__:missing}");

            assertTrue(adapter.hasUnresolvedTemplates(Map.of("field", "{{missing}}"), context));
        }
    }

    @Nested
    @DisplayName("context conversion")
    class ContextConversion {

        @Test
        @DisplayName("should transfer step outputs to V1 context")
        void shouldTransferStepOutputs() {
            when(mockPlan.getTriggers()).thenReturn(List.of());
            when(mockPlan.getId()).thenReturn("plan-1");

            Map<String, Object> stepOutputs = new HashMap<>();
            stepOutputs.put("mcp:step1", Map.of("output", Map.of("result", "ok")));

            ExecutionContext context = createContext(Map.of(), stepOutputs);

            // Use a real template engine call that captures the V1 context
            when(mockTemplateEngine.evaluateTemplate(anyString(), any(WorkflowExecutionContext.class)))
                .thenReturn("value");

            adapter.resolveTemplates(Map.of("test", "template"), context);

            // Verify the template engine was called with a V1 context
            verify(mockTemplateEngine).evaluateTemplate(eq("template"), any(WorkflowExecutionContext.class));
        }

        @Test
        @DisplayName("should handle null plan gracefully")
        void shouldHandleNullPlan() {
            ExecutionContext context = createContextNoPlan(Map.of("key", "val"), Map.of());

            when(mockTemplateEngine.evaluateTemplate(anyString(), any(WorkflowExecutionContext.class)))
                .thenReturn("resolved");

            Map<String, Object> result = adapter.resolveTemplates(Map.of("f", "tmpl"), context);
            assertNotNull(result);
        }

        @Test
        @DisplayName("should transfer trigger data as current_item")
        void shouldTransferTriggerDataAsCurrentItem() {
            when(mockPlan.getTriggers()).thenReturn(List.of());
            when(mockPlan.getId()).thenReturn("plan-1");

            Map<String, Object> triggerData = new HashMap<>();
            triggerData.put("user_id", 42);
            triggerData.put("name", "test");

            ExecutionContext context = createContext(triggerData, Map.of());

            when(mockTemplateEngine.evaluateTemplate(anyString(), any(WorkflowExecutionContext.class)))
                .thenReturn("val");

            adapter.resolveTemplates(Map.of("x", "tmpl"), context);

            // Template engine should have been invoked with a context containing trigger data
            verify(mockTemplateEngine).evaluateTemplate(eq("tmpl"), any(WorkflowExecutionContext.class));
        }
    }

    /**
     * Field-name enrichment: when {@code TemplateEngine.evaluateTemplate} throws
     * {@link JsonParseException} (because a {@code {{json('garbage')}}} call inside a step
     * input failed to parse), V2TemplateAdapter must re-throw with the field name prepended
     * so the inspector / agent can identify which param broke. Regression for the audit
     * finding that the enrichment path was previously dead code (silently swallowed by
     * {@code SpelEvaluator.evaluate}'s catch-all).
     */
    @Nested
    @DisplayName("JsonParseException field-name enrichment")
    class JsonParseExceptionFieldNameTests {

        @Test
        @DisplayName("Map field - re-throws with field name in message + preserves preview")
        void enrichesJsonParseExceptionWithMapFieldName() {
            when(mockPlan.getTriggers()).thenReturn(List.of());
            when(mockPlan.getId()).thenReturn("plan-1");
            ExecutionContext context = createContext(Map.of(), Map.of());

            JsonParseException raw = new JsonParseException(
                "json() failed to parse value as JSON: oops",
                "{not json}",
                new RuntimeException("oops"));
            when(mockTemplateEngine.evaluateTemplate(eq("{{json('{not json}')}}"), any(WorkflowExecutionContext.class)))
                .thenThrow(raw);

            JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> adapter.resolveTemplates(Map.of("generationConfig", "{{json('{not json}')}}"), context));

            assertTrue(thrown.getMessage().contains("'generationConfig'"),
                "Enriched message should name the field, was: " + thrown.getMessage());
            assertEquals("{not json}", thrown.getValuePreview(),
                "Value preview must survive the re-throw");
        }

        @Test
        @DisplayName("List element - re-throws with [index] placeholder, not bare []")
        void enrichesJsonParseExceptionWithListIndex() {
            when(mockPlan.getTriggers()).thenReturn(List.of());
            when(mockPlan.getId()).thenReturn("plan-1");
            ExecutionContext context = createContext(Map.of(), Map.of());

            JsonParseException raw = new JsonParseException(
                "json() failed", "bad", new RuntimeException("bad"));

            when(mockTemplateEngine.evaluateTemplate(eq("ok"), any(WorkflowExecutionContext.class)))
                .thenReturn("ok");
            when(mockTemplateEngine.evaluateTemplate(eq("{{json('bad')}}"), any(WorkflowExecutionContext.class)))
                .thenThrow(raw);

            JsonParseException thrown = assertThrows(JsonParseException.class,
                () -> adapter.resolveTemplates(
                    Map.of("messages", List.of("ok", "{{json('bad')}}", "ok")),
                    context));

            assertTrue(thrown.getMessage().contains("'[1]'"),
                "Should name the failing list index (1, not bare []), was: " + thrown.getMessage());
        }
    }
}
