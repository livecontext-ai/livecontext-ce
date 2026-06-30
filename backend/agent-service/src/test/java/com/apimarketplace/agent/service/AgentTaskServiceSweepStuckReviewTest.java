package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.AgentTaskEntity;
import com.apimarketplace.agent.repository.AgentExecutionRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.AgentTaskEventRepository;
import com.apimarketplace.agent.repository.AgentTaskNoteRepository;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.conversation.client.ConversationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Core logic of {@link AgentTaskService#sweepStuckReviewTasks(long, int)} - the
 * once-a-minute recovery sweep for {@code in_review} tasks whose retry schedule was
 * lost across a restart. {@link TaskReviewSweeper} (the scheduled facade) is tested
 * separately against a mocked service; this exercises the REAL method:
 * <ul>
 *   <li>empty result is a no-op (returns 0);</li>
 *   <li>a row with no reviewer is defensively skipped (never auto-failed);</li>
 *   <li>a row at/over its review-attempt cap is auto-failed, counted ONLY when the
 *       CAS wins (a lost race {@literal ->} another path already recovered it);</li>
 *   <li>a per-task {@code maxReviewAttempts} override raises the cap so a row below it
 *       is re-triggered, not auto-failed;</li>
 *   <li>a row below the cap is re-triggered (counted) without auto-failing;</li>
 *   <li>one throwing row does not abort the batch;</li>
 *   <li>the stale cutoff and page bound are derived from the method arguments.</li>
 * </ul>
 * The {@code self} reference is the {@code REQUIRES_NEW} seam for
 * {@code autoFailAfterReviewerRejection}, so it is a separate mock from the service
 * under test.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AgentTaskService.sweepStuckReviewTasks")
class AgentTaskServiceSweepStuckReviewTest {

    @Mock private AgentTaskRepository taskRepository;
    @Mock private AgentTaskNoteRepository noteRepository;
    @Mock private AgentTaskEventRepository eventRepository;
    @Mock private AgentRepository agentRepository;
    @Mock private AgentExecutionRepository executionRepository;
    @Mock private TaskBoardPublisher taskBoardPublisher;
    @Mock private ConversationClient conversationClient;
    @Mock private AgentTaskService self;

    private AgentTaskService service;

    private static final String TENANT = "tenant-1";

    @BeforeEach
    void setUp() {
        service = new AgentTaskService(taskRepository, noteRepository, eventRepository,
                agentRepository, executionRepository, taskBoardPublisher, conversationClient, self);
        // Pin the async re-trigger path's early exit so the synchronous assertions can never
        // race the background thread: the below-cap branch dispatches executeReviewerForTask on
        // the common pool, which first resolves the task via findByIdAndTenantId (org scope is
        // null off-request). Forcing empty makes that async task return BEFORE touching self.*,
        // so verify(self, never()) on the re-trigger/skip tests is deterministic.
        lenient().when(taskRepository.findByIdAndTenantId(any(), any())).thenReturn(java.util.Optional.empty());
    }

    private AgentTaskEntity reviewTask(UUID reviewer, int attemptCount, Integer maxAttempts) {
        AgentTaskEntity t = new AgentTaskEntity();
        t.setId(UUID.randomUUID());
        t.setTenantId(TENANT);
        t.setStatus(AgentTaskEntity.STATUS_IN_REVIEW);
        t.setReviewerAgentId(reviewer);
        t.setReviewAttemptCount(attemptCount);
        t.setMaxReviewAttempts(maxAttempts);
        return t;
    }

    @Test
    @DisplayName("empty result set -> returns 0, never auto-fails")
    void emptyNoOp() {
        when(taskRepository.findStuckInReviewTasks(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(service.sweepStuckReviewTasks(120L, 50)).isZero();
        verify(self, never()).autoFailAfterReviewerRejection(any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("a row with no reviewer is skipped defensively (not counted, not auto-failed)")
    void nullReviewerSkipped() {
        AgentTaskEntity t = reviewTask(null, 0, null);
        when(taskRepository.findStuckInReviewTasks(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(t));

        assertThat(service.sweepStuckReviewTasks(120L, 50)).isZero();
        verify(self, never()).autoFailAfterReviewerRejection(any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("at the default cap -> auto-failed + counted on CAS win; forwards the reviewer execution id and an attempt-count reason")
    void atCapCasWonCounts() {
        UUID reviewer = UUID.randomUUID();
        UUID execId = UUID.randomUUID();
        AgentTaskEntity t = reviewTask(reviewer, 3, null); // default cap = 3, so 3 >= 3
        t.setReviewerExecutionId(execId);
        when(taskRepository.findStuckInReviewTasks(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(t));
        when(self.autoFailAfterReviewerRejection(eq(t.getId()), eq(TENANT), eq(reviewer), eq(execId), anyString()))
                .thenReturn(t); // CAS won

        assertThat(service.sweepStuckReviewTasks(120L, 50)).isEqualTo(1);
        // The reviewer execution id is forwarded (selects the execution-scoped CAS), and the
        // reason embeds the attempt count so the audit trail explains the auto-fail.
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(self).autoFailAfterReviewerRejection(eq(t.getId()), eq(TENANT), eq(reviewer), eq(execId), reason.capture());
        assertThat(reason.getValue()).contains("3 attempt");
    }

    @Test
    @DisplayName("at the cap but the CAS is lost -> auto-fail attempted, NOT counted")
    void atCapCasLostNotCounted() {
        UUID reviewer = UUID.randomUUID();
        AgentTaskEntity t = reviewTask(reviewer, 3, null);
        when(taskRepository.findStuckInReviewTasks(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(t));
        when(self.autoFailAfterReviewerRejection(eq(t.getId()), eq(TENANT), eq(reviewer), any(), anyString()))
                .thenReturn(null); // CAS lost - another path already recovered the task

        assertThat(service.sweepStuckReviewTasks(120L, 50)).isZero();
        verify(self).autoFailAfterReviewerRejection(eq(t.getId()), eq(TENANT), eq(reviewer), any(), anyString());
    }

    @Test
    @DisplayName("a per-task maxReviewAttempts override raises the cap so a below-override row is re-triggered, not auto-failed")
    void overrideRaisesCap() {
        UUID reviewer = UUID.randomUUID();
        // attempt 3 would be AT the default cap (3), but the override of 5 puts it below.
        AgentTaskEntity t = reviewTask(reviewer, 3, 5);
        when(taskRepository.findStuckInReviewTasks(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(t));

        assertThat(service.sweepStuckReviewTasks(120L, 50)).isEqualTo(1);
        verify(self, never()).autoFailAfterReviewerRejection(any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("a clamped-DOWN override (maxAttempts=1) makes an otherwise-below-default row at-cap -> auto-failed")
    void overrideClampedDownAtCap() {
        UUID reviewer = UUID.randomUUID();
        // attempt 2 is below the default cap (3), but the override of 1 clamps the cap to 1,
        // so 2 >= 1 -> at cap -> auto-fail. Proves the clamp result feeds the sweep decision.
        AgentTaskEntity t = reviewTask(reviewer, 2, 1);
        when(taskRepository.findStuckInReviewTasks(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(t));
        when(self.autoFailAfterReviewerRejection(eq(t.getId()), eq(TENANT), eq(reviewer), any(), anyString()))
                .thenReturn(t);

        assertThat(service.sweepStuckReviewTasks(120L, 50)).isEqualTo(1);
        verify(self).autoFailAfterReviewerRejection(eq(t.getId()), eq(TENANT), eq(reviewer), any(), anyString());
    }

    @Test
    @DisplayName("below the cap -> re-triggered (counted) without auto-failing")
    void belowCapReTriggers() {
        UUID reviewer = UUID.randomUUID();
        AgentTaskEntity t = reviewTask(reviewer, 1, null); // 1 < default cap 3
        when(taskRepository.findStuckInReviewTasks(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(t));

        assertThat(service.sweepStuckReviewTasks(120L, 50)).isEqualTo(1);
        verify(self, never()).autoFailAfterReviewerRejection(any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("one throwing row does not abort the batch - only the survivor is counted")
    void batchIsolation() {
        UUID r1 = UUID.randomUUID();
        UUID r2 = UUID.randomUUID();
        AgentTaskEntity boom = reviewTask(r1, 3, null); // at cap -> auto-fail throws
        AgentTaskEntity ok = reviewTask(r2, 3, null);   // at cap -> auto-fail wins
        when(taskRepository.findStuckInReviewTasks(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(boom, ok));
        when(self.autoFailAfterReviewerRejection(eq(boom.getId()), eq(TENANT), eq(r1), any(), anyString()))
                .thenThrow(new RuntimeException("row blew up"));
        when(self.autoFailAfterReviewerRejection(eq(ok.getId()), eq(TENANT), eq(r2), any(), anyString()))
                .thenReturn(ok);

        assertThat(service.sweepStuckReviewTasks(120L, 50)).isEqualTo(1);
        // The survivor row was still processed despite the earlier row throwing.
        verify(self).autoFailAfterReviewerRejection(eq(ok.getId()), eq(TENANT), eq(r2), any(), anyString());
    }

    @Test
    @DisplayName("accumulates the triggered count across multiple rows (proves the count is not capped at 1)")
    void multiRowAccumulation() {
        AgentTaskEntity a = reviewTask(UUID.randomUUID(), 3, null);
        AgentTaskEntity b = reviewTask(UUID.randomUUID(), 3, null);
        AgentTaskEntity c = reviewTask(UUID.randomUUID(), 3, null);
        when(taskRepository.findStuckInReviewTasks(any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(a, b, c));
        when(self.autoFailAfterReviewerRejection(any(), any(), any(), any(), anyString()))
                .thenReturn(a); // every CAS wins

        assertThat(service.sweepStuckReviewTasks(120L, 50)).isEqualTo(3);
    }

    @Test
    @DisplayName("derives the stale cutoff exactly from staleThresholdSeconds and pages by batchSize")
    void cutoffAndPageBound() {
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(taskRepository.findStuckInReviewTasks(cutoff.capture(), pageable.capture()))
                .thenReturn(List.of());

        Instant before = Instant.now();
        service.sweepStuckReviewTasks(120L, 50);
        Instant after = Instant.now();

        // cutoff == callInstant - 120s, callInstant within [before, after]; the tight +/-500ms
        // slack still trips on a wrong offset (e.g. 60s instead of 120s).
        assertThat(cutoff.getValue())
                .isBetween(before.minusSeconds(120).minusMillis(500), after.minusSeconds(120).plusMillis(500));
        assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
        assertThat(pageable.getValue().getPageNumber()).isZero();
    }
}
