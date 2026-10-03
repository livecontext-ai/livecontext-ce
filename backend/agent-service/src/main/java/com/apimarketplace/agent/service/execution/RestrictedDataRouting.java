package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.service.ModelExecutionLinkService;
import com.apimarketplace.common.classification.DataSensitivity;
import com.apimarketplace.common.classification.RestrictedDataPolicy;
import lombok.extern.slf4j.Slf4j;

/**
 * The restricted-data decision (CASA LC-004) for every agent-service entry point that can apply a
 * model execution link: the agent executions, classify, guardrail and the bare JSON completion.
 *
 * <p>The decision has to be taken on the provider that will actually RECEIVE the content, which a
 * link can change: a run billed as {@code anthropic} can be linked to a CLI bridge or an
 * aggregator. So, for restricted content:
 * <ul>
 *   <li>a link to a provider outside the allow-list is dropped when the billed provider itself is
 *       allowed (the run stays on the billed pair's direct API, the same fallback a failed link
 *       already takes);</li>
 *   <li>otherwise, when the provider that would run it is not allowed, the call is refused with
 *       {@link RestrictedDataPolicy#refusalMessage}.</li>
 * </ul>
 * Normal content is never affected.
 */
@Slf4j
public final class RestrictedDataRouting {

    private RestrictedDataRouting() {
    }

    /** Thrown when restricted content would reach a provider outside the allow-list. */
    public static final class RefusedException extends IllegalStateException {
        private final String provider;

        public RefusedException(String provider) {
            super(RestrictedDataPolicy.refusalMessage(provider));
            this.provider = provider;
        }

        public String getProvider() {
            return provider;
        }
    }

    /**
     * @param sensitivity    the content's tag, as a wire value (null = NORMAL)
     * @param billedProvider the provider the caller asked for
     * @param route          the execution link that would apply, or null
     * @return the route to use (the given one, or null when it had to be dropped)
     * @throws RefusedException when the provider that would run the content may not receive it
     */
    public static ModelExecutionLinkService.ExecutionRoute apply(Object sensitivity, String billedProvider,
                                                                 ModelExecutionLinkService.ExecutionRoute route) {
        if (!DataSensitivity.parse(sensitivity).isRestricted()) {
            return route;
        }
        if (route != null && !RestrictedDataPolicy.mayReceiveRestricted(route.executionProvider())
                && RestrictedDataPolicy.mayReceiveRestricted(billedProvider)) {
            log.info("Restricted data: ignoring execution link {} -> {} and running on the billed provider",
                billedProvider, route.executionProvider());
            route = null;
        }
        String executing = route != null ? route.executionProvider() : billedProvider;
        if (!RestrictedDataPolicy.mayReceiveRestricted(executing)) {
            log.warn("Restricted data refused for provider {}", executing);
            throw new RefusedException(executing);
        }
        return route;
    }
}
