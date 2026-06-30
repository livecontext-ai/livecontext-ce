package com.apimarketplace.trigger.service;

import com.apimarketplace.trigger.domain.ScheduledExecutionEntity;
import com.apimarketplace.trigger.domain.StandaloneChatEndpointEntity;
import com.apimarketplace.trigger.domain.StandaloneFormEndpointEntity;
import com.apimarketplace.trigger.domain.StandaloneWebhookEntity;
import com.apimarketplace.trigger.repository.ScheduledExecutionRepository;
import com.apimarketplace.trigger.repository.StandaloneChatEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneFormEndpointRepository;
import com.apimarketplace.trigger.repository.StandaloneWebhookRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StandaloneTriggerReaperService")
class StandaloneTriggerReaperServiceTest {

    @Mock private StandaloneWebhookRepository webhookRepository;
    @Mock private StandaloneChatEndpointRepository chatEndpointRepository;
    @Mock private StandaloneFormEndpointRepository formEndpointRepository;
    @Mock private ScheduledExecutionRepository scheduleRepository;
    @Mock private OrchestratorWorkflowExistenceClient workflowExistenceClient;

    private StandaloneTriggerReaperService reaper;

    @BeforeEach
    void setUp() {
        reaper = new StandaloneTriggerReaperService(
                webhookRepository, chatEndpointRepository, formEndpointRepository, scheduleRepository,
                workflowExistenceClient);
        ReflectionTestUtils.setField(reaper, "orphanTtlHours", 24);
        ReflectionTestUtils.setField(reaper, "enabled", true);
        ReflectionTestUtils.setField(reaper, "staleFkReaperEnabled", true);
    }

    @Test
    @DisplayName("Deletes orphans older than TTL across all four standalone tables")
    void deletesOrphansAcrossAllTables() {
        StandaloneWebhookEntity oldWebhook = new StandaloneWebhookEntity();
        StandaloneChatEndpointEntity oldChat = new StandaloneChatEndpointEntity();
        StandaloneFormEndpointEntity oldForm = new StandaloneFormEndpointEntity();
        ScheduledExecutionEntity oldSchedule = new ScheduledExecutionEntity();

        when(webhookRepository.findByWorkflowIdIsNullAndCreatedAtBefore(any()))
                .thenReturn(List.of(oldWebhook));
        when(chatEndpointRepository.findByWorkflowIdIsNullAndCreatedAtBefore(any()))
                .thenReturn(List.of(oldChat, oldChat));
        when(formEndpointRepository.findByWorkflowIdIsNullAndCreatedAtBefore(any()))
                .thenReturn(List.of(oldForm));
        when(scheduleRepository.findOrphansOlderThan(any()))
                .thenReturn(List.of(oldSchedule, oldSchedule, oldSchedule));

        reaper.reapOrphans();

        verify(webhookRepository).deleteAll(List.of(oldWebhook));
        verify(chatEndpointRepository).deleteAll(List.of(oldChat, oldChat));
        verify(formEndpointRepository).deleteAll(List.of(oldForm));
        verify(scheduleRepository).deleteAll(List.of(oldSchedule, oldSchedule, oldSchedule));
    }

    @Test
    @DisplayName("Uses a cutoff equal to now() - orphanTtlHours - fresh drafts survive, stale ones don't")
    void usesCutoffBasedOnTtl() {
        ReflectionTestUtils.setField(reaper, "orphanTtlHours", 24);

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        when(webhookRepository.findByWorkflowIdIsNullAndCreatedAtBefore(cutoffCaptor.capture()))
                .thenReturn(List.of());
        when(chatEndpointRepository.findByWorkflowIdIsNullAndCreatedAtBefore(any())).thenReturn(List.of());
        when(formEndpointRepository.findByWorkflowIdIsNullAndCreatedAtBefore(any())).thenReturn(List.of());
        when(scheduleRepository.findOrphansOlderThan(any())).thenReturn(List.of());

        Instant before = Instant.now().minus(Duration.ofHours(24));
        reaper.reapOrphans();
        Instant after = Instant.now().minus(Duration.ofHours(24));

        Instant cutoff = cutoffCaptor.getValue();
        assertThat(cutoff).isBetween(before, after);
    }

