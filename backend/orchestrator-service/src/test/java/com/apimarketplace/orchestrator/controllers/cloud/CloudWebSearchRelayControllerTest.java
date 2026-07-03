package com.apimarketplace.orchestrator.controllers.cloud;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentObservabilityRequest;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.auth.client.AuthClient;
import com.apimarketplace.common.credit.CreditConsumptionClient;
import com.apimarketplace.orchestrator.config.WebSearchConfig;
import com.apimarketplace.orchestrator.tools.websearch.BrowserAgentModule;
import com.apimarketplace.orchestrator.tools.websearch.CeWebSearchRelayRequest;
import com.apimarketplace.orchestrator.tools.websearch.WebSearchModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Cloud-side relay endpoint tests: install-link validation, request shaping and
 * the single WEB_SEARCH debit on the linked cloud account.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CloudWebSearchRelayController (cloud)")
class CloudWebSearchRelayControllerTest {

    private static final long CLOUD_USER_ID = 42L;
    private static final String INSTALL_ID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    @Mock
    private AuthClient authClient;
    @Mock
    private WebSearchModule searchModule;
    @Mock
    private com.apimarketplace.orchestrator.tools.websearch.BrowserAgentModule browserAgentModule;

    private CloudWebSearchRelayController controller;

    @BeforeEach
    void setUp() {
        controller = new CloudWebSearchRelayController(authClient, searchModule, browserAgentModule);
    }

    private static CeWebSearchRelayRequest request(String query) {
        return new CeWebSearchRelayRequest(query, 5, "week", "stream-1", "tc-1");
    }

    @Nested
    @DisplayName("link validation")
    class LinkValidation {

        @Test
        @DisplayName("rejects installs the caller does not own with 403 and never executes a search")
        void rejectsNonOwnerInstall() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(false);

            ResponseEntity<Map<String, Object>> response =
                    controller.search(CLOUD_USER_ID, INSTALL_ID, request("java"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isEqualTo(Map.of("error", "CE_LINK_NOT_ACTIVE"));
            verifyNoInteractions(searchModule);
        }

        @Test
        @DisplayName("rejects a missing user id with 401")
        void rejectsMissingUserId() {
            ResponseEntity<Map<String, Object>> response =
                    controller.search(null, INSTALL_ID, request("java"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verifyNoInteractions(authClient, searchModule);
        }

        @Test
        @DisplayName("rejects a blank query with 400 after link validation")
        void rejectsBlankQuery() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);

            ResponseEntity<Map<String, Object>> response =
                    controller.search(CLOUD_USER_ID, INSTALL_ID, request("  "));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isEqualTo(Map.of("error", "INVALID_RELAY_REQUEST"));
            verifyNoInteractions(searchModule);
        }
    }

    @Nested
    @DisplayName("happy path")
    class HappyPath {

        @Test
        @DisplayName("executes the search as the cloud user and threads the CE chat ids into the billing context")
        void executesAsCloudUserWithCeChatIds() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
            Map<String, Object> searchData = Map.of("results", List.of(Map.of("url", "https://e.com")));
            when(searchModule.execute(eq("search"), anyMap(), eq("42"), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(searchData)));

            ResponseEntity<Map<String, Object>> response =
                    controller.search(CLOUD_USER_ID, INSTALL_ID, request("java 21"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(searchData);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
            ArgumentCaptor<ToolExecutionContext> contextCaptor =
                    ArgumentCaptor.forClass(ToolExecutionContext.class);
            verify(searchModule, times(1)).execute(eq("search"), paramsCaptor.capture(),
                    eq("42"), contextCaptor.capture());
            assertThat(paramsCaptor.getValue())
                    .containsEntry("query", "java 21")
                    .containsEntry("max_results", 5)
                    .containsEntry("time_range", "week");
            // regression (audit F1): CE-supplied chat identifiers must NEVER reach the
            // billing credentials - the ledger dedups on a globally-unique source_id, so
            // a client-controlled key would let a linked install replay one pair for
            // unlimited searches billed once. Billing falls back to a server-side UUID.
            assertThat(contextCaptor.getValue().tenantId()).isEqualTo("42");
            assertThat(contextCaptor.getValue().credentials()).isEmpty();
        }

        @Test
        @DisplayName("clamps max_results into [1, 50] and omits absent optional params")
        void clampsMaxResultsAndOmitsAbsentParams() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
            when(searchModule.execute(eq("search"), anyMap(), eq("42"), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("results", List.of()))));

