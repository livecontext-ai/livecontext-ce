package com.apimarketplace.conversation.controller.v3;

import com.apimarketplace.conversation.service.UserChatDefaultsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Per-(user, workspace) default chat options (V312).
 *
 * <p>Self-service: each user reads/writes ONLY their own defaults for the active
 * workspace. Scoped server-side by {@code X-User-ID} + {@code X-Organization-ID}
 * (the gateway injects the active org), so there is no cross-member exposure and no
 * role gate. Mounted under {@code /api/v3/chat/*} - same prefix as the chat endpoints,
 * so it routes to conversation-service with no gateway change.
 */
@Slf4j
@RestController
@RequestMapping("/api/v3/chat/defaults")
@RequiredArgsConstructor
public class UserChatDefaultsController {

    private final UserChatDefaultsService service;

    /**
     * Return the caller's default chat options for the active workspace. Empty object
     * when none set (or when no workspace is in scope) - callers treat that as "no
     * defaults".
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> get(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            return ResponseEntity.ok(Map.of());
        }
        return ResponseEntity.ok(service.get(userId, organizationId));
    }

    /**
     * Upsert the caller's default chat options for the active workspace. A workspace
     * must be in scope (defaults are per (user, workspace)); without one this is a 400.
     * Unknown keys in the body are dropped server-side.
     */
    @PutMapping
    public ResponseEntity<Map<String, Object>> put(
            @RequestHeader("X-User-ID") String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestBody(required = false) Map<String, Object> config) {
        if (organizationId == null || organizationId.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(service.save(userId, organizationId, config));
    }
}
