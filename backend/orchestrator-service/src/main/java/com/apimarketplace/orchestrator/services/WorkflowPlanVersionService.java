package com.apimarketplace.orchestrator.services;

import com.apimarketplace.common.plan.PlanLayoutNormalizer;
import com.apimarketplace.common.plan.PlanStripUtils;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.domain.WorkflowPlanVersionEntity;
import com.apimarketplace.orchestrator.domain.workflow.WorkflowPlan;
import com.apimarketplace.orchestrator.repository.WorkflowPlanVersionRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.dao.DataIntegrityViolationException;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Service for managing workflow plan version history.
 * Creates versions of the NEW plan after each save, and supports restore/rename operations.
 * The current plan is ALWAYS the latest version - no ambiguity.
 */
@Service
@Transactional
public class WorkflowPlanVersionService {

    private static final Logger logger = LoggerFactory.getLogger(WorkflowPlanVersionService.class);

    private final WorkflowPlanVersionRepository versionRepository;
    private final WorkflowRepository workflowRepository;
    private final StorageBreakdownService breakdownService;
    private final ObjectMapper objectMapper;

    @Value("${workflow.versioning.max-versions:20}")
    private int maxVersions;

    public WorkflowPlanVersionService(WorkflowPlanVersionRepository versionRepository,
                                       WorkflowRepository workflowRepository,
                                       StorageBreakdownService breakdownService,
                                       ObjectMapper objectMapper) {
        this.versionRepository = versionRepository;
        this.workflowRepository = workflowRepository;
        this.breakdownService = breakdownService;
        this.objectMapper = objectMapper;
    }

    private static final int MAX_VERSION_RETRIES = 3;

    /**
     * Create a new version of the plan (the NEW plan, after it has been saved to workflows.plan).
     * Only creates a version if the plan actually changed compared to the latest version.
     * Returns the version number (new or existing if no change).
     *
     * @param workflowId the workflow ID
     * @param plan       the new plan to version (already saved to workflows.plan)
     * @param userId     the user performing the save
     * @param label      optional label for the version (e.g., "Agent session"), nullable
     * @return the version number assigned (or existing max if plan unchanged)
     */
    public int createVersion(UUID workflowId, Map<String, Object> plan, String userId, String label) {
        // Check if the plan actually changed compared to the latest version.
        // "Changed" means the WORKFLOW changed, not the canvas layout: a moved node
        // must not mint a version, or the next execution keys on that new version
        // and starts a second run instead of accumulating an epoch into the live one.
        int currentMax = versionRepository.getMaxVersion(workflowId).orElse(0);
        if (currentMax > 0) {
            Optional<WorkflowPlanVersionEntity> latestVersion =
                    versionRepository.findByWorkflowIdAndVersion(workflowId, currentMax);
            if (latestVersion.isPresent() && plansAreSemanticallyEqual(plan, latestVersion.get().getPlan())) {
                WorkflowPlanVersionEntity latest = latestVersion.get();
                boolean layoutOnlyDrift = !plansAreEqual(plan, latest.getPlan());
                if (!layoutOnlyDrift) {
                    logger.debug("Plan unchanged for workflow {}, skipping version creation (current: v{})",
                            workflowId, currentMax);
                    return currentMax;
                }

                // A PINNED row is immutable: the pin is a contract that this exact
                // content stays reproducible, and a run stamped with the pinned number
                // can shadow the real production run (see EditorRunResolver's
                // production-run guard). Never refresh it in place, and never report
                // the pin as this save's version - fall through and mint a draft, which
                // is what the pinned lane of resolveContentVersionForExecution expects
                // from this method.
                Integer pinnedVersion = workflowRepository.findById(workflowId)
                        .map(WorkflowEntity::getPinnedVersion)
                        .orElse(null);
                if (pinnedVersion != null && pinnedVersion.intValue() == currentMax) {
                    logger.debug("Layout-only change for workflow {} but v{} is PINNED - minting a draft instead of "
                            + "touching the pinned row", workflowId, currentMax);
                } else {
                    // Layout-only drift refreshes the stored row IN PLACE (same number)
                    // so the version history keeps the coordinates a restore would put
                    // back on the canvas. A label supplied by this save fills an EMPTY
                    // one (the row would otherwise stay nameless forever, since this
                    // path is the only write it gets) but never overwrites a name that
                    // is already there - a background auto-layout must not silently
                    // relabel a version the user named.
                    latest.setPlan(new HashMap<>(plan));
                    if (label != null && !label.isBlank()
                            && (latest.getLabel() == null || latest.getLabel().isBlank())) {
                        latest.setLabel(label);
                    }
                    versionRepository.save(latest);
                    logger.debug("Layout-only change for workflow {}: refreshed v{} in place, no new version",
                            workflowId, currentMax);
                    return currentMax;
                }
            }
        }

        // Plan changed (or no versions yet) - create a new version
        for (int attempt = 0; attempt < MAX_VERSION_RETRIES; attempt++) {
            try {
                int nextVersion = versionRepository.getMaxVersion(workflowId).orElse(0) + 1;

                WorkflowPlanVersionEntity versionEntity = new WorkflowPlanVersionEntity(
                        workflowId, nextVersion, plan, userId
                );
                if (label != null && !label.isBlank()) {
                    versionEntity.setLabel(label);
                }
                versionRepository.save(versionEntity);
                // Issue #149 - look up the workflow to thread orgId into the rollup so
                // team-workspace CONFIGURATION usage reflects version history growth.
                String orgId = workflowRepository.findById(workflowId)
                        .map(WorkflowEntity::getOrganizationId)
                        .orElse(null);
                breakdownService.trackSave(userId, "CONFIGURATION", estimatePlanSize(plan), orgId);

                logger.info("Created version {} for workflow {}{}", nextVersion, workflowId,
                        label != null && !label.isBlank() ? " (label: " + label + ")" : "");

                // Purge old versions beyond retention limit
                purgeOldVersions(workflowId);

                return nextVersion;
            } catch (DataIntegrityViolationException e) {
                if (attempt < MAX_VERSION_RETRIES - 1) {
                    logger.warn("Version number collision for workflow {} (attempt {}), retrying...",
                            workflowId, attempt + 1);
                } else {
                    logger.error("Failed to create version for workflow {} after {} attempts",
                            workflowId, MAX_VERSION_RETRIES);
                    throw e;
                }
            }
        }
        // Unreachable, but required by compiler
        throw new IllegalStateException("Failed to create version after retries");
    }

