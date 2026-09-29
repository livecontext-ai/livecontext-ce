package com.apimarketplace.common.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The client decides whether an anonymous share holder sees a row, so every failure mode must
 * answer "no", and the request must carry exactly the ids the orchestrator check needs.
 */
@DisplayName("SharedApplicationScopeClient - fail closed, exact parameters")
class SharedApplicationScopeClientTest {

    private static final String BASE = "http://orchestrator";
    private static final UUID PUB = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID IFACE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID FILE = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private MockRestServiceServer server;
    private SharedApplicationScopeClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        client = new SharedApplicationScopeClient(builder.build());
    }

    private static String allowed(boolean value) {
        return "{\"allowed\":" + value + "}";
    }

    @Test
    @DisplayName("interface check: path + publication + workspace, answer followed")
    void interfaceCheckSendsIdsAndFollowsAnswer() {
        server.expect(requestTo(BASE + "/api/internal/orchestrator/share-scope/interfaces/" + IFACE
                        + "?publicationId=" + PUB + "&organizationId=org-1"))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(withSuccess(allowed(true), MediaType.APPLICATION_JSON));

        assertThat(client.interfaceBelongsToApplication(PUB, "org-1", IFACE)).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("file check: run, workflow and file ids are sent; special characters are encoded")
    void fileCheckEncodesParameters() {
        server.expect(queryParam("runId", "run%20a%26b%3Dc"))
                .andExpect(queryParam("workflowId", "iface-x%2Fy"))
                .andExpect(queryParam("fileId", FILE.toString()))
                .andExpect(queryParam("publicationId", PUB.toString()))
                .andExpect(queryParam("tenantId", "owner-1"))
                .andExpect(queryParam("organizationId", "org-1"))
                .andRespond(withSuccess(allowed(true), MediaType.APPLICATION_JSON));

        assertThat(client.fileBelongsToApplication(PUB, "owner-1", "org-1", "run a&b=c", "iface-x/y", FILE)).isTrue();
        server.verify();
    }

    @Test
    @DisplayName("HTTP 500 from orchestrator: refused")
    void serverErrorFailsClosed() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThat(client.fileBelongsToApplication(PUB, "t", "org-1", "run", null, FILE)).isFalse();
    }

    @Test
    @DisplayName("timeout / transport failure: refused")
    void timeoutFailsClosed() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                .andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThat(client.interfaceBelongsToApplication(PUB, "org-1", IFACE)).isFalse();
    }

    @Test
    @DisplayName("missing or non-boolean 'allowed' field, or explicit false: refused")
    void unexpectedBodyFailsClosed() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                .andRespond(withSuccess("{\"allowed\":\"true\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(BASE)))
                .andRespond(withSuccess(allowed(false), MediaType.APPLICATION_JSON));

        assertThat(client.interfaceBelongsToApplication(PUB, "org-1", IFACE)).isFalse();
        assertThat(client.interfaceBelongsToApplication(PUB, "org-1", IFACE)).isFalse();
        assertThat(client.interfaceBelongsToApplication(PUB, "org-1", IFACE)).isFalse();
    }

    @Test
    @DisplayName("nothing to check (no publication, no workspace, no identifying field): refused without a call")
    void missingInputsRefusedWithoutCall() {
        assertThat(client.interfaceBelongsToApplication(null, "org-1", IFACE)).isFalse();
        assertThat(client.interfaceBelongsToApplication(PUB, " ", IFACE)).isFalse();
        assertThat(client.interfaceBelongsToApplication(PUB, "org-1", null)).isFalse();
        assertThat(client.fileBelongsToApplication(PUB, "t", "org-1", null, " ", null)).isFalse();
        server.verify(); // no request was expected
    }
}
