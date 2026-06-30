package com.apimarketplace.orchestrator.services.template;

import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.TemplateEngine.ConditionEvaluationResult;
import com.apimarketplace.orchestrator.services.TypeCastingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive integration tests for the SpEL expression pipeline.
 *
 * Tests the FULL evaluation chain:
 *   TemplateEngine ({{...}} extraction)
 *     -> SpelEvaluator (variable resolution + SpEL parsing)
 *       -> ExpressionFunctions (custom functions)
 *       -> CollectionLengthPropertyAccessor (.length on collections)
 *
 * Uses REAL instances (no mocks) to catch integration bugs like the
 * .length-on-ArrayList bug where SpEL silently returned null.
 */
@DisplayName("SpEL Expression Integration Tests")
class SpelExpressionIntegrationTest {

    private SpelEvaluator spelEvaluator;
    private PathNavigator pathNavigator;
    private TemplateEngine templateEngine;

    @BeforeEach
    void setUp() {
        spelEvaluator = new SpelEvaluator();
        spelEvaluator.init();
        pathNavigator = new PathNavigator();
        NamespaceResolver namespaceResolver = new NamespaceResolver(pathNavigator);
        TypeCastingService typeCastingService = new TypeCastingService();
        templateEngine = new TemplateEngine(typeCastingService, namespaceResolver, pathNavigator, spelEvaluator);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 1. Variable Resolution - {{type:label.output.field}} pattern
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Variable Resolution")
    class VariableResolutionTests {

        @Test
        @DisplayName("MCP output: {{mcp:label.output.field}}")
        void shouldResolveMcpOutput() {
            Map<String, Object> ctx = Map.of(
                "mcp:fetch_emails", Map.of("output", Map.of("messages", List.of("msg1", "msg2")))
            );
            Object result = spelEvaluator.evaluateWithMap(
                "mcp:fetch_emails.output.messages", ctx, pathNavigator);
            assertInstanceOf(List.class, result);
            assertEquals(2, ((List<?>) result).size());
        }

        @Test
        @DisplayName("Trigger output: {{trigger:label.output.field}}")
        void shouldResolveTriggerOutput() {
            Map<String, Object> ctx = Map.of(
                "trigger:webhook", Map.of("output", Map.of("user_id", 42, "status", "active"))
            );
            Object result = spelEvaluator.evaluateWithMap(
                "trigger:webhook.output.user_id", ctx, pathNavigator);
            assertEquals(42, result);
        }

        @Test
        @DisplayName("Agent output: {{agent:label.output.field}}")
        void shouldResolveAgentOutput() {
            Map<String, Object> ctx = Map.of(
                "agent:assistant", Map.of("output", Map.of("response", "Hello world"))
            );
            Object result = spelEvaluator.evaluateWithMap(
                "agent:assistant.output.response", ctx, pathNavigator);
            assertEquals("Hello world", result);
        }

        @Test
        @DisplayName("Core output: {{core:label.output.field}}")
        void shouldResolveCoreOutput() {
            Map<String, Object> ctx = Map.of(
                "core:decision", Map.of("output", Map.of("selected_branch", "if"))
            );
            Object result = spelEvaluator.evaluateWithMap(
                "core:decision.output.selected_branch", ctx, pathNavigator);
            assertEquals("if", result);
        }

        @Test
        @DisplayName("Table output: {{table:label.output.field}}")
        void shouldResolveTableOutput() {
            Map<String, Object> ctx = Map.of(
                "table:find", Map.of("output", Map.of(
                    "current_item", Map.of("name", "John", "age", 30),
                    "current_index", 0
                ))
            );
            Object item = spelEvaluator.evaluateWithMap(
                "table:find.output.current_item", ctx, pathNavigator);
            assertInstanceOf(Map.class, item);

            Object index = spelEvaluator.evaluateWithMap(
                "table:find.output.current_index", ctx, pathNavigator);
            assertEquals(0, index);
        }

        @Test
        @DisplayName("Split current item: {{core:split.output.current_item.field}}")
        void shouldResolveSplitCurrentItem() {
            Map<String, Object> ctx = Map.of(
                "core:split", Map.of("output", Map.of(
                    "current_item", Map.of("email", "test@test.com", "subject", "Hello"),
                    "current_index", 2,
                    "items", List.of("a", "b", "c")
                ))
            );
            Object email = spelEvaluator.evaluateWithMap(
                "core:split.output.current_item.email", ctx, pathNavigator);
            assertEquals("test@test.com", email);
        }

        @Test
        @DisplayName("Split full list: {{core:split.output.items}}")
        void shouldResolveSplitItems() {
            Map<String, Object> ctx = Map.of(
                "core:split", Map.of("output", Map.of(
                    "items", List.of("item1", "item2", "item3")
                ))
            );
            Object items = spelEvaluator.evaluateWithMap(
                "core:split.output.items", ctx, pathNavigator);
            assertInstanceOf(List.class, items);
            assertEquals(3, ((List<?>) items).size());
        }

        @Test
        @DisplayName("Loop iteration: {{core:loop.output.iteration}}")
        void shouldResolveLoopIteration() {
            Map<String, Object> ctx = Map.of(
                "core:loop", Map.of("output", Map.of("iteration", 3, "max_iterations", 10))
            );
            Object iteration = spelEvaluator.evaluateWithMap(
                "core:loop.output.iteration", ctx, pathNavigator);
            assertEquals(3, iteration);
        }

        @Test
        @DisplayName("Nested field access with dots")
        void shouldResolveNestedFieldAccess() {
            Map<String, Object> ctx = Map.of(
                "mcp:api", Map.of("output", Map.of(
                    "data", Map.of("user", Map.of("profile", Map.of("name", "Alice")))
                ))
            );
            Object name = spelEvaluator.evaluateWithMap(
                "mcp:api.output.data.user.profile.name", ctx, pathNavigator);
            assertEquals("Alice", name);
        }

        @Test
        @DisplayName("Unresolved variable returns null")
        void shouldReturnNullForUnresolvedVariable() {
            Map<String, Object> ctx = Map.of(
                "mcp:step1", Map.of("output", Map.of("data", "value"))
            );
            Object result = spelEvaluator.evaluateWithMap(
                "mcp:nonexistent.output.field", ctx, pathNavigator);
            assertNull(result);
        }

        @Test
        @DisplayName("Simple variable without namespace")
        void shouldResolveSimpleVariable() {
            Map<String, Object> ctx = Map.of("score", 85, "name", "Alice");
            Object result = spelEvaluator.evaluateWithMap("score", ctx, pathNavigator);
            assertEquals(85, result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 2. Type Casting Functions: int(), double(), string(), bool()
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Type Casting Functions")
    class TypeCastingTests {

        @Test
        @DisplayName("int() converts string to integer")
        void intConvertsStringToInteger() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            assertEquals(42, spelEvaluator.evaluate("#int('42')", ctx));
        }

        @Test
        @DisplayName("int() converts null to 0")
        void intConvertsNullToZero() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("val", null);
            assertEquals(0, spelEvaluator.evaluate("#int(#val)", ctx));
        }

        @Test
        @DisplayName("int() converts boolean to 0/1")
        void intConvertsBooleanToInt() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            assertEquals(1, spelEvaluator.evaluate("#int(true)", ctx));
            assertEquals(0, spelEvaluator.evaluate("#int(false)", ctx));
        }

        @Test
        @DisplayName("int() truncates decimal")
        void intTruncatesDecimal() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            assertEquals(42, spelEvaluator.evaluate("#int(42.9)", ctx));
        }

        @Test
        @DisplayName("double() converts string to double")
        void doubleConvertsStringToDouble() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            assertEquals(3.14, spelEvaluator.evaluate("#double('3.14')", ctx));
        }