            controller.search(CLOUD_USER_ID, INSTALL_ID,
                    new CeWebSearchRelayRequest("java", 999, null, null, null));
            controller.search(CLOUD_USER_ID, INSTALL_ID,
                    new CeWebSearchRelayRequest("java", -3, null, null, null));
            controller.search(CLOUD_USER_ID, INSTALL_ID,
                    new CeWebSearchRelayRequest("java", null, null, null, null));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
            verify(searchModule, times(3)).execute(eq("search"), paramsCaptor.capture(), eq("42"), any());
            List<Map<String, Object>> calls = paramsCaptor.getAllValues();
            assertThat(calls.get(0)).containsEntry("max_results", 50);
            assertThat(calls.get(1)).containsEntry("max_results", 1);
            assertThat(calls.get(2)).doesNotContainKeys("max_results", "time_range");
            assertThat(calls.get(2)).doesNotContainKey("__streamId__");
        }
    }

    @Nested
    @DisplayName("failure propagation")
    class FailurePropagation {

        @Test
        @DisplayName("search failure maps to 502 with the module error")
        void searchFailureMapsTo502() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
            when(searchModule.execute(eq("search"), anyMap(), eq("42"), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.failure(
                            ToolErrorCode.EXTERNAL_SERVICE_ERROR, "No response from websearch-service")));

