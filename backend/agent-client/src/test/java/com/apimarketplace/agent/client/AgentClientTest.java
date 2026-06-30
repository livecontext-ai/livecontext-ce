package com.apimarketplace.agent.client;

import com.apimarketplace.agent.client.dto.AgentDto;
import com.apimarketplace.agent.client.dto.AgentObservabilityRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgentClient - HTTP inter-service calls to agent-service")
class AgentClientTest {

    @Mock
    private RestTemplate restTemplate;

    private AgentClient agentClient;

    private static final String BASE_URL = "http://localhost:8090";
    private static final String TENANT_ID = "auth0|tenant-test";
    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agentClient = new AgentClient(restTemplate, BASE_URL);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private AgentDto buildAgentDto(UUID id, String tenantId) {
        AgentDto dto = new AgentDto();
        dto.setId(id);
        dto.setTenantId(tenantId);
        dto.setName("Test Agent");
        return dto;
    }

    // =========================================================================
    // getAgent
    // =========================================================================

    @Nested
    @DisplayName("getAgent")
    class GetAgent {

        @Test
        @DisplayName("returns AgentDto body on success")
        void getAgent_success_returnsBody() {
            AgentDto expected = buildAgentDto(AGENT_ID, TENANT_ID);
            when(restTemplate.exchange(
                    eq(BASE_URL + "/api/internal/agents/" + AGENT_ID + "/get"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(AgentDto.class)
            )).thenReturn(ResponseEntity.ok(expected));

            AgentDto result = agentClient.getAgent(AGENT_ID, TENANT_ID);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(AGENT_ID);
            assertThat(result.getTenantId()).isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("returns null on HTTP error without throwing")
        void getAgent_httpError_returnsNull() {
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(AgentDto.class)
            )).thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

            AgentDto result = agentClient.getAgent(AGENT_ID, TENANT_ID);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("sends request to correct URL with X-User-ID header")
        void getAgent_sendsCorrectUrlAndHeader() {
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(AgentDto.class)
            )).thenReturn(ResponseEntity.ok(buildAgentDto(AGENT_ID, TENANT_ID)));

            agentClient.getAgent(AGENT_ID, TENANT_ID);

            ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(urlCaptor.capture(), eq(HttpMethod.GET),
                    entityCaptor.capture(), eq(AgentDto.class));

            assertThat(urlCaptor.getValue()).isEqualTo(BASE_URL + "/api/internal/agents/" + AGENT_ID + "/get");
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
        }

