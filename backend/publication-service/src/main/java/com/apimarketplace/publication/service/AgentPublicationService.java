package com.apimarketplace.publication.service;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.auth.client.entitlement.ResourceType;
import com.apimarketplace.agent.client.dto.AgentDto;
import com.apimarketplace.agent.client.dto.AgentSkillDto;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.client.DataSourceClient;
import com.apimarketplace.datasource.client.dto.ColumnMappingSpecDto;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import com.apimarketplace.datasource.client.dto.DataSourceItemDto;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.interfaces.client.dto.InterfaceCreateRequest;
import com.apimarketplace.interfaces.client.dto.InterfaceDto;
import com.apimarketplace.publication.config.OrchestratorInternalClient;
import com.apimarketplace.publication.config.PaidTemplatesFeatureFlag;
import com.apimarketplace.publication.domain.PublicationReceiptEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationStatus;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationVisibility;
import com.apimarketplace.publication.repository.PublicationReceiptRepository;
import com.apimarketplace.publication.repository.WorkflowPublicationRepository;
import com.apimarketplace.publication.service.resource.DataSourceFileCloneService;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Service for publishing and acquiring agents on the marketplace.
 * Handles recursive agent snapshot building (sub-agents, skills, workflows,
 * interfaces, datasources) and deep cloning with ID remapping on acquisition.
 */
@Service
@Transactional
public class AgentPublicationService {

    private static final Logger logger = LoggerFactory.getLogger(AgentPublicationService.class);
    private static final int MAX_AGENT_DEPTH = 15;

    /** The 5 internal resource families carrying a {@code <family>Grant} in toolsConfig. */
    private static final List<String> GRANT_FAMILIES =
            List.of("workflows", "tables", "interfaces", "agents", "applications");

    /**
     * Snapshot size guards. A publication embeds an explicit resource selection;
     * these caps turn a runaway selection (huge tables, dozens of heavy resources)
     * into an explicit 422 with a per-resource breakdown instead of an unbounded
     * JSONB row + OOM-prone build. Field defaults apply to plain unit-test
     * constructions; Spring overrides from properties.
     */
    @org.springframework.beans.factory.annotation.Value("${publication.agent-snapshot.max-bytes:15728640}")
    long agentSnapshotMaxBytes = 15L * 1024 * 1024;

    @org.springframework.beans.factory.annotation.Value("${publication.agent-snapshot.max-table-rows:5000}")
    int agentSnapshotMaxTableRows = 5000;

    private final WorkflowPublicationRepository publicationRepository;
    private final PublicationReceiptRepository receiptRepository;
    private final AgentClient agentClient;
    private final InterfaceClient interfaceClient;
    private final DataSourceClient dataSourceClient;
    private final OrchestratorInternalClient orchestratorClient;
    private final StorageBreakdownService breakdownService;
    private final SnapshotCloneService snapshotCloneService;
    private final ObjectMapper objectMapper;
    private final WorkflowPublicationService workflowPublicationService;
    private final EntitlementGuard entitlementGuard;
    private final DataSourceFileCloneService fileCloneService;
    private final LandingInterfaceSnapshotter landingInterfaceSnapshotter;

