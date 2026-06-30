package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.*;
import com.apimarketplace.catalog.domain.dto.ApiResponse;
import com.apimarketplace.catalog.domain.dto.MonetizationResponse;
import com.apimarketplace.catalog.repository.*;
import com.apimarketplace.catalog.service.monetization.MonetizationService;
import com.apimarketplace.credential.client.CredentialClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ApiResponseConverter service.
 *
 * ApiResponseConverter converts API entities to response DTOs.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ApiResponseConverter")
class ApiResponseConverterTest {

    @Mock private ApiToolRepository apiToolRepository;
    @Mock private ApiCategoryRepository categoryRepository;
    @Mock private ApiSubcategoryRepository subcategoryRepository;
    @Mock private ApiToolSqlConfigRepository sqlConfigRepository;
    @Mock private ApiToolAmqpConfigRepository amqpConfigRepository;
    @Mock private ApiToolKafkaConfigRepository kafkaConfigRepository;
    @Mock private ApiToolMqttConfigRepository mqttConfigRepository;
    @Mock private ApiToolRedisConfigRepository redisConfigRepository;
    @Mock private ApiToolParameterService parameterService;
    @Mock private MonetizationService monetizationService;
    @Mock private ToolCategoryService toolCategoryService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private CredentialClient credentialClient;

