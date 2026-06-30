package com.apimarketplace.orchestrator.services.storage;

import com.apimarketplace.common.storage.domain.TenantStorageBreakdown;
import com.apimarketplace.common.storage.repository.StorageUsageHistoryRepository;
import com.apimarketplace.common.storage.repository.TenantStorageBreakdownRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for StorageHistoryService:
 * - snapshot clamp (defense in depth above the SQL GREATEST clamp)
 * - cron-ordering invariant (reconciliation must run before snapshot)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StorageHistoryService")
class StorageHistoryServiceTest {

    private static final String TENANT = "tenant-history-1";

    @Mock
    private StorageUsageHistoryRepository historyRepository;

    @Mock
    private TenantStorageBreakdownRepository breakdownRepository;

    private StorageHistoryService service;

    @BeforeEach
    void setUp() {
        service = new StorageHistoryService(historyRepository, breakdownRepository);
    }

    @Test
    @DisplayName("snapshotTenant clamps a negative used_bytes from breakdown to zero before upsert")
    void snapshotClampsNegativeUsedBytes() {
        TenantStorageBreakdown row = new TenantStorageBreakdown(TENANT, "EXECUTION_DATA", -1_805_499L, 95);
        when(breakdownRepository.findByTenantId(TENANT)).thenReturn(List.of(row));

        service.snapshotTenant(TENANT);

        ArgumentCaptor<Long> bytesCap = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Integer> countCap = ArgumentCaptor.forClass(Integer.class);
        verify(historyRepository).upsertSnapshot(
                anyString(), anyString(), bytesCap.capture(), countCap.capture(),
                any(LocalDate.class));

        assertThat(bytesCap.getValue()).as("would have been -1805499 pre-fix").isEqualTo(0L);
        assertThat(countCap.getValue()).isEqualTo(95);
    }

    @Test
    @DisplayName("snapshotTenant clamps a negative item_count from breakdown to zero before upsert")
    void snapshotClampsNegativeItemCount() {
        TenantStorageBreakdown row = new TenantStorageBreakdown(TENANT, "INTERFACES", 0L, -1);
        when(breakdownRepository.findByTenantId(TENANT)).thenReturn(List.of(row));

        service.snapshotTenant(TENANT);

        ArgumentCaptor<Integer> countCap = ArgumentCaptor.forClass(Integer.class);
        verify(historyRepository).upsertSnapshot(
                anyString(), anyString(), anyLong(), countCap.capture(),
                any(LocalDate.class));

        assertThat(countCap.getValue()).as("would have been -1 pre-fix").isEqualTo(0);
    }

    @Test
    @DisplayName("snapshotTenant preserves positive values unchanged in the upsert call")
    void snapshotPreservesPositiveValues() {
        TenantStorageBreakdown row = new TenantStorageBreakdown(TENANT, "FILES", 124_183_188L, 42);
        when(breakdownRepository.findByTenantId(TENANT)).thenReturn(List.of(row));

        service.snapshotTenant(TENANT);

        verify(historyRepository).upsertSnapshot(
                TENANT, "FILES", 124_183_188L, 42, LocalDate.now());
    }

    @Test
    @DisplayName("Reconciliation cron must fire strictly before the snapshot cron each day")
    void reconciliationCronFiresBeforeSnapshotCron() throws Exception {
        String reconcileCron = readCron(StorageReconciliationService.class, "dailyReconciliation");
        String snapshotCron = readCron(StorageHistoryService.class, "snapshotAllTenants");

        LocalDateTime anchor = LocalDate.now().atStartOfDay();
        LocalDateTime reconcileNext = CronExpression.parse(reconcileCron).next(anchor);
        LocalDateTime snapshotNext = CronExpression.parse(snapshotCron).next(anchor);

        assertThat(reconcileNext)
                .as("reconciliation (%s) must run before snapshot (%s) so the snapshot captures reconciled values",
                        reconcileCron, snapshotCron)
                .isBefore(snapshotNext);
    }

    private static String readCron(Class<?> cls, String methodName) throws NoSuchMethodException {
        Method method = cls.getDeclaredMethod(methodName);
        Scheduled annotation = method.getAnnotation(Scheduled.class);
        assertThat(annotation).as("@Scheduled annotation on %s.%s", cls.getSimpleName(), methodName).isNotNull();
        return annotation.cron();
    }
}
