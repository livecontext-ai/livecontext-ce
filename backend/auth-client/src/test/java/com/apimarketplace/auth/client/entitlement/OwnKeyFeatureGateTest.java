package com.apimarketplace.auth.client.entitlement;

import com.apimarketplace.common.web.AppEditionProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OwnKeyFeatureGate - own provider keys from the plan an admin set")
class OwnKeyFeatureGateTest {

    private static final List<String> KEYS = List.of(OwnKeyFeatureGate.FEATURE_KEY);

    @Mock private AppEditionProvider edition;
    @Mock private PlanFeatureGate planFeatureGate;

    private OwnKeyFeatureGate sharedCloud() {
        when(edition.isSelfHosted()).thenReturn(false);
        when(edition.isDedicatedCloud()).thenReturn(false);
        return new OwnKeyFeatureGate(edition, planFeatureGate);
    }

    @Test
    @DisplayName("shared cloud: the plan gate decides, on the tenant, for feature:own_llm_key")
    void sharedCloudAsksThePlanGate() {
        OwnKeyFeatureGate gate = sharedCloud();
        when(planFeatureGate.allows("tenant-pro", KEYS)).thenReturn(true);
        when(planFeatureGate.allows("tenant-free", KEYS)).thenReturn(false);
        when(planFeatureGate.upgradeRequiredFor("tenant-free", KEYS)).thenReturn("PRO");

        assertTrue(gate.isAllowed("tenant-pro"));
        assertFalse(gate.isAllowed("tenant-free"));
        assertEquals("PRO", gate.upgradeRequiredFor("tenant-free"));
    }

    @Test
    @DisplayName("self-hosted and dedicated cloud are never gated: they own their keys and their contract")
    void singleTenantEditionsAreNeverGated() {
        when(edition.isSelfHosted()).thenReturn(true);
        OwnKeyFeatureGate selfHosted = new OwnKeyFeatureGate(edition, planFeatureGate);
        assertTrue(selfHosted.isAllowed("anyone"));
        assertNull(selfHosted.upgradeRequiredFor("anyone"));

        when(edition.isSelfHosted()).thenReturn(false);
        when(edition.isDedicatedCloud()).thenReturn(true);
        OwnKeyFeatureGate dedicated = new OwnKeyFeatureGate(edition, planFeatureGate);
        assertTrue(dedicated.isAllowed("anyone"));

        verify(planFeatureGate, never()).allows(any(), any());
    }

    @Test
    @DisplayName("shared cloud with no plan gate wired: not entitled (the platform key serves, nothing fails)")
    void unwiredGateOnSharedCloudRefuses() {
        when(edition.isSelfHosted()).thenReturn(false);
        when(edition.isDedicatedCloud()).thenReturn(false);
        OwnKeyFeatureGate gate = new OwnKeyFeatureGate(edition, null);

        assertFalse(gate.isAllowed("tenant-pro"));
        assertNull(gate.upgradeRequiredFor("tenant-pro"));
    }

    @Test
    @DisplayName("a plan lookup failure fails OPEN: a paying user's own key must not be dropped because auth-service blinked")
    void lookupFailureFailsOpen() {
        OwnKeyFeatureGate gate = sharedCloud();
        when(planFeatureGate.allows(eq("tenant-pro"), any())).thenThrow(new IllegalStateException("auth down"));
        when(planFeatureGate.upgradeRequiredFor(eq("tenant-pro"), any())).thenThrow(new IllegalStateException("auth down"));

        assertTrue(gate.isAllowed("tenant-pro"));
        assertNull(gate.upgradeRequiredFor("tenant-pro"));
    }
}
