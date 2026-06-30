package com.apimarketplace.catalog.domain.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiConfigurationRequest and all its nested records.
 *
 * Tests record construction, accessors, equals, and hashCode.
 */
@DisplayName("ApiConfigurationRequest Tests")
class ApiConfigurationRequestTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    // ═══════════════════════════════════════════════════════════════════════════
    // ApiConfigurationRequest TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ApiConfigurationRequest")
    class MainRecordTests {

        @Test
        @DisplayName("Should create record with all fields")
        void shouldCreateWithAllFields() {
            ApiConfigurationRequest.ApiConfigDto apiConfig = new ApiConfigurationRequest.ApiConfigDto(
                "https://api.example.com", "/health",
                new ApiConfigurationRequest.AuthorizationDto("bearer", "Use token", "Authorization", "Bearer xxx"),
                "public"
            );
            ApiConfigurationRequest.MonetizationConfigDto monetization = new ApiConfigurationRequest.MonetizationConfigDto(
                "freemium", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null
            );

            ApiConfigurationRequest request = new ApiConfigurationRequest(
                "My API", "API description", "Finance", "Finance desc",
                "Payments", "Payments desc", "payments-icon", "cat-123", "subcat-456",
                false, false, false, "api-icon", "my-api", "user_key", "platform-cred",
                "tool-cat-icon", apiConfig, monetization, Collections.emptyList()
            );

            assertEquals("My API", request.apiName());
            assertEquals("API description", request.apiDescription());
            assertEquals("Finance", request.selectedCategory());
            assertEquals("Finance desc", request.categoryDescription());
            assertEquals("Payments", request.selectedSubcategory());
            assertEquals("Payments desc", request.subcategoryDescription());
            assertEquals("payments-icon", request.subcategoryIconUrl());
            assertEquals("cat-123", request.categoryId());
            assertEquals("subcat-456", request.subcategoryId());
            assertFalse(request.isCustomCategory());
            assertFalse(request.isCustomSubcategory());
            assertFalse(request.isLocal());
            assertEquals("api-icon", request.iconSlug());
            assertEquals("my-api", request.apiSlug());
            assertEquals("platform-cred", request.platformCredentialName());
            assertEquals("tool-cat-icon", request.toolCategoryIconUrl());
            assertNotNull(request.apiConfig());
            assertNotNull(request.monetization());
            assertNotNull(request.mcpTools());
        }

        @Test
        @DisplayName("Should allow null optional fields")
        void shouldAllowNullOptionalFields() {
            ApiConfigurationRequest.ApiConfigDto apiConfig = new ApiConfigurationRequest.ApiConfigDto(
                "https://api.example.com", null,
                new ApiConfigurationRequest.AuthorizationDto("none", null, null, null),
                "public"
            );
            ApiConfigurationRequest.MonetizationConfigDto monetization = new ApiConfigurationRequest.MonetizationConfigDto(
                "free", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null
            );

            ApiConfigurationRequest request = new ApiConfigurationRequest(
                "My API", "Description", "Category", null,
                "Subcategory", null, null, null, null,
                null, null, null, null, null, null, null,
                null, apiConfig, monetization, Collections.emptyList()
            );

            assertNotNull(request);
            assertNull(request.categoryDescription());
            assertNull(request.subcategoryDescription());
            assertNull(request.subcategoryIconUrl());
            assertNull(request.categoryId());
        }

        @Test
        @DisplayName("Should implement equals correctly")
        void shouldImplementEquals() {
            ApiConfigurationRequest.ApiConfigDto apiConfig = new ApiConfigurationRequest.ApiConfigDto(
                "https://api.example.com", "/health",
                new ApiConfigurationRequest.AuthorizationDto("bearer", null, null, null),
                "public"
            );
            ApiConfigurationRequest.MonetizationConfigDto monetization = new ApiConfigurationRequest.MonetizationConfigDto(
                "free", null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null
            );

            ApiConfigurationRequest request1 = new ApiConfigurationRequest(
                "API", "Desc", "Cat", null, "Subcat", null, null, null, null,
                null, null, null, null, null, null, null, null, apiConfig, monetization, Collections.emptyList()
            );
            ApiConfigurationRequest request2 = new ApiConfigurationRequest(
                "API", "Desc", "Cat", null, "Subcat", null, null, null, null,
                null, null, null, null, null, null, null, null, apiConfig, monetization, Collections.emptyList()
            );

            assertEquals(request1, request2);
            assertEquals(request1.hashCode(), request2.hashCode());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ApiConfigDto TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ApiConfigDto")
    class ApiConfigDtoTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() {
            ApiConfigurationRequest.AuthorizationDto auth = new ApiConfigurationRequest.AuthorizationDto(
                "bearer", "Bearer token authentication", "Authorization", "Bearer abc123"
            );

            ApiConfigurationRequest.ApiConfigDto config = new ApiConfigurationRequest.ApiConfigDto(
                "https://api.example.com", "/health", auth, "public"
            );

            assertEquals("https://api.example.com", config.baseUrl());
            assertEquals("/health", config.healthcheckEndpoint());
            assertNotNull(config.authorization());
            assertEquals("public", config.visibility());
        }

        @Test
        @DisplayName("Should allow null healthcheck endpoint")
        void shouldAllowNullHealthcheckEndpoint() {
            ApiConfigurationRequest.AuthorizationDto auth = new ApiConfigurationRequest.AuthorizationDto(
                "none", null, null, null
            );

            ApiConfigurationRequest.ApiConfigDto config = new ApiConfigurationRequest.ApiConfigDto(
                "https://api.example.com", null, auth, "private"
            );

            assertNull(config.healthcheckEndpoint());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AuthorizationDto TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AuthorizationDto")
    class AuthorizationDtoTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() {
            ApiConfigurationRequest.AuthorizationDto auth = new ApiConfigurationRequest.AuthorizationDto(
                "bearer", "Use Bearer token", "Authorization", "Bearer token123"
            );

            assertEquals("bearer", auth.type());
            assertEquals("Use Bearer token", auth.description());
            assertEquals("Authorization", auth.headerName());
            assertEquals("Bearer token123", auth.headerValue());
        }

        @Test
        @DisplayName("Should handle API key authorization")
        void shouldHandleApiKeyAuthorization() {
            ApiConfigurationRequest.AuthorizationDto auth = new ApiConfigurationRequest.AuthorizationDto(
                "api_key", "API Key required", "X-API-Key", "abc123"
            );

            assertEquals("api_key", auth.type());
            assertEquals("X-API-Key", auth.headerName());
        }

        @Test
        @DisplayName("Should handle no authorization")
        void shouldHandleNoAuthorization() {
            ApiConfigurationRequest.AuthorizationDto auth = new ApiConfigurationRequest.AuthorizationDto(
                "none", null, null, null
            );

            assertEquals("none", auth.type());
            assertNull(auth.headerName());
            assertNull(auth.headerValue());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MonetizationConfigDto TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("MonetizationConfigDto")
    class MonetizationConfigDtoTests {

        @Test
        @DisplayName("Should create freemium config")
        void shouldCreateFreemiumConfig() {
            ApiConfigurationRequest.RateLimitDto rateLimit = new ApiConfigurationRequest.RateLimitDto(100, "minute");

            ApiConfigurationRequest.MonetizationConfigDto config = new ApiConfigurationRequest.MonetizationConfigDto(
                "freemium", List.of("freemium"), rateLimit, 1000, "monthly", null, null,
                50, 0.05, 100, null, null, null,
                null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null
            );

            assertEquals("freemium", config.pricing());
            assertEquals(List.of("freemium"), config.selectedPricingModels());
            assertNotNull(config.rateLimit());
            assertEquals(1000, config.freeRequestsPerUser());
            assertEquals("monthly", config.freeRequestsType());
            assertEquals(50, config.uniformToolPrice());
            assertEquals(0.05, config.uniformToolPriceInDollars());
            assertEquals(100, config.uniformCalls());
        }

        @Test
        @DisplayName("Should create paid config")
        void shouldCreatePaidConfig() {
            Map<String, Boolean> selectedPlans = new HashMap<>();
            selectedPlans.put("basic", true);
            selectedPlans.put("pro", true);

            Map<String, List<String>> planTools = new HashMap<>();
            planTools.put("basic", List.of("tool1"));
            planTools.put("pro", List.of("tool1", "tool2"));

            ApiConfigurationRequest.MonetizationConfigDto config = new ApiConfigurationRequest.MonetizationConfigDto(
                "paid", List.of("paid"), null, null, null, null, null,
                null, null, null, null, selectedPlans, planTools,
                9.99, 19.99, 49.99, 99.99,
                100, 500, 2000, 10000,
                10, 50, 100, 500,
                "second", "second", "second", "second",
                0.01, 0.008, 0.005, 0.003,
                false, false, false, false
            );

            assertEquals("paid", config.pricing());
            assertEquals(9.99, config.priceBasic());
            assertEquals(19.99, config.pricePro());
            assertEquals(49.99, config.priceUltra());
            assertEquals(99.99, config.priceMega());
            assertEquals(100, config.quotaBasic());
            assertEquals(10, config.rpsBasic());
            assertEquals("second", config.rpsPeriodBasic());
            assertEquals(0.01, config.overusageCostBasic());
            assertFalse(config.hardLimitBasic());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RateLimitDto TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("RateLimitDto")
    class RateLimitDtoTests {

        @Test
        @DisplayName("Should create with requests and period")
        void shouldCreateWithRequestsAndPeriod() {
            ApiConfigurationRequest.RateLimitDto rateLimit = new ApiConfigurationRequest.RateLimitDto(100, "minute");

            assertEquals(100, rateLimit.requests());
            assertEquals("minute", rateLimit.period());
        }

        @Test
        @DisplayName("Should allow null values")
        void shouldAllowNullValues() {
            ApiConfigurationRequest.RateLimitDto rateLimit = new ApiConfigurationRequest.RateLimitDto(null, null);

            assertNull(rateLimit.requests());
            assertNull(rateLimit.period());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ToolRateLimitsDto TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ToolRateLimitsDto")
    class ToolRateLimitsDtoTests {

        @Test
        @DisplayName("Should create with requests and period")
        void shouldCreateWithRequestsAndPeriod() {
            ApiConfigurationRequest.ToolRateLimitsDto limits = new ApiConfigurationRequest.ToolRateLimitsDto(50, "second");

            assertEquals(50, limits.requests());
            assertEquals("second", limits.period());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ToolPricingDto TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ToolPricingDto")
    class ToolPricingDtoTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() {
            ApiConfigurationRequest.ToolPricingDto pricing = new ApiConfigurationRequest.ToolPricingDto(
                "tool-123", 1000, 0.05, "USD", 100
            );

            assertEquals("tool-123", pricing.id());
            assertEquals(1000, pricing.mauValue());
            assertEquals(0.05, pricing.price());
            assertEquals("USD", pricing.currency());
            assertEquals(100, pricing.calls());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PricingModelsUpdateRequest TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PricingModelsUpdateRequest")
    class PricingModelsUpdateRequestTests {

        @Test
        @DisplayName("Should create with apiId and pricing models")
        void shouldCreateWithApiIdAndPricingModels() {
            ApiConfigurationRequest.PricingModelsUpdateRequest request =
                new ApiConfigurationRequest.PricingModelsUpdateRequest(
                    "api-123", List.of("freemium", "paid")
                );

            assertEquals("api-123", request.apiId());
            assertEquals(List.of("freemium", "paid"), request.selectedPricingModels());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ToolFreemiumConfigDto TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ToolFreemiumConfigDto")
    class ToolFreemiumConfigDtoTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() {
            ApiConfigurationRequest.ToolFreemiumConfigDto config =
                new ApiConfigurationRequest.ToolFreemiumConfigDto(
                    100, "monthly", 10, "second", 1000, 50
                );

            assertEquals(100, config.freeRequests());
            assertEquals("monthly", config.freeRequestsType());
            assertEquals(10, config.rateLimitRequests());
            assertEquals("second", config.rateLimitPeriod());
            assertEquals(1000, config.mauValue());
            assertEquals(50, config.calls());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ToolFreemiumConfigUpdateRequest TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ToolFreemiumConfigUpdateRequest")
    class ToolFreemiumConfigUpdateRequestTests {

        @Test
        @DisplayName("Should create with all required fields")
        void shouldCreateWithAllRequiredFields() {
            ApiConfigurationRequest.ToolFreemiumConfigDto config =
                new ApiConfigurationRequest.ToolFreemiumConfigDto(
                    100, "monthly", 10, "second", 1000, 50
                );

            ApiConfigurationRequest.ToolFreemiumConfigUpdateRequest request =
                new ApiConfigurationRequest.ToolFreemiumConfigUpdateRequest(
                    "api-123", "tool-456", "FREEMIUM", config
                );

            assertEquals("api-123", request.apiId());
            assertEquals("tool-456", request.apiToolId());
            assertEquals("FREEMIUM", request.monetizationType());
            assertNotNull(request.config());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BatchToolFreemiumConfigUpdateRequest TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("BatchToolFreemiumConfigUpdateRequest")
    class BatchToolFreemiumConfigUpdateRequestTests {

        @Test
        @DisplayName("Should create with tools config map")
        void shouldCreateWithToolsConfigMap() {
            Map<String, ApiConfigurationRequest.ToolFreemiumConfigDto> toolsConfig = new HashMap<>();
            toolsConfig.put("tool1", new ApiConfigurationRequest.ToolFreemiumConfigDto(
                100, "monthly", 10, "second", 1000, 50
            ));
            toolsConfig.put("tool2", new ApiConfigurationRequest.ToolFreemiumConfigDto(
                200, "daily", 20, "minute", 2000, 100
            ));

            ApiConfigurationRequest.BatchToolFreemiumConfigUpdateRequest request =
                new ApiConfigurationRequest.BatchToolFreemiumConfigUpdateRequest(
                    "api-123", "FREEMIUM", toolsConfig
                );

            assertEquals("api-123", request.apiId());
            assertEquals("FREEMIUM", request.monetizationType());
            assertEquals(2, request.toolsConfig().size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ToolPaidConfigDto TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ToolPaidConfigDto")
    class ToolPaidConfigDtoTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() {
            ApiConfigurationRequest.ToolPaidConfigDto config =
                new ApiConfigurationRequest.ToolPaidConfigDto(
                    "pro", 500, new BigDecimal("19.99"), new BigDecimal("0.01"),
                    false, 100, "minute"
                );

            assertEquals("pro", config.planName());
            assertEquals(500, config.quota());
            assertEquals(new BigDecimal("19.99"), config.price());
            assertEquals(new BigDecimal("0.01"), config.overusageCost());
            assertFalse(config.hardLimit());
            assertEquals(100, config.rateLimitRequests());
            assertEquals("minute", config.rateLimitPeriod());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ToolPaidConfigUpdateRequest TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ToolPaidConfigUpdateRequest")
    class ToolPaidConfigUpdateRequestTests {

        @Test
        @DisplayName("Should create with all required fields")
        void shouldCreateWithAllRequiredFields() {
            ApiConfigurationRequest.ToolPaidConfigDto config =
                new ApiConfigurationRequest.ToolPaidConfigDto(
                    "basic", 100, new BigDecimal("9.99"), new BigDecimal("0.02"),
                    true, 50, "second"
                );

            ApiConfigurationRequest.ToolPaidConfigUpdateRequest request =
                new ApiConfigurationRequest.ToolPaidConfigUpdateRequest(
                    "api-123", "tool-456", "PAID", config
                );

            assertEquals("api-123", request.apiId());
            assertEquals("tool-456", request.apiToolId());
            assertEquals("PAID", request.monetizationType());
            assertNotNull(request.config());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BatchToolPaidConfigUpdateRequest TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("BatchToolPaidConfigUpdateRequest")
    class BatchToolPaidConfigUpdateRequestTests {

        @Test
        @DisplayName("Should create with tools config map")
        void shouldCreateWithToolsConfigMap() {
            Map<String, ApiConfigurationRequest.ToolPaidConfigDto> toolsConfig = new HashMap<>();
            toolsConfig.put("tool1", new ApiConfigurationRequest.ToolPaidConfigDto(
                "basic", 100, new BigDecimal("9.99"), new BigDecimal("0.02"),
                true, 50, "second"
            ));

            ApiConfigurationRequest.BatchToolPaidConfigUpdateRequest request =
                new ApiConfigurationRequest.BatchToolPaidConfigUpdateRequest(
                    "api-123", "PAID", toolsConfig
                );

            assertEquals("api-123", request.apiId());
            assertEquals("PAID", request.monetizationType());
            assertEquals(1, request.toolsConfig().size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PaidPlansUpdateRequest TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PaidPlansUpdateRequest")
    class PaidPlansUpdateRequestTests {

        @Test
        @DisplayName("Should create with selected plans and plan tools")
        void shouldCreateWithSelectedPlansAndPlanTools() {
            Map<String, Boolean> selectedPlans = new HashMap<>();
            selectedPlans.put("basic", true);
            selectedPlans.put("pro", true);
            selectedPlans.put("ultra", false);

            Map<String, List<String>> planTools = new HashMap<>();
            planTools.put("basic", List.of("tool1", "tool2"));
            planTools.put("pro", List.of("tool1", "tool2", "tool3"));

            ApiConfigurationRequest.PaidPlansUpdateRequest request =
                new ApiConfigurationRequest.PaidPlansUpdateRequest(selectedPlans, planTools);

            assertEquals(3, request.selectedPlans().size());
            assertTrue(request.selectedPlans().get("basic"));
            assertFalse(request.selectedPlans().get("ultra"));
            assertEquals(2, request.planTools().get("basic").size());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // McpToolDto TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("McpToolDto")
    class McpToolDtoTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() throws Exception {
            JsonNode runtimeMetadata = objectMapper.readTree("{\"timeout\": 30}");

            ApiConfigurationRequest.McpToolDto tool = new ApiConfigurationRequest.McpToolDto(
                "tool-123", "Get Users", "Retrieves all users", "/users", "GET", "REST",
                runtimeMetadata, "Data Access", "Data access tools", "data-icon",
                "tool-name-123", false, false,
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(),
                Collections.emptyMap(), null, null, null, null, null, null,
                null, null, null, // executionSpec, outputSchema, executionMode
                null, null, null, null  // synthesis, pagination, nextHint, requiredScopes
            );

            assertEquals("tool-123", tool.id());
            assertEquals("Get Users", tool.name());
            assertEquals("Retrieves all users", tool.description());
            assertEquals("/users", tool.endpoint());
            assertEquals("GET", tool.method());
            assertEquals("REST", tool.protocol());
            assertNotNull(tool.runtimeMetadata());
            assertEquals("Data Access", tool.toolCategory());
            assertEquals("tool-name-123", tool.toolNameId());
            assertFalse(tool.isCustomCategory());
            assertFalse(tool.isCustomToolName());
        }

        @Test
        @DisplayName("Should allow null optional fields")
        void shouldAllowNullOptionalFields() {
            ApiConfigurationRequest.McpToolDto tool = new ApiConfigurationRequest.McpToolDto(
                null, "Tool", "Description", "/endpoint", "POST", null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, // executionSpec, outputSchema, executionMode
                null, null, null, null  // synthesis, pagination, nextHint, requiredScopes
            );

            assertNotNull(tool);
            assertNull(tool.id());
            assertNull(tool.protocol());
            assertNull(tool.headers());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SqlConfigDto TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SqlConfigDto")
    class SqlConfigDtoTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() throws Exception {
            JsonNode parameters = objectMapper.readTree("{\"userId\": \"string\"}");

            ApiConfigurationRequest.SqlConfigDto config = new ApiConfigurationRequest.SqlConfigDto(
                "postgresql", "SELECT * FROM users WHERE id = :userId",
                "public", "users", "single", parameters
            );

            assertEquals("postgresql", config.dialect());
            assertEquals("SELECT * FROM users WHERE id = :userId", config.query());
            assertEquals("public", config.defaultSchema());
            assertEquals("users", config.defaultTable());
            assertEquals("single", config.resultMode());
            assertNotNull(config.parameters());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // AmqpConfigDto TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("AmqpConfigDto")
    class AmqpConfigDtoTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() {
            ApiConfigurationRequest.AmqpConfigDto config = new ApiConfigurationRequest.AmqpConfigDto(
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
    // KafkaConfigDto TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("KafkaConfigDto")
    class KafkaConfigDtoTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() {
            ApiConfigurationRequest.KafkaConfigDto config = new ApiConfigurationRequest.KafkaConfigDto(
                "localhost:9092,localhost:9093", "my-topic", "consumer-group",
                "SASL_SSL", "PLAIN", "kafka-user", "kafka-pass", true
            );

            assertEquals("localhost:9092,localhost:9093", config.brokers());
            assertEquals("my-topic", config.topic());
            assertEquals("consumer-group", config.consumerGroup());
            assertEquals("SASL_SSL", config.securityProtocol());
            assertEquals("PLAIN", config.saslMechanism());
            assertEquals("kafka-user", config.saslUsername());
            assertEquals("kafka-pass", config.saslPassword());
            assertTrue(config.sslEnabled());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MqttConfigDto TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("MqttConfigDto")
    class MqttConfigDtoTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() {
            ApiConfigurationRequest.MqttConfigDto config = new ApiConfigurationRequest.MqttConfigDto(
                "tcp://localhost:1883", "sensors/+/data", 1, true,
                "client-123", "mqtt-user", "mqtt-pass", false
            );

            assertEquals("tcp://localhost:1883", config.brokerUrl());
            assertEquals("sensors/+/data", config.topics());
            assertEquals(1, config.qos());
            assertTrue(config.retain());
            assertEquals("client-123", config.clientId());
            assertEquals("mqtt-user", config.username());
            assertEquals("mqtt-pass", config.password());
            assertFalse(config.useTls());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RedisConfigDto TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("RedisConfigDto")
    class RedisConfigDtoTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() {
            ApiConfigurationRequest.RedisConfigDto config = new ApiConfigurationRequest.RedisConfigDto(
                "redis.example.com", 6379, 0, "channel1,channel2",
                true, "redis-user", "redis-pass"
            );

            assertEquals("redis.example.com", config.host());
            assertEquals(6379, config.port());
            assertEquals(0, config.dbIndex());
            assertEquals("channel1,channel2", config.channels());
            assertTrue(config.useTls());
            assertEquals("redis-user", config.username());
            assertEquals("redis-pass", config.password());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HeaderDto TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("HeaderDto")
    class HeaderDtoTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() throws Exception {
            JsonNode extras = objectMapper.readTree("{\"description\": \"Custom header\"}");

            ApiConfigurationRequest.HeaderDto header = new ApiConfigurationRequest.HeaderDto(
                "X-Custom-Header", "custom-value", true, null, null, extras
            );

            assertEquals("X-Custom-Header", header.name());
            assertEquals("custom-value", header.value());
            assertTrue(header.required());
            assertNotNull(header.extras());
        }

        @Test
        @DisplayName("Should handle optional header")
        void shouldHandleOptionalHeader() {
            ApiConfigurationRequest.HeaderDto header = new ApiConfigurationRequest.HeaderDto(
                "X-Optional", "optional-value", false, null, null, null
            );

            assertFalse(header.required());
            assertNull(header.extras());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PathParameterDto TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PathParameterDto")
    class PathParameterDtoTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() throws Exception {
            JsonNode extras = objectMapper.readTree("{\"minLength\": 1}");

            ApiConfigurationRequest.PathParameterDto param = new ApiConfigurationRequest.PathParameterDto(
                "userId", "string", true, "The user identifier",
                "user-123", "^[a-z0-9-]+$", null, null, extras
            );

            assertEquals("userId", param.name());
            assertEquals("string", param.type());
            assertTrue(param.required());
            assertEquals("The user identifier", param.description());
            assertEquals("user-123", param.example());
            assertEquals("^[a-z0-9-]+$", param.pattern());
            assertNotNull(param.extras());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // QueryParameterDto TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("QueryParameterDto")
    class QueryParameterDtoTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() throws Exception {
            JsonNode extras = objectMapper.readTree("{\"deprecated\": false}");

            ApiConfigurationRequest.QueryParameterDto param = new ApiConfigurationRequest.QueryParameterDto(
                "status", "string", false, "Filter by status",
                "active", "active", List.of("active", "inactive", "pending"), extras
            );

            assertEquals("status", param.name());
            assertEquals("string", param.type());
            assertFalse(param.required());
            assertEquals("Filter by status", param.description());
            assertEquals("active", param.example());
            assertEquals("active", param.defaultValue());
            assertEquals(3, param.allowedValues().size());
            assertNotNull(param.extras());
        }

        @Test
        @DisplayName("Should handle pagination parameters")
        void shouldHandlePaginationParameters() {
            ApiConfigurationRequest.QueryParameterDto limit = new ApiConfigurationRequest.QueryParameterDto(
                "limit", "integer", false, "Max results",
                "10", "10", null, null
            );
            ApiConfigurationRequest.QueryParameterDto offset = new ApiConfigurationRequest.QueryParameterDto(
                "offset", "integer", false, "Skip results",
                "0", "0", null, null
            );

            assertEquals("limit", limit.name());
            assertEquals("integer", limit.type());
            assertEquals("10", limit.defaultValue());

            assertEquals("offset", offset.name());
            assertEquals("0", offset.defaultValue());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // BodyParamDto TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("BodyParamDto")
    class BodyParamDtoTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() throws Exception {
            JsonNode extras = objectMapper.readTree("{\"format\": \"email\"}");

            ApiConfigurationRequest.BodyParamDto param = new ApiConfigurationRequest.BodyParamDto(
                "email", "user@example.com", "string", null, "User email address",
                null, null, null, extras
            );

            assertEquals("email", param.name());
            assertEquals("user@example.com", param.value());
            assertEquals("string", param.type());
            assertEquals("User email address", param.description());
            assertNull(param.fileSize());
            assertNotNull(param.extras());
        }

        @Test
        @DisplayName("Should handle file upload parameter")
        void shouldHandleFileUploadParameter() {
            ApiConfigurationRequest.BodyParamDto param = new ApiConfigurationRequest.BodyParamDto(
                "document", null, "file", null, "Upload document",
                "10MB", null, null, null
            );

            assertEquals("document", param.name());
            assertEquals("file", param.type());
            assertEquals("10MB", param.fileSize());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ResponseDto TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("ResponseDto")
    class ResponseDtoTests {

        @Test
        @DisplayName("Should create with all fields")
        void shouldCreateWithAllFields() throws Exception {
            JsonNode example = objectMapper.readTree("{\"users\": []}");
            JsonNode schema = objectMapper.readTree("{\"type\": \"object\"}");

            ApiConfigurationRequest.ResponseDto response = new ApiConfigurationRequest.ResponseDto(
                "application/json", "List of users", 200,
                example, example, schema, "json", true, true
            );

            assertEquals("application/json", response.type());
            assertEquals("List of users", response.description());
            assertEquals(200, response.statusCode());
            assertNotNull(response.example());
            assertNotNull(response.exampleJsonb());
            assertNotNull(response.schema());
            assertEquals("json", response.format());
            assertTrue(response.isDefault());
            assertTrue(response.isActive());
        }

        @Test
        @DisplayName("Should handle error response")
        void shouldHandleErrorResponse() throws Exception {
            JsonNode errorExample = objectMapper.readTree("{\"error\": \"Not found\"}");

            ApiConfigurationRequest.ResponseDto response = new ApiConfigurationRequest.ResponseDto(
                "application/json", "Resource not found", 404,
                errorExample, null, null, "json", false, true
            );

            assertEquals(404, response.statusCode());
            assertEquals("Resource not found", response.description());
            assertFalse(response.isDefault());
        }

        @Test
        @DisplayName("Should handle binary response")
        void shouldHandleBinaryResponse() {
            ApiConfigurationRequest.ResponseDto response = new ApiConfigurationRequest.ResponseDto(
                "application/pdf", "PDF document", 200,
                null, null, null, "binary", true, true
            );

            assertEquals("application/pdf", response.type());
            assertEquals("binary", response.format());
        }
    }
}
