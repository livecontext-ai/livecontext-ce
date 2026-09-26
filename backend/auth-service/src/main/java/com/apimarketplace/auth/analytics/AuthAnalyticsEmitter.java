package com.apimarketplace.auth.analytics;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.UserOnboarding;
import com.apimarketplace.common.analytics.PostHogAnalyticsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side product-analytics (PostHog) emitter for the identity, persona and
 * plan lifecycle: who signed up, how, what they said they are, what they pay.
 *
 * <p>These are the events that build the PERSON in PostHog. Each one carries a
 * {@code $set} / {@code $set_once} block so the person profile ends up with
 * bounded persona properties (profession, company size, use cases, interests,
 * experience, signup method, plan) that every later event can be broken down by,
 * whichever surface emitted it. The frontend fires its own onboarding events for
 * the funnel view; this side is the reliable record (a user who closes the tab a
 * second early is still counted here).</p>
 *
 * <p>Contract: distinct_id = the internal numeric user id (the value the gateway
 * sends as X-User-ID, and what the frontend identifies with, so both halves
 * resolve to one person). PII-free: no email, name, display name, or free text;
 * persona values are re-bucketed by {@link PersonaBuckets}. Best-effort: inert
 * when unconfigured, never throws, never blocks.</p>
 */
@Component
public class AuthAnalyticsEmitter {

    private static final Logger log = LoggerFactory.getLogger(AuthAnalyticsEmitter.class);

    static final String REGISTERED = "auth_registered";
    static final String LOGIN_SUCCEEDED = "auth_login_succeeded";
    static final String ONBOARDING_STEP = "onboarding_step_completed";
    static final String ONBOARDING_COMPLETED = "onboarding_completed";
    static final String ONBOARDING_SKIPPED = "onboarding_skipped";
    static final String SUBSCRIPTION_CHANGED = "subscription_changed";
    static final String SUBSCRIPTION_CANCELLED = "subscription_cancelled";
    static final String CREDITS_PURCHASED = "credits_purchased";
    static final String PLAN_GRANTED = "plan_granted";
    static final String ORGANIZATION_CREATED = "organization_created";
    static final String INVITATION_ACCEPTED = "invitation_accepted";
    static final String CREDIT_ALERT_SENT = "credit_alert_sent";
    static final String LIFECYCLE_EVENT_SENT = "lifecycle_event_sent";
    static final String MARKETING_CONSENT_CHANGED = "marketing_consent_changed";
    static final String SSO_MEMBER_JOINED = "sso_member_joined";

    public static final String SSO_OUTCOME_JOINED = "joined";
    public static final String SSO_OUTCOME_REJECTED = "rejected";

    @Autowired(required = false)
    private PostHogAnalyticsClient postHog;

    private boolean inactive() {
        return postHog == null || !postHog.isActive();
    }

    /**
     * Whether an event would actually be sent. For callers that must READ something only to
     * build an event (a previous value), so that read is skipped when nothing is captured.
     */
    public boolean isActive() {
        return !inactive();
    }

    private void capture(Long userId, String event, Map<String, Object> props) {
        if (inactive() || userId == null) return;
        try {
            postHog.capture(String.valueOf(userId), event, props);
        } catch (Exception e) {
            log.debug("[posthog] {} dropped: {}", event, e.toString());
        }
    }

    // ── identity ──────────────────────────────────────────────────────────────

    public void registered(Long userId, String method, boolean firstUser) {
        capture(userId, REGISTERED, buildRegisteredProps(method, firstUser, Instant.now()));
    }

    static Map<String, Object> buildRegisteredProps(String method, boolean firstUser, Instant now) {
        Map<String, Object> props = base();
        props.put("method", method);
        props.put("first_user", firstUser);
        Map<String, Object> once = new LinkedHashMap<>();
        once.put("signup_method", method);
        once.put("signup_at", now.toString());
        props.put("$set_once", once);
        return props;
    }

    public void loginSucceeded(Long userId, String method) {
        capture(userId, LOGIN_SUCCEEDED, buildLoginProps(method, Instant.now()));
    }

    static Map<String, Object> buildLoginProps(String method, Instant now) {
        Map<String, Object> props = base();
        props.put("method", method);
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("last_login_method", method);
        set.put("last_login_at", now.toString());
        props.put("$set", set);
        return props;
    }

    // ── persona (onboarding) ──────────────────────────────────────────────────

    public void onboardingStepSaved(Long userId, Integer step) {
        if (step == null) return;
        Map<String, Object> props = base();
        props.put("step", step);
        capture(userId, ONBOARDING_STEP, props);
    }

