package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.classification.DataSensitivity;
import com.apimarketplace.common.storage.domain.QuotaStatus;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.domain.StorageStatus;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.common.storage.service.api.MappingOperations;
import com.apimarketplace.common.storage.service.api.QuotaOperations;
import com.apimarketplace.common.storage.util.JsonSkeletonGenerator;
import com.apimarketplace.common.storage.util.StorageUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LC-011 / LC-066: a RESTRICTED payload (Gmail / Drive) is always written with a bounded
 * retention deadline, so no caller can store it forever by passing a null expiry, which every
 * caller did before.
 *
 * <p>The deadline lives in {@code retention_expires_at} (V562), never in {@code expires_at}: the
 * latter is the caller's TTL, which releases before V562 enforce on their own (generic cleanup,
 * reads). These tests pin both halves: the deadline is stamped whatever the purge switch says and
 * enforced only when it is armed, and the caller's TTL behaves exactly as it did before the
 * deadline existed.
 */
@DisplayName("StorageService restricted-data retention")
@ExtendWith(MockitoExtension.class)
class StorageServiceRestrictedDataTest {

    private static final String TENANT = "tenant-1";

    @Mock private StorageRepository storageRepository;
    @Mock private QuotaOperations quotaService;
    @Mock private MappingOperations mappingService;
    @Mock private StorageUtils storageUtils;
    @Mock private JsonSkeletonGenerator skeletonGenerator;
    @Mock private StorageBreakdownService breakdownService;

