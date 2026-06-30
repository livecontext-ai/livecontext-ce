package com.apimarketplace.catalog.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiToolAmqpConfigEntity class.
 *
 * Tests AMQP configuration entity getters, setters, and default values.
 */
@DisplayName("ApiToolAmqpConfigEntity Tests")
class ApiToolAmqpConfigEntityTest {

    // ═══════════════════════════════════════════════════════════════════════════
    // CONSTRUCTOR TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("Should create entity with default constructor")
        void shouldCreateWithDefaultConstructor() {
            ApiToolAmqpConfigEntity entity = new ApiToolAmqpConfigEntity();

            assertNotNull(entity);
            assertNull(entity.getId());
            assertNull(entity.getApiToolId());
            assertNull(entity.getBrokerUrl());
            assertNull(entity.getVirtualHost());
            assertNull(entity.getExchangeName());
            assertNull(entity.getQueueName());
            assertNull(entity.getRoutingKey());
            assertNull(entity.getPrefetchCount());
            assertNull(entity.getAckMode());
            assertNull(entity.getSslEnabled());
            assertNull(entity.getOptions());
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
            ApiToolAmqpConfigEntity entity = new ApiToolAmqpConfigEntity();
            UUID id = UUID.randomUUID();

            entity.setId(id);

            assertEquals(id, entity.getId());
        }

        @Test
        @DisplayName("Should set and get apiToolId")
        void shouldSetAndGetApiToolId() {
            ApiToolAmqpConfigEntity entity = new ApiToolAmqpConfigEntity();
            UUID apiToolId = UUID.randomUUID();

            entity.setApiToolId(apiToolId);

            assertEquals(apiToolId, entity.getApiToolId());
        }

        @Test
        @DisplayName("Should set and get brokerUrl")
        void shouldSetAndGetBrokerUrl() {
            ApiToolAmqpConfigEntity entity = new ApiToolAmqpConfigEntity();
            String brokerUrl = "amqp://localhost:5672";

            entity.setBrokerUrl(brokerUrl);

            assertEquals(brokerUrl, entity.getBrokerUrl());
        }

        @Test
        @DisplayName("Should set and get virtualHost")
        void shouldSetAndGetVirtualHost() {
            ApiToolAmqpConfigEntity entity = new ApiToolAmqpConfigEntity();
            String virtualHost = "/production";

            entity.setVirtualHost(virtualHost);

            assertEquals(virtualHost, entity.getVirtualHost());
        }

        @Test
        @DisplayName("Should set and get exchangeName")
        void shouldSetAndGetExchangeName() {
            ApiToolAmqpConfigEntity entity = new ApiToolAmqpConfigEntity();
            String exchangeName = "my-exchange";

            entity.setExchangeName(exchangeName);

            assertEquals(exchangeName, entity.getExchangeName());
        }

        @Test
        @DisplayName("Should set and get queueName")
        void shouldSetAndGetQueueName() {
            ApiToolAmqpConfigEntity entity = new ApiToolAmqpConfigEntity();
            String queueName = "my-queue";

            entity.setQueueName(queueName);

            assertEquals(queueName, entity.getQueueName());
        }

        @Test
        @DisplayName("Should set and get routingKey")
        void shouldSetAndGetRoutingKey() {
            ApiToolAmqpConfigEntity entity = new ApiToolAmqpConfigEntity();
            String routingKey = "my.routing.key";

            entity.setRoutingKey(routingKey);

            assertEquals(routingKey, entity.getRoutingKey());
        }

        @Test
        @DisplayName("Should set and get prefetchCount")
        void shouldSetAndGetPrefetchCount() {
            ApiToolAmqpConfigEntity entity = new ApiToolAmqpConfigEntity();
            Integer prefetchCount = 10;

            entity.setPrefetchCount(prefetchCount);

            assertEquals(prefetchCount, entity.getPrefetchCount());
        }

        @Test
        @DisplayName("Should set and get ackMode")
        void shouldSetAndGetAckMode() {
            ApiToolAmqpConfigEntity entity = new ApiToolAmqpConfigEntity();
            String ackMode = "MANUAL";

            entity.setAckMode(ackMode);

            assertEquals(ackMode, entity.getAckMode());
        }

        @Test
        @DisplayName("Should set and get sslEnabled")
        void shouldSetAndGetSslEnabled() {
            ApiToolAmqpConfigEntity entity = new ApiToolAmqpConfigEntity();

            entity.setSslEnabled(true);

            assertTrue(entity.getSslEnabled());
        }

