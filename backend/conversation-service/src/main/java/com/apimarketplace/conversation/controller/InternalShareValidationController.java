package com.apimarketplace.conversation.controller;

import com.apimarketplace.conversation.repository.ConversationRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    private final ConversationRepository conversationRepository;

    public InternalShareValidationController(ConversationRepository conversationRepository) {
        this.conversationRepository = conversationRepository;
    }

    @GetMapping("/validate/{token}")
    public ResponseEntity<Map<String, String>> validate(@PathVariable String token) {
        return conversationRepository.findByShareToken(token)
                .filter(c -> c.getShareMode() != null && !"off".equals(c.getShareMode()))
                .map(c -> ResponseEntity.ok(Map.of("userId", c.getUserId())))
                .orElse(ResponseEntity.notFound().build());
    }
}
