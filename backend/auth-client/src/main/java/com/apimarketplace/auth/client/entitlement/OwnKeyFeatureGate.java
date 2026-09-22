package com.apimarketplace.auth.client.entitlement;

import com.apimarketplace.common.plan.PlanFeatureKeys;
import com.apimarketplace.common.web.AppEditionProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.List;

/**
 * Plan gate for running agents on the tenant's OWN provider key.
 *
 * <p>Same shape as the vector-search gate, for the same reasons. The bar is set by an admin in
 * {@code auth.plan_feature_requirement} under {@link #FEATURE_KEY} (seeded at PRO), not by this
 * code, so it moves without a deploy. A missing row means "everyone".
 *
 * <p><b>Two editions are never gated: self-hosted and dedicated cloud.</b> They own their keys
 * and their contract; the plan is a shared-cloud pricing device. Do not read that off the
 * {@code PlanFeatureGate} bean's own flag: it is enabled on {@code isCloud()} only, so relying on
 * it would make dedicated cloud free by accident.
 *
 * <p><b>Fail-open on a LOOKUP failure, closed on an unwired gate.</b> An unreadable requirement
 * map or an unknown plan code resolves to allowed, like every other plan gate here. But a shared
 * cloud where no gate is wired at all cannot ask the question and answers "not entitled": the
 * consequence is merely the platform key and platform billing (the pre-feature behaviour), never
 * a failed run.
 *
 * <p>Lives in auth-client because two services pin a route: agent-service ({@code
 * KeyRouteResolver}, every LLM execution kind) and the orchestrator ({@code BrowserAgentModule},
 * whose runner is handed a key directly). Not entitled means the user's saved key is skipped and
 * the execution runs {@code PLATFORM}, exactly as a {@code proxy}-mode credential does.
 */
public class OwnKeyFeatureGate {

    private static final Logger log = LoggerFactory.getLogger(OwnKeyFeatureGate.class);

    /**
     * The admin-configurable requirement key. Shared with auth-service, which gates the PRICE a
     * picker quotes on the same answer this gate gives the run.
     */
    public static final String FEATURE_KEY = PlanFeatureKeys.OWN_LLM_KEY;
    private static final List<String> FEATURE_KEYS = List.of(FEATURE_KEY);

    private final boolean neverGated;
    private final PlanFeatureGate planFeatureGate;

    public OwnKeyFeatureGate(AppEditionProvider editionProvider, @Nullable PlanFeatureGate planFeatureGate) {
        this.neverGated = editionProvider.isSelfHosted() || editionProvider.isDedicatedCloud();
        this.planFeatureGate = planFeatureGate;
        log.info("[OwnKeyFeatureGate] own provider keys {}",
                neverGated ? "ENABLED for every plan (single-tenant edition, never priced)"
                        : planFeatureGate == null ? "DISABLED (shared cloud, no plan gate wired)"
                        : "PLAN-GATED (shared cloud, key " + FEATURE_KEY + ")");
    }

    /**
     * Whether {@code tenantId} may run on its own saved provider key. Blank is an internal or
     * system execution and is never gated, matching {@code PlanFeatureGate}'s contract.
     */
    public boolean isAllowed(String tenantId) {
        if (neverGated) {
            return true;
        }
        if (planFeatureGate == null) {
            return false;
        }
        try {
            return planFeatureGate.allows(tenantId, FEATURE_KEYS);
        } catch (Exception e) {
            log.warn("[OwnKeyFeatureGate] plan lookup failed for tenant {} - allowing: {}", tenantId, e.getMessage());
            return true;
        }
    }

    /**
     * The plan the tenant would have to be on, or {@code null} when they may already use their
     * key (or the gate cannot say). Lets a refusal NAME the plan instead of describing a wall.
     */
    public String upgradeRequiredFor(String tenantId) {
        if (neverGated || planFeatureGate == null) {
            return null;
        }
        try {
            return planFeatureGate.upgradeRequiredFor(tenantId, FEATURE_KEYS);
        } catch (Exception e) {
            return null;
        }
    }
}
