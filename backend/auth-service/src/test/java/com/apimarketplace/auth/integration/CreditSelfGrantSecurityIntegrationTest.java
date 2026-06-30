package com.apimarketplace.auth.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Simulates the core "can't give yourself extra credits" guarantee against the
 * real web layer: credit GRANTS are ADMIN-only. The gateway strips any client
 * X-User-Roles and re-injects roles from the validated JWT, so a self-service /
 * CE caller can only ever present a non-ADMIN role - and the controller rejects
 * it with 403 BEFORE any grant logic runs. There is no request shape by which a
 * non-admin mints credits.
 *
 * <p>(The complementary "even an ADMIN cannot grant in CE/unlimited mode → 503"
 * is enforced by {@code AdminCreditController} when {@code credit.unlimited=true},
 * which is the CE monolith's runtime config - exercised in the CE boot, not here,
 * since flipping unlimited=true requires full CE edition+embedded flags.)
 */
@IntegrationTest
@AutoConfigureMockMvc
@DisplayName("Credit self-grant security (real web layer)")
class CreditSelfGrantSecurityIntegrationTest {

    @Autowired private MockMvc mockMvc;

    private static final String GRANT_BODY = "{\"targetUserId\":999,\"amount\":100}";

    @Test
    @DisplayName("non-ADMIN caller cannot grant credits → 403 (before any grant logic)")
    void nonAdminCannotGrantCredits() throws Exception {
        mockMvc.perform(post("/api/admin/credits/grant")
                        .header("X-User-ID", "42")
                        .header("X-User-Roles", "USER")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GRANT_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("caller with no roles header (default USER) cannot grant credits → 403")
    void missingRolesCannotGrantCredits() throws Exception {
        mockMvc.perform(post("/api/admin/credits/grant")
                        .header("X-User-ID", "42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(GRANT_BODY))
                .andExpect(status().isForbidden());
    }
}
