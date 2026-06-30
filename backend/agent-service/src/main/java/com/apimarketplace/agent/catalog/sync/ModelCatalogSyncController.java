package com.apimarketplace.agent.catalog.sync;

import com.apimarketplace.common.web.AdminRoleGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * REST surface for the live catalog sync (LiteLLM + OpenRouter).
 *
 * <p>Admin-only. Never exposed to CE - the sync runs cloud-side, and the
 * bundle flow (see {@code CatalogBundleController}) is what distributes the
 * resulting catalog to CE instances.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/model-config/catalog-sync?mode=dry-run} - fetch,
 *       parse, run guards, compute diff, return plan. No DB writes on
 *       {@code model_config_overrides} (only a row in
 *       {@code model_catalog_sync_log}).</li>
 *   <li>{@code POST /api/model-config/catalog-sync?mode=apply} - same, then
 *       apply via {@link com.apimarketplace.agent.catalog.bundle.CatalogMergeService}.
 *       Refuses to apply if any guard fails unless
 *       {@code overrideGuards=<guard-name>,...} explicitly lists them.</li>
 * </ul>
 *
 * <p>Behaviour when guards fail without override: returns HTTP 412
 * Precondition Failed with the plan attached so the admin UI can render the
 * flagged rows and prompt for explicit confirmation.
 */
@Slf4j
@RestController
@RequestMapping("/api/model-config")
@RequiredArgsConstructor
public class ModelCatalogSyncController {

    private final ModelCatalogSyncService syncService;

    /** Request-to-service shape. */
    @PostMapping("/catalog-sync")
    public ResponseEntity<?> sync(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestHeader(value = "X-User-ID", defaultValue = "admin") String actor,
            @RequestParam(value = "mode", defaultValue = "dry-run") String mode,
            @RequestParam(value = "overrideGuards", required = false) String overrideGuardsCsv) {

        ResponseEntity<?> denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        boolean dryRun = switch (mode) {
            case "dry-run"   -> true;
            case "apply"     -> false;
            default          -> true;  // safest default
        };

        Set<String> overrides = parseCsv(overrideGuardsCsv);

        ModelCatalogSyncService.SyncRequest req = dryRun
                ? ModelCatalogSyncService.SyncRequest.dryRun(actor)
                : ModelCatalogSyncService.SyncRequest.apply(actor, overrides);

        ModelCatalogSyncService.SyncResult result;
        try {
            result = syncService.sync(req);
        } catch (Exception e) {
            log.error("catalog-sync failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "sync failed: " + e.getMessage()));
        }

        // 412 when guards fired and apply was requested (dry-run always 200).
        boolean guardFired = !result.plan().guardFailures().isEmpty();
        if (!dryRun && guardFired) {
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).body(result);
        }

        return ResponseEntity.ok(result);
    }

    /** History tab backing endpoint - last N sync attempts (any outcome). */
    @GetMapping("/catalog-sync/history")
    public ResponseEntity<?> history(
            @RequestHeader(value = "X-User-Roles", defaultValue = "USER") String roles,
            @RequestParam(value = "limit", defaultValue = "25") int limit) {
        ResponseEntity<?> denied = AdminRoleGuard.denyIfNotAdmin(roles);
        if (denied != null) return denied;

        // Cap limit to avoid the UI pulling thousands of rows accidentally.
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return ResponseEntity.ok(syncService.recentHistory(safeLimit));
    }

    private static Set<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptySet();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
