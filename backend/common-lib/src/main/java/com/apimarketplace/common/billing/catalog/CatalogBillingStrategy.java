package com.apimarketplace.common.billing.catalog;

import java.util.Set;

/**
 * Strategy plug-in for catalog tool billing. Each strategy declares which
 * {@code toolId}(s) it owns and implements {@link #bill}.
 *
 * <p><b>Registration model</b> - strategies are Spring beans. The
 * dispatcher builds a {@code Map<String, CatalogBillingStrategy>} keyed on
 * {@link #handledToolIds} at startup; bean validation enforces uniqueness
 * of toolIds across strategies. This is deterministic dispatch: one
 * tool → one strategy. Tools without a strategy → no billing (debug log).
 *
 * <p><b>Cross-service registry</b> - this interface lives in {@code
 * common-lib} so both the orchestrator's {@code CatalogToolsGateway} (used
 * by image-gen and workflow nodes) and the catalog-service's {@code
 * CatalogExecuteModule} (used by chat agents) inject the same dispatcher
 * bean and consult the same set of strategies. Without this, the chat-agent
 * path would silently bypass billing entirely.
 *
 * <p><b>Adding a new billable tool</b> = adding a single Spring bean. No
 * dispatcher edit, no enum change.
 *
 * <p><b>Failure mode</b> - exceptions thrown by {@link #bill} are caught
 * and logged by the dispatcher; they NEVER propagate to the catalog
 * caller. Mirrors the async-billing posture of {@code WebSearchModule}.
 */
public interface CatalogBillingStrategy {

    /**
     * Tool slugs this strategy owns. The dispatcher's startup validator
     * fails if two strategies claim the same id - keeps dispatch
     * deterministic.
     */
    Set<String> handledToolIds();

    /**
     * Fire the billing call. The strategy decides:
     * <ul>
     *   <li>Whether the response shape is bill-worthy (e.g. count actual
     *       images returned for image-gen).</li>
     *   <li>How to handle BYOK ({@link CatalogBillingContext#isUserKey()})
     *       - typically write a 0-cost trace row.</li>
     *   <li>Which {@code consumeFor*} entry-point to call (sync vs async,
     *       sourceType, units).</li>
     * </ul>
     *
     * <p>Strategies SHOULD skip billing entirely when
     * {@link CatalogBillingContext#isWorkflowOrigin()} is true - the
     * workflow path's {@code StepCompletionOrchestrator} owns that case.
     */
    void bill(CatalogBillingContext context);
}
