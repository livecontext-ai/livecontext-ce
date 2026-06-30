package com.apimarketplace.conversation.controller.internal;

import com.apimarketplace.common.scope.TolerantScope;
import com.apimarketplace.conversation.service.DmService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal endpoint for the gateway's {@code ChannelAuthorizer} to check WS-subscription
 * access to a DM thread. Protected by X-Gateway-Secret (GatewayAuthenticationFilter).
 *
 * <p>DM threads are identity-level (global, not org-scoped), so access is purely
 * "is {@code userId} one of the two participants" - no orgId is consulted.
 */
@RestController
@RequestMapping("/api/internal")
public class InternalDmController {

    private final DmService dmService;

    public InternalDmController(DmService dmService) {
        this.dmService = dmService;
    }

    @GetMapping("/dm-threads/{threadId}/access")
    @TolerantScope(reason = "Gateway ChannelAuthorizer for DM WS subscriptions - DM threads are identity-level (global, not org-scoped); access = requester is one of the two participants")
    public ResponseEntity<Boolean> checkAccess(@PathVariable String threadId,
                                               @RequestParam String userId) {
        return ResponseEntity.ok(dmService.isParticipant(threadId, userId));
    }
}
