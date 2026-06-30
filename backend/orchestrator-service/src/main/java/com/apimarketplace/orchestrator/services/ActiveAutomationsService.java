package com.apimarketplace.orchestrator.services;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.ActiveAgentWebhookTokenDto;
import com.apimarketplace.agent.client.dto.AgentDto;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto.ResourceType;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto.ScheduleInfo;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto.TriggerType;
import com.apimarketplace.orchestrator.controllers.dto.ActiveAutomationDto.WebhookInfo;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowEntity.WorkflowType;
import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregates the home-page "Triggers" tab strip across workflows, applications,
 * and agents - one DTO per (resource, trigger-kind).
 *
 * <p><b>Emission rules</b>:
 * <ul>
 *   <li><b>workflow / application</b>: {@code pinnedVersion != null} AND one
 *       of:
 *       <ul>
 *         <li><i>schedule</i> (ARMED): an enabled schedule row exists. One DTO
 *             per {@code ScheduledExecutionDto} preserves multi-schedule
 *             granularity.</li>
 *         <li><i>webhook</i> (ARMED): at least one webhook token exists.</li>
 *         <li><i>manual / chat / form / datasource / workflow / error</i>
 *             (DECLARED): the pinned plan declares the trigger node, read from
 *             {@code WorkflowEntity.nodeIcons} (filtered to
 *             {@code nodeKind == "entry"}). {@code lastRunAt} comes from
 *             {@code WorkflowEntity.lastExecutedAt} (shared across same-workflow
 *             rows; see {@link ActiveAutomationDto} Javadoc for the
 *             trade-off).</li>
 *       </ul>
 *       The pin gate mirrors {@code ProductionRunResolver} - without a pin,
 *       triggers don't fire in production, so showing the row would mislead.</li>
 *   <li><b>agent</b>: schedule + webhook only (unchanged). Agents have a
 *       separate trigger model and no {@code nodeIcons}. Frontend filter chips
 *       for the 6 new kinds hide agent rows by design.</li>
 * </ul>
 *
 * <p><b>Rationale for the "declared, not armed" semantic on the 6 new kinds</b>:
 * the user requirement is "ALL prod workflows visible per kind" - the existing
 * "armed iff schedule-row OR webhook-token" gate cannot generalize because the
 * 6 new kinds have no per-trigger armament rows in trigger-service.
 *
 * <p><b>Sort (2-tier)</b>:
 * <ol>
 *   <li>Tier 1: SCHEDULE rows by {@code nextFireAt ASC NULLS LAST}.</li>
 *   <li>Tier 2: WEBHOOK + the 6 new kinds, by {@code lastRunAt DESC NULLS LAST}.</li>
 *   <li>Tiebreak: by {@code name} case-insensitive.</li>
 * </ol>
 * UX shift acknowledged: webhook rows leave the current NULLS-LAST tail and
 * intermix in Tier 2 alongside the new kinds.
 *
 * <p><b>Wire calls bounded</b>: 1 workflow query (own DB), 1 schedules call to
 * trigger-service, 1 agents call to agent-service, plus two conditional calls
 * (webhook-ids when pinned exists, agent-webhook-tokens when fleet non-empty).
 * No new external calls are added for the 6 new kinds - they read from the
 * already-loaded {@code WorkflowEntity.nodeIcons} column.
 */
@Service
public class ActiveAutomationsService {

    private static final Logger logger = LoggerFactory.getLogger(ActiveAutomationsService.class);

    /**
     * Reverse-map from {@code node_icons[].nodeId} (the entry-kind icon key) to
     * the canonical {@link TriggerType} for the 6 "declared" kinds. Schedule and
     * webhook are deliberately OMITTED: those rows are emitted from
     * trigger-service authoritative sources (enabled schedule rows + active
     * webhook tokens) and would double-emit if read from {@code nodeIcons} too.
     *
     * <p>Pinned by {@link com.apimarketplace.orchestrator.services.WorkflowIconExtractorParityTest}:
     * the 6 keys here must equal {@code WorkflowIconExtractor.TRIGGER_TYPE_TO_NODE_ID}
     * values minus {@code "schedule-trigger"} and {@code "webhook-trigger"}.
     */
    // Package-private for WorkflowIconExtractorParityTest access.
    static final Map<String, TriggerType> KIND_BY_NODE_ID = Map.of(
            "manual-trigger", TriggerType.MANUAL,
            "chat-trigger", TriggerType.CHAT,
            "form-trigger", TriggerType.FORM,
            "tables-trigger", TriggerType.DATASOURCE,
            "workflows-trigger", TriggerType.WORKFLOW,
            "error-trigger", TriggerType.ERROR
    );

    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository runRepository;
    private final TriggerClient triggerClient;
    private final AgentClient agentClient;

