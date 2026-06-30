package com.apimarketplace.catalog.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiToolRedisConfigEntity.
 *
 * ApiToolRedisConfigEntity represents Redis protocol configuration for API tools.
 */
@DisplayName("ApiToolRedisConfigEntity")
class ApiToolRedisConfigEntityTest {

    // ========================================================================
    // Constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("should create entity with default constructor")
        void shouldCreateEntityWithDefaultConstructor() {
            // Act
            ApiToolRedisConfigEntity entity = new ApiToolRedisConfigEntity();

            // Assert
            assertNotNull(entity);
            assertNull(entity.getId());
            assertNull(entity.getApiToolId());
            assertNull(entity.getHost());
            assertNull(entity.getPort());
            assertNull(entity.getDbIndex());
            assertNull(entity.getChannels());
            assertNull(entity.getUseTls());
            assertNull(entity.getUsername());
            assertNull(entity.getPassword());
            assertNull(entity.getCreatedAt());
            assertNull(entity.getUpdatedAt());
        }
    }

    // ========================================================================
    // Getter/Setter tests
    // ========================================================================

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("should set and get id correctly")
        void shouldSetAndGetIdCorrectly() {
            // Arrange
            ApiToolRedisConfigEntity entity = new ApiToolRedisConfigEntity();
            UUID id = UUID.randomUUID();

            // Act
            entity.setId(id);

            // Assert
            assertEquals(id, entity.getId());
        }

        @Test
        @DisplayName("should set and get apiToolId correctly")
        void shouldSetAndGetApiToolIdCorrectly() {
            // Arrange
            ApiToolRedisConfigEntity entity = new ApiToolRedisConfigEntity();
            UUID apiToolId = UUID.randomUUID();

            // Act
            entity.setApiToolId(apiToolId);

            // Assert
            assertEquals(apiToolId, entity.getApiToolId());
        }

        @Test
        @DisplayName("should set and get host correctly")
        void shouldSetAndGetHostCorrectly() {
            // Arrange
            ApiToolRedisConfigEntity entity = new ApiToolRedisConfigEntity();
            String host = "redis.example.com";

            // Act
            entity.setHost(host);

            // Assert
            assertEquals(host, entity.getHost());
        }

        @Test
        @DisplayName("should set and get port correctly")
        void shouldSetAndGetPortCorrectly() {
            // Arrange
            ApiToolRedisConfigEntity entity = new ApiToolRedisConfigEntity();
            Integer port = 6379;

            // Act
            entity.setPort(port);

            // Assert
            assertEquals(port, entity.getPort());
        }

        @Test
        @DisplayName("should set and get dbIndex correctly")
        void shouldSetAndGetDbIndexCorrectly() {
            // Arrange
            ApiToolRedisConfigEntity entity = new ApiToolRedisConfigEntity();
            Integer dbIndex = 0;

            // Act
            entity.setDbIndex(dbIndex);

            // Assert
            assertEquals(dbIndex, entity.getDbIndex());
        }

        @Test
        @DisplayName("should set and get channels correctly")
        void shouldSetAndGetChannelsCorrectly() {
            // Arrange
            ApiToolRedisConfigEntity entity = new ApiToolRedisConfigEntity();
            String channels = "channel1,channel2,channel3";

            // Act
            entity.setChannels(channels);

            // Assert
            assertEquals(channels, entity.getChannels());
        }

        @Test
        @DisplayName("should set and get useTls correctly")
        void shouldSetAndGetUseTlsCorrectly() {
            // Arrange
            ApiToolRedisConfigEntity entity = new ApiToolRedisConfigEntity();

            // Act & Assert
            entity.setUseTls(true);
            assertTrue(entity.getUseTls());

            entity.setUseTls(false);
            assertFalse(entity.getUseTls());
        }

        @Test
        @DisplayName("should set and get username correctly")
        void shouldSetAndGetUsernameCorrectly() {
            // Arrange
            ApiToolRedisConfigEntity entity = new ApiToolRedisConfigEntity();
            String username = "redis-user";

            // Act
            entity.setUsername(username);

            // Assert
            assertEquals(username, entity.getUsername());
        }

        @Test
        @DisplayName("should set and get password correctly")
        void shouldSetAndGetPasswordCorrectly() {
            // Arrange
            ApiToolRedisConfigEntity entity = new ApiToolRedisConfigEntity();
            String password = "redis-secret";

            // Act
            entity.setPassword(password);

            // Assert
            assertEquals(password, entity.getPassword());
        }

        @Test
        @DisplayName("should set and get createdAt correctly")
        void shouldSetAndGetCreatedAtCorrectly() {
            // Arrange
            ApiToolRedisConfigEntity entity = new ApiToolRedisConfigEntity();
            Long createdAt = System.currentTimeMillis();

            // Act
            entity.setCreatedAt(createdAt);

            // Assert
            assertEquals(createdAt, entity.getCreatedAt());
        }

        @Test
        @DisplayName("should set and get updatedAt correctly")
        void shouldSetAndGetUpdatedAtCorrectly() {
            // Arrange
            ApiToolRedisConfigEntity entity = new ApiToolRedisConfigEntity();
            Long updatedAt = System.currentTimeMillis();

            // Act
            entity.setUpdatedAt(updatedAt);

            // Assert
            assertEquals(updatedAt, entity.getUpdatedAt());
        }
    }

    // ========================================================================
    // Null value tests
    // ========================================================================

    @Nested
    @DisplayName("Null Values")
    class NullValueTests {

        @Test
        @DisplayName("should handle null values in all setters")
        void shouldHandleNullValuesInAllSetters() {
            // Arrange
            ApiToolRedisConfigEntity entity = new ApiToolRedisConfigEntity();
            entity.setId(UUID.randomUUID());
            entity.setHost("localhost");
            entity.setPort(6379);
            entity.setUseTls(true);

            // Act
            entity.setId(null);
            entity.setHost(null);
            entity.setPort(null);
            entity.setUseTls(null);

            // Assert
            assertNull(entity.getId());
            assertNull(entity.getHost());
            assertNull(entity.getPort());
            assertNull(entity.getUseTls());
        }
    }

    // ========================================================================
    // Full entity configuration test
    // ========================================================================

    @Nested
    @DisplayName("Full Configuration")
    class FullConfigurationTests {

        @Test
        @DisplayName("should configure complete Redis entity")
        void shouldConfigureCompleteRedisEntity() {
            // Arrange
            ApiToolRedisConfigEntity entity = new ApiToolRedisConfigEntity();
            UUID id = UUID.randomUUID();
            UUID apiToolId = UUID.randomUUID();
            String host = "redis-cluster.example.com";
            Integer port = 6380;
            Integer dbIndex = 5;
            String channels = "events,notifications";
            Boolean useTls = true;
            String username = "app-user";
            String password = "secure-password";
            Long createdAt = 1700000000000L;
            Long updatedAt = 1700000001000L;

            // Act
            entity.setId(id);
            entity.setApiToolId(apiToolId);
            entity.setHost(host);
            entity.setPort(port);
            entity.setDbIndex(dbIndex);
            entity.setChannels(channels);
            entity.setUseTls(useTls);
            entity.setUsername(username);
            entity.setPassword(password);
            entity.setCreatedAt(createdAt);
            entity.setUpdatedAt(updatedAt);

            // Assert
            assertEquals(id, entity.getId());
            assertEquals(apiToolId, entity.getApiToolId());
            assertEquals(host, entity.getHost());
            assertEquals(port, entity.getPort());
            assertEquals(dbIndex, entity.getDbIndex());
            assertEquals(channels, entity.getChannels());
            assertEquals(useTls, entity.getUseTls());
            assertEquals(username, entity.getUsername());
            assertEquals(password, entity.getPassword());
            assertEquals(createdAt, entity.getCreatedAt());
            assertEquals(updatedAt, entity.getUpdatedAt());
        }
    }
}
