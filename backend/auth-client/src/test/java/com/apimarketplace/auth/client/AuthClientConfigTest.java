package com.apimarketplace.auth.client;

import com.apimarketplace.auth.client.entitlement.EntitlementGuard;
import com.apimarketplace.auth.client.entitlement.ResourceType;
import com.apimarketplace.common.web.AppEditionProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthClientConfigTest {

    private final AuthClientConfig config = new AuthClientConfig();

    @Test
    void entitlementGuardIsNoOpOnlyForCeFreeUnlimitedResources() {
        AuthClient authClient = mock(AuthClient.class);
        AppEditionProvider editionProvider = mock(AppEditionProvider.class);
        when(editionProvider.hasCeFreeUnlimitedLocalResources()).thenReturn(true);

        EntitlementGuard guard = config.entitlementGuard(authClient, editionProvider);
        guard.check("u1", ResourceType.WORKFLOW, () -> 100L);

        verifyNoInteractions(authClient);
    }

    @Test
    void entitlementGuardChecksAuthServiceWhenCeFreeUnlimitedResourcesAreDisabled() {
        AuthClient authClient = mock(AuthClient.class);
        AppEditionProvider editionProvider = mock(AppEditionProvider.class);
        when(editionProvider.hasCeFreeUnlimitedLocalResources()).thenReturn(false);
        when(authClient.getResourceLimit("u1", "WORKFLOW"))
                .thenReturn(new AuthClient.PlanLimitResponse("ENTERPRISE", null));

        EntitlementGuard guard = config.entitlementGuard(authClient, editionProvider);
        guard.check("u1", ResourceType.WORKFLOW, () -> 100L);

        verify(authClient).getResourceLimit("u1", "WORKFLOW");
    }

    @Test
    void selfHostedEnterpriseEntitlementGuardFailsClosedWhenLimitsCannotBeResolved() {
        AuthClient authClient = mock(AuthClient.class);
        AppEditionProvider editionProvider = mock(AppEditionProvider.class);
        when(editionProvider.hasCeFreeUnlimitedLocalResources()).thenReturn(false);
        when(editionProvider.isSelfHostedEnterprise()).thenReturn(true);
        when(authClient.getResourceLimit("u1", "WORKFLOW")).thenReturn(null);

        EntitlementGuard guard = config.entitlementGuard(authClient, editionProvider);

        assertThatThrownBy(() -> guard.check("u1", ResourceType.WORKFLOW, () -> 100L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Entitlement lookup unavailable");
    }

    /**
     * REGRESSION GUARD: managed Cloud must stay fail-OPEN when a user has no
     * resolvable active subscription ({@code __NONE__}). The recovered enterprise
     * wiring initially made every non-CE-Free edition fail-closed, which would have
     * 500'd resource creation for any Cloud user in a provisioning / expiry /
     * cancellation window that dev allowed. Only Self-Hosted Enterprise fails closed.
     */
    @Test
    void cloudEntitlementGuardStaysFailOpenForAbsentSubscription() {
        AuthClient authClient = mock(AuthClient.class);
        AppEditionProvider editionProvider = mock(AppEditionProvider.class);
        when(editionProvider.hasCeFreeUnlimitedLocalResources()).thenReturn(false);
        when(editionProvider.isSelfHostedEnterprise()).thenReturn(false);
        when(authClient.getResourceLimit("u1", "WORKFLOW"))
                .thenReturn(new AuthClient.PlanLimitResponse("__NONE__", null));

        EntitlementGuard guard = config.entitlementGuard(authClient, editionProvider);

        // Must NOT throw - a __NONE__ plan on Cloud is allowed (fail-open), as on dev.
        guard.check("u1", ResourceType.WORKFLOW, () -> 100L);
        verify(authClient).getResourceLimit("u1", "WORKFLOW");
    }

    @Test
    void cloudEntitlementGuardStaysFailOpenWhenLookupReturnsNull() {
        AuthClient authClient = mock(AuthClient.class);
        AppEditionProvider editionProvider = mock(AppEditionProvider.class);
        when(editionProvider.hasCeFreeUnlimitedLocalResources()).thenReturn(false);
        when(editionProvider.isSelfHostedEnterprise()).thenReturn(false);
        when(authClient.getResourceLimit("u1", "WORKFLOW")).thenReturn(null);

        EntitlementGuard guard = config.entitlementGuard(authClient, editionProvider);

        // auth-service unreachable on Cloud → fail-open (no throw), matching dev.
        guard.check("u1", ResourceType.WORKFLOW, () -> 100L);
        verify(authClient).getResourceLimit("u1", "WORKFLOW");
    }
}
