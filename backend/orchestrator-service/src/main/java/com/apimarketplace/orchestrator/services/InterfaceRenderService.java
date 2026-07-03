package com.apimarketplace.orchestrator.services;

import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.orchestrator.controllers.workflow.WorkflowControllerHelper;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.interfaces.client.dto.InterfaceSnapshotDto;
import com.apimarketplace.orchestrator.config.OrchestratorLimitsConfig;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.services.interfaces.InterfaceRenderer;
import com.apimarketplace.orchestrator.services.interfaces.InterfaceTemplateDefaults;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.DataSourceItemDto;
import com.apimarketplace.orchestrator.persistence.WorkflowStepDataRepository;
import com.apimarketplace.orchestrator.repository.EpochItemProjection;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.context.RunContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for resolving interface variables and generating render data.
 * Uses InterfaceClient to fetch interface/snapshot data from interface-service.
 * Uses outputs stored in storage.storage via workflow_step_data.
 *
 * Interface templates use GENERIC variable names with optional pipe defaults:
 * - {{title|My Product}} - generic name with inline default
 * - {{price|0.00}} - shows "0.00" when no data
 * - {{title}} - shows [title] placeholder when no data
 *
 * Variable mapping (generic → workflow expression) is configured on the workflow node.
 * At render time, mapped workflow data is resolved and injected by generic name.
 *
 * Workflow params (NOT interface templates) use unified syntax:
 * - {{mcp:alias.output.field}}, {{trigger:alias.output.field}}
 * - {{formatDate(mcp:alias.output.date, 'DD/MM/YYYY')}}
 */
@Service
public class InterfaceRenderService implements InterfaceRenderer {

    private static final Logger logger = LoggerFactory.getLogger(InterfaceRenderService.class);

    @Autowired
    private InterfaceClient interfaceClient;

    @Autowired
    private WorkflowStepDataRepository stepDataRepository;

    @Autowired
    private TemplateEngine templateEngine;

    @Autowired
    private DataSourceClient dataSourceClient;

    @Autowired
    private RunContextService runContextService;

    @Autowired
    private WorkflowRunRepository workflowRunRepository;

    @Autowired
    private OrchestratorLimitsConfig renderLimits;

    // Per-run {{$vars.*}} bundle for interface variable_mapping resolution.
    // Optional: absent in plain unit tests -> $vars simply does not resolve there
    // (graceful degradation), same pattern as the execution engine.
    @Autowired(required = false)
    private com.apimarketplace.orchestrator.services.context.WorkflowVariableBundleCache workflowVariableBundleCache;

    /**
     * Résultat du rendu d'une interface.
     *
     * <p>{@code truncation} is null when the result was not truncated; non-null carries the real
     * pre-truncation count + the reason so the template / frontend can show "Showing N of M".
     */
    public record InterfaceRenderResult(
        String htmlTemplate,
        String cssTemplate,
        String jsTemplate,
        List<ItemRenderData> items,
        PaginationInfo pagination,
        Map<String, String> actionMappings, // CSS selector → trigger ref
        RenderTruncation truncation
    ) {
        /** Backward-compatible 6-arg constructor: no truncation. */
        public InterfaceRenderResult(String htmlTemplate, String cssTemplate, String jsTemplate,
                                     List<ItemRenderData> items, PaginationInfo pagination,
                                     Map<String, String> actionMappings) {
            this(htmlTemplate, cssTemplate, jsTemplate, items, pagination, actionMappings, null);
        }
    }

    /**
     * Truncation metadata surfaced when {@link OrchestratorLimitsConfig} caps are hit.
     *
     * @param total        real item count before truncation (server-side count via
     *                     {@code DataSourceClient.getItemsCount})
     * @param rendered     items actually included in the result
     * @param reason       {@code "max_items_per_render"} when the count cap clamped {@code size};
     *                     {@code "max_payload_bytes"} when the bytes guard short-circuited the
     *                     per-item rendering loop
     * @param payloadBytes cumulative size of rendered HTML across the items in the result,
     *                     measured as {@code String.length() * 2} (UTF-16 code-unit bytes - an
     *                     in-memory heuristic, NOT the on-wire UTF-8 byte count). Populated for
     *                     both truncation reasons so callers can correlate item count with
     *                     rendered weight; the value is the cumulative weight regardless of which
     *                     cap tripped first.
     */
    public record RenderTruncation(long total, int rendered, String reason, long payloadBytes) {}

    public record ItemRenderData(
        int epoch,
        int itemIndex,
        int spawn,
        Map<String, Object> data,
        Map<String, Map<String, Object>> triggerData // triggerKey → submitted payload
    ) {}

    public record PaginationInfo(
        int page,
        int size,
        long totalItems,
        int totalPages
    ) {}

    /**
     * Backend-rendered snapshot of an interface for a specific epoch - the HTML has been
     * substituted with {@link InterfaceTemplateDefaults#apply} using the first item's resolved
     * variables, mirroring exactly what the iframe (and the screenshot sidecar) sees. CSS and JS
     * are returned raw - the iframe handles their per-item hydration via {@code __RESOLVED_DATA__}.
     *
     * <p>Single source of truth for "the interface as it would be displayed right now". Consumed by:
     * <ul>
     *   <li>{@code InterfaceScreenshotServiceImpl} - feeds {@code html} into the Playwright sidecar.</li>
     *   <li>{@code InterfaceNode} - feeds {@code html}/{@code css}/{@code js} into the
     *       {@code rendered_html}/{@code rendered_css}/{@code rendered_js} workflow outputs.</li>
     * </ul>
     *
     * @param html   HTML with {@code {{var|default}}} resolved
     * @param css    raw CSS template (may be null when the interface has none)
     * @param js     raw JS template (may be null when the interface has none)
     * @param vars   the variable map used for substitution (items[0].data()); empty when no items
     */
    public record ResolvedTemplateSnapshot(String html, String css, String js, Map<String, Object> vars) {}

    /**
     * Template data and configuration for a run
     */
    private record TemplateConfig(String htmlTemplate, String cssTemplate, String jsTemplate, Map<String, String> variableMappings, Map<String, String> actionMappings) {}

    /**
     * Génère les données de rendu pour une interface et un run donné.
     * Si size <= 0, utilise 10 comme taille de page par défaut.
     *
     * @param interfaceId Interface UUID
     * @param runId String run ID (format: run_timestamp_uuid)
     * @param tenantId Tenant ID
     * @param page Page number
     * @param size Page size
     */
    @Transactional(readOnly = true)
    public InterfaceRenderResult render(UUID interfaceId, String runId, String tenantId, int page, int size, Integer epoch) {
        return render(interfaceId, runId, tenantId, page, size, epoch, Map.of());
    }