            ResponseEntity<Map<String, Object>> response =
                    controller.search(CLOUD_USER_ID, INSTALL_ID, request("java"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(response.getBody()).isEqualTo(Map.of("error", "No response from websearch-service"));
        }

        @Test
        @DisplayName("module returning empty maps to 502")
        void emptyModuleResultMapsTo502() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
            when(searchModule.execute(eq("search"), anyMap(), eq("42"), any()))
                    .thenReturn(Optional.empty());

            ResponseEntity<Map<String, Object>> response =
                    controller.search(CLOUD_USER_ID, INSTALL_ID, request("java"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        }
    }

    /**
     * End-to-end billing proof with the REAL {@link WebSearchModule}: every successful
     * relayed search posts exactly ONE flat WEB_SEARCH debit on the cloud account under
     * a SERVER-generated sourceId (client identifiers never form the dedup key); a
     * failed search posts none.
     */
    @Nested
    @DisplayName("billing through the real WebSearchModule")
    class BillingThroughRealModule {

        @Mock
        private RestTemplate searxRestTemplate;
        @Mock
        private WebSearchConfig config;
        @Mock
        private CreditConsumptionClient creditClient;

        @BeforeEach
        void wireRealModule() {
            when(searxRestTemplate.getInterceptors()).thenReturn(List.of());
            lenient().when(config.getServiceUrl()).thenReturn("http://websearch:8085");
            WebSearchModule realModule = new WebSearchModule(searxRestTemplate, config, creditClient);
            clearInvocations(searxRestTemplate);
            controller = new CloudWebSearchRelayController(authClient, realModule, null);
        }

        @Test
        @DisplayName("regression (audit F1): each relayed search bills once with a SERVER-generated sourceId - replaying client ids cannot dedupe-dodge the debit")
        void billsWebSearchOnceOnCloudUser() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
            when(searxRestTemplate.postForObject(eq("http://websearch:8085/search"), any(), eq(Map.class)))
                    .thenReturn(Map.of("results", List.of()));

            ResponseEntity<Map<String, Object>> first =
                    controller.search(CLOUD_USER_ID, INSTALL_ID, request("java"));
            ResponseEntity<Map<String, Object>> replay =
                    controller.search(CLOUD_USER_ID, INSTALL_ID, request("java"));

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
            ArgumentCaptor<String> sourceIds = ArgumentCaptor.forClass(String.class);
            verify(creditClient, times(2)).consumeCreditsAsync(
                    eq("42"), eq("WEB_SEARCH"),
                    sourceIds.capture(),
                    eq("websearch"), eq("default"), eq(0), eq(0));
            // Server-side fallback scheme, never the client-controlled CHAT scheme.
            assertThat(sourceIds.getAllValues()).allSatisfy(id -> {
                assertThat(id).contains(":FALLBACK:");
                assertThat(id).doesNotContain("stream-1").doesNotContain("tc-1");
            });
            // Two searches with IDENTICAL client identifiers debit under DISTINCT ids.
            assertThat(sourceIds.getAllValues().get(0)).isNotEqualTo(sourceIds.getAllValues().get(1));
        }

        @Test
        @DisplayName("failed relayed search bills nothing")
        void failedSearchBillsNothing() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
            when(searxRestTemplate.postForObject(eq("http://websearch:8085/search"), any(), eq(Map.class)))
                    .thenReturn(null);

            ResponseEntity<Map<String, Object>> response =
                    controller.search(CLOUD_USER_ID, INSTALL_ID, request("java"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            verify(creditClient, never()).consumeCreditsAsync(
                    anyString(), anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt());
        }
    }

    /**
     * End-to-end billing proof with the REAL {@link BrowserAgentModule} (the browse
     * analogue of {@link BillingThroughRealModule}): every relayed {@code agent_browse}
     * lands exactly ONE {@code BROWSER_AGENT_EXECUTION} observability row keyed to the
     * CLOUD tenant (the CE install never bills), through the module's own
     * {@code recordObservabilityFromResult} path - the SAME path
     * {@code __skipObservability__} would short-circuit. Because the cloud controller
     * does NOT thread that flag (asserted structurally in
     * {@code AgentBrowseRelay.runsAsCloudUserAndReturnsCdpUrl}), the debit fires; and a
     * second identical browse bills AGAIN (browse is NOT dedup-protected, unlike a
     * catalog tool).
     */
    @Nested
    @DisplayName("browse billing through the real BrowserAgentModule")
    class BrowseBillingThroughRealModule {

        @Mock
        private RestTemplate browseRestTemplate;
        @Mock
        private WebSearchConfig browseConfig;
        @Mock
        private StringRedisTemplate browseRedisTemplate;
        @Mock
        private ListOperations<String, String> browseListOps;
        @Mock
        private AgentClient agentClient;

        private final ObjectMapper objectMapper = new ObjectMapper();

        /** A COMPLETED browse blob with a non-zero token cost so observability fires. */
        private static final String COMPLETED_BROWSE_RESULT =
                "{\"final_result\":\"done\",\"stop_reason\":\"COMPLETED\","
                + "\"cost\":{\"tokens_in\":5000,\"tokens_out\":250,\"llm_calls\":1,"
                + "\"browser_seconds\":1.0,\"cost_usd\":0.0,\"by_model\":{}}}";

        @BeforeEach
        void wireRealBrowserModule() {
            // Full 10-arg ctor with only the AgentClient (observability) collaborator
            // wired - mirrors the observability tests in BrowserAgentModuleTest.
            BrowserAgentModule realBrowseModule = new BrowserAgentModule(
                    browseRestTemplate, browseConfig, browseRedisTemplate, objectMapper,
                    null, null, null, null, null, agentClient);
            controller = new CloudWebSearchRelayController(authClient, searchModule, realBrowseModule);
        }

        private com.apimarketplace.orchestrator.tools.websearch.CeBrowseRelayRequest browse(String task) {
            return new com.apimarketplace.orchestrator.tools.websearch.CeBrowseRelayRequest(
                    task, "https://example.com",
                    Map.of("provider", "google", "model", "gemini-2.5-flash"),
                    25, Map.of("interaction_mode", "autonomous"), "stream-9", "tc-9");
        }

        @Test
        @DisplayName("each relayed browse records exactly one BROWSER_AGENT_EXECUTION row on the CLOUD tenant; a second identical browse bills again (no dedup)")
        @SuppressWarnings("unchecked")
        void billsBrowserAgentOnCloudUserEveryCall() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
            when(browseConfig.getBrowserAgentBlpopTimeout()).thenReturn(150);
            when(browseConfig.getCallbackBaseUrl()).thenReturn("http://orchestrator:8099");
            when(browseRedisTemplate.opsForList()).thenReturn(browseListOps);
            HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
            when(browseRedisTemplate.opsForHash()).thenReturn((HashOperations) hashOps);
            when(browseListOps.leftPop(anyString(), any(Duration.class)))
                    .thenReturn(COMPLETED_BROWSE_RESULT);
            when(browseRestTemplate.postForObject(anyString(), any(), eq(Map.class)))
                    .thenReturn(Map.of("job_id", "job-browse-1"));

            ResponseEntity<Map<String, Object>> first =
                    controller.agentBrowse(CLOUD_USER_ID, INSTALL_ID, browse("book a flight"));
            ResponseEntity<Map<String, Object>> replay =
                    controller.agentBrowse(CLOUD_USER_ID, INSTALL_ID, browse("book a flight"));

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);

            ArgumentCaptor<AgentObservabilityRequest> reqCaptor =
                    ArgumentCaptor.forClass(AgentObservabilityRequest.class);
            // Exactly one debit per browse - the second identical call bills AGAIN.
            verify(agentClient, times(2)).recordObservability(reqCaptor.capture());
            assertThat(reqCaptor.getAllValues()).allSatisfy(req -> {
                // Billed to the CLOUD user (X-User-ID=42), never the CE install.
                assertThat(req.getTenantId()).isEqualTo("42");
                assertThat(req.getAgentType()).isEqualTo("browser_agent");
                assertThat(req.getStatus()).isEqualTo("COMPLETED");
                assertThat(req.getSource()).isEqualTo("chat_tool");
                assertThat(req.getTotalTokens()).isEqualTo(5250L);
            });
        }
    }

