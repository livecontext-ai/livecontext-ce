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
}
