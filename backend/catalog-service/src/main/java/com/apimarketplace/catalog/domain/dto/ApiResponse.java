package com.apimarketplace.catalog.domain.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * DTO for API response
 */
public record ApiResponse(
    UUID id,
    String apiName,
    String apiSlug,
    String description,
    String baseUrl,
    UUID categoryId,
    String categoryName,
    UUID subcategoryId,
    String subcategoryName,
    Boolean isActive,
    Boolean isLocal,
    Long createdAt,
    Long updatedAt,
    String createdBy,
    List<ToolResponse> tools,
    // API Configuration fields
    String healthcheckEndpoint,
    String visibility,
    Boolean isPublic,
    String authType,
    String authHeaderName,
    String authHeaderValue,
    String pricingModel,
    String status,
    Boolean platformCredentialMissing
) {
    
    /**
     * DTO for tool response
     */
    public record ToolResponse(
        UUID id,
        String name,
        String description,
        String endpoint,
        String method,
        String protocol,
        JsonNode runtimeMetadata,
        SqlConfigResponse sqlConfig,
        AmqpConfigResponse amqpConfig,
        KafkaConfigResponse kafkaConfig,
        MqttConfigResponse mqttConfig,
        RedisConfigResponse redisConfig,
        Boolean isActive,
        Long createdAt,
        Long updatedAt,
        List<ParameterResponse> parameters,
        List<MonetizationResponse> monetization,
        String status,
        ToolCategoryInfo toolCategories
    ) {}
    
    /**
     * DTO for tool category information
     */
    public record ToolCategoryInfo(
        String id,
        String name,
        String slug,
        String description,
        String icon,
        String color,
        Integer sortOrder,
        Boolean isActive,
        Long createdAt,
        Long updatedAt
    ) {}

    public record SqlConfigResponse(
        String dialect,
        String queryTemplate,
        String defaultSchema,
        String defaultTable,
        String resultMode,
        String parameterMapping
    ) {}

    public record AmqpConfigResponse(
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

    public record KafkaConfigResponse(
        String brokers,
        String topic,
        String consumerGroup,
        String securityProtocol,
        String saslMechanism,
        String saslUsername,
        Boolean sslEnabled
    ) {}

    public record MqttConfigResponse(
        String brokerUrl,
        String topics,
        Integer qos,
        Boolean retain,
        String clientId,
        String username,
        Boolean useTls
    ) {}

    public record RedisConfigResponse(
        String host,
        Integer port,
        Integer dbIndex,
        String channels,
        Boolean useTls,
        String username
    ) {}
    
    /**
     * DTO for parameter response
     */
    public record ParameterResponse(
        UUID id,
        String name,
        String type,
        String description,
        Boolean required,
        String defaultValue,
        String exampleValue,
        String parameterType,
        /**
         * Closed enumeration of admissible values declared in the API migration JSON
         * (e.g. all model IDs for OpenAI's chat-completion). Populated from the
         * {@code api_tool_parameters.allowed_values} column. {@code null} when the
         * param has no documented enum.
         */
        List<String> allowedValues
    ) {}
}
