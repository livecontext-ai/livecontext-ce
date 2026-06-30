package com.apimarketplace.catalog.dto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ToolUpdateDto class.
 *
 * ToolUpdateDto contains updatable properties for tool modifications.
 */
@DisplayName("ToolUpdateDto")
class ToolUpdateDtoTest {

    private ToolUpdateDto dto;

    @BeforeEach
    void setUp() {
        dto = new ToolUpdateDto();
    }

    // ========================================================================
    // Constructor tests
    // ========================================================================

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        @DisplayName("should create empty DTO")
        void shouldCreateEmptyDto() {
            ToolUpdateDto newDto = new ToolUpdateDto();

            assertNotNull(newDto);
            assertNull(newDto.getName());
            assertNull(newDto.getDescription());
            assertNull(newDto.getMethod());
        }
    }

    // ========================================================================
    // Basic property tests
    // ========================================================================

    @Nested
    @DisplayName("Basic properties")
    class BasicPropertyTests {

        @Test
        @DisplayName("should set and get name")
        void shouldSetAndGetName() {
            dto.setName("Test Tool");

            assertEquals("Test Tool", dto.getName());
        }

        @Test
        @DisplayName("should set and get description")
        void shouldSetAndGetDescription() {
            dto.setDescription("A test tool description");

            assertEquals("A test tool description", dto.getDescription());
        }

        @Test
        @DisplayName("should set and get method")
        void shouldSetAndGetMethod() {
            dto.setMethod("POST");

            assertEquals("POST", dto.getMethod());
        }

        @Test
        @DisplayName("should set and get endpoint")
        void shouldSetAndGetEndpoint() {
            dto.setEndpoint("/api/v1/test");

            assertEquals("/api/v1/test", dto.getEndpoint());
        }

        @Test
        @DisplayName("should set and get protocol")
        void shouldSetAndGetProtocol() {
            dto.setProtocol("HTTPS");

            assertEquals("HTTPS", dto.getProtocol());
        }

        @Test
        @DisplayName("should set and get status")
        void shouldSetAndGetStatus() {
            dto.setStatus("active");

            assertEquals("active", dto.getStatus());
        }

        @Test
        @DisplayName("should set and get isActive")
        void shouldSetAndGetIsActive() {
            dto.setIsActive(true);

            assertTrue(dto.getIsActive());
        }
    }

    // ========================================================================
    // Configuration property tests
    // ========================================================================

    @Nested
    @DisplayName("Configuration properties")
    class ConfigurationPropertyTests {

        @Test
        @DisplayName("should set and get pricing")
        void shouldSetAndGetPricing() {
            dto.setPricing("free");

            assertEquals("free", dto.getPricing());
        }

        @Test
        @DisplayName("should set and get rateLimit")
        void shouldSetAndGetRateLimit() {
            dto.setRateLimit(100);

            assertEquals(100, dto.getRateLimit());
        }

        @Test
        @DisplayName("should set and get version")
        void shouldSetAndGetVersion() {
            dto.setVersion("1.0.0");

            assertEquals("1.0.0", dto.getVersion());
        }

        @Test
        @DisplayName("should set and get testStatus")
        void shouldSetAndGetTestStatus() {
            dto.setTestStatus("passed");

            assertEquals("passed", dto.getTestStatus());
        }
    }

    // ========================================================================
    // Parameter tests
    // ========================================================================

    @Nested
    @DisplayName("Parameters")
    class ParameterTests {

        @Test
        @DisplayName("should set and get pathParameters")
        void shouldSetAndGetPathParameters() {
            List<Map<String, Object>> params = List.of(
                    Map.of("name", "id", "type", "string")
            );

            dto.setPathParameters(params);

            assertEquals(1, dto.getPathParameters().size());
            assertEquals("id", dto.getPathParameters().get(0).get("name"));
        }

        @Test
        @DisplayName("should set and get queryParameters")
        void shouldSetAndGetQueryParameters() {
            List<Map<String, Object>> params = List.of(
                    Map.of("name", "page", "type", "integer"),
                    Map.of("name", "limit", "type", "integer")
            );

            dto.setQueryParameters(params);

            assertEquals(2, dto.getQueryParameters().size());
        }

        @Test
        @DisplayName("should set and get headers")
        void shouldSetAndGetHeaders() {
            List<Map<String, Object>> headers = List.of(
                    Map.of("name", "Authorization", "required", true)
            );

            dto.setHeaders(headers);

            assertEquals(1, dto.getHeaders().size());
        }

        @Test
        @DisplayName("should set and get bodyParams")
        void shouldSetAndGetBodyParams() {
            List<Map<String, Object>> params = List.of(
                    Map.of("name", "data", "type", "object")
            );

            dto.setBodyParams(params);

            assertEquals(1, dto.getBodyParams().size());
        }
    }

    // ========================================================================
    // Schema tests
    // ========================================================================

    @Nested
    @DisplayName("Schemas")
    class SchemaTests {

        @Test
        @DisplayName("should set and get bodySchema")
        void shouldSetAndGetBodySchema() {
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of("name", Map.of("type", "string"))
            );

            dto.setBodySchema(schema);

            assertNotNull(dto.getBodySchema());
            assertEquals("object", dto.getBodySchema().get("type"));
        }

        @Test
        @DisplayName("should set and get response")
        void shouldSetAndGetResponse() {
            Map<String, Object> response = Map.of(
                    "200", Map.of("description", "Success")
            );

            dto.setResponse(response);

            assertNotNull(dto.getResponse());
        }
    }

    // ========================================================================
    // Config tests
    // ========================================================================

    @Nested
    @DisplayName("Config objects")
    class ConfigTests {

        @Test
        @DisplayName("should set and get sqlConfig")
        void shouldSetAndGetSqlConfig() {
            Map<String, Object> config = Map.of("database", "postgres", "query", "SELECT *");

            dto.setSqlConfig(config);

            assertNotNull(dto.getSqlConfig());
            assertEquals("postgres", dto.getSqlConfig().get("database"));
        }

        @Test
        @DisplayName("should set and get amqpConfig")
        void shouldSetAndGetAmqpConfig() {
            Map<String, Object> config = Map.of("exchange", "test-exchange");

            dto.setAmqpConfig(config);

            assertNotNull(dto.getAmqpConfig());
        }

        @Test
        @DisplayName("should set and get kafkaConfig")
        void shouldSetAndGetKafkaConfig() {
            Map<String, Object> config = Map.of("topic", "test-topic");

            dto.setKafkaConfig(config);

            assertNotNull(dto.getKafkaConfig());
        }

        @Test
        @DisplayName("should set and get mqttConfig")
        void shouldSetAndGetMqttConfig() {
            Map<String, Object> config = Map.of("broker", "tcp://localhost:1883");

            dto.setMqttConfig(config);

            assertNotNull(dto.getMqttConfig());
        }

        @Test
        @DisplayName("should set and get redisConfig")
        void shouldSetAndGetRedisConfig() {
            Map<String, Object> config = Map.of("host", "localhost", "port", 6379);

            dto.setRedisConfig(config);

            assertNotNull(dto.getRedisConfig());
        }

        @Test
        @DisplayName("should set and get runtimeMetadata")
        void shouldSetAndGetRuntimeMetadata() {
            Map<String, Object> metadata = Map.of("timeout", 30000, "retries", 3);

            dto.setRuntimeMetadata(metadata);

            assertNotNull(dto.getRuntimeMetadata());
            assertEquals(30000, dto.getRuntimeMetadata().get("timeout"));
        }
    }

    // ========================================================================
    // toString tests
    // ========================================================================

    @Nested
    @DisplayName("toString()")
    class ToStringTests {

        @Test
        @DisplayName("should include all properties in string representation")
        void shouldIncludeAllPropertiesInString() {
            dto.setName("Test");
            dto.setDescription("Description");
            dto.setMethod("GET");
            dto.setEndpoint("/api");
            dto.setProtocol("HTTPS");
            dto.setStatus("active");
            dto.setIsActive(true);
            dto.setPricing("free");
            dto.setRateLimit(100);

            String str = dto.toString();

            assertTrue(str.contains("name='Test'"));
            assertTrue(str.contains("description='Description'"));
            assertTrue(str.contains("method='GET'"));
            assertTrue(str.contains("endpoint='/api'"));
            assertTrue(str.contains("protocol='HTTPS'"));
            assertTrue(str.contains("status='active'"));
            assertTrue(str.contains("isActive=true"));
            assertTrue(str.contains("pricing='free'"));
            assertTrue(str.contains("rateLimit=100"));
        }

        @Test
        @DisplayName("should handle null values in toString")
        void shouldHandleNullValuesInToString() {
            String str = dto.toString();

            assertTrue(str.contains("name='null'"));
            assertTrue(str.contains("method='null'"));
        }
    }
}