    /**
     * Create a new version without a label.
     */
    public int createVersion(UUID workflowId, Map<String, Object> plan, String userId) {
        return createVersion(workflowId, plan, userId, null);
    }

    /**
     * Same as {@link #createVersion(UUID, Map, String, String)} but in its OWN
     * transaction ({@code REQUIRES_NEW}).
     *
     * <p>Use from inside a caller-owned transaction when a versioning failure must
     * degrade (WARN + keep the legacy version stamp) instead of poisoning the caller:
     * with the default {@code REQUIRED} propagation, an exception crossing this bean's
     * proxy marks the shared transaction rollback-only even when the caller catches
     * it - the caller's commit then fails with {@code UnexpectedRollbackException},
     * turning the documented degrade into a request-level failure.
     *
     * <p>Trade-off: the version row commits even if the caller's transaction later
     * rolls back - harmless for the append-only history (dedupe absorbs replays).
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public int createVersionInNewTransaction(UUID workflowId, Map<String, Object> plan, String userId, String label) {
        return createVersion(workflowId, plan, userId, label);
    }

    /**
     * Execution-time version resolution - NEVER mints a new version number for an
     * unpinned workflow. Runs (trigger fires, re-fires, SBS steps, agent fires) must
     * stay on the same version across epochs; only explicit save paths advance the
     * version history.
     *
     * <p>Semantics ("latest plan overwrites the old one, same version"):
     * <ul>
     *   <li>plan content == latest stored version → return the latest number (read-only)</li>
     *   <li>plan content differs → overwrite the latest version's stored plan IN PLACE
     *       (same number, label preserved) so the run↔version content parity holds
     *       without inflating the history</li>
     *   <li>workflow is pinned and the plan content == the PINNED version's content
     *       (a production fire) → return the pinned number, read-only. Checked before
     *       the latest-row comparison so a pinned run is never mislabelled with the
     *       latest number nor allowed to clobber a newer draft</li>
     *   <li>plan content == some OLDER version's content (a version replay, or a
     *       canvas undone back to an earlier state) → return that version's number,
     *       read-only. Without this the older plan would be written over the latest
     *       row, destroying it</li>
     *   <li>latest version is the workflow's pinned version → the pinned row is
     *       immutable: fall back to {@link #createVersion} (mints a draft version)</li>
     *   <li>no version history yet → {@link #createVersion} seeds v1</li>
     * </ul>
     *
     * @param workflowId the workflow ID
     * @param plan       the plan the run is about to execute
     * @param userId     the user on whose behalf the run executes
     * @return the version number whose stored content now equals {@code plan}
     */
    public int resolveContentVersionForExecution(UUID workflowId, Map<String, Object> plan, String userId) {
        int currentMax = versionRepository.getMaxVersion(workflowId).orElse(0);
        if (currentMax == 0) {
            // First version ever - seed the history.
            return createVersion(workflowId, plan, userId, null);
        }

        Optional<WorkflowPlanVersionEntity> latestOpt =
                versionRepository.findByWorkflowIdAndVersion(workflowId, currentMax);
        if (latestOpt.isEmpty()) {
            return createVersion(workflowId, plan, userId, null);
        }

        WorkflowPlanVersionEntity latest = latestOpt.get();
        // Scalar projection rather than findById: only the pin number is needed, and
        // two of the four call sites resolve under REQUIRES_NEW where a fresh
        // persistence context would hydrate WorkflowEntity's JSONB columns (the full
        // plan among them) from the database. On the other two the entity is already
        // managed, so the projection costs one extra round-trip there.
        Integer pinnedVersion = workflowRepository.findPinnedVersionById(workflowId).orElse(null);

        // A pinned workflow's runs execute the PINNED content, which is not
        // necessarily the latest row. Resolve those against the pinned row FIRST,
        // otherwise the pinned plan is mistaken for drifted canvas content: the run
        // gets stamped with the latest number (a version it does not execute) and the
        // newer draft is overwritten in place with the pinned plan - silent data loss
        // on the user's work in progress. Read-only: no row is touched.
        //
        // Deliberately ordered BEFORE the latest-row comparison. This is NOT free:
        // the pin lookup now runs on every resolve (pre-fix it ran only once content
        // had drifted), and when a pin is present the branch additionally fetches the
        // pinned row and runs a full plan comparison that is discarded on a miss -
        // more work than the entity hydration the scalar projection above avoids.
        // Correctness alone justifies the ordering:
        // ProductionRunResolver.isAllowedForProduction gates the next production fire
        // on run.planVersion == workflow.pinnedVersion, so a run stamped with the
        // draft number is REFUSED at that chokepoint - the mislabel silently breaks
        // production, it does not merely look wrong in the history. A draft carrying
        // byte-identical content to the pin is exactly the case that must still
        // answer with the pinned number.
        boolean matchesLatestExactly = plansAreEqual(plan, latest.getPlan());
        if (pinnedVersion != null && pinnedVersion.intValue() != currentMax) {
            Optional<WorkflowPlanVersionEntity> pinnedOpt =
                    versionRepository.findByWorkflowIdAndVersion(workflowId, pinnedVersion);
            if (pinnedOpt.isPresent()) {
                // An EXACT match on the pin wins outright, including when the draft
                // happens to carry the same bytes - the run does execute the pin.
                if (plansAreEqual(plan, pinnedOpt.get().getPlan())) {
                    return pinnedVersion;
                }
                // A NORMALIZED match - trigger refs re-armed, or canvas coordinates
                // drifted - only counts when the plan is not exactly the draft.
                //
                // Layout is normalized here for the same reason trigger refs are: a
                // production fire whose node coordinates drifted executes the pinned
                // LOGIC, and stamping it with the draft number gets it refused at
                // ProductionRunResolver.isAllowedForProduction.
                //
                // But it must stay behind `!matchesLatestExactly`. Otherwise an editor
                // run on the CURRENT DRAFT of a pinned workflow - where the draft
                // differs from the pin by nothing but a re-armed trigger ref or a moved
                // node - would be stamped with the PIN, and a fresh editor run at the
                // pinned version can shadow the real production run on the next fire
                // (see EditorRunResolver's CREATED hazard). Exact beats normalized: the
                // draft is what this run executes.
                // ONE normalization that drops BOTH, not two predicates OR'd together:
                // a re-armed schedule and a moved node are independent, everyday events,
                // and a plan carrying both matched neither half.
                if (!matchesLatestExactly
                        && plansMatchAllowingTriggerRebinding(plan, pinnedOpt.get().getPlan())) {
                    return pinnedVersion;
                }
            } else {
                // The pin points at a row that no longer exists (purge protection
                // bypassed, manual delete, restore gone wrong). Execution continues on
                // the legacy lane below, which may overwrite the latest draft - the
                // exact data loss this block prevents. Loud because it is unreachable
                // through any supported flow.
                logger.warn("Pinned version {} row is missing for workflow {} - cannot verify whether this run "
                        + "executes the pinned plan; falling through to latest-version resolution", pinnedVersion, workflowId);
            }
        }

        if (matchesLatestExactly) {
            return currentMax;
        }

        // Pinned rows are immutable - a pin is a contract that this exact content
        // stays reproducible. If the latest version IS the pinned one, mint a draft
        // version instead of mutating it (editor runs on pinned workflows).
        if (pinnedVersion != null && pinnedVersion.intValue() == currentMax) {
            // Same restore normalization as the pin-trails-draft branch above, which
            // cannot run here (it is gated on pinned != max). Restoring a version
            // strips standalone trigger refs regardless of whether the pin happens to
            // be the latest, so without this a restore of the PINNED version onto a
            // pin == max workflow misses the byte comparison and mints a spurious
            // draft - stamping the run one above the pin and getting it refused at
            // the production chokepoint, the exact failure this method now prevents.
            if (plansMatchIgnoringStandaloneRefs(plan, latest.getPlan())) {
                return currentMax;
            }
            Optional<Integer> replayedPinned = findVersionWithMatchingContent(workflowId, plan, currentMax);
            if (replayedPinned.isPresent()) {
                return replayedPinned.get();
            }
            return createVersion(workflowId, plan, userId, null);
        }

        // Last guard before mutating anything: the executing plan may BE an older
        // version's content rather than drifted canvas content. That is what a version
        // replay hands us (findOrCreateRunForVersion -> createExecution ->
        // recordWorkflowStart -> autoArchiveExecutionPlan passes the HISTORICAL plan),
        // and it also happens when a canvas is undone back to an earlier state.
        // Overwriting the latest row with it would destroy that row's content - the
        // unpinned sibling of the pinned data loss above. Resolving to the matching
        // historical number instead is both content-true and non-destructive.
        //
        // Deliberately the LAST check: it loads the version bodies, so it only runs on
        // the rare path where a write was about to happen anyway.
        //
        // Gated on NOT being a re-bound copy of `latest`. The caller above only ruled
        // `latest` out with a PLAIN comparison, while the scan matches with trigger
        // refs normalized away - so without this gate a plan that is `latest` with a
        // re-armed scheduleId would skip `latest` (excluded from the scan) and match
        // an older same-logic version, stamping the run with a stale number. That is
        // the unpinned refresh lane's normal traffic, not an edge case. Falling
        // through instead refreshes the row in place, which is the pre-existing
        // behaviour for a re-bind and keeps the stored ref current.
        if (!plansMatchIgnoringStandaloneRefs(plan, latest.getPlan())) {
            Optional<Integer> replayed = findVersionWithMatchingContent(workflowId, plan, currentMax);
            if (replayed.isPresent()) {
                return replayed.get();
            }
        }

        latest.setPlan(new HashMap<>(plan));
        versionRepository.save(latest);
        logger.info("Overwrote version {} content in place for workflow {} (execution-time refresh, no new version)",
                currentMax, workflowId);
        return currentMax;
    }

