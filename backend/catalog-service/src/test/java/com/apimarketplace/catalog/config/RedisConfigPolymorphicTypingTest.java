package com.apimarketplace.catalog.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * LC-081 regression through the PRODUCTION {@code RedisTemplate} bean's value serializer. Pre-fix
 * the template used {@code LaissezFaireSubTypeValidator} and materialised {@code java.io.File}
 * from a cached value.
 */
@DisplayName("catalog-service RedisConfig polymorphic typing (LC-081)")
class RedisConfigPolymorphicTypingTest {

    @SuppressWarnings("unchecked")
    private static RedisSerializer<Object> valueSerializer() {
        RedisTemplate<String, Object> template =
                new RedisConfig().redisTemplate(mock(RedisConnectionFactory.class));
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
    @DisplayName("still round-trips the value shapes this service caches")
    @SuppressWarnings("unchecked")
    void roundTripsCachedShapes() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sessionId", "s-1");
        value.put("items", new ArrayList<>(List.of(Map.of("id", 1), "two")));
        value.put("count", new BigInteger("98765432109876543210"));

        RedisSerializer<Object> serializer = valueSerializer();
        Object restored = serializer.deserialize(serializer.serialize(value));

        assertThat(restored).isInstanceOf(Map.class);
        assertThat((Map<String, Object>) restored)
                .containsEntry("sessionId", "s-1")
                .containsEntry("count", new BigInteger("98765432109876543210"));
        assertThat(serializer.deserialize(serializer.serialize("plain"))).isEqualTo("plain");
    }
}
