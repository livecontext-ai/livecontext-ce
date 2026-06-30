package com.apimarketplace.auth.web;

import com.apimarketplace.auth.dto.CeLinkEntitlements;
import com.apimarketplace.auth.dto.CeLinkHeartbeatRequest;
import com.apimarketplace.auth.dto.CeLinkRegisterRequest;
import com.apimarketplace.auth.dto.CeLinkRegisterResponse;
import com.apimarketplace.auth.dto.CeLinkSummary;
import com.apimarketplace.auth.service.CeLinkEntitlementsService;
import com.apimarketplace.auth.service.CeLinkHeartbeatService;
import com.apimarketplace.auth.service.CeLinkService;
import com.apimarketplace.auth.service.IpHashService;
import com.apimarketplace.auth.service.RequestAuditContext;
import com.apimarketplace.auth.util.ClientIpExtractor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * REST controller for CE-install ↔ cloud-user binding (doc §3.2/§3.3).
 * Thin controller: delegates all business logic to {@link CeLinkService}.
 *
 * <p>All endpoints require {@code X-User-ID} (injected by gateway from JWT).
 * The {@code CeLinkConstantTimeFilter} (PR3d) wraps register/revoke to ensure
 * uniform 400ms latency across 201/409/404 - closes the timing-side-channel
 * called out in doc §1 #16.
 */
@RestController
@RequestMapping("/api/ce-link")
@ConditionalOnProperty(name = "auth.mode", havingValue = "keycloak", matchIfMissing = false)
// Note: no @CrossOrigin here - the global CorsConfigurationSource in SecurityConfig
// governs origins for the whole service. Adding a per-controller wildcard would
// mask the global config and confuse future tightening. CeLink-specific CSP + cache
// + referrer headers are stamped by CeLinkSecurityHeadersFilter; timing-side-channel
// is closed by CeLinkConstantTimeFilter.
public class CeLinkController {

    private static final Logger log = LoggerFactory.getLogger(CeLinkController.class);

    private final CeLinkService service;
    private final CeLinkHeartbeatService heartbeatService;
    private final IpHashService ipHashService;
    private final CeLinkEntitlementsService entitlementsService;
    private final com.apimarketplace.auth.service.CeLinkRewardReadService rewardReadService;

    public CeLinkController(CeLinkService service,
                            CeLinkHeartbeatService heartbeatService,
                            IpHashService ipHashService,
                            CeLinkEntitlementsService entitlementsService,
                            com.apimarketplace.auth.service.CeLinkRewardReadService rewardReadService) {
        this.service = service;
        this.heartbeatService = heartbeatService;
        this.ipHashService = ipHashService;
        this.entitlementsService = entitlementsService;
        this.rewardReadService = rewardReadService;
    }

    /**
     * Bind a CE install to the caller. Idempotent on (install_id, user_id).
     * <ul>
     *   <li>201 + {@code registered=true} - new binding, or idempotent retry by same user.</li>
     *   <li>409 + {@code error=ALREADY_BOUND} - install_id already owned (possibly by someone else).
     *       {@code boundToEmail} is non-null ONLY when the caller is the prior owner.</li>
     * </ul>
     */
    @PostMapping("/register")
    public ResponseEntity<CeLinkRegisterResponse> register(
            @RequestHeader("X-User-ID") Long userId,
            @Valid @RequestBody CeLinkRegisterRequest body,
            HttpServletRequest httpRequest
    ) {
        log.info("POST /api/ce-link/register userId={} installId={}", userId, body.installId());
        RequestAuditContext audit = RequestAuditContext.from(httpRequest, ipHashService, body.installId());
        CeLinkRegisterResponse response = service.register(userId, body.installId(), body.ceVersion(),
                body.label(), audit);
        HttpStatus status = response.registered() ? HttpStatus.CREATED : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(response);
    }