    /**
     * Render with SQL-level variable pagination. Variables listed in {@code variablePages}
     * are resolved via JSONB array slicing in SQL (LIMIT/OFFSET) - only the requested page
     * leaves PostgreSQL. Other variables use the standard full-load + earlyClamp path.
     *
     * @param variablePages map of variable name → 0-based page index (e.g. {"rows": 2, "contacts": 0})
     */
    @Transactional(readOnly = true)
    public InterfaceRenderResult render(UUID interfaceId, String runId, String tenantId,
                                         int page, int size, Integer epoch,
                                         Map<String, Integer> variablePages) {
        // Cross-tenant fix: resolve the run owner's tenantId for storage queries
        String ownerTenantId = resolveRunOwnerTenantId(runId, tenantId);

        // 1. Get workflowRunId with lightweight query (no JSONB loading)
        UUID workflowRunId = findWorkflowRunId(runId);

        // 2. Get snapshot (frozen template) or live interface with config
        TemplateConfig config = getTemplateConfigForRun(interfaceId, workflowRunId, ownerTenantId);
        String htmlTemplate = config.htmlTemplate();
        String cssTemplate = config.cssTemplate();
        String jsTemplate = config.jsTemplate();
        Map<String, String> actionMappings = config.actionMappings();

        logger.info("[InterfaceRender] render() interface={} runId={} callerTenant={} ownerTenant={} actionMappings={}",
                interfaceId, runId, tenantId, ownerTenantId, actionMappings);

        // Use configured size if not specified, then clamp to the hard cap. A caller asking
        // for 10 000 items is silently capped at maxItemsPerRender - pagination is the right
        // tool to fetch more. Cap is always active; toggling it requires changing the config,
        // not the request, so a runaway template cannot bypass it by passing a large size.
        int requestedSize = size > 0 ? size : 10;
        int maxItems = renderLimits.getMaxItemsPerRender();
        int effectiveSize = Math.min(requestedSize, maxItems);
        boolean clampedByCount = effectiveSize < requestedSize;
        if (clampedByCount) {
            logger.warn("[InterfaceRender] requested size={} exceeds cap={} - clamping (interfaceId={}, runId={})",
                    requestedSize, maxItems, interfaceId, runId);
        }

        if (htmlTemplate == null || htmlTemplate.isEmpty()) {
            logger.warn("No template found for interface {} and run {}", interfaceId, runId);
            return new InterfaceRenderResult("", cssTemplate, jsTemplate, List.of(), new PaginationInfo(page, effectiveSize, 0, 0), actionMappings);
        }

        // 3. Get variable mappings from snapshot (may be empty for static interfaces)
        Map<String, String> variableMappings = config.variableMappings();
        boolean hasVariableMappings = variableMappings != null && !variableMappings.isEmpty();

        // 4. Get distinct (epoch, spawn, itemIndex) triples scoped to the interface node.
        //    Pagination is by (epoch, itemIndex) - spawn is a rerun counter, NOT a page.
        //    We keep only the latest (max) spawn per (epoch, itemIndex).
        record EpochItem(int epoch, int spawn, int itemIndex) implements Comparable<EpochItem> {
            @Override
            public int compareTo(EpochItem other) {
                int cmp = Integer.compare(other.epoch, this.epoch);
                return cmp != 0 ? cmp : Integer.compare(other.itemIndex, this.itemIndex);
            }
        }

        // Resolve the interface node's normalizedKey to scope pagination to this node only
        String interfaceNormalizedKey = resolveInterfaceNormalizedKey(interfaceId, runId);
        List<EpochItemProjection> projections;
        if (interfaceNormalizedKey != null) {
            projections = stepDataRepository.findDistinctEpochItemPairsByRunIdAndNormalizedKey(runId, interfaceNormalizedKey);
        } else {
            // Fallback: no interface step_data yet (interface hasn't executed), use excluding-triggers query
            projections = stepDataRepository.findDistinctEpochItemPairsExcludingTriggers(runId);
        }

        if (projections.isEmpty()) {
            logger.debug("No step data found for run {}", runId);
            return new InterfaceRenderResult(htmlTemplate, cssTemplate, jsTemplate, List.of(), new PaginationInfo(page, effectiveSize, 0, 0), actionMappings);
        }

        // Group by (epoch, itemIndex) and keep only the max spawn for each group
        Map<String, EpochItem> latestByEpochItem = new HashMap<>();
        for (EpochItemProjection p : projections) {
            int ep = p.getEpoch() != null ? p.getEpoch() : 0;
            int sp = p.getSpawn() != null ? p.getSpawn() : 0;
            int idx = p.getItemIndex() != null ? p.getItemIndex() : 0;
            String groupKey = ep + ":" + idx;
            EpochItem existing = latestByEpochItem.get(groupKey);
            if (existing == null || sp > existing.spawn()) {
                latestByEpochItem.put(groupKey, new EpochItem(ep, sp, idx));
            }
        }

        // 5. Pagination - sorted by epoch desc, itemIndex desc
        List<EpochItem> sortedKeys = new ArrayList<>(latestByEpochItem.values());
        Collections.sort(sortedKeys);

        // If epoch is specified, filter to only that epoch's items
        if (epoch != null) {
            sortedKeys.removeIf(key -> key.epoch() != epoch);
            // If no step_data for this interface at the requested epoch (e.g. interface is still
            // awaiting_signal and hasn't persisted its own step_data yet), add a synthetic entry
            // so we can still resolve predecessor variables from the epoch's run context.
            // Validate by checking if ANY node ran in this epoch (not just this interface).
            if (sortedKeys.isEmpty()) {
                List<EpochItemProjection> allEpochData =
                        stepDataRepository.findDistinctEpochItemPairsExcludingTriggers(runId);
                boolean epochHasData = allEpochData.stream().anyMatch(p ->
                        p.getEpoch() != null && p.getEpoch().equals(epoch));
                if (epochHasData) {
                    sortedKeys.add(new EpochItem(epoch, 0, 0));
                }
            }
        }

        int totalItems = sortedKeys.size();
        int totalPages = (int) Math.ceil((double) totalItems / effectiveSize);
        int fromIndex = page * effectiveSize;
        int toIndex = Math.min(fromIndex + effectiveSize, totalItems);

        if (fromIndex >= totalItems) {
            return new InterfaceRenderResult(htmlTemplate, cssTemplate, jsTemplate, List.of(), new PaginationInfo(page, effectiveSize, totalItems, totalPages), actionMappings);
        }

        List<EpochItem> pageItems = sortedKeys.subList(fromIndex, toIndex);

        // 6. Resolve variables and extract trigger data for each (epoch, spawn, itemIndex) on the page
        List<ItemRenderData> items = new ArrayList<>();
        for (EpochItem key : pageItems) {
            Map<String, Object> resolvedData;
            if (hasVariableMappings) {
                resolvedData = resolveVariablesWithPagination(
                    variableMappings, runId, key.epoch(), key.spawn(), key.itemIndex(),
                    ownerTenantId, variablePages);
            } else {
                resolvedData = Map.of();
            }
            Map<String, Map<String, Object>> triggerData = extractTriggerData(runId, ownerTenantId, actionMappings, key.epoch(), key.spawn(), key.itemIndex());
            items.add(new ItemRenderData(key.epoch(), key.itemIndex(), key.spawn(), resolvedData, triggerData));
        }

        // Surface the clamp so single-page consumers (e.g. ShowcaseSnapshotBuilder, which captures
        // page 0 only without iterating) can detect that a requested size > maxItemsPerRender was
        // silently shrunk. {@code total} reports the real epoch/item count for the run - paginating
        // callers can use it to fetch the remaining pages; single-page callers can render
        // "Showing N of M" without hitting the DB twice. {@code payloadBytes=0} because the
        // workflow-run render path doesn't compute a cumulative HTML weight (templates are resolved
        // client-side via window.__RESOLVED_DATA__, not server-side per item).
        RenderTruncation truncation = clampedByCount
                ? new RenderTruncation(totalItems, items.size(), "max_items_per_render", 0L)
                : null;

        return new InterfaceRenderResult(
            htmlTemplate,
            cssTemplate,
            jsTemplate,
            items,
            new PaginationInfo(page, effectiveSize, totalItems, totalPages),
            actionMappings,
            truncation
        );
    }

