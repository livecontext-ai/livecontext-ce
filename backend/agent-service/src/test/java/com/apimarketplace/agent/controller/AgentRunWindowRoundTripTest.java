package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.agent.client.dto.AgentRunFireDto;
import com.apimarketplace.agent.client.dto.AgentRunWindowDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The agent-run window, serialized by the server and read back by the client.
 *
 * <p>This is the one contract the feature rests on that no other test reaches. The
 * endpoint answers with a record that has no setters and no Jackson annotations, and the
 * client reads it through {@code RestTemplate}'s own converter chain - a chain neither
 * side configures. If that binding ever stops working, {@code AgentClient} catches the
 * exception and returns an empty list, exactly as it does for an unreachable service, and
 * the calendar quietly draws no agent rows at all. A green build, a silent feature.
 *
 * <p>So this test drives the REAL client, with its REAL template, against a canned HTTP
 * response produced by a mapper configured the way Spring Boot configures an MVC
 * controller's (see {@code serverMapper}, whose javadoc records the one default that had
 * to be corrected), and asserts the list that comes out the far end is the list that went
 * in. It lives in agent-service rather than agent-client because this module already has
 * {@code spring-test}, and because the two ends of the contract belong in one file.
 */
@DisplayName("Agent-run window - server serialization binds back through AgentClient")
class AgentRunWindowRoundTripTest {

    private AgentClient client;
    private MockRestServiceServer server;
    /**
     * The serializer the running service uses.
     *
     * <p>{@code Jackson2ObjectMapperBuilder.json()} ALONE is not it: the bare builder
     * leaves {@code WRITE_DATES_AS_TIMESTAMPS} on and writes an Instant as a float of
     * epoch seconds, which this test caught on its first run. Spring Boot's
     * {@code JacksonAutoConfiguration} disables it, and the live endpoint was observed
     * emitting {@code "2026-05-17T10:27:38.520594Z"}. Mirroring Boot here is what keeps
     * the round trip a statement about production rather than about a builder default.
     */
    private final ObjectMapper serverMapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-30T00:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        client = new AgentClient("http://agent:8090");
        // Bind to the template the call actually uses, not a fresh one: the bounded-budget
        // template is the object whose converters are under test.
        Field f = AgentClient.class.getDeclaredField("recentActivityRestTemplate");
        f.setAccessible(true);
        RestTemplate template = (RestTemplate) f.get(client);
        server = MockRestServiceServer.bindTo(template).build();
    }

    /** The shapes the endpoint really returns, taken from a live workspace. */
    private static List<AgentRunFireDto> serverRows() {
        return Arrays.asList(
                new AgentRunFireDto(
                        UUID.fromString("e4141964-5534-4300-a0e3-b442f9526a64"),
                        UUID.fromString("ba479bb6-ea84-484b-9a24-19eeeebc2ee8"),
                        "E2E Claude Agent",
                        Instant.parse("2026-05-17T10:27:38.520594Z"),
                        Instant.parse("2026-05-17T10:27:39.100000Z"),
                        "COMPLETED", "CHAT",
                        "fbb9cd2a-32bd-4227-8079-78cce5d323ae"),
                // A CLI-provider agent node inside a workflow: no conversation at all.
                // Every one of the 778 such rows on a real install looks like this.
                new AgentRunFireDto(
                        UUID.fromString("8f1d6098-0500-4a32-a285-63745a22d249"),
                        UUID.fromString("0090da28-f269-4185-ae75-22ca1b729ff6"),
                        "e2e-app-journey-tester",
                        Instant.parse("2026-05-15T15:55:36.149451Z"),
                        null, "FAILED", "WORKFLOW", null));
    }

    @Test
    @DisplayName("every field survives the trip, including the microseconds and the nulls")
    void roundTripsEveryField() throws Exception {
        Instant boundary = Instant.parse("2026-05-15T15:55:36.149451Z");
        AgentRunWindowDto sent = AgentRunWindowDto.of(serverRows(), true, boundary);
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "http://agent:8090/api/internal/agents/executions/window")))
                .andExpect(method(org.springframework.http.HttpMethod.GET))
                .andExpect(header("X-User-ID", "user-1"))
                .andExpect(header("X-Organization-ID", "org-1"))
                .andExpect(header("X-Organization-Role", "OWNER"))
                .andRespond(withSuccess(serverMapper.writeValueAsString(sent),
                        MediaType.APPLICATION_JSON));

        AgentRunWindowDto received = client.getWorkspaceAgentRuns(
                "user-1", "org-1", "OWNER", FROM, TO, 250);

        server.verify();
        // Records compare by value, so one assertion covers every component of the
        // window AND of each run inside it - it would fail on a truncated instant, a
        // dropped null, a renamed field, or a completeness flag lost in transit.
        assertThat(received).isEqualTo(sent);
    }

    @Test
    @DisplayName("an instant is written as ISO-8601 and read back to the same instant")
    void instantsAreNotTimestamps() throws Exception {
        String json = serverMapper.writeValueAsString(
                AgentRunWindowDto.of(serverRows(), false, null));

        // With WRITE_DATES_AS_TIMESTAMPS left on, an Instant ships as a float of epoch
        // seconds instead. The client reads both, so nothing breaks loudly - but the
        // payload doubles in ambiguity and stops matching what the live endpoint sends,
        // and this assertion is the only place that would notice.
        assertThat(json).contains("2026-05-17T10:27:38.520594Z");

        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://agent:8090")))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        assertThat(client.getWorkspaceAgentRuns("user-1", "org-1", "OWNER", FROM, TO, 250)
                .runs().get(0).startedAt())
                .isEqualTo(Instant.parse("2026-05-17T10:27:38.520594Z"));
    }

    @Test
    @DisplayName("an empty workspace is an AVAILABLE empty window, told apart from a failure")
    void emptyIsEmptyButAvailable() throws Exception {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://agent:8090")))
                .andRespond(withSuccess(
                        serverMapper.writeValueAsString(AgentRunWindowDto.of(List.of(), false, null)),
                        MediaType.APPLICATION_JSON));

        AgentRunWindowDto window = client.getWorkspaceAgentRuns(
                "user-1", "org-1", "OWNER", FROM, TO, 250);

        // "No agent ran" and "could not ask" are both zero rows, and a calendar must not
        // draw them the same way: one is a quiet month, the other a missing source.
        assertThat(window.runs()).isEmpty();
        assertThat(window.available()).isTrue();
    }

    @Test
    @DisplayName("a 500 from agent-service comes back as UNAVAILABLE, never as a quiet month")
    void serverErrorIsUnavailable() throws Exception {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://agent:8090")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withServerError());

        AgentRunWindowDto window = client.getWorkspaceAgentRuns(
                "user-1", "org-1", "OWNER", FROM, TO, 250);

        assertThat(window.available()).isFalse();
        assertThat(window.runs()).isEmpty();
    }
}
