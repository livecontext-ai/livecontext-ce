package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.orchestrator.services.access.OwnershipResolverRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

/**
 * Internal endpoint used by other services (via auth-client) to ask
 * "which org owns this resource?" - required by the cross-service deny-list
 * write check (PR-2).
 *
 * <p>Reachability is restricted to the cluster: the gateway already denies
 * {@code /api/internal/**} by default (covered by gateway routing tests) and
 * the box-level UFW closes the orchestrator port to the public internet.
 * The HMAC inter-service filter that authenticates caller→callee pairs lands
 * in PR-1.h.
 */
@RestController
@RequestMapping("/api/internal/orchestrator")
public class InternalOwnerController {

    private final OwnershipResolverRegistry registry;

    public InternalOwnerController(OwnershipResolverRegistry registry) {
        this.registry = registry;
    }

    /**
     * Look up the owning org of a resource owned by this service.
     *
     * @return 200 with {@code {ownerOrgId: "..."}} when found ; 404 otherwise
     *         (unknown type, malformed id, not-found, or personal resource).
     */
    @GetMapping("/owner/{type}/{id}")
    public ResponseEntity<Map<String, String>> getOwner(@PathVariable("type") String type,
                                                        @PathVariable("id") String id) {
        Optional<String> ownerOrgId = registry.resolveOwnerOrgId(type, id);
        return ownerOrgId
                .<ResponseEntity<Map<String, String>>>map(orgId -> ResponseEntity.ok(Map.of("ownerOrgId", orgId)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
