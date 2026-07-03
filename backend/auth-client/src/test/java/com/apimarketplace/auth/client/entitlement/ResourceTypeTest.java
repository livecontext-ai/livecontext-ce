package com.apimarketplace.auth.client.entitlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ResourceType} is a cross-service wire contract: each constant's name
 * is the canonical key used in HTTP/JSON limit payloads and matched by
 * {@code Plan.getResourceLimit(String)} on the auth-service side. Renaming or
 * dropping a constant silently breaks plan-limit enforcement, so the full set
 * is pinned here.
 */
@DisplayName("ResourceType enum contract")
class ResourceTypeTest {

    @Test
    @DisplayName("contains exactly the seven plan-limited resource types including WORKFLOW_VARIABLE")
    void containsExactlyTheKnownResourceTypes() {
        assertThat(ResourceType.values())
                .extracting(Enum::name)
                .containsExactly(
                        "WORKFLOW",
                        "AGENT",
                        "DATASOURCE",
                        "INTERFACE",
                        "APPLICATION",
                        "PUBLICATION",
                        "WORKFLOW_VARIABLE");
    }

    @Test
    @DisplayName("WORKFLOW_VARIABLE.key() equals its name - the string Plan.getResourceLimit switches on")
    void workflowVariableKeyMatchesName() {
        assertThat(ResourceType.WORKFLOW_VARIABLE.key()).isEqualTo("WORKFLOW_VARIABLE");
    }

    @Test
    @DisplayName("key() equals name() for every constant - no divergent wire aliases")
    void keyEqualsNameForEveryConstant() {
        for (ResourceType type : ResourceType.values()) {
            assertThat(type.key())
                    .as("key() of %s must be its enum name", type)
                    .isEqualTo(type.name());
        }
    }
}
