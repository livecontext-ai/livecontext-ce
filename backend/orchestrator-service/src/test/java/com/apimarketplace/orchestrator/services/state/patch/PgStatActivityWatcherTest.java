package com.apimarketplace.orchestrator.services.state.patch;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Plan v4 §1.11 - PgStatActivityWatcher")
class PgStatActivityWatcherTest {

    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock StringRedisTemplate redis;

    private SimpleMeterRegistry meterRegistry;
    private PgStatActivityWatcher watcher;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        watcher = new PgStatActivityWatcher(jdbc, redis, meterRegistry, true);
    }

    @Nested
    @DisplayName("Feature flag")
    class FeatureFlag {

        @Test
        @DisplayName("Watcher disabled → no DB call, no Redis call")
        void disabledIsNoOp() {
            PgStatActivityWatcher disabled =
                    new PgStatActivityWatcher(jdbc, redis, meterRegistry, false);

            disabled.pollLockWaits();

            verify(jdbc, never()).queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class));
            verify(redis, never()).convertAndSend(anyString(), any());
        }
    }

    @Nested
    @DisplayName("Lock-wait below threshold → no flip + tick counter reset")
    class BelowThreshold {

        @Test
        @DisplayName("Wait=0 → counters stay at 0, no flip")
        void zeroWaitNoFlip() {
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                    .thenReturn(0L);

            watcher.pollLockWaits();

            assertThat(watcher.__testGetLastObservedWaitMs()).isZero();
            assertThat(watcher.__testGetSustainedTicks()).isZero();
            verify(redis, never()).convertAndSend(anyString(), any());
            assertThat(meterRegistry.counter("orchestrator.pg_stat_watcher.poll_ok_count").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("Wait=400 below 500ms threshold → no flip + sustained=0")
        void belowThresholdResetsTicks() {
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                    .thenReturn(400L);

            watcher.pollLockWaits();

            assertThat(watcher.__testGetLastObservedWaitMs()).isEqualTo(400L);
            assertThat(watcher.__testGetSustainedTicks()).isZero();
            verify(redis, never()).convertAndSend(anyString(), any());
        }
    }

    @Nested
    @DisplayName("Lock-wait above threshold → sustained accumulation + flip")
    class AboveThreshold {

        @Test
        @DisplayName("Single tick above threshold → sustained=1, no flip yet (needs 2)")
        void singleTickNoFlip() {
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                    .thenReturn(600L);

            watcher.pollLockWaits();

            assertThat(watcher.__testGetSustainedTicks()).isEqualTo(1);
            verify(redis, never()).convertAndSend(anyString(), any());
        }

        @Test
        @DisplayName("2 consecutive ticks above threshold → flip published")
        void twoSustainedTicksFlips() {
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                    .thenReturn(800L);

            watcher.pollLockWaits();
            watcher.pollLockWaits();

            verify(redis).convertAndSend(eq(CasKillSwitch.CHANNEL), anyString());
            assertThat(meterRegistry.counter("orchestrator.pg_stat_watcher.flip_published_count").count())
                    .isEqualTo(1.0);
            // Counter reset after flip
            assertThat(watcher.__testGetSustainedTicks()).isZero();
        }

        @Test
        @DisplayName("Spike below threshold between 2 high ticks → counter resets, no flip")
        void interruptedSequenceNoFlip() {
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                    .thenReturn(800L)   // tick 1: above
                    .thenReturn(100L)   // tick 2: below - RESET
                    .thenReturn(800L);  // tick 3: above (sustained=1 again, no flip)

            watcher.pollLockWaits();
            watcher.pollLockWaits();
            watcher.pollLockWaits();

            verify(redis, never()).convertAndSend(anyString(), any());
            assertThat(watcher.__testGetSustainedTicks()).isEqualTo(1);
        }

        @Test
        @DisplayName("Anti-spam: re-flip within 5min suppressed")
        void antiSpam5MinSuppression() {
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                    .thenReturn(800L);

            watcher.pollLockWaits();
            watcher.pollLockWaits();  // first flip
            watcher.pollLockWaits();
            watcher.pollLockWaits();  // second sustained burst - but within 5min anti-spam

            // Only 1 Redis publish despite 2 sustained-flip thresholds crossed
            verify(redis, times(1)).convertAndSend(eq(CasKillSwitch.CHANNEL), anyString());
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("DB query fails → poll_error_count + no flip (next tick can retry)")
        void dbErrorSwallowed() {
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                    .thenThrow(new DataAccessException("DB unreachable") {});

            watcher.pollLockWaits();

            assertThat(meterRegistry.counter("orchestrator.pg_stat_watcher.poll_error_count").count())
                    .isEqualTo(1.0);
            verify(redis, never()).convertAndSend(anyString(), any());
        }

        @Test
        @DisplayName("Redis publish fails → swallowed (next tick can retry; circuit breaker still works)")
        void redisErrorSwallowed() {
            when(jdbc.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                    .thenReturn(800L);
            org.mockito.Mockito.doThrow(new RuntimeException("Redis down"))
                    .when(redis).convertAndSend(anyString(), any());

            // Should not throw
            watcher.pollLockWaits();
            watcher.pollLockWaits();
        }
    }
}
