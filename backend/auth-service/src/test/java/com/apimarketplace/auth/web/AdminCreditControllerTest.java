package com.apimarketplace.auth.web;

import com.apimarketplace.auth.audit.AuditLogger;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.CreditService;
import com.apimarketplace.auth.service.CreditService.CreditConsumeResult;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminCreditController")
class AdminCreditControllerTest {

    @Mock
    private CreditService creditService;

    @Mock
    private UserRepository userRepository;

    // AuditLogger is a concrete class with a fluent Builder; we mock the whole chain to
    // verify the event is written (or not) without caring about the emitted payload.
    @Mock
    private AuditLogger auditLogger;

    @Mock
    private AuditLogger.Builder auditBuilder;

    private AdminCreditController controller;

    private static final long ADMIN_USER_ID = 1L;
    private static final long TARGET_USER_ID = 42L;
    private static final String TARGET_EMAIL = "alice@example.com";
    private static final String ADMIN_ROLES = "USER,ADMIN";
    private static final String NON_ADMIN_ROLES = "USER,OWNER";

    @BeforeEach
    void setUp() {
        // Cloud mode by default (unlimited=false). CE tests override via a second instance.
        controller = new AdminCreditController(creditService, userRepository, auditLogger, false);
    }

    private User mockUser(Long id, String email) {
        User user = mock(User.class);
        org.mockito.Mockito.lenient().when(user.getId()).thenReturn(id);
        org.mockito.Mockito.lenient().when(user.getEmail()).thenReturn(email);
        return user;
    }

    /**
     * Stub the full fluent chain on the mocked AuditLogger so `write()` can be called without
     * NullPointerException. Use lenient() is unnecessary here because we call this helper only
     * in tests that actually trigger an audit write.
     */
    private void stubAuditChain() {
        // lenient() because individual tests only exercise one terminal (.success() or
        // .failure(...)), not both - strict mode would otherwise flag the unused one.
        org.mockito.Mockito.lenient().when(auditLogger.event(anyString())).thenReturn(auditBuilder);
        org.mockito.Mockito.lenient().when(auditBuilder.user(any(Long.class))).thenReturn(auditBuilder);
        org.mockito.Mockito.lenient().when(auditBuilder.ip(any())).thenReturn(auditBuilder);
        org.mockito.Mockito.lenient().when(auditBuilder.userAgent(any())).thenReturn(auditBuilder);
        org.mockito.Mockito.lenient().when(auditBuilder.detail(anyString(), any())).thenReturn(auditBuilder);
        org.mockito.Mockito.lenient().when(auditBuilder.success()).thenReturn(auditBuilder);
        org.mockito.Mockito.lenient().when(auditBuilder.failure(anyString())).thenReturn(auditBuilder);
        org.mockito.Mockito.lenient().when(auditBuilder.warn()).thenReturn(auditBuilder);
    }

    private HttpServletRequest mockRequest() {
        // lenient() because not every test path reaches the audit writer that reads these
        // headers (e.g. validation failures short-circuit before) - Mockito's strict mode
        // would otherwise fail with UnnecessaryStubbingException for those cases.
        HttpServletRequest req = mock(HttpServletRequest.class);
        org.mockito.Mockito.lenient().when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        org.mockito.Mockito.lenient().when(req.getHeader("X-Real-IP")).thenReturn(null);
        org.mockito.Mockito.lenient().when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        org.mockito.Mockito.lenient().when(req.getHeader("User-Agent")).thenReturn("admin-cli/1.0");
        return req;
    }

    // ─────────────────────── Authorization ───────────────────────

    @Nested
    @DisplayName("authorization")
    class Authorization {

