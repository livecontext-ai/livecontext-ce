package com.apimarketplace.catalog.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiToolMqttConfigEntity class.
 *
 * Tests MQTT configuration entity getters, setters, and default values.
 */
@DisplayName("ApiToolMqttConfigEntity Tests")
class ApiToolMqttConfigEntityTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRUCTOR TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create entity with default constructor")
        void shouldCreateWithDefaultConstructor() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();

            assertNotNull(entity);
            assertNull(entity.getId());
            assertNull(entity.getApiToolId());
            assertNull(entity.getBrokerUrl());
            assertNull(entity.getTopics());
            assertNull(entity.getQos());
            assertNull(entity.getRetain());
            assertNull(entity.getClientId());
            assertNull(entity.getUsername());
            assertNull(entity.getPassword());
            assertNull(entity.getUseTls());
            assertNull(entity.getCreatedAt());
            assertNull(entity.getUpdatedAt());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GETTER/SETTER TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("Should set and get id")
        void shouldSetAndGetId() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();
            UUID id = UUID.randomUUID();

            entity.setId(id);

            assertEquals(id, entity.getId());
        }

        @Test
        @DisplayName("Should set and get apiToolId")
        void shouldSetAndGetApiToolId() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();
            UUID apiToolId = UUID.randomUUID();

            entity.setApiToolId(apiToolId);

            assertEquals(apiToolId, entity.getApiToolId());
        }

        @Test
        @DisplayName("Should set and get brokerUrl")
        void shouldSetAndGetBrokerUrl() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();
            String brokerUrl = "tcp://localhost:1883";

            entity.setBrokerUrl(brokerUrl);

            assertEquals(brokerUrl, entity.getBrokerUrl());
        }

        @Test
        @DisplayName("Should set and get topics")
        void shouldSetAndGetTopics() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();
            String topics = "sensors/temperature,sensors/humidity";

            entity.setTopics(topics);

            assertEquals(topics, entity.getTopics());
        }

        @Test
        @DisplayName("Should set and get qos")
        void shouldSetAndGetQos() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();
            Integer qos = 1;

            entity.setQos(qos);

            assertEquals(qos, entity.getQos());
        }

        @Test
        @DisplayName("Should set and get retain")
        void shouldSetAndGetRetain() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();

            entity.setRetain(true);

            assertTrue(entity.getRetain());
        }

        @Test
        @DisplayName("Should set and get clientId")
        void shouldSetAndGetClientId() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();
            String clientId = "my-client-id";

            entity.setClientId(clientId);

            assertEquals(clientId, entity.getClientId());
        }

        @Test
        @DisplayName("Should set and get username")
        void shouldSetAndGetUsername() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();
            String username = "mqtt-user";

            entity.setUsername(username);

            assertEquals(username, entity.getUsername());
        }

        @Test
        @DisplayName("Should set and get password")
        void shouldSetAndGetPassword() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();
            String password = "secret-password";

            entity.setPassword(password);

            assertEquals(password, entity.getPassword());
        }

        @Test
        @DisplayName("Should set and get useTls")
        void shouldSetAndGetUseTls() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();

            entity.setUseTls(true);

            assertTrue(entity.getUseTls());
        }

        @Test
        @DisplayName("Should set and get createdAt")
        void shouldSetAndGetCreatedAt() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();
            Long createdAt = 1700000000000L;

            entity.setCreatedAt(createdAt);

            assertEquals(createdAt, entity.getCreatedAt());
        }

        @Test
        @DisplayName("Should set and get updatedAt")
        void shouldSetAndGetUpdatedAt() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();
            Long updatedAt = 1700000000000L;

            entity.setUpdatedAt(updatedAt);

            assertEquals(updatedAt, entity.getUpdatedAt());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // QOS LEVEL TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("QoS Level Tests")
    class QosLevelTests {

        @Test
        @DisplayName("Should handle QoS level 0 (At most once)")
        void shouldHandleQosLevel0() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();

            entity.setQos(0);

            assertEquals(0, entity.getQos());
        }

        @Test
        @DisplayName("Should handle QoS level 1 (At least once)")
        void shouldHandleQosLevel1() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();

            entity.setQos(1);

            assertEquals(1, entity.getQos());
        }

        @Test
        @DisplayName("Should handle QoS level 2 (Exactly once)")
        void shouldHandleQosLevel2() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();

            entity.setQos(2);

            assertEquals(2, entity.getQos());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE CASES
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle TLS broker URL")
        void shouldHandleTlsBrokerUrl() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();
            String tlsUrl = "ssl://broker.hivemq.com:8883";

            entity.setBrokerUrl(tlsUrl);

            assertEquals(tlsUrl, entity.getBrokerUrl());
        }

        @Test
        @DisplayName("Should handle WebSocket broker URL")
        void shouldHandleWebSocketBrokerUrl() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();
            String wsUrl = "ws://broker.example.com:8080/mqtt";

            entity.setBrokerUrl(wsUrl);

            assertEquals(wsUrl, entity.getBrokerUrl());
        }

        @Test
        @DisplayName("Should handle wildcard topics")
        void shouldHandleWildcardTopics() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();

            entity.setTopics("sensors/+/temperature");
            assertEquals("sensors/+/temperature", entity.getTopics());

            entity.setTopics("home/#");
            assertEquals("home/#", entity.getTopics());
        }

        @Test
        @DisplayName("Should handle multiple topics")
        void shouldHandleMultipleTopics() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();
            String multipleTopics = "topic1,topic2,topic3";

            entity.setTopics(multipleTopics);

            assertEquals(multipleTopics, entity.getTopics());
        }

        @Test
        @DisplayName("Should handle retain as false")
        void shouldHandleRetainFalse() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();

            entity.setRetain(false);

            assertFalse(entity.getRetain());
        }

        @Test
        @DisplayName("Should handle null retain")
        void shouldHandleNullRetain() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();
            entity.setRetain(true);

            entity.setRetain(null);

            assertNull(entity.getRetain());
        }

        @Test
        @DisplayName("Should handle TLS disabled")
        void shouldHandleTlsDisabled() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();

            entity.setUseTls(false);

            assertFalse(entity.getUseTls());
        }

        @Test
        @DisplayName("Should handle empty password for anonymous access")
        void shouldHandleEmptyPassword() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();

            entity.setUsername("");
            entity.setPassword("");

            assertEquals("", entity.getUsername());
            assertEquals("", entity.getPassword());
        }

        @Test
        @DisplayName("Should handle generated client ID")
        void shouldHandleGeneratedClientId() {
            ApiToolMqttConfigEntity entity = new ApiToolMqttConfigEntity();
            String generatedClientId = "client-" + UUID.randomUUID().toString();

            entity.setClientId(generatedClientId);

            assertEquals(generatedClientId, entity.getClientId());
        }
    }
}
