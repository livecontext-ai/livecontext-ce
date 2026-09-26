package com.apimarketplace.orchestrator.services.notification.delivery;

import com.apimarketplace.common.i18n.MessageCatalog;
import com.apimarketplace.orchestrator.services.notification.SubjectNameResolver;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The words of every message that leaves the platform, in the RECIPIENT's language and
 * time zone ({@link NotificationLocaleResolver}; English and UTC when unknown). The texts
 * live in one catalog, {@code i18n/notification-messages_<locale>.properties}, for the six
 * app locales; links carry the locale's route prefix ({@code /fr/app/...}).
 *
 * <p>Every alert says what happens NEXT, because the anti-spam rules make silence
 * meaningful: a person told "you will hear once a day at most" does not read the
 * quiet that follows as "fixed".
 *
 * <p>Credit alerts (emitted by auth-service's credit scan) are worded here too, like
 * every other category: there is no second copy of these texts anywhere.
 */
@Component
public class NotificationMessageComposer {

    static final int DIGEST_MAX_ITEMS = 20;
    static final MessageCatalog CATALOG = MessageCatalog.load("i18n/notification-messages");

    /** One bell row, as the digest needs it. */
    public record DigestItem(String category, String subjectType, UUID subjectId, Map<String, Object> payload,
                             Instant occurredAt) {}

    private final Map<String, SubjectNameResolver> resolvers = new HashMap<>();
    private final NotificationLocaleResolver localeResolver;

    public NotificationMessageComposer(List<SubjectNameResolver> resolvers, NotificationLocaleResolver localeResolver) {
        for (SubjectNameResolver r : resolvers) {
            this.resolvers.put(r.subjectType(), r);
        }
        this.localeResolver = localeResolver;
    }

    /** The message for one notification sent on its own, or null when the category is never sent alone. */
    public NotificationMessage alert(NotificationCreatedEvent event) {
        Words w = words(event.tenantId());
        String name = nameOf(w, event.subjectType(), event.subjectId(), event.payload());
        Map<String, Object> p = event.payload() != null ? event.payload() : Map.of();
        return switch (event.category()) {
            case "RUN_FAILED" -> w.message(
                    w.text("run_failed.subject", Map.of("name", name)),
                    List.of(w.text("run_failed.line1", Map.of("name", name, "time", w.when(event.occurredAt()))),
                            w.text("failure.rationed")),
                    runPath(event), "action.open_run");
            case "BUDGET_REACHED" -> w.message(
                    w.text("budget.subject", Map.of("name", name)),
                    List.of(w.text("budget.line1", Map.of("name", name,
                                    "spent", w.number(p.get("spentCredits")), "cap", w.number(p.get("capCredits")))),
                            w.text("budget.line2")),
                    "/app/workflow/" + event.subjectId(), "action.open_workflow");
            case "CREDIT_LOW" -> {
                Object remaining = p.get("remainingCredits");
                yield w.message(
                        w.text("credit_low.subject"),
                        List.of(w.plural("credit_low.line1", pluralCount(remaining),
                                        Map.of("count", w.number(remaining))),
                                w.text("credit_low.line2")),
                        "/app/settings/billing", "action.add_credits");
            }
            case "CREDIT_EXHAUSTED" -> w.message(
                    w.text("credit_exhausted.subject"),
                    List.of(w.text("credit_exhausted.line1"), w.text("credit_exhausted.line2")),
                    "/app/settings/billing", "action.add_credits");
            default -> null;
        };
    }

    /** The one correction sent when a workflow announced as recovered breaks again. */
    public NotificationMessage failingAgain(NotificationCreatedEvent event) {
        Words w = words(event.tenantId());
        String name = nameOf(w, event.subjectType(), event.subjectId(), event.payload());
        return w.message(
                w.text("failing_again.subject", Map.of("name", name)),
                List.of(w.text("failing_again.line1", Map.of("name", name, "time", w.when(event.occurredAt()))),
                        w.text("failure.rationed")),
                runPath(event), "action.open_run");
    }

    public NotificationMessage reminder(NotificationIncidentStore.Incident incident) {
        Words w = words(incident.tenantId());
        String name = nameOf(w, SubjectNameResolver.WORKFLOW, incident.workflowId(), null);
        int since = incident.failureCount() - incident.notifiedFailureCount();
        return w.message(
                w.text("reminder.subject", Map.of("name", name)),
                List.of(w.plural("reminder.line1", since, Map.of("name", name, "count", w.number(since),
                                "time", w.when(incident.lastNotifiedAt()))),
                        w.plural("reminder.line2", incident.failureCount(), Map.of(
                                "count", w.number(incident.failureCount()), "time", w.when(incident.openedAt())))),
                "/app/workflow/" + incident.workflowId(), "action.open_workflow");
    }

    public NotificationMessage recovered(NotificationIncidentStore.Incident incident) {
        Words w = words(incident.tenantId());
        String name = nameOf(w, SubjectNameResolver.WORKFLOW, incident.workflowId(), null);
        return w.message(
                w.text("recovered.subject", Map.of("name", name)),
                List.of(w.text("recovered.line1", Map.of("name", name)),
                        w.plural("recovered.line2", incident.failureCount(), Map.of(
                                "count", w.number(incident.failureCount()),
                                "from", w.when(incident.openedAt()), "to", w.when(incident.lastFailureAt())))),
                "/app/workflow/" + incident.workflowId(), "action.open_workflow");
    }

    /**
     * One message for everything a day produced, or null when there is nothing to say.
     *
     * @param recipientId the person the summary goes to (their language and zone)
     */
    public NotificationMessage digest(String recipientId, List<DigestItem> items) {
        if (items == null || items.isEmpty()) return null;
        Words w = words(recipientId);
        int total = items.size();
        List<String> lines = new ArrayList<>();
        lines.add(w.plural("digest.intro", total, Map.of("count", w.number(total))));
        int shown = Math.min(total, DIGEST_MAX_ITEMS);
        for (int i = 0; i < shown; i++) {
            DigestItem item = items.get(i);
            lines.add(w.text("digest.item", Map.of("label", label(w.locale, item.category()),
                    "name", nameOf(w, item.subjectType(), item.subjectId(), item.payload()))));
        }
        if (total > shown) {
            int more = total - shown;
            lines.add(w.plural("digest.more", more, Map.of("count", w.number(more))));
        }
        return w.message(w.plural("digest.subject", total, Map.of("count", w.number(total))),
                lines, "/app", "action.open_app");
    }

    /** The short name of a category in the digest, in {@code locale}. */
    static String label(String locale, String category) {
        String key = "category." + category;
        return CATALOG.keys(MessageCatalog.DEFAULT_LOCALE).contains(key)
                ? CATALOG.text(locale, key) : CATALOG.text(locale, "category.default");
    }

    private Words words(String recipientId) {
        NotificationLocale l = localeResolver != null ? localeResolver.resolve(recipientId) : NotificationLocale.DEFAULT;
        return new Words(l != null ? l : NotificationLocale.DEFAULT);
    }

    private static String runPath(NotificationCreatedEvent event) {
        return event.runIdPublic() != null
                ? "/app/workflow/" + event.subjectId() + "/run/" + event.runIdPublic()
                : "/app/workflow/" + event.subjectId();
    }

    private String nameOf(Words w, String subjectType, UUID subjectId, Map<String, Object> payload) {
        // A billing subject's stored name is the English word the credit scan writes for the bell;
        // the email and chat texts name it in the reader's language instead.
        if (SubjectNameResolver.BILLING.equals(subjectType)) return w.text("name.billing");
        if (payload != null && payload.get("subjectName") instanceof String s && !s.isBlank()) {
            return s;
        }
        SubjectNameResolver resolver = subjectType != null ? resolvers.get(subjectType) : null;
        if (resolver != null && subjectId != null) {
            String name = resolver.resolveNames(Set.of(subjectId)).get(subjectId);
            if (name != null && !name.isBlank()) return name;
        }
        return w.text("name.untitled");
    }

    /** The integer a plural form is chosen by: a whole number, else "other" (count -1). */
    static long pluralCount(Object value) {
        BigDecimal d = decimal(value);
        if (d == null) return -1;
        try {
            return d.stripTrailingZeros().longValueExact();
        } catch (ArithmeticException notWhole) {
            return -1;
        }
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return null;
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Everything one message needs to speak the recipient's language. */
    static final class Words {
        final String locale;
        private final NotificationLocale where;
        private final Locale javaLocale;

        Words(NotificationLocale where) {
            this.where = where;
            this.locale = where.locale();
            this.javaLocale = Locale.forLanguageTag(locale);
        }

        String text(String key) {
            return CATALOG.text(locale, key);
        }

        String text(String key, Map<String, ?> args) {
            return CATALOG.text(locale, key, args);
        }

        String plural(String key, long count, Map<String, ?> args) {
            return CATALOG.plural(locale, key, count, args);
        }

        /** A date and time in the recipient's zone, in the locale's format, the zone named: {@code 24/09/2026 14:05 (Europe/Paris)}. */
        String when(Instant at) {
            DateTimeFormatter f = DateTimeFormatter.ofPattern(CATALOG.raw(locale, "format.datetime"), javaLocale)
                    .withZone(where.zone());
            return f.format(at != null ? at : Instant.now()) + " (" + where.zone().getId() + ")";
        }

        /** A number with the locale's grouping, or "?" when there is none to show. */
        String number(Object value) {
            BigDecimal d = decimal(value);
            if (d == null) return "?";
            NumberFormat f = NumberFormat.getNumberInstance(javaLocale);
            f.setMaximumFractionDigits(2);
            return f.format(d);
        }

        NotificationMessage message(String subject, List<String> lines, String path, String actionKey) {
            return new NotificationMessage(subject, lines, MessageCatalog.localizedPath(locale, path), text(actionKey));
        }
    }
}
