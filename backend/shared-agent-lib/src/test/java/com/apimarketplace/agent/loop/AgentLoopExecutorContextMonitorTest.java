package com.apimarketplace.agent.loop;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.agent.domain.CompletionRequest;
import com.apimarketplace.agent.domain.CompletionResponse;
import com.apimarketplace.agent.domain.Message;
import com.apimarketplace.agent.domain.ToolCall;
import com.apimarketplace.agent.domain.ToolDefinition;
import com.apimarketplace.agent.domain.ToolResult;
import com.apimarketplace.agent.domain.UsageInfo;
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
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * Regression: the context monitor screamed on healthy runs and stayed silent on doomed ones.
 *
 * <p>It compared a chars/4 estimate against a hard-coded {@code CONTEXT_MAX_TOKENS = 50000}
 * and logged {@code ERROR [CONTEXT CRITICAL]}. Production ran agents on models with a
 * 1 000 000-token window, so a perfectly healthy 69 301-token conversation - 6.9% of capacity -
 * produced an ERROR on every iteration (18 of them in one 3h window). The symmetric half is
 * worse: a model with a 32 000-token window holding 30 000 tokens stayed completely silent,
 * because 30 000 &lt; 50 000.
 *
 * <p>Occupancy is now a fraction of the model's own {@code contextWindow}, counted from the
 * provider's reported prompt tokens, and no severity is claimed when the window is unknown.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentLoopExecutor - context monitor reports occupancy, not an absolute token count")
class AgentLoopExecutorContextMonitorTest {

    private static final int ONE_MILLION = 1_000_000;

    @Mock private LLMProvider provider;

    private AgentLoopExecutor executor;
    private Logger executorLogger;
    private Level previousLevel;
    private ListAppender<ILoggingEvent> appender;
    private java.util.concurrent.ExecutorService toolExecutor;

    @BeforeEach
    void setUp() {
        // A tool call is what keeps the iteration alive to the end - an iteration with no tool
        // calls returns "complete" long before the monitor runs, which is also why the monitor
        // only ever fired on tool-calling turns in production.
        ToolExecutionService toolExecutionService = new ToolExecutionService() {
            @Override
            public ToolResult executeTool(ToolCall toolCall, ToolDefinition toolDefinition,
                                          String tenantId, Map<String, Object> credentials) {
                return ToolResult.builder().toolCall(toolCall).success(true).content("{}").build();
            }

            @Override
            public boolean isToolAvailable(ToolDefinition toolDefinition, String tenantId) {
                return true;
            }
        };
        toolExecutor = Executors.newSingleThreadExecutor();
        executor = new AgentLoopExecutor(
            toolExecutionService, AgentLogger.NOOP, toolExecutor, 5000L, false);

        executorLogger = (Logger) LoggerFactory.getLogger(AgentLoopExecutor.class);
        previousLevel = executorLogger.getLevel();
        appender = new ListAppender<>();
        appender.start();
        executorLogger.addAppender(appender);
        executorLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        executorLogger.detachAppender(appender);
        executorLogger.setLevel(previousLevel);
        toolExecutor.shutdownNow();
    }

    @Test
    @DisplayName("69k tokens on a 1M-token model is not an alert (the exact production false positive)")
    void healthyOccupancyOnALargeWindowRaisesNothing() {
        // Faithful to the production run: a conversation genuinely ~69k tokens long, so the old
        // chars/4 estimate also lands above the retired 50 000 ceiling. With a short history the
        // old code would stay quiet for the wrong reason and this test would prove nothing.
        runIterationWith(69_301, ONE_MILLION, historyOfAbout(69_301));

        assertThat(contextEventsAtOrAbove(Level.WARN))
            .as("6.9%% of the window is a healthy run; the old absolute 50k ceiling turned it "
                + "into an ERROR on every iteration and buried the service's real errors")
            .isEmpty();
    }

    @Test
    @DisplayName("a nearly-full small window IS critical, even though it is under the old 50k ceiling")
    void nearlyFullSmallWindowIsCritical() {
        runIterationWith(30_000, 32_000);

        assertThat(contextEventsAt(Level.ERROR))
            .as("this run is about to overflow; the old absolute ceiling stayed silent because "
                + "30000 < 50000, so the alarm missed exactly the case it existed for")
            .isNotEmpty()
            .allSatisfy(message -> assertThat(message).contains("CONTEXT CRITICAL").contains("93%"));
    }

    @Test
    @DisplayName("80% of the window warns without claiming critical")
    void highOccupancyWarnsOnly() {
        runIterationWith(800_000, ONE_MILLION);

        assertThat(contextEventsAt(Level.WARN))
            .isNotEmpty()
            .allSatisfy(message -> assertThat(message).contains("CONTEXT WARNING").contains("80%"));
        assertThat(contextEventsAt(Level.ERROR)).isEmpty();
    }

