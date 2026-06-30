package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AgentNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("AGENT")
            .label("Agent")
            .category("agent")
            .variablePrefix("agent")
            .description("Executes an AI agent with tools and instructions")
            .outputs(List.of(
                OutputFieldDef.builder()
                    .key("response")
                    .type("string")
                    .description("Agent's text response")
                    .aliases(List.of("content"))
                    .build(),
                OutputFieldDef.builder()
                    .key("tokens_used")
                    .type("number")
                    .description("Total tokens consumed")
                    .build(),
                OutputFieldDef.builder()
                    .key("prompt_tokens")
                    .type("number")
                    .description("Prompt/input tokens consumed")
                    .aliases(List.of("promptTokens"))
                    .build(),
                OutputFieldDef.builder()
                    .key("completion_tokens")
                    .type("number")
                    .description("Completion/output tokens consumed")
                    .aliases(List.of("completionTokens"))
                    .build(),
                OutputFieldDef.builder()
                    .key("tool_calls")
                    .type("number")
                    .description("Number of tool calls made by the agent")
                    .defaultValue(0)
                    .build(),
                OutputFieldDef.builder()
                    .key("tool_calls_detail")
                    .type("array")
                    .description("Detailed list of tool calls made by the agent (name, arguments, result)")
                    .defaultValue(List.of())
                    .aliases(List.of("tool_calls_detail"))
                    .build(),
                OutputFieldDef.builder()
                    .key("iterations_used")
                    .type("number")
                    .description("Number of LLM iterations used")
                    .aliases(List.of("iterations"))
                    .build(),
                OutputFieldDef.builder()
                    .key("duration_ms")
                    .type("number")
                    .description("Execution duration in milliseconds")
                    .aliases(List.of("durationMs"))
                    .build(),
                OutputFieldDef.builder()
                    .key("model")
                    .type("string")
                    .description("LLM model used")
                    .build(),
                OutputFieldDef.builder()
                    .key("provider")
                    .type("string")
                    .description("LLM provider used")
                    .build(),
                // split_item_count preserved for consistency with sync path (SplitAwareNodeExecutor
                // injects it on every node in split context) and to support template references
                // like {{agent.output.split_item_count}}.
                OutputFieldDef.builder()
                    .key("split_item_count")
                    .type("number")
                    .description("Total number of items when executed inside a split context")
                    .build()
            ))
            .keywords(List.of("agent", "ai", "llm", "chat"))
            .build();
    }
}
