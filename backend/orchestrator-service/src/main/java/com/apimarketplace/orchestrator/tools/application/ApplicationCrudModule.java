package com.apimarketplace.orchestrator.tools.application;

import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.AgentListEnvelope;
import com.apimarketplace.agent.tools.common.ToolParamUtils;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.InterfaceDef;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlanParser;
import com.apimarketplace.orchestrator.repository.OffsetLimitPageable;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.orchestrator.tools.common.AgentResourceRequirements;
import com.apimarketplace.orchestrator.tools.common.AgentTriggerSchema;
import com.apimarketplace.agent.tools.common.ToolModule;
import com.apimarketplace.orchestrator.tools.visualization.VisualizationToolsProvider;
import com.apimarketplace.orchestrator.tools.workflow.builder.AgentWorkflowFireService;
import com.apimarketplace.publication.client.PublicationClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * CRUD module for the application tool.
 * Handles: search, my, get, acquire, uninstall, visualize, create, runs, get_run, get_node_output.
 */
@Slf4j
@Component
public class ApplicationCrudModule implements ToolModule {

    private final PublicationClient publicationClient;
    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowPlanVersionRepository planVersionRepository;
    private final WorkflowPlanVersionService planVersionService;
    private final AgentWorkflowFireService agentWorkflowFireService;
    private final com.apimarketplace.credential.client.CredentialClient credentialClient;
    private final ApplicationShowcaseResolver showcaseResolver;
    private final com.apimarketplace.orchestrator.services.ApplicationLifecycleService applicationLifecycleService;

    // Lazy injection to avoid circular dependencies
    private VisualizationToolsProvider visualizationToolsProvider;
    // Lazy: the canonical cascade delete (runs, plan versions, schedules, files) used by uninstall.
    private com.apimarketplace.orchestrator.services.WorkflowManagementService workflowManagementService;

    private static final Set<String> HANDLED_ACTIONS = Set.of(
        "search", "my", "get", "acquire", "uninstall", "visualize", "create",
        "runs", "get_run", "get_node_output"
    );

    public ApplicationCrudModule(PublicationClient publicationClient,
                                  WorkflowRepository workflowRepository,
                                  WorkflowRunRepository workflowRunRepository,
                                  WorkflowPlanVersionRepository planVersionRepository,
                                  WorkflowPlanVersionService planVersionService,
                                  AgentWorkflowFireService agentWorkflowFireService,
                                  com.apimarketplace.credential.client.CredentialClient credentialClient,
                                  ApplicationShowcaseResolver showcaseResolver,
                                  com.apimarketplace.orchestrator.services.ApplicationLifecycleService applicationLifecycleService) {
        this.publicationClient = publicationClient;
        this.workflowRepository = workflowRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.planVersionRepository = planVersionRepository;
        this.planVersionService = planVersionService;
        this.agentWorkflowFireService = agentWorkflowFireService;
        this.credentialClient = credentialClient;
        this.showcaseResolver = showcaseResolver;
        this.applicationLifecycleService = applicationLifecycleService;
    }

    @Autowired
    @Lazy
    public void setVisualizationToolsProvider(VisualizationToolsProvider visualizationToolsProvider) {
        this.visualizationToolsProvider = visualizationToolsProvider;
    }

