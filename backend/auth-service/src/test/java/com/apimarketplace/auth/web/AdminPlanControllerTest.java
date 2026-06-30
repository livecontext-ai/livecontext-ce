package com.apimarketplace.auth.web;

import com.apimarketplace.auth.audit.AuditLogger;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.AdminPlanService;
import com.apimarketplace.auth.service.AdminPlanService.AssignPlanResult;
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

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminPlanController")
class AdminPlanControllerTest {

    @Mock
    private AdminPlanService adminPlanService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditLogger auditLogger;

    @Mock
    private AuditLogger.Builder auditBuilder;

    private AdminPlanController controller;

    private static final long ADMIN_USER_ID = 1L;
    private static final long TARGET_USER_ID = 42L;
    private static final String TARGET_EMAIL = "alice@example.com";
    private static final String ADMIN_ROLES = "USER,ADMIN";
    private static final String NON_ADMIN_ROLES = "USER,OWNER";

    @BeforeEach
    void setUp() {
        controller = new AdminPlanController(adminPlanService, userRepository, auditLogger, false);
    }

    private User mockUser(Long id, String email) {
        User user = mock(User.class);
        org.mockito.Mockito.lenient().when(user.getId()).thenReturn(id);
        org.mockito.Mockito.lenient().when(user.getEmail()).thenReturn(email);
        return user;
    }

    private void stubAuditChain() {
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
        HttpServletRequest req = mock(HttpServletRequest.class);
        org.mockito.Mockito.lenient().when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        org.mockito.Mockito.lenient().when(req.getHeader("X-Real-IP")).thenReturn(null);
        org.mockito.Mockito.lenient().when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        org.mockito.Mockito.lenient().when(req.getHeader("User-Agent")).thenReturn("admin-cli/1.0");
        return req;
    }

    private AdminPlanController.AdminAssignPlanRequest byId(Long id, String planCode) {
        return new AdminPlanController.AdminAssignPlanRequest(id, null, planCode);
    }

    private AdminPlanController.AdminAssignPlanRequest byEmail(String email, String planCode) {
        return new AdminPlanController.AdminAssignPlanRequest(null, email, planCode);
    }

    // ─────────────────────── Authorization ───────────────────────

    @Nested
    @DisplayName("authorization")
    class Authorization {

        @Test
        @DisplayName("non-admin caller gets 403 - service untouched, audit failure written")
        void nonAdminReturns403() {
            stubAuditChain();

            ResponseEntity<Map<String, Object>> response = controller.assignPlan(
                    NON_ADMIN_ROLES, ADMIN_USER_ID, byId(TARGET_USER_ID, "PRO"), mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verifyNoInteractions(adminPlanService);
            verify(auditLogger).event("plan.granted");
            verify(auditBuilder).failure("forbidden_non_admin");
            verify(auditBuilder).write();
        }
    }

    // ─────────────────────── CE gate ───────────────────────

    @Nested
    @DisplayName("CE gate (credit.unlimited=true)")
    class CeGate {

        @Test
        @DisplayName("returns 503 in CE - assignPlan not called, no audit")
        void ceReturns503() {
            AdminPlanController ceController = new AdminPlanController(
                    adminPlanService, userRepository, auditLogger, true);

            ResponseEntity<Map<String, Object>> response = ceController.assignPlan(
                    ADMIN_ROLES, ADMIN_USER_ID, byId(TARGET_USER_ID, "PRO"), mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody()).containsEntry("error", "not_available_in_ce");
            verifyNoInteractions(adminPlanService);
            verifyNoInteractions(auditLogger);
        }
    }

    // ─────────────────────── Input validation ───────────────────────

    @Nested
    @DisplayName("input validation")
    class Validation {

        @Test
        @DisplayName("null body → 400 missing_body")
        void nullBodyReturns400() {
            ResponseEntity<Map<String, Object>> response = controller.assignPlan(
                    ADMIN_ROLES, ADMIN_USER_ID, null, mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "missing_body");
            verifyNoInteractions(adminPlanService);
        }

        @Test
        @DisplayName("both id and email → 400 ambiguous_target")
        void bothTargetsReturns400() {
            var req = new AdminPlanController.AdminAssignPlanRequest(TARGET_USER_ID, TARGET_EMAIL, "PRO");

            ResponseEntity<Map<String, Object>> response = controller.assignPlan(
                    ADMIN_ROLES, ADMIN_USER_ID, req, mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "ambiguous_target");
            verifyNoInteractions(adminPlanService);
            verifyNoInteractions(userRepository);
        }

        @Test
        @DisplayName("neither id nor email → 400 missing_target")
        void neitherTargetReturns400() {
            ResponseEntity<Map<String, Object>> response = controller.assignPlan(
                    ADMIN_ROLES, ADMIN_USER_ID, byId(null, "PRO"), mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "missing_target");
        }

        @Test
        @DisplayName("missing plan_code → 400 missing_plan")
        void missingPlanReturns400() {
            ResponseEntity<Map<String, Object>> response = controller.assignPlan(
                    ADMIN_ROLES, ADMIN_USER_ID, byId(TARGET_USER_ID, "  "), mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "missing_plan");
            verifyNoInteractions(adminPlanService);
        }

        @Test
        @DisplayName("unsupported plan (ENTERPRISE_BASIC) → 400 unsupported_plan - service untouched")
        void unsupportedPlanReturns400() {
            ResponseEntity<Map<String, Object>> response = controller.assignPlan(
                    ADMIN_ROLES, ADMIN_USER_ID, byId(TARGET_USER_ID, "ENTERPRISE_BASIC"), mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "unsupported_plan");
            verifyNoInteractions(adminPlanService);
        }

