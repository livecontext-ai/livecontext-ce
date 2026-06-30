package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.dto.ApiConfigurationRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * Converts ApiConfigurationRequest DTO to JsonNode for API submission processing.
 */
public class ApiConfigurationConverter {

    private final ObjectMapper objectMapper;

    public ApiConfigurationConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode toJsonNode(ApiConfigurationRequest request) {
        Map<String, Object> data = new HashMap<>();

        // Stable UUID path: only forward apiId when explicitly provided (importer).
        // The legacy frontend create-API flow leaves this null and relies on the
        // Postgres gen_random_uuid() default for catalog.apis.id.
        if (request.apiId() != null && !request.apiId().isBlank()) {
            data.put("apiId", request.apiId());
        }

        // Basic info
        data.put("apiName", request.apiName());
        data.put("apiDescription", request.apiDescription());
        data.put("selectedCategory", request.selectedCategory());
        data.put("categoryDescription", request.categoryDescription());
        data.put("selectedSubcategory", request.selectedSubcategory());
        data.put("subcategoryDescription", request.subcategoryDescription());
        data.put("subcategoryIconUrl", request.subcategoryIconUrl());
        data.put("categoryId", request.categoryId());
        data.put("subcategoryId", request.subcategoryId());
        data.put("isCustomCategory", request.isCustomCategory());
        data.put("isCustomSubcategory", request.isCustomSubcategory());
        data.put("isLocal", request.isLocal() != null && request.isLocal());
        data.put("iconSlug", request.iconSlug());
        data.put("source", request.source());
        data.put("iconUrl", request.iconUrl());

        // API config
        data.put("apiConfig", convertApiConfig(request.apiConfig()));

        // Monetization
        data.put("monetization", convertMonetization(request.monetization()));

        // MCP tools
        data.put("mcpTools", convertMcpTools(request.mcpTools()));

        return objectMapper.valueToTree(data);
    }

    private Map<String, Object> convertApiConfig(ApiConfigurationRequest.ApiConfigDto config) {
        Map<String, Object> apiConfig = new HashMap<>();
        apiConfig.put("baseUrl", config.baseUrl());
        apiConfig.put("healthcheckEndpoint", config.healthcheckEndpoint());
        apiConfig.put("visibility", config.visibility());

        Map<String, Object> authorization = new HashMap<>();
        authorization.put("type", config.authorization().type());
        authorization.put("description", config.authorization().description());
        authorization.put("headerName", config.authorization().headerName());
        authorization.put("headerValue", config.authorization().headerValue());
        apiConfig.put("authorization", authorization);

        return apiConfig;
    }

