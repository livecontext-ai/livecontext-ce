package com.apimarketplace.common.scaling.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RedisSemaphore Lua scripts - structural assertions")
class RedisSemaphoreLuaScriptTest {

    private static final Path LUA_DIR = Path.of("src/main/resources/lua");

    @Nested
    @DisplayName("semaphore_acquire.lua")
    class AcquireScript {

        @Test
        @DisplayName("cleans up expired owners before counting")
        void cleansUpBeforeCounting() throws Exception {
            String script = Files.readString(LUA_DIR.resolve("semaphore_acquire.lua"));
            assertThat(script).contains("ZREMRANGEBYSCORE");
            assertThat(script).contains("ZCARD");
            int cleanupIdx = script.indexOf("ZREMRANGEBYSCORE");
            int countIdx = script.indexOf("ZCARD");
            assertThat(cleanupIdx).isLessThan(countIdx);
        }

        @Test
        @DisplayName("uses MAX(current TTL, new TTL) to prevent TTL shrinkage")
        void preventsTtlShrinkage() throws Exception {
            String script = Files.readString(LUA_DIR.resolve("semaphore_acquire.lua"));
            assertThat(script).contains("redis.call('TTL', KEYS[1])");
            assertThat(script).contains("if cur_ttl < new_ttl then");
        }

        @Test
        @DisplayName("uses Redis server TIME for clock-skew safety")
        void usesServerTime() throws Exception {
            String script = Files.readString(LUA_DIR.resolve("semaphore_acquire.lua"));
            assertThat(script).contains("redis.call('TIME')");
        }
    }

    @Nested
    @DisplayName("semaphore_heartbeat.lua")
    class HeartbeatScript {

        @Test
        @DisplayName("checks owner exists before extending")
        void checksOwnerExists() throws Exception {
            String script = Files.readString(LUA_DIR.resolve("semaphore_heartbeat.lua"));
            assertThat(script).contains("ZSCORE");
            assertThat(script).contains("if not exists then return 0 end");
        }

        @Test
        @DisplayName("uses MAX(current TTL, new TTL) to prevent TTL shrinkage")
        void preventsTtlShrinkage() throws Exception {
            String script = Files.readString(LUA_DIR.resolve("semaphore_heartbeat.lua"));
            assertThat(script).contains("redis.call('TTL', KEYS[1])");
            assertThat(script).contains("if cur_ttl < new_ttl then");
        }
    }

    @Nested
    @DisplayName("semaphore_count.lua")
    class CountScript {

        @Test
        @DisplayName("atomically cleans up expired entries and returns count")
        void atomicCleanupAndCount() throws Exception {
            String script = Files.readString(LUA_DIR.resolve("semaphore_count.lua"));
            assertThat(script).contains("ZREMRANGEBYSCORE");
            assertThat(script).contains("ZCARD");
            int cleanupIdx = script.indexOf("ZREMRANGEBYSCORE");
            int countIdx = script.indexOf("ZCARD");
            assertThat(cleanupIdx).isLessThan(countIdx);
        }

        @Test
        @DisplayName("uses Redis server TIME for consistency")
        void usesServerTime() throws Exception {
            String script = Files.readString(LUA_DIR.resolve("semaphore_count.lua"));
            assertThat(script).contains("redis.call('TIME')");
        }
    }

    @Nested
    @DisplayName("semaphore_release.lua")
    class ReleaseScript {

        @Test
        @DisplayName("removes owner from ZSET")
        void removesOwner() throws Exception {
            String script = Files.readString(LUA_DIR.resolve("semaphore_release.lua"));
            assertThat(script).contains("ZREM");
        }
    }
}
