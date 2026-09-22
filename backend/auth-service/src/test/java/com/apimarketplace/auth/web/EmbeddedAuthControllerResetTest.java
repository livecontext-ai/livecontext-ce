package com.apimarketplace.auth.web;

import com.apimarketplace.auth.audit.AuditEventTypes;
import com.apimarketplace.auth.audit.AuditLogger;
import com.apimarketplace.auth.ce.CeInstallStateService;
import com.apimarketplace.auth.service.OrganizationMemberService;
import com.apimarketplace.auth.service.PasswordAuthService;
import com.apimarketplace.auth.service.PasswordResetService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two reset endpoints, at the boundary where the enumeration guarantee is
 * actually observable.
 *
 * <p>The service is deliberately built so that "unknown address", "no local
 * password", "disabled account", "rate limited", "SMTP refused the message" and
 * "this install has no mail server at all" are indistinguishable from the
 * outside. That only holds if the CONTROLLER also refuses to branch on them.
 *
 * <p>Pinning that needs care: an earlier version of the first test called the
 * same do-nothing mock twice and compared the two answers, which reduces to
 * comparing a constant with itself. The version below is better but not
 * dramatically so, and it is worth being precise about how much: of its four
 * cases, only the THROWING one exercises a distinct code path, because the
 * service returns void for unknown, OAuth-only, disabled and rate-limited alike
 * and the controller cannot see which it was. What earns its keep is the
 * throwing case plus {@code theServiceIsAlwaysCalled}, which stops the
 * comparison going green on a controller that does no work at all.
 *
 * <p>There is no "no SMTP configured" answer to test, on purpose: see the note
 * in PasswordResetServiceTest.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EmbeddedAuthController - forgot-password / reset-password")
class EmbeddedAuthControllerResetTest {

    @Mock private PasswordAuthService passwordAuthService;
    @Mock private CeInstallStateService installStateService;
    @Mock private OrganizationMemberService organizationMemberService;
    @Mock private PasswordResetService passwordResetService;
    @Mock private HttpServletRequest request;

    private EmbeddedAuthController controller;
    private AuditLogger auditLogger;

    @BeforeEach
    void setUp() {
        controller = new EmbeddedAuthController(
                passwordAuthService, installStateService, organizationMemberService, passwordResetService);
        // @Autowired(required = false) field, so production wiring is reproduced
        // by reflection. Without this the audit branches run with a null logger
        // and are never executed at all.
        auditLogger = mock(AuditLogger.class, RETURNS_DEEP_STUBS);
        ReflectionTestUtils.setField(controller, "auditLogger", auditLogger);
    }

    private static Map<String, String> body(String key, String value) {
        Map<String, String> m = new HashMap<>();
        m.put(key, value);
        return m;
    }

    private static Map<String, String> resetBody(String token, String password) {
        Map<String, String> m = new HashMap<>();
        m.put("token", token);
        m.put("newPassword", password);
        return m;
    }

    @Test
    @DisplayName("every outcome the service can have produces the SAME status and the SAME body")
    void everyOutcomeLooksIdentical() {
        List<ResponseEntity<Map<String, Object>>> answers = new ArrayList<>();

        // 1. A real address: the service issues a token and dispatches a mail.
        doNothing().when(passwordResetService).requestReset(anyString(), any());
        answers.add(controller.forgotPassword(body("email", "owner@example.com"), request));

        // 2. Unknown address, no local password, disabled, rate limited: the
        //    service returns void for all of them, and the controller must not
        //    invent a difference. Distinguished here by the argument, which is
        //    all the controller ever sees.
        answers.add(controller.forgotPassword(body("email", "nobody@example.com"), request));

        // 3. The service blew up (the DB is down, SMTP handoff failed hard).
        doThrow(new RuntimeException("database down"))
                .when(passwordResetService).requestReset(anyString(), any());
        answers.add(controller.forgotPassword(body("email", "broken@example.com"), request));

        // 4. No email field at all.
        doNothing().when(passwordResetService).requestReset(any(), any());
        answers.add(controller.forgotPassword(new HashMap<>(), request));

        ResponseEntity<Map<String, Object>> first = answers.get(0);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (ResponseEntity<Map<String, Object>> answer : answers) {
            assertThat(answer.getStatusCode()).isEqualTo(first.getStatusCode());
            assertThat(answer.getBody()).isEqualTo(first.getBody());
        }
        // And the body must not name the address back, which would confirm it was
        // accepted as a real one.
        assertThat(String.valueOf(first.getBody())).doesNotContain("owner@example.com");
    }

    @Test
    @DisplayName("the address reaches the service even when it is not a known one, so the controller "
            + "is not quietly skipping the work")
    void theServiceIsAlwaysCalled() {
        when(request.getRemoteAddr()).thenReturn("203.0.113.7");

        controller.forgotPassword(body("email", "  Owner@Example.com "), request);

        // Normalisation belongs to the service, so the controller passes it on as
        // typed. If this ever stops being called the test above goes green for
        // the wrong reason: two identical answers and no work done.
        //
        // The IP is asserted rather than matched with any(): it is the
        // abuse-investigation record both the migration and the entity justify,
        // and passing null instead left every test green.
        verify(passwordResetService).requestReset("  Owner@Example.com ", "203.0.113.7");
    }

