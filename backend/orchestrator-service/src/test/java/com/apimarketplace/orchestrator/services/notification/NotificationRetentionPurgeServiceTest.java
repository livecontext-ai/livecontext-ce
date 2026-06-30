package com.apimarketplace.orchestrator.services.notification;

import com.apimarketplace.orchestrator.repository.NotificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationRetentionPurgeService")
class NotificationRetentionPurgeServiceTest {

    @Mock private NotificationRepository notificationRepository;

    private NotificationRetentionPurgeService service;

    @BeforeEach
    void setUp() {
        // Constructor takes a `@Lazy self` reference to engage the
        // Spring transactional proxy on `deleteChunk`. In unit tests we wire
        // `self = this` so the delegation still happens (the actual proxy is
        // exercised by integration tests).
        service = new NotificationRetentionPurgeService(notificationRepository, null);
        try {
            java.lang.reflect.Field selfField =
                    NotificationRetentionPurgeService.class.getDeclaredField("self");
            selfField.setAccessible(true);
            selfField.set(service, service);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("Single chunk under BATCH_SIZE → loop exits after one iteration")
    void singleChunkUnderBatchSizeStopsLoop() {
        when(notificationRepository.deleteOlderThanChunked(any(), eq(NotificationRetentionPurgeService.BATCH_SIZE)))
                .thenReturn(42); // < BATCH_SIZE

        service.purgeExpiredNotifications();

        verify(notificationRepository, times(1))
                .deleteOlderThanChunked(any(), eq(NotificationRetentionPurgeService.BATCH_SIZE));
    }

    @Test
    @DisplayName("Multiple chunks at BATCH_SIZE keep looping until a partial chunk")
    void multipleChunksLoopUntilDrained() {
        when(notificationRepository.deleteOlderThanChunked(any(), eq(NotificationRetentionPurgeService.BATCH_SIZE)))
                .thenReturn(NotificationRetentionPurgeService.BATCH_SIZE,
                            NotificationRetentionPurgeService.BATCH_SIZE,
                            500); // last chunk partial

        service.purgeExpiredNotifications();

        verify(notificationRepository, times(3))
                .deleteOlderThanChunked(any(), eq(NotificationRetentionPurgeService.BATCH_SIZE));
    }

    @Test
    @DisplayName("Cutoff is now() minus 30-day retention window")
    void cutoffMatchesRetentionWindow() {
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        when(notificationRepository.deleteOlderThanChunked(cutoff.capture(), eq(NotificationRetentionPurgeService.BATCH_SIZE)))
                .thenReturn(0);

        Instant before = Instant.now().minus(NotificationRetentionPurgeService.RETENTION).minusSeconds(2);
        service.purgeExpiredNotifications();
        Instant after = Instant.now().minus(NotificationRetentionPurgeService.RETENTION).plusSeconds(2);

        assertThat(cutoff.getValue()).isAfter(before).isBefore(after);
    }

    @Test
    @DisplayName("Retention duration is exactly 30 days (matches V172 backfill window)")
    void retentionIsThirtyDays() {
        assertThat(NotificationRetentionPurgeService.RETENTION).isEqualTo(Duration.ofDays(30));
    }

    @Test
    @DisplayName("MAX_LOOP_ITERATIONS guards against runaway DELETE loop on adversarial backlogs")
    void chunkedDeleteHonorsMaxLoopIterations() {
        // Repository always returns BATCH_SIZE → loop has no natural exit.
        // The MAX_LOOP_ITERATIONS bound (100) must terminate the loop and
        // surface a warning so the tail rows aren't silently abandoned.
        when(notificationRepository.deleteOlderThanChunked(any(), eq(NotificationRetentionPurgeService.BATCH_SIZE)))
                .thenReturn(NotificationRetentionPurgeService.BATCH_SIZE);

        service.purgeExpiredNotifications();

        verify(notificationRepository, times(100))
                .deleteOlderThanChunked(any(), eq(NotificationRetentionPurgeService.BATCH_SIZE));
    }
}