    /**
     * Get template config for a run (snapshot if exists, otherwise live interface).
     * Uses InterfaceClient to fetch from interface-service.
     */
    private TemplateConfig getTemplateConfigForRun(UUID interfaceId, UUID workflowRunId, String tenantId) {
        // First try snapshot via interface-service
        if (workflowRunId != null) {
            InterfaceSnapshotDto snapshot = interfaceClient.getSnapshot(interfaceId, workflowRunId, tenantId);
            if (snapshot != null) {
                Map<String, String> mappings = snapshot.getVariableMappings() != null ? snapshot.getVariableMappings() : Map.of();
                Map<String, String> actions = snapshot.getActionMappings() != null ? snapshot.getActionMappings() : Map.of();
                return new TemplateConfig(snapshot.getHtmlTemplate(), snapshot.getCssTemplate(), snapshot.getJsTemplate(), mappings, actions);
            }
        }

        // Fallback to live interface (no tenant restriction for internal lookup)
        InterfaceDto iface = interfaceClient.getInterfaceTemplateForRender(interfaceId);
        if (iface != null) {
            return new TemplateConfig(iface.getHtmlTemplate(), iface.getCssTemplate(), iface.getJsTemplate(), Map.of(), Map.of());
        }

        return new TemplateConfig(null, null, null, Map.of(), Map.of());
    }

    /**
     * Compte le nombre total d'items pour un run, scoped to the interface node.
     *
     * <p><b>WARNING</b> - un-scoped variant. Returns the count for any
     * (interface, run) pair regardless of caller. Reserved for internal
     * callers that have already verified scope (e.g. interface render flow
     * after the run's tenant check). Public callers MUST use the 3-arg
     * overload below.
     */
    @Transactional(readOnly = true)
    public long countItems(UUID interfaceId, String runId) {
        String interfaceNormalizedKey = resolveInterfaceNormalizedKey(interfaceId, runId);
        long count;
        if (interfaceNormalizedKey != null) {
            count = stepDataRepository.countDistinctItemsByRunIdAndNormalizedKey(runId, interfaceNormalizedKey);
        } else {
            count = stepDataRepository.countDistinctItemsExcludingTriggers(runId);
        }
        return count;
    }

    /**
     * Tenant-scoped countItems (2026-05-18). Verifies the run belongs to the
     * caller's tenant before returning the count. The orchestrator-side
     * /api/interfaces/{id}/items-count endpoint funnels through here so a
     * UUID-guess by a different tenant returns 0 (not the actual count).
     */
    @Transactional(readOnly = true)
    public long countItems(UUID interfaceId, String runId, String tenantId) {
        return countItems(interfaceId, runId, tenantId, null);
    }

    /**
     * Workspace-aware countItems. In org mode, teammates can render/count a run
     * owned by another user when the run belongs to the active workspace.
     */
    @Transactional(readOnly = true)
    public long countItems(UUID interfaceId, String runId, String tenantId, String organizationId) {
        if (!callerCanAccessRun(runId, tenantId, organizationId)) return 0L;
        return countItems(interfaceId, runId);
    }

    /**
     * Legacy personal-scope gate for authenticated render endpoints. New REST
     * callers should use {@link #callerCanAccessRun(String, String, String)}
     * so org-workspace teammates use the same strict-scope predicate as the
     * workflow run controllers.
     */
    @Transactional(readOnly = true)
    public boolean callerOwnsRun(String runId, String tenantId) {
        return callerCanAccessRun(runId, tenantId, null);
    }

    /**
     * Strict-scope gate for authenticated interface render endpoints. Personal
     * scope still requires same tenant; org scope allows workspace teammates
     * through when {@code organization_id} matches the active workspace.
     */
    @Transactional(readOnly = true)
    public boolean callerCanAccessRun(String runId, String tenantId, String organizationId) {
        if (tenantId == null || tenantId.isBlank() || runId == null) {
            return false;
        }
        return workflowRunRepository.findByRunIdPublic(runId)
            .map(run -> ScopeGuard.isInStrictScope(
                    tenantId,
                    organizationId,
                    run.getTenantId(),
                    run.getOrganizationId())
                && WorkflowControllerHelper.shareContextPermitsRun(run))
            .orElse(false);
    }

    /**
     * Strict-scope gate for the standalone /render-datasource +
     * /datasource-items-count endpoints. Trusts the interface-service's
     * strict-scope finder (see
     * {@code InterfaceService#findInScope(id, tenantId, orgId)}).
     *
     * <p>The inbound request carries {@code X-Organization-ID} (always
     * injected by the gateway post-V261; personal workspaces resolve to the
     * user's default personal org). The {@code OrgContextHeaderForwarder}
     * (invoked inside {@code InterfaceClient.buildHeaders}) propagates it
     * to the outbound call; interface-service routes through the org-strict
     * finder ({@code organization_id = :orgId}). Org-teammates legitimately
     * see each other's interfaces; the caller's own {@code tenant_id} is
     * NOT compared.
     *
     * <p>A non-null DTO returned by the interface-service means
     * the {@code (tenantId, orgId)} pair already passed the strict-scope
     * gate. A {@code null} DTO means the lookup failed (cross-scope,
     * unknown id, or interface-service error) → controller returns 404.
     *
     * <p>Critically, this gate does NOT re-compare {@code iface.getTenantId()}
     * against the caller's tenantId: that comparison would falsely reject
     * org-teammate access to a workspace-shared interface owned by a
     * different teammate. Trust the strict-scope finder; do not duplicate
     * its predicate with weaker logic here.
     */
    @Transactional(readOnly = true)
    public boolean callerOwnsInterface(UUID interfaceId, String tenantId) {
        if (tenantId == null || tenantId.isBlank() || interfaceId == null) {
            return false;
        }
        return interfaceClient.getInterface(interfaceId, tenantId) != null;
    }

