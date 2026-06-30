package com.apimarketplace.catalog.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiToolKafkaConfigEntity.
 *
 * ApiToolKafkaConfigEntity represents Kafka protocol configuration for API tools.
 */
@DisplayName("ApiToolKafkaConfigEntity")
class ApiToolKafkaConfigEntityTest {

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
            ApiToolKafkaConfigEntity entity = new ApiToolKafkaConfigEntity();

            // Assert
            assertNotNull(entity);
            assertNull(entity.getId());
            assertNull(entity.getApiToolId());
            assertNull(entity.getBrokers());
            assertNull(entity.getTopic());
            assertNull(entity.getConsumerGroup());
            assertNull(entity.getSecurityProtocol());
            assertNull(entity.getSaslMechanism());
            assertNull(entity.getSaslUsername());
            assertNull(entity.getSaslPassword());
            assertNull(entity.getSslEnabled());
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
            ApiToolKafkaConfigEntity entity = new ApiToolKafkaConfigEntity();
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
            ApiToolKafkaConfigEntity entity = new ApiToolKafkaConfigEntity();
            UUID apiToolId = UUID.randomUUID();

            // Act
            entity.setApiToolId(apiToolId);

            // Assert
            assertEquals(apiToolId, entity.getApiToolId());
        }

        @Test
        @DisplayName("should set and get brokers correctly")
        void shouldSetAndGetBrokersCorrectly() {
            // Arrange
            ApiToolKafkaConfigEntity entity = new ApiToolKafkaConfigEntity();
            String brokers = "broker1:9092,broker2:9092,broker3:9092";

            // Act
            entity.setBrokers(brokers);

            // Assert
            assertEquals(brokers, entity.getBrokers());
        }

        @Test
        @DisplayName("should set and get topic correctly")
        void shouldSetAndGetTopicCorrectly() {
            // Arrange
            ApiToolKafkaConfigEntity entity = new ApiToolKafkaConfigEntity();
            String topic = "my-events-topic";

            // Act
            entity.setTopic(topic);

            // Assert
            assertEquals(topic, entity.getTopic());
        }

        @Test
        @DisplayName("should set and get consumerGroup correctly")
        void shouldSetAndGetConsumerGroupCorrectly() {
            // Arrange
            ApiToolKafkaConfigEntity entity = new ApiToolKafkaConfigEntity();
            String consumerGroup = "my-consumer-group";

            // Act
            entity.setConsumerGroup(consumerGroup);

            // Assert
            assertEquals(consumerGroup, entity.getConsumerGroup());
        }

        @Test
        @DisplayName("should set and get securityProtocol correctly")
        void shouldSetAndGetSecurityProtocolCorrectly() {
            // Arrange
            ApiToolKafkaConfigEntity entity = new ApiToolKafkaConfigEntity();
            String securityProtocol = "SASL_SSL";

            // Act
            entity.setSecurityProtocol(securityProtocol);

            // Assert
            assertEquals(securityProtocol, entity.getSecurityProtocol());
        }

        @Test
        @DisplayName("should set and get saslMechanism correctly")
        void shouldSetAndGetSaslMechanismCorrectly() {
            // Arrange
            ApiToolKafkaConfigEntity entity = new ApiToolKafkaConfigEntity();
            String saslMechanism = "SCRAM-SHA-256";

            // Act
            entity.setSaslMechanism(saslMechanism);

            // Assert
            assertEquals(saslMechanism, entity.getSaslMechanism());
        }

        @Test
        @DisplayName("should set and get saslUsername correctly")
        void shouldSetAndGetSaslUsernameCorrectly() {
            // Arrange
            ApiToolKafkaConfigEntity entity = new ApiToolKafkaConfigEntity();
            String saslUsername = "kafka-user";

            // Act
            entity.setSaslUsername(saslUsername);

            // Assert
            assertEquals(saslUsername, entity.getSaslUsername());
        }

        @Test
        @DisplayName("should set and get saslPassword correctly")
        void shouldSetAndGetSaslPasswordCorrectly() {
            // Arrange
            ApiToolKafkaConfigEntity entity = new ApiToolKafkaConfigEntity();
            String saslPassword = "secret-password";

            // Act
            entity.setSaslPassword(saslPassword);

            // Assert
            assertEquals(saslPassword, entity.getSaslPassword());
        }

        @Test
        @DisplayName("should set and get sslEnabled correctly")
        void shouldSetAndGetSslEnabledCorrectly() {
            // Arrange
            ApiToolKafkaConfigEntity entity = new ApiToolKafkaConfigEntity();

            // Act & Assert
            entity.setSslEnabled(true);
            assertTrue(entity.getSslEnabled());

            entity.setSslEnabled(false);
            assertFalse(entity.getSslEnabled());
        }

        @Test
        @DisplayName("should set and get createdAt correctly")
        void shouldSetAndGetCreatedAtCorrectly() {
            // Arrange
            ApiToolKafkaConfigEntity entity = new ApiToolKafkaConfigEntity();
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
            ApiToolKafkaConfigEntity entity = new ApiToolKafkaConfigEntity();
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
            ApiToolKafkaConfigEntity entity = new ApiToolKafkaConfigEntity();
            entity.setId(UUID.randomUUID());
            entity.setBrokers("broker:9092");
            entity.setTopic("topic");
            entity.setSslEnabled(true);

            // Act
            entity.setId(null);
            entity.setBrokers(null);
            entity.setTopic(null);
            entity.setSslEnabled(null);

            // Assert
            assertNull(entity.getId());
            assertNull(entity.getBrokers());
            assertNull(entity.getTopic());
            assertNull(entity.getSslEnabled());
        }
    }

    // ========================================================================
    // Full entity configuration test
    // ========================================================================

    @Nested
    @DisplayName("Full Configuration")
    class FullConfigurationTests {

        @Test
        @DisplayName("should configure complete Kafka entity")
        void shouldConfigureCompleteKafkaEntity() {
            // Arrange
            ApiToolKafkaConfigEntity entity = new ApiToolKafkaConfigEntity();
            UUID id = UUID.randomUUID();
            UUID apiToolId = UUID.randomUUID();
            String brokers = "kafka1.example.com:9092,kafka2.example.com:9092";
            String topic = "events";
            String consumerGroup = "event-processors";
            String securityProtocol = "SASL_SSL";
            String saslMechanism = "PLAIN";
            String saslUsername = "api-user";
            String saslPassword = "api-secret";
            Boolean sslEnabled = true;
            Long createdAt = 1700000000000L;
            Long updatedAt = 1700000001000L;

            // Act
            entity.setId(id);
            entity.setApiToolId(apiToolId);
            entity.setBrokers(brokers);
            entity.setTopic(topic);
            entity.setConsumerGroup(consumerGroup);
            entity.setSecurityProtocol(securityProtocol);
            entity.setSaslMechanism(saslMechanism);
            entity.setSaslUsername(saslUsername);
            entity.setSaslPassword(saslPassword);
            entity.setSslEnabled(sslEnabled);
            entity.setCreatedAt(createdAt);
            entity.setUpdatedAt(updatedAt);

            // Assert
            assertEquals(id, entity.getId());
            assertEquals(apiToolId, entity.getApiToolId());
            assertEquals(brokers, entity.getBrokers());
            assertEquals(topic, entity.getTopic());
            assertEquals(consumerGroup, entity.getConsumerGroup());
            assertEquals(securityProtocol, entity.getSecurityProtocol());
            assertEquals(saslMechanism, entity.getSaslMechanism());
            assertEquals(saslUsername, entity.getSaslUsername());
            assertEquals(saslPassword, entity.getSaslPassword());
            assertEquals(sslEnabled, entity.getSslEnabled());
            assertEquals(createdAt, entity.getCreatedAt());
            assertEquals(updatedAt, entity.getUpdatedAt());
        }
    }
}
