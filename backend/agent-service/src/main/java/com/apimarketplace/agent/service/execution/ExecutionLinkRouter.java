package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.service.ModelExecutionLinkService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Single chokepoint that turns a BILLED {@code (provider, model)} pair into the
 * route it must actually execute on, for every loop-shaped LLM caller.
 *
 * <p>The link store ({@link ModelExecutionLinkService}) answers "is this billed pair
 * linked?". Two things had to be repeated at every call site on top of that answer,
 * and repeating them is what let three callers drift apart:
 *
 * <ol>
 *   <li>the bean is CLOUD-only ({@code model-catalog.execution-links.enabled}), so a
 *       null service means "no link, run the billed pair";</li>
 *   <li>a route that targets a CLI bridge is only runnable when the bridge transport is
 *       wired - otherwise the link is dropped and the billed pair runs on its own
 *       provider, never a silent bridge failure.</li>
 * </ol>
 *
 * <p>Callers get a route that is already safe to execute, or {@code null} meaning
 * "keep the billed pair". They stay responsible for the two halves the route cannot
 * carry: running the loop on the EXECUTION identity, and re-stamping the BILLED
 * identity onto the response so the ledger keeps charging the model the user picked.
 *
 * <p>Consumers: {@link AgentRemoteExecutionService} (agent nodes, chat and every
 * standalone-agent surface), {@link ClassifyService} and {@link GuardrailService}
 * (workflow classify / guardrail nodes), {@link SubAgentExecutionHandler}
 * (delegated sub-agents) and {@link JsonCompletionService} (the bare completion behind
 * COLD-summary compaction). Two more resolve their own direct-API-only variant, which
 * cannot accept a bridge target and so cannot use this router: avatar generation, and
 * the browser agent (which reaches the store over HTTP).
 */
@Slf4j
@Component
public class ExecutionLinkRouter {

    /**
     * Credentials marker that puts a CLI bridge run into restricted "API mode": an empty
     * cwd (no project files) and none of the CLI's native tools, so a linked model behaves
     * like a plain API. Re-exported from the DTO that the agent path writes it with, so the
     * two paths cannot drift onto different spellings of a key the bridge matches exactly.
     */
    public static final String RESTRICTED_TOOLSET_KEY =
        com.apimarketplace.agent.client.dto.execution.AgentExecutionRequestDto.RESTRICTED_TOOLSET_KEY;

    /** CLOUD-only bean: absent in the CE monolith, where every pair runs verbatim. */
    @Autowired(required = false)
    private ModelExecutionLinkService executionLinkService;

    /** Follows a disabled link TARGET to its explicit replacement (V515). Null in unit tests = no swap. */
    @Autowired(required = false)
    private com.apimarketplace.agent.service.ModelReplacementResolver modelReplacementResolver;

    private final BridgeLoopDispatcher bridgeDispatcher;

    /** Billed pairs already reported as "linked to an unwired bridge" - keeps the warn to one per pair. */
    private final java.util.Set<String> droppedBridgeRoutesLogged = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ExecutionLinkRouter(BridgeLoopDispatcher bridgeDispatcher) {
        this.bridgeDispatcher = bridgeDispatcher;
    }

    /**
     * The execution route to run this billed pair on, or {@code null} when the pair is
     * unlinked, the link feature is off (CE), or the link targets a CLI bridge that is
     * not wired here.
     *
     * @param activitySource the run's logical origin ({@code WORKFLOW}, {@code CHAT},
     *                       {@code SUB_AGENT}, ...). A source matching no surface means
     *                       only an {@code ALL}-scoped link can apply.
     */
    public ModelExecutionLinkService.ExecutionRoute runnableRoute(String billedProvider,
                                                                 String billedModel,
                                                                 String activitySource) {
        if (executionLinkService == null) {
            return null;
        }
        ModelExecutionLinkService.ExecutionRoute route;
        try {
            route = executionLinkService.resolve(billedProvider, billedModel, activitySource).orElse(null);
        } catch (RuntimeException e) {
            // The store reads through a cached DB query. A blip there must not fail a run:
            // before links existed these callers had no dependency on it at all, and the
            // honest degradation is "no link", which runs the pair the user asked for.
            log.warn("Execution-link lookup failed for {}/{} ({}); running the billed pair",
                billedProvider, billedModel, e.toString());
            return null;
        }
        if (route == null) {
            return null;
        }
        route = followTargetReplacement(billedProvider, billedModel, route);
        if (SubAgentBridgeClient.isBridgeProvider(route.executionProvider()) && !bridgeDispatcher.isAvailable()) {
            // Once per pair, not once per call: a misconfigured link inside a split loop
            // would otherwise write one line per item.
            if (droppedBridgeRoutesLogged.add(billedProvider + "/" + billedModel)) {
                log.warn("Execution link {}/{} -> {} dropped: the CLI bridge transport is not wired here, "
                        + "so the run stays on the billed pair's own provider key",
                    billedProvider, billedModel, route.executionProvider());
            }
            return null;
        }
        return route;
    }

    /**
     * A link whose EXECUTION target an admin disabled AND gave a replacement runs on that
     * replacement (V515). Only an explicit replacement moves it: a disabled target with none
     * keeps running, because disabling a CLI bridge row to hide it from users while still
     * routing a linked model onto it is a normal setup. The billed pair itself was already
     * swapped by the caller (ModelReplacementResolver.substituteIfDisabled) before it asked.
     */
    private ModelExecutionLinkService.ExecutionRoute followTargetReplacement(
            String billedProvider, String billedModel, ModelExecutionLinkService.ExecutionRoute route) {
        if (modelReplacementResolver == null) {
            return route;
        }
        var replacement = modelReplacementResolver
            .explicitReplacementIfDisabled(route.executionProvider(), route.executionModel())
            .orElse(null);
        if (replacement == null) {
            return route;
        }
        log.info("Execution link {}/{}: disabled target {}/{} replaced by {}/{}",
            billedProvider, billedModel, route.executionProvider(), route.executionModel(),
            replacement.provider(), replacement.model());
        return new ModelExecutionLinkService.ExecutionRoute(replacement.provider(), replacement.model());
    }

    /** True when the route runs on a CLI bridge, which needs the restricted "API mode". */
    public static boolean targetsBridge(ModelExecutionLinkService.ExecutionRoute route) {
        return route != null && SubAgentBridgeClient.isBridgeProvider(route.executionProvider());
    }
}
