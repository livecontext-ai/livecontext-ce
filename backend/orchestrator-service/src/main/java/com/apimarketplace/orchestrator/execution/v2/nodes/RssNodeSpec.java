package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RssNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("RSS")
            .label("RSS")
            .category("core")
            .variablePrefix("core")
            .description("Fetches and parses RSS/Atom feeds")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("items")
                    .type("array")
                    .description("Feed items/entries")
                    .build(),
                OutputFieldDef.builder()
                    .key("channel")
                    .type("object")
                    .description("Feed channel metadata")
                    .build(),
                OutputFieldDef.builder()
                    .key("itemCount")
                    .type("number")
                    .description("Number of items in the feed")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("feedUrl")
                    .type("string")
                    .description("URL of the RSS feed")
                    .build(),
                OutputFieldDef.builder()
                    .key("feedFormat")
                    .type("string")
                    .description("Feed format: rss or atom")
                    .defaultValue("rss")
                    .build(),
                OutputFieldDef.builder()
                    .key("success")
                    .type("boolean")
                    .description("Whether the feed was fetched successfully")
                    .defaultValue(false)
                    .build()
            ))
            .keywords(List.of("rss", "atom", "feed", "news"))
            .build();
    }
}
