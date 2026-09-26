package com.apimarketplace.conversation.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The finalize call carries the producer's error reason so conversation-service can record
 * the real cause instead of a fixed placeholder. The field is optional on the wire.
 */
@DisplayName("ConversationClient.finalizeStream")
class ConversationClientFinalizeStreamTest {

    private static final String BASE_URL = "http://conversation-service:8087";
    private static final String URL = BASE_URL + "/api/internal/streams/stream-1/finalize";

    private MockRestServiceServer server;
    private ConversationClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        client = new ConversationClient(restTemplate, BASE_URL);
    }

    @Test
    @DisplayName("ERROR with a reason sends state AND errorMessage")
    void errorWithReasonSendsErrorMessage() {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.state").value("ERROR"))
                .andExpect(jsonPath("$.errorMessage").value("LLM provider timeout"))
                .andRespond(withSuccess());

        client.finalizeStream("stream-1", "ERROR", "LLM provider timeout");

        server.verify();
    }

    @Test
    @DisplayName("the two-argument form sends no errorMessage (unchanged wire shape)")
    void twoArgumentFormSendsNoErrorMessage() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.state").value("COMPLETED"))
                .andExpect(jsonPath("$.errorMessage").doesNotExist())
                .andRespond(withSuccess());

        client.finalizeStream("stream-1", "COMPLETED");

        server.verify();
    }

    @Test
    @DisplayName("a blank reason is not sent, so the receiver applies its own placeholder")
    void blankReasonIsNotSent() {
        server.expect(requestTo(URL))
                .andExpect(jsonPath("$.state").value("ERROR"))
                .andExpect(jsonPath("$.errorMessage").doesNotExist())
                .andRespond(withSuccess());

        client.finalizeStream("stream-1", "ERROR", "  ");

        server.verify();
    }
}
