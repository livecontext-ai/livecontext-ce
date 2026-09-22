package com.apimarketplace.agent.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The public-read declaration must actually leave the process as a query parameter.
 *
 * <p>This flag is the single mechanism that delivers the public-catalogue fix: agent-service
 * trims the CLI bridges only when it sees it. Nothing else in the repo exercises the wire, so a
 * parameter that was built but never appended, or appended under another name, would leave every
 * test green and the leak in production. The name itself is a shared constant referenced by the
 * controller's {@code @RequestParam}, so the two sides cannot drift by spelling.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentClient - publicRead reaches the wire")
class AgentClientPublicReadTest {

    @Mock private RestTemplate restTemplate;
    private AgentClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = new AgentClient("http://agent:8090");
        Field f = AgentClient.class.getDeclaredField("restTemplate");
        f.setAccessible(true);
        f.set(client, restTemplate);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private String urlSentFor(boolean publicRead) {
        return urlSentFor(publicRead, false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private String urlSentFor(boolean publicRead, boolean hideBridges) {
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        when(restTemplate.exchange(url.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Map.class)))
            .thenReturn((ResponseEntity) ResponseEntity.ok(Map.of()));
        client.getModelsInfo(null, null, null, publicRead, hideBridges);
        return url.getValue();
    }

    @Test
    @DisplayName("a public read appends the parameter under the shared name")
    void publicReadIsAppended() {
        assertThat(urlSentFor(true))
            .endsWith("/api/internal/agent/models?" + AgentClient.PUBLIC_READ_PARAM + "=true");
    }

    @Test
    @DisplayName("an ordinary call sends no such parameter, so nothing internal gets trimmed")
    void ordinaryCallSendsNothing() {
        assertThat(urlSentFor(false))
            .doesNotContain(AgentClient.PUBLIC_READ_PARAM)
            .doesNotContain(AgentClient.HIDE_BRIDGES_PARAM);
    }

    @Test
    @DisplayName("a signed-in non-admin read appends hideBridges, and NOT publicRead")
    void hideBridgesIsAppendedOnItsOwn() {
        // The two must never travel together: publicRead also widens the catalogue to
        // providers with no key, and a signed-in user must lose the bridges without gaining
        // those. Same wire test as above, for the same reason: nothing else exercises it.
        String url = urlSentFor(false, true);

        assertThat(url).endsWith("/api/internal/agent/models?" + AgentClient.HIDE_BRIDGES_PARAM + "=true");
        assertThat(url).doesNotContain(AgentClient.PUBLIC_READ_PARAM);
    }
}