    public ActiveAutomationsService(WorkflowRepository workflowRepository,
                                    WorkflowRunRepository runRepository,
                                    TriggerClient triggerClient,
                                    AgentClient agentClient) {
        this.workflowRepository = workflowRepository;
        this.runRepository = runRepository;
        this.triggerClient = triggerClient;
        this.agentClient = agentClient;
    }

    public List<ActiveAutomationDto> getActiveAutomations(String tenantId, String orgId, String orgRole) {
        // Post-V261 (2026-05-19): the gateway always injects X-Organization-ID
        // (personal workspaces resolve to the user's default personal org), so
        // orgId is never null/blank for normal traffic. The legacy personal-
        // scope branch (IS NULL filter) is gone - every active workspace
        // routes through the strict-org finders.
        boolean orgScope = orgId != null;

        // 1. Pull schedules. V220 follow-up - org scope MUST call the
        //    org-scoped wire endpoint, not the tenant-scoped one. The
        //    pre-fix code called {@code getSchedulesByTenant(tenantId)} which
        //    only returned rows where {@code tenant_id == currentUser}. A
        //    schedule created by a teammate in the same org has
        //    {@code tenant_id = teammate_user_id} and was silently dropped at
        //    the wire - the in-memory orgId post-filter ran on an already
        //    incomplete list.
        //    /schedules/by-organization/{orgId} returns every schedule
        //    tagged with the org (strict-org finder).
        List<ScheduledExecutionDto> allSchedules = orgScope
                ? triggerClient.getSchedulesByOrganization(orgId)
                : triggerClient.getSchedulesByTenant(tenantId);
        List<ScheduledExecutionDto> enabledSchedules = allSchedules.stream()
                .filter(ScheduledExecutionDto::isEnabled)
                .filter(s -> !s.hasReachedMaxExecutions())
                .filter(s -> orgScope
                        ? orgId.equals(s.getOrganizationId())
                        : s.getOrganizationId() == null)
                .toList();

        Map<UUID, List<ScheduledExecutionDto>> schedulesByWorkflow = enabledSchedules.stream()
                .filter(s -> s.getWorkflowId() != null)
                .collect(Collectors.groupingBy(ScheduledExecutionDto::getWorkflowId));
        Map<UUID, List<ScheduledExecutionDto>> schedulesByAgent = enabledSchedules.stream()
                .filter(s -> s.getAgentEntityId() != null)
                .collect(Collectors.groupingBy(ScheduledExecutionDto::getAgentEntityId));
        // By-id lookup over the SAME enabled+org-filtered list. Standalone
        // schedule rows carry a NULL workflow_id by design (V206
        // raise_immutable_workflow_id anti-hijack) and a NULL agentEntityId, so
        // they fall through BOTH maps above. They link to their workflow only
        // through the plan's schedule-trigger {@code scheduleId} param, resolved
        // per pinned workflow below against this map - no extra wire call.
        Map<UUID, ScheduledExecutionDto> enabledScheduleById = enabledSchedules.stream()
                .collect(Collectors.toMap(ScheduledExecutionDto::getId, s -> s, (a, b) -> a));

        // 2. Pull active workflows scoped to the active workspace using the
        //    PR30 strict-org finder. Pre-V220 this read findByTenantIdAndIsActiveTrue,
        //    which leaked cross-org workflows when the user belonged to
        //    multiple orgs. Post-V261 the strict-personal IS NULL companion
        //    is removed - personal workspaces resolve to a real
        //    organization_id (user's default personal org) and route here.
        //    The legacy null-orgId branch is preserved as an empty-list
        //    fallback for any defensive caller still passing null; in
        //    production traffic orgId is never null because the gateway
        //    always injects X-Organization-ID.
        List<WorkflowEntity> scopedWorkflows = orgScope
                ? workflowRepository.findByOrganizationIdStrictAndIsActiveTrueOrderByCreatedAtDesc(orgId)
                : Collections.emptyList();
        List<WorkflowEntity> pinned = scopedWorkflows.stream()
                .filter(w -> w.getPinnedVersion() != null)
                .toList();

        // 3. Resolve which pinned workflows have at least one webhook token (workflow-side).
        List<UUID> pinnedIds = pinned.stream().map(WorkflowEntity::getId).toList();
        Set<UUID> workflowIdsWithWebhooks = pinnedIds.isEmpty()
                ? Collections.emptySet()
                : triggerClient.findWorkflowIdsWithTokens(pinnedIds);

        // 3b. Batch-resolve the production run for each pinned workflow so the
        //     bell can route the user straight to /run/{prodRun} (matching the
        //     workflow board click target). Same query the board uses - single
        //     DISTINCT ON across all pinned ids at the trusted statuses (see
        //     WorkflowRunRepository.findProductionRunsBatch). Returns at most
        //     one row per workflow; absent => fall back to edit mode on click.
        Map<UUID, String> productionRunIdPublicByWorkflow = pinnedIds.isEmpty()
                ? Collections.emptyMap()
                : runRepository.findProductionRunsBatch(pinnedIds).stream()
                        .collect(Collectors.toMap(
                                r -> r.getWorkflow().getId(),
                                WorkflowRunEntity::getRunIdPublic,
                                (first, second) -> first));

        // 4. Pull all agents + their active webhook tokens. Webhooks call is
        //    skipped entirely when the tenant has no agents (every agent webhook
        //    row has agent_id FK CASCADE-deleted with the agent, so an empty
        //    fleet implies an empty webhook table for the tenant).
        List<AgentDto> agents = agentClient.getAgents(tenantId, orgId, orgRole);
        List<ActiveAgentWebhookTokenDto> agentWebhookTokens = agents.isEmpty()
                ? List.of()
                : agentClient.getActiveAgentWebhookTokens(tenantId);
        Map<UUID, List<ActiveAgentWebhookTokenDto>> webhooksByAgent = agentWebhookTokens.stream()
                .collect(Collectors.groupingBy(ActiveAgentWebhookTokenDto::getAgentId));

        // 5. Build the unified list. Workflows and apps share the same resolver
        //    because they share the same trigger model - only the resourceType
        //    differs (driven by WorkflowType.APPLICATION).
        List<ActiveAutomationDto> items = new ArrayList<>();
        for (WorkflowEntity w : pinned) {
            ResourceType type = w.getWorkflowType() == WorkflowType.APPLICATION
                    ? ResourceType.APPLICATION
                    : ResourceType.WORKFLOW;

            String productionRunIdPublic = productionRunIdPublicByWorkflow.get(w.getId());
            // v5 F4-PUB-HIJACK observability: APPLICATION rows must route to
            // /app/applications/{publicationId} not /app/applications/{workflowId}.
            // The frontend route param is keyed by publication id.
            String publicationId = (type == ResourceType.APPLICATION && w.getSourcePublicationId() != null)
                    ? w.getSourcePublicationId().toString()
                    : null;
            List<ScheduledExecutionDto> schedules = schedulesByWorkflow.getOrDefault(w.getId(), List.of());
            Set<UUID> emittedScheduleIds = new HashSet<>();
            for (ScheduledExecutionDto s : schedules) {
                emittedScheduleIds.add(s.getId());
                items.add(toScheduleAutomation(type, w.getId(), w.getName(), null, s, true,
                        w.getLastExecutedAt(), productionRunIdPublic, publicationId));
            }
            // Standalone schedules: workflow_id NULL by design, so absent from
            // schedulesByWorkflow. Resolve them from THIS workflow's plan via the
            // schedule-trigger scheduleId param against the enabled+org-filtered
            // set. Without this, a pinned workflow whose only trigger is a
            // standalone schedule emits zero automation rows - the Triggers tab
            // (and the whole bell, for a notification-free account) stays empty
            // even though the schedule is armed and firing.
            // A scheduleId referenced by two pinned workflows in the same org
            // surfaces under each (de-dup is per-workflow) - bounded by the org
            // filter, and each workflow legitimately declares it.
            for (UUID scheduleId : standaloneScheduleIds(w.getPlan())) {
                if (!emittedScheduleIds.add(scheduleId)) continue;   // de-dup vs attached
                ScheduledExecutionDto s = enabledScheduleById.get(scheduleId);
                if (s == null) continue;   // disabled / max-reached / other org / not found
                items.add(toScheduleAutomation(type, w.getId(), w.getName(), null, s, true,
                        w.getLastExecutedAt(), productionRunIdPublic, publicationId));
            }
            if (workflowIdsWithWebhooks.contains(w.getId())) {
                items.add(toWebhookAutomation(type, w.getId(), w.getName(), null, true,
                        w.getLastExecutedAt(), null, productionRunIdPublic, publicationId));
            }

            // Declared-kind rows for the 6 non-armed kinds (manual, chat, form,
            // datasource, workflow, error). Read directly from node_icons
            // (already populated on workflow save by WorkflowIconExtractor).
            // For legacy rows where node_icons is null, fall back to an inline
            // extraction from the plan - compute-only, no write-on-read.
            List<Map<String, Object>> icons = w.getNodeIcons();
            if (icons == null) {
                icons = WorkflowIconExtractor.extractNodeIcons(w.getPlan());
            }
            // De-dup: a workflow can declare the same trigger kind on multiple
            // nodes (rare, but legal). One DTO per kind is the desired UX.
            EnumSet<TriggerType> declaredKinds = EnumSet.noneOf(TriggerType.class);
            for (Map<String, Object> icon : icons) {
                Object nodeKind = icon.get("nodeKind");
                if (!"entry".equals(nodeKind)) continue;
                Object nodeId = icon.get("nodeId");
                if (!(nodeId instanceof String idStr)) continue;
                TriggerType kind = KIND_BY_NODE_ID.get(idStr);
                if (kind == null) continue;            // schedule/webhook excluded by design
                if (!declaredKinds.add(kind)) continue; // de-dup
                items.add(toDeclaredKindAutomation(type, w.getId(), w.getName(), kind, true,
                        w.getLastExecutedAt(), productionRunIdPublic, publicationId));
            }
        }

        // 6. Agents - schedules always armed if enabled (no pin), webhooks armed if active.
        Map<UUID, AgentDto> agentById = agents.stream()
                .collect(Collectors.toMap(AgentDto::getId, a -> a, (a, b) -> a));
        Set<UUID> agentIdsWithTriggers = new HashSet<>();
        agentIdsWithTriggers.addAll(schedulesByAgent.keySet());
        agentIdsWithTriggers.addAll(webhooksByAgent.keySet());

        for (UUID agentId : agentIdsWithTriggers) {
            AgentDto agent = agentById.get(agentId);
            if (agent == null) continue; // Stale schedule for a deleted agent - skip silently.

            // Agents have no pinning concept => no production run to route to.
            for (ScheduledExecutionDto s : schedulesByAgent.getOrDefault(agentId, List.of())) {
                items.add(toScheduleAutomation(ResourceType.AGENT, agent.getId(), agent.getName(),
                        agent.getAvatarUrl(), s, null, null, null, null));
            }
            List<ActiveAgentWebhookTokenDto> webhooks = webhooksByAgent.getOrDefault(agentId, List.of());
            if (!webhooks.isEmpty()) {
                String httpMethod = webhooks.get(0).getHttpMethod();
                items.add(toWebhookAutomation(ResourceType.AGENT, agent.getId(), agent.getName(),
                        agent.getAvatarUrl(), null, null, httpMethod, null, null));
            }
        }

        // 7. Sort - 2-tier:
        //    Tier 1: SCHEDULE rows (schedule != null) by nextFireAt ASC NULLS LAST.
        //    Tier 2: every other row (WEBHOOK + 6 new kinds) by lastRunAt DESC NULLS LAST.
        //    Tiebreak by name. UX shift documented in DTO Javadoc: webhook rows
        //    leave the current NULLS-LAST tail and intermix in Tier 2.
        Comparator<ActiveAutomationDto> byTier =
                Comparator.comparingInt(a -> a.schedule() != null ? 0 : 1);
        Comparator<ActiveAutomationDto> withinTier = (a, b) -> {
            if (a.schedule() != null) {
                // Both Tier 1: schedule rows. Schedules with null nextFireAt
                // (transient between cron recomputes) sort to Tier 1 NULLS LAST
                // tail - preserves current behavior.
                return Comparator.nullsLast(Comparator.<Instant>naturalOrder())
                        .compare(a.schedule().nextFireAt(), b.schedule().nextFireAt());
            }
            // Both Tier 2: webhook + 6 new kinds. Never-fired rows
            // (lastRunAt == null) sort to the tail by name.
            return Comparator.nullsLast(Comparator.<Instant>reverseOrder())
                    .compare(a.lastRunAt(), b.lastRunAt());
        };
        items.sort(byTier.thenComparing(withinTier)
                .thenComparing(ActiveAutomationDto::name,
                        Comparator.nullsLast(String::compareToIgnoreCase)));

        logger.debug("Active automations for tenant {}: {} items ({} pinned workflows/apps, {} agents with triggers)",
                tenantId, items.size(), pinned.size(), agentIdsWithTriggers.size());
        return items;
    }

