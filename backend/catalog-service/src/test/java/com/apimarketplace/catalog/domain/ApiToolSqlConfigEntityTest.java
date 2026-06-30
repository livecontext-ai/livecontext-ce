package com.apimarketplace.catalog.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiToolSqlConfigEntity class.
 *
 * ApiToolSqlConfigEntity represents SQL configuration for an API tool.
 */
@DisplayName("ApiToolSqlConfigEntity Tests")
class ApiToolSqlConfigEntityTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRUCTOR TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create entity with default constructor")
        void shouldCreateEntityWithDefaultConstructor() {
            // Act
            ApiToolSqlConfigEntity entity = new ApiToolSqlConfigEntity();

            // Assert
            assertNull(entity.getId());
            assertNull(entity.getApiToolId());
            assertNull(entity.getDialect());
            assertNull(entity.getQueryTemplate());
            assertNull(entity.getParameterMapping());
            assertNull(entity.getDefaultSchema());
            assertNull(entity.getDefaultTable());
            assertNull(entity.getResultMode());
            assertNull(entity.getCreatedAt());
            assertNull(entity.getUpdatedAt());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GETTERS AND SETTERS TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Getters and Setters Tests")
    class GettersSettersTests {

        @Test
        @DisplayName("Should set and get id")
        void shouldSetAndGetId() {
            // Arrange
            ApiToolSqlConfigEntity entity = new ApiToolSqlConfigEntity();
            UUID id = UUID.randomUUID();

            // Act
            entity.setId(id);

            // Assert
            assertEquals(id, entity.getId());
        }

        @Test
        @DisplayName("Should set and get apiToolId")
        void shouldSetAndGetApiToolId() {
            // Arrange
            ApiToolSqlConfigEntity entity = new ApiToolSqlConfigEntity();
            UUID apiToolId = UUID.randomUUID();

            // Act
            entity.setApiToolId(apiToolId);

            // Assert
            assertEquals(apiToolId, entity.getApiToolId());
        }

        @Test
        @DisplayName("Should set and get dialect")
        void shouldSetAndGetDialect() {
            // Arrange
            ApiToolSqlConfigEntity entity = new ApiToolSqlConfigEntity();

            // Act
            entity.setDialect("postgresql");

            // Assert
            assertEquals("postgresql", entity.getDialect());
        }

        @Test
        @DisplayName("Should set and get queryTemplate")
        void shouldSetAndGetQueryTemplate() {
            // Arrange
            ApiToolSqlConfigEntity entity = new ApiToolSqlConfigEntity();
            String query = "SELECT * FROM users WHERE id = :userId";

            // Act
            entity.setQueryTemplate(query);

            // Assert
            assertEquals(query, entity.getQueryTemplate());
        }

        @Test
        @DisplayName("Should set and get parameterMapping")
        void shouldSetAndGetParameterMapping() {
            // Arrange
            ApiToolSqlConfigEntity entity = new ApiToolSqlConfigEntity();
            String mapping = "{\"userId\": \"$.params.user_id\"}";

            // Act
            entity.setParameterMapping(mapping);

            // Assert
            assertEquals(mapping, entity.getParameterMapping());
        }

        @Test
        @DisplayName("Should set and get defaultSchema")
        void shouldSetAndGetDefaultSchema() {
            // Arrange
            ApiToolSqlConfigEntity entity = new ApiToolSqlConfigEntity();

            // Act
            entity.setDefaultSchema("public");

            // Assert
            assertEquals("public", entity.getDefaultSchema());
        }

        @Test
        @DisplayName("Should set and get defaultTable")
        void shouldSetAndGetDefaultTable() {
            // Arrange
            ApiToolSqlConfigEntity entity = new ApiToolSqlConfigEntity();

            // Act
            entity.setDefaultTable("users");

            // Assert
            assertEquals("users", entity.getDefaultTable());
        }

        @Test
        @DisplayName("Should set and get resultMode")
        void shouldSetAndGetResultMode() {
            // Arrange
            ApiToolSqlConfigEntity entity = new ApiToolSqlConfigEntity();

            // Act
            entity.setResultMode("single");

            // Assert
            assertEquals("single", entity.getResultMode());
        }

        @Test
        @DisplayName("Should set and get timestamps")
        void shouldSetAndGetTimestamps() {
            // Arrange
            ApiToolSqlConfigEntity entity = new ApiToolSqlConfigEntity();
            Long createdAt = 1704067200000L;
            Long updatedAt = 1704153600000L;

            // Act
            entity.setCreatedAt(createdAt);
            entity.setUpdatedAt(updatedAt);

            // Assert
            assertEquals(createdAt, entity.getCreatedAt());
            assertEquals(updatedAt, entity.getUpdatedAt());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // REAL-WORLD USAGE TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Real-World Usage Tests")
    class RealWorldUsageTests {

        @Test
        @DisplayName("Should configure PostgreSQL query")
        void shouldConfigurePostgresqlQuery() {
            // Arrange
            ApiToolSqlConfigEntity entity = new ApiToolSqlConfigEntity();

            // Act
            entity.setDialect("postgresql");
            entity.setDefaultSchema("public");
            entity.setDefaultTable("customers");
            entity.setQueryTemplate("SELECT * FROM customers WHERE status = :status");
            entity.setResultMode("multiple");

            // Assert
            assertEquals("postgresql", entity.getDialect());
            assertEquals("public", entity.getDefaultSchema());
            assertEquals("customers", entity.getDefaultTable());
            assertTrue(entity.getQueryTemplate().contains("SELECT"));
        }

        @Test
        @DisplayName("Should configure MySQL query")
        void shouldConfigureMysqlQuery() {
            // Arrange
            ApiToolSqlConfigEntity entity = new ApiToolSqlConfigEntity();

            // Act
            entity.setDialect("mysql");
            entity.setDefaultTable("orders");
            entity.setQueryTemplate("SELECT order_id, total FROM orders WHERE customer_id = ?");
            entity.setParameterMapping("{\"0\": \"$.customerId\"}");
            entity.setResultMode("single");

            // Assert
            assertEquals("mysql", entity.getDialect());
            assertEquals("orders", entity.getDefaultTable());
            assertEquals("single", entity.getResultMode());
        }

        @Test
        @DisplayName("Should support various SQL dialects")
        void shouldSupportVariousSqlDialects() {
            // Arrange
            ApiToolSqlConfigEntity postgres = new ApiToolSqlConfigEntity();
            ApiToolSqlConfigEntity mysql = new ApiToolSqlConfigEntity();
            ApiToolSqlConfigEntity mssql = new ApiToolSqlConfigEntity();
            ApiToolSqlConfigEntity oracle = new ApiToolSqlConfigEntity();

            // Act
            postgres.setDialect("postgresql");
            mysql.setDialect("mysql");
            mssql.setDialect("mssql");
            oracle.setDialect("oracle");

            // Assert
            assertEquals("postgresql", postgres.getDialect());
            assertEquals("mysql", mysql.getDialect());
            assertEquals("mssql", mssql.getDialect());
            assertEquals("oracle", oracle.getDialect());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NULL HANDLING TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Null Handling Tests")
    class NullHandlingTests {

        @Test
        @DisplayName("Should handle null values in setters")
        void shouldHandleNullValuesInSetters() {
            // Arrange
            ApiToolSqlConfigEntity entity = new ApiToolSqlConfigEntity();
            entity.setDialect("postgresql");
            entity.setQueryTemplate("SELECT 1");

            // Act
            entity.setDialect(null);
            entity.setQueryTemplate(null);

            // Assert
            assertNull(entity.getDialect());
            assertNull(entity.getQueryTemplate());
        }
    }
}
