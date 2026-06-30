package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ErrorTriggerNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("ERROR_TRIGGER")
            .label("Error Trigger")
            .category("trigger")
            .variablePrefix("trigger")
            .description("Triggered when a parent workflow fails or partially succeeds")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("parentWorkflowId")
                    .type("string")
                    .description("UUID of the failed parent workflow")
                    .build(),
                OutputFieldDef.builder()
                    .key("parentRunId")
                    .type("string")
                    .description("Run ID of the failed parent execution")
                    .build(),
                OutputFieldDef.builder()
                    .key("status")
                    .type("string")
                    .description("FAILED or PARTIAL_SUCCESS")
                    .build(),
                OutputFieldDef.builder()
                    .key("errorMessage")
                    .type("string")
                    .description("Failure reason or default message")
                    .build(),
                OutputFieldDef.builder()
                    .key("triggered_at")
                    .type("datetime")
                    .description("ISO timestamp of when the error was dispatched")
                    .defaultValue("__NOW__")
                    .aliases(List.of("triggeredAt"))
                    .build(),
                OutputFieldDef.builder()
                    .key("triggered_by")
                    .type("string")
                    .description("Display name of the owner of the failed parent workflow. Empty when it ran in a system context.")
                    .defaultValue("")
                    .aliases(List.of("triggeredBy"))
                    .build(),
                OutputFieldDef.builder()
                    .key("failedSteps")
                    .type("number")
                    .description("Count of failed steps")
                    .build(),
                OutputFieldDef.builder()
                    .key("completedSteps")
                    .type("number")
                    .description("Count of completed steps")
                    .build(),
                OutputFieldDef.builder()
                    .key("totalSteps")
                    .type("number")
                    .description("Total step count")
                    .build(),
                OutputFieldDef.builder()
                    .key("skippedSteps")
                    .type("number")
                    .description("Count of skipped steps")
                    .build()
            ))
            .keywords(List.of("error", "trigger", "failure", "parent"))
            .build();
    }
}
