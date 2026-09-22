package com.apimarketplace.agent.client;

import com.apimarketplace.agent.client.dto.AgentRunFireDto;
import com.apimarketplace.agent.client.dto.AgentRunWindowDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The agenda's agent-history call, on the wire.
 *
 * <p>What this file covers is the REQUEST and the failure posture: a lost parameter or
 * header makes the endpoint answer for the wrong window or refuse outright, and a
 * rethrown error would take a whole calendar down because one of its two history sources
 * was unavailable. The RESPONSE binding is covered where both ends of it exist, in
 * agent-service's {@code AgentRunWindowRoundTripTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentClient.getWorkspaceAgentRuns")
class AgentClientWorkspaceRunsTest {

    @Mock private RestTemplate restTemplate;
    private AgentClient client;

    private static final Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-09-30T00:00:00Z");

    @BeforeEach
    void setUp() throws Exception {
        client = new AgentClient("http://agent:8090");
        // The call deliberately uses the bounded-budget template, not the default one:
        // it sits inside a page render. Injecting into that field is also what proves it.
        Field f = AgentClient.class.getDeclaredField("recentActivityRestTemplate");
        f.setAccessible(true);
        f.set(client, restTemplate);
    }

    private void givenResponse(List<AgentRunFireDto> body) {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(AgentRunWindowDto.class)))
                .thenReturn(ResponseEntity.ok(AgentRunWindowDto.of(body, false, null)));
    }

    @Nested
    @DisplayName("the request")
    class Request {

        @Test
        @DisplayName("carries the window, the limit, the caller and the workspace")
        void carriesEverythingTheEndpointNeeds() {
            givenResponse(List.of());

            client.getWorkspaceAgentRuns("user-1", "org-1", "OWNER", FROM, TO, 250);

            ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<HttpEntity<Void>> entity = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(url.capture(), eq(HttpMethod.GET), entity.capture(),
                    eq(AgentRunWindowDto.class));
            assertThat(url.getValue())
                    .startsWith("http://agent:8090/api/internal/agents/executions/window")
                    .contains("from=2026-09-01T00:00:00Z")
                    .contains("to=2026-09-30T00:00:00Z")
                    .contains("limit=250");
            HttpHeaders headers = entity.getValue().getHeaders();
            // Without the org header the endpoint refuses: the read is org-strict.
            assertThat(headers.getFirst("X-User-ID")).isEqualTo("user-1");
            assertThat(headers.getFirst("X-Organization-ID")).isEqualTo("org-1");
            // The ROLE too. OrgContextHeaderForwarder carries only the id, and the
            // endpoint's deny-list reads the role: without it the guard treats an owner
            // as a plain member, skips its admin short-circuit, and pays an uncached
            // auth-service lookup on every single agenda load.
            assertThat(headers.getFirst("X-Organization-Role")).isEqualTo("OWNER");
        }

        @Test
        @DisplayName("is not made at all without a workspace, a caller, a window or a limit")
        void skipsImpossibleCalls() {
            for (AgentRunWindowDto skipped : List.of(
                    client.getWorkspaceAgentRuns(null, "org-1", "OWNER", FROM, TO, 10),
                    client.getWorkspaceAgentRuns("user-1", null, "OWNER", FROM, TO, 10),
                    client.getWorkspaceAgentRuns("user-1", "  ", "OWNER", FROM, TO, 10),
                    client.getWorkspaceAgentRuns("user-1", "org-1", "OWNER", null, TO, 10),
                    client.getWorkspaceAgentRuns("user-1", "org-1", "OWNER", FROM, null, 10),
                    client.getWorkspaceAgentRuns("user-1", "org-1", "OWNER", FROM, TO, 0))) {
                assertThat(skipped.runs()).isEmpty();
                // Not asked is not "could not ask": the caller chose not to, so this must
                // not raise an "agent history unavailable" notice on the page.
                assertThat(skipped.available()).isTrue();
                assertThat(skipped.truncated()).isFalse();
            }

            verify(restTemplate, never()).exchange(any(String.class), any(HttpMethod.class),
                    any(HttpEntity.class), eq(AgentRunWindowDto.class));
        }
    }

    @Nested
    @DisplayName("the response")
    class Response {

        // The binding itself is proven where BOTH ends exist: agent-service's
        // AgentRunWindowRoundTripTest serializes with the server's mapper and reads the
        // result back through this client's real template. Asserting it here with a
        // hand-built ObjectMapper would only prove that Jackson can bind a record.

        @Test
        @DisplayName("a body-less 200 is UNAVAILABLE, not an empty complete window")
        void nullBodyIsUnavailable() {
            when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                    eq(AgentRunWindowDto.class)))
                    .thenReturn(ResponseEntity.ok(null));

            AgentRunWindowDto window = client.getWorkspaceAgentRuns(
                    "user-1", "org-1", "OWNER", FROM, TO, 10);

            assertThat(window.runs()).isEmpty();
            assertThat(window.available()).isFalse();
        }

        @Test
        @DisplayName("carries the server's completeness through instead of re-deriving it")
        void carriesCompletenessThrough() {
            Instant boundary = Instant.parse("2026-09-12T08:00:00Z");
            when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                    eq(AgentRunWindowDto.class)))
                    .thenReturn(ResponseEntity.ok(AgentRunWindowDto.of(List.of(), true, boundary)));

            AgentRunWindowDto window = client.getWorkspaceAgentRuns(
                    "user-1", "org-1", "OWNER", FROM, TO, 10);

            // An EMPTY but TRUNCATED window is exactly the shape a row-count inference
            // cannot express: the endpoint's deny-list can filter a full page down to
            // nothing, and that window is still incomplete.
            assertThat(window.truncated()).isTrue();
            assertThat(window.coveredFrom()).isEqualTo(boundary);
        }
    }

    @Test
    @DisplayName("fails OPEN: an unreachable agent-service costs the agent rows, not the page")
    void failsOpen() {
        when(restTemplate.exchange(any(String.class), eq(HttpMethod.GET), any(HttpEntity.class),
                eq(AgentRunWindowDto.class)))
                .thenThrow(new ResourceAccessException("connection refused"));

        AgentRunWindowDto window = client.getWorkspaceAgentRuns(
                "user-1", "org-1", "OWNER", FROM, TO, 10);

        // The agenda renders the rest of the month around this. Rethrowing would take a
        // whole calendar down because one of its two history sources was unavailable.
        assertThat(window.runs()).isEmpty();
        // But it must NOT look like an answer: an available empty window says "no agent
        // ran", which the page draws as a complete, quiet month.
        assertThat(window.available()).isFalse();
    }
}
