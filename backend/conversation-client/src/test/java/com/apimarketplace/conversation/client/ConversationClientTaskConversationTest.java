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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * LC-066 (review finding r5-2): the per-task conversation of a RESTRICTED delegated task. The one
 * property that matters for the rolling update: a conversation-service without the endpoint (404)
 * answers null, so agent-service refuses the task instead of sending it to the agent's own
 * conversation.
 */
class ConversationClientTaskConversationTest {

    private static final String BASE_URL = "http://conversation-service:8087";
    private static final String URL = BASE_URL + "/api/internal/conversations/agent/agent-1/task/task-1";

    private MockRestServiceServer server;
    private ConversationClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        client = new ConversationClient(restTemplate, BASE_URL);
    }

    @Test
    @DisplayName("find-or-create posts with the tenant and workspace, and answers the conversation id")
    void findOrCreateAnswersId() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-User-ID", "tenant-1"))
                .andExpect(header("X-Organization-ID", "org-1"))
                .andExpect(content().json("{\"title\":\"Worker - task 1234\"}"))
                .andRespond(withSuccess("{\"id\":\"conv-task\"}", MediaType.APPLICATION_JSON));

        assertThat(client.findOrCreateTaskConversation("agent-1", "task-1", "tenant-1", "Worker - task 1234", "org-1"))
                .isEqualTo("conv-task");
        server.verify();
    }

    @Test
    @DisplayName("an old conversation-service without the endpoint (404) answers null, never another conversation")
    void oldConversationServiceAnswersNull() {
        server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.findOrCreateTaskConversation("agent-1", "task-1", "tenant-1", "t", "org-1")).isNull();
        server.verify();
    }

    @Test
    @DisplayName("find answers the id, and null when the task has no conversation yet")
    void findAnswersIdOrNull() {
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\":\"conv-task\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.findTaskConversation("agent-1", "task-1", "tenant-1", "org-1")).isEqualTo("conv-task");
        assertThat(client.findTaskConversation("agent-1", "task-1", "tenant-1", "org-1")).isNull();
        server.verify();
    }
}
