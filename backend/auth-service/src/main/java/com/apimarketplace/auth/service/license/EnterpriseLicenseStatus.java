package com.apimarketplace.auth.service.license;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;

/**
 * Verification result for the local self-hosted enterprise license file.
 */
public record EnterpriseLicenseStatus(
        boolean active,
        String reason,
        String licenseId,
        String customerName,
        String planCode,
        Instant validUntil,
        JsonNode entitlements) {

    public static EnterpriseLicenseStatus inactive(String reason) {
        return new EnterpriseLicenseStatus(
                false,
                reason,
                null,
                null,
                EnterpriseLicenseService.NO_LICENSE_PLAN_CODE,
                null,
                JsonNodeFactory.instance.objectNode());
    }
}
