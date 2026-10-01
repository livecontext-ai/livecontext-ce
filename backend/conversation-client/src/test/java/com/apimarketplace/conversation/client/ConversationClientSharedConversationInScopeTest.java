package com.apimarketplace.conversation.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link ConversationClient#findSharedConversationIdInScope}: publication-service's question
 * "which conversation does this share token open, for THIS caller?" before it files a
 * CONVERSATION share link. Driven through a real RestTemplate so the URL and the scope headers
 * are what is asserted, not a mock of the method.
 */
class ConversationClientSharedConversationInScopeTest {

    private static final String BASE_URL = "http://conversation-service:8087";
    private static final String URL = BASE_URL + "/api/internal/share/validate/cs_abc/in-scope";

    private MockRestServiceServer server;
    private ConversationClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        client = new ConversationClient(restTemplate, BASE_URL);
    }

    @Test
    @DisplayName("asks with the caller's user and workspace, and answers the conversation id")
    void answersConversationId() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User-ID", "user-1"))
                .andExpect(header("X-Organization-ID", "org-1"))
                .andRespond(withSuccess("{\"conversationId\":\"conv-1\"}", MediaType.APPLICATION_JSON));

        assertThat(client.findSharedConversationIdInScope("cs_abc", "user-1", "org-1")).isEqualTo("conv-1");
        server.verify();
    }

    @Test
    @DisplayName("answers null on a 200 that carries no conversation id")
    void answersNullWithoutConversationId() {
        server.expect(requestTo(URL)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(client.findSharedConversationIdInScope("cs_abc", "user-1", "org-1")).isNull();
        server.verify();
    }

    @Test
    @DisplayName("answers null on a 404: the token opens no conversation of the caller's workspace")
    void answersNullOnNotFound() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.findSharedConversationIdInScope("cs_abc", "user-1", "org-1")).isNull();
        server.verify();
    }

    @Test
    @DisplayName("throws when the lookup fails: an outage must not read as 'this conversation is not yours'")
    void throwsOnFailure() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.findSharedConversationIdInScope("cs_abc", "user-1", "org-1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("cs_abc");
        server.verify();
    }

    @Test
    @DisplayName("a token that is not one plain path segment opens nothing and is never sent")
    void unsafeTokenIsNeverSent() {
        // No expectation registered: any request would fail the verify below.
        assertThat(client.findSharedConversationIdInScope("cs_x/../../other", "user-1", "org-1")).isNull();
        assertThat(client.findSharedConversationIdInScope(null, "user-1", "org-1")).isNull();
        server.verify();
    }
}
