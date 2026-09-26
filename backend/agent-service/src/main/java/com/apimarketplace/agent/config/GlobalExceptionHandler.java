package com.apimarketplace.agent.config;

import com.apimarketplace.agent.bridge.BridgeAccessDeniedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

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
     * A request body the converter cannot parse (malformed JSON, a truncated payload, a
     * value its field cannot hold): 400. Named explicitly because, unlike the framework
     * exceptions the catch-all's ErrorResponse branch covers, it does not carry a status.
     * A SERVER-side mapping defect (our own target type cannot be built) arrives as the
     * parent HttpMessageConversionException instead and still reaches the catch-all as 500.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMessageNotReadable(HttpMessageNotReadableException e) {
        log.info("Unreadable request body: {}", e.getMessage());
        return ResponseEntity.badRequest().body(Map.of(
                "error", "BAD_REQUEST",
                "message", "Request body could not be parsed"
        ));
    }

    /**
     * A path variable or parameter of the wrong shape, e.g. a non-UUID
     * {@code /api/agents/executions/{execId}}. The caller's mistake: 400, not 500.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.info("Invalid value for parameter '{}'", e.getName());
        return ResponseEntity.badRequest().body(Map.of(
                "error", "INVALID_PARAMETER",
                "message", "Invalid value for parameter '" + e.getName() + "': expected "
                        + (e.getRequiredType() != null ? e.getRequiredType().getSimpleName() : "a valid format")
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

    /**
     * Everything no specific handler above claims.
     *
     * <p>A Spring MVC exception that implements {@link ErrorResponse} already carries the
     * status the framework would have answered (404 unmapped route, 405 wrong method, 415
     * content type, 400 unreadable body / missing parameter / missing header, a
     * {@code ResponseStatusException}'s own status, ...). Without this branch the catch-all
     * turned every one of them into a 500 and an ERROR with a stack trace: a caller's
     * mistake filed as a server incident. Answering the carried status closes the whole
     * family in one place instead of one handler per exception type. A 5xx carried that
     * way is still ours and keeps ERROR; anything else keeps 500 + ERROR.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        if (ex instanceof ErrorResponse er) {
            HttpStatusCode status = er.getStatusCode();
            if (status.is5xxServerError()) {
                log.error("Unhandled exception ({}): {}", status.value(), ex.getMessage(), ex);
            } else {
                log.info("Request refused with {}: {}", status.value(), ex.getMessage());
            }
            HttpStatus resolved = HttpStatus.resolve(status.value());
            String code = resolved != null ? resolved.name() : "HTTP_" + status.value();
            String detail = er.getBody().getDetail();
            String message = detail != null && !detail.isBlank() ? detail
                    : (resolved != null ? resolved.getReasonPhrase() : "Request refused");
            return ResponseEntity.status(status).headers(er.getHeaders())
                    .body(Map.of("error", code, "message", message));
        }
        log.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "INTERNAL_ERROR",
                "message", "An unexpected error occurred"
        ));
    }
}
