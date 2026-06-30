package com.apimarketplace.datasource.crud.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the read/write classification used by the user-facing CRUD write gate.
 */
@DisplayName("CrudOperation.isWrite")
class CrudOperationTest {

    @Test
    @DisplayName("READ_ROW is the only non-write operation")
    void readRowIsNotWrite() {
        assertThat(CrudOperation.READ_ROW.isWrite()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = CrudOperation.class,
            names = {"CREATE_ROW", "CREATE_COLUMN", "UPDATE_ROW", "DELETE_ROW"})
    @DisplayName("Every row/column mutation is a write")
    void mutationsAreWrites(CrudOperation op) {
        assertThat(op.isWrite()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(CrudOperation.class)
    @DisplayName("isWrite is the strict complement of READ_ROW for every enum constant")
    void isWriteIsComplementOfRead(CrudOperation op) {
        assertThat(op.isWrite()).isEqualTo(op != CrudOperation.READ_ROW);
    }
}
