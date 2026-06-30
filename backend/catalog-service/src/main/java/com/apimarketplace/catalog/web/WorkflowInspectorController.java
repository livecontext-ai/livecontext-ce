package com.apimarketplace.catalog.web;

import com.apimarketplace.catalog.dto.ToolBatchRequest;
import com.apimarketplace.catalog.dto.WorkflowApiDTO;
import com.apimarketplace.catalog.dto.WorkflowToolDTO;
import com.apimarketplace.catalog.dto.WorkflowToolDetailDTO;
import com.apimarketplace.catalog.service.WorkflowInspectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Contrôleur spécialisé pour l'extraction optimisée des données API/Tool pour le workflow inspector
 * Ne retourne que les champs nécessaires à l'affichage, sans construire d'objets complets
 */
@RestController
@RequestMapping("/api/workflow-inspector")
@RequiredArgsConstructor
@Slf4j
public class WorkflowInspectorController {

    private final WorkflowInspectorService workflowInspectorService;

    /**
     * Récupère toutes les APIs avec leurs tools (uniquement les champs nécessaires)
     * GET /api/workflow-inspector/apis
     * 
     * Query parameters:
     * - page: numéro de page (défaut: 0)
     * - size: taille de page (défaut: 100)
     * - name: filtre par nom d'API (optionnel)
     */
    @GetMapping("/apis")
    public ResponseEntity<Map<String, Object>> getApisForWorkflow(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "100") int size,
            @RequestParam(value = "name", required = false) String nameFilter) {
        try {
            log.info("Fetching APIs for workflow inspector - page: {}, size: {}, nameFilter: {}", 
                    page, size, nameFilter);
            
            List<WorkflowApiDTO> allApis = workflowInspectorService.getAllApisForWorkflow(nameFilter);
            log.info("Total APIs returned from service: {}", allApis.size());
            
            // Calculer la pagination
            int total = allApis.size();
            int totalPages = total > 0 ? (int) Math.ceil((double) total / size) : 0;
            int start = page * size;
            int end = Math.min(start + size, total);
            
            // Obtenir les APIs paginées (gérer le cas où la liste est vide)
            List<WorkflowApiDTO> paginatedApis;
            if (total == 0 || start >= total) {
                paginatedApis = new java.util.ArrayList<>();
            } else {
                paginatedApis = allApis.subList(start, end);
            }
            log.info("Paginated APIs: {} (start: {}, end: {})", paginatedApis.size(), start, end);
            
            // Construire la réponse paginée
            Map<String, Object> response = new java.util.HashMap<>();
            response.put("content", paginatedApis);
            response.put("totalElements", total);
            response.put("totalPages", totalPages);
            response.put("page", page);
            response.put("size", size);
            response.put("first", page == 0);
            response.put("last", page >= totalPages - 1);
            response.put("numberOfElements", paginatedApis.size());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error fetching APIs for workflow inspector: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Récupère une API spécifique par son slug (uniquement les champs nécessaires)
     * GET /api/workflow-inspector/apis/{apiSlug}
     */
    @GetMapping("/apis/{apiSlug}")
    public ResponseEntity<WorkflowApiDTO> getApiBySlug(@PathVariable String apiSlug) {
        try {
            log.info("Fetching API by slug: {}", apiSlug);
            return workflowInspectorService.getApiBySlug(apiSlug)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("Error fetching API by slug {}: {}", apiSlug, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Récupère les tools d'une API spécifique par son slug (uniquement les champs nécessaires)
     * GET /api/workflow-inspector/apis/{apiSlug}/tools
     */
    @GetMapping("/apis/{apiSlug}/tools")
    public ResponseEntity<List<WorkflowToolDTO>> getToolsForApi(@PathVariable String apiSlug) {
        try {
            log.info("Fetching tools for API slug: {}", apiSlug);
            List<WorkflowToolDTO> tools = workflowInspectorService.getToolsForApi(apiSlug);
            return ResponseEntity.ok(tools);
        } catch (Exception e) {
            log.error("Error fetching tools for API slug {}: {}", apiSlug, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Batch-resolve light {@link WorkflowToolDTO} records by {@code api_tools.id} UUIDs.
     * GET /api/workflow-inspector/tools/by-ids?ids=uuid1,uuid2,...
     *
     * <p>Used by the agent-builder backend to normalise UUID tool references to slug
     * format before persistence, and by the agent-fleet frontend to render legacy
     * UUID-only entries (apiSlug + toolSlug + iconSlug resolved in one round-trip).
     * Unknown or malformed UUIDs are silently dropped - clients detect missing items
     * by size or by per-UUID lookup.
     */
    @GetMapping("/tools/by-ids")
    public ResponseEntity<List<WorkflowToolDTO>> getToolsByIds(@RequestParam("ids") List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return ResponseEntity.ok(List.of());
        }
        List<java.util.UUID> uuids = new java.util.ArrayList<>(ids.size());
        for (String raw : ids) {
            if (raw == null || raw.isBlank()) continue;
            try {
                uuids.add(java.util.UUID.fromString(raw.trim()));
            } catch (IllegalArgumentException ignored) {
                // Silently skip non-UUID inputs - the endpoint is best-effort resolution,
                // not validation. Callers see missing UUIDs as missing entries in the response.
            }
        }
        try {
            return ResponseEntity.ok(workflowInspectorService.resolveToolsByIds(uuids));
        } catch (Exception e) {
            log.error("Error resolving tools by ids (count={}): {}", uuids.size(), e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Récupère un tool spécifique par son slug (uniquement les champs de base)
     * GET /api/workflow-inspector/tools/{toolSlug}
     */
    @GetMapping("/tools/{toolSlug}")
    public ResponseEntity<WorkflowToolDTO> getToolBySlug(@PathVariable String toolSlug) {
        try {
            log.info("Fetching tool by slug: {}", toolSlug);
            return workflowInspectorService.getToolBySlug(toolSlug)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("Error fetching tool by slug {}: {}", toolSlug, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Récupère les détails complets d'un tool par son slug (paramètres, réponses, credentials)
     * GET /api/workflow-inspector/tools/{toolSlug}/details
     */
    @GetMapping("/tools/{toolSlug}/details")
    public ResponseEntity<WorkflowToolDetailDTO> getToolDetailBySlug(@PathVariable String toolSlug) {
        try {
            log.info("Fetching tool details by slug: {}", toolSlug);
            return workflowInspectorService.getToolDetailBySlug(toolSlug)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("Error fetching tool details by slug {}: {}", toolSlug, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Récupère uniquement l'iconSlug d'une API par son slug (endpoint léger)
     * GET /api/workflow-inspector/apis/{apiSlug}/icon
     */
    @GetMapping("/apis/{apiSlug}/icon")
    public ResponseEntity<Map<String, String>> getApiIcon(@PathVariable String apiSlug) {
        try {
            log.info("Fetching icon for API slug: {}", apiSlug);
            return workflowInspectorService.getApiIconSlug(apiSlug)
                .map(iconSlug -> {
                    Map<String, String> response = new java.util.HashMap<>();
                    response.put("iconSlug", iconSlug);
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("Error fetching icon for API slug {}: {}", apiSlug, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Récupère l'iconSlug d'une API par le slug d'un de ses tools (endpoint léger)
     * GET /api/workflow-inspector/tools/{toolSlug}/icon
     */
    @GetMapping("/tools/{toolSlug}/icon")
    public ResponseEntity<Map<String, String>> getToolIcon(@PathVariable String toolSlug) {
        try {
            log.info("Fetching icon for tool slug: {}", toolSlug);
            return workflowInspectorService.getToolIconSlug(toolSlug)
                .map(iconSlug -> {
                    Map<String, String> response = new java.util.HashMap<>();
                    response.put("iconSlug", iconSlug);
                    return ResponseEntity.ok(response);
                })
                .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            log.error("Error fetching icon for tool slug {}: {}", toolSlug, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Batch fetch multiple tools by their slugs
     * POST /api/workflow-inspector/tools/batch
     *
     * Optimized for workflow import - reduces N+1 queries to a single batch request
     *
     * Request body:
     * {
     *   "toolSlugs": ["tool-slug-1", "tool-slug-2", ...]
     * }
     *
     * Response:
     * {
     *   "tool-slug-1": { ...WorkflowToolDetailDTO... },
     *   "tool-slug-2": { ...WorkflowToolDetailDTO... }
     * }
     */
    @PostMapping("/tools/batch")
    public ResponseEntity<Map<String, WorkflowToolDetailDTO>> getToolsBatch(@RequestBody ToolBatchRequest request) {
        try {
            if (request == null || request.toolSlugs() == null || request.toolSlugs().isEmpty()) {
                log.warn("Batch request with empty tool slugs");
                return ResponseEntity.ok(new java.util.HashMap<>());
            }

            log.info("Batch fetching {} tools", request.toolSlugs().size());
            Map<String, WorkflowToolDetailDTO> result = workflowInspectorService.getToolsBatch(request.toolSlugs());
            log.info("Batch fetch returned {} tools", result.size());

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error in batch tool fetch: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}

