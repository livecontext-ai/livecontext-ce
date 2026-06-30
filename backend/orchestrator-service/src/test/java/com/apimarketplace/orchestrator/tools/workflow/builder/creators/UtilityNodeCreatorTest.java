package com.apimarketplace.orchestrator.tools.workflow.builder.creators;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.tools.workflow.builder.ResponseOptimizer;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * Verifies that filter/sort node creators accept the documented input aliases
 * (input, inputExpression, items, list) and store them under params.input.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UtilityNodeCreator - filter/sort input alias handling")
class UtilityNodeCreatorTest {

    @Mock private WorkflowBuilderSessionStore sessionStore;
    @Mock private ResponseOptimizer responseOptimizer;
    @Mock private NodeLibraryService nodeLibraryService;
    @Mock private WorkflowRepository workflowRepository;

    private UtilityNodeCreator creator;
    private WorkflowBuilderSession session;

    @BeforeEach
    void setUp() {
        creator = new UtilityNodeCreator(sessionStore, responseOptimizer, nodeLibraryService, workflowRepository);
        session = WorkflowBuilderSession.builder()
            .sessionId("s")
            .tenantId("t")
            .workflowName("w")
            .createdAt(Instant.now())
            .updatedAt(Instant.now())
            .build();
        // Add a trigger so creators don't bail out
        Map<String, Object> trig = new LinkedHashMap<>();
        trig.put("label", "Start");
        trig.put("id", "trigger:start");
        trig.put("type", "webhook");
        session.getTriggers().add(trig);

        lenient().when(nodeLibraryService.findByType(anyString())).thenReturn(Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> firstCoreParams() {
        Map<String, Object> node = session.getCores().get(0);
        return (Map<String, Object>) node.get("params");
    }

    private Map<String, Object> baseFilterParams(String inputKey, String inputValue) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "My Filter");
        p.put("connect_after", "Start");
        p.put("conditions", List.of(Map.of("field", "status", "operator", "equals", "value", "active")));
        p.put(inputKey, inputValue);
        return p;
    }

    @Test
    @DisplayName("filter: 'inputExpression' alias is stored under params.input")
    void filterInputExpressionAlias() {
        ToolExecutionResult r = creator.executeAddFilter(session, baseFilterParams("inputExpression", "{{trigger:start.output.rows}}"));
        assertThat(r.success()).isTrue();
        assertThat(firstCoreParams().get("input")).isEqualTo("{{trigger:start.output.rows}}");
    }

    @Test
    @DisplayName("filter: 'items' alias is stored under params.input")
    void filterItemsAlias() {
        ToolExecutionResult r = creator.executeAddFilter(session, baseFilterParams("items", "{{trigger:start.output.rows}}"));
        assertThat(r.success()).isTrue();
        assertThat(firstCoreParams().get("input")).isEqualTo("{{trigger:start.output.rows}}");
    }

    @Test
    @DisplayName("filter: 'list' alias is stored under params.input")
    void filterListAlias() {
        ToolExecutionResult r = creator.executeAddFilter(session, baseFilterParams("list", "{{trigger:start.output.rows}}"));
        assertThat(r.success()).isTrue();
        assertThat(firstCoreParams().get("input")).isEqualTo("{{trigger:start.output.rows}}");
    }

    @Test
    @DisplayName("filter: canonical 'input' is stored under params.input")
    void filterCanonicalInput() {
        ToolExecutionResult r = creator.executeAddFilter(session, baseFilterParams("input", "{{trigger:start.output.rows}}"));
        assertThat(r.success()).isTrue();
        assertThat(firstCoreParams().get("input")).isEqualTo("{{trigger:start.output.rows}}");
    }

