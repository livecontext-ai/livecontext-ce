package com.apimarketplace.orchestrator.services.state.patch;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Plan v4 §1.6 - AdvisoryLockHelper")
class AdvisoryLockHelperTest {

    @Mock NamedParameterJdbcTemplate jdbc;

    private SimpleMeterRegistry meterRegistry;
    private AdvisoryLockHelper helper;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        helper = new AdvisoryLockHelper(jdbc, meterRegistry, true);
    }

    @Nested
    @DisplayName("Acquire behavior")
    class AcquireBehavior {

        @Test
        @DisplayName("acquireForRun emits SELECT pg_advisory_xact_lock with hashtextextended + namespace OR")
        void emitsCorrectSql() {
            helper.acquireForRun("run-abc");

            ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<MapSqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(MapSqlParameterSource.class);
            // Plan v4 E2E2 - helper now uses jdbc.query(... RowCallbackHandler) instead
            // of jdbc.update() to consume the void-function result without aborting the tx.
            verify(jdbc).query(sqlCaptor.capture(), paramsCaptor.capture(),
                    org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowCallbackHandler.class));

            assertThat(sqlCaptor.getValue())
                    .contains("pg_advisory_xact_lock")
                    .contains("hashtextextended")
                    .contains(":runId")
                    .contains(":nsPrefix")
                    .contains(":nsMask");

            // Verify namespace constants
            assertThat(paramsCaptor.getValue().getValues())
                    .containsEntry("runId", "run-abc")
                    .containsEntry("nsPrefix", AdvisoryLockHelper.NAMESPACE_PREFIX)
                    .containsEntry("nsMask", AdvisoryLockHelper.NAMESPACE_MASK);

            assertThat(meterRegistry.counter("orchestrator.advisory_lock.acquire_count").count())
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("Namespace prefix is 0x0100…0 (top byte 0x01 reserved per plan §26)")
        void namespacePrefixCorrect() {
            assertThat(AdvisoryLockHelper.NAMESPACE_PREFIX).isEqualTo(0x0100000000000000L);
        }

        @Test
        @DisplayName("Namespace mask preserves low 56 bits (0x00FFFFFFFFFFFFFFL - 14 F's, not 64-bit all-ones)")
        void namespaceMaskCorrect() {
            assertThat(AdvisoryLockHelper.NAMESPACE_MASK).isEqualTo(0x00FFFFFFFFFFFFFFL);
            // Critical: (prefix | (anyHash & mask)) MUST preserve top byte 0x01
            // The mask MUST NOT allow any hash bit into the top byte slot.
            assertThat(AdvisoryLockHelper.NAMESPACE_MASK >>> 56)
                    .as("top byte of mask must be 0x00 so OR with prefix preserves 0x01")
                    .isZero();
            long anyHash = 0xFFFFFFFFFFFFFFFFL;  // worst case all-ones hash
            long key = AdvisoryLockHelper.NAMESPACE_PREFIX | (anyHash & AdvisoryLockHelper.NAMESPACE_MASK);
            assertThat(key >>> 56)
                    .as("OR with prefix must always yield top byte 0x01 regardless of hash")
                    .isEqualTo(0x01L);
        }
    }

    @Nested
    @DisplayName("Defensive bail-outs")
    class DefensiveBailouts {

        @Test
        @DisplayName("null runId → no DB call")
        void nullRunIdNoOp() {
            helper.acquireForRun(null);
            verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
        }

        @Test
        @DisplayName("blank runId → no DB call")
        void blankRunIdNoOp() {
            helper.acquireForRun("");
            helper.acquireForRun("   ");
            verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
        }

        @Test
        @DisplayName("Feature flag OFF → no DB call, skip_disabled_count incremented")
        void flagOffSkips() {
            AdvisoryLockHelper disabled = new AdvisoryLockHelper(jdbc, meterRegistry, false);

            disabled.acquireForRun("run-1");

            verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
            assertThat(meterRegistry.counter("orchestrator.advisory_lock.skip_disabled_count").count())
                    .isEqualTo(1.0);
            assertThat(disabled.isEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("Error handling - fail-OPEN")
    class ErrorHandling {

        @Test
        @DisplayName("DataAccessException swallowed → error_count incremented, no throw")
        void dbErrorSwallowed() {
            // Plan v4 E2E2 - helper switched from jdbc.update to jdbc.query for the
            // void pg_advisory_xact_lock; re-stub the new method so the throw still fires.
            org.mockito.Mockito.doThrow(new DataAccessException("perm denied") {})
                    .when(jdbc).query(anyString(), any(MapSqlParameterSource.class),
                            org.mockito.ArgumentMatchers.any(org.springframework.jdbc.core.RowCallbackHandler.class));

            // Should not throw
            helper.acquireForRun("run-1");

            assertThat(meterRegistry.counter("orchestrator.advisory_lock.error_count").count())
                    .isEqualTo(1.0);
            assertThat(meterRegistry.counter("orchestrator.advisory_lock.acquire_count").count())
                    .isZero();
        }
    }
}
