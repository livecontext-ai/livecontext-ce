package com.apimarketplace.orchestrator.controllers.notification;

import com.apimarketplace.orchestrator.services.notification.delivery.NotificationPreferencesService;
import com.apimarketplace.orchestrator.services.notification.delivery.NotificationPreferencesService.PreferencesView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Where a person's notifications go, in the active workspace. Served under
 * {@code /api/notifications/**}, which the gateway already routes here.
 *
 * <p>Personal settings, so any member (viewers included) may change their own:
 * the choice only ever affects what reaches that person.
 */
@RestController
@RequestMapping("/api/notifications/preferences")
public class NotificationPreferencesController {

    /** Body of a PUT: one topic at a time, which is how the screen edits them. */
    public record UpdateRequest(String topic, String delivery) {}

    private final NotificationPreferencesService preferencesService;

    public NotificationPreferencesController(NotificationPreferencesService preferencesService) {
        this.preferencesService = preferencesService;
    }

    @GetMapping
    public ResponseEntity<?> get(@RequestHeader("X-User-ID") String tenantId,
                                 @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        if (orgId == null || orgId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "An active workspace is required."));
        }
        return ResponseEntity.ok(preferencesService.view(tenantId, orgId));
    }

    @PutMapping
    public ResponseEntity<?> update(@RequestHeader("X-User-ID") String tenantId,
                                    @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
                                    @RequestBody UpdateRequest body) {
        if (orgId == null || orgId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "An active workspace is required."));
        }
        if (body == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "A topic and a delivery are required."));
        }
        try {
            PreferencesView view = preferencesService.update(tenantId, orgId, body.topic(), body.delivery());
            return ResponseEntity.ok(view);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }
}
