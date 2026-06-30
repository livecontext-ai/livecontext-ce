package com.apimarketplace.conversation.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Regression tests for {@link ConversationClient#getConversationMessages}.
 *
 * <p>These guard the bug where the client called the dead endpoint
 * {@code GET /{id}/messages?limit=N} (removed when message reads moved to the
 * paginated {@code /messages/page} endpoint returning a {@code PagedResponseDto}).
 * The dead URL 404'd, the exception was swallowed, and every memory/history
 * consumer (agent memory, get_history, sub-agent, widget) silently got an empty
 * list. A plain mock of the method never caught it because it never exercised
 * the real HTTP call - so these tests drive {@link RestTemplate} through
 * {@link MockRestServiceServer}.
 */
class ConversationClientGetMessagesTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private ConversationClient client;

    private static final String BASE_URL = "http://conversation-service:8087";
    private static final String CONV_ID = "conv-123";

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        client = new ConversationClient(restTemplate, BASE_URL);
    }

    @Test
    @DisplayName("hits the paginated /messages/page endpoint (page=0, size=limit), not the dead /messages?limit URL")
    void usesPaginatedEndpoint() {
        server.expect(requestTo(BASE_URL + "/api/conversations/" + CONV_ID + "/messages/page?page=0&size=20"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-User-ID", "tenant-1")) // controller returns 401 without it
                .andRespond(withSuccess("{\"content\":[],\"page\":0,\"size\":20,\"totalElements\":0}",
                        MediaType.APPLICATION_JSON));

        List<Map<String, Object>> result = client.getConversationMessages(CONV_ID, 20, "tenant-1");

        assertThat(result).isEmpty();
        server.verify(); // fails if the dead URL was hit instead
    }

    @Test
    @DisplayName("unwraps PagedResponseDto.content and reverses DESC page into chronological order (oldest first)")
    void unwrapsAndReversesToChronological() {
        // conversation-service returns page 0 newest-first (DESC).
        String body = "{\"content\":["
                + "{\"role\":\"assistant\",\"content\":\"newest\"},"
                + "{\"role\":\"user\",\"content\":\"middle\"},"
                + "{\"role\":\"assistant\",\"content\":\"oldest\"}"
                + "],\"page\":0,\"size\":20,\"totalElements\":3}";

        server.expect(requestTo(BASE_URL + "/api/conversations/" + CONV_ID + "/messages/page?page=0&size=20"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<Map<String, Object>> result = client.getConversationMessages(CONV_ID, 20, "tenant-1");

        // Reversed to chronological order: oldest first.
        assertThat(result).hasSize(3);
        assertThat(result.get(0)).containsEntry("content", "oldest");
        assertThat(result.get(1)).containsEntry("content", "middle");
        assertThat(result.get(2)).containsEntry("content", "newest");
        server.verify();
    }

    @Test
    @DisplayName("returns empty list (never throws) when the endpoint errors")
    void returnsEmptyOnError() {
        server.expect(requestTo(BASE_URL + "/api/conversations/" + CONV_ID + "/messages/page?page=0&size=5"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("not-json", MediaType.TEXT_PLAIN));

        List<Map<String, Object>> result = client.getConversationMessages(CONV_ID, 5, "tenant-1");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("returns empty list (never throws) on the swallowed-404 path that originally caused the bug")
    void returnsEmptyOn404() {
        server.expect(requestTo(BASE_URL + "/api/conversations/" + CONV_ID + "/messages/page?page=0&size=5"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        List<Map<String, Object>> result = client.getConversationMessages(CONV_ID, 5, "tenant-1");

        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    @DisplayName("tolerates a malformed page: missing 'content' key and non-Map items are skipped, never throws")
    void toleratesMalformedContent() {
        // 'content' present but holding a non-Map element mixed with a valid one.
        String body = "{\"content\":[\"junk\",{\"role\":\"user\",\"content\":\"only-valid\"}],"
                + "\"page\":0,\"size\":10,\"totalElements\":2}";
        server.expect(requestTo(BASE_URL + "/api/conversations/" + CONV_ID + "/messages/page?page=0&size=10"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        List<Map<String, Object>> result = client.getConversationMessages(CONV_ID, 10, "tenant-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("content", "only-valid");
    }

    @Test
    @DisplayName("missing 'content' key yields empty list (body shape with no content field)")
    void missingContentKeyYieldsEmpty() {
        server.expect(requestTo(BASE_URL + "/api/conversations/" + CONV_ID + "/messages/page?page=0&size=10"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"page\":0,\"size\":10,\"totalElements\":0}",
                        MediaType.APPLICATION_JSON));

        List<Map<String, Object>> result = client.getConversationMessages(CONV_ID, 10, "tenant-1");

        assertThat(result).isEmpty();
    }
}