    private Map<String, Object> convertMonetization(ApiConfigurationRequest.MonetizationConfigDto mon) {
        Map<String, Object> monetization = new HashMap<>();
        monetization.put("pricing", mon.pricing());
        monetization.put("selectedPricingModels", mon.selectedPricingModels());

        if (mon.rateLimit() != null) {
            Map<String, Object> rateLimit = new HashMap<>();
            rateLimit.put("requests", mon.rateLimit().requests());
            rateLimit.put("period", mon.rateLimit().period());
            monetization.put("rateLimit", rateLimit);
        }

        monetization.put("freeRequestsPerUser", mon.freeRequestsPerUser());
        monetization.put("freeRequestsType", mon.freeRequestsType());

        // Tool free requests
        Map<String, Object> toolFreeRequests = new HashMap<>();
        if (mon.toolFreeRequests() != null) {
            toolFreeRequests.putAll(mon.toolFreeRequests());
        }
        monetization.put("toolFreeRequests", toolFreeRequests);

        // Tool rate limits
        Map<String, Object> toolRateLimits = new HashMap<>();
        if (mon.toolRateLimits() != null) {
            for (Map.Entry<String, ApiConfigurationRequest.ToolRateLimitsDto> entry : mon.toolRateLimits().entrySet()) {
                Map<String, Object> rateLimit = new HashMap<>();
                rateLimit.put("requests", entry.getValue().requests());
                rateLimit.put("period", entry.getValue().period());
                toolRateLimits.put(entry.getKey(), rateLimit);
            }
        }
        monetization.put("toolRateLimits", toolRateLimits);

        // Pricing
        monetization.put("uniformToolPrice", mon.uniformToolPrice());
        monetization.put("uniformToolPriceInDollars", mon.uniformToolPriceInDollars());
        monetization.put("uniformCalls", mon.uniformCalls());

        // Tool pricing
        Map<String, Object> toolPricing = new HashMap<>();
        if (mon.toolPricing() != null) {
            for (Map.Entry<String, ApiConfigurationRequest.ToolPricingDto> entry : mon.toolPricing().entrySet()) {
                Map<String, Object> pricing = new HashMap<>();
                pricing.put("mauValue", entry.getValue().mauValue());
                pricing.put("price", entry.getValue().price());
                pricing.put("currency", entry.getValue().currency());
                pricing.put("calls", entry.getValue().calls());
                toolPricing.put(entry.getKey(), pricing);
            }
        }
        monetization.put("toolPricing", toolPricing);

        // PAID monetization
        if (mon.selectedPlans() != null) monetization.put("selectedPlans", mon.selectedPlans());
        if (mon.planTools() != null) monetization.put("planTools", mon.planTools());

        monetization.put("priceBasic", mon.priceBasic());
        monetization.put("pricePro", mon.pricePro());
        monetization.put("priceUltra", mon.priceUltra());
        monetization.put("priceMega", mon.priceMega());

        monetization.put("quotaBasic", mon.quotaBasic());
        monetization.put("quotaPro", mon.quotaPro());
        monetization.put("quotaUltra", mon.quotaUltra());
        monetization.put("quotaMega", mon.quotaMega());

        monetization.put("rpsBasic", mon.rpsBasic());
        monetization.put("rpsPro", mon.rpsPro());
        monetization.put("rpsUltra", mon.rpsUltra());
        monetization.put("rpsMega", mon.rpsMega());

        monetization.put("rpsPeriodBasic", mon.rpsPeriodBasic());
        monetization.put("rpsPeriodPro", mon.rpsPeriodPro());
        monetization.put("rpsPeriodUltra", mon.rpsPeriodUltra());
        monetization.put("rpsPeriodMega", mon.rpsPeriodMega());

        monetization.put("overusageCostBasic", mon.overusageCostBasic());
        monetization.put("overusageCostPro", mon.overusageCostPro());
        monetization.put("overusageCostUltra", mon.overusageCostUltra());
        monetization.put("overusageCostMega", mon.overusageCostMega());

        monetization.put("hardLimitBasic", mon.hardLimitBasic());
        monetization.put("hardLimitPro", mon.hardLimitPro());
        monetization.put("hardLimitUltra", mon.hardLimitUltra());
        monetization.put("hardLimitMega", mon.hardLimitMega());

        return monetization;
    }

    private List<Map<String, Object>> convertMcpTools(List<ApiConfigurationRequest.McpToolDto> mcpTools) {
        List<Map<String, Object>> tools = new ArrayList<>();
        if (mcpTools == null) return tools;

        for (ApiConfigurationRequest.McpToolDto tool : mcpTools) {
            Map<String, Object> toolData = new HashMap<>();
            toolData.put("id", tool.id());
            toolData.put("name", tool.name());
            toolData.put("description", tool.description());
            toolData.put("endpoint", tool.endpoint());
            toolData.put("method", tool.method());
            toolData.put("protocol", tool.protocol() == null ? "HTTP" : tool.protocol());
            toolData.put("toolCategory", tool.toolCategory());
            toolData.put("toolCategoryDescription", tool.toolCategoryDescription());
            toolData.put("toolCategoryIconUrl", tool.toolCategoryIconUrl());
            toolData.put("toolNameId", tool.toolNameId());
            toolData.put("isCustomCategory", tool.isCustomCategory());
            toolData.put("isCustomToolName", tool.isCustomToolName());

            if (tool.runtimeMetadata() != null && !tool.runtimeMetadata().isNull()) {
                toolData.put("runtimeMetadata", tool.runtimeMetadata());
            }

            convertProtocolConfigs(tool, toolData);
            convertParameters(tool, toolData);

            if (tool.defaultHeaders() != null) toolData.put("defaultHeaders", tool.defaultHeaders());
            if (tool.response() != null) toolData.put("response", convertResponse(tool.response()));

            // V52 typed-execution refactor: forward the three declarative fields so
            // ApiSubmissionOrchestrator can persist them on api_tools. Without this
            // wiring, executionSpec/outputSchema would silently drop on the wire and
            // every imported tool would land with execution_mode=sync and NULL spec.
            if (tool.executionSpec() != null && !tool.executionSpec().isNull()) {
                toolData.put("executionSpec", tool.executionSpec());
            }
            if (tool.outputSchema() != null && !tool.outputSchema().isNull()) {
                toolData.put("outputSchema", tool.outputSchema());
            }
            if (tool.executionMode() != null && !tool.executionMode().isBlank()) {
                toolData.put("executionMode", tool.executionMode());
            }

            // V83 custom API registration fields
            if (tool.synthesis() != null && !tool.synthesis().isNull()) {
                toolData.put("synthesis", tool.synthesis());
            }
            if (tool.pagination() != null && !tool.pagination().isNull()) {
                toolData.put("pagination", tool.pagination());
            }
            if (tool.nextHint() != null && !tool.nextHint().isBlank()) {
                toolData.put("nextHint", tool.nextHint());
            }

            // V166: per-endpoint OAuth scope requirements. Forward when present so
            // ApiSubmissionOrchestrator persists onto api_tools.required_scopes.
            // Always forward (including empty list) to preserve "clear stale data
            // on re-import" semantics - toolData.put with empty list still serializes
            // as a valid JSON array that the orchestrator's size>0 guard handles.
            if (tool.requiredScopes() != null) {
                toolData.put("requiredScopes", tool.requiredScopes());
            }

            tools.add(toolData);
        }
        return tools;
    }

