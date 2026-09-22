package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.catalog.service.credential.EndpointCredentialCapabilityService;
import com.apimarketplace.credential.client.CredentialClient;
import com.apimarketplace.credential.client.dto.CredentialSummaryDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Two refusals that used to send a caller away from the thing that would have worked.
 *
 * <p>Both were found in production on the same account, in the same week, and they cost
 * the same six days for opposite reasons: one said the call was blameless when the call
 * was the entire problem, the other said the provider refused without mentioning that the
 * account was three scopes short of running the endpoint at all.
 */
class CatalogExecuteModuleToolIdAndScopeRefusalTest {

    private CatalogExecuteModule module;
    private RestTemplate restTemplate;
    private CredentialClient credentialClient;
    private EndpointCredentialCapabilityService capability;

    @BeforeEach
    void setUp() throws Exception {
        credentialClient = mock(CredentialClient.class);
        capability = mock(EndpointCredentialCapabilityService.class);
        module = new CatalogExecuteModule(new ObjectMapper(), credentialClient);
        module.setCredentialCapability(capability);

        restTemplate = mock(RestTemplate.class);
        Field rtField = CatalogExecuteModule.class.getDeclaredField("restTemplate");
        rtField.setAccessible(true);
        rtField.set(module, restTemplate);
        Field portField = CatalogExecuteModule.class.getDeclaredField("serverPort");
        portField.setAccessible(true);
        portField.setInt(module, 8081);
    }

    private ToolExecutionResult run(String toolId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("tool_id", toolId);
        params.put("params", Map.of("limit", 20));
        return module.execute("execute", params, "user-1", ToolExecutionContext.of("user-1")).orElseThrow();
    }

    @Nested
    @DisplayName("tool_id that cannot name a tool")
    class ToolIdShape {

        /**
         * The exact value a production agent sent, eight times over six days. It composed it
         * from the two readable fields of a catalog(search) hit - provider "Telegram" and name
         * "get_updates" - because three different APIs publish a tool called get_updates and
         * the name alone identifies none of them.
         */
        @Test
        @DisplayName("a provider and a name joined by ':' is refused before any call, and the message names the fix")
        void refusesProviderColonName() {
            ToolExecutionResult result = run("telegram:get_updates");

            assertThat(result.success()).isFalse();
            assertThat(result.errorCode()).isEqualTo(ToolErrorCode.INVALID_PARAMETER_VALUE);
            assertThat(result.error())
                    .as("the agent must be told WHICH field to send, since it holds it already")
                    .contains("catalog(action='search')")
                    .contains("`id`")
                    .contains("telegram:get_updates");
            assertThat(result.error())
                    .as("the old message claimed the call was blameless, which sent the caller to escalate")
                    .doesNotContain("Nothing in the call itself causes this");
        }

        @Test
        @DisplayName("a dotted name is refused the same way")
        void refusesDottedName() {
            ToolExecutionResult result = run("telegram.get_updates");
            assertThat(result.success()).isFalse();
            assertThat(result.error()).contains("catalog(action='search')");
        }

        @Test
        @DisplayName("nothing is sent upstream for a malformed id: the refusal is free")
        void neverCallsUpstream() {
            run("telegram:get_updates");
            verify(restTemplate, never()).exchange(contains("/execute"), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(String.class));
        }

        /**
         * The three shapes the execution endpoint documents itself as accepting. Rejecting any
         * of them would break a live caller: the workflow path sends apiSlug/toolSlug, and the
         * existing refusal-message suite drives this module with exactly that shape.
         */
        @Test
        @DisplayName("a UUID, a bare slug and apiSlug/toolSlug all pass the shape gate")
        void acceptsEveryDocumentedShape() {
            for (String accepted : List.of(
                    "beb1c5c1-d9df-44d0-8b3b-b10f8604c4e2",
                    "telegram-get-updates",
                    "elevenlabs/sound-generation")) {
                ToolExecutionResult result = run(accepted);
                assertThat(result.error())
                        .as("%s is a legitimate tool_id and must not be refused on its shape", accepted)
                        .doesNotContain("cannot name a tool");
            }
        }

        /**
         * A typo inside a well-formed UUID is NOT this gate's business. Production saw one
         * (40d0 where the tool has 44d0) and the resolver is what knows the id does not exist;
         * answering "cannot name a tool" here would be a guess dressed as a fact.
         */
        @Test
        @DisplayName("a well-formed but unknown UUID passes the shape gate and is left to the resolver")
        void leavesUnknownUuidToTheResolver() {
            ToolExecutionResult result = run("beb1c5c1-d9df-40d0-8b3b-b10f8604c4e2");
            assertThat(result.error()).doesNotContain("cannot name a tool");
        }
    }

