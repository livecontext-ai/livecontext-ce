package com.apimarketplace.auth.audit;

import com.apimarketplace.auth.domain.AuthProvider;
import com.apimarketplace.auth.metrics.AuthMetrics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("AuthEventRecorder")
class AuthEventRecorderTest {

    private AuthEventRecorder newRecorder(AuthMetrics metrics, AuditLogger auditLogger) {
        AuthEventRecorder r = new AuthEventRecorder();
        ReflectionTestUtils.setField(r, "authMetrics", metrics);
        ReflectionTestUtils.setField(r, "auditLogger", auditLogger);
        return r;
    }

    @Test
    @DisplayName("providerTag returns bounded enum strings, never null")
    void providerTag_bounded() {
        AuthEventRecorder r = newRecorder(null, null);
        assertThat(r.providerTag(null)).isEqualTo("keycloak");
        assertThat(r.providerTag(AuthProvider.GOOGLE)).isEqualTo("google");
        assertThat(r.providerTag(AuthProvider.GITHUB)).isEqualTo("github");
        assertThat(r.providerTag(AuthProvider.LOCAL)).isEqualTo("local");
        assertThat(r.providerTag(AuthProvider.KEYCLOAK)).isEqualTo("keycloak");
        assertThat(r.providerTag(AuthProvider.SAML)).isEqualTo("saml");
    }

