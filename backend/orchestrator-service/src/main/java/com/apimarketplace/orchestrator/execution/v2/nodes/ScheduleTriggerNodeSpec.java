package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ScheduleTriggerNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("SCHEDULE_TRIGGER")
            .label("Schedule Trigger")
            .category("trigger")
            .variablePrefix("trigger")
            .description("Triggered on a schedule (cron)")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("triggered_at")
                    .type("datetime")
                    .description("ISO timestamp when triggered")
                    .defaultValue("__NOW__")
                    .aliases(List.of("triggeredAt"))
                    .build(),
                OutputFieldDef.builder()
                    .key("execution_count")
                    .type("number")
                    .description("Number of times this schedule has fired")
                    .aliases(List.of("executionCount"))
                    .build(),
                OutputFieldDef.builder()
                    .key("next_execution")
                    .type("datetime")
                    .description("Next scheduled execution time")
                    .aliases(List.of("nextExecution", "nextScheduled"))
                    .build(),
                OutputFieldDef.builder()
                    .key("triggered_by")
                    .type("string")
                    .description("Display name of the workflow owner (schedule fires autonomously - still carries the owner's identity for variable_mapping).")
                    .defaultValue("")
                    .aliases(List.of("triggeredBy"))
                    .build()
            ))
            .keywords(List.of("schedule", "cron", "timer", "trigger"))
            .build();
    }
}
