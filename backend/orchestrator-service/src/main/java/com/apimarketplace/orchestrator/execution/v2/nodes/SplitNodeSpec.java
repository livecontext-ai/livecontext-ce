package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SplitNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("SPLIT")
            .label("Split")
            .category("core")
            .variablePrefix("core")
            .description("Splits a collection into parallel execution contexts")
            .outputs(List.of(
                // RUNTIME CONTEXT (body nodes only): current_item / current_index are
                // injected per parallel branch by SplitAwareNodeExecutor and are NOT
                // persisted on the split's own output. Declared here (conditional: no
                // defaultValue) so they are DISCOVERABLE in the node's output schema while
                // staying excluded from the persisted run output. Access from a body node:
                // {{core:<label>.output.current_item}} (or .current_item.<field>) and
                // {{core:<label>.output.current_index}} - shorthands {{item}} / {{index}}.
                OutputFieldDef.builder()
                    .key("current_item")
                    .type("object")
                    .description("RUNTIME (body nodes only): the item for this parallel branch. "
                        + "Use {{core:<label>.output.current_item}} or .current_item.<field>; shorthand {{item}}.")
                    .runtimeOnly(true)
                    .build(),
                OutputFieldDef.builder()
                    .key("current_index")
                    .type("number")
                    .description("RUNTIME (body nodes only): 0-based index of the current item. "
                        + "Use {{core:<label>.output.current_index}}; shorthand {{index}}.")
                    .runtimeOnly(true)
                    .build(),
                OutputFieldDef.builder()
                    .key("items")
                    .type("array")
                    .description("The collection being split (also persisted for inspection)")
                    .build(),
                OutputFieldDef.builder()
                    .key("item_count")
                    .type("number")
                    .description("Number of items in the split")
                    .build(),
                OutputFieldDef.builder()
                    .key("split_id")
                    .type("string")
                    .description("Unique identifier for this split operation")
                    .build(),
                OutputFieldDef.builder()
                    .key("spawn_reason")
                    .type("string")
                    .description("Why the split completed: items_spawned (N parallel branches created) or empty_list")
                    .build(),
                OutputFieldDef.builder()
                    .key("terminated")
                    .type("boolean")
                    .description("Always true: the split node completes immediately after spawning its branches")
                    .build()
            ))
            .keywords(List.of("split", "each", "iterate", "parallel"))
            .build();
    }
}