    private void convertProtocolConfigs(ApiConfigurationRequest.McpToolDto tool, Map<String, Object> toolData) {
        if (tool.sqlConfig() != null) {
            Map<String, Object> sqlConfig = new HashMap<>();
            sqlConfig.put("dialect", tool.sqlConfig().dialect());
            sqlConfig.put("query", tool.sqlConfig().query());
            sqlConfig.put("defaultSchema", tool.sqlConfig().defaultSchema());
            sqlConfig.put("defaultTable", tool.sqlConfig().defaultTable());
            sqlConfig.put("resultMode", tool.sqlConfig().resultMode());
            if (tool.sqlConfig().parameters() != null) {
                sqlConfig.put("parameters", tool.sqlConfig().parameters());
            }
            toolData.put("sqlConfig", sqlConfig);
        }

        if (tool.amqpConfig() != null) {
            Map<String, Object> config = new HashMap<>();
            config.put("brokerUrl", tool.amqpConfig().brokerUrl());
            config.put("virtualHost", tool.amqpConfig().virtualHost());
            config.put("exchangeName", tool.amqpConfig().exchangeName());
            config.put("queueName", tool.amqpConfig().queueName());
            config.put("routingKey", tool.amqpConfig().routingKey());
            config.put("prefetchCount", tool.amqpConfig().prefetchCount());
            config.put("ackMode", tool.amqpConfig().ackMode());
            config.put("sslEnabled", tool.amqpConfig().sslEnabled());
            config.put("options", tool.amqpConfig().options());
            toolData.put("amqpConfig", config);
        }

        if (tool.kafkaConfig() != null) {
            Map<String, Object> config = new HashMap<>();
            config.put("brokers", tool.kafkaConfig().brokers());
            config.put("topic", tool.kafkaConfig().topic());
            config.put("consumerGroup", tool.kafkaConfig().consumerGroup());
            config.put("securityProtocol", tool.kafkaConfig().securityProtocol());
            config.put("saslMechanism", tool.kafkaConfig().saslMechanism());
            config.put("saslUsername", tool.kafkaConfig().saslUsername());
            config.put("saslPassword", tool.kafkaConfig().saslPassword());
            config.put("sslEnabled", tool.kafkaConfig().sslEnabled());
            toolData.put("kafkaConfig", config);
        }

        if (tool.mqttConfig() != null) {
            Map<String, Object> config = new HashMap<>();
            config.put("brokerUrl", tool.mqttConfig().brokerUrl());
            config.put("topics", tool.mqttConfig().topics());
            config.put("qos", tool.mqttConfig().qos());
            config.put("retain", tool.mqttConfig().retain());
            config.put("clientId", tool.mqttConfig().clientId());
            config.put("username", tool.mqttConfig().username());
            config.put("password", tool.mqttConfig().password());
            config.put("useTls", tool.mqttConfig().useTls());
            toolData.put("mqttConfig", config);
        }

        if (tool.redisConfig() != null) {
            Map<String, Object> config = new HashMap<>();
            config.put("host", tool.redisConfig().host());
            config.put("port", tool.redisConfig().port());
            config.put("dbIndex", tool.redisConfig().dbIndex());
            config.put("channels", tool.redisConfig().channels());
            config.put("useTls", tool.redisConfig().useTls());
            config.put("username", tool.redisConfig().username());
            config.put("password", tool.redisConfig().password());
            toolData.put("redisConfig", config);
        }
    }

