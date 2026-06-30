package com.apimarketplace.catalog.domain.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiResponse and its nested records.
 *
 * Tests record construction, accessors, equals, and hashCode.
 */
@DisplayName("ApiResponse Tests")
class ApiResponseTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ═══════════════════════════════════════════════════════════════════════════
    // ApiResponse TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ApiResponse")
    class ApiResponseTests {

        @Test
        @DisplayName("Should create record with all fields")
        void shouldCreateWithAllFields() {
            UUID id = UUID.randomUUID();
            UUID categoryId = UUID.randomUUID();
            UUID subcategoryId = UUID.randomUUID();
            Long createdAt = System.currentTimeMillis();
            Long updatedAt = System.currentTimeMillis();

            ApiResponse response = new ApiResponse(
                id, "My API", "my-api", "API description", "https://api.example.com",
                categoryId, "Finance", subcategoryId, "Payments",
                true, false, createdAt, updatedAt, "user123", Collections.emptyList(),
                "/health", "public", true, "bearer", "Authorization", "Bearer token",
                "freemium", "active", true
            );

            assertEquals(id, response.id());
            assertEquals("My API", response.apiName());
            assertEquals("my-api", response.apiSlug());
            assertEquals("API description", response.description());
            assertEquals("https://api.example.com", response.baseUrl());
            assertEquals(categoryId, response.categoryId());
            assertEquals("Finance", response.categoryName());
            assertEquals(subcategoryId, response.subcategoryId());
            assertEquals("Payments", response.subcategoryName());
            assertTrue(response.isActive());
            assertFalse(response.isLocal());
            assertEquals(createdAt, response.createdAt());
            assertEquals(updatedAt, response.updatedAt());
            assertEquals("user123", response.createdBy());
            assertNotNull(response.tools());
            assertEquals("/health", response.healthcheckEndpoint());
            assertEquals("public", response.visibility());
            assertTrue(response.isPublic());
            assertEquals("bearer", response.authType());
            assertEquals("Authorization", response.authHeaderName());
            assertEquals("Bearer token", response.authHeaderValue());
            assertEquals("freemium", response.pricingModel());
            assertEquals("active", response.status());
            assertTrue(response.platformCredentialMissing());
        }

        @Test
        @DisplayName("Should allow null fields")
        void shouldAllowNullFields() {
            ApiResponse response = new ApiResponse(
                null, null, null, null, null,
                null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null
            );

            assertNotNull(response);
            assertNull(response.id());
            assertNull(response.apiName());
        }

        @Test
        @DisplayName("Should implement equals correctly")
        void shouldImplementEquals() {
            UUID id = UUID.randomUUID();
            ApiResponse response1 = new ApiResponse(
                id, "API", "api", null, null,
                null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null
            );
            ApiResponse response2 = new ApiResponse(
                id, "API", "api", null, null,
                null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null
            );

            assertEquals(response1, response2);
            assertEquals(response1.hashCode(), response2.hashCode());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ToolResponse TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ToolResponse")
    class ToolResponseTests {

        @Test
        @DisplayName("Should create ToolResponse with all fields")
        void shouldCreateWithAllFields() throws Exception {
            UUID id = UUID.randomUUID();
            JsonNode runtimeMetadata = objectMapper.readTree("{\"timeout\": 30}");
            Long createdAt = System.currentTimeMillis();
            Long updatedAt = System.currentTimeMillis();

            ApiResponse.ToolResponse tool = new ApiResponse.ToolResponse(
                id, "Get Users", "Retrieves user list", "/users", "GET", "REST",
                runtimeMetadata, null, null, null, null, null,
                true, createdAt, updatedAt, Collections.emptyList(), Collections.emptyList(),
                "active", null
            );

            assertEquals(id, tool.id());
            assertEquals("Get Users", tool.name());
            assertEquals("Retrieves user list", tool.description());
            assertEquals("/users", tool.endpoint());
            assertEquals("GET", tool.method());
            assertEquals("REST", tool.protocol());
            assertEquals(runtimeMetadata, tool.runtimeMetadata());
            assertTrue(tool.isActive());
            assertEquals(createdAt, tool.createdAt());
            assertEquals("active", tool.status());
        }

        @Test
        @DisplayName("Should allow all null config fields")
        void shouldAllowNullConfigFields() {
            ApiResponse.ToolResponse tool = new ApiResponse.ToolResponse(
                UUID.randomUUID(), "Tool", null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null
            );

            assertNull(tool.sqlConfig());
            assertNull(tool.amqpConfig());
            assertNull(tool.kafkaConfig());
            assertNull(tool.mqttConfig());
            assertNull(tool.redisConfig());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ToolCategoryInfo TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ToolCategoryInfo")
    class ToolCategoryInfoTests {

        @Test
        @DisplayName("Should create ToolCategoryInfo with all fields")
        void shouldCreateWithAllFields() {
            ApiResponse.ToolCategoryInfo info = new ApiResponse.ToolCategoryInfo(
                "cat-123", "Data Processing", "data-processing",
                "Tools for data processing", "data-icon", "#4CAF50",
                1, true, 1000L, 2000L
            );

            assertEquals("cat-123", info.id());
            assertEquals("Data Processing", info.name());
            assertEquals("data-processing", info.slug());
            assertEquals("Tools for data processing", info.description());
            assertEquals("data-icon", info.icon());
            assertEquals("#4CAF50", info.color());
            assertEquals(1, info.sortOrder());
            assertTrue(info.isActive());
            assertEquals(1000L, info.createdAt());
            assertEquals(2000L, info.updatedAt());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SqlConfigResponse TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SqlConfigResponse")
    class SqlConfigResponseTests {

        @Test
        @DisplayName("Should create SqlConfigResponse with all fields")
        void shouldCreateWithAllFields() {
            ApiResponse.SqlConfigResponse config = new ApiResponse.SqlConfigResponse(
                "postgresql", "SELECT * FROM users WHERE id = :id",
                "public", "users", "single", "{\"id\": \"userId\"}"
            );

            assertEquals("postgresql", config.dialect());
            assertEquals("SELECT * FROM users WHERE id = :id", config.queryTemplate());
            assertEquals("public", config.defaultSchema());
            assertEquals("users", config.defaultTable());
            assertEquals("single", config.resultMode());
            assertEquals("{\"id\": \"userId\"}", config.parameterMapping());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AmqpConfigResponse TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AmqpConfigResponse")
    class AmqpConfigResponseTests {

        @Test
        @DisplayName("Should create AmqpConfigResponse with all fields")
        void shouldCreateWithAllFields() {
            ApiResponse.AmqpConfigResponse config = new ApiResponse.AmqpConfigResponse(
                "amqp://localhost:5672", "/vhost", "my-exchange", "my-queue",
                "routing.key", 10, "MANUAL", true, "{}"
            );

            assertEquals("amqp://localhost:5672", config.brokerUrl());
            assertEquals("/vhost", config.virtualHost());
            assertEquals("my-exchange", config.exchangeName());
            assertEquals("my-queue", config.queueName());
            assertEquals("routing.key", config.routingKey());
            assertEquals(10, config.prefetchCount());
            assertEquals("MANUAL", config.ackMode());
            assertTrue(config.sslEnabled());
            assertEquals("{}", config.options());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // KafkaConfigResponse TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("KafkaConfigResponse")
    class KafkaConfigResponseTests {

        @Test
        @DisplayName("Should create KafkaConfigResponse with all fields")
        void shouldCreateWithAllFields() {
            ApiResponse.KafkaConfigResponse config = new ApiResponse.KafkaConfigResponse(
                "localhost:9092,localhost:9093", "my-topic", "consumer-group-1",
                "SASL_SSL", "PLAIN", "kafka-user", true
            );

            assertEquals("localhost:9092,localhost:9093", config.brokers());
            assertEquals("my-topic", config.topic());
            assertEquals("consumer-group-1", config.consumerGroup());
            assertEquals("SASL_SSL", config.securityProtocol());
            assertEquals("PLAIN", config.saslMechanism());
            assertEquals("kafka-user", config.saslUsername());
            assertTrue(config.sslEnabled());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MqttConfigResponse TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("MqttConfigResponse")
    class MqttConfigResponseTests {

        @Test
        @DisplayName("Should create MqttConfigResponse with all fields")
        void shouldCreateWithAllFields() {
            ApiResponse.MqttConfigResponse config = new ApiResponse.MqttConfigResponse(
                "tcp://localhost:1883", "sensors/+/data", 1, true,
                "client-123", "mqtt-user", false
            );

            assertEquals("tcp://localhost:1883", config.brokerUrl());
            assertEquals("sensors/+/data", config.topics());
            assertEquals(1, config.qos());
            assertTrue(config.retain());
            assertEquals("client-123", config.clientId());
            assertEquals("mqtt-user", config.username());
            assertFalse(config.useTls());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RedisConfigResponse TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("RedisConfigResponse")
    class RedisConfigResponseTests {

        @Test
        @DisplayName("Should create RedisConfigResponse with all fields")
        void shouldCreateWithAllFields() {
            ApiResponse.RedisConfigResponse config = new ApiResponse.RedisConfigResponse(
                "redis.example.com", 6379, 0, "channel1,channel2",
                true, "redis-user"
            );

            assertEquals("redis.example.com", config.host());
            assertEquals(6379, config.port());
            assertEquals(0, config.dbIndex());
            assertEquals("channel1,channel2", config.channels());
            assertTrue(config.useTls());
            assertEquals("redis-user", config.username());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ParameterResponse TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ParameterResponse")
    class ParameterResponseTests {

        @Test
        @DisplayName("Should create ParameterResponse with all fields")
        void shouldCreateWithAllFields() {
            UUID id = UUID.randomUUID();

            ApiResponse.ParameterResponse param = new ApiResponse.ParameterResponse(
                id, "userId", "string", "The user identifier",
                true, null, "user-123", "path", null
            );

            assertEquals(id, param.id());
            assertEquals("userId", param.name());
            assertEquals("string", param.type());
            assertEquals("The user identifier", param.description());
            assertTrue(param.required());
            assertNull(param.defaultValue());
            assertEquals("user-123", param.exampleValue());
            assertEquals("path", param.parameterType());
            assertNull(param.allowedValues());
        }

        @Test
        @DisplayName("Should handle optional parameter")
        void shouldHandleOptionalParameter() {
            ApiResponse.ParameterResponse param = new ApiResponse.ParameterResponse(
                UUID.randomUUID(), "limit", "integer", "Max results",
                false, "10", "25", "query", null
            );

            assertFalse(param.required());
            assertEquals("10", param.defaultValue());
        }

        @Test
        @DisplayName("Should carry allowedValues for closed-enum params (drives orchestrator-side dropdown)")
        void shouldCarryAllowedValues() {
            ApiResponse.ParameterResponse param = new ApiResponse.ParameterResponse(
                UUID.randomUUID(), "model", "string", "Model ID",
                true, "gpt-4o", null, "body",
                java.util.List.of("gpt-4o", "gpt-4o-mini", "gpt-4.1")
            );

            assertEquals("gpt-4o", param.defaultValue());
            assertEquals(3, param.allowedValues().size());
            assertTrue(param.allowedValues().contains("gpt-4o-mini"));
        }
    }
}
