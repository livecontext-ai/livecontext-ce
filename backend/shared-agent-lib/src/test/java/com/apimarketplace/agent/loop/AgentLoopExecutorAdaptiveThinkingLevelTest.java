package com.apimarketplace.agent.loop;

import com.apimarketplace.agent.domain.ThinkingLevel;
import com.apimarketplace.agent.domain.ToolDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 1b.1 chokepoint - pin the AUTO thinking-level wiring inside
 * {@link AgentLoopExecutor#resolveAdaptiveThinkingLevel(AgentLoopContext, List)}.
 * The resolver itself ({@link ThinkingLevel#auto}) has its own dedicated spec in
 * {@code ThinkingLevelAutoResolutionTest}; this file asserts only the wiring:
 * context {@code purpose}, the {@code tools} list size, and the {@code userPrompt}
 * length reach the resolver unchanged so every CLASSIFY/GUARDRAIL turn lands on
 * {@link ThinkingLevel#MEDIUM} and MAIN turns pick the right tier.
 */
@DisplayName("AgentLoopExecutor.resolveAdaptiveThinkingLevel (Stage 1b.1 chokepoint)")
class AgentLoopExecutorAdaptiveThinkingLevelTest {

    private static final ToolDefinition TOOL_A = ToolDefinition.builder().name("a").description("").build();
    private static final ToolDefinition TOOL_B = ToolDefinition.builder().name("b").description("").build();
    private static final ToolDefinition TOOL_C = ToolDefinition.builder().name("c").description("").build();

    @Test
    @DisplayName("CLASSIFY context → MEDIUM regardless of tools or userPrompt")
    void classifyPurposeAlwaysMedium() {
        AgentLoopContext ctx = AgentLoopContext.builder()
                .provider("openai")
                .purpose(CallPurpose.CLASSIFY)
                .userPrompt("x".repeat(5000))
                .build();

        assertThat(AgentLoopExecutor.resolveAdaptiveThinkingLevel(ctx, null))
                .isEqualTo(ThinkingLevel.MEDIUM);
        assertThat(AgentLoopExecutor.resolveAdaptiveThinkingLevel(ctx, List.of(TOOL_A, TOOL_B, TOOL_C)))
                .isEqualTo(ThinkingLevel.MEDIUM);
    }

    @Test
    @DisplayName("GUARDRAIL context → MEDIUM regardless of tools or userPrompt")
    void guardrailPurposeAlwaysMedium() {
        AgentLoopContext ctx = AgentLoopContext.builder()
                .provider("openai")
                .purpose(CallPurpose.GUARDRAIL)
                .userPrompt("x".repeat(5000))
                .build();

        assertThat(AgentLoopExecutor.resolveAdaptiveThinkingLevel(ctx, null))
                .isEqualTo(ThinkingLevel.MEDIUM);
        assertThat(AgentLoopExecutor.resolveAdaptiveThinkingLevel(ctx, List.of(TOOL_A, TOOL_B, TOOL_C)))
                .isEqualTo(ThinkingLevel.MEDIUM);
    }

    @Test
    @DisplayName("MAIN + short userPrompt (<50) + ≤2 tools → LOW")
    void mainShortTurnIsLow() {
        AgentLoopContext ctx = AgentLoopContext.builder()
                .provider("openai")
                .purpose(CallPurpose.MAIN)
                .userPrompt("hello")
                .build();

        assertThat(AgentLoopExecutor.resolveAdaptiveThinkingLevel(ctx, List.of(TOOL_A, TOOL_B)))
                .isEqualTo(ThinkingLevel.LOW);
        assertThat(AgentLoopExecutor.resolveAdaptiveThinkingLevel(ctx, null))
                .isEqualTo(ThinkingLevel.LOW);
    }

    @Test
    @DisplayName("MAIN + long userPrompt (≥50 chars) → HIGH")
    void mainLongUserPromptIsHigh() {
        AgentLoopContext ctx = AgentLoopContext.builder()
                .provider("openai")
                .purpose(CallPurpose.MAIN)
                .userPrompt("x".repeat(50))
                .build();

        assertThat(AgentLoopExecutor.resolveAdaptiveThinkingLevel(ctx, null))
                .isEqualTo(ThinkingLevel.HIGH);
    }

    @Test
    @DisplayName("MAIN + >2 tools → HIGH even with short userPrompt")
    void mainManyToolsIsHigh() {
        AgentLoopContext ctx = AgentLoopContext.builder()
                .provider("openai")
                .purpose(CallPurpose.MAIN)
                .userPrompt("hi")
                .build();

        assertThat(AgentLoopExecutor.resolveAdaptiveThinkingLevel(ctx, List.of(TOOL_A, TOOL_B, TOOL_C)))
                .isEqualTo(ThinkingLevel.HIGH);
    }

    @Test
    @DisplayName("null purpose → MAIN behavior (tiny turn → LOW)")
    void nullPurposeTreatedAsMainLow() {
        AgentLoopContext ctx = AgentLoopContext.builder()
                .provider("openai")
                .purpose(null)
                .userPrompt("hi")
                .build();

        assertThat(AgentLoopExecutor.resolveAdaptiveThinkingLevel(ctx, null))
                .isEqualTo(ThinkingLevel.LOW);
    }

    @Test
    @DisplayName("null userPrompt treated as 0 chars - MAIN short turn → LOW")
    void nullUserPromptTreatedAsZeroChars() {
        AgentLoopContext ctx = AgentLoopContext.builder()
                .provider("openai")
                .purpose(CallPurpose.MAIN)
                .userPrompt(null)
                .build();

        assertThat(AgentLoopExecutor.resolveAdaptiveThinkingLevel(ctx, null))
                .isEqualTo(ThinkingLevel.LOW);
    }

    @Test
    @DisplayName("null tools list treated as 0 tools - MAIN short turn → LOW")
    void nullToolsListTreatedAsZero() {
        AgentLoopContext ctx = AgentLoopContext.builder()
                .provider("openai")
                .purpose(CallPurpose.MAIN)
                .userPrompt("hi")
                .build();

        assertThat(AgentLoopExecutor.resolveAdaptiveThinkingLevel(ctx, null))
                .isEqualTo(ThinkingLevel.LOW);
    }

    @Test
    @DisplayName("empty tools list is 0 tools - MAIN short turn → LOW")
    void emptyToolsListIsZero() {
        AgentLoopContext ctx = AgentLoopContext.builder()
                .provider("openai")
                .purpose(CallPurpose.MAIN)
                .userPrompt("hi")
                .build();

        assertThat(AgentLoopExecutor.resolveAdaptiveThinkingLevel(ctx, Collections.emptyList()))
                .isEqualTo(ThinkingLevel.LOW);
    }

    @Test
    @DisplayName("caller-pinned thinkingLevel wins over auto-resolution - keeps Anthropic prompt cache warm")
    void callerPinnedLevelOverridesAutoResolution() {
        // Context shape would auto-resolve to HIGH (MAIN + 5 tools + 1000 chars),
        // but the caller pinned LOW - e.g. conversation-service decided on turn 1
        // that this Claude conversation should stay on LOW for the duration.
        AgentLoopContext pinnedLow = AgentLoopContext.builder()
                .provider("anthropic")
                .purpose(CallPurpose.MAIN)
                .userPrompt("x".repeat(1000))
                .thinkingLevel(ThinkingLevel.LOW)
                .build();

        assertThat(AgentLoopExecutor.resolveAdaptiveThinkingLevel(pinnedLow,
                List.of(TOOL_A, TOOL_B, TOOL_C)))
                .isEqualTo(ThinkingLevel.LOW);

        // And the symmetric case: context would auto-resolve to LOW, but caller pinned HIGH.
        AgentLoopContext pinnedHigh = AgentLoopContext.builder()
                .provider("anthropic")
                .purpose(CallPurpose.MAIN)
                .userPrompt("hi")
                .thinkingLevel(ThinkingLevel.HIGH)
                .build();

        assertThat(AgentLoopExecutor.resolveAdaptiveThinkingLevel(pinnedHigh, null))
                .isEqualTo(ThinkingLevel.HIGH);
    }

    @Test
    @DisplayName("caller-pinned override also beats the CLASSIFY → MEDIUM default")
    void callerPinnedLevelWinsOverClassifyDefault() {
        // CLASSIFY would otherwise force MEDIUM; the override still wins. This
        // path is not used in production today (CLASSIFY callers don't pin),
        // but we pin the contract: pinned value ALWAYS wins, unconditionally,
        // so future callers don't discover a silent exception the hard way.
        AgentLoopContext ctx = AgentLoopContext.builder()
                .provider("anthropic")
                .purpose(CallPurpose.CLASSIFY)
                .userPrompt("extract intent")
                .thinkingLevel(ThinkingLevel.LOW)
                .build();

        assertThat(AgentLoopExecutor.resolveAdaptiveThinkingLevel(ctx, null))
                .isEqualTo(ThinkingLevel.LOW);
    }

    @Test
    @DisplayName("boundary: tools=2, chars=49 → LOW; tools=3 or chars=50 → HIGH")
    void mainBoundaries() {
        AgentLoopContext edgeLow = AgentLoopContext.builder()
                .provider("openai")
                .purpose(CallPurpose.MAIN)
                .userPrompt("x".repeat(49))
                .build();
        assertThat(AgentLoopExecutor.resolveAdaptiveThinkingLevel(edgeLow, List.of(TOOL_A, TOOL_B)))
                .isEqualTo(ThinkingLevel.LOW);

        assertThat(AgentLoopExecutor.resolveAdaptiveThinkingLevel(edgeLow, List.of(TOOL_A, TOOL_B, TOOL_C)))
                .isEqualTo(ThinkingLevel.HIGH);

        AgentLoopContext atCharThreshold = AgentLoopContext.builder()
                .provider("openai")
                .purpose(CallPurpose.MAIN)
                .userPrompt("x".repeat(50))
                .build();
        assertThat(AgentLoopExecutor.resolveAdaptiveThinkingLevel(atCharThreshold, List.of(TOOL_A)))
                .isEqualTo(ThinkingLevel.HIGH);
    }
}