    private ActiveAutomationDto toScheduleAutomation(ResourceType type, UUID resourceId, String name, String avatarUrl,
                                                     ScheduledExecutionDto s, Boolean isPinned, Instant fallbackLastRun,
                                                     String productionRunIdPublic, String publicationId) {
        ScheduleInfo schedule = new ScheduleInfo(
                s.getCronExpression(),
                s.getTimezone(),
                s.getNextExecutionAt(),
                s.getExecutionCount());
        Instant lastRun = s.getLastExecutionAt() != null ? s.getLastExecutionAt() : fallbackLastRun;
        return new ActiveAutomationDto(type, resourceId, name, avatarUrl, TriggerType.SCHEDULE,
                schedule, null, lastRun, isPinned, productionRunIdPublic, publicationId);
    }

    /**
     * Standalone schedule ids declared by a workflow plan's schedule-trigger
     * nodes ({@code params.scheduleId}). A standalone schedule row carries a
     * NULL {@code workflow_id} by design (V206 {@code raise_immutable_workflow_id}
     * anti-hijack immutability) and is linked to its owning workflow ONLY through
     * this plan reference - so it never appears in the {@code workflow_id}-keyed
     * {@code schedulesByWorkflow} map and would stay invisible in the bell's
     * Triggers tab despite firing.
     *
     * <p><b>Draft-plan read (deliberate).</b> Reads the live
     * {@code WorkflowEntity.getPlan()} (draft) column - NOT the pinned version's
     * plan that {@code ScheduleSyncService} arms schedules from. This is the SAME
     * draft-plan view the sibling declared-kind extraction already uses
     * ({@code nodeIcons}, recomputed from the draft on every save), so the bell's
     * schedule and declared-kind rows stay on one consistent source and the read
     * costs no extra query. Accepted limitation, shared with the declared-kind
     * rows: if the draft diverges from the pinned version (a schedule trigger
     * added to / removed from the draft but not yet re-pinned) the bell can
     * momentarily over- or under-list a schedule relative to what actually fires.
     * The enabled+org filter applied to the resolved row at the call site still
     * gates what surfaces, so a divergent draft can never surface a disabled or
     * cross-org schedule.
     *
     * <p>Returns an empty set when the plan is null/unparseable or declares no
     * standalone schedule; a malformed {@code scheduleId} value is skipped
     * individually without dropping the other triggers.
     */
    private static Set<UUID> standaloneScheduleIds(Map<String, Object> planMap) {
        if (planMap == null) {
            return Set.of();
        }
        WorkflowPlan plan;
        try {
            plan = WorkflowPlan.fromMap(planMap, null);
        } catch (Exception e) {
            return Set.of();
        }
        if (plan == null) {
            return Set.of();
        }
        Set<UUID> ids = new HashSet<>();
        for (Trigger trigger : plan.getTriggers()) {   // getTriggers() returns a copy, never null
            if (!"schedule".equalsIgnoreCase(trigger.type())) {
                continue;
            }
            Object scheduleId = trigger.params().get("scheduleId");
            if (scheduleId == null) {
                continue;
            }
            try {
                ids.add(UUID.fromString(scheduleId.toString()));
            } catch (IllegalArgumentException ignored) {
                // Malformed UUID in plan - skip this trigger, keep the rest.
            }
        }
        return ids;
    }

