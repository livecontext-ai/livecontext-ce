package com.apimarketplace.orchestrator.common.web;

import com.apimarketplace.common.web.TenantRequiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * Global exception handler for all REST controllers.
 * Provides consistent error responses across the API.
 *
 * <p>Replaces 40+ duplicate try-catch blocks across controllers,
 * following the DRY and Single Responsibility principles.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handle missing tenant ID errors.
     */
    @ExceptionHandler(TenantRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleTenantRequired(TenantRequiredException ex) {
        logger.warn("Missing tenant ID: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(errorResponse("TENANT_REQUIRED", ex.getMessage()));
    }

    /**
     * Handle missing required request headers before they fall through to 500.
     */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingRequestHeader(MissingRequestHeaderException ex) {
        String headerName = ex.getHeaderName();
        logger.warn("Missing required request header: {}", headerName);

        if ("X-User-ID".equalsIgnoreCase(headerName)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(errorResponse("UNAUTHENTICATED", "Missing X-User-ID"));
        }

        return ResponseEntity.badRequest()
                .body(errorResponse("MISSING_REQUEST_HEADER",
                        "Required request header '" + headerName + "' is not present"));
    }

    /**
     * Handle unmapped routes and missing static resources.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> handleSpringNotFound(Exception ex) {
        logger.info("Route not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(errorResponse("NOT_FOUND", "Resource not found"));
    }

    /**
     * Handle a wrong HTTP method on an existing route (e.g. GET on a POST/DELETE-only path).
     * Returns a proper 405 with an {@code Allow} header instead of letting it fall through to
     * the generic 500. Mirrors the 404 handling above so unmatched method/route requests are
     * always a clean 4xx, never a misleading INTERNAL_ERROR.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        logger.info("Method not allowed: {}", ex.getMessage());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED);
        if (ex.getSupportedHttpMethods() != null) {
            builder.allow(ex.getSupportedHttpMethods().toArray(new HttpMethod[0]));
        }
        return builder.body(errorResponse("METHOD_NOT_ALLOWED", "HTTP method not supported for this endpoint"));
    }

    /**
     * Handle invalid argument errors (typically from service layer validation).
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        logger.warn("Invalid argument: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(errorResponse("INVALID_ARGUMENT", ex.getMessage()));
    }

    /**
     * Handle invalid state errors (e.g., workflow already running).
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        logger.warn("Invalid state: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(errorResponse("INVALID_STATE", ex.getMessage()));
    }

    /**
     * Handle Spring validation errors (@Valid annotation failures).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Validation failed");
        logger.warn("Request validation failed: {}", message);
        return ResponseEntity.badRequest()
                .body(errorResponse("VALIDATION_ERROR", message));
    }

    /**
     * Handle async request timeouts.
     * These are expected during long-running async operations - a dedicated handler
     * prevents the generic handler from trying to serialize an error response.
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public void handleAsyncTimeout(AsyncRequestTimeoutException ex) {
        logger.debug("Async request timeout: {}", ex.getMessage());
    }

    /**
     * Handle optimistic locking failures (concurrent modifications).
     * Returns 409 Conflict instead of 500 Internal Server Error.
     */
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, Object>> handleOptimisticLocking(
            org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
        logger.warn("Concurrent modification conflict: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(errorResponse("CONFLICT", "Resource was modified by another request. Please retry."));
    }

    /**
     * Handle storage quota exceeded errors (413 Payload Too Large).
     */
    @ExceptionHandler(com.apimarketplace.common.storage.exception.QuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> handleQuotaExceeded(
            com.apimarketplace.common.storage.exception.QuotaExceededException ex) {
        logger.warn("Storage quota exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(errorResponse("STORAGE_QUOTA_EXCEEDED", ex.getMessage()));
    }

    /**
     * Handle type mismatch errors (e.g., invalid UUID in path variables).
     * Returns 400 instead of 500 with a clean error message.
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        String paramName = ex.getName();
        String value = ex.getValue() != null ? ex.getValue().toString() : "null";
        // Truncate long values to avoid log spam
        if (value.length() > 100) value = value.substring(0, 100) + "...";
        logger.warn("Invalid parameter '{}': {}", paramName, value);
        return ResponseEntity.badRequest()
                .body(errorResponse("INVALID_PARAMETER",
                    "Invalid value for parameter '" + paramName + "': expected " +
                    (ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "valid format")));
    }

    /**
     * Handle all other unexpected errors.
     * Logs full stack trace for debugging but returns generic message to client.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        logger.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity.internalServerError()
                .body(errorResponse("INTERNAL_ERROR", "An unexpected error occurred"));
    }

    /**
     * Build a standard error response map.
     */
    private Map<String, Object> errorResponse(String errorCode, String message) {
        return Map.of(
                "success", false,
                "errorCode", errorCode,
                "message", message
        );
    }
}
