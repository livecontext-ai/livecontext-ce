package com.apimarketplace.common.storage.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("QuotaStatus Enum Tests")
class QuotaStatusTest {

    @Test
    @DisplayName("should have exactly 3 values")
    void shouldHaveExactly3Values() {
        assertThat(QuotaStatus.values()).hasSize(3);
    }

    @ParameterizedTest
    @EnumSource(QuotaStatus.class)
    @DisplayName("should convert to and from name")
    void shouldConvertToAndFromName(QuotaStatus status) {
        String name = status.name();
        QuotaStatus result = QuotaStatus.valueOf(name);

        assertThat(result).isEqualTo(status);
    }

    @Test
    @DisplayName("should contain OK status")
    void shouldContainOk() {
        assertThat(QuotaStatus.valueOf("OK")).isEqualTo(QuotaStatus.OK);
    }

    @Test
    @DisplayName("should contain SOFT_LIMIT_REACHED status")
    void shouldContainSoftLimitReached() {
        assertThat(QuotaStatus.valueOf("SOFT_LIMIT_REACHED")).isEqualTo(QuotaStatus.SOFT_LIMIT_REACHED);
    }

    @Test
    @DisplayName("should contain HARD_LIMIT_REACHED status")
    void shouldContainHardLimitReached() {
        assertThat(QuotaStatus.valueOf("HARD_LIMIT_REACHED")).isEqualTo(QuotaStatus.HARD_LIMIT_REACHED);
    }
}
