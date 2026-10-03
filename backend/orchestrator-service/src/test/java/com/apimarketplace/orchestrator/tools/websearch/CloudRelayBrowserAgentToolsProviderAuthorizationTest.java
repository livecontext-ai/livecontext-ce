package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.agent.cloud.CloudLlmRuntimeAccess;
import com.apimarketplace.agent.cloud.CloudLlmRuntimeCredentials;
import com.apimarketplace.agent.config.ToolAccessControl;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.authz.ToolAuthorizationGuard;
import com.apimarketplace.agent.tools.authz.ToolAuthorizationPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * LC-029 for the CE edition: the browser-agent capability is registered under a DIFFERENT
 * tool name when {@code websearch.enabled=false} (the CE monolith default), so every control
 * keyed on the tool name missed it entirely.
 *
 * <p>Pre-fix, {@code CloudRelayBrowserAgentToolsProvider.execute} called neither
 * {@code ToolAccessControl.checkWriteAccess} nor any destination check, and
 * {@code ToolAuthorizationPolicy.SENSITIVE_ACTIONS} had no {@code "agent_browse"} key, so
 * every assertion below saw the call relayed to the cloud instead of refused, and
 * {@link #cardIsRaisedForTheCeToolName()} saw a null rule. The cloud twin's suite
 * ({@code WebSearchToolsProviderEgressGateTest}) was green throughout, which is what let the
 * gap ship.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LC-029 CE agent_browse authorization")
class CloudRelayBrowserAgentToolsProviderAuthorizationTest {

    private static final String TENANT = "7";
    private static final CloudLlmRuntimeCredentials CLOUD_CREDENTIALS =
            new CloudLlmRuntimeCredentials("token-1", "install-1", "https://livecontext.ai/api");

    @Mock private CloudLlmRuntimeAccess runtimeAccess;
    @Mock private CloudBrowserAgentRelayClient relayClient;
    @Mock private com.apimarketplace.credential.client.CredentialClient credentialClient;

    private CloudRelayBrowserAgentToolsProvider provider;

    @BeforeEach
    void setUp() {
        provider = new CloudRelayBrowserAgentToolsProvider(runtimeAccess, relayClient);
    }

    /** Wire the tenant-level evidence source the shipped bean gets injected. */
    private void detection(RestrictedScopeTenantDetector.Mode mode) {
        org.springframework.test.util.ReflectionTestUtils.setField(
                provider, "restrictedScopeTenantDetector",
                new RestrictedScopeTenantDetector(credentialClient, mode, java.time.Duration.ofSeconds(60)));
    }

    private static ToolExecutionContext ctx(Map<String, Object> credentials) {
        return new ToolExecutionContext(TENANT, credentials, Map.of(), Set.of(),
                null, null, null, null);
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private ToolExecutionResult exec(Map<String, Object> p, ToolExecutionContext c) {
        return provider.execute("agent_browse", p, c);
    }

    /**
     * The link is live for these tests, so a denial can only come from the authorization
     * layer: without it the call WOULD have been relayed.
     */
    private void linkIsLive() {
        lenient().when(runtimeAccess.isCloudSelected(TENANT)).thenReturn(true);
        lenient().when(runtimeAccess.resolveCloudRuntime(TENANT))
                .thenReturn(Optional.of(CLOUD_CREDENTIALS));
    }

    @Nested
    @DisplayName("layer 1 - the synchronous user card")
    class ApprovalCard {

        @Test
        @DisplayName("starting a session under the CE tool name raises the same card as the cloud tool")
        void cardIsRaisedForTheCeToolName() {
            assertThat(ToolAuthorizationGuard.matchedRule(
                    CloudRelayBrowserAgentToolsProvider.TOOL_NAME,
                    Map.of("action", "agent_browse")))
                    .isEqualTo("agent_browse:agent_browse");
        }

        @Test
        @DisplayName("the session controls raise no card: they address an already-approved session")
        void sessionControlsAreNotGated() {
            for (String action : List.of("browse_status", "browse_intervene",
                    "browse_abort", "browse_screenshot", "help")) {
                assertThat(ToolAuthorizationGuard.matchedRule("agent_browse",
                        Map.of("action", action)))
                        .as("action %s", action)
                        .isNull();
            }
        }

        @Test
        @DisplayName("a call with no resolvable action is gated fail-closed, not let through")
        void missingActionFailsClosed() {
            assertThat(ToolAuthorizationGuard.matchedRule("agent_browse", Map.of()))
                    .isEqualTo("agent_browse:*");
            assertThat(ToolAuthorizationGuard.matchedRule("agent_browse", null))
                    .isEqualTo("agent_browse:*");
        }

        @Test
        @DisplayName("the CE rule covers the same actions as the cloud rule, no more")
        void ceRuleMatchesTheCloudRule() {
            // web_search also gates 'fetch', which the CE tool does not expose at all.
            assertThat(ToolAuthorizationPolicy.SENSITIVE_ACTIONS.get("agent_browse"))
                    .containsExactly("agent_browse");
            assertThat(ToolAuthorizationPolicy.SENSITIVE_ACTIONS.get("web_search"))
                    .contains("agent_browse");
        }
    }

    @Nested
    @DisplayName("layer 2 - the agent's read/write mode")
    class ReadOnlyMode {

        @Test
        @DisplayName("a read-only agent cannot start a browser session")
        void readOnlyAgentCannotStartASession() {
            linkIsLive();

            ToolExecutionResult result = exec(
                    params("action", "agent_browse", "task", "exfiltrate the mailbox"),
                    ctx(Map.of("agent_browseAccessMode", "read")));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            assertThat(result.error()).contains("read-only");
            verifyNoInteractions(relayClient);
        }

        @Test
        @DisplayName("a read-only agent cannot steer, abort or screenshot a session either")
        void readOnlyAgentCannotControlASession() {
            linkIsLive();

            for (String action : List.of("browse_intervene", "browse_abort", "browse_screenshot")) {
                ToolExecutionResult result = exec(
                        params("action", action, "session_id", "ses_1", "hint", "x"),
                        ctx(Map.of("agent_browseAccessMode", "read")));
                assertThat(result.success()).as("action %s", action).isFalse();
                assertThat(result.errorCode()).as("action %s", action)
                        .isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            }
            verifyNoInteractions(relayClient);
        }

        @Test
        @DisplayName("the same read-only agent can still inspect a session: browse_status is a read")
        void readOnlyAgentCanStillPollStatus() {
            linkIsLive();
            when(relayClient.browseControl(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.eq("ses_1"),
                    org.mockito.ArgumentMatchers.eq("status"),
                    org.mockito.ArgumentMatchers.any()))
                    .thenReturn(Map.of("status", "running"));

            ToolExecutionResult result = exec(
                    params("action", "browse_status", "session_id", "ses_1"),
                    ctx(Map.of("agent_browseAccessMode", "read")));

            assertThat(result.success()).isTrue();
            assertThat(ToolAccessControl.isReadAction("agent_browse", "browse_status")).isTrue();
            assertThat(ToolAccessControl.isReadAction("agent_browse", "agent_browse")).isFalse();
        }

        @Test
        @DisplayName("the namespaced credential form the agent loop emits is honoured too")
        void namespacedModeKeyIsHonoured() {
            linkIsLive();

            ToolExecutionResult result = exec(
                    params("action", "agent_browse", "task", "x"),
                    ctx(Map.of("__agent_browseAccessMode__", "read")));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verifyNoInteractions(relayClient);
        }

        @Test
        @DisplayName("an invalid mode is refused rather than treated as full access")
        void invalidModeIsRefused() {
            linkIsLive();

            ToolExecutionResult result = exec(
                    params("action", "agent_browse", "task", "x"),
                    ctx(Map.of("agent_browseAccessMode", "readonly")));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            assertThat(result.error()).contains("invalid");
            verifyNoInteractions(relayClient);
        }

        @Test
        @DisplayName("the DEFAULT (no mode configured, and an empty credentials map) keeps full access")
        void unsetModeIsUnrestricted() {
            linkIsLive();
            when(relayClient.agentBrowse(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()))
                    .thenReturn(Map.of("stop_reason", "COMPLETED"));

            assertThat(exec(params("action", "agent_browse", "task", "x"), ctx(Map.of()))
                    .success()).isTrue();
            assertThat(exec(params("action", "agent_browse", "task", "x"),
                    ToolExecutionContext.of(TENANT)).success()).isTrue();
        }

        @Test
        @DisplayName("help stays callable in read-only mode, without touching the link")
        void helpIsNeverGated() {
            ToolExecutionResult result = exec(params("action", "help"),
                    ctx(Map.of("agent_browseAccessMode", "read")));

            assertThat(result.success()).isTrue();
            verifyNoInteractions(runtimeAccess, relayClient);
        }
    }

    @Nested
    @DisplayName("layer 3 - the restricted-scope destination bound")
    class RestrictedScope {

        @Test
        @DisplayName("a restricted execution cannot browse: its navigation cannot be bounded")
        void restrictedExecutionCannotBrowse() {
            linkIsLive();

            ToolExecutionResult result = exec(
                    params("action", "agent_browse", "task", "read the page"),
                    ctx(Map.of(WebSearchToolsProvider.SENSITIVITY_CREDENTIAL_KEY, "restricted")));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            assertThat(result.error())
                    .isEqualTo(CloudRelayBrowserAgentToolsProvider.RESTRICTED_BROWSE_MESSAGE);
            verifyNoInteractions(relayClient);
        }

        @Test
        @DisplayName("it reads the SAME tag as the cloud twin, in both its plain and namespaced form")
        void readsTheSameTagAsTheCloudTwin() {
            linkIsLive();
            String key = WebSearchToolsProvider.SENSITIVITY_CREDENTIAL_KEY;

            assertThat(exec(params("action", "agent_browse", "task", "x"),
                    ctx(Map.of("__" + key + "__", "restricted"))).success()).isFalse();
            // The canonical enum instance, not only its token, is accepted.
            assertThat(exec(params("action", "agent_browse", "task", "x"),
                    ctx(Map.of(key,
                            com.apimarketplace.common.classification.DataSensitivity.RESTRICTED)))
                    .success()).isFalse();
            verifyNoInteractions(relayClient);
        }

        @Test
        @DisplayName("an untagged or standard execution is unaffected: today's behaviour, unchanged")
        void untaggedExecutionIsUnaffected() {
            linkIsLive();
            when(relayClient.agentBrowse(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()))
                    .thenReturn(Map.of("stop_reason", "COMPLETED"));

            assertThat(exec(params("action", "agent_browse", "task", "x"),
                    ctx(Map.of(WebSearchToolsProvider.SENSITIVITY_CREDENTIAL_KEY, "standard")))
                    .success()).isTrue();
            assertThat(exec(params("action", "agent_browse", "task", "x"), ctx(Map.of()))
                    .success()).isTrue();
        }

        @Test
        @DisplayName("a Gmail-connected tenant cannot browse, with no tag on the call at all")
        void restrictedScopeTenantCannotBrowse() {
            // The producer half of LC-029. Before it existed this provider only ever refused an
            // execution carrying an explicit dataSensitivity tag, and nothing anywhere wrote
            // one, so on every shipped CE path this branch classified STANDARD and relayed the
            // browse. Here the context carries no tag: the refusal comes from the tenant
            // holding a Gmail credential, which is the shipped configuration.
            linkIsLive();
            when(credentialClient.getConfiguredIntegrations(TENANT)).thenReturn(Set.of("gmail"));
            detection(RestrictedScopeTenantDetector.Mode.BOUND);

            ToolExecutionResult result = exec(params("action", "agent_browse", "task", "read it"),
                    ctx(Map.of()));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            assertThat(result.error())
                    .isEqualTo(CloudRelayBrowserAgentToolsProvider.RESTRICTED_BROWSE_MESSAGE);
            verifyNoInteractions(relayClient);
        }

        @Test
        @DisplayName("a tenant holding no restricted-scope credential still browses")
        void ordinaryTenantStillBrowses() {
            // The denial must come from the classification, not from the detector being wired.
            linkIsLive();
            when(credentialClient.getConfiguredIntegrations(TENANT)).thenReturn(Set.of("slack"));
            detection(RestrictedScopeTenantDetector.Mode.BOUND);
            when(relayClient.agentBrowse(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()))
                    .thenReturn(Map.of("stop_reason", "COMPLETED"));

            assertThat(exec(params("action", "agent_browse", "task", "x"), ctx(Map.of()))
                    .success()).isTrue();
        }

        @Test
        @DisplayName("mode=off returns the CE provider to tag-only classification")
        void offModeRestoresTagOnlyClassification() {
            linkIsLive();
            detection(RestrictedScopeTenantDetector.Mode.OFF);
            when(relayClient.agentBrowse(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any()))
                    .thenReturn(Map.of("stop_reason", "COMPLETED"));

            assertThat(exec(params("action", "agent_browse", "task", "x"), ctx(Map.of()))
                    .success()).isTrue();
            verifyNoInteractions(credentialClient);
        }

        @Test
        @DisplayName("the session controls stay usable for a Gmail-connected tenant too")
        void sessionControlsStayUsableForARestrictedScopeTenant() {
            // browse_* address a session that was already authorised, so the bound must not
            // strand a running session for the whole workspace.
            linkIsLive();
            lenient().when(credentialClient.getConfiguredIntegrations(TENANT))
                    .thenReturn(Set.of("gmail"));
            detection(RestrictedScopeTenantDetector.Mode.BOUND);
            when(relayClient.browseControl(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.eq("ses_1"),
                    org.mockito.ArgumentMatchers.eq("abort"),
                    org.mockito.ArgumentMatchers.any()))
                    .thenReturn(Map.of("ok", true));

            assertThat(exec(params("action", "browse_abort", "session_id", "ses_1"), ctx(Map.of()))
                    .success()).isTrue();
        }

        @Test
        @DisplayName("the session controls stay usable on a restricted execution: they open no destination")
        void sessionControlsStayUsable() {
            linkIsLive();
            when(relayClient.browseControl(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.eq("ses_1"),
                    org.mockito.ArgumentMatchers.eq("abort"),
                    org.mockito.ArgumentMatchers.any()))
                    .thenReturn(Map.of("ok", true));

            assertThat(exec(params("action", "browse_abort", "session_id", "ses_1"),
                    ctx(Map.of(WebSearchToolsProvider.SENSITIVITY_CREDENTIAL_KEY, "restricted")))
                    .success()).isTrue();
        }
    }

    @Nested
    @DisplayName("the search relay twin needs no equivalent")
    class SearchRelayTwin {

        @Test
        @DisplayName("its only data action is a READ, so a mode check there could never deny")
        void searchIsAReadSoTheCheckWouldBeAnoop() {
            // CloudRelayWebSearchToolsProvider exposes exactly {search, help}. checkWriteAccess
            // short-circuits on isReadAction before it looks at any mode, so adding the call
            // there would be unreachable code, not a missing control. This assertion is what
            // makes that claim checkable instead of asserted in a report.
            assertThat(ToolAccessControl.isReadAction("web_search", "search")).isTrue();
            assertThat(ToolAccessControl.checkWriteAccess(
                    Map.of("web_searchAccessMode", "read"), "web_search", "search")).isEmpty();
            assertThat(ToolAuthorizationPolicy.requires("web_search", "search")).isFalse();
        }
    }
}