    @Nested
    @DisplayName("provider refuses an authenticated call for what the account was granted")
    class InsufficientScope {

        private static final String TOOL_ID = "gmail/gmail-list-messages";

        /**
         * Verbatim shape of what Gmail answered in production at 23:00:58 on 2026-09-20: a 200
         * from catalog-service carrying the provider's own 403. It is NOT the
         * {@code credentials_required} envelope, because a credential WAS found and sent, which
         * is exactly why it missed the only branch that knows how to talk about scopes.
         */
        private static final String INSUFFICIENT_SCOPE_ENVELOPE = """
                {"success":false,
                 "result":{"httpStatus":{"code":403},
                           "error":"Access forbidden for gmail: Request had insufficient authentication scopes."},
                 "error":"Access forbidden for gmail: Request had insufficient authentication scopes.",
                 "metadata":{"toolName":"list_messages","endpoint":"/messages","method":"GET",
                             "iconSlug":"gmail","status":"unknown"},
                 "toolId":"2f0a4d76-8a52-4b8d-9f11-0a0a0a0a0a0a"}
                """;

        @SuppressWarnings({"rawtypes", "unchecked"})
        private void stubPreflightPasses() {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("iconSlug", "gmail");
            info.put("integrationName", "gmail");
            info.put("name", "list_messages");
            info.put("authType", "oauth2");
            info.put("requiredScopes", List.of("https://www.googleapis.com/auth/gmail.readonly"));
            when(restTemplate.exchange(contains("/info"), eq(HttpMethod.GET),
                    any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(new ResponseEntity<>(info, HttpStatus.OK));

            CredentialSummaryDto credential = new CredentialSummaryDto();
            credential.setName("Jaden");
            credential.setIntegration("gmail");
            credential.setDefault(true);
            when(credentialClient.getDefaultCredential(eq("user-1"), eq("gmail")))
                    .thenReturn(Optional.of(credential));
        }

        @SuppressWarnings("unchecked")
        private void stubExecuteAnswers(String body) {
            when(restTemplate.exchange(contains("/execute"), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(String.class)))
                    .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
        }

        private ToolExecutionResult runGmail() {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("tool_id", TOOL_ID);
            params.put("params", Map.of("userId", "me"));
            return module.execute("execute", params, "user-1", ToolExecutionContext.of("user-1")).orElseThrow();
        }

        @Test
        @DisplayName("a 403 carries the capability remedy and a code of its own")
        void appendsTheRemedy() {
            stubPreflightPasses();
            stubExecuteAnswers(INSUFFICIENT_SCOPE_ENVELOPE);
            when(capability.describe(any(), anyString(), anyList(), anyString()))
                    .thenReturn(Map.of(
                            "accounts", List.of(Map.of(
                                    "name", "Jaden", "status", "active", "canRunThis", false,
                                    "missingScopes", List.of("https://www.googleapis.com/auth/gmail.readonly"))),
                            "remedy",
                            "The connected Gmail account was not granted gmail.readonly, and re-connecting "
                                    + "the ordinary way can never grant them. Ask the user to connect Gmail "
                                    + "with their own OAuth client credentials and to grant those scopes."));

            ToolExecutionResult result = runGmail();

            assertThat(result.success()).isFalse();
            assertThat(result.error())
                    .as("a distinct code, because 'connect one' is the wrong remedy here")
                    .startsWith(CatalogExecuteModule.CREDENTIALS_INSUFFICIENT_CODE + ":");
            assertThat(result.error())
                    .as("the provider's own sentence still leads: it names what was objected to")
                    .contains("insufficient authentication scopes");
            assertThat(result.error())
                    .as("and the remedy is what the caller was missing entirely")
                    .contains("own OAuth client credentials");
            assertThat(result.error())
                    .as("'retrying is refused the same way' ends the thread; this refusal has a next step")
                    .doesNotContain("Retrying unchanged is refused the same way");
        }

        /**
         * The capability is silent whenever it has nothing certain to say (no bean, no contract,
         * an unreadable account listing). Silence must leave the refusal exactly as it was, or
         * every provider 403 on the platform changes wording the day a lookup goes down.
         */
        @Test
        @DisplayName("no remedy available leaves the plain UPSTREAM_REJECTED refusal untouched")
        void fallsBackWhenTheCapabilityIsSilent() {
            stubPreflightPasses();
            stubExecuteAnswers(INSUFFICIENT_SCOPE_ENVELOPE);
            when(capability.describe(any(), anyString(), anyList(), anyString())).thenReturn(null);

            ToolExecutionResult result = runGmail();

            assertThat(result.error()).startsWith(CatalogExecuteModule.UPSTREAM_REJECTED_CODE + ":");
            assertThat(result.error()).contains("Retrying unchanged is refused the same way");
        }

        /**
         * A 5xx is not an account problem. Consulting the capability there would cost a lookup
         * on every upstream outage and could only produce advice about a credential that is
         * working fine.
         */
        /**
         * The capability answers for three situations, and only one of them is this code's.
         * Here it reports that NOTHING is connected, whose remedy is "connect one" - the exact
         * sentence the new code is documented as contradicting ("a key WAS sent, so connecting
         * another is not the fix"). Reachable in production on a platform-keyed endpoint: the
         * call runs on the platform's key, the provider answers 403, and the caller holds no
         * account of that integration at all.
         */
        @Test
        @DisplayName("a 403 with no connected account keeps the plain refusal, not the scope code")
        void doesNotClaimAScopeGapWhenNothingIsConnected() {
            stubPreflightPasses();
            stubExecuteAnswers(INSUFFICIENT_SCOPE_ENVELOPE);
            when(capability.describe(any(), anyString(), anyList(), anyString()))
                    .thenReturn(Map.of(
                            "accounts", List.of(),
                            "remedy", "No Gmail account is connected. Call "
                                    + "credential(action='require', services=['gmail'], reason='<why>') "
                                    + "to ask the user to connect one, then run this again."));

            ToolExecutionResult result = runGmail();

            assertThat(result.error()).startsWith(CatalogExecuteModule.UPSTREAM_REJECTED_CODE + ":");
            assertThat(result.error())
                    .as("the code must not lead a sentence that says the opposite of what it means")
                    .doesNotContain(CatalogExecuteModule.CREDENTIALS_INSUFFICIENT_CODE);
        }

        /**
         * The other non-scope remedy: the account holds everything the endpoint needs and is
         * revoked. Telling its owner they were "not granted" a permission they hold sends them
         * to obtain it twice, and routing the agent to require(scopes=[...]) produces
         * "Credentials already exist" because the server finds no gap.
         */
        @Test
        @DisplayName("a 403 on an account that is merely unusable keeps the plain refusal too")
        void doesNotClaimAScopeGapForAStatusProblem() {
            stubPreflightPasses();
            stubExecuteAnswers(INSUFFICIENT_SCOPE_ENVELOPE);
            when(capability.describe(any(), anyString(), anyList(), anyString()))
                    .thenReturn(Map.of(
                            "accounts", List.of(Map.of(
                                    "name", "Jaden", "status", "needs_reauth", "canRunThis", false)),
                            "remedy", "The connected Gmail account holds what this endpoint needs but "
                                    + "cannot be used right now. Only the user can re-authorise it."));

            ToolExecutionResult result = runGmail();

            assertThat(result.error()).startsWith(CatalogExecuteModule.UPSTREAM_REJECTED_CODE + ":");
        }

        /** And the case it IS for: an account of the integration, short of a required scope. */
        @Test
        @DisplayName("an account listed with missingScopes is what raises the scope code")
        void raisesTheScopeCodeOnRealEvidence() {
            stubPreflightPasses();
            stubExecuteAnswers(INSUFFICIENT_SCOPE_ENVELOPE);
            when(capability.describe(any(), anyString(), anyList(), anyString()))
                    .thenReturn(Map.of(
                            "accounts", List.of(Map.of(
                                    "name", "Jaden", "status", "active", "canRunThis", false,
                                    "missingScopes", List.of("https://www.googleapis.com/auth/gmail.readonly"))),
                            "remedy", "The connected Gmail account was not granted gmail.readonly."));

            ToolExecutionResult result = runGmail();

            assertThat(result.error())
                    .startsWith(CatalogExecuteModule.CREDENTIALS_INSUFFICIENT_CODE + ":");
        }

        @Test
        @DisplayName("a 500 is not routed through the capability")
        void ignoresNonAuthFailures() {
            stubPreflightPasses();
            stubExecuteAnswers("""
                    {"success":false,
                     "result":{"httpStatus":{"code":500},"error":"Internal error"},
                     "error":"Internal error",
                     "metadata":{"iconSlug":"gmail"}}
                    """);

            ToolExecutionResult result = runGmail();

            assertThat(result.error()).startsWith(CatalogExecuteModule.UPSTREAM_REJECTED_CODE + ":");
            verify(capability, never()).describe(any(), anyString(), anyList(), anyString());
        }

        /** A scope gap somewhere in the tenant, and a call whose key it says nothing about. */
        private void stubOneAccountShortOfAScope() {
            when(capability.describe(any(), anyString(), anyList(), anyString()))
                    .thenReturn(Map.of(
                            "accounts", List.of(Map.of(
                                    "name", "Jaden", "status", "active", "canRunThis", false,
                                    "missingScopes", List.of("https://www.googleapis.com/auth/gmail.readonly"))),
                            "remedy", "Ask the user to connect Gmail with their own OAuth client."));
        }

        private ToolExecutionResult runGmailWith(String key, Object value) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("tool_id", TOOL_ID);
            params.put("params", Map.of("userId", "me"));
            params.put(key, value);
            return module.execute("execute", params, "user-1", ToolExecutionContext.of("user-1")).orElseThrow();
        }

        /**
         * The gap belongs to a USER account; this call went out on a platform key. A plan or
         * quota 403 on the shared pool has nothing to do with what some user was granted, and
         * answering it with "re-authorise your Gmail" sends the agent to a require call the
         * server will find nothing missing from, on a problem only the account owner can fix.
         */
        @Test
        @DisplayName("a platform-keyed 403 is not blamed on a user account's scope gap")
        void doesNotBlameAScopeGapForAPlatformKeyedCall() {
            stubPreflightPasses();
            stubExecuteAnswers(INSUFFICIENT_SCOPE_ENVELOPE);
            stubOneAccountShortOfAScope();

            ToolExecutionResult result = runGmailWith("credential_source", "platform");

            assertThat(result.error())
                    .as("the platform pool's key is not the account the gap describes")
                    .startsWith(CatalogExecuteModule.UPSTREAM_REJECTED_CODE + ":");
            assertThat(result.error())
                    .as("the provider's own sentence is still what the agent reads")
                    .contains("insufficient authentication scopes");
        }

        /**
         * The call named its account. Only that one's scopes are in play: computing the remedy
         * from a sibling produces {@code Run it with credential_name="Jaden"} naming the account
         * the call just used, which reads as an instruction to retry identically.
         */
        @Test
        @DisplayName("a named account that is NOT the one short of scopes gets the plain refusal")
        void doesNotBlameASiblingAccountsScopeGap() {
            stubPreflightPasses();
            stubExecuteAnswers(INSUFFICIENT_SCOPE_ENVELOPE);
            stubOneAccountShortOfAScope();

            ToolExecutionResult result = runGmailWith("credential_name", "Work");

            assertThat(result.error()).startsWith(CatalogExecuteModule.UPSTREAM_REJECTED_CODE + ":");
        }

        @Test
        @DisplayName("a named account that IS short of scopes still gets the scope code")
        void stillRaisesTheScopeCodeForTheNamedAccount() {
            stubPreflightPasses();
            stubExecuteAnswers(INSUFFICIENT_SCOPE_ENVELOPE);
            stubOneAccountShortOfAScope();

            ToolExecutionResult result = runGmailWith("credential_name", "Jaden");

            assertThat(result.error())
                    .as("narrowing the evidence must not silence the case the branch exists for")
                    .startsWith(CatalogExecuteModule.CREDENTIALS_INSUFFICIENT_CODE + ":");
        }

        /**
         * The branch covers {@code 401 || 403} and a 401 is the commoner of the two, so the
         * evidence rule has to hold there as well rather than only being exercised on 403.
         */
        @Test
        @DisplayName("a 401 with a real scope gap is answered the same way a 403 is")
        void appliesToA401Too() {
            stubPreflightPasses();
            stubExecuteAnswers("""
                    {"success":false,
                     "result":{"httpStatus":{"code":401},"error":"Invalid Credentials"},
                     "error":"Invalid Credentials",
                     "metadata":{"iconSlug":"gmail","status":"unknown"}}
                    """);
            stubOneAccountShortOfAScope();

            ToolExecutionResult result = runGmail();

            assertThat(result.error()).startsWith(CatalogExecuteModule.CREDENTIALS_INSUFFICIENT_CODE + ":");
        }

        /**
         * The capability is what the card reads to explain itself. Losing it off the metadata
         * turns an explained refusal back into a bare one, with the text still promising an
         * explanation.
         */
        @Test
        @DisplayName("the capability travels on the failure metadata, not only in the sentence")
        void attachesTheCapabilityToTheMetadata() {
            stubPreflightPasses();
            stubExecuteAnswers(INSUFFICIENT_SCOPE_ENVELOPE);
            stubOneAccountShortOfAScope();

            ToolExecutionResult result = runGmail();

            assertThat(result.metadata()).containsKey("credentialCapability");
        }
    }
}
