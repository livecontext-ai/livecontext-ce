package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.client.dto.*;
import com.apimarketplace.common.recentactivity.RecentActivityItemDto;
import com.apimarketplace.common.recentactivity.RecentActivityScopeResultDto;
import com.apimarketplace.common.recentactivity.ResourceKind;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.scope.TolerantScope;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.AgentSkillEntity;
import com.apimarketplace.agent.domain.AgentWebhookTokenEntity;
import com.apimarketplace.agent.domain.SkillEntity;
import com.apimarketplace.agent.domain.SkillFolderEntity;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentSkillRepository;
import com.apimarketplace.agent.repository.AgentWebhookTokenRepository;
import com.apimarketplace.agent.repository.SkillRepository;
import com.apimarketplace.agent.service.AgentObservabilityService;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.SkillFolderService;
import com.apimarketplace.agent.service.SkillService;
import com.apimarketplace.agent.service.SkillService.SkillAssignment;
import com.apimarketplace.agent.service.execution.AgentActivitySnapshotService;
import com.apimarketplace.agent.service.execution.ConversationStopCascadeService;
import com.apimarketplace.agent.util.RequestParameterExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Internal API controller for inter-service communication.
 * These endpoints are called by other services (orchestrator, catalog, conversation)
 * via the AgentClient HTTP client. No gateway authentication needed -- internal network only.
 *
 * <p>Endpoint prefix: /api/internal/agents
 */
@RestController
@RequestMapping("/api/internal/agents")
public class InternalAgentController {

    private static final Logger logger = LoggerFactory.getLogger(InternalAgentController.class);

    private final AgentService agentService;
    private final AgentRepository agentRepository;
    private final AgentSkillRepository agentSkillRepository;
    private final AgentWebhookTokenRepository webhookTokenRepository;
    private final SkillRepository skillRepository;
    private final SkillService skillService;
    private final SkillFolderService skillFolderService;
    private final AgentObservabilityService observabilityService;
    private final TenantResolver tenantResolver;
    private final RequestParameterExtractor extractor;
    private final ConversationStopCascadeService conversationStopCascadeService;
    private final AgentActivitySnapshotService agentActivitySnapshotService;

    public InternalAgentController(
            AgentService agentService,
            AgentRepository agentRepository,
            AgentSkillRepository agentSkillRepository,
            AgentWebhookTokenRepository webhookTokenRepository,
            SkillRepository skillRepository,
            SkillService skillService,
            SkillFolderService skillFolderService,
            AgentObservabilityService observabilityService,
            TenantResolver tenantResolver,
            RequestParameterExtractor extractor,
            ConversationStopCascadeService conversationStopCascadeService,
            AgentActivitySnapshotService agentActivitySnapshotService) {
        this.agentService = agentService;
        this.agentRepository = agentRepository;
        this.agentSkillRepository = agentSkillRepository;
        this.webhookTokenRepository = webhookTokenRepository;
        this.skillRepository = skillRepository;
        this.skillService = skillService;
        this.skillFolderService = skillFolderService;
        this.observabilityService = observabilityService;
        this.tenantResolver = tenantResolver;
        this.extractor = extractor;
        this.conversationStopCascadeService = conversationStopCascadeService;
        this.agentActivitySnapshotService = agentActivitySnapshotService;
    }

    // ========== Access Check (WebSocket channel authorization) ==========

    /**
     * Check if a user has access to an agent (for WebSocket channel authorization).
     * Used by gateway ChannelAuthorizer for agent:activity:{agentId} channels.
     * GET /api/internal/agents/{id}/access?userId={userId}
     */
    @GetMapping("/{id}/access")
    @TolerantScope(reason = "Gateway ChannelAuthorizer for ws:agent:{agentId} - gateway has already validated session.organizationId is a real membership for userId, so owner-OR-org access lets the user subscribe to channels for their agents across workspaces")
    public ResponseEntity<Boolean> checkAccess(
            @PathVariable("id") UUID id,
            @RequestParam("userId") String userId,
            @RequestParam(value = "orgId", required = false) String orgId) {
        // 2026-05-18 - aligned with orchestrator's InternalAccessController
        // pattern via ScopeGuard. Tolerant by design (WS channel authorizer
        // pre-gated upstream); see @TolerantScope.reason.
        return agentRepository.findById(id)
                .map(a -> ResponseEntity.ok(ScopeGuard.isInOwnerOrOrgScope(
                        userId, orgId, a.getTenantId(), a.getOrganizationId())))
                .orElse(ResponseEntity.ok(false));
    }

    // ========== Activity Snapshot (WebSocket late-subscribe replay) ==========

    /**
     * Re-publish a snapshot of this agent's currently-running executions to the
     * {@code ws:agent:activity:{id}} channel. Triggered by the gateway when a client
     * subscribes with {@code requestSnapshot=true} (mirrors workflow:run / conversation
     * snapshots). The channel access was already authorized upstream by the gateway's
     * {@code /{id}/access} check before this fires, so no re-authorization here - this
     * call only re-emits state the subscriber is already cleared to see.
     *
     * <p>Solves the timing gap where the only activity events for a bridge/CLI agent
     * are {@code execution_started}/{@code execution_completed}: a client that subscribed
     * AFTER the start, with no tool events in between, would otherwise stay idle for the
     * whole run. Re-emitting {@code execution_started} for each RUNNING execution lets it
     * learn the agent is busy. Idempotent for clients already tracking the execution
     * (the store preserves their live counters on a same-execution re-announcement).
     *
     * <p>POST /api/internal/agents/{id}/activity-snapshot
     */
    @PostMapping("/{id}/activity-snapshot")
    public ResponseEntity<Void> activitySnapshot(@PathVariable("id") UUID id) {
        agentActivitySnapshotService.publishRunningSnapshot(id);
        return ResponseEntity.ok().build();
    }

    // ========== Internal CRUD endpoints (mirror public API for inter-service calls) ==========

