package com.apimarketplace.agent.loop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the single source of truth for MAIN-purpose entry points. If a new caller
 * is added (or one is removed / renamed) this test MUST be updated - which is the
 * point: it forces a reviewer to decide whether the new caller belongs in the
 * centralized pipeline and to add an integration test replaying it through the
 * chokepoint.
 */
@DisplayName("MainCallerRegistry")
class MainCallerRegistryTest {

    @Test
    @DisplayName("enumerates exactly the four authorized MAIN-pipeline entry points")
    void authorizedCallers() {
        assertThat(MainCallerRegistry.MAIN_CALLERS)
            .as("Adding a new MAIN caller requires updating MainCallerRegistry AND adding a centralization test")
            .containsExactly(
                MainCallerRegistry.Caller.CONVERSATION_AGENT,
                MainCallerRegistry.Caller.SUB_AGENT,
                MainCallerRegistry.Caller.WORKFLOW_AGENT_NODE,
                MainCallerRegistry.Caller.BRIDGE_TASK);
    }

    @Test
    @DisplayName("registry size matches the CallPurpose.MAIN invariant - CLASSIFY/GUARDRAIL are NOT in the list")
    void classifyAndGuardrailExcluded() {
        assertThat(MainCallerRegistry.MAIN_CALLERS)
            .as("CLASSIFY and GUARDRAIL have their own dedicated services and bypass the MAIN pipeline")
            .hasSize(4);
    }
}