        @Test
        @DisplayName("Should set and get options")
        void shouldSetAndGetOptions() {
            ApiToolAmqpConfigEntity entity = new ApiToolAmqpConfigEntity();
            String options = "{\"durable\": true, \"autoDelete\": false}";

            entity.setOptions(options);

            assertEquals(options, entity.getOptions());
        }

        @Test
        @DisplayName("Should set and get createdAt")
        void shouldSetAndGetCreatedAt() {
            ApiToolAmqpConfigEntity entity = new ApiToolAmqpConfigEntity();
            Long createdAt = 1700000000000L;

            entity.setCreatedAt(createdAt);

            assertEquals(createdAt, entity.getCreatedAt());
        }

        @Test
        @DisplayName("Should set and get updatedAt")
        void shouldSetAndGetUpdatedAt() {
            ApiToolAmqpConfigEntity entity = new ApiToolAmqpConfigEntity();
            Long updatedAt = 1700000000000L;

            entity.setUpdatedAt(updatedAt);

            assertEquals(updatedAt, entity.getUpdatedAt());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EDGE CASES
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("Should handle AMQPS URL")
        void shouldHandleAmqpsUrl() {
            ApiToolAmqpConfigEntity entity = new ApiToolAmqpConfigEntity();
            String amqpsUrl = "amqps://secure-broker.example.com:5671";

            entity.setBrokerUrl(amqpsUrl);

            assertEquals(amqpsUrl, entity.getBrokerUrl());
        }

        @Test
        @DisplayName("Should handle default virtual host")
        void shouldHandleDefaultVirtualHost() {
            ApiToolAmqpConfigEntity entity = new ApiToolAmqpConfigEntity();

            entity.setVirtualHost("/");

            assertEquals("/", entity.getVirtualHost());
        }

        @Test
        @DisplayName("Should handle wildcard routing key")
        void shouldHandleWildcardRoutingKey() {
            ApiToolAmqpConfigEntity entity = new ApiToolAmqpConfigEntity();

            entity.setRoutingKey("orders.*.created");
            assertEquals("orders.*.created", entity.getRoutingKey());

            entity.setRoutingKey("logs.#");
            assertEquals("logs.#", entity.getRoutingKey());
        }

        @Test
        @DisplayName("Should handle zero prefetch count")
        void shouldHandleZeroPrefetchCount() {
            ApiToolAmqpConfigEntity entity = new ApiToolAmqpConfigEntity();

            entity.setPrefetchCount(0);

            assertEquals(0, entity.getPrefetchCount());
        }

        @Test
        @DisplayName("Should handle different ack modes")
        void shouldHandleDifferentAckModes() {
            ApiToolAmqpConfigEntity entity = new ApiToolAmqpConfigEntity();

            entity.setAckMode("AUTO");
            assertEquals("AUTO", entity.getAckMode());

            entity.setAckMode("MANUAL");
            assertEquals("MANUAL", entity.getAckMode());

            entity.setAckMode("NONE");
            assertEquals("NONE", entity.getAckMode());
        }

        @Test
        @DisplayName("Should handle SSL disabled")
        void shouldHandleSslDisabled() {
            ApiToolAmqpConfigEntity entity = new ApiToolAmqpConfigEntity();

            entity.setSslEnabled(false);

            assertFalse(entity.getSslEnabled());
        }

        @Test
        @DisplayName("Should handle null SSL setting")
        void shouldHandleNullSslSetting() {
            ApiToolAmqpConfigEntity entity = new ApiToolAmqpConfigEntity();
            entity.setSslEnabled(true);

            entity.setSslEnabled(null);

            assertNull(entity.getSslEnabled());
        }

        @Test
        @DisplayName("Should handle empty options JSON")
        void shouldHandleEmptyOptionsJson() {
            ApiToolAmqpConfigEntity entity = new ApiToolAmqpConfigEntity();

            entity.setOptions("{}");

            assertEquals("{}", entity.getOptions());
        }

        @Test
        @DisplayName("Should handle complex options JSON")
        void shouldHandleComplexOptionsJson() {
            ApiToolAmqpConfigEntity entity = new ApiToolAmqpConfigEntity();
            String complexOptions = "{\"durable\": true, \"arguments\": {\"x-message-ttl\": 60000}}";

            entity.setOptions(complexOptions);

            assertEquals(complexOptions, entity.getOptions());
        }
    }
}
