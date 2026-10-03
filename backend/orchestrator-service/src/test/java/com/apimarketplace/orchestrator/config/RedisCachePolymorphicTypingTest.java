package com.apimarketplace.orchestrator.config;

import com.apimarketplace.orchestrator.tools.workflow.builder.WorkflowBuilderSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * LC-081 regression through the PRODUCTION {@code RedisTemplate} bean's value serializer, so the
 * validator that actually runs is the one tested. Pre-fix the template used
 * {@code LaissezFaireSubTypeValidator} and materialised {@code java.io.File} from a cached value.
 */
@DisplayName("RedisCacheConfig polymorphic typing (LC-081)")
class RedisCachePolymorphicTypingTest {

    @SuppressWarnings("unchecked")
    private static RedisSerializer<Object> valueSerializer() {
        RedisTemplate<String, Object> template =
                new RedisCacheConfig().redisTemplate(mock(RedisConnectionFactory.class));
        return (RedisSerializer<Object>) template.getValueSerializer();
    }

    @Test
    @DisplayName("refuses a cached value whose type id is outside the allow-list")
    void refusesHostileTypeId() {
        byte[] hostile = "[\"java.io.File\",\"/tmp/lc-081\"]".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> valueSerializer().deserialize(hostile))
                .hasStackTraceContaining("PolymorphicTypeValidator");
    }

    @Test
    @DisplayName("still round-trips the workflow builder session with free-form node data")
    void roundTripsWorkflowBuilderSession() {
        Map<String, Object> mcp = new LinkedHashMap<>();
        mcp.put("id", "mcp:fetch");
        mcp.put("params", new LinkedHashMap<>(Map.of("limit", 10, "ratio", new BigDecimal("0.25"))));
        mcp.put("tags", List.of("a", "b"));
        WorkflowBuilderSession session = WorkflowBuilderSession.builder()
                .sessionId("s-1")
                .tenantId("t-1")
                .workflowName("LC-081 round trip")
                .createdAt(Instant.parse("2026-01-02T03:04:05Z"))
                .mcps(new java.util.ArrayList<>(List.of(mcp)))
                .build();

        RedisSerializer<Object> serializer = valueSerializer();
        Object restored = serializer.deserialize(serializer.serialize(session));

        assertThat(restored).isInstanceOf(WorkflowBuilderSession.class);
        WorkflowBuilderSession back = (WorkflowBuilderSession) restored;
        assertThat(back.getWorkflowName()).isEqualTo("LC-081 round trip");
        assertThat(back.getCreatedAt()).isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
        assertThat(back.getMcps()).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) back.getMcps().get(0).get("params");
        assertThat(params).containsEntry("ratio", new BigDecimal("0.25"));
    }

    @Test
    @DisplayName("still round-trips the chat-session hash values (plain strings)")
    void roundTripsHashStrings() {
        RedisSerializer<Object> serializer = valueSerializer();

        assertThat(serializer.deserialize(serializer.serialize("conv-1"))).isEqualTo("conv-1");
    }
}
