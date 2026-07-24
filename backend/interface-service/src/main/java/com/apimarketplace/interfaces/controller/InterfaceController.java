package com.apimarketplace.interfaces.controller;

import com.apimarketplace.interfaces.client.InterfaceFormat;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.interfaces.client.dto.InterfaceSnapshotDto;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.interfaces.domain.InterfaceEntity;
import com.apimarketplace.interfaces.service.InterfaceDtoMapper;
import com.apimarketplace.interfaces.service.InterfaceService;
import com.apimarketplace.interfaces.service.InterfaceSnapshotService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public-facing REST controller for interface CRUD operations.
 * Routed through gateway with authentication.
 */
@RestController
@RequestMapping("/api/interfaces")
public class InterfaceController {

    private static final Logger log = LoggerFactory.getLogger(InterfaceController.class);

    private final InterfaceService interfaceService;
    private final InterfaceSnapshotService snapshotService;
    private final InterfaceDtoMapper mapper;
    private final TenantResolver tenantResolver;

    public InterfaceController(InterfaceService interfaceService,
                                InterfaceSnapshotService snapshotService,
                                InterfaceDtoMapper mapper,
                                TenantResolver tenantResolver) {
        this.interfaceService = interfaceService;
        this.snapshotService = snapshotService;
        this.mapper = mapper;
        this.tenantResolver = tenantResolver;
    }

