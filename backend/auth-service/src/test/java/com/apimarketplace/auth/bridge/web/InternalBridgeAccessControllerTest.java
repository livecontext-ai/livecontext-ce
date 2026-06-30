package com.apimarketplace.auth.bridge.web;

import com.apimarketplace.auth.bridge.domain.BridgeAccessModels.AccessDecision;
import com.apimarketplace.auth.bridge.service.BridgeAccessService;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the admin-resolution logic that gates {@code ADMIN_ONLY} bridge
 * providers (e.g. claude-code).
 *
 * <p>Regression context: the schedule daemon dispatches a scheduled agent via the
 * synchronous chat path carrying only {@code X-User-ID} (no {@code X-User-Roles}).
 * Before the fix the controller derived admin purely from the header, so an
 * admin-owned scheduled bridge agent was mis-classified as a plain {@code USER}
 * and silently denied under {@code ADMIN_ONLY}. The fix resolves admin from the
 * persisted role store when the header does not assert it.
 */
class InternalBridgeAccessControllerTest {

    private BridgeAccessService service;
    private UserService userService;
    private InternalBridgeAccessController controller;

    @BeforeEach
    void setUp() {
        service = mock(BridgeAccessService.class);
        userService = mock(UserService.class);
        controller = new InternalBridgeAccessController(service, userService);
        // The decision body is irrelevant to these tests; we assert the resolved
        // isAdmin flag handed to the service via an ArgumentCaptor.
        when(service.checkAccess(anyString(), anyBoolean(), anyString(), anyBoolean()))
                .thenReturn(AccessDecision.allow("claude-code", null));
    }

    private boolean capturedIsAdmin() {
        ArgumentCaptor<Boolean> captor = ArgumentCaptor.forClass(Boolean.class);
        verify(service).checkAccess(anyString(), captor.capture(), anyString(), anyBoolean());
        return captor.getValue();
    }

    private User userWithRoles(String... roles) {
        User u = new User();
        u.setRoles(Set.of(roles));
        return u;
    }

    @Test
    @DisplayName("Header asserting ADMIN is trusted and short-circuits the role store")
    void headerAdminShortCircuitsStore() {
        controller.check("claude-code", true, "42", "USER,ADMIN");

        assertThat(capturedIsAdmin()).isTrue();
        // No DB read when the validated header already asserts ADMIN.
        verify(userService, never()).findById(any());
    }

    @Test
    @DisplayName("Role-less caller owned by an admin is resolved to ADMIN from the store (Nova regression)")
    void rolelessAdminResolvedFromStore() {
        // Daemon path: X-User-Roles defaults to USER (no header forwarded).
        when(userService.findById(42L)).thenReturn(Optional.of(userWithRoles("USER", "ADMIN")));

        controller.check("claude-code", true, "42", "USER");

        // Pre-fix this was false (header-only) → silent ADMIN_ONLY denial.
        assertThat(capturedIsAdmin()).isTrue();
        verify(userService).findById(42L);
    }

    @Test
    @DisplayName("Role-less caller who is NOT an admin in the store stays non-admin")
    void rolelessNonAdminStaysNonAdmin() {
        when(userService.findById(7L)).thenReturn(Optional.of(userWithRoles("USER")));

        controller.check("claude-code", true, "7", "USER");

        assertThat(capturedIsAdmin()).isFalse();
        verify(userService).findById(7L);
    }

    @Test
    @DisplayName("Role-less caller whose stored roles are null resolves to non-admin (no NPE)")
    void rolelessNullRolesStaysNonAdmin() {
        // User.setRoles(null) coerces to an empty set, so a genuine null must be
        // stubbed to exercise the defensive getRoles() != null guard.
        User noRoles = mock(User.class);
        when(noRoles.getRoles()).thenReturn(null);
        when(userService.findById(8L)).thenReturn(Optional.of(noRoles));

        controller.check("claude-code", true, "8", "USER");

        assertThat(capturedIsAdmin()).isFalse();
    }

    @Test
    @DisplayName("Unknown user in the store resolves to non-admin")
    void unknownUserResolvesNonAdmin() {
        when(userService.findById(99L)).thenReturn(Optional.empty());

        controller.check("claude-code", true, "99", "USER");

        assertThat(capturedIsAdmin()).isFalse();
    }

    @Test
    @DisplayName("Non-numeric user id (CE / non-cloud principal) falls back to the header without throwing")
    void nonNumericUserIdFallsBackToHeader() {
        controller.check("claude-code", true, "kc-uuid-sub", "USER");

        assertThat(capturedIsAdmin()).isFalse();
        // Parse fails before any store lookup.
        verify(userService, never()).findById(any());
    }

    @Test
    @DisplayName("Non-numeric user id still honours an ADMIN header")
    void nonNumericUserIdHonoursAdminHeader() {
        controller.check("claude-code", true, "kc-uuid-sub", "ADMIN");

        assertThat(capturedIsAdmin()).isTrue();
        verify(userService, never()).findById(any());
    }

    @Test
    @DisplayName("Blank X-User-ID is rejected with 400 and no decision is taken")
    void blankUserIdRejected() {
        ResponseEntity<?> resp = controller.check("claude-code", true, "", "USER");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).checkAccess(anyString(), anyBoolean(), eq("claude-code"), anyBoolean());
    }
}
