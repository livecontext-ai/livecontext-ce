package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentDto;
import com.apimarketplace.agent.client.dto.AgentSkillDto;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.datasource.client.dto.DataSourceItemDto;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.service.resource.ResourcePublicationStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
public class PublicationModerationService {

    private static final Logger log = LoggerFactory.getLogger(PublicationModerationService.class);

    private final WorkflowPublicationRepository publicationRepository;
    private final OrchestratorInternalClient orchestratorClient;
    private final AgentClient agentClient;
    private final InterfaceClient interfaceClient;
    private final DataSourceClient dataSourceClient;
    private final WorkflowPublicationService workflowPublicationService;
    private final LandingInterfaceSnapshotter landingInterfaceSnapshotter;
    private final Map<PublicationType, ResourcePublicationStrategy> resourceStrategies;

    public PublicationModerationService(WorkflowPublicationRepository publicationRepository,
                                        OrchestratorInternalClient orchestratorClient,
                                        AgentClient agentClient,
                                        InterfaceClient interfaceClient,
                                        DataSourceClient dataSourceClient,
                                        WorkflowPublicationService workflowPublicationService,
                                        LandingInterfaceSnapshotter landingInterfaceSnapshotter,
                                        List<ResourcePublicationStrategy> strategies) {
        this.publicationRepository = publicationRepository;
        this.orchestratorClient = orchestratorClient;
        this.agentClient = agentClient;
        this.interfaceClient = interfaceClient;
        this.dataSourceClient = dataSourceClient;
        this.workflowPublicationService = workflowPublicationService;
        this.landingInterfaceSnapshotter = landingInterfaceSnapshotter;
        Map<PublicationType, ResourcePublicationStrategy> map = new EnumMap<>(PublicationType.class);
        for (ResourcePublicationStrategy s : strategies) {
            map.put(s.getPublicationType(), s);
        }
        this.resourceStrategies = map;
    }

    @Transactional(readOnly = true)
    public Page<WorkflowPublicationEntity> getPendingPublications(Pageable pageable) {
        return publicationRepository.findPendingReviewPublications(pageable);
    }

    @Transactional(readOnly = true)
    public WorkflowPublicationEntity getPublicationForReview(UUID publicationId) {
        return publicationRepository.findById(publicationId)
                .orElseThrow(() -> new IllegalArgumentException("Publication not found: " + publicationId));
    }

    /**
     * Build a comparison payload containing the snapshot data and the current live source data.
     * This allows the admin to see exactly what was snapshotted vs what currently exists.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getComparisonData(UUID publicationId) {
        WorkflowPublicationEntity publication = publicationRepository.findById(publicationId)
                .orElseThrow(() -> new IllegalArgumentException("Publication not found: " + publicationId));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("publicationId", publication.getId());
        result.put("publicationType", publication.getPublicationType() != null ? publication.getPublicationType().name() : "WORKFLOW");
        result.put("title", publication.getTitle());
        result.put("description", publication.getDescription());
        result.put("publisherId", publication.getPublisherId());
        result.put("publisherName", publication.getPublisherName());
        result.put("visibility", publication.getVisibility() != null ? publication.getVisibility().name() : null);
        result.put("creditsPerUse", publication.getCreditsPerUse());
        result.put("status", publication.getStatus().name());

        boolean isAgent = publication.getPublicationType() == PublicationType.AGENT;

        // Snapshot data (what was captured at publish time)
        Map<String, Object> snapshot;
        if (isAgent) {
            snapshot = publication.getAgentSnapshot() != null ? new LinkedHashMap<>(publication.getAgentSnapshot()) : new LinkedHashMap<>();
        } else {
            snapshot = publication.getPlanSnapshot() != null ? new LinkedHashMap<>(publication.getPlanSnapshot()) : new LinkedHashMap<>();
        }

        // Current live source data (what exists now in the original resource)
        Map<String, Object> currentSource = fetchCurrentSource(publication, isAgent);

        // Normalize DataInput file paths to filename-only so storage-path differences don't create false diffs
        if (isAgent) {
            // For agent snapshots, normalize inside each workflow's plan
            normalizeDataInputPathsInWorkflows(snapshot);
            normalizeDataInputPathsInWorkflows(currentSource);
        } else if (publication.getPublicationType() == PublicationType.WORKFLOW) {
            normalizeDataInputPaths(snapshot);
            Object sourcePlan = currentSource.get("plan");
            if (sourcePlan instanceof Map) {
                normalizeDataInputPaths((Map<String, Object>) sourcePlan);
            }
        }
        // TABLE/INTERFACE/SKILL: no DataInput nodes to normalize

        result.put("snapshot", snapshot);
        result.put("currentSource", currentSource);

        // Completeness manifest: reconcile the plan's DECLARED resource references against what the
        // SNAPSHOT actually captured - computed from the snapshot ALONE, never via re-enrichment
        // (which regenerates the live side with the same pipeline that produced the snapshot, so a
        // dropped resource is missing from BOTH sides and reads as "Identical"). This manifest is
        // the only signal that surfaces e.g. a diamond / shared-child sub-workflow dropped at
        // publish, or an agent/datasource/interface that failed to snapshot.
        if (publication.getPublicationType() == PublicationType.WORKFLOW) {
            result.put("completenessManifest", buildPlanCompletenessManifest(snapshot));
        } else if (isAgent) {
            result.put("completenessManifest", buildAgentCompletenessManifest(snapshot));
        }

        return result;
    }

    /**
     * Reconcile a WORKFLOW plan snapshot's DECLARED resource references against the {@code _snapshot_*}
     * data actually captured, recursing into {@code _snapshot_subworkflows}. Returns
     * {@code {complete, captured:{...counts}, missing:[{type, ref, at, reason}]}}. Pure function of
     * the snapshot - it does not call any live source, so it cannot be fooled by symmetric enrichment.
     */
    Map<String, Object> buildPlanCompletenessManifest(Map<String, Object> plan) {
        List<Map<String, Object>> missing = new ArrayList<>();
        int[] captured = new int[4]; // [agents, datasources, interfaces, subWorkflows]
        reconcilePlanCompleteness(plan, "", missing, captured, new HashSet<>());
        Map<String, Object> capturedCounts = new LinkedHashMap<>();
        capturedCounts.put("agents", captured[0]);
        capturedCounts.put("datasources", captured[1]);
        capturedCounts.put("interfaces", captured[2]);
        capturedCounts.put("subWorkflows", captured[3]);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("complete", missing.isEmpty());
        manifest.put("captured", capturedCounts);
        manifest.put("missing", missing);
        return manifest;
    }

