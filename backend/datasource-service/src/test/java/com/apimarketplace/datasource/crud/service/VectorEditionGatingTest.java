package com.apimarketplace.datasource.crud.service;

import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.datasource.crud.domain.CrudResult;
import com.apimarketplace.datasource.crud.dto.CreateColumnRequest;
import com.apimarketplace.datasource.crud.dto.CreateRowRequest;
import com.apimarketplace.datasource.crud.dto.ReadRowRequest;
import com.apimarketplace.datasource.crud.dto.SimilarityQueryDto;
import com.apimarketplace.datasource.crud.repository.CrudRepository;
import com.apimarketplace.datasource.crud.repository.VectorRepository;
import com.apimarketplace.datasource.domain.ColumnStructure;
import com.apimarketplace.datasource.domain.ColumnType;
import com.apimarketplace.datasource.domain.DataSourceModels.ColumnMappingSpec;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.events.DatasourceRowEventPublisher;
import com.apimarketplace.datasource.events.VectorColumnCreatedEvent;
import com.apimarketplace.datasource.persistence.DataSourceColumnRepository;
import com.apimarketplace.datasource.services.DataSourceService;
import com.apimarketplace.datasource.services.VectorFeatureGate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Edition gating of vector (embedding) features in the CRUD executor - vector
 * columns are self-hosted-only; managed cloud must reject every entry point
 * with the single agent-actionable message, while CE keeps full behavior.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CrudExecutorService - vector edition gating")
class VectorEditionGatingTest {

    private static final String TENANT = "tenant-1";

    @Mock private CrudRepository crudRepository;
    @Mock private VectorRepository vectorRepository;
    @Mock private DataSourceService dataSourceService;
    @Mock private StorageBreakdownService breakdownService;
    @Mock private ColumnValueCoercer columnValueCoercer;
    @Mock private DataSourceColumnRepository columnRepository;
    @Mock private SqlSanitizer sqlSanitizer;
    @Mock private DatasourceRowEventPublisher rowEventPublisher;
    @Mock private ApplicationEventPublisher eventPublisher;

    private static VectorFeatureGate gate(String edition) {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.edition", edition);
        return new VectorFeatureGate(new com.apimarketplace.common.web.AppEditionProvider(env));
    }

    private CrudExecutorService service(String edition) {
        return new CrudExecutorService(crudRepository, vectorRepository, dataSourceService,
                breakdownService, columnValueCoercer, columnRepository, sqlSanitizer,
                rowEventPublisher, gate(edition), eventPublisher);
    }

    private DataSource vectorDataSource() {
        Map<String, ColumnMappingSpec> mapping = new LinkedHashMap<>();
        mapping.put("title", new ColumnMappingSpec("data.title", ColumnType.TEXT, ColumnStructure.SCALAR, Map.of(), Map.of()));
        mapping.put("embedding", new ColumnMappingSpec("data.embedding", ColumnType.VECTOR, ColumnStructure.SCALAR,
                Map.of(), Map.of("dimension", 4, "metric", "cosine")));
        return new DataSource(1L, TENANT, "docs", null, null, null, null, null, null, TENANT,
                null, mapping, null, null, null, null);
    }

    private void stubDataSource(DataSource ds) {
        when(dataSourceService.getDataSource(1L)).thenReturn(Optional.of(ds));
    }

    private ReadRowRequest similarityRequest() {
        ReadRowRequest request = new ReadRowRequest();
        request.setDataSourceId(1L);
        SimilarityQueryDto sim = new SimilarityQueryDto();
        sim.setColumn("embedding");
        sim.setQueryVector(new float[]{0.1f, 0.2f, 0.3f, 0.4f});
        sim.setTopK(3);
        request.setSimilarity(sim);
        return request;
    }

    @Nested
    @DisplayName("managed cloud (blocked)")
    class CloudBlocked {

