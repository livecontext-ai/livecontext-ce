package com.apimarketplace.catalog.controller;

import com.apimarketplace.catalog.repository.ToolResponseRepository;
import com.apimarketplace.catalog.service.StructureSkeletonService;
import com.apimarketplace.catalog.web.CatalogAdminAccess;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/structure")
public class StructureSkeletonController {

    private final StructureSkeletonService service;
    private final CatalogAdminAccess adminAccess;

    public StructureSkeletonController(StructureSkeletonService service, CatalogAdminAccess adminAccess) {
        this.service = service;
        this.adminAccess = adminAccess;
    }

    /**
     * Recupere la racine de la structure d'une reponse
     */
    @GetMapping("/{responseId}/root")
    public ResponseEntity<List<ToolResponseRepository.StructureNode>> getRootStructure(
            @PathVariable UUID responseId) {
        return ResponseEntity.ok(service.getRootStructure(responseId));
    }

    /**
     * Recupere un sous-niveau de la structure
     * Le path doit etre fourni comme une liste de segments dans l'ordre de traversée JSONB
     * Ex: ?path=props,users,items,props,address
     */
    @GetMapping("/{responseId}/path")
    public ResponseEntity<List<ToolResponseRepository.StructureNode>> getPathStructure(
            @PathVariable UUID responseId,
            @RequestParam(required = false) List<String> path) {
        
        if (path == null || path.isEmpty()) {
            return ResponseEntity.ok(service.getRootStructure(responseId));
        }
        
        // Conversion List<String> -> String[] pour le service
        String[] pathArray = path.toArray(new String[0]);
        return ResponseEntity.ok(service.getPathStructure(responseId, pathArray));
    }

    /**
     * Get skeleton for a tool by its toolId
     * Returns the skeleton and flattened paths for SpEL mapping.
     *
     * GET /api/v1/structure/tool/{toolId}/skeleton
     *
     * <p>Cold-start contract: when no {@code tool_responses} row exists yet for
     * this tool (the tool has never been executed in this tenant), this endpoint
     * still returns {@code 200 OK} with {@code skeleton: null, paths: [], hint:
     * "..."} instead of {@code 404}. Returning 404 used to bubble up as
     * {@code TOOL_050 EXECUTION_FAILED} in the agent's "Get response schema"
     * action - agents need a soft signal ("execute the tool first") not an
     * execution error.
     */
    @GetMapping("/tool/{toolId}/skeleton")
    public ResponseEntity<java.util.Map<String, Object>> getSkeletonByToolId(
            @PathVariable UUID toolId) {
        java.util.Map<String, Object> result = service.getSkeletonByToolId(toolId);

        // Two underlying "no schema" branches converge here:
        //   (a) no tool_responses row at all → result has {error:"No response found..."}
        //   (b) row exists but skeleton is null → result has {skeleton:null, paths:[]}
        // Both are semantically the same to the agent - emit ONE shape with the same hint.
        boolean noSchema = (result.containsKey("error") && !result.containsKey("skeleton"))
                || result.get("skeleton") == null;
        if (noSchema) {
            java.util.Map<String, Object> graceful = new java.util.LinkedHashMap<>();
            graceful.put("toolId", toolId.toString());
            graceful.put("skeleton", null);
            graceful.put("paths", List.of());
            graceful.put("hint", "No response schema learned yet for this tool. " +
                    "Execute the tool at least once via catalog(action='execute'); the skeleton is " +
                    "auto-saved from the first non-empty result. " +
                    "If you have already executed it and still see this, the tool returned an " +
                    "empty payload - pass different params or check the executed result for an error.");
            return ResponseEntity.ok(graceful);
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Admin only: runs a skeleton backfill batch over the GLOBAL tool responses.
     * The route is reachable by any signed-in user through the gateway, so the
     * admin check lives here (see {@link CatalogAdminAccess}).
     */
    @PostMapping("/migrate")
    public ResponseEntity<?> triggerMigration(
            @RequestParam(defaultValue = "100") int batchSize,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = CatalogAdminAccess.ADMIN_TOKEN_HEADER, required = false) String adminToken) {
        var denied = adminAccess.denyIfNotAdmin(roles, adminToken);
        if (denied != null) return denied;
        int count = service.runMigrationBatch(batchSize);
        return ResponseEntity.ok("Migration batch completed. Processed " + count + " items.");
    }

    /**
     * Admin only: rewrites the skeleton of one GLOBAL tool response.
     */
    @PostMapping("/{responseId}/regenerate")
    public ResponseEntity<?> regenerateSkeleton(
            @PathVariable UUID responseId,
            @RequestHeader(value = "X-User-Roles", required = false) String roles,
            @RequestHeader(value = CatalogAdminAccess.ADMIN_TOKEN_HEADER, required = false) String adminToken) {
        var denied = adminAccess.denyIfNotAdmin(roles, adminToken);
        if (denied != null) return denied;
        service.generateAndSaveSkeleton(responseId);
        return ResponseEntity.ok().build();
    }
}

