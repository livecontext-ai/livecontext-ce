package com.apimarketplace.auth.bridge.web;

import com.apimarketplace.auth.bridge.domain.BridgeAccessModels.AccessDecision;
import com.apimarketplace.auth.bridge.service.BridgeAccessService;
import com.apimarketplace.auth.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Internal-only access check. Called by {@code BridgeAccessGuard} in
 * shared-agent-lib before a bridge provider dispatches an LLM call.
 *
 * <p>Not exposed through the public gateway. The caller must supply the
 * authoritative user id via {@code X-User-ID}. The role set is supplied via
 * {@code X-User-Roles} on the interactive path (gateway-injected from the
 * validated JWT), but <b>role-less internal callers exist</b> - the schedule
 * daemon's synchronous chat carries only {@code X-User-ID}. For those the admin
 * determination falls back to the <b>persisted</b> user roles (this service owns
 * the role store), so an admin-owned scheduled bridge agent is not silently
 * mis-classified as a plain {@code USER} and denied under {@code ADMIN_ONLY}.
 */
@RestController
@RequestMapping("/api/internal/bridge-access")
public class InternalBridgeAccessController {

    private static final Logger log = LoggerFactory.getLogger(InternalBridgeAccessController.class);

    private final BridgeAccessService service;
    private final UserService userService;

    public InternalBridgeAccessController(BridgeAccessService service, UserService userService) {
        this.service = service;
        this.userService = userService;
    }

    /**
     * POST /api/internal/bridge-access/check?bridge=claude-code&incrementUsage=true
     * Headers: X-User-ID, X-User-Roles
     *
     * <p>Return shape aligns with {@link AccessDecision}. The counter
     * side-effect is gated by {@code incrementUsage}: pre-flight UI filter
     * calls with {@code false}, dispatch-time guard calls with {@code true}.
     */
    @PostMapping("/check")
    public ResponseEntity<?> check(
            @RequestParam("bridge") String bridge,
            @RequestParam(value = "incrementUsage", defaultValue = "false") boolean incrementUsage,
            @RequestHeader(value = "X-User-ID", defaultValue = "") String userId,
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        if (userId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "X-User-ID header is required"));
        }
        // Admin is authoritative from the persisted role store, NOT solely the forwarded
        // header. The header is trusted (validated JWT on the interactive path) and wins
        // when it already asserts ADMIN; when it does not (role-less internal caller, e.g.
        // the schedule daemon), we resolve the real role from this service's user store.
        boolean isAdmin = headerClaimsAdmin(roles) || persistedAdmin(userId);
        AccessDecision decision = service.checkAccess(userId, isAdmin, bridge, incrementUsage);
        return ResponseEntity.ok(decision);
    }

    private static boolean headerClaimsAdmin(String roles) {
        return roles != null && roles.toUpperCase().contains("ADMIN");
    }

    /**
     * Authoritative ADMIN check from the persisted user roles.
     *
     * <p>Invariant this relies on: on every path that reaches this service the
     * {@code X-User-ID} is the <b>numeric</b> auth-service user id (the same idiom
     * {@code UserController}/{@code InternalAuthController} use with
     * {@code Long.parseLong}); the schedule daemon forwards the schedule's numeric
     * {@code tenant_id}. So {@code findById} resolves on the daemon path.
     *
     * <p>Defensive by design: a non-numeric {@code X-User-ID} (CE / non-cloud
     * principal) or an unknown user resolves to {@code false} without throwing,
     * so the header remains the primary signal and the call never fails on
     * identity shape.
     */
    private boolean persistedAdmin(String userId) {
        try {
            return userService.findById(Long.parseLong(userId))
                    .map(u -> u.getRoles() != null && u.getRoles().contains("ADMIN"))
                    .orElse(false);
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
