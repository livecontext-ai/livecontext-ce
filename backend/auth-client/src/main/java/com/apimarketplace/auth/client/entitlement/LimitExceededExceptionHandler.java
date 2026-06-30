package com.apimarketplace.auth.client.entitlement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global exception handler that maps {@link LimitExceededException} thrown by
 * any service controller to HTTP 409 with a stable {@link LimitExceededError}
 * JSON body.
 *
 * <p>This advice is auto-registered in services that import {@code auth-client}
 * because {@code AuthClientConfig} (Spring component-scanned by package) and
 * this class share a parent package - Spring Boot picks them up via the
 * {@code @SpringBootApplication} scan.
 *
 * <p>HTTP 409 Conflict is the correct semantic: the request itself is valid
 * (auth ok, payload ok), but the server refuses because of a state conflict
 * with the user's plan quota. This distinguishes it cleanly from 401/403
 * (auth issues) and 422 (payload validation).
 */
@RestControllerAdvice
public class LimitExceededExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(LimitExceededExceptionHandler.class);

    @ExceptionHandler(LimitExceededException.class)
    public ResponseEntity<LimitExceededError> handle(LimitExceededException ex) {
        LimitExceededError payload = ex.payload();
        log.info("Plan resource limit hit: user-facing 409 - type={} plan={} count={}/{}",
                payload.resourceType(), payload.planCode(), payload.currentCount(), payload.limit());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(payload);
    }
}
