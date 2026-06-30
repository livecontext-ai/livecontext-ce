package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiConfigurationConverter.
 *
 * Tests conversion from ApiConfigurationRequest DTO to JsonNode.
 */
@DisplayName("ApiConfigurationConverter Tests")
class ApiConfigurationConverterTest {

    private ObjectMapper objectMapper;
    private ApiConfigurationConverter converter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        converter = new ApiConfigurationConverter(objectMapper);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // toJsonNode() BASIC TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("toJsonNode() Basic Conversion")
    class ToJsonNodeBasicTests {

        @Test
        @DisplayName("Should convert basic info fields")
        void shouldConvertBasicInfoFields() {
            ApiConfigurationRequest.AuthorizationDto auth = new ApiConfigurationRequest.AuthorizationDto(
                "none", null, null, null
            );
            ApiConfigurationRequest.ApiConfigDto apiConfig = new ApiConfigurationRequest.ApiConfigDto(
                "https://api.example.com", "/health", auth, "public"
            );
            ApiConfigurationRequest.MonetizationConfigDto monetization = new ApiConfigurationRequest.MonetizationConfigDto(
                "free", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null
            );

            ApiConfigurationRequest request = new ApiConfigurationRequest(
                "My API", "API description", "Finance", "Finance category",
                "Payments", "Payments subcategory", "icon-url", "cat-123", "subcat-456",
                true, false, false, "api-icon", "my-api", null, null,
                null, apiConfig, monetization, Collections.emptyList()
            );

            JsonNode result = converter.toJsonNode(request);

            assertEquals("My API", result.get("apiName").asText());
            assertEquals("API description", result.get("apiDescription").asText());
            assertEquals("Finance", result.get("selectedCategory").asText());
            assertEquals("Finance category", result.get("categoryDescription").asText());
            assertEquals("Payments", result.get("selectedSubcategory").asText());
            assertEquals("Payments subcategory", result.get("subcategoryDescription").asText());
            assertEquals("icon-url", result.get("subcategoryIconUrl").asText());
            assertEquals("cat-123", result.get("categoryId").asText());
            assertEquals("subcat-456", result.get("subcategoryId").asText());
            assertTrue(result.get("isCustomCategory").asBoolean());
            assertFalse(result.get("isCustomSubcategory").asBoolean());
            assertFalse(result.get("isLocal").asBoolean());
            assertEquals("api-icon", result.get("iconSlug").asText());
        }

