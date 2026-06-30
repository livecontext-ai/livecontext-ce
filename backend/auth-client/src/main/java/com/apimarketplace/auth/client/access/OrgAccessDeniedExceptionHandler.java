package com.apimarketplace.auth.client.access;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Global exception handler that maps {@link OrgAccessDeniedException} thrown
 * by any service controller to HTTP 403 with a stable JSON body.
 *
 * <p>This advice is required because per-service catch-all
 * {@code @ExceptionHandler(Exception.class)} handlers (e.g. {@code orchestrator.common.web.GlobalExceptionHandler#handleGeneric})
 * would otherwise shadow the {@link org.springframework.web.bind.annotation.ResponseStatus}
 * annotation on the exception and return 500 instead of 403. By providing a
 * specific {@code @ExceptionHandler(OrgAccessDeniedException.class)} via
 * {@code @RestControllerAdvice}, Spring's {@code ExceptionHandlerMethodResolver}
 * picks this advice first (specificity beats generality).
 *
 * <p>{@link Order} is set to {@link Ordered#HIGHEST_PRECEDENCE} so that this
 * advice is consulted before any per-service {@code @RestControllerAdvice}
 * declaring a broader catch (e.g. {@code Exception.class}).
 *
 * <p>The response body intentionally omits the {@code resourceId} to avoid
 * leaking existence information. The server-side log still records it for
 * ops triage.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OrgAccessDeniedExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(OrgAccessDeniedExceptionHandler.class);

    @ExceptionHandler(OrgAccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handle(OrgAccessDeniedException ex) {
        log.warn("OrgAccess denied: type={} id={}", ex.getResourceType(), ex.getResourceId());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "success", false,
                "errorCode", "ORG_ACCESS_DENIED",
                "resourceType", ex.getResourceType(),
                "message", ex.getMessage()
        ));
    }
}
