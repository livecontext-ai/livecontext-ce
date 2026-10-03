package com.apimarketplace.agent.service.execution;

import com.apimarketplace.agent.service.ModelExecutionLinkService.ExecutionRoute;
import com.apimarketplace.common.classification.DataSensitivity;
import com.apimarketplace.common.classification.RestrictedDataPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LC-004: {@link RestrictedDataRouting} is the ONE decision every agent-service entry point that
 * can apply a model execution link (classify, guardrail, json-completion, sub-agent) shares for
 * "may this restricted content reach the provider that will actually run it". It has no Spring
 * dependency, so it is exercised directly here rather than only indirectly through each caller.
 */
@DisplayName("RestrictedDataRouting")
class RestrictedDataRoutingTest {

    @AfterEach
    void resetPolicy() {
        RestrictedDataPolicy.setLlmAllowListEnforced(true);
    }

    @Test
    @DisplayName("NORMAL content: the route is returned unchanged, whatever the providers are")
    void normalContentIsNeverGated() {
        ExecutionRoute route = new ExecutionRoute("openrouter", "some-model");

        assertThat(RestrictedDataRouting.apply(null, "deepseek", route)).isSameAs(route);
        assertThat(RestrictedDataRouting.apply("NORMAL", "deepseek", route)).isSameAs(route);
        assertThat(RestrictedDataRouting.apply(null, "deepseek", null)).isNull();
    }

    @Test
    @DisplayName("restricted, no link, billed provider allowed: passes through with no route")
    void restrictedNoLinkAllowedBilledProvider() {
        assertThat(RestrictedDataRouting.apply("RESTRICTED", "anthropic", null)).isNull();
    }

    @Test
    @DisplayName("restricted, no link, billed provider not allowed: refused")
    void restrictedNoLinkDisallowedBilledProviderIsRefused() {
        assertThatThrownBy(() -> RestrictedDataRouting.apply("RESTRICTED", "deepseek", null))
            .isInstanceOf(RestrictedDataRouting.RefusedException.class)
            .hasMessageContaining(RestrictedDataPolicy.REFUSAL_CODE)
            .hasMessageContaining("deepseek");
        assertThatThrownBy(() -> RestrictedDataRouting.apply("RESTRICTED", "deepseek", null))
            .extracting(e -> ((RestrictedDataRouting.RefusedException) e).getProvider())
            .isEqualTo("deepseek");
    }

    @Test
    @DisplayName("restricted, link execution provider allowed: the route is kept regardless of the billed provider")
    void restrictedLinkToAllowedProviderIsKept() {
        ExecutionRoute route = new ExecutionRoute("openai", "gpt-x");

        assertThat(RestrictedDataRouting.apply("RESTRICTED", "deepseek", route)).isSameAs(route);
    }

    @Test
    @DisplayName("restricted, link execution provider disallowed but billed provider allowed: the link is dropped, not refused")
    void restrictedLinkToDisallowedProviderIsDroppedWhenBilledIsAllowed() {
        ExecutionRoute route = new ExecutionRoute("openrouter", "some-model");

        ExecutionRoute result = RestrictedDataRouting.apply("RESTRICTED", "anthropic", route);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("restricted, link and billed provider both disallowed: refused, naming the EXECUTING provider")
    void restrictedLinkAndBilledBothDisallowedIsRefusedOnExecutingProvider() {
        ExecutionRoute route = new ExecutionRoute("openrouter", "some-model");

        assertThatThrownBy(() -> RestrictedDataRouting.apply("RESTRICTED", "deepseek", route))
            .isInstanceOf(RestrictedDataRouting.RefusedException.class)
            .extracting(e -> ((RestrictedDataRouting.RefusedException) e).getProvider())
            .isEqualTo("openrouter");
    }

    @Test
    @DisplayName("case-insensitive and DataSensitivity-enum-shaped inputs are read the same as the wire string")
    void acceptsEnumAndCaseVariants() {
        assertThat(RestrictedDataRouting.apply(DataSensitivity.RESTRICTED, "anthropic", null)).isNull();
        assertThatThrownBy(() -> RestrictedDataRouting.apply("restricted", "deepseek", null))
            .isInstanceOf(RestrictedDataRouting.RefusedException.class);
    }

    @Test
    @DisplayName("when the allow-list is not enforced on this install (CE), nothing is ever refused or dropped")
    void allowListNotEnforcedNeverGates() {
        RestrictedDataPolicy.setLlmAllowListEnforced(false);
        ExecutionRoute route = new ExecutionRoute("openrouter", "some-model");

        assertThat(RestrictedDataRouting.apply("RESTRICTED", "deepseek", route)).isSameAs(route);
        assertThat(RestrictedDataRouting.apply("RESTRICTED", "deepseek", null)).isNull();
    }
}
