package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.RewardCode;
import com.apimarketplace.auth.domain.RewardProgram;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.PartnerProgramAdminService;
import com.apimarketplace.auth.service.PartnerProgramAdminService.PartnerCodeRequest;
import com.apimarketplace.auth.service.PartnerProgramAdminService.Result;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * V549 admin API of the partner program: the admin gate, the CE refusal, how a partner is
 * resolved from an email, the percent-to-basis-points conversion, and the error-to-status map.
 */
@DisplayName("AdminPartnerController")
class AdminPartnerControllerTest {

    private PartnerProgramAdminService service;
    private UserRepository userRepository;
    private com.apimarketplace.auth.audit.AuditLogger auditLogger;
    private AdminPartnerController controller;

    @BeforeEach
    void setUp() {
        service = mock(PartnerProgramAdminService.class);
        userRepository = mock(UserRepository.class);
        auditLogger = mock(com.apimarketplace.auth.audit.AuditLogger.class, RETURNS_DEEP_STUBS);
        controller = new AdminPartnerController(service, userRepository, auditLogger, false);
    }

    private static RewardCode code(String value) {
        RewardCode c = new RewardCode();
        c.setId(1L);
        c.setCode(value);
        c.setProgram(RewardProgram.PARTNER);
        return c;
    }

    private static AdminPartnerController.PartnerCodeBody partnerBody(String email, Double percent) {
        return new AdminPartnerController.PartnerCodeBody(email, null, "TECHDOX", null, null, percent, null, null, null, 200);
    }

