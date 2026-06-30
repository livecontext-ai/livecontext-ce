package com.apimarketplace.orchestrator.services.template;

import com.apimarketplace.orchestrator.domain.WorkflowExecutionContext;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.TypeCastingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end behavior of the template engine for SpEL selection, projection,
 * method calls and chained operations - the cases that used to break when the
 * variable-rewriting pre-pass incorrectly rewrote identifiers inside
 * {@code .?[ ... ]} / {@code .![ ... ]} blocks.
 *
 * <p>Exercises both paths:
 * <ul>
 *   <li>Map-based - {@code templateEngine.evaluateTemplateWithMap(...)}</li>
 *   <li>WorkflowExecutionContext-based - {@code templateEngine.evaluateTemplate(..., ctx)}</li>
 * </ul>
 */
@DisplayName("SpEL selection / projection / method calls through templates")
class SpelSelectionAndProjectionIntegrationTest {

    private TemplateEngine templateEngine;
    private PathNavigator pathNavigator;

    @BeforeEach
    void setUp() {
        SpelEvaluator spelEvaluator = new SpelEvaluator();
        spelEvaluator.init();
        pathNavigator = new PathNavigator();
        NamespaceResolver namespaceResolver = new NamespaceResolver(pathNavigator);
        TypeCastingService typeCastingService = new TypeCastingService();
        templateEngine = new TemplateEngine(typeCastingService, namespaceResolver, pathNavigator, spelEvaluator);
    }

    /**
     * Context mimicking a Gmail-like payload: headers is a list of {name, value} maps.
     */
    private Map<String, Object> gmailContext() {
        List<Map<String, Object>> headers = List.of(
            Map.of("name", "From", "value", "alice@example.com"),
            Map.of("name", "To", "value", "bob@example.com"),
            Map.of("name", "Subject", "value", "Hello there")
        );
        Map<String, Object> payload = Map.of("headers", headers, "snippet", "preview");
        Map<String, Object> mcpOutput = Map.of("output", Map.of("payload", payload));
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("mcp:get_email", mcpOutput);
        return ctx;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Selection - was broken, now works
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Selection .?[ ... ]")
    class SelectionTests {

        @Test
        @DisplayName("Filter list of maps by field value - returns filtered list")
        void filterHeadersByName() {
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{mcp:get_email.output.payload.headers.?[name == 'From']}}",
                gmailContext()
            );
            assertInstanceOf(List.class, result);
            List<?> filtered = (List<?>) result;
            assertEquals(1, filtered.size());
            assertEquals("From", ((Map<?, ?>) filtered.get(0)).get("name"));
        }

