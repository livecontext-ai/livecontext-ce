package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.PartnerCommission;
import com.apimarketplace.auth.domain.RewardCode;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.PartnerProgramAdminService;
import com.apimarketplace.auth.service.PartnerProgramAdminService.CodeReport;
import com.apimarketplace.auth.service.PartnerProgramAdminService.CreatorCodeRequest;
import com.apimarketplace.auth.service.PartnerProgramAdminService.PartnerCodeRequest;
import com.apimarketplace.auth.service.PartnerProgramAdminService.Result;
import com.apimarketplace.common.web.AdminRoleGuard;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Admin API of the partner / influencer program (V549). Nested under
 * {@code /api/admin/credits/} on purpose: that prefix is already routed by the gateway
 * ({@code auth-admin-credits}) and kept behind its authentication filter, exactly like
 * the comp-plan grant next door. Cloud only: refused when credits are unlimited (CE).
 *
 * <ul>
 *   <li>{@code GET  /partners}: every creator and partner code with its totals, plus the
 *       configured defaults the creation forms start from.</li>
 *   <li>{@code POST /partners/creator-codes}: a single-use (by default) creator code.</li>
 *   <li>{@code POST /partners/partner-codes}: a partner's audience code and revenue share.</li>
 *   <li>{@code POST /partners/codes/{id}/active}: enable or disable a code.</li>
 *   <li>{@code POST /partners/codes/{id}/mark-paid}: settle the code's payable commissions.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/credits/partners")
public class AdminPartnerController {

    private static final Logger log = LoggerFactory.getLogger(AdminPartnerController.class);

    private final PartnerProgramAdminService service;
    private final UserRepository userRepository;
    private final com.apimarketplace.auth.audit.AuditLogger auditLogger;
    private final boolean unlimited;

    public AdminPartnerController(PartnerProgramAdminService service,
                                  UserRepository userRepository,
                                  com.apimarketplace.auth.audit.AuditLogger auditLogger,
                                  @Value("${credit.unlimited:false}") boolean unlimited) {
        this.service = service;
        this.userRepository = userRepository;
        this.auditLogger = auditLogger;
        this.unlimited = unlimited;
    }

    /** Money-moving admin actions leave an audit event, like the comp-plan grant next door. */
    private void audit(String type, Long adminUserId, Result result, java.util.Map<String, Object> details) {
        var event = auditLogger.event(type).user(adminUserId);
        details.forEach(event::detail);
        if (result == null || result.success()) {
            if (result != null) event.detail("code", result.code().getCode());
            event.success().write();
        } else {
            event.failure(result.error()).write();
        }
    }

    public record CreatorCodeBody(String code, String label,
                                  @JsonProperty("plan_code") String planCode,
                                  @JsonProperty("plan_days") Integer planDays,
                                  Integer credits,
                                  @JsonProperty("max_uses") Integer maxUses,
                                  @JsonProperty("valid_days") Integer validDays) {}

    public record PartnerCodeBody(@JsonProperty("partner_email") String partnerEmail,
                                  @JsonProperty("partner_user_id") Long partnerUserId,
                                  String code, String label,
                                  @JsonProperty("audience_credits") Integer audienceCredits,
                                  @JsonProperty("commission_percent") Double commissionPercent,
                                  @JsonProperty("commission_months") Integer commissionMonths,
                                  @JsonProperty("hold_days") Integer holdDays,
                                  @JsonProperty("valid_days") Integer validDays,
                                  @JsonProperty("max_uses") Integer maxUses) {}

    public record ActiveBody(Boolean active) {}

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        ResponseEntity<Map<String, Object>> gate = gate(roles);
        if (gate != null) return gate;
        List<Map<String, Object>> codes = service.report().stream().map(AdminPartnerController::toJson).toList();
        Map<String, Object> body = new HashMap<>();
        body.put("codes", codes);
        body.put("defaults", service.defaults());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/creator-codes")
    public ResponseEntity<Map<String, Object>> createCreatorCode(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestHeader(value = "X-User-ID", required = false) Long adminUserId,
            @RequestBody(required = false) CreatorCodeBody body) {
        ResponseEntity<Map<String, Object>> gate = gate(roles);
        if (gate != null) return gate;
        CreatorCodeBody b = body == null ? new CreatorCodeBody(null, null, null, null, null, null, null) : body;
        Result result;
        try {
            result = service.createCreatorCode(new CreatorCodeRequest(
                    b.code(), b.label(), b.planCode(), b.planDays(), b.credits(), b.maxUses(), b.validDays()));
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            result = raceResult(race);
        }
        audit(com.apimarketplace.auth.audit.AuditEventTypes.PARTNER_CODE_CREATED, adminUserId, result,
                java.util.Map.of("kind", "creator"));
        log.info("Admin {} created creator code: {}", adminUserId,
                result.success() ? result.code().getCode() : result.error());
        return respond(result);
    }

