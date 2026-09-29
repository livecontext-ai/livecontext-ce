package com.apimarketplace.orchestrator.controllers.internal;

import com.apimarketplace.orchestrator.services.SharedApplicationScopeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Share-link scope checks for services that serve rows of an application they do not own
 * (interface-service for interface definitions, storage-service for files). Called with the
 * owner identity and the shared publication id the edge injected; answers {@code {allowed}}.
 * Same network posture as the sibling {@code /api/internal/orchestrator/**} endpoints.
 */
@RestController
@RequestMapping("/api/internal/orchestrator/share-scope")
public class InternalSharedApplicationScopeController {

    private final SharedApplicationScopeService scopeService;

    public InternalSharedApplicationScopeController(SharedApplicationScopeService scopeService) {
        this.scopeService = scopeService;
    }

    /**
     * No tenant parameter on purpose: every workflow row carries its workspace id, and the lookup
     * behind this is org-strict ({@code organization_id = :organizationId}), so the workspace IS
     * the scope. A missing workspace id is refused.
     */
    @GetMapping("/interfaces/{interfaceId}")
    public ResponseEntity<Map<String, Object>> interfaceInScope(
            @PathVariable UUID interfaceId,
            @RequestParam UUID publicationId,
            @RequestParam(required = false) String organizationId) {
        boolean allowed = scopeService.interfaceBelongsToApplication(publicationId, organizationId, interfaceId);
        return ResponseEntity.ok(Map.of("allowed", allowed));
    }

    @GetMapping("/files")
    public ResponseEntity<Map<String, Object>> fileInScope(
            @RequestParam UUID publicationId,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String organizationId,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) String workflowId,
            @RequestParam(required = false) String fileId) {
        boolean allowed = scopeService.fileBelongsToApplication(
                publicationId, tenantId, organizationId, runId, workflowId, parseUuid(fileId));
        return ResponseEntity.ok(Map.of("allowed", allowed));
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
