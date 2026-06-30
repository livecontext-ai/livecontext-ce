package com.apimarketplace.auth.service;

import com.apimarketplace.auth.repository.OrganizationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Re-applies every multi-workspace owner's plan {@code max_workspaces} cap by calling
 * {@link OrganizationService#reconcileWorkspacePauseState} for each. Reusable entry point shared by
 * the CE-only {@link WorkspacePauseReconcileScheduler} (autonomous trigger) and the internal
 * reconcile endpoint (ops + deterministic e2e).
 *
 * <p><b>Why CE needs this:</b> in Cloud the pause reconcile is event-driven - it runs from the
 * Stripe webhook ({@code SubscriptionService.onSubscriptionUpsert}) and the admin plan grant
 * ({@code AdminPlanService.assignPlan}). In CE neither fires: there is no local Stripe, and the
 * admin plan endpoint is disabled (503). A CE user's plan is governed by the linked cloud account
 * and read live via {@code CloudPlanAccess.governingPlanCode}, so the cap is always current - but
 * nothing calls the reconcile to flip {@code paused_at} on the existing over-cap workspaces. This
 * reconciler is that missing trigger: because {@code reconcileWorkspacePauseState} resolves the cap
 * through the (cloud-aware) {@code resolveMaxWorkspaces}, simply re-running it applies the current
 * cloud plan. It is idempotent - it only writes rows whose pause state actually changes.
 *
 * <p>Each owner is reconciled through the proxied {@link OrganizationService} bean so the
 * per-owner {@code @Transactional} boundary is honored (one failing owner can never roll back the
 * others, mirroring {@code WorkspacePurgeScheduler}).
 */
@Component
public class WorkspacePauseReconciler {

    private static final Logger log = LoggerFactory.getLogger(WorkspacePauseReconciler.class);

    private final OrganizationRepository organizationRepository;
    private final OrganizationService organizationService;

    public WorkspacePauseReconciler(OrganizationRepository organizationRepository,
                                    OrganizationService organizationService) {
        this.organizationRepository = organizationRepository;
        this.organizationService = organizationService;
    }

    /**
     * Reconcile the workspace pause state of every owner that has at least one active non-personal
     * workspace. Returns the number of owners processed. Never throws: a per-owner failure is logged
     * and skipped so one bad owner can't stall the sweep.
     */
    public int reconcileAll() {
        List<Long> ownerIds = organizationRepository.findDistinctOwnerIdsWithActiveNonPersonalWorkspaces();
        if (ownerIds.isEmpty()) {
            return 0;
        }
        int processed = 0;
        for (Long ownerId : ownerIds) {
            try {
                organizationService.reconcileWorkspacePauseState(ownerId);
                processed++;
            } catch (Exception e) {
                log.error("Workspace pause reconcile failed for owner {}: {}", ownerId, e.getMessage(), e);
            }
        }
        log.debug("Workspace pause reconcile sweep: {} owner(s) processed", processed);
        return processed;
    }
}
