package com.apimarketplace.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * PR5 regression - pin the owner-of-active-workspace guard.
 *
 * The 9 BillingController write endpoints (checkout, portal, cancel,
 * reactivate, change-plan, downgrade, change-credit-tier, change-cycle,
 * scheduled-change DELETE) all funnel through {@code requireActiveOrgOwner}.
 * A drift here = members of a workspace they don't own could mutate the
 * org owner's subscription. This guard reads {@code X-Organization-Role}
 * from the gateway-injected header (PR0.5b) which is itself sourced from
 * the user's actual memberships - header injection is therefore safe.
 *
 * <p>The helper is private; we invoke it via reflection to avoid
 * standing up Spring + WebMvc just to verify a 5-line gate.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BillingController.requireActiveOrgOwner (PR5 guard)")
class BillingControllerOwnerGuardTest {

    @Mock
    private HttpServletRequest request;

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, Object>> invokeGuard(BillingController controller, HttpServletRequest req)
            throws Exception {
        Method m = BillingController.class.getDeclaredMethod("requireActiveOrgOwner", HttpServletRequest.class);
        m.setAccessible(true);
        return (ResponseEntity<Map<String, Object>>) m.invoke(controller, req);
    }

    @Test
    @DisplayName("OWNER passes - guard returns null (call proceeds)")
    void ownerPasses() throws Exception {
        when(request.getHeader("X-Organization-Role")).thenReturn("OWNER");

        ResponseEntity<Map<String, Object>> result = invokeGuard(new BillingController(), request);

        assertThat(result).as("null = guard does not block, call proceeds").isNull();
    }

    @Test
    @DisplayName("OWNER is case-insensitive - defensive against header-case drift")
    void ownerCaseInsensitive() throws Exception {
        when(request.getHeader("X-Organization-Role")).thenReturn("owner");

        ResponseEntity<Map<String, Object>> result = invokeGuard(new BillingController(), request);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("MEMBER blocked with 403 NOT_WORKSPACE_OWNER - the core protection")
    void memberBlocked() throws Exception {
        when(request.getHeader("X-Organization-Role")).thenReturn("MEMBER");

        ResponseEntity<Map<String, Object>> result = invokeGuard(new BillingController(), request);

        assertThat(result).isNotNull();
        assertThat(result.getStatusCode().value()).isEqualTo(403);
        assertThat(result.getBody())
                .containsEntry("code", "NOT_WORKSPACE_OWNER")
                .extracting("message").asString()
                .contains("Switch to your personal organization");
    }

    @Test
    @DisplayName("ADMIN blocked too - only OWNER (not ADMIN) can manage billing per Q1=b")
    void adminBlocked() throws Exception {
        when(request.getHeader("X-Organization-Role")).thenReturn("ADMIN");

        ResponseEntity<Map<String, Object>> result = invokeGuard(new BillingController(), request);

        assertThat(result).isNotNull();
        assertThat(result.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("VIEWER blocked")
    void viewerBlocked() throws Exception {
        when(request.getHeader("X-Organization-Role")).thenReturn("VIEWER");

        ResponseEntity<Map<String, Object>> result = invokeGuard(new BillingController(), request);

        assertThat(result.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("Missing role header → 403 fail-closed (gateway didn't inject for some reason)")
    void missingHeaderFailsClosed() throws Exception {
        when(request.getHeader("X-Organization-Role")).thenReturn(null);

        ResponseEntity<Map<String, Object>> result = invokeGuard(new BillingController(), request);

        assertThat(result).isNotNull();
        assertThat(result.getStatusCode().value()).isEqualTo(403);
    }

    @Test
    @DisplayName("Garbage role string → 403 fail-closed")
    void unknownRoleFailsClosed() throws Exception {
        when(request.getHeader("X-Organization-Role")).thenReturn("CUSTOM_ROLE_XYZ");

        ResponseEntity<Map<String, Object>> result = invokeGuard(new BillingController(), request);

        assertThat(result.getStatusCode().value()).isEqualTo(403);
    }
}
