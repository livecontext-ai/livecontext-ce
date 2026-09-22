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
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.Trigger;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.WorkflowIconExtractor;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService;
import com.apimarketplace.orchestrator.services.epoch.WorkflowEpochService.LatestEpochOutcome;
import com.apimarketplace.trigger.client.TriggerClient;
import com.apimarketplace.trigger.client.dto.ScheduledExecutionDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregates production triggers across workflows, applications and agents. The
 * notification bell receives one compact DTO per resource and trigger kind. The
 * agenda receives one DTO per exact trigger from the immutable production plan.
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

    /**
     * The same map plus schedule and webhook - used to name the kind that FIRED an epoch, where
     * the two armed kinds are as valid an answer as the six declared ones. Kept separate rather
     * than widening {@link #KIND_BY_NODE_ID}, whose omissions are load-bearing: reading schedule
     * or webhook rows out of {@code nodeIcons} would double-emit them.
     */
    private static final Map<String, TriggerType> KIND_BY_NODE_ID_ALL = Map.of(
            "manual-trigger", TriggerType.MANUAL,
            "chat-trigger", TriggerType.CHAT,
            "form-trigger", TriggerType.FORM,
            "tables-trigger", TriggerType.DATASOURCE,
            "workflows-trigger", TriggerType.WORKFLOW,
            "error-trigger", TriggerType.ERROR,
            "schedule-trigger", TriggerType.SCHEDULE,
            "webhook-trigger", TriggerType.WEBHOOK
    );

    /**
     * Run statuses under which an epoch that is still OPEN really is executing.
     *
     * <p>Enumerated rather than computed as "not terminal": an unknown value - one from a
     * newer build, a status this list has not been taught - must not be read as "executing",
     * which would put a live pulse on an automation that has settled. Mirrors the frontend's
     * {@code EXECUTING_RUN_STATUSES} in {@code runFormatting.ts}, which badges the epoch rows
     * of the run panel from the same reasoning. {@code WAITING_TRIGGER} is deliberately absent:
     * the run is parked between fires, doing nothing.
     */
    private static final Set<RunStatus> EXECUTING_RUN_STATUSES = EnumSet.of(
            RunStatus.PENDING, RunStatus.RUNNING, RunStatus.PAUSED, RunStatus.AWAITING_SIGNAL);

    /**
     * Run statuses whose ending ABANDONS whatever epoch was still open: the run was killed
     * mid-flight, so that fire never reached the ending its own tally would suggest.
     *
     * <p>The frontend's {@code ABANDONING_RUN_STATUSES} minus {@code stopped}, which this
     * enum has no member for. Deliberately NOT "every terminal status": a run that ended
     * COMPLETED or FAILED with an epoch still open is the deferred-close case, and the
     * frontend badges that epoch from its own (absent) outcome, i.e. with nothing. Handing
     * over the RUN's verdict there would make the bell draw a green check on an epoch the
     * run panel leaves blank, one click apart.
     */
    private static final Set<RunStatus> ABANDONING_RUN_STATUSES = EnumSet.of(
            RunStatus.CANCELLED, RunStatus.TIMEOUT);

    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository runRepository;
    private final TriggerClient triggerClient;
    private final AgentClient agentClient;
    private final WorkflowEpochService epochService;

    public ActiveAutomationsService(WorkflowRepository workflowRepository,
                                    WorkflowRunRepository runRepository,
                                    TriggerClient triggerClient,
                                    AgentClient agentClient,
                                    WorkflowEpochService epochService) {
        this.workflowRepository = workflowRepository;
        this.runRepository = runRepository;
        this.triggerClient = triggerClient;
        this.agentClient = agentClient;
        this.epochService = epochService;
    }

    /**
     * The bell's view: only automations that are armed and will fire.
     */
    public List<ActiveAutomationDto> getActiveAutomations(String tenantId, String orgId, String orgRole) {
        return getActiveAutomations(tenantId, orgId, orgRole, false);
    }

    /**
     * @param includeDisabledSchedules when true, schedules that are paused or have
     *        exhausted their max-executions cap are ALSO emitted, flagged
     *        {@code schedule.armed = false}. The agenda draws them greyed so a user
     *        wondering why a job stopped running can see it sitting there paused
     *        instead of finding an unexplained hole in the calendar. ARCHIVED rows stay
     *        excluded in both modes - they never dispatch again and cannot be moved or
     *        resumed, so drawing them would promise something the platform cannot do.
     *        The bell passes false and its behaviour is unchanged.
     */
    public List<ActiveAutomationDto> getActiveAutomations(String tenantId, String orgId, String orgRole,
                                                          boolean includeDisabledSchedules) {
        return getActiveAutomations(tenantId, orgId, orgRole, includeDisabledSchedules, false);
    }

    /**
     * The agenda's trigger catalogue. Unlike the bell's compact per-kind rows, this emits
     * one row per trigger from the immutable production plan, including its normalized key
     * and authored label. That identity is what lets search distinguish two manual triggers
     * on the same workflow and attribute historical fires to the right one.
     */
    public List<ActiveAutomationDto> getAgendaAutomations(String tenantId, String orgId, String orgRole) {
        return getActiveAutomations(tenantId, orgId, orgRole, true, true);
    }

    private List<ActiveAutomationDto> getActiveAutomations(String tenantId, String orgId, String orgRole,
                                                           boolean includeDisabledSchedules,
                                                           boolean exactProductionTriggers) {
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
        // `isActive` is the archive flag, NOT the pause flag: TriggerLifecycleManager
        // clears `enabled` on suspend but keeps `isActive` true, and clears both on
        // archive. So this predicate reads as "everything but archived" in agenda mode
        // and "armed only" in bell mode.
        List<ScheduledExecutionDto> visibleSchedules = allSchedules.stream()
                .filter(s -> includeDisabledSchedules ? s.getIsActive() : s.isEnabled())
                .filter(s -> includeDisabledSchedules || !s.hasReachedMaxExecutions())
                .filter(s -> orgScope
                        ? orgId.equals(s.getOrganizationId())
                        : s.getOrganizationId() == null)
                .toList();

        Map<UUID, List<ScheduledExecutionDto>> schedulesByWorkflow = visibleSchedules.stream()
                .filter(s -> s.getWorkflowId() != null)
                .collect(Collectors.groupingBy(ScheduledExecutionDto::getWorkflowId));
        Map<UUID, List<ScheduledExecutionDto>> schedulesByAgent = visibleSchedules.stream()
                .filter(s -> s.getAgentEntityId() != null)
                .collect(Collectors.groupingBy(ScheduledExecutionDto::getAgentEntityId));
        // By-id lookup over the SAME visibility+org-filtered list. Standalone
        // schedule rows carry a NULL workflow_id by design (V206
        // raise_immutable_workflow_id anti-hijack) and a NULL agentEntityId, so
        // they fall through BOTH maps above. They link to their workflow only
        // through the plan's schedule-trigger {@code scheduleId} param, resolved
        // per pinned workflow below against this map - no extra wire call.
        Map<UUID, ScheduledExecutionDto> visibleScheduleById = visibleSchedules.stream()
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
        Set<UUID> workflowIdsWithWebhooks = pinnedIds.isEmpty() || exactProductionTriggers
                ? Collections.emptySet()
                : triggerClient.findWorkflowIdsWithTokens(pinnedIds);
        Map<UUID, Set<String>> activeWebhookTriggerIdsByWorkflow = pinnedIds.isEmpty() || !exactProductionTriggers
                ? Collections.emptyMap()
                : triggerClient.findActiveTriggerIdsByWorkflow(pinnedIds);
        if (activeWebhookTriggerIdsByWorkflow == null) {
            activeWebhookTriggerIdsByWorkflow = Collections.emptyMap();
        }

        // 3b. Batch-resolve the production run for each pinned workflow so the
        //     bell can route the user straight to /run/{prodRun} (matching the
        //     workflow board click target). Same query the board uses - single
        //     DISTINCT ON across all pinned ids at the trusted statuses (see
        //     WorkflowRunRepository.findProductionRunsBatch). Returns at most
        //     one row per workflow; absent => fall back to edit mode on click.
        List<WorkflowRunEntity> productionRuns = pinnedIds.isEmpty()
                ? Collections.emptyList()
                : runRepository.findProductionRunsBatch(pinnedIds);
        Map<UUID, WorkflowRunEntity> productionRunByWorkflow = productionRuns.stream()
                .collect(Collectors.toMap(
                        r -> r.getWorkflow().getId(),
                        r -> r,
                        (first, second) -> first));

        // 3c. The run whose epochs the last-run badge reads - FK FIRST, and the FK DECIDES,
        //     exactly as ProductionRunResolver picks the run a trigger fires into. The scan
        //     above cannot serve this: it orders by started_at DESC at the pinned version, and
        //     an EDITOR run lives at that same version (EditorRunResolver mints its own rather
        //     than adopting production), so a builder session out-sorts the real production run.
        //     Reading its epochs would badge a schedule row with the time and verdict of a Play
        //     click. The routing target above deliberately keeps the older scan behaviour.
        Map<UUID, WorkflowRunEntity> epochRunByWorkflow = epochRunsByWorkflow(
                pinned, productionRunByWorkflow, exactProductionTriggers);

        // 3d. Batch-resolve how each of those runs' LAST fire ended, so a row can badge its
        //     last-run time with the same COMPLETED/FAILED icon the run panel draws on that
        //     epoch. One query for the whole popover (see
        //     WorkflowEpochService#getLatestEpochOutcomeByRunIds) - never one per row.
        Map<String, LatestEpochOutcome> lastEpochByRun = lastEpochOutcomes(epochRunByWorkflow.values());

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

            WorkflowRunEntity epochRun = epochRunByWorkflow.get(w.getId());
            WorkflowRunEntity productionRun = exactProductionTriggers
                    ? epochRun
                    : (epochRun != null ? epochRun : productionRunByWorkflow.get(w.getId()));
            String productionRunIdPublic = productionRun != null ? productionRun.getRunIdPublic() : null;
            boolean resourcePaused = productionRun != null && productionRun.getStatus() == RunStatus.CANCELLED;
            LatestEpochOutcome lastEpoch = epochRun != null
                    ? lastEpochByRun.get(epochRun.getRunIdPublic())
                    : null;
            // The whole "Last: <verdict> <time>" line, from ONE event.
            LastRun workflowLastRun = LastRun.of(lastEpoch,
                    epochRun != null ? epochRun.getStatus() : null);
            // Which KIND of trigger opened that epoch. Every trigger of a workflow fires into
            // the same run, so a row must not claim a fire of another kind (see firedKind).
            // Parsed once and handed to both readers below: this runs per pinned workflow on an
            // endpoint the frontend polls, and WorkflowPlan.fromMap is not free.
            WorkflowPlan draftPlan = parsePlan(w.getPlan());
            WorkflowPlan productionPlan = parsePlan(productionRun != null ? productionRun.getPlan() : null);
            WorkflowPlan plan = exactProductionTriggers ? productionPlan : draftPlan;
            TriggerType firedKind = firedTriggerKind(
                    productionPlan != null ? productionPlan : draftPlan, lastEpoch);
            // v5 F4-PUB-HIJACK observability: APPLICATION rows must route to
            // /app/applications/{publicationId} not /app/applications/{workflowId}.
            // The frontend route param is keyed by publication id.
            String publicationId = (type == ResourceType.APPLICATION && w.getSourcePublicationId() != null)
                    ? w.getSourcePublicationId().toString()
                    : null;
            List<ScheduledExecutionDto> schedules = schedulesByWorkflow.getOrDefault(w.getId(), List.of());
            Set<UUID> emittedScheduleIds = new HashSet<>();
            for (ScheduledExecutionDto s : schedules) {
                if (exactProductionTriggers
                        && !hasTrigger(plan, s.getTriggerId(), TriggerType.SCHEDULE)) continue;
                emittedScheduleIds.add(s.getId());
                items.add(toScheduleAutomation(type, w.getId(), w.getName(), null, s, true, resourcePaused,
                        scheduleLastRun(workflowLastRun, s, w), productionRunIdPublic, publicationId, w,
                        triggerLabel(plan, s.getTriggerId())));
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
            for (UUID scheduleId : standaloneScheduleIds(plan)) {
                if (!emittedScheduleIds.add(scheduleId)) continue;   // de-dup vs attached
                ScheduledExecutionDto s = visibleScheduleById.get(scheduleId);
                if (s == null) continue;   // disabled / max-reached / other org / not found
                items.add(toScheduleAutomation(type, w.getId(), w.getName(), null, s, true, resourcePaused,
                        scheduleLastRun(workflowLastRun, s, w), productionRunIdPublic, publicationId, w,
                        triggerLabel(plan, s.getTriggerId())));
            }
            if (exactProductionTriggers) {
                Set<String> activeWebhookTriggerIds = activeWebhookTriggerIdsByWorkflow
                        .getOrDefault(w.getId(), Collections.emptySet());
                for (Trigger trigger : triggersOfKind(plan, TriggerType.WEBHOOK)) {
                    if (activeWebhookTriggerIds.contains(trigger.getNormalizedKey())) {
                        items.add(toWebhookAutomation(type, w.getId(), w.getName(), null, true, resourcePaused,
                                workflowLastRun.forTrigger(trigger.getNormalizedKey()), null,
                                productionRunIdPublic, publicationId,
                                trigger.getNormalizedKey(), trigger.label()));
                    }
                }
            } else if (workflowIdsWithWebhooks.contains(w.getId())) {
                items.add(toWebhookAutomation(type, w.getId(), w.getName(), null, true, resourcePaused,
                        workflowLastRun.forKind(firedKind, TriggerType.WEBHOOK)
                                .orElseAt(w.getLastExecutedAt()), null,
                        productionRunIdPublic, publicationId, null, null));
            }

            // Declared-kind rows for the 6 non-armed kinds (manual, chat, form,
            // datasource, workflow, error). Read directly from node_icons
            // (already populated on workflow save by WorkflowIconExtractor).
            // For legacy rows where node_icons is null, fall back to an inline
            // extraction from the plan - compute-only, no write-on-read.
            if (exactProductionTriggers) {
                if (plan != null) {
                    for (Trigger trigger : plan.getTriggers()) {
                        TriggerType kind = triggerType(trigger);
                        if (kind == null || kind == TriggerType.SCHEDULE || kind == TriggerType.WEBHOOK) continue;
                        items.add(toDeclaredKindAutomation(type, w.getId(), w.getName(), kind, true,
                                resourcePaused, workflowLastRun.forTrigger(trigger.getNormalizedKey()),
                                productionRunIdPublic, publicationId,
                                trigger.getNormalizedKey(), trigger.label()));
                    }
                }
            } else {
                List<Map<String, Object>> icons = w.getNodeIcons();
                if (icons == null) {
                    icons = WorkflowIconExtractor.extractNodeIcons(w.getPlan());
                }
                // De-dup: the notification bell stays compact, with one row per kind.
                EnumSet<TriggerType> declaredKinds = EnumSet.noneOf(TriggerType.class);
                for (Map<String, Object> icon : icons) {
                    Object nodeKind = icon.get("nodeKind");
                    if (!"entry".equals(nodeKind)) continue;
                    Object nodeId = icon.get("nodeId");
                    if (!(nodeId instanceof String idStr)) continue;
                    TriggerType kind = KIND_BY_NODE_ID.get(idStr);
                    if (kind == null || !declaredKinds.add(kind)) continue;
                    items.add(toDeclaredKindAutomation(type, w.getId(), w.getName(), kind, true,
                            resourcePaused,
                            workflowLastRun.forKind(firedKind, kind).orElseAt(w.getLastExecutedAt()),
                            productionRunIdPublic, publicationId, null, null));
                }
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
            boolean resourcePaused = Boolean.FALSE.equals(agent.getIsActive());

            // Agents have no pinning concept => no production run to route to.
            //
            // An agent schedule has no WORKFLOW cap, which is what budgetOwner describes, so
            // that argument stays null. It does have the agent's OWN cap, and the schedule
            // executor now refuses a fire against it - so the calendar has to draw those
            // fires as not-going-to-happen, or it promises runs the gate is going to refuse.
            // The verdict is resolved by agent-service and read here; re-deriving it from
            // creditBudget and creditsConsumed would miss the reservation and the pending
            // lazy reset, and grey out a month of fires that will actually run.
            BudgetBlock agentBudget = agentBudgetBlock(agent);
            for (ScheduledExecutionDto s : schedulesByAgent.getOrDefault(agentId, List.of())) {
                items.add(toScheduleAutomationWithBudget(ResourceType.AGENT, agent.getId(), agent.getName(),
                        agent.getAvatarUrl(), s, null, resourcePaused, LastRun.NONE.orElseAt(s.getLastExecutionAt()),
                        null, null, null, agentBudget));
            }
            List<ActiveAgentWebhookTokenDto> webhooks = webhooksByAgent.getOrDefault(agentId, List.of());
            if (!webhooks.isEmpty()) {
                String httpMethod = webhooks.get(0).getHttpMethod();
                items.add(toWebhookAutomation(ResourceType.AGENT, agent.getId(), agent.getName(),
                        agent.getAvatarUrl(), null, resourcePaused, LastRun.NONE, httpMethod, null, null,
                        null, null));
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
                                                     ScheduledExecutionDto s, Boolean isPinned, boolean resourcePaused, LastRun lastRun,
                                                     String productionRunIdPublic, String publicationId) {
        return toScheduleAutomation(type, resourceId, name, avatarUrl, s, isPinned, resourcePaused, lastRun,
                productionRunIdPublic, publicationId, null, null);
    }

    /**
     * @param budgetOwner the workflow whose SPENDING cap governs these fires, or
     *        {@code null} when nothing caps them (an agent's schedule: its budget
     *        is a different subsystem, with its own counter and its own reset).
     */
    private ActiveAutomationDto toScheduleAutomation(ResourceType type, UUID resourceId, String name, String avatarUrl,
                                                     ScheduledExecutionDto s, Boolean isPinned, boolean resourcePaused, LastRun lastRun,
                                                     String productionRunIdPublic, String publicationId,
                                                     WorkflowEntity budgetOwner, String triggerLabel) {
        return toScheduleAutomationWithBudget(type, resourceId, name, avatarUrl, s, isPinned, resourcePaused, lastRun,
                productionRunIdPublic, publicationId, triggerLabel,
                budgetBlock(budgetOwner, Instant.now()));
    }

    /**
     * The variant that takes the verdict instead of the workflow that owns it.
     *
     * <p>Exists because the two resource kinds capped in this product keep their cap in
     * different places: a workflow's is columns on the row this service already holds, an
     * agent's is resolved by agent-service and arrives on its DTO. Both end up in the same
     * {@link BudgetBlock}, so everything downstream of here, the agenda included, stays
     * blind to which kind it is looking at.
     */
    private ActiveAutomationDto toScheduleAutomationWithBudget(ResourceType type, UUID resourceId, String name, String avatarUrl,
                                                     ScheduledExecutionDto s, Boolean isPinned, boolean resourcePaused, LastRun lastRun,
                                                     String productionRunIdPublic, String publicationId,
                                                     String triggerLabel, BudgetBlock block) {
        boolean budgetBlocked = block.blocked();
        Instant budgetBlockedUntil = block.until();
        ScheduleInfo schedule = new ScheduleInfo(
                s.getCronExpression(),
                s.getTimezone(),
                s.getNextExecutionAt(),
                s.getExecutionCount(),
                s.getId(),
                // "Will it fire again", not the raw enabled column: an exhausted cap
                // leaves enabled=true on a schedule that never runs again.
                s.isEnabled() && !s.hasReachedMaxExecutions(),
                pausedReason(s),
                budgetBlocked,
                budgetBlockedUntil);
        return new ActiveAutomationDto(type, resourceId, name, avatarUrl, TriggerType.SCHEDULE,
                schedule, null, lastRun.at(), isPinned, resourcePaused, productionRunIdPublic, publicationId,
                lastRun.status(), s.getTriggerId(), triggerLabel);
    }

    /**
     * Which run's epochs answer "how did this automation last fire", per workflow.
     *
     * <p>{@code WorkflowEntity.productionRunId} is the FK a trigger fire follows
     * ({@code ProductionRunResolver}: FK first, and the FK decides). The pinned-version scan
     * used for the row's click target is NOT interchangeable with it: it orders by
     * {@code started_at DESC}, and an editor run sits at the same pinned version, so the
     * newest builder session out-sorts the run the schedules actually fire into.
     *
     * <p>This is NOT the resolver, though: it only READS, so it declines an FK it cannot vouch
     * for ({@link #usableAsProductionRun}) and falls back to the scan, where the resolver would
     * repair the FK. It also does not re-check the pin or the run's terminal status the way the
     * resolver does before FIRING - a run that has since drifted from the pin still fired the
     * epoch this badge is describing, and describing it is the whole job.
     */
    private Map<UUID, WorkflowRunEntity> epochRunsByWorkflow(List<WorkflowEntity> pinned,
                                                             Map<UUID, WorkflowRunEntity> scanned,
                                                             boolean exactProductionTriggers) {
        // The scan usually already holds the FK row - these are full entities, JSONB columns
        // included, so re-selecting them would be the most expensive query on the endpoint.
        Map<UUID, WorkflowRunEntity> knownById = scanned.values().stream()
                .filter(r -> r.getId() != null)
                .collect(Collectors.toMap(WorkflowRunEntity::getId, r -> r, (a, b) -> a));
        List<UUID> missingFkIds = pinned.stream()
                .map(WorkflowEntity::getProductionRunId)
                .filter(Objects::nonNull)
                .filter(id -> !knownById.containsKey(id))
                .distinct()
                .toList();
        Map<UUID, WorkflowRunEntity> fkRunsById = new HashMap<>(knownById);
        if (!missingFkIds.isEmpty()) {
            runRepository.findAllById(missingFkIds)
                    .forEach(r -> fkRunsById.put(r.getId(), r));
        }
        Map<UUID, WorkflowRunEntity> resolved = new HashMap<>();
        for (WorkflowEntity w : pinned) {
            WorkflowRunEntity fkRun = w.getProductionRunId() != null
                    ? fkRunsById.get(w.getProductionRunId())
                    : null;
            WorkflowRunEntity run = usableAsProductionRun(fkRun, w)
                    ? fkRun
                    : (exactProductionTriggers ? null : scanned.get(w.getId()));
            if (run != null && run.getRunIdPublic() != null) {
                resolved.put(w.getId(), run);
            }
        }
        return resolved;
    }

    /**
     * Whether a workflow's {@code production_run_id} still points at something this badge may
     * read, or the scan should answer instead.
     *
     * <p>The checks mirror the immutable production identity used by the trigger lane. The run
     * must belong to this workflow, match the currently pinned version, carry a production-safe
     * lifecycle status, and not be a showcase replay. CANCELLED is retained because it is the
     * explicit resource-paused state exposed by Agenda.
     * A SHOWCASE run is a published snapshot's replay and is never production identity.
     * {@code getWorkflow().getId()} is safe on the LAZY proxy because reading the identifier
     * does not initialize it.
     */
    private static boolean usableAsProductionRun(WorkflowRunEntity fkRun, WorkflowEntity w) {
        if (fkRun == null) {
            return false;
        }
        if (fkRun.getWorkflow() == null || !w.getId().equals(fkRun.getWorkflow().getId())) {
            return false;
        }
        if (!Objects.equals(fkRun.getPlanVersion(), w.getPinnedVersion())) {
            return false;
        }
        RunStatus status = fkRun.getStatus();
        if (status != RunStatus.COMPLETED
                && status != RunStatus.WAITING_TRIGGER
                && status != RunStatus.RUNNING
                && status != RunStatus.PAUSED
                && status != RunStatus.CANCELLED) {
            return false;
        }
        String runIdPublic = fkRun.getRunIdPublic();
        return !"showcase".equalsIgnoreCase(fkRun.getSource())
                && (runIdPublic == null || !runIdPublic.startsWith("showcase_"));
    }

    /**
     * A supplied workflow plan map, parsed, or null when it is absent or unreadable.
     *
     * <p>One parse per workflow, shared by every reader below - an unparseable plan costs those
     * readers their answer, never the row.
     */
    private static WorkflowPlan parsePlan(Map<String, Object> planMap) {
        if (planMap == null) {
            return null;
        }
        try {
            return WorkflowPlan.fromMap(planMap, null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The KIND of trigger that opened the given epoch, or null when it cannot be named.
     *
     * <p>The epoch header records the trigger's normalized key, and a plan trigger derives the
     * same key from its label ({@code Trigger.getNormalizedKey()}, the very value
     * {@code ScheduleSyncService} stores on a schedule row), so the two are comparable without
     * inventing a format. Null when the plan is unreadable, when no trigger carries that key
     * (it was renamed or removed since the fire), or when its type is one this map does not
     * cover - and a null kind matches no row, so the effect is a missing badge, never a wrong
     * one.
     */
    private static TriggerType firedTriggerKind(WorkflowPlan plan, LatestEpochOutcome lastEpoch) {
        if (plan == null || lastEpoch == null || lastEpoch.triggerId() == null) {
            return null;
        }
        for (Trigger trigger : plan.getTriggers()) {
            if (!lastEpoch.triggerId().equals(trigger.getNormalizedKey())) {
                continue;
            }
            return triggerType(trigger);
        }
        return null;
    }

    private static TriggerType triggerType(Trigger trigger) {
        if (trigger == null || trigger.type() == null) return null;
        String nodeId = WorkflowIconExtractor.TRIGGER_TYPE_TO_NODE_ID.get(
                trigger.type().toLowerCase(java.util.Locale.ROOT));
        return nodeId == null ? null : KIND_BY_NODE_ID_ALL.get(nodeId);
    }

    private static List<Trigger> triggersOfKind(WorkflowPlan plan, TriggerType kind) {
        if (plan == null) return List.of();
        return plan.getTriggers().stream()
                .filter(trigger -> triggerType(trigger) == kind)
                .toList();
    }

    private static boolean hasTrigger(WorkflowPlan plan, String triggerId, TriggerType kind) {
        if (plan == null || triggerId == null) return false;
        return plan.getTriggers().stream()
                .anyMatch(trigger -> triggerId.equals(trigger.getNormalizedKey())
                        && triggerType(trigger) == kind);
    }

    private static String triggerLabel(WorkflowPlan plan, String triggerId) {
        if (plan == null || triggerId == null) return null;
        return plan.getTriggers().stream()
                .filter(trigger -> triggerId.equals(trigger.getNormalizedKey()))
                .map(Trigger::label)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * How each production run's LAST fire ended, or an empty map if that read fails.
     *
     * <p>Degrading is the point. This feeds a badge on one line of one popover, and the same
     * endpoint serves the whole home payload - the inbox, the unread count, the automations
     * themselves. A {@code workflow_epochs} hiccup must cost the badges, never the page.
     */
    private Map<String, LatestEpochOutcome> lastEpochOutcomes(Collection<WorkflowRunEntity> productionRuns) {
        List<String> runIds = productionRuns.stream()
                .map(WorkflowRunEntity::getRunIdPublic)
                .filter(Objects::nonNull)
                .toList();
        try {
            return epochService.getLatestEpochOutcomeByRunIds(runIds);
        } catch (Exception e) {
            logger.warn("[ActiveAutomations] Last-fire outcomes unavailable for {} run(s), rows keep their "
                    + "timestamps without a status badge: {}", runIds.size(), e.getMessage());
            return Map.of();
        }
    }

    /**
     * The last-run line of ONE schedule row, attributed to THAT schedule.
     *
     * <p>Every trigger of a workflow fires into the SAME production run, so its newest epoch
     * belongs to whichever trigger fired last - not necessarily this one. A workflow with a
     * daily and a weekly schedule would otherwise print the daily fire's time and verdict on
     * the weekly row, which claims a run that row never caused. The epoch header records the
     * trigger that opened it and a schedule row carries the same id
     * ({@code ScheduledExecutionDto.getTriggerId()}, the very value
     * {@code ScheduleExecutorService} passes to {@code executeTrigger}), so the two are
     * comparable directly - no label normalisation, no plan parsing.
     *
     * <p>When they match, the epoch supplies both halves of the line. When they do not, the row
     * keeps its own {@code lastExecutionAt} and shows NO verdict: the fire it is naming is one
     * this service has no outcome for.
     */
    private static LastRun scheduleLastRun(LastRun workflowLastRun, ScheduledExecutionDto s, WorkflowEntity w) {
        Instant scheduleFallback = s.getLastExecutionAt() != null
                ? s.getLastExecutionAt()
                : w.getLastExecutedAt();
        return workflowLastRun.forTrigger(s.getTriggerId()).orElseAt(scheduleFallback);
    }

    /**
     * When an automation last ran, and how that run ended - carried together because the bell
     * prints them on one line and a verdict that describes a DIFFERENT fire than the timestamp
     * beside it is worse than no verdict at all.
     *
     * <p>A non-null {@code status} therefore always comes with the {@code at} of the very epoch
     * it was derived from, and {@link #orElseAt} - the only way to substitute another timestamp -
     * drops the status when it does.
     */
    record LastRun(Instant at, String status, String triggerId) {

        /** Nothing known: no production run, or a run that never opened an epoch. */
        static final LastRun NONE = new LastRun(null, null, null);

        static LastRun of(LatestEpochOutcome lastEpoch, RunStatus runStatus) {
            if (lastEpoch == null || lastEpoch.startedAt() == null) return NONE;
            return new LastRun(lastEpoch.startedAt(), resolveLastRunStatus(lastEpoch, runStatus),
                    lastEpoch.triggerId());
        }

        /**
         * This reading if the fire it describes came from a trigger of {@code rowKind}, else
         * nothing at all.
         *
         * <p>Kind-level, because these rows ARE per kind: one webhook row, one row per declared
         * kind. An unknown {@code firedKind} matches nothing, so an epoch this service cannot
         * attribute costs a badge rather than misplacing one.
         */
        LastRun forKind(TriggerType firedKind, TriggerType rowKind) {
            return firedKind != null && firedKind == rowKind ? this : NONE;
        }

        /**
         * This reading if the fire it describes came from {@code triggerId}, else nothing at all.
         *
         * <p>For a row that speaks for ONE trigger among several sharing a run. A blank id on
         * either side cannot be matched, so it answers {@link #NONE} rather than assuming.
         */
        LastRun forTrigger(String rowTriggerId) {
            if (rowTriggerId == null || rowTriggerId.isBlank() || !rowTriggerId.equals(triggerId)) {
                return NONE;
            }
            return this;
        }

        /**
         * This reading if it has a time, else {@code fallback} - which arrives unbadged, because
         * a timestamp from another source has no outcome of its own and cannot borrow one. The
         * substitution therefore only ever happens on {@link #NONE} (a verdict never exists
         * without the time it belongs to), and this is the single place a row's timestamp can
         * come from anywhere but the epoch.
         */
        LastRun orElseAt(Instant fallback) {
            return at != null ? this : new LastRun(fallback, null, null);
        }
    }

    /**
     * How the row's last fire ENDED - the badge drawn next to its last-run time.
     *
     * <p>Same reasoning as the run panel's per-epoch badge ({@code resolveEpochBadgeStatus} in
     * {@code runFormatting.ts}), and deliberately kept to that one shape so the bell can never
     * contradict the epoch row that is one click away:
     * <ul>
     *   <li>A CLOSED epoch states its own outcome ({@code COMPLETED} / {@code FAILED}), which
     *       outranks the run - the run may already be executing the NEXT fire.</li>
     *   <li>An OPEN epoch cannot: its stored state is the one written when it opened. Only the
     *       run knows, because the close is DEFERRED (a blocking signal or an in-flight agent
     *       leaves the epoch open long after its last node finished). So an executing run makes
     *       it {@code RUNNING}, and a run KILLED mid-flight ({@link #ABANDONING_RUN_STATUSES})
     *       hands over its own status - that fire never reached any ending of its own.</li>
     *   <li>Anything else - no epoch at all, an open epoch under a run that ended normally or
     *       is parked at {@code WAITING_TRIGGER}, a status this build does not know - answers
     *       null, and the frontend renders the time with an empty badge slot. Silence beats a
     *       guessed verdict, and it is also what the run panel shows for that same epoch.</li>
     * </ul>
     */
    static String resolveLastRunStatus(LatestEpochOutcome lastEpoch, RunStatus runStatus) {
        if (lastEpoch == null) return null;
        if (lastEpoch.outcome() != null) return lastEpoch.outcome();
        if (!lastEpoch.active() || runStatus == null) return null;
        if (ABANDONING_RUN_STATUSES.contains(runStatus)) return runStatus.name();
        return EXECUTING_RUN_STATUSES.contains(runStatus) ? RunStatus.RUNNING.name() : null;
    }

    /**
     * Whether a workflow's SPENDING cap is refusing its fires, and when that
     * lifts.
     *
     * @param blocked the cap is refusing fires right now
     * @param until   when it stops refusing, or {@code null} while blocked to
     *                mean "not on its own" (a cadence that never resets). Null
     *                is therefore NOT "not blocked" - read it with
     *                {@code blocked}, never alone.
     */
    record BudgetBlock(boolean blocked, Instant until) {}

    /**
     * The same verdict for an AGENT, read rather than computed.
     *
     * <p>Unlike the workflow one, this does not re-derive anything: agent-service resolved it
     * on the DTO, because two of the inputs never leave that service (the reservation held by
     * an in-flight sub-agent, and the lazy reset that has not been written yet). Deriving it
     * here from {@code creditBudget} and {@code creditsConsumed} alone is the tempting version
     * and it is wrong in a way nobody would notice for a month: a monthly agent that hit its
     * cap in September reads {@code consumed >= budget} all through October, so the calendar
     * would grey out a month of fires that will actually run.
     *
     * <p>Fails OPEN: a missing verdict means "not blocked", never "blocked".
     */
    static BudgetBlock agentBudgetBlock(AgentDto agent) {
        if (agent == null || !agent.budgetBlockedOrFalse()) {
            return new BudgetBlock(false, null);
        }
        return new BudgetBlock(true, agent.getBudgetBlockedUntil());
    }

    /**
     * Whether a WORKFLOW spending cap is refusing the fires it governs, and when that lifts.
     *
     * <p>Package-private and static so it can be tested the way {@code pausedReason} is:
     * this decides whether the agenda greys a schedule future, and the mapper that consumes
     * it is private, so keeping the decision inline would put it out of reach of any test.
     *
     * @param owner the workflow whose cap governs these fires, or {@code null} when none
     *              does, which includes every agent schedule (an agent cap is a different
     *              subsystem, with its own counter and its own reset - see
     *              {@link #agentBudgetBlock})
     */
    static BudgetBlock budgetBlock(WorkflowEntity owner, Instant now) {
        if (owner == null) {
            return new BudgetBlock(false, null);
        }
        boolean blocked = com.apimarketplace.orchestrator.services.credit.WorkflowBudgetPeriod.isBlocked(
                owner.getBudgetCredits(), owner.getBudgetPeriodMode(),
                owner.getBudgetPeriodSpent(), owner.getBudgetPeriodStartedAt(), now);
        return new BudgetBlock(blocked, blocked
                ? com.apimarketplace.orchestrator.services.credit.WorkflowBudgetPeriod
                        .nextPeriodStart(owner.getBudgetPeriodMode(), now)
                : null);
    }

    /**
     * Why this schedule is not armed, or null when it is.
     *
     * <p>Three ways to sit un-armed, and only one of them is a pause the user can undo:
     *
     * <ul>
     *   <li><b>The user disabled it</b> - {@code USER}. This is what the Resume button is
     *       for.</li>
     *   <li><b>Its cap is exhausted</b> - {@code CAP_REACHED}. {@code armSchedule}
     *       short-circuits on an already-ACTIVE row and returns true WITHOUT writing, so
     *       the UI announced a success while nothing changed and the schedule still could
     *       not fire.</li>
     *   <li><b>The platform suspended it</b> - {@code PLATFORM}. Its trigger left the
     *       pinned plan, or the workflow was unpinned or deleted. Re-arming rebuilds
     *       exactly the orphan the suspension sweep exists to retire, and the next tick
     *       suspends it again; the cause is upstream, in the plan.</li>
     * </ul>
     *
     * <p>The cap is checked BEFORE the reason, because a row can carry both: the sweep
     * stamps {@code MAX_EXEC_REACHED}, but a schedule the user had also paused would
     * otherwise report as resumable and resume into a state that still cannot fire.
     */
    static ActiveAutomationDto.PausedReason pausedReason(ScheduledExecutionDto s) {
        if (s.isEnabled() && !s.hasReachedMaxExecutions()) {
            return null;
        }
        if (s.hasReachedMaxExecutions()) {
            return ActiveAutomationDto.PausedReason.CAP_REACHED;
        }
        String reason = s.getLastDisabledReason();
        // A blank reason is a row disabled before reasons were recorded. Treat it as a user
        // pause: refusing to resume a legacy row the user CAN fix is the worse error, and
        // an arm that does not stick shows up on the next refresh rather than silently.
        if (reason == null || reason.isBlank()
                || "USER_DISABLED".equals(reason) || "LEGACY_DISABLED".equals(reason)) {
            return ActiveAutomationDto.PausedReason.USER;
        }
        return ActiveAutomationDto.PausedReason.PLATFORM;
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
     * <p>The caller chooses the source deliberately. The compact notification view passes
     * the editable workflow plan to preserve its existing behaviour. The agenda passes the
     * immutable production-run plan, so its searchable catalogue cannot advertise a draft
     * trigger that is not active in production.
     *
     * <p>Returns an empty set when the plan is null/unparseable or declares no
     * standalone schedule; a malformed {@code scheduleId} value is skipped
     * individually without dropping the other triggers.
     */
    private static Set<UUID> standaloneScheduleIds(WorkflowPlan plan) {
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
                                                    Boolean isPinned, boolean resourcePaused, LastRun lastRun, String httpMethod,
                                                    String productionRunIdPublic, String publicationId,
                                                    String triggerId, String triggerLabel) {
        WebhookInfo webhook = new WebhookInfo(httpMethod);
        return new ActiveAutomationDto(type, resourceId, name, avatarUrl, TriggerType.WEBHOOK,
                null, webhook, lastRun.at(), isPinned, resourcePaused, productionRunIdPublic, publicationId,
                lastRun.status(), triggerId, triggerLabel);
    }

    /**
     * DTO for a "declared" trigger kind (manual, chat, form, datasource, workflow,
     * error). Neither {@code schedule} nor {@code webhook} is set - the frontend
     * renders the kind's NodeIcon and the last-run line. That line is the production
     * run's last fire whatever trigger caused it, which is the same per-workflow
     * granularity these rows have always had (see {@link ActiveAutomationDto} Javadoc),
     * now read from the run rather than from a column any draft execution also stamps.
     */
    private ActiveAutomationDto toDeclaredKindAutomation(ResourceType type, UUID resourceId, String name,
                                                        TriggerType kind, Boolean isPinned, boolean resourcePaused, LastRun lastRun,
                                                        String productionRunIdPublic, String publicationId,
                                                        String triggerId, String triggerLabel) {
        return new ActiveAutomationDto(type, resourceId, name, null, kind,
                null, null, lastRun.at(), isPinned, resourcePaused, productionRunIdPublic, publicationId,
                lastRun.status(), triggerId, triggerLabel);
    }
}
