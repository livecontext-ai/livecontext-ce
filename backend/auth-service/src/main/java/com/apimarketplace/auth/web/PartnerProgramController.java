package com.apimarketplace.auth.web;

import com.apimarketplace.auth.audit.AuditEventTypes;
import com.apimarketplace.auth.audit.AuditLogger;
import com.apimarketplace.auth.domain.PartnerApplication;
import com.apimarketplace.auth.domain.RewardCode;
import com.apimarketplace.auth.service.PartnerProgramService;
import com.apimarketplace.auth.service.PartnerProgramService.ApplicationForm;
import com.apimarketplace.auth.service.PartnerProgramService.Dashboard;
import com.apimarketplace.auth.service.PartnerProgramService.Outcome;
import com.apimarketplace.auth.service.PartnerProgramService.Terms;
import com.apimarketplace.auth.service.PartnerTermsService;
import com.apimarketplace.auth.service.PartnerTierService;
import com.apimarketplace.auth.lifecycle.LifecycleInputs;
import com.apimarketplace.auth.util.ClientIpExtractor;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Partner-facing API of the partner program. Cloud only: every endpoint answers 503 when
 * credits are unlimited (CE), where there is no billing to share.
 *
 * <ul>
 *   <li>{@code GET  /api/public/partner-program/terms}: the terms a new partner gets, for the
 *       public /partners page. Anonymous (on the gateway public allowlist).</li>
 *   <li>{@code GET  /api/public/partner-program/codes/{code}}: what a partner code offers a new
 *       account (its credits), for the page a partner's link opens. Anonymous; 404 for anything
 *       but a partner code that can be redeemed now, and never who owns the code.</li>
 *   <li>{@code GET  /api/billing/partner/me}: the signed-in user's partner dashboard.</li>
 *   <li>{@code POST /api/billing/partner/applications}: apply to the program, accepting the
 *       Partner Program Terms (V557) in the same request.</li>
 *   <li>{@code POST /api/billing/partner/terms/accept}: accept the current Partner Program Terms
 *       from the dashboard (a partner who never accepted them, or accepted an older version).</li>
 * </ul>
 */
@RestController
public class PartnerProgramController {

    private final PartnerProgramService service;
    private final PartnerTermsService termsService;
    private final AuditLogger auditLogger;
    private final boolean unlimited;

    public PartnerProgramController(PartnerProgramService service,
                                    PartnerTermsService termsService,
                                    AuditLogger auditLogger,
                                    @Value("${credit.unlimited:false}") boolean unlimited) {
        this.service = service;
        this.termsService = termsService;
        this.auditLogger = auditLogger;
        this.unlimited = unlimited;
    }

    public record ApplicationBody(@JsonProperty("company_name") String companyName,
                                  String website, String audience, String message,
                                  @JsonProperty("terms_version") String termsVersion) {}

    public record TermsAcceptBody(@JsonProperty("terms_version") String termsVersion) {}

    @GetMapping("/api/public/partner-program/terms")
    public ResponseEntity<Map<String, Object>> terms() {
        if (unlimited) return error(HttpStatus.SERVICE_UNAVAILABLE, "not_available_in_ce");
        Map<String, Object> terms = termsJson(service.terms());
        // The Partner Program Terms version the application form sends back when it applies.
        terms.put("terms_version", termsService.currentVersion());
        return ResponseEntity.ok(terms);
    }

