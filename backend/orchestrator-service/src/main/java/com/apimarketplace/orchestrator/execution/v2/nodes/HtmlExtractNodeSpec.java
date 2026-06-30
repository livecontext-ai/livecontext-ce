package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import com.apimarketplace.orchestrator.services.persistence.schema.GenericOutputSchemaMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Node specification for the HtmlExtract node (CSS-selector HTML parsing via jsoup).
 */
@Component
public class HtmlExtractNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("HTML_EXTRACT")
            .label("HTML Extract")
            .category("core")
            .variablePrefix("core")
            .description("Parses HTML using CSS selectors and extracts fields into structured items (jsoup)")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("items")
                    .type("array")
                    .description("Array of extracted items (one per matched root element)")
                    .build(),
                OutputFieldDef.builder()
                    .key("count")
                    .type("number")
                    .description("Number of items extracted")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("matched_root")
                    .type("number")
                    .description("Number of root elements matched")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("errors")
                    .type("array")
                    .description("List of human-readable errors for required fields that were missing")
                    .build()
            ))
            .keywords(List.of("html", "extract", "scrape", "parse", "css", "selector", "jsoup"))
            .build();
    }

    @Override
    public Map<String, Object> customTransform(Map<String, Object> backendOutput) {
        Map<String, Object> result = backendOutput == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(backendOutput);
        GenericOutputSchemaMapper.ENGINE_ENVELOPE_KEYS.forEach(result::remove);
        result.putIfAbsent("items", List.of());
        result.putIfAbsent("count", 0);
        result.putIfAbsent("matched_root", 0);
        result.putIfAbsent("errors", List.of());
        return result;
    }
}
