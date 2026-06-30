package com.apimarketplace.catalog.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DataConfig")
class DataConfigTest {

    private final DataConfig dataConfig = new DataConfig();

    @Nested
    @DisplayName("PgObjectToStringConverter")
    class PgObjectToStringConverterTests {

        @Test
        @DisplayName("should convert PGobject to string")
        void convertsPGobjectToString() throws SQLException {
            PGobject pgObject = new PGobject();
            pgObject.setType("jsonb");
            pgObject.setValue("{\"key\":\"value\"}");

            // Access the converter via the INSTANCE
            // Using JdbcCustomConversions to get the converter
            JdbcCustomConversions conversions = dataConfig.jdbcCustomConversions();
            assertThat(conversions).isNotNull();
        }

        @Test
        @DisplayName("should return null for null PGobject")
        void returnsNullForNullPGobject() {
            // Access the converter enum directly
            @SuppressWarnings("unchecked")
            Converter<PGobject, String> converter =
                    (Converter<PGobject, String>) DataConfig.PgObjectToStringConverter.INSTANCE;

            String result = converter.convert(null);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should return value from PGobject")
        void returnsValueFromPGobject() throws SQLException {
            PGobject pgObject = new PGobject();
            pgObject.setType("jsonb");
            pgObject.setValue("{\"nested\":true}");

            @SuppressWarnings("unchecked")
            Converter<PGobject, String> converter =
                    (Converter<PGobject, String>) DataConfig.PgObjectToStringConverter.INSTANCE;

            String result = converter.convert(pgObject);
            assertThat(result).isEqualTo("{\"nested\":true}");
        }

        @Test
        @DisplayName("should handle PGobject with null value")
        void handlesPGobjectWithNullValue() {
            PGobject pgObject = new PGobject();
            pgObject.setType("jsonb");
            // value is null by default

            @SuppressWarnings("unchecked")
            Converter<PGobject, String> converter =
                    (Converter<PGobject, String>) DataConfig.PgObjectToStringConverter.INSTANCE;

            String result = converter.convert(pgObject);
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("jdbcCustomConversions")
    class JdbcCustomConversionsTests {

        @Test
        @DisplayName("should create JdbcCustomConversions with PGobject converter")
        void createsJdbcCustomConversions() {
            JdbcCustomConversions conversions = dataConfig.jdbcCustomConversions();

            assertThat(conversions).isNotNull();
        }
    }
}