    /**
     * Get an agent by ID (inter-service equivalent of GET /api/agents/{id}).
     * GET /api/internal/agents/{id}/get
     */
    @GetMapping("/{id}/get")
    public ResponseEntity<AgentDto> getAgentInternal(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        // 2026-05-21 - read org context explicitly (defense-in-depth on top
        // of AgentService.getAgent self-heal). Non-OWNER org members hit
        // this from in-process callers that forward X-Organization-ID/Role.
        String orgId = httpRequest.getHeader("X-Organization-ID");
        String orgRole = httpRequest.getHeader("X-Organization-Role");
        return agentService.getAgent(id, tenantId, orgId, orgRole)
            .map(agent -> ResponseEntity.ok(toDto(agent)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * List all agents for a tenant (inter-service equivalent of GET /api/agents).
     * Supports org-based access filtering via X-Organization-ID / X-Organization-Role headers.
     * GET /api/internal/agents/all
     */
    @GetMapping("/all")
    public ResponseEntity<List<AgentDto>> getAllAgents(HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        String orgId = httpRequest.getHeader("X-Organization-ID");
        String orgRole = httpRequest.getHeader("X-Organization-Role");
        List<AgentEntity> agents = agentService.listAgents(tenantId, orgId, orgRole);
        return ResponseEntity.ok(agents.stream().map(this::toDto).toList());
    }

    /**
     * Recent-activity fan-out branch (agents + skills union). Returns the
     * top-{@value #RECENT_LIMIT} most recently-edited rows in the caller's
     * active workspace plus the peer-scope count. Called by orchestrator's
     * {@code RecentActivityAggregatorService} via
     * {@code AgentClient.getRecentAgentResources}.
     *
     * <p>Both agent + skill rows are queried with the same limit, then merged
     * + sorted in Java and capped at {@code RECENT_LIMIT}. Net: ONE downstream
     * RPC for both kinds (avoids the 4th fan-out branch the v3 plan had).
     *
     * <p>Scope routing matches PR27/PR27.2 strict-pair pattern. Backed by V236
     * + V237 partial indexes ({@code idx_agents_*} + {@code idx_skills_*}).
     *
     * <p>{@code actorId} = {@code tenantId} on each row (consistency with the
     * other fan-out branches - see {@link RecentActivityItemDto} javadoc on
     * the {@code created_by}/{@code updated_by} note).
     */
    private static final int RECENT_LIMIT = 50;

    @GetMapping("/recent-activity")
    public ResponseEntity<RecentActivityScopeResultDto> getRecentActivity(HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        String orgId = httpRequest.getHeader("X-Organization-ID");

        TenantResolver.requireOrgId(orgId);
        List<AgentEntity> agents = agentRepository.findRecentByOrganizationIdStrict(orgId, PageRequest.of(0, RECENT_LIMIT));
        List<SkillEntity> skills = skillRepository.findRecentByOrganizationIdStrict(orgId, PageRequest.of(0, RECENT_LIMIT));
        // Post-V261 peer scope (personal-only across both kinds) no longer exists -
        // every user-scoped row is org-tagged, so the cross-workspace count is N/A.
        int peerScopeCount = 0;

        List<RecentActivityItemDto> items = new ArrayList<>(agents.size() + skills.size());
        for (AgentEntity a : agents) {
            items.add(RecentActivityItemDto.builder()
                    .kind(ResourceKind.AGENT)
                    .resourceId(a.getId().toString())
                    .name(a.getName())
                    .lastEditedAt(a.getUpdatedAt())
                    .actorId(a.getTenantId())
                    .build());
        }
        for (SkillEntity s : skills) {
            items.add(RecentActivityItemDto.builder()
                    .kind(ResourceKind.SKILL)
                    .resourceId(s.getId().toString())
                    .name(s.getName())
                    .lastEditedAt(s.getUpdatedAt())
                    .actorId(s.getTenantId())
                    .build());
        }
        // Sort by updatedAt DESC (NULL-safe - entities can't have null updatedAt
        // per @Column nullable=false but defensive on read).
        items.sort((x, y) -> {
            if (x.lastEditedAt() == null) return 1;
            if (y.lastEditedAt() == null) return -1;
            return y.lastEditedAt().compareTo(x.lastEditedAt());
        });
        if (items.size() > RECENT_LIMIT) {
            items = items.subList(0, RECENT_LIMIT);
        }

        return ResponseEntity.ok(new RecentActivityScopeResultDto(items, peerScopeCount));
    }

    /**
     * Batch lookup of every active agent webhook token owned by a tenant - backs the
     * orchestrator dashboard's "active automations" widget. Single round-trip,
     * eliminates the per-agent N+1 that would otherwise be needed to enumerate
     * webhooks across the fleet.
     *
     * <p>Filter is {@code is_active = true}; agents without a webhook row simply
     * don't appear in the result. Schedules live in trigger-service and are fetched
     * via TriggerClient, not this endpoint.
     *
     * GET /api/internal/agents/active-webhook-tokens
     */
    @GetMapping("/active-webhook-tokens")
    public ResponseEntity<List<ActiveAgentWebhookTokenDto>> getActiveAgentWebhookTokens(HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        List<AgentWebhookTokenEntity> tokens = webhookTokenRepository.findActiveByTenantId(tenantId);
        List<ActiveAgentWebhookTokenDto> dtos = tokens.stream()
                .map(t -> new ActiveAgentWebhookTokenDto(
                        t.getAgentId(),
                        t.getToken(),
                        t.getHttpMethod(),
                        t.getIsActive(),
                        t.getUpdatedAt()))
                .toList();
        return ResponseEntity.ok(dtos);
    }

    /**
     * Create an agent (inter-service equivalent of POST /api/agents).
     * POST /api/internal/agents/create
     */
    @PostMapping("/create")
    public ResponseEntity<AgentDto> createAgentInternal(
            @RequestBody Map<String, Object> request,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);

        AgentEntity created = agentService.createAgent(
            tenantId,
            extractor.getString(request, "name"),
            extractor.getString(request, "description"),
            extractor.getString(request, "systemPrompt"),
            extractor.getString(request, "modelProvider"),
            extractor.getString(request, "modelName"),
            extractor.getBigDecimal(request, "temperature"),
            extractor.getInteger(request, "maxTokens"),
            extractor.getInteger(request, "maxIterations"),
            extractor.getInteger(request, "executionTimeout"),
            extractor.getMap(request, "toolsConfig"),
            extractor.getUUID(request, "workflowId"),
            extractor.getLong(request, "dataSourceId"),
            extractor.getUUID(request, "conversationId"),
            extractor.getMap(request, "config"),
            extractor.getString(request, "avatarUrl"),
            extractor.getBoolean(request, "isPublic"),
            extractor.getBoolean(request, "isActive"),
            organizationId,
            extractor.getBigDecimal(request, "creditBudget"),
            extractor.getString(request, "budgetResetMode"),
            extractor.extractIntegerMap(request, GUARD_OVERRIDE_KEYS)
        );

        return ResponseEntity.ok(toDto(created));
    }

    private static final List<String> GUARD_OVERRIDE_KEYS = List.of(
        com.apimarketplace.agent.config.GuardOverrides.MAX_PER_RESOURCE_PER_TURN,
        com.apimarketplace.agent.config.GuardOverrides.LOOP_IDENTICAL_STOP,
        com.apimarketplace.agent.config.GuardOverrides.LOOP_CONSECUTIVE_STOP);

    /**
     * Update an agent (inter-service equivalent of PUT /api/agents/{id}).
     * PUT /api/internal/agents/{id}/update
     */
    @PutMapping("/{id}/update")
    public ResponseEntity<AgentDto> updateAgentInternal(
            @PathVariable("id") UUID id,
            @RequestBody Map<String, Object> request,
            HttpServletRequest httpRequest,
            @RequestHeader(value = "X-Organization-ID", required = false) String callerOrgId) {

        String tenantId = tenantResolver.resolve(httpRequest);

        // Handle conversationId with explicit clearing support
        UUID conversationId = null;
        if (request.containsKey("conversationId")) {
            Object conversationIdObj = request.get("conversationId");
            if (conversationIdObj != null) {
                String conversationIdStr = conversationIdObj.toString();
                if (!conversationIdStr.isBlank()) {
                    conversationId = UUID.fromString(conversationIdStr);
                }
            }
        }

        // Audit 2026-05-16 round-2: thread callerOrgId so the service-layer
        // positive-scope check (org-teammate write) fires correctly for internal
        // hops. PR16 reflective forwarder is silent on daemon threads, so the
        // gateway-set X-Organization-ID is the canonical source here.
        AgentEntity updated = agentService.updateAgent(
            id,
            tenantId,
            extractor.getString(request, "name"),
            extractor.getString(request, "description"),
            extractor.getString(request, "systemPrompt"),
            extractor.getString(request, "modelProvider"),
            extractor.getString(request, "modelName"),
            extractor.getBigDecimal(request, "temperature"),
            extractor.getInteger(request, "maxTokens"),
            extractor.getInteger(request, "maxIterations"),
            extractor.getInteger(request, "executionTimeout"),
            extractor.getMap(request, "toolsConfig"),
            extractor.getUUID(request, "workflowId"),
            extractor.getLong(request, "dataSourceId"),
            conversationId,
            extractor.getMap(request, "config"),
            extractor.getString(request, "avatarUrl"),
            extractor.getBoolean(request, "isPublic"),
            extractor.getBoolean(request, "isActive"),
            extractor.getBigDecimal(request, "creditBudget"),
            extractor.getString(request, "budgetResetMode"),
            extractor.extractIntegerMap(request, GUARD_OVERRIDE_KEYS),
            callerOrgId,
            null,
            request.containsKey("creditBudget")
        );

        return ResponseEntity.ok(toDto(updated));
    }

    /**
     * Delete an agent (inter-service equivalent of DELETE /api/agents/{id}).
     * DELETE /api/internal/agents/{id}/delete
     */
    @DeleteMapping("/{id}/delete")
    public ResponseEntity<Void> deleteAgentInternal(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest,
            @RequestHeader(value = "X-Organization-ID", required = false) String callerOrgId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {

        String tenantId = tenantResolver.resolve(httpRequest);
        agentService.deleteAgent(id, tenantId, orgRole, callerOrgId);
        return ResponseEntity.ok().build();
    }

    // ========== Agent Lookup ==========

    /**
     * Resolve agent config by agent ID (used by AgentConfigResolver in orchestrator execution path).
     * GET /api/internal/agents/by-config/{id}
     */
    @GetMapping("/by-config/{id}")
    public ResponseEntity<AgentDto> resolveAgentConfig(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        // 2026-05-21 CRITICAL - AgentConfigResolver hits this on every
        // workflow agent-node execution. Without explicit X-Organization-ID
        // + X-Organization-Role propagation, non-OWNER org members get 404
        // mid-run ("Agent not found"). See commits f3b4522a0 / d4ad7ad27.
        String orgId = httpRequest.getHeader("X-Organization-ID");
        String orgRole = httpRequest.getHeader("X-Organization-Role");
        Optional<AgentEntity> opt = agentService.getAgent(id, tenantId, orgId, orgRole);
        return opt.map(agent -> ResponseEntity.ok(toDto(agent)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get agent by conversation ID.
     * GET /api/internal/agents/by-conversation/{conversationId}
     */
    @GetMapping("/by-conversation/{conversationId}")
    public ResponseEntity<AgentDto> getByConversationId(
            @PathVariable("conversationId") UUID conversationId,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        Optional<AgentEntity> opt = agentService.getAgentByConversationId(conversationId, tenantId);
        return opt.map(agent -> ResponseEntity.ok(toDto(agent)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get agents by project ID.
     * GET /api/internal/agents/by-project/{projectId}
     */
    @GetMapping("/by-project/{projectId}")
    @Transactional(readOnly = true)
    public ResponseEntity<List<AgentDto>> findByProjectId(
            @PathVariable("projectId") UUID projectId,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        // Batch A2 - workspace-scope routing. Internal endpoint forwarders
        // propagate X-Organization-ID so the org-strict finder lands when
        // the caller is acting in an org workspace.
        String orgId = tenantResolver.resolveOrgId(httpRequest);
        List<AgentEntity> agents = (orgId != null && !orgId.isBlank())
                ? agentRepository.findByProjectIdAndOrganizationIdStrict(projectId, orgId)
                : agentRepository.findByProjectIdAndTenantId(projectId, tenantId);
        return ResponseEntity.ok(agents.stream().map(this::toDto).toList());
    }

    /**
     * Count agents by project ID.
     * GET /api/internal/agents/by-project/{projectId}/count
     */
    @GetMapping("/by-project/{projectId}/count")
    public ResponseEntity<Long> countByProjectId(
            @PathVariable("projectId") UUID projectId,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        String orgId = tenantResolver.resolveOrgId(httpRequest);
        long count = (orgId != null && !orgId.isBlank())
                ? agentRepository.countByProjectIdAndOrganizationIdStrict(projectId, orgId)
                : agentRepository.countByProjectIdAndTenantId(projectId, tenantId);
        return ResponseEntity.ok(count);
    }

    /**
     * Assign an agent to a project.
     * PUT /api/internal/agents/{agentId}/project/{projectId}
     */
    @PutMapping("/{agentId}/project/{projectId}")
    public ResponseEntity<Boolean> assignToProject(
            @PathVariable("agentId") UUID agentId,
            @PathVariable("projectId") UUID projectId,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        boolean result = agentService.assignToProject(agentId, projectId, tenantId);
        return ResponseEntity.ok(result);
    }

    /**
     * Remove an agent from a project.
     * DELETE /api/internal/agents/{agentId}/project/{projectId}
     */
    @DeleteMapping("/{agentId}/project/{projectId}")
    public ResponseEntity<Boolean> removeFromProject(
            @PathVariable("agentId") UUID agentId,
            @PathVariable("projectId") UUID projectId,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        boolean result = agentService.removeFromProject(agentId, projectId, tenantId);
        return ResponseEntity.ok(result);
    }

    /**
     * Unassign all agents from a project.
     * DELETE /api/internal/agents/by-project/{projectId}/unassign-all
     */
    @DeleteMapping("/by-project/{projectId}/unassign-all")
    public ResponseEntity<Void> unassignAllFromProject(
            @PathVariable("projectId") UUID projectId,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        agentService.unassignAllFromProject(projectId, tenantId);
        return ResponseEntity.ok().build();
    }

    /**
     * Bulk find agents by IDs.
     * POST /api/internal/agents/bulk
     */
    @PostMapping("/bulk")
    public ResponseEntity<List<AgentDto>> bulkFind(
            @RequestBody List<UUID> ids,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        // 2026-05-21 - bulk lookup must also propagate org context so each
        // entry is filtered with the caller's workspace view (same shape
        // as /by-config/{id} above).
        String orgId = httpRequest.getHeader("X-Organization-ID");
        String orgRole = httpRequest.getHeader("X-Organization-Role");
        List<AgentDto> result = new ArrayList<>();
        for (UUID id : ids) {
            agentService.getAgent(id, tenantId, orgId, orgRole)
                .ifPresent(agent -> result.add(toDto(agent)));
        }
        return ResponseEntity.ok(result);
    }

    /**
     * Count agents for a tenant.
     * GET /api/internal/agents/count
     */
    @GetMapping("/count")
    public ResponseEntity<Long> countByTenantId(HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        long count = agentRepository.countByTenantId(tenantId);
        return ResponseEntity.ok(count);
    }

    // ========== Cascade Operations ==========

    /**
     * Delete all agents associated with a workflow (cascade delete).
     * DELETE /api/internal/agents/by-workflow/{workflowId}
     */
    @DeleteMapping("/by-workflow/{workflowId}")
    @Transactional
    public ResponseEntity<Void> deleteAgentsByWorkflowId(
            @PathVariable("workflowId") UUID workflowId,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        // Batch A2 - org-scoped cascade so deleting a workflow in one
        // workspace cannot reach an agent rowed against a different one.
        String orgId = tenantResolver.resolveOrgId(httpRequest);
        List<AgentEntity> agents = (orgId != null && !orgId.isBlank())
                ? agentRepository.findByWorkflowIdAndOrganizationIdStrict(workflowId, orgId)
                : agentRepository.findByWorkflowIdAndTenantId(workflowId, tenantId);
        for (AgentEntity agent : agents) {
            agentService.deleteAgent(agent.getId(), tenantId);
        }
        logger.info("Deleted {} agents for workflow {} (tenant={})", agents.size(), workflowId, tenantId);
        return ResponseEntity.ok().build();
    }

    // ========== Skills ==========

    /**
     * Get skills assigned to an agent (with details).
     * GET /api/internal/agents/{agentId}/skills
     */
    @GetMapping("/{agentId}/skills")
    public ResponseEntity<List<AgentSkillDto>> getSkillsForAgent(
            @PathVariable("agentId") UUID agentId,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        List<AgentSkillEntity> agentSkills = skillService.getAgentSkills(agentId, tenantId);
        List<AgentSkillDto> dtos = agentSkills.stream()
            .map(as -> new AgentSkillDto(
                as.getId(),
                as.getAgentId(),
                as.getSkillId(),
                as.getSortOrder(),
                as.getSkill() != null ? as.getSkill().getName() : null,
                as.getSkill() != null ? as.getSkill().getDescription() : null,
                as.getSkill() != null ? as.getSkill().getIcon() : null,
                as.getSkill() != null ? as.getSkill().getInstructions() : null
            ))
            .toList();
        return ResponseEntity.ok(dtos);
    }

    // ========== Conversation STOP cascade ==========

    /**
     * F2.1 - propagate a conversation STOP to running workflow runs the agent
     * loop spawned. Called by conversation-service after the stream is stopped.
     * Returns the count of distinct workflow runs flagged for cancellation
     * (Redis {@code workflow:cancel:&#123;runId&#125;}, honored by the engine
     * before every node).
     *
     * <p>POST /api/internal/agents/conversations/{conversationId}/cancel-workflows
     */
    @PostMapping("/conversations/{conversationId}/cancel-workflows")
    public ResponseEntity<Map<String, Object>> cancelWorkflowsForConversation(
            @PathVariable("conversationId") String conversationId,
            HttpServletRequest httpRequest) {
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        int count = conversationStopCascadeService.cancelRunningWorkflowsForConversation(conversationId, organizationId);
        return ResponseEntity.ok(Map.of("cancelledRuns", count));
    }

    /**
     * F3.4 - cascade-cancel any non-terminal background tasks linked to
     * executions that ran in this conversation. Used by conversation STOP so a
     * task assigned via {@code agent.assign} doesn't keep running after the
     * user has stopped the conversation that spawned it.
     *
     * <p>POST /api/internal/agents/conversations/{conversationId}/cancel-tasks
     */
    @PostMapping("/conversations/{conversationId}/cancel-tasks")
    public ResponseEntity<Map<String, Object>> cancelTasksForConversation(
            @PathVariable("conversationId") String conversationId,
            HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        String organizationId = tenantResolver.resolveOrgId(httpRequest);
        int count = conversationStopCascadeService.cancelTasksForConversation(conversationId, tenantId, organizationId);
        return ResponseEntity.ok(Map.of("cancelledTasks", count));
    }

    // ========== Observability ==========

    /**
     * Record agent execution observability data (fire-and-forget from orchestrator).
     * POST /api/internal/agents/observability
     */
    @PostMapping("/observability")
    public ResponseEntity<Void> recordObservability(
            @RequestBody AgentObservabilityRequest request,
            HttpServletRequest httpRequest) {

        // tenantId is embedded in the request DTO (set by orchestrator before sending)
        try {
            observabilityService.recordFromRequest(request);
            logger.debug("Recorded observability for agent={}", request.getAgentEntityId());
        } catch (Exception e) {
            logger.warn("Failed to record observability (non-critical): {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    // ========== Credit Budget ==========

    /**
     * Check and reset periodic credit budget if needed.
     * POST /api/internal/agent/{id}/budget/check-reset
     */
    @PostMapping("/{id}/budget/check-reset")
    public ResponseEntity<Map<String, Object>> checkAndResetBudget(@PathVariable("id") UUID id) {
        boolean wasReset = agentService.resetBudgetIfNeeded(id);
        return ResponseEntity.ok(Map.of("reset", wasReset));
    }

    // ========== Metrics ==========

    /**
     * Get fleet counters for a tenant (aggregated metrics).
     * GET /api/internal/agents/fleet-counters
     */
    @GetMapping("/fleet-counters")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getFleetCounters(HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        Object[] raw = agentRepository.getFleetCounters(tenantId);
        Object[] row = (raw.length == 1 && raw[0] instanceof Object[]) ? (Object[]) raw[0] : raw;

        Map<String, Object> counters = new LinkedHashMap<>();
        counters.put("totalAgents", ((Number) row[0]).longValue());
        counters.put("totalExecutions", ((Number) row[1]).longValue());
        counters.put("totalTokensUsed", ((Number) row[2]).longValue());
        counters.put("totalToolCalls", ((Number) row[3]).longValue());
        counters.put("totalDurationMs", ((Number) row[4]).longValue());
        counters.put("successCount", ((Number) row[5]).longValue());
        counters.put("failureCount", ((Number) row[6]).longValue());
        counters.put("totalCreditsConsumed", row.length > 7 ? ((Number) row[7]).doubleValue() : 0.0);

        return ResponseEntity.ok(counters);
    }

    /**
     * Get metrics summary for agents associated with a workflow.
     * GET /api/internal/agents/metrics/workflow/{workflowId}
     */
    @GetMapping("/metrics/workflow/{workflowId}")
    @Transactional(readOnly = true)
    public ResponseEntity<Map<String, Object>> getMetricsForWorkflow(
            @PathVariable("workflowId") UUID workflowId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        // Find agents for this workflow. Batch A2 - workspace-scoped via the
        // X-Organization-ID forwarded by the internal caller.
        String orgId = tenantResolver.resolveOrgId(httpRequest);
        List<AgentEntity> agents = (orgId != null && !orgId.isBlank())
                ? agentRepository.findByWorkflowIdAndOrganizationIdStrict(workflowId, orgId)
                : agentRepository.findByWorkflowIdAndTenantId(workflowId, tenantId);

        List<Map<String, Object>> agentSummaries = agents.stream()
            .map(agent -> {
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("agentId", agent.getId().toString());
                summary.put("agentName", agent.getName());
                summary.put("totalExecutions", agent.getTotalExecutions());
                summary.put("totalTokensUsed", agent.getTotalTokensUsed());
                summary.put("totalToolCalls", agent.getTotalToolCalls());
                summary.put("successCount", agent.getSuccessCount());
                summary.put("failureCount", agent.getFailureCount());
                summary.put("totalDurationMs", agent.getTotalDurationMs());
                return summary;
            })
            .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("executions", agentSummaries);
        result.put("totalCount", agents.size());
        return ResponseEntity.ok(result);
    }

    // ========== Skill CRUD (for SkillCrudModule in orchestrator) ==========

    /**
     * Create a skill.
     * POST /api/internal/agents/skills
     */
    @PostMapping("/skills")
    public ResponseEntity<SkillDto> createSkill(
            @RequestBody Map<String, Object> request,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        String name = (String) request.get("name");
        String description = (String) request.get("description");
        String icon = (String) request.get("icon");
        String instructions = (String) request.get("instructions");
        String folderIdStr = (String) request.get("folderId");
        UUID folderId = folderIdStr != null ? UUID.fromString(folderIdStr) : null;
        String organizationId = firstNonBlank(
                request.get("organizationId"),
                request.get("organization_id"),
                tenantResolver.resolveOrgId(httpRequest));

        SkillEntity result = skillService.createSkill(
                tenantId,
                name,
                description,
                icon,
                instructions,
                folderId,
                false,
                false,
                organizationId);

        String sourcePublicationIdStr = (String) request.get("sourcePublicationId");
        if (sourcePublicationIdStr != null && !sourcePublicationIdStr.isBlank()) {
            try {
                result.setSourcePublicationId(UUID.fromString(sourcePublicationIdStr));
                result = skillRepository.save(result);
            } catch (IllegalArgumentException e) {
                logger.warn("Ignoring invalid sourcePublicationId={} on skill create", sourcePublicationIdStr);
            }
        }

        return ResponseEntity.ok(toSkillDto(result));
    }

    /**
     * Get a skill by ID.
     * GET /api/internal/agents/skills/{skillId}
     */
    @GetMapping("/skills/{skillId}")
    public ResponseEntity<SkillDto> getSkill(
            @PathVariable("skillId") UUID skillId,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        return skillService.getSkill(skillId, tenantId)
            .map(skill -> ResponseEntity.ok(toSkillDto(skill)))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * List all skills for a tenant.
     * GET /api/internal/agents/skills
     */
    @GetMapping("/skills")
    public ResponseEntity<List<SkillDto>> listSkills(HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        List<SkillEntity> skills = skillService.listSkills(tenantId);
        return ResponseEntity.ok(skills.stream().map(this::toSkillDto).toList());
    }

    /**
     * Update a skill.
     * PUT /api/internal/agents/skills/{skillId}
     */
    @PutMapping("/skills/{skillId}")
    public ResponseEntity<SkillDto> updateSkill(
            @PathVariable("skillId") UUID skillId,
            @RequestBody Map<String, Object> updates,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        String name = (String) updates.get("name");
        String description = (String) updates.get("description");
        String icon = (String) updates.get("icon");
        String instructions = (String) updates.get("instructions");
        Boolean isActive = updates.containsKey("isActive") ? (Boolean) updates.get("isActive") : null;

        SkillEntity result = skillService.updateSkill(skillId, tenantId, name, description, icon, instructions, isActive);
        return ResponseEntity.ok(toSkillDto(result));
    }

    /**
     * Move a skill to a folder.
     * PUT /api/internal/agents/skills/{skillId}/move
     */
    @PutMapping("/skills/{skillId}/move")
    public ResponseEntity<Void> moveSkill(
            @PathVariable("skillId") UUID skillId,
            @RequestBody Map<String, Object> request,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        String folderIdStr = request.get("folderId") != null ? request.get("folderId").toString() : null;
        UUID folderId = (folderIdStr != null && !folderIdStr.equals("null")) ? UUID.fromString(folderIdStr) : null;
        skillService.moveSkill(skillId, tenantId, folderId);
        return ResponseEntity.ok().build();
    }

    /**
     * Delete a skill.
     * DELETE /api/internal/agents/skills/{skillId}
     */
    @DeleteMapping("/skills/{skillId}")
    public ResponseEntity<Void> deleteSkill(
            @PathVariable("skillId") UUID skillId,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        skillService.deleteSkill(skillId, tenantId);
        return ResponseEntity.ok().build();
    }

    /**
     * Seed default skills for a tenant (creates DB entities from DefaultSkillsProvider).
     * Idempotent: skips skills that already exist for this tenant.
     * POST /api/internal/agents/skills/seed-defaults
     */
    @PostMapping("/skills/seed-defaults")
    public ResponseEntity<Map<String, Object>> seedDefaultSkills(HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        int seeded = skillService.seedDefaultSkills(tenantId);
        return ResponseEntity.ok(Map.of("seeded", seeded));
    }

    /**
     * Reset a default skill to its original content from DefaultSkillsProvider.
     * POST /api/internal/agents/skills/{skillId}/reset
     */
    @PostMapping("/skills/{skillId}/reset")
    public ResponseEntity<SkillDto> resetDefaultSkill(
            @PathVariable("skillId") UUID skillId,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        SkillEntity result = skillService.resetDefaultSkill(skillId, tenantId);
        return ResponseEntity.ok(toSkillDto(result));
    }

    /**
     * Add skills to an agent (additive).
     * POST /api/internal/agents/{agentId}/skills/add
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/{agentId}/skills/add")
    public ResponseEntity<Integer> addAgentSkills(
            @PathVariable("agentId") UUID agentId,
            @RequestBody List<Map<String, Object>> assignments,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        List<SkillAssignment> skillAssignments = assignments.stream()
            .map(a -> new SkillAssignment(UUID.fromString((String) a.get("skillId"))))
            .toList();
        int added = skillService.addAgentSkills(agentId, tenantId, skillAssignments);
        return ResponseEntity.ok(added);
    }

    /**
     * Set skills for an agent (replaces existing).
     * POST /api/internal/agents/{agentId}/skills/set
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/{agentId}/skills/set")
    public ResponseEntity<Void> setAgentSkills(
            @PathVariable("agentId") UUID agentId,
            @RequestBody List<Map<String, Object>> assignments,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        List<SkillAssignment> skillAssignments = assignments.stream()
            .map(a -> new SkillAssignment(UUID.fromString((String) a.get("skillId"))))
            .toList();
        skillService.setAgentSkills(agentId, tenantId, skillAssignments);
        return ResponseEntity.ok().build();
    }

    // ========== Skill Folder CRUD (for SkillFolderModule in orchestrator) ==========

    /**
     * Create a skill folder.
     * POST /api/internal/agents/skill-folders
     */
    @PostMapping("/skill-folders")
    public ResponseEntity<SkillFolderDto> createFolder(
            @RequestBody Map<String, Object> request,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        String name = (String) request.get("name");
        String parentIdStr = request.get("parentId") != null ? request.get("parentId").toString() : null;
        UUID parentId = (parentIdStr != null && !parentIdStr.equals("null")) ? UUID.fromString(parentIdStr) : null;

        SkillFolderEntity folder = skillFolderService.createFolder(tenantId, name, parentId);
        return ResponseEntity.ok(toFolderDto(folder));
    }

    /**
     * List all skill folders for a tenant.
     * GET /api/internal/agents/skill-folders
     */
    @GetMapping("/skill-folders")
    public ResponseEntity<List<SkillFolderDto>> listFolders(HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolve(httpRequest);
        List<SkillFolderEntity> folders = skillFolderService.listAllFolders(tenantId);
        return ResponseEntity.ok(folders.stream().map(this::toFolderDto).toList());
    }

    /**
     * Rename a skill folder.
     * PUT /api/internal/agents/skill-folders/{folderId}/rename
     */
    @PutMapping("/skill-folders/{folderId}/rename")
    public ResponseEntity<SkillFolderDto> renameFolder(
            @PathVariable("folderId") UUID folderId,
            @RequestBody Map<String, Object> request,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        String name = (String) request.get("name");
        SkillFolderEntity folder = skillFolderService.renameFolder(folderId, tenantId, name);
        return ResponseEntity.ok(toFolderDto(folder));
    }

    /**
     * Move a skill folder.
     * PUT /api/internal/agents/skill-folders/{folderId}/move
     */
    @PutMapping("/skill-folders/{folderId}/move")
    public ResponseEntity<SkillFolderDto> moveFolder(
            @PathVariable("folderId") UUID folderId,
            @RequestBody Map<String, Object> request,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        String parentIdStr = request.get("parentId") != null ? request.get("parentId").toString() : null;
        UUID newParentId = (parentIdStr != null && !parentIdStr.equals("null")) ? UUID.fromString(parentIdStr) : null;

        SkillFolderEntity folder = skillFolderService.moveFolder(folderId, tenantId, newParentId);
        return ResponseEntity.ok(toFolderDto(folder));
    }

    /**
     * Delete a skill folder.
     * DELETE /api/internal/agents/skill-folders/{folderId}
     */
    @DeleteMapping("/skill-folders/{folderId}")
    public ResponseEntity<Void> deleteFolder(
            @PathVariable("folderId") UUID folderId,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolve(httpRequest);
        skillFolderService.deleteFolder(folderId, tenantId);
        return ResponseEntity.ok().build();
    }

    // ========== Sub-agent Observability ==========

    /**
     * Record sub-agent execution observability data.
     * Called by AgentExecuteModule in orchestrator via AgentClient.
     * POST /api/internal/agents/observability/sub-agent
     */
    @PostMapping("/observability/sub-agent")
    public ResponseEntity<Void> recordSubAgentObservability(
            @RequestBody AgentObservabilityRequest request,
            HttpServletRequest httpRequest) {
        try {
            observabilityService.recordFromRequest(request);
            logger.debug("Recorded sub-agent observability for agent={}", request.getAgentEntityId());
        } catch (Exception e) {
            logger.warn("Failed to record sub-agent observability (non-critical): {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    // ========== Publication Acquisition: Clone Agent ==========

    /**
     * Clone an agent from snapshot data during publication acquisition.
     * Creates a new AgentEntity + SkillEntities + AgentSkillEntity links.
     * Returns the new agent ID.
     * POST /api/internal/agents/clone-from-snapshot
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/clone-from-snapshot")
    @Transactional
    public ResponseEntity<Map<String, Object>> cloneFromSnapshot(
            @RequestBody Map<String, Object> request,
            HttpServletRequest httpRequest) {
        try {
            String tenantId = (String) request.get("tenantId");
            String publicationIdStr = (String) request.get("publicationId");
            UUID publicationId = publicationIdStr != null ? UUID.fromString(publicationIdStr) : null;
            String organizationId = firstNonBlank(
                    request.get("organizationId"),
                    request.get("organization_id"),
                    tenantResolver.resolveOrgId(httpRequest));

            // Create agent entity from snapshot fields
            AgentEntity newAgent = new AgentEntity();
            newAgent.setTenantId(tenantId);
            if (organizationId != null) {
                newAgent.setOrganizationId(organizationId);
            }
            // Content fields validated (not raw-cast): a numeric systemPrompt/name/description
            // in the snapshot must fail loud (400), never silently clone a bare number.
            newAgent.setName(asContentText(request.get("name"), null));
            newAgent.setDescription(asContentText(request.get("description"), null));
            newAgent.setSystemPrompt(asContentText(request.get("systemPrompt"), null));
            newAgent.setModelProvider((String) request.get("modelProvider"));
            newAgent.setModelName((String) request.get("modelName"));

            Object tempRaw = request.get("temperature");
            if (tempRaw instanceof Number n) {
                newAgent.setTemperature(java.math.BigDecimal.valueOf(n.doubleValue()));
            }
            Object maxTokensRaw = request.get("maxTokens");
            if (maxTokensRaw instanceof Number n) newAgent.setMaxTokens(n.intValue());
            Object maxIterRaw = request.get("maxIterations");
            if (maxIterRaw instanceof Number n) newAgent.setMaxIterations(n.intValue());
            Object timeoutRaw = request.get("executionTimeout");
            if (timeoutRaw instanceof Number n) newAgent.setExecutionTimeout(n.intValue());
            // V372 - preserve the publisher's inactivity watchdog window on clone (0 = disabled,
            // [10,7200] = custom). The snapshot value is already validated, so no re-check here
            // (mirrors executionTimeout above); without this the clone reverts to the 300s default.
            Object inactivityRaw = request.get("inactivityTimeout");
            if (inactivityRaw instanceof Number n) newAgent.setInactivityTimeout(n.intValue());

            newAgent.setAvatarUrl((String) request.get("avatarUrl"));

            Object configRaw = request.get("config");
            if (configRaw instanceof Map) {
                newAgent.setConfig((Map<String, Object>) configRaw);
            }

            Object dsIdRaw = request.get("dataSourceId");
            if (dsIdRaw instanceof Number n && n.longValue() > 0) {
                newAgent.setDataSourceId(n.longValue());
            }

            Object toolsConfigRaw = request.get("toolsConfig");
            // Normalize through AgentService so absent internal-resource keys are
            // backfilled with [] (snapshots from before V163 may omit them, which
            // would otherwise let downstream readers treat absent as "all").
            // Cast: snapshot payloads always carry a JSON object here.
            Map<String, Object> tcMap = (toolsConfigRaw instanceof Map<?, ?> raw)
                ? new LinkedHashMap<>((Map<String, Object>) raw)
                : null;
            newAgent.setToolsConfig(AgentService.normalizeToolsConfig(tcMap));

            // Credit budget config
            Object creditBudgetRaw = request.get("creditBudget");
            if (creditBudgetRaw instanceof Number n) {
                newAgent.setCreditBudget(java.math.BigDecimal.valueOf(n.doubleValue()));
            }
            String budgetResetMode = (String) request.get("budgetResetMode");
            if (budgetResetMode != null) {
                newAgent.setBudgetResetMode(budgetResetMode);
            }

            // M1: per-agent loop-guard + compaction-model overrides (preserve the publisher's
            // runtime stop behaviour + COLD-summariser model instead of reverting to platform defaults).
            Object maxPerResRaw = request.get("maxPerResourcePerTurn");
            if (maxPerResRaw instanceof Number n) newAgent.setMaxPerResourcePerTurn(n.intValue());
            Object loopIdRaw = request.get("loopIdenticalStop");
            if (loopIdRaw instanceof Number n) newAgent.setLoopIdenticalStop(n.intValue());
            Object loopConsecRaw = request.get("loopConsecutiveStop");
            if (loopConsecRaw instanceof Number n) newAgent.setLoopConsecutiveStop(n.intValue());
            String compactionProvider = (String) request.get("compactionModelProvider");
            if (compactionProvider != null) newAgent.setCompactionModelProvider(compactionProvider);
            String compactionName = (String) request.get("compactionModelName");
            if (compactionName != null) newAgent.setCompactionModelName(compactionName);
            // M3: per-agent reasoning-effort override.
            String reasoningEffort = (String) request.get("reasoningEffort");
            if (reasoningEffort != null && !reasoningEffort.isBlank()) {
                newAgent.setReasoningEffort(reasoningEffort);
            }

            newAgent.setIsPublic(false);
            newAgent.setIsActive(true);
            newAgent.setSourcePublicationId(publicationId);

            AgentEntity savedAgent = agentRepository.save(newAgent);

            // Clone skills
            Object skillsRaw = request.get("skills");
            if (skillsRaw instanceof List<?> skillsList) {
                int sortOrder = 0;
                for (Object skillRaw : skillsList) {
                    if (!(skillRaw instanceof Map<?, ?> skillMap)) continue;

                    SkillEntity newSkill = new SkillEntity();
                    newSkill.setTenantId(tenantId);
                    if (organizationId != null) {
                        newSkill.setOrganizationId(organizationId);
                    }
                    // Content fields are validated, not blindly .toString()'d: a numeric value
                    // here would silently store a bare number (the "106735"-in-instructions bug).
                    newSkill.setName(asContentText(skillMap.get("name"), "Skill"));
                    newSkill.setDescription(asContentText(skillMap.get("description"), ""));
                    newSkill.setIcon(asContentText(skillMap.get("icon"), null));
                    newSkill.setInstructions(asContentText(skillMap.get("instructions"), ""));
                    newSkill.setIsActive(true);
                    newSkill.setSourcePublicationId(publicationId);
                    SkillEntity savedSkill = skillRepository.save(newSkill);

                    AgentSkillEntity link = new AgentSkillEntity(savedAgent.getId(), savedSkill.getId(), sortOrder++);
                    agentSkillRepository.save(link);
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("agentId", savedAgent.getId().toString());
            logger.info("Cloned agent from snapshot → {} for tenant {}", savedAgent.getId(), tenantId);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Failed to clone agent from snapshot: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Read a skill CONTENT field (name/description/icon/instructions) from a cloned snapshot
     * map, refusing to coerce a non-text value (Number/Boolean) into a string. A numeric
     * value here is the fingerprint of the {@code getString().toString()} corruption that
     * stored "106735" into a skill's instructions - fail loud instead of cloning garbage.
     */
    private static String asContentText(Object value, String defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof CharSequence cs) return cs.toString();
        throw new IllegalArgumentException(
                "skill content field must be text, got " + value.getClass().getSimpleName() + ": " + value);
    }

    /**
     * Remap resource IDs in an agent's toolsConfig after all resources have been cloned.
     * POST /api/internal/agents/{agentId}/remap-tools-config
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/{agentId}/remap-tools-config")
    @Transactional
    public ResponseEntity<?> remapToolsConfig(
            @PathVariable("agentId") UUID agentId,
            @RequestBody Map<String, Object> request) {
        try {
            AgentEntity agent = agentRepository.findById(agentId).orElse(null);
            if (agent == null || agent.getToolsConfig() == null) {
                return ResponseEntity.ok().build();
            }

            Map<String, Object> tc = new HashMap<>(agent.getToolsConfig());

            Map<String, String> tableMapping = toStringMap(request.get("tables"));
            Map<String, String> interfaceMapping = toStringMap(request.get("interfaces"));
            Map<String, String> agentMapping = toStringMap(request.get("agents"));
            Map<String, String> workflowMapping = toStringMap(request.get("workflows"));
            Map<String, String> applicationsMapping = toStringMap(request.get("applications"));
            String workflowId = (String) request.get("workflowId");

            remapIdList(tc, "tables", tableMapping);
            remapIdList(tc, "interfaces", interfaceMapping);
            remapIdList(tc, "agents", agentMapping);
            remapIdList(tc, "workflows", workflowMapping);
            remapIdList(tc, "applications", applicationsMapping);

            // Replace __self__ sentinel with actual workflow ID (after ID remapping)
            Object wfVal = tc.get("workflows");
            if (wfVal instanceof List<?> wfList && !wfList.isEmpty() && workflowId != null) {
                List<String> remapped = wfList.stream()
                        .map(id -> "__self__".equals(id.toString()) ? workflowId : id.toString())
                        .collect(Collectors.toList());
                tc.put("workflows", remapped);
            }

            // Normalize so any pre-existing absent internal key (legacy snapshot)
            // is backfilled with [] before persist - same chokepoint as create/update.
            agent.setToolsConfig(AgentService.normalizeToolsConfig(tc));
            agentRepository.save(agent);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            logger.error("Failed to remap toolsConfig for agent {}: {}", agentId, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> toStringMap(Object raw) {
        if (!(raw instanceof Map)) return Collections.emptyMap();
        Map<String, String> result = new HashMap<>();
        ((Map<?, ?>) raw).forEach((k, v) -> result.put(k.toString(), v != null ? v.toString() : null));
        return result;
    }

    private void remapIdList(Map<String, Object> toolsConfig, String key, Map<String, String> mapping) {
        Object value = toolsConfig.get(key);
        if (!(value instanceof List<?> list) || list.isEmpty() || mapping.isEmpty()) return;
        List<String> remapped = list.stream()
                .map(id -> mapping.getOrDefault(id.toString(), id.toString()))
                .collect(Collectors.toList());
        toolsConfig.put(key, remapped);
    }

    // ========== DTO Mapping ==========

    private SkillDto toSkillDto(SkillEntity entity) {
        SkillDto dto = new SkillDto();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setIcon(entity.getIcon());
        dto.setInstructions(entity.getInstructions());
        dto.setIsActive(entity.getIsActive());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setFolderId(entity.getFolderId());
        dto.setSourcePublicationId(entity.getSourcePublicationId());
        dto.setDefaultKey(entity.getDefaultKey());
        dto.setOrganizationId(entity.getOrganizationId());
        return dto;
    }

    private SkillFolderDto toFolderDto(SkillFolderEntity entity) {
        SkillFolderDto dto = new SkillFolderDto();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setName(entity.getName());
        dto.setParentId(entity.getParentId());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private AgentDto toDto(AgentEntity entity) {
        AgentDto dto = new AgentDto();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setName(entity.getName());
        dto.setDescription(entity.getDescription());
        dto.setSystemPrompt(entity.getSystemPrompt());
        dto.setModelProvider(entity.getModelProvider());
        dto.setModelName(entity.getModelName());
        dto.setTemperature(entity.getTemperature());
        dto.setMaxTokens(entity.getMaxTokens());
        dto.setMaxIterations(entity.getMaxIterations());
        dto.setExecutionTimeout(entity.getExecutionTimeout());
        dto.setInactivityTimeout(entity.getInactivityTimeout());
        // Per-agent guard overrides (V100). Forwarded so orchestrator / caller services
        // can size LoopDetector and per-turn caps from the caller-agent entity.
        dto.setMaxPerResourcePerTurn(entity.getMaxPerResourcePerTurn());
        dto.setLoopIdenticalStop(entity.getLoopIdenticalStop());
        dto.setLoopConsecutiveStop(entity.getLoopConsecutiveStop());
        // Per-agent reasoning-effort override (bridge/CLI providers).
        dto.setReasoningEffort(entity.getReasoningEffort());
        // Stage 5.2b - per-agent compaction-model override (V106).
        dto.setCompactionModelProvider(entity.getCompactionModelProvider());
        dto.setCompactionModelName(entity.getCompactionModelName());
        // Per-agent compaction enable + cadence override (V350). Forwarded so the
        // post-turn compaction orchestrator can resolve the effective config.
        dto.setCompactionEnabled(entity.getCompactionEnabled());
        dto.setCompactionAfterTurns(entity.getCompactionAfterTurns());
        dto.setToolsConfig(entity.getToolsConfig());
        dto.setWorkflowId(entity.getWorkflowId());
        dto.setDataSourceId(entity.getDataSourceId());
        dto.setConversationId(entity.getConversationId());
        dto.setConfig(entity.getConfig());
        dto.setAvatarUrl(entity.getAvatarUrl());
        dto.setIsPublic(entity.getIsPublic());
        dto.setIsActive(entity.getIsActive());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setProjectId(entity.getProjectId());
        dto.setOrganizationId(entity.getOrganizationId());
        dto.setSourcePublicationId(entity.getSourcePublicationId());

        // Credit budget
        dto.setCreditBudget(entity.getCreditBudget());
        dto.setCreditsConsumed(entity.getCreditsConsumed());
        dto.setBudgetResetMode(entity.getBudgetResetMode());
        dto.setBudgetLastReset(entity.getBudgetLastReset());

        // Observability counters
        dto.setTotalExecutions(entity.getTotalExecutions());
        dto.setTotalTokensUsed(entity.getTotalTokensUsed());
        dto.setTotalToolCalls(entity.getTotalToolCalls());
        dto.setSuccessCount(entity.getSuccessCount());
        dto.setFailureCount(entity.getFailureCount());
        dto.setTotalDurationMs(entity.getTotalDurationMs());
        dto.setLastExecutionAt(entity.getLastExecutionAt());

        return dto;
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