    @Test
    @DisplayName("occupancy is counted from the provider's reported prompt tokens, not a chars/4 guess")
    void occupancyUsesTheProviderReportedPromptTokens() {
        // A 4-char prompt estimates to ~1 token, so only the provider's own number can put
        // this run in the critical band. If the estimate were used, nothing would fire.
        runIterationWith("hi!!", 950_000, ONE_MILLION);

        assertThat(contextEventsAt(Level.ERROR))
            .isNotEmpty()
            .allSatisfy(message -> assertThat(message)
                .contains("provider-reported")
                .contains("950000"));
    }

    @Test
    @DisplayName("Claude tokens served from the prompt cache still count against the window")
    void cachedTokensCountTowardsOccupancy() {
        // Anthropic reports only the tokens it read FRESH in input_tokens; everything served
        // from the cache arrives in cache_read_input_tokens. ClaudeProvider caches the system
        // blocks and the last history message, so a nearly-full conversation on a cache hit
        // reports a few hundred prompt tokens. Counting only those would leave the flagship
        // provider permanently silent - the exact failure this change set out to remove.
        runIterationWithCache(2_000, 930_000, 18_000, ONE_MILLION);

        assertThat(contextEventsAt(Level.ERROR))
            .as("2000 fresh + 930000 cached + 18000 cache-creation = 95%% of the window")
            .isNotEmpty()
            .allSatisfy(message -> assertThat(message).contains("CONTEXT CRITICAL").contains("95%"));
    }

    @Test
    @DisplayName("a tool result appended after the provider reported usage is still counted")
    void freshlyAppendedMessagesAreNotMissed() {
        // Usage is recorded BEFORE this iteration's tool result is appended, so the provider
        // number always describes the previous request. A huge tool result would go unseen
        // for a whole turn if the estimate were discarded once a provider number existed.
        runIterationWith(1_000, ONE_MILLION, historyOfAbout(940_000));

        assertThat(contextEventsAt(Level.ERROR))
            .as("the estimate over the message list must win when it is the larger of the two")
            .isNotEmpty()
            .allSatisfy(message -> assertThat(message).contains("estimated").contains("CONTEXT CRITICAL"));
    }

    @Test
    @DisplayName("74% stays silent and 75% warns - the warning threshold is pinned")
    void warningThresholdBoundary() {
        runIterationWith(74_000, 100_000);
        assertThat(contextEventsAtOrAbove(Level.WARN)).as("74%% is below the warn line").isEmpty();

        appender.list.clear();
        runIterationWith(75_000, 100_000);
        assertThat(contextEventsAt(Level.WARN)).as("75%% is the warn line").isNotEmpty();
    }

    @Test
    @DisplayName("89% warns and 90% is critical - the critical threshold is pinned")
    void criticalThresholdBoundary() {
        runIterationWith(89_000, 100_000);
        assertThat(contextEventsAt(Level.ERROR)).isEmpty();
        assertThat(contextEventsAt(Level.WARN)).isNotEmpty();

        appender.list.clear();
        runIterationWith(90_000, 100_000);
        assertThat(contextEventsAt(Level.ERROR)).as("90%% is the critical line").isNotEmpty();
    }

    @Test
    @DisplayName("a non-positive context window is treated as unknown, never as a divisor")
    void nonPositiveWindowIsTreatedAsUnknown() {
        runIterationWith(69_301, 0);

        assertThat(contextEventsAtOrAbove(Level.WARN))
            .as("dividing by it would throw; claiming 100%% would be a fabricated alarm")
            .isEmpty();
        assertThat(contextEventsAt(Level.INFO)).isNotEmpty();
    }

    @Test
    @DisplayName("a large run whose model declares no window is reported at INFO, not buried at DEBUG")
    void unknownWindowOnALargeRunIsVisible() {
        runIterationWith(69_301, null);

        assertThat(contextEventsAt(Level.INFO))
            .as("a missing window means the run is UNWATCHED; hiding that at DEBUG is how a "
                + "monitor ends up looking healthy because it went quiet")
            .isNotEmpty()
            .allSatisfy(message -> assertThat(message)
                .contains("declares no usable context window")
                .contains("(absent)"));
    }

    @Test
    @DisplayName("a declared window of zero is reported as zero, not as an absent one")
    void zeroWindowIsDistinguishedFromAnAbsentOne() {
        runIterationWith(69_301, 0);

        assertThat(contextEventsAt(Level.INFO))
            .as("both route to the unknown branch, but a model that declares 0 is a catalog "
                + "error to fix, while an absent window is simply a model nobody has enriched - "
                + "one message for both would send whoever reads it to the wrong place")
            .isNotEmpty()
            .allSatisfy(message -> assertThat(message).contains("(0)").doesNotContain("absent"));
    }

