package com.apimarketplace.agent.tools.validation;

import com.apimarketplace.agent.domain.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 4a.5 - pin the 500-token budget on the serialised
 * {@link ToolParamValidationError} payload (R47).
 *
 * <p>The worst case the plan calls out is {@code agent(action='create_task')}
 * with the full required-params list. A naive implementation that inlined
 * the whole schema would blow the budget; this test is a guard against
 * regressing toward that. The implementation defends the budget by
 * truncating {@link ToolParamValidationError#oneLineExample} first (R47),
 * since the example grows unboundedly with schema size while every other
 * field is O(1) or names-only.
 *
 * <p><b>Token estimate.</b> Production code uses provider-specific
 * tokenizers; for a cross-provider upper bound we use the common
 * <em>chars / 4</em> heuristic (GPT tokenizers average ~4 chars per
 * token for ASCII identifiers). Claude's tokenizer is tighter (~3.5
 * chars/token), so a chars/4 count slightly overestimates - worth
 * tightening this heuristic if we ever drop the budget below 300 tokens,
 * but at current headroom (~200-token typical) the margin is safe.
 */
@DisplayName("ToolParamValidationError - serialised payload < 500 tokens (Stage 4a.5)")
class ToolParamValidationErrorTokenBudgetTest {

    private static final int TOKEN_BUDGET = 500;
    private static final double CHARS_PER_TOKEN = 4.0;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("worst-case agent(action='create_task') with full required-params list stays under 500 tokens")
    void worstCaseUnderBudget() throws Exception {
        // Worst-case action per the plan: agent(action='create_task') with
        // a generous required-params list covering everything an async task
        // might need.
        List<String> worstCaseRequired = List.of(
                "action",
                "title",
                "description",
                "assignee_agent_id",
                "priority",
                "due_date",
                "context_summary",
                "parent_task_id",
                "attachments_url",
                "budget_credits",
                "callback_webhook_url",
                "notification_recipients"
        );
        ToolDefinition def = ToolDefinition.builder()
                .name("agent")
                .requiredParameters(worstCaseRequired)
                .build();

        ToolParamValidationError err = ToolParamValidationError.schemaNotLoaded("agent", "create_task", def);
        String json = objectMapper.writeValueAsString(err);

        int estimatedTokens = (int) Math.ceil(json.length() / CHARS_PER_TOKEN);
        assertThat(estimatedTokens)
                .as("serialised error should stay well under the %d-token budget (was ~%d tokens, %d chars)",
                        TOKEN_BUDGET, estimatedTokens, json.length())
                .isLessThan(TOKEN_BUDGET);
    }

    @Test
    @DisplayName("oneLineExample is truncated before the required-params list when the schema is huge")
    void truncatesExampleFirst() {
        // 40 required params - no way this fits in 240 chars of example.
        List<String> huge = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) huge.add("parameter_" + i);

        ToolDefinition def = ToolDefinition.builder()
                .name("mega_tool")
                .requiredParameters(huge)
                .build();

        ToolParamValidationError err = ToolParamValidationError.schemaNotLoaded("mega_tool", "do_everything", def);

        // Example is truncated to the soft cap and ends with ", ...)".
        assertThat(err.oneLineExample().length())
                .isLessThanOrEqualTo(ToolParamValidationError.ONE_LINE_EXAMPLE_MAX_CHARS);
        assertThat(err.oneLineExample()).endsWith(", ...)");

        // The requiredParams list itself is NOT trimmed - it's names-only
        // and stays cheap even at 40 entries. The LLM sees the full list.
        assertThat(err.requiredParams()).hasSize(40);
    }

    @Test
    @DisplayName("typical error (3-5 required params) stays well under the budget")
    void typicalSmallPayload() throws Exception {
        ToolDefinition def = ToolDefinition.builder()
                .name("agent")
                .requiredParameters(List.of("action", "name", "type"))
                .build();

        ToolParamValidationError err = ToolParamValidationError.schemaNotLoaded("agent", "create", def);
        String json = objectMapper.writeValueAsString(err);

        int estimatedTokens = (int) Math.ceil(json.length() / CHARS_PER_TOKEN);
        // Typical errors should be on the order of ~200 tokens - assert
        // comfortably below the budget so accidental regressions surface.
        assertThat(estimatedTokens).isLessThan(250);
    }
}
