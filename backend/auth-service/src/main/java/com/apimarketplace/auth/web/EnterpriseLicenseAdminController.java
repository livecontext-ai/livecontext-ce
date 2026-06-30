package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.license.EnterpriseLicenseService;
import com.apimarketplace.auth.service.license.EnterpriseLicenseStatus;
import com.apimarketplace.common.web.AdminRoleGuard;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin/support read API for diagnosing the local self-hosted enterprise license.
 *
 * <p>Do not return the signed payload or signature here. Operators need status,
 * expiry, and known resource limits; the complete license file remains local
 * configuration.
 */
@RestController
@RequestMapping("/api/admin/enterprise/license")
public class EnterpriseLicenseAdminController {

    private static final Map<String, String> RESOURCE_LIMIT_KEYS = resourceLimitKeys();

    private final EnterpriseLicenseService licenseService;

    public EnterpriseLicenseAdminController(EnterpriseLicenseService licenseService) {
        this.licenseService = licenseService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles) {
        ResponseEntity<Map<String, Object>> denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) {
            return denied;
        }

        EnterpriseLicenseStatus status = licenseService.currentStatus();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active", status.active());
        body.put("reason", status.reason());
        body.put("licenseId", status.licenseId());
        body.put("customerName", status.customerName());
        body.put("planCode", status.planCode());
        body.put("validUntil", status.validUntil());
        body.put("entitlementKeys", entitlementKeys(status.entitlements()));
        body.put("resourceLimits", resourceLimits(status.entitlements()));
        return ResponseEntity.ok(body);
    }

    private static List<String> entitlementKeys(JsonNode entitlements) {
        if (entitlements == null || !entitlements.isObject()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        entitlements.fieldNames().forEachRemaining(keys::add);
        keys.sort(String::compareTo);
        return keys;
    }

    private static Map<String, Object> resourceLimits(JsonNode entitlements) {
        Map<String, Object> limits = new LinkedHashMap<>();
        if (entitlements == null || !entitlements.isObject()) {
            return limits;
        }
        RESOURCE_LIMIT_KEYS.forEach((label, key) -> {
            JsonNode value = entitlements.get(key);
            if (value == null) {
                limits.put(label, 0);
            } else if (value.isNull()) {
                limits.put(label, null);
            } else if (value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0) {
                limits.put(label, value.intValue());
            } else {
                limits.put(label, 0);
            }
        });
        return limits;
    }

    private static Map<String, String> resourceLimitKeys() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("workflows", "resources.workflow.max");
        keys.put("agents", "resources.agent.max");
        keys.put("datasources", "resources.datasource.max");
        keys.put("interfaces", "resources.interface.max");
        keys.put("applications", "resources.application.max");
        return keys;
    }
}
