package com.apimarketplace.orchestrator.common.web;

import com.apimarketplace.common.web.TenantRequiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
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
     * Handle a request body sent with a Content-Type no converter can read (e.g. a JSON
     * payload labelled {@code text/plain}). Returns 415 with an {@code Accept} header
     * listing what the endpoint does read, instead of falling through to the generic 500.
     *
     * <p>Without this, the public webhook endpoint answered <b>500</b> to a caller whose only
     * mistake was the header - indistinguishable, to that caller, from "the workflow blew up",
     * so a well-behaved sender retries a request that can never succeed. 415 is the status
     * that tells it to stop and fix the header. Mirrors the 404/405 handlers above.
     *
     * @see #handleMessageNotReadable the sibling case, a body the converter cannot parse
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        logger.info("Unsupported media type: {}", ex.getMessage());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        // isEmpty, not a null check: HttpMediaTypeException always stores an unmodifiable list
        // (empty for the message-only constructor), so null is unreachable here - unlike the 405
        // handler above, whose getSupportedHttpMethods() genuinely can be null. Emitting an
        // empty Accept header would be worse than omitting it: it advertises "nothing accepted".
        if (!ex.getSupportedMediaTypes().isEmpty()) {
            builder.header(HttpHeaders.ACCEPT, MediaType.toString(ex.getSupportedMediaTypes()));
        }
        return builder.body(errorResponse("UNSUPPORTED_MEDIA_TYPE",
                "Request Content-Type is not supported by this endpoint"));
    }

    /**
     * Handle a request body the converter cannot parse (malformed JSON, a truncated payload,
     * a type the target field cannot accept). Returns 400.
     *
     * <p>Same defect as the 415 above and arguably the commoner one on a public webhook: the
     * sender got the header right and the body wrong. It fell through to the generic 500 too,
     * which tells a retrying sender to keep sending a body that can never parse. Fixing only
     * the media-type half would have left the same trap one keystroke away.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        // Blanket 400 is safe HERE, and only because Spring has already split the two cases for
        // us. A SERVER-side mapping defect (Jackson's InvalidDefinitionException: our own target
        // type cannot be constructed) is wrapped in the PARENT HttpMessageConversionException,
        // which this handler does not match and which therefore still reaches handleGeneric as a
        // 500 with its stack trace. Only a genuinely unreadable payload arrives as this subclass.
        // Verified by dispatch, not by reading: see RealDispatch#serverSideMappingDefectStays500.
        // INFO without a stack is deliberate - the caller's malformed body is not our incident.
        logger.info("Unreadable request body: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(errorResponse("MALFORMED_REQUEST_BODY", "Request body could not be parsed"));
    }

    /**
     * Handle a required query parameter the caller did not send. Returns 400 naming it.
     *
     * <p>Completes the family this file already half-covered: {@link MissingRequestHeaderException}
     * (a missing header) was handled at the top while its sibling, a missing parameter, still fell
     * to the generic 500. Both are the caller omitting a required input.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingRequestParameter(
            MissingServletRequestParameterException ex) {
        logger.info("Missing request parameter: {}", ex.getParameterName());
        return ResponseEntity.badRequest()
                .body(errorResponse("MISSING_PARAMETER",
                        "Required parameter '" + ex.getParameterName() + "' is missing"));
    }

    /**
     * Handle an {@code Accept} header no producible representation satisfies. Returns 406.
     *
     * <p><b>Unlike the three siblings above, this one does not change the status</b> - and saying
     * otherwise would be wrong. Their 500 came from the catch-all producing a JSON envelope the
     * container could write; here it cannot (the caller accepts nothing we produce), so Spring
     * rethrows and {@code DefaultHandlerExceptionResolver} already answered 406. What this
     * handler buys is the log: the catch-all recorded every 406 as an ERROR with a stack trace,
     * i.e. a caller's Accept header filed as a server incident. That is the regression pinned by
     * the test, not the status. The body below is likewise undeliverable in the pure case and is
     * built for symmetry, and for callers that accept JSON among other types.
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Map<String, Object>> handleMediaTypeNotAcceptable(
            HttpMediaTypeNotAcceptableException ex) {
        logger.info("Not acceptable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE)
                .body(errorResponse("NOT_ACCEPTABLE",
                        "No representation matching the Accept header is available"));
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
