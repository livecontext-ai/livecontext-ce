package com.apimarketplace.orchestrator.tools.application;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.workflow.InterfaceDef;
import com.apimarketplace.orchestrator.domain.workflow.RunStatus;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Shared resolution of the two ingredients that turn a workflow publication into an
 * <em>application</em>: the showcase <b>interface</b> (entry interface) and the showcase
 * <b>run</b> (latest successful automatic run).
 *
 * <p>A publication is treated as an application iff it carries a {@code showcaseInterfaceId}
 * ({@code WorkflowPublicationEntity.isApplication() == (showcaseInterfaceId != null)}). Both
 * {@code application(action='create')} and {@code workflow(action='publish')} must resolve these
 * identically so the agent gets the same result whichever path it takes - otherwise a workflow
 * published through the lower-level path ships a showcase-less listing whose app preview renders
 * nothing. Centralizing the logic here is the single source of truth both call.
 */
@Component
@RequiredArgsConstructor
public class ApplicationShowcaseResolver {

    private final WorkflowRunRepository workflowRunRepository;

    /**
     * Run statuses that may be showcased - MUST mirror the publish pipeline's source of truth
     * ({@code InternalPublicationSupportController.validateShowcaseRun} -> {@code publishable}).
     * A reusable-trigger workflow (webhook/manual/chat/datasource/schedule) sits at
     * {@code WAITING_TRIGGER} after a successful cycle, so it is showcaseable even though it
     * never reaches {@code COMPLETED}. FAILED / CANCELLED / TIMEOUT / RUNNING / PENDING / PAUSED
     * are excluded.
     */
    private static final Set<RunStatus> SHOWCASEABLE_STATUSES = Set.of(
        RunStatus.COMPLETED, RunStatus.PARTIAL_SUCCESS, RunStatus.WAITING_TRIGGER);

    /** Whether a run status is one a showcase may freeze (see {@link #SHOWCASEABLE_STATUSES}). */
    public boolean isShowcaseableStatus(RunStatus status) {
        return status != null && SHOWCASEABLE_STATUSES.contains(status);
    }

    /** A run is showcaseable iff it is automatic, in a successful/idle state, and not a showcase clone. */
    public boolean isShowcaseableRun(WorkflowRunEntity run) {
        return run != null
            && !run.isStepByStepMode()
            && isShowcaseableStatus(run.getStatus())
            && !"showcase".equalsIgnoreCase(run.getSource());
    }

    /**
     * The interface to land on when the application opens: the one flagged
     * {@code isEntryInterface=true}, falling back to the first interface in the plan.
     * Empty when the plan has no interface (i.e. it is not an application).
     */
    public Optional<InterfaceDef> resolveEntryInterface(WorkflowPlan plan) {
        if (plan == null) return Optional.empty();
        List<InterfaceDef> interfaces = plan.getInterfaces();
        if (interfaces == null || interfaces.isEmpty()) return Optional.empty();
        return Optional.of(interfaces.stream()
            .filter(i -> Boolean.TRUE.equals(i.isEntryInterface()))
            .findFirst()
            .orElse(interfaces.get(0)));
    }

    /**
     * The {@code run_id_public} of the latest showcaseable run for the workflow (newest first),
     * or empty when none exists yet. Scans the most recent 50 runs - the same window the
     * application-create path uses.
     */
    public Optional<String> resolveLatestShowcaseRunId(UUID workflowId) {
        if (workflowId == null) return Optional.empty();
        List<WorkflowRunEntity> runs = workflowRunRepository
            .findByWorkflowIdOrderByStartedAtDescPageable(workflowId, PageRequest.of(0, 50))
            .getContent();
        for (WorkflowRunEntity run : runs) {
            if (isShowcaseableRun(run)) {
                return Optional.of(run.getRunIdPublic());
            }
        }
        return Optional.empty();
    }
}
