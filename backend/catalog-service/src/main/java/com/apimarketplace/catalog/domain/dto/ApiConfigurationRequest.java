package com.apimarketplace.catalog.domain.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * DTO for complete API configuration from frontend
 */
public record ApiConfigurationRequest(
    @NotBlank(message = "API name is required")
    @Size(max = 100, message = "API name must not exceed 100 characters")
    String apiName,
    
    @NotBlank(message = "API description is required")
    @Size(max = 50000, message = "API description must not exceed 50000 characters")
    String apiDescription,
    
    @NotBlank(message = "Selected category is required")
    String selectedCategory,
    
    String categoryDescription,
    
    @NotBlank(message = "Selected subcategory is required")
    String selectedSubcategory,
    
    String subcategoryDescription,
    
    String subcategoryIconUrl,
    
    String categoryId,
    
    String subcategoryId,
    
    Boolean isCustomCategory,
    
    Boolean isCustomSubcategory,
    
    Boolean isLocal,
    
    // Icon slug for the API - if null, falls back to subcategory icon_slug
    String iconSlug,

    // Optional API slug - if provided, will be used instead of auto-generated slug
    String apiSlug,

    // Credential mode: 'user_key', 'platform_key', or 'both'
    String credentialMode,

    // Platform credential name for fallback keys
    String platformCredentialName,

    // Source of the API configuration (e.g. "import", "manual", "marketplace")
    String source,

    // Direct URL to the API icon image
    String iconUrl,

    String toolCategoryIconUrl,

    @NotNull(message = "API config is required")
    ApiConfigDto apiConfig,
    
    @NotNull(message = "Monetization config is required")
    MonetizationConfigDto monetization,
    
    @NotNull(message = "MCP tools are required")
    List<McpToolDto> mcpTools,

    // Stable UUID for catalog.apis.id - when provided, catalog-service writes it
    // verbatim as the primary key so re-imports are idempotent (see
    // scripts/api-migrations/SCHEMA.md). The migration importer path requires it
    // (ApiMigrationImporter fails loudly if missing); the legacy frontend
    // create-API flow leaves it null and falls back to gen_random_uuid().
    // Trailing position so existing positional callers stay backwards-compat
    // (see the no-apiId convenience constructor below).
    String apiId
) {

    /**
     * Backwards-compat convenience constructor that defaults {@code apiId} to
     * {@code null}. The legacy frontend create-API flow and the majority of
     * unit tests build this DTO positionally without needing to care about
     * stable UUIDs - only the catalog-service-import path (which reads
     * {@code apiId} from the JSON migration files) populates it.
     */
    public ApiConfigurationRequest(
            String apiName,
            String apiDescription,
            String selectedCategory,
            String categoryDescription,
            String selectedSubcategory,
            String subcategoryDescription,
            String subcategoryIconUrl,
            String categoryId,
            String subcategoryId,
            Boolean isCustomCategory,
            Boolean isCustomSubcategory,
            Boolean isLocal,
            String iconSlug,
            String apiSlug,
            String credentialMode,
            String platformCredentialName,
            String toolCategoryIconUrl,
            ApiConfigDto apiConfig,
            MonetizationConfigDto monetization,
            List<McpToolDto> mcpTools
    ) {
        this(apiName, apiDescription, selectedCategory, categoryDescription,
                selectedSubcategory, subcategoryDescription, subcategoryIconUrl,
                categoryId, subcategoryId, isCustomCategory, isCustomSubcategory,
                isLocal, iconSlug, apiSlug, credentialMode, platformCredentialName,
                null, null, toolCategoryIconUrl, apiConfig, monetization, mcpTools, null);
    }

    /**
     * API configuration details
     */
    public record ApiConfigDto(
        @NotBlank(message = "Base URL is required")
        @Size(max = 255, message = "Base URL must not exceed 255 characters")
        String baseUrl,
        
        @Size(max = 255, message = "Health check endpoint must not exceed 255 characters")
        String healthcheckEndpoint,
        
        @NotNull(message = "Authorization is required")
        AuthorizationDto authorization,
        
        @NotBlank(message = "Visibility is required")
        String visibility
    ) {}
    
    /**
     * Authorization configuration
     */
    public record AuthorizationDto(
        @NotBlank(message = "Authorization type is required")
        String type,
        
        @Size(max = 50000, message = "Description must not exceed 50000 characters")
        String description,
        
        @Size(max = 100, message = "Header name must not exceed 100 characters")
        String headerName,
        
        @Size(max = 500, message = "Header value must not exceed 500 characters")
        String headerValue
    ) {}
    
    /**
     * Monetization configuration
     */
    public record MonetizationConfigDto(
        @NotBlank(message = "Pricing model is required")
        String pricing,

        List<String> selectedPricingModels,
        
        // Rate limiting - for all plans
        RateLimitDto rateLimit,
        
        // Free requests - for FREEMIUM
        Integer freeRequestsPerUser,
        String freeRequestsType,
        Map<String, Object> toolFreeRequests,
        Map<String, ToolRateLimitsDto> toolRateLimits,
        
        // Pricing - for FREEMIUM only
        Integer uniformToolPrice,
        Double uniformToolPriceInDollars,
        Integer uniformCalls,
        Map<String, ToolPricingDto> toolPricing,
        
        // PAID monetization fields
        Map<String, Boolean> selectedPlans,
        Map<String, List<String>> planTools,
        
        // Plan pricing and configuration
        Double priceBasic,
        Double pricePro,
        Double priceUltra,
        Double priceMega,
        
        Integer quotaBasic,
        Integer quotaPro,
        Integer quotaUltra,
        Integer quotaMega,
        
        Integer rpsBasic,
        Integer rpsPro,
        Integer rpsUltra,
        Integer rpsMega,
        
        String rpsPeriodBasic,
        String rpsPeriodPro,
        String rpsPeriodUltra,
        String rpsPeriodMega,
        
        Double overusageCostBasic,
        Double overusageCostPro,
        Double overusageCostUltra,
        Double overusageCostMega,
        
        Boolean hardLimitBasic,
        Boolean hardLimitPro,
        Boolean hardLimitUltra,
        Boolean hardLimitMega
    ) {}
    
    /**
     * Rate limiting configuration
     */
    public record RateLimitDto(
        Integer requests,
        String period
    ) {}
    
    
    /**
     * Tool rate limits configuration
     */
    public record ToolRateLimitsDto(
        Integer requests,
        String period
    ) {}
    
    /**
     * Tool pricing configuration
     */
    public record ToolPricingDto(
        String id,
        Integer mauValue,
        Double price,
        String currency,
        Integer calls // Number of calls per MAU
    ) {}
    
    /**
     * Pricing models update request (simplified)
     * Only requires API ID and selected pricing models
     * Backend will create monetization configurations with default values
     */
    public record PricingModelsUpdateRequest(
        @NotBlank(message = "API ID is required")
        String apiId,
        
        @NotNull(message = "Selected pricing models are required")
        List<String> selectedPricingModels
    ) {}

    /**
     * Request for updating FREEMIUM configuration for a specific tool
     */
    public record ToolFreemiumConfigUpdateRequest(
        @NotBlank(message = "API ID is required")
        String apiId,
        
        @NotBlank(message = "API Tool ID is required")
        String apiToolId,
        
        @NotBlank(message = "Monetization type is required")
        String monetizationType,
        
        @NotNull(message = "Tool configuration is required")
        ToolFreemiumConfigDto config
    ) {}

    /**
     * Request for batch updating FREEMIUM configuration for multiple tools
     */
    public record BatchToolFreemiumConfigUpdateRequest(
        @NotBlank(message = "API ID is required")
        String apiId,
        
        @NotBlank(message = "Monetization type is required")
        String monetizationType,
        
        @NotNull(message = "Tools configuration is required")
        Map<String, ToolFreemiumConfigDto> toolsConfig
    ) {}

    /**
     * FREEMIUM configuration for a specific tool
     */
    public record ToolFreemiumConfigDto(
        Integer freeRequests,
        String freeRequestsType,
        Integer rateLimitRequests,
        String rateLimitPeriod,
        Integer mauValue,
        Integer calls
    ) {}

    /**
     * Request for updating PAID configuration for a specific tool
     */
    public record ToolPaidConfigUpdateRequest(
        @NotBlank(message = "API ID is required")
        String apiId,
        
        @NotBlank(message = "API Tool ID is required")
        String apiToolId,
        
        @NotBlank(message = "Monetization type is required")
        String monetizationType,
        
        @NotNull(message = "Tool configuration is required")
        ToolPaidConfigDto config
    ) {}

    /**
     * Request for batch updating PAID configuration for multiple tools
     */
    public record BatchToolPaidConfigUpdateRequest(
        @NotBlank(message = "API ID is required")
        String apiId,
        
        @NotBlank(message = "Monetization type is required")
        String monetizationType,
        
        @NotNull(message = "Tools configuration is required")
        Map<String, ToolPaidConfigDto> toolsConfig
    ) {}

    /**
     * PAID configuration for a specific tool
     */
    public record ToolPaidConfigDto(
        String planName,
        Integer quota,
        BigDecimal price,
        BigDecimal overusageCost,
        Boolean hardLimit,
        Integer rateLimitRequests,
        String rateLimitPeriod
    ) {}
    
    /**
     * Request for updating PAID plans selection
     */
    public record PaidPlansUpdateRequest(
        @NotNull(message = "Selected plans are required")
        Map<String, Boolean> selectedPlans,
        
        @NotNull(message = "Plan tools are required")
        Map<String, List<String>> planTools
    ) {}
    
    /**
     * MCP Tool configuration
     */
    public record McpToolDto(
        String id, // Optional - will be generated by backend if not provided
        
        @NotBlank(message = "Tool name is required")
        @Size(max = 100, message = "Tool name must not exceed 100 characters")
        String name,
        
        @NotBlank(message = "Tool description is required")
        @Size(max = 250, message = "Tool description must not exceed 250 characters")
        String description,
        
        @NotBlank(message = "Endpoint is required")
        @Size(max = 255, message = "Endpoint must not exceed 255 characters")
        String endpoint,
        
        @NotBlank(message = "Method is required")
        @Size(max = 10, message = "Method must not exceed 10 characters")
        String method,

        String protocol,
        JsonNode runtimeMetadata,

        @Size(max = 50, message = "Tool category must not exceed 50 characters")
        String toolCategory,
        String toolCategoryDescription,
        String toolCategoryIconUrl,
        
        String toolNameId, // ID du nom d'outil dans la DB
        Boolean isCustomCategory, // Indique si c'est une categorie personnalisee
        Boolean isCustomToolName, // Indique si c'est un nom d'outil personnalise
        
        List<HeaderDto> headers,
        List<PathParameterDto> pathParameters,
        List<QueryParameterDto> queryParameters,
        List<BodyParamDto> bodyParams,
        Map<String, String> defaultHeaders,
        ResponseDto response,
        SqlConfigDto sqlConfig,
        AmqpConfigDto amqpConfig,
        KafkaConfigDto kafkaConfig,
        MqttConfigDto mqttConfig,
        RedisConfigDto redisConfig,

        // Typed-execution refactor (V52). All three are persisted on api_tools.
        // Trailing position so existing positional callers stay backwards-compat.
        // executionSpec: declarative request/response handling
        // outputSchema:  typed output fields (OutputFieldDef shape)
        // executionMode: denormalized from executionSpec.mode for indexed lookups
        JsonNode executionSpec,
        JsonNode outputSchema,
        String executionMode,

        // Custom API registration fields (V83). Trailing for backwards compat.
        // synthesis:   lexical search index data for agent discovery
        // pagination:  pagination config for paginated endpoints
        // nextHint:    hint for LLM about what to do after using this tool
        JsonNode synthesis,
        JsonNode pagination,
        String nextHint,

        // V166: per-endpoint OAuth scope requirements. Optional - null when the
        // endpoint has no scope requirement (95% of catalog). Persisted on
        // api_tools.required_scopes by ApiSubmissionOrchestrator. Read by
        // HttpExecutionService.preflightScopeCheck before credential resolution.
        @com.fasterxml.jackson.annotation.JsonAlias({"required_scopes"})
        java.util.List<String> requiredScopes
    ) {}

    public record SqlConfigDto(
        String dialect,
        String query,
        String defaultSchema,
        String defaultTable,
        String resultMode,
        JsonNode parameters
    ) {}

    public record AmqpConfigDto(
        String brokerUrl,
        String virtualHost,
        String exchangeName,
        String queueName,
        String routingKey,
        Integer prefetchCount,
        String ackMode,
        Boolean sslEnabled,
        String options
    ) {}

    public record KafkaConfigDto(
        String brokers,
        String topic,
        String consumerGroup,
        String securityProtocol,
        String saslMechanism,
        String saslUsername,
        String saslPassword,
        Boolean sslEnabled
    ) {}

    public record MqttConfigDto(
        String brokerUrl,
        String topics,
        Integer qos,
        Boolean retain,
        String clientId,
        String username,
        String password,
        Boolean useTls
    ) {}

    public record RedisConfigDto(
        String host,
        Integer port,
        Integer dbIndex,
        String channels,
        Boolean useTls,
        String username,
        String password
    ) {}
    
    /**
     * Header configuration
     */
    public record HeaderDto(
        String name,
        String value,
        Boolean required,
        String defaultValue,
        List<String> allowedValues,
        JsonNode extras
    ) {}

    /**
     * Path parameter configuration
     */
    public record PathParameterDto(
        String name,
        String type,
        Boolean required,
        String description,
        String example,
        String pattern,
        String defaultValue,
        List<String> allowedValues,
        JsonNode extras
    ) {}

    /**
     * Query parameter configuration
     */
    public record QueryParameterDto(
        String name,
        String type,
        Boolean required,
        String description,
        String example,
        String defaultValue,
        List<String> allowedValues,
        JsonNode extras
    ) {}

    /**
     * Body parameter configuration
     */
    public record BodyParamDto(
        String name,
        String value,
        String type,
        Boolean required,
        String description,
        String fileSize,
        String defaultValue,
        List<String> allowedValues,
        JsonNode extras
    ) {}
    
    /**
     * Response configuration
     */
    public record ResponseDto(
        String type,
        String description,
        Integer statusCode,
        JsonNode example,
        JsonNode exampleJsonb,
        JsonNode schema,
        String format,
        Boolean isDefault,
        Boolean isActive
    ) {}
}