    @PostMapping("/partner-codes")
    public ResponseEntity<Map<String, Object>> createPartnerCode(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestHeader(value = "X-User-ID", required = false) Long adminUserId,
            @RequestBody(required = false) PartnerCodeBody body) {
        ResponseEntity<Map<String, Object>> gate = gate(roles);
        if (gate != null) return gate;
        if (body == null) return error(HttpStatus.BAD_REQUEST, "missing_body");
        Long partnerId = body.partnerUserId();
        if (partnerId == null) {
            if (body.partnerEmail() == null || body.partnerEmail().isBlank()) {
                return error(HttpStatus.BAD_REQUEST, "missing_partner");
            }
            String email = body.partnerEmail().trim();
            Optional<User> user = userRepository.findByEmail(email)
                    .or(() -> userRepository.findByEmail(email.toLowerCase(java.util.Locale.ROOT)));
            if (user.isEmpty()) return error(HttpStatus.NOT_FOUND, "user_not_found");
            partnerId = user.get().getId();
        }
        Integer bps = body.commissionPercent() == null ? null
                : (int) Math.round(body.commissionPercent() * 100);
        Result result;
        try {
            result = service.createPartnerCode(new PartnerCodeRequest(partnerId, body.code(), body.label(),
                    body.audienceCredits(), bps, body.commissionMonths(), body.holdDays(), body.validDays(),
                    body.maxUses()));
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            result = raceResult(race);
        }
        audit(com.apimarketplace.auth.audit.AuditEventTypes.PARTNER_CODE_CREATED, adminUserId, result,
                java.util.Map.of("kind", "partner", "partner_user_id", partnerId));
        log.info("Admin {} created partner code for user {}: {}", adminUserId, partnerId,
                result.success() ? result.code().getCode() : result.error());
        return respond(result);
    }

    @PostMapping("/codes/{id}/active")
    public ResponseEntity<Map<String, Object>> setActive(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestHeader(value = "X-User-ID", required = false) Long adminUserId,
            @PathVariable("id") Long id,
            @RequestBody(required = false) ActiveBody body) {
        ResponseEntity<Map<String, Object>> gate = gate(roles);
        if (gate != null) return gate;
        if (body == null || body.active() == null) return error(HttpStatus.BAD_REQUEST, "missing_active");
        if (!service.setActive(id, body.active())) return error(HttpStatus.NOT_FOUND, "code_not_found");
        audit(com.apimarketplace.auth.audit.AuditEventTypes.PARTNER_CODE_ACTIVE_CHANGED, adminUserId, null,
                java.util.Map.of("code_id", id, "active", body.active()));
        return ResponseEntity.ok(Map.of("success", true, "active", body.active()));
    }

    @PostMapping("/codes/{id}/mark-paid")
    public ResponseEntity<Map<String, Object>> markPaid(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestHeader(value = "X-User-ID", required = false) Long adminUserId,
            @PathVariable("id") Long id) {
        ResponseEntity<Map<String, Object>> gate = gate(roles);
        if (gate != null) return gate;
        List<PartnerCommission> settled = service.markPayablePaid(id, adminUserId);
        Map<String, Long> amounts = new HashMap<>();
        settled.forEach(c -> amounts.merge(c.getCurrency(), c.getCommissionMinor(), Long::sum));
        audit(com.apimarketplace.auth.audit.AuditEventTypes.PARTNER_COMMISSIONS_PAID, adminUserId, null,
                java.util.Map.of("code_id", id, "lines", settled.size(), "amounts", amounts.toString()));
        log.info("Admin {} settled {} partner commission lines on code {}", adminUserId, settled.size(), id);
        return ResponseEntity.ok(Map.of("success", true, "lines", settled.size(), "amounts", amounts));
    }

    /** A concurrent create lost on a unique index: which one decides the 409 token. */
    private static Result raceResult(org.springframework.dao.DataIntegrityViolationException race) {
        String detail = String.valueOf(race.getMostSpecificCause().getMessage());
        if (detail.contains("uq_reward_code_owner_program")) return new Result(null, "partner_already_has_code");
        if (detail.contains("uq_reward_code_code")) return new Result(null, "code_taken");
        throw race; // not a lost race on a known index: a real error, reported as such
    }

    private ResponseEntity<Map<String, Object>> gate(String roles) {
        ResponseEntity<Map<String, Object>> denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        if (unlimited) return error(HttpStatus.SERVICE_UNAVAILABLE, "not_available_in_ce");
        return null;
    }

    private ResponseEntity<Map<String, Object>> respond(Result result) {
        if (!result.success()) {
            HttpStatus status = switch (result.error()) {
                case "user_not_found" -> HttpStatus.NOT_FOUND;
                case "code_taken", "partner_already_has_code" -> HttpStatus.CONFLICT;
                default -> HttpStatus.BAD_REQUEST;
            };
            return error(status, result.error());
        }
        Map<String, Object> body = new HashMap<>();
        body.put("success", true);
        body.put("code", codeJson(result.code()));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("success", false, "error", code));
    }

    private static Map<String, Object> toJson(CodeReport r) {
        Map<String, Object> m = codeJson(r.code());
        m.put("owner_email", r.ownerEmail());
        m.put("redemptions", r.redemptions());
        m.put("paying_customers", r.payingCustomers());
        m.put("commissions", Map.of(
                "on_hold", r.commissions().onHold(),
                "payable", r.commissions().payable(),
                "paid", r.commissions().paid(),
                "voided", r.commissions().voided()));
        return m;
    }

    private static Map<String, Object> codeJson(RewardCode c) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", c.getId());
        m.put("code", c.getCode());
        m.put("kind", c.getProgram() == com.apimarketplace.auth.domain.RewardProgram.PARTNER ? "partner" : "creator");
        m.put("label", c.getLabel());
        m.put("owner_user_id", c.getOwnerUserId());
        m.put("credits", c.getBenefitAmount());
        m.put("plan_code", c.getBenefitPlanCode());
        m.put("plan_days", c.getBenefitPlanDays());
        m.put("max_uses", c.getCapLimit());
        m.put("commission_percent", c.getPayoutBps() == null ? null : c.getPayoutBps() / 100.0);
        m.put("commission_months", c.getPayoutMonths());
        m.put("hold_days", c.getHoldDays());
        m.put("active", c.isActive());
        m.put("valid_until", c.getValidUntil() != null ? c.getValidUntil().toString() : null);
        m.put("created_at", c.getCreatedAt() != null ? c.getCreatedAt().toString() : null);
        return m;
    }
}