    /** Agent snapshot: reconcile each embedded workflow's plan (the agent → workflow → sub-workflow chain). */
    @SuppressWarnings("unchecked")
    Map<String, Object> buildAgentCompletenessManifest(Map<String, Object> agentSnapshot) {
        List<Map<String, Object>> missing = new ArrayList<>();
        int[] captured = new int[4];
        Set<String> visited = new HashSet<>();
        Object workflows = agentSnapshot == null ? null : agentSnapshot.get("workflows");
        if (workflows instanceof List<?> wfList) {
            for (Object wf : wfList) {
                if (!(wf instanceof Map<?, ?> wfMap)) continue;
                Object subPlan = ((Map<String, Object>) wfMap).get("plan");
                if (subPlan instanceof Map) {
                    reconcilePlanCompleteness((Map<String, Object>) subPlan, "agent-workflow/", missing, captured, visited);
                }
            }
        }
        Map<String, Object> capturedCounts = new LinkedHashMap<>();
        capturedCounts.put("agents", captured[0]);
        capturedCounts.put("datasources", captured[1]);
        capturedCounts.put("interfaces", captured[2]);
        capturedCounts.put("subWorkflows", captured[3]);
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("complete", missing.isEmpty());
        manifest.put("captured", capturedCounts);
        manifest.put("missing", missing);
        return manifest;
    }

