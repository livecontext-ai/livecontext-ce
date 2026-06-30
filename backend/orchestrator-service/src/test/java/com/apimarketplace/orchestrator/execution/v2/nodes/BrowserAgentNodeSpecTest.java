package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BrowserAgentNodeSpec - 3-way alignment guard for the BROWSER_AGENT node")
class BrowserAgentNodeSpecTest {

    private final BrowserAgentNodeSpec spec = new BrowserAgentNodeSpec();

    @Test
    @DisplayName("definition: nodeType / label / category / variablePrefix are wired correctly")
    void coreMetadata() {
        NodeDefinition def = spec.definition();

        assertThat(def.nodeType()).isEqualTo("BROWSER_AGENT");
        assertThat(def.label()).isEqualTo("Browser Agent");
        assertThat(def.category()).isEqualTo("agent");
        assertThat(def.variablePrefix()).isEqualTo("agent");
        assertThat(def.description()).contains("Autonomously navigate web pages");
        assertThat(def.keywords())
            .containsExactly("browser", "agent", "navigate", "scrape", "interact");
    }

    @Test
    @DisplayName("outputs: full set is declared so GenericOutputSchemaMapper does not silently drop fields")
    void declaresAllExpectedOutputs() {
        Set<String> keys = outputKeys();

        assertThat(keys).containsExactlyInAnyOrder(
            "node_type",
            "final_result",
            "extracted_data",
            "stop_reason",
            "final_url",
            "pages_visited",
            "steps",
            "screenshots",
            "cost",
            "session_id"
        );
    }

    @Test
    @DisplayName("node_type: defaults to 'BROWSER_AGENT' so split-context routing keeps working when the runner forgets the field")
    void nodeTypeHasDefault() {
        OutputFieldDef nodeType = findOutput("node_type");
        assertThat(nodeType.defaultValue()).isEqualTo("BROWSER_AGENT");
        assertThat(nodeType.hasDefault()).isTrue();
    }

    @Test
    @DisplayName("conditional outputs: extracted_data, stop_reason, final_url, pages_visited, steps, screenshots, cost, session_id, final_result")
    void conditionalOutputs() {
        // Everything except node_type is conditional - present only when the runner emits it.
        for (String key : List.of("final_result", "extracted_data", "stop_reason", "final_url",
                                   "pages_visited", "steps", "screenshots", "cost", "session_id")) {
            assertThat(findOutput(key).isConditional())
                .as("output '%s' should be conditional", key)
                .isTrue();
        }
    }

    @Test
    @DisplayName("output types align with the runner payload (string / object / array)")
    void outputTypes() {
        Map<String, String> expected = Map.of(
            "node_type", "string",
            "final_result", "string",
            "extracted_data", "object",
            "stop_reason", "string",
            "final_url", "string",
            "pages_visited", "array",
            "steps", "array",
            "screenshots", "array",
            "cost", "object",
            "session_id", "string"
        );

        for (Map.Entry<String, String> e : expected.entrySet()) {
            assertThat(findOutput(e.getKey()).type())
                .as("type of '%s'", e.getKey())
                .isEqualTo(e.getValue());
        }
    }

    @Test
    @DisplayName("outputsToDocMap: produces the JSONB shape stored in node_type_documentation.outputs")
    void docMapShape() {
        Map<String, Object> docMap = spec.definition().outputsToDocMap();

        // Every declared output is present.
        assertThat(docMap.keySet()).containsAll(outputKeys());

        // Each entry has at least 'type' and 'description'.
        for (Map.Entry<String, Object> e : docMap.entrySet()) {
            assertThat(e.getValue())
                .as("doc for output '%s'", e.getKey())
                .isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) e.getValue();
            assertThat(entry).containsKeys("type", "description");
        }
    }

    private OutputFieldDef findOutput(String key) {
        return spec.definition().outputs().stream()
            .filter(f -> f.key().equals(key))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Output '" + key + "' not declared in BrowserAgentNodeSpec"));
    }

    private Set<String> outputKeys() {
        return spec.definition().outputs().stream()
            .map(OutputFieldDef::key)
            .collect(java.util.stream.Collectors.toSet());
    }
}
