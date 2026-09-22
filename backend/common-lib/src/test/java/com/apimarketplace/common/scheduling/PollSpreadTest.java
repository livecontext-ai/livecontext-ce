package com.apimarketplace.common.scheduling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PollSpread - one process, one slot in the shared period")
class PollSpreadTest {

    @Test
    @DisplayName("the expression parses as a cron, or every poller it defaults would fail to schedule at startup")
    void theExpressionIsAValidCron() {
        // A malformed expression is not a wrong schedule, it is a bean-creation
        // failure at boot - and this string reaches production as a DEFAULT, so
        // nothing an operator does would mask it.
        for (int i = 0; i < 200; i++) {
            String cron = PollSpread.quarterHourlyCron();
            assertThat(CronExpression.isValidExpression(cron)).as(cron).isTrue();
        }
    }

    @Test
    @DisplayName("it still fires every 15 minutes - the spread must move the slot, never the period")
    void thePeriodStaysAQuarterHour() {
        CronExpression cron = CronExpression.parse(PollSpread.quarterHourlyCron());

        LocalDateTime first = cron.next(LocalDateTime.of(2026, 9, 8, 0, 0));
        LocalDateTime second = cron.next(first);
        LocalDateTime third = cron.next(second);

        assertThat(java.time.Duration.between(first, second).toMinutes()).isEqualTo(15);
        assertThat(java.time.Duration.between(second, third).toMinutes()).isEqualTo(15);
    }

    @Test
    @DisplayName("the 15-minute period holds ACROSS an hour boundary, where an offset cron misfires")
    void thePeriodHoldsAcrossTheHourBoundary() {
        // "M/15" means M, M+15, M+30, M+45 within EACH hour - it does not carry a
        // remainder into the next one. The boundary is the only place a wrong
        // reading of that would show, so walk right through it.
        for (int i = 0; i < 50; i++) {
            CronExpression cron = CronExpression.parse(PollSpread.quarterHourlyCron());
            LocalDateTime at = cron.next(LocalDateTime.of(2026, 9, 8, 22, 40));
            for (int step = 0; step < 6; step++) {
                LocalDateTime next = cron.next(at);
                assertThat(java.time.Duration.between(at, next).toMinutes())
                        .as("gap after " + at)
                        .isEqualTo(15);
                at = next;
            }
        }
    }

    @Test
    @DisplayName("slots land across the whole quarter hour, not just across the minute")
    void slotsSpreadOverThePeriodAndNotOnlyTheMinute() {
        // Spreading over seconds alone would still put the fleet inside one
        // minute, which for a payload this size is the same burst.
        Set<Integer> secondsIntoThePeriod = new HashSet<>();
        for (int i = 0; i < 400; i++) {
            LocalDateTime fire = CronExpression.parse(PollSpread.quarterHourlyCron())
                    .next(LocalDateTime.of(2026, 9, 8, 0, 0));
            secondsIntoThePeriod.add(fire.getMinute() * 60 + fire.getSecond());
        }

        assertThat(secondsIntoThePeriod)
                .as("400 draws over 900 slots must cover far more than one minute")
                .hasSizeGreaterThan(100);
        // Bound taken from the draw range, not from where next() happens to start:
        // minute offsets run 0-14, so over 400 draws the late minutes must appear
        // many times, not merely once.
        assertThat(secondsIntoThePeriod.stream().filter(s -> s >= 10 * 60).count())
                .as("the late end of the quarter hour must be reachable, repeatedly")
                .isGreaterThan(20L);
    }

    @Test
    @DisplayName("Spring evaluates it as the DEFAULT of a property, which is the only way it ships")
    void springResolvesItAsAPropertyDefault() {
        // The whole design rests on this: `${prop:#{T(PollSpread)...}}` must
        // resolve the placeholder AND then evaluate the SpEL. If Spring only did
        // the first, every scheduler would try to parse the literal `#{...}` as a
        // cron and fail to start.
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(SpreadDefaultConfig.class)) {
            String resolved = context.getBean(SpreadDefaultConfig.class).cron;

            assertThat(resolved).doesNotContain("#{");
            assertThat(CronExpression.isValidExpression(resolved)).as(resolved).isTrue();
        }
    }

    @Test
    @DisplayName("an operator's pinned expression wins over the spread")
    void aConfiguredExpressionOverridesTheDefault() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource(
                            "pinned", java.util.Map.of("test.poll.cron", "0 0 3 * * *")));
            context.register(SpreadDefaultConfig.class);
            context.refresh();

            assertThat(context.getBean(SpreadDefaultConfig.class).cron).isEqualTo("0 0 3 * * *");
        }
    }

    @Test
    @DisplayName("it also resolves when the SpEL is the property VALUE, which is how CE ships it")
    void springResolvesItAsAPropertyValue() {
        // application-ce.yml writes `cron: ${CATALOG_BUNDLE_SYNC_CRON:#{...}}`,
        // so with no env var set the property's VALUE becomes the expression and
        // the scheduler resolves it one step later. That is a different path from
        // the annotation default, and the nested braces are exactly where a
        // placeholder parser would go wrong, so it gets its own proof.
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource(
                            "ce-yaml", java.util.Map.of("test.poll.cron",
                                    "#{T(com.apimarketplace.common.scheduling.PollSpread).quarterHourlyCron()}")));
            context.register(SpreadDefaultConfig.class);
            context.refresh();

            String resolved = context.getBean(SpreadDefaultConfig.class).cron;
            assertThat(resolved).doesNotContain("#{");
            assertThat(CronExpression.isValidExpression(resolved)).as(resolved).isTrue();
        }
    }

    @Configuration
    static class SpreadDefaultConfig {
        @Value("${test.poll.cron:#{T(com.apimarketplace.common.scheduling.PollSpread).quarterHourlyCron()}}")
        String cron;

        @org.springframework.context.annotation.Bean
        static PropertySourcesPlaceholderConfigurer placeholders() {
            return new PropertySourcesPlaceholderConfigurer();
        }
    }
}
