package com.apimarketplace.auth.web;

import com.apimarketplace.auth.service.WorkspacePurgeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Internal ops/maintenance hook to force the hard-purge of an ALREADY soft-deleted workspace,
 * bypassing the grace-period wait (the {@code WorkspacePurgeScheduler} normally fires on cron).
 *
 * <p>Safe by construction: {@link WorkspacePurgeService#purgeWorkspace} guards that the org is
 * soft-deleted, not personal, and not already purged - a live workspace can never be purged here.
 * Lives under {@code /api/internal/*} (service-to-service / admin only; the gateway does not
 * route this prefix from external traffic).
 */
@RestController
@RequestMapping("/api/internal/auth/workspace")
public class WorkspacePurgeInternalController {

    private final WorkspacePurgeService purgeService;

    public WorkspacePurgeInternalController(WorkspacePurgeService purgeService) {
        this.purgeService = purgeService;
    }

    @PostMapping("/{orgId}/purge")
    public ResponseEntity<Map<String, Object>> purge(@PathVariable UUID orgId) {
        boolean purged = purgeService.purgeWorkspace(orgId);
        return ResponseEntity.ok(Map.of("orgId", orgId.toString(), "purged", purged));
    }
}
