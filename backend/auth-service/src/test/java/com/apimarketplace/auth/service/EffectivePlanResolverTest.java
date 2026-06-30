package com.apimarketplace.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tranche 4 (decision core) of CE↔Cloud pricing delegation: a CLOUD-sourced
 * linked install is governed by the cloud account's plan; everything else keeps
 * its local plan, and an unknown cloud plan never strips entitlements.
 */
class EffectivePlanResolverTest {

    @Test
    @DisplayName("Linked + CLOUD source: the cloud account's plan governs the install")
    void cloudPlanGovernsWhenLinkedAndCloudSourced() {
        String result = EffectivePlanResolver.resolve("FREE", true, true, "PRO");
        assertThat(result).isEqualTo("PRO");
    }

    @Test
    @DisplayName("Linked but BYOK source: the install keeps its local plan")
    void localPlanWhenByokSource() {
        String result = EffectivePlanResolver.resolve("STARTER", true, false, "PRO");
        assertThat(result).isEqualTo("STARTER");
    }

    @Test
    @DisplayName("Not linked: the install keeps its local plan")
    void localPlanWhenNotLinked() {
        String result = EffectivePlanResolver.resolve("STARTER", false, true, "PRO");
        assertThat(result).isEqualTo("STARTER");
    }

    @Test
    @DisplayName("Linked + CLOUD but unknown cloud plan: falls back to local, never strips entitlements")
    void fallsBackToLocalWhenCloudPlanUnknown() {
        assertThat(EffectivePlanResolver.resolve("STARTER", true, true, null)).isEqualTo("STARTER");
        assertThat(EffectivePlanResolver.resolve("STARTER", true, true, "")).isEqualTo("STARTER");
        assertThat(EffectivePlanResolver.resolve("STARTER", true, true, PlanLimitService.NO_SUBSCRIPTION))
                .isEqualTo("STARTER");
    }

    @Test
    @DisplayName("Paying on the cloud upgrades a local-FREE install to the cloud plan")
    void cloudUpgradesLocalFree() {
        String result = EffectivePlanResolver.resolve(PlanLimitService.NO_SUBSCRIPTION, true, true, "TEAM");
        assertThat(result).isEqualTo("TEAM");
    }
}