        @Test
        @DisplayName("malformed email (no @) → 400 invalid_target_email")
        void malformedEmailReturns400() {
            ResponseEntity<Map<String, Object>> response = controller.assignPlan(
                    ADMIN_ROLES, ADMIN_USER_ID, byEmail("alice", "PRO"), mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).containsEntry("error", "invalid_target_email");
            verifyNoInteractions(userRepository);
        }
    }

    // ─────────────────────── Happy path ───────────────────────

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("valid grant by id: calls service, returns new + previous plan, audits success")
        void validGrantById() {
            when(adminPlanService.assignPlan(eq(TARGET_USER_ID), eq("PRO"), eq(ADMIN_USER_ID)))
                    .thenReturn(AssignPlanResult.ok("FREE", "PRO"));
            stubAuditChain();

            ResponseEntity<Map<String, Object>> response = controller.assignPlan(
                    ADMIN_ROLES, ADMIN_USER_ID, byId(TARGET_USER_ID, "PRO"), mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .containsEntry("success", true)
                    .containsEntry("target_user_id", TARGET_USER_ID)
                    .containsEntry("plan_code", "PRO")
                    .containsEntry("previous_plan_code", "FREE");
            verify(auditLogger).event("plan.granted");
            verify(auditBuilder).success();
            verify(auditBuilder).write();
            verify(auditBuilder, never()).failure(anyString());
        }

        @Test
        @DisplayName("lowercase plan code is normalized before validation + service call")
        void lowercasePlanNormalized() {
            when(adminPlanService.assignPlan(eq(TARGET_USER_ID), eq("team"), eq(ADMIN_USER_ID)))
                    .thenReturn(AssignPlanResult.ok("FREE", "TEAM"));
            stubAuditChain();

            ResponseEntity<Map<String, Object>> response = controller.assignPlan(
                    ADMIN_ROLES, ADMIN_USER_ID, byId(TARGET_USER_ID, "team"), mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            // The controller passes the raw plan_code through; the service normalizes it.
            verify(adminPlanService).assignPlan(eq(TARGET_USER_ID), eq("team"), eq(ADMIN_USER_ID));
        }

        @Test
        @DisplayName("revert to FREE is a valid grant")
        void revertToFree() {
            when(adminPlanService.assignPlan(eq(TARGET_USER_ID), eq("FREE"), eq(ADMIN_USER_ID)))
                    .thenReturn(AssignPlanResult.ok("PRO", "FREE"));
            stubAuditChain();

            ResponseEntity<Map<String, Object>> response = controller.assignPlan(
                    ADMIN_ROLES, ADMIN_USER_ID, byId(TARGET_USER_ID, "FREE"), mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).containsEntry("plan_code", "FREE");
        }
    }

    // ─────────────────────── Email lookup ───────────────────────

    @Nested
    @DisplayName("email lookup")
    class EmailLookup {

        @Test
        @DisplayName("valid email resolves to id and the resolved email is echoed back")
        void emailResolvesAndAssigns() {
            User user = mockUser(TARGET_USER_ID, TARGET_EMAIL);
            when(userRepository.findByEmail(TARGET_EMAIL)).thenReturn(Optional.of(user));
            when(adminPlanService.assignPlan(eq(TARGET_USER_ID), eq("STARTER"), eq(ADMIN_USER_ID)))
                    .thenReturn(AssignPlanResult.ok("FREE", "STARTER"));
            stubAuditChain();

            ResponseEntity<Map<String, Object>> response = controller.assignPlan(
                    ADMIN_ROLES, ADMIN_USER_ID, byEmail(TARGET_EMAIL, "STARTER"), mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody())
                    .containsEntry("target_user_id", TARGET_USER_ID)
                    .containsEntry("target_email", TARGET_EMAIL)
                    .containsEntry("plan_code", "STARTER");
        }

        @Test
        @DisplayName("unknown email → 404 user_not_found - service untouched")
        void unknownEmailReturns404() {
            when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

            ResponseEntity<Map<String, Object>> response = controller.assignPlan(
                    ADMIN_ROLES, ADMIN_USER_ID, byEmail("nobody@example.com", "PRO"), mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).containsEntry("error", "user_not_found");
            verifyNoInteractions(adminPlanService);
        }
    }

    // ─────────────────────── Service failure mapping ───────────────────────

    @Nested
    @DisplayName("service failure mapping")
    class ServiceFailure {

        @Test
        @DisplayName("paid Stripe subscription → 409 Conflict + audit failure")
        void paidSubscriptionReturns409() {
            when(adminPlanService.assignPlan(eq(TARGET_USER_ID), eq("PRO"), eq(ADMIN_USER_ID)))
                    .thenReturn(AssignPlanResult.fail("has_paid_subscription"));
            stubAuditChain();

            ResponseEntity<Map<String, Object>> response = controller.assignPlan(
                    ADMIN_ROLES, ADMIN_USER_ID, byId(TARGET_USER_ID, "PRO"), mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody()).containsEntry("error", "has_paid_subscription");
            verify(auditBuilder).failure("has_paid_subscription");
            verify(auditBuilder).write();
            verify(auditBuilder, never()).success();
        }

        @Test
        @DisplayName("service user_not_found → 404")
        void serviceUserNotFoundReturns404() {
            when(adminPlanService.assignPlan(eq(TARGET_USER_ID), eq("PRO"), eq(ADMIN_USER_ID)))
                    .thenReturn(AssignPlanResult.fail("user_not_found"));
            stubAuditChain();

            ResponseEntity<Map<String, Object>> response = controller.assignPlan(
                    ADMIN_ROLES, ADMIN_USER_ID, byId(TARGET_USER_ID, "PRO"), mockRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).containsEntry("error", "user_not_found");
        }
    }
}