    /**
     * Same as {@link #resolveContentVersionForExecution(UUID, Map, String)} but in its
     * OWN transaction ({@code REQUIRES_NEW}) - same degrade rationale as
     * {@link #createVersionInNewTransaction}: a versioning failure inside a
     * caller-owned transaction must WARN and fall back, not poison the caller's
     * commit with {@code UnexpectedRollbackException}.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public int resolveContentVersionForExecutionInNewTransaction(UUID workflowId, Map<String, Object> plan, String userId) {
        return resolveContentVersionForExecution(workflowId, plan, userId);
    }

    /**
     * Create or update a session-scoped version.
     *
     * <p>If the latest version's label matches the given sessionId, the plan is
     * overwritten in-place (same version number). Otherwise a new version is created
     * with label = sessionId.
     *
     * <p>This avoids version spam when the agent makes many modifications in a single
     * session - only one version entry is used per session, updated on each change.
     *
     * @param workflowId the workflow ID
     * @param plan       the current plan
     * @param userId     the user performing the modification
     * @param sessionId  the builder session ID (e.g. "wb_a1b2c3d4e5f6")
     * @return the version number (new or updated)
     */
    public int createOrUpdateSessionVersion(UUID workflowId, Map<String, Object> plan,
                                             String userId, String sessionId) {
        int currentMax = versionRepository.getMaxVersion(workflowId).orElse(0);

        if (currentMax > 0) {
            Optional<WorkflowPlanVersionEntity> latestOpt =
                    versionRepository.findByWorkflowIdAndVersion(workflowId, currentMax);

            if (latestOpt.isPresent()) {
                WorkflowPlanVersionEntity latest = latestOpt.get();

                // Same session → overwrite in-place (no new version number)
                if (sessionId.equals(latest.getLabel())) {
                    if (plansAreEqual(plan, latest.getPlan())) {
                        logger.debug("Session version v{} unchanged for workflow {}, skipping update",
                                currentMax, workflowId);
                        return currentMax;
                    }
                    latest.setPlan(new HashMap<>(plan));
                    versionRepository.save(latest);
                    logger.debug("Updated session version v{} for workflow {} (session={})",
                            currentMax, workflowId, sessionId);
                    return currentMax;
                }

                // Different label but plan unchanged → skip. Layout-only drift counts
                // as unchanged here too (same reason as createVersion): a moved node
                // must not fork the version history and, with it, the live run.
                if (plansAreSemanticallyEqual(plan, latest.getPlan())) {
                    if (!plansAreEqual(plan, latest.getPlan())) {
                        // Same workflow, moved nodes: refresh the row in place, exactly
                        // as createVersion does. Skipping the write keeps the number
                        // stable but leaves stale coordinates behind, so restoring this
                        // version would drag the canvas back to a layout the user has
                        // already moved away from.
                        //
                        // A PINNED row is immutable, for the same reason it is in
                        // createVersion: the pin is a contract that this exact content
                        // stays reproducible, and an agent session's background
                        // auto-layout must not rewrite what the user pinned. Fall
                        // through to mint a draft instead.
                        Integer pinnedVersion = workflowRepository.findById(workflowId)
                                .map(WorkflowEntity::getPinnedVersion)
                                .orElse(null);
                        if (pinnedVersion == null || pinnedVersion.intValue() != currentMax) {
                            latest.setPlan(new HashMap<>(plan));
                            versionRepository.save(latest);
                            logger.debug("Layout-only change for workflow {} (session={}): refreshed v{} in place",
                                    workflowId, sessionId, currentMax);
                            return currentMax;
                        }
                        logger.debug("Layout-only change for workflow {} (session={}) but v{} is PINNED - "
                                + "minting a draft instead of touching the pinned row", workflowId, sessionId, currentMax);
                        return createVersion(workflowId, plan, userId, sessionId);
                    }
                    logger.debug("Plan unchanged for workflow {}, skipping version creation (current: v{})",
                            workflowId, currentMax);
                    return currentMax;
                }
            }
        }

        // Different session (or no versions yet) → create new version with sessionId as label
        return createVersion(workflowId, plan, userId, sessionId);
    }

