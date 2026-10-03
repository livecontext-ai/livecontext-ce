package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.PartnerOffer;
import com.apimarketplace.auth.dto.PublicProfileDto;
import com.apimarketplace.auth.service.PartnerOfferDeliveryService;
import com.apimarketplace.auth.service.PartnerOfferService;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A partner's offers (one link per client, V559). Cloud only: 503 when credits are unlimited (CE),
 * like the rest of the partner program.
 *
 * <ul>
 *   <li>{@code POST   /api/billing/partner/offers}: create one (plan, credit tier, cycle, note, apps).</li>
 *   <li>{@code GET    /api/billing/partner/offers}: the partner's live offers.</li>
 *   <li>{@code DELETE /api/billing/partner/offers/{token}}: deactivate one of their own.</li>
 *   <li>{@code GET    /api/public/partner-program/offers/{token}}: what the offer page shows,
 *       anonymously (gateway public allowlist). 404 for an unknown or deactivated offer, or one
 *       whose partner code can no longer bring a sign-up.</li>
 *   <li>{@code GET    /api/billing/partner/offers/{token}/welcome}: what a client who just paid
 *       through the offer sees: the apps it gives, where each delivery stands, and the partner.</li>
 * </ul>
 */
@RestController
public class PartnerOfferController {

    private final PartnerOfferService service;
    private final PartnerOfferDeliveryService deliveries;
    private final boolean unlimited;

    public PartnerOfferController(PartnerOfferService service, PartnerOfferDeliveryService deliveries,
                                  @Value("${credit.unlimited:false}") boolean unlimited) {
        this.service = service;
        this.deliveries = deliveries;
        this.unlimited = unlimited;
    }

    public record OfferBody(@JsonProperty("plan_code") String planCode,
                            @JsonProperty("credit_tier_index") Integer creditTierIndex,
                            @JsonProperty("billing_cycle") String billingCycle,
                            String label,
                            @JsonProperty("app_ids") List<String> appIds) {}

