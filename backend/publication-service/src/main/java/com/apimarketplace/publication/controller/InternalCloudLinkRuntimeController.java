package com.apimarketplace.publication.controller;

import com.apimarketplace.publication.service.CloudLinkService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Internal CE endpoint used by the local agent runtime to resolve the current
 * LLM source and cloud relay credentials for a tenant.
 */
@RestController
@RequestMapping("/api/internal/cloud-link")
@ConditionalOnProperty(name = "marketplace.mode", havingValue = "remote")
public class InternalCloudLinkRuntimeController {

    private final CloudLinkService cloudLinkService;

    public InternalCloudLinkRuntimeController(CloudLinkService cloudLinkService) {
        this.cloudLinkService = cloudLinkService;
    }

    @GetMapping("/source/{tenantId}")
    public ResponseEntity<Map<String, Object>> source(
            @RequestHeader("X-User-ID") Long authenticatedTenantId,
            @PathVariable Long tenantId) {
        if (!tenantId.equals(authenticatedTenantId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "TENANT_MISMATCH"));
        }
        return ResponseEntity.ok(Map.of(
                "source", cloudLinkService.getLlmSource(tenantId).name()
        ));
    }

    @GetMapping("/runtime/{tenantId}")
    public ResponseEntity<Map<String, Object>> runtime(
            @RequestHeader("X-User-ID") Long authenticatedTenantId,
            @PathVariable Long tenantId) {
        if (!tenantId.equals(authenticatedTenantId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "TENANT_MISMATCH"));
        }
        CloudLinkService.CloudRuntimeStatus status = cloudLinkService.getCloudRuntimeStatus(tenantId);
        return ResponseEntity.ok(Map.of(
                "source", status.source().name(),
                "cloudReady", status.cloudReady(),
                "accessToken", status.accessToken() == null ? "" : status.accessToken(),
                "installId", status.installId() == null ? "" : status.installId(),
                "cloudApiUrl", status.cloudApiUrl() == null ? "" : status.cloudApiUrl()
        ));
    }

    /**
     * Install-global runtime for the model-catalog bundle sync. Unlike
     * {@link #runtime} this is NOT tenant-scoped (no {@code X-User-ID} match): the
     * sync scheduler runs once per CE install, so it resolves THE active cloud link
     * rather than a specific user's. The credentials it returns are only used to
     * authenticate the bundle download, which is itself gated server-side by
     * {@code userOwnsActiveCeLink} - so an install with no registered link gets
     * {@code cloudReady=false} here and the sync simply skips.
     */
    @GetMapping("/active-runtime")
    public ResponseEntity<Map<String, Object>> activeRuntime() {
        CloudLinkService.CloudRuntimeStatus status = cloudLinkService.getActiveInstallRuntime();
        return ResponseEntity.ok(Map.of(
                "source", status.source().name(),
                "cloudReady", status.cloudReady(),
                "accessToken", status.accessToken() == null ? "" : status.accessToken(),
                "installId", status.installId() == null ? "" : status.installId(),
                "cloudApiUrl", status.cloudApiUrl() == null ? "" : status.cloudApiUrl()
        ));
    }
}
