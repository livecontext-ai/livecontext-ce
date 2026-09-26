package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.orchestrator.domain.workflow.Core;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.execution.v2.engine.ExecutionContext;
import com.apimarketplace.orchestrator.execution.v2.template.V2TemplateAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;

/**
 * Unit tests for HtmlExtractNode (CSS-selector HTML parsing via jsoup).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HtmlExtractNode")
class HtmlExtractNodeTest {

    @Mock
    private WorkflowPlan mockPlan;

    @Mock
    private V2TemplateAdapter mockTemplateAdapter;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.create(
            "run-1",
            "workflow-run-1",
            "tenant-1",
            "item-1",
            0,
            new HashMap<>(),
            mockPlan
        );
    }

    private HtmlExtractNode buildNode(Core.HtmlExtractConfig config, String resolvedHtml) {
        HtmlExtractNode node = new HtmlExtractNode("core:html_extract", config);
        node.setTemplateAdapter(mockTemplateAdapter);
        when(mockTemplateAdapter.resolveTemplates(anyMap(), any()))
            .thenReturn(Map.of("__html__", resolvedHtml));
        return node;
    }

    @Test
    @DisplayName("extracts a single field with text attribute")
    void extractsSingleField() {
        String html = "<html><body><h1 class='title'>Hello World</h1></body></html>";
        Core.HtmlExtractConfig config = new Core.HtmlExtractConfig(
            "{{html}}", "single", null,
            List.of(new Core.HtmlExtractField("title", "h1.title", "text", "none", true, null)),
            true
        );
        HtmlExtractNode node = buildNode(config, html);

        NodeExecutionResult result = node.execute(context);
        assertTrue(result.isSuccess());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
        assertEquals(1, items.size());
        assertEquals("Hello World", items.get(0).get("title"));
        assertEquals(0, ((List<?>) result.output().get("errors")).size());
    }

    @Test
    @DisplayName("extracts href attribute")
    void extractsHrefAttribute() {
        String html = "<html><body><a href='https://example.com'>link</a></body></html>";
        Core.HtmlExtractConfig config = new Core.HtmlExtractConfig(
            "{{html}}", "single", null,
            List.of(new Core.HtmlExtractField("url", "a", "href", "none", false, null)),
            true
        );
        HtmlExtractNode node = buildNode(config, html);

        NodeExecutionResult result = node.execute(context);
        assertTrue(result.isSuccess());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
        assertEquals("https://example.com", items.get(0).get("url"));
    }

    @Test
    @DisplayName("multiple mode iterates root selector")
    void multipleMode() {
        String html = "<ul><li class='item'>A</li><li class='item'>B</li><li class='item'>C</li></ul>";
        Core.HtmlExtractConfig config = new Core.HtmlExtractConfig(
            "{{html}}", "multiple", "li.item",
            List.of(new Core.HtmlExtractField("name", ":root", "text", "none", false, null)),
            true
        );
        HtmlExtractNode node = buildNode(config, html);

        NodeExecutionResult result = node.execute(context);
        assertTrue(result.isSuccess());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
        assertEquals(3, items.size());
        assertEquals(3, result.output().get("count"));
        assertEquals(3, result.output().get("matched_root"));
    }

    @Test
    @DisplayName("missing required field is reported in errors")
    void missingRequiredFieldReported() {
        String html = "<html><body><p>nothing here</p></body></html>";
        Core.HtmlExtractConfig config = new Core.HtmlExtractConfig(
            "{{html}}", "single", null,
            List.of(new Core.HtmlExtractField("title", "h1.missing", "text", "none", true, "fallback")),
            true
        );
        HtmlExtractNode node = buildNode(config, html);

        NodeExecutionResult result = node.execute(context);
        assertTrue(result.isSuccess());

        @SuppressWarnings("unchecked")
        List<String> errors = (List<String>) result.output().get("errors");
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("title"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
        assertEquals("fallback", items.get(0).get("title"));
    }

    @Test
    @DisplayName("uppercase transform applied")
    void uppercaseTransform() {
        String html = "<html><body><span>hello</span></body></html>";
        Core.HtmlExtractConfig config = new Core.HtmlExtractConfig(
            "{{html}}", "single", null,
            List.of(new Core.HtmlExtractField("name", "span", "text", "uppercase", false, null)),
            true
        );
        HtmlExtractNode node = buildNode(config, html);

        NodeExecutionResult result = node.execute(context);
        assertTrue(result.isSuccess());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
        assertEquals("HELLO", items.get(0).get("name"));
    }

    @Test
    @DisplayName("missing sourceHtml fails")
    void missingSourceHtmlFails() {
        Core.HtmlExtractConfig config = new Core.HtmlExtractConfig(
            null, "single", null, List.of(), true
        );
        HtmlExtractNode node = new HtmlExtractNode("core:html_extract", config);
        NodeExecutionResult result = node.execute(context);
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("The success path reports its configuration under the plan's key names")
    @SuppressWarnings("unchecked")
    void successPathReportsConfigurationUnderPlanKeyNames() {
        String html = "<html><body><h1 class='title'>Hello</h1></body></html>";
        Core.HtmlExtractConfig config = new Core.HtmlExtractConfig(
            "{{html}}", "single", "body",
            List.of(new Core.HtmlExtractField("title", "h1.title", "text", "none", true, null)),
            true
        );
        HtmlExtractNode node = buildNode(config, html);

        NodeExecutionResult result = node.execute(context);

        assertTrue(result.isSuccess());
        Map<String, Object> params = (Map<String, Object>) result.output().get("resolved_params");
        assertEquals("single", params.get("extractionMode"));
        assertEquals("body", params.get("rootSelector"));
        assertEquals(true, params.get("cleanWhitespace"));
        assertEquals(html.length(), params.get("sourceHtmlLength"));
        // sourceHtml carries the RESOLVED html (clamped), not the expression: on the
        // success path what the reader needs is what was actually parsed.
        assertEquals(html, params.get("sourceHtml"));
        assertEquals(1, ((List<Object>) params.get("fields")).size());
    }

    @Test
    @DisplayName("A missing sourceHtml still reports the rest of the configuration, absent keys omitted")
    @SuppressWarnings("unchecked")
    void failurePathStillReportsConfiguration() {
        // Built without the shared helper on purpose: this path never resolves a
        // template, so stubbing the adapter would be an unused stub.
        Core.HtmlExtractConfig config = new Core.HtmlExtractConfig(
            "  ", "single", null,
            List.of(new Core.HtmlExtractField("title", "h1", "text", "none", true, null)),
            true
        );
        HtmlExtractNode node = new HtmlExtractNode("core:html_extract", config);

        NodeExecutionResult result = node.execute(context);

        assertFalse(result.isSuccess());
        Map<String, Object> params = (Map<String, Object>) result.output().get("resolved_params");
        // An empty Params column on a red node is the moment the reader most
        // needs to see what it was configured with.
        assertEquals("single", params.get("extractionMode"));
        assertEquals(true, params.get("cleanWhitespace"));
        assertEquals(1, params.get("field_count"));
        // rootSelector was never configured, so it is absent rather than null:
        // a key present with an empty value reads as a setting the author made.
        assertFalse(params.containsKey("rootSelector"));
    }

    @Test
    @DisplayName("a {{...}} defaultValue is resolved when the selector matches nothing, not inserted as its text")
    @SuppressWarnings("unchecked")
    void resolvesTemplatedDefaultValue() {
        String html = "<html><body><p>no price here</p></body></html>";
        Core.HtmlExtractConfig config = new Core.HtmlExtractConfig(
            "{{html}}", "single", null,
            List.of(new Core.HtmlExtractField("price", ".price", "text", "none", false, "{{trigger:in.output.fallback}}")),
            true
        );
        HtmlExtractNode node = new HtmlExtractNode("core:html_extract", config);
        node.setTemplateAdapter(mockTemplateAdapter);
        when(mockTemplateAdapter.resolveTemplates(anyMap(), any())).thenAnswer(invocation -> {
            Map<String, Object> in = invocation.getArgument(0);
            return in.containsKey("__html__") ? Map.of("__html__", html) : Map.of("__v__", "N/A");
        });

        NodeExecutionResult result = node.execute(context);

        assertTrue(result.isSuccess());
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.output().get("items");
        assertEquals("N/A", items.get(0).get("price"));
    }

    @Test
    @DisplayName("a failure AFTER the source resolved reports the resolved HTML, not the expression")
    @SuppressWarnings("unchecked")
    void failureReportsResolvedSourceHtml() {
        String html = "<html><body><div>x</div></body></html>";
        Core.HtmlExtractConfig config = new Core.HtmlExtractConfig(
            "{{core:fetch.output.body}}", "multiple", "div[",
            List.of(new Core.HtmlExtractField("t", "div", "text", "none", false, null)),
            true
        );
        HtmlExtractNode node = buildNode(config, html);

        NodeExecutionResult result = node.execute(context);

        assertFalse(result.isSuccess(), "an unparseable root selector must fail the node");
        Map<String, Object> params = (Map<String, Object>) result.output().get("resolved_params");
        assertEquals(html, params.get("sourceHtml"));
    }

    @Test
    @DisplayName("a {{...}} cleanWhitespace runs with the resolved boolean, not the typed default false")
    @SuppressWarnings("unchecked")
    void templatedCleanWhitespaceIsResolved() {
        String html = "<html><body><h1>Title</h1></body></html>";
        Core.HtmlExtractConfig config = new Core.HtmlExtractConfig(
            "{{html}}", "single", null,
            List.of(new Core.HtmlExtractField("title", "h1", "text", "none", false, null)),
            false
        );
        HtmlExtractNode node = new HtmlExtractNode("core:html_extract", config);
        node.setTemplateAdapter(mockTemplateAdapter);
        node.setDeferredScalars(Map.of("htmlExtract", Map.of("cleanWhitespace", "{{core:x.output.n}}")));
        when(mockTemplateAdapter.resolveTemplates(anyMap(), any())).thenAnswer(invocation -> {
            Map<String, Object> in = invocation.getArgument(0);
            return in.containsKey("__html__") ? Map.of("__html__", html) : Map.of("__v__", true);
        });

        NodeExecutionResult result = node.execute(context);

        assertTrue(result.isSuccess(), String.valueOf(result.errorMessage()));
        Map<String, Object> params = (Map<String, Object>) result.output().get("resolved_params");
        assertEquals(true, params.get("cleanWhitespace"));
    }
}