    /**
     * @deprecated Use countItems(UUID, String) instead for node-scoped counting
     */
    @Transactional(readOnly = true)
    public long countItems(String runId) {
        return stepDataRepository.countDistinctItemsByRunId(runId);
    }

    /**
     * Result for a single item, optimized for per-item lazy loading.
     */
    public record SingleItemResult(int epoch, int itemIndex, Map<String, Object> data) {}

    /**
     * Render a single item with optimized resolution from DB storage.
     *
     * @param interfaceId Interface UUID
     * @param runId String run ID
     * @param tenantId Tenant ID
     * @param epoch Epoch to render
     * @param itemIndex Item index to render
     * @return Resolved data for the item, or empty if not found
     */
    @Transactional(readOnly = true)
    public Optional<SingleItemResult> renderItem(UUID interfaceId, String runId, String tenantId, int epoch, int itemIndex) {
        // Cross-tenant fix: resolve the run owner's tenantId for storage queries
        String ownerTenantId = resolveRunOwnerTenantId(runId, tenantId);

        // Get workflowRunId with lightweight query (no JSONB loading)
        UUID workflowRunId = findWorkflowRunId(runId);

        TemplateConfig config = getTemplateConfigForRun(interfaceId, workflowRunId, ownerTenantId);
        Map<String, String> mappings = config.variableMappings();
        boolean hasMappings = mappings != null && !mappings.isEmpty();

        // Resolve with SpEL-based resolution (empty map if no variable mappings)
        Map<String, Object> resolved = hasMappings
            ? resolveVariablesWithPagination(mappings, runId, epoch, 0, itemIndex, ownerTenantId, Map.of())
            : Map.of();

        // Return item with resolved data (may be empty for static interfaces)
        return Optional.of(new SingleItemResult(epoch, itemIndex, resolved));
    }

    /**
     * Resolve variables using RunContextService with SpEL evaluation.
     *
     * <p>Post-2026-05-22 OOM hardening: routes through
     * {@link RunContextService#evaluateExpressionsForItemNarrowed} which loads only the
     * step_keys referenced by the mappings (vs every storage row of the epoch) and skips
     * rows above {@code maxStorageRowBytes}. After resolve, each Collection variable is
     * clamped to {@code maxRowsPerVariable} and the cumulative byte budget
     * {@code maxResolvedVariableBytes} short-circuits the loop. {@code __truncated}
     * markers are stamped so templates can render "Showing N of M".
     */
    /**
     * The run's {@code {{$vars.*}}} bundle (workspace/personal variables), keyed
     * by runId in the RunScopedCache so repeated renders reuse one fetch. Fetched
     * with the run OWNER's tenant + org so a workspace variable resolves for any
     * viewer of the run. Empty when the cache is unwired (unit tests) or the run
     * row is gone.
     */
    private Map<String, Object> resolveVarsBundle(String runId, String ownerTenantId) {
        if (workflowVariableBundleCache == null || runId == null || ownerTenantId == null) {
            return Map.of();
        }
        String orgId = workflowRunRepository.findByRunIdPublic(runId)
                .map(WorkflowRunEntity::getOrgId)
                .orElse(null);
        return workflowVariableBundleCache.getBundle(runId, ownerTenantId, orgId);
    }

    private Map<String, Object> resolveVariablesOptimized(
            Map<String, String> mappings,
            String runId,
            int epoch,
            int spawn,
            int itemIndex,
            String tenantId) {

        if (mappings == null || mappings.isEmpty() || runId == null) {
            return Map.of();
        }

        logger.info("[InterfaceRender] Resolving {} variables for runId={}, epoch={}, spawn={}, itemIndex={}, mappings={}",
                mappings.size(), runId, epoch, spawn, itemIndex, mappings.keySet());

        // Per-run {{$vars.*}} bundle so interface variable_mapping can reference
        // workspace variables at render time. tenantId here is the run OWNER's
        // tenant (callers pass ownerTenantId); the org is read off the run row.
        Map<String, Object> varsBundle = resolveVarsBundle(runId, tenantId);

        // Narrowed path: storage rows filtered by referenced step_keys + size cap.
        // Pass maxRowsPerVariable for EARLY truncation (before SpEL evaluation) to prevent
        // OOM from large deserialized collections occupying heap during resolution.
        Map<String, Object> resolved = runContextService.evaluateExpressionsForItemNarrowed(
                runId, tenantId, epoch, spawn, itemIndex, mappings,
                renderLimits.getMaxStorageRowBytes(), renderLimits.getMaxRowsPerVariable(), varsBundle);

        // Post-SpEL clamp + cumulative byte budget short-circuit. Mutates {@code resolved}
        // in-place rebuilt into a new map so iteration order is preserved.
        resolved = clampResolvedVariables(resolved);

        for (var entry : resolved.entrySet()) {
            Object val = entry.getValue();
            String preview = val == null ? "null"
                    : val instanceof java.util.Collection<?> c ? c.getClass().getSimpleName() + "(size=" + c.size() + ")"
                    : val instanceof Map<?,?> m ? "Map(keys=" + m.keySet() + ")"
                    : String.valueOf(val).length() > 80 ? String.valueOf(val).substring(0, 80) + "..." : String.valueOf(val);
            logger.info("[InterfaceRender] var {} = {}", entry.getKey(), preview);
        }

        Set<String> missing = new java.util.LinkedHashSet<>(mappings.keySet());
        missing.removeAll(resolved.keySet());
        if (!missing.isEmpty()) {
            logger.warn("[InterfaceRender] UNRESOLVED variables: {}", missing);
        }

        return resolved;
    }

