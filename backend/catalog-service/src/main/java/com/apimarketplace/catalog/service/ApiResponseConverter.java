package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.*;
import com.apimarketplace.catalog.domain.dto.ApiResponse;
import com.apimarketplace.catalog.domain.dto.MonetizationResponse;
import com.apimarketplace.catalog.repository.*;
import com.apimarketplace.catalog.service.monetization.MonetizationService;
import com.apimarketplace.credential.client.CredentialClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;

/**
 * Service for converting API entities to response DTOs.
 * Centralizes all entity-to-DTO conversion logic.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ApiResponseConverter {

    private final ApiToolRepository apiToolRepository;
    private final ApiCategoryRepository categoryRepository;
    private final ApiSubcategoryRepository subcategoryRepository;
    private final ApiToolSqlConfigRepository sqlConfigRepository;
    private final ApiToolAmqpConfigRepository amqpConfigRepository;
    private final ApiToolKafkaConfigRepository kafkaConfigRepository;
    private final ApiToolMqttConfigRepository mqttConfigRepository;
    private final ApiToolRedisConfigRepository redisConfigRepository;
    private final ApiToolParameterService parameterService;
    private final MonetizationService monetizationService;
    private final ToolCategoryService toolCategoryService;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final CredentialClient credentialClient;

    /**
     * Convert ApiEntity to ApiResponse without the platform-credential availability
     * probe (cheap path, used by list endpoints).
     */
    public ApiResponse toApiResponse(ApiEntity api, Function<ApiToolEntity, String> toolNameResolver) {
        return toApiResponse(api, toolNameResolver, false);
    }

    /**
     * Convert ApiEntity to ApiResponse.
     *
     * @param checkPlatformCredential when {@code true} and the API has a non-FREE
     *     pricing model, query auth-service to populate
     *     {@link ApiResponse#platformCredentialMissing()}. Adds one HTTP call, so
     *     callers on list endpoints should pass {@code false}.
     */
    public ApiResponse toApiResponse(ApiEntity api,
                                     Function<ApiToolEntity, String> toolNameResolver,
                                     boolean checkPlatformCredential) {
        List<ApiToolEntity> tools = apiToolRepository.findByApiId(api.getId());
        List<ApiResponse.ToolResponse> toolResponses = tools.stream()
                .map(tool -> toToolResponse(tool, toolNameResolver))
                .toList();

        String categoryName = categoryRepository.findById(api.getCategoryId())
                .map(ApiCategoryEntity::getName)
                .orElse("Unknown");

        String subcategoryName = subcategoryRepository.findById(api.getSubcategoryId())
                .map(ApiSubcategoryEntity::getName)
                .orElse("Unknown");

        Boolean platformCredentialMissing = checkPlatformCredential
                ? computePlatformCredentialMissing(api)
                : null;

        return new ApiResponse(
                api.getId(),
                api.getApiName(),
                api.getApiSlug(),
                api.getDescription(),
                api.getBaseUrl(),
                api.getCategoryId(),
                categoryName,
                api.getSubcategoryId(),
                subcategoryName,
                api.getIsActive(),
                api.getIsLocal(),
                api.getCreatedAt(),
                api.getUpdatedAt(),
                api.getCreatedBy(),
                toolResponses,
                api.getHealthcheckEndpoint(),
                api.getVisibility(),
                api.getIsPublic(),
                api.getAuthType(),
                api.getAuthHeaderName(),
                api.getAuthHeaderValue(),
                api.getPricingModel(),
                api.getStatus(),
                platformCredentialMissing
        );
    }

    /**
     * Returns {@code true} when the API advertises a paid pricing model
     * ({@code FREEMIUM} or {@code PAID}) but no usable platform credential is
     * stored for its integration name. Null when the API's pricing model is
     * FREE, unrecognized, or missing, or when there is no
     * {@code platform_credential_name} (nothing to warn about). Fails open via
     * {@link CredentialClient}: transient auth-service errors are treated as
     * "credential present".
     */
    private Boolean computePlatformCredentialMissing(ApiEntity api) {
        String pricingModel = api.getPricingModel();
        if (pricingModel == null || pricingModel.isBlank()) {
            return null;
        }
        String normalized = pricingModel.trim().toUpperCase(Locale.ROOT);
        if (!"FREEMIUM".equals(normalized) && !"PAID".equals(normalized)) {
            return null;
        }
        String integrationName = api.getPlatformCredentialName();
        if (integrationName == null || integrationName.isBlank()) {
            return null;
        }
        boolean available = credentialClient.platformCredentialAvailable(integrationName);
        if (!available) {
            log.warn("API '{}' (id={}) has pricing='{}' but platform credential '{}' is missing or incomplete",
                    api.getApiName(), api.getId(), pricingModel, integrationName);
        }
        return !available;
    }

    /**
     * Convert ApiToolEntity to ToolResponse.
     */
    public ApiResponse.ToolResponse toToolResponse(ApiToolEntity tool, Function<ApiToolEntity, String> toolNameResolver) {
        List<ApiResponse.ParameterResponse> parameters = parameterService.getToolParameters(tool.getId());
        List<MonetizationResponse> monetization = monetizationService.getToolMonetization(tool.getId());
        ApiResponse.ToolCategoryInfo toolCategories = getToolCategoryInfo(tool.getId());

        return new ApiResponse.ToolResponse(
                tool.getId(),
                toolNameResolver.apply(tool),
                tool.getDescription(),
                tool.getEndpoint(),
                tool.getMethod(),
                tool.getProtocol(),
                parseRuntimeMetadata(tool),
                toSqlConfigResponse(tool.getId()),
                toAmqpConfigResponse(tool),
                toKafkaConfigResponse(tool),
                toMqttConfigResponse(tool),
                toRedisConfigResponse(tool),
                tool.getIsActive(),
                tool.getCreatedAt(),
                tool.getUpdatedAt(),
                parameters,
                monetization,
                tool.getStatus(),
                toolCategories
        );
    }

    private JsonNode parseRuntimeMetadata(ApiToolEntity tool) {
        if (tool.getRuntimeMetadata() == null || tool.getRuntimeMetadata().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(tool.getRuntimeMetadata());
        } catch (Exception e) {
            log.warn("Unable to parse runtime metadata for tool {}: {}", tool.getId(), e.getMessage());
            return null;
        }
    }

    private ApiResponse.SqlConfigResponse toSqlConfigResponse(UUID toolId) {
        return sqlConfigRepository.findByApiToolId(toolId)
                .map(cfg -> new ApiResponse.SqlConfigResponse(
                        cfg.getDialect(),
                        cfg.getQueryTemplate(),
                        cfg.getDefaultSchema(),
                        cfg.getDefaultTable(),
                        cfg.getResultMode(),
                        cfg.getParameterMapping()
                ))
                .orElse(null);
    }

    private ApiResponse.AmqpConfigResponse toAmqpConfigResponse(ApiToolEntity tool) {
        if (!isProtocol(tool, "AMQP")) return null;
        return amqpConfigRepository.findByApiToolId(tool.getId())
                .map(cfg -> new ApiResponse.AmqpConfigResponse(
                        cfg.getBrokerUrl(),
                        cfg.getVirtualHost(),
                        cfg.getExchangeName(),
                        cfg.getQueueName(),
                        cfg.getRoutingKey(),
                        cfg.getPrefetchCount(),
                        cfg.getAckMode(),
                        cfg.getSslEnabled(),
                        cfg.getOptions()
                ))
                .orElse(null);
    }

    private ApiResponse.KafkaConfigResponse toKafkaConfigResponse(ApiToolEntity tool) {
        if (!isProtocol(tool, "KAFKA")) return null;
        return kafkaConfigRepository.findByApiToolId(tool.getId())
                .map(cfg -> new ApiResponse.KafkaConfigResponse(
                        cfg.getBrokers(),
                        cfg.getTopic(),
                        cfg.getConsumerGroup(),
                        cfg.getSecurityProtocol(),
                        cfg.getSaslMechanism(),
                        cfg.getSaslUsername(),
                        cfg.getSslEnabled()
                ))
                .orElse(null);
    }

    private ApiResponse.MqttConfigResponse toMqttConfigResponse(ApiToolEntity tool) {
        if (!isProtocol(tool, "MQTT")) return null;
        return mqttConfigRepository.findByApiToolId(tool.getId())
                .map(cfg -> new ApiResponse.MqttConfigResponse(
                        cfg.getBrokerUrl(),
                        cfg.getTopics(),
                        cfg.getQos(),
                        cfg.getRetain(),
                        cfg.getClientId(),
                        cfg.getUsername(),
                        cfg.getUseTls()
                ))
                .orElse(null);
    }

    private ApiResponse.RedisConfigResponse toRedisConfigResponse(ApiToolEntity tool) {
        if (!isProtocol(tool, "REDIS")) return null;
        return redisConfigRepository.findByApiToolId(tool.getId())
                .map(cfg -> new ApiResponse.RedisConfigResponse(
                        cfg.getHost(),
                        cfg.getPort(),
                        cfg.getDbIndex(),
                        cfg.getChannels(),
                        cfg.getUseTls(),
                        cfg.getUsername()
                ))
                .orElse(null);
    }

    private boolean isProtocol(ApiToolEntity tool, String protocol) {
        return tool.getProtocol() != null && tool.getProtocol().equalsIgnoreCase(protocol);
    }

    private ApiResponse.ToolCategoryInfo getToolCategoryInfo(UUID apiToolId) {
        try {
            String toolNameId = getToolNameIdByApiToolId(apiToolId);
            if (toolNameId == null) return null;

            String sql = """
                SELECT tc.id, tc.name, tc.slug, tc.description, tc.icon, tc.color,
                       tc.sort_order, tc.is_active, tc.created_at, tc.updated_at
                FROM tool_categories tc
                INNER JOIN tool_names tn ON tc.id = tn.tool_category_id
                WHERE tn.id = ?
                """;

            List<ApiResponse.ToolCategoryInfo> categories = jdbcTemplate.query(sql, (rs, rowNum) ->
                    new ApiResponse.ToolCategoryInfo(
                            rs.getObject("id", UUID.class).toString(),
                            rs.getString("name"),
                            rs.getString("slug"),
                            rs.getString("description"),
                            rs.getString("icon"),
                            rs.getString("color"),
                            rs.getObject("sort_order", Integer.class),
                            rs.getObject("is_active", Boolean.class),
                            rs.getObject("created_at", Long.class),
                            rs.getObject("updated_at", Long.class)
                    ), UUID.fromString(toolNameId));

            return categories.isEmpty() ? null : categories.get(0);
        } catch (Exception e) {
            log.error("Error getting tool category info for API tool ID {}: {}", apiToolId, e.getMessage());
            return null;
        }
    }

    private String getToolNameIdByApiToolId(UUID apiToolId) {
        try {
            List<String> results = jdbcTemplate.query(
                    "SELECT tool_name_id FROM api_tools WHERE id = ?",
                    (rs, rowNum) -> rs.getString("tool_name_id"),
                    apiToolId
            );
            return results.isEmpty() ? null : results.get(0);
        } catch (Exception e) {
            log.error("Error getting tool name ID for API tool ID {}: {}", apiToolId, e.getMessage());
            return null;
        }
    }
}
