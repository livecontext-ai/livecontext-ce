package com.apimarketplace.orchestrator.controllers.interfaces;

import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.services.InterfaceRenderService;
import com.apimarketplace.orchestrator.services.InterfaceRenderService.InterfaceRenderResult;
import com.apimarketplace.orchestrator.services.InterfaceRenderService.SingleItemResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for Interface render/runtime operations.
 * CRUD and snapshot operations are handled by interface-service via gateway routing.
 * This controller only handles render endpoints that need execution engine data.
 */
@RestController
@RequestMapping("/api/interfaces")
public class InterfaceController {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final InterfaceRenderService interfaceRenderService;
    private final TenantResolver tenantResolver;

    public InterfaceController(
            InterfaceRenderService interfaceRenderService,
            TenantResolver tenantResolver) {
        this.interfaceRenderService = interfaceRenderService;
        this.tenantResolver = tenantResolver;
    }

    /**
     * Render an interface for a specific workflow run with pagination.
     *
     * <p>Phase 6c (2026-05-19) - added run-ownership gate before render
     * (mirrors the items-count fix from Phase 5c). A caller in TenantA
     * passing a runId owned by TenantB now gets 404 instead of the
     * actual rendered content. Cross-tenant scenarios (marketplace
     * preview, screenshot worker, runtime InterfaceNode) go through the
     * /showcase-render path and InterfaceRenderService directly, NOT
     * this controller.
     */
    @GetMapping("/{id}/render")
    public ResponseEntity<InterfaceRenderResult> renderInterface(
            @PathVariable("id") UUID id,
            @RequestParam("runId") String runId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "10") int size,
            @RequestParam(value = "epoch", required = false) Integer epoch,
            @RequestParam(value = "variablePages", required = false) String variablePagesJson,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        String organizationId = request.getHeader("X-Organization-ID");
        if (!interfaceRenderService.callerCanAccessRun(runId, tenantId, organizationId)) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Integer> variablePages = parseVariablePages(variablePagesJson);
        InterfaceRenderResult result = interfaceRenderService.render(id, runId, tenantId, page, size, epoch, variablePages);
        return ResponseEntity.ok(result);
    }

    /**
     * Get a single item's resolved data for an interface in a workflow run.
     *
     * <p>Phase 6c (2026-05-19) - added run-ownership gate.
     */
    @GetMapping("/{id}/items/{itemIndex}")
    public ResponseEntity<SingleItemResult> getInterfaceItem(
            @PathVariable("id") UUID id,
            @PathVariable("itemIndex") int itemIndex,
            @RequestParam("runId") String runId,
            @RequestParam(value = "epoch", defaultValue = "0") int epoch,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        String organizationId = request.getHeader("X-Organization-ID");
        if (!interfaceRenderService.callerCanAccessRun(runId, tenantId, organizationId)) {
            return ResponseEntity.notFound().build();
        }
        return interfaceRenderService.renderItem(id, runId, tenantId, epoch, itemIndex)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Get run-info metadata for an interface (template, config, resolved indices).
     *
     * <p>Phase 6c (2026-05-19) - added run-ownership gate.
     */
    @GetMapping("/{id}/run-info")
    public ResponseEntity<Map<String, Object>> getInterfaceRunInfo(
            @PathVariable("id") UUID id,
            @RequestParam("runId") String runId,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        String organizationId = request.getHeader("X-Organization-ID");
        if (!interfaceRenderService.callerCanAccessRun(runId, tenantId, organizationId)) {
            return ResponseEntity.notFound().build();
        }
        return interfaceRenderService.getRunInfo(id, runId, tenantId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Get the count of items for an interface in a specific workflow run.
     *
     * <p>2026-05-18 - added tenantId resolution + null guard. The render
     * service's overload below performs the (interface, run) lookup with
     * tenant scope; the prior 2-arg countItems(id, runId) returned counts
     * for ANY UUID-guessable pair regardless of caller identity.
     */
    @GetMapping("/{id}/items-count")
    public ResponseEntity<Long> getItemsCount(
            @PathVariable("id") UUID id,
            @RequestParam("runId") String runId,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        if (tenantId == null || tenantId.isBlank()) {
            return ResponseEntity.status(401).build();
        }
        String organizationId = request.getHeader("X-Organization-ID");
        long count = interfaceRenderService.countItems(id, runId, tenantId, organizationId);
        return ResponseEntity.ok(count);
    }

    /**
     * Get available functions for the interface expression engine.
     */
    @GetMapping("/expression-functions")
    public ResponseEntity<Map<String, String>> getAvailableFunctions() {
        Map<String, String> functions = interfaceRenderService.getAvailableFunctions();
        return ResponseEntity.ok(functions);
    }

    /**
     * Render an interface using its connected datasource.
     *
     * <p>Phase 6c (2026-05-19) - added interface-ownership gate. The
     * legacy {@link InterfaceRenderService#renderWithDatasource} passes
     * {@code null} tenantId to {@code interfaceClient.getInterface} for
     * the runtime path; the controller now verifies caller ownership
     * before delegating so a UUID-guessed interface from another tenant
     * returns 404 (not its template/css/js).
     */
    @GetMapping("/{id}/render-datasource")
    public ResponseEntity<InterfaceRenderResult> renderInterfaceWithDatasource(
            @PathVariable("id") UUID id,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "0") int size,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        if (!interfaceRenderService.callerOwnsInterface(id, tenantId)) {
            return ResponseEntity.notFound().build();
        }
        InterfaceRenderResult result = interfaceRenderService.renderWithDatasource(id, tenantId, page, size);
        return ResponseEntity.ok(result);
    }

    /**
     * Get the count of items in the datasource connected to an interface.
     *
     * <p>Phase 6c (2026-05-19) - added interface-ownership gate (same
     * shape as /render-datasource above). UUID-guess from another tenant
     * now returns 404 instead of leaking the row count.
     */
    @GetMapping("/{id}/datasource-items-count")
    public ResponseEntity<Long> getDatasourceItemsCount(
            @PathVariable("id") UUID id,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        if (!interfaceRenderService.callerOwnsInterface(id, tenantId)) {
            return ResponseEntity.notFound().build();
        }
        long count = interfaceRenderService.countDatasourceItems(id, tenantId);
        return ResponseEntity.ok(count);
    }

    /**
     * Parse the variablePages JSON param: {"rows": 2, "contacts": 0} → Map.
     * Returns empty map on null/blank/parse-error (graceful degradation to standard path).
     */
    private static Map<String, Integer> parseVariablePages(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<Map<String, Integer>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
