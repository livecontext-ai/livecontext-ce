package com.apimarketplace.orchestrator.services.storage;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.apimarketplace.common.storage.repository.StorageUsageHistoryRepository;
import com.apimarketplace.common.storage.repository.TenantStorageBreakdownRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * F8 regression - StorageHistoryService.snapshotAllTenants per-tenant catch
 * was at WARN, so ops alerting (filtered at ERROR) silently lost daily
 * snapshot failures until the next-day delta surfaced a UI graph gap. Fix:
 * WARN → ERROR + {@code [ExceptionClass]} prefix, per-tenant isolation
 * preserved.
 *
 * <p>Kept in a separate test file from the V184 clamp tests
 * ({@code StorageHistoryServiceTest}) to avoid cross-bundle coupling.
 */
@DisplayName("StorageHistoryService - F8 observability")
@ExtendWith(MockitoExtension.class)
class StorageHistoryServiceF8Test {

    @Mock
    private StorageUsageHistoryRepository historyRepository;

    @Mock
    private TenantStorageBreakdownRepository breakdownRepository;

    private StorageHistoryService service;
    private Logger f8Logger;
    private ListAppender<ILoggingEvent> f8Appender;

    @BeforeEach
    void setUp() {
        service = new StorageHistoryService(historyRepository, breakdownRepository);
        f8Logger = (Logger) LoggerFactory.getLogger(StorageHistoryService.class);
        f8Appender = new ListAppender<>();
        f8Appender.start();
        f8Logger.addAppender(f8Appender);
    }

    @AfterEach
    void tearDown() {
        if (f8Logger != null && f8Appender != null) {
            f8Logger.detachAppender(f8Appender);
        }
    }

    @Test
    @DisplayName("F8: snapshotAllTenants logs ERROR with exception class when a per-tenant snapshot fails")
    void f8LogsErrorWithExceptionClassOnTenantFailure() {
        when(historyRepository.findDistinctTenantIds()).thenReturn(List.of("tenant-A", "tenant-B"));
        when(breakdownRepository.findByTenantId("tenant-A")).thenReturn(Collections.emptyList());
        when(breakdownRepository.findByTenantId("tenant-B"))
                .thenThrow(new DataAccessResourceFailureException("PG conn lost"));

        // Per-tenant isolation contract: the outer cron entry must NOT throw.
        service.snapshotAllTenants();

        boolean foundError = f8Appender.list.stream()
                .anyMatch(e -> e.getLevel() == Level.ERROR
                        && e.getFormattedMessage().contains("Storage history snapshot failed")
                        && e.getFormattedMessage().contains("[DataAccessResourceFailureException]")
                        && e.getFormattedMessage().contains("tenant-B"));
        assertThat(foundError)
                .as("F8: per-tenant snapshot failure must log ERROR with exception class for ops alerting - "
                        + "captured events: " + f8Appender.list)
                .isTrue();
    }

    @Test
    @DisplayName("F8: tenant-A still snapshotted even when tenant-B fails - per-tenant isolation contract preserved")
    void f8PerTenantIsolationContractPreserved() {
        when(historyRepository.findDistinctTenantIds()).thenReturn(List.of("tenant-A", "tenant-B"));
        when(breakdownRepository.findByTenantId("tenant-A")).thenReturn(Collections.emptyList());
        when(breakdownRepository.findByTenantId("tenant-B"))
                .thenThrow(new RuntimeException("boom"));

        service.snapshotAllTenants();

        Mockito.verify(breakdownRepository).findByTenantId("tenant-A");
        Mockito.verify(breakdownRepository).findByTenantId("tenant-B");
    }
}
