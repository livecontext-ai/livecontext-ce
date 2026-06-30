package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.CeLink;
import com.apimarketplace.auth.service.CeLinkService;
import com.apimarketplace.auth.service.IpHashService;
import com.apimarketplace.auth.service.RequestAuditContext;
import com.apimarketplace.auth.service.SquatRecoveryTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Endpoint for the squat-recovery email link (doc §1 #41 - victim-UX closure).
 *
 * <p>Unauthenticated by design: the one-time HMAC token IS the auth. The
 * gateway lets this path through without an X-User-ID header. Each token is
 * single-use (atomic GETDEL on Redis), so replay is impossible.
 *
 * <p>On valid token: force-revoke the contested ce_link row via
 * {@link CeLinkService#adminRevoke} with reason {@code SQUAT_RECOVERY} and
 * acting userId = the victim. The victim's CE can then re-register cleanly
 * (it'll get a fresh install_id since the squatter's row is now REVOKED but
 * not deleted - install_id is the PK so it's gone from the ACTIVE namespace).
 */
@RestController
@RequestMapping("/api/ce-link/squat-recovery")
@ConditionalOnProperty(name = "auth.mode", havingValue = "keycloak", matchIfMissing = false)
// CORS governed by the global config - see CeLinkController for rationale.
public class SquatRecoveryController {

    private static final Logger log = LoggerFactory.getLogger(SquatRecoveryController.class);

    private final SquatRecoveryTokenService tokenService;
    private final CeLinkService ceLinkService;
    private final IpHashService ipHashService;

    public SquatRecoveryController(SquatRecoveryTokenService tokenService,
                                   CeLinkService ceLinkService,
                                   IpHashService ipHashService) {
        this.tokenService = tokenService;
        this.ceLinkService = ceLinkService;
        this.ipHashService = ipHashService;
    }

    /**
     * Consume the recovery token and force-revoke the contested link. 204 on
     * success, 404 on unknown/expired/already-consumed (all three are
     * intentionally indistinguishable from the caller - no oracle).
     */
    @PostMapping("/{token}")
    public ResponseEntity<Void> consume(@PathVariable String token, HttpServletRequest request) {
        // Peek first - only consume the token after the revoke succeeds, so a
        // transient DB hiccup doesn't burn the victim's recovery link (audit M-1).
        Optional<SquatRecoveryTokenService.TokenBinding> binding = tokenService.peek(token);
        if (binding.isEmpty()) {
            log.info("SquatRecovery consume: unknown/expired token");
            return ResponseEntity.notFound().build();
        }
        SquatRecoveryTokenService.TokenBinding b = binding.get();
        RequestAuditContext audit = RequestAuditContext.from(request, ipHashService, b.installId());
        boolean ok;
        try {
            ok = ceLinkService.adminRevoke(
                    b.installId(),
                    CeLink.RevokeReason.SQUAT_RECOVERY,
                    b.victimUserId(),
                    audit);
        } catch (RuntimeException revokeFailure) {
            // Leave the token live - victim can retry with the SAME email link.
            log.warn("SquatRecovery consume: adminRevoke threw for installId={} victimUserId={} ({}) - token preserved",
                    b.installId(), b.victimUserId(), revokeFailure.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!ok) {
            // Token was valid but the install_id has since vanished - same 404 shape.
            // The token is also invalidated since the bound install no longer exists.
            tokenService.invalidate(token);
            log.warn("SquatRecovery consume: installId={} no longer present", b.installId());
            return ResponseEntity.notFound().build();
        }
        tokenService.invalidate(token);
        log.info("SquatRecovery consume OK: installId={} victimUserId={}", b.installId(), b.victimUserId());
        return ResponseEntity.noContent().build();
    }
}
