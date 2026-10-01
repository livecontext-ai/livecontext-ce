package com.apimarketplace.auth.web;

import com.apimarketplace.auth.audit.AuditLogger;
import com.apimarketplace.auth.domain.PartnerApplication;
import com.apimarketplace.auth.domain.RewardCode;
import com.apimarketplace.auth.repository.RewardCodeRepository;
import com.apimarketplace.auth.service.PartnerProgramMailer;
import com.apimarketplace.auth.service.PartnerProgramService;
import com.apimarketplace.auth.service.PartnerProgramService.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** V553 admin queue: list, approve (creates the code, mails the applicant), reject. */
@DisplayName("AdminPartnerApplicationController")
class AdminPartnerApplicationControllerTest {

    private PartnerProgramService service;
    private PartnerProgramMailer mailer;
    private RewardCodeRepository codeRepository;
    private AuditLogger auditLogger;
    private AdminPartnerApplicationController controller;

    @BeforeEach
    void setUp() {
        service = mock(PartnerProgramService.class);
        mailer = mock(PartnerProgramMailer.class);
        codeRepository = mock(RewardCodeRepository.class);
        auditLogger = mock(AuditLogger.class, RETURNS_DEEP_STUBS);
        controller = new AdminPartnerApplicationController(service, mailer, codeRepository, auditLogger, false);
        // The application is still pending unless a case says another admin decided it first.
        when(service.isPending(anyLong())).thenReturn(true);
    }

    private static PartnerApplication application(PartnerApplication.Status status) {
        PartnerApplication a = new PartnerApplication();
        a.setId(5L);
        a.setUserId(7L);
        a.setStatus(status);
        a.setCompanyName("Acme");
        a.setRewardCodeId(status == PartnerApplication.Status.APPROVED ? 99L : null);
        return a;
    }

    @Test
    @DisplayName("a non-admin is refused 403 on every action, and nothing is decided or mailed")
    void nonAdminRefused() {
        assertThat(controller.list("USER", "pending").getStatusCode().value()).isEqualTo(403);
        assertThat(controller.approve("USER", 1L, 5L, null).getStatusCode().value()).isEqualTo(403);
        assertThat(controller.reject("USER", 1L, 5L, null).getStatusCode().value()).isEqualTo(403);
        verifyNoInteractions(service, mailer);
    }

    @Test
    @DisplayName("self-hosted (credits unlimited): 503 even for an admin")
    void ceRefused() {
        var ce = new AdminPartnerApplicationController(service, mailer, codeRepository, auditLogger, true);

        assertThat(ce.list("ADMIN", "pending").getStatusCode().value()).isEqualTo(503);
        assertThat(ce.approve("ADMIN", 1L, 5L, null).getStatusCode().value()).isEqualTo(503);
        verifyNoInteractions(service, mailer);
    }

    @Test
    @DisplayName("list: pending by default, all on request, each row with the applicant e-mail")
    void list() {
        PartnerApplication a = application(PartnerApplication.Status.PENDING);
        when(service.applications(true)).thenReturn(List.of(a));
        when(service.applicantEmails(List.of(a))).thenReturn(Map.of(7L, "p@acme.io"));

        var resp = controller.list("ADMIN", "pending");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) resp.getBody().get("applications");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("email", "p@acme.io").containsEntry("status", "pending")
                .containsEntry("user_id", 7L);