    private ObjectMapper objectMapper;
    private ApiResponseConverter converter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        converter = new ApiResponseConverter(
                apiToolRepository,
                categoryRepository,
                subcategoryRepository,
                sqlConfigRepository,
                amqpConfigRepository,
                kafkaConfigRepository,
                mqttConfigRepository,
                redisConfigRepository,
                parameterService,
                monetizationService,
                toolCategoryService,
                objectMapper,
                jdbcTemplate,
                credentialClient
        );
    }

    // ========================================================================
    // toApiResponse tests
    // ========================================================================

    @Nested
    @DisplayName("toApiResponse")
    class ToApiResponseTests {

        @Test
        @DisplayName("should convert ApiEntity to ApiResponse with all fields")
        void shouldConvertApiEntityToApiResponse() {
            // Arrange
            UUID apiId = UUID.randomUUID();
            UUID categoryId = UUID.randomUUID();
            UUID subcategoryId = UUID.randomUUID();

            ApiEntity api = createApiEntity(apiId, categoryId, subcategoryId);
            ApiCategoryEntity category = new ApiCategoryEntity();
            category.setName("Weather");
            ApiSubcategoryEntity subcategory = new ApiSubcategoryEntity();
            subcategory.setName("Forecast");

            when(apiToolRepository.findByApiId(apiId)).thenReturn(Collections.emptyList());
            when(categoryRepository.findById(categoryId)).thenReturn(Optional.of(category));
            when(subcategoryRepository.findById(subcategoryId)).thenReturn(Optional.of(subcategory));

            Function<ApiToolEntity, String> toolNameResolver = tool -> tool.getEndpoint();

            // Act
            ApiResponse response = converter.toApiResponse(api, toolNameResolver);

            // Assert
            assertEquals(apiId, response.id());
            assertEquals("Test API", response.apiName());
            assertEquals("test-api", response.apiSlug());
            assertEquals("Weather", response.categoryName());
            assertEquals("Forecast", response.subcategoryName());
        }

        @Test
        @DisplayName("should handle missing category and subcategory")
        void shouldHandleMissingCategoryAndSubcategory() {
            // Arrange
            UUID apiId = UUID.randomUUID();
            UUID categoryId = UUID.randomUUID();
            UUID subcategoryId = UUID.randomUUID();

            ApiEntity api = createApiEntity(apiId, categoryId, subcategoryId);

            when(apiToolRepository.findByApiId(apiId)).thenReturn(Collections.emptyList());
            when(categoryRepository.findById(categoryId)).thenReturn(Optional.empty());
            when(subcategoryRepository.findById(subcategoryId)).thenReturn(Optional.empty());

            Function<ApiToolEntity, String> toolNameResolver = tool -> "tool";

            // Act
            ApiResponse response = converter.toApiResponse(api, toolNameResolver);

            // Assert
            assertEquals("Unknown", response.categoryName());
            assertEquals("Unknown", response.subcategoryName());
        }

        @Test
        @DisplayName("should include tool responses in API response")
        void shouldIncludeToolResponsesInApiResponse() {
            // Arrange
            UUID apiId = UUID.randomUUID();
            UUID toolId = UUID.randomUUID();
            UUID categoryId = UUID.randomUUID();
            UUID subcategoryId = UUID.randomUUID();

            ApiEntity api = createApiEntity(apiId, categoryId, subcategoryId);
            ApiToolEntity tool = createToolEntity(toolId, "REST");

            when(apiToolRepository.findByApiId(apiId)).thenReturn(List.of(tool));
            when(categoryRepository.findById(categoryId)).thenReturn(Optional.empty());
            when(subcategoryRepository.findById(subcategoryId)).thenReturn(Optional.empty());
            when(parameterService.getToolParameters(toolId)).thenReturn(Collections.emptyList());
            when(monetizationService.getToolMonetization(toolId)).thenReturn(Collections.emptyList());
            when(sqlConfigRepository.findByApiToolId(toolId)).thenReturn(Optional.empty());
            when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any()))
                    .thenReturn(Collections.emptyList());

            Function<ApiToolEntity, String> toolNameResolver = t -> "get_weather";

            // Act
            ApiResponse response = converter.toApiResponse(api, toolNameResolver);

            // Assert
            assertEquals(1, response.tools().size());
            assertEquals("get_weather", response.tools().get(0).name());
        }
    }

    // ========================================================================
    // toToolResponse tests
    // ========================================================================

    @Nested
    @DisplayName("toToolResponse")
    class ToToolResponseTests {

        @Test
        @DisplayName("should convert REST tool entity to response")
        void shouldConvertRestToolEntityToResponse() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = createToolEntity(toolId, "REST");

            when(parameterService.getToolParameters(toolId)).thenReturn(Collections.emptyList());
            when(monetizationService.getToolMonetization(toolId)).thenReturn(Collections.emptyList());
            when(sqlConfigRepository.findByApiToolId(toolId)).thenReturn(Optional.empty());
            when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any()))
                    .thenReturn(Collections.emptyList());

            Function<ApiToolEntity, String> toolNameResolver = t -> "weather_tool";

            // Act
            ApiResponse.ToolResponse response = converter.toToolResponse(tool, toolNameResolver);

            // Assert
            assertEquals(toolId, response.id());
            assertEquals("weather_tool", response.name());
            assertEquals("GET", response.method());
            assertEquals("REST", response.protocol());
        }

        @Test
        @DisplayName("should include SQL config for SQL tool")
        void shouldIncludeSqlConfigForSqlTool() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = createToolEntity(toolId, "SQL");

            ApiToolSqlConfigEntity sqlConfig = new ApiToolSqlConfigEntity();
            sqlConfig.setDialect("postgresql");
            sqlConfig.setQueryTemplate("SELECT * FROM users");
            sqlConfig.setDefaultSchema("public");

            when(parameterService.getToolParameters(toolId)).thenReturn(Collections.emptyList());
            when(monetizationService.getToolMonetization(toolId)).thenReturn(Collections.emptyList());
            when(sqlConfigRepository.findByApiToolId(toolId)).thenReturn(Optional.of(sqlConfig));
            when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any()))
                    .thenReturn(Collections.emptyList());

            Function<ApiToolEntity, String> toolNameResolver = t -> "query_users";

            // Act
            ApiResponse.ToolResponse response = converter.toToolResponse(tool, toolNameResolver);

            // Assert
            assertNotNull(response.sqlConfig());
            assertEquals("postgresql", response.sqlConfig().dialect());
            assertEquals("SELECT * FROM users", response.sqlConfig().queryTemplate());
        }

        @Test
        @DisplayName("should include AMQP config for AMQP protocol")
        void shouldIncludeAmqpConfigForAmqpProtocol() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = createToolEntity(toolId, "AMQP");

            ApiToolAmqpConfigEntity amqpConfig = new ApiToolAmqpConfigEntity();
            amqpConfig.setBrokerUrl("amqp://localhost:5672");
            amqpConfig.setQueueName("test-queue");
            amqpConfig.setExchangeName("test-exchange");

            when(parameterService.getToolParameters(toolId)).thenReturn(Collections.emptyList());
            when(monetizationService.getToolMonetization(toolId)).thenReturn(Collections.emptyList());
            when(sqlConfigRepository.findByApiToolId(toolId)).thenReturn(Optional.empty());
            when(amqpConfigRepository.findByApiToolId(toolId)).thenReturn(Optional.of(amqpConfig));
            when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any()))
                    .thenReturn(Collections.emptyList());

            Function<ApiToolEntity, String> toolNameResolver = t -> "send_message";

            // Act
            ApiResponse.ToolResponse response = converter.toToolResponse(tool, toolNameResolver);

            // Assert
            assertNotNull(response.amqpConfig());
            assertEquals("amqp://localhost:5672", response.amqpConfig().brokerUrl());
            assertEquals("test-queue", response.amqpConfig().queueName());
        }

        @Test
        @DisplayName("should include Kafka config for Kafka protocol")
        void shouldIncludeKafkaConfigForKafkaProtocol() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = createToolEntity(toolId, "KAFKA");

            ApiToolKafkaConfigEntity kafkaConfig = new ApiToolKafkaConfigEntity();
            kafkaConfig.setBrokers("localhost:9092");
            kafkaConfig.setTopic("test-topic");
            kafkaConfig.setConsumerGroup("test-group");

            when(parameterService.getToolParameters(toolId)).thenReturn(Collections.emptyList());
            when(monetizationService.getToolMonetization(toolId)).thenReturn(Collections.emptyList());
            when(sqlConfigRepository.findByApiToolId(toolId)).thenReturn(Optional.empty());
            when(kafkaConfigRepository.findByApiToolId(toolId)).thenReturn(Optional.of(kafkaConfig));
            when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any()))
                    .thenReturn(Collections.emptyList());

            Function<ApiToolEntity, String> toolNameResolver = t -> "consume_events";

            // Act
            ApiResponse.ToolResponse response = converter.toToolResponse(tool, toolNameResolver);

            // Assert
            assertNotNull(response.kafkaConfig());
            assertEquals("localhost:9092", response.kafkaConfig().brokers());
            assertEquals("test-topic", response.kafkaConfig().topic());
        }

        @Test
        @DisplayName("should return null for non-matching protocol configs")
        void shouldReturnNullForNonMatchingProtocolConfigs() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = createToolEntity(toolId, "REST");

            when(parameterService.getToolParameters(toolId)).thenReturn(Collections.emptyList());
            when(monetizationService.getToolMonetization(toolId)).thenReturn(Collections.emptyList());
            when(sqlConfigRepository.findByApiToolId(toolId)).thenReturn(Optional.empty());
            when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any()))
                    .thenReturn(Collections.emptyList());

            Function<ApiToolEntity, String> toolNameResolver = t -> "rest_tool";

            // Act
            ApiResponse.ToolResponse response = converter.toToolResponse(tool, toolNameResolver);

            // Assert
            assertNull(response.amqpConfig());
            assertNull(response.kafkaConfig());
            assertNull(response.mqttConfig());
            assertNull(response.redisConfig());
        }
    }

    // ========================================================================
    // Runtime metadata tests
    // ========================================================================

    @Nested
    @DisplayName("Runtime metadata parsing")
    class RuntimeMetadataTests {

        @Test
        @DisplayName("should parse valid runtime metadata JSON")
        void shouldParseValidRuntimeMetadataJson() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = createToolEntity(toolId, "REST");
            tool.setRuntimeMetadata("{\"timeout\": 30, \"retry\": true}");

            when(parameterService.getToolParameters(toolId)).thenReturn(Collections.emptyList());
            when(monetizationService.getToolMonetization(toolId)).thenReturn(Collections.emptyList());
            when(sqlConfigRepository.findByApiToolId(toolId)).thenReturn(Optional.empty());
            when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any()))
                    .thenReturn(Collections.emptyList());

            Function<ApiToolEntity, String> toolNameResolver = t -> "tool_name";

            // Act
            ApiResponse.ToolResponse response = converter.toToolResponse(tool, toolNameResolver);

            // Assert
            assertNotNull(response.runtimeMetadata());
            assertEquals(30, response.runtimeMetadata().get("timeout").asInt());
            assertTrue(response.runtimeMetadata().get("retry").asBoolean());
        }

        @Test
        @DisplayName("should return null for null runtime metadata")
        void shouldReturnNullForNullRuntimeMetadata() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = createToolEntity(toolId, "REST");
            tool.setRuntimeMetadata(null);

            when(parameterService.getToolParameters(toolId)).thenReturn(Collections.emptyList());
            when(monetizationService.getToolMonetization(toolId)).thenReturn(Collections.emptyList());
            when(sqlConfigRepository.findByApiToolId(toolId)).thenReturn(Optional.empty());
            when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any()))
                    .thenReturn(Collections.emptyList());

            Function<ApiToolEntity, String> toolNameResolver = t -> "tool_name";

            // Act
            ApiResponse.ToolResponse response = converter.toToolResponse(tool, toolNameResolver);

            // Assert
            assertNull(response.runtimeMetadata());
        }

        @Test
        @DisplayName("should return null for invalid JSON in runtime metadata")
        void shouldReturnNullForInvalidJsonInRuntimeMetadata() {
            // Arrange
            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = createToolEntity(toolId, "REST");
            tool.setRuntimeMetadata("invalid json {{{");

            when(parameterService.getToolParameters(toolId)).thenReturn(Collections.emptyList());
            when(monetizationService.getToolMonetization(toolId)).thenReturn(Collections.emptyList());
            when(sqlConfigRepository.findByApiToolId(toolId)).thenReturn(Optional.empty());
            when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any()))
                    .thenReturn(Collections.emptyList());

            Function<ApiToolEntity, String> toolNameResolver = t -> "tool_name";

            // Act
            ApiResponse.ToolResponse response = converter.toToolResponse(tool, toolNameResolver);

            // Assert
            assertNull(response.runtimeMetadata());
        }
    }

    // ========================================================================
    // Platform credential availability probe
    // ========================================================================

    @Nested
    @DisplayName("platformCredentialMissing flag")
    class PlatformCredentialMissingTests {

        @Test
        @DisplayName("defaults to null when credential probe is not requested (list path)")
        void shouldLeaveFlagNullOnListPath() {
            UUID apiId = UUID.randomUUID();
            ApiEntity api = createApiEntity(apiId, UUID.randomUUID(), UUID.randomUUID());
            api.setPricingModel("FREEMIUM");
            api.setPlatformCredentialName("ably");

            when(apiToolRepository.findByApiId(apiId)).thenReturn(Collections.emptyList());
            when(categoryRepository.findById(any())).thenReturn(Optional.empty());
            when(subcategoryRepository.findById(any())).thenReturn(Optional.empty());

            ApiResponse response = converter.toApiResponse(api, t -> "name");

            assertNull(response.platformCredentialMissing());
            verify(credentialClient, never()).platformCredentialAvailable(anyString());
        }

        @Test
        @DisplayName("returns true when pricing is non-FREE and platform credential is unavailable")
        void shouldFlagMissingWhenPricingActiveButNoCredential() {
            UUID apiId = UUID.randomUUID();
            ApiEntity api = createApiEntity(apiId, UUID.randomUUID(), UUID.randomUUID());
            api.setPricingModel("FREEMIUM");
            api.setPlatformCredentialName("ably");

            when(apiToolRepository.findByApiId(apiId)).thenReturn(Collections.emptyList());
            when(categoryRepository.findById(any())).thenReturn(Optional.empty());
            when(subcategoryRepository.findById(any())).thenReturn(Optional.empty());
            when(credentialClient.platformCredentialAvailable("ably")).thenReturn(false);

            ApiResponse response = converter.toApiResponse(api, t -> "name", true);

            assertEquals(Boolean.TRUE, response.platformCredentialMissing());
            verify(credentialClient).platformCredentialAvailable("ably");
        }

        @Test
        @DisplayName("returns false when pricing is non-FREE and platform credential is available")
        void shouldFlagPresentWhenCredentialAvailable() {
            UUID apiId = UUID.randomUUID();
            ApiEntity api = createApiEntity(apiId, UUID.randomUUID(), UUID.randomUUID());
            api.setPricingModel("PAID");
            api.setPlatformCredentialName("stripe");

            when(apiToolRepository.findByApiId(apiId)).thenReturn(Collections.emptyList());
            when(categoryRepository.findById(any())).thenReturn(Optional.empty());
            when(subcategoryRepository.findById(any())).thenReturn(Optional.empty());
            when(credentialClient.platformCredentialAvailable("stripe")).thenReturn(true);

            ApiResponse response = converter.toApiResponse(api, t -> "name", true);

            assertEquals(Boolean.FALSE, response.platformCredentialMissing());
        }

        @Test
        @DisplayName("returns null (not applicable) when pricing model is FREE - nothing to warn about")
        void shouldSkipProbeForFreeApis() {
            UUID apiId = UUID.randomUUID();
            ApiEntity api = createApiEntity(apiId, UUID.randomUUID(), UUID.randomUUID());
            api.setPricingModel("FREE");
            api.setPlatformCredentialName("ably");

            when(apiToolRepository.findByApiId(apiId)).thenReturn(Collections.emptyList());
            when(categoryRepository.findById(any())).thenReturn(Optional.empty());
            when(subcategoryRepository.findById(any())).thenReturn(Optional.empty());

            ApiResponse response = converter.toApiResponse(api, t -> "name", true);

            assertNull(response.platformCredentialMissing());
            verify(credentialClient, never()).platformCredentialAvailable(anyString());
        }

        @Test
        @DisplayName("returns null when API has no platform_credential_name - nothing to probe")
        void shouldSkipProbeWhenNoIntegrationName() {
            UUID apiId = UUID.randomUUID();
            ApiEntity api = createApiEntity(apiId, UUID.randomUUID(), UUID.randomUUID());
            api.setPricingModel("FREEMIUM");
            api.setPlatformCredentialName(null);

            when(apiToolRepository.findByApiId(apiId)).thenReturn(Collections.emptyList());
            when(categoryRepository.findById(any())).thenReturn(Optional.empty());
            when(subcategoryRepository.findById(any())).thenReturn(Optional.empty());

            ApiResponse response = converter.toApiResponse(api, t -> "name", true);

            assertNull(response.platformCredentialMissing());
            verify(credentialClient, never()).platformCredentialAvailable(anyString());
        }

        @Test
        @DisplayName("fails open: when CredentialClient treats a probe failure as available, the flag is false (no spurious warnings)")
        void shouldNotFlagWhenProbeFailsOpen() {
            UUID apiId = UUID.randomUUID();
            ApiEntity api = createApiEntity(apiId, UUID.randomUUID(), UUID.randomUUID());
            api.setPricingModel("FREEMIUM");
            api.setPlatformCredentialName("ably");

            when(apiToolRepository.findByApiId(apiId)).thenReturn(Collections.emptyList());
            when(categoryRepository.findById(any())).thenReturn(Optional.empty());
            when(subcategoryRepository.findById(any())).thenReturn(Optional.empty());
            // CredentialClient's contract: transport error / null body → returns true (available).
            when(credentialClient.platformCredentialAvailable("ably")).thenReturn(true);

            ApiResponse response = converter.toApiResponse(api, t -> "name", true);

            assertEquals(Boolean.FALSE, response.platformCredentialMissing(),
                    "fail-open probe result must not surface as a 'missing' warning");
        }

        @Test
        @DisplayName("returns null for unrecognized pricing models - only FREEMIUM/PAID warrant the probe")
        void shouldSkipProbeForUnrecognizedPricingModel() {
            UUID apiId = UUID.randomUUID();
            ApiEntity api = createApiEntity(apiId, UUID.randomUUID(), UUID.randomUUID());
            api.setPricingModel("TRIAL"); // not in the allow-list
            api.setPlatformCredentialName("ably");

            when(apiToolRepository.findByApiId(apiId)).thenReturn(Collections.emptyList());
            when(categoryRepository.findById(any())).thenReturn(Optional.empty());
            when(subcategoryRepository.findById(any())).thenReturn(Optional.empty());

            ApiResponse response = converter.toApiResponse(api, t -> "name", true);

            assertNull(response.platformCredentialMissing());
            verify(credentialClient, never()).platformCredentialAvailable(anyString());
        }

        @Test
        @DisplayName("case-insensitive match: 'freemium' (lowercase) still triggers the probe")
        void shouldMatchPricingModelCaseInsensitively() {
            UUID apiId = UUID.randomUUID();
            ApiEntity api = createApiEntity(apiId, UUID.randomUUID(), UUID.randomUUID());
            api.setPricingModel("freemium"); // lowercase variant
            api.setPlatformCredentialName("ably");

            when(apiToolRepository.findByApiId(apiId)).thenReturn(Collections.emptyList());
            when(categoryRepository.findById(any())).thenReturn(Optional.empty());
            when(subcategoryRepository.findById(any())).thenReturn(Optional.empty());
            when(credentialClient.platformCredentialAvailable("ably")).thenReturn(false);

            ApiResponse response = converter.toApiResponse(api, t -> "name", true);

            assertEquals(Boolean.TRUE, response.platformCredentialMissing());
        }
    }

    // ========================================================================
    // Helper methods
    // ========================================================================

    private ApiEntity createApiEntity(UUID apiId, UUID categoryId, UUID subcategoryId) {
        ApiEntity api = new ApiEntity();
        api.setId(apiId);
        api.setApiName("Test API");
        api.setApiSlug("test-api");
        api.setDescription("A test API");
        api.setBaseUrl("https://api.test.com");
        api.setCategoryId(categoryId);
        api.setSubcategoryId(subcategoryId);
        api.setIsActive(true);
        api.setIsLocal(false);
        api.setCreatedAt(System.currentTimeMillis());
        api.setUpdatedAt(System.currentTimeMillis());
        api.setCreatedBy("user-123");
        api.setVisibility("PUBLIC");
        api.setIsPublic(true);
        api.setAuthType("API_KEY");
        api.setStatus("ACTIVE");
        return api;
    }

    private ApiToolEntity createToolEntity(UUID toolId, String protocol) {
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        tool.setEndpoint("/weather");
        tool.setMethod("GET");
        tool.setProtocol(protocol);
        tool.setDescription("Get weather data");
        tool.setIsActive(true);
        tool.setCreatedAt(System.currentTimeMillis());
        tool.setUpdatedAt(System.currentTimeMillis());
        tool.setStatus("ACTIVE");
        return tool;
    }
}
