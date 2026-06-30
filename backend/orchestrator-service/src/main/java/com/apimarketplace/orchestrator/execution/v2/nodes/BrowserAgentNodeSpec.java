package com.apimarketplace.orchestrator.execution.v2.nodes;

import com.apimarketplace.agent.domain.NodeDefinition;
import com.apimarketplace.agent.domain.NodeSpec;
import com.apimarketplace.agent.domain.OutputFieldDef;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Node spec for the BROWSER_AGENT node - autonomous LLM-driven browser sessions.
 *
 * <p>Output schema is the source of truth for the 3-way alignment
 * (mapper / DB doc / frontend schema). Field names use snake_case to match the
 * runner's payload and the LLM-facing MCP tool surface.</p>
 */
@Component
public class BrowserAgentNodeSpec implements NodeSpec {

    @Override
    public NodeDefinition definition() {
        return NodeDefinition.builder()
            .nodeType("BROWSER_AGENT")
            .label("Browser Agent")
            .category("agent")
            .variablePrefix("agent")
            .description("Autonomously navigate web pages with an LLM-driven browser agent")
            .outputs(List.of(
                // node_type must be preserved: ReadyNodeCalculator uses it for split-context
                // routing, mirroring ClassifyNodeSpec.
                OutputFieldDef.builder()
                    .key("node_type")
                    .type("string")
                    .description("Internal node type identifier (always 'BROWSER_AGENT')")
                    .defaultValue("BROWSER_AGENT")
                    .build(),
                OutputFieldDef.builder()
                    .key("final_result")
                    .type("string")
                    .description("Natural-language summary the agent produced when terminating "
                        + "(e.g. 'Logged in and downloaded invoice INV-2042'). Always present, "
                        + "even on partial / error stops - describes what the agent managed to do.")
                    .build(),
                OutputFieldDef.builder()
                    .key("extracted_data")
                    .type("object")
                    .description("Structured data the agent collected, validated against "
                        + "expected_output_schema if one was provided. Null when the task did "
                        + "not request structured extraction.")
                    .build(),
                OutputFieldDef.builder()
                    .key("stop_reason")
                    .type("string")
                    .description("Free-form termination reason. One of: COMPLETED, MAX_STEPS, "
                        + "USER_TAKEOVER, LLM_FAILED, SCHEMA_MISMATCH, DOMAIN_BLOCKED, TIMEOUT, "
                        + "CANCELLED, BUDGET_EXHAUSTED.")
                    .build(),
                OutputFieldDef.builder()
                    .key("final_url")
                    .type("string")
                    .description("URL the browser was on when the session ended.")
                    .build(),
                OutputFieldDef.builder()
                    .key("pages_visited")
                    .type("array")
                    .description("Ordered list of URLs the agent navigated through during the session.")
                    .build(),
                OutputFieldDef.builder()
                    .key("steps")
                    .type("array")
                    .description("Per-step trace: { step_index, action, action_args, target, url, "
                        + "eval, memory, next_goal, screenshot_key, tokens_in, tokens_out, "
                        + "duration_ms }. Use for replay / debugging.")
                    .build(),
                OutputFieldDef.builder()
                    .key("screenshots")
                    .type("array")
                    .description("Ordered list of MinIO screenshot keys captured during the session "
                        + "(governed by screenshot_policy). Empty when policy='off'.")
                    .build(),
                OutputFieldDef.builder()
                    .key("cost")
                    .type("object")
                    .description("Cost breakdown: { tokens_in, tokens_out, llm_calls, "
                        + "browser_seconds, cost_usd }. tokens_* count LLM steering tokens; "
                        + "browser_seconds is wall-clock time the Chromium process held the lock.")
                    .build(),
                OutputFieldDef.builder()
                    .key("session_id")
                    .type("string")
                    .description("UUID identifying this session - pass to browse_status / "
                        + "browse_intervene / browse_abort / browse_screenshot to control or "
                        + "inspect the session while it is still in flight.")
                    .build()
            ))
            .keywords(List.of("browser", "agent", "navigate", "scrape", "interact"))
            .build();
    }
}