    @Test
    @DisplayName("recorder is a no-op when both beans are null (does not throw)")
    void allMethods_noOp_whenBeansNull() {
        AuthEventRecorder r = newRecorder(null, null);
        assertThatCode(() -> {
            r.recordLoginSuccess(1L, "google");
            r.recordLoginFailure("google", "invalid_credentials");
            r.recordSignupAndLogin(1L, "google", true);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("recordLoginSuccess increments metric and writes audit event")
    void loginSuccess_metricAndAudit() {
        AuthMetrics metrics = mock(AuthMetrics.class);
        AuditLogger auditLogger = mock(AuditLogger.class);
        AuditLogger.Builder builder = mock(AuditLogger.Builder.class);
        when(auditLogger.event(anyString())).thenReturn(builder);
        when(builder.user(anyLong())).thenReturn(builder);
        when(builder.success()).thenReturn(builder);
        when(builder.detail(anyString(), any())).thenReturn(builder);

        newRecorder(metrics, auditLogger).recordLoginSuccess(42L, "google");

        verify(metrics).loginSuccess("google");
        verify(auditLogger).event(AuditEventTypes.LOGIN_SUCCESS);
        verify(builder).user(42L);
        verify(builder).success();
        verify(builder).detail("provider", "google");
        verify(builder).write();
    }

    @Test
    @DisplayName("recordLoginFailure increments failure counter with reason")
    void loginFailure_metricAndAudit() {
        AuthMetrics metrics = mock(AuthMetrics.class);
        AuditLogger auditLogger = mock(AuditLogger.class);
        AuditLogger.Builder builder = mock(AuditLogger.Builder.class);
        when(auditLogger.event(anyString())).thenReturn(builder);
        when(builder.warn()).thenReturn(builder);
        when(builder.failure(anyString())).thenReturn(builder);
        when(builder.detail(anyString(), any())).thenReturn(builder);

        newRecorder(metrics, auditLogger).recordLoginFailure("keycloak", "invalid_credentials");

        verify(metrics).loginFailure("keycloak", "invalid_credentials");
        verify(builder).failure("invalid_credentials");
        verify(builder).write();
    }

    @Test
    @DisplayName("recordSignupAndLogin emits BOTH signup metric and login metric (and 2 audit events)")
    void signupAndLogin_emitsBoth() {
        AuthMetrics metrics = mock(AuthMetrics.class);
        AuditLogger auditLogger = mock(AuditLogger.class);
        AuditLogger.Builder builder = mock(AuditLogger.Builder.class);
        when(auditLogger.event(anyString())).thenReturn(builder);
        when(builder.user(anyLong())).thenReturn(builder);
        when(builder.success()).thenReturn(builder);
        when(builder.detail(anyString(), any())).thenReturn(builder);

        newRecorder(metrics, auditLogger).recordSignupAndLogin(7L, "github", true);

        verify(metrics).signup("github", true);
        verify(metrics).loginSuccess("github");
        verify(auditLogger).event(AuditEventTypes.SIGNUP_SUCCESS);
        verify(auditLogger).event(AuditEventTypes.LOGIN_SUCCESS);
        verify(builder, times(2)).write();
    }

    @Test
    @DisplayName("metrics-only configuration: audit calls are skipped, metrics still fire")
    void metricsOnly_noAuditCalls() {
        AuthMetrics metrics = mock(AuthMetrics.class);
        AuthEventRecorder r = newRecorder(metrics, null);
        r.recordLoginSuccess(1L, "google");
        verify(metrics).loginSuccess("google");
    }

    @Test
    @DisplayName("audit-only configuration: metrics calls are skipped, audit still fires")
    void auditOnly_noMetricCalls() {
        AuditLogger auditLogger = mock(AuditLogger.class);
        AuditLogger.Builder builder = mock(AuditLogger.Builder.class);
        when(auditLogger.event(anyString())).thenReturn(builder);
        when(builder.user(anyLong())).thenReturn(builder);
        when(builder.success()).thenReturn(builder);
        when(builder.detail(anyString(), any())).thenReturn(builder);

        AuthEventRecorder r = newRecorder(null, auditLogger);
        r.recordLoginSuccess(1L, "google");

        verify(auditLogger).event(AuditEventTypes.LOGIN_SUCCESS);
        verify(builder).write();
    }

    @Test
    @DisplayName("exceptions inside metrics/audit do NOT propagate to caller")
    void swallowsExceptions() {
        AuthMetrics metrics = mock(AuthMetrics.class);
        org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(metrics).loginSuccess(anyString());
        AuthEventRecorder r = newRecorder(metrics, null);
        assertThatCode(() -> r.recordLoginSuccess(1L, "google")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("signup+login emits auth_registered AND auth_login_succeeded through the analytics emitter")
    void recordSignupAndLogin_emitsBothAnalyticsEvents() {
        com.apimarketplace.auth.analytics.AuthAnalyticsEmitter analytics =
                org.mockito.Mockito.mock(com.apimarketplace.auth.analytics.AuthAnalyticsEmitter.class);
        AuthEventRecorder r = newRecorder(null, null);
        ReflectionTestUtils.setField(r, "analytics", analytics);

        r.recordSignupAndLogin(42L, "google", true);

        org.mockito.Mockito.verify(analytics).registered(42L, "google", true);
        org.mockito.Mockito.verify(analytics).loginSucceeded(42L, "google");
    }

    @Test
    @DisplayName("a plain login emits only auth_login_succeeded; a failure emits no analytics (no user to attribute)")
    void recordLoginSuccess_emitsLoginOnly() {
        com.apimarketplace.auth.analytics.AuthAnalyticsEmitter analytics =
                org.mockito.Mockito.mock(com.apimarketplace.auth.analytics.AuthAnalyticsEmitter.class);
        AuthEventRecorder r = newRecorder(null, null);
        ReflectionTestUtils.setField(r, "analytics", analytics);

        r.recordLoginSuccess(7L, "keycloak");
        r.recordLoginFailure("local", "bad_password");

        org.mockito.Mockito.verify(analytics).loginSucceeded(7L, "keycloak");
        org.mockito.Mockito.verify(analytics, org.mockito.Mockito.never())
                .registered(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyBoolean());
        org.mockito.Mockito.verifyNoMoreInteractions(analytics);
    }

    @Test
    @DisplayName("an analytics emitter that throws never breaks the auth path")
    void analyticsFailureIsSwallowed() {
        com.apimarketplace.auth.analytics.AuthAnalyticsEmitter analytics =
                org.mockito.Mockito.mock(com.apimarketplace.auth.analytics.AuthAnalyticsEmitter.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("posthog down")).when(analytics)
                .loginSucceeded(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString());
        AuthEventRecorder r = newRecorder(null, null);
        ReflectionTestUtils.setField(r, "analytics", analytics);

        r.recordLoginSuccess(7L, "keycloak"); // must not throw
    }

    @Test
    @DisplayName("recordLoginRateLimited writes the audit event AND both counters")
    void loginRateLimited_metricsAndAudit() {
        AuthMetrics metrics = mock(AuthMetrics.class);
        AuditLogger auditLogger = mock(AuditLogger.class);
        AuditLogger.Builder builder = mock(AuditLogger.Builder.class);
        when(auditLogger.event(anyString())).thenReturn(builder);
        when(builder.warn()).thenReturn(builder);
        when(builder.failure(anyString())).thenReturn(builder);
        when(builder.detail(anyString(), any())).thenReturn(builder);

        newRecorder(metrics, auditLogger).recordLoginRateLimited("local");

        // Both counters, because the two answer different questions and both had a
        // dashboard before this method existed.
        verify(metrics).rateLimitHit("login");
        verify(metrics).loginFailure("local", "rate_limited");
        // The audit row is the part that did not exist: LOGIN_RATE_LIMITED was declared in
        // AuditEventTypes and written by nobody, so repeated refusals against one account
        // left no trail for a security review to find.
        verify(auditLogger).event(AuditEventTypes.LOGIN_RATE_LIMITED);
        verify(builder).failure("rate_limited");
        verify(builder).write();
    }

    @Test
    @DisplayName("recordAuthTimeClaimMissing counts, and deliberately writes NO audit row")
    void authTimeClaimMissing_metricOnly() {
        AuthMetrics metrics = mock(AuthMetrics.class);
        AuditLogger auditLogger = mock(AuditLogger.class);

        newRecorder(metrics, auditLogger).recordAuthTimeClaimMissing("keycloak", "absent");

        verify(metrics).authTimeClaimMissing("keycloak", "absent");
        // Nothing happened to the account. This fires per REQUEST while a provider is
        // misconfigured, so an audit row per occurrence would drown the trail it shares.
        verifyNoInteractions(auditLogger);
    }

    @Test
    @DisplayName("the new recorders are no-ops when no beans are wired")
    void newRecorders_noOp_whenBeansNull() {
        AuthEventRecorder r = newRecorder(null, null);
        assertThatCode(() -> {
            r.recordLoginRateLimited("local");
            r.recordAuthTimeClaimMissing("keycloak", "absent");
        }).doesNotThrowAnyException();
    }
}