    public void onboardingCompleted(Long userId, UserOnboarding onboarding, String organizationId) {
        capture(userId, ONBOARDING_COMPLETED, buildOnboardingCompletedProps(onboarding, organizationId, Instant.now()));
    }

    static Map<String, Object> buildOnboardingCompletedProps(UserOnboarding o, String organizationId, Instant now) {
        String profession = PersonaBuckets.profession(o.getProfession());
        String companySize = PersonaBuckets.companySize(o.getCompanySize());
        String experience = PersonaBuckets.experienceLevel(o.getExperienceLevel());
        List<String> interests = PersonaBuckets.interests(o.getInterests());
        List<String> useCases = PersonaBuckets.useCases(o.getUseCases());
        String primaryGoal = PersonaBuckets.primaryGoal(o.getPrimaryGoal());
        List<String> toolsUsed = PersonaBuckets.toolsUsed(o.getToolsUsed());
        String previousTool = PersonaBuckets.previousTool(o.getPreviousTool());
        String referralSource = PersonaBuckets.referralSource(o.getReferralSource());

        Map<String, Object> props = base();
        putOrg(props, organizationId);
        props.put("profession", profession);
        props.put("company_size", companySize);
        props.put("experience_level", experience);
        props.put("interests", interests);
        props.put("use_cases", useCases);
        props.put("interest_count", o.getInterests() != null ? o.getInterests().size() : 0);
        props.put("use_case_count", o.getUseCases() != null ? o.getUseCases().size() : 0);
        props.put("custom_interest_count", PersonaBuckets.customCount(o.getInterests(), PersonaBuckets.INTERESTS));
        props.put("custom_use_case_count", PersonaBuckets.customCount(o.getUseCases(), PersonaBuckets.USE_CASES));
        props.put("primary_goal", primaryGoal);
        props.put("tools_used", toolsUsed);
        props.put("tools_count", o.getToolsUsed() != null ? o.getToolsUsed().size() : 0);
        props.put("previous_tool", previousTool);
        props.put("referral_source", referralSource);

        Map<String, Object> set = new LinkedHashMap<>();
        set.put("profession", profession);
        set.put("company_size", companySize);
        set.put("experience_level", experience);
        set.put("interests", interests);
        set.put("use_cases", useCases);
        set.put("primary_goal", primaryGoal);
        set.put("tools_used", toolsUsed);
        set.put("previous_tool", previousTool);
        set.put("referral_source", referralSource);
        set.put("onboarding_completed", true);
        set.put("onboarding_completed_at", now.toString());
        props.put("$set", set);
        // Where they came from is an acquisition fact: first answer wins for good.
        if (referralSource != null) props.put("$set_once", Map.of("referral_source_first", referralSource));
        return props;
    }

    public void onboardingSkipped(Long userId, int atStep, String organizationId) {
        Map<String, Object> props = base();
        putOrg(props, organizationId);
        props.put("skipped_at_step", atStep);
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("onboarding_skipped", true);
        props.put("$set", set);
        capture(userId, ONBOARDING_SKIPPED, props);
    }

    // ── plan / money ──────────────────────────────────────────────────────────

    /**
     * @param source {@code stripe} for a webhook-driven change, {@code admin} for a comp grant.
     */
    public void subscriptionChanged(Long userId, String previousPlanCode, String newPlanCode,
                                    String status, int creditQuantity, String source) {
        capture(userId, SUBSCRIPTION_CHANGED,
                buildSubscriptionChangedProps(previousPlanCode, newPlanCode, status, creditQuantity, source));
    }

    static Map<String, Object> buildSubscriptionChangedProps(String previousPlanCode, String newPlanCode,
                                                             String status, int creditQuantity, String source) {
        Map<String, Object> props = base();
        props.put("previous_plan_code", previousPlanCode);
        props.put("plan_code", newPlanCode);
        props.put("plan_changed", previousPlanCode == null || !previousPlanCode.equals(newPlanCode));
        props.put("subscription_status", status);
        props.put("credit_quantity", creditQuantity);
        props.put("source", source);
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("plan_code", newPlanCode);
        set.put("subscription_status", status);
        props.put("$set", set);
        return props;
    }

    public void subscriptionCancelled(Long userId, String planCode) {
        Map<String, Object> props = base();
        props.put("plan_code", planCode);
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("subscription_status", "canceled");
        set.put("plan_code", "FREE");
        props.put("$set", set);
        capture(userId, SUBSCRIPTION_CANCELLED, props);
    }

    public void creditsPurchased(Long userId, BigDecimal amount, String tier) {
        Map<String, Object> props = base();
        props.put("credits", amount);
        props.put("tier", tier);
        capture(userId, CREDITS_PURCHASED, props);
    }

