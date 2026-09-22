package com.apimarketplace.auth.web;

import com.apimarketplace.auth.audit.AuditEventTypes;
import com.apimarketplace.auth.audit.AuditLogger;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.VerifiedAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminVerifiedAccountController")
class AdminVerifiedAccountControllerTest {

    private static final String PATH = "/api/admin/verified-accounts/set";

    @Mock private VerifiedAccountService verifiedAccountService;
    @Mock private UserRepository userRepository;
    @Mock private AuditLogger auditLogger;

    private AuditLogger.Builder auditBuilder;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // The audit builder is fluent, so every setter has to hand itself back or the
        // controller's chain NPEs before the assertion under test is reached. Lenient
        // because a given test walks only one branch and so touches only some of them;
        // strictness stays ON for every stub the individual tests declare.
        auditBuilder = mock(AuditLogger.Builder.class);
        // any(), not any(Long.class): an anonymous caller has no X-User-ID, the controller
        // passes null, and a typed matcher does not match null - the fluent chain would
        // NPE and the deny path would be untestable rather than merely untested.
        lenient().when(auditBuilder.user(nullable(Long.class))).thenReturn(auditBuilder);
        lenient().when(auditBuilder.ip(anyString())).thenReturn(auditBuilder);
        lenient().when(auditBuilder.userAgent(any())).thenReturn(auditBuilder);
        lenient().when(auditBuilder.warn()).thenReturn(auditBuilder);
        lenient().when(auditBuilder.success()).thenReturn(auditBuilder);
        lenient().when(auditBuilder.failure(anyString())).thenReturn(auditBuilder);
        lenient().when(auditBuilder.detail(anyString(), any())).thenReturn(auditBuilder);
        lenient().when(auditLogger.event(anyString())).thenReturn(auditBuilder);

        lenient().when(verifiedAccountService.isFeatureEnabled()).thenReturn(true);