    @PostMapping("/api/billing/partner/offers")
    public ResponseEntity<Map<String, Object>> create(@RequestHeader(value = "X-User-ID", required = false) String userId,
                                                      @RequestBody(required = false) OfferBody body) {
        if (unlimited) return error(HttpStatus.SERVICE_UNAVAILABLE, "not_available_in_ce");
        Long id = parseUserId(userId);
        if (id == null) return error(HttpStatus.UNAUTHORIZED, "unauthorized");
        if (body == null) return error(HttpStatus.BAD_REQUEST, "missing_body");
        PartnerOfferService.Outcome outcome = service.create(id, body.planCode(), body.creditTierIndex(), body.billingCycle(), body.label(), body.appIds());
        if (!outcome.succeeded()) {
            HttpStatus status = switch (outcome.error()) {
                case "not_partner" -> HttpStatus.FORBIDDEN;
                case "code_inactive", "too_many_offers" -> HttpStatus.CONFLICT;
                case "apps_unavailable" -> HttpStatus.SERVICE_UNAVAILABLE;
                default -> HttpStatus.BAD_REQUEST;
            };
            return error(status, outcome.error());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("success", true);
        out.put("offer", offerJson(outcome.offer()));
        return ResponseEntity.status(HttpStatus.CREATED).body(out);
    }

    @GetMapping("/api/billing/partner/offers")
    public ResponseEntity<Map<String, Object>> list(@RequestHeader(value = "X-User-ID", required = false) String userId) {
        if (unlimited) return error(HttpStatus.SERVICE_UNAVAILABLE, "not_available_in_ce");
        Long id = parseUserId(userId);
        if (id == null) return error(HttpStatus.UNAUTHORIZED, "unauthorized");
        List<Map<String, Object>> rows = service.list(id).stream().map(PartnerOfferController::offerJson).toList();
        return ResponseEntity.ok(Map.of("offers", rows));
    }

    @DeleteMapping("/api/billing/partner/offers/{token}")
    public ResponseEntity<Map<String, Object>> deactivate(@RequestHeader(value = "X-User-ID", required = false) String userId,
                                                          @PathVariable("token") String token) {
        if (unlimited) return error(HttpStatus.SERVICE_UNAVAILABLE, "not_available_in_ce");
        Long id = parseUserId(userId);
        if (id == null) return error(HttpStatus.UNAUTHORIZED, "unauthorized");
        // Not found and not yours read the same: an offer token says nothing about who owns it.
        if (!service.deactivate(id, token)) return error(HttpStatus.NOT_FOUND, "unknown_offer");
        return ResponseEntity.ok(Map.of("success", true));
    }

    @GetMapping("/api/public/partner-program/offers/{token}")
    public ResponseEntity<Map<String, Object>> publicOffer(@PathVariable("token") String token) {
        if (unlimited) return error(HttpStatus.SERVICE_UNAVAILABLE, "not_available_in_ce");
        return service.publicOffer(token)
                .map(o -> ResponseEntity.ok(publicJson(o)))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "unknown_offer"));
    }

    /**
     * The offer as the signed-in caller sees it: the public offer, also once the partner's code
     * can no longer bring a sign-up when the caller is already that partner's client.
     */
    @GetMapping("/api/billing/partner/offers/{token}/view")
    public ResponseEntity<Map<String, Object>> viewOffer(@RequestHeader(value = "X-User-ID", required = false) String userId,
                                                        @PathVariable("token") String token) {
        if (unlimited) return error(HttpStatus.SERVICE_UNAVAILABLE, "not_available_in_ce");
        Long id = parseUserId(userId);
        if (id == null) return error(HttpStatus.UNAUTHORIZED, "unauthorized");
        return service.publicOffer(token, id)
                .map(o -> ResponseEntity.ok(publicJson(o)))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "unknown_offer"));
    }

    @GetMapping("/api/billing/partner/offers/{token}/welcome")
    public ResponseEntity<Map<String, Object>> welcome(@RequestHeader(value = "X-User-ID", required = false) String userId,
                                                       @PathVariable("token") String token) {
        if (unlimited) return error(HttpStatus.SERVICE_UNAVAILABLE, "not_available_in_ce");
        Long id = parseUserId(userId);
        if (id == null) return error(HttpStatus.UNAUTHORIZED, "unauthorized");
        return deliveries.welcome(id, token)
                .map(w -> ResponseEntity.ok(welcomeJson(w)))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "unknown_offer"));
    }

    /** The partner's own view of an offer: what they set, and its note. */
    private static Map<String, Object> offerJson(PartnerOffer o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", o.getToken());
        m.put("plan_code", o.getPlanCode());
        m.put("credit_tier_index", o.getCreditTierIndex());
        m.put("billing_cycle", o.getBillingCycle());
        m.put("label", o.getLabel());
        m.put("app_ids", o.getAppPublicationIds());
        m.put("created_at", o.getCreatedAt() == null ? null : o.getCreatedAt().toString());
        return m;
    }

    /** The client's view: the plan and the code's offer, and the partner's PUBLIC identity only. */
    private static Map<String, Object> publicJson(PartnerOfferService.PublicOffer o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", o.token());
        m.put("code", o.code());
        m.put("credits", o.credits());
        m.put("plan_code", o.planCode());
        m.put("credit_tier_index", o.creditTierIndex());
        m.put("billing_cycle", o.billingCycle());
        m.put("partner", partnerJson(o.partner(), null));
        m.put("apps_plan", o.appsPlan());
        // Marketplace cards (camelCase, as the marketplace serves them): id, title, description,
        // publisherId, publisherName, nodeIcons, visibility, showcaseRunId, showcaseInterfaceId.
        m.put("apps", o.apps());
        return m;
    }

    /**
     * The client's welcome: the partner (named when their profile is public; {@code user_id} only
     * when the client may write to them) and each app with its {@code status}: WAITING (payment
     * not confirmed yet), PENDING (being installed), INSTALLED or FAILED.
     */
    private static Map<String, Object> welcomeJson(PartnerOfferDeliveryService.Welcome w) {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> partner = partnerJson(w.partner(), w.partnerUserId());
        if (partner == null && w.partnerUserId() != null) {
            partner = new LinkedHashMap<>();
            partner.put("user_id", String.valueOf(w.partnerUserId()));
            partner.put("name", null);
            partner.put("handle", null);
            partner.put("avatar_url", null);
            partner.put("tier", null);
            partner.put("verified", false);
        }
        m.put("partner", partner);
        m.put("apps", w.apps());
        return m;
    }

    private static Map<String, Object> partnerJson(PublicProfileDto p, Long writableUserId) {
        if (p == null) return null;
        Map<String, Object> partner = new LinkedHashMap<>();
        if (writableUserId != null) partner.put("user_id", String.valueOf(writableUserId));
        partner.put("name", p.displayName());
        partner.put("handle", p.handle());
        partner.put("avatar_url", p.avatarUrl());
        partner.put("tier", p.partnerTier());
        partner.put("verified", p.verified());
        return partner;
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("success", false, "error", code));
    }

    private static Long parseUserId(String header) {
        if (header == null || header.isBlank()) return null;
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
