package com.apimarketplace.orchestrator.services.template;

import com.apimarketplace.orchestrator.domain.WorkflowExecutionContext;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.TypeCastingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end unit tests for {{$vars.*}} / {{vars:*}} workflow-variable
 * resolution through the REAL template stack:
 *
 *   TemplateEngine (normalization + {{...}} extraction)
 *     -> VarsSyntaxNormalizer ($vars.name / vars:name -> vars.name)
 *       -> NamespaceResolver ("vars" branch, bundle in global variables)
 *         -> PathNavigator (deep navigation into JSON-typed values)
 *           -> SpelEvaluator (real SpEL evaluation)
 *
 * No mocks: this pins the full wiring, mirroring how the execution engine
 * resolves node parameters at runtime once the per-run bundle has been
 * attached to the context's global variables under the "vars" key.
 */
@DisplayName("Workflow variables ($vars) end-to-end template resolution")
class VarsTemplateResolutionEndToEndTest {

    private TemplateEngine templateEngine;
    private WorkflowExecutionContext context;

    @BeforeEach
    void setUp() {
        SpelEvaluator spelEvaluator = new SpelEvaluator();
        spelEvaluator.init();
        PathNavigator pathNavigator = new PathNavigator();
        NamespaceResolver namespaceResolver = new NamespaceResolver(pathNavigator);
        TypeCastingService typeCastingService = new TypeCastingService();
        templateEngine = new TemplateEngine(typeCastingService, namespaceResolver, pathNavigator, spelEvaluator);

        context = new WorkflowExecutionContext("wf-1", "run-1", "tenant-1");
        context.setGlobalVariable("vars", Map.of(
            "api_base_url", "https://api.example.com",
            "n", 5,
            "config", Map.of("api", Map.of("url", "https://deep.example.com"))
        ));
    }

    @Nested
    @DisplayName("Mixed templates")
    class MixedTemplateTests {

        @Test
        @DisplayName("Should resolve {{$vars.api_base_url}}/users to the variable value plus suffix")
        void shouldResolveMixedTemplateWithSuffix() {
            // Act
            Object result = templateEngine.evaluateTemplate("{{$vars.api_base_url}}/users", context);

            // Assert
            assertEquals("https://api.example.com/users", result);
        }

        @Test
        @DisplayName("Should resolve the vars: alias form in a mixed template identically")
        void shouldResolveAliasFormInMixedTemplate() {
            Object result = templateEngine.evaluateTemplate("{{vars:api_base_url}}/users", context);

            assertEquals("https://api.example.com/users", result);
        }

        @Test
        @DisplayName("Should resolve a template with text on both sides of the expression")
        void shouldResolveTemplateWithSurroundingText() {
            Object result = templateEngine.evaluateTemplate("GET {{$vars.api_base_url}} now", context);

            assertEquals("GET https://api.example.com now", result);
        }
    }

    @Nested
    @DisplayName("Pure expressions")
    class PureExpressionTests {

        @Test
        @DisplayName("Should return the typed value for a pure {{$vars.n}} expression")
        void shouldReturnTypedValueForPureExpression() {
            Object result = templateEngine.evaluateTemplate("{{$vars.n}}", context);

            assertEquals(5, result);
        }

        @Test
        @DisplayName("Should navigate deep paths into a JSON-typed variable")
        void shouldNavigateDeepPathIntoJsonVariable() {
            Object result = templateEngine.evaluateTemplate("{{$vars.config.api.url}}", context);

            assertEquals("https://deep.example.com", result);
        }

        @Test
        @DisplayName("Should return null for an unknown variable name")
        void shouldReturnNullForUnknownVariable() {
            Object result = templateEngine.evaluateTemplate("{{$vars.does_not_exist}}", context);

            assertNull(result);
        }
    }

    @Nested
    @DisplayName("Conditions")
    class ConditionTests {

        @Test
        @DisplayName("Should evaluate {{$vars.n > 3}} to true with n=5")
        void shouldEvaluateNumericConditionTrue() {
            assertTrue(templateEngine.evaluateCondition("{{$vars.n > 3}}", context));
        }

        @Test
        @DisplayName("Should evaluate {{$vars.n > 10}} to false with n=5")
        void shouldEvaluateNumericConditionFalse() {
            assertFalse(templateEngine.evaluateCondition("{{$vars.n > 10}}", context));
        }

        @Test
        @DisplayName("Should evaluate the vars: alias in a condition")
        void shouldEvaluateAliasCondition() {
            assertTrue(templateEngine.evaluateCondition("{{vars:n == 5}}", context));
        }
    }

    @Nested
    @DisplayName("Protected regions and missing bundle")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should keep a quoted '$vars.x' string literal as data, not a reference")
        void shouldKeepQuotedVarsLiteralAsData() {
            Object result = templateEngine.evaluateTemplate("{{'$vars.x' + '!'}}", context);

            assertEquals("$vars.x!", result);
        }

        @Test
        @DisplayName("Should resolve to null when the context carries no vars bundle")
        void shouldResolveToNullWithoutBundle() {
            WorkflowExecutionContext emptyContext = new WorkflowExecutionContext("wf-2", "run-2", "tenant-1");

            Object result = templateEngine.evaluateTemplate("{{$vars.api_base_url}}", emptyContext);

            assertNull(result);
        }
    }
}