        @Test
        @DisplayName("non-admin caller gets 403 Forbidden - no DB mutation, but audit event IS written")
        void nonAdminReturns403() {
            var request = new AdminCreditController.AdminGrantRequest(
                    TARGET_USER_ID, null, new BigDecimal("100"), null);
            stubAuditChain();

            ResponseEntity<Map<String, Object>> response = controller.grantCredits(
                    NON_ADMIN_ROLES, ADMIN_USER_ID, request, mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verifyNoInteractions(creditService);
            // Denied attempts MUST be audited so brute-force probes are detectable.
            verify(auditLogger).event("credit.granted");
            verify(auditBuilder).failure("forbidden_non_admin");
            verify(auditBuilder).write();
        }

        @Test
        @DisplayName("missing X-User-Roles header gets 403 (defaults to USER) + audit")
        void missingRolesReturns403() {
            var request = new AdminCreditController.AdminGrantRequest(
                    TARGET_USER_ID, null, new BigDecimal("100"), null);
            stubAuditChain();

            ResponseEntity<Map<String, Object>> response = controller.grantCredits(
                    "USER", ADMIN_USER_ID, request, mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verifyNoInteractions(creditService);
            verify(auditBuilder).failure("forbidden_non_admin");
        }
    }

    // ─────────────────────── CE gate ───────────────────────

    @Nested
    @DisplayName("CE gate (credit.unlimited=true)")
    class CeGate {

        @Test
        @DisplayName("returns 503 Service Unavailable in CE - grantCredits not called")
        void ceReturns503() {
            AdminCreditController ceController = new AdminCreditController(
                    creditService, userRepository, auditLogger, true /* unlimited = CE */);
            var request = new AdminCreditController.AdminGrantRequest(
                    TARGET_USER_ID, null, new BigDecimal("100"), null);

            ResponseEntity<Map<String, Object>> response = ceController.grantCredits(
                    ADMIN_ROLES, ADMIN_USER_ID, request, mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody()).containsEntry("error", "not_available_in_ce");
            verifyNoInteractions(creditService);
            verifyNoInteractions(auditLogger);
        }
    }

    // ─────────────────────── Input validation ───────────────────────

    @Nested
    @DisplayName("input validation")
    class Validation {

        @Test
        @DisplayName("null body → 400")
        void nullBodyReturns400() {
            ResponseEntity<Map<String, Object>> response = controller.grantCredits(
                    ADMIN_ROLES, ADMIN_USER_ID, null, mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "missing_body");
            verifyNoInteractions(creditService);
        }

        @Test
        @DisplayName("negative target_user_id → 400 invalid_target_user_id")
        void negativeTargetUserIdReturns400() {
            var request = new AdminCreditController.AdminGrantRequest(
                    -1L, null, new BigDecimal("100"), null);

            ResponseEntity<Map<String, Object>> response = controller.grantCredits(
                    ADMIN_ROLES, ADMIN_USER_ID, request, mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "invalid_target_user_id");
        }

        @Test
        @DisplayName("zero amount → 400 (must be strictly positive)")
        void zeroAmountReturns400() {
            var request = new AdminCreditController.AdminGrantRequest(
                    TARGET_USER_ID, null, BigDecimal.ZERO, null);

            ResponseEntity<Map<String, Object>> response = controller.grantCredits(
                    ADMIN_ROLES, ADMIN_USER_ID, request, mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "invalid_amount");
        }

        @Test
        @DisplayName("negative amount → 400")
        void negativeAmountReturns400() {
            var request = new AdminCreditController.AdminGrantRequest(
                    TARGET_USER_ID, null, new BigDecimal("-50"), null);

            ResponseEntity<Map<String, Object>> response = controller.grantCredits(
                    ADMIN_ROLES, ADMIN_USER_ID, request, mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "invalid_amount");
        }

        @Test
        @DisplayName("amount above fat-finger cap → 400")
        void amountAboveCapReturns400() {
            var request = new AdminCreditController.AdminGrantRequest(
                    TARGET_USER_ID, null, new BigDecimal("9999999"), null);

            ResponseEntity<Map<String, Object>> response = controller.grantCredits(
                    ADMIN_ROLES, ADMIN_USER_ID, request, mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "amount_exceeds_cap");
            verifyNoInteractions(creditService);
        }
    }

    // ─────────────────────── Happy path ───────────────────────

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("valid grant: calls CreditService with MANUAL_ADJUSTMENT and unique source_id")
        void validGrantCallsService() {
            var request = new AdminCreditController.AdminGrantRequest(
                    TARGET_USER_ID, null, new BigDecimal("500"), "Refund for incident 2026-04-09");
            when(creditService.grantCredits(
                    eq(TARGET_USER_ID),
                    eq(new BigDecimal("500")),
                    eq("MANUAL_ADJUSTMENT"),
                    anyString(),
                    eq("Refund for incident 2026-04-09")
            )).thenReturn(CreditConsumeResult.success(BigDecimal.ZERO, new BigDecimal("500.8949")));
            stubAuditChain();

            ResponseEntity<Map<String, Object>> response = controller.grantCredits(
                    ADMIN_ROLES, ADMIN_USER_ID, request, mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .containsEntry("success", true)
                    .containsEntry("target_user_id", TARGET_USER_ID)
                    .containsEntry("new_balance", new BigDecimal("500.8949"))
                    .containsKey("source_id");

            // source_id should be prefixed and unique.
            String sourceId = (String) response.getBody().get("source_id");
            assertThat(sourceId).startsWith("admin-grant-");
            assertThat(sourceId.length()).isGreaterThan("admin-grant-".length());
        }

        @Test
        @DisplayName("blank description falls back to default citing the admin user ID")
        void blankDescriptionFallsBackToDefault() {
            var request = new AdminCreditController.AdminGrantRequest(
                    TARGET_USER_ID, null, new BigDecimal("100"), "   ");
            when(creditService.grantCredits(
                    eq(TARGET_USER_ID),
                    eq(new BigDecimal("100")),
                    eq("MANUAL_ADJUSTMENT"),
                    anyString(),
                    eq("Admin grant by user 1")
            )).thenReturn(CreditConsumeResult.success(BigDecimal.ZERO, new BigDecimal("100")));
            stubAuditChain();

            ResponseEntity<Map<String, Object>> response = controller.grantCredits(
                    ADMIN_ROLES, ADMIN_USER_ID, request, mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(creditService).grantCredits(
                    eq(TARGET_USER_ID),
                    eq(new BigDecimal("100")),
                    eq("MANUAL_ADJUSTMENT"),
                    anyString(),
                    eq("Admin grant by user 1"));
        }

        @Test
        @DisplayName("null description falls back to default")
        void nullDescriptionFallsBackToDefault() {
            var request = new AdminCreditController.AdminGrantRequest(
                    TARGET_USER_ID, null, new BigDecimal("100"), null);
            when(creditService.grantCredits(
                    anyLong(), any(BigDecimal.class), anyString(), anyString(), eq("Admin grant by user 1")
            )).thenReturn(CreditConsumeResult.success(BigDecimal.ZERO, new BigDecimal("100")));
            stubAuditChain();

            ResponseEntity<Map<String, Object>> response = controller.grantCredits(
                    ADMIN_ROLES, ADMIN_USER_ID, request, mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("writes an audit event on success")
        void writesAuditOnSuccess() {
            var request = new AdminCreditController.AdminGrantRequest(
                    TARGET_USER_ID, null, new BigDecimal("250"), "promo");
            when(creditService.grantCredits(
                    anyLong(), any(BigDecimal.class), anyString(), anyString(), anyString()
            )).thenReturn(CreditConsumeResult.success(BigDecimal.ZERO, new BigDecimal("250")));
            stubAuditChain();

            controller.grantCredits(ADMIN_ROLES, ADMIN_USER_ID, request, mockRequest());

            verify(auditLogger).event("credit.granted");
            verify(auditBuilder).success();
            verify(auditBuilder).write();
            verify(auditBuilder, never()).failure(anyString());
        }
    }

    // ─────────────────────── Email lookup ───────────────────────

    @Nested
    @DisplayName("email lookup")
    class EmailLookup {

        @Test
        @DisplayName("valid target_email resolves to user id and calls CreditService")
        void emailResolvesAndGrants() {
            var request = new AdminCreditController.AdminGrantRequest(
                    null, TARGET_EMAIL, new BigDecimal("100"), null);
            // Build User mock BEFORE the outer when() - Mockito's strict mode flags
            // nested stubbing as UnfinishedStubbing otherwise.
            User user = mockUser(TARGET_USER_ID, TARGET_EMAIL);
            when(userRepository.findByEmail(TARGET_EMAIL)).thenReturn(Optional.of(user));
            when(creditService.grantCredits(
                    eq(TARGET_USER_ID),
                    eq(new BigDecimal("100")),
                    eq("MANUAL_ADJUSTMENT"),
                    anyString(),
                    anyString()
            )).thenReturn(CreditConsumeResult.success(BigDecimal.ZERO, new BigDecimal("100")));
            stubAuditChain();

            ResponseEntity<Map<String, Object>> response = controller.grantCredits(
                    ADMIN_ROLES, ADMIN_USER_ID, request, mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .containsEntry("target_user_id", TARGET_USER_ID)
                    .containsEntry("target_email", TARGET_EMAIL);
            verify(creditService).grantCredits(
                    eq(TARGET_USER_ID), eq(new BigDecimal("100")),
                    eq("MANUAL_ADJUSTMENT"), anyString(), anyString());
        }

        @Test
        @DisplayName("unknown email → 404 user_not_found - no subscription lookup attempted")
        void unknownEmailReturns404() {
            var request = new AdminCreditController.AdminGrantRequest(
                    null, "nobody@example.com", new BigDecimal("100"), null);
            when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

            ResponseEntity<Map<String, Object>> response = controller.grantCredits(
                    ADMIN_ROLES, ADMIN_USER_ID, request, mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).containsEntry("error", "user_not_found");
            verifyNoInteractions(creditService);
        }

        @Test
        @DisplayName("both target_user_id AND target_email → 400 ambiguous_target")
        void bothTargetsReturnsAmbiguous() {
            var request = new AdminCreditController.AdminGrantRequest(
                    TARGET_USER_ID, TARGET_EMAIL, new BigDecimal("100"), null);

            ResponseEntity<Map<String, Object>> response = controller.grantCredits(
                    ADMIN_ROLES, ADMIN_USER_ID, request, mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "ambiguous_target");
            verifyNoInteractions(userRepository);
            verifyNoInteractions(creditService);
        }

        @Test
        @DisplayName("neither target_user_id nor target_email → 400 missing_target")
        void neitherTargetReturnsMissing() {
            var request = new AdminCreditController.AdminGrantRequest(
                    null, null, new BigDecimal("100"), null);

            ResponseEntity<Map<String, Object>> response = controller.grantCredits(
                    ADMIN_ROLES, ADMIN_USER_ID, request, mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "missing_target");
            verifyNoInteractions(userRepository);
        }

        @Test
        @DisplayName("malformed email (no @) → 400 invalid_target_email")
        void malformedEmailReturns400() {
            var request = new AdminCreditController.AdminGrantRequest(
                    null, "alice", new BigDecimal("100"), null);

            ResponseEntity<Map<String, Object>> response = controller.grantCredits(
                    ADMIN_ROLES, ADMIN_USER_ID, request, mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "invalid_target_email");
            verifyNoInteractions(userRepository);
        }

        @Test
        @DisplayName("email is trimmed before lookup")
        void emailIsTrimmed() {
            var request = new AdminCreditController.AdminGrantRequest(
                    null, "  " + TARGET_EMAIL + "  ", new BigDecimal("100"), null);
            User user = mockUser(TARGET_USER_ID, TARGET_EMAIL);
            when(userRepository.findByEmail(TARGET_EMAIL)).thenReturn(Optional.of(user));
            when(creditService.grantCredits(
                    anyLong(), any(BigDecimal.class), anyString(), anyString(), anyString()
            )).thenReturn(CreditConsumeResult.success(BigDecimal.ZERO, new BigDecimal("100")));
            stubAuditChain();

            ResponseEntity<Map<String, Object>> response = controller.grantCredits(
                    ADMIN_ROLES, ADMIN_USER_ID, request, mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(userRepository).findByEmail(TARGET_EMAIL);
        }
    }

    // ─────────────────────── Failure paths ───────────────────────

    @Nested
    @DisplayName("service failure")
    class ServiceFailure {

        @Test
        @DisplayName("no active subscription on target → 404 and audit failure event")
        void noSubscriptionReturns404() {
            var request = new AdminCreditController.AdminGrantRequest(
                    TARGET_USER_ID, null, new BigDecimal("100"), null);
            when(creditService.grantCredits(
                    anyLong(), any(BigDecimal.class), anyString(), anyString(), anyString()
            )).thenReturn(CreditConsumeResult.noSubscription());
            stubAuditChain();

            ResponseEntity<Map<String, Object>> response = controller.grantCredits(
                    ADMIN_ROLES, ADMIN_USER_ID, request, mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).containsEntry("error", "grant_failed");

            verify(auditBuilder).failure("No active subscription");
            verify(auditBuilder).write();
            verify(auditBuilder, never()).success();
        }
    }
}
