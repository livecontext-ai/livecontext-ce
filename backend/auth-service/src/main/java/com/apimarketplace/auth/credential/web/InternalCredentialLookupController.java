package com.apimarketplace.auth.credential.web;

import com.apimarketplace.auth.credential.domain.CredentialModels.*;
import com.apimarketplace.auth.credential.repository.CredentialRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

/**
 * Internal API for credential lookup operations.
 * Called by orchestrator-service via CredentialClient.
 */
@RestController
@RequestMapping("/api/internal/credentials")
public class InternalCredentialLookupController {

    private final CredentialRepository credentialRepository;

    public InternalCredentialLookupController(CredentialRepository credentialRepository) {
        this.credentialRepository = credentialRepository;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Credential> getCredentialById(
            @PathVariable Long id,
            @RequestParam String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        Optional<Credential> credential = credentialRepository.findById(id);
        // Org-aware: accept when the caller owns the row OR it belongs to the caller's
        // active workspace (mirrors InternalCredentialService.findActiveCredentialById).
        if (credential.isPresent() && matchesOwnerOrOrg(credential.get(), userId, organizationId)) {
            return ResponseEntity.ok(credential.get());
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/default")
    public ResponseEntity<Credential> getDefaultCredential(
            @RequestParam String userId,
            @RequestParam String integration,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        // The executing user's own default wins; otherwise fall back to the
        // workspace-shared credential for this integration (is_default first). This
        // makes execution nodes that resolve by integration (SSH/SFTP/Database/SMTP,
        // catalog execute, LLM keys) honor workspace-shared credentials, matching the
        // name-based resolution fix in InternalCredentialService.
        Optional<Credential> credential = credentialRepository.findDefaultByTenantIdAndIntegration(userId, integration);
        if (credential.isEmpty() && organizationId != null && !organizationId.isBlank()) {
            credential = credentialRepository.findByScopeAndIntegration(userId, organizationId, integration)
                    .stream()
                    .filter(c -> c.status() == CredentialStatus.active)
                    .findFirst();
        }
        return credential.map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/all")
    public ResponseEntity<List<Credential>> getAllCredentials(
            @RequestParam String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        // Org-aware: when an active workspace is supplied, return every credential in
        // that workspace (post-V261 each row carries the workspace org_id, including the
        // user's own), so agent/builder credential pickers see workspace-shared rows.
        // Falls back to tenant scope when no org context is present (back-compat).
        List<Credential> credentials = (organizationId != null && !organizationId.isBlank())
                ? credentialRepository.findByOrganizationIdStrict(organizationId, 1, 10_000)
                : credentialRepository.findAllByTenantId(userId);
        return ResponseEntity.ok(credentials);
    }

    private static boolean matchesOwnerOrOrg(Credential c, String userId, String organizationId) {
        if (userId != null && userId.equals(c.tenantId())) {
            return true;
        }
        return organizationId != null && !organizationId.isBlank()
                && organizationId.equals(c.organizationId());
    }
}
