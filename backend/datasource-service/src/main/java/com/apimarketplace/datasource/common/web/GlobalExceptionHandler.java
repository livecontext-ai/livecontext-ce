package com.apimarketplace.datasource.common.web;

import com.apimarketplace.auth.client.access.OrgAccessDeniedException;
import com.apimarketplace.common.web.TenantRequiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /**
     * A request body the converter cannot parse (malformed JSON, a truncated payload, a
     * value its field cannot hold): 400. Named explicitly because, unlike the framework
     * exceptions the catch-all's ErrorResponse branch covers, it does not carry a status.
     * A SERVER-side mapping defect (our own target type cannot be built) arrives as the
     * parent HttpMessageConversionException instead and still reaches the catch-all as 500.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        logger.info("Unreadable request body: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(errorResponse("BAD_REQUEST", "Request body could not be parsed"));
    }

    /**
     * A path variable or parameter of the wrong shape (e.g. a non-UUID id): 400, not 500.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        logger.info("Invalid value for parameter '{}'", ex.getName());
        return ResponseEntity.badRequest()
                .body(errorResponse("INVALID_PARAMETER",
                        "Invalid value for parameter '" + ex.getName() + "': expected "
                                + (ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "a valid format")));
    }

    /**
     * Client disconnected before the response was written. Nothing can be sent on a
     * closed connection; logged at WARN because it is not a server fault.
     */
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void handleClientDisconnect(AsyncRequestNotUsableException ex) {
        logger.warn("Client disconnected before response could be written: {}", ex.getMessage());
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
                logger.error("Unhandled exception ({}): {}", status.value(), ex.getMessage(), ex);
            } else {
                logger.info("Request refused with {}: {}", status.value(), ex.getMessage());
            }
            HttpStatus resolved = HttpStatus.resolve(status.value());
            String code = resolved != null ? resolved.name() : "HTTP_" + status.value();
            String detail = er.getBody().getDetail();
            String message = detail != null && !detail.isBlank() ? detail
                    : (resolved != null ? resolved.getReasonPhrase() : "Request refused");
            return ResponseEntity.status(status).headers(er.getHeaders())
                    .body(errorResponse(code, message));
        }
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
