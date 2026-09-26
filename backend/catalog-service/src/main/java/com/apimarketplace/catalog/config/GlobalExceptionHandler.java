package com.apimarketplace.catalog.config;

import com.apimarketplace.catalog.service.exception.AccessDeniedException;
import com.apimarketplace.catalog.service.exception.ApiAuthenticationException;
import com.apimarketplace.catalog.service.exception.CatalogServiceException;
import com.apimarketplace.catalog.service.exception.ToolNotFoundException;
import com.apimarketplace.catalog.service.exception.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import jakarta.servlet.http.HttpServletResponse;

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
            .body(buildErrorResponse(ToolNotFoundException.ERROR_CODE, ex.getMessage(), null));
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
     * Async request timed out.
     *
     * <p>The public bundle download is the service's first
     * {@code StreamingResponseBody} endpoint, so it is the first that can reach
     * this. By the time the timeout fires the response is normally committed
     * with part of the body already sent, and answering with a 500 JSON error
     * would try to set headers on a committed response, which surfaces as an
     * unrelated Tomcat error rather than as this timeout. Log it and hand the
     * request back so the container simply completes the truncated response.
     */
    @ExceptionHandler(AsyncRequestTimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleAsyncTimeout(
            AsyncRequestTimeoutException ex, HttpServletResponse response) {
        log.warn("Async request timed out after the configured spring.mvc.async.request-timeout; " +
                "committed={}", response.isCommitted());
        if (response.isCommitted()) {
            return null;
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(buildErrorResponse("ASYNC_TIMEOUT", "The request took too long to complete", null));
    }

    /**
     * A request body the converter cannot parse (malformed JSON, a truncated payload, a
     * value its field cannot hold): 400. Named explicitly because, unlike the framework
     * exceptions the catch-all's ErrorResponse branch covers, it does not carry a status.
     * A SERVER-side mapping defect (our own target type cannot be built) arrives as the
     * parent HttpMessageConversionException instead and still reaches the catch-all as 500.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        log.info("Unreadable request body: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(buildErrorResponse("BAD_REQUEST", "Request body could not be parsed", null));
    }

    /**
     * A path variable or parameter of the wrong shape (e.g. a non-UUID where a UUID is
     * expected). The caller's mistake: 400, not the catch-all's 500.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.info("Invalid value for parameter '{}'", ex.getName());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(buildErrorResponse("INVALID_PARAMETER",
                "Invalid value for parameter '" + ex.getName() + "': expected "
                    + (ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "a valid format"),
                null));
    }

    /**
     * Client disconnected before the response was written ("ServletOutputStream failed
     * to write", "disconnected client"): the caller hung up, typically a closed tab or a
     * timed-out upstream. Nothing can be sent on a closed connection, so no body is
     * returned; logged at WARN because it is not a server fault. Same handling as
     * agent-service.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(AsyncRequestNotUsableException ex) {
        log.warn("Client disconnected before response could be written: {}", ex.getMessage());
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
                    .body(buildErrorResponse(code, message, null));
        }
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