        @Test
        @DisplayName("similarity search is rejected with the edition message - even on a table that has vectors")
        void similarityRejected() {
            stubDataSource(vectorDataSource());

            CrudResult result = service("cloud").execute(similarityRequest(), TENANT);

            assertThat(result.success()).isFalse();
            assertThat(result.message()).isEqualTo(VectorFeatureGate.DISABLED_MESSAGE);
            verify(vectorRepository, never()).similaritySearch(anyLong(), anyString(), anyString(),
                    any(float[].class), org.mockito.ArgumentMatchers.anyInt(), anyString(),
                    org.mockito.ArgumentMatchers.anyInt(), any(), any(), any());
        }

        @Test
        @DisplayName("create-column with type=vector is rejected with the edition message")
        void createVectorColumnRejected() {
            stubDataSource(vectorDataSource());
            CreateColumnRequest request = new CreateColumnRequest();
            request.setDataSourceId(1L);
            request.setColumns(List.of(new CreateColumnRequest.ColumnDefinition(
                    "embedding2", "vector", null, Map.of("dimension", 8))));

            CrudResult result = service("cloud").execute(request, TENANT);

            assertThat(result.success()).isFalse();
            assertThat(result.message()).isEqualTo(VectorFeatureGate.DISABLED_MESSAGE);
            verify(crudRepository, never()).createColumns(anyLong(), anyString(), anyList());
        }

        @Test
        @DisplayName("insert supplying a vector VALUE is rejected; vectors are never stored")
        void insertWithVectorValueRejected() {
            stubDataSource(vectorDataSource());
            CreateRowRequest request = new CreateRowRequest();
            request.setDataSourceId(1L);
            request.setRows(List.of(new CreateRowRequest.RowData(null, new LinkedHashMap<>(
                    Map.of("title", "doc", "embedding", List.of(0.1, 0.2, 0.3, 0.4))))));

            CrudResult result = service("cloud").execute(request, TENANT);

            assertThat(result.success()).isFalse();
            assertThat(result.message()).isEqualTo(VectorFeatureGate.DISABLED_MESSAGE);
            verify(vectorRepository, never()).insertVectorBatch(anyLong(), anyString(), anyList());
            verify(crudRepository, never()).createRows(anyLong(), anyString(), anyList());
        }

        @Test
        @DisplayName("update_rows supplying a vector VALUE is rejected - the write bypass the create-row gate alone would leave open")
        void updateWithVectorValueRejected() {
            stubDataSource(vectorDataSource());
            com.apimarketplace.datasource.crud.dto.UpdateRowRequest request =
                    new com.apimarketplace.datasource.crud.dto.UpdateRowRequest();
            request.setDataSourceId(1L);
            request.setWhere(new com.apimarketplace.datasource.crud.dto.WhereConditionDto("title", "=", "doc"));
            Map<String, Object> set = new LinkedHashMap<>();
            set.put("embedding", List.of(0.1, 0.2, 0.3, 0.4));
            request.setSet(set);

            CrudResult result = service("cloud").execute(request, TENANT);

            assertThat(result.success()).isFalse();
            assertThat(result.message()).isEqualTo(VectorFeatureGate.DISABLED_MESSAGE);
            verify(vectorRepository, never()).insertVectorBatch(anyLong(), anyString(), anyList());
            verify(crudRepository, never()).updateRows(anyLong(), anyString(), any(), any());
        }

        @Test
        @DisplayName("update_rows WITHOUT vector values on a legacy vector table stays allowed")
        void scalarUpdateOnLegacyVectorTableAllowed() {
            stubDataSource(vectorDataSource());
            when(crudRepository.findIdsMatching(anyLong(), anyString(), any())).thenReturn(List.of(10L));
            when(crudRepository.updateRows(anyLong(), anyString(), any(), any())).thenReturn(1);
            com.apimarketplace.datasource.crud.dto.UpdateRowRequest request =
                    new com.apimarketplace.datasource.crud.dto.UpdateRowRequest();
            request.setDataSourceId(1L);
            request.setWhere(new com.apimarketplace.datasource.crud.dto.WhereConditionDto("title", "=", "doc"));
            Map<String, Object> set = new LinkedHashMap<>();
            set.put("title", "doc-renamed");
            request.setSet(set);

            CrudResult result = service("cloud").execute(request, TENANT);

            assertThat(result.success()).isTrue();
            verify(vectorRepository, never()).insertVectorBatch(anyLong(), anyString(), anyList());
        }

