package com.apimarketplace.common.credit;

import com.apimarketplace.common.web.TenantResolver;

/**
 * Handler for persisting failed credit consumption attempts to a dead-letter table.
 * Each service implements this with its own JPA repository and schema.
 *
 * <p>Phase 6 MIGRATION_ORG_ID_NOT_NULL (CC-2, 2026-05-19): dead-letter rows
 * carry {@code organization_id NOT NULL} post-V261. Async/daemon writers
 * (retry exhaustion in {@code consumeCreditsAsync}, signal resume, agent
 * worker) execute off the servlet thread so the listener ThreadLocal
 * fallback would resolve null. The 9-arg overload threads the orgId
 * explicitly; the 8-arg overload is a backward-compat default that resolves
 * via {@link TenantResolver#currentRequestOrganizationId()} on the caller's
 * thread (HTTP-bound callers only).
 */
public interface CreditDeadLetterHandler {

    /**
     * Persist a failed consumption attempt for later retry.
     *
     * <p>Backward-compat 8-arg form: resolves {@code organizationId} via
     * {@link TenantResolver#currentRequestOrganizationId()} at the call
     * site. Callers running on async/daemon threads MUST use the 9-arg
     * overload to pass an explicit {@code organizationId} captured at
     * dispatch time.
     */
    default void persistFailedConsumption(String tenantId, String sourceType, String sourceId,
                                          String provider, String model,
                                          Integer promptTokens, Integer completionTokens,
                                          String errorReason) {
        persistFailedConsumption(tenantId, sourceType, sourceId, provider, model,
                promptTokens, completionTokens, errorReason,
                TenantResolver.currentRequestOrganizationId());
    }

    /**
     * Persist a failed consumption attempt with an explicit {@code organizationId}.
     *
     * <p>Canonical 9-arg signature - implementations save this directly to the
     * V261-NOT-NULL column. Callers on async/daemon threads MUST capture the
     * orgId at the dispatch site (where {@link TenantResolver#currentRequestOrganizationId()}
     * still resolves to the originating request) and pass it explicitly here.
     */
    void persistFailedConsumption(String tenantId, String sourceType, String sourceId,
                                   String provider, String model,
                                   Integer promptTokens, Integer completionTokens,
                                   String errorReason, String organizationId);
}
