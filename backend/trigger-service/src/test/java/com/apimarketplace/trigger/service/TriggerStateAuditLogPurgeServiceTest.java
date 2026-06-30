package com.apimarketplace.trigger.service;

import com.apimarketplace.trigger.repository.TriggerStateAuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TriggerStateAuditLogPurgeService}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TriggerStateAuditLogPurgeService")
class TriggerStateAuditLogPurgeServiceTest {

    @Mock
    private TriggerStateAuditLogRepository auditLogRepository;

    private TriggerStateAuditLogPurgeService service;

    @BeforeEach
    void setUp() {
        service = new TriggerStateAuditLogPurgeService(auditLogRepository, 30L);
    }

    @Test
    @DisplayName("Cutoff is now() - retentionDays days")
    void cutoffMatchesRetentionWindow() {
        Instant beforeCall = Instant.now();
        when(auditLogRepository.deleteOldestBatch(any(), anyInt())).thenReturn(0);

        service.purgeNow();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(auditLogRepository).deleteOldestBatch(cutoffCaptor.capture(), anyInt());
        Instant captured = cutoffCaptor.getValue();
        Instant expectedLatest = Instant.now().minus(Duration.of(30, ChronoUnit.DAYS));
        Instant expectedEarliest = beforeCall.minus(Duration.of(30, ChronoUnit.DAYS));
        assertThat(captured).isBetween(expectedEarliest, expectedLatest);
    }

    @Test
    @DisplayName("Returns the deletion count on success")
    void returnsDeletionCountOnSuccess() {
        when(auditLogRepository.deleteOldestBatch(any(), anyInt())).thenReturn(42);

        int result = service.purgeNow();

        assertThat(result).isEqualTo(42);
    }

    @Test
    @DisplayName("Returns 0 when no rows to delete (logs DEBUG, not INFO)")
    void returnsZeroWhenNoRows() {
        when(auditLogRepository.deleteOldestBatch(any(), anyInt())).thenReturn(0);

        int result = service.purgeNow();

        assertThat(result).isZero();
    }

    @Test
    @DisplayName("Returns -1 on repository failure (logs WARN; next day's run retries)")
    void returnsMinusOneOnFailure() {
        when(auditLogRepository.deleteOldestBatch(any(), anyInt()))
                .thenThrow(new RuntimeException("DB unreachable"));

        int result = service.purgeNow();

        assertThat(result).isEqualTo(-1);
    }

    @Test
    @DisplayName("Custom retention window (e.g. 7 days) drives the cutoff")
    void customRetentionWindowAppliesToCutoff() {
        TriggerStateAuditLogPurgeService customService =
                new TriggerStateAuditLogPurgeService(auditLogRepository, 7L);
        Instant beforeCall = Instant.now();
        when(auditLogRepository.deleteOldestBatch(any(), anyInt())).thenReturn(0);

        customService.purgeNow();

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(auditLogRepository).deleteOldestBatch(cutoffCaptor.capture(), anyInt());
        Instant captured = cutoffCaptor.getValue();
        Instant expectedEarliest = beforeCall.minus(Duration.of(7, ChronoUnit.DAYS));
        Instant expectedLatest = Instant.now().minus(Duration.of(7, ChronoUnit.DAYS));
        assertThat(captured).isBetween(expectedEarliest, expectedLatest);
    }

    @Test
    @DisplayName("Scheduled purgeOldAuditLog delegates to purgeNow")
    void scheduledMethodDelegatesToPurgeNow() {
        // The @Scheduled method is purgeOldAuditLog; it delegates to purgeNow
        // which is the unit-testable entry point. Verify the chain.
        when(auditLogRepository.deleteOldestBatch(any(), anyInt())).thenReturn(5);

        service.purgeOldAuditLog();

        verify(auditLogRepository).deleteOldestBatch(any(), anyInt());
    }

    @Test
    @DisplayName("Failure on the @Scheduled call is swallowed (no exception escapes the cron)")
    void scheduledMethodSwallowsFailure() {
        when(auditLogRepository.deleteOldestBatch(any(), anyInt()))
                .thenThrow(new RuntimeException("DB unreachable"));

        // Must not throw - Spring's @Scheduled would otherwise log a stack
        // trace and skip the next firing in some configurations.
        service.purgeOldAuditLog();

        verify(auditLogRepository).deleteOldestBatch(any(), anyInt());
    }
}
