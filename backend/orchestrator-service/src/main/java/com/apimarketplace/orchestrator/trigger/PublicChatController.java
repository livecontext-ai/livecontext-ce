package com.apimarketplace.orchestrator.trigger;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Public controller for chat endpoints (no auth required, token-based).
 */
@RestController
@RequestMapping("/api/internal/chat")
public class PublicChatController {

    private static final Logger logger = LoggerFactory.getLogger(PublicChatController.class);

    private final ChatDispatchService chatDispatchService;

    public PublicChatController(ChatDispatchService chatDispatchService) {
        this.chatDispatchService = chatDispatchService;
    }

    @PostMapping("/{token}/session")
    public ResponseEntity<?> createOrResumeSession(
            @PathVariable String token,
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {
        try {
            String clientSessionId = body != null ? body.get("sessionId") : null;
            String ipAddress = getClientIp(request);

            ChatDispatchService.SessionResponse session =
                    chatDispatchService.createOrResumeSession(token, clientSessionId, ipAddress);

            return ResponseEntity.ok(session);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error creating chat session for token {}: {}", token, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Failed to create session"));
        }
    }

    @PostMapping("/{token}/message")
    public ResponseEntity<?> sendMessage(
            @PathVariable String token,
            @RequestBody Map<String, String> body) {
        try {
            String sessionId = body.get("sessionId");
            String message = body.get("message");

            if (sessionId == null || message == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "sessionId and message are required"));
            }

            Map<String, Object> result = chatDispatchService.sendMessage(token, sessionId, message);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        } catch (ShareInvocationLimitExceededException e) {
            return ResponseEntity.status(429).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error sending message for token {}: {}", token, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Failed to send message"));
        }
    }

    @GetMapping("/{token}/history")
    public ResponseEntity<?> getHistory(
            @PathVariable String token,
            @RequestHeader(value = "X-Chat-Session", required = false) String sessionId) {
        try {
            if (sessionId == null || sessionId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "X-Chat-Session header is required"));
            }

            return chatDispatchService.getHistory(token, sessionId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error fetching history for token {}: {}", token, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Failed to fetch history"));
        }
    }

    @GetMapping("/{token}/config")
    public ResponseEntity<?> getPublicConfig(@PathVariable String token) {
        try {
            return ResponseEntity.ok(chatDispatchService.getPublicConfig(token));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
