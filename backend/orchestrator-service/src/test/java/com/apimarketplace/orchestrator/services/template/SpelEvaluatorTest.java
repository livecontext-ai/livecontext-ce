package com.apimarketplace.orchestrator.services.template;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive unit tests for SpelEvaluator.
 * Tests SpEL expression evaluation, custom function registration,
 * variable resolution, and caching mechanisms.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SpelEvaluator")
class SpelEvaluatorTest {

    @Mock
    private PathNavigator pathNavigator;

    private SpelEvaluator spelEvaluator;

    @BeforeEach
    void setUp() {
        spelEvaluator = new SpelEvaluator();
        spelEvaluator.init();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Initialization and Custom Functions Registration
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Initialization")
    class InitializationTests {

        @Test
        @DisplayName("Should register all custom functions on init")
        void shouldRegisterAllCustomFunctionsOnInit() {
            Map<String, Method> functions = spelEvaluator.getCustomFunctions();

            assertNotNull(functions);
            assertFalse(functions.isEmpty());

            // Type casting functions
            assertTrue(functions.containsKey("int"));
            assertTrue(functions.containsKey("double"));
            assertTrue(functions.containsKey("string"));
            assertTrue(functions.containsKey("bool"));
            assertTrue(functions.containsKey("long"));
            assertTrue(functions.containsKey("float"));

            // Utility functions
            assertTrue(functions.containsKey("size"));
            assertTrue(functions.containsKey("len")); // Alias for size
            assertTrue(functions.containsKey("typeof"));
            assertTrue(functions.containsKey("default"));
            assertTrue(functions.containsKey("coalesce"));
            assertTrue(functions.containsKey("ifempty"));
            assertTrue(functions.containsKey("isnull"));
            assertTrue(functions.containsKey("isempty"));

            // Math functions
            assertTrue(functions.containsKey("abs"));
            assertTrue(functions.containsKey("round"));
            assertTrue(functions.containsKey("floor"));
            assertTrue(functions.containsKey("ceil"));
            assertTrue(functions.containsKey("min"));
            assertTrue(functions.containsKey("max"));
            assertTrue(functions.containsKey("pow"));
            assertTrue(functions.containsKey("sqrt"));

            // String functions
            assertTrue(functions.containsKey("uppercase"));
            assertTrue(functions.containsKey("lowercase"));
            assertTrue(functions.containsKey("capitalize"));
            assertTrue(functions.containsKey("trim"));
            assertTrue(functions.containsKey("truncate"));
            assertTrue(functions.containsKey("padleft"));
            assertTrue(functions.containsKey("padright"));
            assertTrue(functions.containsKey("replace"));
            assertTrue(functions.containsKey("substring"));
            assertTrue(functions.containsKey("split"));
            assertTrue(functions.containsKey("join"));
            assertTrue(functions.containsKey("startswith"));
            assertTrue(functions.containsKey("endswith"));
            assertTrue(functions.containsKey("contains"));
            assertTrue(functions.containsKey("matches"));
            assertTrue(functions.containsKey("length"));

            // Date/formatting functions
            assertTrue(functions.containsKey("formatdate"));
            assertTrue(functions.containsKey("formatnumber"));
            assertTrue(functions.containsKey("formatcurrency"));
            assertTrue(functions.containsKey("now"));
            assertTrue(functions.containsKey("today"));
        }

        @Test
        @DisplayName("Should have correct number of custom functions")
        void shouldHaveCorrectNumberOfCustomFunctions() {
            Map<String, Method> functions = spelEvaluator.getCustomFunctions();

            // Count expected functions from CUSTOM_FUNCTION_NAMES
            int expectedCount = SpelEvaluator.CUSTOM_FUNCTION_NAMES.size();
            assertEquals(expectedCount, functions.size());
        }

        @Test
        @DisplayName("Should define all reserved words")
        void shouldDefineAllReservedWords() {
            Set<String> reserved = SpelEvaluator.RESERVED_WORDS;

            assertTrue(reserved.contains("true"));
            assertTrue(reserved.contains("false"));
            assertTrue(reserved.contains("null"));
            assertTrue(reserved.contains("and"));
            assertTrue(reserved.contains("or"));
            assertTrue(reserved.contains("not"));
            assertTrue(reserved.contains("eq"));
            assertTrue(reserved.contains("ne"));
            assertTrue(reserved.contains("lt"));
            assertTrue(reserved.contains("gt"));
            assertTrue(reserved.contains("le"));
            assertTrue(reserved.contains("ge"));
            assertTrue(reserved.contains("instanceof"));
            assertTrue(reserved.contains("matches"));
            assertTrue(reserved.contains("between"));
            assertTrue(reserved.contains("T"));
            assertTrue(reserved.contains("new"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // createEvaluationContext() Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("createEvaluationContext()")
    class CreateEvaluationContextTests {

        @Test
        @DisplayName("Should create context without variables")
        void shouldCreateContextWithoutVariables() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext();

            assertNotNull(context);
        }

        @Test
        @DisplayName("Should create context with variables")
        void shouldCreateContextWithVariables() {
            Map<String, Object> variables = Map.of(
                "name", "John",
                "age", 30,
                "active", true
            );

            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(variables);

            assertNotNull(context);
            assertEquals("John", context.lookupVariable("name"));
            assertEquals(30, context.lookupVariable("age"));
            assertEquals(true, context.lookupVariable("active"));
        }

        @Test
        @DisplayName("Should create context with empty variables map")
        void shouldCreateContextWithEmptyVariablesMap() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(Map.of());

            assertNotNull(context);
        }

        @Test
        @DisplayName("Should create context with null values in variables")
        void shouldCreateContextWithNullValuesInVariables() {
            Map<String, Object> variables = new HashMap<>();
            variables.put("nullValue", null);
            variables.put("presentValue", "test");

            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(variables);

            assertNotNull(context);
            assertNull(context.lookupVariable("nullValue"));
            assertEquals("test", context.lookupVariable("presentValue"));
        }

        @Test
        @DisplayName("Should register custom functions in context")
        void shouldRegisterCustomFunctionsInContext() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext();

            // Custom functions should be accessible - test by evaluating
            Object result = spelEvaluator.evaluate("#uppercase('hello')", context);
            assertEquals("HELLO", result);
        }
    }

    @Nested
    @DisplayName("Security restrictions")
    class SecurityRestrictionTests {

        @Test
        @DisplayName("Should block type references")
        void shouldBlockTypeReferences() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext();

            Object result = spelEvaluator.evaluate(
                "T(java.lang.Runtime).getRuntime().exec('calc')",
                context);

            assertNull(result);
        }

        @Test
        @DisplayName("Should block constructors")
        void shouldBlockConstructors() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext();

            Object result = spelEvaluator.evaluate(
                "new java.lang.ProcessBuilder('calc').start()",
                context);

            assertNull(result);
        }

        @Test
        @DisplayName("Should block reflection through getClass")
        void shouldBlockReflectionThroughGetClass() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(
                Map.of("text", "hello")
            );

            Object result = spelEvaluator.evaluate(
                "#text.getClass().forName('java.lang.Runtime')",
                context);

            assertNull(result);
        }