        @Test
        @DisplayName("First match .^[ ... ] - scalar element access")
        void firstMatchByName() {
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{mcp:get_email.output.payload.headers.^[name == 'From'].value}}",
                gmailContext()
            );
            assertEquals("alice@example.com", result);
        }

        @Test
        @DisplayName("Last match .$[ ... ]")
        void lastMatchByName() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("items", List.of(
                Map.of("type", "a", "id", 1),
                Map.of("type", "b", "id", 2),
                Map.of("type", "a", "id", 3)
            ));
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{items.$[type == 'a'].id}}", ctx);
            assertEquals(3, result);
        }

        @Test
        @DisplayName("Filter numbers list - #this")
        void filterNumbers() {
            Map<String, Object> ctx = Map.of("nums", List.of(1, 5, 10, 15, 20));
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{nums.?[#this > 10]}}", ctx);
            assertInstanceOf(List.class, result);
            assertEquals(List.of(15, 20), result);
        }

        @Test
        @DisplayName("Selection with numeric comparison on map field")
        void selectionNumericComparison() {
            Map<String, Object> ctx = Map.of("users", List.of(
                Map.of("name", "Alice", "age", 25),
                Map.of("name", "Bob", "age", 17),
                Map.of("name", "Carol", "age", 30)
            ));
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{users.?[age >= 18]}}", ctx);
            assertInstanceOf(List.class, result);
            assertEquals(2, ((List<?>) result).size());
        }

        @Test
        @DisplayName("Selection with AND")
        void selectionWithAnd() {
            Map<String, Object> ctx = Map.of("users", List.of(
                Map.of("name", "Alice", "age", 25, "active", true),
                Map.of("name", "Bob", "age", 17, "active", true),
                Map.of("name", "Carol", "age", 30, "active", false)
            ));
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{users.?[age >= 18 and active == true]}}", ctx);
            assertInstanceOf(List.class, result);
            assertEquals(1, ((List<?>) result).size());
            assertEquals("Alice", ((Map<?, ?>) ((List<?>) result).get(0)).get("name"));
        }

        @Test
        @DisplayName("Empty result when no match")
        void emptyResult() {
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{mcp:get_email.output.payload.headers.?[name == 'X-Missing']}}",
                gmailContext()
            );
            assertInstanceOf(List.class, result);
            assertTrue(((List<?>) result).isEmpty());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Projection
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Projection .![ ... ]")
    class ProjectionTests {

        @Test
        @DisplayName("Extract single field from each map")
        void projectNames() {
            Map<String, Object> ctx = Map.of("users", List.of(
                Map.of("name", "Alice", "age", 25),
                Map.of("name", "Bob", "age", 30)
            ));
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{users.![name]}}", ctx);
            assertInstanceOf(List.class, result);
            assertEquals(List.of("Alice", "Bob"), result);
        }

        @Test
        @DisplayName("Project computed value")
        void projectComputed() {
            Map<String, Object> ctx = Map.of("users", List.of(
                Map.of("first", "Alice", "last", "Doe"),
                Map.of("first", "Bob", "last", "Smith")
            ));
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{users.![first + ' ' + last]}}", ctx);
            assertInstanceOf(List.class, result);
            assertEquals(List.of("Alice Doe", "Bob Smith"), result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Chained: selection → projection → method call
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Chained operations")
    class ChainedTests {

        @Test
        @DisplayName("Selection then projection")
        void selectionThenProjection() {
            Map<String, Object> ctx = Map.of("users", List.of(
                Map.of("name", "Alice", "age", 25),
                Map.of("name", "Bob", "age", 17),
                Map.of("name", "Carol", "age", 30)
            ));
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{users.?[age >= 18].![name]}}", ctx);
            assertInstanceOf(List.class, result);
            assertEquals(List.of("Alice", "Carol"), result);
        }

        @Test
        @DisplayName("Selection then .size() method")
        void selectionThenSize() {
            Map<String, Object> ctx = Map.of("users", List.of(
                Map.of("age", 25),
                Map.of("age", 17),
                Map.of("age", 30)
            ));
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{users.?[age >= 18].size()}}", ctx);
            assertEquals(2, result);
        }

        @Test
        @DisplayName("Selection then property .size wrapped in size() function")
        void sizeFunctionOnSelection() {
            Map<String, Object> ctx = Map.of("users", List.of(
                Map.of("age", 25),
                Map.of("age", 17),
                Map.of("age", 30)
            ));
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{size(users.?[age >= 18])}}", ctx);
            assertEquals(2, result);
        }

        @Test
        @DisplayName("Deep nested path with selection at the end")
        void deepPathWithSelection() {
            Map<String, Object> ctx = Map.of(
                "mcp:fetch", Map.of("output", Map.of(
                    "data", Map.of("users", List.of(
                        Map.of("name", "Alice", "role", "admin"),
                        Map.of("name", "Bob", "role", "user")
                    ))))
            );
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{mcp:fetch.output.data.users.?[role == 'admin'].![name]}}", ctx);
            assertInstanceOf(List.class, result);
            assertEquals(List.of("Alice"), result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Method calls on collections (used to break in ctx path due to missing split)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Method calls / property access on resolved collections")
    class MethodCallTests {

        @Test
        @DisplayName("list.size() on a resolved List")
        void listSizeMethod() {
            Map<String, Object> ctx = Map.of("items", List.of("a", "b", "c"));
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{items.size()}}", ctx);
            assertEquals(3, result);
        }

        @Test
        @DisplayName("list.isEmpty() on a resolved List")
        void listIsEmpty() {
            Map<String, Object> ctx = Map.of("items", Collections.emptyList());
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{items.isEmpty()}}", ctx);
            assertEquals(true, result);
        }

        @Test
        @DisplayName("list[0].field chained property access")
        void indexThenProperty() {
            Map<String, Object> ctx = Map.of("users", List.of(
                Map.of("name", "Alice")
            ));
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{users[0].name}}", ctx);
            assertEquals("Alice", result);
        }

        @Test
        @DisplayName("items[idx] with idx as external variable")
        void indexViaVariable() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("items", List.of("a", "b", "c"));
            ctx.put("idx", 1);
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{items[idx]}}", ctx);
            assertEquals("b", result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // JSONPath-style property access on filtered list (auto-projection)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("JSONPath-style projection on filtered List<Map>")
    class JsonPathStyleProjection {

        @Test
        @DisplayName("headers.?[name == 'Subject'].value → projected list")
        void selectionThenBareProperty() {
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{mcp:get_email.output.payload.headers.?[name == 'Subject'].value}}",
                gmailContext());
            assertEquals(List.of("Hello there"), result);
        }

        @Test
        @DisplayName("Filtered list with N matches → N projected values")
        void multipleMatchesProjected() {
            List<Map<String, Object>> users = List.of(
                Map.of("name", "Alice", "role", "admin"),
                Map.of("name", "Bob", "role", "user"),
                Map.of("name", "Carol", "role", "admin")
            );
            Map<String, Object> ctx = Map.of("users", users);
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{users.?[role == 'admin'].name}}", ctx);
            assertEquals(List.of("Alice", "Carol"), result);
        }

        @Test
        @DisplayName("Empty selection result → empty list")
        void emptySelectionProjection() {
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{mcp:get_email.output.payload.headers.?[name == 'Nope'].value}}",
                gmailContext());
            assertEquals(Collections.emptyList(), result);
        }

        @Test
        @DisplayName("Plain list (no .?[]) with bare property also works")
        void plainListProjection() {
            List<Map<String, Object>> items = List.of(
                Map.of("id", 1, "label", "a"),
                Map.of("id", 2, "label", "b")
            );
            Map<String, Object> ctx = Map.of("items", items);
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{items.label}}", ctx);
            assertEquals(List.of("a", "b"), result);
        }

        @Test
        @DisplayName("Built-in SpEL properties (size) still win over projection")
        void builtinSizeNotHijacked() {
            List<Map<String, Object>> items = List.of(
                Map.of("size", "should-not-project", "x", 1),
                Map.of("size", "nor-this", "x", 2)
            );
            Map<String, Object> ctx = Map.of("items", items);
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{items.size()}}", ctx);
            // list.size() is a real method - evaluator should return the list's size,
            // not project the 'size' key from each Map element.
            assertEquals(2, result);
        }

        @Test
        @DisplayName("List<String> .anyName → NOT hijacked (first element is not a Map)")
        void listOfNonMapsNotHijacked() {
            Map<String, Object> ctx = Map.of("items", List.of("a", "b", "c"));
            // 'charAt' is a legit method on String, but as a bare property on
            // ArrayList it should still fail - accessor must only fire for Map elements.
            // We assert no crash: evaluation returns null (swallowed by TemplateEngine).
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{items.nonExistentProp}}", ctx);
            assertNull(result);
        }

        @Test
        @DisplayName("Key missing from all Maps → accessor declines, result null")
        void keyNotPresent() {
            List<Map<String, Object>> items = List.of(
                Map.of("id", 1), Map.of("id", 2)
            );
            Map<String, Object> ctx = Map.of("items", items);
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{items.notAKey}}", ctx);
            assertNull(result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Pre-existing patterns - regression guard
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Existing patterns still work (regression guard)")
    class RegressionGuard {

        @Test
        @DisplayName("Simple variable access")
        void simpleVariable() {
            Map<String, Object> ctx = Map.of("name", "Alice");
            Object result = templateEngine.evaluateTemplateWithMap("{{name}}", ctx);
            assertEquals("Alice", result);
        }

        @Test
        @DisplayName("Nested field access mcp:x.output.y")
        void nestedNamespaced() {
            Map<String, Object> ctx = Map.of(
                "mcp:step", Map.of("output", Map.of("user_id", 42))
            );
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{mcp:step.output.user_id}}", ctx);
            assertEquals(42, result);
        }

        @Test
        @DisplayName("Numeric index mcp:x.output.list[0]")
        void numericIndex() {
            Map<String, Object> ctx = Map.of(
                "mcp:step", Map.of("output", Map.of("items", List.of("a", "b")))
            );
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{mcp:step.output.items[0]}}", ctx);
            assertEquals("a", result);
        }

        @Test
        @DisplayName("Ternary")
        void ternary() {
            Map<String, Object> ctx = Map.of("age", 25);
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{age >= 18 ? 'adult' : 'minor'}}", ctx);
            assertEquals("adult", result);
        }

        @Test
        @DisplayName("Arithmetic")
        void arithmetic() {
            Map<String, Object> ctx = Map.of("a", 3, "b", 4);
            Object result = templateEngine.evaluateTemplateWithMap("{{a + b}}", ctx);
            assertEquals(7, result);
        }

        @Test
        @DisplayName("Custom function call size(list)")
        void customFunctionSize() {
            Map<String, Object> ctx = Map.of("items", List.of(1, 2, 3));
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{size(items)}}", ctx);
            assertEquals(3, result);
        }

        @Test
        @DisplayName("Mixed template with text and expression")
        void mixedTemplate() {
            Map<String, Object> ctx = Map.of("name", "Alice");
            Object result = templateEngine.evaluateTemplateWithMap(
                "Hello {{name}}!", ctx);
            assertEquals("Hello Alice!", result);
        }

        @Test
        @DisplayName("String concatenation with literal")
        void stringConcat() {
            Map<String, Object> ctx = Map.of("name", "Alice");
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{'Hello ' + name}}", ctx);
            assertEquals("Hello Alice", result);
        }

        @Test
        @DisplayName("Boolean negation")
        void booleanNegation() {
            Map<String, Object> ctx = Map.of("flag", false);
            Object result = templateEngine.evaluateTemplateWithMap("{{!flag}}", ctx);
            assertEquals(true, result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // WorkflowExecutionContext path (non-Map) - property-access + split resolution fix
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("WorkflowExecutionContext path")
    class CtxPath {

        private WorkflowExecutionContext ctxWithStep(Object stepOutput) {
            WorkflowExecutionContext ctx = new WorkflowExecutionContext();
            // mcp:fetch has output -> items mapping
            Map<String, Object> payload = new HashMap<>();
            payload.put("output", Map.of("items", stepOutput));
            ctx.setStepOutput("mcp:fetch", payload);
            return ctx;
        }

        @Test
        @DisplayName("Selection .?[ ... ] through ctx path")
        void selectionCtxPath() {
            List<Map<String, Object>> items = List.of(
                Map.of("name", "a", "value", 10),
                Map.of("name", "b", "value", 20),
                Map.of("name", "c", "value", 5)
            );
            WorkflowExecutionContext ctx = ctxWithStep(items);

            Object result = templateEngine.evaluateTemplate(
                "{{mcp:fetch.output.items.?[value > 8]}}", ctx);

            assertInstanceOf(List.class, result);
            assertEquals(2, ((List<?>) result).size());
        }

        @Test
        @DisplayName("First-match .^[ ... ].value through ctx path")
        void firstMatchCtxPath() {
            List<Map<String, Object>> headers = List.of(
                Map.of("name", "From", "value", "a@b.com"),
                Map.of("name", "To", "value", "c@d.com")
            );
            WorkflowExecutionContext ctx = ctxWithStep(headers);

            Object result = templateEngine.evaluateTemplate(
                "{{mcp:fetch.output.items.^[name == 'From'].value}}", ctx);

            assertEquals("a@b.com", result);
        }

        @Test
        @DisplayName("Method call .size() on List - needs split resolution")
        void methodCallOnList() {
            WorkflowExecutionContext ctx = ctxWithStep(List.of("a", "b", "c"));

            Object result = templateEngine.evaluateTemplate(
                "{{mcp:fetch.output.items.size()}}", ctx);

            assertEquals(3, result);
        }

        @Test
        @DisplayName("Numeric index and nested property access")
        void indexAndProperty() {
            List<Map<String, Object>> items = List.of(
                Map.of("name", "Alice")
            );
            WorkflowExecutionContext ctx = ctxWithStep(items);

            Object result = templateEngine.evaluateTemplate(
                "{{mcp:fetch.output.items[0].name}}", ctx);

            assertEquals("Alice", result);
        }

        @Test
        @DisplayName("Projection through ctx path")
        void projectionCtxPath() {
            List<Map<String, Object>> users = List.of(
                Map.of("name", "Alice", "age", 25),
                Map.of("name", "Bob", "age", 30)
            );
            WorkflowExecutionContext ctx = ctxWithStep(users);

            Object result = templateEngine.evaluateTemplate(
                "{{mcp:fetch.output.items.![name]}}", ctx);

            assertInstanceOf(List.class, result);
            assertEquals(List.of("Alice", "Bob"), result);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Condition evaluation (if-branches, loop exit conditions) - selection in conditions
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Condition evaluation with selection")
    class ConditionTests {

        @Test
        @DisplayName("Condition using selection size")
        void selectionSizeCondition() {
            Map<String, Object> ctx = Map.of("users", List.of(
                Map.of("age", 25),
                Map.of("age", 17),
                Map.of("age", 30)
            ));
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{users.?[age >= 18].size() >= 2}}", ctx));
            assertFalse(templateEngine.evaluateConditionWithMap(
                "{{users.?[age >= 18].size() >= 5}}", ctx));
        }

        @Test
        @DisplayName("Condition comparing selection[0].field to literal")
        void selectionFieldComparison() {
            Map<String, Object> ctx = Map.of("headers", List.of(
                Map.of("name", "From", "value", "alice@example.com")
            ));
            assertTrue(templateEngine.evaluateConditionWithMap(
                "{{headers.^[name == 'From'].value == 'alice@example.com'}}", ctx));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Security - must NOT magically expand dangerous constructs beyond what
    // raw SpEL already allows. No new attack surface.
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Security / safety")
    class SecurityTests {

        @Test
        @DisplayName("Null-safe operator ?. is NOT broken by the rewriter")
        void nullSafeOperator() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("obj", null);
            // obj?.name should evaluate to null without NPE
            Object result = templateEngine.evaluateTemplateWithMap("{{obj?.name}}", ctx);
            assertNull(result);
        }

        @Test
        @DisplayName("Identifier inside string literal is not treated as variable")
        void identifierInStringNotTreatedAsVar() {
            Map<String, Object> ctx = Map.of("foo", "secret");
            // The 'foo' inside the string must NOT be replaced by the value of the foo variable.
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{'foo is literal'}}", ctx);
            assertEquals("foo is literal", result);
        }

        @Test
        @DisplayName("Unresolved variable returns null, not exception")
        void unresolvedVariableReturnsNull() {
            Map<String, Object> ctx = Map.of();
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{does_not_exist}}", ctx);
            assertNull(result);
        }

        @Test
        @DisplayName("Selection on null base returns null gracefully")
        void selectionOnNullBase() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("items", null);
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{items?.?[name == 'x']}}", ctx);
            assertNull(result);
        }

        @Test
        @DisplayName("Malformed selection (unterminated bracket) - no crash")
        void malformedSelection() {
            Map<String, Object> ctx = Map.of("users", new ArrayList<>());
            // Unterminated - should return null rather than throw
            Object result = templateEngine.evaluateTemplateWithMap(
                "{{users.?[age > 10}}", ctx);
            assertNull(result);
        }
    }
}