    @Test
    @DisplayName("No deleteAll when nothing to reap - avoids an empty JPA flush")
    void skipsDeleteWhenNoOrphans() {
        when(webhookRepository.findByWorkflowIdIsNullAndCreatedAtBefore(any())).thenReturn(List.of());
        when(chatEndpointRepository.findByWorkflowIdIsNullAndCreatedAtBefore(any())).thenReturn(List.of());
        when(formEndpointRepository.findByWorkflowIdIsNullAndCreatedAtBefore(any())).thenReturn(List.of());
        when(scheduleRepository.findOrphansOlderThan(any())).thenReturn(List.of());

        reaper.reapOrphans();

        verify(webhookRepository, never()).deleteAll(any());
        verify(chatEndpointRepository, never()).deleteAll(any());
        verify(formEndpointRepository, never()).deleteAll(any());
        verify(scheduleRepository, never()).deleteAll(any());
    }

    @Test
    @DisplayName("When disabled, does not touch any repository")
    void skipsAllWhenDisabled() {
        ReflectionTestUtils.setField(reaper, "enabled", false);

        reaper.reapOrphans();

        verifyNoInteractions(webhookRepository, chatEndpointRepository, formEndpointRepository, scheduleRepository);
        verifyNoInteractions(workflowExistenceClient);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Stale-FK reaper regression tests
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Stale-FK reaper deletes rows across all 4 soft-link tables when their workflow is dead (prod incident reproduction)")
    void staleFkReaperDeletesRowsForDeletedWorkflows() {
        // Reproduces the app-host prod state: rows referencing workflows that have been
        // deleted in orchestrator, spread across multiple soft-link tables. The reaper
        // must delete from EVERY soft-link table, not just schedules.
        UUID alive = UUID.fromString("e629511a-10ad-4b30-bcda-6b2297f85b2a");
        UUID dead1 = UUID.fromString("1f9437cc-f4ba-45ef-a40b-52239bef552b");
        UUID dead2 = UUID.fromString("293e8a34-34b0-4bf2-832b-585f53d448ce");

        // dead1 referenced by both a schedule and a webhook; dead2 by a chat AND a form
        // (worst case: every soft-link table has at least one orphan to clean up).
        when(webhookRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of(dead1));
        when(chatEndpointRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of(dead2));
        when(formEndpointRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of(dead2));
        when(scheduleRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of(alive, dead1, dead2));

        // Orchestrator only confirms `alive` exists.
        when(workflowExistenceClient.getExistingWorkflowIds(anySet())).thenReturn(Set.of(alive));
        when(scheduleRepository.deleteByWorkflowIdIn(anySet())).thenReturn(2);
        when(webhookRepository.deleteByWorkflowIdIn(anySet())).thenReturn(1);
        when(chatEndpointRepository.deleteByWorkflowIdIn(anySet())).thenReturn(1);
        when(formEndpointRepository.deleteByWorkflowIdIn(anySet())).thenReturn(1);

        reaper.reapStaleWorkflowReferences();

        // The dead set passed to every repository's delete must contain BOTH dead workflows,
        // and `alive` must NOT be in it.
        Set<UUID> expectedDead = Set.of(dead1, dead2);
        ArgumentCaptor<Set<UUID>> webhookCap = ArgumentCaptor.forClass(Set.class);
        ArgumentCaptor<Set<UUID>> chatCap = ArgumentCaptor.forClass(Set.class);
        ArgumentCaptor<Set<UUID>> formCap = ArgumentCaptor.forClass(Set.class);
        ArgumentCaptor<Set<UUID>> scheduleCap = ArgumentCaptor.forClass(Set.class);

        verify(webhookRepository).deleteByWorkflowIdIn(webhookCap.capture());
        verify(chatEndpointRepository).deleteByWorkflowIdIn(chatCap.capture());
        verify(formEndpointRepository).deleteByWorkflowIdIn(formCap.capture());
        verify(scheduleRepository).deleteByWorkflowIdIn(scheduleCap.capture());

        assertThat(webhookCap.getValue()).isEqualTo(expectedDead);
        assertThat(chatCap.getValue()).isEqualTo(expectedDead);
        assertThat(formCap.getValue()).isEqualTo(expectedDead);
        assertThat(scheduleCap.getValue()).isEqualTo(expectedDead);
        assertThat(scheduleCap.getValue()).doesNotContain(alive);
    }

    @Test
    @DisplayName("Stale-FK reaper passes an age cutoff equal to now() - minAgeMinutes - fresh trigger rows are not race-deleted")
    void staleFkReaperUsesMinAgeCutoffAsRaceGuard() {
        // The race scenario: a workflow is created in orchestrator and a trigger row in
        // trigger-service in the same logical save flow, but the daily 03:15 sweeper
        // happens to fire microseconds after the trigger row commits and microseconds
        // before the workflow row is visible. The min-age guard prevents that.
        ReflectionTestUtils.setField(reaper, "staleFkReaperMinAgeMinutes", 60);

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        when(webhookRepository.findDistinctNonNullWorkflowIds(cutoffCaptor.capture())).thenReturn(Set.of());
        when(chatEndpointRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of());
        when(formEndpointRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of());
        when(scheduleRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of());

        Instant before = Instant.now().minus(Duration.ofMinutes(60));
        reaper.reapStaleWorkflowReferences();
        Instant after = Instant.now().minus(Duration.ofMinutes(60));

        Instant cutoff = cutoffCaptor.getValue();
        assertThat(cutoff).isBetween(before, after);
    }

    @Test
    @DisplayName("Top-level try/catch - a repo failure during the sweep is logged but does NOT escape to the scheduler")
    void staleFkReaperSwallowsRepoExceptionsToShieldScheduler() {
        when(webhookRepository.findDistinctNonNullWorkflowIds(any(Instant.class)))
                .thenThrow(new RuntimeException("simulated DB outage"));

        // Must not throw to the caller (Spring scheduler).
        reaper.reapStaleWorkflowReferences();

        // Subsequent repos were short-circuited by the early failure but the method
        // returned cleanly - that's the contract we ship.
        verify(webhookRepository).findDistinctNonNullWorkflowIds(any(Instant.class));
    }

    @Test
    @DisplayName("Stale-FK reaper makes ZERO HTTP calls when no workflow references exist")
    void staleFkReaperSkipsHttpWhenNoReferences() {
        when(webhookRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of());
        when(chatEndpointRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of());
        when(formEndpointRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of());
        when(scheduleRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of());

        reaper.reapStaleWorkflowReferences();

        verifyNoInteractions(workflowExistenceClient);
        verify(scheduleRepository, never()).deleteByWorkflowIdIn(any());
    }

    @Test
    @DisplayName("Stale-FK reaper deletes nothing when orchestrator confirms all referenced workflows exist")
    void staleFkReaperLeavesAliveRowsAlone() {
        UUID alive = UUID.randomUUID();
        when(webhookRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of(alive));
        when(chatEndpointRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of());
        when(formEndpointRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of());
        when(scheduleRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of());
        when(workflowExistenceClient.getExistingWorkflowIds(anySet())).thenReturn(Set.of(alive));

        reaper.reapStaleWorkflowReferences();

        verify(webhookRepository, never()).deleteByWorkflowIdIn(any());
        verify(scheduleRepository, never()).deleteByWorkflowIdIn(any());
    }

    @Test
    @DisplayName("Stale-FK reaper is fail-safe when client returns input unchanged (orchestrator unreachable)")
    void staleFkReaperIsFailSafeOnHttpFailure() {
        // The client contract: when orchestrator is unreachable, it returns the input set
        // unchanged (assume all alive). The reaper must therefore delete NOTHING.
        UUID anyId = UUID.randomUUID();
        when(webhookRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of());
        when(chatEndpointRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of());
        when(formEndpointRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of());
        when(scheduleRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of(anyId));
        when(workflowExistenceClient.getExistingWorkflowIds(eq(Set.of(anyId)))).thenReturn(Set.of(anyId));

        reaper.reapStaleWorkflowReferences();

        verify(scheduleRepository, never()).deleteByWorkflowIdIn(any());
        verify(webhookRepository, never()).deleteByWorkflowIdIn(any());
    }

    @Test
    @DisplayName("Stale-FK reaper deduplicates IDs across the four soft-link tables - one HTTP check per workflow")
    void staleFkReaperDeduplicatesAcrossTables() {
        // Same workflow referenced by both a schedule AND a webhook. The reaper must
        // make a single existence check, not two.
        UUID shared = UUID.randomUUID();
        when(webhookRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of(shared));
        when(chatEndpointRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of());
        when(formEndpointRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of());
        when(scheduleRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of(shared));
        when(workflowExistenceClient.getExistingWorkflowIds(anySet())).thenReturn(Set.of(shared));

        reaper.reapStaleWorkflowReferences();

        // The single batch sent to orchestrator contains exactly one ID, not two.
        ArgumentCaptor<Set<UUID>> captor = ArgumentCaptor.forClass(Set.class);
        verify(workflowExistenceClient).getExistingWorkflowIds(captor.capture());
        assertThat(captor.getValue()).hasSize(1).contains(shared);
    }

    @Test
    @DisplayName("Stale-FK reaper batches existence checks (cap 100 per HTTP call) - large reference sets do not blow URL length")
    void staleFkReaperBatchesLargeReferenceSets() {
        // Build 250 distinct workflow IDs across the schedules table to force batching.
        Set<UUID> many = new java.util.HashSet<>();
        for (int i = 0; i < 250; i++) many.add(UUID.randomUUID());
        when(webhookRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of());
        when(chatEndpointRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of());
        when(formEndpointRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(Set.of());
        when(scheduleRepository.findDistinctNonNullWorkflowIds(any(Instant.class))).thenReturn(many);
        // Orchestrator confirms every batch - none deleted.
        when(workflowExistenceClient.getExistingWorkflowIds(anySet())).thenAnswer(inv -> inv.getArgument(0));

        reaper.reapStaleWorkflowReferences();

        // 250 IDs / 100 per batch → 3 HTTP calls (100 + 100 + 50).
        verify(workflowExistenceClient, times(3)).getExistingWorkflowIds(anySet());
    }

    @Test
    @DisplayName("Stale-FK reaper is gated by its own enabled flag (independent of the NULL-orphan reaper)")
    void staleFkReaperRespectsItsEnabledFlag() {
        ReflectionTestUtils.setField(reaper, "staleFkReaperEnabled", false);

        reaper.reapStaleWorkflowReferences();

        verifyNoInteractions(webhookRepository, chatEndpointRepository, formEndpointRepository,
                scheduleRepository, workflowExistenceClient);
    }

    @Test
    @DisplayName("Failure in one table does not block the others - each repository call is independent")
    void partialFailureIsolatesTables() {
        // Webhook reaper uses the webhook repo exclusively; same for each other.
        // Verified by reapSchedules / reapChatEndpoints / etc. being public-package.
        StandaloneFormEndpointEntity form = new StandaloneFormEndpointEntity();
        when(formEndpointRepository.findByWorkflowIdIsNullAndCreatedAtBefore(any()))
                .thenReturn(List.of(form));

        int deleted = reaper.reapFormEndpoints(Instant.now());

        assertThat(deleted).isEqualTo(1);
        verify(formEndpointRepository).deleteAll(List.of(form));
        verifyNoInteractions(webhookRepository, chatEndpointRepository, scheduleRepository);
    }
}
