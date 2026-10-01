package com.apimarketplace.auth.web;

import com.apimarketplace.auth.audit.AuditLogger;
import com.apimarketplace.auth.service.PersonalOfferAdminService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("Personal offer admin routes require the existing admin role guard")
class AdminPersonalOfferControllerTest {
    private PersonalOfferAdminService service;
    private AuditLogger audit;
    private AuditLogger.Builder auditEvent;
    private AdminPersonalOfferController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(PersonalOfferAdminService.class);
        audit = mock(AuditLogger.class);
        auditEvent = mock(AuditLogger.Builder.class, RETURNS_SELF);
        when(audit.event(anyString())).thenReturn(auditEvent);
        controller = new AdminPersonalOfferController(service, audit, false);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "SUPERADMIN", "", "USER,NOT_ADMIN"})
    @DisplayName("Non-admin roles cannot list, edit, activate, pause, or revoke personal offers")
    void noAdminMeansNoAccess(String roles) {
        assertThat(controller.policies(roles).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.codes(roles).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.create(roles, 1L, null).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.update(roles, 1L, 1L, null).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.activate(roles, 1L, 1L).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.pause(roles, 1L, 1L).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.disable(roles, 1L, 1L).getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(service, audit);
    }

    @Test
    @DisplayName("The policies HTTP response uses the frontend's policies envelope")
    void listUsesAgreedEnvelope() throws Exception {
        when(service.list()).thenReturn(List.of());
        mvc.perform(get("/api/admin/credits/offers/policies").header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.policies").isArray());
    }

    @Test
    @DisplayName("A published-policy edit returns a stable validation error and writes an audit failure")
    void immutablePolicyReturnsValidationError() throws Exception {
        when(service.update(2L, null)).thenThrow(new PersonalOfferAdminService.InvalidPolicy("OFFER_POLICY_IMMUTABLE"));
        mvc.perform(put("/api/admin/credits/offers/policies/2").header("X-User-Roles", "ADMIN").header("X-User-ID", "1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("OFFER_POLICY_IMMUTABLE"));
        verify(auditEvent).failure("OFFER_POLICY_IMMUTABLE");
        verify(auditEvent).write();
    }

    @Test
    @DisplayName("Personal campaign administration is unavailable with unlimited CE credits")
    void ceCannotActivateCloudCampaign() {
        var ce = new AdminPersonalOfferController(service, audit, true);
        assertThat(ce.activate("ADMIN", 1L, 1L).getStatusCode().value()).isEqualTo(503);
        verifyNoInteractions(service, audit);
    }

    @Test
    @DisplayName("Revoking a code records its ID and admin without writing the personal code to audit")
    void revokeIsAudited() throws Exception {
        mvc.perform(post("/api/admin/credits/offers/codes/9/disable")
                        .header("X-User-Roles", "ADMIN").header("X-User-ID", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(service).disableCode(9L);
        verify(auditEvent).detail("target_id", 9L);
        verify(auditEvent).user(1L);
        verify(auditEvent).success();
        verify(auditEvent).write();
    }
}