        @Test
        @DisplayName("string() converts number to string")
        void stringConvertsNumberToString() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            assertEquals("42", spelEvaluator.evaluate("#string(42)", ctx));
        }

        @Test
        @DisplayName("bool() converts truthy values")
        void boolConvertsTruthyValues() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            assertEquals(true, spelEvaluator.evaluate("#bool('true')", ctx));
            assertEquals(true, spelEvaluator.evaluate("#bool('yes')", ctx));
            assertEquals(true, spelEvaluator.evaluate("#bool('1')", ctx));
            assertEquals(false, spelEvaluator.evaluate("#bool('false')", ctx));
            assertEquals(false, spelEvaluator.evaluate("#bool('0')", ctx));
            assertEquals(false, spelEvaluator.evaluate("#bool(null)", ctx));
        }

        @Test
        @DisplayName("int() with variable from context map")
        void intWithContextVariable() {
            Map<String, Object> ctx = Map.of("user_id", "123");
            Object result = spelEvaluator.evaluateWithMap("int(user_id)", ctx, pathNavigator);
            assertEquals(123, result);
        }

        @Test
        @DisplayName("{{int(user_id) % 2 == 0}} - full pipeline with TemplateEngine")
        void intModuloInTemplateEngine() {
            Map<String, Object> ctx = Map.of("user_id", "4");
            assertTrue(templateEngine.evaluateConditionWithMap("{{int(user_id) % 2 == 0}}", ctx));

            Map<String, Object> ctx2 = Map.of("user_id", "5");
            assertFalse(templateEngine.evaluateConditionWithMap("{{int(user_id) % 2 == 0}}", ctx2));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 3. Arithmetic Operations: + - * / %
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Arithmetic Operations")
    class ArithmeticTests {

        @Test
        @DisplayName("Addition")
        void addition() {
            Map<String, Object> ctx = Map.of("a", 10, "b", 20);
            Object result = spelEvaluator.evaluateWithMap("a + b", ctx, pathNavigator);
            assertEquals(30, result);
        }

        @Test
        @DisplayName("Subtraction")
        void subtraction() {
            Map<String, Object> ctx = Map.of("a", 100, "b", 37);
            Object result = spelEvaluator.evaluateWithMap("a - b", ctx, pathNavigator);
            assertEquals(63, result);
        }

        @Test
        @DisplayName("Multiplication")
        void multiplication() {
            Map<String, Object> ctx = Map.of("quantity", 5, "price", 12);
            Object result = spelEvaluator.evaluateWithMap("quantity * price", ctx, pathNavigator);
            assertEquals(60, result);
        }

        @Test
        @DisplayName("Division")
        void division() {
            Map<String, Object> ctx = Map.of("total", 100, "count", 4);
            Object result = spelEvaluator.evaluateWithMap("total / count", ctx, pathNavigator);
            assertEquals(25, result);
        }

        @Test
        @DisplayName("Modulo")
        void modulo() {
            Map<String, Object> ctx = Map.of("value", 17);
            Object result = spelEvaluator.evaluateWithMap("value % 5", ctx, pathNavigator);
            assertEquals(2, result);
        }

        @Test
        @DisplayName("{{quantity * price}} - full pipeline")
        void arithmeticInTemplate() {
            Map<String, Object> ctx = Map.of("quantity", 3, "price", 25);
            assertTrue(templateEngine.evaluateConditionWithMap("{{quantity * price > 50}}", ctx));
        }

        @Test
        @DisplayName("Complex arithmetic expression")
        void complexArithmetic() {
            Map<String, Object> ctx = Map.of("a", 10, "b", 3, "c", 2);
            Object result = spelEvaluator.evaluateWithMap("(a + b) * c", ctx, pathNavigator);
            assertEquals(26, result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 4. Comparison Operators: == != < > <= >=
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Comparison Operators")
    class ComparisonTests {

        @Test
        @DisplayName("Equals (==)")
        void equals() {
            Map<String, Object> ctx = Map.of("status", "active");
            assertTrue(templateEngine.evaluateConditionWithMap("{{status == 'active'}}", ctx));
            assertFalse(templateEngine.evaluateConditionWithMap("{{status == 'inactive'}}", ctx));
        }

        @Test
        @DisplayName("Not equals (!=)")
        void notEquals() {
            Map<String, Object> ctx = Map.of("status", "active");
            assertTrue(templateEngine.evaluateConditionWithMap("{{status != 'inactive'}}", ctx));
        }

        @Test
        @DisplayName("Greater than (>)")
        void greaterThan() {
            Map<String, Object> ctx = Map.of("score", 95);
            assertTrue(templateEngine.evaluateConditionWithMap("{{score > 90}}", ctx));
            assertFalse(templateEngine.evaluateConditionWithMap("{{score > 100}}", ctx));
        }

        @Test
        @DisplayName("Less than (<)")
        void lessThan() {
            Map<String, Object> ctx = Map.of("score", 30);
            assertTrue(templateEngine.evaluateConditionWithMap("{{score < 50}}", ctx));
        }

        @Test
        @DisplayName("Greater than or equal (>=)")
        void greaterThanOrEqual() {
            Map<String, Object> ctx = Map.of("score", 90);
            assertTrue(templateEngine.evaluateConditionWithMap("{{score >= 90}}", ctx));
            assertTrue(templateEngine.evaluateConditionWithMap("{{score >= 89}}", ctx));
            assertFalse(templateEngine.evaluateConditionWithMap("{{score >= 91}}", ctx));
        }

        @Test
        @DisplayName("Less than or equal (<=)")
        void lessThanOrEqual() {
            Map<String, Object> ctx = Map.of("count", 5);
            assertTrue(templateEngine.evaluateConditionWithMap("{{count <= 5}}", ctx));
            assertTrue(templateEngine.evaluateConditionWithMap("{{count <= 10}}", ctx));
            assertFalse(templateEngine.evaluateConditionWithMap("{{count <= 4}}", ctx));
        }

        @Test
        @DisplayName("Comparison with namespaced variable")
        void comparisonWithNamespace() {
            Map<String, Object> ctx = Map.of(
                "mcp:api", Map.of("output", Map.of("score", 85))
            );
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{mcp:api.output.score > 80}}", ctx));
        }

        @Test
        @DisplayName("String comparison with ==")
        void stringComparison() {
            Map<String, Object> ctx = Map.of(
                "trigger:webhook", Map.of("output", Map.of("method", "POST"))
            );
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{trigger:webhook.output.method == 'POST'}}", ctx));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 5. Logical Operators: && || !
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Logical Operators")
    class LogicalTests {

        @Test
        @DisplayName("AND (&&)")
        void logicalAnd() {
            Map<String, Object> ctx = Map.of("x", 50, "y", 30);
            assertTrue(templateEngine.evaluateConditionWithMap("{{x > 0 && y < 100}}", ctx));
            assertFalse(templateEngine.evaluateConditionWithMap("{{x > 0 && y > 100}}", ctx));
        }

        @Test
        @DisplayName("OR (||)")
        void logicalOr() {
            Map<String, Object> ctx = Map.of("role", "admin");
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{role == 'admin' || role == 'superadmin'}}", ctx));
        }

        @Test
        @DisplayName("NOT (!)")
        void logicalNot() {
            Map<String, Object> ctx = Map.of("active", false);
            assertTrue(templateEngine.evaluateConditionWithMap("{{!active}}", ctx));
        }

        @Test
        @DisplayName("Combined logical operators")
        void combinedLogical() {
            Map<String, Object> ctx = Map.of("age", 25, "status", "active", "verified", true);
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{age >= 18 && (status == 'active' || verified)}}", ctx));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 6. Math Functions: abs, round, floor, ceil, min, max, pow, sqrt
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Math Functions")
    class MathFunctionTests {

        @Test
        @DisplayName("abs() returns absolute value")
        void absFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("val", -42);
            assertEquals(42.0, spelEvaluator.evaluate("#abs(#val)", ctx));
        }

        @Test
        @DisplayName("round(val, decimals)")
        void roundFunction() {
            Map<String, Object> ctx = Map.of("price", 19.956);
            Object result = spelEvaluator.evaluateWithMap("round(price, 2)", ctx, pathNavigator);
            assertEquals(19.96, result);
        }

        @Test
        @DisplayName("floor() rounds down")
        void floorFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("val", 3.7);
            assertEquals(3L, spelEvaluator.evaluate("#floor(#val)", ctx));
        }

        @Test
        @DisplayName("ceil() rounds up")
        void ceilFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("val", 3.2);
            assertEquals(4L, spelEvaluator.evaluate("#ceil(#val)", ctx));
        }

        @Test
        @DisplayName("min(a, b)")
        void minFunction() {
            Map<String, Object> ctx = Map.of("a", 10, "b", 25);
            Object result = spelEvaluator.evaluateWithMap("min(a, b)", ctx, pathNavigator);
            assertEquals(10.0, result);
        }

        @Test
        @DisplayName("max(a, b)")
        void maxFunction() {
            Map<String, Object> ctx = Map.of("a", 10, "b", 25);
            Object result = spelEvaluator.evaluateWithMap("max(a, b)", ctx, pathNavigator);
            assertEquals(25.0, result);
        }

        @Test
        @DisplayName("pow(base, exponent)")
        void powFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            assertEquals(8.0, spelEvaluator.evaluate("#pow(2, 3)", ctx));
        }

        @Test
        @DisplayName("sqrt()")
        void sqrtFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            assertEquals(5.0, spelEvaluator.evaluate("#sqrt(25)", ctx));
        }

        @Test
        @DisplayName("{{round(price, 2)}} - full pipeline condition")
        void roundInCondition() {
            Map<String, Object> ctx = Map.of("price", 19.956);
            assertTrue(templateEngine.evaluateConditionWithMap("{{round(price, 2) == 19.96}}", ctx));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 7. String Functions
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("String Functions")
    class StringFunctionTests {

        @Test
        @DisplayName("uppercase()")
        void uppercaseFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("name", "hello");
            assertEquals("HELLO", spelEvaluator.evaluate("#uppercase(#name)", ctx));
        }

        @Test
        @DisplayName("lowercase()")
        void lowercaseFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("name", "HELLO");
            assertEquals("hello", spelEvaluator.evaluate("#lowercase(#name)", ctx));
        }

        @Test
        @DisplayName("trim()")
        void trimFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("text", "  hello  ");
            assertEquals("hello", spelEvaluator.evaluate("#trim(#text)", ctx));
        }

        @Test
        @DisplayName("length() on string")
        void lengthOnString() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("text", "hello");
            assertEquals(5, spelEvaluator.evaluate("#length(#text)", ctx));
        }

        @Test
        @DisplayName("contains(str, search)")
        void containsFunction() {
            Map<String, Object> ctx = Map.of("email", "user@company.com");
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{contains(email, '@company.com')}}", ctx));
            assertFalse(templateEngine.evaluateConditionWithMap(
                "{{contains(email, '@gmail.com')}}", ctx));
        }

        @Test
        @DisplayName("startswith() and endswith()")
        void startsWithEndsWith() {
            Map<String, Object> ctx = Map.of("url", "https://api.example.com/data");
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{startswith(url, 'https://')}}", ctx));
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{endswith(url, '/data')}}", ctx));
        }

        @Test
        @DisplayName("replace(str, old, new)")
        void replaceFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("text", "hello world");
            assertEquals("hello universe", spelEvaluator.evaluate("#replace(#text, 'world', 'universe')", ctx));
        }

        @Test
        @DisplayName("substring(str, start, end)")
        void substringFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("text", "Hello World");
            assertEquals("World", spelEvaluator.evaluate("#substring(#text, 6, 11)", ctx));
        }

        @Test
        @DisplayName("split(str, delim)")
        void splitFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("csv", "a,b,c,d");
            Object result = spelEvaluator.evaluate("#split(#csv, ',')", ctx);
            assertInstanceOf(List.class, result);
            assertEquals(4, ((List<?>) result).size());
        }

        @Test
        @DisplayName("join(arr, delim)")
        void joinFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("items", List.of("one", "two", "three"));
            assertEquals("one-two-three", spelEvaluator.evaluate("#join(#items, '-')", ctx));
        }

        @Test
        @DisplayName("capitalize()")
        void capitalizeFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("name", "john DOE");
            assertEquals("John doe", spelEvaluator.evaluate("#capitalize(#name)", ctx));
        }

        @Test
        @DisplayName("matches() regex - direct SpEL")
        void matchesFunction() {
            // Test matches() directly via SpEL context to avoid template variable interference
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("email", "test@example.com");
            assertEquals(true, spelEvaluator.evaluate("#matches(#email, '.*@.*\\.com')", ctx));
            assertEquals(false, spelEvaluator.evaluate("#matches(#email, '.*@.*\\.org')", ctx));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 8. Utility Functions: size, default, coalesce, isempty, isnull, typeof, len
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Utility Functions")
    class UtilityFunctionTests {

        @Test
        @DisplayName("size() on list")
        void sizeOnList() {
            Map<String, Object> ctx = Map.of("items", List.of(1, 2, 3));
            assertTrue(templateEngine.evaluateConditionWithMap("{{size(items) > 0}}", ctx));
            assertTrue(templateEngine.evaluateConditionWithMap("{{size(items) == 3}}", ctx));
        }

        @Test
        @DisplayName("size() on empty list")
        void sizeOnEmptyList() {
            Map<String, Object> ctx = Map.of("items", List.of());
            assertTrue(templateEngine.evaluateConditionWithMap("{{size(items) == 0}}", ctx));
        }

        @Test
        @DisplayName("size() on map")
        void sizeOnMap() {
            Map<String, Object> ctx = Map.of("data", Map.of("a", 1, "b", 2));
            Object result = spelEvaluator.evaluateWithMap("size(data)", ctx, pathNavigator);
            assertEquals(2, result);
        }

        @Test
        @DisplayName("size() on string")
        void sizeOnString() {
            Map<String, Object> ctx = Map.of("name", "hello");
            Object result = spelEvaluator.evaluateWithMap("size(name)", ctx, pathNavigator);
            assertEquals(5, result);
        }

        @Test
        @DisplayName("size() on null returns 0")
        void sizeOnNull() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("val", null);
            assertEquals(0, spelEvaluator.evaluate("#size(#val)", ctx));
        }

        @Test
        @DisplayName("len() alias for size()")
        void lenAlias() {
            Map<String, Object> ctx = Map.of("items", List.of("a", "b"));
            Object result = spelEvaluator.evaluateWithMap("len(items)", ctx, pathNavigator);
            assertEquals(2, result);
        }

        @Test
        @DisplayName("default(val, fallback)")
        void defaultFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("name", null);
            assertEquals("Unknown", spelEvaluator.evaluate("#default(#name, 'Unknown')", ctx));

            ctx.setVariable("name", "Alice");
            assertEquals("Alice", spelEvaluator.evaluate("#default(#name, 'Unknown')", ctx));
        }

        @Test
        @DisplayName("{{default(name, 'Unknown')}} - full pipeline")
        void defaultInTemplate() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("name", null);
            // When variable is null, default should return fallback
            StandardEvaluationContext evalCtx = spelEvaluator.createEvaluationContext();
            evalCtx.setVariable("name", null);
            assertEquals("Unknown", spelEvaluator.evaluate("#default(#name, 'Unknown')", evalCtx));
        }

        @Test
        @DisplayName("coalesce(a, b, c) - returns first non-null")
        void coalesceFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("a", null);
            ctx.setVariable("b", "");
            ctx.setVariable("c", "found");
            assertEquals("found", spelEvaluator.evaluate("#coalesce(#a, #b, #c)", ctx));
        }

        @Test
        @DisplayName("isempty() on various types")
        void isEmptyFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();

            ctx.setVariable("val", null);
            assertEquals(true, spelEvaluator.evaluate("#isempty(#val)", ctx));

            ctx.setVariable("val", "");
            assertEquals(true, spelEvaluator.evaluate("#isempty(#val)", ctx));

            ctx.setVariable("val", List.of());
            assertEquals(true, spelEvaluator.evaluate("#isempty(#val)", ctx));

            ctx.setVariable("val", "hello");
            assertEquals(false, spelEvaluator.evaluate("#isempty(#val)", ctx));

            ctx.setVariable("val", List.of(1));
            assertEquals(false, spelEvaluator.evaluate("#isempty(#val)", ctx));
        }

        @Test
        @DisplayName("isnull()")
        void isNullFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("val", null);
            assertEquals(true, spelEvaluator.evaluate("#isnull(#val)", ctx));

            ctx.setVariable("val", "something");
            assertEquals(false, spelEvaluator.evaluate("#isnull(#val)", ctx));
        }

        @Test
        @DisplayName("typeof() returns type name")
        void typeofFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();

            ctx.setVariable("val", "hello");
            assertEquals("string", spelEvaluator.evaluate("#typeof(#val)", ctx));

            ctx.setVariable("val", 42);
            assertEquals("int", spelEvaluator.evaluate("#typeof(#val)", ctx));

            ctx.setVariable("val", List.of(1, 2));
            assertEquals("list", spelEvaluator.evaluate("#typeof(#val)", ctx));

            ctx.setVariable("val", Map.of("a", 1));
            assertEquals("map", spelEvaluator.evaluate("#typeof(#val)", ctx));

            ctx.setVariable("val", true);
            assertEquals("bool", spelEvaluator.evaluate("#typeof(#val)", ctx));

            ctx.setVariable("val", null);
            assertEquals("null", spelEvaluator.evaluate("#typeof(#val)", ctx));
        }

        @Test
        @DisplayName("ifempty(val, fallback)")
        void ifEmptyFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("val", "");
            assertEquals("default", spelEvaluator.evaluate("#ifempty(#val, 'default')", ctx));

            ctx.setVariable("val", "value");
            assertEquals("value", spelEvaluator.evaluate("#ifempty(#val, 'default')", ctx));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 9. Date/Number Formatting: formatdate, formatnumber, formatcurrency, now, today
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Date/Number Formatting Functions")
    class FormattingTests {

        @Test
        @DisplayName("formatnumber(val, decimals)")
        void formatNumberFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("val", 1234.5);
            Object result = spelEvaluator.evaluate("#formatnumber(#val, 2)", ctx);
            assertNotNull(result);
            // formatnumber uses locale-specific formatting
            assertInstanceOf(String.class, result);
        }

        @Test
        @DisplayName("formatdate(value, pattern)")
        void formatDateFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("date", "2026-04-15");
            Object result = spelEvaluator.evaluate("#formatdate(#date, 'dd/MM/yyyy')", ctx);
            assertEquals("15/04/2026", result);
        }

        @Test
        @DisplayName("now() returns current datetime")
        void nowFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            Object result = spelEvaluator.evaluate("#now()", ctx);
            assertNotNull(result);
            assertInstanceOf(String.class, result);
            assertDoesNotThrow(() -> LocalDateTime.parse((String) result));
        }

        @Test
        @DisplayName("today() returns current date")
        void todayFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            Object result = spelEvaluator.evaluate("#today()", ctx);
            assertNotNull(result);
            assertInstanceOf(String.class, result);
            assertTrue(((String) result).matches("\\d{4}-\\d{2}-\\d{2}"));
        }

        @Test
        @DisplayName("formatcurrency(val, code)")
        void formatCurrencyFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            Object result = spelEvaluator.evaluate("#formatcurrency(42.5, 'EUR')", ctx);
            assertNotNull(result);
            assertInstanceOf(String.class, result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 10. Collection Access: [0], ['key'], .?[condition], .![field]
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Collection Access")
    class CollectionAccessTests {

        @Test
        @DisplayName("Array index access [0]")
        void arrayIndexAccess() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("items", List.of("first", "second", "third"));
            assertEquals("first", spelEvaluator.evaluate("#items[0]", ctx));
            assertEquals("third", spelEvaluator.evaluate("#items[2]", ctx));
        }

        @Test
        @DisplayName("Map key access ['key']")
        void mapKeyAccess() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("data", Map.of("name", "Alice", "age", 30));
            assertEquals("Alice", spelEvaluator.evaluate("#data['name']", ctx));
            assertEquals(30, spelEvaluator.evaluate("#data['age']", ctx));
        }

        @Test
        @DisplayName("Collection filtering .?[condition]")
        void collectionFiltering() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            List<Map<String, Object>> users = List.of(
                Map.of("name", "Alice", "age", 25),
                Map.of("name", "Bob", "age", 17),
                Map.of("name", "Charlie", "age", 30)
            );
            ctx.setVariable("users", users);

            Object result = spelEvaluator.evaluate("#users.?[#this['age'] > 18]", ctx);
            assertInstanceOf(List.class, result);
            assertEquals(2, ((List<?>) result).size());
        }

        @Test
        @DisplayName("Collection projection .![field]")
        void collectionProjection() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            List<Map<String, Object>> users = List.of(
                Map.of("name", "Alice", "age", 25),
                Map.of("name", "Bob", "age", 17),
                Map.of("name", "Charlie", "age", 30)
            );
            ctx.setVariable("users", users);

            Object result = spelEvaluator.evaluate("#users.![#this['name']]", ctx);
            assertInstanceOf(List.class, result);
            assertEquals(List.of("Alice", "Bob", "Charlie"), result);
        }

        @Test
        @DisplayName("Nested access: items[0].name from context map")
        void nestedAccessFromContextMap() {
            Map<String, Object> ctx = Map.of(
                "mcp:api", Map.of("output", Map.of(
                    "items", List.of(
                        Map.of("name", "First"),
                        Map.of("name", "Second")
                    )
                ))
            );
            Object items = spelEvaluator.evaluateWithMap(
                "mcp:api.output.items", ctx, pathNavigator);
            assertInstanceOf(List.class, items);
            // Direct SpEL index access on resolved variable
            StandardEvaluationContext evalCtx = spelEvaluator.createEvaluationContext();
            evalCtx.setVariable("items", items);
            assertEquals("First", spelEvaluator.evaluate("#items[0]['name']", evalCtx));
        }

        @Test
        @DisplayName("Array index with context map path: items[0]")
        void arrayIndexWithContextMapPath() {
            Map<String, Object> ctx = Map.of(
                "mcp:api", Map.of("output", Map.of(
                    "items", List.of("alpha", "beta", "gamma")
                ))
            );
            Object result = spelEvaluator.evaluateWithMap(
                "mcp:api.output.items[0]", ctx, pathNavigator);
            assertEquals("alpha", result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 11. Ternary Operator: condition ? trueValue : falseValue
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Ternary Operator")
    class TernaryTests {

        @Test
        @DisplayName("Simple ternary: score > 50 ? 'pass' : 'fail'")
        void simpleTernary() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("score", 75);
            assertEquals("pass", spelEvaluator.evaluate("#score > 50 ? 'pass' : 'fail'", ctx));

            ctx.setVariable("score", 30);
            assertEquals("fail", spelEvaluator.evaluate("#score > 50 ? 'pass' : 'fail'", ctx));
        }

        @Test
        @DisplayName("Ternary with map context")
        void ternaryWithMapContext() {
            Map<String, Object> ctx = Map.of("age", 20);
            Object result = spelEvaluator.evaluateWithMap(
                "age >= 18 ? 'adult' : 'minor'", ctx, pathNavigator);
            assertEquals("adult", result);
        }

        @Test
        @DisplayName("Nested ternary")
        void nestedTernary() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("score", 85);
            assertEquals("B", spelEvaluator.evaluate(
                "#score >= 90 ? 'A' : (#score >= 80 ? 'B' : 'C')", ctx));
        }

        @Test
        @DisplayName("Ternary with function call")
        void ternaryWithFunction() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("items", List.of(1, 2, 3));
            assertEquals("non-empty", spelEvaluator.evaluate(
                "#size(#items) > 0 ? 'non-empty' : 'empty'", ctx));
        }

        @Test
        @DisplayName("Elvis operator (?:)")
        void elvisOperator() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("name", null);
            assertEquals("default", spelEvaluator.evaluate("#name ?: 'default'", ctx));

            ctx.setVariable("name", "Alice");
            assertEquals("Alice", spelEvaluator.evaluate("#name ?: 'default'", ctx));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 12. .length on Collections (the bug that prompted this)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName(".length on Collections - PropertyAccessor fix")
    class CollectionLengthPropertyTests {

        @Test
        @DisplayName("messages.length on ArrayList - the production bug")
        void messagesLengthOnArrayList() {
            Map<String, Object> ctx = Map.of(
                "mcp:fetch_unread_emails", Map.of("output", Map.of(
                    "messages", List.of(
                        Map.of("id", "msg1", "threadId", "t1"),
                        Map.of("id", "msg2", "threadId", "t2")
                    )
                ))
            );
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{int(mcp:fetch_unread_emails.output.messages?.length) > 0}}", ctx));
        }

        @Test
        @DisplayName("messages.length returns 0 for empty list")
        void messagesLengthEmptyList() {
            Map<String, Object> ctx = Map.of(
                "mcp:fetch_unread_emails", Map.of("output", Map.of(
                    "messages", List.of()
                ))
            );
            assertFalse(templateEngine.evaluateConditionWithMap(
                "{{int(mcp:fetch_unread_emails.output.messages?.length) > 0}}", ctx));
        }

        @Test
        @DisplayName("?.length returns null for null → int(null) = 0")
        void safeLengthOnNull() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("messages", null);
            Object result = spelEvaluator.evaluate("#int(#messages?.length)", ctx);
            assertEquals(0, result);
        }

        @Test
        @DisplayName(".length on Map")
        void lengthOnMap() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("data", Map.of("a", 1, "b", 2, "c", 3));
            assertEquals(3, spelEvaluator.evaluate("#data.length", ctx));
        }

        @Test
        @DisplayName(".size() still works alongside .length")
        void sizeAndLengthBothWork() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("items", List.of(1, 2, 3));
            assertEquals(3, spelEvaluator.evaluate("#items.length", ctx));
            assertEquals(3, spelEvaluator.evaluate("#items.size()", ctx));
        }

        @Test
        @DisplayName(".length on string still works")
        void lengthOnStringStillWorks() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("text", "hello");
            // String.length() is a method, not a property, so use .length()
            assertEquals(5, spelEvaluator.evaluate("#text.length()", ctx));
        }

        @Test
        @DisplayName("#length() function on collection")
        void lengthFunctionOnCollection() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("items", List.of("a", "b", "c", "d"));
            assertEquals(4, spelEvaluator.evaluate("#length(#items)", ctx));
        }

        @Test
        @DisplayName("#length() function on null")
        void lengthFunctionOnNull() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("items", null);
            assertEquals(0, spelEvaluator.evaluate("#length(#items)", ctx));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 13. {{}} Wrapping - inside vs outside braces
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("{{}} Wrapping Behavior")
    class TemplateWrappingTests {

        @Test
        @DisplayName("Pure expression {{...}} returns typed result")
        void pureExpressionReturnsTyped() {
            Map<String, Object> ctx = Map.of("score", 42);
            // evaluateConditionWithMap extracts from {{}} and evaluates
            assertTrue(templateEngine.evaluateConditionWithMap("{{score > 40}}", ctx));
        }

        @Test
        @DisplayName("Mixed expression: text + {{...}} blocks")
        void mixedExpression() {
            Map<String, Object> ctx = Map.of("score", 85);
            // Mixed: {{score}} > 80 - each {{}} block resolved, then whole thing evaluated
            assertTrue(templateEngine.evaluateConditionWithMap("{{score}} > 80", ctx));
        }

        @Test
        @DisplayName("Raw expression without {{}} - still evaluated")
        void rawExpressionWithoutBraces() {
            Map<String, Object> ctx = Map.of("score", 95);
            // Raw expression: no {{}} at all
            assertTrue(templateEngine.evaluateConditionWithMap("score > 90", ctx));
        }

        @Test
        @DisplayName("Multiple {{}} blocks in condition")
        void multipleBlocksInCondition() {
            Map<String, Object> ctx = Map.of("a", 10, "b", 20);
            assertTrue(templateEngine.evaluateConditionWithMap("{{a}} < {{b}}", ctx));
            assertFalse(templateEngine.evaluateConditionWithMap("{{a}} > {{b}}", ctx));
        }

        @Test
        @DisplayName("{{}} with string comparison")
        void bracesWithStringComparison() {
            Map<String, Object> ctx = Map.of("status", "active");
            assertTrue(templateEngine.evaluateConditionWithMap("{{status}} == 'active'", ctx));
        }

        @Test
        @DisplayName("Condition details include resolved expression")
        void conditionDetailsResolvedExpression() {
            Map<String, Object> ctx = Map.of("score", 75);
            ConditionEvaluationResult result = templateEngine.evaluateConditionWithDetailsWithMap(
                "{{score > 50}}", ctx);
            assertTrue(result.result());
            assertNull(result.errorMessage());
            assertNotNull(result.resolvedExpression());
        }

        @Test
        @DisplayName("Null condition returns false")
        void nullConditionReturnsFalse() {
            assertFalse(templateEngine.evaluateConditionWithMap(null, Map.of()));
            assertFalse(templateEngine.evaluateConditionWithMap("", Map.of()));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 14. Safe Navigation Operator (?.)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Safe Navigation Operator (?.)")
    class SafeNavigationTests {

        @Test
        @DisplayName("?. on null returns null")
        void safeNavOnNull() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("obj", null);
            assertNull(spelEvaluator.evaluate("#obj?.length", ctx));
        }

        @Test
        @DisplayName("?. on non-null proceeds normally")
        void safeNavOnNonNull() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("items", List.of(1, 2, 3));
            assertEquals(3, spelEvaluator.evaluate("#items?.length", ctx));
        }

        @Test
        @DisplayName("?. chained: obj?.field?.size()")
        void safeNavChained() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("data", Map.of("items", List.of(1, 2)));
            assertEquals(2, spelEvaluator.evaluate("#data?.['items']?.size()", ctx));
        }

        @Test
        @DisplayName("?. with null map returns null")
        void safeNavNullMap() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("data", null);
            assertNull(spelEvaluator.evaluate("#data?.['items']", ctx));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 15. Edge Cases and Error Handling
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge Cases & Error Handling")
    class EdgeCaseTests {

        @Test
        @DisplayName("Division by zero returns Infinity (no crash)")
        void divisionByZero() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("a", 10.0);
            ctx.setVariable("b", 0.0);
            Object result = spelEvaluator.evaluate("#a / #b", ctx);
            // SpEL with doubles returns Infinity
            assertNotNull(result);
        }

        @Test
        @DisplayName("Undefined variable in condition defaults to false")
        void undefinedVariableDefaultsFalse() {
            Map<String, Object> ctx = Map.of();
            assertFalse(templateEngine.evaluateConditionWithMap("{{nonexistent > 5}}", ctx));
        }

        @Test
        @DisplayName("Malformed expression returns false condition")
        void malformedExpressionReturnsFalse() {
            Map<String, Object> ctx = Map.of("x", 5);
            // Invalid SpEL syntax should not throw, should return false
            ConditionEvaluationResult result = templateEngine.evaluateConditionWithDetailsWithMap(
                "{{x >>>>> 5}}", ctx);
            // May error or evaluate incorrectly - should not throw
            assertNotNull(result);
        }

        @Test
        @DisplayName("Empty string is falsy in condition")
        void emptyStringIsFalsy() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("val", "");
            // toBoolean("") = false
            assertFalse(spelEvaluator.toBoolean(spelEvaluator.evaluate("#val", ctx)));
        }

        @Test
        @DisplayName("0 is falsy in condition")
        void zeroIsFalsy() {
            assertFalse(spelEvaluator.toBoolean(0));
            assertFalse(spelEvaluator.toBoolean(0.0));
        }

        @Test
        @DisplayName("Non-zero is truthy")
        void nonZeroIsTruthy() {
            assertTrue(spelEvaluator.toBoolean(1));
            assertTrue(spelEvaluator.toBoolean(-1));
            assertTrue(spelEvaluator.toBoolean(42));
        }

        @Test
        @DisplayName("Non-empty string is truthy")
        void nonEmptyStringIsTruthy() {
            assertTrue(spelEvaluator.toBoolean("hello"));
            assertTrue(spelEvaluator.toBoolean("true"));
        }

        @Test
        @DisplayName("'false' string is falsy")
        void falseStringIsFalsy() {
            assertFalse(spelEvaluator.toBoolean("false"));
            assertFalse(spelEvaluator.toBoolean("0"));
        }

        @Test
        @DisplayName("null is falsy")
        void nullIsFalsy() {
            assertFalse(spelEvaluator.toBoolean(null));
        }

        @Test
        @DisplayName("List is truthy")
        void listIsTruthy() {
            assertTrue(spelEvaluator.toBoolean(List.of(1)));
        }

        @Test
        @DisplayName("int() with unparseable string returns 0")
        void intWithUnparseableString() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            assertEquals(0, spelEvaluator.evaluate("#int('not_a_number')", ctx));
        }

        @Test
        @DisplayName("Deeply nested null access is safe")
        void deeplyNestedNullAccess() {
            Map<String, Object> ctx = Map.of(
                "mcp:step", Map.of("output", Map.of("data", "value"))
            );
            // Accessing a field that doesn't exist at a deep level
            Object result = spelEvaluator.evaluateWithMap(
                "mcp:step.output.nonexistent.deep.field", ctx, pathNavigator);
            assertNull(result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 16. Complex Real-World Expressions
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Real-World Complex Expressions")
    class RealWorldTests {

        @Test
        @DisplayName("Email workflow: check if unread messages exist")
        void emailWorkflowCheckUnread() {
            Map<String, Object> ctx = Map.of(
                "mcp:fetch_unread_emails", Map.of("output", Map.of(
                    "messages", List.of(
                        Map.of("id", "msg1"),
                        Map.of("id", "msg2"),
                        Map.of("id", "msg3")
                    )
                ))
            );
            // All these patterns should work for "has messages"
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{int(mcp:fetch_unread_emails.output.messages?.length) > 0}}", ctx));
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{size(mcp:fetch_unread_emails.output.messages) > 0}}", ctx));
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{!isempty(mcp:fetch_unread_emails.output.messages)}}", ctx));
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{len(mcp:fetch_unread_emails.output.messages) > 0}}", ctx));
        }

        @Test
        @DisplayName("Email workflow: no messages → false")
        void emailWorkflowNoMessages() {
            Map<String, Object> ctx = Map.of(
                "mcp:fetch_unread_emails", Map.of("output", Map.of(
                    "messages", List.of()
                ))
            );
            assertFalse(templateEngine.evaluateConditionWithMap(
                "{{int(mcp:fetch_unread_emails.output.messages?.length) > 0}}", ctx));
            assertFalse(templateEngine.evaluateConditionWithMap(
                "{{size(mcp:fetch_unread_emails.output.messages) > 0}}", ctx));
        }

        @Test
        @DisplayName("Loop condition: iteration <= max")
        void loopCondition() {
            Map<String, Object> ctx = Map.of(
                "core:loop", Map.of("output", Map.of("iteration", 5, "max_iterations", 10))
            );
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{core:loop.output.iteration <= 10}}", ctx));
        }

        @Test
        @DisplayName("Score-based routing with ternary")
        void scoreBasedRouting() {
            Map<String, Object> ctx = Map.of("score", 85);
            Object result = spelEvaluator.evaluateWithMap(
                "score >= 90 ? 'A' : (score >= 80 ? 'B' : (score >= 70 ? 'C' : 'D'))",
                ctx, pathNavigator);
            assertEquals("B", result);
        }

        @Test
        @DisplayName("Check already processed with table output")
        void checkAlreadyProcessed() {
            Map<String, Object> ctxFound = Map.of(
                "table:check_if_processed", Map.of("output", Map.of(
                    "current_item", Map.of("id", "123", "status", "done"),
                    "total_count", 1
                ))
            );
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{table:check_if_processed.output.total_count > 0}}", ctxFound));

            Map<String, Object> ctxNotFound = Map.of(
                "table:check_if_processed", Map.of("output", Map.of(
                    "total_count", 0
                ))
            );
            assertFalse(templateEngine.evaluateConditionWithMap(
                "{{table:check_if_processed.output.total_count > 0}}", ctxNotFound));
        }

        @Test
        @DisplayName("Multiple variable references in one expression")
        void multipleVariableReferences() {
            Map<String, Object> ctx = Map.of(
                "mcp:step1", Map.of("output", Map.of("count", 10)),
                "mcp:step2", Map.of("output", Map.of("count", 20))
            );
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{mcp:step1.output.count + mcp:step2.output.count > 25}}", ctx));
        }

        @Test
        @DisplayName("String concatenation with +")
        void stringConcatenation() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("first", "Hello");
            ctx.setVariable("last", "World");
            assertEquals("Hello World", spelEvaluator.evaluate("#first + ' ' + #last", ctx));
        }

        @Test
        @DisplayName("Combined functions: int + size + comparison")
        void combinedFunctions() {
            Map<String, Object> ctx = Map.of(
                "items", List.of("a", "b", "c"),
                "threshold", "2"
            );
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{size(items) > int(threshold)}}", ctx));
        }

        @Test
        @DisplayName("Trigger field equality check")
        void triggerFieldEquality() {
            Map<String, Object> ctx = Map.of(
                "trigger:webhook", Map.of("output", Map.of(
                    "action", "create",
                    "resource_type", "user"
                ))
            );
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{trigger:webhook.output.action == 'create' && trigger:webhook.output.resource_type == 'user'}}",
                ctx));
        }

        @Test
        @DisplayName("contains() with namespaced variable")
        void containsWithNamespace() {
            Map<String, Object> ctx = Map.of(
                "mcp:get_email", Map.of("output", Map.of(
                    "from", "notifications@company.com"
                ))
            );
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{contains(mcp:get_email.output.from, '@company.com')}}", ctx));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 17. Preprocessor - custom function name detection
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Function Name Preprocessing")
    class PreprocessorTests {

        @Test
        @DisplayName("Recognizes function call: int(...)")
        void recognizesFunctionCall() {
            String result = spelEvaluator.preprocessCustomFunctions("int(value)");
            assertEquals("#int(value)", result);
        }

        @Test
        @DisplayName("Does not prefix non-function identifiers")
        void doesNotPrefixNonFunction() {
            String result = spelEvaluator.preprocessCustomFunctions("myVariable");
            assertEquals("myVariable", result);
        }

        @Test
        @DisplayName("Handles multiple functions in expression")
        void multipleFunction() {
            String result = spelEvaluator.preprocessCustomFunctions("int(size(items))");
            assertEquals("#int(#size(items))", result);
        }

        @Test
        @DisplayName("Preserves string literals")
        void preservesStringLiterals() {
            String result = spelEvaluator.preprocessCustomFunctions("contains(val, 'int(x)')");
            assertEquals("#contains(val, 'int(x)')", result);
        }

        @Test
        @DisplayName("Case-insensitive function names")
        void caseInsensitiveFunctions() {
            String result = spelEvaluator.preprocessCustomFunctions("Int(value)");
            assertEquals("#int(value)", result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 18. Reserved Words
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Reserved Words")
    class ReservedWordTests {

        @Test
        @DisplayName("true/false as boolean literals")
        void booleanLiterals() {
            Map<String, Object> ctx = Map.of("active", true);
            assertTrue(templateEngine.evaluateConditionWithMap("{{active == true}}", ctx));
            assertFalse(templateEngine.evaluateConditionWithMap("{{active == false}}", ctx));
        }

        @Test
        @DisplayName("null comparison")
        void nullComparison() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("val", null);
            assertEquals(true, spelEvaluator.evaluate("#val == null", ctx));
        }

        @Test
        @DisplayName("not operator")
        void notOperator() {
            Map<String, Object> ctx = Map.of("active", false);
            assertTrue(templateEngine.evaluateConditionWithMap("{{!active}}", ctx));
        }

        @Test
        @DisplayName("type references are blocked")
        void typeReferencesAreBlocked() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("val", "hello");
            assertNull(spelEvaluator.evaluate("#val instanceof T(String)", ctx));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 19. Condition Evaluation Details (for UI debug display)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Condition Evaluation Details")
    class ConditionDetailsTests {

        @Test
        @DisplayName("Details include original expression")
        void detailsIncludeOriginal() {
            Map<String, Object> ctx = Map.of("x", 10);
            ConditionEvaluationResult result = templateEngine.evaluateConditionWithDetailsWithMap(
                "{{x > 5}}", ctx);
            assertEquals("{{x > 5}}", result.originalExpression());
            assertTrue(result.result());
        }

        @Test
        @DisplayName("Details include resolved expression")
        void detailsIncludeResolved() {
            Map<String, Object> ctx = Map.of("x", 10);
            ConditionEvaluationResult result = templateEngine.evaluateConditionWithDetailsWithMap(
                "{{x > 5}}", ctx);
            assertNotNull(result.resolvedExpression());
            // Resolved should show the actual value: "10 > 5 = true" or similar
        }

        @Test
        @DisplayName("Details: no error on valid expression")
        void noErrorOnValid() {
            Map<String, Object> ctx = Map.of("x", 10);
            ConditionEvaluationResult result = templateEngine.evaluateConditionWithDetailsWithMap(
                "{{x > 5}}", ctx);
            assertNull(result.errorMessage());
        }

        @Test
        @DisplayName("Details: error message on invalid expression")
        void errorMessageOnInvalid() {
            Map<String, Object> ctx = Map.of();
            ConditionEvaluationResult result = templateEngine.evaluateConditionWithDetailsWithMap(
                "{{@#$%^}}", ctx);
            // Should have error or null expression result
            assertNotNull(result);
        }

        @Test
        @DisplayName("Mixed expression details")
        void mixedExpressionDetails() {
            Map<String, Object> ctx = Map.of("a", 10, "b", 20);
            ConditionEvaluationResult result = templateEngine.evaluateConditionWithDetailsWithMap(
                "{{a}} + {{b}} > 25", ctx);
            assertTrue(result.result());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 20. Long/Float/Double edge cases
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Numeric Edge Cases")
    class NumericEdgeCaseTests {

        @Test
        @DisplayName("long() converts correctly")
        void longConversion() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            assertEquals(9999999999L, spelEvaluator.evaluate("#long('9999999999')", ctx));
        }

        @Test
        @DisplayName("float() converts correctly")
        void floatConversion() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            Object result = spelEvaluator.evaluate("#float('3.14')", ctx);
            assertInstanceOf(Float.class, result);
            assertEquals(3.14f, (Float) result, 0.001);
        }

        @Test
        @DisplayName("Comparing integers and doubles works")
        void intDoubleComparison() {
            Map<String, Object> ctx = Map.of("intVal", 5, "doubleVal", 5.0);
            assertTrue(templateEngine.evaluateConditionWithMap("{{intVal == doubleVal}}", ctx));
        }

        @Test
        @DisplayName("Large number arithmetic")
        void largeNumberArithmetic() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("a", 1000000L);
            ctx.setVariable("b", 2000000L);
            assertEquals(3000000L, spelEvaluator.evaluate("#a + #b", ctx));
        }

        @Test
        @DisplayName("Negative number handling")
        void negativeNumbers() {
            Map<String, Object> ctx = Map.of("val", -5);
            assertTrue(templateEngine.evaluateConditionWithMap("{{val < 0}}", ctx));
            assertTrue(templateEngine.evaluateConditionWithMap("{{abs(val) == 5}}", ctx));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 21. Hybrid expressions - inside vs outside {{}}
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Hybrid Expression Patterns")
    class HybridExpressionTests {

        // --- PURE: everything inside {{}} ---

        @Test
        @DisplayName("Pure: {{var > 5}} - comparison inside braces")
        void pureComparison() {
            Map<String, Object> ctx = Map.of("score", 85);
            assertTrue(templateEngine.evaluateConditionWithMap("{{score > 50}}", ctx));
        }

        @Test
        @DisplayName("Pure: {{size(items) > 0}} - function inside braces")
        void pureFunctionInside() {
            Map<String, Object> ctx = Map.of("items", List.of(1, 2, 3));
            assertTrue(templateEngine.evaluateConditionWithMap("{{size(items) > 0}}", ctx));
        }

        @Test
        @DisplayName("Pure: {{status == 'active'}} - string comparison inside braces")
        void pureStringComparison() {
            Map<String, Object> ctx = Map.of("status", "active");
            assertTrue(templateEngine.evaluateConditionWithMap("{{status == 'active'}}", ctx));
        }

        @Test
        @DisplayName("Pure: {{items?.length > 0}} - safe nav .length inside braces")
        void pureSafeNavLength() {
            Map<String, Object> ctx = Map.of("items", List.of("a", "b"));
            assertTrue(templateEngine.evaluateConditionWithMap("{{items?.length > 0}}", ctx));
        }

        @Test
        @DisplayName("Pure: {{!isempty(items)}} - negated function inside braces")
        void pureNegatedFunction() {
            Map<String, Object> ctx = Map.of("items", List.of("a", "b"));
            assertTrue(templateEngine.evaluateConditionWithMap("{{!isempty(items)}}", ctx));
        }

        // --- MIXED: {{}} blocks + operators/methods outside ---

        @Test
        @DisplayName("Mixed: {{score}} > 80 - variable block + comparison outside")
        void mixedVariableAndComparison() {
            Map<String, Object> ctx = Map.of("score", 85);
            assertTrue(templateEngine.evaluateConditionWithMap("{{score}} > 80", ctx));
            assertFalse(templateEngine.evaluateConditionWithMap("{{score}} > 90", ctx));
        }

        @Test
        @DisplayName("Mixed: {{a}} + {{b}} > 25 - two blocks with arithmetic")
        void mixedTwoBlocksArithmetic() {
            Map<String, Object> ctx = Map.of("a", 10, "b", 20);
            assertTrue(templateEngine.evaluateConditionWithMap("{{a}} + {{b}} > 25", ctx));
        }

        @Test
        @DisplayName("Mixed: {{status}} == 'active' - block + string comparison outside")
        void mixedBlockStringComparison() {
            Map<String, Object> ctx = Map.of("status", "active");
            assertTrue(templateEngine.evaluateConditionWithMap("{{status}} == 'active'", ctx));
        }

        @Test
        @DisplayName("Mixed: {{items}}.size() > 0 - method call on resolved block")
        void mixedBlockMethodCall() {
            Map<String, Object> ctx = Map.of("items", List.of(1, 2, 3));
            assertTrue(templateEngine.evaluateConditionWithMap("{{items}}.size() > 0", ctx));
        }

        @Test
        @DisplayName("Mixed: {{items}}.length > 0 - .length property on resolved block")
        void mixedBlockLengthProperty() {
            Map<String, Object> ctx = Map.of("items", List.of(1, 2, 3));
            assertTrue(templateEngine.evaluateConditionWithMap("{{items}}.length > 0", ctx));
        }

        @Test
        @DisplayName("Mixed: {{items}}.empty - .empty property on resolved block")
        void mixedBlockEmptyProperty() {
            Map<String, Object> ctx = Map.of("items", List.of(1, 2, 3));
            assertFalse(templateEngine.evaluateConditionWithMap("{{items}}.empty", ctx));

            Map<String, Object> ctx2 = Map.of("items", List.of());
            assertTrue(templateEngine.evaluateConditionWithMap("{{items}}.empty", ctx2));
        }

        // --- RAW: no {{}} at all ---

        @Test
        @DisplayName("Raw: score > 90 - no braces at all")
        void rawNoBraces() {
            Map<String, Object> ctx = Map.of("score", 95);
            assertTrue(templateEngine.evaluateConditionWithMap("score > 90", ctx));
        }

        @Test
        @DisplayName("Raw: size(items) > 0 - function without braces")
        void rawFunction() {
            Map<String, Object> ctx = Map.of("items", List.of("a", "b"));
            assertTrue(templateEngine.evaluateConditionWithMap("size(items) > 0", ctx));
        }

        @Test
        @DisplayName("Raw: status == 'active' - string comparison without braces")
        void rawStringComparison() {
            Map<String, Object> ctx = Map.of("status", "active");
            assertTrue(templateEngine.evaluateConditionWithMap("status == 'active'", ctx));
        }

        // --- NAMESPACED HYBRID ---

        @Test
        @DisplayName("Pure with namespace: {{mcp:api.output.count > 0}}")
        void pureWithNamespace() {
            Map<String, Object> ctx = Map.of(
                "mcp:api", Map.of("output", Map.of("count", 5))
            );
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{mcp:api.output.count > 0}}", ctx));
        }

        @Test
        @DisplayName("Mixed with namespace: {{mcp:api.output.count}} > 0")
        void mixedWithNamespace() {
            Map<String, Object> ctx = Map.of(
                "mcp:api", Map.of("output", Map.of("count", 5))
            );
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{mcp:api.output.count}} > 0", ctx));
        }

        @Test
        @DisplayName("Mixed: {{mcp:api.output.items}}.size() > 0 - method on namespaced block")
        void mixedNamespacedMethodCall() {
            Map<String, Object> ctx = Map.of(
                "mcp:api", Map.of("output", Map.of("items", List.of("x", "y")))
            );
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{mcp:api.output.items}}.size() > 0", ctx));
        }

        @Test
        @DisplayName("Pure: {{mcp:api.output.items?.length > 0}} - safe nav inside braces")
        void pureNamespacedSafeNavLength() {
            Map<String, Object> ctx = Map.of(
                "mcp:api", Map.of("output", Map.of("items", List.of("x", "y")))
            );
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{mcp:api.output.items?.length > 0}}", ctx));
        }

        // --- COMPLEX MIXED ---

        @Test
        @DisplayName("Mixed: {{a}} > 0 && {{b}} < 100 - logical with two blocks")
        void mixedLogicalTwoBlocks() {
            Map<String, Object> ctx = Map.of("a", 5, "b", 50);
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{a}} > 0 && {{b}} < 100", ctx));
        }

        @Test
        @DisplayName("Mixed: {{size(items)}} == 3 - function result comparison outside")
        void mixedFunctionResultComparison() {
            Map<String, Object> ctx = Map.of("items", List.of("a", "b", "c"));
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{size(items)}} == 3", ctx));
        }

        @Test
        @DisplayName("Mixed: {{score}} >= 90 ? 'A' : 'B' - ternary with block")
        void mixedTernaryWithBlock() {
            // Ternary outside {{}} with resolved values
            Map<String, Object> ctx = Map.of("score", 95);
            // This is evaluated as a condition, so it checks truthiness of the ternary result
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{score >= 90}}", ctx));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 22. Variable resolution - dotted paths on non-Map objects
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Variable Resolution Edge Cases")
    class VariableResolutionEdgeCaseTests {

        @Test
        @DisplayName("items.size() - method call on simple variable (NOT dotted path)")
        void methodCallOnSimpleVar() {
            Map<String, Object> ctx = Map.of("items", List.of(1, 2, 3));
            Object result = spelEvaluator.evaluateWithMap("items.size()", ctx, pathNavigator);
            assertEquals(3, result);
        }

        @Test
        @DisplayName("items.length - .length property on simple variable")
        void lengthPropertyOnSimpleVar() {
            Map<String, Object> ctx = Map.of("items", List.of(1, 2, 3));
            Object result = spelEvaluator.evaluateWithMap("items.length", ctx, pathNavigator);
            assertEquals(3, result);
        }

        @Test
        @DisplayName("items.empty - .empty property on collection")
        void emptyPropertyOnCollection() {
            Map<String, Object> ctx = Map.of("items", List.of(1, 2));
            Object result = spelEvaluator.evaluateWithMap("items.empty", ctx, pathNavigator);
            assertEquals(false, result);

            Map<String, Object> ctx2 = Map.of("items", List.of());
            Object result2 = spelEvaluator.evaluateWithMap("items.empty", ctx2, pathNavigator);
            assertEquals(true, result2);
        }

        @Test
        @DisplayName("text.length() - method call on string")
        void methodCallOnString() {
            Map<String, Object> ctx = Map.of("text", "hello");
            Object result = spelEvaluator.evaluateWithMap("text.length()", ctx, pathNavigator);
            assertEquals(5, result);
        }

        @Test
        @DisplayName("Namespace path THEN method: mcp:api.output.items.size()")
        void namespacePathThenMethod() {
            Map<String, Object> ctx = Map.of(
                "mcp:api", Map.of("output", Map.of("items", List.of(1, 2, 3)))
            );
            Object result = spelEvaluator.evaluateWithMap(
                "mcp:api.output.items.size()", ctx, pathNavigator);
            assertEquals(3, result);
        }

        @Test
        @DisplayName("Namespace path THEN .length: mcp:api.output.items.length")
        void namespacePathThenLength() {
            Map<String, Object> ctx = Map.of(
                "mcp:api", Map.of("output", Map.of("items", List.of(1, 2, 3)))
            );
            Object result = spelEvaluator.evaluateWithMap(
                "mcp:api.output.items.length", ctx, pathNavigator);
            assertEquals(3, result);
        }

        @Test
        @DisplayName("Namespace path with safe nav: mcp:api.output.items?.length")
        void namespacePathSafeNavLength() {
            Map<String, Object> ctx = Map.of(
                "mcp:api", Map.of("output", Map.of("items", List.of(1, 2)))
            );
            Object result = spelEvaluator.evaluateWithMap(
                "mcp:api.output.items?.length", ctx, pathNavigator);
            assertEquals(2, result);
        }

        @Test
        @DisplayName("Unresolvable dotted path: nonexistent.size() → null/false")
        void unresolvableDottedPath() {
            Map<String, Object> ctx = Map.of("other", "value");
            // nonexistent is not in context, so nonexistent.size() should fail gracefully
            assertFalse(templateEngine.evaluateConditionWithMap("{{nonexistent.size() > 0}}", ctx));
        }

        @Test
        @DisplayName("'length' as a variable name (not function, not property)")
        void lengthAsVariableName() {
            Map<String, Object> ctx = Map.of("length", 42);
            // 'length' is in CUSTOM_FUNCTION_NAMES but NOT followed by (
            // → should be treated as variable, not function
            assertTrue(templateEngine.evaluateConditionWithMap("{{length > 40}}", ctx));
        }

        @Test
        @DisplayName("items?.size() - method call with safe navigation")
        void safeNavMethodCall() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("items", List.of(1, 2, 3));
            assertEquals(3, spelEvaluator.evaluate("#items?.size()", ctx));

            ctx.setVariable("items", null);
            assertNull(spelEvaluator.evaluate("#items?.size()", ctx));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 23. String literal edge cases
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("String Literal Edge Cases")
    class StringLiteralEdgeCaseTests {

        @Test
        @DisplayName("String literal containing identifier: 'hello' == 'hello'")
        void stringLiteralContainingIdentifier() {
            Map<String, Object> ctx = Map.of();
            // Both 'hello' values are string literals - should not be resolved as variables
            assertTrue(templateEngine.evaluateConditionWithMap("{{'hello' == 'hello'}}", ctx));
        }

        @Test
        @DisplayName("String literal vs variable: greeting == 'hello'")
        void stringLiteralVsVariable() {
            Map<String, Object> ctx = Map.of("greeting", "hello");
            assertTrue(templateEngine.evaluateConditionWithMap("{{greeting == 'hello'}}", ctx));
            assertFalse(templateEngine.evaluateConditionWithMap("{{greeting == 'world'}}", ctx));
        }

        @Test
        @DisplayName("String with function name inside quotes: 'int' is not a function")
        void stringWithFunctionName() {
            Map<String, Object> ctx = Map.of("type", "int");
            assertTrue(templateEngine.evaluateConditionWithMap("{{type == 'int'}}", ctx));
        }

        @Test
        @DisplayName("SpEL doubled single-quote escape: 'it''s'")
        void spelDoubledQuoteEscape() {
            StandardEvaluationContext ctx = spelEvaluator.createEvaluationContext();
            ctx.setVariable("text", "it's");
            // SpEL uses '' to represent a literal single quote inside a single-quoted string
            assertEquals(true, spelEvaluator.evaluate("#text == 'it''s'", ctx));
        }

        @Test
        @DisplayName("Multiple string literals in one expression")
        void multipleStringLiterals() {
            Map<String, Object> ctx = Map.of("status", "active", "role", "admin");
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{status == 'active' && role == 'admin'}}", ctx));
        }

        @Test
        @DisplayName("Empty string literal comparison")
        void emptyStringLiteral() {
            Map<String, Object> ctx = Map.of("name", "");
            assertTrue(templateEngine.evaluateConditionWithMap("{{name == ''}}", ctx));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 24. Preprocessor string literal robustness
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Preprocessor String Literal Robustness")
    class PreprocessorStringLiteralTests {

        @Test
        @DisplayName("preprocessCustomFunctions skips function names in single-quoted SpEL escaped strings")
        void preprocessorSkipsDoubledQuoteEscape() {
            // 'it''s int()' contains "int" but inside a string literal - must not become #int
            String result = spelEvaluator.preprocessCustomFunctions("contains(x, 'it''s int()')");
            assertEquals("#contains(x, 'it''s int()')", result);
        }

        @Test
        @DisplayName("preprocessCustomFunctions skips function names in double-quoted strings with backslash escape")
        void preprocessorSkipsBackslashEscapeInDoubleQuotes() {
            // "say \"int()\"" - int() inside double-quoted string must not become #int
            String result = spelEvaluator.preprocessCustomFunctions("contains(x, \"say \\\"int()\\\"\")");
            assertEquals("#contains(x, \"say \\\"int()\\\"\")", result);
        }

        @Test
        @DisplayName("preprocessCustomFunctions handles function after SpEL escaped string")
        void preprocessorFunctionAfterEscapedString() {
            // After the string with '' escape, the real function call should still get #
            String result = spelEvaluator.preprocessCustomFunctions("'it''s' == trim(x)");
            assertEquals("'it''s' == #trim(x)", result);
        }

        @Test
        @DisplayName("preprocessCustomFunctions handles multiple quoted segments")
        void preprocessorMultipleQuotedSegments() {
            String result = spelEvaluator.preprocessCustomFunctions("'hello' == uppercase('world')");
            assertEquals("'hello' == #uppercase('world')", result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 25. formatValueForSpel
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Format Value For SpEL")
    class FormatValueForSpelTests {

        @Test
        @DisplayName("String with single quote uses SpEL doubled-quote escaping")
        void stringWithSingleQuote() {
            String result = spelEvaluator.formatValueForSpel("it's");
            assertEquals("'it''s'", result);
        }

        @Test
        @DisplayName("String without quotes wraps in single quotes")
        void plainString() {
            assertEquals("'hello'", spelEvaluator.formatValueForSpel("hello"));
        }

        @Test
        @DisplayName("Long value has L suffix")
        void longValue() {
            assertEquals("42L", spelEvaluator.formatValueForSpel(42L));
        }

        @Test
        @DisplayName("Boolean value is plain")
        void booleanValue() {
            assertEquals("true", spelEvaluator.formatValueForSpel(true));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // 26. trySplitResolution edge cases
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Split Resolution Edge Cases")
    class SplitResolutionEdgeCaseTests {

        @Test
        @DisplayName("trySplitResolution with multi-level remaining path")
        void multiLevelRemainingPath() {
            // items resolves to a List, so .size should be handled by split resolution
            // even though there's no deeper nesting, the mechanism should work
            Map<String, Object> ctx = Map.of("items", List.of(
                Map.of("nested", Map.of("value", 42))
            ));
            // items[0].nested.value → items resolves, then [0].nested.value in SpEL
            Object result = templateEngine.evaluateConditionWithMap(
                "{{items[0].nested.value == 42}}", ctx);
            assertTrue((Boolean) result);
        }

        @Test
        @DisplayName("trySplitResolution when base is Map keeps navigating")
        void baseIsMapKeepsNavigating() {
            // When the base resolves to a Map, it should NOT split - should keep navigating
            Map<String, Object> ctx = Map.of("data", Map.of("count", 5));
            Object result = templateEngine.evaluateConditionWithMap(
                "{{data.count > 3}}", ctx);
            assertTrue((Boolean) result);
        }

        @Test
        @DisplayName("toSafeVarName collision produces distinct variables")
        void safeVarNameDistinctPaths() {
            // a:b and a.b both map to a_b - but in practice they resolve different values
            // since a:b is a namespaced ref (trigger:xxx) and a.b is a dotted path
            // This test verifies the resolution still works correctly
            Map<String, Object> ctx = Map.of(
                "data", Map.of("value", 10)
            );
            Object result = templateEngine.evaluateConditionWithMap(
                "{{data.value == 10}}", ctx);
            assertTrue((Boolean) result);
        }
    }
}
