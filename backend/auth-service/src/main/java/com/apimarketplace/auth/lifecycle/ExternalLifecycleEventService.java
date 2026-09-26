package com.apimarketplace.auth.lifecycle;

import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The lifecycle events other services may send ({@code POST /api/internal/auth/lifecycle/events}).
 *
 * <p>A closed allow-list, checked field by field: an event name or payload that is not exactly
 * what an automation expects is refused ({@link IllegalArgumentException}, a 400) rather than
 * forwarded, because Resend would accept it and the email would render with a blank.
 *
 * <p>The payload that reaches Resend is built HERE, in the recipient's language (read from the
 * user row on the Resend worker thread): trophy and tier names, the month label, and counts
 * with the locale's digit grouping. The sender only states facts (codes, numbers, a month).
 * Delivery then follows the ordinary path: contact synced first, verified and active accounts
 * only.
 */
@Service
public class ExternalLifecycleEventService {

    /** {@code kind} of a {@code badge.unlocked}: which of the three trophy emails it is. */
    static final Set<String> TROPHY_KINDS = Set.of("popularity", "first_publication", "top_tier");

    private final LifecycleEmailService lifecycleEmails;
    private final LifecycleLabels labels;

    public ExternalLifecycleEventService(LifecycleEmailService lifecycleEmails, LifecycleLabels labels) {
        this.lifecycleEmails = lifecycleEmails;
        this.labels = labels;
    }

    /**
     * Validates, localizes and queues one event for this user.
     *
     * @throws IllegalArgumentException when the event is not on the allow-list or its payload is
     *                                  not the one its automation expects
     */
    public LifecycleEmailService.Dispatch accept(Long userId, String event, Map<String, Object> payload) {
        Map<String, Object> in = payload != null ? payload : Map.of();
        if (LifecycleEvents.BADGE_UNLOCKED.equals(event)) {
            return lifecycleEmails.submitLocalized(userId, event, badgeUnlocked(in), false);
        }
        if (LifecycleEvents.RECAP_MONTHLY.equals(event)) {
            // Bulk: the monthly recap is sent to every active user in one pass, so it yields the
            // worker queue to single events (sign-ups, checkouts) and the sender retries later.
            return lifecycleEmails.submitLocalized(userId, event, recapMonthly(in), true);
        }
        throw new IllegalArgumentException("event not accepted from other services: " + event);
    }

    private Function<String, Map<String, Object>> badgeUnlocked(Map<String, Object> in) {
        String kind = text(in, "kind");
        if (kind == null || !TROPHY_KINDS.contains(kind)) throw new IllegalArgumentException("unknown trophy kind: " + kind);
        String code = text(in, "badge_code");
        if (!labels.isKnownBadge(code)) throw new IllegalArgumentException("unknown badge_code: " + code);
        String tier = text(in, "tier");
        if (!labels.isKnownTier(tier)) throw new IllegalArgumentException("unknown tier: " + tier);
        String family = text(in, "family");
        if (!labels.isKnownFamily(family)) throw new IllegalArgumentException("unknown family: " + family);
        return locale -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kind", kind);
            out.put("badge_code", code);
            out.put("tier", tier);
            out.put("family", family);
            out.put("badge_name", labels.badgeName(code, locale));
            out.put("tier_name", labels.tierName(tier, locale));
            out.put("family_name", labels.familyName(family, locale));
            return out;
        };
    }

    private Function<String, Map<String, Object>> recapMonthly(Map<String, Object> in) {
        YearMonth month;
        try {
            month = YearMonth.parse(String.valueOf(text(in, "month")));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("month must be yyyy-MM");
        }
        long runs = count(in, "runs");
        long workflows = count(in, "active_workflows");
        long badges = count(in, "badges");
        return locale -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("month_label", labels.monthLabel(month, locale));
            out.put("runs", labels.count(runs, locale));
            out.put("active_workflows", labels.count(workflows, locale));
            out.put("badges", labels.count(badges, locale));
            return out;
        };
    }

    private static String text(Map<String, Object> in, String key) {
        Object v = in.get(key);
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    private static long count(Map<String, Object> in, String key) {
        Object v = in.get(key);
        if (!(v instanceof Integer || v instanceof Long)) {
            throw new IllegalArgumentException(key + " must be a whole number");
        }
        long n = ((Number) v).longValue();
        if (n < 0) throw new IllegalArgumentException(key + " must not be negative");
        return n;
    }
}
