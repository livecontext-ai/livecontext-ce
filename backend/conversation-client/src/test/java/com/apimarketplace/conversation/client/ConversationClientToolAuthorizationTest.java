package com.apimarketplace.conversation.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The exact bytes an answer from a chat button puts on the wire.
 *
 * <p><b>Why this is worth a test of its own.</b> The key {@code askFingerprint} is what narrows a
 * late approval to the one action the person was shown: without it on the wire, the endpoint
 * records a grant against the RULE and the agent's next unattended run may do a different thing of
 * the same kind, unasked. Nothing else pins that key. The controller test posts a hand-written
 * body, and the orchestrator test mocks this client away, so renaming it HERE leaves every test in
 * the repo green while every chat approval silently reverts to the wider grant. That is the shape
 * of a security regression nobody sees: no error, no log line, no failing build.
 *
 * <p>A mock server rather than a mocked client, because the thing under test is the serialisation
 * and nothing else.
 */
@DisplayName("ConversationClient.answerToolAuthorization - what actually goes on the wire")
class ConversationClientToolAuthorizationTest {

    private static final String BASE_URL = "http://conversation-service:8087";
    private static final String CONV_ID = "3dddb2d1-5c16-4b57-b5b1-534c20d739d7";
    private static final String APPROVE_URL =
            BASE_URL + "/api/conversations/" + CONV_ID + "/tool-authorization/approve";
    private static final String DENY_URL =
            BASE_URL + "/api/conversations/" + CONV_ID + "/tool-authorization/deny";

    private ConversationClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        client = new ConversationClient(restTemplate, BASE_URL);
        server = MockRestServiceServer.createServer(restTemplate);
    }

    private boolean answer(String fingerprint, boolean approve) {
        return client.answerToolAuthorization(CONV_ID, "42", "org-1", "workflow:execute",
                "call-9", fingerprint, approve);
    }

    @Test
    @DisplayName("sends the ask fingerprint under the key the endpoint reads")
    void sendsTheAskFingerprint() {
        server.expect(requestTo(APPROVE_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.rule").value("workflow:execute"))
                .andExpect(jsonPath("$.toolCallId").value("call-9"))
                .andExpect(jsonPath("$.remember").value(false))
                // The one key that narrows the grant. ConversationController reads exactly this
                // name; a rename on either side is silent and reopens the rule-wide grant.
                .andExpect(jsonPath("$.askFingerprint").value("digest-abc"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(answer("digest-abc", true)).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("sends it on a refusal too, so the two verdicts are one body shape")
    void sendsItOnRefusal() {
        server.expect(requestTo(DENY_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.askFingerprint").value("digest-abc"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(answer("digest-abc", false)).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("omits the key entirely when there is no ask, rather than sending a null")
    void omitsItWhenAbsent() {
        server.expect(requestTo(APPROVE_URL)).andExpect(method(HttpMethod.POST))
                // Absent, not null: the endpoint treats an unreadable value as "no ask" and falls
                // back to the rule-wide grant, which is the in-app behaviour and must stay the
                // thing that happens when nobody supplied an ask.
                .andExpect(jsonPath("$.askFingerprint").doesNotExist())
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(answer(null, true)).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("omits a blank one, which is not an ask either")
    void omitsItWhenBlank() {
        server.expect(requestTo(APPROVE_URL)).andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.askFingerprint").doesNotExist())
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(answer("   ", true)).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("reports a refused answer as not applied rather than throwing")
    void aRefusalIsAValueNotAnException() {
        server.expect(requestTo(APPROVE_URL))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        // The caller shows a verdict on the strength of this boolean. An exception here would
        // escape onto a public webhook thread and turn "could not apply" into a 500 for a person
        // who pressed a button.
        assertThat(answer("digest-abc", true)).isFalse();
    }
}
