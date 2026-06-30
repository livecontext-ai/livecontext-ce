package com.apimarketplace.datasource.controllers.internal;

import com.apimarketplace.common.web.AppEditionProvider;
import com.apimarketplace.datasource.crud.service.CrudExecutorService;
import com.apimarketplace.datasource.domain.DataSourceModels.DataSource;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceItemRepository;
import com.apimarketplace.datasource.persistence.DataSourceRepositories.DataSourceRepository;
import com.apimarketplace.datasource.services.DataSourceService;
import com.apimarketplace.datasource.services.VectorFeatureGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Snapshot-clone strip wiring - {@code POST /create-from-snapshot} is the path
 * a marketplace acquisition takes; a snapshot published from a self-hosted
 * deployment can carry vector columns that managed cloud must not recreate.
 * The strip must cover BOTH the mappingSpec (the column itself) AND
 * columnOrder (a stripped name left there renders as a ghost column).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InternalDataSourceController - snapshot vector strip")
class InternalDataSourceControllerSnapshotStripTest {

    @Mock private DataSourceService dataSourceService;
    @Mock private DataSourceRepository dataSourceRepository;
    @Mock private DataSourceItemRepository dataSourceItemRepository;
    @Mock private CrudExecutorService crudExecutorService;

    private InternalDataSourceController controller(String edition) {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("app.edition", edition);
        return new InternalDataSourceController(dataSourceService, dataSourceRepository,
                dataSourceItemRepository, crudExecutorService, new ObjectMapper(),
                new VectorFeatureGate(new AppEditionProvider(env)));
    }

    private static Map<String, Object> vectorSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", "cloned-docs");
        snapshot.put("sourceType", "INLINE");
        snapshot.put("organizationId", "org-1");
        snapshot.put("columnOrder", List.of(
                Map.of("field", "title", "order", 0),
                Map.of("field", "embedding", "order", 1)));
        snapshot.put("mappingSpec", Map.of(
                "title", Map.of("path", "data.title", "type", "text", "structure", "SCALAR"),
                "embedding", Map.of("path", "data.embedding", "type", "vector", "structure", "SCALAR",
                        "display", Map.of("dimension", 1536))));
        return snapshot;
    }

    @Test
    @DisplayName("managed cloud strips the vector column from BOTH mappingSpec and columnOrder; the rest of the clone survives")
    void cloudStripsVectorFromSpecAndColumnOrder() {
        when(dataSourceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = controller("cloud").createFromSnapshot(vectorSnapshot(), "tenant-1", "org-1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        ArgumentCaptor<DataSource> captor = ArgumentCaptor.forClass(DataSource.class);
        verify(dataSourceRepository).save(captor.capture());
        DataSource saved = captor.getValue();
        assertThat(saved.mappingSpec().keySet()).containsExactly("title");
        assertThat(saved.columnOrder())
                .extracting(entry -> entry.get("field"))
                .containsExactly("title");
    }

    @Test
    @DisplayName("self-hosted CE clones the vector column untouched")
    void ceKeepsVectorColumn() {
        when(dataSourceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = controller("ce").createFromSnapshot(vectorSnapshot(), "tenant-1", "org-1");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        ArgumentCaptor<DataSource> captor = ArgumentCaptor.forClass(DataSource.class);
        verify(dataSourceRepository).save(captor.capture());
        DataSource saved = captor.getValue();
        assertThat(saved.mappingSpec().keySet()).containsExactlyInAnyOrder("title", "embedding");
        assertThat(saved.columnOrder()).hasSize(2);
    }
}
