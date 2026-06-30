package com.apimarketplace.orchestrator.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * P2.1.6 - fail-CLOSED startup preconditions for state-snapshot.elide-running-nodes.
 *
 * <p>The probe gates application context startup. Each precondition (scaling.backend,
 * agent queue, Redis maxmemory-policy) is independently verified - any failure
 * throws and aborts boot. Tests cover both pass and fail arms for all three.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ElideRunningNodesPreconditionProbe")
class ElideRunningNodesPreconditionProbeTest {

    @Mock private StringRedisTemplate redis;
    @Mock private RedisConnection redisConnection;
    @Mock private RedisServerCommands serverCommands;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void wireRedisMockChain() {
        // Tests that don't exercise the maxmemory-policy probe set their own behavior
        // via verifyScalingBackendIsRedis / verifyAgentQueueEnabled directly. Lenient
        // stubbing avoids UnnecessaryStubbingException for tests that bypass the
        // verifyMaxMemoryPolicy path.
        lenient().when(redis.execute(any(RedisCallback.class))).thenAnswer(inv -> {
            RedisCallback<?> callback = inv.getArgument(0);
            return callback.doInRedis(redisConnection);
        });
        lenient().when(redisConnection.serverCommands()).thenReturn(serverCommands);
    }

    private ElideRunningNodesPreconditionProbe probe(String backend, boolean queueEnabled) {
        return new ElideRunningNodesPreconditionProbe(redis, backend, queueEnabled);
    }

    @Nested
    @DisplayName("scaling.backend probe")
    class ScalingBackendProbe {

        @Test
        @DisplayName("Passes when scaling.backend=redis")
        void passesOnRedis() {
            assertDoesNotThrow(() -> probe("redis", true).verifyScalingBackendIsRedis());
        }

        @Test
        @DisplayName("Throws when scaling.backend=memory (the default)")
        void throwsOnMemory() {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> probe("memory", true).verifyScalingBackendIsRedis());
            assertTrue(ex.getMessage().contains("scaling.backend=redis"));
        }

        @Test
        @DisplayName("Throws when scaling.backend is unset / arbitrary value")
        void throwsOnArbitrary() {
            assertThrows(IllegalStateException.class,
                    () -> probe("postgres", true).verifyScalingBackendIsRedis());
            assertThrows(IllegalStateException.class,
                    () -> probe("", true).verifyScalingBackendIsRedis());
        }

        @Test
        @DisplayName("Accepts case-insensitive Redis spelling")
        void caseInsensitive() {
            assertDoesNotThrow(() -> probe("REDIS", true).verifyScalingBackendIsRedis());
            assertDoesNotThrow(() -> probe("Redis", true).verifyScalingBackendIsRedis());
        }
    }

    @Nested
    @DisplayName("scaling.agent.queue.enabled probe")
    class AgentQueueProbe {

        @Test
        @DisplayName("Passes when scaling.agent.queue.enabled=true")
        void passesWhenTrue() {
            assertDoesNotThrow(() -> probe("redis", true).verifyAgentQueueEnabled());
        }

        @Test
        @DisplayName("Throws when scaling.agent.queue.enabled=false")
        void throwsWhenFalse() {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> probe("redis", false).verifyAgentQueueEnabled());
            assertTrue(ex.getMessage().contains("scaling.agent.queue.enabled=true"));
        }
    }

    @Nested
    @DisplayName("Redis maxmemory-policy probe")
    class MaxMemoryPolicyProbe {

        private void stubPolicy(String policy) {
            Properties p = new Properties();
            if (policy != null) p.setProperty("maxmemory_policy", policy);
            when(serverCommands.info("memory")).thenReturn(p);
        }

        @Test
        @DisplayName("Passes when policy=noeviction")
        void passesNoeviction() {
            stubPolicy("noeviction");
            assertDoesNotThrow(() -> probe("redis", true).verifyMaxMemoryPolicy());
        }

        @Test
        @DisplayName("Passes when policy=volatile-ttl")
        void passesVolatileTtl() {
            stubPolicy("volatile-ttl");
            assertDoesNotThrow(() -> probe("redis", true).verifyMaxMemoryPolicy());
        }

        @Test
        @DisplayName("Throws when policy=allkeys-lru (the dangerous default)")
        void throwsAllkeysLru() {
            stubPolicy("allkeys-lru");
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> probe("redis", true).verifyMaxMemoryPolicy());
            assertTrue(ex.getMessage().contains("maxmemory-policy"));
            assertTrue(ex.getMessage().contains("allkeys-lru"));
        }

        @Test
        @DisplayName("Throws on volatile-lru - TTL-bearing keys can still evict before TTL under pressure")
        void throwsVolatileLru() {
            stubPolicy("volatile-lru");
            assertThrows(IllegalStateException.class,
                    () -> probe("redis", true).verifyMaxMemoryPolicy());
        }

        @Test
        @DisplayName("Throws when INFO memory returns null (Redis unreachable / probe failure)")
        void throwsWhenInfoReturnsNull() {
            when(serverCommands.info("memory")).thenReturn(null);
            assertThrows(IllegalStateException.class,
                    () -> probe("redis", true).verifyMaxMemoryPolicy());
        }

        @Test
        @DisplayName("Throws when INFO memory throws (Redis exception is fail-CLOSED)")
        void throwsWhenInfoThrows() {
            when(serverCommands.info("memory")).thenThrow(new RuntimeException("Redis down"));
            assertThrows(IllegalStateException.class,
                    () -> probe("redis", true).verifyMaxMemoryPolicy());
        }

        @Test
        @DisplayName("Throws when INFO memory returns properties without maxmemory_policy key")
        void throwsWhenPolicyKeyMissing() {
            stubPolicy(null);
            assertThrows(IllegalStateException.class,
                    () -> probe("redis", true).verifyMaxMemoryPolicy());
        }
    }

    @Nested
    @DisplayName("Combined verifyPreconditions")
    class CombinedVerify {

        private void stubPolicy(String policy) {
            Properties p = new Properties();
            p.setProperty("maxmemory_policy", policy);
            when(serverCommands.info("memory")).thenReturn(p);
        }

        @Test
        @DisplayName("All three preconditions pass - no exception")
        void allPass() {
            stubPolicy("noeviction");
            assertDoesNotThrow(() -> probe("redis", true).verifyPreconditions());
        }

        @Test
        @DisplayName("scaling.backend fails first - agent queue + Redis probe never reached")
        void scalingBackendFailsFirst() {
            // No stubbing on serverCommands needed - verifyScalingBackendIsRedis throws first.
            assertThrows(IllegalStateException.class,
                    () -> probe("memory", true).verifyPreconditions());
        }

        @Test
        @DisplayName("Agent queue fails after scaling.backend passes - Redis probe never reached")
        void agentQueueFailsSecond() {
            assertThrows(IllegalStateException.class,
                    () -> probe("redis", false).verifyPreconditions());
        }

        @Test
        @DisplayName("Redis policy fails last - first two passed")
        void redisPolicyFailsLast() {
            stubPolicy("allkeys-lru");
            assertThrows(IllegalStateException.class,
                    () -> probe("redis", true).verifyPreconditions());
        }
    }
}
