package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CompareDatasetsNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("COMPARE_DATASETS")
            .label("Compare Datasets")
            .category("core")
            .variablePrefix("core")
            .description("Compares two datasets and finds matches/differences")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("matched")
                    .type("array")
                    .description("Rows that match between both datasets")
                    .defaultValue(List.of())
                    .build(),
                OutputFieldDef.builder()
                    .key("onlyInA")
                    .type("array")
                    .description("Rows only in dataset A")
                    .defaultValue(List.of())
                    .build(),
                OutputFieldDef.builder()
                    .key("onlyInB")
                    .type("array")
                    .description("Rows only in dataset B")
                    .defaultValue(List.of())
                    .build(),
                OutputFieldDef.builder()
                    .key("matchedCount")
                    .type("number")
                    .description("Number of matched rows")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("onlyInACount")
                    .type("number")
                    .description("Number of rows only in A")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("onlyInBCount")
                    .type("number")
                    .description("Number of rows only in B")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("totalA")
                    .type("number")
                    .description("Total rows in dataset A")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("totalB")
                    .type("number")
                    .description("Total rows in dataset B")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("matchFields")
                    .type("array")
                    .description("Fields used for matching")
                    .defaultValue(List.of())
                    .build(),
                OutputFieldDef.builder()
                    .key("success")
                    .type("boolean")
                    .description("Whether the comparison was successful")
                    .defaultValue(true)
                    .build()
            ))
            .keywords(List.of("compare", "diff", "match", "dataset"))
            .build();
    }
}
