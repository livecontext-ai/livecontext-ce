package com.apimarketplace.common.billing.catalog;

import java.util.Collections;
import java.util.Map;

/**
 * Carries everything a {@link CatalogBillingStrategy} needs to decide whether
 * (and how much) to bill after a catalog tool execution.
 *
 * <p>Built post-call by every entry point that invokes a catalog tool - the
 * orchestrator's {@code CatalogToolsGateway} (workflow + agent-driven), and
 * the catalog-service-internal {@code CatalogExecuteModule} (chat-agent
 * dispatcher). Centralizing the dispatcher in {@code common-lib} (rather
 * than living in orchestrator-service only) is what lets both entry points
 * share the same strategy registry - single source of truth for "did
 * tool X cost the user credits?".
 *
 * <p><b>Idempotency identifiers</b> &mdash; {@code streamId / toolCallId /
 * runId} are extracted by callers from their execution context and propagated
 * here so strategies can build deterministic {@code sourceId}s via
 * {@code SourceIdBuilder} (chat-scope or workflow-scope). Missing values fall
 * back to {@code null}; strategies decide whether to skip billing or use a
 * UUID-fallback sourceId.
 *
 * @param tenantId           billable user (X-User-Id)
 * @param toolId             slug "{apiSlug}/{toolName}" or UUID
 * @param request            original tool params (the dispatcher is
 *                            read-only with respect to it)
 * @param response           catalog output (flattened, may include
 *                            {@code metadata.credentialSource})
 * @param credentialSource   {@code "user" | "platform" | null}; null
 *                            means catalog didn't (or couldn't) resolve a
 *                            credential - strategies should NOT bill in
 *                            that case (fail-safe).
 * @param streamId           chat stream id from credentials, if present
 * @param toolCallId         LLM-assigned tool-call id
 * @param runId              workflow run id (set when invocation came
 *                            from a workflow node - bypass dispatcher
 *                            since workflow-side billing handles it)
 * @param callIndex          for retries / loop iterations
 */
public record CatalogBillingContext(
        String tenantId,
        String toolId,
        Map<String, Object> request,
        Map<String, Object> response,
        String credentialSource,
        String streamId,
        String toolCallId,
        String runId,
        int callIndex
) {
    public CatalogBillingContext {
        if (request == null) request = Collections.emptyMap();
        if (response == null) response = Collections.emptyMap();
    }

    /** True iff the catalog resolved a platform-pool credential. */
    public boolean isPlatformKey() {
        return "platform".equalsIgnoreCase(credentialSource);
    }

    /** True iff the catalog resolved a user-pool credential (BYOK). */
    public boolean isUserKey() {
        return "user".equalsIgnoreCase(credentialSource);
    }

    /** True iff this call originated from a workflow run. Catalog-side
     * billing in V148+ uses the RUN scope (see CatalogToolBillingService)
     * regardless of origin; this flag is informational. */
    public boolean isWorkflowOrigin() {
        return runId != null && !runId.isBlank();
    }
}
