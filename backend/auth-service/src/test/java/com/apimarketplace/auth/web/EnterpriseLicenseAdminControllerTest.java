package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.license.EnterpriseLicenseService;
import com.apimarketplace.auth.service.license.EnterpriseLicenseStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EnterpriseLicenseAdminControllerTest {

    private final EnterpriseLicenseService licenseService = mock(EnterpriseLicenseService.class);
    private final EnterpriseLicenseAdminController controller = new EnterpriseLicenseAdminController(licenseService);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void deniesNonAdminWithoutReadingLicense() {
        ResponseEntity<Map<String, Object>> response = controller.status("USER");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "Admin access required");
        verifyNoInteractions(licenseService);
    }

    @Test
    void returnsRedactedActiveLicenseStatusForAdmin() {
        when(licenseService.currentStatus()).thenReturn(activeStatus());

        ResponseEntity<Map<String, Object>> response = controller.status("USER,ADMIN");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).containsEntry("active", true)
                .containsEntry("reason", "active")
                .containsEntry("licenseId", "lic-test")
                .containsEntry("customerName", "Example Corp")
                .containsEntry("planCode", EnterpriseLicenseService.SELF_HOSTED_PLAN_CODE)
                .containsEntry("validUntil", Instant.parse("2027-01-01T00:00:00Z"));
        assertThat(body).doesNotContainKeys("payload", "signature", "entitlements");
        @SuppressWarnings("unchecked")
        List<String> entitlementKeys = (List<String>) body.get("entitlementKeys");
        assertThat(entitlementKeys)
                .containsExactly(
                        "compliance.hipaa_mode",
                        "resources.agent.max",
                        "resources.application.max",
                        "resources.datasource.max",
                        "resources.workflow.max",
                        "sso.saml");
        @SuppressWarnings("unchecked")
        Map<String, Object> resourceLimits = (Map<String, Object>) body.get("resourceLimits");
        assertThat(resourceLimits)
                .containsEntry("workflows", 25)
                .containsEntry("agents", null)
                .containsEntry("datasources", 10)
                .containsEntry("interfaces", 0)
                .containsEntry("applications", 3);
    }

    @Test
    void returnsInactiveStatusWithFailClosedResourceLimits() {
        when(licenseService.currentStatus()).thenReturn(EnterpriseLicenseStatus.inactive("license_expired"));

        ResponseEntity<Map<String, Object>> response = controller.status("ADMIN");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("active", false)
                .containsEntry("reason", "license_expired")
                .containsEntry("planCode", EnterpriseLicenseService.NO_LICENSE_PLAN_CODE);
        @SuppressWarnings("unchecked")
        List<String> entitlementKeys = (List<String>) response.getBody().get("entitlementKeys");
        @SuppressWarnings("unchecked")
        Map<String, Object> resourceLimits = (Map<String, Object>) response.getBody().get("resourceLimits");
        assertThat(entitlementKeys).isEmpty();
        assertThat(resourceLimits)
                .containsEntry("workflows", 0)
                .containsEntry("agents", 0)
                .containsEntry("datasources", 0)
                .containsEntry("interfaces", 0)
                .containsEntry("applications", 0);
    }

    private EnterpriseLicenseStatus activeStatus() {
        ObjectNode entitlements = objectMapper.createObjectNode();
        entitlements.put("resources.workflow.max", 25);
        entitlements.putNull("resources.agent.max");
        entitlements.put("resources.datasource.max", 10);
        entitlements.put("resources.application.max", 3);
        entitlements.put("sso.saml", true);
        entitlements.put("compliance.hipaa_mode", true);

        JsonNode entitlementNode = entitlements;
        return new EnterpriseLicenseStatus(
                true,
                "active",
                "lic-test",
                "Example Corp",
                EnterpriseLicenseService.SELF_HOSTED_PLAN_CODE,
                Instant.parse("2027-01-01T00:00:00Z"),
                entitlementNode);
    }
}
