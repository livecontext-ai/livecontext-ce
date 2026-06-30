package com.apimarketplace.trigger.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Cron expression parsing and validation.
 *
 * <p>Accepts a 5-field Unix-style cron expression and converts it to the 6-field
 * Spring representation used internally by {@link CronExpression}. The 5-field
 * input is the canonical user-facing format.
 *
 * <p><b>Strict step validation</b>: Spring's {@code CronExpression} silently
 * accepts {@code *&#47;N} step values where {@code N} exceeds the field's maximum
 * (e.g. {@code *&#47;120} in the minute field). The parsed expression collapses to
 * "the start of the field range only" (minute 0), so the user's intended
 * "every 120 minutes" becomes "every hour at HH:00". This class rejects such
 * expressions before Spring sees them so the validation error is honest.
 */
@Service
public class ScheduleCronParser {

    private static final Logger logger = LoggerFactory.getLogger(ScheduleCronParser.class);

    /** Maximum value per 5-field cron position (minute, hour, day, month, weekday). */
    private static final int[] FIELD_MAX_5 = { 59, 23, 31, 12, 7 };

    /** Minimum value per 5-field cron position. day/month start at 1; everything else at 0. */
    private static final int[] FIELD_MIN_5 = { 0, 0, 1, 1, 0 };

    /** Maximum value per 6-field Spring cron position (second, minute, hour, day, month, weekday). */
    private static final int[] FIELD_MAX_6 = { 59, 59, 23, 31, 12, 7 };

    /** Minimum value per 6-field Spring cron position. */
    private static final int[] FIELD_MIN_6 = { 0, 0, 0, 1, 1, 0 };

    /**
     * Convert standard 5-field cron to 6-field Spring cron (prepends a "0" seconds field).
     * Returns the input unchanged if it already has 6 fields.
     */
    public String toSpringCron(String cron) {
        if (cron == null || cron.isBlank()) return null;
        String[] parts = cron.trim().split("\\s+");
        if (parts.length == 5) {
            return "0 " + cron.trim();
        }
        return cron.trim();
    }

    /**
     * Validate a cron expression.
     *
     * <p>Two passes:
     * <ol>
     *   <li>Strict step validation: reject {@code *&#47;N} where {@code N} exceeds the
     *       field's maximum (the silent-collapse footgun).</li>
     *   <li>Delegate to Spring's {@code CronExpression.parse} for everything else
     *       (syntax, ranges, lists, named months/weekdays, etc.).</li>
     * </ol>
     */
    public boolean isValid(String cron) {
        if (cron == null || cron.isBlank()) return false;
        try {
            String trimmed = cron.trim();
            String[] parts = trimmed.split("\\s+");
            if (parts.length == 5) {
                if (!stepValuesWithinFieldRange(parts, FIELD_MIN_5, FIELD_MAX_5)) return false;
            } else if (parts.length == 6) {
                if (!stepValuesWithinFieldRange(parts, FIELD_MIN_6, FIELD_MAX_6)) return false;
            }
            CronExpression.parse(toSpringCron(trimmed));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Validate that every {@code *&#47;N} step has {@code N} within the field's range.
     * Applied to either the 5-field or 6-field shape via the matching min/max arrays.
     * Returns {@code true} if all steps are sane.
     *
     * <p>Comma-separated lists are also checked: {@code 0,*&#47;120} in minutes is rejected
     * because the embedded {@code *&#47;120} would still collapse silently.
     */
    private boolean stepValuesWithinFieldRange(String[] fieldParts, int[] fieldMin, int[] fieldMax) {
        for (int position = 0; position < fieldParts.length; position++) {
            String field = fieldParts[position];
            int max = fieldMax[position];
            int min = fieldMin[position];
            for (String part : field.split(",")) {
                int slash = part.indexOf('/');
                if (slash < 0) continue;
                String stepStr = part.substring(slash + 1);
                int step;
                try {
                    step = Integer.parseInt(stepStr);
                } catch (NumberFormatException e) {
                    return false;
                }
                if (step <= 0 || step > (max - min + 1)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Calculate the next execution time for a cron expression in a given timezone.
     */
    public Instant getNextExecution(String cron, String timezone) {
        List<Instant> next = getNextExecutions(cron, timezone, 1);
        return next.isEmpty() ? null : next.get(0);
    }

    /**
     * Calculate the next {@code count} execution times for a cron expression in a
     * given timezone. Returned in ascending chronological order. If the expression
     * is invalid or has no future firings, returns an empty list.
     */
    public List<Instant> getNextExecutions(String cron, String timezone, int count) {
        List<Instant> result = new ArrayList<>(count);
        if (!isValid(cron)) return result;
        try {
            CronExpression expression = CronExpression.parse(toSpringCron(cron));
            ZoneId zone = timezone != null && !timezone.isBlank() ? ZoneId.of(timezone) : ZoneId.of("UTC");
            LocalDateTime cursor = LocalDateTime.now(zone);
            for (int i = 0; i < count; i++) {
                LocalDateTime next = expression.next(cursor);
                if (next == null) break;
                result.add(next.atZone(zone).toInstant());
                cursor = next;
            }
        } catch (Exception e) {
            logger.error("Failed to calculate next executions for cron '{}': {}", cron, e.getMessage());
            return new ArrayList<>();
        }
        return result;
    }

    /**
     * Check if the cron should execute now (preventing double execution within ~1 min).
     */
    public boolean shouldExecuteNow(String cron, String timezone, Instant lastExecutionAt) {
        if (lastExecutionAt == null) return true;
        Instant now = Instant.now();
        return ChronoUnit.SECONDS.between(lastExecutionAt, now) >= 55;
    }

    /**
     * Get a human-readable description of the cron expression.
     *
     * <p>Returns short, deterministic descriptions for the patterns the inspector
     * exposes via the preset dropdown. Falls back to {@code "Custom: <cron>"} for
     * anything not in the known set. The description is consumed by the inspector
     * as the source of truth - the frontend never re-computes it.
     */
    public String getDescription(String cron) {
        if (cron == null || cron.isBlank()) return "Invalid cron";
        String[] parts = cron.trim().split("\\s+");
        if (parts.length != 5) return "Custom: " + cron;

        String minute = parts[0];
        String hour = parts[1];
        String dayOfMonth = parts[2];
        String month = parts[3];
        String dayOfWeek = parts[4];

        // Every minute
        if ("*".equals(minute) && "*".equals(hour) && "*".equals(dayOfMonth)
                && "*".equals(month) && "*".equals(dayOfWeek)) {
            return "Every minute";
        }

        // Every N minutes (only when N is a valid step within [1, 30])
        if (minute.startsWith("*/") && "*".equals(hour) && "*".equals(dayOfMonth)
                && "*".equals(month) && "*".equals(dayOfWeek)) {
            try {
                int n = Integer.parseInt(minute.substring(2));
                if (n >= 1 && n <= 59) return "Every " + n + " minutes";
            } catch (NumberFormatException ignored) { /* fall through */ }
        }

        // Every hour at minute 0
        if ("0".equals(minute) && "*".equals(hour) && "*".equals(dayOfMonth)
                && "*".equals(month) && "*".equals(dayOfWeek)) {
            return "Every hour";
        }

        // Every N hours at minute 0
        if ("0".equals(minute) && hour.startsWith("*/") && "*".equals(dayOfMonth)
                && "*".equals(month) && "*".equals(dayOfWeek)) {
            try {
                int n = Integer.parseInt(hour.substring(2));
                if (n >= 1 && n <= 23) return "Every " + n + " hours";
            } catch (NumberFormatException ignored) { /* fall through */ }
        }

        // Daily at HH:MM (no day/month/weekday constraint)
        if (isNumeric(minute) && isNumeric(hour) && "*".equals(dayOfMonth)
                && "*".equals(month) && "*".equals(dayOfWeek)) {
            return "Every day at " + pad(hour) + ":" + pad(minute);
        }

        // Weekly at HH:MM on specific weekday(s)
        if (isNumeric(minute) && isNumeric(hour) && "*".equals(dayOfMonth)
                && "*".equals(month) && !"*".equals(dayOfWeek)) {
            return "Every " + formatWeekdays(dayOfWeek) + " at " + pad(hour) + ":" + pad(minute);
        }

        // Monthly at HH:MM on a specific day-of-month
        if (isNumeric(minute) && isNumeric(hour) && isNumeric(dayOfMonth)
                && "*".equals(month) && "*".equals(dayOfWeek)) {
            return "On day " + dayOfMonth + " of every month at " + pad(hour) + ":" + pad(minute);
        }

        return "Custom: " + cron;
    }

    private static boolean isNumeric(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static String pad(String s) {
        if (s == null) return "00";
        return s.length() == 1 ? "0" + s : s;
    }

    private static final String[] WEEKDAY_NAMES = {
            "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"
    };

    private static String formatWeekdays(String dayOfWeek) {
        // Special cases for common patterns the inspector exposes.
        if ("1-5".equals(dayOfWeek)) return "weekday";
        if ("0,6".equals(dayOfWeek) || "6,0".equals(dayOfWeek)) return "weekend day";

        // Single day or comma-separated list of single days.
        StringBuilder sb = new StringBuilder();
        String[] tokens = dayOfWeek.split(",");
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i].trim();
            if (i > 0) sb.append(", ");
            if (isNumeric(token)) {
                int n = Integer.parseInt(token);
                if (n >= 0 && n <= 7) {
                    sb.append(WEEKDAY_NAMES[n]);
                    continue;
                }
            }
            sb.append(token);
        }
        return sb.toString();
    }
}
