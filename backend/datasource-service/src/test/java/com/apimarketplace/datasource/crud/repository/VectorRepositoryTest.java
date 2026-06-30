package com.apimarketplace.datasource.crud.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

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
}
