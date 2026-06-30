package com.apimarketplace.orchestrator.services.notification;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves user-facing display names for notification subjects.
 *
 * <p>Each {@code subject_type} (V1: {@code WORKFLOW}; future: {@code APPLICATION},
 * {@code AGENT_TASK}, {@code CREDENTIAL}, …) has its own resolver because the
 * source of the name lives in a different schema or service. Spring discovers
 * all {@code SubjectNameResolver} beans and {@link NotificationService}
 * dispatches per-row by {@link #subjectType()}.
 *
 * <p>Implementations MUST batch-resolve to keep the bell read path bounded:
 * {@code resolveNames} is called once per subject_type per bell fetch, with
 * up to {@code MAX_BUCKETS = 20} ids.
 *
 * <p><b>Why a strategy instead of a switch in NotificationService:</b>
 * extending the bell to a new subject_type (e.g. AGENT_TASK in P3+) becomes
 * "add a new resolver bean" rather than "edit the read path and risk a
 * regression for unrelated categories." Spring DI auto-wires the new
 * resolver into the dispatcher map keyed on {@link #subjectType()}.
 */
public interface SubjectNameResolver {

    // ---- shared subject-type discriminator constants ----
    // Single source of truth for the subject_type column value. The emitter
    // writes one of these; the resolver dispatch keys on them. Defining
    // them once here prevents the drift mode where emitter and resolver
    // each carry a private copy and a typo in one silently misroutes the
    // bell read path. MUST match the V176 chk_notif_subject_type_v1
    // allow-list (uppercase, no underscores).
    String WORKFLOW    = "WORKFLOW";
    String TRIGGER     = "TRIGGER";
    String CREDENTIAL  = "CREDENTIAL";
    String AGENT_TASK  = "AGENT_TASK";
    String APPLICATION = "APPLICATION";
    String ORG_INVITATION = "ORG_INVITATION";

    /**
     * Stable string discriminator matching the {@code subject_type} column
     * value (uppercase). Must be unique across all registered resolvers
     * - {@link NotificationService} throws on collision at construction.
     */
    String subjectType();

    /**
     * Look up display names for the given subject ids. Missing ids are
     * simply absent from the returned map; the caller renders them with
     * {@link NotificationService#DELETED_WORKFLOW_LABEL} as a placeholder.
     */
    Map<UUID, String> resolveNames(Set<UUID> subjectIds);
}
