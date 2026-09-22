package com.apimarketplace.agent.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which deployment may OFFER a CLI bridge as a model choice.
 *
 * <p>The rule is inverted between deployments and that inversion is the whole point, so it is
 * asserted in both directions rather than left to the javadoc. On a SELF-HOSTED install the CLI is
 * the operator's own binary under their own login: hiding it would remove the main reason that
 * deployment exists. On the hosted product the four CLIs share ONE subscription, so offering one
 * hands a user someone else's quota.
 *
 * <p>The predicate takes a BOOLEAN, not an {@code auth.mode} string, and that is the fix for a
 * real misclassification: {@code auth.mode != "embedded"} reads a SELF_HOSTED_ENTERPRISE install
 * (self-hosted, but running keycloak) as hosted and would ban that operator from their own CLI.
 * Resolving the deployment is the caller's job, through {@code AppEditionProvider.isSelfHosted()},
 * which covers CE_FREE and SELF_HOSTED_ENTERPRISE alike.
 *
 * <p>Read together with {@code CeBlockedProvidersTest}: same shape, opposite direction. Getting the
 * comparison backwards in either is silent, because a catalogue that hides too much and one that
 * hides nothing both return a valid list.
 */
@DisplayName("BridgeProviders - who may be shown a CLI bridge")
class BridgeProvidersVisibilityTest {

    private static final boolean SELF_HOSTED = true;
    private static final boolean HOSTED = false;
    private static final boolean ADMIN = true;
    private static final boolean USER = false;

    @Test
    @DisplayName("hosted: every bridge is hidden from a non-admin")
    void hostedHidesEveryBridgeFromUsers() {
        for (String bridge : BridgeProviders.NAMES) {
            assertThat(BridgeProviders.isHiddenFromUser(HOSTED, USER, bridge))
                .as("%s must be hidden from a hosted user - all four CLIs there run on one "
                    + "shared operator subscription, and the access policy is not consulted: an "
                    + "admin can widen it to all_users, and the hosted product must not reopen "
                    + "on that", bridge)
                .isTrue();
        }
    }

    @Test
    @DisplayName("hosted: an ADMIN is shown every bridge - it is their choice to make")
    void hostedShowsEveryBridgeToAnAdmin() {
        // The regression this pins: hiding the bridges from hosted admins too made the CLI
        // models vanish from every picker in production for the very accounts that run them.
        // Whether an admin may DISPATCH on one stays the access policy's answer at run time.
        for (String bridge : BridgeProviders.NAMES) {
            assertThat(BridgeProviders.isHiddenFromUser(HOSTED, ADMIN, bridge)).isFalse();
        }
    }

    @Test
    @DisplayName("self-hosted: every bridge stays visible whatever the role, enterprise tier included")
    void selfHostedShowsEveryBridge() {
        for (String bridge : BridgeProviders.NAMES) {
            for (boolean admin : new boolean[]{ADMIN, USER}) {
                assertThat(BridgeProviders.isHiddenFromUser(SELF_HOSTED, admin, bridge))
                    .as("%s must stay visible on a self-hosted install (admin=%s). The caller "
                        + "resolves this flag with AppEditionProvider.isSelfHosted(), which is true "
                        + "for CE_FREE AND for SELF_HOSTED_ENTERPRISE - the tier an auth.mode "
                        + "check would have misread as hosted, banning an operator from the CLI "
                        + "they installed themselves. A self-hosted non-admin is trimmed by the "
                        + "access policy, not by this helper", bridge, admin)
                    .isFalse();
            }
        }
    }

    @Test
    @DisplayName("an API provider is never hidden, in either deployment, for either role")
    void apiProvidersAreNeverHidden() {
        for (String api : new String[]{"anthropic", "openai", "deepseek", "google", "xai"}) {
            assertThat(BridgeProviders.isHiddenFromUser(HOSTED, USER, api))
                .as("hiding %s on the hosted product would empty the picker of the models users "
                    + "actually buy", api)
                .isFalse();
            assertThat(BridgeProviders.isHiddenFromUser(HOSTED, ADMIN, api)).isFalse();
            assertThat(BridgeProviders.isHiddenFromUser(SELF_HOSTED, USER, api)).isFalse();
        }
    }

    @Test
    @DisplayName("a bridge name is matched case-insensitively and trimmed")
    void nameMatchingIsForgiving() {
        // The value often arrives from a persisted snapshot rather than from code, so a stored
        // "Claude-Code" must not slip through the filter on casing alone.
        assertThat(BridgeProviders.isHiddenFromUser(HOSTED, USER, "Claude-Code")).isTrue();
        assertThat(BridgeProviders.isHiddenFromUser(HOSTED, USER, "  codex  ")).isTrue();
    }

    @Test
    @DisplayName("a null provider name is not hidden, and does not throw")
    void nullProviderIsSafe() {
        // A catalogue row with no name must not be mistaken for a bridge and dropped: removing an
        // unnamed provider would silently shrink the list for a reason nobody asked for.
        assertThat(BridgeProviders.isHiddenFromUser(HOSTED, USER, null)).isFalse();
        assertThat(BridgeProviders.isHiddenFromUser(SELF_HOSTED, USER, null)).isFalse();
    }
}
