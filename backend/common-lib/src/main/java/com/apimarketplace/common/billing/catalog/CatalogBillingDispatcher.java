package com.apimarketplace.common.billing.catalog;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Central dispatcher for catalog tool billing. Spring auto-injects the full
 * list of {@link CatalogBillingStrategy} beans; we index them by
 * {@code toolId} at startup and look up per-call.
 *
 * <p><b>Cross-service singleton</b> - this dispatcher lives in
 * {@code common-lib} so every service that calls a catalog tool imports the
 * same bean: orchestrator-service ({@code CatalogToolsGateway} for
 * image-gen + workflow nodes), catalog-service ({@code CatalogExecuteModule}
 * for chat-agent dispatch). The strategy registry is a single source of
 * truth - adding a new billable tool means adding one Spring bean
 * implementing {@link CatalogBillingStrategy}, automatically picked up by
 * every entry point.
 *
 * <p><b>Determinism</b> - exactly one strategy per toolId. Conflicting
 * registrations fail at startup ({@link #validateUnique}) so production
 * never silently picks the "wrong" strategy.
 *
 * <p><b>Failure isolation</b> - strategy exceptions are swallowed; the
 * caller never sees a billing-induced error. The strategy is responsible
 * for its own retry / dead-letter via the async credit client. This
 * mirrors the {@code WebSearchModule} posture.
 *
 * <p><b>Workflow bypass</b> - when
 * {@link CatalogBillingContext#isWorkflowOrigin()} is true, the dispatcher
 * skips billing entirely. The workflow path bills its own way:
 * <ul>
 *   <li>{@code WORKFLOW_NODE} (flat 1 credit per node) via
 *       {@code StepCompletionOrchestrator.consumeCreditForNode}.</li>
 *   <li>{@code consumePlatformMarkup} (per-tool platform-credential
 *       markup) when the node uses a platform credential.</li>
 * </ul>
 * Per-image cost is NOT billed in the workflow path today (out of scope
 * for v1 - workflow image-gen would need plan-time pricing resolution
 * like markup, deferred). Without this guard the dispatcher would double-
 * or triple-bill workflow nodes that use billable tools. The contract
 * requires {@code StepNode} and {@code FindNode} to inject
 * {@code __workflowRunId__} into {@code billingIdentifiers}.
 *
 * <p><b>Null credentialSource</b> - if the catalog didn't surface
 * {@code metadata.credentialSource} (older deploy, failure path before
 * credential resolution), the dispatcher logs at warn and skips. Fail-safe:
 * never bill on uncertainty.
 */
@Slf4j
@Service
public class CatalogBillingDispatcher {

    private final List<CatalogBillingStrategy> strategies;
    private final Map<String, CatalogBillingStrategy> byToolId = new HashMap<>();

    public CatalogBillingDispatcher(List<CatalogBillingStrategy> strategies) {
        this.strategies = strategies != null ? strategies : List.of();
    }

    @PostConstruct
    void index() {
        validateUnique(strategies);
        for (CatalogBillingStrategy strategy : strategies) {
            for (String toolId : strategy.handledToolIds()) {
                byToolId.put(toolId, strategy);
            }
        }
        log.info("[CatalogBillingDispatcher] Registered {} strategies covering {} tool ids",
                strategies.size(), byToolId.size());
    }

    private static void validateUnique(List<CatalogBillingStrategy> strategies) {
        Set<String> seen = new HashSet<>();
        for (CatalogBillingStrategy strategy : strategies) {
            for (String toolId : strategy.handledToolIds()) {
                if (!seen.add(toolId)) {
                    throw new IllegalStateException(
                            "Duplicate CatalogBillingStrategy registration for toolId='" + toolId + "'. "
                            + "Each tool can only be billed by one strategy.");
                }
            }
        }
    }

    /**
     * Look up a strategy by toolId and invoke it. No-op when:
     * <ul>
     *   <li>No strategy registered for the tool - debug log.</li>
     *   <li>Workflow origin - workflow's plan-time billing handles it.</li>
     *   <li>{@code credentialSource} missing - fail-safe.</li>
     * </ul>
     * Strategy exceptions are caught and logged; never propagated.
     */
}
