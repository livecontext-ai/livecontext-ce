package com.apimarketplace.orchestrator.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link OrchestratorScalingConfig}.
 *
 * <p>The pool size must TRACK the worker-permit count so it can never sit below it
 * (a pool &lt; permits causes HikariCP acquire-timeouts → HTTP 500 under load). The
 * derivation is {@code max(MIN_POOL_SIZE, workerThreads + CONNECTION_HEADROOM)} with
 * an explicit {@code scaling.hikari.max-pool-size} override.
 */
@DisplayName("OrchestratorScalingConfig")
class OrchestratorScalingConfigTest {

    private static OrchestratorScalingConfig config(DataSource ds, int workerThreads, Integer explicit) {
        return new OrchestratorScalingConfig(ds, workerThreads, explicit);
    }

    @Nested
    @DisplayName("resolvePoolSize")
    class ResolvePoolSizeTests {

        @Test
        @DisplayName("Derives pool = workerThreads + headroom when above the floor (the scaling case)")
        void derivesFromWorkerThreads() {
            int workers = 100;
            int pool = config(mock(DataSource.class), workers, null).resolvePoolSize();
            assertThat(pool).isEqualTo(workers + OrchestratorScalingConfig.CONNECTION_HEADROOM);
        }

        @Test
        @DisplayName("Default worker-threads (20) yields a modest pool above the old fixed 40")
        void defaultWorkerThreadsModestPool() {
            int pool = config(mock(DataSource.class), 20, null).resolvePoolSize();
            assertThat(pool).isEqualTo(Math.max(OrchestratorScalingConfig.MIN_POOL_SIZE,
                    20 + OrchestratorScalingConfig.CONNECTION_HEADROOM));
        }

        @Test
        @DisplayName("Small deployments stay at the floor (no-op vs the historical 40)")
        void smallDeploymentKeepsFloor() {
            int pool = config(mock(DataSource.class), 5, null).resolvePoolSize();
            assertThat(pool).isEqualTo(OrchestratorScalingConfig.MIN_POOL_SIZE);
        }

        @Test
        @DisplayName("Explicit positive override wins over the derivation")
        void explicitOverrideWins() {
            int pool = config(mock(DataSource.class), 100, 64).resolvePoolSize();
            assertThat(pool).isEqualTo(64);
        }

        @Test
        @DisplayName("Non-positive explicit override is ignored - falls back to the derivation")
        void nonPositiveOverrideIgnored() {
            int pool = config(mock(DataSource.class), 100, 0).resolvePoolSize();
            assertThat(pool).isEqualTo(100 + OrchestratorScalingConfig.CONNECTION_HEADROOM);
        }

        @Test
        @DisplayName("Regression: derived pool is NEVER below the worker-thread count (no starvation by construction)")
        void poolNeverBelowWorkerThreads() {
            for (int workers : new int[] { 5, 20, 50, 100, 250 }) {
                int pool = config(mock(DataSource.class), workers, null).resolvePoolSize();
                assertThat(pool)
                        .as("pool must cover all worker permits (workers=%d)", workers)
                        .isGreaterThanOrEqualTo(workers);
            }
        }
    }

    @Nested
    @DisplayName("tuneForRedisScaling")
    class TuneForRedisScalingTests {

        @Test
        @DisplayName("Applies the derived pool size to the HikariDataSource")
        void appliesDerivedPoolSize() {
            HikariDataSource hikari = new HikariDataSource();
            hikari.setMaximumPoolSize(10); // some prior value

            config(hikari, 100, null).tuneForRedisScaling();

            assertThat(hikari.getMaximumPoolSize()).isEqualTo(100 + OrchestratorScalingConfig.CONNECTION_HEADROOM);
        }

        @Test
        @DisplayName("Applies an explicit override to the HikariDataSource")
        void appliesExplicitOverride() {
            HikariDataSource hikari = new HikariDataSource();

            config(hikari, 20, 80).tuneForRedisScaling();

            assertThat(hikari.getMaximumPoolSize()).isEqualTo(80);
        }

        @Test
        @DisplayName("Sets connection timeout to 5 seconds")
        void shouldSetConnectionTimeout() {
            HikariDataSource hikari = new HikariDataSource();
            config(hikari, 20, null).tuneForRedisScaling();
            assertThat(hikari.getConnectionTimeout()).isEqualTo(5_000);
        }

        @Test
        @DisplayName("Sets leak detection threshold to 30 seconds")
        void shouldSetLeakDetectionThreshold() {
            HikariDataSource hikari = new HikariDataSource();
            config(hikari, 20, null).tuneForRedisScaling();
            assertThat(hikari.getLeakDetectionThreshold()).isEqualTo(30_000);
        }

        @Test
        @DisplayName("Sets minimum idle to 5")
        void shouldSetMinimumIdle() {
            HikariDataSource hikari = new HikariDataSource();
            config(hikari, 20, null).tuneForRedisScaling();
            assertThat(hikari.getMinimumIdle()).isEqualTo(5);
        }

        @Test
        @DisplayName("Does not throw when DataSource is not HikariDataSource")
        void shouldHandleNonHikariDataSource() {
            DataSource nonHikari = mock(DataSource.class);
            config(nonHikari, 20, null).tuneForRedisScaling(); // should only log a warning
        }
    }
}