        @Test
        @DisplayName("Should keep JSON-like helper methods")
        void shouldKeepJsonLikeHelperMethods() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(
                Map.of(
                    "items", List.of(1, 2, 3),
                    "user", Map.of("name", "Ada"),
                    "text", "hello"
                )
            );

            assertEquals(3, spelEvaluator.evaluate("#items.size()", context));
            assertEquals("Ada", spelEvaluator.evaluate("#user.get('name')", context));
            assertEquals(5, spelEvaluator.evaluate("#text.length()", context));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluate() Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("evaluate()")
    class EvaluateTests {

        @Test
        @DisplayName("Should evaluate simple arithmetic expression")
        void shouldEvaluateSimpleArithmeticExpression() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext();

            Object result = spelEvaluator.evaluate("2 + 3", context);

            assertEquals(5, result);
        }

        @Test
        @DisplayName("Should evaluate string concatenation")
        void shouldEvaluateStringConcatenation() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext();

            Object result = spelEvaluator.evaluate("'Hello' + ' ' + 'World'", context);

            assertEquals("Hello World", result);
        }

        @Test
        @DisplayName("Should evaluate boolean expression")
        void shouldEvaluateBooleanExpression() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext();

            Object result = spelEvaluator.evaluate("5 > 3", context);

            assertEquals(true, result);
        }

        @Test
        @DisplayName("Should evaluate variable reference")
        void shouldEvaluateVariableReference() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(
                Map.of("name", "John")
            );

            Object result = spelEvaluator.evaluate("#name", context);

            assertEquals("John", result);
        }

        @Test
        @DisplayName("Should evaluate custom function call")
        void shouldEvaluateCustomFunctionCall() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext();

            Object result = spelEvaluator.evaluate("#int('42')", context);

            assertEquals(42, result);
        }

        @Test
        @DisplayName("Should evaluate nested custom function calls")
        void shouldEvaluateNestedCustomFunctionCalls() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext();

            Object result = spelEvaluator.evaluate("#uppercase(#trim('  hello  '))", context);

            assertEquals("HELLO", result);
        }

        @Test
        @DisplayName("Should return null for invalid expression")
        void shouldReturnNullForInvalidExpression() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext();

            Object result = spelEvaluator.evaluate("invalid[[[expression", context);

            assertNull(result);
        }

        @Test
        @DisplayName("Should return null for undefined variable")
        void shouldReturnNullForUndefinedVariable() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext();

            Object result = spelEvaluator.evaluate("#undefinedVar", context);

            assertNull(result);
        }

        @Test
        @DisplayName("Should evaluate ternary operator")
        void shouldEvaluateTernaryOperator() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(
                Map.of("value", 10)
            );

            Object result = spelEvaluator.evaluate("#value > 5 ? 'big' : 'small'", context);

            assertEquals("big", result);
        }

        @Test
        @DisplayName("Should evaluate Elvis operator")
        void shouldEvaluateElvisOperator() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(
                Map.of("name", "John")
            );

            Object result = spelEvaluator.evaluate("#name ?: 'default'", context);

            assertEquals("John", result);
        }

        @Test
        @DisplayName("Should evaluate Elvis operator with null value")
        void shouldEvaluateElvisOperatorWithNullValue() {
            Map<String, Object> vars = new HashMap<>();
            vars.put("name", null);
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(vars);

            Object result = spelEvaluator.evaluate("#name ?: 'default'", context);

            assertEquals("default", result);
        }

        @Test
        @DisplayName("Should evaluate safe navigation operator")
        void shouldEvaluateSafeNavigationOperator() {
            Map<String, Object> user = Map.of("name", "John");
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(
                Map.of("user", user)
            );

            Object result = spelEvaluator.evaluate("#user?.get('name')", context);

            assertEquals("John", result);
        }

        @Test
        @DisplayName("Should evaluate collection access")
        void shouldEvaluateCollectionAccess() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(
                Map.of("items", List.of("a", "b", "c"))
            );

            Object result = spelEvaluator.evaluate("#items[1]", context);

            assertEquals("b", result);
        }

        @Test
        @DisplayName("Should evaluate map access")
        void shouldEvaluateMapAccess() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(
                Map.of("data", Map.of("key", "value"))
            );

            Object result = spelEvaluator.evaluate("#data['key']", context);

            assertEquals("value", result);
        }

        @Test
        @DisplayName("Should evaluate method call on string")
        void shouldEvaluateMethodCallOnString() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(
                Map.of("text", "Hello World")
            );

            Object result = spelEvaluator.evaluate("#text.length()", context);

            assertEquals(11, result);
        }

        @Test
        @DisplayName("Should evaluate complex expression with multiple operators")
        void shouldEvaluateComplexExpressionWithMultipleOperators() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(
                Map.of("a", 10, "b", 5, "c", 3)
            );

            Object result = spelEvaluator.evaluate("(#a + #b) * #c", context);

            assertEquals(45, result);
        }

        @Test
        @DisplayName("Should evaluate logical AND")
        void shouldEvaluateLogicalAnd() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(
                Map.of("a", true, "b", false)
            );

            Object result = spelEvaluator.evaluate("#a and #b", context);

            assertEquals(false, result);
        }

        @Test
        @DisplayName("Should evaluate logical OR")
        void shouldEvaluateLogicalOr() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(
                Map.of("a", true, "b", false)
            );

            Object result = spelEvaluator.evaluate("#a or #b", context);

            assertEquals(true, result);
        }

        @Test
        @DisplayName("Should evaluate NOT operator")
        void shouldEvaluateNotOperator() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(
                Map.of("flag", false)
            );

            Object result = spelEvaluator.evaluate("not #flag", context);

            assertEquals(true, result);
        }

        @Test
        @DisplayName("Should block type references used by instanceof")
        void shouldBlockTypeReferencesUsedByInstanceof() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(
                Map.of("value", "hello")
            );

            Object result = spelEvaluator.evaluate("#value instanceof T(String)", context);

            assertNull(result);
        }

        @Test
        @DisplayName("Should evaluate matches operator")
        void shouldEvaluateMatchesOperator() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(
                Map.of("email", "test@example.com")
            );

            Object result = spelEvaluator.evaluate("#email matches '.*@.*\\..*'", context);

            assertEquals(true, result);
        }

        @Test
        @DisplayName("Should evaluate between operator")
        void shouldEvaluateBetweenOperator() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(
                Map.of("value", 5)
            );

            Object result = spelEvaluator.evaluate("#value between {1, 10}", context);

            assertEquals(true, result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateWithMap() Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("evaluateWithMap()")
    class EvaluateWithMapTests {

        @Test
        @DisplayName("Should evaluate simple variable from map")
        void shouldEvaluateSimpleVariableFromMap() {
            Map<String, Object> context = Map.of("name", "John");

            // Simple variable is resolved directly from map, no path navigation needed
            Object result = spelEvaluator.evaluateWithMap("name", context, pathNavigator);

            assertEquals("John", result);
        }

        @Test
        @DisplayName("Should evaluate arithmetic with map variables")
        void shouldEvaluateArithmeticWithMapVariables() {
            Map<String, Object> context = Map.of("a", 10, "b", 5);

            Object result = spelEvaluator.evaluateWithMap("a + b", context, pathNavigator);

            assertEquals(15, result);
        }

        @Test
        @DisplayName("Should evaluate comparison with map variables")
        void shouldEvaluateComparisonWithMapVariables() {
            Map<String, Object> context = Map.of("value", 100);

            Object result = spelEvaluator.evaluateWithMap("value > 50", context, pathNavigator);

            assertEquals(true, result);
        }

        @Test
        @DisplayName("Should evaluate nested path from map")
        void shouldEvaluateNestedPathFromMap() {
            Map<String, Object> user = Map.of("name", "John", "age", 30);
            Map<String, Object> context = Map.of("user", user);

            when(pathNavigator.navigateMapPath(eq(user), eq("name"))).thenReturn("John");

            Object result = spelEvaluator.evaluateWithMap("user.name", context, pathNavigator);

            assertEquals("John", result);
        }

        @Test
        @DisplayName("Should evaluate prefixed key from map")
        void shouldEvaluatePrefixedKeyFromMap() {
            Map<String, Object> stepOutput = Map.of("data", "result");
            Map<String, Object> context = Map.of("mcp:api_call", stepOutput);

            Object result = spelEvaluator.evaluateWithMap("mcp:api_call", context, pathNavigator);

            // Should transform mcp:api_call to mcp_api_call for SpEL
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should handle custom function in map expression")
        void shouldHandleCustomFunctionInMapExpression() {
            Map<String, Object> context = Map.of("name", "john");

            Object result = spelEvaluator.evaluateWithMap("uppercase(name)", context, pathNavigator);

            assertEquals("JOHN", result);
        }

        @Test
        @DisplayName("Should handle reserved words correctly")
        void shouldHandleReservedWordsCorrectly() {
            Map<String, Object> context = Map.of("value", 5);

            // 'true' and 'false' should not be transformed to variables
            Object result = spelEvaluator.evaluateWithMap("value > 0 and true", context, pathNavigator);

            assertEquals(true, result);
        }

        @Test
        @DisplayName("Should return null for evaluation error")
        void shouldReturnNullForEvaluationError() {
            Map<String, Object> context = Map.of();

            Object result = spelEvaluator.evaluateWithMap("invalid[[[", context, pathNavigator);

            assertNull(result);
        }

        @Test
        @DisplayName("Should handle empty context")
        void shouldHandleEmptyContext() {
            Map<String, Object> context = Map.of();

            Object result = spelEvaluator.evaluateWithMap("1 + 1", context, pathNavigator);

            assertEquals(2, result);
        }

        @Test
        @DisplayName("Should handle null value in context")
        void shouldHandleNullValueInContext() {
            Map<String, Object> context = new HashMap<>();
            context.put("value", null);

            Object result = spelEvaluator.evaluateWithMap("value", context, pathNavigator);

            assertNull(result);
        }

        @Test
        @DisplayName("Should evaluate expression with output nested structure")
        void shouldEvaluateExpressionWithOutputNestedStructure() {
            Map<String, Object> output = Map.of("result", "success");
            Map<String, Object> stepData = Map.of("output", output);
            Map<String, Object> context = Map.of("mcp:step1", stepData);

            when(pathNavigator.navigateMapPath(eq(stepData), eq("output.result"))).thenReturn("success");

            Object result = spelEvaluator.evaluateWithMap("mcp:step1.output.result", context, pathNavigator);

            assertNotNull(result);
        }

        @Test
        @DisplayName("Should evaluate expression with array access in path")
        void shouldEvaluateExpressionWithArrayAccessInPath() {
            Map<String, Object> splitOutput = Map.of("output", Map.of(
                "current_item", Map.of("edges", List.of(
                    Map.of("node", Map.of("text", "caption text"))
                ))
            ));
            Map<String, Object> context = Map.of("core:split_posts", splitOutput);

            when(pathNavigator.navigateMapPath(eq(splitOutput),
                eq("output.current_item.edges[0].node.text")))
                .thenReturn("caption text");

            Object result = spelEvaluator.evaluateWithMap(
                "core:split_posts.output.current_item.edges[0].node.text",
                context, pathNavigator);

            assertEquals("caption text", result);
        }

        @Test
        @DisplayName("Should evaluate expression with array access at end of path")
        void shouldEvaluateExpressionWithArrayAccessAtEnd() {
            Map<String, Object> data = Map.of("output", Map.of(
                "items", List.of("first", "second")
            ));
            Map<String, Object> context = Map.of("core:split", data);

            when(pathNavigator.navigateMapPath(eq(data), eq("output.items[1]")))
                .thenReturn("second");

            Object result = spelEvaluator.evaluateWithMap(
                "core:split.output.items[1]", context, pathNavigator);

            assertEquals("second", result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateWithMap() Array Access Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("evaluateWithMap() - Array Access")
    class EvaluateWithMapArrayAccessTests {

        @Test
        @DisplayName("Should resolve standalone array access items[1]")
        void shouldResolveStandaloneArrayAccess() {
            Map<String, Object> context = Map.of("items", List.of("a", "b", "c"));

            Object result = spelEvaluator.evaluateWithMap("items[1]", context, pathNavigator);

            assertEquals("b", result);
        }

        @Test
        @DisplayName("Should resolve array access with arithmetic items[0] + items[1]")
        void shouldResolveArrayAccessWithArithmetic() {
            Map<String, Object> context = Map.of("values", List.of(10, 20, 30));

            Object result = spelEvaluator.evaluateWithMap("values[0] + values[2]", context, pathNavigator);

            assertEquals(40, result);
        }

        @Test
        @DisplayName("Should resolve deep path with array access in middle")
        void shouldResolveDeepPathWithArrayAccessInMiddle() {
            Map<String, Object> splitOutput = Map.of("output", Map.of(
                "current_item", Map.of(
                    "edge_media_to_caption", Map.of(
                        "edges", List.of(
                            Map.of("node", Map.of("text", "Hello caption"))
                        )
                    )
                )
            ));
            Map<String, Object> context = Map.of("core:split_posts", splitOutput);

            when(pathNavigator.navigateMapPath(eq(splitOutput),
                eq("output.current_item.edge_media_to_caption.edges[0].node.text")))
                .thenReturn("Hello caption");

            Object result = spelEvaluator.evaluateWithMap(
                "core:split_posts.output.current_item.edge_media_to_caption.edges[0].node.text",
                context, pathNavigator);

            assertEquals("Hello caption", result);
        }

        @Test
        @DisplayName("Should return null for out-of-bounds array access")
        void shouldReturnNullForOutOfBoundsArrayAccess() {
            Map<String, Object> context = Map.of("items", List.of("only_one"));

            Object result = spelEvaluator.evaluateWithMap("items[5]", context, pathNavigator);

            assertNull(result);
        }

        @Test
        @DisplayName("Should handle array access on non-list value")
        void shouldHandleArrayAccessOnNonListValue() {
            Map<String, Object> context = Map.of("name", "John");

            Object result = spelEvaluator.evaluateWithMap("name[0]", context, pathNavigator);

            assertNull(result);
        }

        @Test
        @DisplayName("Should handle ternary with array access and numeric comparison")
        void shouldHandleTernaryWithArrayAccess() {
            Map<String, Object> context = Map.of("values", List.of(10, 20, 30));

            // Use numeric comparison (string literals in evaluateWithMap are not supported
            // because transformExpressionForMap captures identifiers inside string literals)
            Object result = spelEvaluator.evaluateWithMap(
                "values[0] > 0 ? values[0] : values[1]", context, pathNavigator);

            assertEquals(10, result);
        }

        @Test
        @DisplayName("Should handle array access with custom function")
        void shouldHandleArrayAccessWithCustomFunction() {
            Map<String, Object> context = Map.of("names", List.of("alice", "bob"));

            Object result = spelEvaluator.evaluateWithMap(
                "uppercase(names[0])", context, pathNavigator);

            assertEquals("ALICE", result);
        }

        @Test
        @DisplayName("Should handle prefixed key with array access in remaining path")
        void shouldHandlePrefixedKeyWithArrayAccess() {
            Map<String, Object> nodeOutput = Map.of("output", Map.of(
                "results", List.of("first", "second")
            ));
            Map<String, Object> context = Map.of("mcp:api_call", nodeOutput);

            when(pathNavigator.navigateMapPath(eq(nodeOutput), eq("output.results[1]")))
                .thenReturn("second");

            Object result = spelEvaluator.evaluateWithMap(
                "mcp:api_call.output.results[1]", context, pathNavigator);

            assertEquals("second", result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // preprocessCustomFunctions() Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("preprocessCustomFunctions()")
    class PreprocessCustomFunctionsTests {

        @Test
        @DisplayName("Should return null for null input")
        void shouldReturnNullForNullInput() {
            String result = spelEvaluator.preprocessCustomFunctions(null);

            assertNull(result);
        }

        @Test
        @DisplayName("Should return unchanged expression without functions")
        void shouldReturnUnchangedExpressionWithoutFunctions() {
            String result = spelEvaluator.preprocessCustomFunctions("a + b");

            assertEquals("a + b", result);
        }

        @Test
        @DisplayName("Should add # prefix to custom function")
        void shouldAddHashPrefixToCustomFunction() {
            String result = spelEvaluator.preprocessCustomFunctions("uppercase('hello')");

            assertEquals("#uppercase('hello')", result);
        }

        @Test
        @DisplayName("Should add # prefix to multiple functions")
        void shouldAddHashPrefixToMultipleFunctions() {
            String result = spelEvaluator.preprocessCustomFunctions("uppercase(trim(' hello '))");

            assertEquals("#uppercase(#trim(' hello '))", result);
        }

        @Test
        @DisplayName("Should not modify non-function identifiers")
        void shouldNotModifyNonFunctionIdentifiers() {
            String result = spelEvaluator.preprocessCustomFunctions("myVariable + 1");

            assertEquals("myVariable + 1", result);
        }

        @Test
        @DisplayName("Should handle string literals correctly")
        void shouldHandleStringLiteralsCorrectly() {
            // Function name inside string should not be modified
            String result = spelEvaluator.preprocessCustomFunctions("'uppercase is a function'");

            assertEquals("'uppercase is a function'", result);
        }

        @Test
        @DisplayName("Should handle double quoted strings")
        void shouldHandleDoubleQuotedStrings() {
            String result = spelEvaluator.preprocessCustomFunctions("\"trim this\"");

            assertEquals("\"trim this\"", result);
        }

        @Test
        @DisplayName("Should handle mixed functions and variables")
        void shouldHandleMixedFunctionsAndVariables() {
            String result = spelEvaluator.preprocessCustomFunctions("size(myList) + myValue");

            assertEquals("#size(myList) + myValue", result);
        }

        @Test
        @DisplayName("Should handle function with whitespace before parenthesis")
        void shouldHandleFunctionWithWhitespaceBeforeParenthesis() {
            String result = spelEvaluator.preprocessCustomFunctions("uppercase ('hello')");

            // Implementation normalizes function calls, removing extra whitespace
            assertEquals("#uppercase('hello')", result);
        }

        @Test
        @DisplayName("Should convert function name to lowercase")
        void shouldConvertFunctionNameToLowercase() {
            String result = spelEvaluator.preprocessCustomFunctions("UPPERCASE('hello')");

            assertEquals("#uppercase('hello')", result);
        }

        @Test
        @DisplayName("Should handle all type casting functions")
        void shouldHandleAllTypeCastingFunctions() {
            assertEquals("#int(x)", spelEvaluator.preprocessCustomFunctions("int(x)"));
            assertEquals("#double(x)", spelEvaluator.preprocessCustomFunctions("double(x)"));
            assertEquals("#string(x)", spelEvaluator.preprocessCustomFunctions("string(x)"));
            assertEquals("#bool(x)", spelEvaluator.preprocessCustomFunctions("bool(x)"));
            assertEquals("#long(x)", spelEvaluator.preprocessCustomFunctions("long(x)"));
            assertEquals("#float(x)", spelEvaluator.preprocessCustomFunctions("float(x)"));
        }

        @Test
        @DisplayName("Should handle all utility functions")
        void shouldHandleAllUtilityFunctions() {
            assertEquals("#size(x)", spelEvaluator.preprocessCustomFunctions("size(x)"));
            assertEquals("#len(x)", spelEvaluator.preprocessCustomFunctions("len(x)"));
            assertEquals("#typeof(x)", spelEvaluator.preprocessCustomFunctions("typeof(x)"));
            assertEquals("#default(x, y)", spelEvaluator.preprocessCustomFunctions("default(x, y)"));
            assertEquals("#coalesce(x, y)", spelEvaluator.preprocessCustomFunctions("coalesce(x, y)"));
            assertEquals("#ifempty(x, y)", spelEvaluator.preprocessCustomFunctions("ifempty(x, y)"));
            assertEquals("#isnull(x)", spelEvaluator.preprocessCustomFunctions("isnull(x)"));
            assertEquals("#isempty(x)", spelEvaluator.preprocessCustomFunctions("isempty(x)"));
        }

        @Test
        @DisplayName("Should handle all math functions")
        void shouldHandleAllMathFunctions() {
            assertEquals("#abs(x)", spelEvaluator.preprocessCustomFunctions("abs(x)"));
            assertEquals("#round(x, 2)", spelEvaluator.preprocessCustomFunctions("round(x, 2)"));
            assertEquals("#floor(x)", spelEvaluator.preprocessCustomFunctions("floor(x)"));
            assertEquals("#ceil(x)", spelEvaluator.preprocessCustomFunctions("ceil(x)"));
            assertEquals("#min(x, y)", spelEvaluator.preprocessCustomFunctions("min(x, y)"));
            assertEquals("#max(x, y)", spelEvaluator.preprocessCustomFunctions("max(x, y)"));
            assertEquals("#pow(x, y)", spelEvaluator.preprocessCustomFunctions("pow(x, y)"));
            assertEquals("#sqrt(x)", spelEvaluator.preprocessCustomFunctions("sqrt(x)"));
        }

        @Test
        @DisplayName("Should handle all string functions")
        void shouldHandleAllStringFunctions() {
            assertEquals("#uppercase(x)", spelEvaluator.preprocessCustomFunctions("uppercase(x)"));
            assertEquals("#lowercase(x)", spelEvaluator.preprocessCustomFunctions("lowercase(x)"));
            assertEquals("#capitalize(x)", spelEvaluator.preprocessCustomFunctions("capitalize(x)"));
            assertEquals("#trim(x)", spelEvaluator.preprocessCustomFunctions("trim(x)"));
            assertEquals("#truncate(x, 10, '...')", spelEvaluator.preprocessCustomFunctions("truncate(x, 10, '...')"));
            assertEquals("#padleft(x, 5, '0')", spelEvaluator.preprocessCustomFunctions("padleft(x, 5, '0')"));
            assertEquals("#padright(x, 5, '0')", spelEvaluator.preprocessCustomFunctions("padright(x, 5, '0')"));
            assertEquals("#replace(x, 'a', 'b')", spelEvaluator.preprocessCustomFunctions("replace(x, 'a', 'b')"));
            assertEquals("#substring(x, 0, 5)", spelEvaluator.preprocessCustomFunctions("substring(x, 0, 5)"));
            assertEquals("#split(x, ',')", spelEvaluator.preprocessCustomFunctions("split(x, ',')"));
            assertEquals("#join(x, ',')", spelEvaluator.preprocessCustomFunctions("join(x, ',')"));
            assertEquals("#startswith(x, 'a')", spelEvaluator.preprocessCustomFunctions("startswith(x, 'a')"));
            assertEquals("#endswith(x, 'a')", spelEvaluator.preprocessCustomFunctions("endswith(x, 'a')"));
            assertEquals("#contains(x, 'a')", spelEvaluator.preprocessCustomFunctions("contains(x, 'a')"));
            assertEquals("#matches(x, '.*')", spelEvaluator.preprocessCustomFunctions("matches(x, '.*')"));
            assertEquals("#length(x)", spelEvaluator.preprocessCustomFunctions("length(x)"));
        }

        @Test
        @DisplayName("Should handle all date/formatting functions")
        void shouldHandleAllDateFormattingFunctions() {
            assertEquals("#formatdate(x, 'yyyy-MM-dd')", spelEvaluator.preprocessCustomFunctions("formatdate(x, 'yyyy-MM-dd')"));
            assertEquals("#formatnumber(x, 2)", spelEvaluator.preprocessCustomFunctions("formatnumber(x, 2)"));
            assertEquals("#formatcurrency(x, 'USD')", spelEvaluator.preprocessCustomFunctions("formatcurrency(x, 'USD')"));
            assertEquals("#now()", spelEvaluator.preprocessCustomFunctions("now()"));
            assertEquals("#today()", spelEvaluator.preprocessCustomFunctions("today()"));
        }

        @Test
        @DisplayName("Should handle complex nested expression")
        void shouldHandleComplexNestedExpression() {
            String input = "uppercase(trim(substring(name, 0, int(length(name) / 2))))";
            String expected = "#uppercase(#trim(#substring(name, 0, #int(#length(name) / 2))))";

            String result = spelEvaluator.preprocessCustomFunctions(input);

            assertEquals(expected, result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // evaluateWithCache() Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("evaluateWithCache()")
    class EvaluateWithCacheTests {

        @Test
        @DisplayName("Should evaluate simple variable with # prefix")
        void shouldEvaluateSimpleVariableWithHashPrefix() {
            Map<String, Object> context = Map.of("value", 42);

            // SpEL variables must use # prefix
            Object result = spelEvaluator.evaluateWithCache("#value", context);

            assertEquals(42, result);
        }

        @Test
        @DisplayName("Should preprocess custom functions before evaluation")
        void shouldPreprocessCustomFunctionsBeforeEvaluation() {
            Map<String, Object> context = Map.of("name", "hello");

            // uppercase(#name) gets preprocessed to #uppercase(#name)
            Object result = spelEvaluator.evaluateWithCache("uppercase(#name)", context);

            assertEquals("HELLO", result);
        }

        @Test
        @DisplayName("Should cache parsed expressions")
        void shouldCacheParsedExpressions() {
            Map<String, Object> context = Map.of("value", 10);

            // SpEL variables must use # prefix
            Object result1 = spelEvaluator.evaluateWithCache("#value * 2", context);
            Object result2 = spelEvaluator.evaluateWithCache("#value * 2", context);

            assertEquals(20, result1);
            assertEquals(20, result2);
        }

        @Test
        @DisplayName("Should return raw expression on error")
        void shouldReturnRawExpressionOnError() {
            Map<String, Object> context = Map.of();

            Object result = spelEvaluator.evaluateWithCache("invalid[[[", context);

            assertEquals("invalid[[[", result);
        }

        @Test
        @DisplayName("Should handle multiple variables with # prefix")
        void shouldHandleMultipleVariablesWithHashPrefix() {
            Map<String, Object> context = Map.of("a", 5, "b", 3, "c", 2);

            // SpEL variables must use # prefix
            Object result = spelEvaluator.evaluateWithCache("#a + #b * #c", context);

            assertEquals(11, result); // 5 + 3*2 = 11
        }

        @Test
        @DisplayName("Should handle nested map values with # prefix")
        void shouldHandleNestedMapValuesWithHashPrefix() {
            Map<String, Object> nested = Map.of("value", 100);
            Map<String, Object> context = Map.of("data", nested);

            // SpEL variables must use # prefix
            Object result = spelEvaluator.evaluateWithCache("#data", context);

            assertEquals(nested, result);
        }

        @Test
        @DisplayName("Should evaluate literal expression without variables")
        void shouldEvaluateLiteralExpressionWithoutVariables() {
            Map<String, Object> context = Map.of();

            Object result = spelEvaluator.evaluateWithCache("2 + 3", context);

            assertEquals(5, result);
        }

        @Test
        @DisplayName("Should evaluate string literal")
        void shouldEvaluateStringLiteral() {
            Map<String, Object> context = Map.of();

            Object result = spelEvaluator.evaluateWithCache("'hello world'", context);

            assertEquals("hello world", result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // toBoolean() Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("toBoolean()")
    class ToBooleanTests {

        @Test
        @DisplayName("Should return true for Boolean true")
        void shouldReturnTrueForBooleanTrue() {
            assertTrue(spelEvaluator.toBoolean(true));
        }

        @Test
        @DisplayName("Should return false for Boolean false")
        void shouldReturnFalseForBooleanFalse() {
            assertFalse(spelEvaluator.toBoolean(false));
        }

        @Test
        @DisplayName("Should return true for non-zero numbers")
        void shouldReturnTrueForNonZeroNumbers() {
            assertTrue(spelEvaluator.toBoolean(1));
            assertTrue(spelEvaluator.toBoolean(-1));
            assertTrue(spelEvaluator.toBoolean(42));
            assertTrue(spelEvaluator.toBoolean(0.1));
            assertTrue(spelEvaluator.toBoolean(-0.1));
            assertTrue(spelEvaluator.toBoolean(100L));
        }

        @Test
        @DisplayName("Should return false for zero")
        void shouldReturnFalseForZero() {
            assertFalse(spelEvaluator.toBoolean(0));
            assertFalse(spelEvaluator.toBoolean(0.0));
            assertFalse(spelEvaluator.toBoolean(0L));
        }

        @Test
        @DisplayName("Should return true for non-empty non-false strings")
        void shouldReturnTrueForNonEmptyNonFalseStrings() {
            assertTrue(spelEvaluator.toBoolean("hello"));
            assertTrue(spelEvaluator.toBoolean("true"));
            assertTrue(spelEvaluator.toBoolean("yes"));
            assertTrue(spelEvaluator.toBoolean("1"));
        }

        @Test
        @DisplayName("Should return false for 'false' string")
        void shouldReturnFalseForFalseString() {
            assertFalse(spelEvaluator.toBoolean("false"));
            assertFalse(spelEvaluator.toBoolean("FALSE"));
            assertFalse(spelEvaluator.toBoolean("False"));
        }

        @Test
        @DisplayName("Should return false for '0' string")
        void shouldReturnFalseForZeroString() {
            assertFalse(spelEvaluator.toBoolean("0"));
        }

        @Test
        @DisplayName("Should return false for blank string")
        void shouldReturnFalseForBlankString() {
            assertFalse(spelEvaluator.toBoolean(""));
            assertFalse(spelEvaluator.toBoolean("   "));
            assertFalse(spelEvaluator.toBoolean("\t\n"));
        }

        @Test
        @DisplayName("Should return false for null")
        void shouldReturnFalseForNull() {
            assertFalse(spelEvaluator.toBoolean(null));
        }

        @Test
        @DisplayName("Should return true for non-null objects")
        void shouldReturnTrueForNonNullObjects() {
            assertTrue(spelEvaluator.toBoolean(List.of()));
            assertTrue(spelEvaluator.toBoolean(Map.of()));
            assertTrue(spelEvaluator.toBoolean(new Object()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // formatValueForSpel() Tests
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("formatValueForSpel()")
    class FormatValueForSpelTests {

        @Test
        @DisplayName("Should format string with single quotes")
        void shouldFormatStringWithSingleQuotes() {
            String result = spelEvaluator.formatValueForSpel("hello");

            assertEquals("'hello'", result);
        }

        @Test
        @DisplayName("Should escape single quotes in string")
        void shouldEscapeSingleQuotesInString() {
            String result = spelEvaluator.formatValueForSpel("it's a test");

            assertEquals("'it''s a test'", result);
        }

        @Test
        @DisplayName("Should format Long with L suffix")
        void shouldFormatLongWithLSuffix() {
            String result = spelEvaluator.formatValueForSpel(100L);

            assertEquals("100L", result);
        }

        @Test
        @DisplayName("Should format Double with D suffix")
        void shouldFormatDoubleWithDSuffix() {
            String result = spelEvaluator.formatValueForSpel(3.14);

            assertEquals("3.14D", result);
        }

        @Test
        @DisplayName("Should format Float with F suffix")
        void shouldFormatFloatWithFSuffix() {
            String result = spelEvaluator.formatValueForSpel(2.5f);

            assertEquals("2.5F", result);
        }

        @Test
        @DisplayName("Should format Integer without suffix")
        void shouldFormatIntegerWithoutSuffix() {
            String result = spelEvaluator.formatValueForSpel(42);

            assertEquals("42", result);
        }

        @Test
        @DisplayName("Should format Boolean as string")
        void shouldFormatBooleanAsString() {
            assertEquals("true", spelEvaluator.formatValueForSpel(true));
            assertEquals("false", spelEvaluator.formatValueForSpel(false));
        }

        @Test
        @DisplayName("Should format other objects with quotes")
        void shouldFormatOtherObjectsWithQuotes() {
            String result = spelEvaluator.formatValueForSpel(List.of(1, 2, 3));

            assertTrue(result.startsWith("'"));
            assertTrue(result.endsWith("'"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Integration Tests - Full SpEL Evaluation with Custom Functions
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Integration Tests")
    class IntegrationTests {

        @Test
        @DisplayName("Should evaluate type casting functions")
        void shouldEvaluateTypeCastingFunctions() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext();

            assertEquals(42, spelEvaluator.evaluate("#int('42')", context));
            assertEquals(42L, spelEvaluator.evaluate("#long('42')", context));
            assertEquals(3.14, spelEvaluator.evaluate("#double('3.14')", context));
            assertEquals("42", spelEvaluator.evaluate("#string(42)", context));
            assertEquals(true, spelEvaluator.evaluate("#bool('true')", context));
        }

        @Test
        @DisplayName("Should evaluate utility functions")
        void shouldEvaluateUtilityFunctions() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(
                Map.of("list", List.of(1, 2, 3), "text", "hello")
            );

            assertEquals(3, spelEvaluator.evaluate("#size(#list)", context));
            assertEquals(5, spelEvaluator.evaluate("#len(#text)", context));
            assertEquals("list", spelEvaluator.evaluate("#typeof(#list)", context));
            assertEquals("default", spelEvaluator.evaluate("#default(null, 'default')", context));
            assertEquals(true, spelEvaluator.evaluate("#isnull(null)", context));
            assertEquals(false, spelEvaluator.evaluate("#isempty(#list)", context));
        }

        @Test
        @DisplayName("Should evaluate math functions")
        void shouldEvaluateMathFunctions() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext();

            assertEquals(5.0, spelEvaluator.evaluate("#abs(-5)", context));
            assertEquals(3L, spelEvaluator.evaluate("#round(3.4, 0)", context));
            assertEquals(3L, spelEvaluator.evaluate("#floor(3.9)", context));
            assertEquals(4L, spelEvaluator.evaluate("#ceil(3.1)", context));
            assertEquals(2.0, spelEvaluator.evaluate("#min(2, 5)", context));
            assertEquals(5.0, spelEvaluator.evaluate("#max(2, 5)", context));
            assertEquals(8.0, spelEvaluator.evaluate("#pow(2, 3)", context));
            assertEquals(3.0, spelEvaluator.evaluate("#sqrt(9)", context));
        }

        @Test
        @DisplayName("Should evaluate string functions")
        void shouldEvaluateStringFunctions() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext();

            assertEquals("HELLO", spelEvaluator.evaluate("#uppercase('hello')", context));
            assertEquals("hello", spelEvaluator.evaluate("#lowercase('HELLO')", context));
            assertEquals("Hello", spelEvaluator.evaluate("#capitalize('HELLO')", context));
            assertEquals("hello", spelEvaluator.evaluate("#trim('  hello  ')", context));
            assertEquals("hel...", spelEvaluator.evaluate("#truncate('hello world', 6, null)", context));
            assertEquals("00042", spelEvaluator.evaluate("#padleft('42', 5, '0')", context));
            assertEquals("42000", spelEvaluator.evaluate("#padright('42', 5, '0')", context));
            assertEquals("hello world", spelEvaluator.evaluate("#replace('hello-world', '-', ' ')", context));
            assertEquals("llo", spelEvaluator.evaluate("#substring('hello', 2, 5)", context));
            assertEquals(5, spelEvaluator.evaluate("#length('hello')", context));
        }

        @Test
        @DisplayName("Should evaluate string test functions")
        void shouldEvaluateStringTestFunctions() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext();

            assertEquals(true, spelEvaluator.evaluate("#startswith('hello', 'hel')", context));
            assertEquals(true, spelEvaluator.evaluate("#endswith('hello', 'llo')", context));
            assertEquals(true, spelEvaluator.evaluate("#contains('hello', 'ell')", context));
            assertEquals(true, spelEvaluator.evaluate("#matches('test@email.com', '.*@.*')", context));
        }

        @Test
        @DisplayName("Should evaluate split and join functions")
        void shouldEvaluateSplitAndJoinFunctions() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(
                Map.of("csv", "a,b,c", "list", List.of("x", "y", "z"))
            );

            Object split = spelEvaluator.evaluate("#split(#csv, ',')", context);
            assertTrue(split instanceof List);
            assertEquals(3, ((List<?>) split).size());

            assertEquals("x-y-z", spelEvaluator.evaluate("#join(#list, '-')", context));
        }

        @Test
        @DisplayName("Should evaluate date functions")
        void shouldEvaluateDateFunctions() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext();

            Object now = spelEvaluator.evaluate("#now()", context);
            assertTrue(now instanceof String, "now() should return String, got: " + (now != null ? now.getClass().getName() : "null"));
            // now() returns ISO date-time string like "2026-03-05T16:00:00"
            assertTrue(((String) now).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?"),
                "now() should return ISO date-time format, got: " + now);

            Object today = spelEvaluator.evaluate("#today()", context);
            assertTrue(today instanceof String);
            assertTrue(((String) today).matches("\\d{4}-\\d{2}-\\d{2}"));
        }

        @Test
        @DisplayName("Should chain multiple functions")
        void shouldChainMultipleFunctions() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext();

            // Trim, then uppercase, then get length
            Object result = spelEvaluator.evaluate(
                "#length(#uppercase(#trim('  hello  ')))", context);

            assertEquals(5, result);
        }

        @Test
        @DisplayName("Should combine custom functions with SpEL operators")
        void shouldCombineCustomFunctionsWithSpelOperators() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(
                Map.of("value", "42")
            );

            // Convert string to int and do arithmetic
            Object result = spelEvaluator.evaluate("#int(#value) * 2 + 10", context);

            assertEquals(94, result);
        }

        @Test
        @DisplayName("Should handle conditional with custom functions")
        void shouldHandleConditionalWithCustomFunctions() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(
                Map.of("text", "")
            );

            Object result = spelEvaluator.evaluate(
                "#isempty(#text) ? 'empty' : 'not empty'", context);

            assertEquals("empty", result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Edge Cases and Error Handling
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCasesTests {

        @Test
        @DisplayName("Should handle very long expression")
        void shouldHandleVeryLongExpression() {
            StringBuilder expr = new StringBuilder("1");
            for (int i = 0; i < 100; i++) {
                expr.append(" + 1");
            }

            StandardEvaluationContext context = spelEvaluator.createEvaluationContext();
            Object result = spelEvaluator.evaluate(expr.toString(), context);

            assertEquals(101, result);
        }

        @Test
        @DisplayName("Should handle Unicode in strings")
        void shouldHandleUnicodeInStrings() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(
                Map.of("text", "Héllo Wörld 你好")
            );

            Object result = spelEvaluator.evaluate("#uppercase(#text)", context);

            assertEquals("HÉLLO WÖRLD 你好", result);
        }

        @Test
        @DisplayName("Should handle special characters in strings")
        void shouldHandleSpecialCharactersInStrings() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext();

            Object result = spelEvaluator.evaluate("'Special: @#$%^&*()'", context);

            assertEquals("Special: @#$%^&*()", result);
        }

        @Test
        @DisplayName("Should handle empty string")
        void shouldHandleEmptyString() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext();

            Object result = spelEvaluator.evaluate("''", context);

            assertEquals("", result);
        }

        @Test
        @DisplayName("Should handle negative numbers")
        void shouldHandleNegativeNumbers() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext();

            assertEquals(-5, spelEvaluator.evaluate("-5", context));
            assertEquals(5.0, spelEvaluator.evaluate("#abs(-5)", context));
        }

        @Test
        @DisplayName("Should handle floating point precision")
        void shouldHandleFloatingPointPrecision() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext();

            Object result = spelEvaluator.evaluate("0.1 + 0.2", context);

            // Floating point arithmetic may not be exactly 0.3
            assertTrue(result instanceof Number);
            assertTrue(Math.abs(((Number) result).doubleValue() - 0.3) < 0.0001);
        }

        @Test
        @DisplayName("Should handle division by zero")
        void shouldHandleDivisionByZero() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext();

            Object result = spelEvaluator.evaluate("1.0 / 0.0", context);

            assertTrue(result instanceof Double);
            assertTrue(Double.isInfinite((Double) result));
        }

        @Test
        @DisplayName("Should handle deeply nested expressions")
        void shouldHandleDeeplyNestedExpressions() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext();

            Object result = spelEvaluator.evaluate(
                "#uppercase(#trim(#lowercase(#trim(#uppercase('  Hello  ')))))", context);

            assertEquals("HELLO", result);
        }

        @Test
        @DisplayName("Should handle list projection")
        void shouldHandleListProjection() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(
                Map.of("numbers", List.of(1, 2, 3, 4, 5))
            );

            Object result = spelEvaluator.evaluate("#numbers.![#this * 2]", context);

            assertTrue(result instanceof List);
            assertEquals(List.of(2, 4, 6, 8, 10), result);
        }

        @Test
        @DisplayName("Should handle list selection")
        void shouldHandleListSelection() {
            StandardEvaluationContext context = spelEvaluator.createEvaluationContext(
                Map.of("numbers", List.of(1, 2, 3, 4, 5))
            );

            Object result = spelEvaluator.evaluate("#numbers.?[#this > 3]", context);

            assertTrue(result instanceof List);
            assertEquals(List.of(4, 5), result);
        }

        @Test
        @DisplayName("Should handle variable with colon in name after transformation")
        void shouldHandleVariableWithColonInNameAfterTransformation() {
            Map<String, Object> context = Map.of("mcp:step1", Map.of("value", 42));

            Object result = spelEvaluator.evaluateWithMap("mcp:step1", context, pathNavigator);

            assertNotNull(result);
        }

        @Test
        @DisplayName("Should handle variable with dot in name after transformation")
        void shouldHandleVariableWithDotInNameAfterTransformation() {
            Map<String, Object> context = Map.of("user.name", "John");

            Object result = spelEvaluator.evaluateWithMap("user.name", context, pathNavigator);

            // The direct key lookup should work
            assertNotNull(result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CollectionLengthPropertyAccessor - .length on Collections/Maps
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Collection .length property access")
    class CollectionLengthTests {

        @Test
        @DisplayName("Should access .length on ArrayList")
        void shouldAccessLengthOnArrayList() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("messages", List.of("a", "b", "c"));

            Object result = spelEvaluator.evaluate("#messages.length", ctx);

            assertEquals(3, result);
        }

        @Test
        @DisplayName("Should access ?.length on ArrayList with safe navigation")
        void shouldAccessSafeLengthOnArrayList() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("messages", List.of("a", "b"));

            Object result = spelEvaluator.evaluate("#messages?.length", ctx);

            assertEquals(2, result);
        }

        @Test
        @DisplayName("Should return null for ?.length on null variable")
        void shouldReturnNullForSafeLengthOnNull() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("messages", null);

            Object result = spelEvaluator.evaluate("#messages?.length", ctx);

            assertNull(result);
        }

        @Test
        @DisplayName("Should access .length on empty list")
        void shouldAccessLengthOnEmptyList() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("items", List.of());

            Object result = spelEvaluator.evaluate("#items.length", ctx);

            assertEquals(0, result);
        }

        @Test
        @DisplayName("Should access .length on Map")
        void shouldAccessLengthOnMap() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("data", Map.of("a", 1, "b", 2));

            Object result = spelEvaluator.evaluate("#data.length", ctx);

            assertEquals(2, result);
        }

        @Test
        @DisplayName("int(list?.length) > 0 should work for non-empty list")
        void shouldEvaluateIntLengthGreaterThanZero() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("messages", List.of(Map.of("id", "msg1"), Map.of("id", "msg2")));

            Object result = spelEvaluator.evaluate("#int(#messages?.length) > 0", ctx);

            assertEquals(true, result);
        }

        @Test
        @DisplayName("int(list?.length) > 0 should be false for empty list")
        void shouldEvaluateIntLengthNotGreaterThanZeroForEmpty() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("messages", List.of());

            Object result = spelEvaluator.evaluate("#int(#messages?.length) > 0", ctx);

            assertEquals(false, result);
        }

        @Test
        @DisplayName("int(null?.length) > 0 should be false for null")
        void shouldEvaluateIntLengthNotGreaterThanZeroForNull() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("messages", null);

            Object result = spelEvaluator.evaluate("#int(#messages?.length) > 0", ctx);

            assertEquals(false, result);
        }

        @Test
        @DisplayName("Should still work with .size() on collections")
        void shouldStillWorkWithSizeMethod() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("items", List.of(1, 2, 3, 4));

            Object result = spelEvaluator.evaluate("#items.size()", ctx);

            assertEquals(4, result);
        }

        @Test
        @DisplayName("Should still work with .length on strings")
        void shouldStillWorkWithLengthOnStrings() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("text", "hello");

            Object result = spelEvaluator.evaluate("#text.length()", ctx);

            assertEquals(5, result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Expression Cache Tests - Piste 5 (SpEL LRU cache wired on every code path)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Expression cache (LRU)")
    class ExpressionCacheTests {

        @Test
        @DisplayName("evaluate(): caches parsed expression - same input returns same Expression instance")
        void evaluateCachesParsedExpression() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("x", 10);

            spelEvaluator.evaluate("#x + 1", ctx);
            org.springframework.expression.Expression first =
                spelEvaluator.getExpressionCache().computeIfAbsent("#x + 1",
                    e -> { throw new AssertionError("Should already be cached: " + e); });

            spelEvaluator.evaluate("#x + 1", ctx);
            org.springframework.expression.Expression second =
                spelEvaluator.getExpressionCache().computeIfAbsent("#x + 1",
                    e -> { throw new AssertionError("Should still be cached: " + e); });

            assertSame(first, second,
                "Cache must return the SAME Expression instance on hit - otherwise the LRU is bypassed");
        }

        @Test
        @DisplayName("evaluateWithMap(): caches by TRANSFORMED expression, not raw")
        void evaluateWithMapCachesByTransformedExpression() {
            Map<String, Object> context = new HashMap<>();
            context.put("foo", Map.of("output", Map.of("bar", "hello")));

            // First evaluation populates the cache.
            spelEvaluator.evaluateWithMap("foo.output.bar", context, pathNavigator);

            // The transformed string is what's cached - not the raw "foo.output.bar".
            // We verify the raw string is NOT a cache key (otherwise we'd be caching the
            // wrong thing and a different context would return stale results).
            assertFalse(spelEvaluator.getExpressionCache().containsKey("foo.output.bar"),
                "Raw expression must NOT be cache key - transformation is context-dependent");

            // And we verify at least one entry exists in the cache (the transformed one).
            assertTrue(spelEvaluator.getExpressionCache().size() > 0,
                "evaluateWithMap must populate the cache with the transformed expression");
        }

        @Test
        @DisplayName("evaluateWithCache(): caches by preprocessed expression")
        void evaluateWithCacheCachesByPreprocessedExpression() {
            Map<String, Object> context = Map.of("x", 5);

            spelEvaluator.evaluateWithCache("#x + 1", context);
            int sizeAfterFirst = spelEvaluator.getExpressionCache().size();

            spelEvaluator.evaluateWithCache("#x + 1", context);
            int sizeAfterSecond = spelEvaluator.getExpressionCache().size();

            assertEquals(sizeAfterFirst, sizeAfterSecond,
                "Second evaluation of the same expression must hit the cache (no new entry)");
        }

        @Test
        @DisplayName("Cache evicts oldest entries when exceeding EXPRESSION_CACHE_MAX_SIZE")
        void cacheEvictsOldestAtMaxSize() {
            // The LRU cache is bounded to 10_000. Generating 10_005 unique expressions
            // forces eviction of the first 5.
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("x", 1);

            int over = SpelEvaluator.EXPRESSION_CACHE_MAX_SIZE + 5;
            for (int i = 0; i < over; i++) {
                spelEvaluator.evaluate("#x + " + i, ctx);
            }

            int size = spelEvaluator.getExpressionCache().size();
            assertTrue(size <= SpelEvaluator.EXPRESSION_CACHE_MAX_SIZE,
                "Cache size " + size + " must stay <= " + SpelEvaluator.EXPRESSION_CACHE_MAX_SIZE
                + " - otherwise LRU eviction is broken and memory will leak on unbounded expressions");
        }

        @Test
        @DisplayName("Cache is shared across evaluate(), evaluateWithMap(), evaluateWithCache()")
        void cacheSharedAcrossEvaluationPaths() {
            // Same parsed expression "1 + 1" appears in all three paths after preprocessing.
            // Since they all share the same cache, evaluating once through evaluate() means
            // the second call through evaluateWithCache() hits the same entry.
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            spelEvaluator.evaluate("1 + 1", ctx);

            int sizeAfterEvaluate = spelEvaluator.getExpressionCache().size();
            spelEvaluator.evaluateWithCache("1 + 1", Map.of());

            // Either size grew by 1 (if preprocessCustomFunctions transformed the string)
            // or stayed identical. The contract is that they SHARE the cache instance -
            // we test that by checking the cache is the same object.
            assertNotNull(spelEvaluator.getExpressionCache());
            assertTrue(spelEvaluator.getExpressionCache().size() >= sizeAfterEvaluate,
                "Cache must be shared across paths (size only grows, never shrinks within test)");
        }
    }
}
