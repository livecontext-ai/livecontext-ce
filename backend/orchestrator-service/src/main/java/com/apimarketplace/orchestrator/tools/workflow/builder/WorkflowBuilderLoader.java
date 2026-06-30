package com.apimarketplace.orchestrator.tools.workflow.builder;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.ColumnMappingSpecDto;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.service.NodeLibraryService;
import com.apimarketplace.orchestrator.services.WorkflowManagementService;
import com.apimarketplace.orchestrator.services.WorkflowPlanVersionService;
import com.apimarketplace.orchestrator.utils.LabelNormalizer;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.StandaloneWebhookDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Handles loading existing workflows for editing.
 * Actions: load, save, discard
 *
 * Load supports:
 * - workflow(action='load', id='uuid') - Load by workflow ID
 * - workflow(action='load', name='...') - Load by name (most recent if duplicates)
 *
 * Note: workflow_list() is a separate tool for listing workflows.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowBuilderLoader {

    private final WorkflowBuilderSessionStore sessionStore;
    private final WorkflowManagementService workflowService;
    private final WorkflowRepository workflowRepository;
    private final WorkflowBuilderLogger buildLogger;
    private final WorkflowBuilderValidator validator;
    private final DataSourceClient dataSourceClient;
    private final ToolSchemaFetcher toolSchemaFetcher;
    private final NodeLibraryService nodeLibraryService;
    private final ObjectMapper objectMapper;
    private final TriggerClient triggerClient;
    private final WorkflowPlanVersionService versionService;
    private final AgentWorkflowFireService agentFireService;

    /**
     * Mirrors {@code workflow.builder.allow-create-without-validation} from
     * {@link WorkflowBuilderProvider}. When {@code false} (default), save is
     * blocked when the unified validator reports errors - same rule as the
     * {@code finish} action on a new workflow, so editing an existing workflow
     * cannot bypass validation that creation enforces.
     */
    @Value("${workflow.builder.allow-create-without-validation:false}")
    private boolean allowSaveWithoutValidation;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    /**
     * List all workflows for the caller workspace.
     * <p>BATCH-B (2026-05-20) - strict-org routing when {@code orgId} is provided
     * (which is now the case for every MCP call after the gateway propagates the
     * caller's X-Organization-ID header). Falls back to tenant-only finder when
     * the caller is in personal scope (no org), matching the existing semantics
     * before strict-org rollout.
     */
    public ToolExecutionResult executeListWorkflows(String tenantId, String orgId, Map<String, Object> parameters) {
        try {
            List<WorkflowEntity> workflows = (orgId != null && !orgId.isBlank())
                    ? workflowRepository.findByOrganizationIdAndWorkflowTypeOrderByUpdatedAtDescStrict(
                            orgId, WorkflowEntity.WorkflowType.WORKFLOW)
                    : workflowRepository.findByTenantIdAndWorkflowTypeOrderByUpdatedAtDesc(
                            tenantId, WorkflowEntity.WorkflowType.WORKFLOW);

            if (workflows.isEmpty()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "OK");
                result.put("message", "No workflows found.");
                result.put("count", 0);
                result.put("tip", "Use workflow(action='init', name='...') to create a new workflow.");
                return ToolExecutionResult.success(result);
            }

            // Build summary list
            List<Map<String, Object>> workflowList = new ArrayList<>();
            for (WorkflowEntity wf : workflows) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", wf.getId().toString());
                item.put("name", wf.getName());
                item.put("status", wf.getStatus().name());
                item.put("created", DATE_FORMAT.format(wf.getCreatedAt()));
                if (wf.getUpdatedAt() != null) {
                    item.put("updated", DATE_FORMAT.format(wf.getUpdatedAt()));
                }
                if (wf.getDescription() != null && !wf.getDescription().isBlank()) {
                    item.put("description", truncate(wf.getDescription(), 50));
                }
                // Count nodes from plan
                int nodeCount = countNodesInPlan(wf.getPlan());
                item.put("nodes", nodeCount);
                workflowList.add(item);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "OK");
            result.put("count", workflows.size());
            result.put("workflows", workflowList);
            result.put("usage", Map.of(
                "load_by_id", "workflow(action='load', id='<uuid>')",
                "load_by_name", "workflow(action='load', name='<workflow name>')"
            ));

            return ToolExecutionResult.success(result);

        } catch (Exception e) {
            log.error("Error listing workflows: {}", e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Error listing workflows: " + e.getMessage());
        }
    }

    /**
     * Load a workflow for editing.
     * Supports: load(id="uuid") or load(name="workflow name")
     * If multiple workflows have the same name, loads the most recent.
     * <p>BATCH-B (2026-05-20) - overload with {@code orgId} added so load-by-name
     * uses the strict-org partial-name finder and the agent only sees workflows
     * in the caller's current workspace. Pre-BATCH-B the name search hit every
     * workflow the user owns across every workspace (cross-org name leak).
     */
    public ToolExecutionResult executeLoad(String tenantId, String orgId, String conversationId, Map<String, Object> parameters) {
        String workflowId = (String) parameters.get("workflow_id");
        if (workflowId == null) workflowId = (String) parameters.get("id");
        String workflowName = (String) parameters.get("name");

        // Must provide either id or name
        if ((workflowId == null || workflowId.isBlank()) && (workflowName == null || workflowName.isBlank())) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "ERROR");
            result.put("message", "Provide either 'id' or 'name' to load a workflow.");
            result.put("usage", Map.of(
                "by_id", "workflow(action='load', id='<uuid>')",
                "by_name", "workflow(action='load', name='<workflow name>')"
            ));
            result.put("tip", "Use workflow(action='list') to see available workflows.");
            return ToolExecutionResult.success(result);
        }

        try {
            WorkflowEntity workflow;

            if (workflowId != null && !workflowId.isBlank()) {
                // Load by ID
                UUID uuid = UUID.fromString(workflowId);
                Optional<WorkflowEntity> opt = workflowRepository.findById(uuid);
                if (opt.isEmpty()) {
                    return ToolExecutionResult.failure(ToolErrorCode.WORKFLOW_NOT_FOUND, "Workflow not found with ID: " + workflowId);
                }
                workflow = opt.get();

                // Verify scope - org-aware. Strict-tenant equality was wrong:
                // a workflow tagged with an org_id can be loaded by any member
                // of that org regardless of which user (tenant) created it.
                // Pre-fix sites: prod log 2026-05-22 08:18:36 - agent tenant=5
                // tried workflow.load id=c0aeae6f (workflow.tenant_id=1, same
                // org 00000000). Tenant-strict rejected; org-strict allows.
                if (!ScopeGuard.isInStrictScope(tenantId, orgId,
                        workflow.getTenantId(), workflow.getOrganizationId())) {
                    return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, "Workflow is not in your current workspace.");
                }

            } else {
                // Load by name - find most recent matching workflow (exclude APPLICATION type)
                // BATCH-B (2026-05-20) - strict-org search when orgId is present.
                List<WorkflowEntity> matches = ((orgId != null && !orgId.isBlank())
                                ? workflowRepository.findByOrganizationIdAndNameContainingIgnoreCaseStrict(orgId, workflowName)
                                : workflowRepository.findByTenantIdAndNameContainingIgnoreCase(tenantId, workflowName))
                        .stream()
                        .filter(w -> w.getWorkflowType() != WorkflowEntity.WorkflowType.APPLICATION)
                        .collect(java.util.stream.Collectors.toList());

                if (matches.isEmpty()) {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("status", "ERROR");
                    result.put("message", "No workflow found with name containing: \"" + workflowName + "\"");
                    result.put("tip", "Use workflow(action='list') to see available workflows.");
                    return ToolExecutionResult.success(result);
                }

                // Sort by createdAt descending and pick the most recent
                matches.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
                workflow = matches.get(0);

                // If multiple matches, just use most recent (already sorted)

            }

            // Reuse existing session if same workflow is re-loaded in same conversation
            if (conversationId != null && !conversationId.isBlank()) {
                Optional<WorkflowBuilderSession> existingOpt =
                    sessionStore.getSessionForConversation(tenantId, conversationId);
                if (existingOpt.isPresent()) {
                    WorkflowBuilderSession existing = existingOpt.get();
                    if (workflow.getId().toString().equals(existing.getLoadedWorkflowId())) {
                        // Same workflow → reuse session (keep undo history, avoid redundant work)
                        log.info("♻️ Reusing existing session {} for workflow {} in conversation {}",
                                existing.getSessionId(), workflow.getId(), conversationId);

                        // Refresh snapshot from DB (may have changed via frontend save)
                        if (workflow.getPlan() != null && !workflow.getPlan().isEmpty()) {
                            try {
                                @SuppressWarnings("unchecked")
                                Map<String, Object> snapshot = objectMapper.convertValue(
                                        workflow.getPlan(), LinkedHashMap.class);
                                existing.setLoadedPlanSnapshot(snapshot);
                            } catch (Exception e) {
                                log.warn("Failed to update plan snapshot on reuse: {}", e.getMessage());
                            }
                        }

                        // Refresh Redis TTL and indices
                        sessionStore.save(existing);

                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("status", "OK");
                        result.put("message", "Workflow \"" + workflow.getName() + "\" is already loaded.");
                        result.put("workflow_id", workflow.getId().toString());
                        result.put("workflow_name", workflow.getName());
                        if (workflow.getDescription() != null) {
                            result.put("description", workflow.getDescription());
                        }
                        result.put("structure", buildVisualStructure(existing));
                        WorkflowBuilderCommonResponses.addCommonElements(result, nodeLibraryService, false);
                        Map<String, Object> executeInfo = agentFireService.buildTriggerExecuteInfo(
                                existing.getTriggers(), workflow.getId().toString());
                        if (executeInfo != null) result.put("execute_info", executeInfo);
                        Map<String, Object> metadata = new LinkedHashMap<>();
                        metadata.put("plan", existing.buildPlanMap());
                        metadata.put("visualization", Map.of("type", "workflow", "id", workflow.getId().toString(), "title", workflow.getName()));
                        return ToolExecutionResult.success(result, metadata);
                    }
                    // Different workflow → discard old session
                    log.info("🧹 Auto-discarded session {} (different workflow) before load", existing.getSessionId());
                    sessionStore.delete(existing.getSessionId());
                }
            } else {
                // Fallback: discard all sessions for tenant (legacy behavior)
                int discarded = sessionStore.discardAllForTenant(tenantId);
                if (discarded > 0) {
                    log.info("🧹 Auto-discarded {} old session(s) for tenant {} before load (no conversationId)", discarded, tenantId);
                }
            }

            // Convert workflow to session with conversation isolation
            WorkflowBuilderSession session = convertWorkflowToSession(workflow, tenantId, conversationId);

            // Store plan snapshot in session memory for reference (NO DB write)
            if (workflow.getPlan() != null && !workflow.getPlan().isEmpty()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> snapshot = objectMapper.convertValue(
                            workflow.getPlan(), LinkedHashMap.class);
                    session.setLoadedPlanSnapshot(snapshot);
                } catch (Exception e) {
                    log.warn("Failed to store plan snapshot for workflow {}: {}", workflow.getId(), e.getMessage());
                }
            }

            sessionStore.save(session);

            // Log session start (loaded from existing workflow)
            buildLogger.logSessionStart(session);

            // Build response with visual summary
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "OK");
            result.put("message", "Workflow \"" + workflow.getName() + "\" loaded for editing.");
            result.put("workflow_id", workflow.getId().toString());
            result.put("workflow_name", workflow.getName());
            if (workflow.getDescription() != null) {
                result.put("description", workflow.getDescription());
            }

            // Add visual structure
            result.put("structure", buildVisualStructure(session));

            // Add common elements (rules, variable_syntax, available_node_types_by_category, actions)
            // isInit=false since workflow already has a trigger
            WorkflowBuilderCommonResponses.addCommonElements(result, nodeLibraryService, false);

            // Trigger execute schemas (so agent knows what data_inputs to provide when firing)
            Map<String, Object> executeInfo = agentFireService.buildTriggerExecuteInfo(
                    session.getTriggers(), workflow.getId().toString());
            if (executeInfo != null) result.put("execute_info", executeInfo);

            // Build metadata with full plan for LLM context
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("plan", session.buildPlanMap());
            metadata.put("visualization", Map.of("type", "workflow", "id", workflow.getId().toString(), "title", workflow.getName()));

            return ToolExecutionResult.success(result, metadata);

        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid workflow ID format: " + workflowId);
        } catch (Exception e) {
            log.error("Error loading workflow: {}", e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Error loading workflow: " + e.getMessage());
        }
    }

    /**
     * Convert a WorkflowEntity to a WorkflowBuilderSession for editing.
     */
    @SuppressWarnings("unchecked")
    private WorkflowBuilderSession convertWorkflowToSession(WorkflowEntity workflow, String tenantId, String conversationId) {
        // Re-open editing carries the loaded workflow's org so saves preserve scope.
        WorkflowBuilderSession session = WorkflowBuilderSession.create(
                tenantId,
                workflow.getOrganizationId(),
                conversationId,
                workflow.getName(),
                workflow.getDescription()
        );
        session.setLoadedWorkflowId(workflow.getId().toString());
        // Pin the application-type flag at load time so the dispatch-level guard in
        // WorkflowBuilderProvider can short-circuit modifying actions without
        // re-querying the DB. Applications are immutable acquired marketplace clones.
        session.setLoadedWorkflowIsApplication(workflow.isApplication());

        Map<String, Object> plan = workflow.getPlan();
        if (plan == null) {
            return session;
        }

        // Load interfaces first (so they're available when linking)
        // Deep-normalize variable references to fix old plans with {{mcp:Label With Spaces.output...}}
        List<Map<String, Object>> interfaces = (List<Map<String, Object>>) plan.get("interfaces");
        if (interfaces != null) {
            for (Map<String, Object> iface : interfaces) {
                session.getInterfaces().add(LabelNormalizer.normalizeVariableReferencesDeep(new LinkedHashMap<>(iface)));
            }
        }

        // Load triggers
        List<Map<String, Object>> triggers = (List<Map<String, Object>>) plan.get("triggers");
        if (triggers != null) {
            for (Map<String, Object> trigger : triggers) {
                Map<String, Object> triggerCopy = LabelNormalizer.normalizeVariableReferencesDeep(new LinkedHashMap<>(trigger));
                session.getTriggers().add(triggerCopy);

                // Assign logical number
                String nodeId = computeNodeId(triggerCopy, "trigger");
                // Rebuild schema (for available_variables in describe)
                rebuildTriggerSchema(session, nodeId, triggerCopy);

                // Sync interfaceIds to linkedInterfaces map
                syncInterfaceIds(session, nodeId, triggerCopy);
            }
        }

        // Load webhook URLs for triggers with standalone webhooks
        resolveStandaloneWebhookUrls(session, tenantId);

        // Load mcps
        List<Map<String, Object>> steps = (List<Map<String, Object>>) plan.get("mcps");
        if (steps != null) {
            for (Map<String, Object> step : steps) {
                Map<String, Object> stepCopy = LabelNormalizer.normalizeVariableReferencesDeep(new LinkedHashMap<>(step));
                session.getMcps().add(stepCopy);

                // Assign logical number
                boolean isAgent = Boolean.TRUE.equals(step.get("isAgent"));
                String prefix = isAgent ? "agent" : "mcp";
                String nodeId = computeNodeId(stepCopy, prefix);
                // Rebuild schema (for available_variables in describe)
                rebuildStepSchema(session, nodeId, stepCopy, isAgent);

                // Sync interfaceIds to linkedInterfaces map
                syncInterfaceIds(session, nodeId, stepCopy);
            }
        }

        // Load agents (stored separately in plan but added to steps in session)
        List<Map<String, Object>> agents = (List<Map<String, Object>>) plan.get("agents");
        if (agents != null) {
            for (Map<String, Object> agent : agents) {
                Map<String, Object> agentCopy = LabelNormalizer.normalizeVariableReferencesDeep(new LinkedHashMap<>(agent));
                // Ensure isAgent flag is set (should already be, but make sure)
                agentCopy.put("isAgent", true);
                session.getMcps().add(agentCopy);

                // Assign logical number
                String nodeId = computeNodeId(agentCopy, "agent");
                // Rebuild schema (for available_variables in describe)
                rebuildStepSchema(session, nodeId, agentCopy, true);

                // Sync interfaceIds to linkedInterfaces map
                syncInterfaceIds(session, nodeId, agentCopy);
            }
        }

        // Load control nodes (loops, decisions, switch, merge, fork, etc.)
        // All core nodes use the "core:" prefix regardless of their specific type
        List<Map<String, Object>> cores = (List<Map<String, Object>>) plan.get("cores");
        if (cores != null) {
            for (Map<String, Object> cn : cores) {
                Map<String, Object> cnCopy = LabelNormalizer.normalizeVariableReferencesDeep(new LinkedHashMap<>(cn));
                session.getCores().add(cnCopy);

                // Assign logical number - all cores use "core:" prefix
                String nodeId = computeNodeId(cnCopy, LabelNormalizer.PREFIX_CORE);
                // Rebuild schema (for available_variables in describe)
                rebuildCoreSchema(session, nodeId, cnCopy);

                // Sync interfaceIds to linkedInterfaces map
                syncInterfaceIds(session, nodeId, cnCopy);
            }
        }

        // Load table nodes (CRUD operations)
        List<Map<String, Object>> tables = (List<Map<String, Object>>) plan.get("tables");
        if (tables != null) {
            for (Map<String, Object> table : tables) {
                Map<String, Object> tableCopy = LabelNormalizer.normalizeVariableReferencesDeep(new LinkedHashMap<>(table));
                session.getTables().add(tableCopy);

                // Assign logical number
                String nodeId = computeNodeId(tableCopy, LabelNormalizer.PREFIX_TABLE);
                // Sync interfaceIds to linkedInterfaces map
                syncInterfaceIds(session, nodeId, tableCopy);
            }
        }

        // Load edges (expand loop edges into simple edges for visualization)
        List<Map<String, Object>> edges = (List<Map<String, Object>>) plan.get("edges");
        if (edges != null) {
            for (Map<String, Object> edge : edges) {
                expandAndAddEdge(session, edge);
            }
        }

        return session;
    }

    /**
     * V2: Add edge to session. Edges are simple { from, to } format with ports.
     */
    private void expandAndAddEdge(WorkflowBuilderSession session, Map<String, Object> edge) {
        // V2: All edges are simple { from, to } format - add directly
        session.getEdges().add(new LinkedHashMap<>(edge));
    }

    /**
     * Build a visual structure summary for display.
     */
    private List<String> buildVisualStructure(WorkflowBuilderSession session) {
        List<String> lines = new ArrayList<>();

        // Build node list with logical IDs
        List<WorkflowBuilderSession.LogicalNode> nodes = session.getLogicalNodeList();
        if (nodes.isEmpty()) {
            lines.add("(empty workflow - no nodes yet)");
            lines.add("");
            lines.add("📖 START BY ADDING A TRIGGER:");
            lines.add("  • Schedule: workflow(action='add_node', type='schedule', label='Daily', params={schedule: '0 9 * * *'})");
            lines.add("  • Table: workflow(action='add_node', type='table', label='Each Row', params={table_id: 123})");
            lines.add("  • Webhook: workflow(action='add_node', type='webhook', label='API', params={path: '/orders'})");
            lines.add("  • Manual: workflow(action='add_node', type='manual', label='Run Now')");
            lines.add("  • Chat: workflow(action='add_node', type='chat', label='Help', params={chatMatch: {type: 'startsWith', pattern: '/help'}})");
            lines.add("  • Workflow: workflow(action='add_node', type='workflow', label='After Import', params={workflow_id: '<parent-uuid>'})");
            return lines;
        }

        for (WorkflowBuilderSession.LogicalNode node : nodes) {
            StringBuilder sb = new StringBuilder();
            sb.append(node.getLogicalId());
            sb.append(" [").append(node.getType()).append("] ");
            sb.append("\"").append(node.getLabel()).append("\"");

            // Add connection info
            List<Map<String, Object>> outgoing = session.getOutgoingConnections(node.getNodeId());
            if (!outgoing.isEmpty()) {
                List<String> targets = new ArrayList<>();
                for (Map<String, Object> edge : outgoing) {
                    String to = (String) edge.get("to");
                    if (to == null) to = (String) edge.get("target");
                    String logicalTo = session.getLogicalId(to);
                    targets.add(logicalTo != null ? logicalTo : to);
                }
                sb.append(" → ").append(String.join(", ", targets));
            }

            lines.add(sb.toString());
        }

        return lines;
    }

    /**
     * Save the current session as a workflow.
     */
    public ToolExecutionResult executeSave(WorkflowBuilderSession session) {
        try {
            // Block saving to a read-only workflow
            if (session.getLoadedWorkflowId() != null) {
                UUID loadedId = UUID.fromString(session.getLoadedWorkflowId());
                Optional<WorkflowEntity> loadedOpt = workflowRepository.findById(loadedId);

                // Defense-in-depth APPLICATION immutability guard.
                //
                // The primary gate lives in WorkflowBuilderProvider.execute(), where
                // modifying actions are rejected up front using the session-cached
                // `loadedWorkflowIsApplication` flag. This second check fires against
                // the LIVE entity at save time to catch:
                //   • a session restored from disk with a stale `false` flag while the
                //     workflow type was flipped to APPLICATION between load and save;
                //   • any future code path that builds + saves a session without going
                //     through the dispatch guard;
                //   • a missed action in MODIFYING_ACTIONS that mutates without being
                //     tagged as modifying.
                // Reset-plan is the sanctioned restore route; it owns its own service
                // path and does not go through this method, so this guard cannot fire
                // on a legitimate basePlan restore.
                if (loadedOpt.isPresent() && loadedOpt.get().isApplication()) {
                    log.warn("Refused executeSave on APPLICATION workflow {}: applications are immutable acquired clones",
                            loadedId);
                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("code", "APPLICATION_PLAN_IMMUTABLE");
                    meta.put("workflow_id", loadedId.toString());
                    return ToolExecutionResult.failure(
                            ToolErrorCode.RESOURCE_CONFLICT,
                            "Cannot save changes onto an APPLICATION workflow: it is a frozen acquired "
                                    + "marketplace clone. Use workflow(action='discard') to drop this session.",
                            meta);
                }
            }

            // 1. VALIDATE BEFORE SAVE - same unified validator as workflow(action='validate')
            //    and as the finish-on-new-workflow guard. Block the save when errors
            //    exist (previous behavior was to log-and-save-anyway, which landed
            //    broken workflows in prod - e.g. schedule triggers firing every 10
            //    minutes against a plan with missing required params).
            var validationResult = validator.validate(session);
            Map<String, Object> agentFormat = validator.toAgentFormat(validationResult);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> validationErrors = (List<Map<String, Object>>) agentFormat.get("errors");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> validationWarnings = (List<Map<String, Object>>) agentFormat.get("warnings");

            if (!validationErrors.isEmpty()) {
                if (!allowSaveWithoutValidation) {
                    log.warn("⚠️ [SAVE] Workflow has {} validation errors - blocking save", validationErrors.size());
                    Map<String, Object> failMeta = new LinkedHashMap<>();
                    failMeta.put("workflow_name", session.getWorkflowName());
                    failMeta.put("error_count", validationErrors.size());
                    failMeta.put("errors", validationErrors);
                    if (!validationWarnings.isEmpty()) {
                        failMeta.put("warnings", validationWarnings);
                    }
                    failMeta.put("next_action",
                        "Fix the errors with workflow(action='modify', ...) or workflow(action='remove', ...), " +
                        "then call workflow(action='validate') to confirm, then workflow(action='finish') again.");
                    return ToolExecutionResult.failure(
                        ToolErrorCode.WORKFLOW_INVALID,
                        "Cannot save workflow: " + validationErrors.size() + " validation error(s). " +
                        "Run workflow(action='validate') for details.",
                        failMeta);
                }
                log.warn("⚠️ [SAVE] Workflow has {} validation errors - bypass flag enabled, saving anyway",
                        validationErrors.size());
            }
            if (!validationWarnings.isEmpty()) {
                log.info("📋 [SAVE] Workflow has {} validation warnings", validationWarnings.size());
            }

            Map<String, Object> planMap = session.buildPlanMap();
            planMap.put("tenant_id", session.getTenantId());

            // Debug: log cores count at save time
            @SuppressWarnings("unchecked")
            List<?> savedCores = (List<?>) planMap.get("cores");
            log.info("📦 [SAVE] Plan has {} cores, {} mcps, {} edges, {} triggers",
                    savedCores != null ? savedCores.size() : 0,
                    planMap.get("mcps") instanceof List<?> mcps ? mcps.size() : 0,
                    planMap.get("edges") instanceof List<?> edges ? edges.size() : 0,
                    planMap.get("triggers") instanceof List<?> triggers ? triggers.size() : 0);
            if (savedCores != null && !savedCores.isEmpty()) {
                log.info("📦 [SAVE] Cores: {}", savedCores);
            }

            // Convert to WorkflowPlan and save
            var plan = com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan.fromMap(planMap);

            // Use existing workflow ID if editing, else generate new
            UUID workflowId;
            if (session.getLoadedWorkflowId() != null) {
                workflowId = UUID.fromString(session.getLoadedWorkflowId());
            } else {
                workflowId = UUID.randomUUID();
            }

            // Pass session.getOrgId() so the workflow row carries the active
            // workspace tag - without it, org-teammates can't see workflows
            // built by an LLM agent through the MCP `workflow(action='finish')`
            // flow. Audit 2026-05-16.
            var saveResult = workflowService.saveWorkflow(plan, Map.of(), workflowId, session.getOrgId());

            String savedId = saveResult.getWorkflow().getId().toString();

            // Create version with "Agent session" label
            try {
                UUID savedUuid = UUID.fromString(savedId);
                versionService.createVersion(savedUuid, planMap, session.getTenantId(), "Agent session");
            } catch (Exception e) {
                log.warn("Failed to create version for workflow {}: {}", savedId, e.getMessage());
            }

            // Keep session alive after save - user may continue editing
            session.setLoadedWorkflowId(savedId);
            sessionStore.save(session);

            Map<String, Object> result = new LinkedHashMap<>();

            // Check for missing credentials and adjust status/message accordingly
            if (session.hasMissingCredentials()) {
                var missingCreds = session.getMissingCredentials();
                var services = session.getMissingCredentialServices();

                // Combine status: credentials + validation errors
                if (!validationErrors.isEmpty()) {
                    result.put("status", "SAVED_WITH_ERRORS");
                    result.put("message", "⚠️ Workflow saved - credentials needed + " + validationErrors.size() + " validation error(s)");
                } else {
                    result.put("status", "SAVED_PENDING_CREDENTIALS");
                    result.put("message", "⚠️ Workflow saved - credentials needed before execution");
                }
                result.put("workflow_id", savedId);
                result.put("workflow_name", session.getWorkflowName());

                // Build credential recap with affected steps
                Map<String, Object> credRecap = new LinkedHashMap<>();
                credRecap.put("missing_services", services);
                credRecap.put("affected_steps", missingCreds.entrySet().stream()
                    .map(e -> Map.of(
                        "node_id", e.getKey(),
                        "logical_id", session.getLogicalId(e.getKey()),
                        "service", e.getValue().get("serviceName")
                    ))
                    .toList());
                credRecap.put("connect_now", "request_credential(services=" + services + ", reason='Workflow execution')");
                credRecap.put("note", "Workflow is saved but will FAIL at execution without these credentials");
                result.put("⚠️_CREDENTIALS_MISSING", credRecap);

                result.put("marker", "[visualize:workflow:" + savedId + "]");
                result.put("▶️ TO_EXECUTE", "⚠️ Connect credentials first, then: workflow(action='execute')");
            } else {
                // Adjust status based on validation errors
                if (!validationErrors.isEmpty()) {
                    result.put("status", "SAVED_WITH_ERRORS");
                    result.put("message", "⚠️ Workflow saved but has " + validationErrors.size() + " validation error(s)");
                } else {
                    result.put("status", "OK");
                    result.put("message", "✅ Workflow saved!");
                }
                result.put("workflow_id", savedId);
                result.put("workflow_name", session.getWorkflowName());
                result.put("marker", "[visualize:workflow:" + savedId + "]");
                result.put("▶️ TO_EXECUTE", "workflow(action='execute')");
            }

            // 2. ADD VALIDATION RESULTS TO RESPONSE
            if (!validationErrors.isEmpty()) {
                result.put("❌_VALIDATION_ERRORS", validationErrors);
                result.put("⚠️_FIX_REQUIRED", "These errors may cause execution to fail. Use workflow(action='validate') for details.");
            }
            if (!validationWarnings.isEmpty()) {
                result.put("⚠️_VALIDATION_WARNINGS", validationWarnings);
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("visualization", Map.of("type", "workflow", "id", savedId, "title", session.getWorkflowName()));
            return ToolExecutionResult.success(result, metadata);

        } catch (Exception e) {
            log.error("Error saving workflow: {}", e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Error saving workflow: " + e.getMessage());
        }
    }

    /**
     * Discard changes and close the session.
     */
    public ToolExecutionResult executeDiscard(WorkflowBuilderSession session) {
        String sessionId = session.getSessionId();
        String workflowName = session.getWorkflowName();
        boolean wasLoaded = session.getLoadedWorkflowId() != null;

        // Log session end before deletion
        buildLogger.logSessionEnd(session, wasLoaded ? "DISCARDED (loaded)" : "DISCARDED (draft)");

        sessionStore.delete(sessionId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "OK");

        if (wasLoaded) {
            result.put("message", "Changes discarded. Original workflow \"" + workflowName + "\" unchanged.");
        } else {
            result.put("message", "Draft \"" + workflowName + "\" discarded.");
        }

        result.put("discarded_session", sessionId);

        return ToolExecutionResult.success(result);
    }

    /**
     * Get execution history for a workflow.
     */
    public ToolExecutionResult executeGetHistory(String tenantId, Map<String, Object> parameters) {
        String workflowId = (String) parameters.get("workflow_id");
        if (workflowId == null) workflowId = (String) parameters.get("id");

        if (workflowId == null || workflowId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "workflow_id is required for get_history.");
        }

        try {
            UUID uuid = UUID.fromString(workflowId);
            var runs = workflowService.getRecentInstances(uuid, 10);

            if (runs.isEmpty()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("status", "OK");
                result.put("workflow_id", workflowId);
                result.put("message", "No execution history found for this workflow.");
                return ToolExecutionResult.success(result);
            }

            List<Map<String, Object>> history = new ArrayList<>();
            for (int i = 0; i < runs.size(); i++) {
                var run = runs.get(i);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("run_id", run.getRunIdPublic());
                item.put("status", run.getStatus().name());
                item.put("started", DATE_FORMAT.format(run.getStartedAt()));
                if (run.getEndedAt() != null) {
                    item.put("completed", DATE_FORMAT.format(run.getEndedAt()));
                }
                history.add(item);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "OK");
            result.put("workflow_id", workflowId);
            result.put("showing_latest", runs.size());
            result.put("executions", history);

            return ToolExecutionResult.success(result);

        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid workflow ID format.");
        } catch (Exception e) {
            log.error("Error getting execution history: {}", e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Error getting execution history: " + e.getMessage());
        }
    }

    /**
     * Get execution details - placeholder for future implementation.
     */
    public ToolExecutionResult executeGetExecution(String tenantId, Map<String, Object> parameters) {
        String executionId = (String) parameters.get("execution_id");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "INFO");
        result.put("message", "Execution details viewing is not yet available via this tool.");
        if (executionId != null) {
            result.put("execution_id", executionId);
        }
        return ToolExecutionResult.success(result);
    }

    // ==================== Helper Methods ====================

    /**
     * Resolve standalone webhook URLs for triggers that have a webhookId in their params.
     * Fetches the webhook from DB and stores the URL on the trigger node.
     */
    @SuppressWarnings("unchecked")
    private void resolveStandaloneWebhookUrls(WorkflowBuilderSession session, String tenantId) {
        for (Map<String, Object> trigger : session.getTriggers()) {
            if (!"webhook".equals(trigger.get("type"))) continue;

            Map<String, Object> params = (Map<String, Object>) trigger.get("params");
            if (params == null) continue;

            Object webhookIdObj = params.get("webhookId");
            if (webhookIdObj == null) continue;

            String webhookId = webhookIdObj.toString();
            if (webhookId.isBlank()) continue;

            // Already resolved?
            if (trigger.get("standaloneWebhookUrl") != null) continue;

            try {
                UUID webhookUuid = UUID.fromString(webhookId);
                StandaloneWebhookDto webhook = null;
                // Find the standalone webhook via trigger-service
                List<StandaloneWebhookDto> workflowWebhooks = session.getLoadedWorkflowId() != null
                        ? triggerClient.findStandaloneByWorkflowId(UUID.fromString(session.getLoadedWorkflowId()))
                        : Collections.emptyList();
                for (StandaloneWebhookDto w : workflowWebhooks) {
                    if (webhookUuid.equals(w.getId())) {
                        webhook = w;
                        break;
                    }
                }
                if (webhook == null) {
                    log.warn("Could not find standalone webhook {} for trigger", webhookId);
                    continue;
                }
                String webhookUrl = "/webhook/" + webhook.getToken();
                trigger.put("standaloneWebhookId", webhook.getId().toString());
                trigger.put("standaloneWebhookUrl", webhookUrl);
                trigger.put("standaloneWebhookToken", webhook.getToken());

                // Also populate session token map for WorkflowBuilderViewer
                String nodeId = computeNodeId(trigger, "trigger");
                session.getWebhookTokens().put(nodeId, webhook.getToken());
            } catch (Exception e) {
                log.warn("Failed to resolve standalone webhook {} for trigger: {}", webhookId, e.getMessage());
            }
        }
    }

    private String computeNodeId(Map<String, Object> node, String prefix) {
        String label = (String) node.get("label");
        if (label == null) {
            label = (String) node.get("id");
        }
        if (label == null) {
            label = "unknown";
        }
        return prefix + ":" + normalizeLabel(label);
    }

    private String normalizeLabel(String label) {
        // Use centralized LabelNormalizer for consistency with WorkflowBuilderSession
        String normalized = LabelNormalizer.normalizeLabel(label);
        return normalized != null ? normalized : "";
    }

    @SuppressWarnings("unchecked")
    private int countNodesInPlan(Map<String, Object> plan) {
        if (plan == null) return 0;
        int count = 0;
        List<?> triggers = (List<?>) plan.get("triggers");
        if (triggers != null) count += triggers.size();
        List<?> mcps = (List<?>) plan.get("mcps");
        if (mcps != null) count += mcps.size();
        List<?> cores = (List<?>) plan.get("cores");
        if (cores != null) count += cores.size();
        return count;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "";
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }

    /**
     * Sync interfaceIds from a loaded node to the session's linkedInterfaces map.
     * This ensures consistency between node.interfaceIds and session.linkedInterfaces.
     */
    @SuppressWarnings("unchecked")
    private void syncInterfaceIds(WorkflowBuilderSession session, String nodeId, Map<String, Object> node) {
        Object interfaceIdsObj = node.get("interfaceIds");
        if (interfaceIdsObj instanceof List<?> interfaceIds) {
            for (Object id : interfaceIds) {
                if (id instanceof String interfaceId) {
                    session.linkInterface(nodeId, interfaceId);
                }
            }
        }
    }

    /**
     * Rebuild core node schema (decision, loop, split, switch, fork, merge, transform, wait, option).
     * Called during load to populate nodeSchemas for available_variables.
     */
    private void rebuildCoreSchema(WorkflowBuilderSession session, String nodeId, Map<String, Object> core) {
        String label = (String) core.get("label");
        String type = (String) core.get("type");
        if (type == null) return;

        String normalizedLabel = normalizeLabel(label);

        Map<String, String> outputs = new LinkedHashMap<>();
        Map<String, String> refs = new LinkedHashMap<>();

        switch (type) {
            case "decision", "option" -> {
                outputs.put("selected_branch", "string");
                refs.put("selected_branch", "{{core:" + normalizedLabel + ".output.selected_branch}}");
            }
            case "loop", "while" -> {
                outputs.put("iteration", "number");
                refs.put("iteration", "{{core:" + normalizedLabel + ".output.iteration}}");
            }
            case "split", "for_each" -> {
                // Layer 2: Runtime context (body nodes only, NOT persisted)
                outputs.put("current_item", "object (RUNTIME - per-branch item)");
                outputs.put("current_index", "number (RUNTIME - 0-based index)");
                refs.put("current_item", "{{core:" + normalizedLabel + ".output.current_item}}");
                refs.put("current_index", "{{core:" + normalizedLabel + ".output.current_index}}");
                // Layer 1: Persisted outputs (accessible everywhere after split)
                outputs.put("items", "array (PERSISTED - full list)");
                outputs.put("item_count", "number (PERSISTED - total count)");
                outputs.put("split_id", "string (PERSISTED - split identifier)");
                outputs.put("spawn_reason", "string (PERSISTED - items_spawned or empty_list)");
                outputs.put("terminated", "boolean (PERSISTED - completion status)");
                refs.put("items", "{{core:" + normalizedLabel + ".output.items}}");
                refs.put("item_count", "{{core:" + normalizedLabel + ".output.item_count}}");
                refs.put("split_id", "{{core:" + normalizedLabel + ".output.split_id}}");
                refs.put("spawn_reason", "{{core:" + normalizedLabel + ".output.spawn_reason}}");
                refs.put("terminated", "{{core:" + normalizedLabel + ".output.terminated}}");
            }
            case "switch" -> {
                outputs.put("selected_case", "string");
                refs.put("selected_case", "{{core:" + normalizedLabel + ".output.selected_case}}");
            }
            case "transform" -> {
                outputs.put("result", "object");
                refs.put("result", "{{core:" + normalizedLabel + ".output.result}}");
            }
            case "wait" -> {
                outputs.put("completed", "boolean");
                refs.put("completed", "{{core:" + normalizedLabel + ".output.completed}}");
            }
            case "http_request" -> {
                outputs.put("success", "boolean");
                outputs.put("status", "number");
                outputs.put("statusText", "string");
                outputs.put("data", "any");
                outputs.put("headers", "object");
                outputs.put("error", "string");
                refs.put("success", "{{core:" + normalizedLabel + ".output.success}}");
                refs.put("status", "{{core:" + normalizedLabel + ".output.status}}");
                refs.put("statusText", "{{core:" + normalizedLabel + ".output.statusText}}");
                refs.put("data", "{{core:" + normalizedLabel + ".output.data}}");
                refs.put("headers", "{{core:" + normalizedLabel + ".output.headers}}");
                refs.put("error", "{{core:" + normalizedLabel + ".output.error}}");
            }
            // fork, merge: no outputs (control flow only)
        }

        if (!outputs.isEmpty()) {
            session.getNodeSchemas().put(nodeId, WorkflowBuilderSession.NodeSchema.builder()
                .nodeId(nodeId)
                .nodeType("core")
                .label(label)
                .outputs(outputs)
                .referenceSyntax(refs)
                .build());
        }
    }

    /**
     * Rebuild MCP/Agent schema from tool catalog.
     * Called during load to populate nodeSchemas for available_variables.
     */
    private void rebuildStepSchema(WorkflowBuilderSession session, String nodeId, Map<String, Object> step, boolean isAgent) {
        String label = (String) step.get("label");
        String toolId = (String) step.get("id");
        if (toolId == null) toolId = (String) step.get("tool_id");

        // For agents, use generic response output
        if (isAgent) {
            String normalizedLabel = normalizeLabel(label);
            Map<String, String> outputs = Map.of("response", "object");
            Map<String, String> referenceSyntax = Map.of(
                "response", "{{agent:" + normalizedLabel + ".output.response}}"
            );
            session.getNodeSchemas().put(nodeId, WorkflowBuilderSession.NodeSchema.builder()
                .nodeId(nodeId)
                .nodeType("agent")
                .label(label)
                .outputs(outputs)
                .referenceSyntax(referenceSyntax)
                .build());
            return;
        }

        // For MCP steps, fetch schema from catalog
        if (toolId != null && !toolId.startsWith("crud/")) {
            try {
                Optional<ToolSchemaFetcher.ToolSchemaResult> schemaOpt = toolSchemaFetcher.fetchToolSchema(toolId);
                if (schemaOpt.isPresent()) {
                    var schema = schemaOpt.get();
                    Map<String, String> outputs = toolSchemaFetcher.pathsToOutputSchema(schema.getPaths());
                    Map<String, String> refs = toolSchemaFetcher.generateReferenceSyntax(nodeId, schema.getPaths());

                    session.getNodeSchemas().put(nodeId, WorkflowBuilderSession.NodeSchema.builder()
                        .nodeId(nodeId)
                        .nodeType("mcp")
                        .label(label)
                        .toolId(toolId)
                        .outputs(outputs)
                        .referenceSyntax(refs)
                        .build());
                }
            } catch (Exception e) {
                log.warn("Could not rebuild schema for step {}: {}", nodeId, e.getMessage());
            }
        }
    }

    /**
     * Rebuild trigger schema from datasource columns.
     * Called during load to populate nodeSchemas for available_variables.
     */
    @SuppressWarnings("unchecked")
    private void rebuildTriggerSchema(WorkflowBuilderSession session, String nodeId, Map<String, Object> trigger) {
        Map<String, String> outputs = new LinkedHashMap<>();
        Map<String, String> referenceSyntax = new LinkedHashMap<>();

        String type = (String) trigger.get("type");
        Object dsIdObj = trigger.get("datasource_id");
        if (dsIdObj == null) dsIdObj = trigger.get("dataSourceId");

        if ("datasource".equals(type) && dsIdObj != null) {
            try {
                Long dsId = dsIdObj instanceof Number
                    ? ((Number) dsIdObj).longValue()
                    : Long.parseLong(dsIdObj.toString());

                DataSourceDto ds = dataSourceClient.getDataSource(dsId, session.getTenantId());
                if (ds != null) {
                    outputs.put("id", "string");
                    referenceSyntax.put("id", "{{" + nodeId + ".output.id}}");

                    Map<String, ColumnMappingSpecDto> mappingSpec = ds.mappingSpec();
                    if (mappingSpec != null) {
                        for (Map.Entry<String, ColumnMappingSpecDto> entry : mappingSpec.entrySet()) {
                            String field = entry.getKey();
                            String fieldType = entry.getValue().type() != null
                                ? entry.getValue().type().name().toLowerCase() : "text";
                            outputs.put(field, fieldType);
                            referenceSyntax.put(field, "{{" + nodeId + ".output." + field + "}}");
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Could not rebuild schema for trigger {}: {}", nodeId, e.getMessage());
            }
        } else if ("form".equals(type)) {
            Map<String, Object> params = (Map<String, Object>) trigger.get("params");
            if (params != null) {
                List<Map<String, Object>> fields = (List<Map<String, Object>>) params.get("fields");
                if (fields != null) {
                    for (Map<String, Object> field : fields) {
                        String fieldName = field.get("name") instanceof String s ? s : null;
                        if (fieldName != null && !fieldName.isBlank()) {
                            String fieldType = field.get("type") instanceof String s ? s : "string";
                            outputs.put(fieldName, fieldType);
                            referenceSyntax.put(fieldName, "{{" + nodeId + ".output." + fieldName + "}}");
                        }
                    }
                }
            }
            outputs.put("submittedAt", "string");
            referenceSyntax.put("submittedAt", "{{" + nodeId + ".output.submittedAt}}");
        } else if ("webhook".equals(type)) {
            for (String f : List.of("payload", "headers", "query", "method", "triggered_at", "triggered_by")) {
                outputs.put(f, "string");
                referenceSyntax.put(f, "{{" + nodeId + ".output." + f + "}}");
            }
        } else if ("chat".equals(type)) {
            for (String f : List.of("message", "extracted_message", "conversation_id", "attachments",
                    "matched", "match_type", "match_value", "triggered_at", "triggered_by",
                    "trigger_id", "item_id", "item_index", "data", "count")) {
                outputs.put(f, "string");
                referenceSyntax.put(f, "{{" + nodeId + ".output." + f + "}}");
            }
        }

        if (!outputs.isEmpty()) {
            String label = (String) trigger.get("label");
            session.getNodeSchemas().put(nodeId, WorkflowBuilderSession.NodeSchema.builder()
                .nodeId(nodeId)
                .nodeType("trigger")
                .label(label)
                .outputs(outputs)
                .referenceSyntax(referenceSyntax)
                .build());
        }
    }
}
