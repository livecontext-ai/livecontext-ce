package com.apimarketplace.agent.config;

import com.apimarketplace.agent.bridge.BridgeAccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.util.HashMap;
import java.util.Map;

/**
 * Centralized REST exception handling for agent-service.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "INVALID_ARGUMENT",
                "message", e.getMessage()
        ));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "INVALID_STATE",
                "message", e.getMessage()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        return ResponseEntity.badRequest().body(Map.of(
                "error", "VALIDATION_ERROR",
                "message", message
        ));
    }

    /**
     * Client disconnected before the response was written.
     * Expected when conversation-service times out or user stops the stream.
     * No response can be sent since the connection is already closed.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(AsyncRequestNotUsableException e) {
        log.warn("Client disconnected before response could be written: {}", e.getMessage());
    }

    /**
     * Bridge access denied. Quota exhaustion → 429 (Retry tomorrow); everything
     * else (disabled, admin-only, not allowlisted, guard unreachable) → 403.
     */
    @ExceptionHandler(BridgeAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleBridgeAccessDenied(BridgeAccessDeniedException e) {
        HttpStatus status = e.isQuotaExhausted() ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.FORBIDDEN;
        Map<String, Object> body = new HashMap<>();
        body.put("error", "BRIDGE_ACCESS_DENIED");
        body.put("reason", e.getReason());
        body.put("provider", e.getProviderName());
        body.put("message", e.getMessage());
        if (e.getRemainingRequestsToday() != null) {
            body.put("remainingRequestsToday", e.getRemainingRequestsToday());
        }
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleDataIntegrity(DataIntegrityViolationException e) {
        String detail = mostSpecificMessage(e);
        if (isUniqueConstraintViolation(detail)) {
            String constraint = extractConstraintName(detail);
            log.warn("Duplicate resource rejected by unique constraint {}: {}", constraint, detail);

            Map<String, Object> body = new HashMap<>();
            body.put("error", "DUPLICATE_RESOURCE");
            body.put("message", "A resource already exists with the same unique fields.");
            if (constraint != null) {
                body.put("constraint", constraint);
            }
            return ResponseEntity.badRequest().body(body);
        }

        log.error("Data integrity violation: {}", e.getMessage(), e);
        return ResponseEntity.badRequest().body(Map.of(
                "error", "DATA_INTEGRITY_VIOLATION",
                "message", "The request violates a data integrity constraint"
        ));
    }

    private static String mostSpecificMessage(DataIntegrityViolationException e) {
        Throwable cause = e.getMostSpecificCause();
        if (cause != null && cause.getMessage() != null) {
            return cause.getMessage();
        }
        return e.getMessage() != null ? e.getMessage() : "";
    }

    private static boolean isUniqueConstraintViolation(String detail) {
        String normalized = detail == null ? "" : detail.toLowerCase();
        return normalized.contains("duplicate key value")
                || normalized.contains("unique constraint");
    }

    private static String extractConstraintName(String detail) {
        if (detail == null) {
            return null;
        }

        int marker = detail.indexOf("constraint \"");
        if (marker < 0) {
            return null;
        }
        int start = marker + "constraint \"".length();
        int end = detail.indexOf('"', start);
        return end > start ? detail.substring(start, end) : null;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        log.error("Unhandled exception: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "INTERNAL_ERROR",
                "message", "An unexpected error occurred"
        ));
    }
}
