package com.apimarketplace.interfaces.client;

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
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InterfaceClient")
class InterfaceClientTest {

    @Mock
    private RestTemplate restTemplate;

    private InterfaceClient interfaceClient;

    private static final String BASE_URL = "http://localhost:8089";
    private static final String TENANT_ID = "auth0|tenant-test";

    @BeforeEach
    void setUp() {
        interfaceClient = new InterfaceClient(restTemplate, BASE_URL);
    }

    @Nested
    @DisplayName("getInterface")
    class GetInterface {

        @Test
        @DisplayName("sets explicit organization header for scoped fetch")
        void getInterfaceSetsExplicitOrganizationHeader() {
            UUID interfaceId = UUID.randomUUID();
            String organizationId = UUID.randomUUID().toString();
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET),
                    any(HttpEntity.class), eq(com.apimarketplace.interfaces.client.dto.InterfaceDto.class)))
                    .thenReturn(ResponseEntity.ok(new com.apimarketplace.interfaces.client.dto.InterfaceDto()));

            interfaceClient.getInterface(interfaceId, TENANT_ID, organizationId);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(eq(BASE_URL + "/api/internal/interfaces/" + interfaceId),
                    eq(HttpMethod.GET), entityCaptor.capture(),
                    eq(com.apimarketplace.interfaces.client.dto.InterfaceDto.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .isEqualTo(organizationId);
        }
    }

    @Nested
    @DisplayName("deleteInterface")
    class DeleteInterface {

        @Test
        @DisplayName("sets explicit organization header for scoped cleanup")
        void deleteInterfaceSetsExplicitOrganizationHeader() {
            UUID interfaceId = UUID.randomUUID();
            String organizationId = UUID.randomUUID().toString();
            when(restTemplate.exchange(anyString(), eq(HttpMethod.DELETE),
                    any(HttpEntity.class), eq(Void.class)))
                    .thenReturn(ResponseEntity.ok().build());

            boolean deleted = interfaceClient.deleteInterface(interfaceId, TENANT_ID, organizationId);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(eq(BASE_URL + "/api/internal/interfaces/" + interfaceId),
                    eq(HttpMethod.DELETE), entityCaptor.capture(), eq(Void.class));
            assertThat(deleted).isTrue();
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .isEqualTo(organizationId);
        }

        @Test
        @DisplayName("keeps personal scope when organization header is blank")
        void deleteInterfaceKeepsPersonalScopeWhenOrganizationBlank() {
            UUID interfaceId = UUID.randomUUID();
            when(restTemplate.exchange(anyString(), eq(HttpMethod.DELETE),
                    any(HttpEntity.class), eq(Void.class)))
                    .thenReturn(ResponseEntity.ok().build());

            boolean deleted = interfaceClient.deleteInterface(interfaceId, TENANT_ID, " ");

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(eq(BASE_URL + "/api/internal/interfaces/" + interfaceId),
                    eq(HttpMethod.DELETE), entityCaptor.capture(), eq(Void.class));
            assertThat(deleted).isTrue();
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            assertThat(entityCaptor.getValue().getHeaders().containsKey("X-Organization-ID")).isFalse();
        }
    }

    @Nested
    @DisplayName("getSnapshotsForRun")
    class GetSnapshotsForRun {

        @Test
        @DisplayName("sets explicit organization header for scoped snapshot list")
        void getSnapshotsForRunSetsExplicitOrganizationHeader() {
            UUID workflowRunId = UUID.randomUUID();
            String organizationId = UUID.randomUUID().toString();
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET),
                    any(HttpEntity.class), any(org.springframework.core.ParameterizedTypeReference.class)))
                    .thenReturn(ResponseEntity.ok(List.of()));

            interfaceClient.getSnapshotsForRun(workflowRunId, TENANT_ID, organizationId);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(eq(BASE_URL + "/api/internal/interfaces/snapshots/by-run/" + workflowRunId),
                    eq(HttpMethod.GET), entityCaptor.capture(),
                    any(org.springframework.core.ParameterizedTypeReference.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .isEqualTo(organizationId);
        }
    }

    @Nested
    @DisplayName("getInterfacesByIds")
    class GetInterfacesByIds {

        @Test
        @DisplayName("sets explicit organization header for scoped batch fetch")
        void getInterfacesByIdsSetsExplicitOrganizationHeader() {
            UUID interfaceId = UUID.randomUUID();
            String organizationId = UUID.randomUUID().toString();
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), any(org.springframework.core.ParameterizedTypeReference.class)))
                    .thenReturn(ResponseEntity.ok(List.of()));

            interfaceClient.getInterfacesByIds(List.of(interfaceId), TENANT_ID, organizationId);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(eq(BASE_URL + "/api/internal/interfaces/batch"),
                    eq(HttpMethod.POST), entityCaptor.capture(),
                    any(org.springframework.core.ParameterizedTypeReference.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .isEqualTo(organizationId);
        }
    }
}
