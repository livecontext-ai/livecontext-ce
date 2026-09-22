package com.apimarketplace.agent.service;

import com.apimarketplace.agent.bridge.BridgeAccessDecision;
import com.apimarketplace.agent.bridge.BridgeAccessGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rule that decides whether an agent may be SAVED on a given provider.
 *
 * <p>It lives in one place because an agent is written from several: the REST controller, the
 * {@code agent} MCP tool, and clone. The rule's two narrowings carry all the risk, so they are
 * pinned here rather than at each call site: a TRANSIENT denial must never block a save (or an
 * auth-service outage would lock every owner out of their own agent), and a provider that is
 * NOT changing must never be re-judged (or an agent already stuck on a bridge could not be
 * moved off it).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BridgeProviderSaveGuard")
class BridgeProviderSaveGuardTest {

    private static final String BRIDGE = "claude-code";
    private static final String USER = "121";

    @Mock private BridgeAccessGuard bridgeAccessGuard;

    private BridgeProviderSaveGuard guard;

    @BeforeEach
    void setUp() {
        guard = new BridgeProviderSaveGuard();
        guard.setBridgeAccessGuard(bridgeAccessGuard);
        // CE. The access-policy logic below is reachable here for every role; in cloud only an
        // ADMIN reaches it, a user is refused before any policy is read (see the
        // CloudRefusesNonAdminBridgeSaves tests at the end).
        // Pinned explicitly rather than left to the blank default, so these tests say which
        // edition they describe instead of passing by accident on whatever the default becomes.
        guard.setAuthMode("embedded");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        BridgeAccessDecision.REASON_NOT_ADMIN,
        BridgeAccessDecision.REASON_NOT_ALLOWLISTED,
        BridgeAccessDecision.REASON_DISABLED,
        BridgeAccessDecision.REASON_UNKNOWN_BRIDGE,
    })
    @DisplayName("a standing denial blocks the save")
    void standingDenialsBlockTheSave(String reason) {
        denyWith(reason);

        assertThat(guard.denialReason(USER, "USER", BRIDGE, null))
            .as("%s is policy, not a passing condition - an agent saved on it can never run", reason)
            .contains(reason);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        BridgeAccessDecision.REASON_QUOTA_EXHAUSTED,
        BridgeAccessDecision.REASON_GUARD_UNAVAILABLE,
    })
    @DisplayName("a transient denial does NOT block the save")
    void transientDenialsDoNotBlockTheSave(String reason) {
        denyWith(reason);

        assertThat(guard.denialReason(USER, "USER", BRIDGE, null))
            .as("a quota resets and an unreachable guard proves nothing; blocking on either "
                + "would lock owners out of their own agents whenever auth-service blinks")
            .isEmpty();
    }

    @Test
    @DisplayName("an unreachable access service denies rather than throws, which is the live path")
    void guardUnavailableIsADenialNotAnException() {
        // HttpBridgeAccessClient converts every failure into deny(guard_unavailable) instead of
        // throwing, so this - not the catch block - is what an auth-service outage looks like.
        denyWith(BridgeAccessDecision.REASON_GUARD_UNAVAILABLE);

        assertThat(guard.denialReason(USER, null, BRIDGE, null)).isEmpty();
    }

    @Test
    @DisplayName("an allowed caller may save on the bridge")
    void allowedCallerMaySave() {
        when(bridgeAccessGuard.check(any(), any(), any()))
            .thenReturn(BridgeAccessDecision.allow(BRIDGE, null));

        assertThat(guard.denialReason(USER, "USER,ADMIN", BRIDGE, null)).isEmpty();
    }

    @Test
    @DisplayName("an unchanged provider is never submitted to the access guard")
    void unchangedProviderIsNeverJudged() {
        assertThat(guard.denialReason(USER, "USER", BRIDGE, BRIDGE)).isEmpty();

        verify(bridgeAccessGuard, never()).check(any(), any(), any());
    }

    @Test
    @DisplayName("case differences do not make a provider look like a change")
    void providerComparisonIgnoresCase() {
        assertThat(guard.denialReason(USER, "USER", "Claude-Code", BRIDGE)).isEmpty();

        verify(bridgeAccessGuard, never()).check(any(), any(), any());
    }

    @Test
    @DisplayName("a non-bridge provider is never submitted to the access guard")
    void nonBridgeProviderIsNeverJudged() {
        assertThat(guard.denialReason(USER, "USER", "openai", null)).isEmpty();

        verify(bridgeAccessGuard, never()).check(any(), any(), any());
    }

    @Test
    @DisplayName("no requested provider means there is nothing to judge")
    void absentProviderIsNeverJudged() {
        assertThat(guard.denialReason(USER, "USER", null, BRIDGE)).isEmpty();
        assertThat(guard.denialReason(USER, "USER", "  ", BRIDGE)).isEmpty();

        verify(bridgeAccessGuard, never()).check(any(), any(), any());
    }

    @Test
    @DisplayName("a deployment with no access guard wired allows the save")
    void unwiredGuardAllowsTheSave() {
        BridgeProviderSaveGuard unwired = new BridgeProviderSaveGuard();
        // CE, like the outer setUp: an unwired access guard only decides anything on the edition
        // that consults it. Cloud refuses a user before the lookup; the unwired cloud case has
        // its own test in the nested class.
        unwired.setAuthMode("embedded");

        assertThat(unwired.denialReason(USER, "USER", BRIDGE, null))
            .as("a bridge-less deployment must not have its agent saves blocked by a missing bean")
            .isEmpty();
    }

    @Test
    @DisplayName("an access guard that throws allows the save")
    void throwingGuardAllowsTheSave() {
        when(bridgeAccessGuard.check(any(), any(), any()))
            .thenThrow(new IllegalStateException("auth-service down"));

        assertThat(guard.denialReason(USER, "USER", BRIDGE, null))
            .as("defensive: saving an agent must not depend on the access service at all")
            .isEmpty();
    }

    @Test
    @DisplayName("the caller's id and roles are passed through in that order, not transposed")
    void identityIsPassedThroughUntransposed() {
        // Every other assertion here stubs check(any(), any(), any()), which stays green if
        // userId and userRoles swap places - and a transposed pair judges a role string as a
        // user id, so every caller reads as unknown and every bridge save is refused.
        when(bridgeAccessGuard.check(USER, "USER,ADMIN", BRIDGE))
            .thenReturn(BridgeAccessDecision.allow(BRIDGE, null));

        assertThat(guard.denialReason(USER, "USER,ADMIN", BRIDGE, null)).isEmpty();

        verify(bridgeAccessGuard).check(USER, "USER,ADMIN", BRIDGE);
    }

    @Test
    @DisplayName("every denial reason is classified deliberately - a new one cannot slip through")
    void everyKnownReasonIsClassifiedDeliberately() throws Exception {
        // isStanding is a whitelist, so a reason added later silently defaults to 'transient',
        // i.e. allowed. This forces that decision to be made rather than inherited.
        java.lang.reflect.Method isStanding =
            BridgeProviderSaveGuard.class.getDeclaredMethod("isStanding", String.class);
        isStanding.setAccessible(true);

        java.util.Map<String, Boolean> expected = java.util.Map.of(
            BridgeAccessDecision.REASON_NOT_ADMIN, true,
            BridgeAccessDecision.REASON_NOT_ALLOWLISTED, true,
            BridgeAccessDecision.REASON_DISABLED, true,
            BridgeAccessDecision.REASON_UNKNOWN_BRIDGE, true,
            BridgeAccessDecision.REASON_QUOTA_EXHAUSTED, false,
            BridgeAccessDecision.REASON_GUARD_UNAVAILABLE, false);

        for (java.lang.reflect.Field field : BridgeAccessDecision.class.getDeclaredFields()) {
            if (!field.getName().startsWith("REASON_") || field.getType() != String.class) {
                continue;
            }
            String reason = (String) field.get(null);
            assertThat(expected)
                .as("BridgeAccessDecision.%s is not classified here - decide whether it is a "
                    + "standing policy (blocks a save) or a passing condition (does not), and "
                    + "add it to isStanding and to this test", field.getName())
                .containsKey(reason);
            assertThat(isStanding.invoke(null, reason))
                .as("%s", field.getName())
                .isEqualTo(expected.get(reason));
        }
    }

    @Test
    @DisplayName("a denial carrying no reason allows the save")
    void denialWithoutAReasonAllowsTheSave() {
        when(bridgeAccessGuard.check(any(), any(), any()))
            .thenReturn(new BridgeAccessDecision(false, null, BRIDGE, null));

        assertThat(guard.denialReason(USER, "USER", BRIDGE, null))
            .as("an unclassifiable denial is not evidence of a standing policy")
            .isEmpty();
    }

    @Test
    @DisplayName("a null decision allows the save")
    void nullDecisionAllowsTheSave() {
        when(bridgeAccessGuard.check(any(), any(), any())).thenReturn(null);

        assertThat(guard.denialReason(USER, "USER", BRIDGE, null))
            .as("no answer is not a refusal; the dispatch guard still fails closed")
            .isEmpty();
    }

    @Test
    @DisplayName("an unwired access guard says so, once, and only when a bridge save is attempted")
    void unwiredGuardAnnouncesItself() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(BridgeProviderSaveGuard.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
            new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            BridgeProviderSaveGuard unwired = new BridgeProviderSaveGuard();
            unwired.setAuthMode("embedded"); // see unwiredGuardAllowsTheSave

            unwired.denialReason(USER, "USER", "openai", null);
            assertThat(appender.list)
                .as("an ordinary provider was never going to be checked; saying so would be noise")
                .isEmpty();

            unwired.denialReason(USER, "USER", BRIDGE, null);
            unwired.denialReason(USER, "USER", BRIDGE, null);
            assertThat(appender.list)
                .as("every other failure mode of this class is silent, so an unwired guard - "
                    + "which makes it a no-op - must not be. Once per process, not per save.")
                .hasSize(1);
            assertThat(appender.list.get(0).getLevel())
                .isEqualTo(ch.qos.logback.classic.Level.WARN);
        } finally {
            logger.detachAppender(appender);
        }
    }

    private void denyWith(String reason) {
        when(bridgeAccessGuard.check(any(), any(), any()))
            .thenReturn(new BridgeAccessDecision(false, reason, BRIDGE, null));
    }

    /** Guards the message every path shares, so the three call sites cannot word it differently. */
    @Test
    @DisplayName("the refusal names the provider and what to do about it")
    void refusalMessageNamesTheProviderAndTheWayOut() {
        assertThat(BridgeProviderSaveGuard.deniedMessage(BRIDGE))
            .contains(BRIDGE)
            .contains("another provider")
            .contains("administrator");
    }

    @Test
    @DisplayName("the cloud refusal says something different from the access refusal")
    void theCloudRefusalGivesDifferentAdvice() {
        String cloud = BridgeProviderSaveGuard.deniedMessage(BRIDGE, BridgeProviderSaveGuard.REASON_CLOUD);
        String policy = BridgeProviderSaveGuard.deniedMessage(
            BRIDGE, BridgeAccessDecision.REASON_NOT_ADMIN);

        // "Ask an administrator for access" is the right next step when a policy could be widened
        // for this caller. Under REASON_CLOUD no policy an administrator could widen lets a USER select,
        // so repeating it sends the reader somewhere that cannot help. Asserted because a distinct
        // reason token was introduced FOR this wording: without this test, deleting the cloud
        // branch of deniedMessage changes nothing any test can see.
        assertThat(cloud).contains(BRIDGE).doesNotContain("administrator");
        assertThat(cloud)
            .as("the useful advice is to name the model you want; the platform routes it")
            .containsIgnoringCase("not a selectable provider");
        assertThat(policy)
            .as("the access refusal keeps its own advice - the two must not collapse into one")
            .contains("administrator");
        assertThat(cloud).isNotEqualTo(policy);
    }

    @Test
    @DisplayName("the edition comes from AppEditionProvider when it is wired - the branch production runs")
    void editionComesFromTheProviderWhenWired() {
        // Every other edition-sensitive test here pins the auth.mode FALLBACK. Production never
        // takes it: common-lib auto-configuration registers the bean in every service. Without this
        // test, inverting isSelfHosted() in the real branch left the whole suite green.
        com.apimarketplace.common.web.AppEditionProvider edition =
            org.mockito.Mockito.mock(com.apimarketplace.common.web.AppEditionProvider.class);
        guard.setAppEditionProvider(edition);
        guard.setAuthMode("embedded"); // the fallback says self-hosted; the provider must win

        when(edition.isSelfHosted()).thenReturn(false);
        assertThat(guard.denialReason(USER, "USER", BRIDGE, null))
            .as("hosted per the provider, so the cloud refusal applies to a user even though the "
                + "fallback would have said self-hosted")
            .contains(BridgeProviderSaveGuard.REASON_CLOUD);

        when(edition.isSelfHosted()).thenReturn(true);
        when(bridgeAccessGuard.check(any(), any(), any()))
            .thenReturn(BridgeAccessDecision.allow(BRIDGE, null));
        assertThat(guard.denialReason(USER, "USER,ADMIN", BRIDGE, null))
            .as("self-hosted per the provider: the access policy decides, and it allowed")
            .isEmpty();
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("cloud: a bridge is a selectable provider for an ADMIN only")
    class CloudRefusesNonAdminBridgeSaves {

        @BeforeEach
        void cloud() {
            guard.setAuthMode("");
        }

        @ParameterizedTest(name = "roles={0}")
        @ValueSource(strings = {"USER", "MEMBER", "admin"})
        @DisplayName("a non-admin is refused whatever the policy says (the role is the gateway's upper-case ADMIN, nothing looser)")
        void everyNonAdminIsRefused(String roles) {
            assertThat(guard.denialReason(USER, roles, BRIDGE, null))
                .as("all four CLIs run on one operator subscription; a user's pick buys nothing "
                    + "an anthropic/openai pick does not already get through the execution links")
                .contains(BridgeProviderSaveGuard.REASON_CLOUD);
        }

        @Test
        @DisplayName("absent roles are a non-admin, not an exemption")
        void absentRolesAreRefused() {
            assertThat(guard.denialReason(USER, null, BRIDGE, null))
                .contains(BridgeProviderSaveGuard.REASON_CLOUD);
        }

        @Test
        @DisplayName("for a non-admin the access policy is never consulted - the answer must not depend on it")
        void theAccessGuardIsNotCalledForAUser() {
            guard.denialReason(USER, "USER", BRIDGE, null);

            // Not an optimisation. Consulting it would make the refusal depend on a policy an
            // admin can widen (all_users), silently re-opening in cloud the choice this closes.
            verify(bridgeAccessGuard, never()).check(any(), any(), any());
        }

        @ParameterizedTest(name = "roles={0}")
        @ValueSource(strings = {"ADMIN", "USER,ADMIN", "USER,ADMIN,MEMBER"})
        @DisplayName("an ADMIN is the policy's call: allowed when it allows")
        void anAdminIsAdmittedByThePolicy(String roles) {
            when(bridgeAccessGuard.check(any(), any(), any()))
                .thenReturn(BridgeAccessDecision.allow(BRIDGE, null));

            // The regression this pins: refusing admins too left the platform's own operators
            // unable to save an agent on the CLI they administer. The policy still speaks, so
            // the admin-only default admits exactly them and nobody else.
            assertThat(guard.denialReason(USER, roles, BRIDGE, null)).isEmpty();
            verify(bridgeAccessGuard).check(USER, roles, BRIDGE);
        }

        @Test
        @DisplayName("an ADMIN is still refused by a standing policy denial (a disabled bridge)")
        void anAdminIsStillBoundByThePolicy() {
            denyWith(BridgeAccessDecision.REASON_DISABLED);

            assertThat(guard.denialReason(USER, "ADMIN", BRIDGE, null))
                .as("cloud admits an admin to the policy, it does not exempt them from it")
                .contains(BridgeAccessDecision.REASON_DISABLED);
        }

        @Test
        @DisplayName("an agent ALREADY on a bridge stays editable, exactly as in CE")
        void anExistingBridgeAgentStaysEditable() {
            assertThat(guard.denialReason(USER, "USER", BRIDGE, BRIDGE))
                .as("the 44 agents already stored on claude-code must keep being editable - "
                    + "refusing here would strand every one of them, including the repair that "
                    + "moves them off it")
                .isEmpty();
        }

        @Test
        @DisplayName("moving an agent OFF a bridge onto an API provider is allowed")
        void movingOffTheBridgeIsAllowed() {
            assertThat(guard.denialReason(USER, "USER", "anthropic", BRIDGE))
                .as("this is the migration path; refusing it would be the opposite of the rule")
                .isEmpty();
        }

        @Test
        @DisplayName("a hand-constructed guard with no access bean stays permissive")
        void unwiredFallbackDoesNotRefuse() {
            // AgentController and AgentCrudModule each field-initialise one of these as a fallback.
            // It has no injected edition, so it reads as hosted - and refusing on that would block
            // every bridge save on a SELF-HOSTED install, where they are the whole point. The
            // absent access bean is the tell that this instance was never wired.
            BridgeProviderSaveGuard unwired = new BridgeProviderSaveGuard();

            assertThat(unwired.denialReason(USER, "USER", BRIDGE, null)).isEmpty();
        }

        @Test
        @DisplayName("an API provider is never refused")
        void apiProvidersAreUntouched() {
            assertThat(guard.denialReason(USER, "USER", "anthropic", null)).isEmpty();
            assertThat(guard.denialReason(USER, "USER", "deepseek", "openai")).isEmpty();
        }
    }
}
