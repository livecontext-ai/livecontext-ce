package com.apimarketplace.datasource.common.web;

import com.apimarketplace.auth.client.access.OrgAccessDeniedException;
import com.apimarketplace.common.web.TenantRequiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Global exception handler for datasource-service REST controllers.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(TenantRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleTenantRequired(TenantRequiredException ex) {
        logger.warn("Missing tenant ID: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(errorResponse("TENANT_REQUIRED", ex.getMessage()));
    }

    /**
     * Org per-resource access (deny-list / read-only) violation → 403.
     *
     * <p>{@link OrgAccessDeniedException} is annotated {@code @ResponseStatus(FORBIDDEN)},
     * but the catch-all {@code @ExceptionHandler(Exception.class)} in this advice would
     * otherwise intercept it first and map it to 500. An explicit handler keeps the
     * 403 contract for every path that throws it (the user-facing CRUD write gate and
     * the existing clone/delete rethrows in DataSourceCrudController).
     */
    @ExceptionHandler(OrgAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleOrgAccessDenied(OrgAccessDeniedException ex) {
        logger.warn("Org access denied: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(errorResponse("ACCESS_DENIED", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        logger.warn("Invalid argument: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(errorResponse("INVALID_ARGUMENT", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        logger.warn("Invalid state: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(errorResponse("INVALID_STATE", ex.getMessage()));
    }

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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        logger.error("Unhandled exception: {}", ex.getMessage(), ex);
        return ResponseEntity.internalServerError()
                .body(errorResponse("INTERNAL_ERROR", "An unexpected error occurred"));
    }

    private Map<String, Object> errorResponse(String errorCode, String message) {
        return Map.of(
                "success", false,
                "errorCode", errorCode,
                "message", message
        );
    }
}