    private StorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new StorageService(storageRepository, quotaService, mappingService,
                storageUtils, skeletonGenerator, new ObjectMapper(), breakdownService);
        lenient().when(quotaService.checkQuotaForScope(anyString(), nullable(String.class), anyLong()))
                .thenReturn(QuotaStatus.OK);
        lenient().when(storageUtils.calculateSize(any())).thenReturn(10);
        lenient().when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
            StorageEntity entity = inv.getArgument(0);
            entity.setId(UUID.randomUUID());
            return entity;
        });
    }

    private StorageEntity saved() {
        ArgumentCaptor<StorageEntity> captor = ArgumentCaptor.forClass(StorageEntity.class);
        verify(storageRepository).save(captor.capture());
        return captor.getValue();
    }

    private void saveRestricted(Instant callerExpiry) {
        storageService.saveJsonWithContext(TENANT, Map.of("body", "mail"), "application/json", callerExpiry,
                null, "run-1", "mcp:gmail", 0, 1, 0, "wf-1", "STEP_OUTPUT", DataSensitivity.RESTRICTED);
    }

    private void disarmPurge() throws Exception {
        java.lang.reflect.Field flag = StorageService.class.getDeclaredField("restrictedPurgeArmed");
        flag.setAccessible(true);
        flag.setBoolean(storageService, false);
    }

    private static StorageEntity row(String sensitivity, Instant expiresAt, Instant retentionExpiresAt) {
        StorageEntity row = new StorageEntity(TENANT, "application/json", Map.of("a", 1), 10, "c", expiresAt);
        row.setId(UUID.randomUUID());
        row.setDataSensitivity(sensitivity);
        row.setRetentionExpiresAt(retentionExpiresAt);
        return row;
    }

    private Optional<StorageEntity> read(StorageEntity row) {
        lenient().when(storageRepository.findByIdAndTenantId(row.getId(), TENANT)).thenReturn(Optional.of(row));
        return storageService.getEntityById(row.getId(), TENANT);
    }

    private static void assertAboutThirtyDaysFrom(Instant actual, Instant before) {
        assertThat(actual).isBetween(before.plus(Duration.ofDays(30)).minusSeconds(5),
                Instant.now().plus(Duration.ofDays(30)).plusSeconds(5));
    }

    // --- write path -------------------------------------------------------------------------

    @Test
    @DisplayName("a RESTRICTED payload saved with a null expiry gets the tag and a 30-day retention deadline; expires_at stays null")
    void restrictedPayloadGetsRetentionDeadline() {
        Instant before = Instant.now();

        saveRestricted(null);

        StorageEntity row = saved();
        assertThat(row.getDataSensitivity()).isEqualTo("RESTRICTED");
        assertAboutThirtyDaysFrom(row.getRetentionExpiresAt(), before);
        assertThat(row.getExpiresAt()).isNull();
    }

    @Test
    @DisplayName("a caller's expiry on a RESTRICTED payload is stored untouched, sooner or later than the window")
    void callerExpiryStoredUntouched() {
        Instant soon = Instant.now().plus(Duration.ofDays(2)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        Instant late = Instant.now().plus(Duration.ofDays(90)).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

        saveRestricted(soon);
        saveRestricted(late);

        ArgumentCaptor<StorageEntity> captor = ArgumentCaptor.forClass(StorageEntity.class);
        verify(storageRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(StorageEntity::getExpiresAt).containsExactly(soon, late);
        // The window lives in its own column; the armed sweep deletes at the earlier of the two.
        assertThat(captor.getAllValues()).allSatisfy(r ->
                assertThat(r.getRetentionExpiresAt()).isAfter(Instant.now().plus(Duration.ofDays(29))));
    }

    @Test
    @DisplayName("a NORMAL payload keeps the caller's (null) expiry, the NORMAL tag and no retention deadline")
    void normalPayloadUnchanged() {
        storageService.saveJsonWithContext(TENANT, Map.of("a", 1), "application/json", null,
                null, "run-1", "k", 0, 1, 0, "wf-1", "STEP_OUTPUT");

        StorageEntity row = saved();
        assertThat(row.getDataSensitivity()).isEqualTo("NORMAL");
        assertThat(row.getExpiresAt()).isNull();
        assertThat(row.getRetentionExpiresAt()).isNull();
    }

    @Test
    @DisplayName("LC-011 regression: with the purge disarmed a restricted row STILL gets its retention deadline, and expires_at is left to the caller")
    void lc011RetentionDeadlineStampedWhilePurgeDisarmed() throws Exception {
        disarmPurge();
        Instant before = Instant.now();

        saveRestricted(null);

        StorageEntity row = saved();
        assertThat(row.getDataSensitivity()).isEqualTo("RESTRICTED");
        // Before the stamp-always fix nothing was stamped while disarmed; before the separate
        // column the stamp went into expires_at, which a previous release would enforce.
        assertAboutThirtyDaysFrom(row.getRetentionExpiresAt(), before);
        assertThat(row.getExpiresAt()).isNull();
    }

    // --- markRestricted ---------------------------------------------------------------------

    @Test
    @DisplayName("markRestricted tags the rows and bounds their retention deadline to the window, armed or disarmed, without touching expires_at")
    void markRestrictedBoundsRetentionDeadlineOnly() throws Exception {
        List<UUID> ids = List.of(UUID.randomUUID());
        when(storageRepository.markRestricted(TENANT, ids)).thenReturn(1);

        assertThat(storageService.markRestricted(TENANT, ids, null)).isEqualTo(1);
        disarmPurge();
        storageService.markRestricted(TENANT, ids, null);

        ArgumentCaptor<Instant> deadline = ArgumentCaptor.forClass(Instant.class);
        verify(storageRepository, org.mockito.Mockito.times(2)).boundRetentionExpiry(eq(TENANT), eq(ids), deadline.capture());
        assertThat(deadline.getAllValues()).allSatisfy(d -> assertThat(d).isAfter(Instant.now().plus(Duration.ofDays(29))));
        verify(storageRepository, never()).boundExpiry(any(), any(), any());
    }

    @Test
    @DisplayName("LC-011 backfill race: an old row already RESTRICTED with no deadline gets created_at + window, not now + window")
    void lc011BackfillRaceOldRestrictedRowGetsCreatedAtDeadline() {
        UUID oldRow = UUID.randomUUID();
        UUID agedOut = UUID.randomUUID();
        UUID newlyTagged = UUID.randomUUID();
        List<UUID> ids = List.of(oldRow, agedOut, newlyTagged);
        Instant twentyDaysAgo = Instant.now().minus(Duration.ofDays(20));
        Instant fortyDaysAgo = Instant.now().minus(Duration.ofDays(40));
        // Only the rows already RESTRICTED with no deadline come back (written before V562).
        when(storageRepository.findRestrictedWithoutRetentionDeadline(TENANT, ids)).thenReturn(List.of(
                new Object[] {oldRow, twentyDaysAgo}, new Object[] {agedOut, fortyDaysAgo}));
        Instant before = Instant.now();

        storageService.markRestricted(TENANT, ids, null);

        // Same deadline as storage-service's catch-up, whichever of the two runs first.
        verify(storageRepository).boundRetentionExpiry(TENANT, List.of(oldRow), twentyDaysAgo.plus(Duration.ofDays(30)));
        verify(storageRepository).boundRetentionExpiry(TENANT, List.of(agedOut), fortyDaysAgo.plus(Duration.ofDays(30)));
        // The catch-up rows are read before this call tags anything, so a newly tagged row is not one.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(storageRepository);
        order.verify(storageRepository).findRestrictedWithoutRetentionDeadline(TENANT, ids);
        order.verify(storageRepository).markRestricted(TENANT, ids);
        // Every other row (the newly tagged one) keeps the fresh now + window, which only moves earlier.
        ArgumentCaptor<Instant> fresh = ArgumentCaptor.forClass(Instant.class);
        order.verify(storageRepository).boundRetentionExpiry(eq(TENANT), eq(ids), fresh.capture());
        assertAboutThirtyDaysFrom(fresh.getValue(), before);
    }

    @Test
    @DisplayName("LC-011 backfill race: a catch-up deadline is never later than now + window (created_at in the future, or missing)")
    void lc011CatchUpDeadlineCappedAtFreshDeadline() {
        UUID skewed = UUID.randomUUID();
        UUID undated = UUID.randomUUID();
        List<UUID> ids = List.of(skewed, undated, UUID.randomUUID());
        when(storageRepository.findRestrictedWithoutRetentionDeadline(TENANT, ids)).thenReturn(List.of(
                new Object[] {skewed, Instant.now().plus(Duration.ofDays(3))}, new Object[] {undated, null}));
        Instant before = Instant.now();

        storageService.markRestricted(TENANT, ids, null);

        ArgumentCaptor<Instant> catchUp = ArgumentCaptor.forClass(Instant.class);
        verify(storageRepository).boundRetentionExpiry(eq(TENANT), eq(List.of(skewed, undated)), catchUp.capture());
        assertAboutThirtyDaysFrom(catchUp.getValue(), before);
    }

    @Test
    @DisplayName("markRestricted with a caller expiry bounds expires_at to exactly that expiry")
    void markRestrictedBoundsCallerExpiry() {
        List<UUID> ids = List.of(UUID.randomUUID());
        Instant ttl = Instant.now().plus(Duration.ofHours(6));

        storageService.markRestricted(TENANT, ids, ttl);

        verify(storageRepository).boundExpiry(TENANT, ids, ttl);
    }

    @Test
    @DisplayName("markRestricted with no ids touches nothing")
    void markRestrictedEmpty() {
        assertThat(storageService.markRestricted(TENANT, List.of(), null)).isZero();
        verify(storageRepository, never()).markRestricted(any(), any());
        verify(storageRepository, never()).boundRetentionExpiry(any(), any(), any());
    }

    // --- generic cleanup: caller TTL exactly as before the retention deadline existed ----------

    @Test
    @DisplayName("LC-011 rollback parity: disarmed, the generic cleanup soft-deletes a RESTRICTED row past the CALLER's TTL, as before")
    void lc011CallerTtlOnRestrictedRowHonouredByCleanupWhileDisarmed() throws Exception {
        disarmPurge();
        StorageEntity expiredByCaller = row("RESTRICTED", Instant.now().minus(Duration.ofHours(1)), null);
        when(storageRepository.findExpiredStoragesIncludingRestricted(any(Instant.class)))
                .thenReturn(List.of(expiredByCaller));

        assertThat(storageService.cleanupExpired()).isEqualTo(1);

        verify(storageRepository).updateStatus(expiredByCaller.getId(), StorageStatus.DELETED);
        verify(storageRepository, never()).findExpiredStorages(any());
    }

    @Test
    @DisplayName("armed, the generic cleanup leaves RESTRICTED rows to the hard-delete sweep (findExpiredStorages excludes them)")
    void armedCleanupLeavesRestrictedRowsToTheSweep() {
        when(storageRepository.findExpiredStorages(any(Instant.class))).thenReturn(List.of());

        storageService.cleanupExpired();

        verify(storageRepository).findExpiredStorages(any(Instant.class));
        verify(storageRepository, never()).findExpiredStoragesIncludingRestricted(any());
    }

    // --- reads --------------------------------------------------------------------------------

    @Test
    @DisplayName("LC-011 rollback parity: disarmed, a RESTRICTED row past the CALLER's TTL is refused, as before")
    void lc011CallerTtlOnRestrictedRowRefusedOnReadsWhileDisarmed() throws Exception {
        disarmPurge();

        assertThat(read(row("RESTRICTED", Instant.now().minus(Duration.ofMinutes(1)), null))).isEmpty();
        assertThat(read(row("RESTRICTED", Instant.now().plus(Duration.ofDays(1)), null))).isPresent();
    }

    @Test
    @DisplayName("LC-011: disarmed, a RESTRICTED row past its retention deadline stays readable (the deadline is not enforced yet)")
    void lc011RetentionDeadlineNotEnforcedOnReadsWhileDisarmed() throws Exception {
        disarmPurge();
        StorageEntity row = row("RESTRICTED", null, Instant.now().minus(Duration.ofDays(1)));

        assertThat(read(row)).contains(row);
    }

    @Test
    @DisplayName("LC-011: armed, a RESTRICTED row past its retention deadline is refused; one inside it, and a NORMAL row, are served")
    void lc011RetentionDeadlineEnforcedOnReadsWhenArmed() {
        StorageEntity past = row("RESTRICTED", null, Instant.now().minus(Duration.ofDays(1)));
        StorageEntity inside = row("RESTRICTED", null, Instant.now().plus(Duration.ofDays(1)));
        StorageEntity unstamped = row("RESTRICTED", null, null);
        StorageEntity normal = row("NORMAL", null, Instant.now().minus(Duration.ofDays(1)));

        assertThat(read(past)).isEmpty();
        assertThat(read(inside)).contains(inside);
        // Written by a previous release, not yet caught up by the backfill: nothing to enforce.
        assertThat(read(unstamped)).contains(unstamped);
        // The deadline only means something on a RESTRICTED row.
        assertThat(read(normal)).contains(normal);
    }

    @Test
    @DisplayName("an expired NORMAL row is refused whether the purge is armed or not")
    void expiredNormalRowRefusedEitherWay() throws Exception {
        assertThat(read(row("NORMAL", Instant.now().minus(Duration.ofDays(1)), null))).isEmpty();
        disarmPurge();
        assertThat(read(row("NORMAL", Instant.now().minus(Duration.ofDays(1)), null))).isEmpty();
    }

    @Test
    @DisplayName("runHoldsRestrictedData asks the repository for a RESTRICTED row of the run")
    void runTaintLookup() {
        when(storageRepository.existsByRunIdAndDataSensitivity("run-9", "RESTRICTED")).thenReturn(true);
        assertThat(storageService.runHoldsRestrictedData("run-9")).isTrue();
        assertThat(storageService.runHoldsRestrictedData(null)).isFalse();
    }

    // --- LC-066 durable "restricted since" (V564) ------------------------------------------

    private static final Instant FIRST = Instant.parse("2026-09-01T10:00:00Z");

    @Test
    @DisplayName("LC-066 retention skew: a partly purged run keeps its recorded first moment, not the oldest row left")
    void restrictedSinceSurvivesPartialPurge() {
        // The purge deleted the run's oldest restricted rows: the oldest left is a week later.
        when(storageRepository.findRecordedRestrictedSince("run-p")).thenReturn(java.sql.Timestamp.from(FIRST));
        when(storageRepository.findFirstRestrictedCreatedAt("run-p")).thenReturn(FIRST.plus(Duration.ofDays(7)));

        assertThat(storageService.firstRestrictedDataAt("run-p")).contains(FIRST);
    }

    @Test
    @DisplayName("LC-066: a restricted row older than the recorded moment (tagged later by the backfill) wins: the answer only moves earlier")
    void olderRestrictedRowWinsOverRecord() {
        when(storageRepository.findRecordedRestrictedSince("run-b")).thenReturn(FIRST.atOffset(java.time.ZoneOffset.UTC));
        when(storageRepository.findFirstRestrictedCreatedAt("run-b")).thenReturn(FIRST.minus(Duration.ofHours(1)));

        assertThat(storageService.firstRestrictedDataAt("run-b")).contains(FIRST.minus(Duration.ofHours(1)));
    }

    @Test
    @DisplayName("LC-066: a run with rows but no record yet (written by a previous release) answers its oldest row; neither = unknown")
    void restrictedSinceFallbacks() {
        when(storageRepository.findFirstRestrictedCreatedAt("run-old")).thenReturn(FIRST);
        assertThat(storageService.firstRestrictedDataAt("run-old")).contains(FIRST);

        // Every row purged and nothing recorded: unknown (empty), never a moment that clears a copy.
        assertThat(storageService.firstRestrictedDataAt("run-gone")).isEmpty();
        assertThat(storageService.firstRestrictedDataAt(null)).isEmpty();
    }

    @Test
    @DisplayName("LC-066: a run whose restricted rows were all purged still has a recorded restriction")
    void recordedRestrictionOutlivesRows() {
        when(storageRepository.findRecordedRestrictedSince("run-purged")).thenReturn(java.sql.Timestamp.from(FIRST));

        assertThat(storageService.runHasRecordedRestriction("run-purged")).isTrue();
        assertThat(storageService.runHasRecordedRestriction("run-clean")).isFalse();
        assertThat(storageService.runHasRecordedRestriction(null)).isFalse();
    }

    @Test
    @DisplayName("LC-066: the first restricted payload of a run records the run's restricted-since moment; a NORMAL one does not")
    void restrictedWriteRecordsRestrictedSince() {
        saveRestricted(null);
        StorageEntity row = saved();
        verify(storageRepository).recordRestrictedSince("run-1", row.getCreatedAt());

        storageService.saveJsonWithContext(TENANT, Map.of("x", 1), "application/json", null,
                null, "run-2", "core:x", 0, 1, 0, "wf-1", "STEP_OUTPUT", DataSensitivity.NORMAL);
        verify(storageRepository, never()).recordRestrictedSince(eq("run-2"), any());
    }

    @Test
    @DisplayName("LC-066: markRestricted records, per run, the oldest of the rows it tagged")
    void markRestrictedRecordsRestrictedSince() {
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
        when(storageRepository.markRestricted(TENANT, ids)).thenReturn(2);
        when(storageRepository.findFirstCreatedAtByRun(TENANT, ids))
                .thenReturn(List.<Object[]>of(new Object[] {"run-m", FIRST}));

        storageService.markRestricted(TENANT, ids, null);

        verify(storageRepository).recordRestrictedSince("run-m", FIRST);
    }

    @Test
    @DisplayName("LC-066: markRestricted that tags nothing records nothing")
    void markRestrictedNothingTaggedRecordsNothing() {
        List<UUID> ids = List.of(UUID.randomUUID());
        when(storageRepository.markRestricted(TENANT, ids)).thenReturn(0);

        storageService.markRestricted(TENANT, ids, null);

        verify(storageRepository, never()).findFirstCreatedAtByRun(any(), any());
        verify(storageRepository, never()).recordRestrictedSince(any(), any());
    }
}
