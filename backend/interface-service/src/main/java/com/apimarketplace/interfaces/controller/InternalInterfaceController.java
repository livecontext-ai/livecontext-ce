package com.apimarketplace.interfaces.controller;

import com.apimarketplace.common.recentactivity.RecentActivityItemDto;
import com.apimarketplace.common.recentactivity.RecentActivityScopeResultDto;
import com.apimarketplace.common.recentactivity.ResourceKind;
import com.apimarketplace.interfaces.client.InterfaceFormat;
import com.apimarketplace.interfaces.client.dto.*;
import com.apimarketplace.interfaces.domain.InterfaceEntity;
import com.apimarketplace.interfaces.domain.InterfaceRunSnapshotEntity;
import com.apimarketplace.interfaces.repository.InterfaceRepository;
import com.apimarketplace.interfaces.service.InterfaceDtoMapper;
import com.apimarketplace.interfaces.service.InterfaceService;
import com.apimarketplace.interfaces.service.InterfaceSnapshotService;
import com.apimarketplace.interfaces.service.InterfaceVariableExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Internal API controller for inter-service communication.
 * Called by orchestrator-service via InterfaceClient.
 * No gateway authentication required (internal API endpoints are public).
 */
@RestController
@RequestMapping("/api/internal/interfaces")
public class InternalInterfaceController {

    private static final Logger log = LoggerFactory.getLogger(InternalInterfaceController.class);

    private final InterfaceService interfaceService;
    private final InterfaceSnapshotService snapshotService;
    private final InterfaceVariableExtractor variableExtractor;
    private final InterfaceDtoMapper mapper;
    private final InterfaceRepository interfaceRepository;

    public InternalInterfaceController(InterfaceService interfaceService,
                                        InterfaceSnapshotService snapshotService,
                                        InterfaceVariableExtractor variableExtractor,
                                        InterfaceDtoMapper mapper,
                                        InterfaceRepository interfaceRepository) {
        this.interfaceService = interfaceService;
        this.snapshotService = snapshotService;
        this.variableExtractor = variableExtractor;
        this.mapper = mapper;
        this.interfaceRepository = interfaceRepository;
    }

    // ========== Interface CRUD ==========

