package com.apimarketplace.orchestrator.services;

import com.apimarketplace.orchestrator.domain.WorkflowExecutionContext;
import com.apimarketplace.orchestrator.services.TemplateEngine.ConditionEvaluationResult;
import com.apimarketplace.orchestrator.services.expression.JsonParseException;
import com.apimarketplace.orchestrator.services.template.NamespaceResolver;
import com.apimarketplace.orchestrator.services.template.PathNavigator;
import com.apimarketplace.orchestrator.services.template.SpelEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TemplateEngine service.
 *
 * The TemplateEngine handles {{...}} expression syntax with SpEL support.
 * It resolves templates using WorkflowExecutionContext or Map-based contexts.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TemplateEngine")
class TemplateEngineTest {

    @Mock
    private TypeCastingService typeCastingService;

    @Mock
    private NamespaceResolver namespaceResolver;

    @Mock
    private PathNavigator pathNavigator;

    @Mock
    private SpelEvaluator spelEvaluator;

    @Mock
    private WorkflowExecutionContext executionContext;

    private TemplateEngine templateEngine;

    @BeforeEach
    void setUp() {
        templateEngine = new TemplateEngine(
            typeCastingService,
            namespaceResolver,
            pathNavigator,
            spelEvaluator
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateTemplate() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("evaluateTemplate()")
    class EvaluateTemplateTests {

        @Test
        @DisplayName("Should return null for null template")
        void shouldReturnNullForNullTemplate() {
            assertNull(templateEngine.evaluateTemplate(null, executionContext));
        }

        @Test
        @DisplayName("Should return empty string for empty template")
        void shouldReturnEmptyStringForEmptyTemplate() {
            assertEquals("", templateEngine.evaluateTemplate("", executionContext));
        }

        @Test
        @DisplayName("Should return template as-is if no expressions")
        void shouldReturnTemplateAsIsIfNoExpressions() {
            assertEquals("Hello World", templateEngine.evaluateTemplate("Hello World", executionContext));
        }

        @Test
        @DisplayName("Should evaluate pure expression and return typed result")
        void shouldEvaluatePureExpressionAndReturnTypedResult() {
            // Setup mocks for pure expression "{{trigger:start.value}}"
            when(namespaceResolver.resolveVariable(eq("trigger:start.value"), any()))
                .thenReturn(42);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn(42);

            Object result = templateEngine.evaluateTemplate("{{trigger:start.value}}", executionContext);

            assertEquals(42, result);
        }

        @Test
        @DisplayName("Should evaluate mixed template and return string")
        void shouldEvaluateMixedTemplateAndReturnString() {
            // For mixed templates, the result should be a String
            when(namespaceResolver.resolveVariable(eq("trigger:start.name"), any()))
                .thenReturn("John");
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn("John");

            Object result = templateEngine.evaluateTemplate("Hello {{trigger:start.name}}!", executionContext);

            assertTrue(result instanceof String);
            assertTrue(result.toString().contains("John") || result.toString().contains("Hello"));
        }

        @Test
        @DisplayName("Should handle nested expressions")
        void shouldHandleNestedExpressions() {
            // Pure expression with nested path
            when(namespaceResolver.resolveVariable(eq("mcp:api.output.data.items"), any()))
                .thenReturn(List.of(1, 2, 3));
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn(List.of(1, 2, 3));

            Object result = templateEngine.evaluateTemplate("{{mcp:api.output.data.items}}", executionContext);

            assertEquals(List.of(1, 2, 3), result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateCondition() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("evaluateCondition()")
    class EvaluateConditionTests {

        @Test
        @DisplayName("Should return false for null condition")
        void shouldReturnFalseForNullCondition() {
            assertFalse(templateEngine.evaluateCondition(null, executionContext));
        }

        @Test
        @DisplayName("Should return false for empty condition")
        void shouldReturnFalseForEmptyCondition() {
            assertFalse(templateEngine.evaluateCondition("", executionContext));
        }

        @Test
        @DisplayName("Should evaluate boolean true condition")
        void shouldEvaluateBooleanTrueCondition() {
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn(true);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn(true);
            when(spelEvaluator.toBoolean(true)).thenReturn(true);

            assertTrue(templateEngine.evaluateCondition("{{trigger:start.active}}", executionContext));
        }

        @Test
        @DisplayName("Should evaluate comparison condition")
        void shouldEvaluateComparisonCondition() {
            when(namespaceResolver.resolveVariable(contains("score"), any())).thenReturn(150);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn(true);
            when(spelEvaluator.toBoolean(true)).thenReturn(true);

            assertTrue(templateEngine.evaluateCondition("{{mcp:api.output.score > 100}}", executionContext));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateConditionWithDetails() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("evaluateConditionWithDetails()")
    class EvaluateConditionWithDetailsTests {

        @Test
        @DisplayName("Should return result with original expression")
        void shouldReturnResultWithOriginalExpression() {
            ConditionEvaluationResult result = templateEngine.evaluateConditionWithDetails(null, executionContext);

            assertNotNull(result);
            assertNull(result.originalExpression());
            assertFalse(result.result());
        }

        @Test
        @DisplayName("Should return error message on evaluation failure")
        void shouldReturnErrorMessageOnEvaluationFailure() {
            // Simulate evaluation that returns null
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn(null);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn(null);
            when(spelEvaluator.toBoolean(null)).thenReturn(false);

            ConditionEvaluationResult result = templateEngine.evaluateConditionWithDetails(
                "{{unknown.variable}}", executionContext);

            assertNotNull(result);
            assertFalse(result.result());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateStepInput() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("evaluateStepInput()")
    class EvaluateStepInputTests {

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNullInput() {
            assertNull(templateEngine.evaluateStepInput(null, executionContext));
        }

        @Test
        @DisplayName("Should return empty map for empty input")
        void shouldReturnEmptyMapForEmptyInput() {
            Map<String, Object> result = templateEngine.evaluateStepInput(Map.of(), executionContext);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should preserve non-string values")
        void shouldPreserveNonStringValues() {
            Map<String, Object> input = Map.of(
                "count", 42,
                "enabled", true,
                "items", List.of(1, 2, 3)
            );

            Map<String, Object> result = templateEngine.evaluateStepInput(input, executionContext);

            assertEquals(42, result.get("count"));
            assertEquals(true, result.get("enabled"));
            assertEquals(List.of(1, 2, 3), result.get("items"));
        }

        @Test
        @DisplayName("Should evaluate string templates")
        void shouldEvaluateStringTemplates() {
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn("resolved");
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn("resolved");

            Map<String, Object> input = new HashMap<>();
            input.put("value", "{{trigger:start.data}}");

            Map<String, Object> result = templateEngine.evaluateStepInput(input, executionContext);

            assertNotNull(result.get("value"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // resolveTemplatesSimple() tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("resolveTemplatesSimple()")
    class ResolveTemplatesSimpleTests {

        @Test
        @DisplayName("Should return null for null template")
        void shouldReturnNullForNullTemplate() {
            assertNull(templateEngine.resolveTemplatesSimple(null, Map.of()));
        }

        @Test
        @DisplayName("Should return empty string for empty template")
        void shouldReturnEmptyStringForEmptyTemplate() {
            assertEquals("", templateEngine.resolveTemplatesSimple("", Map.of()));
        }

        @Test
        @DisplayName("Should return template if no expressions")
        void shouldReturnTemplateIfNoExpressions() {
            assertEquals("Hello", templateEngine.resolveTemplatesSimple("Hello", Map.of()));
        }

        @Test
        @DisplayName("Should resolve simple variable")
        void shouldResolveSimpleVariable() {
            when(pathNavigator.getVariableValueFromMap(eq("name"), anyMap()))
                .thenReturn("John");

            String result = templateEngine.resolveTemplatesSimple(
                "Hello {{name}}!",
                Map.of("name", "John")
            );

            assertEquals("Hello John!", result);
        }

        @Test
        @DisplayName("Should resolve nested path")
        void shouldResolveNestedPath() {
            when(pathNavigator.getVariableValueFromMap(eq("user.name"), anyMap()))
                .thenReturn("John");

            String result = templateEngine.resolveTemplatesSimple(
                "Hello {{user.name}}!",
                Map.of("user", Map.of("name", "John"))
            );

            assertEquals("Hello John!", result);
        }

        @Test
        @DisplayName("Should mark unresolved variables")
        void shouldMarkUnresolvedVariables() {
            when(pathNavigator.getVariableValueFromMap(eq("unknown"), anyMap()))
                .thenReturn(null);

            String result = templateEngine.resolveTemplatesSimple(
                "Value: {{unknown}}",
                Map.of()
            );

            assertTrue(result.contains("__UNRESOLVED__"));
        }

        @Test
        @DisplayName("Should convert Map to JSON")
        void shouldConvertMapToJson() {
            Map<String, Object> data = Map.of("id", 1, "name", "test");
            when(pathNavigator.getVariableValueFromMap(eq("data"), anyMap()))
                .thenReturn(data);

            String result = templateEngine.resolveTemplatesSimple(
                "Data: {{data}}",
                Map.of("data", data)
            );

            assertTrue(result.contains("id"));
            assertTrue(result.contains("name"));
        }

        @Test
        @DisplayName("Should convert List to JSON")
        void shouldConvertListToJson() {
            List<Integer> items = List.of(1, 2, 3);
            when(pathNavigator.getVariableValueFromMap(eq("items"), anyMap()))
                .thenReturn(items);

            String result = templateEngine.resolveTemplatesSimple(
                "Items: {{items}}",
                Map.of("items", items)
            );

            assertTrue(result.contains("[1,2,3]") || result.contains("[1, 2, 3]"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Map-based methods tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("evaluateConditionWithMap()")
    class EvaluateConditionWithMapTests {

        @Test
        @DisplayName("Should return false for null condition")
        void shouldReturnFalseForNullCondition() {
            assertFalse(templateEngine.evaluateConditionWithMap(null, Map.of()));
        }

        @Test
        @DisplayName("Should return false for empty condition")
        void shouldReturnFalseForEmptyCondition() {
            assertFalse(templateEngine.evaluateConditionWithMap("", Map.of()));
        }

        @Test
        @DisplayName("Should evaluate pure expression")
        void shouldEvaluatePureExpression() {
            when(spelEvaluator.evaluateWithMap(anyString(), anyMap(), eq(pathNavigator)))
                .thenReturn(true);
            when(spelEvaluator.toBoolean(true)).thenReturn(true);

            boolean result = templateEngine.evaluateConditionWithMap(
                "{{value > 10}}",
                Map.of("value", 15)
            );

            assertTrue(result);
        }

        @Test
        @DisplayName("Should evaluate mixed expression")
        void shouldEvaluateMixedExpression() {
            when(spelEvaluator.evaluateWithMap(anyString(), anyMap(), eq(pathNavigator)))
                .thenReturn(15);
            when(spelEvaluator.toBoolean(any())).thenReturn(true);

            boolean result = templateEngine.evaluateConditionWithMap(
                "{{value}} > 10",
                Map.of("value", 15)
            );

            assertTrue(result);
        }

        @Test
        @DisplayName("Should handle evaluation error gracefully")
        void shouldHandleEvaluationErrorGracefully() {
            when(spelEvaluator.evaluateWithMap(anyString(), anyMap(), eq(pathNavigator)))
                .thenThrow(new RuntimeException("SpEL error"));

            boolean result = templateEngine.evaluateConditionWithMap(
                "{{invalid expression}}",
                Map.of()
            );

            assertFalse(result);
        }
    }

    @Nested
    @DisplayName("evaluateTemplateWithMap()")
    class EvaluateTemplateWithMapTests {

        @Test
        @DisplayName("Should return null for null template")
        void shouldReturnNullForNullTemplate() {
            assertNull(templateEngine.evaluateTemplateWithMap(null, Map.of()));
        }

        @Test
        @DisplayName("Should return empty string for empty template")
        void shouldReturnEmptyStringForEmptyTemplate() {
            assertEquals("", templateEngine.evaluateTemplateWithMap("", Map.of()));
        }

        @Test
        @DisplayName("Should return typed result for pure expression")
        void shouldReturnTypedResultForPureExpression() {
            when(spelEvaluator.evaluateWithMap(anyString(), anyMap(), eq(pathNavigator)))
                .thenReturn(42);

            Object result = templateEngine.evaluateTemplateWithMap(
                "{{value}}",
                Map.of("value", 42)
            );

            assertEquals(42, result);
        }

        @Test
        @DisplayName("Should return string for mixed template")
        void shouldReturnStringForMixedTemplate() {
            when(spelEvaluator.evaluateWithMap(anyString(), anyMap(), eq(pathNavigator)))
                .thenReturn("John");

            Object result = templateEngine.evaluateTemplateWithMap(
                "Hello {{name}}!",
                Map.of("name", "John")
            );

            assertTrue(result instanceof String);
            assertEquals("Hello John!", result);
        }
    }

    @Nested
    @DisplayName("resolveWithMap()")
    class ResolveWithMapTests {

        @Test
        @DisplayName("Should return null for null template")
        void shouldReturnNullForNullTemplate() {
            assertNull(templateEngine.resolveWithMap(null, Map.of()));
        }

        @Test
        @DisplayName("Should return empty string for empty template")
        void shouldReturnEmptyStringForEmptyTemplate() {
            assertEquals("", templateEngine.resolveWithMap("", Map.of()));
        }

        @Test
        @DisplayName("Should return template if no expressions")
        void shouldReturnTemplateIfNoExpressions() {
            assertEquals("Hello", templateEngine.resolveWithMap("Hello", Map.of()));
        }

        @Test
        @DisplayName("Should resolve all expressions")
        void shouldResolveAllExpressions() {
            when(spelEvaluator.evaluateWithMap(eq("name"), anyMap(), eq(pathNavigator)))
                .thenReturn("John");
            when(spelEvaluator.evaluateWithMap(eq("age"), anyMap(), eq(pathNavigator)))
                .thenReturn(30);

            String result = templateEngine.resolveWithMap(
                "{{name}} is {{age}} years old",
                Map.of("name", "John", "age", 30)
            );

            assertEquals("John is 30 years old", result);
        }

        @Test
        @DisplayName("Should handle null evaluation results")
        void shouldHandleNullEvaluationResults() {
            when(spelEvaluator.evaluateWithMap(anyString(), anyMap(), eq(pathNavigator)))
                .thenReturn(null);

            String result = templateEngine.resolveWithMap(
                "Value: {{unknown}}",
                Map.of()
            );

            assertEquals("Value: ", result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Edge cases and error handling
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge cases")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle whitespace in expressions")
        void shouldHandleWhitespaceInExpressions() {
            when(namespaceResolver.resolveVariable(eq("value"), any())).thenReturn(42);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn(42);

            Object result = templateEngine.evaluateTemplate("{{  value  }}", executionContext);

            assertEquals(42, result);
        }

        @Test
        @DisplayName("Should handle multiple expressions in template")
        void shouldHandleMultipleExpressionsInTemplate() {
            when(namespaceResolver.resolveVariable(eq("first"), any())).thenReturn("Hello");
            when(namespaceResolver.resolveVariable(eq("second"), any())).thenReturn("World");
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(contains("first"), any(StandardEvaluationContext.class)))
                .thenReturn("Hello");
            when(spelEvaluator.evaluate(contains("second"), any(StandardEvaluationContext.class)))
                .thenReturn("World");

            Object result = templateEngine.evaluateTemplate(
                "{{first}} {{second}}!",
                executionContext
            );

            assertTrue(result.toString().contains("Hello") || result.toString().contains("World"));
        }

        @Test
        @DisplayName("Should handle special characters in template text")
        void shouldHandleSpecialCharactersInTemplateText() {
            // Template with special regex characters outside of expressions
            String template = "Price: $100.00 (50% off)";
            assertEquals(template, templateEngine.evaluateTemplate(template, executionContext));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // isPureExpression() edge cases (via evaluateTemplate behavior)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Pure expression detection")
    class PureExpressionTests {

        @Test
        @DisplayName("Should detect pure expression: single {{...}}")
        void shouldDetectPureExpression() {
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn(List.of(1, 2, 3));
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn(List.of(1, 2, 3));

            // Pure expression should return typed result (List), not String
            Object result = templateEngine.evaluateTemplate("{{items}}", executionContext);

            assertTrue(result instanceof List);
        }

        @Test
        @DisplayName("Should NOT detect pure expression: multiple {{...}} blocks")
        void shouldNotDetectPureExpressionWithMultipleBlocks() {
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn("value");
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn("value");

            // Multiple blocks should return String
            Object result = templateEngine.evaluateTemplate("{{a}}{{b}}", executionContext);

            assertTrue(result instanceof String);
        }

        @Test
        @DisplayName("Should NOT detect pure expression: text before {{...}}")
        void shouldNotDetectPureExpressionWithTextBefore() {
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn("world");
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn("world");

            // Text before expression should return String
            Object result = templateEngine.evaluateTemplate("Hello {{name}}", executionContext);

            assertTrue(result instanceof String);
        }

        @Test
        @DisplayName("Should NOT detect pure expression: text after {{...}}")
        void shouldNotDetectPureExpressionWithTextAfter() {
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn("Hello");
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn("Hello");

            // Text after expression should return String
            Object result = templateEngine.evaluateTemplate("{{greeting}}!", executionContext);

            assertTrue(result instanceof String);
        }

        @Test
        @DisplayName("Should handle pure expression with whitespace around braces")
        void shouldHandlePureExpressionWithWhitespace() {
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn(42);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn(42);

            // Whitespace inside braces is trimmed, still pure
            Object result = templateEngine.evaluateTemplate("{{   value   }}", executionContext);

            assertEquals(42, result);
        }

        @Test
        @DisplayName("Should handle pure expression returning boolean")
        void shouldHandlePureExpressionReturningBoolean() {
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn(true);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn(true);

            Object result = templateEngine.evaluateTemplate("{{isActive}}", executionContext);

            assertEquals(true, result);
        }

        @Test
        @DisplayName("Should handle pure expression returning Map")
        void shouldHandlePureExpressionReturningMap() {
            Map<String, Object> mapResult = Map.of("id", 1, "name", "test");
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn(mapResult);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn(mapResult);

            Object result = templateEngine.evaluateTemplate("{{data}}", executionContext);

            assertTrue(result instanceof Map);
            assertEquals(mapResult, result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Complex condition evaluation tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Complex condition evaluation")
    class ComplexConditionTests {

        @Test
        @DisplayName("Should evaluate mixed condition: {{var}} > 100")
        void shouldEvaluateMixedCondition() {
            when(spelEvaluator.evaluateWithMap(anyString(), anyMap(), eq(pathNavigator)))
                .thenReturn(150)
                .thenReturn(true);
            when(spelEvaluator.toBoolean(any())).thenReturn(true);

            boolean result = templateEngine.evaluateConditionWithMap(
                "{{score}} > 100",
                Map.of("score", 150)
            );

            assertTrue(result);
        }

        @Test
        @DisplayName("Should evaluate condition with multiple {{}} blocks")
        void shouldEvaluateConditionWithMultipleBlocks() {
            when(spelEvaluator.evaluateWithMap(anyString(), anyMap(), eq(pathNavigator)))
                .thenReturn(50)
                .thenReturn(100)
                .thenReturn(true);
            when(spelEvaluator.toBoolean(any())).thenReturn(true);

            boolean result = templateEngine.evaluateConditionWithMap(
                "{{min}} < {{max}}",
                Map.of("min", 50, "max", 100)
            );

            assertTrue(result);
        }

        @Test
        @DisplayName("Should evaluate raw condition without {{}} braces")
        void shouldEvaluateRawConditionWithoutBraces() {
            when(spelEvaluator.evaluateWithMap(eq("value > 0"), anyMap(), eq(pathNavigator)))
                .thenReturn(true);
            when(spelEvaluator.toBoolean(true)).thenReturn(true);

            boolean result = templateEngine.evaluateConditionWithMap(
                "value > 0",
                Map.of("value", 42)
            );

            assertTrue(result);
        }

        @Test
        @DisplayName("Should handle condition with logical operators")
        void shouldHandleConditionWithLogicalOperators() {
            when(spelEvaluator.evaluateWithMap(anyString(), anyMap(), eq(pathNavigator)))
                .thenReturn(true);
            when(spelEvaluator.toBoolean(true)).thenReturn(true);

            boolean result = templateEngine.evaluateConditionWithMap(
                "{{a > 0 and b < 100}}",
                Map.of("a", 10, "b", 50)
            );

            assertTrue(result);
        }

        @Test
        @DisplayName("Should handle condition with ternary operator")
        void shouldHandleConditionWithTernaryOperator() {
            when(spelEvaluator.evaluateWithMap(anyString(), anyMap(), eq(pathNavigator)))
                .thenReturn(true);
            when(spelEvaluator.toBoolean(true)).thenReturn(true);

            boolean result = templateEngine.evaluateConditionWithMap(
                "{{value > 0 ? true : false}}",
                Map.of("value", 5)
            );

            assertTrue(result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Namespace prefix resolution tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Namespace prefix resolution")
    class NamespacePrefixTests {

        @Test
        @DisplayName("Should resolve trigger: prefix")
        void shouldResolveTriggerPrefix() {
            Map<String, Object> triggerData = Map.of("payload", Map.of("id", 123));
            when(namespaceResolver.resolveVariable(eq("trigger:webhook.payload.id"), any()))
                .thenReturn(123);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn(123);

            Object result = templateEngine.evaluateTemplate("{{trigger:webhook.payload.id}}", executionContext);

            assertEquals(123, result);
        }

        @Test
        @DisplayName("Should resolve mcp: prefix")
        void shouldResolveMcpPrefix() {
            when(namespaceResolver.resolveVariable(eq("mcp:api_call.output.data"), any()))
                .thenReturn("result");
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn("result");

            Object result = templateEngine.evaluateTemplate("{{mcp:api_call.output.data}}", executionContext);

            assertEquals("result", result);
        }

        @Test
        @DisplayName("Should resolve agent: prefix")
        void shouldResolveAgentPrefix() {
            when(namespaceResolver.resolveVariable(eq("agent:analyzer.response"), any()))
                .thenReturn("AI analysis result");
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn("AI analysis result");

            Object result = templateEngine.evaluateTemplate("{{agent:analyzer.response}}", executionContext);

            assertEquals("AI analysis result", result);
        }

        @Test
        @DisplayName("Should resolve core: prefix")
        void shouldResolveCorePrefix() {
            when(namespaceResolver.resolveVariable(eq("core:loop.iteration"), any()))
                .thenReturn(5);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn(5);

            Object result = templateEngine.evaluateTemplate("{{core:loop.iteration}}", executionContext);

            assertEquals(5, result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Custom function usage in templates tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Custom function usage in templates")
    class CustomFunctionTests {

        @Test
        @DisplayName("Should evaluate template with uppercase function")
        void shouldEvaluateTemplateWithUppercaseFunction() {
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn("hello");
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn("HELLO");

            Object result = templateEngine.evaluateTemplate("{{uppercase(name)}}", executionContext);

            assertEquals("HELLO", result);
        }

        @Test
        @DisplayName("Should evaluate template with size function")
        void shouldEvaluateTemplateWithSizeFunction() {
            List<Integer> items = List.of(1, 2, 3, 4, 5);
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn(items);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn(5);

            Object result = templateEngine.evaluateTemplate("{{size(items)}}", executionContext);

            assertEquals(5, result);
        }

        @Test
        @DisplayName("Should evaluate template with nested functions")
        void shouldEvaluateTemplateWithNestedFunctions() {
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn("  hello  ");
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn("HELLO");

            Object result = templateEngine.evaluateTemplate("{{uppercase(trim(name))}}", executionContext);

            assertEquals("HELLO", result);
        }

        @Test
        @DisplayName("Should evaluate template with int conversion")
        void shouldEvaluateTemplateWithIntConversion() {
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn("42");
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn(42);

            Object result = templateEngine.evaluateTemplate("{{int(value)}}", executionContext);

            assertEquals(42, result);
        }

        @Test
        @DisplayName("Should evaluate template with default function")
        void shouldEvaluateTemplateWithDefaultFunction() {
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn(null);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn("fallback");

            Object result = templateEngine.evaluateTemplate("{{default(missing, 'fallback')}}", executionContext);

            assertEquals("fallback", result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Unicode and special character handling tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Unicode and special character handling")
    class UnicodeAndSpecialCharacterTests {

        @Test
        @DisplayName("Should handle Unicode characters in template text")
        void shouldHandleUnicodeCharactersInTemplateText() {
            String template = "Café résumé 日本語 emoji: 🎉";
            assertEquals(template, templateEngine.evaluateTemplate(template, executionContext));
        }

        @Test
        @DisplayName("Should handle Unicode in variable value")
        void shouldHandleUnicodeInVariableValue() {
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn("Héllo Wörld 你好");
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn("Héllo Wörld 你好");

            Object result = templateEngine.evaluateTemplate("{{greeting}}", executionContext);

            assertEquals("Héllo Wörld 你好", result);
        }

        @Test
        @DisplayName("Should handle newlines in template text")
        void shouldHandleNewlinesInTemplateText() {
            String template = "Line 1\nLine 2\nLine 3";
            assertEquals(template, templateEngine.evaluateTemplate(template, executionContext));
        }

        @Test
        @DisplayName("Should handle tabs in template text")
        void shouldHandleTabsInTemplateText() {
            String template = "Column1\tColumn2\tColumn3";
            assertEquals(template, templateEngine.evaluateTemplate(template, executionContext));
        }

        @Test
        @DisplayName("Should handle backslash in template text")
        void shouldHandleBackslashInTemplateText() {
            String template = "Path: C:\\Users\\test";
            assertEquals(template, templateEngine.evaluateTemplate(template, executionContext));
        }

        @Test
        @DisplayName("Should handle quotes in template text")
        void shouldHandleQuotesInTemplateText() {
            String template = "He said \"Hello\" and 'Goodbye'";
            assertEquals(template, templateEngine.evaluateTemplate(template, executionContext));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Array and index access tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Array and index access")
    class ArrayIndexAccessTests {

        @Test
        @DisplayName("Should evaluate array index access")
        void shouldEvaluateArrayIndexAccess() {
            List<String> items = List.of("first", "second", "third");
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn(items);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn("second");

            Object result = templateEngine.evaluateTemplate("{{items[1]}}", executionContext);

            assertEquals("second", result);
        }

        @Test
        @DisplayName("Should evaluate nested array access")
        void shouldEvaluateNestedArrayAccess() {
            Map<String, Object> data = Map.of("items", List.of(
                Map.of("name", "Item1"),
                Map.of("name", "Item2")
            ));
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn("Item1");
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn("Item1");

            Object result = templateEngine.evaluateTemplate("{{data.items[0].name}}", executionContext);

            assertEquals("Item1", result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Error handling and recovery tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Error handling and recovery")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should handle SpEL evaluation exception gracefully")
        void shouldHandleSpelEvaluationExceptionGracefully() {
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn("value");
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenThrow(new RuntimeException("SpEL error"));

            Object result = templateEngine.evaluateTemplate("{{invalid.expression}}", executionContext);

            // Should return null or handle error gracefully
            assertNull(result);
        }

        @Test
        @DisplayName("Should handle null variable resolution")
        void shouldHandleNullVariableResolution() {
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn(null);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn(null);

            Object result = templateEngine.evaluateTemplate("{{unknown}}", executionContext);

            assertNull(result);
        }

        @Test
        @DisplayName("Should handle condition with exception returning false")
        void shouldHandleConditionWithExceptionReturningFalse() {
            when(spelEvaluator.evaluateWithMap(anyString(), anyMap(), eq(pathNavigator)))
                .thenThrow(new RuntimeException("Evaluation error"));

            boolean result = templateEngine.evaluateConditionWithMap("{{invalid}}", Map.of());

            assertFalse(result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Integration tests - Full workflow scenarios
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Integration tests - Full workflow scenarios")
    class IntegrationTests {

        @Test
        @DisplayName("Should evaluate complex template with multiple variables and functions")
        void shouldEvaluateComplexTemplateWithMultipleVariablesAndFunctions() {
            when(namespaceResolver.resolveVariable(eq("name"), any())).thenReturn("john");
            when(namespaceResolver.resolveVariable(eq("count"), any())).thenReturn(5);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn("JOHN")
                .thenReturn(5);

            // This tests a real-world scenario with mixed text and expressions
            Object result = templateEngine.evaluateTemplate(
                "User {{uppercase(name)}} has {{count}} items",
                executionContext
            );

            assertTrue(result instanceof String);
        }

        @Test
        @DisplayName("Should evaluate condition from workflow decision node")
        void shouldEvaluateConditionFromWorkflowDecisionNode() {
            when(spelEvaluator.evaluateWithMap(anyString(), anyMap(), eq(pathNavigator)))
                .thenReturn(true);
            when(spelEvaluator.toBoolean(true)).thenReturn(true);

            // Real workflow condition: check if API response was successful
            boolean result = templateEngine.evaluateConditionWithMap(
                "{{mcp:api_call.output.status == 200 and mcp:api_call.output.data != null}}",
                Map.of("mcp:api_call", Map.of("output", Map.of("status", 200, "data", "result")))
            );

            assertTrue(result);
        }

        @Test
        @DisplayName("Should evaluate template for API input construction")
        void shouldEvaluateTemplateForApiInputConstruction() {
            Map<String, Object> input = new HashMap<>();
            input.put("userId", "{{trigger:webhook.payload.userId}}");
            input.put("action", "process");
            input.put("timestamp", "{{now()}}");

            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn(123L);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn(123L);

            Map<String, Object> result = templateEngine.evaluateStepInput(input, executionContext);

            assertNotNull(result);
            assertEquals("process", result.get("action")); // Non-template values preserved
        }

        @Test
        @DisplayName("Should evaluate foreach item access")
        void shouldEvaluateForeachItemAccess() {
            Map<String, Object> currentItem = Map.of("id", 42, "name", "Test Item");
            when(namespaceResolver.resolveVariable(eq("current_item.name"), any()))
                .thenReturn("Test Item");
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn("Test Item");

            Object result = templateEngine.evaluateTemplate("{{current_item.name}}", executionContext);

            assertEquals("Test Item", result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SpEL operator tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SpEL operators in templates")
    class SpelOperatorTests {

        @Test
        @DisplayName("Should evaluate arithmetic operators")
        void shouldEvaluateArithmeticOperators() {
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn(10);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn(15);

            Object result = templateEngine.evaluateTemplate("{{a + 5}}", executionContext);

            assertEquals(15, result);
        }

        @Test
        @DisplayName("Should evaluate comparison operators")
        void shouldEvaluateComparisonOperators() {
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn(10);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn(true);

            Object result = templateEngine.evaluateTemplate("{{value > 5}}", executionContext);

            assertEquals(true, result);
        }

        @Test
        @DisplayName("Should evaluate ternary operator")
        void shouldEvaluateTernaryOperator() {
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn(true);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn("yes");

            Object result = templateEngine.evaluateTemplate("{{active ? 'yes' : 'no'}}", executionContext);

            assertEquals("yes", result);
        }

        @Test
        @DisplayName("Should evaluate Elvis operator")
        void shouldEvaluateElvisOperator() {
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn(null);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn("default");

            Object result = templateEngine.evaluateTemplate("{{value ?: 'default'}}", executionContext);

            assertEquals("default", result);
        }

        @Test
        @DisplayName("Should evaluate safe navigation operator")
        void shouldEvaluateSafeNavigationOperator() {
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn(null);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn(null);

            Object result = templateEngine.evaluateTemplate("{{user?.name}}", executionContext);

            assertNull(result);
        }
    }

    @Nested
    @DisplayName("Array Access in Variable Paths")
    class ArrayAccessTests {

        @Test
        @DisplayName("Should resolve array access in pure expression: data[0].embedding")
        void shouldResolveArrayAccessInPureExpression() {
            List<Double> embeddingVector = List.of(-0.009, 0.021, 0.03);

            when(namespaceResolver.resolveVariable(eq("mcp:embed_chunk.output.data[0].embedding"), any()))
                .thenReturn(embeddingVector);

            // The safe var name: mcp_embed_chunk_output_data_0__embedding
            String safeVar = "mcp_embed_chunk_output_data_0__embedding";
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(eq("#" + safeVar), any(StandardEvaluationContext.class)))
                .thenReturn(embeddingVector);

            Object result = templateEngine.evaluateTemplate("{{mcp:embed_chunk.output.data[0].embedding}}", executionContext);

            assertEquals(embeddingVector, result);
            verify(namespaceResolver).resolveVariable(eq("mcp:embed_chunk.output.data[0].embedding"), any());
        }

        @Test
        @DisplayName("Should NOT split array access into separate variables")
        void shouldNotSplitArrayAccessIntoSeparateVariables() {
            // If the regex is broken, it would call resolveVariable twice:
            // once with "mcp:embed_chunk.output.data" and once with "embedding"
            when(namespaceResolver.resolveVariable(anyString(), any())).thenReturn(null);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(anyString(), any(StandardEvaluationContext.class)))
                .thenReturn(null);

            templateEngine.evaluateTemplate("{{mcp:embed_chunk.output.data[0].embedding}}", executionContext);

            // Should be called exactly ONCE with the full path, not twice with split paths
            verify(namespaceResolver, times(1)).resolveVariable(anyString(), any());
            verify(namespaceResolver).resolveVariable(eq("mcp:embed_chunk.output.data[0].embedding"), any());
            // Must never be called with just "embedding" (the broken behavior)
            verify(namespaceResolver, never()).resolveVariable(eq("embedding"), any());
        }

        @Test
        @DisplayName("Should handle nested array access: items[2].name")
        void shouldHandleNestedArrayAccess() {
            when(namespaceResolver.resolveVariable(eq("core:split.output.items[2].name"), any()))
                .thenReturn("third-item");

            String safeVar = "core_split_output_items_2__name";
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(eq("#" + safeVar), any(StandardEvaluationContext.class)))
                .thenReturn("third-item");

            Object result = templateEngine.evaluateTemplate("{{core:split.output.items[2].name}}", executionContext);

            assertEquals("third-item", result);
            verify(namespaceResolver).resolveVariable(eq("core:split.output.items[2].name"), any());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Map/List substitution in mixed templates (regression for the Gemini
    // generationConfig bug - String.valueOf(Map) used to produce {a=1}, now
    // produces {"a":1} via JsonOutputUtil.encode).
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Map/List substitution in mixed templates")
    class MapListSubstitutionTests {

        @Test
        @DisplayName("Mixed template with Map value emits valid JSON, not Java toString")
        void mixedTemplateWithMapEmitsJson() {
            // Pre-fix: '{"x": {{config}}}' with config=Map.of("a",1) → '{"x": {a=1}}' (broken)
            // Post-fix: '{"x": {{config}}}' → '{"x": {"a":1}}' (parseable JSON)
            Map<String, Object> mapValue = new LinkedHashMap<>();
            mapValue.put("a", 1);
            mapValue.put("b", "hello");

            when(namespaceResolver.resolveVariable(eq("config"), any())).thenReturn(mapValue);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(eq("#config"), any(StandardEvaluationContext.class)))
                .thenReturn(mapValue);

            Object result = templateEngine.evaluateTemplate("{\"x\": {{config}}}", executionContext);

            assertEquals("{\"x\": {\"a\":1,\"b\":\"hello\"}}", result,
                "Map should be JSON-encoded, not rendered as Java toString {a=1}");
        }

        @Test
        @DisplayName("Mixed template with List value emits JSON array, not Java toString")
        void mixedTemplateWithListEmitsJson() {
            List<Object> listValue = List.of(1, 2, 3);

            when(namespaceResolver.resolveVariable(eq("items"), any())).thenReturn(listValue);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(eq("#items"), any(StandardEvaluationContext.class)))
                .thenReturn(listValue);

            Object result = templateEngine.evaluateTemplate("prefix {{items}} suffix", executionContext);

            assertEquals("prefix [1,2,3] suffix", result);
        }

        @Test
        @DisplayName("Mixed template with String value still uses String.valueOf (no JSON quoting)")
        void mixedTemplateWithStringPreservesValueOf() {
            // Strings must NOT be JSON-quoted in mixed templates - this is a regression
            // guard: '{{name}}' with name='Bob' must produce 'Bob', not '"Bob"'.
            when(namespaceResolver.resolveVariable(eq("name"), any())).thenReturn("Bob");
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(eq("#name"), any(StandardEvaluationContext.class)))
                .thenReturn("Bob");

            Object result = templateEngine.evaluateTemplate("Hello {{name}}", executionContext);

            assertEquals("Hello Bob", result);
        }

        @Test
        @DisplayName("Pure expression returning Map preserves typed Map (no String coercion)")
        void pureExpressionReturningMapStaysTyped() {
            // Pure '{{config}}' goes through isPureExpression short-circuit - already typed.
            // Regression: ensure the substitution-path fix didn't regress the pure path.
            Map<String, Object> mapValue = Map.of("k", "v");

            when(namespaceResolver.resolveVariable(eq("config"), any())).thenReturn(mapValue);
            when(spelEvaluator.createEvaluationContext(anyMap()))
                .thenReturn(new StandardEvaluationContext());
            when(spelEvaluator.evaluate(eq("#config"), any(StandardEvaluationContext.class)))
                .thenReturn(mapValue);

            Object result = templateEngine.evaluateTemplate("{{config}}", executionContext);

            assertSame(mapValue, result, "Pure expression should return the typed Map");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // resolveExpressionsWithMap (Loop / Decision contexts) - symmetric with the
    // resolveExpressions fix above. Both substitution paths must JSON-encode
    // Map/List.
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("resolveWithMap Map/List substitution")
    class ResolveWithMapJsonEncodingTests {

        @Test
        @DisplayName("Mixed template with Map value emits JSON in Map context")
        void mapContextEncodesMapAsJson() {
            Map<String, Object> mapValue = Map.of("a", 1);
            Map<String, Object> ctx = Map.of("config", mapValue);

            when(spelEvaluator.evaluateWithMap(eq("config"), eq(ctx), any())).thenReturn(mapValue);

            String result = templateEngine.resolveWithMap("{\"x\": {{config}}}", ctx);

            assertEquals("{\"x\": {\"a\":1}}", result,
                "Map context substitution should also JSON-encode Maps");
        }

        @Test
        @DisplayName("Mixed template with List value emits JSON array in Map context")
        void mapContextEncodesListAsJson() {
            List<Integer> listValue = List.of(1, 2, 3);
            Map<String, Object> ctx = Map.of("items", listValue);

            when(spelEvaluator.evaluateWithMap(eq("items"), eq(ctx), any())).thenReturn(listValue);

            String result = templateEngine.resolveWithMap("count={{items}}", ctx);

            assertEquals("count=[1,2,3]", result);
        }

        @Test
        @DisplayName("Mixed template with scalar value still uses String.valueOf in Map context")
        void mapContextScalarUsesValueOf() {
            Map<String, Object> ctx = Map.of("n", 42);
            when(spelEvaluator.evaluateWithMap(eq("n"), eq(ctx), any())).thenReturn(42);

            String result = templateEngine.resolveWithMap("count={{n}}", ctx);

            assertEquals("count=42", result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SpEL integration: real SpelEvaluator + json() function end-to-end.
    // These exercise the actual SpEL parser, function registration, and the
    // JsonParseException rethrow path that was previously dead code.
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("json() SpEL function - real evaluator integration")
    class JsonFunctionIntegrationTests {

        private TemplateEngine realTemplateEngine;
        private SpelEvaluator realSpelEvaluator;
        private NamespaceResolver mockNamespaceResolver;
        private PathNavigator mockPathNavigator;
        private TypeCastingService mockTypeCasting;

        @BeforeEach
        void setUpReal() {
            // Use a real SpelEvaluator (with json/fromjson/tojson registered) so the test
            // exercises the actual SpEL parser and custom-function dispatch - NOT the mock.
            realSpelEvaluator = new SpelEvaluator();
            realSpelEvaluator.init(); // wires custom functions
            mockNamespaceResolver = mock(NamespaceResolver.class);
            mockPathNavigator = mock(PathNavigator.class);
            mockTypeCasting = mock(TypeCastingService.class);
            realTemplateEngine = new TemplateEngine(
                mockTypeCasting, mockNamespaceResolver, mockPathNavigator, realSpelEvaluator);
        }

        @Test
        @DisplayName("Pure expression {{json('{...}')}} returns typed Map (not String)")
        void pureJsonExpressionReturnsTypedMap() {
            Object result = realTemplateEngine.evaluateTemplate(
                "{{json('{\"responseModalities\":[\"IMAGE\"]}')}}", executionContext);

            assertInstanceOf(Map.class, result, "json() must return a typed Map for downstream tools");
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) result;
            assertEquals(List.of("IMAGE"), m.get("responseModalities"));
        }

        @Test
        @DisplayName("Pure expression {{json('[1,2,3]')}} returns typed List")
        void pureJsonExpressionReturnsTypedList() {
            Object result = realTemplateEngine.evaluateTemplate(
                "{{json('[1,2,3]')}}", executionContext);

            assertInstanceOf(List.class, result);
            assertEquals(List.of(1, 2, 3), result);
        }

        @Test
        @DisplayName("json() with variable input - typed Map preserved through SpEL")
        void jsonWithVariableReturnsTypedMap() {
            // Simulate a stored output that's a JSON string (e.g. from an HTTP node body).
            when(mockNamespaceResolver.resolveVariable(eq("mcp:fetch.output.body"), any()))
                .thenReturn("{\"a\":1,\"b\":2}");

            Object result = realTemplateEngine.evaluateTemplate(
                "{{json(mcp:fetch.output.body)}}", executionContext);

            assertInstanceOf(Map.class, result);
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) result;
            assertEquals(1, m.get("a"));
            assertEquals(2, m.get("b"));
        }

        @Test
        @DisplayName("json() is idempotent on already-typed Map - round-trip safe")
        void jsonIsIdempotentOnTypedMap() {
            Map<String, Object> alreadyTyped = Map.of("k", "v");
            when(mockNamespaceResolver.resolveVariable(eq("mcp:step.output.config"), any()))
                .thenReturn(alreadyTyped);

            Object result = realTemplateEngine.evaluateTemplate(
                "{{json(mcp:step.output.config)}}", executionContext);

            assertSame(alreadyTyped, result, "Already-typed Map should pass through unchanged");
        }

        @Test
        @DisplayName("json() on invalid JSON throws JsonParseException (NOT silently swallowed)")
        void jsonInvalidThrowsRatherThanSwallowing() {
            // Regression for the audit finding: SpelEvaluator's catch-all used to swallow
            // JsonParseException, leaving callers with a silent null. After the rethrow fix,
            // the typed exception propagates so V2TemplateAdapter can enrich with field name.
            JsonParseException ex = assertThrows(JsonParseException.class,
                () -> realTemplateEngine.evaluateTemplate("{{json('{not json')}}", executionContext));

            assertNotNull(ex.getValuePreview());
            assertTrue(ex.getValuePreview().contains("{not json"),
                "Preview should expose the offending value, was: " + ex.getValuePreview());
        }

        @Test
        @DisplayName("tojson() serializes a Map produced by SpEL to a compact JSON string")
        void tojsonSerializesMap() {
            when(mockNamespaceResolver.resolveVariable(eq("mcp:list.output.items"), any()))
                .thenReturn(Map.of("a", 1));

            Object result = realTemplateEngine.evaluateTemplate(
                "{{tojson(mcp:list.output.items)}}", executionContext);

            assertEquals("{\"a\":1}", result);
        }

        @Test
        @DisplayName("fromjson() is a true alias for json() - same SpEL dispatch path")
        void fromjsonIsAlias() {
            Object viaJson = realTemplateEngine.evaluateTemplate(
                "{{json('{\"x\":1}')}}", executionContext);
            Object viaFromjson = realTemplateEngine.evaluateTemplate(
                "{{fromjson('{\"x\":1}')}}", executionContext);

            assertEquals(viaJson, viaFromjson);
        }

        @Test
        @DisplayName("Pure expression {{json('[{\"role\":...}]')}} with brace-rich JSON literal parses to typed List")
        void jsonExpressionWithNestedBracesIsRecognized() {
            // Regression: the old EXPRESSION_PATTERN [^}|]+ rejected `}` inside the SpEL
            // argument. With the new pattern (which treats '...' SpEL strings as opaque),
            // the entire {{json('[{...}]')}} matches and resolves to a typed List.
            Object result = realTemplateEngine.evaluateTemplate(
                "{{json('[{\"role\":\"user\",\"parts\":[{\"text\":\"hello\"}]}]')}}",
                executionContext);

            assertInstanceOf(List.class, result);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) result;
            assertEquals(1, list.size());
            assertEquals("user", list.get(0).get("role"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> parts = (List<Map<String, Object>>) list.get(0).get("parts");
            assertEquals("hello", parts.get(0).get("text"));
        }

        @Test
        @DisplayName("Mixed template embeds Map result of json() as JSON literal, not Java toString")
        void mixedTemplateEmbedsJsonResult() {
            // Combines json() function (Pillar 1) with Map → JSON encoding in mixed-template
            // substitution (Pillar 2). EXPRESSION_PATTERN forbids `}` inside a template, so
            // we resolve the Map via a NamespaceResolver mock instead of an inline literal:
            // the bug surface is identical (mixed template + Map value → must JSON-encode).
            when(mockNamespaceResolver.resolveVariable(eq("mcp:cfg.output.params"), any()))
                .thenReturn(Map.of("a", 1));

            String result = (String) realTemplateEngine.evaluateTemplate(
                "wrap={{mcp:cfg.output.params}};done", executionContext);

            assertEquals("wrap={\"a\":1};done", result,
                "Map in mixed template must serialize as JSON, not Java toString {a=1}");
        }
    }
}
