package com.apimarketplace.auth.web;

import com.apimarketplace.auth.audit.AuditEventTypes;
import com.apimarketplace.auth.audit.AuditLogger;
import com.apimarketplace.auth.domain.PartnerApplication;
import com.apimarketplace.auth.repository.RewardCodeRepository;
import com.apimarketplace.auth.service.PartnerProgramMailer;
import com.apimarketplace.auth.service.PartnerProgramService;
import com.apimarketplace.auth.service.PartnerProgramService.Outcome;
import com.apimarketplace.common.web.AdminRoleGuard;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin side of the partner applications (V553), next to {@link AdminPartnerController} under
 * the same routed, authenticated {@code /api/admin/credits/} prefix. Cloud only.
 *
 * <ul>
 *   <li>{@code GET  /applications?status=pending|all}: the queue (default pending).</li>
 *   <li>{@code POST /applications/{id}/approve}: create the applicant's PARTNER code (program
 *       defaults, optional {@code code} and {@code commission_percent} overrides, and
 *       {@code founder} to grant the founder tier while that window is open) and mail them.</li>
 *   <li>{@code POST /applications/{id}/reject}: optional {@code note} shown to the applicant.</li>
 * </ul>
 *
 * <p>Two admins deciding the same application at once: the second one gets 409
 * {@code not_pending} (optimistic lock on the application) and nothing it did is kept.
 */
@RestController
@RequestMapping("/api/admin/credits/partners/applications")
public class AdminPartnerApplicationController {

    private static final Logger log = LoggerFactory.getLogger(AdminPartnerApplicationController.class);

    private final PartnerProgramService service;
    private final PartnerProgramMailer mailer;
    private final RewardCodeRepository codeRepository;
    private final AuditLogger auditLogger;
    private final boolean unlimited;

    public AdminPartnerApplicationController(PartnerProgramService service,
                                             PartnerProgramMailer mailer,
                                             RewardCodeRepository codeRepository,
                                             AuditLogger auditLogger,
                                             @Value("${credit.unlimited:false}") boolean unlimited) {
        this.service = service;
        this.mailer = mailer;
        this.codeRepository = codeRepository;
        this.auditLogger = auditLogger;
        this.unlimited = unlimited;
    }

    public record ApproveBody(String code, @JsonProperty("commission_percent") Double commissionPercent,
                              Boolean founder) {}