        @Test
        @DisplayName("insert WITHOUT vector values on a legacy vector table stays allowed (scalar columns survive)")
        void scalarInsertOnLegacyVectorTableAllowed() {
            stubDataSource(vectorDataSource());
            when(crudRepository.createRows(anyLong(), anyString(), anyList())).thenReturn(List.of(10L));
            CreateRowRequest request = new CreateRowRequest();
            request.setDataSourceId(1L);
            request.setRows(List.of(new CreateRowRequest.RowData(null, new LinkedHashMap<>(
                    Map.of("title", "doc")))));

            CrudResult result = service("cloud").execute(request, TENANT);

            assertThat(result.success()).isTrue();
            verify(vectorRepository, never()).insertVectorBatch(anyLong(), anyString(), anyList());
        }
    }

    @Nested
    @DisplayName("self-hosted CE (allowed)")
    class CeAllowed {

        @Test
        @DisplayName("similarity search executes against the vector repository")
        void similarityExecutes() {
            stubDataSource(vectorDataSource());
            when(vectorRepository.similaritySearch(anyLong(), anyString(), anyString(),
                    any(float[].class), org.mockito.ArgumentMatchers.anyInt(), anyString(),
                    org.mockito.ArgumentMatchers.anyInt(), any(), any(), any()))
                    .thenReturn(List.of());

            CrudResult result = service("ce").execute(similarityRequest(), TENANT);

            assertThat(result.success()).isTrue();
        }

        @Test
        @DisplayName("dimension mismatch at INSERT is rejected with an actionable message (was silently stored, broke only at query time)")
        void insertDimensionMismatchRejected() {
            stubDataSource(vectorDataSource());
            // Coercion path: mappingSpec is reloaded from the column repository,
            // and the coercer converts the supplied list into a wrong-size float[].
            when(columnRepository.loadMappingSpec(1L, TENANT))
                    .thenReturn(vectorDataSource().mappingSpec());
            when(columnValueCoercer.coerce(any(), any(), any()))
                    .thenAnswer(inv -> inv.getArgument(1) == ColumnType.VECTOR
                            ? new CoercionResult(new float[]{0.1f, 0.2f, 0.3f}, List.of())
                            : new CoercionResult(inv.getArgument(0), List.of()));
            CreateRowRequest request = new CreateRowRequest();
            request.setDataSourceId(1L);
            Map<String, Object> cols = new LinkedHashMap<>();
            cols.put("title", "doc");
            cols.put("embedding", List.of(0.1, 0.2, 0.3));
            request.setRows(List.of(new CreateRowRequest.RowData(null, cols)));

            CrudResult result = service("ce").execute(request, TENANT);

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("expects dimension 4").contains("got 3");
            verify(vectorRepository, never()).insertVectorBatch(anyLong(), anyString(), anyList());
        }

        @Test
        @DisplayName("creating a vector column publishes the post-commit HNSW build event with dimension+metric")
        void createVectorColumnPublishesHnswEvent() {
            stubDataSource(vectorDataSource());
            when(crudRepository.createColumns(anyLong(), anyString(), anyList()))
                    .thenReturn(List.of("embedding2"));
            CreateColumnRequest request = new CreateColumnRequest();
            request.setDataSourceId(1L);
            request.setColumns(List.of(new CreateColumnRequest.ColumnDefinition(
                    "embedding2", "vector", null, Map.of("dimension", 8, "metric", "l2"))));

            CrudResult result = service("ce").execute(request, TENANT);

            assertThat(result.success()).isTrue();
            ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue()).isInstanceOf(VectorColumnCreatedEvent.class);
            VectorColumnCreatedEvent event = (VectorColumnCreatedEvent) captor.getValue();
            assertThat(event.dataSourceId()).isEqualTo(1L);
            assertThat(event.dimension()).isEqualTo(8);
            assertThat(event.metric()).isEqualTo("l2");
        }

