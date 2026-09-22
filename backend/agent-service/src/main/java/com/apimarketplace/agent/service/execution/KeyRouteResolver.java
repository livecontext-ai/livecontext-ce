package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.credential.LlmCredentialRepository;
import com.apimarketplace.agent.domain.KeyRoute;
import com.apimarketplace.auth.client.entitlement.OwnKeyFeatureGate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Decides, once per execution, whose API key it runs on.
 *
 * <p>{@code OWN_KEY} when the tenant has a usable saved key for the provider AND their plan
 * lets them run on it; {@code PLATFORM} otherwise. Not entitled means the saved key is skipped
 * exactly as a {@code proxy}-mode credential is: the platform key serves and the platform
 * bills, nothing fails. The pin then travels with the execution (see {@link KeyRoute}) so a
 * run never changes route between its first and its last turn.
 *
 * <p>The saved-key check is deliberately uncached (a toggle flipped a second ago must show on
 * the next run); the plan gate caches the plan code for its own short TTL, so a plan change
 * takes up to that long to move the pin. The gate asks about the executing tenant, the user
 * whose key it is, like every other feature gate.
 */
@Component
public class KeyRouteResolver {

    private final LlmCredentialRepository credentials;
    private final OwnKeyFeatureGate ownKeyFeatureGate;

    @Autowired
    public KeyRouteResolver(LlmCredentialRepository credentials,
                            @Nullable OwnKeyFeatureGate ownKeyFeatureGate) {
        this.credentials = credentials;
        this.ownKeyFeatureGate = ownKeyFeatureGate;
    }

    /** No plan gate: the saved key alone decides (tests, editions that never gate). */
    public KeyRouteResolver(LlmCredentialRepository credentials) {
        this(credentials, null);
    }

    public KeyRoute resolve(String tenantId, String provider) {
        if (tenantId == null || tenantId.isBlank() || provider == null || provider.isBlank()) {
            return KeyRoute.PLATFORM;
        }
        if (ownKeyFeatureGate != null && !ownKeyFeatureGate.isAllowed(tenantId)) {
            return KeyRoute.PLATFORM;
        }
        return credentials.hasUsableUserKey(tenantId, provider) ? KeyRoute.OWN_KEY : KeyRoute.PLATFORM;
    }
}