    private ActiveAutomationDto toWebhookAutomation(ResourceType type, UUID resourceId, String name, String avatarUrl,
                                                    Boolean isPinned, Instant lastRun, String httpMethod,
                                                    String productionRunIdPublic, String publicationId) {
        WebhookInfo webhook = new WebhookInfo(httpMethod);
        return new ActiveAutomationDto(type, resourceId, name, avatarUrl, TriggerType.WEBHOOK,
                null, webhook, lastRun, isPinned, productionRunIdPublic, publicationId);
    }

    /**
     * DTO for a "declared" trigger kind (manual, chat, form, datasource, workflow,
     * error). Neither {@code schedule} nor {@code webhook} is set - the frontend
     * renders the kind's NodeIcon and a relative-time label from {@code lastRunAt}
     * only. {@code lastRunAt} is the workflow-level {@code lastExecutedAt}; see
     * {@link ActiveAutomationDto} Javadoc for the per-kind precision trade-off.
     */
    private ActiveAutomationDto toDeclaredKindAutomation(ResourceType type, UUID resourceId, String name,
                                                        TriggerType kind, Boolean isPinned, Instant lastRun,
                                                        String productionRunIdPublic, String publicationId) {
        return new ActiveAutomationDto(type, resourceId, name, null, kind,
                null, null, lastRun, isPinned, productionRunIdPublic, publicationId);
    }
}
