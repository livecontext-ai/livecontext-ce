package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.credential.LlmCredentialRepository;
import com.apimarketplace.agent.domain.KeyRoute;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The per-execution key-route pin: OWN_KEY iff the tenant holds a usable saved key for
 * the execution provider, PLATFORM in every other case including a lookup failure.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("KeyRouteResolver")
class KeyRouteResolverTest {

    @Mock
    private LlmCredentialRepository credentials;

    private KeyRouteResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new KeyRouteResolver(credentials);
    }

    @Test
    @DisplayName("pins OWN_KEY when the tenant holds a usable saved key for the provider")
    void ownKeyWhenTenantHoldsUsableKey() {
        when(credentials.hasUsableUserKey("tenant-9", "openai")).thenReturn(true);

        assertThat(resolver.resolve("tenant-9", "openai")).isEqualTo(KeyRoute.OWN_KEY);
    }

    @Test
    @DisplayName("pins PLATFORM when the tenant has no usable key (none saved, proxy mode, or blank api_key)")
    void platformWhenNoUsableKey() {
        when(credentials.hasUsableUserKey("tenant-9", "openai")).thenReturn(false);

        assertThat(resolver.resolve("tenant-9", "openai")).isEqualTo(KeyRoute.PLATFORM);
    }

    @Test
    @DisplayName("pins PLATFORM without a lookup when the tenant is blank (internal or system execution)")
    void platformWithoutLookupForBlankTenant() {
        assertThat(resolver.resolve(null, "openai")).isEqualTo(KeyRoute.PLATFORM);
        assertThat(resolver.resolve("  ", "openai")).isEqualTo(KeyRoute.PLATFORM);

        verify(credentials, never()).hasUsableUserKey(any(), any());
    }

    @Test
    @DisplayName("pins PLATFORM without a lookup when the provider is blank")
    void platformWithoutLookupForBlankProvider() {
        assertThat(resolver.resolve("tenant-9", null)).isEqualTo(KeyRoute.PLATFORM);
        assertThat(resolver.resolve("tenant-9", "")).isEqualTo(KeyRoute.PLATFORM);

        verify(credentials, never()).hasUsableUserKey(any(), any());
    }

    @Test
    @DisplayName("asks the repository fresh on every resolve: the pin must reflect a toggle flipped a second ago")
    void resolvesFreshEveryTime() {
        when(credentials.hasUsableUserKey("tenant-9", "openai")).thenReturn(false).thenReturn(true);

        assertThat(resolver.resolve("tenant-9", "openai")).isEqualTo(KeyRoute.PLATFORM);
        assertThat(resolver.resolve("tenant-9", "openai")).isEqualTo(KeyRoute.OWN_KEY);

        verify(credentials, org.mockito.Mockito.times(2)).hasUsableUserKey("tenant-9", "openai");
    }

    @Test
    @DisplayName("V507: a plan that does not allow the own key pins PLATFORM without even looking the key up (skipped, like a proxy-mode credential)")
    void planGateRefusalPinsPlatformWithoutLookup() {
        com.apimarketplace.auth.client.entitlement.OwnKeyFeatureGate gate =
            org.mockito.Mockito.mock(com.apimarketplace.auth.client.entitlement.OwnKeyFeatureGate.class);
        when(gate.isAllowed("tenant-9")).thenReturn(false);
        KeyRouteResolver gated = new KeyRouteResolver(credentials, gate);

        assertThat(gated.resolve("tenant-9", "openai")).isEqualTo(KeyRoute.PLATFORM);

        verify(credentials, never()).hasUsableUserKey(any(), any());
    }

    @Test
    @DisplayName("V507: an entitled plan lets the saved key decide, exactly as without a gate")
    void entitledPlanConsultsTheSavedKey() {
        com.apimarketplace.auth.client.entitlement.OwnKeyFeatureGate gate =
            org.mockito.Mockito.mock(com.apimarketplace.auth.client.entitlement.OwnKeyFeatureGate.class);
        when(gate.isAllowed("tenant-9")).thenReturn(true);
        when(credentials.hasUsableUserKey("tenant-9", "openai")).thenReturn(true);
        KeyRouteResolver gated = new KeyRouteResolver(credentials, gate);

        assertThat(gated.resolve("tenant-9", "openai")).isEqualTo(KeyRoute.OWN_KEY);
    }

    @Test
    @DisplayName("the gate is never asked for a blank tenant (internal or system execution)")
    void blankTenantNeverAsksTheGate() {
        com.apimarketplace.auth.client.entitlement.OwnKeyFeatureGate gate =
            org.mockito.Mockito.mock(com.apimarketplace.auth.client.entitlement.OwnKeyFeatureGate.class);
        KeyRouteResolver gated = new KeyRouteResolver(credentials, gate);

        assertThat(gated.resolve(null, "openai")).isEqualTo(KeyRoute.PLATFORM);

        verify(gate, never()).isAllowed(any());
    }
}
