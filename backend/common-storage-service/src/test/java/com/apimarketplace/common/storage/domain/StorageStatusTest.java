package com.apimarketplace.common.storage.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StorageStatus Enum Tests")
class StorageStatusTest {

    @Test
    @DisplayName("should have exactly 3 values")
    void shouldHaveExactly3Values() {
        assertThat(StorageStatus.values()).hasSize(3);
    }

    @ParameterizedTest
    @EnumSource(StorageStatus.class)
    @DisplayName("should convert to and from name")
    void shouldConvertToAndFromName(StorageStatus status) {
        String name = status.name();
        StorageStatus result = StorageStatus.valueOf(name);

        assertThat(result).isEqualTo(status);
    }

    @Test
    @DisplayName("should contain ACTIVE status")
    void shouldContainActive() {
        assertThat(StorageStatus.valueOf("ACTIVE")).isEqualTo(StorageStatus.ACTIVE);
    }

    @Test
    @DisplayName("should contain ARCHIVED status")
    void shouldContainArchived() {
        assertThat(StorageStatus.valueOf("ARCHIVED")).isEqualTo(StorageStatus.ARCHIVED);
    }

    @Test
    @DisplayName("should contain DELETED status")
    void shouldContainDeleted() {
        assertThat(StorageStatus.valueOf("DELETED")).isEqualTo(StorageStatus.DELETED);
    }
}
