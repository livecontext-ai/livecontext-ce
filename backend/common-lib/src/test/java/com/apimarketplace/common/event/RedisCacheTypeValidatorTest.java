package com.apimarketplace.common.event;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LC-081: the shared allow-list replacing {@code LaissezFaireSubTypeValidator} on every service's
 * {@code RedisTemplate<String, Object>}. Two halves are pinned: hostile type ids are refused, and
 * every value SHAPE the templates really store still round-trips (a too-narrow list would break
 * cache reads at runtime, silently, since every caller swallows read errors into a cache miss).
 */
@DisplayName("RedisCacheTypeValidator (LC-081)")
class RedisCacheTypeValidatorTest {

    /** Same configuration as the production templates, with the validator under test. */
    private static ObjectMapper mapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.activateDefaultTyping(
                RedisCacheTypeValidator.create(), ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        return mapper;
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the hostile payload is a real bypass under the removed LaissezFaire validator")
    void hostilePayloadWasAcceptedPreFix() throws Exception {
        ObjectMapper preFix = new ObjectMapper();
        preFix.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);

        assertThat(preFix.readValue(bytes("[\"java.io.File\",\"/tmp/lc-081\"]"), Object.class))
                .isInstanceOf(File.class);
    }

    @ParameterizedTest(name = "refuses {0}")
    @ValueSource(strings = {
            "[\"java.io.File\",\"/tmp/lc-081\"]",
            "[\"java.net.URL\",\"http://attacker.example/\"]",
            "[\"[Ljava.net.URL;\",[\"http://attacker.example/\"]]",
            "[\"[Ljava.io.File;\",[\"/tmp/lc-081\"]]",
            "{\"@class\":\"org.springframework.core.io.FileSystemResource\",\"path\":\"/etc/passwd\"}"
    })
    void refusesTypeIdsOutsideTheAllowList(String payload) {
        assertThatThrownBy(() -> mapper().readValue(bytes(payload), Object.class))
                .hasStackTraceContaining("PolymorphicTypeValidator");
    }

    @ParameterizedTest(name = "carve-out refuses {0}")
    @ValueSource(strings = {
            "[\"java.lang.ProcessBuilder\",[\"calc\"]]",
            "[\"java.lang.Class\",\"java.lang.Runtime\"]",
            "[\"java.util.logging.FileHandler\",\"/tmp/x\"]",
            "[\"java.util.prefs.Preferences\",{}]",
            "[\"java.lang.Thread\",{}]",
            "[\"java.util.Timer\",{}]",
            "[\"[Ljava.lang.reflect.Method;\",[]]",
            "[\"[Ljava.lang.Thread;\",[]]"
    })
    void refusesCarvedOutJdkTypes(String payload) {
        // These names sit under the allowed java.lang. / java.util. prefixes; only the deny-first
        // carve-out stands between them and instantiation.
        assertThatThrownBy(() -> mapper().readValue(bytes(payload), Object.class))
                .hasStackTraceContaining("PolymorphicTypeValidator");
    }

    @Test
    @DisplayName("the carve-out matches array element types and spares ordinary neighbours")
    void carveOutBoundaries() {
        assertThat(RedisCacheTypeValidator.isDenied("java.lang.reflect.Method")).isTrue();
        assertThat(RedisCacheTypeValidator.isDenied("[[Ljava.lang.ClassLoader;")).isTrue();
        assertThat(RedisCacheTypeValidator.isDenied("java.lang.Runtime")).isTrue();
        assertThat(RedisCacheTypeValidator.isDenied(null)).isTrue();
        assertThat(RedisCacheTypeValidator.isDenied("java.lang.String")).isFalse();
        assertThat(RedisCacheTypeValidator.isDenied("java.lang.RuntimeException")).isFalse();
        assertThat(RedisCacheTypeValidator.isDenied("java.util.LinkedHashMap")).isFalse();
        assertThat(RedisCacheTypeValidator.isDenied("java.util.concurrent.ConcurrentHashMap")).isFalse();
        assertThat(RedisCacheTypeValidator.isDenied("[Ljava.lang.String;")).isFalse();
        assertThat(RedisCacheTypeValidator.isDenied("[B")).isFalse();
    }

    @Test
    @DisplayName("round-trips the JDK and Jackson value shapes the caches store")
    @SuppressWarnings("unchecked")
    void roundTripsRealValueShapes() throws Exception {
        ObjectNode node = JsonNodeFactory.instance.objectNode().put("k", "v");
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("text", "ok");
        value.put("long", 12345678901L);
        value.put("decimal", new BigDecimal("12.50"));
        value.put("big", new BigInteger("123456789012345678901234567890"));
        value.put("when", Instant.parse("2026-01-02T03:04:05Z"));
        value.put("list", new ArrayList<>(List.of("a", 1, true)));
        value.put("nested", new LinkedHashMap<>(Map.of("x", 1)));
        value.put("tree", node);
        value.put("bytes", new byte[] {1, 2, 3});
        value.put("objects", new Object[] {"a", 2L});

        ObjectMapper mapper = mapper();
        Map<String, Object> restored =
                (Map<String, Object>) mapper.readValue(mapper.writeValueAsBytes(value), Object.class);

        assertThat(restored.get("text")).isEqualTo("ok");
        assertThat(restored.get("long")).isEqualTo(12345678901L);
        assertThat(restored.get("decimal")).isEqualTo(new BigDecimal("12.50"));
        assertThat(restored.get("big")).isEqualTo(new BigInteger("123456789012345678901234567890"));
        assertThat(restored.get("when")).isEqualTo(Instant.parse("2026-01-02T03:04:05Z"));
        assertThat((List<Object>) restored.get("list")).containsExactly("a", 1, true);
        assertThat(restored.get("nested")).isEqualTo(Map.of("x", 1));
        assertThat(restored.get("tree")).isEqualTo(node);
        assertThat((byte[]) restored.get("bytes")).containsExactly(1, 2, 3);
        assertThat((Object[]) restored.get("objects")).containsExactly("a", 2L);
    }

    @Test
    @DisplayName("admits first-party com.apimarketplace types")
    void admitsFirstPartyTypes() throws Exception {
        ObjectMapper mapper = mapper();
        Object restored = mapper.readValue(mapper.writeValueAsBytes(new FirstParty("x")), Object.class);

        assertThat(restored).isInstanceOf(FirstParty.class);
    }

    /** A non-final first-party value object, so default typing writes its class id. */
    public static class FirstParty {
        public String name;

        public FirstParty() {
        }

        FirstParty(String name) {
            this.name = name;
        }
    }
}