    /**
     * Resolve variables with SQL-level pagination where applicable.
     *
     * <p>For each variable, attempt the SQL JSONB array-slice path via
     * {@link RunContextService#resolveVariablePaginated}. Explicit entries in
     * {@code variablePages} select the requested page; otherwise page 0 is used as the
     * bounded default. If successful, the variable value is the paginated slice and
     * pagination metadata markers are added. If the SQL path isn't applicable (complex
     * SpEL, non-array, etc.), fall through to the standard narrowed path.
     *
     * <p>This server-side default keeps initial renders safe even before the frontend has
     * loaded/persisted variable page state for large arrays.
     */
    private Map<String, Object> resolveVariablesWithPagination(
            Map<String, String> mappings,
            String runId,
            int epoch,
            int spawn,
            int itemIndex,
            String tenantId,
            Map<String, Integer> variablePages) {

        if (mappings == null || mappings.isEmpty() || runId == null) {
            return Map.of();
        }

        int varPageSize = renderLimits.getMaxRowsPerVariable();
        Map<String, Integer> requestedPages = variablePages != null ? variablePages : Map.of();

        // Split mappings: paginated vs standard
        Map<String, String> standardMappings = new LinkedHashMap<>();
        Map<String, Object> paginatedResults = new LinkedHashMap<>();

        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            String varName = entry.getKey();
            String expression = entry.getValue();

            int varPage = Math.max(0, requestedPages.getOrDefault(varName, 0));
            RunContextService.PaginatedVariable paginated = runContextService.resolveVariablePaginated(
                expression, runId, tenantId, epoch, varPage, varPageSize);

            if (paginated != null) {
                List<Object> paginatedItems = paginated.items() != null ? paginated.items() : List.of();
                int pageSize = Math.max(1, paginated.pageSize());
                int totalPages = Math.max(1, (int) Math.ceil((double) paginated.totalCount() / pageSize));
                paginatedResults.put(varName, paginatedItems);
                paginatedResults.put(varName + "__total", paginated.totalCount());
                paginatedResults.put(varName + "__page", paginated.page());
                paginatedResults.put(varName + "__pageSize", pageSize);
                paginatedResults.put(varName + "__count", paginatedItems.size());
                paginatedResults.put(varName + "__totalPages", totalPages);
                paginatedResults.put(varName + "__truncated", paginated.totalCount() > pageSize);
                paginatedResults.put(varName + "__paginationSupported", true);
                logger.info("[InterfaceRender] SQL-paginated var '{}': page={}, pageSize={}, total={}",
                    varName, paginated.page(), paginated.pageSize(), paginated.totalCount());
            } else {
                // SQL path not applicable - fall back to standard resolution
                standardMappings.put(varName, expression);
            }
        }

        // Resolve remaining variables via the standard narrowed path.
        Map<String, Object> standardResults = standardMappings.isEmpty()
            ? Map.of()
            : resolveVariablesOptimized(standardMappings, runId, epoch, spawn, itemIndex, tenantId);

