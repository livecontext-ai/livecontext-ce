package com.apimarketplace.auth.client.entitlement;

import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.auth.client.AuthClient.PlanLimitResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.LongSupplier;

/**
 * Pre-create gate for plan resource limits.
 *
 * <p>Each owning service injects this guard and calls
 * {@link #check(String, ResourceType, LongSupplier)} before persisting a new
 * resource (workflow, agent, datasource, interface, acquired application).
 *
 * <p>Behaviour:
 * <ul>
 *   <li><b>CE Free mode</b> - no-op, always allows.</li>
 *   <li><b>Limited mode</b> - fetches the user's plan limit from auth-service via
 *       {@link AuthClient#getResourceLimit(String, String)}; if {@code null}
 *       (unlimited) or {@code currentCount < limit}, allows; otherwise throws
 *       {@link LimitExceededException}.</li>
 *   <li><b>auth-service unreachable</b> - legacy mode fails open, while
 *       paid edition enforcement can be configured fail-closed.</li>
 * </ul>
 *
 * <p>The {@code currentCountSupplier} is only invoked when the plan is known
 * to be limited, so unlimited plans never pay the cost of a count query.
 */
public class EntitlementGuard {

    private static final Logger log = LoggerFactory.getLogger(EntitlementGuard.class);
    private static final String NO_SUBSCRIPTION_PLAN_CODE = "__NONE__";

    private final AuthClient authClient;
    private final boolean ceFreeNoopMode;
    private final boolean failClosedOnLookupFailure;

    public EntitlementGuard(AuthClient authClient, boolean ceFreeNoopMode) {
        this(authClient, ceFreeNoopMode, false);
    }

    public EntitlementGuard(AuthClient authClient, boolean ceFreeNoopMode, boolean failClosedOnLookupFailure) {
        this.authClient = authClient;
        this.ceFreeNoopMode = ceFreeNoopMode;
        this.failClosedOnLookupFailure = failClosedOnLookupFailure;
    }

    /**
     * Checks whether the user can create one more resource of the given type.
     *
     * @param providerId           Keycloak sub (X-User-ID header value)
     * @param type                 the resource type
     * @param currentCountSupplier called only when limit is finite; returns
     *                             the user's current count of that resource type
     *                             (the owning service queries its own schema)
     * @throws LimitExceededException when {@code currentCount >= limit}
     */
    public void check(String providerId, ResourceType type, LongSupplier currentCountSupplier) {
        if (ceFreeNoopMode) {
            return;
        }
        if (providerId == null || providerId.isBlank()) {
            if (failClosedOnLookupFailure) {
                throw new IllegalStateException(
                        "Entitlement lookup unavailable for paid deployment; missing provider id.");
            }
            log.debug("EntitlementGuard.check called with null providerId - allowing (no-op)");
            return;
        }
        PlanLimitResponse resp = authClient.getResourceLimit(providerId, type.key());
        if (resp == null) {
            if (failClosedOnLookupFailure) {
                throw new IllegalStateException(
                        "Entitlement lookup unavailable for paid deployment; refusing "
                                + type.key() + " creation.");
            }
            return;
        }
        String planCode = resp.planCode();
        if (failClosedOnLookupFailure
                && (planCode == null || planCode.isBlank() || NO_SUBSCRIPTION_PLAN_CODE.equals(planCode))) {
            throw new IllegalStateException(
                    "Entitlement lookup unavailable for paid deployment; no active plan for provider id.");
        }
        Integer limit = resp.limit();
        if (limit == null) {
            return;
        }
        long currentCount = currentCountSupplier.getAsLong();
        if (currentCount >= limit) {
            String effectivePlanCode = planCode != null ? planCode : "UNKNOWN";
            String upgradeHint = upgradeHintFor(effectivePlanCode);
            throw new LimitExceededException(LimitExceededError.of(
                    type, effectivePlanCode, currentCount, limit, upgradeHint));
        }
    }

    /**
     * Suggested next plan to recommend in error messages. Pure presentation,
     * caller can ignore. Not localized - frontend re-renders from i18n keys.
     */
    private static String upgradeHintFor(String currentPlanCode) {
        if (currentPlanCode == null) return "Upgrade to STARTER";
        return switch (currentPlanCode) {
            case "FREE" -> "Upgrade to STARTER";
            case "STARTER" -> "Upgrade to PRO";
            case "PRO" -> "Upgrade to TEAM";
            case "TEAM" -> "Upgrade to ENTERPRISE";
            default -> "Contact sales for higher limits";
        };
    }
}
