package com.apimarketplace.datasource.services;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.domain.ColumnStructure;
import com.apimarketplace.datasource.domain.ColumnType;
import com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSourceType;
import com.apimarketplace.datasource.events.VectorColumnCreatedEvent;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceItemRepository;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REGRESSION - raw-mappingSpec bypass of the vector edition gate.
 *
 * <p>The column-definition validators (ToolParameterUtils) only run on the
 * columns[]/add_columns shapes. {@code createDataSource} accepts a caller
 * supplied mappingSpec WHOLESALE (REST POST /api/datasource, internal create,
 * agent table-create), so before this gate a cloud caller could create a
 * vector column by posting {@code mappingSpec: {embedding: {type: 'vector'}}}
 * directly - these tests fail on the pre-gate code.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DataSourceService - vector edition gating (raw mappingSpec)")
class DataSourceServiceVectorGatingTest {

    private static final String TENANT = "tenant-1";

    @Mock private DataSourceRepository dataSourceRepository;
    @Mock private DataSourceItemRepository dataSourceItemRepository;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private OrgAccessGuard orgAccessGuard;
    @Mock private com.apimarketplace.auth.client.entitlement.EntitlementGuard entitlementGuard;
    @Mock private ApplicationEventPublisher eventPublisher;

    private DataSourceService service(String edition) {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.edition", edition);
        return new DataSourceService(dataSourceRepository, dataSourceItemRepository,
                breakdownService, new ObjectMapper(), orgAccessGuard, entitlementGuard,
                new VectorFeatureGate(new com.apimarketplace.common.web.AppEditionProvider(env)), eventPublisher,
                org.mockito.Mockito.mock(com.apimarketplace.publication.client.PublicationClient.class));
    }

    private static Map<String, ColumnMappingSpec> vectorMappingSpec() {
        Map<String, ColumnMappingSpec> mapping = new LinkedHashMap<>();
        mapping.put("title", new ColumnMappingSpec("data.title", ColumnType.TEXT, ColumnStructure.SCALAR, Map.of(), Map.of()));
        mapping.put("embedding", new ColumnMappingSpec("data.embedding", ColumnType.VECTOR, ColumnStructure.SCALAR,
                Map.of(), Map.of("dimension", 1536, "metric", "cosine")));
        return mapping;
    }

    @Test
    @DisplayName("REGRESSION: cloud rejects a raw mappingSpec carrying a vector column (pre-gate this saved silently)")
    void cloudRejectsRawVectorMappingSpec() {
        assertThatThrownBy(() -> service("cloud").createDataSource(
                TENANT, "docs", null, DataSourceType.INLINE, Map.of(), null, TENANT,
                vectorMappingSpec(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(VectorFeatureGate.DISABLED_MESSAGE);

        verify(dataSourceRepository, never()).save(any());
    }

    @Test
    @DisplayName("cloud still accepts a vector-free mappingSpec unchanged")
    void cloudAcceptsVectorFreeMappingSpec() {
        Map<String, ColumnMappingSpec> mapping = new LinkedHashMap<>();
        mapping.put("title", new ColumnMappingSpec("data.title", ColumnType.TEXT, ColumnStructure.SCALAR, Map.of(), Map.of()));
        when(dataSourceRepository.save(any())).thenAnswer(inv -> {
            DataSource ds = inv.getArgument(0);
            return ds.withId(7L);
        });

        DataSource saved = service("cloud").createDataSource(
                TENANT, "docs", null, DataSourceType.INLINE, Map.of(), null, TENANT, mapping, null);

        assertThat(saved.id()).isEqualTo(7L);
        verify(eventPublisher, never()).publishEvent(any(VectorColumnCreatedEvent.class));
    }

    @Test
    @DisplayName("CE accepts the vector mappingSpec and schedules the HNSW build (dimension+metric from display)")
    void ceAcceptsAndSchedulesHnsw() {
        when(dataSourceRepository.save(any())).thenAnswer(inv -> {
            DataSource ds = inv.getArgument(0);
            return ds.withId(42L);
        });

        DataSource saved = service("ce").createDataSource(
                TENANT, "docs", null, DataSourceType.INLINE, Map.of(), null, TENANT,
                vectorMappingSpec(), null);

        assertThat(saved.id()).isEqualTo(42L);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(captor.capture());
        VectorColumnCreatedEvent event = (VectorColumnCreatedEvent) captor.getValue();
        assertThat(event.dataSourceId()).isEqualTo(42L);
        assertThat(event.dimension()).isEqualTo(1536);
        assertThat(event.metric()).isEqualTo("cosine");
    }
}
