package com.apimarketplace.auth.audit;

/**
 * Centralized enum-like constants for audit event types.
 * Using strings (not enum) keeps the wire format stable across versions
 * while still preventing typos via static reference.
 */
public final class AuditEventTypes {
    private AuditEventTypes() {}

    // ----- authentication -----
    public static final String LOGIN_SUCCESS = "login.success";
    public static final String LOGIN_FAILURE = "login.failure";
    public static final String LOGIN_RATE_LIMITED = "login.rate_limited";
    public static final String LOGOUT = "logout";
    public static final String LOGOUT_ALL = "logout.all";

    // ----- registration -----
    public static final String SIGNUP_SUCCESS = "signup.success";
    public static final String SIGNUP_FAILURE = "signup.failure";

    // ----- credentials -----
    public static final String PASSWORD_CHANGED = "password.changed";
    public static final String PASSWORD_CHANGE_FAILED = "password.change_failed";
    public static final String MFA_ENABLED = "mfa.enabled";
    public static final String MFA_DISABLED = "mfa.disabled";

    // ----- tokens -----
    public static final String TOKEN_REFRESHED = "token.refreshed";
    public static final String TOKEN_REUSE_DETECTED = "token.reuse_detected";
    public static final String TOKEN_REVOKED = "token.revoked";

    // ----- account lifecycle -----
    public static final String ACCOUNT_DISABLED = "account.disabled";
    public static final String ACCOUNT_DELETED = "account.deleted";
    public static final String ROLE_CHANGED = "role.changed";

    // ----- admin / sensitive -----
    public static final String ADMIN_ACTION = "admin.action";
    public static final String CREDIT_GRANTED = "credit.granted";
    /** Admin granted/changed a user's comp subscription plan tier (FREE/STARTER/PRO/TEAM). */
    public static final String PLAN_GRANTED = "plan.granted";
    public static final String DATA_EXPORTED = "data.exported";

    // ----- CE install lifecycle -----
    /** Public {@code /api/auth/register} door has been re-opened by admin (or fresh install). */
    public static final String CE_REGISTRATION_OPENED = "ce.registration.opened";
    /** Public {@code /api/auth/register} door has been closed (wizard completion or explicit admin action). */
    public static final String CE_REGISTRATION_CLOSED = "ce.registration.closed";

    // ----- severity -----
    public static final String SEVERITY_INFO = "info";
    public static final String SEVERITY_WARN = "warn";
    public static final String SEVERITY_CRITICAL = "critical";

    // ----- result -----
    public static final String RESULT_SUCCESS = "success";
    public static final String RESULT_FAILURE = "failure";
}