        @Test
        @DisplayName("update_rows dimension mismatch is rejected on CE too (wrong-size embedding via update breaks the HNSW index)")
        void updateDimensionMismatchRejected() {
            stubDataSource(vectorDataSource());
            when(columnRepository.loadMappingSpec(1L, TENANT))
                    .thenReturn(vectorDataSource().mappingSpec());
            when(columnValueCoercer.coerce(any(), any(), any()))
                    .thenAnswer(inv -> inv.getArgument(1) == ColumnType.VECTOR
                            ? new CoercionResult(new float[]{0.1f, 0.2f}, List.of())
                            : new CoercionResult(inv.getArgument(0), List.of()));
            com.apimarketplace.datasource.crud.dto.UpdateRowRequest request =
                    new com.apimarketplace.datasource.crud.dto.UpdateRowRequest();
            request.setDataSourceId(1L);
            request.setWhere(new com.apimarketplace.datasource.crud.dto.WhereConditionDto("title", "=", "doc"));
            Map<String, Object> set = new LinkedHashMap<>();
            set.put("embedding", List.of(0.1, 0.2));
            request.setSet(set);

            CrudResult result = service("ce").execute(request, TENANT);

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("expects dimension 4").contains("got 2");
            verify(vectorRepository, never()).insertVectorBatch(anyLong(), anyString(), anyList());
        }

        @Test
        @DisplayName("REGRESSION: a FAILED vector coercion (e.g. dimension mismatch caught by the coercer) fails the insert - it used to degrade to null+warning, silently dropping the embedding")
        void failedVectorCoercionFailsInsert() {
            stubDataSource(vectorDataSource());
            when(columnRepository.loadMappingSpec(1L, TENANT))
                    .thenReturn(vectorDataSource().mappingSpec());
            when(columnValueCoercer.coerce(any(), any(), any()))
                    .thenAnswer(inv -> inv.getArgument(1) == ColumnType.VECTOR
                            ? CoercionResult.failed("Vector dimension mismatch: expected 4, got 3")
                            : new CoercionResult(inv.getArgument(0), List.of()));
            CreateRowRequest request = new CreateRowRequest();
            request.setDataSourceId(1L);
            Map<String, Object> cols = new LinkedHashMap<>();
            cols.put("title", "doc");
            cols.put("embedding", List.of(0.1, 0.2, 0.3));
            request.setRows(List.of(new CreateRowRequest.RowData(null, cols)));

            CrudResult result = service("ce").execute(request, TENANT);

            assertThat(result.success()).isFalse();
            assertThat(result.message()).contains("expected 4").contains("got 3");
            verify(crudRepository, never()).createRows(anyLong(), anyString(), anyList());
            verify(vectorRepository, never()).insertVectorBatch(anyLong(), anyString(), anyList());
        }

        @Test
        @DisplayName("creating a non-vector column publishes no HNSW event")
        void nonVectorColumnPublishesNoEvent() {
            stubDataSource(vectorDataSource());
            when(crudRepository.createColumns(anyLong(), anyString(), anyList()))
                    .thenReturn(List.of("status"));
            CreateColumnRequest request = new CreateColumnRequest();
            request.setDataSourceId(1L);
            request.setColumns(List.of(new CreateColumnRequest.ColumnDefinition(
                    "status", "text", null, null)));

            CrudResult result = service("ce").execute(request, TENANT);

            assertThat(result.success()).isTrue();
            verify(eventPublisher, never()).publishEvent(any(VectorColumnCreatedEvent.class));
        }
    }
}