        controller.list("ADMIN", "all");
        verify(service).applications(false);
    }

    @Test
    @DisplayName("approve: passes the overrides in bps, mails the applicant their code and rate")
    void approveMails() {
        PartnerApplication approved = application(PartnerApplication.Status.APPROVED);
        when(service.approve(5L, 1L, "ACME", 4500, false)).thenReturn(new Outcome(approved, null));
        RewardCode code = new RewardCode();
        code.setCode("ACME");
        code.setPayoutBps(4500);
        code.setPayoutMonths(12);
        when(codeRepository.findById(99L)).thenReturn(Optional.of(code));
        when(service.effectiveCommissionPercent(code)).thenReturn(45.0);
        when(mailer.sendDecision(approved, "ACME", 45.0, 12)).thenReturn(true);

        var resp = controller.approve("ADMIN", 1L, 5L,
                new AdminPartnerApplicationController.ApproveBody("ACME", 45.0, null));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("code", "ACME").containsEntry("mailed", true);
        verify(mailer).sendDecision(approved, "ACME", 45.0, 12);
        // Money-granting decision: audited, with the code it created.
        verify(auditLogger).event(com.apimarketplace.auth.audit.AuditEventTypes.PARTNER_APPLICATION_APPROVED);
        verify(auditLogger.event(com.apimarketplace.auth.audit.AuditEventTypes.PARTNER_APPLICATION_APPROVED)
                .user(1L).detail("application_id", 5L)).detail("code", "ACME");
    }

    @Test
    @DisplayName("approve refused (already has a code, not pending, missing): the status maps, nothing is mailed")
    void approveRefusals() {
        when(service.approve(eq(5L), any(), any(), any(), anyBoolean())).thenReturn(new Outcome(null, "partner_already_has_code"));
        assertThat(controller.approve("ADMIN", 1L, 5L, null).getStatusCode().value()).isEqualTo(409);

        when(service.approve(eq(6L), any(), any(), any(), anyBoolean())).thenReturn(new Outcome(null, "application_not_found"));
        assertThat(controller.approve("ADMIN", 1L, 6L, null).getStatusCode().value()).isEqualTo(404);

        when(service.approve(eq(8L), any(), any(), any(), anyBoolean())).thenReturn(new Outcome(null, "invalid_code_format"));
        assertThat(controller.approve("ADMIN", 1L, 8L, null).getStatusCode().value()).isEqualTo(400);

        verifyNoInteractions(mailer);
    }

    @Test
    @DisplayName("approve losing a unique-index race maps to 409 with the index's token; an unknown violation rethrows")
    void approveRace() {
        when(service.approve(eq(5L), any(), any(), any(), anyBoolean()))
                .thenThrow(new DataIntegrityViolationException("x", new RuntimeException("uq_reward_code_code")));
        var resp = controller.approve("ADMIN", 1L, 5L, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody()).containsEntry("error", "code_taken");

        when(service.approve(eq(6L), any(), any(), any(), anyBoolean()))
                .thenThrow(new DataIntegrityViolationException("x", new RuntimeException("something_else")));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.approve("ADMIN", 1L, 6L, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        when(service.approve(eq(7L), any(), any(), any(), anyBoolean()))
                .thenThrow(new DataIntegrityViolationException("x", new RuntimeException("uq_reward_code_owner_program")));
        when(service.isPending(7L)).thenReturn(true);
        var owner = controller.approve("ADMIN", 1L, 7L, null);
        assertThat(owner.getStatusCode().value()).isEqualTo(409);
        assertThat(owner.getBody()).containsEntry("error", "partner_already_has_code");
        verifyNoInteractions(mailer);
    }

    @Test
    @DisplayName("regression: approve vs approve, the loser's owner-index refusal reads not_pending (the other admin won)")
    void approveVersusApproveIsNotPending() {
        // The loser's code insert waits on uq_reward_code_owner_program until the winner commits,
        // then fails. Before: 409 partner_already_has_code, a reason that is not what happened.
        when(service.approve(eq(5L), any(), any(), any(), anyBoolean()))
                .thenThrow(new DataIntegrityViolationException("x", new RuntimeException("uq_reward_code_owner_program")));
        when(service.isPending(5L)).thenReturn(false);

        var resp = controller.approve("ADMIN", 1L, 5L, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody()).containsEntry("error", "not_pending");
        verifyNoInteractions(mailer);
    }

    @Test
    @DisplayName("a refusal returned (not thrown) after another admin won also reads not_pending")
    void returnedRefusalAfterLostRaceIsNotPending() {
        // The winner committed between this call's own checks: createPartnerCode then RETURNS
        // partner_already_has_code (owner check) or code_taken (both admins typed the same code).
        when(service.isPending(5L)).thenReturn(false);
        when(service.approve(eq(5L), any(), any(), any(), anyBoolean())).thenReturn(new Outcome(null, "partner_already_has_code"));
        assertThat(controller.approve("ADMIN", 1L, 5L, null).getBody()).containsEntry("error", "not_pending");

        when(service.approve(eq(5L), any(), any(), any(), anyBoolean())).thenReturn(new Outcome(null, "code_taken"));
        assertThat(controller.approve("ADMIN", 1L, 5L, null).getBody()).containsEntry("error", "not_pending");

        // Still pending: the same tokens are the real reason and are reported as such.
        when(service.isPending(6L)).thenReturn(true);
        when(service.approve(eq(6L), any(), any(), any(), anyBoolean())).thenReturn(new Outcome(null, "code_taken"));
        assertThat(controller.approve("ADMIN", 1L, 6L, null).getBody()).containsEntry("error", "code_taken");
        verifyNoInteractions(mailer);
    }

    @Test
    @DisplayName("approve onto an existing but inactive code is a 409, nothing mailed")
    void existingCodeInactiveIsConflict() {
        when(service.approve(eq(5L), any(), any(), any(), anyBoolean())).thenReturn(new Outcome(null, "existing_code_inactive"));

        var resp = controller.approve("ADMIN", 1L, 5L, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody()).containsEntry("error", "existing_code_inactive");
        verifyNoInteractions(mailer);
    }

    @Test
    @DisplayName("regression: a second admin deciding at the same time gets 409 not_pending, and nobody is mailed twice")
    void concurrentDecisionIsRefused() {
        var lost = new org.springframework.orm.ObjectOptimisticLockingFailureException(PartnerApplication.class, 5L);
        when(service.approve(eq(5L), any(), any(), any(), anyBoolean())).thenThrow(lost);
        when(service.reject(eq(5L), any(), any())).thenThrow(lost);

        var approve = controller.approve("ADMIN", 1L, 5L, null);
        var reject = controller.reject("ADMIN", 1L, 5L, null);

        assertThat(approve.getStatusCode().value()).isEqualTo(409);
        assertThat(approve.getBody()).containsEntry("error", "not_pending");
        assertThat(reject.getStatusCode().value()).isEqualTo(409);
        assertThat(reject.getBody()).containsEntry("error", "not_pending");
        verifyNoInteractions(mailer);
    }

    @Test
    @DisplayName("regression: a commission that is not a number between 0 and 100 is refused, never read as the default")
    void invalidCommissionRefused() {
        for (double bad : new double[] {Double.NaN, -1, 100.5}) {
            var resp = controller.approve("ADMIN", 1L, 5L, new AdminPartnerApplicationController.ApproveBody(null, bad, null));
            assertThat(resp.getStatusCode().value()).as(String.valueOf(bad)).isEqualTo(400);
            assertThat(resp.getBody()).containsEntry("error", "invalid_values");
        }
        verifyNoInteractions(service, mailer);
    }

    @Test
    @DisplayName("HTTP: the approve body binds commission_percent (snake_case) to bps, and the reject note is read")
    void jsonBodiesBind() throws Exception {
        PartnerApplication approved = application(PartnerApplication.Status.APPROVED);
        when(service.approve(5L, 1L, "ACME", 1250, true)).thenReturn(new Outcome(approved, null));
        when(codeRepository.findById(99L)).thenReturn(Optional.empty());
        when(service.reject(6L, 1L, "Not yet")).thenReturn(new Outcome(application(PartnerApplication.Status.REJECTED), null));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/credits/partners/applications/5/approve")
                        .header("X-User-Roles", "ADMIN").header("X-User-ID", "1")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ACME\",\"commission_percent\":12.5,\"founder\":true}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/admin/credits/partners/applications/6/reject")
                        .header("X-User-Roles", "ADMIN").header("X-User-ID", "1")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"Not yet\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

        verify(service).approve(5L, 1L, "ACME", 1250, true);
        verify(service).reject(6L, 1L, "Not yet");
    }

    @Test
    @DisplayName("V556 regression: the founder window closing mid-approval answers 409 founder_closed (not a 500), audited as a founder attempt")
    void founderWindowClosingMidApprovalIsA409() {
        when(service.approve(eq(5L), any(), any(), any(), anyBoolean()))
                .thenThrow(new com.apimarketplace.auth.service.FounderWindowClosedException());

        var resp = controller.approve("ADMIN", 1L, 5L, new AdminPartnerApplicationController.ApproveBody(null, null, true));

        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody()).containsEntry("error", "founder_closed");
        verify(auditLogger.event(com.apimarketplace.auth.audit.AuditEventTypes.PARTNER_APPLICATION_APPROVED)
                .user(1L).detail("application_id", 5L)).detail("founder", true);
        verifyNoInteractions(mailer);
    }

    @Test
    @DisplayName("V556: no founder field means no founder grant; a closed founder window answers 409 and mails nothing")
    void founderFlagAndClosedWindow() {
        when(service.approve(eq(5L), any(), any(), any(), anyBoolean())).thenReturn(new Outcome(null, "founder_closed"));

        var resp = controller.approve("ADMIN", 1L, 5L, new AdminPartnerApplicationController.ApproveBody(null, null, true));
        assertThat(resp.getStatusCode().value()).isEqualTo(409);
        assertThat(resp.getBody()).containsEntry("error", "founder_closed");
        verify(service).approve(5L, 1L, null, null, true);

        controller.approve("ADMIN", 1L, 5L, new AdminPartnerApplicationController.ApproveBody(null, null, null));
        verify(service).approve(5L, 1L, null, null, false);
        verifyNoInteractions(mailer);
    }

    @Test
    @DisplayName("reject: records the note through the service and mails the applicant")
    void rejectMails() {
        PartnerApplication rejected = application(PartnerApplication.Status.REJECTED);
        when(service.reject(5L, 1L, "Not yet")).thenReturn(new Outcome(rejected, null));
        when(mailer.sendDecision(rejected, null, 0, 0)).thenReturn(true);

        var resp = controller.reject("ADMIN", 1L, 5L, new AdminPartnerApplicationController.RejectBody("Not yet"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("mailed", true);
        verify(mailer).sendDecision(rejected, null, 0, 0);
        verify(auditLogger).event(com.apimarketplace.auth.audit.AuditEventTypes.PARTNER_APPLICATION_REJECTED);
    }

    @Test
    @DisplayName("reject refused (not pending): 409, nothing mailed")
    void rejectRefused() {
        when(service.reject(5L, 1L, null)).thenReturn(new Outcome(null, "not_pending"));

        assertThat(controller.reject("ADMIN", 1L, 5L, null).getStatusCode().value()).isEqualTo(409);
        verifyNoInteractions(mailer);
    }
}