    @GetMapping("/{id}")
    public ResponseEntity<InterfaceDto> getInterface(@PathVariable UUID id,
                                                       @RequestHeader(value = "X-User-ID", required = true) String tenantId,
                                                       @RequestHeader(value = "X-Organization-ID", required = true) String orgId) {
        // Post-V261 (2026-05-19): both X-User-ID AND X-Organization-ID are required.
        // The legacy no-scope-check fallback (used pre-V261 by inter-service
        // helpers that didn't carry a workspace context) is moved to a dedicated
        // {@code /template} endpoint, explicitly opt-in and limited to the
        // template-fetch use case (interface render flow).
        return interfaceService.getInterface(id, tenantId, orgId)
                .map(e -> ResponseEntity.ok(mapper.toDto(e)))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Render-only template fetch. Returns the HTML/CSS/JS template for an
     * interface regardless of caller scope - the run's organization_id is the
     * scope for the rendered page, not the interface row's. Reserved for
     * {@code InterfaceRenderService} + landing-page render. NOT a public API
     * surface - no public controller endpoint exposes this.
     */
    @GetMapping("/{id}/template")
    public ResponseEntity<InterfaceDto> getInterfaceTemplate(@PathVariable UUID id) {
        return interfaceService.getInterfaceInternal(id)
                .map(e -> ResponseEntity.ok(mapper.toDto(e)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<InterfaceDto>> listInterfaces(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestParam(value = "type", required = false) String interfaceType,
            @RequestParam(value = "orgId", required = false) String orgId,
            @RequestParam(value = "orgRole", required = false) String orgRole) {

        List<InterfaceEntity> interfaces;
        if (interfaceType != null && !interfaceType.isBlank()) {
            interfaces = interfaceService.listInterfacesByType(tenantId, interfaceType, orgId, orgRole);
        } else {
            interfaces = interfaceService.listInterfaces(tenantId, null, orgId, orgRole);
        }
        return ResponseEntity.ok(interfaces.stream().map(mapper::toDto).toList());
    }

    @PostMapping
    public ResponseEntity<InterfaceDto> createInterface(@RequestBody InterfaceCreateRequest request,
                                                          @RequestHeader("X-User-ID") String tenantId) {
        // Same contract as the public and tool create paths: an unusable format is a 400, never
        // silently normalised to null - that would hand the caller a shapeless interface it never
        // asked for. (The /from-snapshot path below is deliberately lenient instead: a bad value
        // there must not 500 an acquire.)
        if (InterfaceFormat.isInvalid(request.getFormat())) {
            return ResponseEntity.badRequest().build();
        }
        InterfaceEntity created = interfaceService.createInterface(
                tenantId,
                request.getName(),
                request.getDescription(),
                request.getHtmlTemplate(),
                request.getCssTemplate(),
                request.getJsTemplate(),
                null, null,
                request.getTargetTable(),
                request.getDataSourceId(),
                request.getIsPublic(),
                null,
                request.getOrganizationId(),
                request.getFormat());
        return ResponseEntity.ok(mapper.toDto(created));
    }

    /**
     * Create an interface from a publication snapshot. Used by publication-service when
     * acquiring an INTERFACE publication; accepts the full set of fields (including
     * interfaceType, data, sourcePublicationId) that the basic create endpoint does not.
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/from-snapshot")
    public ResponseEntity<InterfaceDto> createFromSnapshot(@RequestBody Map<String, Object> snapshot,
                                                            @RequestHeader("X-User-ID") String tenantId) {
        String name = (String) snapshot.get("name");
        String description = (String) snapshot.get("description");
        String htmlTemplate = (String) snapshot.get("htmlTemplate");
        String cssTemplate = (String) snapshot.get("cssTemplate");
        String jsTemplate = (String) snapshot.get("jsTemplate");
        String interfaceType = (String) snapshot.get("interfaceType");
        Object dataRaw = snapshot.get("data");
        Map<String, Object> data = dataRaw instanceof Map ? (Map<String, Object>) dataRaw : null;
        Object dsIdRaw = snapshot.get("dataSourceId");
        Long dataSourceId = null;
        if (dsIdRaw instanceof Number n) {
            dataSourceId = n.longValue();
        } else if (dsIdRaw instanceof String s && !s.isBlank()) {
            try {
                dataSourceId = Long.parseLong(s);
            } catch (NumberFormatException e) {
                log.warn("Ignoring non-numeric dataSourceId={} on interface snapshot", s);
            }
        }
        String pubIdStr = (String) snapshot.get("sourcePublicationId");
        UUID sourcePublicationId = null;
        if (pubIdStr != null && !pubIdStr.isBlank()) {
            try {
                sourcePublicationId = UUID.fromString(pubIdStr);
            } catch (IllegalArgumentException e) {
                log.warn("Ignoring invalid sourcePublicationId={} on interface snapshot", pubIdStr);
            }
        }
        String organizationId = firstNonBlank(snapshot.get("organizationId"), snapshot.get("organization_id"));

        InterfaceEntity created = interfaceService.createFromSnapshot(
                tenantId, name, description, htmlTemplate, cssTemplate, jsTemplate,
                interfaceType, data, dataSourceId, sourcePublicationId, organizationId,
                // instanceof, not a cast: a wrong-typed snapshot value must not 500 the acquire.
                snapshot.get("format") instanceof String fmt ? fmt : null);
        return ResponseEntity.ok(mapper.toDto(created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<InterfaceDto> updateInterface(@PathVariable UUID id,
                                                          @RequestBody InterfaceUpdateRequest request,
                                                          @RequestHeader("X-User-ID") String tenantId,
                                                          @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
                                                          @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {
        // Same contract as the public and tool paths: an unusable format is a 400, never a silent
        // no-op. normalize() maps it to null, which with updateFormat set would CLEAR the shape
        // instead of changing it. Blank stays legal (the explicit clear).
        if (InterfaceFormat.isInvalid(request.getFormat())) {
            return ResponseEntity.badRequest().build();
        }
        // #150 - internal callers that have an active-org context (gateway-
        // routed user requests fan out to internal endpoints, orchestrator
        // proxying user mutations) thread the org headers through so the
        // strict-isolation finder pair routes correctly. Callers without an
        // org context (publication-service compensations, scheduled jobs) just
        // omit the headers and get the personal-strict path.
        InterfaceEntity updated = interfaceService.updateInterface(
                id, tenantId, orgId, orgRole,
                request.getName(),
                request.getDescription(),
                request.getHtmlTemplate(),
                request.getCssTemplate(),
                request.getJsTemplate(),
                null, null,
                request.getTargetTable(),
                request.getDataSourceId(),
                request.getIsPublic(),
                request.getIsActive(),
                request.getClearDataSource(),
                request.getFormat(),
                // Typed DTO: a null format is indistinguishable from an omitted one, so an
                // explicit clear_format=true is what clears it (mirrors clear_data_source).
                (request.getFormat() != null || Boolean.TRUE.equals(request.getClearFormat()))
                        ? Boolean.TRUE : null);
        return ResponseEntity.ok(mapper.toDto(updated));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteInterface(@PathVariable UUID id,
                                                  @RequestHeader("X-User-ID") String tenantId,
                                                  @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
                                                  @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {
        // #150 - same scope-aware routing as the public controller, conditional
        // on header presence. Internal callers without org context fall back
        // to personal-strict (legacy behaviour).
        interfaceService.deleteInterface(id, tenantId, orgId, orgRole);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/clone")
    public ResponseEntity<InterfaceDto> cloneInterface(@PathVariable UUID id,
                                                         @RequestHeader("X-User-ID") String tenantId,
                                                         @RequestHeader(value = "X-Organization-ID", required = false) String orgId,
                                                         @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {
        InterfaceEntity cloned = interfaceService.cloneInterface(id, tenantId, orgId, orgRole);
        return ResponseEntity.ok(mapper.toDto(cloned));
    }

    /**
     * Best-effort activity-touch: bump {@code updated_at} so the bell's Activity tab
     * surfaces this interface after a user action fire (button click, signal
     * resolution, {@code __continue}). Not scope-aware on purpose - the cosmetic
     * timestamp bump cannot leak data and the caller has already passed scope
     * checks at the action-fire path.
     *
     * <p>Returns 204 unconditionally (also when the id is unknown - fire-and-forget
     * idiom matches the {@link com.apimarketplace.interfaces.client.InterfaceClient}
     * swallow contract on the orchestrator side).
     */
    @PostMapping("/{id}/touch")
    public ResponseEntity<Void> touchInterface(@PathVariable UUID id) {
        interfaceRepository.touchUpdatedAt(id, java.time.Instant.now());
        return ResponseEntity.noContent().build();
    }

    // ========== Agent Browse ==========

    /**
     * Persist a browser-agent action result as an Interface row
     * ({@code interface_type='agent_browse'}). Replaces the legacy
     * {@code /web-search} endpoint (removed 2026-05-22) - search/fetch tool
     * calls no longer persist (rendered inline via the favicon stack in chat,
     * see commit f600c8885), so the only remaining persisted-interface case
     * is the live browser-agent CDP card.
     */
    @PostMapping("/agent-browse")
    public ResponseEntity<InterfaceDto> createOrUpdateAgentBrowse(@RequestBody AgentBrowseInterfaceRequest request,
                                                                    @RequestHeader("X-User-ID") String tenantId) {
        InterfaceEntity result = interfaceService.createOrUpdateAgentBrowseInterface(
                tenantId,
                request.getConversationId(),
                request.getMessageId(),
                request.getAgentId(),
                request.getName(),
                request.getData(),
                request.getOrganizationId());
        return ResponseEntity.ok(mapper.toDto(result));
    }

    /**
     * Legacy screenshot-callback endpoint. Kept alive for back-compat with
     * the now-unreachable {@code WebSearchCallbackController} hook - the
     * websearch sidecar stopped producing screenshots in commit f600c8885,
     * so this path is dormant. Removal scheduled with the broader callback
     * cleanup.
     */
    @PutMapping("/{id}/web-search-screenshot")
    public ResponseEntity<Void> updateWebSearchScreenshot(@PathVariable UUID id,
                                                            @RequestBody Map<String, String> body,
                                                            @RequestHeader("X-User-ID") String tenantId) {
        interfaceService.updateWebSearchScreenshot(id, body.get("result_url"), body.get("screenshot_key"));
        return ResponseEntity.ok().build();
    }

    // ========== Image Generation ==========

    @PostMapping("/image-generation")
    public ResponseEntity<InterfaceDto> createOrUpdateImageGeneration(
            @RequestBody com.apimarketplace.interfaces.client.dto.ImageGenerationInterfaceRequest request,
            @RequestHeader("X-User-ID") String tenantId) {
        InterfaceEntity result = interfaceService.createOrUpdateImageGenerationInterface(
                tenantId,
                request.getConversationId(),
                request.getMessageId(),
                request.getAgentId(),
                request.getName(),
                request.getData(),
                request.getOrganizationId());
        return ResponseEntity.ok(mapper.toDto(result));
    }

    // ========== Slides ==========

    @SuppressWarnings("unchecked")
    @PostMapping("/slide")
    public ResponseEntity<InterfaceDto> createSlideInterface(@RequestBody Map<String, Object> body,
                                                               @RequestHeader("X-User-ID") String tenantId) {
        String name = (String) body.get("name");
        Map<String, Object> slideData = (Map<String, Object>) body.get("slide_data");
        InterfaceEntity created = interfaceService.createSlideInterface(tenantId, name, null, slideData);
        return ResponseEntity.ok(mapper.toDto(created));
    }

    @SuppressWarnings("unchecked")
    @PutMapping("/{id}/slide-data")
    public ResponseEntity<InterfaceDto> updateSlideData(@PathVariable UUID id,
                                                          @RequestBody Map<String, Object> slideData,
                                                          @RequestHeader("X-User-ID") String tenantId) {
        InterfaceEntity updated = interfaceService.updateSlideData(id, tenantId, null, null, slideData);
        return ResponseEntity.ok(mapper.toDto(updated));
    }

    // ========== Snapshots ==========

    @PostMapping("/snapshots")
    public ResponseEntity<InterfaceSnapshotDto> createSnapshot(@RequestBody SnapshotCreateRequest request,
                                                                 @RequestHeader("X-User-ID") String tenantId) {
        var snapshot = snapshotService.createSnapshot(
                request.getInterfaceId(),
                request.getWorkflowRunId(),
                request.getVariableMappings(),
                request.getActionMappings(),
                tenantId);
        return snapshot != null
                ? ResponseEntity.ok(mapper.toSnapshotDto(snapshot))
                : ResponseEntity.notFound().build();
    }

    @GetMapping("/snapshots/find")
    public ResponseEntity<InterfaceSnapshotDto> getSnapshot(@RequestParam UUID interfaceId,
                                                              @RequestParam UUID workflowRunId,
                                                              @RequestHeader(value = "X-Organization-ID", required = false)
                                                                  String organizationId) {
        return snapshotService.getSnapshot(interfaceId, workflowRunId, organizationId)
                .map(s -> ResponseEntity.ok(mapper.toSnapshotDto(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/snapshots/by-run/{workflowRunId}")
    public ResponseEntity<List<InterfaceSnapshotDto>> getSnapshotsForRun(@PathVariable UUID workflowRunId,
                                                                           @RequestHeader(value = "X-Organization-ID", required = false)
                                                                               String organizationId) {
        var snapshots = snapshotService.getSnapshotsForRun(workflowRunId, organizationId);
        return ResponseEntity.ok(snapshots.stream().map(mapper::toSnapshotDto).toList());
    }

    @DeleteMapping("/snapshots/by-run/{workflowRunId}")
    public ResponseEntity<Void> deleteSnapshotsForRun(@PathVariable UUID workflowRunId) {
        snapshotService.deleteSnapshotsForRun(workflowRunId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Refresh every snapshot of a workflow run with the current live HTML/CSS/JS of its source
     * {@link com.apimarketplace.interfaces.domain.InterfaceEntity}. Called by orchestrator-service
     * at trigger-fire time so long-running {@code WAITING_TRIGGER} runs pick up agent-driven
     * interface iterations on the next epoch - mirroring the workflow-plan refresh idiom.
     *
     * <p>Idempotent: returns counters even when nothing changed. Tolerates per-snapshot failures
     * (one corrupt row never blocks the trigger pipeline). Preserves the snapshot when the source
     * interface has been deleted.
     */
    @PostMapping("/snapshots/refresh-from-live/{workflowRunId}")
    public ResponseEntity<Map<String, Object>> refreshSnapshotsFromLive(@PathVariable UUID workflowRunId,
                                                                          @RequestHeader(value = "X-Organization-ID", required = false)
                                                                              String organizationId) {
        var result = snapshotService.refreshSnapshotsFromLiveInterface(workflowRunId, organizationId);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("refreshed", result.refreshed());
        body.put("unchanged", result.unchanged());
        body.put("missing", result.missing());
        body.put("errors", result.errors());
        body.put("total", result.total());
        return ResponseEntity.ok(body);
    }

    // ========== Variable Extraction ==========

    @PostMapping("/extract-variables")
    public ResponseEntity<List<String>> extractVariables(@RequestBody Map<String, String> body) {
        String htmlTemplate = body.get("html_template");
        String cssTemplate = body.get("css_template");
        StringBuilder combined = new StringBuilder();
        if (htmlTemplate != null) combined.append(htmlTemplate);
        if (cssTemplate != null) {
            if (!combined.isEmpty()) combined.append("\n");
            combined.append(cssTemplate);
        }
        return ResponseEntity.ok(variableExtractor.extractTemplateVariables(combined.toString()));
    }

    @PostMapping("/extract-form-fields")
    public ResponseEntity<List<String>> extractFormFields(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(variableExtractor.extractFormFields(body.get("html_template")));
    }

    // ========== Project Operations ==========

    @PutMapping("/{id}/project/{projectId}")
    public ResponseEntity<Void> assignToProject(@PathVariable UUID id, @PathVariable UUID projectId,
                                                  @RequestHeader("X-User-ID") String tenantId,
                                                  @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        interfaceService.assignToProject(id, projectId, tenantId, orgId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}/project/{projectId}")
    public ResponseEntity<Void> removeFromProject(@PathVariable UUID id, @PathVariable UUID projectId,
                                                    @RequestHeader("X-User-ID") String tenantId,
                                                    @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        interfaceService.removeFromProject(id, projectId, tenantId, orgId);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/count-by-project/{projectId}")
    public ResponseEntity<Long> countByProject(@PathVariable UUID projectId,
                                               @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        return ResponseEntity.ok(interfaceService.countByProject(projectId, orgId));
    }

    @GetMapping("/by-project/{projectId}")
    public ResponseEntity<List<InterfaceDto>> getByProject(@PathVariable UUID projectId,
                                                           @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        return ResponseEntity.ok(interfaceService.getByProject(projectId, orgId).stream().map(mapper::toDto).toList());
    }

    @DeleteMapping("/unassign-project/{projectId}")
    public ResponseEntity<Void> unassignAllFromProject(@PathVariable UUID projectId,
                                                       @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        interfaceService.unassignAllFromProject(projectId, orgId);
        return ResponseEntity.noContent().build();
    }

    // ========== Batch Fetch ==========

    @PostMapping("/batch")
    public ResponseEntity<List<InterfaceDto>> getInterfacesByIds(@RequestBody List<UUID> ids) {
        if (ids == null || ids.isEmpty()) return ResponseEntity.ok(List.of());
        List<InterfaceEntity> entities = interfaceService.getInterfacesByIds(ids);
        return ResponseEntity.ok(entities.stream().map(mapper::toDto).toList());
    }

    // ========== Bulk Operations ==========

    @GetMapping("/by-source-workflow/{workflowId}")
    public ResponseEntity<List<InterfaceDto>> findBySourceWorkflowId(@PathVariable UUID workflowId) {
        return ResponseEntity.ok(interfaceService.findBySourceWorkflowId(workflowId).stream().map(mapper::toDto).toList());
    }

    @DeleteMapping("/by-source-workflow/{workflowId}")
    public ResponseEntity<Void> deleteBySourceWorkflowId(@PathVariable UUID workflowId) {
        interfaceService.deleteBySourceWorkflowId(workflowId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/count")
    public ResponseEntity<Long> countByTenant(@RequestHeader("X-User-ID") String tenantId) {
        return ResponseEntity.ok(interfaceService.countByTenant(tenantId));
    }

    // ========== Recent Activity (fan-out branch for /api/activities/recent) ==========

    /**
     * Top-{@value #RECENT_LIMIT} interfaces in the caller's active workspace,
     * ordered by last edit time, plus a peer-scope count for the empty-state
     * hint. Called by orchestrator's {@code RecentActivityAggregatorService}
     * via {@code InterfaceClient.getRecentActivity}.
     *
     * <p>Scope routing matches the established strict-pair pattern
     * ({@code ActiveAutomationsService:149-155} precedent - see PR22/PR30):
     * {@code orgId != null} → org-strict finder; otherwise personal-strict.
     * Backed by the V235 partial indexes
     * {@code idx_interfaces_org_updated_at} +
     * {@code idx_interfaces_tenant_updated_at_personal}.
     *
     * <p><b>Actor attribution</b>: interfaces lack a {@code created_by} column
     * (V7 schema), so the {@code actorId} field of each emitted
     * {@link RecentActivityItemDto} is the row's {@code tenant_id} - the user
     * who owns the interface within the active scope. For org-scope rows the
     * tenant_id is the org member who created it; for personal rows it's the
     * user themselves. Imperfect but uniform with the other 4 fan-out
     * branches that all surface {@code tenantId} as actor for consistency.
     */
    private static final int RECENT_LIMIT = 50;

    @GetMapping("/recent-activity")
    public ResponseEntity<RecentActivityScopeResultDto> getRecentActivity(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = true) String orgId) {

        // Post-V261: organizationId is always present (gateway always injects
        // X-Organization-ID - personal-workspace users resolve to their
        // personal-org UUID via auth.organization_member.is_default=true).
        // The IS-NULL "personal scope" branch + cross-scope peer counter are
        // therefore dead code and have been removed. Peer-scope cross-org
        // aggregation stays at 0 - same shape as the prior personal branch
        // (deferred "All workspaces" feature per RecentActivityScopeResultDto).
        List<InterfaceEntity> rows = interfaceRepository.findRecentByOrganizationIdStrict(
                orgId, PageRequest.of(0, RECENT_LIMIT));
        int peerScopeCount = 0;

        List<RecentActivityItemDto> items = rows.stream()
                .map(e -> RecentActivityItemDto.builder()
                        .kind(ResourceKind.INTERFACE)
                        .resourceId(e.getId().toString())
                        .name(e.getName())
                        .lastEditedAt(e.getUpdatedAt())
                        .actorId(e.getTenantId())
                        .build())
                .toList();

        return ResponseEntity.ok(new RecentActivityScopeResultDto(items, peerScopeCount));
    }

    private static String firstNonBlank(Object... values) {
        for (Object value : values) {
            if (value == null) continue;
            String text = value.toString();
            if (!text.isBlank()) {
                return text;
            }
        }
        return null;
    }
}