    /**
     * Find an EXISTING version whose stored content equals {@code plan}, skipping
     * {@code excludeVersion} (already compared by the caller). Highest version first,
     * so the most recent match wins when a plan was saved more than once.
     *
     * <p>Exists to keep the resolver from mistaking "this run executes an older
     * version" for "the canvas drifted". The clearest case is a version replay, which
     * hands this service a historical plan; overwriting the latest row with it would
     * destroy that row. Returning the matching number is content-true and writes
     * nothing.
     *
     * <p>Loads the version bodies, so callers must only reach it on a path that was
     * about to write anyway.
     */
    private Optional<Integer> findVersionWithMatchingContent(UUID workflowId,
                                                             Map<String, Object> plan,
                                                             int excludeVersion) {
        try {
            for (WorkflowPlanVersionEntity candidate : versionRepository.findByWorkflowIdOrderByVersionDesc(workflowId)) {
                if (candidate.getVersion() == null || candidate.getVersion() == excludeVersion) {
                    continue;
                }
                if (plansMatchAllowingTriggerRebinding(plan, candidate.getPlan())) {
                    logger.info("Execution plan matches existing version {} for workflow {} - resolving to it instead of "
                            + "overwriting version {}", candidate.getVersion(), workflowId, excludeVersion);
                    return Optional.of(candidate.getVersion());
                }
            }
        } catch (Exception e) {
            // Never let this lookup break execution - degrade to the legacy behaviour.
            logger.warn("Could not scan version history for a content match on workflow {}: {}", workflowId, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Does {@code plan} execute the content of {@code storedPlan} (a version row)?
     *
     * <p>Byte equality first, then a retry with standalone-trigger back-references
     * ({@code scheduleId} / {@code webhookId} / {@code chatEndpointId} /
     * {@code formEndpointId}) normalized away on BOTH sides.
     *
     * <p>The retry is required, not defensive: restoring a version writes the
     * historical plan into {@code workflows.plan} through
     * {@link com.apimarketplace.common.plan.PlanStripUtils#deepCopyAndStrip}, which
     * removes exactly those keys. Restoring the PINNED version onto a pinned
     * workflow therefore produces a live plan that is the pinned plan minus its
     * trigger refs. On a plain equality check that would miss the pin and fall
     * through to the legacy lane, overwriting the newer draft - the very data loss
     * the pin branch exists to prevent, resurfacing in a subcase of the path that
     * most plausibly reaches it.
     *
     * <p>Those keys are live infrastructure bindings, not plan logic, so ignoring
     * them cannot make two semantically different plans compare equal.
     *
     * <p>The normalization is SYMMETRIC, so it covers more than restore: a plan that
     * gained a {@code scheduleId}, or whose {@code scheduleId} changed (re-armed
     * schedule, re-issued webhook), also matches the pin and resolves read-only where
     * a plain comparison would have minted a draft. That is intended - those are the
     * same plan bound to different infrastructure - and it is what keeps the run's
     * stamp equal to the pin across a trigger re-arm.
     */
    private boolean plansMatchAllowingTriggerRebinding(Map<String, Object> plan, Map<String, Object> pinnedPlan) {
        return plansAreEqual(plan, pinnedPlan) || plansMatchIgnoringStandaloneRefs(plan, pinnedPlan);
    }

    /**
     * The normalized half of {@link #plansMatchAllowingTriggerRebinding}, callable on its own when
     * the plain equality check has already been performed and failed (the
     * {@code pinned == latest} lane, where {@code latest} IS the pinned row).
     *
     * <p>Drops BOTH kinds of noise in ONE pass: standalone trigger refs (re-armed
     * schedule, re-issued webhook, a restore that stripped them) and canvas layout
     * (see {@link PlanLayoutNormalizer}). Testing them as two separate predicates
     * missed the plan that had drifted on both at once - a workflow whose schedule
     * was re-armed AND whose nodes were nudged is neither exotic nor rare, and it
     * got stamped with the draft number, which
     * {@code ProductionRunResolver.isAllowedForProduction} then refuses.
     */
    private boolean plansMatchIgnoringStandaloneRefs(Map<String, Object> plan, Map<String, Object> pinnedPlan) {
        if (plan == null || pinnedPlan == null) {
            return false;
        }
        try {
            return plansAreEqual(
                    PlanStripUtils.deepCopyAndStrip(PlanLayoutNormalizer.withoutLayout(plan), objectMapper),
                    PlanStripUtils.deepCopyAndStrip(PlanLayoutNormalizer.withoutLayout(pinnedPlan), objectMapper));
        } catch (Exception e) {
            // Never let a normalization failure decide the pin question - fall back
            // to "not the pinned plan", i.e. the pre-existing behaviour.
            logger.warn("Could not normalize plans for the pinned-version comparison: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Compare two plans for equality, ignoring transient fields (tenant_id, timestamps).
     * Uses Jackson for normalized deep comparison.
     */
    /**
     * Same as {@link #plansAreEqual} but blind to canvas LAYOUT (node coordinates).
     *
     * <p>This is the predicate for "is this a different PLAN?", as opposed to
     * {@link #plansAreEqual}, which answers "are these bytes identical?" and stays
     * the right question for "do I need to persist this?".
     *
     * <p>The distinction is load-bearing. A node nudged by two pixels - or an
     * auto-layout pass re-centring the graph - made the save path mint a NEW
     * VERSION; the next execution keyed its run on that new version, so it created
     * a SECOND run instead of accumulating an epoch into the live one. Two runs of
     * an unedited workflow, neither accumulating the other's epochs. Layout still
     * gets saved (see {@link PlanLayoutNormalizer}); it just stops forking history.
     */
    public boolean plansAreSemanticallyEqual(Map<String, Object> plan1, Map<String, Object> plan2) {
        if (plan1 == plan2) return true;
        if (plan1 == null || plan2 == null) return false;
        try {
            return plansAreEqual(
                    PlanLayoutNormalizer.withoutLayout(plan1),
                    PlanLayoutNormalizer.withoutLayout(plan2));
        } catch (Exception e) {
            // A normalization failure must never silently merge two different plans:
            // fall back to the strict comparison (the pre-existing behaviour).
            logger.warn("Could not normalize plan layout for comparison, falling back to strict equality: {}",
                    e.getMessage());
            return plansAreEqual(plan1, plan2);
        }
    }

    public boolean plansAreEqual(Map<String, Object> plan1, Map<String, Object> plan2) {
        if (plan1 == plan2) return true;
        if (plan1 == null || plan2 == null) return false;

        try {
            // Normalize both plans via Jackson to handle type differences (Integer vs Long, etc.)
            var node1 = objectMapper.valueToTree(stripTransientFields(plan1));
            var node2 = objectMapper.valueToTree(stripTransientFields(plan2));
            boolean equal = node1.equals(node2);
            if (!equal) {
                // Log the diff to diagnose spurious version creation
                logPlanDiff(node1, node2);
            }
            return equal;
        } catch (Exception e) {
            logger.warn("Error comparing plans, treating as different: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Log differences between two plan JSON trees to diagnose spurious version creation.
     */
    private void logPlanDiff(com.fasterxml.jackson.databind.JsonNode node1, com.fasterxml.jackson.databind.JsonNode node2) {
        try {
            // Compare top-level keys first
            var it1 = node1.fieldNames();
            var it2 = node2.fieldNames();
            Set<String> keys1 = new LinkedHashSet<>();
            Set<String> keys2 = new LinkedHashSet<>();
            it1.forEachRemaining(keys1::add);
            it2.forEachRemaining(keys2::add);

            // Keys only in one side
            Set<String> onlyIn1 = new LinkedHashSet<>(keys1);
            onlyIn1.removeAll(keys2);
            Set<String> onlyIn2 = new LinkedHashSet<>(keys2);
            onlyIn2.removeAll(keys1);
            if (!onlyIn1.isEmpty()) logger.debug("[PlanDiff] Keys only in canvas: {}", onlyIn1);
            if (!onlyIn2.isEmpty()) logger.debug("[PlanDiff] Keys only in stored: {}", onlyIn2);

            // Common keys with different values
            for (String key : keys1) {
                if (keys2.contains(key)) {
                    var v1 = node1.get(key);
                    var v2 = node2.get(key);
                    if (!v1.equals(v2)) {
                        // For arrays, log size diff and first differing element
                        if (v1.isArray() && v2.isArray()) {
                            if (v1.size() != v2.size()) {
                                logger.debug("[PlanDiff] Key '{}': array size {} vs {}", key, v1.size(), v2.size());
                            } else {
                                for (int i = 0; i < v1.size(); i++) {
                                    if (!v1.get(i).equals(v2.get(i))) {
                                        logger.debug("[PlanDiff] Key '{}[{}]' differs: canvas={} | stored={}",
                                            key, i,
                                            v1.get(i).toString().substring(0, Math.min(300, v1.get(i).toString().length())),
                                            v2.get(i).toString().substring(0, Math.min(300, v2.get(i).toString().length())));
                                        break; // only log first diff
                                    }
                                }
                            }
                        } else {
                            logger.debug("[PlanDiff] Key '{}' differs: canvas={} | stored={}",
                                key,
                                v1.toString().substring(0, Math.min(200, v1.toString().length())),
                                v2.toString().substring(0, Math.min(200, v2.toString().length())));
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("[PlanDiff] Error logging diff: {}", e.getMessage());
        }
    }

    /**
     * Strip fields that should not trigger a new version (cosmetic/transient).
     */
    private Map<String, Object> stripTransientFields(Map<String, Object> plan) {
        Map<String, Object> stripped = new HashMap<>(plan);
        stripped.remove("tenant_id");
        return stripped;
    }

    /**
     * Get the current (latest) version number for a workflow.
     */
    @Transactional(readOnly = true)
    public int getCurrentVersion(UUID workflowId) {
        return versionRepository.getMaxVersion(workflowId).orElse(0);
    }

    /**
     * List all versions for a workflow (metadata only, no plan body).
     */
    @Transactional(readOnly = true)
    public List<WorkflowPlanVersionEntity> listVersions(UUID workflowId) {
        return versionRepository.findByWorkflowIdOrderByVersionDesc(workflowId);
    }

    /**
     * Get a specific version with its full plan.
     */
    @Transactional(readOnly = true)
    public Optional<WorkflowPlanVersionEntity> getVersion(UUID workflowId, int version) {
        return versionRepository.findByWorkflowIdAndVersion(workflowId, version);
    }

    /**
     * The plan a run report is built against, and whether it is the run's own plan version.
     * {@code prunedVersion} is set when the run points at a version that retention
     * ({@code workflow.versioning.max-versions}) has since deleted, so the plan is the
     * workflow's current one.
     */
    public record RunPlan(WorkflowPlan plan, Integer prunedVersion) {
        /** Returns the report with a {@code plan_note} when the run's own version is gone. */
        public Map<String, Object> annotate(Map<String, Object> report) {
            if (prunedVersion == null || report == null) return report;
            Map<String, Object> out = new java.util.LinkedHashMap<>(report);
            out.put("plan_note", "Plan version " + prunedVersion + " used by this run is no longer kept, "
                    + "so node names and structure come from the current plan and may differ "
                    + "from what this run executed.");
            return out;
        }
    }

    /**
     * Resolve the plan a run executed: its own plan version when still kept, else the workflow's
     * current plan. Takes ids, never the run's lazy {@code WorkflowEntity}: the agent tool modules
     * call this outside any session, and reading {@code getPlan()} on that proxy threw
     * LazyInitializationException for every run whose version had been pruned.
     */
    @Transactional(readOnly = true)
    public RunPlan resolvePlanForRun(UUID workflowId, Integer planVersion, String tenantId) {
        if (planVersion != null) {
            Optional<WorkflowPlanVersionEntity> version = versionRepository.findByWorkflowIdAndVersion(workflowId, planVersion);
            if (version.isPresent()) {
                return new RunPlan(WorkflowPlan.fromMap(version.get().getPlan(), workflowId.toString(), tenantId), null);
            }
        }
        WorkflowEntity current = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new IllegalStateException("Workflow not found: " + workflowId));
        return new RunPlan(WorkflowPlan.fromMap(current.getPlan(), workflowId.toString(), tenantId), planVersion);
    }

    /**
     * Rename a version (set/update label).
     */
    public WorkflowPlanVersionEntity renameVersion(UUID workflowId, int version, String label) {
        WorkflowPlanVersionEntity entity = versionRepository.findByWorkflowIdAndVersion(workflowId, version)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Version " + version + " not found for workflow " + workflowId));

        entity.setLabel(label);
        logger.info("Renamed workflow {} version {} to '{}'", workflowId, version, label);
        return versionRepository.save(entity);
    }

    /**
     * Purge old versions beyond retention limit.
     * Protects the pinned version from deletion (even if it's beyond the retention window).
     */
    private void purgeOldVersions(UUID workflowId) {
        long count = versionRepository.countByWorkflowId(workflowId);
        if (count > maxVersions) {
            Integer pinnedVersion = workflowRepository.findById(workflowId)
                    .map(WorkflowEntity::getPinnedVersion)
                    .orElse(null);

            int deleted;
            if (pinnedVersion != null) {
                deleted = versionRepository.purgeOldVersionsExcluding(workflowId, maxVersions, pinnedVersion);
            } else {
                deleted = versionRepository.purgeOldVersions(workflowId, maxVersions);
            }
            if (deleted > 0) {
                logger.info("Purged {} old version(s) for workflow {}{}", deleted, workflowId,
                        pinnedVersion != null ? " (protected pinned v" + pinnedVersion + ")" : "");
            }
        }
    }

    private long estimatePlanSize(Map<String, Object> plan) {
        if (plan == null || plan.isEmpty()) return 0;
        try {
            return objectMapper.writeValueAsBytes(plan).length;
        } catch (Exception e) {
            return 0;
        }
    }
}