        @Test
        @DisplayName("sends explicit organization header when resolving org-scoped agent")
        void getAgent_sendsExplicitOrganizationHeader() {
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(AgentDto.class)
            )).thenReturn(ResponseEntity.ok(buildAgentDto(AGENT_ID, TENANT_ID)));

            agentClient.getAgent(AGENT_ID, TENANT_ID, "org-42");

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.GET),
                    entityCaptor.capture(), eq(AgentDto.class));

            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID")).isEqualTo("org-42");
        }
    }

    // =========================================================================
    // createAgent
    // =========================================================================

    @Nested
    @DisplayName("createAgent")
    class CreateAgent {

        @Test
        @DisplayName("sends POST with request body and returns AgentDto body")
        void createAgent_success_sendsPostAndReturnsBody() {
            AgentDto expected = buildAgentDto(AGENT_ID, TENANT_ID);
            Map<String, Object> request = Map.of("name", "New Agent", "description", "Agent desc");
            when(restTemplate.exchange(
                    eq(BASE_URL + "/api/internal/agents/create"),
                    eq(HttpMethod.POST),
                    any(HttpEntity.class),
                    eq(AgentDto.class)
            )).thenReturn(ResponseEntity.ok(expected));

            AgentDto result = agentClient.createAgent(request, TENANT_ID);

            assertThat(result).isNotNull();
            assertThat(result.getId()).isEqualTo(AGENT_ID);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST),
                    entityCaptor.capture(), eq(AgentDto.class));
            assertThat(entityCaptor.getValue().getBody()).isEqualTo(request);
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            assertThat(entityCaptor.getValue().getHeaders().getFirst("Content-Type"))
                    .isEqualTo("application/json");
        }

        @Test
        @DisplayName("returns null on HTTP error without throwing")
        void createAgent_httpError_returnsNull() {
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(AgentDto.class)
            )).thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

            AgentDto result = agentClient.createAgent(Map.of("name", "X"), TENANT_ID);

            assertThat(result).isNull();
        }
    }

    // =========================================================================
    // deleteAgent
    // =========================================================================

    @Nested
    @DisplayName("deleteAgent")
    class DeleteAgent {

        @Test
        @DisplayName("sends DELETE to correct URL")
        void deleteAgent_sendsDeleteRequest() {
            when(restTemplate.exchange(
                    eq(BASE_URL + "/api/internal/agents/" + AGENT_ID + "/delete"),
                    eq(HttpMethod.DELETE),
                    any(HttpEntity.class),
                    eq(Void.class)
            )).thenReturn(ResponseEntity.noContent().build());

            assertThatCode(() -> agentClient.deleteAgent(AGENT_ID, TENANT_ID))
                    .doesNotThrowAnyException();

            verify(restTemplate).exchange(
                    eq(BASE_URL + "/api/internal/agents/" + AGENT_ID + "/delete"),
                    eq(HttpMethod.DELETE),
                    any(HttpEntity.class),
                    eq(Void.class)
            );
        }

        @Test
        @DisplayName("sets explicit organization header for scoped cleanup")
        void deleteAgentSetsExplicitOrganizationHeader() {
            String organizationId = "22222222-2222-4222-8222-222222222222";
            when(restTemplate.exchange(
                    eq(BASE_URL + "/api/internal/agents/" + AGENT_ID + "/delete"),
                    eq(HttpMethod.DELETE),
                    any(HttpEntity.class),
                    eq(Void.class)
            )).thenReturn(ResponseEntity.noContent().build());

            assertThatCode(() -> agentClient.deleteAgent(AGENT_ID, TENANT_ID, organizationId))
                    .doesNotThrowAnyException();

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(
                    eq(BASE_URL + "/api/internal/agents/" + AGENT_ID + "/delete"),
                    eq(HttpMethod.DELETE),
                    entityCaptor.capture(),
                    eq(Void.class)
            );
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .isEqualTo(organizationId);
        }

        @Test
        @DisplayName("swallows HTTP error and does not rethrow")
        void deleteAgent_httpError_doesNotThrow() {
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.DELETE), any(HttpEntity.class), eq(Void.class)
            )).thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

            assertThatCode(() -> agentClient.deleteAgent(AGENT_ID, TENANT_ID))
                    .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // countByTenantId
    // =========================================================================

    @Nested
    @DisplayName("countByTenantId")
    class CountByTenantId {

        @Test
        @DisplayName("sends GET to /api/internal/agents/count and returns Long body")
        void countByTenantId_success_returnsCount() {
            when(restTemplate.exchange(
                    eq(BASE_URL + "/api/internal/agents/count"),
                    eq(HttpMethod.GET),
                    any(HttpEntity.class),
                    eq(Long.class)
            )).thenReturn(ResponseEntity.ok(42L));

            long result = agentClient.countByTenantId(TENANT_ID);

            assertThat(result).isEqualTo(42L);
        }

        @Test
        @DisplayName("returns 0 on HTTP error without throwing")
        void countByTenantId_httpError_returnsZero() {
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Long.class)
            )).thenThrow(new HttpClientErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

            long result = agentClient.countByTenantId(TENANT_ID);

            assertThat(result).isEqualTo(0L);
        }

        @Test
        @DisplayName("returns 0 when response body is null")
        void countByTenantId_nullBody_returnsZero() {
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(Long.class)
            )).thenReturn(ResponseEntity.ok(null));

            long result = agentClient.countByTenantId(TENANT_ID);

            assertThat(result).isEqualTo(0L);
        }
    }

    // =========================================================================
    // assignToProject
    // =========================================================================

    @Nested
    @DisplayName("assignToProject")
    class AssignToProject {

        @Test
        @DisplayName("sends PUT to correct URL and returns true when body is TRUE")
        void assignToProject_success_returnsTrue() {
            String expectedUrl = BASE_URL + "/api/internal/agents/" + AGENT_ID + "/project/" + PROJECT_ID;
            when(restTemplate.exchange(
                    eq(expectedUrl),
                    eq(HttpMethod.PUT),
                    any(HttpEntity.class),
                    eq(Boolean.class)
            )).thenReturn(ResponseEntity.ok(Boolean.TRUE));

            boolean result = agentClient.assignToProject(AGENT_ID, PROJECT_ID, TENANT_ID);

            assertThat(result).isTrue();
            verify(restTemplate).exchange(eq(expectedUrl), eq(HttpMethod.PUT),
                    any(HttpEntity.class), eq(Boolean.class));
        }

        @Test
        @DisplayName("returns false on HTTP error without throwing")
        void assignToProject_httpError_returnsFalse() {
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Boolean.class)
            )).thenThrow(new HttpClientErrorException(HttpStatus.BAD_REQUEST));

            boolean result = agentClient.assignToProject(AGENT_ID, PROJECT_ID, TENANT_ID);

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("returns false when response body is null")
        void assignToProject_nullBody_returnsFalse() {
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.PUT), any(HttpEntity.class), eq(Boolean.class)
            )).thenReturn(ResponseEntity.ok(null));

            boolean result = agentClient.assignToProject(AGENT_ID, PROJECT_ID, TENANT_ID);

            assertThat(result).isFalse();
        }
    }

    // =========================================================================
    // removeFromProject
    // =========================================================================

    @Nested
    @DisplayName("removeFromProject")
    class RemoveFromProject {

        @Test
        @DisplayName("sends DELETE to correct URL including agentId and projectId")
        void removeFromProject_sendsDeleteToCorrectUrl() {
            String expectedUrl = BASE_URL + "/api/internal/agents/" + AGENT_ID + "/project/" + PROJECT_ID;
            when(restTemplate.exchange(
                    eq(expectedUrl),
                    eq(HttpMethod.DELETE),
                    any(HttpEntity.class),
                    eq(Boolean.class)
            )).thenReturn(ResponseEntity.ok(Boolean.TRUE));

            boolean result = agentClient.removeFromProject(AGENT_ID, PROJECT_ID, TENANT_ID);

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("returns false on HTTP error without throwing")
        void removeFromProject_httpError_returnsFalse() {
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.DELETE), any(HttpEntity.class), eq(Boolean.class)
            )).thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

            boolean result = agentClient.removeFromProject(AGENT_ID, PROJECT_ID, TENANT_ID);

            assertThat(result).isFalse();
        }
    }

    // =========================================================================
    // unassignAllFromProject
    // =========================================================================

    @Nested
    @DisplayName("unassignAllFromProject")
    class UnassignAllFromProject {

        @Test
        @DisplayName("sends DELETE to /by-project/{projectId}/unassign-all")
        void unassignAllFromProject_sendsDeleteToCorrectUrl() {
            String expectedUrl = BASE_URL + "/api/internal/agents/by-project/" + PROJECT_ID + "/unassign-all";
            when(restTemplate.exchange(
                    eq(expectedUrl),
                    eq(HttpMethod.DELETE),
                    any(HttpEntity.class),
                    eq(Void.class)
            )).thenReturn(ResponseEntity.noContent().build());

            assertThatCode(() -> agentClient.unassignAllFromProject(PROJECT_ID, TENANT_ID))
                    .doesNotThrowAnyException();

            verify(restTemplate).exchange(eq(expectedUrl), eq(HttpMethod.DELETE),
                    any(HttpEntity.class), eq(Void.class));
        }

        @Test
        @DisplayName("swallows HTTP error and does not rethrow")
        void unassignAllFromProject_httpError_doesNotThrow() {
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.DELETE), any(HttpEntity.class), eq(Void.class)
            )).thenThrow(new HttpClientErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

            assertThatCode(() -> agentClient.unassignAllFromProject(PROJECT_ID, TENANT_ID))
                    .doesNotThrowAnyException();
        }
    }

    // =========================================================================
    // cloneFromSnapshot
    // =========================================================================

    @Nested
    @DisplayName("cloneFromSnapshot")
    class CloneFromSnapshot {

        @Test
        @DisplayName("sends POST to /api/internal/agents/clone-from-snapshot with tenantId in request body")
        @SuppressWarnings("unchecked")
        void cloneFromSnapshot_sendsPostWithTenantId() {
            Map<String, Object> request = new HashMap<>();
            request.put("tenantId", TENANT_ID);
            request.put("agentName", "Cloned Agent");
            UUID newAgentId = UUID.randomUUID();
            Map<String, Object> responseBody = Map.of("agentId", newAgentId.toString());

            when(restTemplate.exchange(
                    eq(BASE_URL + "/api/internal/agents/clone-from-snapshot"),
                    eq(HttpMethod.POST),
                    any(HttpEntity.class),
                    any(org.springframework.core.ParameterizedTypeReference.class)
            )).thenReturn(ResponseEntity.ok(responseBody));

            Map<String, Object> result = agentClient.cloneFromSnapshot(request);

            assertThat(result).isNotNull();
            assertThat(result.get("agentId")).isEqualTo(newAgentId.toString());

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(
                    eq(BASE_URL + "/api/internal/agents/clone-from-snapshot"),
                    eq(HttpMethod.POST),
                    entityCaptor.capture(),
                    any(org.springframework.core.ParameterizedTypeReference.class)
            );
            // tenantId is extracted from request body and set as X-User-ID header
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            assertThat(entityCaptor.getValue().getBody()).isEqualTo(request);
        }

        @Test
        @DisplayName("returns null on HTTP error without throwing")
        @SuppressWarnings("unchecked")
        void cloneFromSnapshot_httpError_returnsNull() {
            Map<String, Object> request = new HashMap<>();
            request.put("tenantId", TENANT_ID);

            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                    any(org.springframework.core.ParameterizedTypeReference.class)
            )).thenThrow(new HttpClientErrorException(HttpStatus.UNPROCESSABLE_ENTITY));

            Map<String, Object> result = agentClient.cloneFromSnapshot(request);

            assertThat(result).isNull();
        }
    }

    // =========================================================================
    // recordObservability (fire-and-forget)
    // =========================================================================

    @Nested
    @DisplayName("recordObservability")
    class RecordObservability {

        @Test
        @DisplayName("sends POST to /api/internal/agents/observability - fire and forget")
        void recordObservability_sendsPost() {
            AgentObservabilityRequest request = new AgentObservabilityRequest();
            request.setTenantId(TENANT_ID);
            request.setStatus("SUCCESS");
            when(restTemplate.exchange(
                    eq(BASE_URL + "/api/internal/agents/observability"),
                    eq(HttpMethod.POST),
                    any(HttpEntity.class),
                    eq(Void.class)
            )).thenReturn(ResponseEntity.ok().build());

            assertThatCode(() -> agentClient.recordObservability(request))
                    .doesNotThrowAnyException();

            verify(restTemplate).exchange(
                    eq(BASE_URL + "/api/internal/agents/observability"),
                    eq(HttpMethod.POST),
                    any(HttpEntity.class),
                    eq(Void.class)
            );
        }

        @Test
        @DisplayName("swallows HTTP error and does not rethrow - fire and forget")
        void recordObservability_httpError_doesNotThrow() {
            AgentObservabilityRequest request = new AgentObservabilityRequest();
            request.setTenantId(TENANT_ID);
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Void.class)
            )).thenThrow(new HttpClientErrorException(HttpStatus.SERVICE_UNAVAILABLE));

            assertThatCode(() -> agentClient.recordObservability(request))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("sets X-User-ID header from request tenantId")
        void recordObservability_setsUserIdHeaderFromRequest() {
            AgentObservabilityRequest request = new AgentObservabilityRequest();
            request.setTenantId(TENANT_ID);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(Void.class)))
                    .thenReturn(ResponseEntity.ok().build());

            agentClient.recordObservability(request);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST),
                    entityCaptor.capture(), eq(Void.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
        }
    }

    // =========================================================================
    // buildHeaders (verified via public methods)
    // =========================================================================

    @Nested
    @DisplayName("buildHeaders - X-User-ID and Content-Type")
    class BuildHeaders {

        @Test
        @DisplayName("sets X-User-ID to tenantId and Content-Type to application/json")
        void buildHeaders_setsRequiredHeaders() {
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(AgentDto.class)
            )).thenReturn(ResponseEntity.ok(buildAgentDto(AGENT_ID, TENANT_ID)));

            agentClient.getAgent(AGENT_ID, TENANT_ID);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.GET),
                    entityCaptor.capture(), eq(AgentDto.class));

            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            assertThat(entityCaptor.getValue().getHeaders().getFirst("Content-Type"))
                    .isEqualTo("application/json");
        }

        @Test
        @DisplayName("getAgent with organizationId sets X-Organization-ID")
        void getAgentWithOrganizationIdSetsOrganizationHeader() {
            String organizationId = "11111111-1111-4111-8111-111111111111";
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(AgentDto.class)
            )).thenReturn(ResponseEntity.ok(buildAgentDto(AGENT_ID, TENANT_ID)));

            agentClient.getAgent(AGENT_ID, TENANT_ID, organizationId);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.GET),
                    entityCaptor.capture(), eq(AgentDto.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .isEqualTo(organizationId);
        }

        @Test
        @DisplayName("resolveAgentConfig with organizationId sets X-Organization-ID")
        void resolveAgentConfigWithOrganizationIdSetsOrganizationHeader() {
            String organizationId = "11111111-1111-4111-8111-111111111111";
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(AgentDto.class)
            )).thenReturn(ResponseEntity.ok(buildAgentDto(AGENT_ID, TENANT_ID)));

            agentClient.resolveAgentConfig(AGENT_ID, TENANT_ID, organizationId);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(
                    eq(BASE_URL + "/api/internal/agents/by-config/" + AGENT_ID),
                    eq(HttpMethod.GET),
                    entityCaptor.capture(),
                    eq(AgentDto.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .isEqualTo(organizationId);
        }

        @Test
        @DisplayName("bulkFind with organizationId sets X-Organization-ID")
        void bulkFindWithOrganizationIdSetsOrganizationHeader() {
            String organizationId = "11111111-1111-4111-8111-111111111111";
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.POST), any(HttpEntity.class),
                    any(org.springframework.core.ParameterizedTypeReference.class)
            )).thenReturn(ResponseEntity.ok(List.of(buildAgentDto(AGENT_ID, TENANT_ID))));

            agentClient.bulkFind(List.of(AGENT_ID), TENANT_ID, organizationId);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(
                    eq(BASE_URL + "/api/internal/agents/bulk"),
                    eq(HttpMethod.POST),
                    entityCaptor.capture(),
                    any(org.springframework.core.ParameterizedTypeReference.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .isEqualTo(organizationId);
        }

        @Test
        @DisplayName("getSkillsForAgent with organizationId sets X-Organization-ID")
        void getSkillsForAgentWithOrganizationIdSetsOrganizationHeader() {
            String organizationId = "11111111-1111-4111-8111-111111111111";
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                    any(org.springframework.core.ParameterizedTypeReference.class)
            )).thenReturn(ResponseEntity.ok(List.of()));

            agentClient.getSkillsForAgent(AGENT_ID, TENANT_ID, organizationId);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(
                    eq(BASE_URL + "/api/internal/agents/" + AGENT_ID + "/skills"),
                    eq(HttpMethod.GET),
                    entityCaptor.capture(),
                    any(org.springframework.core.ParameterizedTypeReference.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .isEqualTo(organizationId);
        }

        @Test
        @DisplayName("does not set X-User-ID when tenantId is null")
        void buildHeaders_nullTenantId_noUserIdHeader() {
            AgentObservabilityRequest request = new AgentObservabilityRequest();
            request.setTenantId(null);
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(Void.class)))
                    .thenReturn(ResponseEntity.ok().build());

            // recordObservability calls buildHeaders(request.getTenantId()) = buildHeaders(null)
            agentClient.recordObservability(request);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST),
                    entityCaptor.capture(), eq(Void.class));
            assertThat(entityCaptor.getValue().getHeaders().containsKey("X-User-ID")).isFalse();
        }

        @Test
        @DisplayName("createOrUpdateSchedule sets organization header from server-side config")
        void createOrUpdateSchedule_setsOrganizationHeaderFromConfig() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("id", "schedule-1")));

            agentClient.createOrUpdateSchedule(AGENT_ID, Map.of(
                    "cron", "0 9 * * *",
                    "organizationId", "org-acquired"
            ), TENANT_ID);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST),
                    entityCaptor.capture(), eq(Map.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .isEqualTo("org-acquired");
        }

        @Test
        @DisplayName("createOrUpdateWebhook sets organization header from server-side config")
        void createOrUpdateWebhook_setsOrganizationHeaderFromConfig() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("id", "webhook-1")));

            agentClient.createOrUpdateWebhook(AGENT_ID, Map.of(
                    "path", "agent-webhook",
                    "organizationId", "org-acquired"
            ), TENANT_ID);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST),
                    entityCaptor.capture(), eq(Map.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .isEqualTo("org-acquired");
        }

        @Test
        @DisplayName("createOrUpdateWebhook normalizes UUID organization header from server-side config")
        void createOrUpdateWebhook_normalizesUuidOrganizationHeaderFromConfig() {
            UUID organizationId = UUID.randomUUID();
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("id", "webhook-1")));

            agentClient.createOrUpdateWebhook(AGENT_ID, Map.of(
                    "path", "agent-webhook",
                    "organizationId", organizationId
            ), TENANT_ID);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST),
                    entityCaptor.capture(), eq(Map.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .isEqualTo(organizationId.toString());
        }

        @Test
        @DisplayName("createOrUpdateSchedule normalizes UUID organization header from server-side config")
        void createOrUpdateSchedule_normalizesUuidOrganizationHeaderFromConfig() {
            UUID organizationId = UUID.randomUUID();
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), eq(Map.class)))
                    .thenReturn(ResponseEntity.ok(Map.of("id", "schedule-1")));

            agentClient.createOrUpdateSchedule(AGENT_ID, Map.of(
                    "cron", "0 9 * * *",
                    "organizationId", organizationId
            ), TENANT_ID);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST),
                    entityCaptor.capture(), eq(Map.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .isEqualTo(organizationId.toString());
        }
    }

    // =========================================================================
    // Additional error-handling coverage
    // =========================================================================

    @Nested
    @DisplayName("Error handling - no exception propagation")
    class ErrorHandling {

        @Test
        @DisplayName("deleteAgentsByWorkflowId swallows HTTP error")
        void deleteAgentsByWorkflowId_httpError_doesNotThrow() {
            UUID workflowId = UUID.randomUUID();
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.DELETE), any(HttpEntity.class), eq(Void.class)
            )).thenThrow(new HttpClientErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

            assertThatCode(() -> agentClient.deleteAgentsByWorkflowId(workflowId, TENANT_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("getSkillsForAgent returns empty list on HTTP error")
        void getSkillsForAgent_httpError_returnsEmptyList() {
            when(restTemplate.exchange(
                    anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                    any(org.springframework.core.ParameterizedTypeReference.class)
            )).thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND));

            List<com.apimarketplace.agent.client.dto.AgentSkillDto> result =
                    agentClient.getSkillsForAgent(AGENT_ID, TENANT_ID);

            assertThat(result).isEmpty();
        }
    }
}