    /**
     * List the caller's ACTIVE installs.
     */
    @GetMapping("/mine")
    public ResponseEntity<Page<CeLinkSummary>> mine(
            @RequestHeader("X-User-ID") Long userId,
            Pageable pageable
    ) {
        log.debug("GET /api/ce-link/mine userId={} page={}", userId, pageable.getPageNumber());
        return ResponseEntity.ok(service.mine(userId, pageable));
    }

    /**
     * Plan entitlements of the cloud account bound to {@code installId} - the
     * channel by which a self-hosted CE inherits the cloud subscription (the
     * CE↔Cloud pricing delegation). Ownership-scoped: a caller only ever sees
     * their own install. Always 200; an unknown, revoked, or foreign install
     * yields {@code none()} so the CE falls back to its local defaults.
     */
    @GetMapping("/{installId}/entitlements")
    public ResponseEntity<CeLinkEntitlements> entitlements(
            @RequestHeader("X-User-ID") Long userId,
            @PathVariable UUID installId
    ) {
        log.debug("GET /api/ce-link/{}/entitlements userId={}", installId, userId);
        return ResponseEntity.ok(entitlementsService.entitlementsForCaller(userId, installId));
    }

    /**
     * Referral code + invite stats of the cloud account bound to {@code installId},
     * so a self-hosted CE can show its owner's referral progress. Ownership-scoped
     * (a caller only ever sees their own install); always 200, with empty stats for
     * an unknown, revoked, or foreign install. The referral code is lazily minted on
     * first read. To redeem, a CE referee calls the cloud {@code /api/billing/redeem}
     * as the bound cloud user (no install scope), so no redeem endpoint lives here.
     */
    @GetMapping("/{installId}/reward-stats")
    public ResponseEntity<com.apimarketplace.auth.service.RewardService.InviteStats> rewardStats(
            @RequestHeader("X-User-ID") Long userId,
            @PathVariable UUID installId
    ) {
        log.debug("GET /api/ce-link/{}/reward-stats userId={}", installId, userId);
        return ResponseEntity.ok(rewardReadService.statsForCaller(userId, installId));
    }

    /**
     * Revoke an install. Ownership-scoped (404 if install isn't the caller's).
     * Idempotent on already-revoked rows.
     */
    @DeleteMapping("/{installId}")
    public ResponseEntity<Void> revoke(
            @RequestHeader("X-User-ID") Long userId,
            @PathVariable UUID installId,
            HttpServletRequest httpRequest
    ) {
        log.info("DELETE /api/ce-link/{} userId={}", installId, userId);
        RequestAuditContext audit = RequestAuditContext.from(httpRequest, ipHashService, installId);
        boolean ok = service.revoke(userId, installId, audit);
        return ok ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * Persist a heartbeat (doc §3.5). 204 = stored. 404 = install not in
     * caller's namespace (also the masking response for cross-user enumeration).
     * 410 GONE = link was revoked; the CE side should stop heartbeating.
     *
     * <p>Caller IP comes from {@link ClientIpExtractor} (X-Forwarded-For chain
     * set by Caddy/Cloudflare) - never persisted in plaintext; immediately
     * HMAC-hashed inside {@link CeLinkHeartbeatService}.
     */
    @PostMapping("/{installId}/heartbeat")
    public ResponseEntity<Void> heartbeat(
            @RequestHeader("X-User-ID") Long userId,
            @PathVariable UUID installId,
            @Valid @RequestBody CeLinkHeartbeatRequest body,
            HttpServletRequest request
    ) {
        String ip = ClientIpExtractor.extract(request);
        log.debug("POST /api/ce-link/{}/heartbeat userId={}", installId, userId);
        CeLinkHeartbeatService.Outcome outcome = heartbeatService.heartbeat(userId, installId, body.ceVersion(), ip);
        return switch (outcome) {
            case OK -> ResponseEntity.noContent().build();
            case NOT_FOUND -> ResponseEntity.notFound().build();
            case REVOKED -> ResponseEntity.status(HttpStatus.GONE).build();
        };
    }
}
