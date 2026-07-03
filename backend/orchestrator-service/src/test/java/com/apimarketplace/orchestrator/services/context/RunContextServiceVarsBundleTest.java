package com.apimarketplace.orchestrator.services.context;

import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.orchestrator.services.TemplateEngine;
import com.apimarketplace.orchestrator.services.TypeCastingService;
import com.apimarketplace.orchestrator.services.template.NamespaceResolver;
import com.apimarketplace.orchestrator.services.template.PathNavigator;
import com.apimarketplace.orchestrator.services.template.SpelEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for the vars-aware
 * {@link RunContextService#evaluateExpressionsWithContext(Map, Map, Map)} overload
 * that merges the per-run {@code {{$vars.*}}} bundle under the {@code "vars"} context
 * key so interface {@code variable_mapping} entries resolve workspace variables at
 * RENDER time (Niveau 1).
 *
 * <p>Uses the REAL template stack (TemplateEngine + NamespaceResolver + SpelEvaluator +
 * PathNavigator), mirroring {@code VarsTemplateResolutionEndToEndTest}, so the merge is
 * pinned end-to-end and not against a mock. The StorageRepository is mocked because this
 * overload never touches storage - it resolves against the pre-built context map + bundle.
 */
@DisplayName("RunContextService.evaluateExpressionsWithContext - $vars bundle merge")
class RunContextServiceVarsBundleTest {

    private RunContextService runContextService;

    @BeforeEach
    void setUp() {
        SpelEvaluator spelEvaluator = new SpelEvaluator();
        spelEvaluator.init();
        PathNavigator pathNavigator = new PathNavigator();
        NamespaceResolver namespaceResolver = new NamespaceResolver(pathNavigator);
        TypeCastingService typeCastingService = new TypeCastingService();
        TemplateEngine templateEngine = new TemplateEngine(typeCastingService, namespaceResolver, pathNavigator, spelEvaluator);

        // Test-only 2-arg constructor: real TemplateEngine, mocked StorageRepository (unused here).
        runContextService = new RunContextService(mock(StorageRepository.class), templateEngine);
    }

    @Test
    @DisplayName("Merges the bundle so {{$vars.api_url}} resolves to the bundle value")
    void mergesBundleSoVarsReferenceResolves() {
        Map<String, Object> bundle = Map.of("api_url", "https://api.example.com");
        Map<String, String> mappings = Map.of("url", "{{$vars.api_url}}");

        Map<String, Object> resolved =
            runContextService.evaluateExpressionsWithContext(new LinkedHashMap<>(), mappings, bundle);

        assertEquals("https://api.example.com", resolved.get("url"));
    }

    @Test
    @DisplayName("Resolves BOTH a step-output reference and a {{$vars.*}} reference in one mapping set")
    void resolvesStepOutputAndVarsTogether() {
        // Pre-built context (as the interface render path assembles from persisted storage).
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("mcp:fetch", Map.of("output", Map.of("title", "Weekly Report")));

        Map<String, Object> bundle = Map.of("api_url", "https://api.example.com");
        Map<String, String> mappings = new LinkedHashMap<>();
        mappings.put("from_step", "{{mcp:fetch.output.title}}");
        mappings.put("from_vars", "{{$vars.api_url}}");

        Map<String, Object> resolved =
            runContextService.evaluateExpressionsWithContext(context, mappings, bundle);

        assertEquals("Weekly Report", resolved.get("from_step"), "step-output reference must still resolve");
        assertEquals("https://api.example.com", resolved.get("from_vars"), "vars reference must resolve from the merged bundle");
    }

    @Test
    @DisplayName("String concatenation of a {{$vars.*}} value with a literal suffix resolves")
    void resolvesVarsWithLiteralSuffix() {
        Map<String, Object> bundle = Map.of("api_url", "https://api.example.com");
        Map<String, String> mappings = Map.of("endpoint", "{{$vars.api_url}}/users");

        Map<String, Object> resolved =
            runContextService.evaluateExpressionsWithContext(new LinkedHashMap<>(), mappings, bundle);

        assertEquals("https://api.example.com/users", resolved.get("endpoint"));
    }

    @Test
    @DisplayName("The {{vars:name}} alias resolves identically (VarsSyntaxNormalizer runs in the Map path)")
    void resolvesVarsColonAlias() {
        Map<String, Object> bundle = Map.of("api_url", "https://api.example.com");
        Map<String, String> mappings = Map.of("url", "{{vars:api_url}}");

        Map<String, Object> resolved =
            runContextService.evaluateExpressionsWithContext(new LinkedHashMap<>(), mappings, bundle);

        assertEquals("https://api.example.com", resolved.get("url"));
    }

    @Test
    @DisplayName("A null bundle is a no-op: {{$vars.api_url}} resolves to nothing (unresolved, key absent)")
    void nullBundleIsNoOp() {
        Map<String, String> mappings = Map.of("url", "{{$vars.api_url}}");

        Map<String, Object> resolved =
            runContextService.evaluateExpressionsWithContext(new LinkedHashMap<>(), mappings, null);

        assertFalse(resolved.containsKey("url"), "with no bundle the vars reference resolves to null and is dropped");
    }

    @Test
    @DisplayName("An empty bundle is a no-op (no 'vars' key injected, reference stays unresolved)")
    void emptyBundleIsNoOp() {
        Map<String, String> mappings = Map.of("url", "{{$vars.api_url}}");

        Map<String, Object> resolved =
            runContextService.evaluateExpressionsWithContext(new LinkedHashMap<>(), mappings, Map.of());

        assertFalse(resolved.containsKey("url"), "empty bundle must not inject a 'vars' key");
    }

    @Test
    @DisplayName("An existing 'vars' key in the context is NOT overwritten by the bundle")
    void existingVarsKeyNotOverwritten() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("vars", Map.of("api_url", "https://from-context.example.com"));

        Map<String, Object> bundle = Map.of("api_url", "https://from-bundle.example.com");
        Map<String, String> mappings = Map.of("url", "{{$vars.api_url}}");

        Map<String, Object> resolved =
            runContextService.evaluateExpressionsWithContext(context, mappings, bundle);

        assertEquals("https://from-context.example.com", resolved.get("url"),
            "the pre-existing 'vars' entry wins - the bundle must not clobber an in-context vars map");
    }
}