    /**
     * Acquire-time avatar file copy (snapshot autonomy: the acquired agent must not
     * depend on the publisher's storage). Field-injected to spare the ~30 test
     * constructions of this service; null (unit tests) falls back to the plain
     * publishable pass-through.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    AvatarFileCloneService avatarFileCloneService;
    private final AuthClient authClient;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PublicationAcquisitionHelper acquisitionHelper;

    public AgentPublicationService(WorkflowPublicationRepository publicationRepository,
                                    PublicationReceiptRepository receiptRepository,
                                    AgentClient agentClient,
                                    InterfaceClient interfaceClient,
                                    DataSourceClient dataSourceClient,
                                    OrchestratorInternalClient orchestratorClient,
                                    StorageBreakdownService breakdownService,
                                    SnapshotCloneService snapshotCloneService,
                                    ObjectMapper objectMapper,
                                    WorkflowPublicationService workflowPublicationService,
                                    EntitlementGuard entitlementGuard,
                                    DataSourceFileCloneService fileCloneService,
                                    LandingInterfaceSnapshotter landingInterfaceSnapshotter,
                                    AuthClient authClient) {
        this.entitlementGuard = entitlementGuard;
        this.publicationRepository = publicationRepository;
        this.receiptRepository = receiptRepository;
        this.agentClient = agentClient;
        this.interfaceClient = interfaceClient;
        this.dataSourceClient = dataSourceClient;
        this.orchestratorClient = orchestratorClient;
        this.breakdownService = breakdownService;
        this.snapshotCloneService = snapshotCloneService;
        this.objectMapper = objectMapper;
        this.workflowPublicationService = workflowPublicationService;
        this.fileCloneService = fileCloneService;
        this.landingInterfaceSnapshotter = landingInterfaceSnapshotter;
        this.authClient = authClient;
    }

    /**
     * V261 - see {@code WorkflowPublicationService#resolveAcquirerOrg}. Falls
     * back to default-personal org for daemon/async paths that don't carry
     * X-Organization-ID. Throws when the user has no default org membership.
     */
    private String resolveAcquirerOrg(String tenantId, String organizationId, UUID publicationId) {
        if (organizationId != null && !organizationId.isBlank()) {
            return organizationId;
        }
        String resolved = authClient.getDefaultOrganizationIdForUser(tenantId);
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalArgumentException(
                    "organizationId required after V261 (tenantId=" + tenantId
                            + ", publicationId=" + publicationId
                            + ") - user has no default organization");
        }
        return resolved;
    }

    // ========================================================================
    // Publish Agent
    // ========================================================================

    /**
     * Personal-scope overload - see {@link #publishAgent(Map, String, String)}.
     */
    public WorkflowPublicationEntity publishAgent(Map<String, Object> request, String tenantId) {
        return publishAgent(request, tenantId, null);
    }

    /**
     * Publish an agent (create or update publication). Org-aware: when
     * {@code organizationId} is non-null/blank, the row is org-owned
     * so every teammate of that organization sees it in their listings;
     * otherwise it is personal-scoped to the publisher.
     */
    @SuppressWarnings("unchecked")
    public WorkflowPublicationEntity publishAgent(Map<String, Object> request, String tenantId, String organizationId) {
        String agentConfigIdStr = (String) request.get("agentConfigId");
        if (agentConfigIdStr == null || agentConfigIdStr.isEmpty()) {
            throw new IllegalArgumentException("agentConfigId is required");
        }
        UUID agentConfigId;
        try {
            agentConfigId = UUID.fromString(agentConfigIdStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid agentConfigId format: " + agentConfigIdStr);
        }

        // Landing interface: optional presentation page. When absent, the marketplace
        // renders the agent's identity hero (avatar + gradient) instead.
        UUID landingInterfaceId = landingInterfaceSnapshotter.parseInterfaceId(request.get("interfaceId"));

        // Validate agent exists and belongs to tenant
        AgentDto agent = agentClient.getAgent(agentConfigId, tenantId, organizationId);
        if (agent == null) {
            throw new IllegalArgumentException("Agent not found: " + agentConfigId);
        }
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (!ScopeGuard.isInStrictScope(tenantId, orgId, agent.getTenantId(), agent.getOrganizationId())) {
            throw new IllegalArgumentException("Agent does not belong to tenant");
        }

        // A publication ships an EXPLICIT resource selection. A per-family grant of
        // "all" (on the agent or any sub-agent of its closure) would embed every
        // resource of the publisher's account - unbounded snapshot + wholesale data
        // export - so it is refused up-front with the FULL violation list (all agents,
        // all families, in one pass) so the publisher fixes everything in one go.
        // The source agent keeps its "all" locally; only the published copy needs an
        // explicit selection. Acquirer-side access is theirs to widen after install.
        List<Map<String, Object>> violations = collectAllGrantViolations(agent, tenantId, organizationId);
        if (!violations.isEmpty()) {
            throw new PublicationValidationException(
                    PublicationValidationException.AGENT_ALL_ACCESS_NOT_PUBLISHABLE,
                    "This agent cannot be published because it has 'All' access on some resource types. "
                            + "Set each listed resource type to an explicit selection (custom) or to none, then republish.",
                    Map.of("violations", violations));
        }

        // Check if already published (update if exists)
        Optional<WorkflowPublicationEntity> existing = publicationRepository.findByAgentConfigId(agentConfigId);

        WorkflowPublicationEntity publication;
        if (existing.isPresent()) {
            publication = existing.get();
            if (publication.getStatus() == PublicationStatus.PENDING_REVIEW) {
                throw new PublicationPendingReviewException("Cannot re-publish while publication is pending review. Please wait for admin approval.");
            }
            logger.info("Updating agent publication for agent: {}", agentConfigId);
        } else {
            publication = new WorkflowPublicationEntity();
            publication.setPublisherId(tenantId);
            logger.info("Creating new agent publication for agent: {} (org={})", agentConfigId,
                    organizationId != null && !organizationId.isBlank() ? organizationId : "personal");
        }

        // Assign owning scope (#151). Same re-publish guard as
        // WorkflowPublicationService.publishWorkflow: scope is locked at
        // first-publish and cannot flip USER ↔ ORG mid-flight.
        if (!publication.hasAssignedOwnerScope()) {
            publication.assignOwnerFromContext(tenantId, organizationId);
        } else {
            WorkflowPublicationEntity.OwnerType requestedType =
                    (organizationId != null && !organizationId.isBlank())
                            ? WorkflowPublicationEntity.OwnerType.ORG
                            : WorkflowPublicationEntity.OwnerType.USER;
            String requestedId = (organizationId != null && !organizationId.isBlank())
                    ? organizationId : tenantId;
            if (publication.getOwnerType() != requestedType
                    || !requestedId.equals(publication.getOwnerId())) {
                throw new IllegalArgumentException(
                        "Cannot change publication ownership scope on re-publish "
                        + "(was " + publication.getOwnerType() + "/" + publication.getOwnerId()
                        + ", requested " + requestedType + "/" + requestedId + ")");
            }
        }

        publication.setPublicationType(PublicationType.AGENT);
        publication.setAgentConfigId(agentConfigId);
        publication.setDisplayMode(DisplayMode.AGENT);

        String title = (String) request.getOrDefault("title", agent.getName());
        String description = (String) request.getOrDefault("description", agent.getDescription());
        publication.setTitle(title);
        publication.setDescription(description);

        // Category
        String categoryIdStr = (String) request.get("categoryId");
        if (categoryIdStr != null && !categoryIdStr.isEmpty()) {
            UUID categoryId = UUID.fromString(categoryIdStr);
            Map<String, Object> category = orchestratorClient.getCategoryById(categoryId);
            if (category != null) {
                publication.setCategoryId(categoryId);
                publication.setCategorySlug((String) category.get("slug"));
                publication.setCategoryName((String) category.get("name"));
                publication.setCategoryIconSlug((String) category.get("iconSlug"));
                publication.setCategoryColor((String) category.get("color"));
            }
        }

        // Credits
        Object creditsRaw = request.get("creditsPerUse");
        int creditsPerUse = creditsRaw instanceof Number ? ((Number) creditsRaw).intValue() : 0;
        // Defense-in-depth: paid templates are disabled platform-wide until the
        // billing pipeline ships. Frontend modals grey the price input, but a
        // direct curl POST must not slip through. Existing paid pubs are
        // grandfathered (this gate runs only on new publishes).
        if (creditsPerUse > 0 && !PaidTemplatesFeatureFlag.isEnabled()) {
            throw new IllegalArgumentException(
                    "Paid templates are coming soon. All new publications must be free "
                    + "(creditsPerUse=0) until the feature ships.");
        }
        publication.setCreditsPerUse(creditsPerUse);

        // Frontend body publisher fields are ignored - identity is resolved
        // server-side via AuthClient at every (re)publish. See
        // PublisherProfileSnapshotter for the uniform rule across paths.
        PublisherProfileSnapshotter.snapshotInto(publication, authClient, tenantId);

        // Visibility
        String visStr = (String) request.get("visibility");
        if (visStr != null) {
            try {
                publication.setVisibility(PublicationVisibility.valueOf(visStr.toUpperCase()));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid visibility: " + visStr);
            }
        } else {
            publication.setVisibility(PublicationVisibility.PUBLIC);
        }

        if (publication.getVisibility() == PublicationVisibility.PRIVATE) {
            publication.setStatus(PublicationStatus.ACTIVE);
        } else {
            publication.setStatus(PublicationStatus.PENDING_REVIEW);
            publication.setReviewerId(null);
            publication.setReviewedAt(null);
            publication.setRejectionReason(null);
        }

        // Build the recursive agent snapshot
        Set<UUID> visitedAgentIds = new HashSet<>();
        Set<UUID> visitedWorkflowIds = new HashSet<>();
        Map<String, Object> agentSnapshot = buildAgentSnapshot(agentConfigId, tenantId, organizationId, visitedAgentIds, visitedWorkflowIds, 0);
        if (agentSnapshot == null) {
            throw new IllegalStateException("Failed to build agent snapshot for " + agentConfigId);
        }
        // Presentation-only landing page (rendered verbatim in the marketplace, never cloned).
        // Optional: without it the card shows the agent identity hero instead.
        if (landingInterfaceId != null) {
            agentSnapshot.put("landingInterface",
                    landingInterfaceSnapshotter.buildSnapshot(landingInterfaceId, tenantId, organizationId));
        }
        // Size guard AFTER the full snapshot (incl. landing interface) is assembled,
        // BEFORE anything is persisted - a refused publish must leave no state behind.
        enforceSnapshotSizeCap(agentSnapshot);

        publication.setShowcaseInterfaceId(landingInterfaceId);
        publication.setAgentSnapshot(agentSnapshot);

        // Build denormalized search index. title/description are local vars
        // set above from the request; category/publisher are set on the
        // entity earlier in this method.
        publication.setSearchText(SearchTextBuilder.create()
                .add(title).add(description)
                .add(publication.getCategoryName()).add(publication.getCategorySlug())
                .add(publication.getPublisherName())
                .fromAgentSnapshot(agentSnapshot)
                .build(publication.getId(), "AGENT"));

        // Compute counts - deduplicated (standalone + workflow-embedded, but each ID counted once)
        int agentCount = 1 + countSubAgents(agentSnapshot);
        int skillCount = countSkills(agentSnapshot);
        int interfaceCount = countInterfaces(agentSnapshot);
        int datasourceCount = countDatasources(agentSnapshot);
        int workflowCount = countWorkflows(agentSnapshot);

        publication.setAgentCount(agentCount);
        publication.setSkillCount(skillCount);
        publication.setInterfaceCount(interfaceCount);
        publication.setDatasourceCount(datasourceCount);
        publication.setWorkflowCount(workflowCount);

        WorkflowPublicationEntity saved = publicationRepository.save(publication);

        // Snapshot data input files from workflow plans (requires publicationId from save).
        // snapshotDataInputFiles mutates plan paths in-place, so re-save to persist updated paths.
        if (snapshotWorkflowDataInputFiles(agentSnapshot, saved.getId(), tenantId)) {
            saved = publicationRepository.save(saved);
        }

        return saved;
    }

    /**
     * Post-save: scan workflow plans in the agent snapshot and snapshot any data input files.
     * Must be called after save because snapshotDataInputFiles needs the publicationId for S3 paths.
     * Returns true if any files were processed (caller should re-save to persist updated paths).
     */
    @SuppressWarnings("unchecked")
    private boolean snapshotWorkflowDataInputFiles(Map<String, Object> snapshot, UUID publicationId, String sourceTenantId) {
        boolean processed = false;
        Object workflowsRaw = snapshot.get("workflows");
        if (workflowsRaw instanceof Map<?, ?> workflows) {
            for (Object val : workflows.values()) {
                if (!(val instanceof Map<?, ?> wfSnapshot)) continue;
                Object plan = wfSnapshot.get("plan");
                if (plan instanceof Map<?, ?>) {
                    workflowPublicationService.snapshotDataInputFiles((Map<String, Object>) plan, publicationId, sourceTenantId);
                    processed = true;
                }
            }
        }
        // Also recurse into sub-agents' workflows
        Object subAgentsRaw = snapshot.get("subAgents");
        if (subAgentsRaw instanceof Map<?, ?> subAgents) {
            for (Object subVal : subAgents.values()) {
                if (subVal instanceof Map<?, ?>) {
                    if (snapshotWorkflowDataInputFiles((Map<String, Object>) subVal, publicationId, sourceTenantId)) {
                        processed = true;
                    }
                }
            }
        }
        return processed;
    }

    // ========================================================================
    // Agent Snapshot Builder (recursive)
    // ========================================================================

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildAgentSnapshot(UUID agentConfigId, String tenantId, String organizationId,
                                                     Set<UUID> visitedAgentIds,
                                                     Set<UUID> visitedWorkflowIds,
                                                     int depth) {
        if (!visitedAgentIds.add(agentConfigId)) {
            return null; // cycle guard
        }
        if (depth > MAX_AGENT_DEPTH) {
            logger.warn("Max agent depth ({}) exceeded at {}, stopping recursion", MAX_AGENT_DEPTH, agentConfigId);
            return null;
        }

        AgentDto agent = agentClient.getAgent(agentConfigId, tenantId, organizationId);
        if (agent == null) {
            logger.warn("Agent not found for snapshot: {}", agentConfigId);
            return null;
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();

        // Core agent data
        Map<String, Object> agentData = new LinkedHashMap<>();
        agentData.put("id", agent.getId().toString());
        agentData.put("name", agent.getName());
        agentData.put("description", agent.getDescription());
        agentData.put("systemPrompt", agent.getSystemPrompt());
        agentData.put("modelProvider", agent.getModelProvider());
        agentData.put("modelName", agent.getModelName());
        agentData.put("temperature", agent.getTemperature() != null ? agent.getTemperature().doubleValue() : null);
        agentData.put("maxTokens", agent.getMaxTokens());
        agentData.put("maxIterations", agent.getMaxIterations());
        agentData.put("executionTimeout", agent.getExecutionTimeout());

        // Avatar: keep only viewer-independent URLs (presets, http, public avatar serve)
        agentData.put("avatarUrl", com.apimarketplace.publication.utils.AvatarUrlPolicy.publishable(agent.getAvatarUrl()));
        agentData.put("config", agent.getConfig());
        agentData.put("dataSourceId", agent.getDataSourceId());

        // Credit budget config (not runtime consumed/lastReset)
        agentData.put("creditBudget", agent.getCreditBudget());
        agentData.put("budgetResetMode", agent.getBudgetResetMode());

        // Tools config - used for both dependency extraction (below, driven by the
        // AUTHORITATIVE per-family grant) AND cloning. Persisted VERBATIM (see the persist
        // block below) so the per-family `<family>Grant` keys survive into the clone; the
        // acquirer's remapToolsConfig + normalizeToolsConfig then re-key any "custom" lists.
        Map<String, Object> toolsConfig = agent.getToolsConfig();

        // Webhook config (without secrets - token will be regenerated on acquisition)
        Map<String, Object> webhookConfig = agentClient.getWebhookConfig(agentConfigId, tenantId, organizationId);
        if (webhookConfig != null) {
            Map<String, Object> webhookSnapshot = new LinkedHashMap<>();
            webhookSnapshot.put("httpMethod", webhookConfig.getOrDefault("httpMethod", "POST"));
            webhookSnapshot.put("memoryEnabled", webhookConfig.getOrDefault("memoryEnabled", false));
            agentData.put("webhookConfig", webhookSnapshot);
        }

        // Schedule config (cron, timezone, prompt - schedule will be recreated on acquisition)
        Map<String, Object> scheduleConfig = agentClient.getScheduleConfig(agentConfigId, tenantId, organizationId);
        if (scheduleConfig != null) {
            Map<String, Object> scheduleSnapshot = new LinkedHashMap<>();
            scheduleSnapshot.put("cronExpression", scheduleConfig.get("cronExpression"));
            scheduleSnapshot.put("timezone", scheduleConfig.getOrDefault("timezone", "UTC"));
            scheduleSnapshot.put("maxExecutions", scheduleConfig.get("maxExecutions"));
            scheduleSnapshot.put("schedulePrompt", scheduleConfig.get("schedulePrompt"));
            scheduleSnapshot.put("withMemory", scheduleConfig.getOrDefault("withMemory", false));
            agentData.put("scheduleConfig", scheduleSnapshot);
        }

        // Skills
        List<AgentSkillDto> skills = agentClient.getSkillsForAgent(agentConfigId, tenantId, organizationId);
        if (!skills.isEmpty()) {
            List<Map<String, Object>> skillSnapshots = new ArrayList<>();
            for (AgentSkillDto skill : skills) {
                Map<String, Object> sd = new LinkedHashMap<>();
                sd.put("name", skill.getSkillName());
                sd.put("description", skill.getSkillDescription());
                sd.put("icon", skill.getSkillIcon());
                sd.put("instructions", skill.getSkillInstructions());
                sd.put("sortOrder", skill.getSortOrder());
                skillSnapshots.add(sd);
            }
            agentData.put("skills", skillSnapshots);
        }

        // Resolve resource access from the AUTHORITATIVE per-family GRANT. Aligned with the
        // conversation runtime contract (AgentConfigProvider.ToolsConfig): for each of the 5
        // internal families {workflows, tables, interfaces, agents, applications} the per-family
        // `<family>Grant` ∈ {none|all|custom} is the SINGLE source of truth:
        //   "all"           → unrestricted (snapshot ALL tenant resources of that type)
        //   "custom"        → respect the explicit id list for that family
        //   "none"/absent   → no resources (deny - absent ≠ all, matching the runtime safety net)
        // This is INDEPENDENT of the catalogue `mode` (all/none/custom for catalog API tools),
        // which never gates internal resources. Each resource type below keys its own decision
        // on its own grant via {@link #familyGrant(Map, String)}.

        // Persist the agent's toolsConfig VERBATIM into the snapshot - including the
        // AUTHORITATIVE per-family GRANT keys (workflowsGrant/tablesGrant/interfacesGrant/
        // agentsGrant/applicationsGrant). In the current model the grant alone decides
        // resource access (none/all/custom); the catalogue `mode` is a SEPARATE axis.
        // The old "unrestricted ⇒ strip to {mode:'all'}" coercion DROPPED the grants, so
        // the cloned agent would read every family as "none" (deny) and silently lose all
        // access - notably an all-granted builder agent. On acquisition, remapToolsConfig
        // remaps any "custom" id lists to the clone's ids and normalizeToolsConfig preserves
        // the grants, so all/custom/none all clone faithfully.
        Map<String, Object> persistedToolsConfig = toolsConfig;
        agentData.put("toolsConfig", persistedToolsConfig);

        snapshot.put("agent", agentData);

        // Snapshot workflows BEFORE sub-agents so root agent captures its workflows first.
        // Sub-agents will skip already-visited workflows (via visitedWorkflowIds).
        // This ensures workflows are at the root level for acquireAgentPublication to clone.
        // "workflows" key contains direct workflow UUIDs.
        // "applications" key contains publication UUIDs (acquired marketplace items) that must
        // be resolved to workflow UUIDs via the publication entity.
        Map<String, Object> workflows = new LinkedHashMap<>();
        Object wfRaw = toolsConfig != null ? toolsConfig.get("workflows") : null;
        Object appRaw = toolsConfig != null ? toolsConfig.get("applications") : null;

        // Workflows are gated by workflowsGrant. grant=all is NOT publishable (validated
        // up-front by publishAgent with the aggregated violation list) - a snapshot only
        // ever embeds an explicit selection, never a tenant enumeration.
        String workflowsGrant = familyGrant(toolsConfig, "workflows");
        if ("all".equals(workflowsGrant)) {
            throw allGrantRefused(agentConfigId, agent.getName(), "workflows");
        } else if ("custom".equals(workflowsGrant)) {
            // workflowsGrant=custom: respect the explicit workflows list.
            collectWorkflowSnapshots(wfRaw, false, tenantId, organizationId, visitedWorkflowIds, workflows);
        }
        // workflowsGrant none/absent ⇒ no direct workflows.

        // Applications are gated SEPARATELY by applicationsGrant (publication UUIDs resolved
        // to workflow UUIDs in collectWorkflowSnapshots). Only "custom" enumerates the
        // explicit applications list; "all" is refused like every other family (the
        // verbatim-persisted toolsConfig must never ship an "all" grant to acquirers).
        String applicationsGrant = familyGrant(toolsConfig, "applications");
        if ("all".equals(applicationsGrant)) {
            throw allGrantRefused(agentConfigId, agent.getName(), "applications");
        }
        if ("custom".equals(applicationsGrant)) {
            collectWorkflowSnapshots(appRaw, true, tenantId, organizationId, visitedWorkflowIds, workflows);
        }
        // applicationsGrant none / absent ⇒ no applications.
        if (!workflows.isEmpty()) {
            snapshot.put("workflows", workflows);
        }

        // Collect interface/datasource IDs already captured inside workflow plans (to avoid duplication)
        Set<String> interfaceIdsInWorkflows = collectInterfaceIdsFromWorkflows(workflows);
        Set<String> datasourceIdsInWorkflows = collectDatasourceIdsFromWorkflows(workflows);

        // Snapshot standalone interfaces - skip those already inside a workflow plan
        Map<String, Object> interfaces = new LinkedHashMap<>();
        Object ifacesRaw = toolsConfig != null ? toolsConfig.get("interfaces") : null;
        // Interfaces are gated by interfacesGrant.
        String interfacesGrant = familyGrant(toolsConfig, "interfaces");
        List<?> ifaceIds;
        if ("all".equals(interfacesGrant)) {
            throw allGrantRefused(agentConfigId, agent.getName(), "interfaces");
        } else if ("custom".equals(interfacesGrant) && ifacesRaw instanceof List<?> explicitIds) {
            ifaceIds = explicitIds;
        } else {
            // interfacesGrant none/absent (or custom with no list) ⇒ no standalone interfaces.
            ifaceIds = List.of();
        }
        for (Object ifIdObj : ifaceIds) {
            if (ifIdObj == null) continue;
            String ifIdStr = ifIdObj.toString();
            if (interfaceIdsInWorkflows.contains(ifIdStr)) {
                logger.debug("Skipping interface {} - already captured in workflow plan", ifIdStr);
                continue;
            }
            try {
                UUID ifaceId = UUID.fromString(ifIdStr);
                InterfaceDto iface = interfaceClient.getInterface(ifaceId, tenantId, organizationId);
                if (iface != null) {
                    Map<String, Object> ifSnapshot = new LinkedHashMap<>();
                    ifSnapshot.put("name", iface.getName());
                    ifSnapshot.put("description", iface.getDescription());
                    ifSnapshot.put("htmlTemplate", iface.getHtmlTemplate());
                    ifSnapshot.put("cssTemplate", iface.getCssTemplate());
                    ifSnapshot.put("jsTemplate", iface.getJsTemplate());
                    // The format travels with the templates: without it, acquiring an agent that
                    // grants a vertical interface silently yields a full-page 1280x800 copy.
                    ifSnapshot.put("format", iface.getFormat());
                    ifSnapshot.put("interfaceType", iface.getInterfaceType());
                    ifSnapshot.put("data", iface.getData());
                    interfaces.put(ifIdStr, ifSnapshot);
                }
            } catch (IllegalArgumentException e) {
                // skip
            }
        }
        if (!interfaces.isEmpty()) {
            snapshot.put("interfaces", interfaces);
        }

        // Snapshot standalone datasources - skip those already inside a workflow plan
        Map<String, Object> datasources = new LinkedHashMap<>();
        Object tablesRaw = toolsConfig != null ? toolsConfig.get("tables") : null;
        // Tables/datasources are gated by tablesGrant.
        String tablesGrant = familyGrant(toolsConfig, "tables");
        List<?> tableIds;
        if ("all".equals(tablesGrant)) {
            throw allGrantRefused(agentConfigId, agent.getName(), "tables");
        } else if ("custom".equals(tablesGrant) && tablesRaw instanceof List<?> explicitIds) {
            tableIds = explicitIds;
        } else {
            // tablesGrant none/absent (or custom with no list) ⇒ no standalone datasources.
            tableIds = List.of();
        }
        for (Object dsIdObj : tableIds) {
            if (dsIdObj == null) continue;
            String dsIdStr = dsIdObj.toString();
            if (datasourceIdsInWorkflows.contains(dsIdStr)) {
                logger.debug("Skipping datasource {} - already captured in workflow plan", dsIdStr);
                continue;
            }
            try {
                Long dsId = Long.parseLong(dsIdStr);
                DataSourceDto ds = dataSourceClient.findByIdAndTenantId(dsId, tenantId, organizationId);
                if (ds != null) {
                    Map<String, Object> dsSnapshot = new LinkedHashMap<>();
                    dsSnapshot.put("name", ds.name());
                    dsSnapshot.put("description", ds.description());
                    dsSnapshot.put("sourceType", ds.sourceType() != null ? ds.sourceType().name() : "INLINE");
                    dsSnapshot.put("sourceConfig", ds.sourceConfig());
                    dsSnapshot.put("columnOrder", ds.columnOrder());
                    dsSnapshot.put("mappingSpec", ds.mappingSpec() != null
                            ? objectMapper.convertValue(ds.mappingSpec(), new TypeReference<Map<String, Object>>() {}) : null);

                    // Include items - capped: a published table ships as inline snapshot
                    // rows, so an oversized table must fail loudly (never truncate silently).
                    List<DataSourceItemDto> items = dataSourceClient.getAllItems(dsId, tenantId, organizationId);
                    if (items.size() > agentSnapshotMaxTableRows) {
                        throw new PublicationValidationException(
                                PublicationValidationException.AGENT_SNAPSHOT_TOO_LARGE,
                                "Table '" + ds.name() + "' has " + items.size() + " rows (max "
                                        + agentSnapshotMaxTableRows + " rows per published table). "
                                        + "Remove it from the agent's resource selection or reduce its content.",
                                Map.of(
                                        "maxTableRows", agentSnapshotMaxTableRows,
                                        "breakdown", List.of(
                                                breakdownEntry("datasource", dsIdStr, ds.name(), items.size(), null))));
                    }
                    if (!items.isEmpty()) {
                        List<Map<String, Object>> itemSnapshots = items.stream()
                                .map(item -> {
                                    Map<String, Object> is = new HashMap<>();
                                    is.put("data", item.data());
                                    is.put("priority", item.priority());
                                    return is;
                                })
                                .toList();
                        dsSnapshot.put("items", itemSnapshots);
                    }
                    datasources.put(dsIdObj.toString(), dsSnapshot);
                }
            } catch (NumberFormatException e) {
                // skip
            }
        }
        if (!datasources.isEmpty()) {
            snapshot.put("datasources", datasources);
        }

        // Recurse into sub-agents (AFTER workflows/interfaces/datasources so root captures first)
        Map<String, Object> subAgents = new LinkedHashMap<>();
        Object agentsRaw = toolsConfig != null ? toolsConfig.get("agents") : null;
        // Sub-agents are gated by agentsGrant.
        String agentsGrant = familyGrant(toolsConfig, "agents");
        List<?> subAgentIds;
        if ("all".equals(agentsGrant)) {
            throw allGrantRefused(agentConfigId, agent.getName(), "agents");
        } else if ("custom".equals(agentsGrant) && agentsRaw instanceof List<?> explicitIds) {
            subAgentIds = explicitIds;
        } else {
            // agentsGrant none/absent (or custom with no list) ⇒ no sub-agents.
            subAgentIds = List.of();
        }
        for (Object idObj : subAgentIds) {
            if (idObj == null) continue;
            try {
                UUID subAgentId = UUID.fromString(idObj.toString());
                if (visitedAgentIds.contains(subAgentId)) continue;
                Map<String, Object> subSnapshot = buildAgentSnapshot(subAgentId, tenantId, organizationId, visitedAgentIds, visitedWorkflowIds, depth + 1);
                if (subSnapshot != null) {
                    subAgents.put(idObj.toString(), subSnapshot);
                }
            } catch (IllegalArgumentException e) {
                // skip invalid UUIDs
            }
        }
        if (!subAgents.isEmpty()) {
            snapshot.put("subAgents", subAgents);
        }

        // Defense-in-depth credential scrub on every text field a publisher
        // can edit (systemPrompt, skill.instructions) plus the free-form
        // agent.config blob. Symmetric with stripSensitiveCredentials on
        // workflow plans and ShowcaseSnapshotBuilder.scrubMap on run-state.
        scrubAgentSnapshotForCredentials(snapshot);

        return snapshot;
    }

    /**
     * Patterns that look like inlined credentials in free-form prompt text.
     * Match conservatively - false positives are acceptable, false negatives
     * (a real key shipped to every acquirer) are not.
     */
    private static final java.util.regex.Pattern[] CREDENTIAL_TEXT_PATTERNS = {
            // OpenAI / Anthropic - both `sk-` (hyphen) and `sk_` (underscore) variants
            java.util.regex.Pattern.compile("sk[-_][A-Za-z0-9_\\-]{16,}"),
            // Stripe live/test keys: sk_live_, pk_live_, rk_live_, sk_test_, pk_test_, rk_test_
            java.util.regex.Pattern.compile("(?:sk|pk|rk)_(?:live|test)_[A-Za-z0-9]{16,}"),
            // Google API key
            java.util.regex.Pattern.compile("AIza[A-Za-z0-9_\\-]{20,}"),
            // Google OAuth access tokens
            java.util.regex.Pattern.compile("ya29\\.[A-Za-z0-9_\\-]{20,}"),
            // Bearer XXX (raw HTTP-style)
            java.util.regex.Pattern.compile("(?i)bearer\\s+[A-Za-z0-9_\\-\\.=]+"),
            // GitHub PAT family: classic ghp_, OAuth gho_, user-to-server ghu_, server-to-server ghs_, refresh ghr_
            java.util.regex.Pattern.compile("gh[opsur]_[A-Za-z0-9]{30,}"),
            // Slack legacy + bot + app tokens
            java.util.regex.Pattern.compile("xox[baprs]-[A-Za-z0-9\\-]{10,}"),
            java.util.regex.Pattern.compile("xapp-[A-Za-z0-9\\-]{10,}"),
            // AWS access key id
            java.util.regex.Pattern.compile("AKIA[0-9A-Z]{16}"),
            // Twilio account / API SIDs
            java.util.regex.Pattern.compile("AC[a-f0-9]{32}"),
            java.util.regex.Pattern.compile("SK[a-f0-9]{32}"),
            // JWT (header.payload.signature) - three base64url segments
            java.util.regex.Pattern.compile("eyJ[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}\\.[A-Za-z0-9_\\-]{8,}"),
            // Generic labeled secret: key=value / key: value
            java.util.regex.Pattern.compile("(?i)(api[_-]?key|password|secret|token|client[_-]?secret|refresh[_-]?token|access[_-]?token)\\s*[:=]\\s*[\"']?[A-Za-z0-9_\\-\\.=]{8,}[\"']?")
    };

    private static final String REDACTED_TEXT = com.apimarketplace.publication.utils.CredentialKeyDetector.REDACTED;

    /**
     * Recursively scrub the agent snapshot:
     *   - free-form text fields (systemPrompt, skill.instructions): regex-redact
     *     known secret patterns
     *   - any nested map key matching {@link #AGENT_CREDENTIAL_KEY_HINTS}:
     *     replace value with sentinel
     */
    @SuppressWarnings("unchecked")
    private static void scrubAgentSnapshotForCredentials(Map<String, Object> snapshot) {
        Object sp = snapshot.get("systemPrompt");
        if (sp instanceof String s) snapshot.put("systemPrompt", redactText(s));

        Object skills = snapshot.get("skills");
        if (skills instanceof List<?> list) {
            for (Object skill : list) {
                if (skill instanceof Map<?, ?> sk) {
                    Object instr = ((Map<String, Object>) sk).get("instructions");
                    if (instr instanceof String txt) {
                        ((Map<String, Object>) sk).put("instructions", redactText(txt));
                    }
                }
            }
        }

        Object cfg = snapshot.get("config");
        if (cfg instanceof Map<?, ?>) {
            scrubByKey(snapshot.get("config"));
        }
        // Recurse into the whole snapshot to redact any nested credential keys
        scrubByKey(snapshot);
    }

    private static String redactText(String text) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        for (java.util.regex.Pattern p : CREDENTIAL_TEXT_PATTERNS) {
            out = p.matcher(out).replaceAll(REDACTED_TEXT);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void scrubByKey(Object node) {
        scrubByKeyAndRedactStrings(node, null);
    }

    /**
     * Recurse through Maps / Lists redacting:
     *   - whole values under credential-shaped keys, AND
     *   - inlined secret patterns in any string leaf (Stripe, AWS, GitHub,
     *     OpenAI, Slack, JWT, …) regardless of where they appear.
     *
     * <p>This closes the gap where a publisher pasted an inlined API key
     * inside a field with a benign name (e.g. {@code description}, {@code
     * notes}) - the key-name scrubber wouldn't catch it but the
     * regex-on-leaves does.
     */
    @SuppressWarnings("unchecked")
    private static void scrubByKeyAndRedactStrings(Object node, java.util.function.BiConsumer<Object, Object> setter) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> m = (Map<String, Object>) map;
            for (Map.Entry<String, Object> e : new ArrayList<>(m.entrySet())) {
                String key = e.getKey();
                if (key != null && looksSensitiveAgent(key)) {
                    safePut(m, key, REDACTED_TEXT);
                    continue;
                }
                Object v = e.getValue();
                if (v instanceof String s) {
                    String redacted = redactText(s);
                    if (!redacted.equals(s)) safePut(m, key, redacted);
                } else {
                    scrubByKeyAndRedactStrings(v, null);
                }
            }
        } else if (node instanceof List<?> list) {
            List<Object> l;
            try {
                l = (List<Object>) list;
            } catch (ClassCastException e) {
                return;
            }
            for (int i = 0; i < l.size(); i++) {
                Object v = l.get(i);
                if (v instanceof String s) {
                    String redacted = redactText(s);
                    if (!redacted.equals(s)) {
                        try {
                            l.set(i, redacted);
                        } catch (UnsupportedOperationException ignored) {
                            // immutable list (e.g. List.of(...) in tests) - leave as-is
                        }
                    }
                } else {
                    scrubByKeyAndRedactStrings(v, null);
                }
            }
        }
    }

    private static void safePut(Map<String, Object> m, String key, Object value) {
        try {
            m.put(key, value);
        } catch (UnsupportedOperationException ignored) {
            // immutable map (e.g. Map.of(...) in tests) - leave as-is
        }
    }

    private static boolean looksSensitiveAgent(String key) {
        return com.apimarketplace.publication.utils.CredentialKeyDetector.looksSensitive(key);
    }

    /**
     * Read the AUTHORITATIVE per-family grant from a toolsConfig blob.
     *
     * <p>Returns the {@code <family>Grant} value ∈ {@code {none|all|custom}} (or
     * {@code null} when the key is absent or {@code toolsConfig} is null). The
     * caller treats {@code null}/{@code "none"} as deny - an absent grant must
     * NEVER resolve to "all", matching the runtime contract in
     * {@code AgentConfigProvider.ToolsConfig} ("absent ≠ all").
     *
     * @param tc     the agent's toolsConfig map (may be null)
     * @param family one of workflows|tables|interfaces|agents|applications
     * @return the grant string, or null if absent
     */
    private static String familyGrant(Map<String, Object> tc, String family) {
        if (tc == null) return null;
        Object grant = tc.get(family + "Grant");
        return grant != null ? grant.toString() : null;
    }

    // ========================================================================
    // Publish-time validation: no "all" grant may enter a publication
    // ========================================================================

    /**
     * Walk the agent closure (root + sub-agents reachable through explicit
     * {@code agentsGrant=custom} lists) and collect every family granted
     * {@code "all"}. Returns ALL violations in one pass so the publisher fixes
     * everything in a single round-trip. Never enumerates the tenant: an
     * {@code agentsGrant=all} is itself a violation, not a traversal edge.
     */
    private List<Map<String, Object>> collectAllGrantViolations(AgentDto rootAgent, String tenantId, String organizationId) {
        List<Map<String, Object>> violations = new ArrayList<>();
        collectAllGrantViolations(rootAgent, tenantId, organizationId,
                new HashSet<>(), List.of(), violations, 0);
        return violations;
    }

    private void collectAllGrantViolations(AgentDto agent, String tenantId, String organizationId,
                                            Set<UUID> visited, List<String> referencedVia,
                                            List<Map<String, Object>> violations, int depth) {
        // Depth check BEFORE marking visited: an agent first reached beyond the depth
        // cap must stay re-validatable through a shorter path, or its violation would
        // silently drop out of the aggregate.
        if (agent == null || agent.getId() == null || depth > MAX_AGENT_DEPTH || !visited.add(agent.getId())) {
            return;
        }
        Map<String, Object> tc = agent.getToolsConfig();
        List<String> allFamilies = new ArrayList<>();
        for (String family : GRANT_FAMILIES) {
            if ("all".equals(familyGrant(tc, family))) {
                allFamilies.add(family);
            }
        }
        if (!allFamilies.isEmpty()) {
            Map<String, Object> violation = new LinkedHashMap<>();
            violation.put("agentId", agent.getId().toString());
            violation.put("agentName", agent.getName() != null ? agent.getName() : agent.getId().toString());
            violation.put("root", referencedVia.isEmpty());
            if (!referencedVia.isEmpty()) {
                violation.put("referencedVia", referencedVia);
            }
            violation.put("families", allFamilies);
            violations.add(violation);
        }
        // Recurse only through the explicit custom sub-agent list (grant=all on the
        // agents family was recorded as a violation above - never expanded).
        if ("custom".equals(familyGrant(tc, "agents")) && tc.get("agents") instanceof List<?> subIds) {
            List<String> childPath = new ArrayList<>(referencedVia);
            childPath.add(agent.getName() != null ? agent.getName() : agent.getId().toString());
            for (Object idObj : subIds) {
                if (idObj == null) continue;
                UUID subId;
                try {
                    subId = UUID.fromString(idObj.toString());
                } catch (IllegalArgumentException ignored) {
                    // invalid UUID in the list - the snapshot builder skips it the same way
                    continue;
                }
                if (visited.contains(subId)) continue;
                AgentDto sub = agentClient.getAgent(subId, tenantId, organizationId);
                collectAllGrantViolations(sub, tenantId, organizationId,
                        visited, List.copyOf(childPath), violations, depth + 1);
            }
        }
    }

    /**
     * Defense-in-depth for the snapshot builder: {@code publishAgent} pre-validates
     * the whole closure and refuses grant=all with the aggregated violation list.
     * Reaching this from a new call path means that validation was skipped - fail
     * closed with a single-violation body, never enumerate the tenant.
     */
    private static PublicationValidationException allGrantRefused(UUID agentId, String agentName, String family) {
        Map<String, Object> violation = new LinkedHashMap<>();
        violation.put("agentId", agentId.toString());
        violation.put("agentName", agentName != null ? agentName : agentId.toString());
        violation.put("root", true);
        violation.put("families", List.of(family));
        return new PublicationValidationException(
                PublicationValidationException.AGENT_ALL_ACCESS_NOT_PUBLISHABLE,
                "Agent '" + (agentName != null ? agentName : agentId) + "' has 'All' access on '" + family
                        + "'. A publication ships an explicit resource selection; set this resource type to custom or none.",
                Map.of("violations", List.of(violation)));
    }

    // ========================================================================
    // Snapshot size guard
    // ========================================================================

    /**
     * Refuse a snapshot whose serialized size exceeds the configured cap, with a
     * heaviest-first per-resource breakdown so the publisher immediately sees what
     * to trim. Serialization failures are ignored here (this is only a size guard;
     * a truly unserializable snapshot fails at persistence with its own error).
     */
    void enforceSnapshotSizeCap(Map<String, Object> snapshot) {
        long size;
        try {
            size = objectMapper.writeValueAsBytes(snapshot).length;
        } catch (Exception e) {
            logger.warn("Snapshot size guard skipped (serialization failed): {}", e.getMessage());
            return;
        }
        if (size <= agentSnapshotMaxBytes) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("sizeBytes", size);
        details.put("maxBytes", agentSnapshotMaxBytes);
        details.put("breakdown", computeSnapshotBreakdown(snapshot));
        throw new PublicationValidationException(
                PublicationValidationException.AGENT_SNAPSHOT_TOO_LARGE,
                "Publication snapshot is " + toMb(size) + " MB (max " + toMb(agentSnapshotMaxBytes)
                        + " MB). Remove the heaviest resources from the agent's selection or reduce their content.",
                details);
    }

    /**
     * Per-resource serialized weight, heaviest first (top 8). Sections walked:
     * workflows / interfaces / datasources / subAgents / landingInterface.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> computeSnapshotBreakdown(Map<String, Object> snapshot) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map.Entry<String, String> section : Map.of(
                "workflows", "workflow",
                "interfaces", "interface",
                "datasources", "datasource",
                "subAgents", "agent").entrySet()) {
            Object raw = snapshot.get(section.getKey());
            if (!(raw instanceof Map<?, ?> map)) continue;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object value = e.getValue();
                String name = null;
                Integer items = null;
                if (value instanceof Map<?, ?> vm) {
                    Object n = vm.get("name");
                    if (n == null && vm.get("agent") instanceof Map<?, ?> am) n = am.get("name");
                    name = n != null ? n.toString() : null;
                    if (vm.get("items") instanceof List<?> l) items = l.size();
                }
                entries.add(breakdownEntry(section.getValue(),
                        String.valueOf(e.getKey()), name, items, approxBytes(value)));
            }
        }
        Object landing = snapshot.get("landingInterface");
        if (landing != null) {
            entries.add(breakdownEntry("landingInterface", null, null, null, approxBytes(landing)));
        }
        entries.sort((a, b) -> Long.compare(
                ((Number) b.getOrDefault("approxBytes", 0L)).longValue(),
                ((Number) a.getOrDefault("approxBytes", 0L)).longValue()));
        return entries.size() > 8 ? new ArrayList<>(entries.subList(0, 8)) : entries;
    }

    private long approxBytes(Object value) {
        try {
            return value != null ? objectMapper.writeValueAsBytes(value).length : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static Map<String, Object> breakdownEntry(String type, String id, String name,
                                                       Integer items, Long approxBytes) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("type", type);
        if (id != null) entry.put("id", id);
        if (name != null) entry.put("name", name);
        if (items != null) entry.put("items", items);
        if (approxBytes != null) entry.put("approxBytes", approxBytes);
        return entry;
    }

    private static String toMb(long bytes) {
        return String.valueOf(Math.round(bytes / (1024.0 * 1024.0) * 10.0) / 10.0);
    }

    /**
     * Collect workflow snapshots from a toolsConfig list.
     *
     * @param listRaw            the raw list (workflow IDs or publication IDs)
     * @param isPublicationIds   if true, IDs are publication UUIDs that must be resolved to workflow UUIDs
     * @param tenantId           the tenant owning the agent
     * @param visitedWorkflowIds cycle detection set
     * @param out                output map to populate (keyed by original ID for remapping)
     */
    @SuppressWarnings("unchecked")
    private void collectWorkflowSnapshots(Object listRaw, boolean isPublicationIds, String tenantId, String organizationId,
                                           Set<UUID> visitedWorkflowIds, Map<String, Object> out) {
        if (!(listRaw instanceof List<?> ids)) return;
        for (Object idObj : ids) {
            if (idObj == null) continue;
            String idStr = idObj.toString();
            if ("__self__".equals(idStr)) continue;
            if (out.containsKey(idStr)) continue;
            try {
                UUID rawId = UUID.fromString(idStr);
                UUID workflowId = rawId;

                // For applications, resolve publication ID → workflow ID
                if (isPublicationIds) {
                    Optional<WorkflowPublicationEntity> pub = publicationRepository.findById(rawId);
                    if (pub.isPresent() && pub.get().getWorkflowId() != null) {
                        workflowId = pub.get().getWorkflowId();
                        logger.info("Resolved application publication {} -> workflow {}", rawId, workflowId);
                    } else {
                        // May be a direct workflow ID stored under "applications" - try as-is
                        logger.info("Application {} not found as publication, trying as workflow ID", rawId);
                    }
                }

                if (!visitedWorkflowIds.add(workflowId)) continue;

                Map<String, Object> wfData = orchestratorClient.getWorkflowForPublication(workflowId, tenantId, organizationId);
                if (wfData != null && wfData.get("plan") != null) {
                    Map<String, Object> plan = (Map<String, Object>) wfData.get("plan");
                    workflowPublicationService.enrichWorkflowPlan(plan, tenantId, organizationId, workflowId);

                    Map<String, Object> wfSnapshot = new LinkedHashMap<>();
                    wfSnapshot.put("name", wfData.get("name"));
                    wfSnapshot.put("description", wfData.get("description"));
                    wfSnapshot.put("plan", plan);
                    // Key by original ID (not resolved) so remapping works on acquisition
                    out.put(idStr, wfSnapshot);
                }
            } catch (IllegalArgumentException e) {
                // skip invalid UUIDs
            }
        }
    }

    // ========================================================================
    // Acquire Agent Publication
    // ========================================================================

    /**
     * Personal-scope overload. Organization-scoped routing uses
     * {@link #acquireAgentPublication(UUID, String, String)}.
     */
    public Map<String, Object> acquireAgentPublication(UUID publicationId, String tenantId) {
        return acquireAgentPublication(publicationId, tenantId, null);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> acquireAgentPublication(UUID publicationId, String tenantId, String organizationId) {
        WorkflowPublicationEntity publication = publicationRepository.findById(publicationId)
                .orElseThrow(() -> new IllegalArgumentException("Publication not found: " + publicationId));

        if (publication.getPublicationType() != PublicationType.AGENT) {
            throw new IllegalArgumentException("Publication is not an agent publication");
        }

        // V261: receipt.organization_id is NOT NULL - resolve fallback from
        // user's default-personal org when request omitted X-Organization-ID.
        // Reassign-then-rebind to a final to keep lambda captures happy below.
        organizationId = resolveAcquirerOrg(tenantId, organizationId, publicationId);
        final String orgScope = organizationId;

        if (acquisitionHelper != null) {
            acquisitionHelper.validateNotOwnPublication(publication, tenantId, orgScope);
        } else if (isOwnPublication(publication, tenantId, orgScope)) {
            throw new IllegalArgumentException("Cannot acquire your own publication");
        }

        // Check receipt for free re-acquisition
        boolean hasReceipt = acquisitionHelper != null
                ? acquisitionHelper.validateAndCheckEntitlement(publication, tenantId, organizationId)
                : hasReceiptInScope(tenantId, publicationId, organizationId);

        if (acquisitionHelper == null && !hasReceipt && publication.getVisibility() == PublicationVisibility.PRIVATE) {
            throw new IllegalArgumentException("Publication is private");
        }
        if (acquisitionHelper == null && !hasReceipt && publication.getStatus() != PublicationStatus.ACTIVE) {
            throw new IllegalArgumentException("Publication is not active");
        }
        if (acquisitionHelper == null && hasReceipt && publication.getStatus() == PublicationStatus.REJECTED) {
            throw new IllegalArgumentException("Publication is not available");
        }

        // Enforce APPLICATION quota only for first-time acquisitions
        if (acquisitionHelper == null && !hasReceipt && entitlementGuard != null) {
            entitlementGuard.check(tenantId, ResourceType.APPLICATION,
                    () -> countReceiptsInScope(tenantId, orgScope));
        }

        Map<String, Object> agentSnapshot = publication.getAgentSnapshot();
        if (agentSnapshot == null) {
            throw new IllegalStateException("Agent snapshot is missing");
        }

        // Clone resources bottom-up. The four mappings double as a paper
        // trail for the failure-mode compensation below: any partial clone
        // that succeeded before a downstream throw is reachable via these
        // maps, so the catch block can roll it back.
        Map<String, String> workflowMapping = new HashMap<>();
        Map<String, String> interfaceMapping = new HashMap<>();
        Map<String, String> dsMapping = new HashMap<>();
        Map<String, String> agentMapping = new HashMap<>();

        try {
            UUID rootAgentId = cloneAgentFromSnapshot(agentSnapshot, tenantId, publicationId, organizationId,
                    workflowMapping, interfaceMapping, dsMapping, agentMapping);

            if (acquisitionHelper != null) {
                acquisitionHelper.recordAcquisition(publication, tenantId, organizationId, hasReceipt);
            } else {
                // Save receipt
                if (!hasReceipt) {
                    PublicationReceiptEntity receipt = new PublicationReceiptEntity(
                            tenantId, publicationId, publication.getCreditsPerUse() != null ? publication.getCreditsPerUse() : 0,
                            normalizeScope(organizationId));
                    receiptRepository.save(receipt);
                }

                // Increment usage
                publicationRepository.incrementUsage(publicationId);
            }

            Map<String, Object> result = new HashMap<>();
            result.put("agentId", rootAgentId.toString());
            result.put("name", publication.getTitle());
            return result;
        } catch (RuntimeException cloneFailure) {
            logger.error("[AgentAcquire] failed for pub={} tenant={}: {} - running compensation",
                    publicationId, tenantId, cloneFailure.getMessage());
            compensateAgentAcquireFailure(publicationId, tenantId, organizationId,
                    workflowMapping, interfaceMapping, dsMapping, agentMapping);
            throw cloneFailure;
        }
    }

    /**
     * CE-cloud remote acquire of an AGENT publication. The publication lives on
     * the cloud (its id is absent from the local DB) so it can't be looked up
     * locally - {@link RemoteMarketplaceService} has already fetched the agent
     * snapshot from the cloud (charging the linked cloud account for paid
     * publications, surfacing INSUFFICIENT_CREDITS on a cloud 402) and verified
     * there is no existing local receipt. We clone the agent fleet from that
     * snapshot under the acquirer's tenant and write a remote-acquisition
     * receipt, sharing the same bottom-up clone + compensation as the local
     * path. {@code creditsPaid} is what the cloud actually charged.
     */
    public Map<String, Object> acquireAgentFromCloudSnapshot(Map<String, Object> agentSnapshot,
                                                             String tenantId, UUID publicationId,
                                                             String organizationId, int creditsPaid) {
        if (agentSnapshot == null || agentSnapshot.isEmpty()) {
            throw new IllegalStateException("Cloud returned an empty agent snapshot for publication " + publicationId);
        }
        Map<String, String> workflowMapping = new HashMap<>();
        Map<String, String> interfaceMapping = new HashMap<>();
        Map<String, String> dsMapping = new HashMap<>();
        Map<String, String> agentMapping = new HashMap<>();
        try {
            UUID rootAgentId = cloneAgentFromSnapshot(agentSnapshot, tenantId, publicationId, organizationId,
                    workflowMapping, interfaceMapping, dsMapping, agentMapping);

            PublicationReceiptEntity receipt = new PublicationReceiptEntity(
                    tenantId, publicationId, creditsPaid, normalizeScope(organizationId));
            receipt.setRemoteAcquisition(true);
            receiptRepository.save(receipt);

            Map<String, Object> result = new HashMap<>();
            result.put("agentId", rootAgentId.toString());
            return result;
        } catch (RuntimeException cloneFailure) {
            logger.error("[AgentAcquire/remote] failed for pub={} tenant={}: {} - running compensation",
                    publicationId, tenantId, cloneFailure.getMessage());
            compensateAgentAcquireFailure(publicationId, tenantId, organizationId,
                    workflowMapping, interfaceMapping, dsMapping, agentMapping);
            throw cloneFailure;
        }
    }

    /**
     * Shared bottom-up clone of an agent snapshot under {@code tenantId}:
     * workflows → standalone interfaces → standalone datasources → agents
     * (leaf-first). Populates the four id-mapping accumulators (old→new) so the
     * CALLER can compensate (roll back) any partial clone if a later step (e.g.
     * its receipt write) throws - this method itself does NOT compensate.
     * Returns the cloned root agent id; throws if the root agent failed to
     * clone. Shared by the local {@link #acquireAgentPublication} and the
     * CE-cloud {@link #acquireAgentFromCloudSnapshot}.
     */
    @SuppressWarnings("unchecked")
    private UUID cloneAgentFromSnapshot(Map<String, Object> agentSnapshot, String tenantId, UUID publicationId,
                                        String organizationId,
                                        Map<String, String> workflowMapping, Map<String, String> interfaceMapping,
                                        Map<String, String> dsMapping, Map<String, String> agentMapping) {
        // Deep copy to avoid mutating the source
        Map<String, Object> snapshot = objectMapper.convertValue(agentSnapshot,
                new TypeReference<Map<String, Object>>() {});

        // 1. Clone workflows
        Object workflowsRaw = snapshot.get("workflows");
        if (workflowsRaw instanceof Map<?, ?> workflows) {
            for (Map.Entry<?, ?> entry : workflows.entrySet()) {
                String oldWfId = entry.getKey().toString();
                if (!(entry.getValue() instanceof Map<?, ?> wfSnapshot)) continue;

                Map<String, Object> wfData = (Map<String, Object>) wfSnapshot;
                Object plan = wfData.get("plan");
                if (plan instanceof Map<?, ?> planMap) {
                    // Use SnapshotCloneService for full workflow cloning (handles interfaces, datasources, agents within the plan)
                    Map<String, Object> result = cloneWorkflowSnapshot(
                            (Map<String, Object>) plan, tenantId, publicationId, wfData, organizationId);
                    if (result != null && result.get("workflowId") != null) {
                        workflowMapping.put(oldWfId, result.get("workflowId").toString());
                    }
                }
            }
        }

        // 2. Clone standalone interfaces
        Object interfacesRaw = snapshot.get("interfaces");
        if (interfacesRaw instanceof Map<?, ?> interfaces) {
            for (Map.Entry<?, ?> entry : interfaces.entrySet()) {
                String oldIfaceId = entry.getKey().toString();
                if (!(entry.getValue() instanceof Map<?, ?> ifaceSnapshot)) continue;

                Map<String, Object> ifData = (Map<String, Object>) ifaceSnapshot;
                InterfaceCreateRequest req = new InterfaceCreateRequest();
                // Content fields validated (not blindly .toString()'d): a numeric value here
                // would silently store a bare number into interface name/description/templates
                // (same class as the "106735"-in-skill-instructions corruption).
                req.setName(asContentText(ifData.get("name"), "Acquired Interface"));
                req.setDescription(asContentText(ifData.get("description"), null));
                req.setHtmlTemplate(asContentText(ifData.get("htmlTemplate"), ""));
                req.setCssTemplate(asContentText(ifData.get("cssTemplate"), null));
                req.setJsTemplate(asContentText(ifData.get("jsTemplate"), null));
                req.setInterfaceType(ifData.get("interfaceType") != null ? ifData.get("interfaceType").toString() : "html");
                // Restore the published shape. Content-validated like the templates it belongs to:
                // a numeric value here must not be stringified into the column.
                req.setFormat(asContentText(ifData.get("format"), null));
                req.setIsPublic(false);
                if (normalizeScope(organizationId) != null) {
                    req.setOrganizationId(normalizeScope(organizationId));
                }
                if (ifData.get("data") instanceof Map) {
                    req.setData((Map<String, Object>) ifData.get("data"));
                }

                InterfaceDto saved = interfaceClient.createInterface(req, tenantId);
                if (saved != null) {
                    interfaceMapping.put(oldIfaceId, saved.getId().toString());
                }
            }
        }

        // 3. Clone standalone datasources
        Object datasourcesRaw = snapshot.get("datasources");
        if (datasourcesRaw instanceof Map<?, ?> datasources) {
            for (Map.Entry<?, ?> entry : datasources.entrySet()) {
                String oldDsId = entry.getKey().toString();
                if (!(entry.getValue() instanceof Map<?, ?> dsSnapshot)) continue;

                Map<String, Object> dsData = (Map<String, Object>) dsSnapshot;

                String sourceType = dsData.get("sourceType") != null
                        ? dsData.get("sourceType").toString() : "INLINE";
                Map<String, Object> sourceConfig = dsData.get("sourceConfig") instanceof Map<?, ?> scm
                        ? new LinkedHashMap<>((Map<String, Object>) scm) : new LinkedHashMap<>();
                List<Map<String, Object>> itemList = dsData.get("items") instanceof List<?> itemsRaw
                        ? new ArrayList<>((List<Map<String, Object>>) itemsRaw) : new ArrayList<>();
                Map<String, ColumnMappingSpecDto> mappingSpec = objectMapper.convertValue(
                        dsData.get("mappingSpec"),
                        new TypeReference<Map<String, ColumnMappingSpecDto>>() {});
                if (mappingSpec == null) mappingSpec = Map.of();

                // Rewrite S3 paths for FILE/IMAGE columns under the acquirer's tenant
                fileCloneService.rewriteFilePaths(sourceType, sourceConfig, itemList, mappingSpec,
                        tenantId, publicationId.toString(), organizationId);

                Map<String, Object> createReq = new HashMap<>();
                createReq.put("name", dsData.get("name"));
                createReq.put("description", dsData.get("description"));
                createReq.put("sourceType", sourceType);
                createReq.put("sourceConfig", sourceConfig);
                createReq.put("columnOrder", dsData.getOrDefault("columnOrder", List.of()));
                createReq.put("mappingSpec", dsData.get("mappingSpec"));
                createReq.put("sourcePublicationId", publicationId.toString());
                if (normalizeScope(organizationId) != null) {
                    createReq.put("organizationId", normalizeScope(organizationId));
                }

                DataSourceDto saved = dataSourceClient.createFromSnapshot(createReq, tenantId);
                if (saved != null) {
                    dsMapping.put(oldDsId, saved.id().toString());

                    // Inject items (with rewritten file paths)
                    if (!itemList.isEmpty()) {
                        int count = dataSourceClient.bulkInsertItems(saved.id(), itemList, tenantId);
                        if (count > 0) {
                            breakdownService.increment(tenantId, "DATA", count * 200L, count);
                        }
                    }
                }
            }
        }

        // 4. Clone agents recursively (leaf-first via post-order)
        UUID rootAgentId = cloneAgentsRecursively(snapshot, tenantId, publicationId,
                normalizeScope(organizationId), agentMapping, workflowMapping, interfaceMapping,
                dsMapping, new HashSet<>(), 0);

        if (rootAgentId == null) {
            throw new RuntimeException("Failed to clone root agent");
        }
        return rootAgentId;
    }

    /**
     * Best-effort compensating cleanup when {@link #acquireAgentPublication}
     * fails partway through the bottom-up clone. Walks the four mappings
     * (workflows, interfaces, datasources, agents) and calls each owning
     * service to drop the partially-created rows in the acquirer's tenant,
     * plus the storage counter delta from datasource items. Failures here
     * are logged but never rethrown - the original cause is what the caller
     * gets to see.
     */
    private void compensateAgentAcquireFailure(UUID publicationId, String tenantId, String organizationId,
                                                Map<String, String> workflowMapping,
                                                Map<String, String> interfaceMapping,
                                                Map<String, String> dsMapping,
                                                Map<String, String> agentMapping) {
        // 1. Wipe any cloned workflow rows (recursive: clone may have created
        //    sub-workflow rows). Runs first, rows dropped after - a leftover
        //    clone keeps the (org, publication) bucket occupied and blocks
        //    re-acquisition. Reuses the same orchestrator helpers as the
        //    workflow-acquire compensation.
        // SCOPED to the exact workflow ids THIS acquisition created (workflowMapping values) - never
        // an org-wide findAllBySourcePublication sweep, which swept in (and deleted) a CONCURRENT
        // acquisition's just-created rows (the winner) when two first-time acquires of the same agent
        // publication raced. Mirrors the workflow-acquire compensation fix (918ac1d53).
        String orgScope = normalizeScope(organizationId);
        for (String clonedWorkflowId : workflowMapping.values()) {
            if (clonedWorkflowId == null) continue;
            try {
                UUID cloneId = UUID.fromString(clonedWorkflowId);
                orchestratorClient.cleanupApplicationRuns(cloneId, publicationId.toString(), tenantId);
                orchestratorClient.deleteAcquiredWorkflow(cloneId, publicationId, tenantId, orgScope);
            } catch (Exception e) {
                logger.warn("[AgentAcquire/compensate] workflow cleanup failed for clone={}: {}",
                        clonedWorkflowId, e.getMessage());
            }
        }

        // 2. Standalone interfaces created in the acquirer's tenant.
        for (String newId : interfaceMapping.values()) {
            try {
                interfaceClient.deleteInterface(UUID.fromString(newId), tenantId, normalizeScope(organizationId));
            } catch (Exception e) {
                logger.warn("[AgentAcquire/compensate] deleteInterface({}) failed: {}", newId, e.getMessage());
            }
        }

        // 3. Standalone datasources + their items, plus refund the storage
        //    counter delta accumulated at line 929 (count * 200L bytes).
        for (String newId : dsMapping.values()) {
            try {
                Long dsId = Long.parseLong(newId);
                dataSourceClient.deleteDataSource(dsId, tenantId, normalizeScope(organizationId));
            } catch (Exception e) {
                logger.warn("[AgentAcquire/compensate] deleteDataSource({}) failed: {}", newId, e.getMessage());
            }
        }
        // Storage counter is best-effort decrement; if it under-counts here,
        // the next breakdown sweep reconciles. Accept drift over double-bill.

        // 4. Cloned agents (post-order so leaves first).
        for (String newId : agentMapping.values()) {
            try {
                agentClient.deleteAgent(UUID.fromString(newId), tenantId, normalizeScope(organizationId));
            } catch (Exception e) {
                logger.warn("[AgentAcquire/compensate] deleteAgentConfig({}) failed: {}", newId, e.getMessage());
            }
        }

        logger.info("[AgentAcquire/compensate] cleanup attempted: workflows={}, interfaces={}, ds={}, agents={}",
                workflowMapping.size(), interfaceMapping.size(), dsMapping.size(), agentMapping.size());
    }

    /**
     * Recursively clone agents from the snapshot in post-order (leaves first).
     * Returns the new root agent UUID. Uses visitedAgentIds for cycle detection.
     */
    @SuppressWarnings("unchecked")
    private UUID cloneAgentsRecursively(Map<String, Object> snapshot, String tenantId, UUID publicationId,
                                         String organizationId,
                                         Map<String, String> agentMapping,
                                         Map<String, String> workflowMapping,
                                         Map<String, String> interfaceMapping,
                                         Map<String, String> dsMapping,
                                         Set<String> visitedAgentIds,
                                         int depth) {
        if (depth > MAX_AGENT_DEPTH) {
            logger.warn("Max clone depth ({}) exceeded, stopping recursion", MAX_AGENT_DEPTH);
            return null;
        }
        // Cycle detection: extract agent ID early to check before recursing
        Object agentDataRaw = snapshot.get("agent");
        String oldAgentId = null;
        if (agentDataRaw instanceof Map<?, ?> ad && ad.get("id") != null) {
            oldAgentId = ad.get("id").toString();
            if (!visitedAgentIds.add(oldAgentId)) {
                logger.warn("Cycle detected in agent snapshot at {}, skipping", oldAgentId);
                return null;
            }
        }

        // First, recurse into sub-agents
        Object subAgentsRaw = snapshot.get("subAgents");
        if (subAgentsRaw instanceof Map<?, ?> subAgents) {
            for (Map.Entry<?, ?> entry : subAgents.entrySet()) {
                String oldSubAgentId = entry.getKey().toString();
                if (!(entry.getValue() instanceof Map<?, ?> subSnapshot)) continue;

                UUID clonedSubId = cloneAgentsRecursively((Map<String, Object>) subSnapshot, tenantId, publicationId,
                        organizationId, agentMapping, workflowMapping, interfaceMapping, dsMapping,
                        visitedAgentIds, depth + 1);
                if (clonedSubId != null) {
                    agentMapping.put(oldSubAgentId, clonedSubId.toString());
                }
            }
        }

        // Now clone this agent
        if (!(agentDataRaw instanceof Map<?, ?> agentData)) return null;

        Map<String, Object> cloneRequest = new HashMap<>();
        cloneRequest.put("tenantId", tenantId);
        cloneRequest.put("publicationId", publicationId.toString());
        if (normalizeScope(organizationId) != null) {
            cloneRequest.put("organizationId", normalizeScope(organizationId));
        }
        cloneRequest.put("name", agentData.get("name"));
        cloneRequest.put("description", agentData.get("description"));
        cloneRequest.put("systemPrompt", agentData.get("systemPrompt"));
        cloneRequest.put("modelProvider", agentData.get("modelProvider"));
        cloneRequest.put("modelName", agentData.get("modelName"));
        cloneRequest.put("temperature", agentData.get("temperature"));
        cloneRequest.put("maxTokens", agentData.get("maxTokens"));
        cloneRequest.put("maxIterations", agentData.get("maxIterations"));
        cloneRequest.put("executionTimeout", agentData.get("executionTimeout"));
        cloneRequest.put("config", agentData.get("config"));
        // Avatar: copy an uploaded/AI file into the ACQUIRER's storage so the clone
        // survives the publisher deleting theirs (presets/http pass through).
        String snapshotAvatarUrl = agentData.get("avatarUrl") != null ? agentData.get("avatarUrl").toString() : null;
        cloneRequest.put("avatarUrl", avatarFileCloneService != null
                ? avatarFileCloneService.cloneForTenant(snapshotAvatarUrl, tenantId, normalizeScope(organizationId))
                : com.apimarketplace.publication.utils.AvatarUrlPolicy.publishable(snapshotAvatarUrl));
        cloneRequest.put("creditBudget", agentData.get("creditBudget"));
        cloneRequest.put("budgetResetMode", agentData.get("budgetResetMode"));

        // Remap dataSourceId
        Object dsIdRaw = agentData.get("dataSourceId");
        if (dsIdRaw instanceof Number n && n.longValue() > 0) {
            String oldDsId = String.valueOf(n.longValue());
            String newDsId = dsMapping.get(oldDsId);
            if (newDsId != null) {
                cloneRequest.put("dataSourceId", Long.parseLong(newDsId));
            }
        }

        // Include toolsConfig (will be remapped after creation)
        Object toolsConfig = agentData.get("toolsConfig");
        if (toolsConfig instanceof Map) {
            cloneRequest.put("toolsConfig", toolsConfig);
        }

        // Skills
        Object skillsRaw = agentData.get("skills");
        if (skillsRaw instanceof List) {
            cloneRequest.put("skills", skillsRaw);
        }

        Map<String, Object> result = agentClient.cloneFromSnapshot(cloneRequest);
        if (result == null || result.get("agentId") == null) {
            logger.error("Failed to clone agent {} for tenant {}", oldAgentId, tenantId);
            return null;
        }

        String newAgentIdStr = (String) result.get("agentId");
        UUID newAgentId = UUID.fromString(newAgentIdStr);

        if (oldAgentId != null) {
            agentMapping.put(oldAgentId, newAgentIdStr);
        }

        // Remap toolsConfig resource IDs (workflows mapping covers both "workflows" and "applications" keys)
        Map<String, Object> mappings = new HashMap<>();
        mappings.put("tables", dsMapping);
        mappings.put("interfaces", interfaceMapping);
        mappings.put("agents", agentMapping);
        mappings.put("workflows", workflowMapping);
        mappings.put("applications", workflowMapping);
        try {
            agentClient.remapToolsConfig(newAgentId, mappings);
        } catch (Exception e) {
            logger.error("Failed to remap toolsConfig for cloned agent {}: {}", newAgentIdStr, e.getMessage(), e);
            throw new RuntimeException("Failed to remap toolsConfig for cloned agent " + newAgentIdStr, e);
        }

        // Create webhook if the snapshot had one (new token auto-generated by agent-service)
        Object webhookConfigRaw = agentData.get("webhookConfig");
        if (webhookConfigRaw instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> wc = (Map<String, Object>) webhookConfigRaw;
            Map<String, Object> webhookReq = new HashMap<>();
            webhookReq.put("httpMethod", wc.getOrDefault("httpMethod", "POST"));
            webhookReq.put("memoryEnabled", wc.getOrDefault("memoryEnabled", false));
            if (normalizeScope(organizationId) != null) {
                webhookReq.put("organizationId", normalizeScope(organizationId));
            }
            try {
                agentClient.createOrUpdateWebhook(newAgentId, webhookReq, tenantId);
                logger.info("Created webhook for acquired agent {}", newAgentIdStr);
            } catch (Exception e) {
                logger.warn("Failed to create webhook for acquired agent {}: {}", newAgentIdStr, e.getMessage());
            }
        }

        // Create schedule if the snapshot had one
        Object scheduleConfigRaw = agentData.get("scheduleConfig");
        if (scheduleConfigRaw instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> sc = (Map<String, Object>) scheduleConfigRaw;
            if (sc.get("cronExpression") != null) {
                Map<String, Object> scheduleReq = new HashMap<>();
                scheduleReq.put("cron", sc.get("cronExpression"));
                scheduleReq.put("timezone", sc.getOrDefault("timezone", "UTC"));
                scheduleReq.put("maxExecutions", sc.get("maxExecutions"));
                scheduleReq.put("schedulePrompt", sc.get("schedulePrompt"));
                scheduleReq.put("withMemory", sc.getOrDefault("withMemory", false));
                scheduleReq.put("enabled", false); // Start disabled - acquirer enables when ready
                if (normalizeScope(organizationId) != null) {
                    scheduleReq.put("organizationId", normalizeScope(organizationId));
                }
                try {
                    agentClient.createOrUpdateSchedule(newAgentId, scheduleReq, tenantId);
                    logger.info("Created schedule for acquired agent {}", newAgentIdStr);
                } catch (Exception e) {
                    logger.warn("Failed to create schedule for acquired agent {}: {}", newAgentIdStr, e.getMessage());
                }
            }
        }

        logger.info("Cloned agent {} -> {} for tenant {}", oldAgentId, newAgentIdStr, tenantId);
        return newAgentId;
    }

    // ========================================================================
    // Unpublish
    // ========================================================================

    /** Personal-scope overload - see {@link #unpublishAgent(UUID, String, String)}. */
    public void unpublishAgent(UUID agentConfigId, String tenantId) {
        unpublishAgent(agentConfigId, tenantId, null);
    }

    /**
     * Unpublish an agent. Org-aware: every teammate of the owning organization
     * may unpublish an ORG-owned publication; for USER-owned rows only the
     * owner may mutate.
     */
    public void unpublishAgent(UUID agentConfigId, String tenantId, String organizationId) {
        WorkflowPublicationEntity publication = publicationRepository.findByAgentConfigId(agentConfigId)
                .orElseThrow(() -> new IllegalArgumentException("Agent publication not found: " + agentConfigId));

        if (!workflowPublicationService.isCallerInOwnerScope(publication, tenantId, organizationId)) {
            throw new IllegalArgumentException("Not the publisher");
        }

        if (publication.getStatus() == PublicationStatus.PENDING_REVIEW) {
            throw new PublicationPendingReviewException("Cannot unpublish while publication is pending review. Please wait for admin approval.");
        }

        publication.setStatus(PublicationStatus.INACTIVE);
        publicationRepository.save(publication);
        logger.info("Unpublished agent {}", agentConfigId);
    }

    // ========================================================================
    // Query helpers
    // ========================================================================

    @Transactional(readOnly = true)
    public boolean isAgentPublished(UUID agentConfigId) {
        Optional<WorkflowPublicationEntity> pub = publicationRepository.findByAgentConfigId(agentConfigId);
        return pub.isPresent() && pub.get().getStatus() == PublicationStatus.ACTIVE;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getAgentPublicationStatus(UUID agentConfigId) {
        Optional<WorkflowPublicationEntity> pub = publicationRepository.findByAgentConfigId(agentConfigId);
        if (pub.isEmpty()) {
            return Map.of("exists", false);
        }
        WorkflowPublicationEntity p = pub.get();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exists", true);
        out.put("status", p.getStatus().name());
        out.put("published", p.getStatus() == PublicationStatus.ACTIVE);
        if (p.getStatus() == PublicationStatus.REJECTED && p.getRejectionReason() != null) {
            out.put("rejectionReason", p.getRejectionReason());
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getAgentSnapshot(UUID publicationId) {
        WorkflowPublicationEntity publication = publicationRepository.findById(publicationId)
                .orElseThrow(() -> new IllegalArgumentException("Publication not found"));
        if (publication.getPublicationType() != PublicationType.AGENT) {
            throw new IllegalArgumentException("Not an agent publication");
        }
        Map<String, Object> snapshot = publication.getAgentSnapshot();
        return snapshot != null ? new LinkedHashMap<>(snapshot) : null;
    }

    // ========================================================================
    // Counting helpers
    // ========================================================================

    /**
     * Collect interface IDs already present inside workflow plans (to avoid storing them twice as standalone).
     */
    @SuppressWarnings("unchecked")
    private Set<String> collectInterfaceIdsFromWorkflows(Map<String, Object> workflows) {
        Set<String> ids = new HashSet<>();
        for (Object val : workflows.values()) {
            if (!(val instanceof Map<?, ?> wf)) continue;
            Object plan = wf.get("plan");
            if (!(plan instanceof Map<?, ?> planMap)) continue;
            Object ifaces = planMap.get("interfaces");
            if (ifaces instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> node && node.get("id") != null) {
                        ids.add(node.get("id").toString());
                    }
                }
            }
        }
        return ids;
    }

    /**
     * Collect datasource IDs already present inside workflow plans (to avoid storing them twice as standalone).
     */
    @SuppressWarnings("unchecked")
    private Set<String> collectDatasourceIdsFromWorkflows(Map<String, Object> workflows) {
        Set<String> ids = new HashSet<>();
        for (Object val : workflows.values()) {
            if (!(val instanceof Map<?, ?> wf)) continue;
            Object plan = wf.get("plan");
            if (!(plan instanceof Map<?, ?> planMap)) continue;
            Object tables = planMap.get("tables");
            if (tables instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> node && node.get("dataSourceId") != null) {
                        ids.add(node.get("dataSourceId").toString());
                    }
                }
            }
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    private int countSubAgents(Map<String, Object> snapshot) {
        return countSubAgents(snapshot, 0);
    }

    @SuppressWarnings("unchecked")
    private int countSubAgents(Map<String, Object> snapshot, int depth) {
        if (depth > MAX_AGENT_DEPTH) return 0;
        Object subAgentsRaw = snapshot.get("subAgents");
        if (!(subAgentsRaw instanceof Map<?, ?> subAgents)) return 0;
        int count = subAgents.size();
        for (Object val : subAgents.values()) {
            if (val instanceof Map<?, ?> subSnapshot) {
                count += countSubAgents((Map<String, Object>) subSnapshot, depth + 1);
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private int countSkills(Map<String, Object> snapshot) {
        return countSkills(snapshot, 0);
    }

    @SuppressWarnings("unchecked")
    private int countSkills(Map<String, Object> snapshot, int depth) {
        if (depth > MAX_AGENT_DEPTH) return 0;
        int count = 0;
        Object agentData = snapshot.get("agent");
        if (agentData instanceof Map<?, ?> agent) {
            Object skills = agent.get("skills");
            if (skills instanceof List<?> list) {
                count += list.size();
            }
        }
        Object subAgentsRaw = snapshot.get("subAgents");
        if (subAgentsRaw instanceof Map<?, ?> subAgents) {
            for (Object val : subAgents.values()) {
                if (val instanceof Map<?, ?> subSnapshot) {
                    count += countSkills((Map<String, Object>) subSnapshot, depth + 1);
                }
            }
        }
        return count;
    }

    @SuppressWarnings("unchecked")
    private int countInterfaces(Map<String, Object> snapshot) {
        Set<String> seen = new HashSet<>();
        collectInterfaceIds(snapshot, seen, 0);
        return seen.size();
    }

    @SuppressWarnings("unchecked")
    private void collectInterfaceIds(Map<String, Object> snapshot, Set<String> seen, int depth) {
        if (depth > MAX_AGENT_DEPTH) return;
        // Standalone interfaces on the agent (Map<interfaceId, data>)
        Object interfaces = snapshot.get("interfaces");
        if (interfaces instanceof Map<?, ?> ifMap) {
            ifMap.keySet().forEach(k -> seen.add(k.toString()));
        }
        // Interfaces inside referenced workflows (List<{id: ...}>)
        Object workflowsRaw = snapshot.get("workflows");
        if (workflowsRaw instanceof Map<?, ?> workflows) {
            for (Object val : workflows.values()) {
                if (val instanceof Map<?, ?> wfSnapshot) {
                    Object plan = wfSnapshot.get("plan");
                    if (plan instanceof Map<?, ?> planMap) {
                        Object planIfaces = planMap.get("interfaces");
                        if (planIfaces instanceof List<?> list) {
                            for (Object item : list) {
                                if (item instanceof Map<?, ?> ifNode && ifNode.get("id") != null) {
                                    seen.add(ifNode.get("id").toString());
                                }
                            }
                        }
                    }
                }
            }
        }
        // Recurse into sub-agents (same seen set → global dedup)
        Object subAgentsRaw = snapshot.get("subAgents");
        if (subAgentsRaw instanceof Map<?, ?> subAgents) {
            for (Object val : subAgents.values()) {
                if (val instanceof Map<?, ?> subSnapshot) {
                    collectInterfaceIds((Map<String, Object>) subSnapshot, seen, depth + 1);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private int countDatasources(Map<String, Object> snapshot) {
        Set<String> seen = new HashSet<>();
        collectDatasourceIds(snapshot, seen, 0);
        return seen.size();
    }

    @SuppressWarnings("unchecked")
    private void collectDatasourceIds(Map<String, Object> snapshot, Set<String> seen, int depth) {
        if (depth > MAX_AGENT_DEPTH) return;
        // Standalone datasources on the agent (Map<dsId, data>)
        Object datasources = snapshot.get("datasources");
        if (datasources instanceof Map<?, ?> dsMap) {
            dsMap.keySet().forEach(k -> seen.add(k.toString()));
        }
        // Datasources inside referenced workflows (List<{dataSourceId: ...}>)
        Object workflowsRaw = snapshot.get("workflows");
        if (workflowsRaw instanceof Map<?, ?> workflows) {
            for (Object val : workflows.values()) {
                if (val instanceof Map<?, ?> wfSnapshot) {
                    Object plan = wfSnapshot.get("plan");
                    if (plan instanceof Map<?, ?> planMap) {
                        Object planTables = planMap.get("tables");
                        if (planTables instanceof List<?> list) {
                            for (Object item : list) {
                                if (item instanceof Map<?, ?> tblNode && tblNode.get("dataSourceId") != null) {
                                    seen.add(tblNode.get("dataSourceId").toString());
                                }
                            }
                        }
                    }
                }
            }
        }
        // Recurse into sub-agents (same seen set → global dedup)
        Object subAgentsRaw = snapshot.get("subAgents");
        if (subAgentsRaw instanceof Map<?, ?> subAgents) {
            for (Object val : subAgents.values()) {
                if (val instanceof Map<?, ?> subSnapshot) {
                    collectDatasourceIds((Map<String, Object>) subSnapshot, seen, depth + 1);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private int countWorkflows(Map<String, Object> snapshot) {
        return countWorkflows(snapshot, 0);
    }

    @SuppressWarnings("unchecked")
    private int countWorkflows(Map<String, Object> snapshot, int depth) {
        if (depth > MAX_AGENT_DEPTH) return 0;
        int count = 0;
        Object workflows = snapshot.get("workflows");
        if (workflows instanceof Map<?, ?> wfMap) {
            count += wfMap.size();
        }
        // Recurse into sub-agents
        Object subAgentsRaw = snapshot.get("subAgents");
        if (subAgentsRaw instanceof Map<?, ?> subAgents) {
            for (Object val : subAgents.values()) {
                if (val instanceof Map<?, ?> subSnapshot) {
                    count += countWorkflows((Map<String, Object>) subSnapshot, depth + 1);
                }
            }
        }
        return count;
    }

    private static String normalizeScope(String organizationId) {
        return organizationId != null && !organizationId.isBlank() ? organizationId : null;
    }

    /**
     * Read a CONTENT field (name/description/template) from a publication snapshot map,
     * refusing to coerce a non-text value (Number/Boolean) into a string. A numeric value
     * here is the fingerprint of the {@code getString().toString()} corruption class that put
     * a bare number into a content field - fail loud instead of cloning garbage.
     */
    private static String asContentText(Object value, String defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof CharSequence cs) return cs.toString();
        throw new IllegalArgumentException(
                "interface content field must be text, got " + value.getClass().getSimpleName() + ": " + value);
    }

    private boolean hasReceiptInScope(String tenantId, UUID publicationId, String organizationId) {
        // Post-V261: organizationId is always present (gateway injects X-Organization-ID;
        // personal-workspace users carry their personal org UUID). The IS-NULL branch is dead.
        String normalizedOrgId = normalizeScope(organizationId);
        if (normalizedOrgId == null) {
            throw new IllegalArgumentException(
                    "organizationId required after V261 (tenantId=" + tenantId
                            + ", publicationId=" + publicationId + ")");
        }
        return receiptRepository.existsByOrganizationIdAndPublicationId(normalizedOrgId, publicationId);
    }

    private long countReceiptsInScope(String tenantId, String organizationId) {
        // Post-V261 invariant: organizationId must be present. See hasReceiptInScope above.
        String normalizedOrgId = normalizeScope(organizationId);
        if (normalizedOrgId == null) {
            throw new IllegalArgumentException(
                    "organizationId required after V261 (tenantId=" + tenantId + ")");
        }
        return receiptRepository.countByOrganizationId(normalizedOrgId);
    }

    private Map<String, Object> cloneWorkflowSnapshot(Map<String, Object> plan,
                                                      String tenantId,
                                                      UUID publicationId,
                                                      Map<String, Object> workflowData,
                                                      String organizationId) {
        String title = workflowData.get("name") != null
                ? workflowData.get("name").toString()
                : "Acquired Workflow";
        String description = workflowData.get("description") != null
                ? workflowData.get("description").toString()
                : null;
        String normalizedOrgId = normalizeScope(organizationId);
        // An AGENT publication has no application root: every cloned workflow is
        // a standard WORKFLOW row. Stamping them APPLICATION would collide on the
        // V268 unique index as soon as the snapshot carries 2+ workflows (or one
        // workflow whose plan nests a sub-workflow).
        return snapshotCloneService.cloneFromSnapshot(
                plan, tenantId, publicationId, title, description, null,
                normalizedOrgId, SnapshotCloneService.CLONE_TYPE_WORKFLOW);
    }

    private boolean isOwnPublication(WorkflowPublicationEntity publication,
                                     String tenantId,
                                     String organizationId) {
        if (publication == null || tenantId == null) {
            return false;
        }
        if (tenantId.equals(publication.getPublisherId())) {
            return true;
        }
        if (!publication.hasAssignedOwnerScope()) {
            return false;
        }
        String normalizedOrgId = normalizeScope(organizationId);
        return publication.getOwnerType() == WorkflowPublicationEntity.OwnerType.ORG
                && normalizedOrgId != null
                && normalizedOrgId.equals(publication.getOwnerId());
    }
}
