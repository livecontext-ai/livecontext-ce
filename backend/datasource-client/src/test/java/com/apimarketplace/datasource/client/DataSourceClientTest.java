package com.apimarketplace.datasource.client;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.datasource.client.dto.DataSourceDto;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DataSourceClient")
class DataSourceClientTest {

    @Mock
    private RestTemplate restTemplate;

    private DataSourceClient dataSourceClient;

    private static final String BASE_URL = "http://localhost:8088";
    private static final String TENANT_ID = "auth0|tenant-test";

    @BeforeEach
    void setUp() {
        dataSourceClient = new DataSourceClient(restTemplate, BASE_URL);
    }

    @Nested
    @DisplayName("findByIdAndTenantId")
    class FindByIdAndTenantId {

        @Test
        @DisplayName("sets explicit organization header for scoped fetch")
        void findByIdAndTenantIdSetsExplicitOrganizationHeader() {
            Long dataSourceId = 42L;
            String organizationId = "22222222-2222-4222-8222-222222222222";
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET),
                    any(HttpEntity.class), eq(DataSourceDto.class)))
                    .thenReturn(ResponseEntity.ok(new DataSourceDto(dataSourceId, TENANT_ID, "Table", null,
                            null, null, null, null, null, null, null, null, null, null, null, organizationId)));

            dataSourceClient.findByIdAndTenantId(dataSourceId, TENANT_ID, organizationId);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(eq(BASE_URL + "/api/internal/datasource/" + dataSourceId + "/by-tenant"),
                    eq(HttpMethod.GET), entityCaptor.capture(), eq(DataSourceDto.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .isEqualTo(organizationId);
        }
    }

    @Nested
    @DisplayName("bulkFind")
    class BulkFind {

        @Test
        @DisplayName("sets explicit organization header for scoped bulk fetch")
        void bulkFindSetsExplicitOrganizationHeader() {
            Long dataSourceId = 42L;
            String organizationId = "22222222-2222-4222-8222-222222222222";
            when(restTemplate.exchange(anyString(), eq(HttpMethod.POST),
                    any(HttpEntity.class), any(org.springframework.core.ParameterizedTypeReference.class)))
                    .thenReturn(ResponseEntity.ok(List.of(new DataSourceDto(dataSourceId, TENANT_ID, "Table", null,
                            null, null, null, null, null, null, null, null, null, null, null, organizationId))));

            dataSourceClient.bulkFind(List.of(dataSourceId), TENANT_ID, organizationId);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(eq(BASE_URL + "/api/internal/datasource/bulk-find"),
                    eq(HttpMethod.POST), entityCaptor.capture(), any(org.springframework.core.ParameterizedTypeReference.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .isEqualTo(organizationId);
        }
    }

    @Nested
    @DisplayName("getAllItems")
    class GetAllItems {

        @Test
        @DisplayName("sets explicit organization header for scoped item snapshot")
        void getAllItemsSetsExplicitOrganizationHeader() {
            Long dataSourceId = 42L;
            String organizationId = "22222222-2222-4222-8222-222222222222";
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET),
                    any(HttpEntity.class), any(org.springframework.core.ParameterizedTypeReference.class)))
                    .thenReturn(ResponseEntity.ok(List.of()));

            dataSourceClient.getAllItems(dataSourceId, TENANT_ID, organizationId);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(eq(BASE_URL + "/api/internal/datasource/" + dataSourceId + "/items?page=0&size=10000"),
                    eq(HttpMethod.GET), entityCaptor.capture(), any(org.springframework.core.ParameterizedTypeReference.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .isEqualTo(organizationId);
        }
    }

    @Nested
    @DisplayName("deleteDataSource")
    class DeleteDataSource {

        @Test
        @DisplayName("sets explicit organization header for scoped cleanup")
        void deleteDataSourceSetsExplicitOrganizationHeader() {
            Long dataSourceId = 42L;
            String organizationId = "22222222-2222-4222-8222-222222222222";
            when(restTemplate.exchange(anyString(), eq(HttpMethod.DELETE),
                    any(HttpEntity.class), eq(Void.class)))
                    .thenReturn(ResponseEntity.ok().build());

            dataSourceClient.deleteDataSource(dataSourceId, TENANT_ID, organizationId);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(eq(BASE_URL + "/api/internal/datasource/" + dataSourceId + "/delete"),
                    eq(HttpMethod.DELETE), entityCaptor.capture(), eq(Void.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID"))
                    .isEqualTo(organizationId);
        }
    }

    @Nested
    @DisplayName("single-table lookups: log level by failure kind")
    class LookupLogLevel {

        private ListAppender<ILoggingEvent> logs;
        private ch.qos.logback.classic.Logger clientLogger;

        @BeforeEach
        void capture() {
            clientLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(DataSourceClient.class);
            logs = new ListAppender<>();
            logs.start();
            clientLogger.addAppender(logs);
        }

        @org.junit.jupiter.api.AfterEach
        void release() {
            clientLogger.detachAppender(logs);
            logs.stop();
        }

        private List<Level> levels() {
            return logs.list.stream().map(ILoggingEvent::getLevel).toList();
        }

        private void respond(Exception failure) {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(DataSourceDto.class)))
                    .thenThrow(failure);
        }

        @Test
        @DisplayName("findByIdAndTenantId: 404 is a normal not-found answer, logged at WARN, returns null")
        void findByIdNotFoundIsWarn() {
            respond(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

            assertThat(dataSourceClient.findByIdAndTenantId(42L, TENANT_ID, "org-1")).isNull();
            assertThat(levels()).containsExactly(Level.WARN);
        }

        @Test
        @DisplayName("getDataSource: 404 is logged at WARN, returns null")
        void getDataSourceNotFoundIsWarn() {
            respond(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null));

            assertThat(dataSourceClient.getDataSource(42L, TENANT_ID)).isNull();
            assertThat(levels()).containsExactly(Level.WARN);
        }

        @Test
        @DisplayName("a 5xx stays ERROR")
        void serverErrorStaysError() {
            respond(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "boom", null, null, null));

            assertThat(dataSourceClient.findByIdAndTenantId(42L, TENANT_ID, "org-1")).isNull();
            assertThat(levels()).containsExactly(Level.ERROR);
        }

        @Test
        @DisplayName("another 4xx (403) stays ERROR: it is not a not-found answer")
        void forbiddenStaysError() {
            respond(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", null, null, null));

            assertThat(dataSourceClient.getDataSource(42L, TENANT_ID, "org-1")).isNull();
            assertThat(levels()).containsExactly(Level.ERROR);
        }

        @Test
        @DisplayName("an I/O failure stays ERROR")
        void ioFailureStaysError() {
            respond(new ResourceAccessException("Connection refused"));

            assertThat(dataSourceClient.getDataSource(42L, TENANT_ID)).isNull();
            assertThat(levels()).containsExactly(Level.ERROR);
        }
    }

    @Nested
    @DisplayName("getDataSource")
    class GetDataSource {

        @Test
        @DisplayName("the org-aware overload sets X-Organization-ID explicitly (no request to forward it from)")
        void orgAwareOverloadSetsOrganizationHeader() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(DataSourceDto.class)))
                    .thenReturn(ResponseEntity.ok(null));

            dataSourceClient.getDataSource(7L, TENANT_ID, "org-1");

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(eq(BASE_URL + "/api/internal/datasource/7/get"),
                    eq(HttpMethod.GET), entityCaptor.capture(), eq(DataSourceDto.class));
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-User-ID")).isEqualTo(TENANT_ID);
            assertThat(entityCaptor.getValue().getHeaders().getFirst("X-Organization-ID")).isEqualTo("org-1");
        }

        @Test
        @DisplayName("the 2-arg overload sends no org header off a request thread (unchanged)")
        void legacyOverloadSendsNoOrganizationHeader() {
            when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(DataSourceDto.class)))
                    .thenReturn(ResponseEntity.ok(null));

            dataSourceClient.getDataSource(7L, TENANT_ID);

            ArgumentCaptor<HttpEntity> entityCaptor = ArgumentCaptor.forClass(HttpEntity.class);
            verify(restTemplate).exchange(eq(BASE_URL + "/api/internal/datasource/7/get"),
                    eq(HttpMethod.GET), entityCaptor.capture(), eq(DataSourceDto.class));
            assertThat(entityCaptor.getValue().getHeaders().containsKey("X-Organization-ID")).isFalse();
        }
    }
}
