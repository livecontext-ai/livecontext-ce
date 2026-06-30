package com.apimarketplace.datasource.crud.dto;

import com.apimarketplace.datasource.crud.domain.WhereCondition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for WhereConditionDto.
 */
@DisplayName("WhereConditionDto")
class WhereConditionDtoTest {

    @Test
    @DisplayName("toDomain should convert DTO to domain object")
    void toDomainShouldConvertCorrectly() {
        WhereConditionDto dto = new WhereConditionDto("name", "=", "Alice");

        WhereCondition domain = dto.toDomain();

        assertThat(domain.column()).isEqualTo("name");
        assertThat(domain.operator()).isEqualTo("=");
        assertThat(domain.value()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("fromDomain should convert domain object to DTO")
    void fromDomainShouldConvertCorrectly() {
        WhereCondition domain = new WhereCondition("age", ">", 18);

        WhereConditionDto dto = WhereConditionDto.fromDomain(domain);

        assertThat(dto.column()).isEqualTo("age");
        assertThat(dto.operator()).isEqualTo(">");
        assertThat(dto.value()).isEqualTo(18);
    }

    @Test
    @DisplayName("Round-trip conversion should preserve data")
    void roundTripShouldPreserveData() {
        WhereConditionDto original = new WhereConditionDto("status", "!=", "deleted");

        WhereCondition domain = original.toDomain();
        WhereConditionDto roundTripped = WhereConditionDto.fromDomain(domain);

        assertThat(roundTripped.column()).isEqualTo(original.column());
        assertThat(roundTripped.operator()).isEqualTo(original.operator());
        assertThat(roundTripped.value()).isEqualTo(original.value());
    }

    @Test
    @DisplayName("Should handle null value")
    void shouldHandleNullValue() {
        WhereConditionDto dto = new WhereConditionDto("name", "IS NULL", null);

        WhereCondition domain = dto.toDomain();

        assertThat(domain.value()).isNull();
    }
}
