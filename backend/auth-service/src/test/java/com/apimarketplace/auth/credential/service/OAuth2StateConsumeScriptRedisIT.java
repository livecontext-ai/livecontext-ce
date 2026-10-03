package com.apimarketplace.auth.credential.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LC-089 against a REAL Redis: the state consume script is atomic read-and-delete, so of N
 * callbacks racing on one state exactly one gets the blob. Mocked Redis cannot prove this; the
 * script's Lua and its null reply are what is under test.
 *
 * <p>Redis source, in order: a Testcontainers {@code redis:7-alpine} (CI), else the local dev
 * Redis on {@code localhost:6379}. With neither, the test is SKIPPED (assumption), never green.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("OAuth2 state consume script on a real Redis (LC-089)")
class OAuth2StateConsumeScriptRedisIT {

    private GenericContainer<?> container;
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;

    @BeforeAll
    void connect() {
        String host = null;
        int port = 0;
        try {
            container = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
            container.start();
            host = container.getHost();
            port = container.getMappedPort(6379);
        } catch (Throwable dockerUnavailable) {
            container = null;
        }
        if (host == null) {
            host = "localhost";
            port = 6379;
        }
        factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
        boolean reachable;
        try {
            reachable = "PONG".equalsIgnoreCase(redis.getConnectionFactory().getConnection().ping());
        } catch (RuntimeException unreachable) {
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "no Redis available (Docker or localhost:6379)");
    }

    @AfterAll
    void close() {
        if (factory != null) factory.destroy();
        if (container != null) container.stop();
    }

    @Test
    @DisplayName("returns the blob once and deletes it; the second consume gets null")
    void consumesOnce() {
        String key = "oauth2:state:it-" + UUID.randomUUID();
        redis.opsForValue().set(key, "{\"userId\":\"u\"}");

        String first = redis.execute(OAuth2Service.CONSUME_STATE_SCRIPT, List.of(key));
        String second = redis.execute(OAuth2Service.CONSUME_STATE_SCRIPT, List.of(key));

        assertThat(first).isEqualTo("{\"userId\":\"u\"}");
        assertThat(second).isNull();
        assertThat(redis.hasKey(key)).isFalse();
    }

    @Test
    @DisplayName("an unknown state is a null reply, not an error")
    void unknownIsNull() {
        assertThat(redis.execute(OAuth2Service.CONSUME_STATE_SCRIPT, List.of("oauth2:state:missing-" + UUID.randomUUID())))
                .isNull();
    }

    @Test
    @DisplayName("16 concurrent callbacks on one state: exactly one wins")
    void exactlyOneWinner() throws Exception {
        String key = "oauth2:state:race-" + UUID.randomUUID();
        redis.opsForValue().set(key, "blob");
        int n = 16;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Callable<String> call = () -> {
                gate.await();
                return redis.execute(OAuth2Service.CONSUME_STATE_SCRIPT, List.of(key));
            };
            results.add(pool.submit(call));
        }
        gate.countDown();
        int winners = 0;
        for (Future<String> f : results) {
            if ("blob".equals(f.get())) winners++;
        }
        pool.shutdown();

        assertThat(winners).isEqualTo(1);
    }
}