    @Test
    @DisplayName("behind a proxy the FIRST X-Forwarded-For element is recorded, not the hop that "
            + "delivered the request")
    void forwardedForWins() {
        when(request.getHeader("X-Forwarded-For")).thenReturn("198.51.100.9, 10.0.0.1, 10.0.0.2");
        when(request.getRemoteAddr()).thenReturn("10.0.0.2");

        controller.forgotPassword(body("email", "owner@example.com"), request);

        // Every CE and cloud deployment sits behind at least one proxy, so
        // getRemoteAddr() is the load balancer and useless for investigating
        // abuse.
        verify(passwordResetService).requestReset(anyString(), eq("198.51.100.9"));
    }

    @Test
    @DisplayName("a forgot-password request is audited, so an operator can see a reset campaign")
    void forgotPasswordIsAudited() {
        controller.forgotPassword(body("email", "owner@example.com"), request);

        // .write() is the call that persists the row. Verifying only that the
        // builder was obtained let removing every .write() in this controller
        // pass, so all three audit tests here assert the terminal call.
        var builder = auditLogger.eventFromRequest(AuditEventTypes.PASSWORD_RESET_REQUESTED, request);
        verify(builder.success()).write();
    }

    @Test
    @DisplayName("reset-password reports success and audits the completion against the user it changed")
    void resetSucceeds() {
        doAnswer(invocation -> 42L).when(passwordResetService).resetPassword("raw", "a-good-password");

        ResponseEntity<Map<String, Object>> res =
                controller.resetPassword(resetBody("raw", "a-good-password"), request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).containsEntry("success", true);
        // The body deliberately does NOT carry the user id; the audit row does.
        assertThat(res.getBody()).doesNotContainKey("userId");
        var completed = auditLogger.eventFromRequest(AuditEventTypes.PASSWORD_RESET_COMPLETED, request);
        verify(completed.user(42L).success()).write();
    }

    @Test
    @DisplayName("a rejected token is a 400 carrying the service's own message, and is audited as a failure")
    void invalidTokenIsBadRequest() {
        doThrow(new PasswordResetService.InvalidTokenException(PasswordResetService.INVALID_TOKEN_MESSAGE))
                .when(passwordResetService).resetPassword("stale", "a-good-password");

        ResponseEntity<Map<String, Object>> res =
                controller.resetPassword(resetBody("stale", "a-good-password"), request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // The message is the one the service owns, so the four refusal cases stay
        // indistinguishable all the way out to the caller.
        assertThat(String.valueOf(res.getBody()))
                .contains(PasswordResetService.INVALID_TOKEN_MESSAGE);
        var failed = auditLogger.eventFromRequest(AuditEventTypes.PASSWORD_RESET_FAILED, request);
        verify(failed.warn().failure("invalid_or_expired_token")).write();
    }

    @Test
    @DisplayName("a too-short password is a 400 that passes the service's message THROUGH unchanged, "
            + "which is what is being tested here: the rule itself belongs to PasswordAuthService "
            + "and is pinned there, not by this stubbed message")
    void weakPasswordIsBadRequest() {
        doThrow(new IllegalArgumentException("New password must be at least 8 characters"))
                .when(passwordResetService).resetPassword("raw", "short");

        ResponseEntity<Map<String, Object>> res =
                controller.resetPassword(resetBody("raw", "short"), request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        // The requester's own input, so naming it leaks nothing and saves a
        // wasted link.
        assertThat(String.valueOf(res.getBody())).contains("8 characters");
    }

    @Test
    @DisplayName("a request that FAILED is audited as a failure, not as a success, so an operator "
            + "reading the rows is not told a dead database was fine")
    void failedRequestIsAuditedAsFailure() {
        doThrow(new RuntimeException("database down"))
                .when(passwordResetService).requestReset(anyString(), any());

        ResponseEntity<Map<String, Object>> res =
                controller.forgotPassword(body("email", "owner@example.com"), request);

        // The RESPONSE still cannot differ, and does not.
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Deep stubs return the same builder for the same call, and the chain is
        // eventFromRequest().warn().failure(): verifying on the first link would
        // look for a call that happened on the second.
        var builder = auditLogger.eventFromRequest(AuditEventTypes.PASSWORD_RESET_REQUESTED, request);
        verify(builder).warn();
        verify(builder.warn().failure("reset_request_failed")).write();
    }

    @Test
    @DisplayName("an unexpected failure is a 500 that says nothing about the account")
    void unexpectedFailureIsGeneric() {
        doThrow(new IllegalStateException("connection pool exhausted"))
                .when(passwordResetService).resetPassword("raw", "a-good-password");

        ResponseEntity<Map<String, Object>> res =
                controller.resetPassword(resetBody("raw", "a-good-password"), request);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(String.valueOf(res.getBody())).doesNotContain("connection pool");
    }
}
