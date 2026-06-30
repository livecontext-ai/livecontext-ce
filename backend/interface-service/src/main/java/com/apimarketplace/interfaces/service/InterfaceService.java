package com.apimarketplace.interfaces.service;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.interfaces.client.OrchestratorCascadeClient;
import com.apimarketplace.interfaces.domain.InterfaceEntity;
import com.apimarketplace.interfaces.repository.InterfaceListView;
import com.apimarketplace.interfaces.repository.InterfaceRepository;
import com.apimarketplace.publication.client.PublicationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class InterfaceService {

    private static final Logger log = LoggerFactory.getLogger(InterfaceService.class);

    private final InterfaceRepository interfaceRepository;
    private final InterfaceVariableExtractor variableExtractor;
    private final StorageBreakdownService breakdownService;
    private final OrgAccessGuard orgAccessService;
    private final com.apimarketplace.auth.client.entitlement.EntitlementGuard entitlementGuard;
    // Optional so tests that don't exercise the cascade path can omit the bean.
    // Production wiring (OrchestratorClientConfig) always provides it.
    private final OrchestratorCascadeClient orchestratorCascadeClient;
    // Optional: present in production (PublicationClientConfig); null in tests that don't exercise
    // the paged-list status path. When null, the paged list skips the visibility filter and emits no
    // publication badges (best-effort, mirrors PublicationClient's own fail-soft behaviour).
    private final PublicationClient publicationClient;

    @Autowired
    public InterfaceService(InterfaceRepository interfaceRepository,
                            InterfaceVariableExtractor variableExtractor,
                            StorageBreakdownService breakdownService,
                            OrgAccessGuard orgAccessService,
                            com.apimarketplace.auth.client.entitlement.EntitlementGuard entitlementGuard,
                            OrchestratorCascadeClient orchestratorCascadeClient,
                            PublicationClient publicationClient) {
        this.interfaceRepository = interfaceRepository;
        this.variableExtractor = variableExtractor;
        this.breakdownService = breakdownService;
        this.orgAccessService = orgAccessService;
        this.entitlementGuard = entitlementGuard;
        this.orchestratorCascadeClient = orchestratorCascadeClient;
        this.publicationClient = publicationClient;
    }

    // Overload without the publication client (back-compat for callers that don't stamp badges).
    public InterfaceService(InterfaceRepository interfaceRepository,
                            InterfaceVariableExtractor variableExtractor,
                            StorageBreakdownService breakdownService,
                            OrgAccessGuard orgAccessService,
                            com.apimarketplace.auth.client.entitlement.EntitlementGuard entitlementGuard,
                            OrchestratorCascadeClient orchestratorCascadeClient) {
        this(interfaceRepository, variableExtractor, breakdownService,
             orgAccessService, entitlementGuard, orchestratorCascadeClient, null);
    }

    // Convenience overload for unit tests that don't need the cascade / publication paths.
    public InterfaceService(InterfaceRepository interfaceRepository,
                            InterfaceVariableExtractor variableExtractor,
                            StorageBreakdownService breakdownService,
                            OrgAccessGuard orgAccessService,
                            com.apimarketplace.auth.client.entitlement.EntitlementGuard entitlementGuard) {
        this(interfaceRepository, variableExtractor, breakdownService,
             orgAccessService, entitlementGuard, null, null);
    }

    /**
     * Create an interface from a publication snapshot - carries interfaceType/data/sourcePublicationId
     * which the basic createInterface signature does not accept. Mirrors the pattern used by
     * DataSourceService.createFromSnapshot so the publication pipeline stays symmetric across
     * resource types.
     */
    public InterfaceEntity createFromSnapshot(String tenantId,
                                              String name,
                                              String description,
                                              String htmlTemplate,
                                              String cssTemplate,
                                              String jsTemplate,
                                              String interfaceType,
                                              Map<String, Object> data,
                                              Long dataSourceId,
                                              UUID sourcePublicationId,
                                              String organizationId) {
        validateCreateOrUpdate(tenantId, name);

        if (entitlementGuard != null) {
            entitlementGuard.check(tenantId,
                    com.apimarketplace.auth.client.entitlement.ResourceType.INTERFACE,
                    () -> interfaceRepository.countByTenantId(tenantId));
        }

        InterfaceEntity entity = new InterfaceEntity();
        entity.setTenantId(tenantId);
        entity.setName(name);
        entity.setDescription(description);
        entity.setHtmlTemplate(htmlTemplate);
        entity.setCssTemplate(cssTemplate);
        entity.setJsTemplate(jsTemplate);
        entity.setIsPublic(false);
        entity.setIsActive(true);
        if (interfaceType != null && !interfaceType.isBlank()) {
            entity.setInterfaceType(interfaceType);
        }
        if (data != null) {
            entity.setData(data);
        }
        if (dataSourceId != null) {
            entity.setDataSourceId(dataSourceId);
        }
        if (sourcePublicationId != null) {
            entity.setSourcePublicationId(sourcePublicationId);
        }
        if (organizationId != null && !organizationId.isBlank()) {
            entity.setOrganizationId(organizationId);
        }

        updateTemplateVariables(entity);

        InterfaceEntity saved = interfaceRepository.save(entity);
        breakdownService.trackSave(tenantId, "INTERFACES", estimateInterfaceSize(saved));

        log.info("[InterfaceService] Created interface from snapshot {} name={} pub={}",
                saved.getId(), name, sourcePublicationId);
        return saved;
    }

    public InterfaceEntity createInterface(String tenantId,
                                           String name,
                                           String description,
                                           String htmlTemplate,
                                           String cssTemplate,
                                           String jsTemplate,
                                           UUID workflowRunId,
                                           Long stepDataId,
                                           String targetTable,
                                           Long dataSourceId,
                                           Boolean isPublic,
                                           Boolean isActive,
                                           String organizationId) {
        validateCreateOrUpdate(tenantId, name);

        // Plan resource limit check (REST + LLM tool path). Null-safe for unit tests
        // that instantiate the service without a Spring context.
        if (entitlementGuard != null) {
            entitlementGuard.check(tenantId,
                    com.apimarketplace.auth.client.entitlement.ResourceType.INTERFACE,
                    () -> interfaceRepository.countByTenantId(tenantId));
        }

        InterfaceEntity entity = new InterfaceEntity();
        entity.setTenantId(tenantId);
        entity.setName(name);
        entity.setDescription(description);
        entity.setHtmlTemplate(htmlTemplate);
        entity.setCssTemplate(cssTemplate);
        entity.setJsTemplate(jsTemplate);
        entity.setWorkflowRunId(workflowRunId);
        entity.setStepDataId(stepDataId);
        entity.setTargetTable(targetTable);
        entity.setDataSourceId(dataSourceId);
        entity.setIsPublic(isPublic != null ? isPublic : false);
        entity.setIsActive(isActive != null ? isActive : true);

        updateTemplateVariables(entity);

        if (organizationId != null) {
            entity.setOrganizationId(organizationId);
        }
        InterfaceEntity saved = interfaceRepository.save(entity);
        breakdownService.trackSave(tenantId, "INTERFACES", estimateInterfaceSize(saved));

        log.info("[InterfaceService] Created interface {} name={}", saved.getId(), name);
        return saved;
    }

    /**
     * #150 - scope-aware update. Routes the lookup through the strict-isolation
     * finder pair based on the caller's active workspace:
     * <ul>
     *   <li>{@code orgId != null} → org-strict (interface MUST live in that org).
     *       The caller's {@code tenantId} no longer gates the row; the org workspace
     *       does. The write visibility gate still applies via {@code orgAccessService.canWrite}
     *       with the gateway-validated {@code orgRole}.</li>
     *   <li>{@code orgId == null} → personal-strict (interface MUST be personal AND
     *       belong to the caller). Org rows are invisible even if the caller created
     *       them - workspace switch is required to act on them.</li>
     * </ul>
     * Cross-scope hit returns {@link IllegalArgumentException} ("Interface not found")
     * - same shape as the pre-#150 tenant-mismatch path so controllers map to 404 via
     * the existing exception handler.
     */
    public InterfaceEntity updateInterface(UUID id,
                                           String tenantId,
                                           String orgId,
                                           String orgRole,
                                           String name,
                                           String description,
                                           String htmlTemplate,
                                           String cssTemplate,
                                           String jsTemplate,
                                           UUID workflowRunId,
                                           Long stepDataId,
                                           String targetTable,
                                           Long dataSourceId,
                                           Boolean isPublic,
                                           Boolean isActive,
                                           Boolean updateDataSourceId) {
        InterfaceEntity existing = findInScope(id, tenantId, orgId)
            .orElseThrow(() -> new IllegalArgumentException("Interface not found: " + id));

        // PR-2.f (audit fix): close the deny-list asymmetry - update was
        // unguarded while delete/clone were. #150 - now threads the
        // gateway-validated orgRole through so OWNER/ADMIN bypass works
        // (was always MEMBER-strict on the inline gate).
        String existingOrgId = existing.getOrganizationId();
        if (existingOrgId != null
                && !orgAccessService.canWrite(existingOrgId, tenantId, "interface", id.toString(), orgRole)) {
            log.warn("OrgAccess denied: user {} restricted from updating interface {} in org {}",
                    tenantId, id, existingOrgId);
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                    "interface", id.toString());
        }

        validateCreateOrUpdate(tenantId, name != null ? name : existing.getName());

        if (name != null) existing.setName(name);
        if (description != null) existing.setDescription(description);
        boolean templateChanged = false;
        if (cssTemplate != null) {
            existing.setCssTemplate(cssTemplate);
            templateChanged = true;
        }
        if (jsTemplate != null) {
            existing.setJsTemplate(jsTemplate);
        }
        if (htmlTemplate != null) {
            existing.setHtmlTemplate(htmlTemplate);
            templateChanged = true;
        }
        if (templateChanged) {
            updateTemplateVariables(existing);
        }

        if (workflowRunId != null) {
            existing.setWorkflowRunId(workflowRunId);
        }
        if (stepDataId != null) {
            existing.setStepDataId(stepDataId);
        }

        if (targetTable != null) existing.setTargetTable(targetTable);
        if (updateDataSourceId != null && updateDataSourceId) {
            existing.setDataSourceId(dataSourceId);
            if (dataSourceId == null) {
                existing.setTargetTable(null);
            }
        }
        if (isPublic != null) existing.setIsPublic(isPublic);
        if (isActive != null) existing.setIsActive(isActive);

        long oldSize = estimateInterfaceSize(existing);
        InterfaceEntity saved = interfaceRepository.save(existing);
        // Size delta accrues to the row owner's storage budget, not the
        // caller's - relevant when a teammate edits via an org workspace.
        breakdownService.trackSizeChange(existing.getTenantId(), "INTERFACES", estimateInterfaceSize(saved) - oldSize);
        log.info("[InterfaceService] Updated interface {} with template_variables: {}", saved.getId(),
            saved.getTemplateVariables());

        return saved;
    }

    /**
     * Apply search/replace edits to ONE template column ({@code html} | {@code css} |
     * {@code js}) of an interface - the "edit" model coding agents use, so a few-line
     * change doesn't require re-sending the whole template.
     *
     * <p>Reads the current target content, applies the edits via
     * {@link InterfaceTemplatePatcher} (all-or-nothing - a non-matching edit throws and
     * NOTHING is written), then delegates to {@link #updateInterface} so the patch path
     * inherits the exact same guards as a full update: org deny-list, template-variable
     * recompute (html/css), storage size tracking, and any acquired-interface immutability
     * guard wired on that shared path (currently latent - {@link InterfaceImmutableException}
     * is defined but not yet thrown, so neither update nor patch blocks acquired interfaces).
     *
     * @param target one of {@code "html"}, {@code "css"}, {@code "js"} (case-insensitive)
     * @throws IllegalArgumentException if {@code target} is invalid or the interface is missing
     * @throws InterfaceTemplatePatcher.PatchException if any edit fails to match
     */
    public InterfaceEntity patchInterface(UUID id,
                                          String tenantId,
                                          String orgId,
                                          String orgRole,
                                          String target,
                                          List<InterfaceTemplatePatcher.Edit> edits,
                                          boolean replaceAll) {
        String normalizedTarget = target == null ? "" : target.trim().toLowerCase();
        if (!normalizedTarget.equals("html") && !normalizedTarget.equals("css") && !normalizedTarget.equals("js")) {
            throw new IllegalArgumentException("Invalid target '" + target + "'. Must be one of: html, css, js.");
        }

        InterfaceEntity existing = findInScope(id, tenantId, orgId)
            .orElseThrow(() -> new IllegalArgumentException("Interface not found: " + id));

        String current = switch (normalizedTarget) {
            case "html" -> existing.getHtmlTemplate();
            case "css"  -> existing.getCssTemplate();
            default     -> existing.getJsTemplate();
        };

        InterfaceTemplatePatcher.PatchResult result =
            InterfaceTemplatePatcher.apply(current, edits, replaceAll);

        String newHtml = normalizedTarget.equals("html") ? result.content() : null;
        String newCss  = normalizedTarget.equals("css")  ? result.content() : null;
        String newJs   = normalizedTarget.equals("js")   ? result.content() : null;

        return updateInterface(id, tenantId, orgId, orgRole,
            null, null, newHtml, newCss, newJs,
            null, null, null, null, null, null, null);
    }

    /**
     * #150 - scope-aware get. See {@link #findInScope} for the routing contract.
     * Cross-scope or unknown id → empty Optional (controller maps to 404).
     */
    @Transactional(readOnly = true)
    public Optional<InterfaceEntity> getInterface(UUID id, String tenantId, String orgId) {
        String orgRole = TenantResolver.currentRequestOrganizationRole();
        return getInterface(id, tenantId, orgId, orgRole);
    }

    @Transactional(readOnly = true)
    public Optional<InterfaceEntity> getInterface(UUID id, String tenantId, String orgId, String orgRole) {
        return findInScope(id, tenantId, orgId)
                .filter(entity -> canReadInterface(entity, tenantId, orgRole))
                .map(this::enrichWithFormFields);
    }

    /**
     * #150 - strict-scope finder. Post-V261, every user-scoped row carries a
     * non-null {@code organization_id} (gateway always injects
     * {@code X-Organization-ID}; personal workspaces resolve to the user's
     * personal-org UUID via {@code auth.organization_member.is_default=true}).
     * The lookup is therefore unconditionally org-strict:
     * {@code WHERE id = :id AND organization_id = :orgId}. Tenant ownership is
     * NOT checked here - the org workspace is the gate. The deny-list still
     * applies via {@link OrgAccessGuard#canAccess} in the caller.
     */
    @Transactional(readOnly = true)
    public Optional<InterfaceEntity> findInScope(UUID id, String tenantId, String orgId) {
        TenantResolver.requireOrgId(orgId);
        return interfaceRepository.findByIdAndOrganizationIdStrict(id, orgId);
    }

    private boolean canReadInterface(InterfaceEntity entity, String tenantId, String orgRole) {
        String interfaceOrgId = entity.getOrganizationId();
        if (interfaceOrgId == null || interfaceOrgId.isBlank()) {
            return true;
        }
        boolean allowed = orgAccessService.canAccess(
                interfaceOrgId, tenantId, "interface", entity.getId().toString(), orgRole);
        if (!allowed) {
            log.warn("OrgAccess deny-list: user {} restricted from reading interface {} in org {}",
                    tenantId, entity.getId(), interfaceOrgId);
        }
        return allowed;
    }

    /**
     * Get interface by ID without tenant check (for internal cross-service calls).
     */
    @Transactional(readOnly = true)
    public Optional<InterfaceEntity> getInterfaceInternal(UUID id) {
        return interfaceRepository.findById(id)
            .map(this::enrichWithFormFields);
    }

    /**
     * Get multiple interfaces by IDs (batch fetch, no tenant check).
     */
    @Transactional(readOnly = true)
    public List<InterfaceEntity> getInterfacesByIds(List<UUID> ids) {
        return interfaceRepository.findAllById(ids);
    }

    private InterfaceEntity enrichWithFormFields(InterfaceEntity entity) {
        if (entity.getHtmlTemplate() != null && !entity.getHtmlTemplate().isEmpty()) {
            entity.setFormFields(variableExtractor.extractFormFields(entity.getHtmlTemplate()));
        } else {
            entity.setFormFields(List.of());
        }
        return entity;
    }

    @Transactional(readOnly = true)
    public List<InterfaceEntity> listInterfaces(String tenantId, Boolean excludeTableAttached,
                                                  String orgId, String orgRole) {
        String decodedTenantId = tenantId != null ? tenantId.replace("%7C", "|") : tenantId;

        List<InterfaceEntity> interfaces;
        if (orgId != null && !orgId.isBlank()) {
            interfaces = interfaceRepository.findByOrganizationOrOwner(orgId, decodedTenantId);
            interfaces = orgAccessService.filterAccessible(interfaces, orgId, decodedTenantId, "interface", orgRole,
                    i -> i.getId().toString());
        } else {
            interfaces = interfaceRepository.findByTenantIdOrderByCreatedAtDesc(decodedTenantId);
        }

        if (Boolean.TRUE.equals(excludeTableAttached)) {
            return interfaces.stream()
                .filter(iface -> iface.getDataSourceId() == null)
                .collect(Collectors.toList());
        }
        return interfaces;
    }

    @Transactional(readOnly = true)
    public List<InterfaceEntity> listInterfacesByType(String tenantId, String interfaceType,
                                                      String orgId, String orgRole) {
        String decodedTenantId = tenantId != null ? tenantId.replace("%7C", "|") : tenantId;
        if (orgId != null && !orgId.isBlank()) {
            // Org workspace: load org-scoped, drop the member's deny-listed interfaces (per-member
            // RBAC) via filterAccessible - exactly like listInterfaces / listInterfacesPaged - THEN
            // narrow to the requested type. The plain tenant+type DB query skips filterAccessible,
            // so a DENY-restricted member would otherwise see deny-listed interfaces via ?type=.
            List<InterfaceEntity> all = interfaceRepository.findByOrganizationOrOwner(orgId, decodedTenantId);
            all = orgAccessService.filterAccessible(all, orgId, decodedTenantId, "interface", orgRole,
                    i -> i.getId().toString());
            return all.stream()
                    .filter(i -> interfaceType == null || interfaceType.equals(i.getInterfaceType()))
                    .collect(Collectors.toList());
        }
        return interfaceRepository.findByTenantIdAndInterfaceTypeOrderByCreatedAtDesc(decodedTenantId, interfaceType);
    }

    /**
     * Page envelope: items for the requested slice + total count (after filter) +
     * the per-row publication badge for the page ({@code publicationStatuses}: id ->
     * {@code {status, rejectionReason?}}, absent = not shared), batched server-side so
     * the card needs no per-row publication call. The search term `q` is matched
     * server-side against name + description (ILIKE).
     */
    public record InterfacePage(List<InterfaceEntity> items, int totalCount, int page, int size,
                                Map<String, Map<String, String>> publicationStatuses) {}

    /**
     * Server-paged, DB-searchable, server-sorted + server-visibility-filtered list - the resource
     * sibling of {@link com.apimarketplace.datasource.services.DataSourceService}'s {@code
     * getDataSourcesPaged}. The visibility filter derives from publication status (owned by
     * publication-service, a different schema - no SQL join), so when it is active the status batch is
     * resolved over the whole searched set BEFORE paginating; when it is "all" the status is only
     * needed for the page's badges and is fetched after slicing. Either way it is ONE HTTP call.
     * {@code sort} = name | lastModified (default lastModified); {@code visibility} = all | public |
     * private (default all).
     */
    @Transactional(readOnly = true)
    public InterfacePage listInterfacesPaged(String tenantId,
                                              String interfaceType,
                                              Boolean excludeTableAttached,
                                              String q,
                                              String orgId,
                                              String orgRole,
                                              int page,
                                              int size,
                                              String sort,
                                              String visibility) {
        String decodedTenantId = tenantId != null ? tenantId.replace("%7C", "|") : tenantId;

        // 1. Load the WHOLE tenant/org set as a LIGHTWEIGHT projection (no @Lob templates / data
        //    JSONB) - we only need it to filter, sort and slice. The page's full entities are
        //    fetched by id in step 5, so the heavy blobs are read for at most `size` rows, never the
        //    whole set. Search is applied at DB level when q is non-blank.
        List<InterfaceListView> all;
        boolean hasSearch = q != null && !q.isBlank();
        if (orgId != null && !orgId.isBlank()) {
            all = interfaceRepository.findViewByOrganizationId(orgId);
            all = orgAccessService.filterAccessible(all, orgId, decodedTenantId, "interface", orgRole,
                    v -> v.getId().toString());
            if (hasSearch) {
                String needle = q.trim().toLowerCase();
                all = all.stream()
                        .filter(v -> matchesNameOrDescription(v.getName(), v.getDescription(), needle))
                        .collect(Collectors.toList());
            }
        } else if (hasSearch) {
            all = interfaceRepository.searchViewByTenant(decodedTenantId, q.trim());
        } else {
            all = interfaceRepository.findViewByTenantIdOrderByCreatedAtDesc(decodedTenantId);
        }

        // 2. Optional type + table-attached filter (kept in-memory because the type query has no q variant).
        if (interfaceType != null && !interfaceType.isBlank()) {
            all = all.stream()
                    .filter(v -> interfaceType.equalsIgnoreCase(v.getInterfaceType()))
                    .collect(Collectors.toList());
        }
        if (Boolean.TRUE.equals(excludeTableAttached)) {
            all = all.stream()
                    .filter(v -> v.getDataSourceId() == null)
                    .collect(Collectors.toList());
        }

        // 3. Visibility filter (derives from publication status). When active, resolve the whole-set
        //    status ONCE before paginating and reuse it for the page badges below.
        String visFilter = visibility == null ? "all" : visibility.trim().toLowerCase();
        boolean filterByVisibility = publicationClient != null
                && (visFilter.equals("public") || visFilter.equals("private"));
        Map<String, PublicationClient.ResourcePublicationStatusRef> fullSetStatuses = filterByVisibility
                ? publicationClient.findResourcePublicationStatuses(
                        INTERFACE_PUBLICATION_TYPE, idsOfViews(all), decodedTenantId)
                : Map.of();
        if (filterByVisibility) {
            boolean wantPublic = visFilter.equals("public");
            final Map<String, PublicationClient.ResourcePublicationStatusRef> refs = fullSetStatuses;
            all = all.stream()
                    .filter(v -> isSharedId(refs, v.getId()) == wantPublic)
                    .toList();
        }

        // 4. Order, then slice (on the light projections). Stable sort keeps the created_at-DESC base
        //    order as the tie-breaker.
        all = sortInterfaceViews(all, sort);

        int totalCount = all.size();
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        int from = Math.min(safePage * safeSize, totalCount);
        int to = Math.min(from + safeSize, totalCount);
        List<InterfaceListView> pageViews = all.subList(from, to);

        // 5. Materialize the page's FULL entities by id (<= size rows, so the @Lob templates load for
        //    the page only) and reorder them to the sliced order (findAllById gives no order guarantee).
        List<UUID> pageIds = pageViews.stream().map(InterfaceListView::getId).collect(Collectors.toList());
        Map<UUID, InterfaceEntity> byId = interfaceRepository.findAllById(pageIds).stream()
                .collect(Collectors.toMap(InterfaceEntity::getId, e -> e));
        List<InterfaceEntity> pageItems = pageIds.stream()
                .map(byId::get).filter(Objects::nonNull).collect(Collectors.toList());

        // 6. Publication badge for the page, batched server-side (no per-row fan-out). Reuse the
        //    full-set statuses already fetched for the visibility filter; otherwise fetch just the page.
        Map<String, PublicationClient.ResourcePublicationStatusRef> pageRefs = filterByVisibility
                ? fullSetStatuses
                : (publicationClient != null
                        ? publicationClient.findResourcePublicationStatuses(
                                INTERFACE_PUBLICATION_TYPE, resourceIdsOf(pageItems), decodedTenantId)
                        : Map.of());
        Map<String, Map<String, String>> publicationStatuses = toPublicationStatusMap(pageItems, pageRefs);

        return new InterfacePage(pageItems, totalCount, safePage, safeSize, publicationStatuses);
    }

    private static final String INTERFACE_PUBLICATION_TYPE = "INTERFACE";

    private static List<String> resourceIdsOf(List<InterfaceEntity> list) {
        return list.stream().map(i -> i.getId().toString()).toList();
    }

    private static List<String> idsOfViews(List<InterfaceListView> list) {
        return list.stream().map(v -> v.getId().toString()).toList();
    }

    private static boolean isSharedId(Map<String, PublicationClient.ResourcePublicationStatusRef> statuses,
                                      UUID id) {
        PublicationClient.ResourcePublicationStatusRef ref = statuses.get(id.toString());
        return ref != null && ref.published();
    }

    /**
     * Server-side equivalent of the frontend {@code listSort.processList} order: {@code name}
     * (case-insensitive A->Z) or, by default, {@code lastModified} (updatedAt, falling back to
     * createdAt, most-recent first; missing dates last). Stable, so equal keys keep the upstream
     * created_at-DESC order.
     */
    private static List<InterfaceListView> sortInterfaceViews(List<InterfaceListView> list, String sort) {
        String key = sort == null ? "lastmodified" : sort.trim().toLowerCase();
        List<InterfaceListView> sorted = new ArrayList<>(list);
        if (key.equals("name")) {
            sorted.sort(Comparator.comparing(
                    v -> v.getName() == null ? "" : v.getName(), String.CASE_INSENSITIVE_ORDER));
        } else {
            sorted.sort(InterfaceService::compareViewByModifiedDesc);
        }
        return sorted;
    }

    private static int compareViewByModifiedDesc(InterfaceListView a, InterfaceListView b) {
        Instant ta = a.getUpdatedAt() != null ? a.getUpdatedAt() : a.getCreatedAt();
        Instant tb = b.getUpdatedAt() != null ? b.getUpdatedAt() : b.getCreatedAt();
        if (ta == null && tb == null) return 0;
        if (ta == null) return 1;   // missing date sorts last
        if (tb == null) return -1;
        return tb.compareTo(ta);    // most-recent first
    }

    private static Map<String, Map<String, String>> toPublicationStatusMap(
            List<InterfaceEntity> pageItems,
            Map<String, PublicationClient.ResourcePublicationStatusRef> refs) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (InterfaceEntity i : pageItems) {
            String id = i.getId().toString();
            PublicationClient.ResourcePublicationStatusRef ref = refs.get(id);
            if (ref == null) continue;
            Map<String, String> info = new LinkedHashMap<>();
            info.put("status", ref.status());
            if (ref.rejectionReason() != null) info.put("rejectionReason", ref.rejectionReason());
            out.put(id, info);
        }
        return out;
    }

    private static boolean matchesNameOrDescription(String name, String desc, String needle) {
        return (name != null && name.toLowerCase().contains(needle))
            || (desc != null && desc.toLowerCase().contains(needle));
    }

    /**
     * #150 - scope-aware clone. Routes the lookup through {@link #findInScope}
     * so a TEAM-workspace user can clone an interface owned by a teammate
     * (pre-#150 failed because the lookup gated on the caller's own tenant_id).
     * The clone is reparented to the caller's tenant_id but keeps the source's
     * organization_id - workspace-scope is preserved end-to-end.
     */
    public InterfaceEntity cloneInterface(UUID sourceId, String tenantId, String orgId, String orgRole) {
        InterfaceEntity source = findInScope(sourceId, tenantId, orgId)
            .orElseThrow(() -> new IllegalArgumentException("Interface not found: " + sourceId));

        // PR-2.c: org-scoped deny-list enforcement on clone.
        String sourceOrgId = source.getOrganizationId();
        if (sourceOrgId != null
                && !orgAccessService.canWrite(sourceOrgId, tenantId, "interface", sourceId.toString(), orgRole)) {
            log.warn("OrgAccess denied: user {} restricted from cloning interface {} in org {}",
                    tenantId, sourceId, sourceOrgId);
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                    "interface", sourceId.toString());
        }

        InterfaceEntity clone = new InterfaceEntity();
        clone.setTenantId(tenantId);
        clone.setName(source.getName() + " (Copy)");
        clone.setDescription(source.getDescription());
        clone.setHtmlTemplate(source.getHtmlTemplate());
        clone.setCssTemplate(source.getCssTemplate());
        clone.setJsTemplate(source.getJsTemplate());
        clone.setTargetTable(source.getTargetTable());
        clone.setDataSourceId(source.getDataSourceId());
        clone.setIsPublic(source.getIsPublic());
        clone.setIsActive(source.getIsActive());
        // Preserve org workspace - if the source lived in an org, the clone
        // joins the same org. Personal sources clone as personal even when
        // the caller is currently in an org workspace (matches the workflow
        // clone semantics).
        if (sourceOrgId != null) {
            clone.setOrganizationId(sourceOrgId);
        }
        clone.setTemplateVariables(source.getTemplateVariables() != null
                ? new ArrayList<>(source.getTemplateVariables()) : null);
        return interfaceRepository.save(clone);
    }

    /**
     * Create or accumulate browser-agent action results into a single
     * Interface grouped by (conversation, message, agent). Backs the live
     * browser-agent CDP card in the chat side panel - the LLM tool result
     * carries a {@code [visualize:agent_browse:<interfaceId>]} marker, the
     * frontend re-fetches the interface, and the {@code data.results[]}
     * array drives the address bar + canvas live view.
     *
     * <p>Replaces the legacy {@code createOrUpdateWebSearchInterface} (removed
     * 2026-05-22) which persisted under {@code interface_type='web_search'}.
     * Search/fetch tool calls no longer persist at all - their results are
     * rendered inline in the chat as a {@code FaviconStack} on the tool-call
     * row (commit f600c8885). The {@code web_search} bucket only ever held
     * dead-weight rows once that landed; this method writes
     * {@code interface_type='agent_browse'} so the new agent-browse rows
     * survive the one-shot prod purge (post-V279).
     */
    @SuppressWarnings("unchecked")
    public InterfaceEntity createOrUpdateAgentBrowseInterface(
            String tenantId, String conversationId, String messageId, String agentId,
            String name, Map<String, Object> result, String organizationId) {

        Optional<InterfaceEntity> existing = interfaceRepository.findAgentBrowseInterface(
            tenantId, conversationId, messageId, agentId);

        if (existing.isPresent()) {
            InterfaceEntity entity = existing.get();
            long oldSize = estimateInterfaceSize(entity);
            Map<String, Object> data = entity.getData();
            if (data == null) {
                data = new HashMap<>();
            }
            List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
            if (results == null) {
                results = new ArrayList<>();
            }
            results.add(result);
            data.put("results", results);
            entity.setData(data);
            String currentName = entity.getName();
            if (currentName != null && !currentName.contains("(")) {
                entity.setName(currentName + " (" + results.size() + ")");
            }
            InterfaceEntity saved = interfaceRepository.save(entity);
            breakdownService.increment(tenantId, "INTERFACES", estimateInterfaceSize(saved) - oldSize, 0);
            return saved;
        }

        InterfaceEntity entity = new InterfaceEntity();
        entity.setTenantId(tenantId);
        entity.setName(name != null ? name : "Browser Agent");
        entity.setInterfaceType("agent_browse");
        entity.setConversationId(conversationId);
        entity.setMessageId(messageId);
        entity.setAgentId(agentId);
        if (organizationId != null) {
            entity.setOrganizationId(organizationId);
        }

        Map<String, Object> data = new HashMap<>();
        List<Map<String, Object>> results = new ArrayList<>();
        results.add(result);
        data.put("results", results);
        entity.setData(data);

        InterfaceEntity saved = interfaceRepository.save(entity);
        breakdownService.trackSave(tenantId, "INTERFACES", estimateInterfaceSize(saved));
        log.info("[InterfaceService] Created agent_browse interface {} for conversation={} message={} agent={}",
            saved.getId(), conversationId, messageId, agentId);
        return saved;
    }

    /**
     * Create or accumulate image-generation results into a single Interface
     * grouped by (conversation, message, agent). Mirrors
     * {@link #createOrUpdateAgentBrowseInterface} so the chat side panel can
     * follow the same protocol: re-fetch the interface, render the
     * accumulated images, no client-side state.
     *
     * <p>The {@code result} map is appended to {@code data.images[]} (each
     * entry contains {@code {base64, mime_type, prompt, provider,
     * billing_model, ...}}). This matches what
     * {@code ImageGenerationModule.buildResultData} produces, so the
     * persistence layer doesn't reshape the payload.
     */
    @SuppressWarnings("unchecked")
    public InterfaceEntity createOrUpdateImageGenerationInterface(
            String tenantId, String conversationId, String messageId, String agentId,
            String name, Map<String, Object> result, String organizationId) {

        Optional<InterfaceEntity> existing = interfaceRepository.findImageGenerationInterface(
                tenantId, conversationId, messageId, agentId);

        if (existing.isPresent()) {
            InterfaceEntity entity = existing.get();
            long oldSize = estimateInterfaceSize(entity);
            Map<String, Object> data = entity.getData();
            if (data == null) data = new HashMap<>();
            List<Map<String, Object>> images = (List<Map<String, Object>>) data.get("images");
            if (images == null) images = new ArrayList<>();
            // Append every image from the new result - module already
            // returns data.images[] as a list, even for n=1.
            Object newImages = result.get("images");
            if (newImages instanceof List<?> newList) {
                for (Object item : newList) {
                    if (item instanceof Map<?, ?> imgMap) {
                        images.add((Map<String, Object>) imgMap);
                    }
                }
            }
            data.put("images", images);
            entity.setData(data);
            String currentName = entity.getName();
            if (currentName != null && !currentName.contains("(")) {
                entity.setName(currentName + " (" + images.size() + ")");
            }
            InterfaceEntity saved = interfaceRepository.save(entity);
            breakdownService.increment(tenantId, "INTERFACES", estimateInterfaceSize(saved) - oldSize, 0);
            return saved;
        }

        InterfaceEntity entity = new InterfaceEntity();
        entity.setTenantId(tenantId);
        entity.setName(name != null ? name : "Generated image");
        entity.setInterfaceType("image_generation");
        entity.setConversationId(conversationId);
        entity.setMessageId(messageId);
        entity.setAgentId(agentId);
        if (organizationId != null) {
            entity.setOrganizationId(organizationId);
        }

        Map<String, Object> data = new HashMap<>();
        List<Map<String, Object>> images = new ArrayList<>();
        Object newImages = result.get("images");
        if (newImages instanceof List<?> newList) {
            for (Object item : newList) {
                if (item instanceof Map<?, ?> imgMap) {
                    images.add((Map<String, Object>) imgMap);
                }
            }
        }
        data.put("images", images);
        // Preserve provenance metadata so the side panel can group by
        // model, show cost breakdown, etc.
        if (result.get("provider") != null) data.put("provider", result.get("provider"));
        if (result.get("billing_model") != null) data.put("billing_model", result.get("billing_model"));
        if (result.get("prompt") != null) data.put("prompt", result.get("prompt"));
        entity.setData(data);

        InterfaceEntity saved = interfaceRepository.save(entity);
        breakdownService.trackSave(tenantId, "INTERFACES", estimateInterfaceSize(saved));
        log.info("[InterfaceService] Created image_generation interface {} for conversation={} message={} agent={}",
                saved.getId(), conversationId, messageId, agentId);
        return saved;
    }

    @SuppressWarnings("unchecked")
    public void updateWebSearchScreenshot(UUID interfaceId, String url, String screenshotKey) {
        if (interfaceId == null || url == null || screenshotKey == null) return;

        // Use pessimistic lock to prevent lost updates when multiple screenshots arrive concurrently
        Optional<InterfaceEntity> opt = interfaceRepository.findByIdForUpdate(interfaceId);
        if (opt.isEmpty()) return;

        InterfaceEntity entity = opt.get();
        Map<String, Object> data = entity.getData();
        if (data == null) return;

        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
        if (results == null) return;

        boolean updated = false;
        for (Map<String, Object> result : results) {
            if (url.equals(result.get("url"))) {
                result.put("screenshot_key", screenshotKey);
                updated = true;
            }
            List<Map<String, Object>> pages = (List<Map<String, Object>>) result.get("pages");
            if (pages != null) {
                for (Map<String, Object> page : pages) {
                    if (url.equals(page.get("url"))) {
                        page.put("screenshot_key", screenshotKey);
                        updated = true;
                    }
                }
            }
        }

        if (updated) {
            interfaceRepository.save(entity);
            breakdownService.increment(entity.getTenantId(), "INTERFACES", screenshotKey.length(), 0);
        }
    }

    public InterfaceEntity createSlideInterface(String tenantId, String name, String description,
                                                 Map<String, Object> slideData) {
        return createSlideInterface(tenantId, name, description, slideData, null);
    }

    /**
     * Org-aware overload. Stamps {@code organizationId} on the slide-deck row
     * so org-teammates can list/edit it when the create is issued from an
     * org workspace. {@code orgId == null} keeps personal-scope behaviour.
     */
    public InterfaceEntity createSlideInterface(String tenantId, String name, String description,
                                                 Map<String, Object> slideData, String organizationId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name cannot be null or empty");
        }

        InterfaceEntity entity = new InterfaceEntity();
        entity.setTenantId(tenantId);
        if (organizationId != null && !organizationId.isBlank()) {
            entity.setOrganizationId(organizationId);
        }
        entity.setName(name);
        entity.setDescription(description);
        entity.setInterfaceType("slide");

        if (slideData != null) {
            entity.setData(slideData);
        } else {
            Map<String, Object> defaultData = new HashMap<>();
            List<Map<String, Object>> slides = new ArrayList<>();
            Map<String, Object> titleSlide = new HashMap<>();
            titleSlide.put("layout", "title");
            titleSlide.put("content", Map.of(
                "title", name,
                "subtitle", description != null ? description : ""
            ));
            slides.add(titleSlide);
            defaultData.put("slides", slides);
            defaultData.put("theme", Map.of(
                "primaryColor", "#1a1a2e",
                "secondaryColor", "#16213e",
                "accentColor", "#0f3460",
                "fontFamily", "Inter, system-ui, sans-serif"
            ));
            entity.setData(defaultData);
        }

        InterfaceEntity saved = interfaceRepository.save(entity);
        breakdownService.trackSave(tenantId, "INTERFACES", estimateInterfaceSize(saved));
        log.info("[InterfaceService] Created slide interface {} name={}", saved.getId(), name);
        return saved;
    }

    public InterfaceEntity updateSlideData(UUID id, String tenantId, String name, String description,
                                            Map<String, Object> slideData) {
        InterfaceEntity existing = interfaceRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Interface not found: " + id));
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (!ScopeGuard.isInStrictScope(tenantId, orgId, existing.getTenantId(), existing.getOrganizationId())) {
            throw new IllegalArgumentException("Interface tenant mismatch");
        }
        if (!"slide".equals(existing.getInterfaceType())) {
            throw new IllegalArgumentException("Interface is not a slide deck: " + id);
        }
        String orgRole = TenantResolver.currentRequestOrganizationRole();
        String existingOrgId = existing.getOrganizationId();
        if (existingOrgId != null
                && !orgAccessService.canWrite(existingOrgId, tenantId, "interface", id.toString(), orgRole)) {
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                    "interface", id.toString());
        }

        if (name != null) existing.setName(name);
        if (description != null) existing.setDescription(description);
        if (slideData != null) existing.setData(slideData);

        long oldSize = estimateInterfaceSize(existing);
        InterfaceEntity saved = interfaceRepository.save(existing);
        breakdownService.increment(tenantId, "INTERFACES", estimateInterfaceSize(saved) - oldSize, 0);
        return saved;
    }

    /**
     * #150 - scope-aware delete. Routes the lookup via {@link #findInScope},
     * so a TEAM workspace user can delete an interface owned by a teammate
     * (subject to the existing org deny-list). Cross-scope id → no-op (the
     * row is invisible from the caller's scope, mirroring the no-op-when-missing
     * semantics of the legacy path).
     */
    public void deleteInterface(UUID id, String tenantId, String orgId, String orgRole) {
        var existing = findInScope(id, tenantId, orgId).orElse(null);
        if (existing == null) return;

        // PR-2 (V2 fix): org-scoped deny-list enforcement on write.
        // Post-V261, organizationId is always non-null; OWNER/ADMIN bypass via
        // OrgAccessGuard contract still applies.
        String existingOrgId = existing.getOrganizationId();
        if (existingOrgId != null
                && !orgAccessService.canWrite(existingOrgId, tenantId, "interface", id.toString(), orgRole)) {
            log.warn("OrgAccess denied: user {} restricted from interface {} in org {}",
                    tenantId, id, existingOrgId);
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                    "interface", id.toString());
        }

        // The row's actual owner - different from the caller's {@code tenantId}
        // when a teammate deletes via an org workspace (#150). The cascade,
        // delete-by-id and breakdown accounting MUST use the owner's tenant_id
        // so plan refs across the OWNER's workflows are scrubbed and the
        // OWNER's storage budget is decremented, not the deleter's.
        String ownerTenantId = existing.getTenantId();

        // Cascade BEFORE the row delete: ask orchestrator to strip every
        // {plan.interfaces[].id == id} and matching plan.edges[] across the
        // owner's tenant. If this fails, we abort the delete - the alternative
        // is dangling refs in plan JSONB that block builder loads and break the
        // workflow → interface link silently.
        if (orchestratorCascadeClient != null) {
            try {
                OrchestratorCascadeClient.CascadeSummary summary =
                    orchestratorCascadeClient.stripInterfaceReferences(ownerTenantId, id.toString());
                if (summary != null && summary.workflowsTouched() > 0) {
                    log.info("[InterfaceService] Cascade scrubbed interface {} from {} workflow plan(s) (entries={} edges={})",
                        id, summary.workflowsTouched(), summary.entriesRemoved(), summary.edgesRemoved());
                }
            } catch (OrchestratorCascadeClient.OrchestratorCascadeException e) {
                log.error("[InterfaceService] Aborting delete of interface {} - cascade failed: {}", id, e.getMessage());
                throw new IllegalStateException(
                    "Cannot delete interface " + id + ": failed to scrub workflow plan references. " +
                    "Retry once orchestrator-service is reachable.", e);
            }
        }

        long sizeBeforeDelete = estimateInterfaceSize(existing);
        // Batch-C: strict-org delete - the row was already org-gated by
        // findInScope above, so issuing the DELETE through the org-scoped
        // predicate matches the row's scope shape exactly and avoids
        // cross-workspace owner-tenant collisions when an org admin removes
        // a teammate-owned interface. Falls back through ownerTenantId only
        // when organizationId is null (pre-V261 legacy rows in test fixtures).
        String existingOrgIdForDelete = existing.getOrganizationId();
        int deleted = (existingOrgIdForDelete != null && !existingOrgIdForDelete.isBlank())
                ? interfaceRepository.deleteByIdAndOrganizationIdStrict(id, existingOrgIdForDelete)
                : interfaceRepository.deleteByIdAndTenantId(id, ownerTenantId);
        if (deleted > 0) {
            breakdownService.trackDelete(ownerTenantId, "INTERFACES", sizeBeforeDelete);
        }
    }

    // ========== Project operations ==========

    public void assignToProject(UUID interfaceId, UUID projectId, String tenantId) {
        assignToProject(interfaceId, projectId, tenantId, null);
    }

    public void assignToProject(UUID interfaceId, UUID projectId, String tenantId, String orgId) {
        String orgRole = TenantResolver.currentRequestOrganizationRole();
        InterfaceEntity entity = interfaceRepository.findById(interfaceId)
            .orElseThrow(() -> new IllegalArgumentException("Interface not found: " + interfaceId));
        if (!matchesProjectWorkspace(entity, tenantId, orgId)) {
            throw new IllegalArgumentException("Interface workspace mismatch");
        }
        if (!canWriteOrgInterface(entity, tenantId, orgId, orgRole)) {
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                    "interface", interfaceId.toString());
        }
        entity.setProjectId(projectId);
        interfaceRepository.save(entity);
    }

    public void removeFromProject(UUID interfaceId, UUID projectId, String tenantId) {
        removeFromProject(interfaceId, projectId, tenantId, null);
    }

    public void removeFromProject(UUID interfaceId, UUID projectId, String tenantId, String orgId) {
        String orgRole = TenantResolver.currentRequestOrganizationRole();
        InterfaceEntity entity = interfaceRepository.findById(interfaceId)
            .orElseThrow(() -> new IllegalArgumentException("Interface not found: " + interfaceId));
        if (!matchesProjectWorkspace(entity, tenantId, orgId)) {
            throw new IllegalArgumentException("Interface workspace mismatch");
        }
        if (!canWriteOrgInterface(entity, tenantId, orgId, orgRole)) {
            throw new com.apimarketplace.auth.client.access.OrgAccessDeniedException(
                    "interface", interfaceId.toString());
        }
        if (!projectId.equals(entity.getProjectId())) return;
        entity.setProjectId(null);
        interfaceRepository.save(entity);
    }

    @Transactional(readOnly = true)
    public long countByProject(UUID projectId) {
        return countByProject(projectId, null);
    }

    @Transactional(readOnly = true)
    public long countByProject(UUID projectId, String orgId) {
        return hasOrg(orgId)
                ? interfaceRepository.countByProjectIdAndOrganizationId(projectId, orgId)
                : interfaceRepository.countByProjectId(projectId);
    }

    @Transactional(readOnly = true)
    public List<InterfaceEntity> getByProject(UUID projectId) {
        return getByProject(projectId, null);
    }

    @Transactional(readOnly = true)
    public List<InterfaceEntity> getByProject(UUID projectId, String orgId) {
        return hasOrg(orgId)
                ? interfaceRepository.findByProjectIdAndOrganizationId(projectId, orgId)
                : interfaceRepository.findByProjectId(projectId);
    }

    public void unassignAllFromProject(UUID projectId) {
        unassignAllFromProject(projectId, null);
    }

    public void unassignAllFromProject(UUID projectId, String orgId) {
        List<InterfaceEntity> interfaces = getByProject(projectId, orgId);
        for (InterfaceEntity iface : interfaces) {
            iface.setProjectId(null);
            interfaceRepository.save(iface);
        }
    }

    private boolean matchesProjectWorkspace(InterfaceEntity entity, String tenantId, String orgId) {
        return ScopeGuard.isInStrictScope(tenantId, orgId, entity.getTenantId(), entity.getOrganizationId());
    }

    private boolean canWriteOrgInterface(InterfaceEntity entity, String tenantId, String orgId, String orgRole) {
        String interfaceOrgId = entity.getOrganizationId();
        if (interfaceOrgId == null || interfaceOrgId.isBlank()) {
            return true;
        }
        String effectiveOrgId = hasOrg(orgId) ? orgId : interfaceOrgId;
        return orgAccessService.canWrite(effectiveOrgId, tenantId, "interface", entity.getId().toString(), orgRole);
    }

    private boolean hasOrg(String orgId) {
        return orgId != null && !orgId.isBlank();
    }

    @Transactional(readOnly = true)
    public List<InterfaceEntity> findBySourceWorkflowId(UUID workflowId) {
        return interfaceRepository.findBySourceWorkflowId(workflowId);
    }

    public void deleteBySourceWorkflowId(UUID workflowId) {
        // Bulk teardown path (publication compensation, workflow tree wipe).
        // Even though the source workflow itself is being torn down, the rows
        // we delete here may still be referenced by *other* workflow plans in
        // the tenant - so the cascade must fire once per row, otherwise we
        // re-introduce the dangling-plan-ref class of bug.
        //
        // Tx asymmetry: each cascade HTTP call commits its plan mutations
        // server-side independently. If interface-service later rolls back
        // (e.g. timeout on row N+1), the orchestrator-side scrubs for rows
        // 0..N are already permanent. This is the safer asymmetry - better
        // a few plans missing a still-existing interface (recoverable) than
        // a few plans pointing at a tombstoned id (silent build break).
        List<InterfaceEntity> affected = interfaceRepository.findBySourceWorkflowId(workflowId);
        if (affected.isEmpty()) return;

        if (orchestratorCascadeClient != null) {
            for (InterfaceEntity iface : affected) {
                try {
                    OrchestratorCascadeClient.CascadeSummary summary =
                        orchestratorCascadeClient.stripInterfaceReferences(
                            iface.getTenantId(), iface.getId().toString());
                    if (summary != null && summary.workflowsTouched() > 0) {
                        log.info("[InterfaceService] Bulk-teardown cascade scrubbed interface {} from {} workflow plan(s)",
                            iface.getId(), summary.workflowsTouched());
                    }
                } catch (OrchestratorCascadeClient.OrchestratorCascadeException e) {
                    log.error("[InterfaceService] Aborting bulk teardown for source-workflow {} - cascade failed for interface {}: {}",
                        workflowId, iface.getId(), e.getMessage());
                    throw new IllegalStateException(
                        "Cannot delete interfaces sourced from workflow " + workflowId
                        + ": cascade failed for interface " + iface.getId(), e);
                }
            }
        }

        interfaceRepository.deleteBySourceWorkflowId(workflowId);
    }

    @Transactional(readOnly = true)
    public long countByTenant(String tenantId) {
        return interfaceRepository.countByTenantId(tenantId);
    }

    // ========== Helpers ==========

    private long estimateInterfaceSize(InterfaceEntity entity) {
        long size = 0;
        if (entity.getHtmlTemplate() != null) size += entity.getHtmlTemplate().length();
        if (entity.getCssTemplate() != null) size += entity.getCssTemplate().length();
        if (entity.getJsTemplate() != null) size += entity.getJsTemplate().length();
        if (entity.getData() != null) size += entity.getData().toString().length();
        return size;
    }

    private void validateCreateOrUpdate(String tenantId, String name) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name cannot be null or empty");
        }
        if (name.length() > 255) {
            throw new IllegalArgumentException("name cannot exceed 255 characters");
        }
    }

    private void updateTemplateVariables(InterfaceEntity entity) {
        StringBuilder combined = new StringBuilder();
        if (entity.getHtmlTemplate() != null && !entity.getHtmlTemplate().isEmpty()) {
            combined.append(entity.getHtmlTemplate());
        }
        if (entity.getCssTemplate() != null && !entity.getCssTemplate().isEmpty()) {
            if (!combined.isEmpty()) {
                combined.append("\n");
            }
            combined.append(entity.getCssTemplate());
        }

        if (!combined.isEmpty()) {
            entity.setTemplateVariables(variableExtractor.extractTemplateVariables(combined.toString()));
        } else {
            entity.setTemplateVariables(List.of());
        }
    }
}