    @Autowired
    @Lazy
    public void setWorkflowManagementService(
            com.apimarketplace.orchestrator.services.WorkflowManagementService workflowManagementService) {
        this.workflowManagementService = workflowManagementService;
    }

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of();
    }

    @Override
    public boolean canHandle(String action) {
        return HANDLED_ACTIONS.contains(action);
    }

    @Override
    public Optional<ToolExecutionResult> execute(String action, Map<String, Object> parameters,
                                                  String tenantId, ToolExecutionContext context) {
        if (!canHandle(action)) return Optional.empty();

        // Access mode check (read/write)
        var accessDenied = com.apimarketplace.agent.config.ToolAccessControl.checkWriteAccess(
                context != null ? context.credentials() : null, "application", action);
        if (accessDenied.isPresent()) return Optional.of(ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, accessDenied.get()));

        return Optional.of(switch (action) {
            case "search" -> executeSearch(parameters, context);
            case "my" -> executeMy(tenantId, parameters, context);
            case "get" -> executeGet(parameters, context);
            case "acquire" -> executeAcquire(parameters, tenantId, context);
            case "uninstall" -> executeUninstall(parameters, tenantId, context);
            case "visualize" -> executeVisualize(parameters, context);
            case "create" -> executeCreate(parameters, tenantId, context);
            case "runs" -> executeRuns(parameters, tenantId, context);
            case "get_run" -> executeGetRun(parameters, tenantId, context);
            case "get_node_output" -> executeGetNodeOutput(parameters, tenantId, context);
            default -> ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                "Unknown action: " + action);
        });
    }

    // ==================== SEARCH ====================
    @SuppressWarnings("unchecked")
    private ToolExecutionResult executeSearch(Map<String, Object> parameters, ToolExecutionContext context) {
        String query = getStringParam(parameters, "query");
        String category = getStringParam(parameters, "category");

        // application.search = SMALL caps (default=10 / max=25 / hintThreshold=50).
        // Filters: query + category (both general refinements). Active-filter-key set
        // is computed from the actual request so the helper's hard-refuse path uses
        // the server-derived truth, not a caller-supplied boolean.
        // legacyKeys: totalItems + totalPages emitted for 1-release backward-compat
        // transition; consumers migrate to total/hasMore/offset/limit then we drop.
        Set<String> activeFilters = new LinkedHashSet<>();
        if (query != null && !query.isBlank()) activeFilters.add("query");
        if (category != null && !category.isBlank()) activeFilters.add("category");

        AgentListEnvelope.Spec spec = AgentListEnvelope.Spec.of(
                        AgentListEnvelope.Caps.SMALL, "applications", "applications", "applications")
                .withLegacyKeys(Set.of("totalItems", "totalPages"))
                .withNext(Map.of(
                        "details_with_schema", "application(action='get', application_id='<id>') - returns data_inputs_schema (required field names) before execute",
                        "run_owned", "application(action='execute', application_id='<id>') - only if owned_by_me=true (fetch get first if you don't know the field names)",
                        "clone_then_run", "application(action='acquire', application_id='<id>') then application(action='get', application_id='<id>') to read data_inputs_schema then application(action='execute', application_id='<id>')",
                        "preview_in_chat", "application(action='visualize', application_id='<id>')",
                        "if_no_match_search_tools", "catalog(action='search', query='<q>') - find API tools to build a workflow from scratch",
                        "if_no_match_build_workflow", "workflow(action='init', name='<...>') - start a new workflow"
                ));
        AgentListEnvelope.Bounds bounds;
        try {
            bounds = AgentListEnvelope.readBounds(parameters, spec, activeFilters);
        } catch (AgentListEnvelope.InvalidParamsException e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.code + ": " + e.getMessage());
        }

        // publication-service is page/size based - derive page from offset & post-clamp limit.
        int page = (int) (bounds.offset() / Math.max(bounds.limit(), 1));

        try {
            Map<String, Object> pageResult;
            if (query != null && !query.isBlank()) {
                pageResult = publicationClient.searchMarketplace(query, page, bounds.limit());
            } else if (category != null && !category.isBlank()) {
                pageResult = publicationClient.getMarketplaceByCategorySlug(category, page, bounds.limit());
            } else {
                pageResult = publicationClient.getMarketplacePublications(page, bounds.limit());
            }

            List<Map<String, Object>> content = (List<Map<String, Object>>) pageResult.getOrDefault("content", List.of());
            long totalElements = pageResult.get("totalElements") instanceof Number n ? n.longValue() : 0;

            // Apply application access restrictions
            List<String> allowedAppIds = getAllowedApplicationIds(context);
            if (allowedAppIds != null) {
                content = content.stream()
                    .filter(pub -> pub.get("id") != null && allowedAppIds.contains(pub.get("id").toString()))
                    .toList();
                log.info("Agent restriction: filtered applications to {}/{} allowed",
                    content.size(), allowedAppIds.size());
            }

            String tenantId = context != null ? context.tenantId() : null;
            RequirementsPrebatch pre = prebatchRequirements(content, tenantId);
            List<Map<String, Object>> slim = content.stream()
                    .map(pub -> slimForAgent(pub, tenantId, pre.configuredIntegrations(), pre.existingSubWfIds()))
                    .toList();

            return ToolExecutionResult.success(
                    AgentListEnvelope.paginateProjection(slim, bounds, totalElements, spec));
        } catch (Exception e) {
            log.error("Error listing applications: {}", e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Failed to list applications: " + e.getMessage());
        }
    }

    // ==================== MY ====================
    private ToolExecutionResult executeMy(String tenantId, Map<String, Object> parameters, ToolExecutionContext context) {
        // application.my = STANDARD caps (default=25 / max=50 / hintThreshold=100,
        // hardRefuse auto=400). `query` (name/description substring) is the one
        // refinement filter, so the `refine` hint suggests it on large result sets.
        String query = getStringParam(parameters, "query");
        AgentListEnvelope.Spec spec = AgentListEnvelope.Spec.of(
                        AgentListEnvelope.Caps.STANDARD, "applications", "applications", "applications")
                .withSuggestedFilters(List.of("query"))
                .withNext(Map.of(
                        "run", "application(action='execute', application_id='<id>') - owned apps run directly, no acquire needed",
                        "details_with_schema", "application(action='get', application_id='<id>') - returns data_inputs_schema (field names + select options) before execute",
                        "run_with_inputs", "application(action='execute', application_id='<id>', data_inputs={...}) - call get first to read data_inputs_schema",
                        "edit_underlying_workflow", "workflow(action='load', id='<workflowId>')",
                        "preview_in_chat", "application(action='visualize', application_id='<id>')"
                ));
        AgentListEnvelope.Bounds bounds;
        try {
            // Active-filter set is server-derived from the request (never a caller
            // boolean) so the hard-refuse-without-filter guard uses the real truth.
            Set<String> activeFilters = ToolParamUtils.hasQuery(query) ? Set.of("query") : Set.of();
            bounds = AgentListEnvelope.readBounds(parameters, spec, activeFilters);
        } catch (AgentListEnvelope.InvalidParamsException e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.code + ": " + e.getMessage());
        }

        try {
            // "My applications" = every app available in the user's workspace: the
            // APPLICATION-typed workflows that carry a source publication - ones the
            // user ACQUIRED from the marketplace OR PUBLISHED themselves (publishing
            // also clones an APPLICATION workflow into the org). Mirrors the
            // /app/applications view. The previous publisher-only source
            // (getPublicationsByPublisher) listed NONE of a consumer's acquired apps,
            // so a user who had installed apps but published none was told "you have
            // no applications" (prod report: a user with 10 acquired apps saw 0).
            String orgId = context != null ? context.orgId() : null;
            long total;
            List<Map<String, Object>> page;
            if (orgId != null && !orgId.isBlank()) {
                // Text search filters on the acquired WORKFLOW's name/description (the
                // cheap local proxy for the app), BEFORE resolving pubIds, so we avoid a
                // getPublicationById fan-out over the whole owned set just to filter. The
                // cloned workflow name mirrors the app title at acquire/publish time; a
                // publisher who set a marketplace title distinct from the workflow name is
                // the only divergence, acceptable for a "my apps" name search.
                List<WorkflowEntity> acquired = workflowRepository
                        .findAcquiredByOrganizationId(orgId, WorkflowEntity.WorkflowType.APPLICATION);
                if (ToolParamUtils.hasQuery(query)) {
                    acquired = acquired.stream()
                            .filter(w -> ToolParamUtils.matchesQuery(query, w.getName(), w.getDescription()))
                            .toList();
                }
                List<UUID> pubIds = acquired.stream()
                        .map(WorkflowEntity::getSourcePublicationId)
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList();
                total = pubIds.size();
                page = pubIds.stream()
                        .skip(bounds.offset()).limit(bounds.limit())
                        .map(publicationClient::getPublicationById)
                        .filter(java.util.Objects::nonNull)
                        .toList();
            } else {
                // No workspace scope (legacy personal / direct API without an org
                // header) - preserve the original publisher-owned listing. Here the full
                // publication maps are already in memory, so filter on the actual
                // marketplace title + description.
                List<Map<String, Object>> published = publicationClient.getPublicationsByPublisher(tenantId);
                if (ToolParamUtils.hasQuery(query)) {
                    published = published.stream()
                            .filter(pub -> ToolParamUtils.matchesQuery(query,
                                    getStringParam(pub, "title"), getStringParam(pub, "description")))
                            .toList();
                }
                total = published.size();
                page = published.stream().skip(bounds.offset()).limit(bounds.limit()).toList();
            }
            RequirementsPrebatch pre = prebatchRequirements(page, tenantId);
            List<Map<String, Object>> slim = page.stream()
                    .map(pub -> slimForAgent(pub, tenantId, pre.configuredIntegrations(), pre.existingSubWfIds()))
                    .toList();

            return ToolExecutionResult.success(
                    AgentListEnvelope.paginateProjection(slim, bounds, total, spec));
        } catch (Exception e) {
            log.error("Error listing my applications: {}", e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Failed to list your applications: " + e.getMessage());
        }
    }

    /**
     * Reshape a publication-service summary into the slim agent-facing payload.
     *
     * <p>Drops UI-only fields the LLM cannot act on ({@code nodeIcons},
     * {@code publisherAvatarUrl}, {@code displayMode}, {@code useCount},
     * {@code interfaceCount}, {@code datasourceCount}, {@code showcase*},
     * {@code category}). Keeps the actionable shape and adds:
     * <ul>
     *   <li>{@code owned_by_me} - when true, the agent can {@code execute} directly,
     *       skipping {@code acquire} (the original prod-2026-05-15 bug: agent burned
     *       a tool call trying to acquire its own FlyFinder app).</li>
     *   <li>{@code default_trigger_id} + {@code data_inputs_schema} - on owned apps,
     *       so the agent can call {@code execute} with the right field names on the
     *       first try instead of guessing (FlyFinder used SerpAPI field names
     *       {@code departure_id/arrival_id/outbound_date}, not standard
     *       {@code origin/destination/departure_date}).</li>
     *   <li>{@code last_run} - quick freshness signal so the agent can mention
     *       "ran successfully 3min ago" without an extra {@code workflow(action='runs')}.</li>
     * </ul>
     *
     * <p>Enrichment is best-effort: any failure (missing plan, parse error, etc.)
     * silently falls back to the base slim payload - the agent still gets the
     * core fields and can call {@code application(action='get')} if it needs more.
     */
    /**
     * Convenience overload for single-item paths (e.g. {@code application.get}) where
     * batching has the same cost as direct lookup. Performs the credential / sub-workflow
     * prefetch inline.
     */
    private Map<String, Object> slimForAgent(Map<String, Object> pub, String tenantId) {
        // executeGet path: verbose=true so the agent receives the full
        // data_inputs_schema (with select options) on a per-app inspection call.
        return slimForAgent(pub, tenantId, /* configuredIntegrations */ null, /* existingSubWfIds */ null,
                /* verbose */ true);
    }

    /**
     * Backward-compatible 4-arg overload used by list paths (executeMy, executeSearch).
     * Defaults to {@code verbose=false} - schema and fireable_triggers stay out of the
     * list projection to keep paginated payloads small (per audit 2026-05-15:
     * data_inputs_schema with select options inflates 10-item pages by ~7KB).
     */
    private Map<String, Object> slimForAgent(Map<String, Object> pub, String tenantId,
                                              Set<String> configuredIntegrations,
                                              Set<UUID> existingSubWfIds) {
        return slimForAgent(pub, tenantId, configuredIntegrations, existingSubWfIds, /* verbose */ false);
    }

    /**
     * Reshape a publication summary into the agent-facing payload.
     *
     * <p>When {@code verbose=false} (default for {@code my} / {@code search}): emits the
     * slim shape - {@code id}, {@code title}, {@code description}, {@code owned_by_me},
     * {@code default_trigger_id} (single fireable trigger) or {@code trigger_types}
     * (list of fireable types like {@code ["form","webhook"]}), {@code requirements},
     * {@code last_run}. NO {@code data_inputs_schema} - the agent calls
     * {@code application(action='get', application_id=...)} when it needs field names
     * to build {@code data_inputs}.
     *
     * <p>When {@code verbose=true} (used by {@code get} and {@code create}): adds
     * {@code data_inputs_schema} (full field list with {@code options} for selects)
     * and {@code fireable_triggers} (multi-trigger per-trigger summary). This is the
     * "deciding to execute" payload - the agent already picked an app and now needs
     * the contract to send {@code data_inputs}.
     *
     * <p>When the caller has pre-batched {@code configuredIntegrations} +
     * {@code existingSubWfIds} (executeMy, executeSearch), pass them in to avoid N+1
     * lookups. {@code null} on either argument triggers an inline single-item lookup
     * (only used by executeGet).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> slimForAgent(Map<String, Object> pub, String tenantId,
                                              Set<String> configuredIntegrations,
                                              Set<UUID> existingSubWfIds,
                                              boolean verbose) {
        Map<String, Object> out = new LinkedHashMap<>();
        Object id = pub.get("id");
        out.put("id", id);
        // Disambiguation (fix 2026-06-05): emit `application_id` as a field whose
        // name is IDENTICAL to the get/execute/acquire/visualize parameter, so the
        // agent copies application_id -> application_id with zero guesswork. The
        // bare `id` + `workflowId` pair previously sat adjacent with no annotation;
        // an agent inspecting an owned app picked `workflowId` for the
        // application_id param and got RESOURCE_NOT_FOUND on get (prod 2026-06-05).
        // Keep `id` (back-compat) and `workflowId` (workflow(action='load')); the
        // new field is the one to copy into the application_id parameter.
        out.put("application_id", id);
        out.put("workflowId", pub.get("workflowId"));
        out.put("title", pub.get("title"));
        out.put("description", pub.get("description"));
        if (pub.containsKey("status")) out.put("status", pub.get("status"));
        if (pub.containsKey("visibility")) out.put("visibility", pub.get("visibility"));
        // Keep category - it enables the agent to refine via
        // application(action='search', category='<slug>') for similar apps.
        if (pub.get("category") != null) out.put("category", pub.get("category"));

        if (id == null || tenantId == null) return out;

        // Owned-by-me detection: a published workflow lives in the publisher's
        // tenant as an APPLICATION-type workflow with sourcePublicationId set.
        UUID pubId;
        try {
            pubId = UUID.fromString(id.toString());
        } catch (IllegalArgumentException e) {
            return out;
        }

        // The lookup is best-effort: a repo failure (Hibernate detach, DB hiccup) should
        // not strip the whole list from the agent. Skip the owned-by-me + enrichment
        // path and return the base shape - the agent can still call execute / acquire
        // and learn ownership from the error.
        // Audit 2026-05-17 round-7 - scope-aware lookup. Route through org-
        // strict variant when caller is in an org workspace; personal-strict
        // when no org. Prior strict-tenant only path broke teammate visibility
        // of org-acquired applications.
        Optional<WorkflowEntity> ownedOpt;
        try {
            String reqOrgId = TenantResolver.currentRequestOrganizationId();
            if (reqOrgId == null || reqOrgId.isBlank()) {
                out.put("owned_by_me", false);
                return out;
            }
            ownedOpt = applicationLifecycleService.resolveClone(reqOrgId, pubId);
        } catch (Exception e) {
            log.debug("slimForAgent: owned_by_me lookup skipped for pub={}: {}", id, e.getMessage());
            return out;
        }
        if (ownedOpt.isEmpty()) {
            out.put("owned_by_me", false);
            return out;
        }
        out.put("owned_by_me", true);

        WorkflowEntity workflow = ownedOpt.get();
        // Reconcile workflowId to a workflow the caller can actually load (F12). The publication
        // summary's workflowId is the PUBLISHER's source workflow: loadable (and the right thing
        // to edit) for the publisher, but a 404 on workflow(action='load') for an ACQUIRER. Only
        // when it is NOT in the caller's scope do we fall back to the local APPLICATION clone they
        // own - so a publisher's own apps keep pointing at their editable source (no regression).
        if (workflow.getId() != null && !workflowLoadableByCaller(out.get("workflowId"), tenantId)) {
            out.put("workflowId", workflow.getId().toString());
        }
        WorkflowPlan parsedPlan = null;
        try {
            Map<String, Object> planData = workflow.getPlan();
            if (planData != null && !planData.isEmpty()) {
                parsedPlan = WorkflowPlanParser.parse(
                        planData, workflow.getId().toString(), tenantId);
                String defaultTrigger = AgentTriggerSchema.defaultTriggerId(parsedPlan);
                if (defaultTrigger != null) out.put("default_trigger_id", defaultTrigger);
                // Trigger types stay in the slim projection - the agent uses them
                // to know whether a fireable trigger exists at all (and what kind:
                // form/webhook/schedule/chat) without paying the schema-with-options
                // weight. Mirrors workflow.list's enrichListItem shape.
                List<String> triggerTypes = AgentTriggerSchema.fireableTriggerTypes(parsedPlan);
                if (!triggerTypes.isEmpty()) out.put("trigger_types", triggerTypes);
                if (verbose) {
                    // verbose=true (executeGet, executeCreate) → include the heavy
                    // schema so the agent can immediately build data_inputs.
                    Map<String, Object> schema = AgentTriggerSchema.dataInputsSchema(parsedPlan);
                    if (schema != null) out.put("data_inputs_schema", schema);
                    // Multi-trigger app: surface the full list so the agent can pick
                    // an explicit trigger_id without firing-then-reading the error.
                    if (defaultTrigger == null) {
                        List<Map<String, Object>> all = AgentTriggerSchema.fireableTriggers(parsedPlan, true);
                        if (all.size() > 1) out.put("fireable_triggers", all);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("slimForAgent: plan enrichment skipped for pub={}: {}", id, e.getMessage());
        }

        // PR4 - requirements (integrations + sub-workflows). Reuses the parse above
        // (no second walk). Integrations come from pub.nodeIcons (pre-computed on
        // publication save). When the caller pre-batched configuredIntegrations /
        // existingSubWfIds we use those; otherwise fall back to inline single-item
        // lookups (executeGet path - only one app so batching wouldn't pay off).
        try {
            List<AgentResourceRequirements.RequiredIntegration> integrations =
                    AgentResourceRequirements.integrationsFromNodeIcons(
                            (List<Map<String, Object>>) pub.get("nodeIcons"));
            List<AgentResourceRequirements.RequiredSubWorkflow> subWorkflows =
                    parsedPlan != null
                            ? AgentResourceRequirements.subWorkflowsFromPlan(parsedPlan)
                            : List.of();
            Set<String> configured = configuredIntegrations;
            Set<UUID> existing = existingSubWfIds;
            if ((configured == null || existing == null)
                    && (!integrations.isEmpty() || !subWorkflows.isEmpty())) {
                // Single-item fallback: at most 1 credential call + 1 workflow-repo call.
                if (configured == null) configured = credentialClient.getConfiguredIntegrations(tenantId);
                if (existing == null) {
                    existing = new HashSet<>();
                    if (!subWorkflows.isEmpty()) {
                        for (WorkflowEntity subWorkflow : workflowRepository.findAllById(
                                subWorkflows.stream()
                                        .map(AgentResourceRequirements.RequiredSubWorkflow::workflowId)
                                        .toList())) {
                            if (subWorkflow.getId() != null) {
                                existing.add(subWorkflow.getId());
                            }
                        }
                    }
                }
            }
            Map<String, Object> req = AgentResourceRequirements.buildEnvelope(
                    integrations, subWorkflows, configured, existing);
            if (req != null) out.put("requirements", req);
        } catch (Exception e) {
            log.debug("slimForAgent: requirements skipped for pub={}: {}", id, e.getMessage());
        }

        try {
            // Single-row query instead of loading the full ordered list and
            // discarding all but the head - match what WorkflowCrudModule's
            // enrichListItem already does.
            workflowRunRepository.findFirstByWorkflowIdOrderByStartedAtDesc(workflow.getId())
                    .ifPresent(last -> {
                        Map<String, Object> lastRun = new LinkedHashMap<>();
                        lastRun.put("status", last.getStatus() != null ? last.getStatus().name() : null);
                        if (last.getStartedAt() != null) lastRun.put("at", last.getStartedAt().toString());
                        out.put("last_run", lastRun);
                    });
        } catch (Exception e) {
            log.debug("slimForAgent: last_run enrichment skipped for pub={}: {}", id, e.getMessage());
        }

        return out;
    }

    // ==================== GET ====================
    private ToolExecutionResult executeGet(Map<String, Object> parameters, ToolExecutionContext context) {
        String applicationId = getStringParam(parameters, "application_id");
        if (applicationId == null || applicationId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "application_id is required for get action");
        }

        List<String> allowedAppIds = getAllowedApplicationIds(context);
        if (allowedAppIds != null && !allowedAppIds.contains(applicationId)) {
            log.info("Agent restriction: application {} not in allowed list", applicationId);
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED,
                "This application is not in your approved application list.");
        }

        try {
            UUID id = UUID.fromString(applicationId);
            Map<String, Object> pub = publicationClient.getPublicationById(id);

            if (pub == null) {
                // Fix 2026-06-05: the most common cause of a null publication on
                // `get` is the agent passing a workflowId where an application_id
                // was expected (my/search emit both UUIDs side by side). Detect
                // that exact case and hand back the correct application_id instead
                // of a dead-end 404, turning a cul-de-sac into a single rebound.
                Optional<String> wfHint = ApplicationIdDisambiguator.workflowIdHint(workflowRepository, id);
                if (wfHint.isPresent()) {
                    return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, wfHint.get());
                }
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND,
                    "Application not found: " + applicationId);
            }

            // No `visualization` metadata on the `get` action - this is a pure
            // inspection call (the agent reads application config to decide what
            // to do next). Auto-opening the right-side panel here would hijack
            // focus from whatever the user was reading. The explicit
            // `application(action='visualize')` path keeps its visualization
            // (it's the user-intent "show this app now") - see executeVisualize.
            //
            // Slim-and-enrich same way `my`/`search` do, so `get` is a SUPERSET
            // of those payloads (richer = invites use). Without this the agent
            // gets *more* useful data from `my` than from the dedicated `get`
            // action, which is an inverted incentive flagged by the audit.
            Map<String, Object> enriched = slimForAgent(pub, context != null ? context.tenantId() : null);
            return ToolExecutionResult.success(enriched);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                "Invalid application ID format. Expected UUID.");
        } catch (Exception e) {
            log.error("Error getting application {}: {}", applicationId, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Failed to get application: " + e.getMessage());
        }
    }

    // ==================== ACQUIRE ====================
    private ToolExecutionResult executeAcquire(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        String applicationId = getStringParam(parameters, "application_id");
        if (applicationId == null || applicationId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "application_id is required for acquire action");
        }

        List<String> allowedAppIds = getAllowedApplicationIds(context);
        if (allowedAppIds != null && !allowedAppIds.contains(applicationId)) {
            log.info("Agent restriction: application {} not in allowed list", applicationId);
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED,
                "This application is not in your approved application list.");
        }

        try {
            UUID id = UUID.fromString(applicationId);
            // Audit 2026-05-16 round-2: thread ctx.orgId() so the acquired-workflow
            // clone lands in the acquirer's active workspace (not their personal scope).
            String orgId = context != null ? context.orgId() : null;
            Map<String, Object> acquireResult = publicationClient.acquirePublication(id, tenantId, orgId);

            String workflowId = (String) acquireResult.get("id");
            String workflowTitle = (String) acquireResult.get("title");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "OK");
            result.put("message", "Application acquired successfully");
            result.put("workflowId", workflowId);
            result.put("workflowName", workflowTitle);
            // Acquire surfaces ONLY the application card - the underlying workflow is an
            // implementation detail cloned during acquisition (and may still be settling),
            // so visualizing it shows a workflow the user neither asked for nor can open.
            // Mirrors create/execute/visualize, which all emit an application-only marker.
            result.put("marker", "[visualize:application:" + applicationId + "]");
            return ToolExecutionResult.success(result);
        } catch (com.apimarketplace.auth.client.entitlement.LimitExceededException e) {
            return ToolExecutionResult.failure(ToolErrorCode.QUOTA_EXCEEDED, e.getMessage());
        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("not found")) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, msg);
            }
            if (msg != null && msg.contains("already acquired")) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_ALREADY_EXISTS, msg);
            }
            if (msg != null && msg.contains("your own")) {
                return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, msg);
            }
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, msg);
        } catch (Exception e) {
            // RuntimeException wrapping LimitExceededException from PublicationClient
            Throwable cause = e.getCause();
            if (cause instanceof com.apimarketplace.auth.client.entitlement.LimitExceededException lex) {
                return ToolExecutionResult.failure(ToolErrorCode.QUOTA_EXCEEDED, lex.getMessage());
            }
            log.error("Error acquiring application {}: {}", applicationId, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Failed to acquire application: " + e.getMessage());
        }
    }

    // ==================== UNINSTALL ====================

    /**
     * Remove an acquired application from the caller's workspace. An acquired app is a local
     * APPLICATION clone (workflow + runs) tagged with the source publication; uninstall deletes
     * that clone via the canonical cascade delete (runs, plan versions, schedules, files). The
     * marketplace publication and the publisher's original are untouched, and the acquisition
     * receipt is retained so the app can be acquired again. Idempotent: an already-removed or
     * never-acquired app returns RESOURCE_NOT_FOUND.
     */
    private ToolExecutionResult executeUninstall(Map<String, Object> parameters, String tenantId,
                                                 ToolExecutionContext context) {
        String applicationId = getStringParam(parameters, "application_id");
        if (applicationId == null || applicationId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "application_id is required for uninstall action");
        }

        List<String> allowedAppIds = getAllowedApplicationIds(context);
        if (allowedAppIds != null && !allowedAppIds.contains(applicationId)) {
            log.info("Agent restriction: application {} not in allowed list", applicationId);
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED,
                "This application is not in your approved application list.");
        }

        // Resolve the local APPLICATION clone org-scoped (same lookup as runs/execute).
        WorkflowEntity clone = resolveAcquiredWorkflow(applicationId, context);
        if (clone == null) {
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND,
                "No acquired application found for this ID in your workspace - it was already uninstalled, "
                + "or never acquired here. Call application(action='my') to list the apps you can uninstall.");
        }
        if (workflowManagementService == null) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Uninstall is currently unavailable. Try again later.");
        }

        try {
            UUID cloneId = clone.getId();
            boolean deleted = workflowManagementService.deleteWorkflow(cloneId, tenantId);
            if (!deleted) {
                return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                    "Could not uninstall the application - it is not in your current workspace.");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "OK");
            result.put("message", "Application uninstalled. The cloned workflow and its runs were removed from "
                + "your workspace; the marketplace listing is unaffected and you can acquire it again.");
            result.put("application_id", applicationId);
            result.put("removed_workflow_id", cloneId.toString());
            log.info("Uninstalled acquired application {} (clone workflow {}) for tenant {}",
                    applicationId, cloneId, tenantId);
            return ToolExecutionResult.success(result);
        } catch (Exception e) {
            log.error("Error uninstalling application {}: {}", applicationId, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Failed to uninstall application: " + e.getMessage());
        }
    }

    // ==================== VISUALIZE ====================
    private ToolExecutionResult executeVisualize(Map<String, Object> parameters, ToolExecutionContext context) {
        String applicationId = getStringParam(parameters, "application_id");
        if (applicationId == null || applicationId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "application_id is required for visualize action");
        }

        List<String> allowedAppIds = getAllowedApplicationIds(context);
        if (allowedAppIds != null && !allowedAppIds.contains(applicationId)) {
            log.info("Agent restriction: application {} not in allowed list", applicationId);
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED,
                "This application is not in your approved application list.");
        }

        String titleOverride = getStringParam(parameters, "title");

        try {
            UUID id = UUID.fromString(applicationId);
            Map<String, Object> pub = publicationClient.getPublicationById(id);

            if (pub == null) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND,
                    "Application not found: " + applicationId);
            }

            String pubTitle = (String) pub.get("title");
            String displayTitle = titleOverride != null ? titleOverride : pubTitle;
            String displayMode = (String) pub.getOrDefault("displayMode", "SHOWCASE");
            String publisherName = pub.get("publisherName") != null ? pub.get("publisherName").toString() : "Unknown";

            // Anti-duplicate check
            String conversationId = getConversationId(context);
            if (conversationId != null && visualizationToolsProvider != null) {
                if (visualizationToolsProvider.wasAlreadyVisualized(conversationId, "application", applicationId)) {
                    return ToolExecutionResult.success(Map.of(
                        "status", "already_displayed",
                        "type", "application",
                        "id", applicationId,
                        "message", "Application '" + displayTitle + "' is already displayed in the conversation."
                    ));
                }
                visualizationToolsProvider.markAsVisualized(conversationId, "application", applicationId);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("display", Map.of(
                "type", "application",
                "id", applicationId,
                "title", displayTitle,
                "displayMode", displayMode,
                "publisherName", publisherName
            ));
            result.put("marker", "[visualize:application:" + applicationId + "]");
            result.put("message", "Application '" + displayTitle + "' is now displayed. The user can click to open it.");

            Map<String, Object> metadata = Map.of(
                "visualization", Map.of(
                    "type", "application",
                    "id", applicationId,
                    "title", displayTitle
                )
            );

            return ToolExecutionResult.success(result, metadata);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                "Invalid application ID format. Expected UUID.");
        } catch (Exception e) {
            log.error("Error visualizing application {}: {}", applicationId, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Failed to visualize application: " + e.getMessage());
        }
    }

    // ==================== CREATE ====================
    private ToolExecutionResult executeCreate(Map<String, Object> parameters, String tenantId,
                                               ToolExecutionContext context) {
        // 1. Resolve workflow_id
        String workflowIdStr = getStringParam(parameters, "workflow_id");
        if ((workflowIdStr == null || workflowIdStr.isBlank()) && context != null && context.viewingWorkflowId() != null) {
            workflowIdStr = context.viewingWorkflowId();
        }
        if (workflowIdStr == null || workflowIdStr.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "workflow_id is required for create action. Provide it explicitly or navigate to a workflow first.");
        }

        UUID workflowId;
        try {
            workflowId = UUID.fromString(workflowIdStr);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                "Invalid workflow_id format. Expected UUID.");
        }

        try {
            // 2. Load workflow and verify ownership
            Optional<WorkflowEntity> workflowOpt = workflowRepository.findById(workflowId);
            if (workflowOpt.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND,
                    "Workflow not found: " + workflowId);
            }
            WorkflowEntity workflow = workflowOpt.get();
            // Scope check - org-aware. Pre-fix this was strict-tenant equality,
            // so a workflow tagged with an org_id could not be wrapped in an
            // application by another org member (publication.acquire would
            // succeed but the subsequent application(action='create') call
            // would 403). Strict-isolation: any org member can wrap any of
            // the org's workflows; cross-workspace caller still rejected.
            String orgId = context != null ? context.orgId() : null;
            if (!ScopeGuard.isInStrictScope(tenantId, orgId,
                    workflow.getTenantId(), workflow.getOrganizationId())) {
                return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED,
                    "Workflow is not in your current workspace.");
            }

            // 3. Get latest plan version
            Optional<Integer> maxVersionOpt = planVersionRepository.getMaxVersion(workflowId);
            Integer planVersion = maxVersionOpt.orElse(null);

            // 4. Parse plan and find entry interface
            Map<String, Object> planData = workflow.getPlan();
            if (planData == null || planData.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.WORKFLOW_INVALID,
                    "Workflow has no plan. Save the workflow first.");
            }

            WorkflowPlan plan = WorkflowPlanParser.parse(planData, workflowId.toString(), tenantId);
            List<InterfaceDef> interfaces = plan.getInterfaces();
            if (interfaces == null || interfaces.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.WORKFLOW_INVALID,
                    "Workflow has no interface - an application requires at least one interface. " +
                    "Add an interface node to the workflow first.");
            }

            // Find entry interface, fallback to first (shared with workflow(action='publish')).
            InterfaceDef entryInterface = showcaseResolver.resolveEntryInterface(plan)
                .orElse(interfaces.get(0));

            UUID showcaseInterfaceId = UUID.fromString(entryInterface.id());

            // 5. Resolve the showcase run + epoch - SAME contract as the publish
            // wizard (ShareWorkflowModal): the showcase must be a COMPLETED,
            // non-step-by-step (automatic) run. The agent MAY pin a specific run
            // via `run_id` and a specific epoch via `epoch`; when omitted we
            // smart-default to the latest completed automatic run (DESC order)
            // and leave the epoch unpinned (null) - the render surface then
            // defaults to the latest epoch, exactly like the wizard's "All" view.
            String requestedRunId = getStringParam(parameters, "run_id");
            Integer showcaseEpoch = getIntParamNullable(parameters, "epoch");
            String showcaseRunId = null;
            if (requestedRunId != null && !requestedRunId.isBlank()) {
                WorkflowRunEntity pinned = workflowRunRepository.findByRunIdPublic(requestedRunId).orElse(null);
                if (pinned == null || pinned.getWorkflow() == null
                        || !workflowId.equals(pinned.getWorkflow().getId())) {
                    return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND,
                        "run_id '" + requestedRunId + "' is not a run of this workflow. "
                        + "Call application(action='runs') (or workflow(action='runs')) to list valid run ids, "
                        + "or omit run_id to auto-pick the latest completed automatic run.");
                }
                if (pinned.isStepByStepMode()) {
                    return ToolExecutionResult.failure(ToolErrorCode.WORKFLOW_INVALID,
                        "run_id '" + requestedRunId + "' is a step-by-step run and cannot be showcased. "
                        + "Pick an automatic run, or omit run_id to auto-pick the latest completed automatic run.");
                }
                if (!showcaseResolver.isShowcaseableStatus(pinned.getStatus())) {
                    return ToolExecutionResult.failure(ToolErrorCode.WORKFLOW_INVALID,
                        "run_id '" + requestedRunId + "' has status " + pinned.getStatus()
                        + " - only a successful run can be showcased (COMPLETED, PARTIAL_SUCCESS, or "
                        + "WAITING_TRIGGER for reusable triggers). Wait for it to finish or pick another run.");
                }
                if ("showcase".equalsIgnoreCase(pinned.getSource())) {
                    return ToolExecutionResult.failure(ToolErrorCode.WORKFLOW_INVALID,
                        "run_id '" + requestedRunId + "' is a showcase snapshot, not a real execution, and "
                        + "cannot itself be showcased. Pick a real run via application(action='runs'), or omit "
                        + "run_id to auto-pick the latest real completed automatic run.");
                }
                showcaseRunId = pinned.getRunIdPublic();
            } else {
                // Latest showcaseable run (automatic, successful/idle, not a showcase clone).
                // The WAITING_TRIGGER acceptance (via the resolver) is what lets reusable-trigger
                // apps - the common case - be published from their post-fire idle state.
                showcaseRunId = showcaseResolver.resolveLatestShowcaseRunId(workflowId).orElse(null);
            }

            // Zero-run guard - an application needs a completed automatic run to
            // showcase, exactly like the publish wizard (which won't let you
            // publish without selecting one). PRIVATE publishes don't enforce
            // this in publication-service, so the agent module hints here instead
            // of silently producing a runless app (the "No run available" panel).
            if (showcaseRunId == null) {
                return ToolExecutionResult.failure(ToolErrorCode.WORKFLOW_INVALID,
                    "No showcaseable run for this workflow yet. Run it first with "
                    + "workflow(action='execute', workflow_id='" + workflowId + "') (automatic mode, "
                    + "not step-by-step), wait until it has completed at least one cycle (status "
                    + "COMPLETED, PARTIAL_SUCCESS, or - for reusable triggers like webhook/manual/"
                    + "chat/schedule - WAITING_TRIGGER), then retry application(action='create'). "
                    + "Once a run exists you may pass run_id and epoch explicitly; omit them to "
                    + "auto-pick the latest run + latest epoch.");
            }

            // 6. Resolve title and description (parameter override or workflow defaults)
            String title = getStringParam(parameters, "title");
            if (title == null || title.isBlank()) {
                title = workflow.getName();
            }
            String description = getStringParam(parameters, "description");
            if (description == null || description.isBlank()) {
                description = workflow.getDescription();
            }

            // 7. Publish as PRIVATE APPLICATION via publication-service
            Map<String, Object> publishRequest = new LinkedHashMap<>();
            publishRequest.put("workflowId", workflowId.toString());
            publishRequest.put("title", title);
            publishRequest.put("description", description);
            publishRequest.put("showcaseInterfaceId", showcaseInterfaceId.toString());
            publishRequest.put("showcaseRunId", showcaseRunId);
            // Forward the chosen epoch through the SAME internal publish pipe the
            // UI wizard uses (InternalPublicationController now reads it). Omitted
            // → null → all epochs captured, render defaults to the latest.
            if (showcaseEpoch != null) {
                publishRequest.put("showcaseEpoch", showcaseEpoch);
            }
            publishRequest.put("creditsPerUse", 0);
            publishRequest.put("publisherName", tenantId);
            publishRequest.put("visibility", "PRIVATE");
            if (planVersion != null) {
                publishRequest.put("planVersion", planVersion);
            }
            publishRequest.put("displayMode", "APPLICATION");

            // Audit 2026-05-16 round-2: thread ctx.orgId() so application's
            // publication carries owner_type='ORG' in org workspace.
            String publishOrgId = context != null ? context.orgId() : null;
            Map<String, Object> publication = publicationClient.publishWorkflow(publishRequest, tenantId, publishOrgId);

            // 8. Auto-grant + return success
            String publicationId = (String) publication.get("id");
            ToolAccessControl.grantCreatedResource(context.credentials(), "application", publicationId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "OK");
            result.put("message", "Application created successfully");
            result.put("publicationId", publicationId);
            result.put("workflowId", workflowId.toString());
            result.put("title", title);
            result.put("visibility", "PRIVATE");
            result.put("displayMode", "APPLICATION");
            // showcaseRunId is guaranteed non-null here (zero-run guard above).
            result.put("hasShowcaseRun", true);
            result.put("showcaseRunId", showcaseRunId);
            if (showcaseEpoch != null) result.put("showcaseEpoch", showcaseEpoch);
            // 4-field marker pins the chat card + side panel to the showcase run,
            // so the card renders the live interface preview (not the node-icon
            // fallback) and the panel mounts against this run - same shape the
            // execute path emits. Render defaults to the latest epoch when unpinned.
            result.put("marker", "[visualize:application:" + publicationId + ":" + showcaseRunId + "]");

            // Mirror the my/search slim payload: include `default_trigger_id` +
            // `data_inputs_schema` so the agent can chain create→execute without
            // a redundant `my`/`get` round-trip. The 2026-05-15 prod regression
            // (agent hallucinated form-field names on FlyFinder execute) closes
            // for the my/search path AND for the create→execute path here.
            String defaultTriggerId = AgentTriggerSchema.defaultTriggerId(plan);
            if (defaultTriggerId != null) result.put("default_trigger_id", defaultTriggerId);
            Map<String, Object> schema = AgentTriggerSchema.dataInputsSchema(plan);
            if (schema != null) result.put("data_inputs_schema", schema);
            result.put("NEXT", "application(action='execute', application_id='" + publicationId
                    + "'" + (schema != null ? ", data_inputs={...})" : ")")
                    + " - use the field names from data_inputs_schema verbatim");

            log.info("Created PRIVATE application for workflow {} by tenant {} (showcaseRun={}, epoch={})",
                    workflowId, tenantId, showcaseRunId, showcaseEpoch);
            // Visualization metadata carries the run (and epoch when pinned) so the
            // auto-opened card renders the live showcase exactly like execute does.
            Map<String, Object> viz = new LinkedHashMap<>();
            viz.put("type", "application");
            viz.put("id", publicationId != null ? publicationId : "");
            viz.put("title", title != null ? title : "");
            viz.put("runId", showcaseRunId);
            if (showcaseEpoch != null) viz.put("epoch", showcaseEpoch);
            Map<String, Object> metadata = Map.of("visualization", viz);
            return ToolExecutionResult.success(result, metadata);

        } catch (IllegalArgumentException e) {
            String msg = e.getMessage();
            if (msg != null && msg.contains("does not belong")) {
                return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, msg);
            }
            if (msg != null && (msg.contains("no plan") || msg.contains("no interface")
                    || msg.contains("step-by-step") || msg.contains("display modes require"))) {
                return ToolExecutionResult.failure(ToolErrorCode.WORKFLOW_INVALID, msg);
            }
            if (msg != null && msg.contains("not found")) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, msg);
            }
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, msg);
        } catch (Exception e) {
            log.error("Error creating application for workflow {}: {}", workflowIdStr, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Failed to create application: " + e.getMessage());
        }
    }

    /**
     * Carrier for the two batched lookups used by {@link #slimForAgent} on multi-item paths.
     * Computed ONCE at the start of executeMy/executeSearch from the full page, then handed to
     * every per-item enrichment to skip per-item I/O.
     */
    private record RequirementsPrebatch(Set<String> configuredIntegrations, Set<UUID> existingSubWfIds) {}

    /**
     * Pre-fetch the {@code configured-integrations} set + sub-workflow existence set for
     * a page of publications. Visits each pub's {@code nodeIcons} (no plan parse) to
     * collect distinct integrations, parses each pub's local APPLICATION-workflow plan
     * (once) to collect sub-workflow refs, then makes ONE credential-service call + ONE
     * workflow-repo batch call. The result is passed back to {@link #slimForAgent}.
     *
     * <p>Best-effort: each lookup is independently guarded - a credential-service hiccup
     * leaves {@code configuredIntegrations} empty (agent sees {@code configured: false}
     * everywhere - strictly more conservative than a false positive).
     */
    @SuppressWarnings("unchecked")
    private RequirementsPrebatch prebatchRequirements(List<Map<String, Object>> pubs, String tenantId) {
        if (pubs == null || pubs.isEmpty() || tenantId == null) {
            return new RequirementsPrebatch(Set.of(), Set.of());
        }
        Set<UUID> referencedSubWfIds = new LinkedHashSet<>();
        for (Map<String, Object> pub : pubs) {
            Object id = pub != null ? pub.get("id") : null;
            if (!(id instanceof String s) || s.isBlank()) continue;
            UUID pubId;
            try { pubId = UUID.fromString(s); } catch (IllegalArgumentException e) { continue; }
            // Audit 2026-05-17 round-7 - same scope-aware routing as L327.
            Optional<WorkflowEntity> owned;
            try {
                String reqOrgId = TenantResolver.currentRequestOrganizationId();
                if (reqOrgId == null || reqOrgId.isBlank()) continue;
                owned = applicationLifecycleService.resolveClone(reqOrgId, pubId);
            } catch (Exception e) { continue; }
            if (owned.isEmpty()) continue;
            WorkflowEntity wf = owned.get();
            Map<String, Object> planData = wf.getPlan();
            if (planData == null || planData.isEmpty()) continue;
            try {
                WorkflowPlan plan = WorkflowPlanParser.parse(planData, wf.getId().toString(), tenantId);
                for (var ref : AgentResourceRequirements.subWorkflowsFromPlan(plan)) {
                    referencedSubWfIds.add(ref.workflowId());
                }
            } catch (Exception ignored) { /* per-item parse failure is silent */ }
        }
        Set<String> configured;
        try {
            configured = credentialClient.getConfiguredIntegrations(tenantId);
        } catch (Exception e) {
            log.debug("application.list: configured-integrations lookup skipped: {}", e.getMessage());
            configured = Set.of();
        }
        Set<UUID> existing = new HashSet<>();
        if (!referencedSubWfIds.isEmpty()) {
            try {
                workflowRepository.findAllById(referencedSubWfIds)
                        .forEach(w -> { if (w.getId() != null) existing.add(w.getId()); });
            } catch (Exception e) {
                log.debug("application.list: sub-workflow existence lookup skipped: {}", e.getMessage());
            }
        }
        return new RequirementsPrebatch(configured, existing);
    }

    // ==================== RUNS ====================

    private ToolExecutionResult executeRuns(Map<String, Object> parameters, String tenantId,
                                             ToolExecutionContext context) {
        String applicationId = getStringParam(parameters, "application_id");
        if (applicationId == null || applicationId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "application_id is required for runs action");
        }

        List<String> allowedAppIds = getAllowedApplicationIds(context);
        if (allowedAppIds != null && !allowedAppIds.contains(applicationId)) {
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED,
                "This application is not in your approved application list.");
        }

        // Resolve acquired workflow via sourcePublicationId
        WorkflowEntity workflow = resolveAcquiredWorkflow(applicationId, context);
        if (workflow == null) {
            return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND,
                "No acquired application found for this ID.");
        }
        UUID workflowId = workflow.getId();

        AgentListEnvelope.Spec spec = AgentListEnvelope.Spec.of(
                        AgentListEnvelope.Caps.LARGE, "runs", "runs", "runs")
                .withSuggestedFilters(List.of())
                .withNext(Map.of(
                        "inspect_run", "application(action='get_run', run_id='<run_id>')",
                        "inspect_run_epoch", "application(action='get_run', run_id='<run_id>', epoch=N)"
                ));
        AgentListEnvelope.Bounds bounds;
        try {
            bounds = AgentListEnvelope.readBounds(parameters, spec, Set.of("application_id"));
        } catch (AgentListEnvelope.InvalidParamsException e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.code + ": " + e.getMessage());
        }

        try {
            var page = workflowRunRepository.findRunSummariesByWorkflowId(
                    workflowId, OffsetLimitPageable.of(bounds.offset(), bounds.limit()));

            List<Map<String, Object>> runs = page.getContent().stream().map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("run_id", r.getRunIdPublic());
                m.put("status", r.getStatus() != null ? r.getStatus().name() : null);
                m.put("plan_version", r.getPlanVersion());
                m.put("started_at", r.getStartedAt() != null ? r.getStartedAt().toString() : null);
                m.put("ended_at", r.getEndedAt() != null ? r.getEndedAt().toString() : null);
                m.put("duration_ms", r.getDurationMs());
                m.put("total_nodes", r.getTotalNodes());
                m.put("execution_mode", r.getExecutionMode() != null ? r.getExecutionMode().name() : null);
                return m;
            }).toList();

            Map<String, Object> data = new LinkedHashMap<>(AgentListEnvelope
                    .paginateProjection(runs, bounds, page.getTotalElements(), spec));
            data.put("application_id", applicationId);
            data.put("workflow_id", workflowId.toString());
            return ToolExecutionResult.success(data);
        } catch (Exception e) {
            log.error("Failed to list runs for application {}: {}", applicationId, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Failed to list runs: " + e.getMessage());
        }
    }

    // ==================== GET RUN ====================

    private ToolExecutionResult executeGetRun(Map<String, Object> parameters, String tenantId,
                                               ToolExecutionContext context) {
        String runId = getStringParam(parameters, "run_id");
        if (runId == null || runId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "run_id is required");
        }

        try {
            Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findByRunIdPublic(runId);
            if (runOpt.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Run not found: " + runId);
            }

            WorkflowRunEntity run = runOpt.get();
            String orgId = context != null ? context.orgId() : null;
            if (!ScopeGuard.isInStrictScope(tenantId, orgId, run.getTenantId(), run.getOrganizationId())) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Run not found: " + runId);
            }

            WorkflowEntity workflow = run.getWorkflow();
            WorkflowPlan plan = resolvePlanForRun(run, workflow, tenantId);

            Integer epoch = getIntParamNullable(parameters, "epoch");
            Map<String, Object> result;
            if (epoch != null) {
                result = agentWorkflowFireService.buildEpochDetailReport(run, plan, epoch, tenantId);
            } else {
                result = agentWorkflowFireService.buildRunMacroReport(run, plan, tenantId);
            }

            // Override NEXT hints to point to application() instead of workflow()
            overrideNextHints(result);
            return ToolExecutionResult.success(result);
        } catch (Exception e) {
            log.error("Failed to get run {}: {}", runId, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Failed to get run: " + e.getMessage());
        }
    }

    // ==================== GET NODE OUTPUT ====================

    private ToolExecutionResult executeGetNodeOutput(Map<String, Object> parameters, String tenantId,
                                                      ToolExecutionContext context) {
        String runId = getStringParam(parameters, "run_id");
        if (runId == null || runId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "run_id is required");
        }
        Integer epoch = getIntParamNullable(parameters, "epoch");
        if (epoch == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "epoch is required for get_node_output");
        }
        String nodeId = getStringParam(parameters, "node_id");
        if (nodeId == null || nodeId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "node_id is required for get_node_output");
        }

        Integer itemIndex = getIntParamNullable(parameters, "item_index");
        Integer iteration = getIntParamNullable(parameters, "iteration");
        Integer spawn = getIntParamNullable(parameters, "spawn");
        // Field-expand: page a single output field's full text value past the 128 KB preview cap
        // (same offset/NEXT idiom as the files tool). Absent = current capped-output behaviour.
        String expandField = getStringParam(parameters, "field");
        Integer fieldOffset = getIntParamNullable(parameters, "offset");
        Integer fieldMaxBytes = getIntParamNullable(parameters, "max_bytes");

        try {
            Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findByRunIdPublic(runId);
            if (runOpt.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Run not found: " + runId);
            }

            WorkflowRunEntity run = runOpt.get();
            String orgId = context != null ? context.orgId() : null;
            if (!ScopeGuard.isInStrictScope(tenantId, orgId, run.getTenantId(), run.getOrganizationId())) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Run not found: " + runId);
            }

            WorkflowEntity workflow = run.getWorkflow();
            WorkflowPlan plan = resolvePlanForRun(run, workflow, tenantId);

            Map<String, Object> result = agentWorkflowFireService.buildNodeOutputReport(
                    run, plan, epoch, nodeId, tenantId, itemIndex, iteration, spawn,
                    expandField, fieldOffset, fieldMaxBytes);

            overrideNextHints(result);
            return ToolExecutionResult.success(result);
        } catch (Exception e) {
            log.error("Failed to get node output for run {}, node {}: {}", runId, nodeId, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Failed to get node output: " + e.getMessage());
        }
    }

    // ==================== SHARED RUN-INSPECTION HELPERS ====================

    /**
     * Resolve the acquired APPLICATION workflow for a given publication ID.
     * Uses org-scoped lookup matching the execute module's pattern.
     */
    private WorkflowEntity resolveAcquiredWorkflow(String applicationId, ToolExecutionContext context) {
        UUID pubId;
        try {
            pubId = UUID.fromString(applicationId);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String orgId = context != null ? context.orgId() : null;
        if (orgId == null || orgId.isBlank()) return null;
        return applicationLifecycleService.resolveClone(orgId, pubId).orElse(null);
    }

    /**
     * Whether the given workflow id resolves to a workflow the caller can load (in their strict
     * tenant/org scope). Used to decide if a publication's reported workflowId (the publisher's
     * source) is usable as-is or must be swapped for the caller's local clone (F12). A missing or
     * malformed id, or any lookup failure, counts as not-loadable (conservative).
     */
    private boolean workflowLoadableByCaller(Object workflowId, String tenantId) {
        if (!(workflowId instanceof String s) || s.isBlank()) return false;
        try {
            UUID wfId = UUID.fromString(s);
            return workflowRepository.findById(wfId)
                    .map(w -> ScopeGuard.isInStrictScope(tenantId,
                            TenantResolver.currentRequestOrganizationId(),
                            w.getTenantId(), w.getOrganizationId()))
                    .orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Resolve the plan for a specific run: prefer the versioned plan, fallback to current workflow plan.
     * Same pattern as WorkflowCrudModule.resolvePlanForRun.
     */
    private WorkflowPlan resolvePlanForRun(WorkflowRunEntity run, WorkflowEntity workflow, String tenantId) {
        if (run.getPlanVersion() != null && workflow.getId() != null) {
            var versionOpt = planVersionService.getVersion(workflow.getId(), run.getPlanVersion());
            if (versionOpt.isPresent()) {
                return WorkflowPlan.fromMap(versionOpt.get().getPlan(),
                        workflow.getId().toString(), tenantId);
            }
        }
        return WorkflowPlan.fromMap(workflow.getPlan(), workflow.getId().toString(), tenantId);
    }

    /**
     * Override NEXT/hint/note fields in report maps to point to application() instead of workflow().
     * The reports from AgentWorkflowFireService reference workflow(action=...) by default.
     */
    private void overrideNextHints(Map<String, Object> result) {
        rewriteWorkflowToApplication(result);
    }

    /**
     * Recursively rewrite the report's own tool-call pointers {@code workflow(action=…} →
     * {@code application(action=…} in every NEXT/hint/note pointer, including the nested ones the
     * field-expand path emits ({@code output_field.NEXT} and {@code output.<field>.NEXT}, possibly
     * inside arrays). Matching the canonical {@code workflow(action=} prefix (not a bare
     * {@code workflow(}) means loaded node-output data that merely mentions "workflow(...)" in prose -
     * even under a key named note/hint/NEXT - is left untouched.
     */
    @SuppressWarnings("unchecked")
    static void rewriteWorkflowToApplication(Object node) {
        if (node instanceof Map<?, ?> mm) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) mm).entrySet()) {
                Object v = e.getValue();
                if (v instanceof String s) {
                    String k = e.getKey();
                    if (("NEXT".equals(k) || "hint".equals(k) || "note".equals(k)) && s.contains("workflow(action=")) {
                        e.setValue(s.replace("workflow(action=", "application(action="));
                    }
                } else {
                    rewriteWorkflowToApplication(v);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object v : list) rewriteWorkflowToApplication(v);
        }
    }

    // ==================== HELPERS ====================

    private String getConversationId(ToolExecutionContext context) {
        if (context == null || context.credentials() == null) return null;
        Object convId = context.credentials().get("conversationId");
        return convId != null ? convId.toString() : null;
    }

    private String getStringParam(Map<String, Object> parameters, String key) {
        Object val = parameters.get(key);
        return val instanceof String s ? s : null;
    }

    private int getIntParam(Map<String, Object> parameters, String key, int defaultValue) {
        Object val = parameters.get(key);
        if (val instanceof Number n) return n.intValue();
        return defaultValue;
    }

    private Integer getIntParamNullable(Map<String, Object> parameters, String key) {
        Object val = parameters.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s && !s.isBlank()) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> getAllowedApplicationIds(ToolExecutionContext context) {
        if (context == null || context.credentials() == null) return null;
        return ToolAccessControl.getAllowedIds(context.credentials(), "application");
    }

}
