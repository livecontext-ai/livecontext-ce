package com.apimarketplace.orchestrator.heartbeat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Plan v4 §10 - InstanceHeartbeatService")
class InstanceHeartbeatServiceTest {

    @Mock NamedParameterJdbcTemplate jdbc;
    @Mock LeaseShutdownCoordinator shutdownCoordinator;

    SimpleMeterRegistry meterRegistry;
    InstanceHeartbeatService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = new InstanceHeartbeatService(jdbc, shutdownCoordinator, meterRegistry, "test-instance-1");
    }

    @Nested
    @DisplayName("bootstrap - UPSERT + orphan self-cleanup")
    class BootstrapTests {

        @Test
        @DisplayName("Bootstrap UPSERT + cleanup releases stranded claims at older generation")
        void bootstrapCleansUpOrphans() {
            // UPSERT returns generation=5
            when(jdbc.queryForObject(contains("INSERT INTO orchestrator.instance_lease"),
                    any(MapSqlParameterSource.class), eq(Long.class)))
                    .thenReturn(5L);
            // Cleanup UPDATE returns 3 (3 orphan signals released)
            when(jdbc.update(contains("UPDATE orchestrator.workflow_signal_waits"),
                    any(MapSqlParameterSource.class)))
                    .thenReturn(3);

            service.bootstrap();

            assertThat(service.getCurrentGeneration()).isEqualTo(5L);
            assertThat(meterRegistry.counter("orchestrator.heartbeat.bootstrap_orphan_cleanup_count").count())
                    .isEqualTo(3.0);
        }

        @Test
        @DisplayName("Bootstrap with no orphans does not increment cleanup counter")
        void bootstrapNoOrphans() {
            when(jdbc.queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Long.class)))
                    .thenReturn(1L);
            when(jdbc.update(any(String.class), any(MapSqlParameterSource.class))).thenReturn(0);

            service.bootstrap();

            assertThat(meterRegistry.counter("orchestrator.heartbeat.bootstrap_orphan_cleanup_count").count())
                    .isEqualTo(0.0);
        }

        @Test
        @DisplayName("Cleanup SQL filters on claimed_generation < newGeneration AND claimed_by = myInstance")
        void cleanupWhereClauseFiltersByOwnInstance() {
            when(jdbc.queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Long.class)))
                    .thenReturn(7L);
            when(jdbc.update(any(String.class), any(MapSqlParameterSource.class))).thenReturn(0);

            service.bootstrap();

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
            verify(jdbc).update(sqlCaptor.capture(), paramsCaptor.capture());

            assertThat(sqlCaptor.getValue())
                    .contains("claimed_by = :id")
                    .contains("claimed_generation < :newGen")
                    .contains("retry_after = NOW() + INTERVAL '5 seconds'");
            assertThat(paramsCaptor.getValue().getValues())
                    .containsEntry("id", "test-instance-1")
                    .containsEntry("newGen", 7L);
        }
    }

    @Nested
    @DisplayName("heartbeat - 3-consecutive-timeout circuit breaker")
    class CircuitBreakerTests {

        @Test
        @DisplayName("Single timeout increments counter but does NOT trip circuit breaker")
        void singleTimeoutNoTrip() {
            when(jdbc.update(any(String.class), any(MapSqlParameterSource.class)))
                    .thenThrow(new QueryTimeoutException("statement timeout"));

            service.heartbeat();

            assertThat(meterRegistry.counter("orchestrator.heartbeat.timeout_count").count())
                    .isEqualTo(1.0);
            verify(shutdownCoordinator, never()).drainAndExit(anyInt());
        }

        @Test
        @DisplayName("3 consecutive timeouts → drainAndExit(5)")
        void threeConsecutiveTimeoutsTripsBreaker() {
            when(jdbc.update(any(String.class), any(MapSqlParameterSource.class)))
                    .thenThrow(new QueryTimeoutException("statement timeout"));

            service.heartbeat();
            service.heartbeat();
            service.heartbeat();

            assertThat(meterRegistry.counter("orchestrator.heartbeat.timeout_count").count())
                    .isEqualTo(3.0);
            verify(shutdownCoordinator, times(1)).drainAndExit(InstanceHeartbeatService.DRAIN_SECONDS);
        }

        @Test
        @DisplayName("Successful heartbeat resets consecutive-timeout counter")
        void successfulHeartbeatResetsCounter() {
            when(jdbc.update(any(String.class), any(MapSqlParameterSource.class)))
                    .thenThrow(new QueryTimeoutException("timeout"))
                    .thenThrow(new QueryTimeoutException("timeout"))
                    .thenReturn(1)   // success at attempt 3 - RESETS counter
                    .thenThrow(new QueryTimeoutException("timeout"))
                    .thenThrow(new QueryTimeoutException("timeout"));

            service.heartbeat();  // timeout 1
            service.heartbeat();  // timeout 2
            service.heartbeat();  // success → reset
            service.heartbeat();  // timeout 1 again (counter was reset)
            service.heartbeat();  // timeout 2 - still under threshold

            verify(shutdownCoordinator, never()).drainAndExit(anyInt());
            assertThat(meterRegistry.counter("orchestrator.heartbeat.ok_count").count())
                    .isEqualTo(1.0);
            assertThat(meterRegistry.counter("orchestrator.heartbeat.timeout_count").count())
                    .isEqualTo(4.0);
        }

        @Test
        @DisplayName("Non-timeout RuntimeException does NOT count toward circuit breaker")
        void nonTimeoutErrorIgnoredByBreaker() {
            when(jdbc.update(any(String.class), any(MapSqlParameterSource.class)))
                    .thenThrow(new RuntimeException("transient deadlock"))
                    .thenThrow(new RuntimeException("transient deadlock"))
                    .thenThrow(new RuntimeException("transient deadlock"))
                    .thenThrow(new RuntimeException("transient deadlock"));

            service.heartbeat();
            service.heartbeat();
            service.heartbeat();
            service.heartbeat();

            verify(shutdownCoordinator, never()).drainAndExit(anyInt());
            assertThat(meterRegistry.counter("orchestrator.heartbeat.timeout_count").count())
                    .isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("stealStaleSignals - atomic CTE NOT EXISTS form")
    class StealTests {

        @Test
        @DisplayName("Steal increments counter by stolen-rows count")
        void stealIncrementsCounter() {
            when(jdbc.update(any(String.class), any(MapSqlParameterSource.class))).thenReturn(7);

            int stolen = service.stealStaleSignals(100);

            assertThat(stolen).isEqualTo(7);
            assertThat(meterRegistry.counter("orchestrator.heartbeat.steal_count").count())
                    .isEqualTo(7.0);
        }

        @Test
        @DisplayName("Steal CTE uses NOT EXISTS form + retry_after COALESCE filter")
        void stealSqlIsNotExistsForm() {
            when(jdbc.update(any(String.class), any(MapSqlParameterSource.class))).thenReturn(0);

            service.stealStaleSignals(50);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
            verify(jdbc).update(sqlCaptor.capture(), paramsCaptor.capture());

            String sql = sqlCaptor.getValue();
            assertThat(sql)
                    .contains("NOT EXISTS")
                    .contains("FROM orchestrator.instance_lease")
                    .contains("COALESCE(sw.retry_after, '1970-01-01'::timestamptz) <= NOW()")
                    .contains("FOR UPDATE SKIP LOCKED")
                    .doesNotContain("LEFT JOIN");  // audit C M1 - must not regress
            assertThat(paramsCaptor.getValue().getValues())
                    .containsEntry("myId", "test-instance-1")
                    .containsEntry("limit", 50);
        }
    }

    @Nested
    @DisplayName("scheduledStealStaleSignals - @Scheduled poller wiring (#10a)")
    class ScheduledStealTests {

        @Test
        @DisplayName("Skips steal when currentGeneration is 0 (bootstrap not completed)")
        void skipsWhenBootstrapNotDone() {
            // Default service has currentGeneration=0 (no bootstrap fired)
            service.scheduledStealStaleSignals();

            verify(jdbc, never()).update(any(String.class), any(MapSqlParameterSource.class));
        }

        @Test
        @DisplayName("Calls stealStaleSignals(STEAL_BATCH_LIMIT) when bootstrapped")
        void callsStealAfterBootstrap() {
            // Bootstrap first
            when(jdbc.queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Long.class)))
                    .thenReturn(1L);
            when(jdbc.update(any(String.class), any(MapSqlParameterSource.class))).thenReturn(0);
            service.bootstrap();
            org.mockito.Mockito.clearInvocations(jdbc);

            // Now steal returns 3
            when(jdbc.update(any(String.class), any(MapSqlParameterSource.class))).thenReturn(3);

            service.scheduledStealStaleSignals();

            ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
            verify(jdbc).update(any(String.class), paramsCaptor.capture());
            assertThat(paramsCaptor.getValue().getValues())
                    .containsEntry("limit", InstanceHeartbeatService.STEAL_BATCH_LIMIT);
            assertThat(meterRegistry.counter("orchestrator.heartbeat.steal_count").count())
                    .isEqualTo(3.0);
        }

        @Test
        @DisplayName("Swallows RuntimeException so next tick can retry")
        void swallowsExceptions() {
            // Bootstrap first
            when(jdbc.queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Long.class)))
                    .thenReturn(1L);
            when(jdbc.update(any(String.class), any(MapSqlParameterSource.class))).thenReturn(0);
            service.bootstrap();

            // Then steal throws
            when(jdbc.update(any(String.class), any(MapSqlParameterSource.class)))
                    .thenThrow(new RuntimeException("PgBouncer pool exhausted"));

            // Should not propagate
            service.scheduledStealStaleSignals();
        }
    }

    @Nested
    @DisplayName("releaseSignalAsStale - anti peer-clobber race (audit C M3)")
    class StaleReleaseTests {

        @Test
        @DisplayName("Release succeeds when claim still ours at rejected generation")
        void releaseSucceedsWhenStillOwned() {
            when(jdbc.update(any(String.class), any(MapSqlParameterSource.class))).thenReturn(1);

            boolean released = service.releaseSignalAsStale(42L, 10L);

            assertThat(released).isTrue();
            assertThat(meterRegistry.counter("orchestrator.heartbeat.stale_release_count").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("Release fails (returns false) when peer stole the claim in the meantime - no clobber")
        void releaseFailsWhenPeerStole() {
            when(jdbc.update(any(String.class), any(MapSqlParameterSource.class))).thenReturn(0);

            boolean released = service.releaseSignalAsStale(42L, 10L);

            assertThat(released).isFalse();
            assertThat(meterRegistry.counter("orchestrator.heartbeat.stale_release_count").count())
                    .isEqualTo(0.0);
        }

        @Test
        @DisplayName("Release SQL WHERE clause includes claimed_by = myInstance AND claimed_generation = rejectedGen")
        void releaseWhereClauseIncludesGuard() {
            when(jdbc.update(any(String.class), any(MapSqlParameterSource.class))).thenReturn(1);

            service.releaseSignalAsStale(42L, 10L);

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbc).update(sqlCaptor.capture(), any(MapSqlParameterSource.class));

            String sql = sqlCaptor.getValue();
            assertThat(sql)
                    .contains("claimed_by = :myId")
                    .contains("claimed_generation = :rejectedGen")
                    .contains("retry_after = NOW() + INTERVAL '5 seconds'");
        }
    }

    @Nested
    @DisplayName("shutdown - graceful @PreDestroy DELETE")
    class ShutdownTests {

        @Test
        @DisplayName("Shutdown DELETEs the instance's lease row")
        void shutdownDeletesLeaseRow() {
            when(jdbc.update(any(String.class), any(MapSqlParameterSource.class))).thenReturn(1);

            service.shutdown();

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            verify(jdbc).update(sqlCaptor.capture(), any(MapSqlParameterSource.class));
            assertThat(sqlCaptor.getValue())
                    .contains("DELETE FROM orchestrator.instance_lease")
                    .contains("WHERE instance_id = :id");
        }

        @Test
        @DisplayName("Shutdown DELETE failure is logged but does not throw - peer will reclaim via lease expiry")
        void shutdownFailureNotThrowing() {
            when(jdbc.update(any(String.class), any(MapSqlParameterSource.class)))
                    .thenThrow(new RuntimeException("DB unreachable"));

            // Should not throw
            service.shutdown();
        }
    }

    // Mockito helper - anyInt() type-narrowed
    private static int anyInt() {
        return org.mockito.ArgumentMatchers.anyInt();
    }
}