    @PostMapping
    public ResponseEntity<InterfaceDto> createInterface(@RequestBody Map<String, Object> body,
                                                          HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        String orgRole = tenantResolver.resolveOrgRole(request);
        if (isViewerRole(orgRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // Parse wrong-typeable fields up front so a bad type is a clean 400 ResponseEntity
        // (portable: no dependency on any exception-mapping advice, which in the CE monolith
        // would otherwise swallow a thrown exception into a generic 500).
        Long dataSourceId;
        Boolean isPublic;
        String format;
        try {
            dataSourceId = num(body, "dataSourceId", "data_source_id");
            isPublic = bool(body, "isPublic", "is_public");
            format = format(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        InterfaceEntity created = interfaceService.createInterface(
                tenantId,
                (String) body.get("name"),
                (String) body.get("description"),
                str(body, "htmlTemplate", "html_template"),
                str(body, "cssTemplate", "css_template"),
                str(body, "jsTemplate", "js_template"),
                null, null,
                str(body, "targetTable", "target_table"),
                dataSourceId,
                isPublic,
                null,
                orgId,
                format);
        return ResponseEntity.ok(mapper.toDto(created));
    }

    @GetMapping
    public ResponseEntity<List<InterfaceDto>> listInterfaces(
            @RequestParam(value = "type", required = false) String interfaceType,
            @RequestParam(value = "excludeTableAttached", required = false) Boolean excludeTableAttached,
            HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        String orgRole = tenantResolver.resolveOrgRole(request);

        List<InterfaceEntity> interfaces;
        if (interfaceType != null && !interfaceType.isBlank()) {
            interfaces = interfaceService.listInterfacesByType(tenantId, interfaceType, orgId, orgRole);
        } else {
            interfaces = interfaceService.listInterfaces(tenantId, excludeTableAttached, orgId, orgRole);
        }
        return ResponseEntity.ok(interfaces.stream().map(mapper::toDto).toList());
    }

    /**
     * Paged, DB-searchable, server-sorted + server-visibility-filtered list. Returns an envelope:
     * {@code { items: InterfaceDto[], totalCount, page, size, publicationStatuses }}.
     * {@code publicationStatuses} maps each shared id on the page to {@code {status, rejectionReason?}}
     * (absent = not shared), batched server-side so the card needs no per-row publication call.
     * Search (`q`) matches name + description (ILIKE) at the DB level when no orgId is set, otherwise
     * the org-filtered list is searched in-memory. {@code sort} = name | lastModified (default
     * lastModified), {@code visibility} = all | public | private (default all).
     */
    @GetMapping("/paged")
    public ResponseEntity<Map<String, Object>> listInterfacesPaged(
            @RequestParam(value = "type", required = false) String interfaceType,
            @RequestParam(value = "excludeTableAttached", required = false) Boolean excludeTableAttached,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "25") int size,
            @RequestParam(value = "includeTemplates", defaultValue = "true") boolean includeTemplates,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "visibility", required = false) String visibility,
            HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        String orgRole = tenantResolver.resolveOrgRole(request);

        InterfaceService.InterfacePage pageResult = interfaceService.listInterfacesPaged(
                tenantId, interfaceType, excludeTableAttached, q, orgId, orgRole, page, size, sort, visibility);

        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("items", pageResult.items().stream()
                .map(item -> includeTemplates ? mapper.toDto(item) : mapper.toListDto(item))
                .toList());
        body.put("totalCount", pageResult.totalCount());
        body.put("page", pageResult.page());
        body.put("size", pageResult.size());
        // Per-interface publication badge for the page (id -> {status, rejectionReason?}), batched
        // server-side - replaces the former full getAllMyPublications sweep on the client.
        body.put("publicationStatuses", pageResult.publicationStatuses());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{id}")
    public ResponseEntity<InterfaceDto> getInterface(@PathVariable UUID id, HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        // #150 - scope-aware get: org workspace lookup when orgId is set,
        // personal-strict otherwise. Cross-scope / unknown id → 404.
        return interfaceService.getInterface(id, tenantId, orgId)
                .map(e -> ResponseEntity.ok(mapper.toDto(e)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<InterfaceDto> updateInterface(@PathVariable UUID id,
                                                          @RequestBody Map<String, Object> body,
                                                          HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        String orgRole = tenantResolver.resolveOrgRole(request);
        if (isViewerRole(orgRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        // Handle dataSourceId clearing (accept both camelCase and snake_case)
        Boolean updateDataSourceId = (body.containsKey("dataSourceId") || body.containsKey("data_source_id"))
                ? Boolean.TRUE : null;

        // Same key-presence rule for the display format: sending "format": null explicitly
        // clears it back to unset (full page), while omitting the key leaves it untouched.
        Boolean updateFormat = body.containsKey("format") ? Boolean.TRUE : null;

        // Parse wrong-typeable fields up front -> clean 400 on a bad type (portable, no advice
        // dependency), kept OUT of the service-call try below so a real "not found" still maps to 404.
        Long dataSourceId;
        Boolean isPublic;
        Boolean isActive;
        String format;
        try {
            dataSourceId = num(body, "dataSourceId", "data_source_id");
            isPublic = bool(body, "isPublic", "is_public");
            isActive = bool(body, "isActive", "is_active");
            format = format(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        // #150 - scope-aware update: routes the lookup via the strict-isolation
        // finder pair AND threads gateway-validated orgRole into the deny-list
        // check (closes PR-2.f MEMBER-strict gap on the inline gate).
        // Round-2 e2e (#150.3) revealed that a cross-scope PUT surfaced as a 500
        // because InterfaceService.updateInterface threw IllegalArgumentException
        // with no controller-level mapping. Catch it here and translate to 404
        // - symmetric with the GET path which returns 404 via Optional.orElse.
        InterfaceEntity updated;
        try {
            updated = interfaceService.updateInterface(
                    id, tenantId, orgId, orgRole,
                    (String) body.get("name"),
                    (String) body.get("description"),
                    str(body, "htmlTemplate", "html_template"),
                    str(body, "cssTemplate", "css_template"),
                    str(body, "jsTemplate", "js_template"),
                    null, null,
                    str(body, "targetTable", "target_table"),
                    dataSourceId,
                    isPublic,
                    isActive,
                    updateDataSourceId,
                    format,
                    updateFormat);
        } catch (IllegalArgumentException e) {
            // "Interface not found" → cross-scope or genuinely missing - 404
            // matches the GET path's Optional.orElse semantics. Other validation
            // IllegalArgumentExceptions (e.g. PR-2.f deny-list) re-throw via
            // OrgAccessDeniedException which has its own 403 mapping.
            if (e.getMessage() != null && e.getMessage().startsWith("Interface not found")) {
                return ResponseEntity.notFound().build();
            }
            throw e;
        }
        return ResponseEntity.ok(mapper.toDto(updated));
    }

    @PostMapping("/{id}/clone")
    public ResponseEntity<InterfaceDto> cloneInterface(@PathVariable UUID id, HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        String orgRole = tenantResolver.resolveOrgRole(request);
        // #150 - scope-aware clone: a teammate in the same org workspace can
        // clone a teammate-owned interface (pre-#150 returned 404 because the
        // lookup gated on the caller's own tenant_id).
        if (isViewerRole(orgRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        InterfaceEntity cloned = interfaceService.cloneInterface(id, tenantId, orgId, orgRole);
        return ResponseEntity.ok(mapper.toDto(cloned));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteInterface(
            @PathVariable UUID id,
            HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        String orgRole = tenantResolver.resolveOrgRole(request);
        // #150 - scope-aware delete: routes via the strict-isolation finder
        // pair. Deny-list still checked with the gateway-validated org-role.
        // OrgAccessDeniedException → 403 via auth-client global advice.
        if (isViewerRole(orgRole)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        interfaceService.deleteInterface(id, tenantId, orgId, orgRole);
        return ResponseEntity.noContent().build();
    }

    // Snapshot endpoints (used by frontend).
    // #150 - these are keyed by (interfaceId, workflowRunId) but historically
    // had NO scope check at all. We now require the caller to be able to see
    // the parent interface in their current scope (org workspace or personal)
    // before returning the snapshot. Cross-scope id → 404 (never reveals
    // existence to an unauthorized caller).
    @GetMapping("/{id}/snapshot")
    public ResponseEntity<InterfaceSnapshotDto> getSnapshot(@PathVariable UUID id,
                                                              @RequestParam UUID workflowRunId,
                                                              HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        if (interfaceService.getInterface(id, tenantId, orgId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return snapshotService.getSnapshot(id, workflowRunId)
                .map(s -> ResponseEntity.ok(mapper.toSnapshotDto(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/snapshots")
    public ResponseEntity<List<InterfaceSnapshotDto>> getSnapshotsForRun(@RequestParam UUID workflowRunId,
                                                                          HttpServletRequest request) {
        String tenantId = tenantResolver.resolve(request);
        String orgId = tenantResolver.resolveOrgId(request);
        // #150 - filter the run's snapshots to those whose parent interface
        // is visible to the caller in the current scope. Personal-snapshot
        // callers don't see org-attached snapshots and vice versa.
        var snapshots = snapshotService.getSnapshotsForRun(workflowRunId);
        var filtered = snapshots.stream()
                .filter(s -> interfaceService.findInScope(s.getInterfaceId(), tenantId, orgId).isPresent())
                .toList();
        return ResponseEntity.ok(filtered.stream().map(mapper::toSnapshotDto).toList());
    }

    // Helpers to accept both camelCase (frontend) and snake_case JSON keys
    private static String str(Map<String, Object> m, String camel, String snake) {
        Object v = m.get(camel);
        return (String) (v != null ? v : m.get(snake));
    }

    /**
     * Reads a plain string field. A wrong-typed value is a 400 (the callers map this
     * IllegalArgumentException to badRequest), never an uncaught ClassCastException surfacing as
     * a 500 - interface-service has no exception-mapping advice. A JSON null is returned as null,
     * which the caller distinguishes from "absent" via key presence.
     */
    private static String text(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        if (v instanceof String s) return s;
        throw new IllegalArgumentException("Field '" + key + "' must be a string");
    }

    /**
     * Reads the display format, rejecting a value that cannot be used. Same contract as the
     * interface tool: an unusable format must be a 400, never a silent no-op - normalize() maps
     * it to null, which on update would CLEAR the interface's shape instead of changing it.
     * Blank stays legal: it is the explicit "back to no declared shape" clear.
     */
    private static String format(Map<String, Object> m) {
        String raw = text(m, "format");
        if (InterfaceFormat.isInvalid(raw)) {
            throw new IllegalArgumentException("Field 'format' is not a usable interface format");
        }
        return raw;
    }

    private static Boolean bool(Map<String, Object> m, String camel, String snake) {
        Object v = m.get(camel);
        if (v == null) v = m.get(snake);
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        // A wrong-typed JSON value (e.g. "true" as a string) must be a 400, not an
        // uncaught ClassCastException surfacing as a 500 (interface-service has no
        // exception-mapping advice). Accept the common string form defensively.
        if (v instanceof String s) {
            String t = s.trim();
            if ("true".equalsIgnoreCase(t)) return Boolean.TRUE;
            if ("false".equalsIgnoreCase(t)) return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Field '" + camel + "' must be a boolean");
    }

    private static Long num(Map<String, Object> m, String camel, String snake) {
        Object v = m.get(camel);
        if (v == null) v = m.get(snake);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        // Accept a numeric string (mirrors InternalInterfaceController.createFromSnapshot);
        // anything else is a 400 (the controller maps this IllegalArgumentException to a
        // badRequest ResponseEntity), never an uncaught ClassCastException / 500.
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                // fall through to the exception below
            }
        }
        throw new IllegalArgumentException("Field '" + camel + "' must be a number");
    }

    private static boolean isViewerRole(String orgRole) {
        return orgRole != null && "VIEWER".equalsIgnoreCase(orgRole.trim());
    }
}