        @Test
        @DisplayName("Should handle null isLocal field")
        void shouldHandleNullIsLocalField() {
            ApiConfigurationRequest.AuthorizationDto auth = new ApiConfigurationRequest.AuthorizationDto(
                "none", null, null, null
            );
            ApiConfigurationRequest.ApiConfigDto apiConfig = new ApiConfigurationRequest.ApiConfigDto(
                "https://api.example.com", null, auth, "public"
            );
            ApiConfigurationRequest.MonetizationConfigDto monetization = new ApiConfigurationRequest.MonetizationConfigDto(
                "free", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null
            );

            ApiConfigurationRequest request = new ApiConfigurationRequest(
                "API", "Desc", "Cat", null, "Subcat", null, null, null, null,
                null, null, null, null, null, null, null, null, apiConfig, monetization, null
            );

            JsonNode result = converter.toJsonNode(request);

            assertFalse(result.get("isLocal").asBoolean());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // API CONFIG CONVERSION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("API Config Conversion")
    class ApiConfigConversionTests {

        @Test
        @DisplayName("Should convert API config with all fields")
        void shouldConvertApiConfigWithAllFields() {
            ApiConfigurationRequest.AuthorizationDto auth = new ApiConfigurationRequest.AuthorizationDto(
                "bearer", "Bearer token auth", "Authorization", "Bearer xxx"
            );
            ApiConfigurationRequest.ApiConfigDto apiConfig = new ApiConfigurationRequest.ApiConfigDto(
                "https://api.example.com", "/healthcheck", auth, "private"
            );
            ApiConfigurationRequest.MonetizationConfigDto monetization = new ApiConfigurationRequest.MonetizationConfigDto(
                "free", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null
            );

            ApiConfigurationRequest request = new ApiConfigurationRequest(
                "API", "Desc", "Cat", null, "Subcat", null, null, null, null,
                null, null, null, null, null, null, null, null, apiConfig, monetization, null
            );

            JsonNode result = converter.toJsonNode(request);
            JsonNode apiConfigNode = result.get("apiConfig");

            assertEquals("https://api.example.com", apiConfigNode.get("baseUrl").asText());
            assertEquals("/healthcheck", apiConfigNode.get("healthcheckEndpoint").asText());
            assertEquals("private", apiConfigNode.get("visibility").asText());

            JsonNode authNode = apiConfigNode.get("authorization");
            assertEquals("bearer", authNode.get("type").asText());
            assertEquals("Bearer token auth", authNode.get("description").asText());
            assertEquals("Authorization", authNode.get("headerName").asText());
            assertEquals("Bearer xxx", authNode.get("headerValue").asText());
        }

        @Test
        @DisplayName("Should handle API key authorization")
        void shouldHandleApiKeyAuthorization() {
            ApiConfigurationRequest.AuthorizationDto auth = new ApiConfigurationRequest.AuthorizationDto(
                "api_key", "API Key required", "X-API-Key", "secret-key"
            );
            ApiConfigurationRequest.ApiConfigDto apiConfig = new ApiConfigurationRequest.ApiConfigDto(
                "https://api.example.com", null, auth, "public"
            );
            ApiConfigurationRequest.MonetizationConfigDto monetization = new ApiConfigurationRequest.MonetizationConfigDto(
                "free", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null
            );

            ApiConfigurationRequest request = new ApiConfigurationRequest(
                "API", "Desc", "Cat", null, "Subcat", null, null, null, null,
                null, null, null, null, null, null, null, null, apiConfig, monetization, null
            );

            JsonNode result = converter.toJsonNode(request);
            JsonNode authNode = result.get("apiConfig").get("authorization");

            assertEquals("api_key", authNode.get("type").asText());
            assertEquals("X-API-Key", authNode.get("headerName").asText());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MONETIZATION CONVERSION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Monetization Conversion")
    class MonetizationConversionTests {

        @Test
        @DisplayName("Should convert freemium monetization")
        void shouldConvertFreemiumMonetization() {
            ApiConfigurationRequest.AuthorizationDto auth = new ApiConfigurationRequest.AuthorizationDto(
                "none", null, null, null
            );
            ApiConfigurationRequest.ApiConfigDto apiConfig = new ApiConfigurationRequest.ApiConfigDto(
                "https://api.example.com", null, auth, "public"
            );
            ApiConfigurationRequest.RateLimitDto rateLimit = new ApiConfigurationRequest.RateLimitDto(100, "minute");
            ApiConfigurationRequest.MonetizationConfigDto monetization = new ApiConfigurationRequest.MonetizationConfigDto(
                "freemium", List.of("freemium"), rateLimit, 1000, "monthly", null, null,
                50, 0.05, 100, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null
            );

            ApiConfigurationRequest request = new ApiConfigurationRequest(
                "API", "Desc", "Cat", null, "Subcat", null, null, null, null,
                null, null, null, null, null, null, null, null, apiConfig, monetization, null
            );

            JsonNode result = converter.toJsonNode(request);
            JsonNode monNode = result.get("monetization");

            assertEquals("freemium", monNode.get("pricing").asText());
            assertEquals(1000, monNode.get("freeRequestsPerUser").asInt());
            assertEquals("monthly", monNode.get("freeRequestsType").asText());
            assertEquals(50, monNode.get("uniformToolPrice").asInt());
            assertEquals(0.05, monNode.get("uniformToolPriceInDollars").asDouble());
            assertEquals(100, monNode.get("uniformCalls").asInt());

            JsonNode rateLimitNode = monNode.get("rateLimit");
            assertEquals(100, rateLimitNode.get("requests").asInt());
            assertEquals("minute", rateLimitNode.get("period").asText());
        }

        @Test
        @DisplayName("Should convert paid monetization with plans")
        void shouldConvertPaidMonetizationWithPlans() {
            ApiConfigurationRequest.AuthorizationDto auth = new ApiConfigurationRequest.AuthorizationDto(
                "none", null, null, null
            );
            ApiConfigurationRequest.ApiConfigDto apiConfig = new ApiConfigurationRequest.ApiConfigDto(
                "https://api.example.com", null, auth, "public"
            );
            Map<String, Boolean> selectedPlans = new HashMap<>();
            selectedPlans.put("basic", true);
            selectedPlans.put("pro", true);

            Map<String, List<String>> planTools = new HashMap<>();
            planTools.put("basic", List.of("tool1"));
            planTools.put("pro", List.of("tool1", "tool2"));

            ApiConfigurationRequest.MonetizationConfigDto monetization = new ApiConfigurationRequest.MonetizationConfigDto(
                "paid", List.of("paid"), null, null, null, null, null, null, null, null, null,
                selectedPlans, planTools,
                9.99, 19.99, 49.99, 99.99,
                100, 500, 2000, 10000,
                10, 50, 100, 500,
                "second", "second", "second", "second",
                0.01, 0.008, 0.005, 0.003,
                false, false, false, false
            );

            ApiConfigurationRequest request = new ApiConfigurationRequest(
                "API", "Desc", "Cat", null, "Subcat", null, null, null, null,
                null, null, null, null, null, null, null, null, apiConfig, monetization, null
            );

            JsonNode result = converter.toJsonNode(request);
            JsonNode monNode = result.get("monetization");

            assertEquals("paid", monNode.get("pricing").asText());
            assertEquals(9.99, monNode.get("priceBasic").asDouble());
            assertEquals(19.99, monNode.get("pricePro").asDouble());
            assertEquals(100, monNode.get("quotaBasic").asInt());
            assertEquals(10, monNode.get("rpsBasic").asInt());
            assertEquals("second", monNode.get("rpsPeriodBasic").asText());
            assertEquals(0.01, monNode.get("overusageCostBasic").asDouble());
        }

        @Test
        @DisplayName("Should convert tool pricing map")
        void shouldConvertToolPricingMap() {
            ApiConfigurationRequest.AuthorizationDto auth = new ApiConfigurationRequest.AuthorizationDto(
                "none", null, null, null
            );
            ApiConfigurationRequest.ApiConfigDto apiConfig = new ApiConfigurationRequest.ApiConfigDto(
                "https://api.example.com", null, auth, "public"
            );

            Map<String, ApiConfigurationRequest.ToolPricingDto> toolPricing = new HashMap<>();
            toolPricing.put("tool1", new ApiConfigurationRequest.ToolPricingDto("tool1", 1000, 0.05, "USD", 100));
            toolPricing.put("tool2", new ApiConfigurationRequest.ToolPricingDto("tool2", 2000, 0.10, "USD", 200));

            ApiConfigurationRequest.MonetizationConfigDto monetization = new ApiConfigurationRequest.MonetizationConfigDto(
                "freemium", null, null, null, null, null, null, null, null, null, toolPricing,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null
            );

            ApiConfigurationRequest request = new ApiConfigurationRequest(
                "API", "Desc", "Cat", null, "Subcat", null, null, null, null,
                null, null, null, null, null, null, null, null, apiConfig, monetization, null
            );

            JsonNode result = converter.toJsonNode(request);
            JsonNode toolPricingNode = result.get("monetization").get("toolPricing");

            assertEquals(1000, toolPricingNode.get("tool1").get("mauValue").asInt());
            assertEquals(0.05, toolPricingNode.get("tool1").get("price").asDouble());
            assertEquals("USD", toolPricingNode.get("tool1").get("currency").asText());
        }

        @Test
        @DisplayName("Should convert tool rate limits map")
        void shouldConvertToolRateLimitsMap() {
            ApiConfigurationRequest.AuthorizationDto auth = new ApiConfigurationRequest.AuthorizationDto(
                "none", null, null, null
            );
            ApiConfigurationRequest.ApiConfigDto apiConfig = new ApiConfigurationRequest.ApiConfigDto(
                "https://api.example.com", null, auth, "public"
            );

            Map<String, ApiConfigurationRequest.ToolRateLimitsDto> toolRateLimits = new HashMap<>();
            toolRateLimits.put("tool1", new ApiConfigurationRequest.ToolRateLimitsDto(50, "second"));
            toolRateLimits.put("tool2", new ApiConfigurationRequest.ToolRateLimitsDto(100, "minute"));

            ApiConfigurationRequest.MonetizationConfigDto monetization = new ApiConfigurationRequest.MonetizationConfigDto(
                "freemium", null, null, null, null, null, toolRateLimits, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null
            );

            ApiConfigurationRequest request = new ApiConfigurationRequest(
                "API", "Desc", "Cat", null, "Subcat", null, null, null, null,
                null, null, null, null, null, null, null, null, apiConfig, monetization, null
            );

            JsonNode result = converter.toJsonNode(request);
            JsonNode rateLimitsNode = result.get("monetization").get("toolRateLimits");

            assertEquals(50, rateLimitsNode.get("tool1").get("requests").asInt());
            assertEquals("second", rateLimitsNode.get("tool1").get("period").asText());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MCP TOOLS CONVERSION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("MCP Tools Conversion")
    class McpToolsConversionTests {

        @Test
        @DisplayName("Should convert MCP tools list")
        void shouldConvertMcpToolsList() throws Exception {
            ApiConfigurationRequest.AuthorizationDto auth = new ApiConfigurationRequest.AuthorizationDto(
                "none", null, null, null
            );
            ApiConfigurationRequest.ApiConfigDto apiConfig = new ApiConfigurationRequest.ApiConfigDto(
                "https://api.example.com", null, auth, "public"
            );
            ApiConfigurationRequest.MonetizationConfigDto monetization = new ApiConfigurationRequest.MonetizationConfigDto(
                "free", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null
            );

            JsonNode runtimeMetadata = objectMapper.readTree("{\"timeout\": 30}");
            ApiConfigurationRequest.McpToolDto tool = new ApiConfigurationRequest.McpToolDto(
                "tool-123", "Get Users", "Retrieves all users", "/users", "GET", "HTTP",
                runtimeMetadata, "Data Access", "Data access tools", "data-icon",
                "tool-name-123", false, false,
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, // executionSpec, outputSchema, executionMode
                null, null, null, null  // synthesis, pagination, nextHint, requiredScopes
            );

            ApiConfigurationRequest request = new ApiConfigurationRequest(
                "API", "Desc", "Cat", null, "Subcat", null, null, null, null,
                null, null, null, null, null, null, null, null, apiConfig, monetization, List.of(tool)
            );

            JsonNode result = converter.toJsonNode(request);
            JsonNode toolsNode = result.get("mcpTools");

            assertTrue(toolsNode.isArray());
            assertEquals(1, toolsNode.size());

            JsonNode toolNode = toolsNode.get(0);
            assertEquals("tool-123", toolNode.get("id").asText());
            assertEquals("Get Users", toolNode.get("name").asText());
            assertEquals("Retrieves all users", toolNode.get("description").asText());
            assertEquals("/users", toolNode.get("endpoint").asText());
            assertEquals("GET", toolNode.get("method").asText());
            assertEquals("HTTP", toolNode.get("protocol").asText());
            assertEquals("Data Access", toolNode.get("toolCategory").asText());
        }

        @Test
        @DisplayName("Should default protocol to HTTP when null")
        void shouldDefaultProtocolToHttpWhenNull() {
            ApiConfigurationRequest.AuthorizationDto auth = new ApiConfigurationRequest.AuthorizationDto(
                "none", null, null, null
            );
            ApiConfigurationRequest.ApiConfigDto apiConfig = new ApiConfigurationRequest.ApiConfigDto(
                "https://api.example.com", null, auth, "public"
            );
            ApiConfigurationRequest.MonetizationConfigDto monetization = new ApiConfigurationRequest.MonetizationConfigDto(
                "free", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null
            );

            ApiConfigurationRequest.McpToolDto tool = new ApiConfigurationRequest.McpToolDto(
                "tool-123", "Tool", "Description", "/endpoint", "POST", null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, // executionSpec, outputSchema, executionMode
                null, null, null, null  // synthesis, pagination, nextHint, requiredScopes
            );

            ApiConfigurationRequest request = new ApiConfigurationRequest(
                "API", "Desc", "Cat", null, "Subcat", null, null, null, null,
                null, null, null, null, null, null, null, null, apiConfig, monetization, List.of(tool)
            );

            JsonNode result = converter.toJsonNode(request);
            JsonNode toolNode = result.get("mcpTools").get(0);

            assertEquals("HTTP", toolNode.get("protocol").asText());
        }

        @Test
        @DisplayName("Should handle null MCP tools list")
        void shouldHandleNullMcpToolsList() {
            ApiConfigurationRequest.AuthorizationDto auth = new ApiConfigurationRequest.AuthorizationDto(
                "none", null, null, null
            );
            ApiConfigurationRequest.ApiConfigDto apiConfig = new ApiConfigurationRequest.ApiConfigDto(
                "https://api.example.com", null, auth, "public"
            );
            ApiConfigurationRequest.MonetizationConfigDto monetization = new ApiConfigurationRequest.MonetizationConfigDto(
                "free", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null
            );

            ApiConfigurationRequest request = new ApiConfigurationRequest(
                "API", "Desc", "Cat", null, "Subcat", null, null, null, null,
                null, null, null, null, null, null, null, null, apiConfig, monetization, null
            );

            JsonNode result = converter.toJsonNode(request);
            JsonNode toolsNode = result.get("mcpTools");

            assertTrue(toolsNode.isArray());
            assertEquals(0, toolsNode.size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PROTOCOL CONFIG CONVERSION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Protocol Config Conversion")
    class ProtocolConfigConversionTests {

        private ApiConfigurationRequest createRequestWithTool(ApiConfigurationRequest.McpToolDto tool) {
            ApiConfigurationRequest.AuthorizationDto auth = new ApiConfigurationRequest.AuthorizationDto(
                "none", null, null, null
            );
            ApiConfigurationRequest.ApiConfigDto apiConfig = new ApiConfigurationRequest.ApiConfigDto(
                "https://api.example.com", null, auth, "public"
            );
            ApiConfigurationRequest.MonetizationConfigDto monetization = new ApiConfigurationRequest.MonetizationConfigDto(
                "free", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null
            );
            return new ApiConfigurationRequest(
                "API", "Desc", "Cat", null, "Subcat", null, null, null, null,
                null, null, null, null, null, null, null, null, apiConfig, monetization, List.of(tool)
            );
        }

        @Test
        @DisplayName("Should convert SQL config")
        void shouldConvertSqlConfig() throws Exception {
            JsonNode params = objectMapper.readTree("{\"userId\": \"string\"}");
            ApiConfigurationRequest.SqlConfigDto sqlConfig = new ApiConfigurationRequest.SqlConfigDto(
                "postgresql", "SELECT * FROM users", "public", "users", "single", params
            );

            ApiConfigurationRequest.McpToolDto tool = new ApiConfigurationRequest.McpToolDto(
                "tool-1", "Tool", "Desc", "/sql", "POST", "SQL",
                null, null, null, null, null, null, null,
                null, null, null, null, null,
                null, sqlConfig, null, null, null, null,
                null, null, null, // executionSpec, outputSchema, executionMode
                null, null, null, null  // synthesis, pagination, nextHint, requiredScopes
            );

            JsonNode result = converter.toJsonNode(createRequestWithTool(tool));
            JsonNode sqlNode = result.get("mcpTools").get(0).get("sqlConfig");

            assertEquals("postgresql", sqlNode.get("dialect").asText());
            assertEquals("SELECT * FROM users", sqlNode.get("query").asText());
            assertEquals("public", sqlNode.get("defaultSchema").asText());
            assertEquals("users", sqlNode.get("defaultTable").asText());
            assertEquals("single", sqlNode.get("resultMode").asText());
        }

        @Test
        @DisplayName("Should convert AMQP config")
        void shouldConvertAmqpConfig() {
            ApiConfigurationRequest.AmqpConfigDto amqpConfig = new ApiConfigurationRequest.AmqpConfigDto(
                "amqp://localhost:5672", "/vhost", "my-exchange", "my-queue",
                "routing.key", 10, "MANUAL", true, "{}"
            );

            ApiConfigurationRequest.McpToolDto tool = new ApiConfigurationRequest.McpToolDto(
                "tool-1", "Tool", "Desc", "/amqp", "POST", "AMQP",
                null, null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, amqpConfig, null, null, null,
                null, null, null, // executionSpec, outputSchema, executionMode
                null, null, null, null  // synthesis, pagination, nextHint, requiredScopes
            );

            JsonNode result = converter.toJsonNode(createRequestWithTool(tool));
            JsonNode amqpNode = result.get("mcpTools").get(0).get("amqpConfig");

            assertEquals("amqp://localhost:5672", amqpNode.get("brokerUrl").asText());
            assertEquals("/vhost", amqpNode.get("virtualHost").asText());
            assertEquals("my-exchange", amqpNode.get("exchangeName").asText());
            assertEquals("my-queue", amqpNode.get("queueName").asText());
            assertEquals("routing.key", amqpNode.get("routingKey").asText());
            assertEquals(10, amqpNode.get("prefetchCount").asInt());
            assertEquals("MANUAL", amqpNode.get("ackMode").asText());
            assertTrue(amqpNode.get("sslEnabled").asBoolean());
        }

        @Test
        @DisplayName("Should convert Kafka config")
        void shouldConvertKafkaConfig() {
            ApiConfigurationRequest.KafkaConfigDto kafkaConfig = new ApiConfigurationRequest.KafkaConfigDto(
                "localhost:9092", "my-topic", "consumer-group",
                "SASL_SSL", "PLAIN", "kafka-user", "kafka-pass", true
            );

            ApiConfigurationRequest.McpToolDto tool = new ApiConfigurationRequest.McpToolDto(
                "tool-1", "Tool", "Desc", "/kafka", "POST", "KAFKA",
                null, null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, kafkaConfig, null, null,
                null, null, null, // executionSpec, outputSchema, executionMode
                null, null, null, null  // synthesis, pagination, nextHint, requiredScopes
            );

            JsonNode result = converter.toJsonNode(createRequestWithTool(tool));
            JsonNode kafkaNode = result.get("mcpTools").get(0).get("kafkaConfig");

            assertEquals("localhost:9092", kafkaNode.get("brokers").asText());
            assertEquals("my-topic", kafkaNode.get("topic").asText());
            assertEquals("consumer-group", kafkaNode.get("consumerGroup").asText());
            assertEquals("SASL_SSL", kafkaNode.get("securityProtocol").asText());
            assertTrue(kafkaNode.get("sslEnabled").asBoolean());
        }

        @Test
        @DisplayName("Should convert MQTT config")
        void shouldConvertMqttConfig() {
            ApiConfigurationRequest.MqttConfigDto mqttConfig = new ApiConfigurationRequest.MqttConfigDto(
                "tcp://localhost:1883", "sensors/+/data", 1, true,
                "client-123", "mqtt-user", "mqtt-pass", false
            );

            ApiConfigurationRequest.McpToolDto tool = new ApiConfigurationRequest.McpToolDto(
                "tool-1", "Tool", "Desc", "/mqtt", "POST", "MQTT",
                null, null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, mqttConfig, null,
                null, null, null, // executionSpec, outputSchema, executionMode
                null, null, null, null  // synthesis, pagination, nextHint, requiredScopes
            );

            JsonNode result = converter.toJsonNode(createRequestWithTool(tool));
            JsonNode mqttNode = result.get("mcpTools").get(0).get("mqttConfig");

            assertEquals("tcp://localhost:1883", mqttNode.get("brokerUrl").asText());
            assertEquals("sensors/+/data", mqttNode.get("topics").asText());
            assertEquals(1, mqttNode.get("qos").asInt());
            assertTrue(mqttNode.get("retain").asBoolean());
            assertEquals("client-123", mqttNode.get("clientId").asText());
            assertFalse(mqttNode.get("useTls").asBoolean());
        }

        @Test
        @DisplayName("Should convert Redis config")
        void shouldConvertRedisConfig() {
            ApiConfigurationRequest.RedisConfigDto redisConfig = new ApiConfigurationRequest.RedisConfigDto(
                "redis.example.com", 6379, 0, "channel1,channel2",
                true, "redis-user", "redis-pass"
            );

            ApiConfigurationRequest.McpToolDto tool = new ApiConfigurationRequest.McpToolDto(
                "tool-1", "Tool", "Desc", "/redis", "POST", "REDIS",
                null, null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, redisConfig,
                null, null, null, // executionSpec, outputSchema, executionMode
                null, null, null, null  // synthesis, pagination, nextHint, requiredScopes
            );

            JsonNode result = converter.toJsonNode(createRequestWithTool(tool));
            JsonNode redisNode = result.get("mcpTools").get(0).get("redisConfig");

            assertEquals("redis.example.com", redisNode.get("host").asText());
            assertEquals(6379, redisNode.get("port").asInt());
            assertEquals(0, redisNode.get("dbIndex").asInt());
            assertEquals("channel1,channel2", redisNode.get("channels").asText());
            assertTrue(redisNode.get("useTls").asBoolean());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PARAMETERS CONVERSION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Parameters Conversion")
    class ParametersConversionTests {

        private ApiConfigurationRequest createRequestWithTool(ApiConfigurationRequest.McpToolDto tool) {
            ApiConfigurationRequest.AuthorizationDto auth = new ApiConfigurationRequest.AuthorizationDto(
                "none", null, null, null
            );
            ApiConfigurationRequest.ApiConfigDto apiConfig = new ApiConfigurationRequest.ApiConfigDto(
                "https://api.example.com", null, auth, "public"
            );
            ApiConfigurationRequest.MonetizationConfigDto monetization = new ApiConfigurationRequest.MonetizationConfigDto(
                "free", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null
            );
            return new ApiConfigurationRequest(
                "API", "Desc", "Cat", null, "Subcat", null, null, null, null,
                null, null, null, null, null, null, null, null, apiConfig, monetization, List.of(tool)
            );
        }

        @Test
        @DisplayName("Should convert headers")
        void shouldConvertHeaders() throws Exception {
            JsonNode extras = objectMapper.readTree("{\"description\": \"Custom header\"}");
            List<ApiConfigurationRequest.HeaderDto> headers = List.of(
                new ApiConfigurationRequest.HeaderDto("X-Custom-Header", "value", true, null, null, extras),
                new ApiConfigurationRequest.HeaderDto("X-Optional", "optional", false, null, null, null)
            );

            ApiConfigurationRequest.McpToolDto tool = new ApiConfigurationRequest.McpToolDto(
                "tool-1", "Tool", "Desc", "/endpoint", "GET", "HTTP",
                null, null, null, null, null, null, null,
                headers, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, // executionSpec, outputSchema, executionMode
                null, null, null, null  // synthesis, pagination, nextHint, requiredScopes
            );

            JsonNode result = converter.toJsonNode(createRequestWithTool(tool));
            JsonNode headersNode = result.get("mcpTools").get(0).get("headers");

            assertEquals(2, headersNode.size());
            assertEquals("X-Custom-Header", headersNode.get(0).get("name").asText());
            assertEquals("value", headersNode.get(0).get("value").asText());
            assertTrue(headersNode.get(0).get("required").asBoolean());
        }

        @Test
        @DisplayName("Should convert path parameters")
        void shouldConvertPathParameters() {
            List<ApiConfigurationRequest.PathParameterDto> pathParams = List.of(
                new ApiConfigurationRequest.PathParameterDto(
                    "userId", "string", true, "User ID", "user-123", null, null, null, null
                )
            );

            ApiConfigurationRequest.McpToolDto tool = new ApiConfigurationRequest.McpToolDto(
                "tool-1", "Tool", "Desc", "/users/{userId}", "GET", "HTTP",
                null, null, null, null, null, null, null,
                null, pathParams, null, null, null,
                null, null, null, null, null, null,
                null, null, null, // executionSpec, outputSchema, executionMode
                null, null, null, null  // synthesis, pagination, nextHint, requiredScopes
            );

            JsonNode result = converter.toJsonNode(createRequestWithTool(tool));
            JsonNode pathParamsNode = result.get("mcpTools").get(0).get("pathParameters");

            assertEquals(1, pathParamsNode.size());
            assertEquals("userId", pathParamsNode.get(0).get("name").asText());
            assertEquals("string", pathParamsNode.get(0).get("type").asText());
            assertTrue(pathParamsNode.get(0).get("required").asBoolean());
        }

        @Test
        @DisplayName("Should convert query parameters")
        void shouldConvertQueryParameters() {
            List<ApiConfigurationRequest.QueryParameterDto> queryParams = List.of(
                new ApiConfigurationRequest.QueryParameterDto(
                    "status", "string", false, "Filter by status",
                    "active", "active", List.of("active", "inactive"), null
                )
            );

            ApiConfigurationRequest.McpToolDto tool = new ApiConfigurationRequest.McpToolDto(
                "tool-1", "Tool", "Desc", "/users", "GET", "HTTP",
                null, null, null, null, null, null, null,
                null, null, queryParams, null, null,
                null, null, null, null, null, null,
                null, null, null, // executionSpec, outputSchema, executionMode
                null, null, null, null  // synthesis, pagination, nextHint, requiredScopes
            );

            JsonNode result = converter.toJsonNode(createRequestWithTool(tool));
            JsonNode queryParamsNode = result.get("mcpTools").get(0).get("queryParameters");

            assertEquals(1, queryParamsNode.size());
            assertEquals("status", queryParamsNode.get(0).get("name").asText());
            assertEquals("active", queryParamsNode.get(0).get("defaultValue").asText());
            assertEquals(2, queryParamsNode.get(0).get("allowedValues").size());
        }

        @Test
        @DisplayName("Should convert body parameters")
        void shouldConvertBodyParameters() {
            List<ApiConfigurationRequest.BodyParamDto> bodyParams = List.of(
                new ApiConfigurationRequest.BodyParamDto("email", "user@example.com", "string", null, "User email", null, null, null, null)
            );

            ApiConfigurationRequest.McpToolDto tool = new ApiConfigurationRequest.McpToolDto(
                "tool-1", "Tool", "Desc", "/users", "POST", "HTTP",
                null, null, null, null, null, null, null,
                null, null, null, bodyParams, null,
                null, null, null, null, null, null,
                null, null, null, // executionSpec, outputSchema, executionMode
                null, null, null, null  // synthesis, pagination, nextHint, requiredScopes
            );

            JsonNode result = converter.toJsonNode(createRequestWithTool(tool));
            JsonNode bodyParamsNode = result.get("mcpTools").get(0).get("bodyParams");

            assertEquals(1, bodyParamsNode.size());
            assertEquals("email", bodyParamsNode.get(0).get("name").asText());
            assertEquals("user@example.com", bodyParamsNode.get(0).get("value").asText());
            assertEquals("string", bodyParamsNode.get(0).get("type").asText());
        }

        @Test
        @DisplayName("Should extract isHidden from extras for query parameters")
        void shouldExtractIsHiddenFromExtras() throws Exception {
            JsonNode hiddenExtras = objectMapper.readTree("{\"hidden\": true}");
            List<ApiConfigurationRequest.QueryParameterDto> queryParams = List.of(
                new ApiConfigurationRequest.QueryParameterDto(
                    "api_key", "string", false, "API key (hidden)",
                    null, null, null, hiddenExtras
                )
            );

            ApiConfigurationRequest.McpToolDto tool = new ApiConfigurationRequest.McpToolDto(
                "tool-1", "Tool", "Desc", "/data", "GET", "HTTP",
                null, null, null, null, null, null, null,
                null, null, queryParams, null, null,
                null, null, null, null, null, null,
                null, null, null,
                null, null, null,
                null
            );

            JsonNode result = converter.toJsonNode(createRequestWithTool(tool));
            JsonNode qp = result.get("mcpTools").get(0).get("queryParameters").get(0);

            assertEquals("api_key", qp.get("name").asText());
            assertTrue(qp.get("isHidden").asBoolean(), "isHidden should be extracted from extras.hidden");
            assertNotNull(qp.get("extras"), "extras should still be present");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STABLE UUID FORWARDING (catalog-service-import round-trip)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * The catalog-service-import path fills {@code apiId} on
     * {@link ApiConfigurationRequest} from the JSON migration file so re-imports
     * after a TRUNCATE reproduce the same primary key. The converter must
     * forward that value verbatim into the submission JsonNode consumed by
     * {@code ApiSubmissionOrchestrator}. If this forwarding silently drops,
     * {@code createApiFromSubmission} never sees the id → Postgres generates
     * a fresh UUID → downstream tool refs like
     * {@code {{mcp:<tool_uuid>.output.*}}} break on every reimport.
     */
    @Nested
    @DisplayName("Stable UUID (apiId forwarding)")
    class StableUuidForwardingTests {

        @Test
        @DisplayName("Should forward apiId when provided (importer path)")
        void shouldForwardApiIdWhenProvided() {
            ApiConfigurationRequest.AuthorizationDto auth = new ApiConfigurationRequest.AuthorizationDto(
                "none", null, null, null
            );
            ApiConfigurationRequest.ApiConfigDto apiConfig = new ApiConfigurationRequest.ApiConfigDto(
                "https://api.example.com", null, auth, "public"
            );
            ApiConfigurationRequest.MonetizationConfigDto monetization = new ApiConfigurationRequest.MonetizationConfigDto(
                "free", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null
            );

            // Use the 23-arg primary constructor (trailing apiId).
            ApiConfigurationRequest request = new ApiConfigurationRequest(
                "API", "Desc", "Cat", null, "Subcat", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, apiConfig, monetization,
                Collections.emptyList(), "11111111-2222-3333-4444-555555555555"
            );

            JsonNode result = converter.toJsonNode(request);

            assertNotNull(result.get("apiId"),
                "apiId must be forwarded into the submission JsonNode");
            assertEquals("11111111-2222-3333-4444-555555555555", result.get("apiId").asText());
        }

        @Test
        @DisplayName("Should omit apiId when null (legacy frontend path)")
        void shouldOmitApiIdWhenNull() {
            ApiConfigurationRequest.AuthorizationDto auth = new ApiConfigurationRequest.AuthorizationDto(
                "none", null, null, null
            );
            ApiConfigurationRequest.ApiConfigDto apiConfig = new ApiConfigurationRequest.ApiConfigDto(
                "https://api.example.com", null, auth, "public"
            );
            ApiConfigurationRequest.MonetizationConfigDto monetization = new ApiConfigurationRequest.MonetizationConfigDto(
                "free", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null
            );

            // Use the backwards-compat 20-arg convenience constructor - apiId defaults null.
            ApiConfigurationRequest request = new ApiConfigurationRequest(
                "API", "Desc", "Cat", null, "Subcat", null, null, null, null,
                null, null, null, null, null, null, null, null, apiConfig, monetization,
                Collections.emptyList()
            );

            JsonNode result = converter.toJsonNode(request);

            // No apiId key present (legacy flow → gen_random_uuid() in Postgres).
            assertFalse(result.has("apiId"),
                "apiId must NOT be in the submission JsonNode when the request carries null");
        }

        @Test
        @DisplayName("Should omit apiId when blank")
        void shouldOmitApiIdWhenBlank() {
            ApiConfigurationRequest.AuthorizationDto auth = new ApiConfigurationRequest.AuthorizationDto(
                "none", null, null, null
            );
            ApiConfigurationRequest.ApiConfigDto apiConfig = new ApiConfigurationRequest.ApiConfigDto(
                "https://api.example.com", null, auth, "public"
            );
            ApiConfigurationRequest.MonetizationConfigDto monetization = new ApiConfigurationRequest.MonetizationConfigDto(
                "free", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null
            );

            ApiConfigurationRequest request = new ApiConfigurationRequest(
                "API", "Desc", "Cat", null, "Subcat", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, apiConfig, monetization,
                Collections.emptyList(), "   "
            );

            JsonNode result = converter.toJsonNode(request);

            assertFalse(result.has("apiId"),
                "Blank apiId must be treated the same as null (no forwarding)");
        }
    }
}
