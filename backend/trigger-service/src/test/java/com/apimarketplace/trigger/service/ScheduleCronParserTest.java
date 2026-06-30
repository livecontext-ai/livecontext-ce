package com.apimarketplace.trigger.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ScheduleCronParser}.
 *
 * <p>The strict step-validation tests guard the regression class
 * {@code "*&#47;N in a field whose range is less than N"} - Spring's
 * {@code CronExpression} silently accepts these expressions and collapses them
 * to "the start of the field range only". A production schedule configured by
 * a user as {@code *&#47;120 * * * *} (intending every 120 minutes) was firing
 * every hour at HH:00:00. The parser must reject such expressions so the user
 * gets honest validation feedback before save.
 */
class ScheduleCronParserTest {

    private final ScheduleCronParser parser = new ScheduleCronParser();

    @Nested
    @DisplayName("isValid - strict step validation")
    class IsValidStrictStep {

        @Test
        @DisplayName("rejects */120 in the minute field (the bug class from production)")
        void rejectsStep120InMinuteField() {
            assertThat(parser.isValid("*/120 * * * *"))
                    .as("Spring accepts this and collapses to minute 0 - must be rejected")
                    .isFalse();
        }

        @Test
        @DisplayName("rejects */90 in the minute field (any value > 59)")
        void rejectsStep90InMinuteField() {
            assertThat(parser.isValid("*/90 * * * *")).isFalse();
        }

        @Test
        @DisplayName("rejects */48 in the hour field (any value > 23)")
        void rejectsStep48InHourField() {
            assertThat(parser.isValid("* */48 * * *")).isFalse();
        }

        @Test
        @DisplayName("rejects 0,*/120 in the minute field - embedded oversize step")
        void rejectsOversizedStepInsideList() {
            assertThat(parser.isValid("0,*/120 * * * *")).isFalse();
        }

        @Test
        @DisplayName("rejects */0 (step zero is meaningless)")
        void rejectsZeroStep() {
            assertThat(parser.isValid("*/0 * * * *")).isFalse();
        }

        @Test
        @DisplayName("rejects */abc (non-numeric step)")
        void rejectsNonNumericStep() {
            assertThat(parser.isValid("*/abc * * * *")).isFalse();
        }

        @Test
        @DisplayName("accepts */2 in the hour field (the correct way to say every 2 hours)")
        void acceptsStep2InHourField() {
            assertThat(parser.isValid("0 */2 * * *")).isTrue();
        }

        @Test
        @DisplayName("accepts */15 in the minute field")
        void acceptsStep15InMinuteField() {
            assertThat(parser.isValid("*/15 * * * *")).isTrue();
        }

        @Test
        @DisplayName("accepts the equivalent of `*` written as the maximum step")
        void acceptsMaxStep() {
            // */60 means "every 60 minutes starting at 0" → only minute 0 → valid as a hourly fire
            // Spec-wise this is equivalent to `0` and is widely accepted, so we allow it.
            assertThat(parser.isValid("*/60 * * * *")).isTrue();
        }

        // ----- 6-field shape (Spring native cron: seconds minute hour day month weekday) -----

        @Test
        @DisplayName("rejects */120 in the minute field of a 6-field cron (oversize step at position 1)")
        void rejectsStep120InMinuteFieldOf6FieldCron() {
            // The bug class lives on the 6-field surface too - any future internal caller
            // passing a 6-field cron must not slip past the strict gate.
            assertThat(parser.isValid("0 */120 * * * *")).isFalse();
        }

        @Test
        @DisplayName("rejects */48 in the hour field of a 6-field cron (oversize step at position 2)")
        void rejectsStep48InHourFieldOf6FieldCron() {
            assertThat(parser.isValid("0 0 */48 * * *")).isFalse();
        }

        @Test
        @DisplayName("rejects */0 in any 6-field position (zero step is meaningless)")
        void rejectsZeroStepIn6FieldCron() {
            assertThat(parser.isValid("0 */0 * * * *")).isFalse();
        }

        @Test
        @DisplayName("accepts a sane 6-field cron with */2 in the hour field")
        void accepts6FieldEvery2Hours() {
            assertThat(parser.isValid("0 0 */2 * * *")).isTrue();
        }
    }

    @Nested
    @DisplayName("isValid - standard expressions")
    class IsValidStandard {

        @Test
        @DisplayName("accepts every minute (* * * * *)")
        void acceptsEveryMinute() {
            assertThat(parser.isValid("* * * * *")).isTrue();
        }

        @Test
        @DisplayName("accepts daily at 9am (0 9 * * *)")
        void acceptsDailyAt9am() {
            assertThat(parser.isValid("0 9 * * *")).isTrue();
        }

        @Test
        @DisplayName("accepts weekdays at 9am (0 9 * * 1-5)")
        void acceptsWeekdays() {
            assertThat(parser.isValid("0 9 * * 1-5")).isTrue();
        }

        @Test
        @DisplayName("accepts 6-field Spring cron (0 0 9 * * *)")
        void accepts6FieldSpringCron() {
            assertThat(parser.isValid("0 0 9 * * *")).isTrue();
        }

        @Test
        @DisplayName("rejects null and blank")
        void rejectsNullAndBlank() {
            assertThat(parser.isValid(null)).isFalse();
            assertThat(parser.isValid("")).isFalse();
            assertThat(parser.isValid("   ")).isFalse();
        }

        @Test
        @DisplayName("rejects nonsense")
        void rejectsGarbage() {
            assertThat(parser.isValid("nonsense")).isFalse();
            assertThat(parser.isValid("* * *")).isFalse();
        }
    }

    @Nested
    @DisplayName("getDescription")
    class GetDescription {

        @Test
        @DisplayName("describes every minute")
        void describesEveryMinute() {
            assertThat(parser.getDescription("* * * * *")).isEqualTo("Every minute");
        }

        @Test
        @DisplayName("describes every N minutes")
        void describesEveryNMinutes() {
            assertThat(parser.getDescription("*/5 * * * *")).isEqualTo("Every 5 minutes");
            assertThat(parser.getDescription("*/15 * * * *")).isEqualTo("Every 15 minutes");
        }

        @Test
        @DisplayName("describes every hour")
        void describesEveryHour() {
            assertThat(parser.getDescription("0 * * * *")).isEqualTo("Every hour");
        }

        @Test
        @DisplayName("describes every N hours")
        void describesEveryNHours() {
            assertThat(parser.getDescription("0 */2 * * *")).isEqualTo("Every 2 hours");
            assertThat(parser.getDescription("0 */6 * * *")).isEqualTo("Every 6 hours");
        }

        @Test
        @DisplayName("describes daily at HH:MM")
        void describesDailyAtTime() {
            assertThat(parser.getDescription("0 9 * * *")).isEqualTo("Every day at 09:00");
            assertThat(parser.getDescription("30 14 * * *")).isEqualTo("Every day at 14:30");
        }

        @Test
        @DisplayName("describes weekdays at HH:MM")
        void describesWeekdays() {
            assertThat(parser.getDescription("0 9 * * 1-5")).isEqualTo("Every weekday at 09:00");
        }

        @Test
        @DisplayName("describes single weekday at HH:MM")
        void describesSingleWeekday() {
            assertThat(parser.getDescription("0 9 * * 1")).isEqualTo("Every Monday at 09:00");
        }

        @Test
        @DisplayName("describes monthly on day-of-month")
        void describesMonthly() {
            assertThat(parser.getDescription("0 9 1 * *")).isEqualTo("On day 1 of every month at 09:00");
        }

        @Test
        @DisplayName("falls back to Custom: for unrecognized patterns")
        void fallsBackToCustom() {
            assertThat(parser.getDescription("17 3 * 6 *")).startsWith("Custom: ");
        }
    }

    @Nested
    @DisplayName("getNextExecutions")
    class GetNextExecutions {

        @Test
        @DisplayName("returns 3 upcoming firings for a valid cron")
        void returns3UpcomingFirings() {
            List<Instant> next = parser.getNextExecutions("0 */2 * * *", "UTC", 3);
            assertThat(next).hasSize(3);
            assertThat(next.get(0)).isBefore(next.get(1));
            assertThat(next.get(1)).isBefore(next.get(2));
            // Every 2 hours → exactly 2 hours between consecutive firings
            assertThat(next.get(1).getEpochSecond() - next.get(0).getEpochSecond()).isEqualTo(2 * 3600);
        }

        @Test
        @DisplayName("returns empty list for invalid cron")
        void returnsEmptyForInvalidCron() {
            assertThat(parser.getNextExecutions("*/120 * * * *", "UTC", 3)).isEmpty();
            assertThat(parser.getNextExecutions("nonsense", "UTC", 3)).isEmpty();
            assertThat(parser.getNextExecutions(null, "UTC", 3)).isEmpty();
        }

        @Test
        @DisplayName("respects timezone")
        void respectsTimezone() {
            List<Instant> utc = parser.getNextExecutions("0 12 * * *", "UTC", 1);
            List<Instant> paris = parser.getNextExecutions("0 12 * * *", "Europe/Paris", 1);
            assertThat(utc).hasSize(1);
            assertThat(paris).hasSize(1);
            // Different "noon" depending on the zone → different absolute instants
            assertThat(utc.get(0)).isNotEqualTo(paris.get(0));
        }

        @Test
        @DisplayName("returns at most count entries")
        void respectsCountLimit() {
            assertThat(parser.getNextExecutions("0 9 * * *", "UTC", 1)).hasSize(1);
            assertThat(parser.getNextExecutions("0 9 * * *", "UTC", 5)).hasSize(5);
        }
    }
}