        // Merge results: paginated first, then standard
        Map<String, Object> merged = new LinkedHashMap<>(paginatedResults);
        merged.putAll(standardResults);
        return merged;
    }

    /**
     * Apply the three render caps to the resolved variable map:
     * <ol>
     *   <li>{@code maxRowsPerVariable} - each {@code Collection} value is truncated to N
     *       elements; a sibling {@code <name>__truncated=true} + {@code <name>__total=M}
     *       pair is added so templates can render "Showing N of M".
     *   <li>{@code maxResolvedVariableBytes} - running total of estimated bytes
     *       (UTF-16 length × 2 for strings; element count × 32 B sentinel for collections)
     *       short-circuits the loop once exceeded. Variables already added stay; subsequent
     *       ones are dropped and a single {@code __resolved_variables_truncated} flag is
     *       added at top level.
     *   <li>{@code onExceed=fail} converts the truncation into an
     *       {@link IllegalStateException}.
     * </ol>
     */
    private Map<String, Object> clampResolvedVariables(Map<String, Object> resolved) {
        if (resolved == null || resolved.isEmpty()) return resolved;

        int maxRows = renderLimits.getMaxRowsPerVariable();
        int maxBytes = renderLimits.getMaxResolvedVariableBytes();
        OrchestratorLimitsConfig.OnExceed onExceed = renderLimits.getOnExceed();

        Map<String, Object> capped = new java.util.LinkedHashMap<>(resolved.size());
        long cumulativeBytes = 0L;
        boolean truncatedByBytes = false;

        for (var entry : resolved.entrySet()) {
            if (truncatedByBytes) break;

            String key = entry.getKey();
            Object value = entry.getValue();

            // Per-variable row cap (Collection only).
            if (value instanceof java.util.Collection<?> c && c.size() > maxRows) {
                if (onExceed == OrchestratorLimitsConfig.OnExceed.fail) {
                    throw new IllegalStateException(
                        "Variable '" + key + "' has " + c.size() + " elements, exceeds maxRowsPerVariable="
                            + maxRows + " (onExceed=fail).");
                }
                List<Object> copy = new ArrayList<>(c);
                List<Object> visible = new ArrayList<>(copy.subList(0, maxRows));
                capped.put(key, visible);
                capped.put(key + "__total", c.size());
                capped.put(key + "__page", 0);
                capped.put(key + "__pageSize", maxRows);
                capped.put(key + "__count", visible.size());
                capped.put(key + "__totalPages", (int) Math.ceil((double) c.size() / maxRows));
                capped.put(key + "__paginationSupported", false);
                capped.put(key + "__truncated", true);
                logger.warn("[InterfaceRender] var '{}' truncated: {} → {} rows (maxRowsPerVariable)",
                    key, c.size(), maxRows);
                cumulativeBytes += (long) maxRows * 64L; // conservative per-row estimate
            } else {
                capped.put(key, value);
                cumulativeBytes += estimateBytes(value);
            }

            if (cumulativeBytes > maxBytes) {
                if (onExceed == OrchestratorLimitsConfig.OnExceed.fail) {
                    throw new IllegalStateException(
                        "Cumulative resolved variable bytes " + cumulativeBytes + " exceeds maxResolvedVariableBytes="
                            + maxBytes + " (onExceed=fail).");
                }
                truncatedByBytes = true;
                capped.put("__resolved_variables_truncated", true);
                capped.put("__resolved_variables_bytes", cumulativeBytes);
                logger.warn("[InterfaceRender] resolved variables byte budget exceeded after key '{}': {} > {}",
                    key, cumulativeBytes, maxBytes);
            }
        }
        return capped;
    }

    /**
     * Cheap, approximate byte-size estimate. Recurses ONE level into Collections and Maps
     * so a {@code {"rows": [485 × {…}]}} payload (the 2026-05-22 prod-OOM shape) measures
     * its real cost (~485 × element-estimate), not the top-level 1-entry shell.
     *
     * <p>Strings = UTF-16 length × 2; Map/Collection cost = own overhead + sum of one-level
     * child estimates capped at the per-call budget so a malicious deeply-nested input
     * cannot cause this estimator itself to allocate large transient state. Children are
     * estimated via the scalar fallback (no further recursion), which keeps the estimator
     * O(N) in the top-level size of the largest child collection.
     */
    private static long estimateBytes(Object value) {
        if (value == null) return 8L;
        if (value instanceof String s) return (long) s.length() * 2L;
        if (value instanceof java.util.Collection<?> c) {
            long total = 64L;
            int counted = 0;
            for (Object element : c) {
                total += estimateScalar(element);
                if (++counted >= 1000) {
                    // Cap traversal to bound the estimator's own cost; remaining elements
                    // are accounted for via the scalar fallback (×128 B sentinel).
                    total += (long) (c.size() - counted) * 128L;
                    break;
                }
            }
            return total;
        }
        if (value instanceof Map<?, ?> m) {
            long total = 128L;
            int counted = 0;
            for (var entry : m.entrySet()) {
                total += estimateScalar(entry.getKey()) + estimateScalar(entry.getValue());
                if (++counted >= 1000) {
                    total += (long) (m.size() - counted) * 192L;
                    break;
                }
            }
            return total;
        }
        return 32L;
    }

    private static long estimateScalar(Object v) {
        if (v == null) return 8L;
        if (v instanceof String s) return (long) s.length() * 2L;
        if (v instanceof java.util.Collection<?> c) return 32L + (long) c.size() * 32L;
        if (v instanceof Map<?, ?> m) return 64L + (long) m.size() * 48L;
        return 16L;
    }

    /**
     * Get run-info metadata for an interface (template, config, resolved indices).
     * Returns everything the frontend needs to start rendering without item data.
     */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> getRunInfo(UUID interfaceId, String runId, String tenantId) {
        // Cross-tenant fix: resolve the run owner's tenantId for storage queries
        String ownerTenantId = resolveRunOwnerTenantId(runId, tenantId);

        // Get workflowRunId with lightweight query (no JSONB loading)
        UUID workflowRunId = findWorkflowRunId(runId);

        TemplateConfig config = getTemplateConfigForRun(interfaceId, workflowRunId, ownerTenantId);
        if (config.htmlTemplate() == null) return Optional.empty();

        // Get distinct (epoch, spawn, itemIndex) triples scoped to the interface node.
        // Pagination is by (epoch, itemIndex) - spawn is a rerun counter, keep only latest.
        String interfaceNormalizedKey = resolveInterfaceNormalizedKey(interfaceId, runId);
        List<EpochItemProjection> projections;
        if (interfaceNormalizedKey != null) {
            projections = stepDataRepository.findDistinctEpochItemPairsByRunIdAndNormalizedKey(runId, interfaceNormalizedKey);
        } else {
            projections = stepDataRepository.findDistinctEpochItemPairsExcludingTriggers(runId);
        }

        // Build epoch timestamps: sorted list aligned with page indices (newest first)
        // Group by (epoch, itemIndex) keeping only latest spawn
        record EpochItem(int epoch, int spawn, int itemIndex, Instant startTime) implements Comparable<EpochItem> {
            @Override
            public int compareTo(EpochItem other) {
                int cmp = Integer.compare(other.epoch, this.epoch);
                return cmp != 0 ? cmp : Integer.compare(other.itemIndex, this.itemIndex);
            }
        }
        Map<String, EpochItem> latestByGroup = new HashMap<>();
        for (EpochItemProjection p : projections) {
            int ep = p.getEpoch() != null ? p.getEpoch() : 0;
            int sp = p.getSpawn() != null ? p.getSpawn() : 0;
            int idx = p.getItemIndex() != null ? p.getItemIndex() : 0;
            String groupKey = ep + ":" + idx;
            EpochItem existing = latestByGroup.get(groupKey);
            if (existing == null || sp > existing.spawn()) {
                latestByGroup.put(groupKey, new EpochItem(ep, sp, idx, p.getMinStartTime()));
            }
        }
        List<EpochItem> sorted = new ArrayList<>(latestByGroup.values());
        Collections.sort(sorted);

        long totalItems = sorted.size();

        // Page-index-aligned list of ISO timestamps (null entries become JSON null)
        List<String> epochTimestamps = sorted.stream()
            .map(e -> e.startTime() != null ? e.startTime().toString() : null)
            .toList();

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("htmlTemplate", config.htmlTemplate());
        info.put("cssTemplate", config.cssTemplate());
        info.put("jsTemplate", config.jsTemplate());
        info.put("totalItems", totalItems);
        info.put("actionMappings", config.actionMappings());
        info.put("epochTimestamps", epochTimestamps);
        return Optional.of(info);
    }

    /**
     * Render the interface for the given epoch and return the resolved-HTML snapshot - the same
     * thing the iframe shows. Centralizes the {@code render() + items[0].data() + applyDefaults}
     * sequence so the screenshot path and the InterfaceNode {@code exposeRenderedSource} path stay
     * in lockstep. Returns {@link Optional#empty()} when the interface has no HTML template.
     */
    @Transactional(readOnly = true)
    public Optional<ResolvedTemplateSnapshot> resolveTemplateSnapshot(UUID interfaceId, String runId, String tenantId, int epoch) {
        InterfaceRenderResult result = render(interfaceId, runId, tenantId, 0, 0, epoch);
        if (result == null || result.htmlTemplate() == null) {
            return Optional.empty();
        }
        List<ItemRenderData> items = result.items();
        Map<String, Object> vars = (items != null && !items.isEmpty() && items.get(0).data() != null)
            ? items.get(0).data()
            : Map.of();
        String resolvedHtml = InterfaceTemplateDefaults.apply(result.htmlTemplate(), vars);
        return Optional.of(new ResolvedTemplateSnapshot(resolvedHtml, result.cssTemplate(), result.jsTemplate(), vars));
    }

    /**
     * Résout un template HTML avec les données d'un item en utilisant le moteur d'expressions.
     */
    public String resolveHtmlWithExpressions(String htmlTemplate, Map<String, Object> resolvedData) {
        if (htmlTemplate == null || htmlTemplate.isEmpty()) {
            return htmlTemplate;
        }
        return templateEngine.resolveWithMap(htmlTemplate, resolvedData);
    }

    /**
     * Retourne la liste des fonctions disponibles pour le moteur d'expressions.
     */
    public Map<String, String> getAvailableFunctions() {
        Map<String, String> functions = new LinkedHashMap<>();
        functions.put("formatDate(value, pattern)", "Format a date. Patterns: 'DD/MM/YYYY', 'YYYY-MM-DD HH:mm'");
        functions.put("formatNumber(value, decimals)", "Format a number with separators");
        functions.put("formatCurrency(value, code)", "Format as currency. Ex: formatCurrency(100, 'EUR')");
        functions.put("truncate(text, max, suffix)", "Truncate text. Ex: truncate(text, 50, '...')");
        functions.put("uppercase(text)", "Convert to uppercase");
        functions.put("lowercase(text)", "Convert to lowercase");
        functions.put("default(value, fallback)", "Return fallback if value is null/empty");
        functions.put("typeof(value)", "Get type name: string, int, double, bool, list, map");
        functions.put("len(value)", "Get length of string, list, or map (alias for size)");
        functions.put("json(value)", "Parse a JSON string into a typed Map/List/scalar (idempotent). Use when a field must be an object/array, e.g. {{json(mcp:fetch.output.body)}}");
        functions.put("fromjson(value)", "Alias for json() - GitHub Actions parity");
        functions.put("tojson(value)", "Serialize a Map/List/scalar to a compact JSON string");
        return functions;
    }

    /**
     * Render an interface using datasource data (not workflow run data).
     * Uses InterfaceClient to fetch interface data.
     *
     * <p>Bounded by {@link OrchestratorLimitsConfig}:
     * <ul>
     *   <li>{@code maxItemsPerRender} clamps {@code size}, so the orchestrator never asks the
     *       datasource client for more than the cap. Replaces the previous {@code getAllItems()}
     *       call that pulled the entire table into heap before paginating in memory.</li>
     *   <li>{@code maxPayloadBytes} caps the cumulative resolved-HTML size. Items past the budget
     *       are dropped from the response and the truncation flag is set, so the iframe never
     *       receives a payload large enough to freeze the browser.</li>
     *   <li>{@code onExceed=fail} converts a clamp into an {@link IllegalStateException} for
     *       callers that need explicit failure rather than silent truncation.</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public InterfaceRenderResult renderWithDatasource(UUID interfaceId, String tenantId, int page, int size) {
        // 1. Get the interface via client
        InterfaceDto iface = interfaceClient.getInterfaceTemplateForRender(interfaceId);
        if (iface == null) {
            logger.warn("Interface not found: {}", interfaceId);
            return new InterfaceRenderResult("", null, null, List.of(), new PaginationInfo(page, size, 0, 0), Map.of());
        }

        String htmlTemplate = iface.getHtmlTemplate();
        String cssTemplate = iface.getCssTemplate();
        String jsTemplate = iface.getJsTemplate();

        // 1b. Clamp size to the configured per-render cap BEFORE we ask the datasource for rows.
        // Without this, the previous getAllItems() loaded the entire table (up to the
        // client-side 10k default) into orchestrator heap, then subList()'d 99% away.
        int requestedSize = size > 0 ? size : 10;
        int maxItems = renderLimits.getMaxItemsPerRender();
        int effectiveSize = Math.min(requestedSize, maxItems);
        boolean clampedByCount = effectiveSize < requestedSize;
        if (clampedByCount) {
            if (renderLimits.getOnExceed() == OrchestratorLimitsConfig.OnExceed.fail) {
                throw new IllegalStateException(
                    "Interface render size " + requestedSize + " exceeds maxItemsPerRender=" + maxItems
                        + " (onExceed=fail). Lower the page size or paginate.");
            }
            logger.warn("[InterfaceRender] datasource render: requested size={} clamped to {} (interfaceId={})",
                    requestedSize, effectiveSize, interfaceId);
        }

        if (htmlTemplate == null || htmlTemplate.isEmpty()) {
            logger.warn("No template found for interface {}", interfaceId);
            return new InterfaceRenderResult("", cssTemplate, jsTemplate, List.of(), new PaginationInfo(page, effectiveSize, 0, 0), Map.of());
        }

        // 2. Check if interface has a datasource
        Long dataSourceId = iface.getDataSourceId();
        if (dataSourceId == null) {
            logger.debug("Interface {} has no datasource attached", interfaceId);
            return new InterfaceRenderResult(htmlTemplate, cssTemplate, jsTemplate, List.of(), new PaginationInfo(page, effectiveSize, 0, 0), Map.of());
        }

        // 3. Bounded fetch: count first (cheap), then fetch only the page we need.
        // True totalItems comes from a dedicated count endpoint so pagination metadata stays
        // accurate even when the actual fetch is clamped by maxItemsPerRender.
        long totalItems = dataSourceClient.getItemsCount(dataSourceId, tenantId);
        if (totalItems <= 0L) {
            logger.debug("No items found in datasource {}", dataSourceId);
            return new InterfaceRenderResult(htmlTemplate, cssTemplate, jsTemplate, List.of(), new PaginationInfo(page, effectiveSize, 0, 0), Map.of());
        }

        int totalPages = (int) Math.ceil((double) totalItems / effectiveSize);
        int fromIndex = page * effectiveSize;
        if (fromIndex >= totalItems) {
            return new InterfaceRenderResult(htmlTemplate, cssTemplate, jsTemplate, List.of(),
                    new PaginationInfo(page, effectiveSize, totalItems, totalPages), Map.of());
        }

        List<DataSourceItemDto> pageItems = dataSourceClient.getItems(dataSourceId, tenantId, fromIndex, effectiveSize);

        // 4. Convert each item to resolved data and apply expression engine, accumulating bytes.
        //    Stop when the cumulative resolved-HTML bytes exceed maxPayloadBytes - this protects
        //    against items with small COUNT but huge BYTES (long descriptions, inline base64).
        int maxBytes = renderLimits.getMaxPayloadBytes();
        long cumulativeBytes = 0L;
        boolean clampedByBytes = false;
        List<ItemRenderData> items = new ArrayList<>();
        for (int i = 0; i < pageItems.size(); i++) {
            DataSourceItemDto dsItem = pageItems.get(i);
            Map<String, Object> itemData = dsItem.data();

            Map<String, Object> resolvedData = new LinkedHashMap<>();
            if (itemData != null) {
                for (Map.Entry<String, Object> entry : itemData.entrySet()) {
                    resolvedData.put(entry.getKey(), entry.getValue());
                }
                for (Map.Entry<String, Object> entry : itemData.entrySet()) {
                    resolvedData.put("current_item." + entry.getKey(), entry.getValue());
                }
            }

            String resolvedHtml = templateEngine.resolveWithMap(htmlTemplate, resolvedData);
            long itemBytes = resolvedHtml == null ? 0L : (long) resolvedHtml.length() * 2L; // UTF-16
            // First-item bypass: an item whose rendered weight alone exceeds maxPayloadBytes is
            // still included so the user always sees something rather than an empty interface.
            // DO NOT simplify this to `cumulativeBytes + itemBytes > maxBytes` - that would make
            // the cap fail-closed on any render where item[0] is bigger than the budget.
            // Regression coverage: InterfaceRenderServiceRenderLimitsTest#firstItemAlwaysIncluded.
            if (!items.isEmpty() && cumulativeBytes + itemBytes > maxBytes) {
                clampedByBytes = true;
                if (renderLimits.getOnExceed() == OrchestratorLimitsConfig.OnExceed.fail) {
                    throw new IllegalStateException(
                        "Interface render payload " + (cumulativeBytes + itemBytes) + " B exceeds maxPayloadBytes="
                            + maxBytes + " (onExceed=fail). Trim the template or paginate.");
                }
                logger.warn("[InterfaceRender] datasource render: bytes budget {} exceeded after {} items (interfaceId={}, ds={})",
                        maxBytes, items.size(), interfaceId, dataSourceId);
                break;
            }
            cumulativeBytes += itemBytes;
            resolvedData.put("_resolvedHtml", resolvedHtml);
            items.add(new ItemRenderData(0, fromIndex + i, 0, resolvedData, Map.of()));
        }

        RenderTruncation truncation = null;
        if (clampedByBytes) {
            truncation = new RenderTruncation(totalItems, items.size(), "max_payload_bytes", cumulativeBytes);
        } else if (clampedByCount) {
            truncation = new RenderTruncation(totalItems, items.size(), "max_items_per_render", cumulativeBytes);
        }

        logger.debug("Rendered interface {} with {} items from datasource {} (total={}, truncation={})",
                interfaceId, items.size(), dataSourceId, totalItems, truncation);

        return new InterfaceRenderResult(
            htmlTemplate,
            cssTemplate,
            jsTemplate,
            items,
            new PaginationInfo(page, effectiveSize, totalItems, totalPages),
            Map.of(),
            truncation
        );
    }

    /**
     * Count items in a datasource for an interface.
     */
    @Transactional(readOnly = true)
    public long countDatasourceItems(UUID interfaceId, String tenantId) {
        InterfaceDto iface = interfaceClient.getInterfaceTemplateForRender(interfaceId);
        if (iface == null) {
            return 0;
        }

        Long dataSourceId = iface.getDataSourceId();
        if (dataSourceId == null) {
            return 0;
        }

        return dataSourceClient.getItemsCount(dataSourceId, tenantId);
    }

    /**
     * Get workflowRunId from runId using lightweight query.
     */
    private UUID findWorkflowRunId(String runId) {
        List<UUID> ids = stepDataRepository.findWorkflowRunIdsByRunId(runId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /**
     * Resolve the run owner's tenantId for cross-tenant scenarios.
     */
    private String resolveRunOwnerTenantId(String runId, String fallbackTenantId) {
        if (runId == null) return fallbackTenantId;
        try {
            Optional<WorkflowRunEntity> run = workflowRunRepository.findByRunIdPublic(runId);
            if (run.isPresent()) {
                String ownerTenantId = run.get().getTenantId();
                if (ownerTenantId != null && !ownerTenantId.equals(fallbackTenantId)) {
                    logger.info("[InterfaceRender] Cross-tenant detected: caller={} owner={} runId={}",
                            fallbackTenantId, ownerTenantId, runId);
                }
                return ownerTenantId != null ? ownerTenantId : fallbackTenantId;
            }
        } catch (Exception e) {
            logger.warn("[InterfaceRender] Failed to resolve run owner tenantId for runId={}: {}", runId, e.getMessage());
        }
        return fallbackTenantId;
    }

    /**
     * Extract trigger data from the run context.
     */
    private Map<String, Map<String, Object>> extractTriggerData(String runId, String tenantId, Map<String, String> actionMappings, int epoch, int spawn, int itemIndex) {
        if (actionMappings == null || actionMappings.isEmpty() || runId == null) {
            return Map.of();
        }

        Set<String> triggerKeys = new LinkedHashSet<>();
        for (String triggerRef : actionMappings.values()) {
            String triggerKey = removeActionTypeSuffix(triggerRef);
            triggerKeys.add(triggerKey);
        }

        logger.info("[InterfaceRender] extractTriggerData: runId={} epoch={} spawn={} itemIndex={} triggerKeys={}", runId, epoch, spawn, itemIndex, triggerKeys);

        Map<String, Object> context = runContextService.loadRunContextForItemNarrowed(
                runId, tenantId, epoch, spawn, itemIndex, triggerKeys,
                renderLimits.getMaxStorageRowBytes(), 0);

        logger.info("[InterfaceRender] Run context keys for trigger extraction: {}", context.keySet());

        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String triggerKey : triggerKeys) {
            String shortKey = triggerKey.startsWith("trigger:") ? triggerKey.substring("trigger:".length()) : triggerKey;
            String[] candidateKeys = {
                triggerKey + ".output",
                shortKey + ".output",
                triggerKey,
                shortKey
            };

            Object triggerOutput = null;
            String matchedKey = null;
            for (String candidate : candidateKeys) {
                Object value = context.get(candidate);
                if (value != null) {
                    triggerOutput = value;
                    matchedKey = candidate;
                    break;
                }
            }

            if (triggerOutput instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rawMap = (Map<String, Object>) triggerOutput;
                logger.info("[InterfaceRender] Raw trigger data for key={} (matched={}): fields={}", triggerKey, matchedKey, rawMap.keySet());

                Object outputField = rawMap.get("output");
                if (outputField instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> userPayload = (Map<String, Object>) outputField;
                    result.put(triggerKey, userPayload);
                    logger.info("[InterfaceRender] Extracted trigger user payload for key={}: fields={}", triggerKey, userPayload.keySet());
                } else {
                    result.put(triggerKey, rawMap);
                    logger.info("[InterfaceRender] Using raw trigger data for key={} (no 'output' sub-key): fields={}", triggerKey, rawMap.keySet());
                }
            } else {
                logger.info("[InterfaceRender] No trigger data found for key={} (tried={}), available context keys={}",
                        triggerKey, java.util.Arrays.toString(candidateKeys), context.keySet());
            }
        }

        return result;
    }

    /**
     * Resolve the normalizedKey for a specific interface node in a run.
     * Uses step_data to find interface nodes, then matches by interfaceId if multiple.
     *
     * @param interfaceId The interface UUID
     * @param runId The run ID
     * @return The normalizedKey (e.g., "interface:display_results"), or null if not found
     */
    private String resolveInterfaceNormalizedKey(UUID interfaceId, String runId) {
        List<String> interfaceKeys = stepDataRepository.findInterfaceNormalizedKeysByRunId(runId);
        if (interfaceKeys.isEmpty()) {
            return null;
        }
        if (interfaceKeys.size() == 1) {
            return interfaceKeys.get(0);
        }

        // Multiple interfaces: match via the workflow plan
        try {
            Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findByRunIdPublic(runId);
            if (runOpt.isPresent() && runOpt.get().getPlan() != null) {
                Map<String, Object> planMap = runOpt.get().getPlan();
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> interfacesData = (List<Map<String, Object>>) planMap.get("interfaces");
                if (interfacesData != null) {
                    String targetId = interfaceId.toString();
                    for (Map<String, Object> ifaceDef : interfacesData) {
                        String defId = (String) ifaceDef.get("id");
                        if (targetId.equals(defId) || targetId.equalsIgnoreCase(defId)) {
                            String label = (String) ifaceDef.get("label");
                            if (label != null) {
                                String key = "interface:" + com.apimarketplace.orchestrator.utils.LabelNormalizer.normalizeLabel(label);
                                if (interfaceKeys.contains(key)) {
                                    return key;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("[InterfaceRender] Failed to resolve normalizedKey from plan for interfaceId={}: {}", interfaceId, e.getMessage());
        }

        // Fallback: return the first key (best effort)
        logger.warn("[InterfaceRender] Could not match interfaceId={} to a specific normalizedKey among {}, using first", interfaceId, interfaceKeys);
        return interfaceKeys.get(0);
    }

    /**
     * Remove action type suffix from trigger ref.
     */
    private static String removeActionTypeSuffix(String triggerRef) {
        if (triggerRef == null) return triggerRef;
        String[] parts = triggerRef.split(":");
        if (parts.length >= 3) {
            String lastPart = parts[parts.length - 1];
            if ("submit".equals(lastPart) || "click".equals(lastPart) || "message".equals(lastPart)) {
                return String.join(":", java.util.Arrays.copyOf(parts, parts.length - 1));
            }
        }
        return triggerRef;
    }
}
