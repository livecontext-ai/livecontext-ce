package com.apimarketplace.agent.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CASA LC-066: the scrub of task notifications asks agent-service which tasks are RESTRICTED. A
 * failure must reach the caller (it then retries), never read as "none restricted".
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentClient.findRestrictedTaskIds")
class AgentClientRestrictedTaskIdsTest {

    private static final String URL = "http://agent:8090/api/internal/agents/tasks/restricted-ids";

    @Mock private RestTemplate restTemplate;
    private AgentClient client;

    @BeforeEach
    void setUp() {
        client = new AgentClient(restTemplate, "http://agent:8090");
    }

    @Test
    @DisplayName("posts the ids and returns the restricted ones")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void returnsRestrictedIds() {
        UUID restricted = UUID.randomUUID();
        UUID normal = UUID.randomUUID();
        ArgumentCaptor<HttpEntity> request = ArgumentCaptor.forClass(HttpEntity.class);
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.POST), request.capture(), any(ParameterizedTypeReference.class)))
                .thenReturn(ResponseEntity.ok(Map.of("restrictedIds", List.of(restricted.toString()))));

        assertThat(client.findRestrictedTaskIds(List.of(restricted, normal))).containsExactly(restricted);
        assertThat(((Map<String, Object>) request.getValue().getBody()).get("ids")).isEqualTo(List.of(restricted, normal));
    }

    @Test
    @DisplayName("agent-service unreachable or answering without the field: the failure propagates")
    @SuppressWarnings("unchecked")
    void failurePropagates() {
        when(restTemplate.exchange(eq(URL), eq(HttpMethod.POST), any(HttpEntity.class), any(ParameterizedTypeReference.class)))
                .thenThrow(new ResourceAccessException("connection refused"))
                .thenReturn(ResponseEntity.ok(Map.of("error", "boom")));

        assertThatThrownBy(() -> client.findRestrictedTaskIds(List.of(UUID.randomUUID())))
                .isInstanceOf(ResourceAccessException.class);
        assertThatThrownBy(() -> client.findRestrictedTaskIds(List.of(UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("no ids: no call")
    void emptyAsksNothing() {
        assertThat(client.findRestrictedTaskIds(Set.of())).isEmpty();
        verifyNoInteractions(restTemplate);
    }
}
