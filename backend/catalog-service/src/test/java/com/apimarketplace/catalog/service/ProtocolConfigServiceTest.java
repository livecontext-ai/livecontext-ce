package com.apimarketplace.catalog.service;

import com.apimarketplace.catalog.domain.*;
import com.apimarketplace.catalog.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProtocolConfigService")
class ProtocolConfigServiceTest {

    @Mock
    private ApiToolSqlConfigRepository sqlConfigRepository;
    @Mock
    private ApiToolAmqpConfigRepository amqpConfigRepository;
    @Mock
    private ApiToolKafkaConfigRepository kafkaConfigRepository;
    @Mock
    private ApiToolMqttConfigRepository mqttConfigRepository;
    @Mock
    private ApiToolRedisConfigRepository redisConfigRepository;

    private ProtocolConfigService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        service = new ProtocolConfigService(
                sqlConfigRepository,
                amqpConfigRepository,
                kafkaConfigRepository,
                mqttConfigRepository,
                redisConfigRepository
        );
        objectMapper = new ObjectMapper();
    }

    @Nested
    @DisplayName("SQL Configuration")
    class SqlConfigTests {

        @Test
        @DisplayName("should persist SQL config when protocol is SQL")
        void persistsSqlConfigWhenProtocolIsSql() throws Exception {
            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = createTool(toolId, "SQL");

            JsonNode toolData = objectMapper.readTree("""
                {
                    "sqlConfig": {
                        "dialect": "POSTGRESQL",
                        "query": "SELECT * FROM users WHERE id = :id",
                        "defaultSchema": "public",
                        "defaultTable": "users",
                        "resultMode": "rows",
                        "parameters": [{"name": "id", "type": "UUID"}]
                    }
                }
                """);

            when(sqlConfigRepository.findByApiToolId(toolId)).thenReturn(Optional.empty());

            service.persistProtocolConfig(tool, toolData);

            ArgumentCaptor<ApiToolSqlConfigEntity> captor = ArgumentCaptor.forClass(ApiToolSqlConfigEntity.class);
            verify(sqlConfigRepository).save(captor.capture());

            ApiToolSqlConfigEntity saved = captor.getValue();
            assertThat(saved.getApiToolId()).isEqualTo(toolId);
            assertThat(saved.getDialect()).isEqualTo("POSTGRESQL");
            assertThat(saved.getQueryTemplate()).isEqualTo("SELECT * FROM users WHERE id = :id");
            assertThat(saved.getDefaultSchema()).isEqualTo("public");
            assertThat(saved.getDefaultTable()).isEqualTo("users");
            assertThat(saved.getResultMode()).isEqualTo("rows");
            assertThat(saved.getParameterMapping()).contains("id");
        }

        @Test
        @DisplayName("should update existing SQL config")
        void updatesExistingSqlConfig() throws Exception {
            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = createTool(toolId, "SQL");

            ApiToolSqlConfigEntity existing = new ApiToolSqlConfigEntity();
            existing.setId(UUID.randomUUID());
            existing.setApiToolId(toolId);
            existing.setCreatedAt(System.currentTimeMillis() - 10000);

            JsonNode toolData = objectMapper.readTree("""
                {
                    "sqlConfig": {
                        "dialect": "MYSQL",
                        "query": "SELECT name FROM products"
                    }
                }
                """);

            when(sqlConfigRepository.findByApiToolId(toolId)).thenReturn(Optional.of(existing));

            service.persistProtocolConfig(tool, toolData);

            ArgumentCaptor<ApiToolSqlConfigEntity> captor = ArgumentCaptor.forClass(ApiToolSqlConfigEntity.class);
            verify(sqlConfigRepository).save(captor.capture());

            ApiToolSqlConfigEntity saved = captor.getValue();
            assertThat(saved.getId()).isEqualTo(existing.getId());
            assertThat(saved.getDialect()).isEqualTo("MYSQL");
        }

        @Test
        @DisplayName("should skip persistence when query is missing")
        void skipsWhenQueryMissing() throws Exception {
            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = createTool(toolId, "SQL");

            JsonNode toolData = objectMapper.readTree("""
                {
                    "sqlConfig": {
                        "dialect": "POSTGRESQL"
                    }
                }
                """);

            service.persistProtocolConfig(tool, toolData);

            verify(sqlConfigRepository, never()).save(any());
        }

        @Test
        @DisplayName("should use default values when not provided")
        void usesDefaultValues() throws Exception {
            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = createTool(toolId, "SQL");

            JsonNode toolData = objectMapper.readTree("""
                {
                    "sqlConfig": {
                        "query": "SELECT 1"
                    }
                }
                """);

            when(sqlConfigRepository.findByApiToolId(toolId)).thenReturn(Optional.empty());

            service.persistProtocolConfig(tool, toolData);

            ArgumentCaptor<ApiToolSqlConfigEntity> captor = ArgumentCaptor.forClass(ApiToolSqlConfigEntity.class);
            verify(sqlConfigRepository).save(captor.capture());

            ApiToolSqlConfigEntity saved = captor.getValue();
            assertThat(saved.getDialect()).isEqualTo("GENERIC");
            assertThat(saved.getResultMode()).isEqualTo("rows");
            assertThat(saved.getParameterMapping()).isEqualTo("[]");
        }
    }

    @Nested
    @DisplayName("AMQP Configuration")
    class AmqpConfigTests {

        @Test
        @DisplayName("should persist AMQP config when protocol is AMQP")
        void persistsAmqpConfig() throws Exception {
            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = createTool(toolId, "AMQP");

            JsonNode toolData = objectMapper.readTree("""
                {
                    "amqpConfig": {
                        "brokerUrl": "amqp://localhost:5672",
                        "virtualHost": "/myapp",
                        "exchangeName": "events",
                        "queueName": "notifications",
                        "routingKey": "user.created",
                        "prefetchCount": 10,
                        "ackMode": "AUTO",
                        "sslEnabled": true,
                        "options": "heartbeat=30"
                    }
                }
                """);

            when(amqpConfigRepository.findByApiToolId(toolId)).thenReturn(Optional.empty());

            service.persistProtocolConfig(tool, toolData);

            ArgumentCaptor<ApiToolAmqpConfigEntity> captor = ArgumentCaptor.forClass(ApiToolAmqpConfigEntity.class);
            verify(amqpConfigRepository).save(captor.capture());

            ApiToolAmqpConfigEntity saved = captor.getValue();
            assertThat(saved.getApiToolId()).isEqualTo(toolId);
            assertThat(saved.getBrokerUrl()).isEqualTo("amqp://localhost:5672");
            assertThat(saved.getVirtualHost()).isEqualTo("/myapp");
            assertThat(saved.getExchangeName()).isEqualTo("events");
            assertThat(saved.getQueueName()).isEqualTo("notifications");
            assertThat(saved.getRoutingKey()).isEqualTo("user.created");
            assertThat(saved.getPrefetchCount()).isEqualTo(10);
            assertThat(saved.getAckMode()).isEqualTo("AUTO");
            assertThat(saved.getSslEnabled()).isTrue();
            assertThat(saved.getOptions()).isEqualTo("heartbeat=30");
        }

        @Test
        @DisplayName("should cleanup other configs when persisting AMQP")
        void cleansUpOtherConfigsForAmqp() throws Exception {
            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = createTool(toolId, "AMQP");

            JsonNode toolData = objectMapper.readTree("""
                {
                    "amqpConfig": {
                        "brokerUrl": "amqp://localhost"
                    }
                }
                """);

            when(amqpConfigRepository.findByApiToolId(toolId)).thenReturn(Optional.empty());

            service.persistProtocolConfig(tool, toolData);

            verify(sqlConfigRepository).deleteByApiToolId(toolId);
            verify(kafkaConfigRepository).deleteByApiToolId(toolId);
            verify(mqttConfigRepository).deleteByApiToolId(toolId);
            verify(redisConfigRepository).deleteByApiToolId(toolId);
            verify(amqpConfigRepository, never()).deleteByApiToolId(toolId);
        }
    }

    @Nested
    @DisplayName("Kafka Configuration")
    class KafkaConfigTests {

        @Test
        @DisplayName("should persist Kafka config when protocol is KAFKA")
        void persistsKafkaConfig() throws Exception {
            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = createTool(toolId, "KAFKA");

            JsonNode toolData = objectMapper.readTree("""
                {
                    "kafkaConfig": {
                        "brokers": "localhost:9092,localhost:9093",
                        "topic": "user-events",
                        "consumerGroup": "analytics-group",
                        "securityProtocol": "SASL_SSL",
                        "saslMechanism": "PLAIN",
                        "saslUsername": "admin",
                        "saslPassword": "secret",
                        "sslEnabled": true
                    }
                }
                """);

            when(kafkaConfigRepository.findByApiToolId(toolId)).thenReturn(Optional.empty());

            service.persistProtocolConfig(tool, toolData);

            ArgumentCaptor<ApiToolKafkaConfigEntity> captor = ArgumentCaptor.forClass(ApiToolKafkaConfigEntity.class);
            verify(kafkaConfigRepository).save(captor.capture());

            ApiToolKafkaConfigEntity saved = captor.getValue();
            assertThat(saved.getApiToolId()).isEqualTo(toolId);
            assertThat(saved.getBrokers()).isEqualTo("localhost:9092,localhost:9093");
            assertThat(saved.getTopic()).isEqualTo("user-events");
            assertThat(saved.getConsumerGroup()).isEqualTo("analytics-group");
            assertThat(saved.getSecurityProtocol()).isEqualTo("SASL_SSL");
            assertThat(saved.getSaslMechanism()).isEqualTo("PLAIN");
            assertThat(saved.getSaslUsername()).isEqualTo("admin");
            assertThat(saved.getSaslPassword()).isEqualTo("secret");
            assertThat(saved.getSslEnabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("MQTT Configuration")
    class MqttConfigTests {

        @Test
        @DisplayName("should persist MQTT config when protocol is MQTT")
        void persistsMqttConfig() throws Exception {
            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = createTool(toolId, "MQTT");

            JsonNode toolData = objectMapper.readTree("""
                {
                    "mqttConfig": {
                        "brokerUrl": "tcp://mqtt.example.com:1883",
                        "topics": "sensors/temperature,sensors/humidity",
                        "qos": 1,
                        "retain": true,
                        "clientId": "sensor-client-001",
                        "username": "device",
                        "password": "secret123",
                        "useTls": false
                    }
                }
                """);

            when(mqttConfigRepository.findByApiToolId(toolId)).thenReturn(Optional.empty());

            service.persistProtocolConfig(tool, toolData);

            ArgumentCaptor<ApiToolMqttConfigEntity> captor = ArgumentCaptor.forClass(ApiToolMqttConfigEntity.class);
            verify(mqttConfigRepository).save(captor.capture());

            ApiToolMqttConfigEntity saved = captor.getValue();
            assertThat(saved.getApiToolId()).isEqualTo(toolId);
            assertThat(saved.getBrokerUrl()).isEqualTo("tcp://mqtt.example.com:1883");
            assertThat(saved.getTopics()).isEqualTo("sensors/temperature,sensors/humidity");
            assertThat(saved.getQos()).isEqualTo(1);
            assertThat(saved.getRetain()).isTrue();
            assertThat(saved.getClientId()).isEqualTo("sensor-client-001");
            assertThat(saved.getUsername()).isEqualTo("device");
            assertThat(saved.getPassword()).isEqualTo("secret123");
            assertThat(saved.getUseTls()).isFalse();
        }
    }

    @Nested
    @DisplayName("Redis Configuration")
    class RedisConfigTests {

        @Test
        @DisplayName("should persist Redis config when protocol is REDIS")
        void persistsRedisConfig() throws Exception {
            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = createTool(toolId, "REDIS");

            JsonNode toolData = objectMapper.readTree("""
                {
                    "redisConfig": {
                        "host": "redis.example.com",
                        "port": 6379,
                        "dbIndex": 2,
                        "channels": "notifications,alerts",
                        "useTls": true,
                        "username": "redis-user",
                        "password": "redis-pass"
                    }
                }
                """);

            when(redisConfigRepository.findByApiToolId(toolId)).thenReturn(Optional.empty());

            service.persistProtocolConfig(tool, toolData);

            ArgumentCaptor<ApiToolRedisConfigEntity> captor = ArgumentCaptor.forClass(ApiToolRedisConfigEntity.class);
            verify(redisConfigRepository).save(captor.capture());

            ApiToolRedisConfigEntity saved = captor.getValue();
            assertThat(saved.getApiToolId()).isEqualTo(toolId);
            assertThat(saved.getHost()).isEqualTo("redis.example.com");
            assertThat(saved.getPort()).isEqualTo(6379);
            assertThat(saved.getDbIndex()).isEqualTo(2);
            assertThat(saved.getChannels()).isEqualTo("notifications,alerts");
            assertThat(saved.getUseTls()).isTrue();
            assertThat(saved.getUsername()).isEqualTo("redis-user");
            assertThat(saved.getPassword()).isEqualTo("redis-pass");
        }
    }

    @Nested
    @DisplayName("Delete All Configs")
    class DeleteAllConfigsTests {

        @Test
        @DisplayName("should delete all protocol configs for a tool")
        void deletesAllConfigs() {
            UUID toolId = UUID.randomUUID();

            service.deleteAllConfigs(toolId);

            verify(sqlConfigRepository).deleteByApiToolId(toolId);
            verify(amqpConfigRepository).deleteByApiToolId(toolId);
            verify(kafkaConfigRepository).deleteByApiToolId(toolId);
            verify(mqttConfigRepository).deleteByApiToolId(toolId);
            verify(redisConfigRepository).deleteByApiToolId(toolId);
        }
    }

    @Nested
    @DisplayName("Protocol Cleanup")
    class ProtocolCleanupTests {

        @Test
        @DisplayName("should not interact with repositories when protocol is null")
        void noOpWhenProtocolNull() throws Exception {
            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = createTool(toolId, null);

            JsonNode toolData = objectMapper.readTree("{}");

            service.persistProtocolConfig(tool, toolData);

            verifyNoInteractions(sqlConfigRepository);
            verifyNoInteractions(amqpConfigRepository);
            verifyNoInteractions(kafkaConfigRepository);
            verifyNoInteractions(mqttConfigRepository);
            verifyNoInteractions(redisConfigRepository);
        }

        @Test
        @DisplayName("should cleanup all configs when protocol is unknown")
        void cleansUpAllWhenProtocolUnknown() throws Exception {
            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = createTool(toolId, "HTTP");

            JsonNode toolData = objectMapper.readTree("{}");

            service.persistProtocolConfig(tool, toolData);

            verify(sqlConfigRepository).deleteByApiToolId(toolId);
            verify(amqpConfigRepository).deleteByApiToolId(toolId);
            verify(kafkaConfigRepository).deleteByApiToolId(toolId);
            verify(mqttConfigRepository).deleteByApiToolId(toolId);
            verify(redisConfigRepository).deleteByApiToolId(toolId);
        }
    }

    @Nested
    @DisplayName("Edge Cases")
    class EdgeCaseTests {

        @Test
        @DisplayName("should handle null values in config gracefully")
        void handlesNullValuesGracefully() throws Exception {
            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = createTool(toolId, "REDIS");

            JsonNode toolData = objectMapper.readTree("""
                {
                    "redisConfig": {
                        "host": "localhost",
                        "port": null,
                        "username": ""
                    }
                }
                """);

            when(redisConfigRepository.findByApiToolId(toolId)).thenReturn(Optional.empty());

            service.persistProtocolConfig(tool, toolData);

            ArgumentCaptor<ApiToolRedisConfigEntity> captor = ArgumentCaptor.forClass(ApiToolRedisConfigEntity.class);
            verify(redisConfigRepository).save(captor.capture());

            ApiToolRedisConfigEntity saved = captor.getValue();
            assertThat(saved.getHost()).isEqualTo("localhost");
            assertThat(saved.getPort()).isNull();
            assertThat(saved.getUsername()).isNull(); // empty string converted to null
        }

        @Test
        @DisplayName("should handle integer values as strings")
        void handlesIntegerAsString() throws Exception {
            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = createTool(toolId, "REDIS");

            JsonNode toolData = objectMapper.readTree("""
                {
                    "redisConfig": {
                        "host": "localhost",
                        "port": "6380"
                    }
                }
                """);

            when(redisConfigRepository.findByApiToolId(toolId)).thenReturn(Optional.empty());

            service.persistProtocolConfig(tool, toolData);

            ArgumentCaptor<ApiToolRedisConfigEntity> captor = ArgumentCaptor.forClass(ApiToolRedisConfigEntity.class);
            verify(redisConfigRepository).save(captor.capture());

            assertThat(captor.getValue().getPort()).isEqualTo(6380);
        }

        @Test
        @DisplayName("should handle boolean values as strings")
        void handlesBooleanAsString() throws Exception {
            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = createTool(toolId, "KAFKA");

            JsonNode toolData = objectMapper.readTree("""
                {
                    "kafkaConfig": {
                        "sslEnabled": "true"
                    }
                }
                """);

            when(kafkaConfigRepository.findByApiToolId(toolId)).thenReturn(Optional.empty());

            service.persistProtocolConfig(tool, toolData);

            ArgumentCaptor<ApiToolKafkaConfigEntity> captor = ArgumentCaptor.forClass(ApiToolKafkaConfigEntity.class);
            verify(kafkaConfigRepository).save(captor.capture());

            assertThat(captor.getValue().getSslEnabled()).isTrue();
        }

        @Test
        @DisplayName("should handle case-insensitive protocol matching")
        void handlesCaseInsensitiveProtocol() throws Exception {
            UUID toolId = UUID.randomUUID();
            ApiToolEntity tool = createTool(toolId, "sql"); // lowercase

            JsonNode toolData = objectMapper.readTree("""
                {
                    "sqlConfig": {
                        "query": "SELECT 1"
                    }
                }
                """);

            when(sqlConfigRepository.findByApiToolId(toolId)).thenReturn(Optional.empty());

            service.persistProtocolConfig(tool, toolData);

            verify(sqlConfigRepository).save(any(ApiToolSqlConfigEntity.class));
        }
    }

    private ApiToolEntity createTool(UUID toolId, String protocol) {
        ApiToolEntity tool = new ApiToolEntity();
        tool.setId(toolId);
        tool.setProtocol(protocol);
        return tool;
    }
}