    @SuppressWarnings("unchecked")
    private void reconcilePlanCompleteness(Map<String, Object> plan, String path,
                                           List<Map<String, Object>> missing, int[] captured,
                                           Set<String> visited) {
        if (plan == null) return;

        Object agents = plan.get("agents");
        if (agents instanceof List<?> agentList) {
            for (Object a : agentList) {
                if (!(a instanceof Map<?, ?> node)) continue;
                if (node.get("agentConfigId") == null) continue; // not an agent-backed node
                if (node.get("_snapshot_agent_name") != null) captured[0]++;
                else missing.add(missingEntry("agent", node.get("agentConfigId"), path,
                        "agent node references an agent that was not captured in the snapshot"));
            }
        }

        Object tables = plan.get("tables");
        if (tables instanceof List<?> tableList) {
            for (Object t : tableList) {
                if (!(t instanceof Map<?, ?> node)) continue;
                if (node.get("dataSourceId") == null) continue;
                if (node.get("_snapshot_ds_name") != null || node.get("_snapshot_ds_sourceType") != null) captured[1]++;
                else missing.add(missingEntry("datasource", node.get("dataSourceId"), path,
                        "table node references a datasource that was not captured in the snapshot"));
            }
        }

        Object interfaces = plan.get("interfaces");
        if (interfaces instanceof List<?> ifaceList) {
            for (Object i : ifaceList) {
                if (!(i instanceof Map<?, ?> node)) continue;
                if (node.get("id") == null) continue;
                if (node.get("_snapshot_htmlTemplate") != null || node.get("_snapshot_name") != null) captured[2]++;
                else missing.add(missingEntry("interface", node.get("id"), path,
                        "interface node references an interface that was not captured in the snapshot"));
            }
        }

        Object subWfsRaw = plan.get("_snapshot_subworkflows");
        Map<String, Object> subWorkflows = subWfsRaw instanceof Map ? (Map<String, Object>) subWfsRaw : Map.of();
        // Collect referenced sub-workflow ids from ALL THREE shapes the publish-side recursion uses
        // (core sub_workflow nodes + agent toolsConfig.workflows + workflow/error trigger ids) - else
        // a shared child dropped via an agent or trigger reference would never surface as missing and
        // the review gate would still mask that loss class.
        Set<String> referenced = new LinkedHashSet<>();
        collectCoreSubWorkflowRefs(plan, referenced);
        collectAgentWorkflowRefs(plan, referenced);
        collectTriggerWorkflowRefs(plan, referenced);
        for (String wfId : referenced) {
            Object snap = subWorkflows.get(wfId);
            if (snap instanceof Map) {
                captured[3]++;
                if (visited.add(wfId)) { // guard cycles
                    Object subPlan = ((Map<String, Object>) snap).get("plan");
                    if (subPlan instanceof Map) {
                        reconcilePlanCompleteness((Map<String, Object>) subPlan, path + wfId + "/", missing, captured, visited);
                    }
                }
            } else {
                missing.add(missingEntry("subWorkflow", wfId, path,
                        "a sub-workflow reference (core sub_workflow / agent toolsConfig.workflows / "
                      + "workflow-error trigger) has no snapshot - dropped at publish, or deleted before publish"));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectCoreSubWorkflowRefs(Map<String, Object> plan, Set<String> out) {
        Object cores = plan.get("cores");
        if (!(cores instanceof List<?> coreList)) return;
        for (Object c : coreList) {
            if (!(c instanceof Map<?, ?> coreMap)) continue;
            if (!"sub_workflow".equals(coreMap.get("type"))) continue;
            Object subWf = coreMap.get("subWorkflow");
            if (!(subWf instanceof Map<?, ?> subMap)) continue;
            addRef(subMap.get("workflowId"), out);
        }
    }

    @SuppressWarnings("unchecked")
    private static void collectAgentWorkflowRefs(Map<String, Object> plan, Set<String> out) {
        Object agents = plan.get("agents");
        if (!(agents instanceof List<?> agentList)) return;
        for (Object a : agentList) {
            if (!(a instanceof Map<?, ?> node)) continue;
            Object tcRaw = node.get("_snapshot_agent_toolsConfig");
            if (!(tcRaw instanceof Map<?, ?> tc)) continue;
            Object wfList = tc.get("workflows");
            if (!(wfList instanceof List<?> wfs)) continue;
            for (Object wf : wfs) addRef(wf, out);
        }
    }

    private static void collectTriggerWorkflowRefs(Map<String, Object> plan, Set<String> out) {
        Object triggers = plan.get("triggers");
        if (!(triggers instanceof List<?> triggerList)) return;
        for (Object t : triggerList) {
            if (!(t instanceof Map<?, ?> tg)) continue;
            String type = tg.get("type") != null ? tg.get("type").toString().toLowerCase() : "";
            if ("workflow".equals(type) || "error".equals(type)) addRef(tg.get("id"), out);
        }
    }

    private static void addRef(Object idObj, Set<String> out) {
        String id = idObj != null ? idObj.toString() : null;
        if (id != null && !id.isBlank() && !"__self__".equals(id)) out.add(id);
    }

    private static Map<String, Object> missingEntry(String type, Object ref, String at, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("ref", ref != null ? ref.toString() : null);
        m.put("at", at.isEmpty() ? "root" : at);
        m.put("reason", reason);
        return m;
    }

    /**
     * Fetch the current live state of the source resource, mirroring the snapshot structure
     * so the admin can do a meaningful side-by-side comparison.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchCurrentSource(WorkflowPublicationEntity publication, boolean isAgent) {
        Map<String, Object> source = new LinkedHashMap<>();
        String tenantId = publication.getPublisherId();
        // Resolve the publication's OWNING org (null for personal pubs) and pass it to every
        // live-source fetch. The admin reviewing the global moderation queue is almost never in
        // the publisher's workspace, so relying on the request's ambient org (or omitting it)
        // makes org-owned resources fail strict-scope lookups → spurious "not found" in the diff.
        String organizationId = publicationOrganizationId(publication);
        PublicationType type = publication.getPublicationType();

        try {
            if (isAgent && publication.getAgentConfigId() != null) {
                fetchAgentSource(source, publication, tenantId);
            } else if (type == PublicationType.WORKFLOW && publication.getWorkflowId() != null) {
                Map<String, Object> workflowData = orchestratorClient.getWorkflowForPublication(
                        publication.getWorkflowId(), tenantId, organizationId);
                if (workflowData != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> plan = (Map<String, Object>) workflowData.get("plan");
                    if (plan != null) {
                        // Enrich the source plan with _snapshot_* fields (interfaces, datasources,
                        // agents, sub-workflows) so it mirrors the snapshot structure exactly
                        workflowPublicationService.enrichWorkflowPlan(plan, tenantId, organizationId, publication.getWorkflowId());
                    }
                    source.put("plan", plan);
                } else {
                    source.put("error", "Workflow not found or deleted");
                }
            } else if (isStandaloneResource(type) && publication.getResourceId() != null) {
                fetchResourceSource(source, publication, tenantId, type);
            } else {
                source.put("error", "No source resource linked");
            }
        } catch (Exception e) {
            log.warn("Failed to fetch current source for publication {}: {}", publication.getId(), e.getMessage());
            source.put("error", "Failed to fetch current source: " + e.getMessage());
        }

        return source;
    }

    private boolean isStandaloneResource(PublicationType type) {
        return type == PublicationType.TABLE
                || type == PublicationType.INTERFACE
                || type == PublicationType.SKILL;
    }

    /**
     * Rebuild the current live state of a standalone resource (TABLE / INTERFACE / SKILL)
     * using the same strategy that produced the stored snapshot at publish time. Reusing
     * the strategy guarantees the two payloads have identical shapes - differences in the
     * side-by-side diff are then real content drift, not structural noise.
     */
    private void fetchResourceSource(Map<String, Object> source, WorkflowPublicationEntity publication,
                                      String tenantId, PublicationType type) {
        ResourcePublicationStrategy strategy = resourceStrategies.get(type);
        if (strategy == null) {
            source.put("error", "No strategy registered for type " + type);
            return;
        }
        try {
            Map<String, Object> live = strategy.buildSnapshot(
                    publication.getResourceId(), tenantId, publicationOrganizationId(publication));
            if (live != null) {
                source.putAll(live);
            }
        } catch (IllegalArgumentException notFound) {
            source.put("error", notFound.getMessage());
            return;
        }

        // Landing interface is stored alongside the resource snapshot for TABLE/SKILL
        // (INTERFACE publications are their own landing, so showcaseInterfaceId is null).
        if (publication.getShowcaseInterfaceId() != null) {
            UUID landingId = publication.getShowcaseInterfaceId();
            try {
                Map<String, Object> landing = landingInterfaceSnapshotter.buildSnapshot(
                        landingId, tenantId, publicationOrganizationId(publication));
                if (landing != null) {
                    source.put("landingInterface", landing);
                }
            } catch (Exception e) {
                log.warn("Failed to fetch current landing interface for publication {}: {}",
                        publication.getId(), e.getMessage());
                Map<String, Object> sentinel = new LinkedHashMap<>();
                sentinel.put("interfaceId", landingId.toString());
                sentinel.put("error", e.getMessage());
                source.put("landingInterface", sentinel);
            }
        }
    }

    /**
     * Build a source map that mirrors the agent snapshot structure:
     * { agent: {...}, skills: [...], workflows: {...}, interfaces: {...}, datasources: {...} }
     */
    @SuppressWarnings("unchecked")
    private void fetchAgentSource(Map<String, Object> source, WorkflowPublicationEntity publication, String tenantId) {
        UUID agentConfigId = publication.getAgentConfigId();
        String organizationId = publicationOrganizationId(publication);

        // 1. Agent config
        AgentDto agent = agentClient.getAgent(agentConfigId, tenantId, organizationId);
        if (agent == null) {
            source.put("error", "Agent not found or deleted");
            return;
        }

        // 1. Agent config - mirror the snapshot structure EXACTLY (same keys, same guards, same transforms)
        Map<String, Object> agentMap = new LinkedHashMap<>();
        agentMap.put("id", agent.getId().toString());
        agentMap.put("name", agent.getName());
        agentMap.put("description", agent.getDescription());
        agentMap.put("systemPrompt", agent.getSystemPrompt());
        agentMap.put("modelProvider", agent.getModelProvider());
        agentMap.put("modelName", agent.getModelName());
        agentMap.put("temperature", agent.getTemperature() != null ? agent.getTemperature().doubleValue() : null);
        agentMap.put("maxTokens", agent.getMaxTokens());
        agentMap.put("maxIterations", agent.getMaxIterations());
        agentMap.put("executionTimeout", agent.getExecutionTimeout());
        agentMap.put("avatarUrl", filterAvatarUrl(agent.getAvatarUrl()));
        agentMap.put("config", agent.getConfig());
        agentMap.put("dataSourceId", agent.getDataSourceId());
        agentMap.put("creditBudget", agent.getCreditBudget());
        agentMap.put("budgetResetMode", agent.getBudgetResetMode());
        agentMap.put("toolsConfig", agent.getToolsConfig());

        // Webhook config - same guard as snapshot: if (webhookConfig != null)
        try {
            Map<String, Object> webhookConfig = agentClient.getWebhookConfig(agentConfigId, tenantId, organizationId);
            if (webhookConfig != null) {
                Map<String, Object> webhookFiltered = new LinkedHashMap<>();
                webhookFiltered.put("httpMethod", webhookConfig.getOrDefault("httpMethod", "POST"));
                webhookFiltered.put("memoryEnabled", webhookConfig.getOrDefault("memoryEnabled", false));
                agentMap.put("webhookConfig", webhookFiltered);
            }
        } catch (Exception e) {
            log.debug("No webhook config for agent {}", agentConfigId);
        }

        // Schedule config - same guard as snapshot: if (scheduleConfig != null)
        try {
            Map<String, Object> scheduleConfig = agentClient.getScheduleConfig(agentConfigId, tenantId, organizationId);
            if (scheduleConfig != null) {
                Map<String, Object> scheduleFiltered = new LinkedHashMap<>();
                scheduleFiltered.put("cronExpression", scheduleConfig.get("cronExpression"));
                scheduleFiltered.put("timezone", scheduleConfig.getOrDefault("timezone", "UTC"));
                scheduleFiltered.put("maxExecutions", scheduleConfig.get("maxExecutions"));
                scheduleFiltered.put("schedulePrompt", scheduleConfig.get("schedulePrompt"));
                scheduleFiltered.put("withMemory", scheduleConfig.getOrDefault("withMemory", false));
                agentMap.put("scheduleConfig", scheduleFiltered);
            }
        } catch (Exception e) {
            log.debug("No schedule config for agent {}", agentConfigId);
        }

        // Skills - same guard as snapshot: only add if !isEmpty()
        try {
            List<AgentSkillDto> skills = agentClient.getSkillsForAgent(agentConfigId, tenantId, organizationId);
            if (!skills.isEmpty()) {
                List<Map<String, Object>> skillsList = new ArrayList<>();
                for (AgentSkillDto skill : skills) {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("name", skill.getSkillName());
                    s.put("description", skill.getSkillDescription());
                    s.put("icon", skill.getSkillIcon());
                    s.put("instructions", skill.getSkillInstructions());
                    s.put("sortOrder", skill.getSortOrder());
                    skillsList.add(s);
                }
                agentMap.put("skills", skillsList);
            }
        } catch (Exception e) {
            log.warn("Failed to fetch skills: {}", e.getMessage());
        }

        source.put("agent", agentMap);

        // 2. Referenced resources - only add keys if non-empty (same as snapshot)
        Map<String, Object> snapshot = publication.getAgentSnapshot();
        if (snapshot != null) {
            if (snapshot.containsKey("workflows")) {
                fetchReferencedWorkflows(source, snapshot, tenantId, organizationId);
            }
            if (snapshot.containsKey("interfaces")) {
                fetchReferencedInterfaces(source, snapshot, tenantId, organizationId);
            }
            if (snapshot.containsKey("datasources")) {
                fetchReferencedDatasources(source, snapshot, tenantId, organizationId);
            }
            if (snapshot.containsKey("subAgents")) {
                fetchReferencedSubAgents(source, snapshot, tenantId, organizationId);
            }
        }

        // Landing interface - agent publications also have a showcase landing
        // (agent UI in marketplace lives on this interface), so rebuild it live
        // the same way we do for TABLE/SKILL, so snapshot vs live has identical shape.
        if (publication.getShowcaseInterfaceId() != null) {
            UUID landingId = publication.getShowcaseInterfaceId();
            try {
                Map<String, Object> landing = landingInterfaceSnapshotter.buildSnapshot(
                        landingId, tenantId, publicationOrganizationId(publication));
                if (landing != null) {
                    source.put("landingInterface", landing);
                }
            } catch (Exception e) {
                log.warn("Failed to fetch current landing interface for agent publication {}: {}",
                        publication.getId(), e.getMessage());
                Map<String, Object> sentinel = new LinkedHashMap<>();
                sentinel.put("interfaceId", landingId.toString());
                sentinel.put("error", e.getMessage());
                source.put("landingInterface", sentinel);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void fetchReferencedWorkflows(Map<String, Object> source, Map<String, Object> snapshot,
                                          String tenantId, String organizationId) {
        Map<String, Object> snapshotWorkflows = (Map<String, Object>) snapshot.get("workflows");
        if (snapshotWorkflows == null || snapshotWorkflows.isEmpty()) return;

        Map<String, Object> liveWorkflows = new LinkedHashMap<>();
        for (String wfIdStr : snapshotWorkflows.keySet()) {
            try {
                UUID rawId = UUID.fromString(wfIdStr);
                UUID workflowId = rawId;

                // The key may be a publication ID (for applications) - resolve to workflow ID
                Map<String, Object> wfData = orchestratorClient.getWorkflowForPublication(rawId, tenantId, organizationId);
                if (wfData == null) {
                    // Try resolving as publication ID → workflow ID
                    Optional<WorkflowPublicationEntity> pub = publicationRepository.findById(rawId);
                    if (pub.isPresent() && pub.get().getWorkflowId() != null) {
                        workflowId = pub.get().getWorkflowId();
                        wfData = orchestratorClient.getWorkflowForPublication(workflowId, tenantId, organizationId);
                    }
                }

                if (wfData != null) {
                    // Enrich plan with _snapshot_* fields to mirror the snapshot structure
                    @SuppressWarnings("unchecked")
                    Map<String, Object> plan = (Map<String, Object>) wfData.get("plan");
                    if (plan != null) {
                        workflowPublicationService.enrichWorkflowPlan(plan, tenantId, organizationId, workflowId);
                    }
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", wfData.get("name"));
                    entry.put("description", wfData.get("description"));
                    entry.put("plan", plan);
                    liveWorkflows.put(wfIdStr, entry);
                } else {
                    liveWorkflows.put(wfIdStr, Map.of("error", "Workflow not found or deleted"));
                }
            } catch (Exception e) {
                liveWorkflows.put(wfIdStr, Map.of("error", e.getMessage()));
            }
        }
        source.put("workflows", liveWorkflows);
    }

    @SuppressWarnings("unchecked")
    private void fetchReferencedInterfaces(Map<String, Object> source, Map<String, Object> snapshot,
                                           String tenantId, String organizationId) {
        Map<String, Object> snapshotInterfaces = (Map<String, Object>) snapshot.get("interfaces");
        if (snapshotInterfaces == null || snapshotInterfaces.isEmpty()) return;

        Map<String, Object> liveInterfaces = new LinkedHashMap<>();
        for (String ifaceIdStr : snapshotInterfaces.keySet()) {
            try {
                UUID ifaceId = UUID.fromString(ifaceIdStr);
                InterfaceDto iface = getInterfaceScoped(ifaceId, tenantId, organizationId);
                if (iface != null) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", iface.getName());
                    entry.put("description", iface.getDescription());
                    entry.put("htmlTemplate", iface.getHtmlTemplate());
                    entry.put("cssTemplate", iface.getCssTemplate());
                    entry.put("jsTemplate", iface.getJsTemplate());
                    // The snapshot side of this diff carries the format, so the live side must
                    // too - otherwise the reviewer sees a spurious "removed" on every interface.
                    entry.put("format", iface.getFormat());
                    entry.put("interfaceType", iface.getInterfaceType());
                    entry.put("data", iface.getData());
                    liveInterfaces.put(ifaceIdStr, entry);
                } else {
                    liveInterfaces.put(ifaceIdStr, Map.of("error", "Interface not found or deleted"));
                }
            } catch (Exception e) {
                liveInterfaces.put(ifaceIdStr, Map.of("error", e.getMessage()));
            }
        }
        source.put("interfaces", liveInterfaces);
    }

    @SuppressWarnings("unchecked")
    private void fetchReferencedDatasources(Map<String, Object> source, Map<String, Object> snapshot,
                                            String tenantId, String organizationId) {
        Map<String, Object> snapshotDs = (Map<String, Object>) snapshot.get("datasources");
        if (snapshotDs == null || snapshotDs.isEmpty()) return;

        Map<String, Object> liveDatasources = new LinkedHashMap<>();
        for (String dsIdStr : snapshotDs.keySet()) {
            try {
                Long dsId = Long.parseLong(dsIdStr);
                DataSourceDto ds = dataSourceClient.findByIdAndTenantId(dsId, tenantId, organizationId);
                if (ds != null) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", ds.name());
                    entry.put("description", ds.description());
                    // Mirror snapshot: sourceType as enum name string with "INLINE" fallback
                    entry.put("sourceType", ds.sourceType() != null ? ds.sourceType().name() : "INLINE");
                    entry.put("sourceConfig", ds.sourceConfig());
                    entry.put("columnOrder", ds.columnOrder());
                    // Mirror snapshot: convert mappingSpec via objectMapper to Map<String, Object>
                    entry.put("mappingSpec", ds.mappingSpec() != null
                            ? new com.fasterxml.jackson.databind.ObjectMapper().convertValue(
                                ds.mappingSpec(), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {})
                            : null);
                    // Mirror snapshot: only add items if non-empty
                    try {
                        List<DataSourceItemDto> items = dataSourceClient.getAllItems(dsId, tenantId, organizationId);
                        if (!items.isEmpty()) {
                            List<Map<String, Object>> itemsList = new ArrayList<>();
                            for (DataSourceItemDto item : items) {
                                Map<String, Object> itemMap = new LinkedHashMap<>();
                                itemMap.put("data", item.data());
                                itemMap.put("priority", item.priority());
                                itemsList.add(itemMap);
                            }
                            entry.put("items", itemsList);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to fetch items for datasource {}: {}", dsId, e.getMessage());
                    }
                    liveDatasources.put(dsIdStr, entry);
                } else {
                    liveDatasources.put(dsIdStr, Map.of("error", "Datasource not found or deleted"));
                }
            } catch (Exception e) {
                liveDatasources.put(dsIdStr, Map.of("error", e.getMessage()));
            }
        }
        source.put("datasources", liveDatasources);
    }

    @SuppressWarnings("unchecked")
    private void fetchReferencedSubAgents(Map<String, Object> source, Map<String, Object> snapshot, String tenantId,
                                          String organizationId) {
        Map<String, Object> snapshotSubAgents = (Map<String, Object>) snapshot.get("subAgents");
        if (snapshotSubAgents == null || snapshotSubAgents.isEmpty()) return;

        Map<String, Object> liveSubAgents = new LinkedHashMap<>();
        for (String agentIdStr : snapshotSubAgents.keySet()) {
            try {
                UUID subAgentId = UUID.fromString(agentIdStr);
                AgentDto subAgent = agentClient.getAgent(subAgentId, tenantId, organizationId);
                if (subAgent != null) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    Map<String, Object> agentData = new LinkedHashMap<>();
                    agentData.put("id", subAgent.getId() != null ? subAgent.getId().toString() : null);
                    agentData.put("name", subAgent.getName());
                    agentData.put("description", subAgent.getDescription());
                    agentData.put("systemPrompt", subAgent.getSystemPrompt());
                    agentData.put("modelProvider", subAgent.getModelProvider());
                    agentData.put("modelName", subAgent.getModelName());
                    agentData.put("temperature", subAgent.getTemperature() != null ? subAgent.getTemperature().doubleValue() : null);
                    agentData.put("maxTokens", subAgent.getMaxTokens());
                    agentData.put("maxIterations", subAgent.getMaxIterations());
                    agentData.put("avatarUrl", filterAvatarUrl(subAgent.getAvatarUrl()));
                    agentData.put("toolsConfig", subAgent.getToolsConfig());
                    agentData.put("executionTimeout", subAgent.getExecutionTimeout());
                    agentData.put("creditBudget", subAgent.getCreditBudget());
                    agentData.put("budgetResetMode", subAgent.getBudgetResetMode());
                    entry.put("agent", agentData);

                    // Sub-agent skills
                    try {
                        List<AgentSkillDto> skills = agentClient.getSkillsForAgent(subAgentId, tenantId, organizationId);
                        List<Map<String, Object>> skillsList = new ArrayList<>();
                        for (AgentSkillDto skill : skills) {
                            Map<String, Object> s = new LinkedHashMap<>();
                            s.put("name", skill.getSkillName());
                            s.put("description", skill.getSkillDescription());
                            s.put("icon", skill.getSkillIcon());
                            s.put("instructions", skill.getSkillInstructions());
                            s.put("sortOrder", skill.getSortOrder());
                            skillsList.add(s);
                        }
                        entry.put("skills", skillsList);
                    } catch (Exception e) {
                        entry.put("skills", List.of());
                    }

                    liveSubAgents.put(agentIdStr, entry);
                } else {
                    liveSubAgents.put(agentIdStr, Map.of("error", "Sub-agent not found or deleted"));
                }
            } catch (Exception e) {
                liveSubAgents.put(agentIdStr, Map.of("error", e.getMessage()));
            }
        }
        source.put("subAgents", liveSubAgents);
    }

    private static String publicationOrganizationId(WorkflowPublicationEntity publication) {
        if (publication == null || publication.getOwnerType() != WorkflowPublicationEntity.OwnerType.ORG) {
            return null;
        }
        return publication.getOwnerId();
    }

    /**
     * Resolve a referenced interface in the publication's OWNING workspace. When the
     * publication is org-owned we pass the org through (strict-scope lookup); for a personal
     * publication we fall back to the 2-arg client overload - mirroring
     * {@link LandingInterfaceSnapshotter}. This keeps the live diff faithful regardless of which
     * workspace the reviewing admin happens to be in.
     */
    private InterfaceDto getInterfaceScoped(UUID interfaceId, String tenantId, String organizationId) {
        return (organizationId != null && !organizationId.isBlank())
                ? interfaceClient.getInterface(interfaceId, tenantId, organizationId)
                : interfaceClient.getInterface(interfaceId, tenantId);
    }

    // ────── DataInput file path normalization ──────

    /**
     * Replace full S3 paths with just the filename in DataInput file references.
     * Snapshot stores files at "_publications/{pubId}/..." while source has original paths.
     * Without normalization, every file path shows as a false diff.
     */
    @SuppressWarnings("unchecked")
    private void normalizeDataInputPaths(Map<String, Object> plan) {
        if (plan == null) return;
        Object coresRaw = plan.get("cores");
        if (!(coresRaw instanceof List<?> cores)) return;

        for (Object coreRaw : cores) {
            if (!(coreRaw instanceof Map<?, ?> coreMap)) continue;
            Object dataInputRaw = coreMap.get("dataInput");
            if (!(dataInputRaw instanceof Map<?, ?> dataInputMap)) continue;
            Object itemsRaw = dataInputMap.get("items");
            if (!(itemsRaw instanceof List<?> items)) continue;

            for (Object itemRaw : items) {
                if (!(itemRaw instanceof Map<?, ?> itemMap)) continue;
                if (!"file".equals(itemMap.get("type"))) continue;
                Object fileRaw = itemMap.get("file");
                if (!(fileRaw instanceof Map<?, ?>)) continue;
                Map<String, Object> fileMap = (Map<String, Object>) fileRaw;
                String path = fileMap.get("path") != null ? fileMap.get("path").toString() : null;
                if (path != null) {
                    // Keep only the filename (last segment after /)
                    int lastSlash = path.lastIndexOf('/');
                    String filename = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
                    fileMap.put("path", "[file] " + filename);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    /** Mirror the snapshot's avatar URL filtering - single rule in {@code AvatarUrlPolicy}. */
    private String filterAvatarUrl(String avatarUrl) {
        return com.apimarketplace.publication.utils.AvatarUrlPolicy.publishable(avatarUrl);
    }

    private void normalizeDataInputPathsInWorkflows(Map<String, Object> data) {
        if (data == null) return;
        Object workflowsRaw = data.get("workflows");
        if (workflowsRaw instanceof Map<?, ?> workflows) {
            for (Object wfRaw : workflows.values()) {
                if (wfRaw instanceof Map<?, ?> wf) {
                    Object plan = wf.get("plan");
                    if (plan instanceof Map<?, ?>) {
                        normalizeDataInputPaths((Map<String, Object>) plan);
                    }
                }
            }
        }
    }

    @Transactional
    public WorkflowPublicationEntity approvePublication(UUID publicationId, String reviewerId) {
        WorkflowPublicationEntity publication = publicationRepository.findById(publicationId)
                .orElseThrow(() -> new IllegalArgumentException("Publication not found: " + publicationId));

        if (publication.getStatus() != PublicationStatus.PENDING_REVIEW) {
            throw new IllegalStateException("Publication is not pending review. Current status: " + publication.getStatus());
        }

        publication.setStatus(PublicationStatus.ACTIVE);
        publication.setReviewerId(reviewerId);
        publication.setReviewedAt(Instant.now());
        publication.setRejectionReason(null);

        log.info("Publication {} approved by reviewer {}", publicationId, reviewerId);
        return publicationRepository.save(publication);
    }

    @Transactional
    public WorkflowPublicationEntity rejectPublication(UUID publicationId, String reviewerId, String reason) {
        WorkflowPublicationEntity publication = publicationRepository.findById(publicationId)
                .orElseThrow(() -> new IllegalArgumentException("Publication not found: " + publicationId));

        if (publication.getStatus() != PublicationStatus.PENDING_REVIEW) {
            throw new IllegalStateException("Publication is not pending review. Current status: " + publication.getStatus());
        }

        publication.setStatus(PublicationStatus.REJECTED);
        publication.setReviewerId(reviewerId);
        publication.setReviewedAt(Instant.now());
        publication.setRejectionReason(reason);

        log.info("Publication {} rejected by reviewer {}. Reason: {}", publicationId, reviewerId, reason);
        return publicationRepository.save(publication);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> getStats() {
        long pending = publicationRepository.countByStatus(PublicationStatus.PENDING_REVIEW);
        long active = publicationRepository.countByStatus(PublicationStatus.ACTIVE);
        long rejected = publicationRepository.countByStatus(PublicationStatus.REJECTED);
        return Map.of(
                "pendingCount", pending,
                "approvedCount", active,
                "rejectedCount", rejected
        );
    }
}