    @Test
    @DisplayName("a small run whose model declares no window stays quiet")
    void unknownWindowOnASmallRunStaysQuiet() {
        runIterationWith(500, null);

        assertThat(contextEventsAt(Level.INFO))
            .as("below the reporting floor there is nothing to warn about either way")
            .isEmpty();
        assertThat(contextEventsAt(Level.DEBUG)).isNotEmpty();
    }

    private void runIterationWithCache(int promptTokens, int cacheRead, int cacheCreation,
                                       Integer contextWindow) {
        UsageInfo usage = UsageInfo.builder()
            .promptTokens(promptTokens)
            .cacheReadInputTokens(cacheRead)
            .cacheCreationInputTokens(cacheCreation)
            .completionTokens(12)
            .build();
        runIteration("short prompt", usage, contextWindow, null);
    }

    @Test
    @DisplayName("the unknown-window reporting floor is pinned on both sides, like the thresholds")
    void unknownWindowReportingFloorBoundary() {
        runIterationWith(19_999, null);
        assertThat(contextEventsAt(Level.INFO)).as("just below the floor").isEmpty();

        appender.list.clear();
        runIterationWith(20_000, null);
        assertThat(contextEventsAt(Level.INFO)).as("the floor itself reports").isNotEmpty();
    }

    @Test
    @DisplayName("a run past the window reports 100%, and still reports it as critical")
    void occupancyIsClampedAtOneHundredPercent() {
        runIterationWith(1_500_000, ONE_MILLION);

        assertThat(contextEventsAt(Level.ERROR))
            .as("the clamp keeps the message sane; what acts on this is the ERROR, and losing "
                + "that on the one run that genuinely overflowed would be the worst case")
            .isNotEmpty()
            .allSatisfy(message -> assertThat(message).contains("100%"));
    }

    private void runIterationWith(int promptTokens, Integer contextWindow) {
        runIterationWith("a prompt long enough to be uninteresting", promptTokens, contextWindow, null);
    }

    private void runIterationWith(int promptTokens, Integer contextWindow, Message seededHistory) {
        runIterationWith("a prompt long enough to be uninteresting", promptTokens, contextWindow, seededHistory);
    }

    private void runIterationWith(String userPrompt, int promptTokens, Integer contextWindow) {
        runIterationWith(userPrompt, promptTokens, contextWindow, null);
    }

    /**
     * A conversation message whose chars/4 estimate is about {@code tokens}. That estimate over
     * {@code state.getMessages()} is exactly what the retired monitor counted, so seeding it is
     * what makes the old code fire and the new code stay quiet on the SAME run.
     */
    private static Message historyOfAbout(int tokens) {
        return new Message(Message.Role.USER, "x".repeat(tokens * 4), null, null, null, null);
    }

    private void runIterationWith(String userPrompt, int promptTokens, Integer contextWindow,
                                  Message seededHistory) {
        runIteration(userPrompt,
            UsageInfo.builder()
                .promptTokens(promptTokens)
                .completionTokens(12)
                .totalTokens(promptTokens + 12)
                .build(),
            contextWindow, seededHistory);
    }

    private void runIteration(String userPrompt, UsageInfo usage, Integer contextWindow,
                              Message seededHistory) {
        CompletionResponse response = CompletionResponse.builder()
            .content("working")
            .finishReason("tool_calls")
            .toolCalls(List.of(new ToolCall("call-1", "files", Map.of("action", "list"), null)))
            .usage(usage)
            .build();
        lenient().when(provider.complete(any(CompletionRequest.class))).thenReturn(response);

        AgentLoopContext context = AgentLoopContext.builder()
            .provider("deepseek")
            .model("deepseek-v4-pro")
            .userPrompt(userPrompt)
            .maxIterations(10)
            .contextWindow(contextWindow)
            .tenantId("tenant-1")
            .build();

        LoopExecutionState state = new LoopExecutionState("run-ctx", 10, 0);
        if (seededHistory != null) {
            state.getMessages().add(seededHistory);
        }
        executor.processIteration(provider, "deepseek-v4-pro", context,
            List.of(ToolDefinition.builder().name("files").build()), state, "system", null);
    }

    /** Formatted messages of the CONTEXT lines logged at exactly {@code level}. */
    private List<String> contextEventsAt(Level level) {
        return appender.list.stream()
            .filter(e -> e.getLevel() == level)
            .map(ILoggingEvent::getFormattedMessage)
            .filter(m -> m.contains("[CONTEXT"))
            .toList();
    }

    /** Formatted messages of the CONTEXT lines logged at {@code level} or more severe. */
    private List<String> contextEventsAtOrAbove(Level level) {
        return appender.list.stream()
            .filter(e -> e.getLevel().isGreaterOrEqual(level))
            .map(ILoggingEvent::getFormattedMessage)
            .filter(m -> m.contains("[CONTEXT"))
            .toList();
    }
}
