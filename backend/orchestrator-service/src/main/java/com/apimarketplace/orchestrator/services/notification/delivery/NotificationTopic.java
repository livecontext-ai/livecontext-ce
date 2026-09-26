package com.apimarketplace.orchestrator.services.notification.delivery;

import java.util.List;
import java.util.Optional;

/**
 * What a person chooses a delivery for. Four groups, not one switch per bell
 * category: nobody tunes twelve rows, and the categories inside a group always
 * deserve the same treatment.
 *
 * <p>Categories that are NOT listed never leave the bell on purpose: approvals
 * and agent questions already reach the chat channel through their own
 * interactive path (sending them here too would deliver them twice), an
 * invitation is already emailed by auth-service, and a trophy is not an alert.
 */
public enum NotificationTopic {

    /** A production workflow failed or stopped on its spending cap. Sent at once, one message per incident. */
    FAILURES(List.of("RUN_FAILED", "BUDGET_REACHED"), false, DeliveryMode.BOTH),
    /**
     * The account's credits are low or gone. Sent at once; the one topic emailed on every plan.
     * PERSON-scoped: the wallet belongs to the person, the alert always lands in their personal
     * workspace, so the choice is one per person, whichever workspace it is edited from.
     */
    CREDITS(List.of("CREDIT_LOW", "CREDIT_EXHAUSTED"), false, DeliveryMode.BOTH),
    /**
     * Something needs fixing but nothing is on fire: a credential expired, a trigger was switched
     * off. EMAIL by default, like TASKS: one person's expired credential is not news for the
     * shared workspace channel, and every member's summary would be posted there separately.
     */
    ACCOUNT(List.of("CRED_EXPIRED", "WEBHOOK_TRIGGER_DISABLED"), true, DeliveryMode.EMAIL),
    /**
     * Work handed to this person. EMAIL by default rather than BOTH: the workspace
     * channel is shared, and "a task was assigned to you" is nobody else's business.
     */
    TASKS(List.of("AGENT_TASK_ASSIGNED", "AGENT_TASK_MENTION", "AGENT_TASK_AWAITING_REVIEW"),
            true, DeliveryMode.EMAIL);

    private final List<String> categories;
    private final boolean digest;
    private final DeliveryMode defaultDelivery;

    NotificationTopic(List<String> categories, boolean digest, DeliveryMode defaultDelivery) {
        this.categories = categories;
        this.digest = digest;
        this.defaultDelivery = defaultDelivery;
    }

    public List<String> categories() {
        return categories;
    }

    /** True when the topic is only ever delivered in the daily summary, never on its own. */
    public boolean isDigest() {
        return digest;
    }

    public DeliveryMode defaultDelivery() {
        return defaultDelivery;
    }

    /**
     * True when the choice belongs to the person rather than to one workspace. Stored under
     * {@link NotificationPreferenceStore#PERSON_SCOPE}, so a choice made while looking at a team
     * workspace governs the alert that actually lands in the personal one.
     */
    public boolean isPersonScoped() {
        return this == CREDITS;
    }

    public static Optional<NotificationTopic> ofCategory(String category) {
        if (category == null) return Optional.empty();
        for (NotificationTopic topic : values()) {
            if (topic.categories.contains(category)) return Optional.of(topic);
        }
        return Optional.empty();
    }

    public static Optional<NotificationTopic> parse(String value) {
        if (value == null) return Optional.empty();
        for (NotificationTopic topic : values()) {
            if (topic.name().equalsIgnoreCase(value.trim())) return Optional.of(topic);
        }
        return Optional.empty();
    }

    /** Every category a digest can carry, for the digest query. */
    public static List<String> digestCategories() {
        return java.util.Arrays.stream(values())
                .filter(NotificationTopic::isDigest)
                .flatMap(t -> t.categories.stream())
                .toList();
    }
}