    @GetMapping("/api/public/partner-program/codes/{code}")
    public ResponseEntity<Map<String, Object>> codeOffer(@PathVariable("code") String code) {
        if (unlimited) return error(HttpStatus.SERVICE_UNAVAILABLE, "not_available_in_ce");
        return service.codeOffer(code)
                .map(o -> ResponseEntity.ok(Map.<String, Object>of("code", o.code(), "credits", o.credits())))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "unknown_code"));
    }

    @GetMapping("/api/billing/partner/me")
    public ResponseEntity<Map<String, Object>> me(@RequestHeader(value = "X-User-ID", required = false) String userId) {
        if (unlimited) return error(HttpStatus.SERVICE_UNAVAILABLE, "not_available_in_ce");
        Long id = parseUserId(userId);
        if (id == null) return error(HttpStatus.UNAUTHORIZED, "unauthorized");
        return ResponseEntity.ok(dashboardJson(service.dashboard(id)));
    }

    @PostMapping("/api/billing/partner/applications")
    public ResponseEntity<Map<String, Object>> apply(
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestBody(required = false) ApplicationBody body,
            HttpServletRequest request) {
        if (unlimited) return error(HttpStatus.SERVICE_UNAVAILABLE, "not_available_in_ce");
        Long id = parseUserId(userId);
        if (id == null) return error(HttpStatus.UNAUTHORIZED, "unauthorized");
        Outcome outcome;
        try {
            outcome = service.apply(id, body == null ? null
                    : new ApplicationForm(body.companyName(), body.website(), body.audience(), body.message(),
                            body.termsVersion()), evidence(request));
        } catch (DataIntegrityViolationException race) {
            // Lost a double submit on uq_partner_application_pending_user. Any other integrity
            // error is a real fault, reported as such rather than dressed up as a duplicate.
            if (!String.valueOf(race.getMostSpecificCause().getMessage())
                    .contains("uq_partner_application_pending_user")) {
                throw race;
            }
            return error(HttpStatus.CONFLICT, "already_pending");
        }
        if (!outcome.success()) {
            HttpStatus status = switch (outcome.error()) {
                case "already_partner", "already_pending", "terms_outdated" -> HttpStatus.CONFLICT;
                case "unauthorized" -> HttpStatus.UNAUTHORIZED;
                default -> HttpStatus.BAD_REQUEST;
            };
            return error(status, outcome.error());
        }
        auditTermsAccepted(id, "APPLICATION", request);
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("application", applicationJson(outcome.application()));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/api/billing/partner/terms/accept")
    public ResponseEntity<Map<String, Object>> acceptTerms(
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestBody(required = false) TermsAcceptBody body,
            HttpServletRequest request) {
        if (unlimited) return error(HttpStatus.SERVICE_UNAVAILABLE, "not_available_in_ce");
        Long id = parseUserId(userId);
        if (id == null) return error(HttpStatus.UNAUTHORIZED, "unauthorized");
        String version = body == null ? null : body.termsVersion();
        String refused = service.acceptTerms(id, version, evidence(request));
        if (refused != null) {
            HttpStatus status = switch (refused) {
                case "unauthorized" -> HttpStatus.UNAUTHORIZED;
                case "not_partner" -> HttpStatus.FORBIDDEN;
                case "terms_outdated" -> HttpStatus.CONFLICT;
                default -> HttpStatus.BAD_REQUEST;
            };
            return error(status, refused);
        }
        auditTermsAccepted(id, "DASHBOARD", request);
        return ResponseEntity.ok(Map.of("success", true, "agreement",
                agreementJson(termsService.status(id), true)));
    }

    /**
     * The client address and browser of the click, kept with the acceptance as its evidence. The
     * address is Cloudflare's {@code CF-Connecting-IP}, which Cloudflare sets and overwrites at
     * the edge (the origin only accepts Cloudflare traffic, see BundleDownloadThrottleFilter);
     * the left-most {@code X-Forwarded-For} entry is whatever the client sent, so it is only the
     * fallback for a request that did not come through Cloudflare.
     */
    static PartnerTermsService.Evidence evidence(HttpServletRequest request) {
        if (request == null) return PartnerTermsService.Evidence.none();
        String cf = LifecycleInputs.ip(request.getHeader("CF-Connecting-IP"));
        return new PartnerTermsService.Evidence(cf != null ? cf : ClientIpExtractor.extract(request),
                request.getHeader("User-Agent"));
    }

    /** Audited with the version and text actually recorded (the current ones), not the request's. */
    private void auditTermsAccepted(Long userId, String source, HttpServletRequest request) {
        auditLogger.eventFromRequest(AuditEventTypes.PARTNER_TERMS_ACCEPTED, request)
                .user(userId)
                .detail("terms_version", termsService.currentVersion())
                .detail("terms_fingerprint", termsService.currentFingerprint())
                .detail("source", source)
                .success()
                .write();
    }

    /**
     * Where the user stands with the Partner Program Terms. {@code required}: a partner (someone
     * with a code) who has not accepted the current version, whom the dashboard asks to.
     * {@code payouts_blocked}: a partner who never accepted any version, who is paid nothing
     * until they do.
     */
    static Map<String, Object> agreementJson(PartnerTermsService.Status s, boolean partner) {
        Map<String, Object> m = new HashMap<>();
        m.put("current_version", s.currentVersion());
        m.put("accepted_version", s.latest() != null ? s.latest().version() : null);
        m.put("accepted_at", s.latest() != null && s.latest().acceptedAt() != null
                ? s.latest().acceptedAt().toString() : null);
        m.put("accepted_current", s.acceptedCurrent());
        m.put("required", s.required(partner));
        m.put("payouts_blocked", s.payoutsBlocked(partner));
        return m;
    }

    static Map<String, Object> termsJson(Terms t) {
        Map<String, Object> m = new HashMap<>();
        m.put("commission_percent", t.commissionPercent());
        m.put("commission_months", t.commissionMonths());
        m.put("hold_days", t.holdDays());
        m.put("audience_credits", t.audienceCredits());
        m.put("tiers", t.tiers() == null ? List.of() : t.tiers().stream().map(PartnerProgramController::tierJson).toList());
        m.put("tier_currency", t.tierCurrency());
        m.put("tier_settle_days", t.tierSettleDays());
        m.put("founder_until", t.founderUntil() != null ? t.founderUntil().toString() : null);
        m.put("founder_open", t.founderOpen());
        return m;
    }

    static Map<String, Object> tierJson(PartnerTierService.TierTerm t) {
        Map<String, Object> m = new HashMap<>();
        m.put("tier", t.tier().name().toLowerCase(java.util.Locale.ROOT));
        m.put("commission_percent", t.rateBps() / 100.0);
        m.put("threshold_minor", t.thresholdMinor());
        return m;
    }

    /** Where a partner stands: their tier, the settled revenue behind it, and the next step. */
    static Map<String, Object> standingJson(PartnerTierService.Standing s) {
        if (s == null) return null;
        Map<String, Object> m = new HashMap<>();
        m.put("tier", s.tier().name().toLowerCase(java.util.Locale.ROOT));
        m.put("founder", s.founder());
        m.put("revenue_minor", s.revenueMinor());
        m.put("currency", s.currency());
        m.put("next_tier", s.nextTier() == null ? null : s.nextTier().name().toLowerCase(java.util.Locale.ROOT));
        m.put("next_threshold_minor", s.nextThresholdMinor());
        m.put("commission_percent", s.rateBps() / 100.0);
        return m;
    }

    static Map<String, Object> applicationJson(PartnerApplication a) {
        if (a == null) return null;
        Map<String, Object> m = new HashMap<>();
        m.put("id", a.getId());
        m.put("status", a.getStatus().name().toLowerCase(java.util.Locale.ROOT));
        m.put("company_name", a.getCompanyName());
        m.put("website", a.getWebsite());
        m.put("audience", a.getAudience());
        m.put("message", a.getMessage());
        m.put("decision_note", a.getDecisionNote());
        m.put("created_at", a.getCreatedAt() != null ? a.getCreatedAt().toString() : null);
        m.put("reviewed_at", a.getReviewedAt() != null ? a.getReviewedAt().toString() : null);
        return m;
    }

    private static Map<String, Object> dashboardJson(Dashboard d) {
        Map<String, Object> m = new HashMap<>();
        m.put("state", d.state());
        m.put("terms", termsJson(d.terms()));
        m.put("application", applicationJson(d.application()));
        m.put("agreement", d.agreement() == null ? null : agreementJson(d.agreement(), d.code() != null));
        RewardCode c = d.code();
        if (c == null) {
            m.put("partner", null);
            return m;
        }
        Map<String, Object> p = new HashMap<>();
        p.put("code", c.getCode());
        // The rate the next commission earns: the higher of the code's own rate and the tier's.
        p.put("commission_percent", d.commissionPercent() != null ? d.commissionPercent()
                : c.getPayoutBps() == null ? null : c.getPayoutBps() / 100.0);
        p.put("standing", standingJson(d.standing()));
        p.put("commission_months", c.getPayoutMonths());
        p.put("hold_days", c.getHoldDays());
        p.put("audience_credits", c.getBenefitAmount());
        p.put("valid_until", c.getValidUntil() != null ? c.getValidUntil().toString() : null);
        // Shown on the partner page: terms clause 6.1 binds a specific condition once it is shown.
        p.put("max_uses", c.getCapLimit());
        p.put("redemptions", d.redemptions());
        p.put("paying_customers", d.payingCustomers());
        p.put("commissions", Map.of(
                "on_hold", d.commissions().onHold(),
                "payable", d.commissions().payable(),
                "paid", d.commissions().paid(),
                "voided", d.commissions().voided()));
        List<Map<String, Object>> lines = d.lines().stream().map(l -> {
            Map<String, Object> line = new HashMap<>();
            line.put("invoice_paid_at", l.invoicePaidAt() != null ? l.invoicePaidAt().toString() : null);
            line.put("currency", l.currency());
            line.put("base_amount_minor", l.baseAmountMinor());
            line.put("commission_minor", l.commissionMinor());
            line.put("status", l.status());
            line.put("due_at", l.dueAt() != null ? l.dueAt().toString() : null);
            line.put("paid_at", l.paidAt() != null ? l.paidAt().toString() : null);
            return line;
        }).toList();
        p.put("lines", lines);
        // The last 12 calendar months, oldest first: what the partner earned each month.
        p.put("months", d.months().stream().map(mo -> Map.of("month", mo.month(), "commissions", mo.commissions())).toList());
        m.put("partner", p);
        return m;
    }

    private static Long parseUserId(String header) {
        if (header == null || header.isBlank()) return null;
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("success", false, "error", code));
    }
}
