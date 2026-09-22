package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.UsageInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How many tokens the last request occupied in the model's context window.
 *
 * <p>The distinction this class exists for is easy to erase by accident, so it is asserted rather
 * than left to the javadoc: Anthropic reports cached tokens SEPARATELY from {@code input_tokens}
 * (they must be added), while OpenAI reports them as a SUBSET of {@code prompt_tokens} (adding
 * them double-counts; Gemini's {@code cachedContentTokenCount} has the same subset shape and is
 * likewise excluded). Getting it wrong in either direction is silent - the number just comes out
 * too small or too large, and the monitor mis-judges occupancy with nothing to show for it.
 */
@DisplayName("LoopExecutionState - context tokens occupied by the last request")
class LoopExecutionStateContextTokensTest {

    @Test
    @DisplayName("Anthropic: cache-read and cache-creation tokens are ADDED to the fresh input")
    void anthropicCacheTokensAreAdded() {
        LoopExecutionState state = stateWith(UsageInfo.builder()
            .promptTokens(2_000)          // input_tokens: only what was read fresh
            .cacheReadInputTokens(930_000)
            .cacheCreationInputTokens(18_000)
            .completionTokens(40)
            .build());

        assertThat(state.getLastIterationContextTokens())
            .as("ClaudeProvider caches the system blocks and the last history message, so a "
                + "nearly-full conversation reports a tiny input_tokens; counting only that "
                + "leaves the flagship provider permanently under the alarm threshold")
            .isEqualTo(950_000L);
    }

    @Test
    @DisplayName("OpenAI: cachedTokens is a SUBSET of promptTokens and must not be added")
    void openAiCachedTokensAreNotDoubleCounted() {
        LoopExecutionState state = stateWith(UsageInfo.builder()
            .promptTokens(120_000)
            .cachedTokens(100_000)        // already inside promptTokens
            .completionTokens(40)
            .build());

        assertThat(state.getLastIterationContextTokens())
            .as("adding cachedTokens here would report 220000 for a 120000-token request and "
                + "fire a critical alert on a conversation at half that size")
            .isEqualTo(120_000L);
    }

    @Test
    @DisplayName("a provider that reports nothing but prompt tokens is counted as-is")
    void plainPromptTokensAreCountedAsIs() {
        LoopExecutionState state = stateWith(UsageInfo.builder()
            .promptTokens(45_000)
            .completionTokens(40)
            .build());

        assertThat(state.getLastIterationContextTokens()).isEqualTo(45_000L);
    }

    @Test
    @DisplayName("before any iteration completes the occupancy is 0, not an exception")
    void noIterationYetReportsZero() {
        assertThat(new LoopExecutionState("run-1", 10, 0).getLastIterationContextTokens())
            .as("the monitor runs on iteration 1 too, before any usage exists")
            .isZero();
    }

    @Test
    @DisplayName("only the LAST iteration counts - occupancy is not a running total")
    void onlyTheLastIterationCounts() {
        LoopExecutionState state = new LoopExecutionState("run-1", 10, 0);
        state.trackUsage(UsageInfo.builder().promptTokens(10_000).completionTokens(5).build());
        state.trackUsage(UsageInfo.builder().promptTokens(12_000).completionTokens(5).build());

        assertThat(state.getLastIterationContextTokens())
            .as("a window holds one request, not the sum of every request in the run; summing "
                + "would cross any threshold purely by iterating")
            .isEqualTo(12_000L);
    }

    @Test
    @DisplayName("null token counts are treated as zero rather than throwing")
    void nullCountsAreTreatedAsZero() {
        LoopExecutionState state = stateWith(UsageInfo.builder().completionTokens(5).build());

        assertThat(state.getLastIterationContextTokens()).isZero();
    }

    private static LoopExecutionState stateWith(UsageInfo usage) {
        LoopExecutionState state = new LoopExecutionState("run-1", 10, 0);
        state.trackUsage(usage);
        return state;
    }
}