    public record RejectBody(String note) {}

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestParam(value = "status", defaultValue = "pending") String status) {
        ResponseEntity<Map<String, Object>> gate = gate(roles);
        if (gate != null) return gate;
        List<PartnerApplication> applications = service.applications(!"all".equalsIgnoreCase(status));
        Map<Long, String> emails = service.applicantEmails(applications);
        List<Map<String, Object>> rows = applications.stream().map(a -> {
            Map<String, Object> m = PartnerProgramController.applicationJson(a);
            m.put("user_id", a.getUserId());
            m.put("email", emails.get(a.getUserId()));
            m.put("reward_code_id", a.getRewardCodeId());
            return m;
        }).toList();
        return ResponseEntity.ok(Map.of("applications", rows));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestHeader(value = "X-User-ID", required = false) Long adminUserId,
            @PathVariable("id") Long id,
            @RequestBody(required = false) ApproveBody body) {
        ResponseEntity<Map<String, Object>> gate = gate(roles);
        if (gate != null) return gate;
        Integer bps = body == null || body.commissionPercent() == null ? null
                : (int) Math.round(body.commissionPercent() * 100);
        if (body != null && body.commissionPercent() != null
                && (body.commissionPercent().isNaN() || body.commissionPercent() < 0 || body.commissionPercent() > 100)) {
            return error(HttpStatus.BAD_REQUEST, "invalid_values");
        }
        boolean founder = body != null && Boolean.TRUE.equals(body.founder());
        Outcome outcome;
        try {
            outcome = service.approve(id, adminUserId, body == null ? null : body.code(), bps, founder);
        } catch (DataIntegrityViolationException race) {
            String detail = String.valueOf(race.getMostSpecificCause().getMessage());
            // Two admins approving at once: the loser's code insert waits on the owner index, then
            // fails once the winner commits. That is the same lost race as the optimistic lock, and
            // answers the same way, rather than claiming the applicant already had a code.
            if (detail.contains("uq_reward_code_owner_program")) {
                outcome = new Outcome(null, service.isPending(id) ? "partner_already_has_code" : "not_pending");
            } else if (detail.contains("uq_reward_code_code")) {
                outcome = new Outcome(null, "code_taken");
            } else {
                throw race;
            }
        } catch (OptimisticLockingFailureException concurrent) {
            // Another admin decided this application first; their decision stands.
            outcome = new Outcome(null, "not_pending");
        } catch (com.apimarketplace.auth.service.FounderWindowClosedException closed) {
            // The window closed between the up-front check and the grant: rolled back, same answer.
            outcome = new Outcome(null, "founder_closed");
        }
        // The same lost race can also come back as a plain refusal: the winner committed between
        // this call's own checks (the owner already has a code, or both admins typed the same code).
        // If the application is no longer pending, that is what happened, whatever the token says.
        if (!outcome.success() && ("partner_already_has_code".equals(outcome.error()) || "code_taken".equals(outcome.error()))
                && !service.isPending(id)) {
            outcome = new Outcome(null, "not_pending");
        }
        PartnerApplication a = outcome.success() ? outcome.application() : null;
        var code = a == null ? null : codeRepository.findById(a.getRewardCodeId()).orElse(null);
        audit(AuditEventTypes.PARTNER_APPLICATION_APPROVED, adminUserId, id, outcome,
                code == null ? null : code.getCode(), founder);
        if (!outcome.success()) return error(statusFor(outcome.error()), outcome.error());
        // The rate the partner will actually earn (a founder starts on the Platinum rate), not
        // only the code's own rate.
        boolean mailed = mailer.sendDecision(a, code == null ? null : code.getCode(),
                service.effectiveCommissionPercent(code),
                code == null || code.getPayoutMonths() == null ? 0 : code.getPayoutMonths());
        log.info("Admin {} approved partner application {} (code {}, mailed {})", adminUserId, id,
                code == null ? null : code.getCode(), mailed);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("application", PartnerProgramController.applicationJson(a));
        response.put("code", code == null ? null : code.getCode());
        response.put("mailed", mailed);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestHeader(value = "X-User-ID", required = false) Long adminUserId,
            @PathVariable("id") Long id,
            @RequestBody(required = false) RejectBody body) {
        ResponseEntity<Map<String, Object>> gate = gate(roles);
        if (gate != null) return gate;
        Outcome outcome;
        try {
            outcome = service.reject(id, adminUserId, body == null ? null : body.note());
        } catch (OptimisticLockingFailureException concurrent) {
            outcome = new Outcome(null, "not_pending");
        }
        audit(AuditEventTypes.PARTNER_APPLICATION_REJECTED, adminUserId, id, outcome, null, false);
        if (!outcome.success()) return error(statusFor(outcome.error()), outcome.error());
        boolean mailed = mailer.sendDecision(outcome.application(), null, 0, 0);
        log.info("Admin {} rejected partner application {} (mailed {})", adminUserId, id, mailed);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("application", PartnerProgramController.applicationJson(outcome.application()));
        response.put("mailed", mailed);
        return ResponseEntity.ok(response);
    }

    private void audit(String type, Long adminUserId, Long applicationId, Outcome outcome, String code, boolean founder) {
        var event = auditLogger.event(type).user(adminUserId).detail("application_id", applicationId);
        if (code != null) event.detail("code", code);
        // A founder approval grants a lifetime rate: the trail says so, success or refusal.
        if (founder) event.detail("founder", true);
        if (outcome.success()) {
            event.success().write();
        } else {
            event.failure(outcome.error()).write();
        }
    }

    static HttpStatus statusFor(String error) {
        return switch (error) {
            case "application_not_found" -> HttpStatus.NOT_FOUND;
            case "not_pending", "code_taken", "partner_already_has_code", "existing_code_inactive",
                 "founder_closed" -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_REQUEST;
        };
    }

    private ResponseEntity<Map<String, Object>> gate(String roles) {
        ResponseEntity<Map<String, Object>> denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        if (unlimited) return error(HttpStatus.SERVICE_UNAVAILABLE, "not_available_in_ce");
        return null;
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("success", false, "error", code));
    }
}