        mockMvc = MockMvcBuilders.standaloneSetup(
                new AdminVerifiedAccountController(verifiedAccountService, userRepository, auditLogger)
        ).build();
    }

    private static User user(long id, String email) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        return u;
    }

    @Test
    @DisplayName("POST /set → 403 without the ADMIN role, and the badge is never touched")
    void nonAdminIsForbidden() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-User-Roles", "USER")
                        .header("X-User-ID", "42")
                        .contentType("application/json")
                        .content("{\"target_email\":\"alice@example.com\",\"verified\":true}"))
                .andExpect(status().isForbidden());

        verify(verifiedAccountService, never()).setManualVerified(anyLong(), anyBoolean(), anyLong());
    }

    @Test
    @DisplayName("POST /set → a denied attempt is audited, with the target it tried to reach")
    void deniedAttemptIsAudited() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-User-Roles", "USER")
                        .header("X-User-ID", "42")
                        .contentType("application/json")
                        .content("{\"target_email\":\"alice@example.com\",\"verified\":true}"))
                .andExpect(status().isForbidden());

        verify(auditLogger).event(AuditEventTypes.ACCOUNT_VERIFIED);
        verify(auditBuilder).failure("forbidden_non_admin");
        verify(auditBuilder).detail("attempted_target_email", "alice@example.com");
        verify(auditBuilder).write();
    }

    @Test
    @DisplayName("POST /set → 403 when the caller sends no role header at all")
    void missingRoleHeaderIsForbidden() throws Exception {
        // The header defaults to USER, so an absent one must deny exactly like a
        // non-admin: a request that reaches this endpoint without the gateway's
        // injection is not an admin request.
        mockMvc.perform(post(PATH)
                        .header("X-User-ID", "42")
                        .contentType("application/json")
                        .content("{\"target_user_id\":7,\"verified\":true}"))
                .andExpect(status().isForbidden());

        verify(verifiedAccountService, never()).setManualVerified(anyLong(), anyBoolean(), anyLong());
    }

    @Test
    @DisplayName("POST /set → 403 for a fully anonymous caller, and the denial is still audited")
    void anonymousCallerIsForbiddenAndAudited() throws Exception {
        // No X-User-ID either: the audit builder is handed a null user id, which is the
        // case a typed any(Long.class) matcher silently fails to cover.
        mockMvc.perform(post(PATH)
                        .contentType("application/json")
                        .content("{\"target_user_id\":7,\"verified\":true}"))
                .andExpect(status().isForbidden());

        verify(auditLogger).event(AuditEventTypes.ACCOUNT_VERIFIED);
        verify(auditBuilder).failure("forbidden_non_admin");
        verify(auditBuilder).write();
        verify(verifiedAccountService, never()).setManualVerified(anyLong(), anyBoolean(), anyLong());
    }

    @Test
    @DisplayName("POST /set → 503 on a self-hosted deployment, where the badge does not exist")
    void selfHostedIsRefused() throws Exception {
        when(verifiedAccountService.isFeatureEnabled()).thenReturn(false);

        mockMvc.perform(post(PATH)
                        .header("X-User-Roles", "ADMIN")
                        .header("X-User-ID", "42")
                        .contentType("application/json")
                        .content("{\"target_email\":\"alice@example.com\",\"verified\":true}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("not_available_self_hosted"));

        verify(verifiedAccountService, never()).setManualVerified(anyLong(), anyBoolean(), anyLong());
    }

    @Test
    @DisplayName("POST /set → 400 when both target fields are given (ambiguous)")
    void bothTargetsIsRejected() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-User-Roles", "ADMIN")
                        .header("X-User-ID", "42")
                        .contentType("application/json")
                        .content("{\"target_email\":\"a@b.c\",\"target_user_id\":7,\"verified\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ambiguous_target"));
    }

    @Test
    @DisplayName("POST /set → 400 when neither target field is given")
    void noTargetIsRejected() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-User-Roles", "ADMIN")
                        .header("X-User-ID", "42")
                        .contentType("application/json")
                        .content("{\"verified\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_target"));
    }

    @Test
    @DisplayName("POST /set → 400 when `verified` is omitted: grant and revoke must be said out loud")
    void missingVerifiedIsRejected() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-User-Roles", "ADMIN")
                        .header("X-User-ID", "42")
                        .contentType("application/json")
                        .content("{\"target_user_id\":7}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_verified"));

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("POST /set → 400 on a non-positive user id")
    void nonPositiveIdIsRejected() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-User-Roles", "ADMIN")
                        .header("X-User-ID", "42")
                        .contentType("application/json")
                        .content("{\"target_user_id\":0,\"verified\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_target_user_id"));
    }

    @Test
    @DisplayName("POST /set → 400 on an empty body, rather than a 500 from the null deref")
    void missingBodyIsRejected() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-User-Roles", "ADMIN")
                        .header("X-User-ID", "42")
                        .contentType("application/json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_body"));

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("POST /set → a blank email counts as NO target, not as an email to look up")
    void blankEmailIsNoTarget() throws Exception {
        mockMvc.perform(post(PATH)
                        .header("X-User-Roles", "ADMIN")
                        .header("X-User-ID", "42")
                        .contentType("application/json")
                        .content("{\"target_email\":\"   \",\"verified\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("missing_target"));

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("POST /set → 404 when the target email matches no account")
    void unknownEmailIsNotFound() throws Exception {
        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        mockMvc.perform(post(PATH)
                        .header("X-User-Roles", "ADMIN")
                        .header("X-User-ID", "42")
                        .contentType("application/json")
                        .content("{\"target_email\":\"ghost@example.com\",\"verified\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("user_not_found"));
    }

    @Test
    @DisplayName("POST /set → resolves the target by email, grants, and answers the stored state")
    void grantsByEmail() throws Exception {
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user(7L, "alice@example.com")));
        when(verifiedAccountService.setManualVerified(7L, true, 42L)).thenReturn(
                Optional.of(new VerifiedAccountService.VerificationState(
                        7L, "alice@example.com", true, false, true, false)));

        mockMvc.perform(post(PATH)
                        .header("X-User-Roles", "ADMIN")
                        .header("X-User-ID", "42")
                        .contentType("application/json")
                        .content("{\"target_email\":\"alice@example.com\",\"verified\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(7))
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.verifiedByRole").value(false))
                .andExpect(jsonPath("$.effectivelyVerified").value(true));

        verify(auditBuilder).detail("verified", true);
        verify(auditBuilder).write();
    }

    @Test
    @DisplayName("POST /set → revoking an ADMIN reports that the role keeps the badge showing")
    void revokeOnAdminReportsRoleGrant() throws Exception {
        when(verifiedAccountService.setManualVerified(eq(7L), eq(false), eq(42L))).thenReturn(
                Optional.of(new VerifiedAccountService.VerificationState(
                        7L, "root@example.com", false, true, true, false)));

        mockMvc.perform(post(PATH)
                        .header("X-User-Roles", "ADMIN")
                        .header("X-User-ID", "42")
                        .contentType("application/json")
                        .content("{\"target_user_id\":7,\"verified\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.verifiedByRole").value(true))
                // The operator must not read this as "badge removed": it is still showing.
                .andExpect(jsonPath("$.effectivelyVerified").value(true));
    }

    @Test
    @DisplayName("POST /set → reports a grant on a withdrawn profile as stored but not showing")
    void withdrawnProfileIsReported() throws Exception {
        when(verifiedAccountService.setManualVerified(eq(7L), eq(true), eq(42L))).thenReturn(
                Optional.of(new VerifiedAccountService.VerificationState(
                        7L, "hidden@example.com", true, false, false, true)));

        mockMvc.perform(post(PATH)
                        .header("X-User-Roles", "ADMIN")
                        .header("X-User-ID", "42")
                        .contentType("application/json")
                        .content("{\"target_user_id\":7,\"verified\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.profileWithdrawn").value(true))
                // The operator must not read this as "the badge is now showing".
                .andExpect(jsonPath("$.effectivelyVerified").value(false));
    }

    @Test
    @DisplayName("POST /set → 404 when the id resolves to no account")
    void unknownIdIsNotFound() throws Exception {
        when(verifiedAccountService.setManualVerified(eq(7L), anyBoolean(), anyLong()))
                .thenReturn(Optional.empty());

        mockMvc.perform(post(PATH)
                        .header("X-User-Roles", "ADMIN")
                        .header("X-User-ID", "42")
                        .contentType("application/json")
                        .content("{\"target_user_id\":7,\"verified\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("user_not_found"));
    }
}