    private void convertParameters(ApiConfigurationRequest.McpToolDto tool, Map<String, Object> toolData) {
        if (tool.headers() != null) {
            List<Map<String, Object>> headers = new ArrayList<>();
            for (ApiConfigurationRequest.HeaderDto header : tool.headers()) {
                Map<String, Object> h = new HashMap<>();
                h.put("name", header.name());
                h.put("value", header.value());
                h.put("required", header.required());
                // Propagate default + allowedValues - required for the inspector
                // dropdown / pre-fill on header params (e.g. Content-Type enums).
                if (header.defaultValue() != null) h.put("defaultValue", header.defaultValue());
                if (header.allowedValues() != null) h.put("allowedValues", header.allowedValues());
                if (header.extras() != null) h.put("extras", header.extras());
                headers.add(h);
            }
            toolData.put("headers", headers);
        }

        if (tool.pathParameters() != null) {
            List<Map<String, Object>> params = new ArrayList<>();
            for (ApiConfigurationRequest.PathParameterDto param : tool.pathParameters()) {
                Map<String, Object> p = new HashMap<>();
                p.put("name", param.name());
                p.put("type", param.type());
                p.put("required", param.required());
                p.put("description", param.description());
                p.put("example", param.example());
                if (param.defaultValue() != null) p.put("defaultValue", param.defaultValue());
                if (param.allowedValues() != null) p.put("allowedValues", param.allowedValues());
                if (param.extras() != null) {
                    p.put("extras", param.extras());
                    if (param.extras().path("hidden").asBoolean(false)) {
                        p.put("isHidden", true);
                    }
                }
                params.add(p);
            }
            toolData.put("pathParameters", params);
        }

        if (tool.queryParameters() != null) {
            List<Map<String, Object>> params = new ArrayList<>();
            for (ApiConfigurationRequest.QueryParameterDto param : tool.queryParameters()) {
                Map<String, Object> p = new HashMap<>();
                p.put("name", param.name());
                p.put("type", param.type());
                p.put("required", param.required());
                p.put("description", param.description());
                p.put("example", param.example());
                p.put("defaultValue", param.defaultValue());
                p.put("allowedValues", param.allowedValues());
                if (param.extras() != null) {
                    p.put("extras", param.extras());
                    if (param.extras().path("hidden").asBoolean(false)) {
                        p.put("isHidden", true);
                    }
                }
                params.add(p);
            }
            toolData.put("queryParameters", params);
        }

        if (tool.bodyParams() != null) {
            List<Map<String, Object>> params = new ArrayList<>();
            for (ApiConfigurationRequest.BodyParamDto param : tool.bodyParams()) {
                Map<String, Object> p = new HashMap<>();
                p.put("name", param.name());
                p.put("value", param.value());
                p.put("type", param.type());
                p.put("required", param.required());
                p.put("description", param.description());
                if (param.defaultValue() != null) p.put("defaultValue", param.defaultValue());
                if (param.allowedValues() != null) p.put("allowedValues", param.allowedValues());
                if (param.extras() != null) {
                    p.put("extras", param.extras());
                    if (param.extras().path("hidden").asBoolean(false)) {
                        p.put("isHidden", true);
                    }
                }
                params.add(p);
            }
            toolData.put("bodyParams", params);
        }
    }

    private Map<String, Object> convertResponse(ApiConfigurationRequest.ResponseDto response) {
        Map<String, Object> r = new HashMap<>();
        r.put("type", response.type());
        r.put("description", response.description());
        if (response.statusCode() != null) r.put("statusCode", response.statusCode());
        if (response.example() != null) r.put("example", response.example());
        if (response.exampleJsonb() != null) r.put("exampleJsonb", response.exampleJsonb());
        if (response.schema() != null) r.put("schema", response.schema());
        if (response.format() != null) r.put("format", response.format());
        if (response.isDefault() != null) r.put("isDefault", response.isDefault());
        if (response.isActive() != null) r.put("isActive", response.isActive());
        return r;
    }
}
