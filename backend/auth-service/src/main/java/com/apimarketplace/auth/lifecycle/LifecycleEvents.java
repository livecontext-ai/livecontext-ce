package com.apimarketplace.auth.lifecycle;

/**
 * Names of the Resend events the lifecycle automations are triggered by or wait for. They
 * are the contract with the automation definitions pushed to Resend: renaming one here
 * without renaming it there silently stops the matching sequence.
 */
public final class LifecycleEvents {

    public static final String USER_SIGNED_UP = "user.signed_up";
    public static final String USER_ACTIVATED = "user.activated";
    public static final String USER_RETURNED = "user.returned";
    public static final String CHECKOUT_STARTED = "checkout.started";
    public static final String CHECKOUT_COMPLETED = "checkout.completed";
    /** A trophy worth an email (popularity, first publication, gold or platinum). Sent by orchestrator. */
    public static final String BADGE_UNLOCKED = "badge.unlocked";
    /** The monthly activity recap of the previous calendar month. Sent by orchestrator. */
    public static final String RECAP_MONTHLY = "recap.monthly";

    /** {@code kind} payload values of the checkout events. */
    public static final String KIND_SUBSCRIPTION = "subscription";
    public static final String KIND_CREDITS = "credits";

    private LifecycleEvents() {
    }
}
