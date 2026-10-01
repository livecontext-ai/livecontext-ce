package com.apimarketplace.auth.web;

import com.apimarketplace.auth.audit.AuditEventTypes;
import com.apimarketplace.auth.audit.AuditLogger;
import com.apimarketplace.auth.service.PersonalOfferAdminService;
import com.apimarketplace.auth.service.PersonalOfferAdminService.PolicyInput;
import com.apimarketplace.common.web.AdminRoleGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.function.Supplier;

/** Shares the existing authenticated admin-credits gateway route and audit logger. */
@RestController
@RequestMapping("/api/admin/credits/offers")
@ConditionalOnProperty(name = "billing.provider", havingValue = "stripe")
public class AdminPersonalOfferController {
    private final PersonalOfferAdminService service;
    private final AuditLogger audit;
    private final boolean unlimited;

    public AdminPersonalOfferController(PersonalOfferAdminService service, AuditLogger audit,
                                        @Value("${credit.unlimited:false}") boolean unlimited) {
        this.service = service;
        this.audit = audit;
        this.unlimited = unlimited;
    }

    @GetMapping("/policies")
    public ResponseEntity<Map<String, Object>> policies(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = gate(roles);
        return denied != null ? denied : ResponseEntity.ok(Map.of("policies", service.list()));
    }

    @PostMapping("/policies")
    public ResponseEntity<Map<String, Object>> create(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestHeader("X-User-ID") Long adminId, @RequestBody(required = false) PolicyInput input) {
        return mutate(roles, adminId, "create", null, () -> Map.of("policy", service.create(input)));
    }

    @PutMapping("/policies/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestHeader("X-User-ID") Long adminId, @PathVariable("id") Long id,
            @RequestBody(required = false) PolicyInput input) {
        return mutate(roles, adminId, "update", id, () -> Map.of("policy", service.update(id, input)));
    }

    @PostMapping("/policies/{id}/activate")
    public ResponseEntity<Map<String, Object>> activate(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestHeader("X-User-ID") Long adminId, @PathVariable("id") Long id) {
        return mutate(roles, adminId, "activate", id, () -> Map.of("policy", service.activate(id)));
    }

    @PostMapping("/policies/{id}/pause")
    public ResponseEntity<Map<String, Object>> pause(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestHeader("X-User-ID") Long adminId, @PathVariable("id") Long id) {
        return mutate(roles, adminId, "pause", id, () -> Map.of("policy", service.pause(id)));
    }

    @GetMapping("/codes")
    public ResponseEntity<Map<String, Object>> codes(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        var denied = gate(roles);
        return denied != null ? denied : ResponseEntity.ok(Map.of("codes", service.codes()));
    }

    @PostMapping("/codes/{id}/disable")
    public ResponseEntity<Map<String, Object>> disable(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestHeader("X-User-ID") Long adminId, @PathVariable("id") Long id) {
        return mutate(roles, adminId, "disable_code", id, () -> {
            service.disableCode(id);
            return Map.of("success", true);
        });
    }

    private ResponseEntity<Map<String, Object>> gate(String roles) {
        var denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;
        return unlimited ? ResponseEntity.status(503).body(Map.of("error", "not_available_in_ce")) : null;
    }

    private ResponseEntity<Map<String, Object>> mutate(String roles, Long adminId, String action, Long targetId,
                                                       Supplier<Map<String, Object>> operation) {
        var denied = gate(roles);
        if (denied != null) return denied;
        var event = audit.event(AuditEventTypes.ADMIN_ACTION).user(adminId)
                .detail("feature", "personal_offer").detail("action", action);
        if (targetId != null) event.detail("target_id", targetId);
        try {
            Map<String, Object> result = operation.get();
            if (result.get("policy") instanceof PersonalOfferAdminService.PolicyView policy)
                event.detail("policy_id", policy.id()).detail("version", policy.version());
            event.success().write();
            return ResponseEntity.ok(result);
        } catch (PersonalOfferAdminService.InvalidPolicy invalid) {
            event.failure(invalid.getMessage()).write();
            return ResponseEntity.badRequest().body(Map.of("error", invalid.getMessage()));
        } catch (DataIntegrityViolationException conflict) {
            event.failure("OFFER_POLICY_CONFLICT").write();
            return ResponseEntity.status(409).body(Map.of("error", "OFFER_POLICY_CONFLICT"));
        }
    }
}