    /**
     * Cloud-side browser-agent relay: install-link validation, request shaping, the
     * single BROWSER_AGENT debit on the linked cloud account (via the module's own
     * observability path), and the cloud-hosted CDP live-view URL flowing back verbatim.
     */
    @Nested
    @DisplayName("agent_browse relay (cloud)")
    class AgentBrowseRelay {

        private com.apimarketplace.orchestrator.tools.websearch.CeBrowseRelayRequest browse(String task) {
            return new com.apimarketplace.orchestrator.tools.websearch.CeBrowseRelayRequest(
                    task, "https://example.com", Map.of("provider", "google", "model", "gemini-2.5-flash"),
                    25, Map.of("interaction_mode", "autonomous"), "stream-9", "tc-9");
        }

        @Test
        @DisplayName("rejects installs the caller does not own with 403 and never runs a browse")
        void rejectsNonOwnerInstall() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(false);

            ResponseEntity<Map<String, Object>> response =
                    controller.agentBrowse(CLOUD_USER_ID, INSTALL_ID, browse("book a flight"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(response.getBody()).isEqualTo(Map.of("error", "CE_LINK_NOT_ACTIVE"));
            verifyNoInteractions(browserAgentModule);
        }

        @Test
        @DisplayName("rejects a missing user id with 401")
        void rejectsMissingUserId() {
            ResponseEntity<Map<String, Object>> response =
                    controller.agentBrowse(null, INSTALL_ID, browse("x"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verifyNoInteractions(authClient, browserAgentModule);
        }

        @Test
        @DisplayName("rejects a blank task with 400 after link validation")
        void rejectsBlankTask() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);

            ResponseEntity<Map<String, Object>> response =
                    controller.agentBrowse(CLOUD_USER_ID, INSTALL_ID, browse("   "));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isEqualTo(Map.of("error", "INVALID_RELAY_REQUEST"));
            verifyNoInteractions(browserAgentModule);
        }

        @Test
        @DisplayName("runs the browse as the cloud user, threads CE run ids into the browse context, and returns the cloud CDP url verbatim")
        void runsAsCloudUserAndReturnsCdpUrl() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
            Map<String, Object> browseData = Map.of(
                    "stop_reason", "COMPLETED",
                    "session_id", "ses_abc",
                    "cdp_ws_url", "wss://cloud.example.com/cdp/ses_abc",
                    "cdp_token", "jwt-cloud-token",
                    "final_result", "done");
            when(browserAgentModule.execute(eq("agent_browse"), anyMap(), eq("42"), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(browseData)));

            ResponseEntity<Map<String, Object>> response =
                    controller.agentBrowse(CLOUD_USER_ID, INSTALL_ID, browse("book a flight"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            // CDP url + token are cloud-hosted and returned verbatim - never rewritten.
            assertThat(response.getBody())
                    .containsEntry("cdp_ws_url", "wss://cloud.example.com/cdp/ses_abc")
                    .containsEntry("cdp_token", "jwt-cloud-token")
                    .containsEntry("session_id", "ses_abc");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
            ArgumentCaptor<ToolExecutionContext> contextCaptor =
                    ArgumentCaptor.forClass(ToolExecutionContext.class);
            verify(browserAgentModule, times(1)).execute(eq("agent_browse"), paramsCaptor.capture(),
                    eq("42"), contextCaptor.capture());
            assertThat(paramsCaptor.getValue())
                    .containsEntry("task", "book a flight")
                    .containsEntry("start_url", "https://example.com")
                    .containsEntry("max_steps", 25)
                    .containsEntry("interaction_mode", "autonomous");
            // Billing is on the cloud tenant; the CE run ids ride the browse context so
            // BrowserAgentModule can mint a CDP token with matching rid/nid claims.
            assertThat(contextCaptor.getValue().tenantId()).isEqualTo("42");
            assertThat(contextCaptor.getValue().credentials())
                    .containsEntry("__streamId__", "stream-9")
                    .containsEntry("__toolCallId__", "tc-9")
                    // Money-path invariant: the cloud controller MUST NOT thread
                    // __skipObservability__ into the browse creds. That flag is the
                    // workflow-node opt-out (BrowserAgentNode records its own row);
                    // if it leaked here, BrowserAgentModule.recordObservabilityFromResult
                    // would early-return and the cloud browse would run FREE. This
                    // assertion fails the moment someone adds it to browseCreds.
                    .doesNotContainKey("__skipObservability__");
        }

        @Test
        @DisplayName("module failure (non-COMPLETED session) maps to 502 with the module error")
        void moduleFailureMapsTo502() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
            when(browserAgentModule.execute(eq("agent_browse"), anyMap(), eq("42"), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.failure(
                            ToolErrorCode.EXECUTION_FAILED, "Browser session failed: DOMAIN_BLOCKED")));

            ResponseEntity<Map<String, Object>> response =
                    controller.agentBrowse(CLOUD_USER_ID, INSTALL_ID, browse("x"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(response.getBody()).isEqualTo(
                    Map.of("error", "Browser session failed: DOMAIN_BLOCKED"));
        }

        @Test
        @DisplayName("degrades to 503 when the browser module is absent (misconfigured cloud)")
        void degradesTo503WithoutModule() {
            CloudWebSearchRelayController noModule =
                    new CloudWebSearchRelayController(authClient, searchModule, null);
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);

            ResponseEntity<Map<String, Object>> response =
                    noModule.agentBrowse(CLOUD_USER_ID, INSTALL_ID, browse("x"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody()).isEqualTo(Map.of("error", "BROWSER_AGENT_UNAVAILABLE"));
        }
    }

    @Nested
    @DisplayName("agent session control relay (cloud)")
    class SessionControlRelay {

        @Test
        @DisplayName("forwards a status call to the module as browse_status with the session id")
        void forwardsStatus() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
            when(browserAgentModule.execute(eq("browse_status"), anyMap(), eq("42"), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("status", "running"))));

            ResponseEntity<Map<String, Object>> response = controller.browseControl(
                    CLOUD_USER_ID, INSTALL_ID, "ses_abc", "status", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isEqualTo(Map.of("status", "running"));
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
            verify(browserAgentModule).execute(eq("browse_status"), params.capture(), eq("42"), any());
            assertThat(params.getValue()).containsEntry("session_id", "ses_abc");
        }

        @Test
        @DisplayName("forwards an intervene call with the hint payload as browse_intervene")
        void forwardsInterveneHint() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);
            when(browserAgentModule.execute(eq("browse_intervene"), anyMap(), eq("42"), any()))
                    .thenReturn(Optional.of(ToolExecutionResult.success(Map.of("ok", true))));

            ResponseEntity<Map<String, Object>> response = controller.browseControl(
                    CLOUD_USER_ID, INSTALL_ID, "ses_abc", "intervene",
                    new com.apimarketplace.orchestrator.tools.websearch.CeBrowseControlRequest(
                            "ses_abc", "click Accept"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
            verify(browserAgentModule).execute(eq("browse_intervene"), params.capture(), eq("42"), any());
            assertThat(params.getValue())
                    .containsEntry("session_id", "ses_abc")
                    .containsEntry("hint", "click Accept");
        }

        @Test
        @DisplayName("rejects an unknown control verb with 400 and never touches the module")
        void rejectsUnknownVerb() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);

            ResponseEntity<Map<String, Object>> response = controller.browseControl(
                    CLOUD_USER_ID, INSTALL_ID, "ses_abc", "teleport", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isEqualTo(Map.of("error", "INVALID_RELAY_REQUEST"));
            verifyNoInteractions(browserAgentModule);
        }

        @Test
        @DisplayName("rejects a non-owner install with 403 before any control call")
        void rejectsNonOwner() {
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(false);

            ResponseEntity<Map<String, Object>> response = controller.browseControl(
                    CLOUD_USER_ID, INSTALL_ID, "ses_abc", "abort", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verifyNoInteractions(browserAgentModule);
        }

        @Test
        @DisplayName("rejects a missing user id with 401 before any control call")
        void rejectsMissingUserId() {
            ResponseEntity<Map<String, Object>> response = controller.browseControl(
                    null, INSTALL_ID, "ses_abc", "status", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verifyNoInteractions(authClient, browserAgentModule);
        }

        @Test
        @DisplayName("degrades to 503 when the browser module is absent (misconfigured cloud)")
        void degradesTo503WithoutModule() {
            CloudWebSearchRelayController noModule =
                    new CloudWebSearchRelayController(authClient, searchModule, null);
            when(authClient.userOwnsActiveCeLink("42", INSTALL_ID)).thenReturn(true);

            ResponseEntity<Map<String, Object>> response = noModule.browseControl(
                    CLOUD_USER_ID, INSTALL_ID, "ses_abc", "status", null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody()).isEqualTo(Map.of("error", "BROWSER_AGENT_UNAVAILABLE"));
        }
    }

    /**
     * Bean-gating contract for {@code @ConditionalOnProperty(name = "websearch.enabled",
     * havingValue = "true", matchIfMissing = true)} on the controller (line 37). The relay
     * is only mounted where the local websearch engine runs (cloud); a CE deployment that
     * sets {@code websearch.enabled=false} must NEVER expose the relay endpoint, so the bean
     * must not be created at all. When the property is absent or {@code true} (cloud default)
     * the bean is wired.
     */
    @Nested
    @DisplayName("bean gating on websearch.enabled")
    class BeanGating {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(AuthClient.class, () -> mock(AuthClient.class))
                .withBean(WebSearchModule.class, () -> mock(WebSearchModule.class))
                .withUserConfiguration(CloudWebSearchRelayController.class);

        @Test
        @DisplayName("bean is NOT wired when websearch.enabled=false (CE deployment)")
        void notWiredWhenDisabled() {
            runner.withPropertyValues("websearch.enabled=false")
                    .run(ctx -> assertThat(ctx)
                            .doesNotHaveBean(CloudWebSearchRelayController.class));
        }

        @Test
        @DisplayName("bean is NOT wired when websearch.enabled is a non-true value")
        void notWiredWhenNonTrueValue() {
            runner.withPropertyValues("websearch.enabled=maybe")
                    .run(ctx -> assertThat(ctx)
                            .doesNotHaveBean(CloudWebSearchRelayController.class));
        }

        @Test
        @DisplayName("bean IS wired when websearch.enabled=true (cloud)")
        void wiredWhenEnabled() {
            runner.withPropertyValues("websearch.enabled=true")
                    .run(ctx -> assertThat(ctx)
                            .hasSingleBean(CloudWebSearchRelayController.class));
        }

        @Test
        @DisplayName("bean IS wired when websearch.enabled is absent (matchIfMissing=true)")
        void wiredWhenPropertyMissing() {
            runner.run(ctx -> assertThat(ctx)
                    .hasSingleBean(CloudWebSearchRelayController.class));
        }
    }
}
