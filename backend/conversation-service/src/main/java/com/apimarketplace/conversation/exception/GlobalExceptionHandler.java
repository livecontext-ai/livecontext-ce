package com.apimarketplace.conversation.exception;

import com.apimarketplace.conversation.service.ai.BridgeAccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gestionnaire global pour standardiser les reponses d'erreur REST.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ConversationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleConversationNotFound(ConversationNotFoundException ex) {
        logger.warn("Conversation introuvable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("CONVERSATION_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(ConversationInactiveException.class)
    public ResponseEntity<ErrorResponse> handleConversationInactive(ConversationInactiveException ex) {
        logger.warn("Conversation inactive: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("CONVERSATION_INACTIVE", ex.getMessage()));
    }

    @ExceptionHandler(InvalidMessageException.class)
    public ResponseEntity<ErrorResponse> handleInvalidMessage(InvalidMessageException ex) {
        logger.warn("Message invalide: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("INVALID_MESSAGE", ex.getMessage()));
    }

    /**
     * Maps a CLI-bridge access deny to a structured 403 (policy refusal) or 429
     * (quota exhausted). Mirrors the agent-service handler so the frontend sees
     * the same shape regardless of which call path was used. The {@code reason}
     * and {@code remainingRequestsToday} fields are surfaced in the body so the
     * UI can distinguish admin-only / allowlist / quota cases.
     */
    @ExceptionHandler(BridgeAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleBridgeAccessDenied(BridgeAccessDeniedException ex) {
        HttpStatus status = ex.isQuotaExhausted() ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.FORBIDDEN;
        logger.info("Bridge access denied: provider={} reason={} status={}",
                ex.getProviderName(), ex.getReason(), status.value());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "BRIDGE_ACCESS_DENIED");
        body.put("message", ex.getMessage());
        body.put("provider", ex.getProviderName());
        body.put("reason", ex.getReason());
        if (ex.getRemainingRequestsToday() != null) {
            body.put("remainingRequestsToday", ex.getRemainingRequestsToday());
        }
        return ResponseEntity.status(status).body(body);
    }

    /**
     * A turn refused because the conversation holds Gmail / Drive content and the chosen model
     * provider may not receive it (LC-004). 403: a policy refusal the user resolves by switching
     * model, not a fault.
     */
    @ExceptionHandler(com.apimarketplace.conversation.service.ai.RestrictedDataTransferGuard.RestrictedDataTransferDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleRestrictedDataTransferDenied(
            com.apimarketplace.conversation.service.ai.RestrictedDataTransferGuard.RestrictedDataTransferDeniedException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "RESTRICTED_DATA_PROVIDER_NOT_ALLOWED");
        body.put("message", ex.getMessage());
        body.put("provider", ex.getProviderName());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /**
     * Representation minimale d'une erreur REST.
     */
    public record ErrorResponse(String code, String message) { }
}