    @Test
    @DisplayName("filter: no input → node has no params (caught by CoreValidator at validate time)")
    void filterWithoutInput() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "My Filter");
        p.put("connect_after", "Start");
        p.put("conditions", List.of(Map.of("field", "status", "operator", "equals", "value", "active")));
        // no input
        ToolExecutionResult r = creator.executeAddFilter(session, p);
        assertThat(r.success()).isTrue();
        // node has no 'params' key - CoreValidator.hasInputField will return false
        assertThat(session.getCores().get(0).containsKey("params")).isFalse();
    }

    private Map<String, Object> baseSortParams(String inputKey, String inputValue) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "My Sort");
        p.put("connect_after", "Start");
        p.put("fields", List.of(Map.of("field", "name", "direction", "asc")));
        p.put(inputKey, inputValue);
        return p;
    }

    @Test
    @DisplayName("sort: 'list' alias is stored under params.input")
    void sortListAlias() {
        ToolExecutionResult r = creator.executeAddSort(session, baseSortParams("list", "{{trigger:start.output.rows}}"));
        assertThat(r.success()).isTrue();
        assertThat(firstCoreParams().get("input")).isEqualTo("{{trigger:start.output.rows}}");
    }

    @Test
    @DisplayName("sort: 'inputExpression' alias is stored under params.input")
    void sortInputExpressionAlias() {
        ToolExecutionResult r = creator.executeAddSort(session, baseSortParams("inputExpression", "{{trigger:start.output.rows}}"));
        assertThat(r.success()).isTrue();
        assertThat(firstCoreParams().get("input")).isEqualTo("{{trigger:start.output.rows}}");
    }

    // #X1: add_node(type='xml', preserveAttributes=true) used to silently drop the flag.
    // The creator now reads preserveAttributes (camelCase and snake_case) and forwards it
    // into the xmlConfig sub-object where XmlConfig.preserveAttributes() reads it.
    @SuppressWarnings("unchecked")
    private Map<String, Object> firstXmlConfig() {
        Map<String, Object> node = session.getCores().get(0);
        return (Map<String, Object>) node.get("xml");
    }

    private Map<String, Object> baseXmlParams() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "My Xml");
        p.put("connect_after", "Start");
        p.put("operation", "xmlToJson");
        p.put("value", "<root><order id='42'/></root>");
        return p;
    }

    @Test
    @DisplayName("xml: preserveAttributes=true (camelCase) is stored in xmlConfig")
    void xmlPreserveAttributesCamelCase() {
        Map<String, Object> p = baseXmlParams();
        p.put("preserveAttributes", true);
        ToolExecutionResult r = creator.executeAddXml(session, p);
        assertThat(r.success()).isTrue();
        assertThat(firstXmlConfig().get("preserveAttributes")).isEqualTo(true);
    }

    @Test
    @DisplayName("xml: preserve_attributes=true (snake_case) is stored in xmlConfig")
    void xmlPreserveAttributesSnakeCase() {
        Map<String, Object> p = baseXmlParams();
        p.put("preserve_attributes", true);
        ToolExecutionResult r = creator.executeAddXml(session, p);
        assertThat(r.success()).isTrue();
        assertThat(firstXmlConfig().get("preserveAttributes")).isEqualTo(true);
    }

    @Test
    @DisplayName("xml: preserveAttributes=false is preserved (not dropped)")
    void xmlPreserveAttributesFalse() {
        Map<String, Object> p = baseXmlParams();
        p.put("preserveAttributes", false);
        ToolExecutionResult r = creator.executeAddXml(session, p);
        assertThat(r.success()).isTrue();
        assertThat(firstXmlConfig().get("preserveAttributes")).isEqualTo(false);
    }

    @Test
    @DisplayName("xml: preserveAttributes absent → key omitted (record default of false applies)")
    void xmlPreserveAttributesAbsent() {
        ToolExecutionResult r = creator.executeAddXml(session, baseXmlParams());
        assertThat(r.success()).isTrue();
        assertThat(firstXmlConfig().containsKey("preserveAttributes")).isFalse();
    }

    // ---- wait: duration validation (D5 audit split MISSING vs INVALID) -----

    @Test
    @DisplayName("wait: missing duration → MISSING_PARAMETER")
    void waitMissingDurationIsMissingParameter() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Wait5s");
        p.put("connect_after", "Start");
        ToolExecutionResult r = creator.executeAddWait(session, p);
        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
    }

    @Test
    @DisplayName("wait: negative duration → INVALID_PARAMETER_VALUE (not MISSING)")
    void waitNegativeDurationIsInvalidParameterValue() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("label", "Wait5s");
        p.put("connect_after", "Start");
        p.put("duration", -1000);
        ToolExecutionResult r = creator.executeAddWait(session, p);
        assertThat(r.success()).isFalse();
        assertThat(r.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
    }
}
