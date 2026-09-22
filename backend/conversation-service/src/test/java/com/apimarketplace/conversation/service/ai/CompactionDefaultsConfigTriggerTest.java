package com.apimarketplace.conversation.service.ai;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.agent.summary.ColdSummaryGate;
import com.apimarketplace.agent.summary.CompactionTrigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the config-side guards: what the YAML is allowed to express, and what
 * it is silently prevented from expressing.
 *
 * <p>The clamp matters because no per-conversation per-day summariser cap is
 * enforced anywhere in this codebase, so a threshold small enough to be met on
 * every turn would mean one LLM call per assistant message with nothing behind
 * it to stop the spend.
 */
@DisplayName("CompactionDefaultsConfig - trigger knobs")
class CompactionDefaultsConfigTriggerTest {

    private final CompactionDefaultsConfig config = new CompactionDefaultsConfig();

    @Test
    @DisplayName("Defaults are the pre-feature behaviour: cadence-only, documented threshold")
    void defaultsArePreFeature() {
        assertThat(config.resolvedTrigger()).isEqualTo(CompactionTrigger.TURNS);
        assertThat(config.resolvedSizeTriggerColdTokens())
                .isEqualTo(ColdSummaryGate.DEFAULT_SIZE_TRIGGER_COLD_TOKENS);
    }

    @ParameterizedTest(name = "size-trigger-cold-tokens={0} falls back to the default")
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    @DisplayName("Non-positive threshold falls back to the default, mirroring the cadence")
    void nonPositiveFallsBack(int configured) {
        config.setSizeTriggerColdTokens(configured);

        assertThat(config.resolvedSizeTriggerColdTokens())
                .isEqualTo(ColdSummaryGate.DEFAULT_SIZE_TRIGGER_COLD_TOKENS);
    }

    @ParameterizedTest(name = "size-trigger-cold-tokens={0} is raised to the credit floor")
    @ValueSource(ints = {1, 500, 1999})
    @DisplayName("A threshold below the credit floor is raised, not honoured")
    void belowFloorIsRaised(int configured) {
        // Below the floor the threshold is unreachable in any meaningful sense:
        // any COLD zone large enough to clear the floor also clears the
        // threshold, so SIZE mode would degrade into "fire on every turn".
        config.setSizeTriggerColdTokens(configured);

        assertThat(config.resolvedSizeTriggerColdTokens())
                .isEqualTo(ColdSummaryGate.MIN_COLD_TOKENS_FLOOR);
    }

    @Test
    @DisplayName("A threshold at or above the floor is honoured exactly")
    void atOrAboveFloorIsHonoured() {
        config.setSizeTriggerColdTokens(ColdSummaryGate.MIN_COLD_TOKENS_FLOOR);
        assertThat(config.resolvedSizeTriggerColdTokens())
                .isEqualTo(ColdSummaryGate.MIN_COLD_TOKENS_FLOOR);

        config.setSizeTriggerColdTokens(120_000);
        assertThat(config.resolvedSizeTriggerColdTokens()).isEqualTo(120_000);
    }

    @Test
    @DisplayName("The startup log survives every reachable configuration and changes no resolved value")
    void startupLogNeverBreaksTheContext() {
        // It reads the raw String, which may be null if a deployment sets the
        // property to nothing at all; a NullPointerException here would fail the
        // context refresh and take the whole service down over a log line. It
        // must also be purely observational: a log line that mutated config
        // would make the boot output disagree with the running behaviour.
        config.setTrigger(null);
        config.logEffectiveTrigger();
        assertThat(config.resolvedTrigger()).isEqualTo(CompactionTrigger.TURNS);

        config.setTrigger("size");
        config.setSizeTriggerColdTokens(10);
        config.logEffectiveTrigger();
        assertThat(config.resolvedTrigger()).isEqualTo(CompactionTrigger.SIZE);
        assertThat(config.resolvedSizeTriggerColdTokens())
                .isEqualTo(ColdSummaryGate.MIN_COLD_TOKENS_FLOOR);
        assertThat(config.getSizeTriggerColdTokens())
                .as("the clamp is applied on read, the configured value is not rewritten")
                .isEqualTo(10);

        config.setTrigger("nonsense");
        config.logEffectiveTrigger();
        assertThat(config.resolvedTrigger()).isEqualTo(CompactionTrigger.TURNS);
    }

    @Test
    @DisplayName("An unreadable mode is WARNed by name, so a typo is visible instead of silent")
    void unreadableModeWarns() {
        // The locale trap made a misconfigured mode completely invisible. This
        // is the counterpart that makes it visible, so it has to assert that a
        // line is actually emitted, not merely that nothing throws.
        List<ILoggingEvent> events = captureLogs(() -> {
            config.setTrigger("siize");
            config.logEffectiveTrigger();
        });

        assertThat(events).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage())
                    .contains("siize")
                    .contains("turns, size, size-or-turns");
        });
    }

    @Test
    @DisplayName("A sub-floor threshold is WARNed with both the configured and the applied value")
    void subFloorThresholdWarns() {
        List<ILoggingEvent> events = captureLogs(() -> {
            config.setSizeTriggerColdTokens(10);
            config.logEffectiveTrigger();
        });

        assertThat(events).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage())
                    .contains("10")
                    .contains(String.valueOf(ColdSummaryGate.MIN_COLD_TOKENS_FLOOR));
        });
    }

    @Test
    @DisplayName("The default configuration logs nothing, so a stock boot stays quiet")
    void defaultConfigurationIsSilent() {
        List<ILoggingEvent> events = captureLogs(() -> {
            config.setTrigger("turns");
            config.logEffectiveTrigger();
        });

        assertThat(events).isEmpty();
    }

    private static List<ILoggingEvent> captureLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(CompactionDefaultsConfig.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        return appender.list;
    }

    @Test
    @DisplayName("A null trigger resolves to TURNS rather than propagating null")
    void nullTriggerResolves() {
        config.setTrigger(null);

        assertThat(config.resolvedTrigger()).isEqualTo(CompactionTrigger.TURNS);
    }
}
