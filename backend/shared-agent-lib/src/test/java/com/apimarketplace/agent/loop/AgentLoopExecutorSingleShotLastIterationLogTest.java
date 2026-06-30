package com.apimarketplace.agent.loop;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.UsageInfo;
// AgentLoopContext lives in the same package (com.apimarketplace.agent.loop) and
// resolves without an explicit import. Earlier draft imported it from
// com.apimarketplace.agent.domain which doesn't exist - only surfaced when the
// shared-agent-lib testCompile ran during `deploy-direct.sh --service=agent`
// (the earlier `mvn test 2>&1 | tail` had masked the Maven failure exit code
// via the tail-pipe).
import com.apimarketplace.agent.logging.AgentLogger;
import com.apimarketplace.agent.provider.LLMProvider;
import com.apimarketplace.agent.tool.ToolExecutionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Regression: AgentLoopExecutor emitted {@code WARN "LAST ITERATION - warning added"}
 * on the only iteration of every single-shot agent (Classify is hard-coded to
 * {@code maxIterations=1}). With the prod Daily Email Digest workflow firing the
 * classifier per email, this produced 56 WARN/day of pure noise that masked real
 * budget-exhaustion warnings in multi-iter agents (audit 2026-05-13). Fix: WARN
 * only when {@code maxIterations > 1}; DEBUG otherwise.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentLoopExecutor - LAST ITERATION log level depends on maxIterations")
class AgentLoopExecutorSingleShotLastIterationLogTest {

    @Mock private ToolExecutionService toolExecutionService;
    @Mock private LLMProvider provider;

    private AgentLoopExecutor executor;
    private Logger executorLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        executor = new AgentLoopExecutor(
            toolExecutionService, AgentLogger.NOOP,
            Executors.newSingleThreadExecutor(), 5000L, false);

        executorLogger = (Logger) LoggerFactory.getLogger(AgentLoopExecutor.class);
        previousLevel = executorLogger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        executorLogger.addAppender(appender);
        executorLogger.setLevel(Level.DEBUG);

        // Provider returns an immediate stop with no tool calls - that closes the
        // iteration cleanly so the LAST-ITERATION branch (which fires BEFORE the
        // LLM call) is the only side effect we're asserting on.
        CompletionResponse stopResponse = CompletionResponse.builder()
            .content("done")
            .finishReason("stop")
            .toolCalls(List.of())
            .usage(UsageInfo.builder().promptTokens(10).completionTokens(2).totalTokens(12).build())
            .build();
        lenient().when(provider.complete(any(CompletionRequest.class))).thenReturn(stopResponse);
    }

    @AfterEach
    void tearDown() {
        executorLogger.detachAppender(appender);
        executorLogger.setLevel(previousLevel);
    }

    @Test
    @DisplayName("maxIterations=1 (Classify single-shot contract): LAST ITERATION uses DEBUG, NOT WARN")
    void singleShotAgentDoesNotEmitLastIterationWarn() {
        LoopExecutionState state = new LoopExecutionState("run-classify", 1, 0);
        AgentLoopContext context = AgentLoopContext.builder()
            .userPrompt("classify this")
            .provider("openai")
            .model("gpt-4o")
            .maxIterations(1)
            .build();

        executor.processIteration(provider, "gpt-4o", context, List.of(), state, "system", null);

        boolean warnedLastIter = appender.list.stream().anyMatch(e ->
            e.getLevel() == Level.WARN
            && e.getFormattedMessage().contains("LAST ITERATION"));
        assertThat(warnedLastIter)
            .as("Single-shot agents (maxIterations=1) hit isLastIteration() on every call by contract - must not WARN")
            .isFalse();

        boolean debugLastIter = appender.list.stream().anyMatch(e ->
            e.getLevel() == Level.DEBUG
            && e.getFormattedMessage().contains("Single-iteration agent"));
        assertThat(debugLastIter)
            .as("The single-shot branch must still log at DEBUG so ad-hoc tracing remains possible")
            .isTrue();
    }

    @Test
    @DisplayName("maxIterations=3, exhaust budget: WARN fires on the LAST iteration only - preserves the real budget signal")
    void multiIterationAgentStillWarnsOnLastIteration() {
        LoopExecutionState state = new LoopExecutionState("run-multi", 3, 0);
        AgentLoopContext context = AgentLoopContext.builder()
            .userPrompt("multi")
            .provider("openai")
            .model("gpt-4o")
            .maxIterations(3)
            .build();

        // Drive 3 iterations. processIteration increments first, so after the third
        // call iterations=3=maxIterations and isLastIteration()=true.
        for (int i = 0; i < 3; i++) {
            executor.processIteration(provider, "gpt-4o", context, List.of(), state, "system", null);
        }

        long warnCount = appender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains("LAST ITERATION"))
            .count();
        assertThat(warnCount)
            .as("Multi-iter agent must emit exactly one LAST ITERATION WARN on the final iteration - preserves real-budget-exhaustion observability")
            .isEqualTo(1L);
    }

    @Test
    @DisplayName("LoopExecutionState.getMaxIterations() exposes the configured ceiling - pins the getter that the WARN branch reads")
    void getMaxIterationsExposesConfiguredCeiling() {
        LoopExecutionState single = new LoopExecutionState("r-1", 1, 0);
        LoopExecutionState many = new LoopExecutionState("r-2", 25, 0);

        assertThat(single.getMaxIterations()).isEqualTo(1);
        assertThat(many.getMaxIterations()).isEqualTo(25);
    }
}
