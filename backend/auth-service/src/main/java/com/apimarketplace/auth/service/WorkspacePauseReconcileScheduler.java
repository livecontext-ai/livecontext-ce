package com.apimarketplace.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * CE-only autonomous trigger for workspace pause reconciliation.
 *
 * <p>In Cloud, the over-cap workspace pause is event-driven (Stripe webhook + admin plan grant call
 * {@code OrganizationService.reconcileWorkspacePauseState} directly). CE has neither event - no
 * local Stripe, and the admin plan endpoint returns 503 - yet a CE user's plan is governed by the
 * linked cloud account and can change there at any time. Without a trigger, a cloud-side downgrade
 * would shrink the workspace cap (blocking NEW creation immediately, since the cap is read live) but
 * leave the existing over-cap workspaces enterable forever.
 *
 * <p>This scheduler closes that gap by periodically re-running {@link WorkspacePauseReconciler}: the
 * reconcile resolves the cap through the cloud-aware {@code resolveMaxWorkspaces}, so re-running it
 * applies the current cloud plan. It is idempotent (writes only on a real state change) and only
 * visits owners that actually have a non-personal workspace, so the steady-state cost is one cheap
 * sweep per interval. Gated to {@code deployment.mode=monolith} so it never runs in Cloud, where the
 * event-driven path already covers every plan change.
 */
@Component
@ConditionalOnProperty(name = "deployment.mode", havingValue = "monolith")
public class WorkspacePauseReconcileScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkspacePauseReconcileScheduler.class);

    private final WorkspacePauseReconciler reconciler;

    public WorkspacePauseReconcileScheduler(WorkspacePauseReconciler reconciler) {
        this.reconciler = reconciler;
    }

    /**
     * Re-apply the cloud-governing plan's workspace cap for every multi-workspace owner. Runs every
     * {@code workspace.reconcile.fixed-delay-ms} (default 5 min, matching the cloud-link heartbeat),
     * after an initial delay so a cold CE start settles first. Never throws - the reconciler swallows
     * per-owner failures.
     */
    @Scheduled(
            fixedDelayString = "${workspace.reconcile.fixed-delay-ms:300000}",
            initialDelayString = "${workspace.reconcile.initial-delay-ms:60000}")
    public void reconcileOverCapWorkspaces() {
        try {
            int processed = reconciler.reconcileAll();
            if (processed > 0) {
                log.debug("CE workspace pause reconcile sweep processed {} owner(s)", processed);
            }
        } catch (Exception e) {
            log.error("CE workspace pause reconcile sweep failed: {}", e.getMessage(), e);
        }
    }
}