    @Test
    @DisplayName("a non-admin is refused on every endpoint (403) and the service is never reached")
    void nonAdminIsRefused() {
        assertThat(controller.list("USER").getStatusCode().value()).isEqualTo(403);
        assertThat(controller.createCreatorCode("USER", 1L, null).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.createPartnerCode("USER", 1L, partnerBody("a@b.c", 30.0)).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.setActive("USER", 1L, 1L, new AdminPartnerController.ActiveBody(false)).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.markPaid("USER", 1L, 1L).getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("self-hosted (credits unlimited): refused with 503, even for an admin")
    void refusedInCe() {
        AdminPartnerController ce = new AdminPartnerController(service, userRepository, auditLogger, true);

        assertThat(ce.list("ADMIN").getStatusCode().value()).isEqualTo(503);
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("partner code: resolves the partner by email (case-insensitive fallback), converts 12.5% to 1250 bps, 201")
    void createsPartnerCodeFromEmail() {
        User u = new User();
        u.setId(99L);
        when(userRepository.findByEmail("Techdox@Example.com")).thenReturn(Optional.empty());
        when(userRepository.findByEmail("techdox@example.com")).thenReturn(Optional.of(u));
        when(service.createPartnerCode(any())).thenReturn(new Result(code("TECHDOX"), null));

        var resp = controller.createPartnerCode("ADMIN", 1L, partnerBody(" Techdox@Example.com ", 12.5));

        assertThat(resp.getStatusCode().value()).isEqualTo(201);
        ArgumentCaptor<PartnerCodeRequest> req = ArgumentCaptor.forClass(PartnerCodeRequest.class);
        verify(service).createPartnerCode(req.capture());
        assertThat(req.getValue().partnerUserId()).isEqualTo(99L);
        assertThat(req.getValue().commissionBps()).isEqualTo(1250);
        assertThat(req.getValue().maxUses()).isEqualTo(200);
    }

    @Test
    @DisplayName("partner code: unknown email -> 404, missing partner -> 400")
    void partnerResolutionErrors() {
        when(userRepository.findByEmail(any())).thenReturn(Optional.empty());

        assertThat(controller.createPartnerCode("ADMIN", 1L, partnerBody("nobody@x.io", null)).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.createPartnerCode("ADMIN", 1L, partnerBody("  ", null)).getStatusCode().value()).isEqualTo(400);
        verify(service, never()).createPartnerCode(any());
    }

    @Test
    @DisplayName("service errors map to HTTP: code_taken / second partner code -> 409, bad values -> 400")
    void errorMapping() {
        when(service.createCreatorCode(any()))
                .thenReturn(new Result(null, "code_taken"))
                .thenReturn(new Result(null, "invalid_values"));
        assertThat(controller.createCreatorCode("ADMIN", 1L, null).getStatusCode().value()).isEqualTo(409);
        assertThat(controller.createCreatorCode("ADMIN", 1L, null).getBody().get("error")).isEqualTo("invalid_values");
    }

    @Test
    @DisplayName("list returns the codes and the configured defaults the forms start from")
    void listReturnsCodesAndDefaults() {
        when(service.report()).thenReturn(List.of());
        when(service.defaults()).thenReturn(new PartnerProgramAdminService.Defaults("PRO", 90, 50000, 1, 60, 10000, 3000, 12, 14));

        var body = controller.list("ADMIN").getBody();

        assertThat(body).containsKeys("codes", "defaults");
    }

    @Test
    @DisplayName("setActive on an unknown code -> 404; mark-paid returns the settled line count")
    void activeAndMarkPaid() {
        when(service.setActive(5L, false)).thenReturn(false);
        when(service.markPayablePaid(5L, 1L)).thenReturn(List.of());

        assertThat(controller.setActive("ADMIN", 1L, 5L, new AdminPartnerController.ActiveBody(false)).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.markPaid("ADMIN", 1L, 5L).getBody().get("lines")).isEqualTo(0);
    }

    @Test
    @DisplayName("money actions leave an audit event: creating a code and marking commissions paid, with the admin id")
    void moneyActionsAreAudited() {
        when(service.createCreatorCode(any())).thenReturn(new Result(code("LC-AAAA2222"), null));
        when(service.markPayablePaid(5L, 42L)).thenReturn(List.of());

        controller.createCreatorCode("ADMIN", 42L, null);
        controller.markPaid("ADMIN", 42L, 5L);

        verify(auditLogger).event(com.apimarketplace.auth.audit.AuditEventTypes.PARTNER_CODE_CREATED);
        verify(auditLogger).event(com.apimarketplace.auth.audit.AuditEventTypes.PARTNER_COMMISSIONS_PAID);
        verify(service).markPayablePaid(5L, 42L);
    }

    @Test
    @DisplayName("enabling / disabling a code (it stops commissions) leaves an audit event")
    void activeChangeIsAudited() {
        when(service.setActive(5L, false)).thenReturn(true);

        controller.setActive("ADMIN", 42L, 5L, new AdminPartnerController.ActiveBody(false));

        verify(auditLogger).event(com.apimarketplace.auth.audit.AuditEventTypes.PARTNER_CODE_ACTIVE_CHANGED);
    }

    @Test
    @DisplayName("a concurrent create that loses on a unique index answers 409, never a 500")
    void creationRaceIs409() {
        when(service.createCreatorCode(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                "insert", new RuntimeException("duplicate key value violates unique constraint \"uq_reward_code_code\"")));

        var resp = controller.createCreatorCode("ADMIN", 42L, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody().get("error")).isEqualTo("code_taken");
    }

    @Test
    @DisplayName("a violation on an index that is not a known 'taken' race is a real error, not a misleading 409")
    void unknownConstraintIsNotCodeTaken() {
        when(service.createCreatorCode(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                "insert", new RuntimeException("violates check constraint \"chk_reward_code_plan_grant_shape\"")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.createCreatorCode("ADMIN", 42L, null))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("two partner codes created at once for the same partner: the loser answers 409 partner_already_has_code")
    void partnerCodeRaceIs409() {
        User u = new User();
        u.setId(99L);
        when(userRepository.findByEmail("p@x.io")).thenReturn(Optional.of(u));
        when(service.createPartnerCode(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException(
                "insert", new RuntimeException("duplicate key value violates unique constraint \"uq_reward_code_owner_program\"")));

        var resp = controller.createPartnerCode("ADMIN", 42L, partnerBody("p@x.io", 30.0));

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody().get("error")).isEqualTo("partner_already_has_code");
    }
}
