package com.apimarketplace.conversation.controller;

import com.apimarketplace.conversation.service.ConversationSharingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Internal endpoint called by the gateway to validate a share token and resolve its owner's userId.
 * Not exposed via the gateway - internal network only.
 *
 * Returns 200 { userId: "..." } when token is valid and shareMode != "off".
 * Returns 404 when token is invalid or sharing is disabled.
 */
@RestController
@RequestMapping("/api/internal/share")
public class InternalShareValidationController {

    private final ConversationSharingService sharingService;

    public InternalShareValidationController(ConversationSharingService sharingService) {
        this.sharingService = sharingService;
    }

    @GetMapping("/validate/{token}")
    public ResponseEntity<Map<String, String>> validate(@PathVariable String token) {
        return sharingService.findByShareToken(token)
                .filter(c -> c.getShareMode() != null && !"off".equals(c.getShareMode()))
                .map(c -> ResponseEntity.ok(Map.of("userId", c.getUserId())))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Which conversation a share token opens, asked on behalf of the caller named by
     * {@code X-User-ID} / {@code X-Organization-ID}: 200 {@code {conversationId}} when sharing is
     * on and that conversation is in the caller's workspace, 404 otherwise (unknown token,
     * sharing off, or another workspace's conversation, deliberately indistinguishable).
     *
     * <p>Called by publication-service before it registers a CONVERSATION share link for a user,
     * so a link can only name a conversation its creator holds. Kept apart from
     * {@link #validate}, whose response the gateway reads as a share-token identity.
     */
    @GetMapping("/validate/{token}/in-scope")
    public ResponseEntity<Map<String, String>> validateInScope(
            @PathVariable String token,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        return sharingService.findSharedInScope(token, userId, organizationId)
                .map(c -> ResponseEntity.ok(Map.of("conversationId", c.getId())))
                .orElse(ResponseEntity.notFound().build());
    }
}