    public void planGranted(Long userId, String previousPlanCode, String newPlanCode) {
        Map<String, Object> props = buildSubscriptionChangedProps(previousPlanCode, newPlanCode, "active", 0, "admin");
        capture(userId, PLAN_GRANTED, props);
    }

    // ── organization ──────────────────────────────────────────────────────────

    public void organizationCreated(Long userId, Organization org, boolean personal) {
        if (org == null) return;
        Map<String, Object> props = base();
        String orgId = org.getId() != null ? org.getId().toString() : null;
        putOrg(props, orgId);
        props.put("personal", personal);
        capture(userId, ORGANIZATION_CREATED, props);
    }

    public void invitationAccepted(Long userId, Organization org, OrganizationRole role) {
        if (org == null) return;
        Map<String, Object> props = base();
        String orgId = org.getId() != null ? org.getId().toString() : null;
        putOrg(props, orgId);
        props.put("role", role != null ? role.name() : null);
        capture(userId, INVITATION_ACCEPTED, props);
    }

    // ── notifications / lifecycle ─────────────────────────────────────────────

    /**
     * A credit alert was delivered and recorded as sent for this cycle.
     *
     * @param level {@code LOW} or {@code EXHAUSTED} (any case), sent lowercased
     */
    public void creditAlertSent(Long userId, String organizationId, String level) {
        capture(userId, CREDIT_ALERT_SENT, buildCreditAlertSentProps(level, organizationId));
    }

    static Map<String, Object> buildCreditAlertSentProps(String level, String organizationId) {
        Map<String, Object> props = base();
        putOrg(props, organizationId);
        props.put("level", level != null ? level.toLowerCase(java.util.Locale.ROOT) : null);
        return props;
    }

    /**
     * A lifecycle (Resend) event was handed to Resend. Only the event NAME travels, never its
     * payload nor the recipient address.
     */
    public void lifecycleEventSent(Long userId, String lifecycleEvent, boolean delivered) {
        if (lifecycleEvent == null) return;
        capture(userId, LIFECYCLE_EVENT_SENT, buildLifecycleEventSentProps(lifecycleEvent, delivered));
    }

    static Map<String, Object> buildLifecycleEventSentProps(String lifecycleEvent, boolean delivered) {
        Map<String, Object> props = base();
        props.put("lifecycle_event", lifecycleEvent);
        props.put("delivered", delivered);
        return props;
    }

    /**
     * The marketing consent of this user actually changed. {@code source} is omitted: the
     * onboarding page and the settings page reach the service through the same endpoint,
     * so the backend cannot tell them apart.
     */
    public void marketingConsentChanged(Long userId, boolean consent) {
        capture(userId, MARKETING_CONSENT_CHANGED, buildMarketingConsentChangedProps(consent));
    }

    static Map<String, Object> buildMarketingConsentChangedProps(boolean consent) {
        Map<String, Object> props = base();
        props.put("consent", consent);
        return props;
    }

    // ── SSO ───────────────────────────────────────────────────────────────────

    /**
     * Outcome of a SAML sign-in against a workspace's SSO connection.
     *
     * @param outcome {@code joined} or {@code rejected} (an existing member is not counted:
     *                the membership check runs per user resolution, not per sign-in)
     * @param reason  the rejection reason (lowercased enum name), only for {@code rejected}
     * @param role    the role granted, only for {@code joined}
     */
    public void ssoMemberJoined(Long userId, String organizationId, String outcome, String reason,
                                OrganizationRole role) {
        capture(userId, SSO_MEMBER_JOINED, buildSsoMemberJoinedProps(organizationId, outcome, reason, role));
    }

    static Map<String, Object> buildSsoMemberJoinedProps(String organizationId, String outcome, String reason,
                                                         OrganizationRole role) {
        Map<String, Object> props = base();
        putOrg(props, organizationId);
        props.put("outcome", outcome);
        if (SSO_OUTCOME_REJECTED.equals(outcome) && reason != null) {
            props.put("reason", reason);
        }
        if (SSO_OUTCOME_JOINED.equals(outcome) && role != null) {
            props.put("role", role.name().toLowerCase(java.util.Locale.ROOT));
        }
        return props;
    }

    // ── shared ────────────────────────────────────────────────────────────────

    private static Map<String, Object> base() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("surface", "backend");
        return props;
    }

    /** organization_id as a plain property AND as a PostHog group, so org-level analytics work. */
    private static void putOrg(Map<String, Object> props, String organizationId) {
        if (organizationId == null || organizationId.isBlank()) return;
        props.put("organization_id", organizationId);
        props.put("$groups", Map.of("organization", organizationId));
    }
}
