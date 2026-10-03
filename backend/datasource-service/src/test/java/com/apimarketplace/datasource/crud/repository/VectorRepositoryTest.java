package com.apimarketplace.datasource.crud.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for VectorRepository transaction configuration.
 *
 * Verifies that vector insert methods use default REQUIRED propagation
 * so they participate in the caller's transaction (atomic create row + vector insert).
 */
@DisplayName("VectorRepository")
class VectorRepositoryTest {

    @Test
    @DisplayName("insertVector should use REQUIRED (default) propagation for atomicity")
    void insertVectorShouldUseRequiredPropagation() throws NoSuchMethodException {
        Method method = VectorRepository.class.getMethod(
            "insertVector", Long.class, String.class, Long.class, String.class, float[].class
        );

        Transactional annotation = method.getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        // Default propagation is REQUIRED
        assertThat(annotation.propagation())
            .as("insertVector must use REQUIRED propagation for atomic row+vector inserts")
            .isEqualTo(Propagation.REQUIRED);
    }

    @Test
    @DisplayName("insertVectorBatch should use REQUIRED (default) propagation for atomicity")
    void insertVectorBatchShouldUseRequiredPropagation() throws NoSuchMethodException {
        Method method = VectorRepository.class.getMethod(
            "insertVectorBatch", Long.class, String.class, java.util.List.class
        );

        Transactional annotation = method.getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation())
            .as("insertVectorBatch must use REQUIRED propagation for atomic row+vector inserts")
            .isEqualTo(Propagation.REQUIRED);
    }

    @Test
    @DisplayName("insertVector must NOT use MANDATORY propagation (breaks parallel execution)")
    void insertVectorMustNotUseMandatoryPropagation() throws NoSuchMethodException {
        Method method = VectorRepository.class.getMethod(
            "insertVector", Long.class, String.class, Long.class, String.class, float[].class
        );

        Transactional annotation = method.getAnnotation(Transactional.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.propagation())
            .as("MANDATORY propagation fails when called from threads without active transaction")
            .isNotEqualTo(Propagation.MANDATORY);
    }

    @Test
    @DisplayName("similaritySearch: joins i.data_sensitivity from data_source_items "
        + "(LC-066 re-audit item 2 - a RESTRICTED matched row must be classifiable by the caller, "
        + "the same way executeReadRow already ORs in each row's own stored tag)")
    void similaritySearchSelectsItemDataSensitivity() {
        NamedParameterJdbcTemplate jdbcTemplate = mock(NamedParameterJdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString(), any(MapSqlParameterSource.class))).thenReturn(List.of());
        VectorRepository repository = new VectorRepository(jdbcTemplate);

        repository.similaritySearch(1L, "tenant-1", "embedding",
                new float[]{0.1f, 0.2f}, 2, "cosine", 5, null, null, null);

        org.mockito.ArgumentCaptor<String> sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), any(MapSqlParameterSource.class));
        assertThat(sqlCaptor.getValue())
            .as("pre-fix this SELECT never joined data_sensitivity, so CrudExecutorService."
                + "executeSimilaritySearch could only tag the result from the calling context's "
                + "restricted flag, never from a RESTRICTED matched row itself")
            .contains("i.data_sensitivity");
    }
}
