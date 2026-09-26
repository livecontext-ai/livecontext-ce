package com.apimarketplace.auth.web;

import com.apimarketplace.auth.dto.LifecycleEventRequest;
import com.apimarketplace.auth.lifecycle.ExternalLifecycleEventService;
import com.apimarketplace.auth.lifecycle.LifecycleEmailService;
import com.apimarketplace.auth.lifecycle.UserLifecycleContextService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Service-to-service lifecycle signals (orchestrator -> auth-service through auth-client).
 *
 * <p>Lives under {@code /api/internal}, which the gateway does not route from the edge.
 */
@RestController
@RequestMapping("/api/internal/auth/lifecycle")
public class InternalLifecycleController {

    private final UserLifecycleContextService lifecycleContextService;
    private final ExternalLifecycleEventService externalEvents;

    public InternalLifecycleController(UserLifecycleContextService lifecycleContextService,
                                       ExternalLifecycleEventService externalEvents) {
        this.lifecycleContextService = lifecycleContextService;
        this.externalEvents = externalEvents;
    }

    /**
     * The user just created a workflow. Idempotent: only the first call ever stamps the
     * activation and emits {@code user.activated}; every call answers 204.
     */
    @PostMapping("/activation")
    public ResponseEntity<Void> activation(@RequestHeader(value = "X-User-ID", required = false) String userIdHeader) {
        Optional<Long> userId = lifecycleContextService.resolveUserId(userIdHeader);
        if (userId.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        lifecycleContextService.recordActivation(userId.get());
        return ResponseEntity.noContent().build();
    }

    /**
     * One lifecycle event sent by another service: {@code badge.unlocked} or
     * {@code recap.monthly}, nothing else. 202 when queued, 204 when nothing was sent because
     * the lifecycle emails are off (CE, no key, kill switch: a sender that records "sent" must
     * not record it), 400 for an event or payload off the allow-list, 404 for an unknown user,
     * 503 when the Resend queue has no room right now (the caller retries later).
     */
    @PostMapping("/events")
    public ResponseEntity<Void> event(@RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
                                      @RequestBody(required = false) LifecycleEventRequest request) {
        Optional<Long> userId = lifecycleContextService.resolveUserId(userIdHeader);
        if (userId.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        if (request == null) {
            return ResponseEntity.badRequest().build();
        }
        LifecycleEmailService.Dispatch dispatch;
        try {
            dispatch = externalEvents.accept(userId.get(), request.event(), request.payload());
        } catch (IllegalArgumentException refused) {
            return ResponseEntity.badRequest().build();
        }
        return switch (dispatch) {
            case BUSY -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            case INACTIVE -> ResponseEntity.noContent().build();
            case QUEUED -> ResponseEntity.accepted().build();
        };
    }
}
