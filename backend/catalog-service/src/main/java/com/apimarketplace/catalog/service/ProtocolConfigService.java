package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.*;
import com.apimarketplace.catalog.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Service for managing protocol-specific configurations for API tools.
 * Centralizes persistence logic for SQL, AMQP, Kafka, MQTT, and Redis configurations.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProtocolConfigService {

    private final ApiToolSqlConfigRepository sqlConfigRepository;
    private final ApiToolAmqpConfigRepository amqpConfigRepository;
    private final ApiToolKafkaConfigRepository kafkaConfigRepository;
    private final ApiToolMqttConfigRepository mqttConfigRepository;
    private final ApiToolRedisConfigRepository redisConfigRepository;

    /**
     * Persists protocol-specific configuration based on the tool's protocol.
     */
    public void persistProtocolConfig(ApiToolEntity tool, JsonNode toolData) {
        String protocol = tool.getProtocol();
        if (protocol == null) {
            return;
        }

        switch (protocol.toUpperCase()) {
            case "SQL" -> persistSqlConfig(tool, toolData);
            case "AMQP" -> persistAmqpConfig(tool, toolData);
            case "KAFKA" -> persistKafkaConfig(tool, toolData);
            case "MQTT" -> persistMqttConfig(tool, toolData);
            case "REDIS" -> persistRedisConfig(tool, toolData);
            default -> cleanupAllConfigs(tool.getId());
        }
    }

    /**
     * Deletes all protocol configurations for a tool.
     */
    public void deleteAllConfigs(UUID apiToolId) {
        sqlConfigRepository.deleteByApiToolId(apiToolId);
        amqpConfigRepository.deleteByApiToolId(apiToolId);
        kafkaConfigRepository.deleteByApiToolId(apiToolId);
        mqttConfigRepository.deleteByApiToolId(apiToolId);
        redisConfigRepository.deleteByApiToolId(apiToolId);
    }

    private void cleanupAllConfigs(UUID apiToolId) {
        deleteAllConfigs(apiToolId);
    }

    private void persistSqlConfig(ApiToolEntity tool, JsonNode toolData) {
        cleanupOtherConfigs(tool.getId(), "SQL");
        JsonNode config = toolData.path("sqlConfig");

        String query = config.path("query").asText("");
        if (query.isBlank()) {
            log.warn("SQL tool {} missing query template, skipping persistence", tool.getId());
            return;
        }

        ApiToolSqlConfigEntity entity = sqlConfigRepository.findByApiToolId(tool.getId())
                .orElseGet(() -> createNewSqlConfig(tool.getId()));

        entity.setDialect(config.path("dialect").asText("GENERIC"));
        entity.setQueryTemplate(query);
        entity.setDefaultSchema(readOptionalText(config, "defaultSchema"));
        entity.setDefaultTable(readOptionalText(config, "defaultTable"));
        entity.setResultMode(config.path("resultMode").asText("rows"));

        JsonNode paramsNode = config.get("parameters");
        entity.setParameterMapping(paramsNode != null && !paramsNode.isNull() ? paramsNode.toString() : "[]");
        entity.setUpdatedAt(System.currentTimeMillis());

        sqlConfigRepository.save(entity);
        log.debug("SQL config persisted for tool {}", tool.getId());
    }

    private void persistAmqpConfig(ApiToolEntity tool, JsonNode toolData) {
        cleanupOtherConfigs(tool.getId(), "AMQP");
        JsonNode config = toolData.path("amqpConfig");

        ApiToolAmqpConfigEntity entity = amqpConfigRepository.findByApiToolId(tool.getId())
                .orElseGet(() -> createNewAmqpConfig(tool.getId()));

        entity.setBrokerUrl(readOptionalText(config, "brokerUrl"));
        entity.setVirtualHost(readOptionalText(config, "virtualHost"));
        entity.setExchangeName(readOptionalText(config, "exchangeName"));
        entity.setQueueName(readOptionalText(config, "queueName"));
        entity.setRoutingKey(readOptionalText(config, "routingKey"));
        entity.setPrefetchCount(readOptionalInteger(config, "prefetchCount"));
        entity.setAckMode(readOptionalText(config, "ackMode"));
        entity.setSslEnabled(readOptionalBoolean(config, "sslEnabled"));
        entity.setOptions(readOptionalText(config, "options"));
        entity.setUpdatedAt(System.currentTimeMillis());

        amqpConfigRepository.save(entity);
        log.debug("AMQP config persisted for tool {}", tool.getId());
    }

    private void persistKafkaConfig(ApiToolEntity tool, JsonNode toolData) {
        cleanupOtherConfigs(tool.getId(), "KAFKA");
        JsonNode config = toolData.path("kafkaConfig");

        ApiToolKafkaConfigEntity entity = kafkaConfigRepository.findByApiToolId(tool.getId())
                .orElseGet(() -> createNewKafkaConfig(tool.getId()));

        entity.setBrokers(readOptionalText(config, "brokers"));
        entity.setTopic(readOptionalText(config, "topic"));
        entity.setConsumerGroup(readOptionalText(config, "consumerGroup"));
        entity.setSecurityProtocol(readOptionalText(config, "securityProtocol"));
        entity.setSaslMechanism(readOptionalText(config, "saslMechanism"));
        entity.setSaslUsername(readOptionalText(config, "saslUsername"));
        entity.setSaslPassword(readOptionalText(config, "saslPassword"));
        entity.setSslEnabled(readOptionalBoolean(config, "sslEnabled"));
        entity.setUpdatedAt(System.currentTimeMillis());

        kafkaConfigRepository.save(entity);
        log.debug("Kafka config persisted for tool {}", tool.getId());
    }

    private void persistMqttConfig(ApiToolEntity tool, JsonNode toolData) {
        cleanupOtherConfigs(tool.getId(), "MQTT");
        JsonNode config = toolData.path("mqttConfig");

        ApiToolMqttConfigEntity entity = mqttConfigRepository.findByApiToolId(tool.getId())
                .orElseGet(() -> createNewMqttConfig(tool.getId()));

        entity.setBrokerUrl(readOptionalText(config, "brokerUrl"));
        entity.setTopics(readOptionalText(config, "topics"));
        entity.setQos(readOptionalInteger(config, "qos"));
        entity.setRetain(readOptionalBoolean(config, "retain"));
        entity.setClientId(readOptionalText(config, "clientId"));
        entity.setUsername(readOptionalText(config, "username"));
        entity.setPassword(readOptionalText(config, "password"));
        entity.setUseTls(readOptionalBoolean(config, "useTls"));
        entity.setUpdatedAt(System.currentTimeMillis());

        mqttConfigRepository.save(entity);
        log.debug("MQTT config persisted for tool {}", tool.getId());
    }

    private void persistRedisConfig(ApiToolEntity tool, JsonNode toolData) {
        cleanupOtherConfigs(tool.getId(), "REDIS");
        JsonNode config = toolData.path("redisConfig");

        ApiToolRedisConfigEntity entity = redisConfigRepository.findByApiToolId(tool.getId())
                .orElseGet(() -> createNewRedisConfig(tool.getId()));

        entity.setHost(readOptionalText(config, "host"));
        entity.setPort(readOptionalInteger(config, "port"));
        entity.setDbIndex(readOptionalInteger(config, "dbIndex"));
        entity.setChannels(readOptionalText(config, "channels"));
        entity.setUseTls(readOptionalBoolean(config, "useTls"));
        entity.setUsername(readOptionalText(config, "username"));
        entity.setPassword(readOptionalText(config, "password"));
        entity.setUpdatedAt(System.currentTimeMillis());

        redisConfigRepository.save(entity);
        log.debug("Redis config persisted for tool {}", tool.getId());
    }

    private void cleanupOtherConfigs(UUID apiToolId, String currentProtocol) {
        if (!"SQL".equals(currentProtocol)) sqlConfigRepository.deleteByApiToolId(apiToolId);
        if (!"AMQP".equals(currentProtocol)) amqpConfigRepository.deleteByApiToolId(apiToolId);
        if (!"KAFKA".equals(currentProtocol)) kafkaConfigRepository.deleteByApiToolId(apiToolId);
        if (!"MQTT".equals(currentProtocol)) mqttConfigRepository.deleteByApiToolId(apiToolId);
        if (!"REDIS".equals(currentProtocol)) redisConfigRepository.deleteByApiToolId(apiToolId);
    }

    // Factory methods for new entities
    private ApiToolSqlConfigEntity createNewSqlConfig(UUID apiToolId) {
        ApiToolSqlConfigEntity config = new ApiToolSqlConfigEntity();
        config.setId(UUID.randomUUID());
        config.setApiToolId(apiToolId);
        config.setCreatedAt(System.currentTimeMillis());
        return config;
    }

    private ApiToolAmqpConfigEntity createNewAmqpConfig(UUID apiToolId) {
        ApiToolAmqpConfigEntity config = new ApiToolAmqpConfigEntity();
        config.setId(UUID.randomUUID());
        config.setApiToolId(apiToolId);
        config.setCreatedAt(System.currentTimeMillis());
        return config;
    }

    private ApiToolKafkaConfigEntity createNewKafkaConfig(UUID apiToolId) {
        ApiToolKafkaConfigEntity config = new ApiToolKafkaConfigEntity();
        config.setId(UUID.randomUUID());
        config.setApiToolId(apiToolId);
        config.setCreatedAt(System.currentTimeMillis());
        return config;
    }

    private ApiToolMqttConfigEntity createNewMqttConfig(UUID apiToolId) {
        ApiToolMqttConfigEntity config = new ApiToolMqttConfigEntity();
        config.setId(UUID.randomUUID());
        config.setApiToolId(apiToolId);
        config.setCreatedAt(System.currentTimeMillis());
        return config;
    }

    private ApiToolRedisConfigEntity createNewRedisConfig(UUID apiToolId) {
        ApiToolRedisConfigEntity config = new ApiToolRedisConfigEntity();
        config.setId(UUID.randomUUID());
        config.setApiToolId(apiToolId);
        config.setCreatedAt(System.currentTimeMillis());
        return config;
    }

    // Utility methods for reading optional values from JsonNode
    private String readOptionalText(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    private Integer readOptionalInteger(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isInt() || value.isLong()) {
            return value.asInt();
        }
        try {
            return Integer.parseInt(value.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean readOptionalBoolean(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        String text = value.asText();
        if (text == null || text.isBlank()) {
            return null;
        }
        return Boolean.parseBoolean(text);
    }

    // ========== Update from Map (DTO) methods ==========

    /**
     * Update all protocol configs from DTO maps.
     */
    public void updateConfigsFromDto(ApiToolEntity tool, Map<String, Object> sqlConfig,
                                      Map<String, Object> amqpConfig, Map<String, Object> kafkaConfig,
                                      Map<String, Object> mqttConfig, Map<String, Object> redisConfig) {
        updateSqlConfigFromDto(tool, sqlConfig);
        updateAmqpConfigFromDto(tool, amqpConfig);
        updateKafkaConfigFromDto(tool, kafkaConfig);
        updateMqttConfigFromDto(tool, mqttConfig);
        updateRedisConfigFromDto(tool, redisConfig);
    }

    public void updateSqlConfigFromDto(ApiToolEntity tool, Map<String, Object> configData) {
        if (!isProtocol(tool, "SQL")) {
            sqlConfigRepository.deleteByApiToolId(tool.getId());
            return;
        }
        if (configData == null || configData.isEmpty()) return;

        Object query = configData.get("query");
        if (query == null || query.toString().isBlank()) {
            throw new IllegalArgumentException("SQL tools require a query template");
        }

        ApiToolSqlConfigEntity entity = sqlConfigRepository.findByApiToolId(tool.getId())
                .orElseGet(() -> createNewSqlConfig(tool.getId()));

        entity.setDialect(stringValue(configData, "dialect", "GENERIC"));
        entity.setQueryTemplate(query.toString());
        entity.setDefaultSchema(stringValue(configData, "defaultSchema"));
        entity.setDefaultTable(stringValue(configData, "defaultTable"));
        entity.setResultMode(stringValue(configData, "resultMode", "rows"));
        entity.setParameterMapping(serializeObject(configData.get("parameters")));
        entity.setUpdatedAt(System.currentTimeMillis());
        sqlConfigRepository.save(entity);
    }

    public void updateAmqpConfigFromDto(ApiToolEntity tool, Map<String, Object> configData) {
        if (!isProtocol(tool, "AMQP")) {
            amqpConfigRepository.deleteByApiToolId(tool.getId());
            return;
        }
        if (configData == null) return;

        ApiToolAmqpConfigEntity entity = amqpConfigRepository.findByApiToolId(tool.getId())
                .orElseGet(() -> createNewAmqpConfig(tool.getId()));

        entity.setBrokerUrl(stringValue(configData, "brokerUrl"));
        entity.setVirtualHost(stringValue(configData, "virtualHost"));
        entity.setExchangeName(stringValue(configData, "exchangeName"));
        entity.setQueueName(stringValue(configData, "queueName"));
        entity.setRoutingKey(stringValue(configData, "routingKey"));
        entity.setPrefetchCount(integerValue(configData, "prefetchCount"));
        entity.setAckMode(stringValue(configData, "ackMode"));
        entity.setSslEnabled(booleanValue(configData, "sslEnabled"));
        entity.setOptions(stringValue(configData, "options"));
        entity.setUpdatedAt(System.currentTimeMillis());
        amqpConfigRepository.save(entity);
    }

    public void updateKafkaConfigFromDto(ApiToolEntity tool, Map<String, Object> configData) {
        if (!isProtocol(tool, "KAFKA")) {
            kafkaConfigRepository.deleteByApiToolId(tool.getId());
            return;
        }
        if (configData == null) return;

        ApiToolKafkaConfigEntity entity = kafkaConfigRepository.findByApiToolId(tool.getId())
                .orElseGet(() -> createNewKafkaConfig(tool.getId()));

        entity.setBrokers(stringValue(configData, "brokers"));
        entity.setTopic(stringValue(configData, "topic"));
        entity.setConsumerGroup(stringValue(configData, "consumerGroup"));
        entity.setSecurityProtocol(stringValue(configData, "securityProtocol"));
        entity.setSaslMechanism(stringValue(configData, "saslMechanism"));
        entity.setSaslUsername(stringValue(configData, "saslUsername"));
        entity.setSaslPassword(stringValue(configData, "saslPassword"));
        entity.setSslEnabled(booleanValue(configData, "sslEnabled"));
        entity.setUpdatedAt(System.currentTimeMillis());
        kafkaConfigRepository.save(entity);
    }

    public void updateMqttConfigFromDto(ApiToolEntity tool, Map<String, Object> configData) {
        if (!isProtocol(tool, "MQTT")) {
            mqttConfigRepository.deleteByApiToolId(tool.getId());
            return;
        }
        if (configData == null) return;

        ApiToolMqttConfigEntity entity = mqttConfigRepository.findByApiToolId(tool.getId())
                .orElseGet(() -> createNewMqttConfig(tool.getId()));

        entity.setBrokerUrl(stringValue(configData, "brokerUrl"));
        entity.setTopics(stringValue(configData, "topics"));
        entity.setQos(integerValue(configData, "qos"));
        entity.setRetain(booleanValue(configData, "retain"));
        entity.setClientId(stringValue(configData, "clientId"));
        entity.setUsername(stringValue(configData, "username"));
        entity.setPassword(stringValue(configData, "password"));
        entity.setUseTls(booleanValue(configData, "useTls"));
        entity.setUpdatedAt(System.currentTimeMillis());
        mqttConfigRepository.save(entity);
    }

    public void updateRedisConfigFromDto(ApiToolEntity tool, Map<String, Object> configData) {
        if (!isProtocol(tool, "REDIS")) {
            redisConfigRepository.deleteByApiToolId(tool.getId());
            return;
        }
        if (configData == null) return;

        ApiToolRedisConfigEntity entity = redisConfigRepository.findByApiToolId(tool.getId())
                .orElseGet(() -> createNewRedisConfig(tool.getId()));

        entity.setHost(stringValue(configData, "host"));
        entity.setPort(integerValue(configData, "port"));
        entity.setDbIndex(integerValue(configData, "dbIndex"));
        entity.setChannels(stringValue(configData, "channels"));
        entity.setUseTls(booleanValue(configData, "useTls"));
        entity.setUsername(stringValue(configData, "username"));
        entity.setPassword(stringValue(configData, "password"));
        entity.setUpdatedAt(System.currentTimeMillis());
        redisConfigRepository.save(entity);
    }

    private boolean isProtocol(ApiToolEntity tool, String protocol) {
        return tool.getProtocol() != null && tool.getProtocol().equalsIgnoreCase(protocol);
    }

    private String stringValue(Map<String, Object> map, String key) {
        return stringValue(map, key, null);
    }

    private String stringValue(Map<String, Object> map, String key, String defaultValue) {
        if (map == null) return defaultValue;
        Object value = map.get(key);
        return value == null ? defaultValue : value.toString();
    }

    private Integer integerValue(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean booleanValue(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object value = map.get(key);
        if (value == null) return null;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(value.toString());
    }

    private String serializeObject(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return s;
        return value.toString();
    }
}
