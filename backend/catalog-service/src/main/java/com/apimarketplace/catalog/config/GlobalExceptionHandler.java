package com.apimarketplace.catalog.config;

import com.apimarketplace.catalog.service.exception.AccessDeniedException;
import com.apimarketplace.catalog.service.exception.ApiAuthenticationException;
import com.apimarketplace.catalog.service.exception.CatalogServiceException;
import com.apimarketplace.catalog.service.exception.ToolNotFoundException;
import com.apimarketplace.catalog.service.exception.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Global exception handler for the catalog service.
 * Converts exceptions to structured JSON error responses.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * Handle access denied exceptions (403 Forbidden).
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied: {} - user={}, resource={}/{}",
            ex.getMessage(), ex.getUserId(), ex.getResourceType(), ex.getResourceId());

        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(buildErrorResponse(
                "ACCESS_DENIED",
                ex.getMessage(),
                Map.of(
                    "resourceType", ex.getResourceType(),
                    "resourceId", ex.getResourceId()
                )
            ));
    }

    /**
     * Handle API authentication exceptions (401/403).
     */
    @ExceptionHandler(ApiAuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleApiAuthentication(ApiAuthenticationException ex) {
        log.warn("API authentication error: {} - service={}, status={}",
            ex.getMessage(), ex.getService(), ex.getStatus());

        return ResponseEntity.status(ex.getStatus())
            .body(buildErrorResponse(
                "API_AUTHENTICATION_ERROR",
                ex.getMessage(),
                Map.of("service", ex.getService())
            ));
    }

    /**
     * Handle tool not found exceptions (404).
     */
    @ExceptionHandler(ToolNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleToolNotFound(ToolNotFoundException ex) {
        log.debug("Tool not found: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(buildErrorResponse("TOOL_NOT_FOUND", ex.getMessage(), null));
    }

    /**
     * Handle validation exceptions (400).
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(ValidationException ex) {
        log.debug("Validation error: {}", ex.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(buildErrorResponse("VALIDATION_ERROR", ex.getMessage(), null));
    }

    /**
     * Handle Spring validation exceptions (400).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        String errors = ex.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .collect(Collectors.joining(", "));

        log.debug("Validation error: {}", errors);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(buildErrorResponse("VALIDATION_ERROR", errors, null));
    }

    /**
     * Handle generic catalog service exceptions (500).
     */
    @ExceptionHandler(CatalogServiceException.class)
    public ResponseEntity<Map<String, Object>> handleCatalogService(CatalogServiceException ex) {
        log.error("Catalog service error: {} - code={}", ex.getMessage(), ex.getErrorCode(), ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(buildErrorResponse(ex.getErrorCode(), ex.getMessage(), null));
    }

    /**
     * Handle all other exceptions (500).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(buildErrorResponse("INTERNAL_ERROR", "An unexpected error occurred", null));
    }

    /**
     * Build a structured error response.
     */
    private Map<String, Object> buildErrorResponse(String code, String message, Map<String, Object> details) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", false);
        response.put("error", code);
        response.put("message", message);
        response.put("timestamp", Instant.now().toString());

        if (details != null && !details.isEmpty()) {
            response.put("details", details);
        }

        return response;
    }
}
