package com.apimarketplace.agent.domain;

import jakarta.persistence.Column;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AgentExecutionEntity mapping")
class AgentExecutionEntityMappingTest {

    @Test
    @DisplayName("distinctTools maps to Postgres text[] instead of JSON")
    void distinctToolsMapsToTextArray() throws NoSuchFieldException {
        Field field = AgentExecutionEntity.class.getDeclaredField("distinctTools");

        Column column = field.getAnnotation(Column.class);
        JdbcTypeCode jdbcTypeCode = field.getAnnotation(JdbcTypeCode.class);

        assertThat(field.getType()).isEqualTo(String[].class);
        assertThat(AgentExecutionEntity.class).hasAnnotation(DynamicUpdate.class);
        assertThat(jdbcTypeCode).isNotNull();
        assertThat(column).isNotNull();
        assertThat(column.columnDefinition()).isEqualTo("text[]");
    }
}
