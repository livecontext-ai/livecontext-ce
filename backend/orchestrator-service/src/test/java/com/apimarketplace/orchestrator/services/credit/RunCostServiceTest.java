package com.apimarketplace.orchestrator.services.credit;

import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.streaming.bus.WorkflowEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RunCostService - accumulate agent cost onto a run + broadcast")
class RunCostServiceTest {

    @Mock
    private WorkflowRunRepository runRepository;
    @Mock
    private WorkflowEventPublisher eventPublisher;
    @InjectMocks
    private RunCostService service;

    @Test
    @DisplayName("increments the run + epoch bucket and emits the fresh totals")
    void incrementsAndEmits() {
        when(runRepository.incrementRunCost("run-1", "org-1", "2", new BigDecimal("0.50"))).thenReturn(1);
        when(runRepository.findCostCreditsByRunIdPublic("run-1")).thenReturn(Optional.of(new BigDecimal("1.50")));
        when(runRepository.findEpochCostByRunIdPublic("run-1", "2")).thenReturn(Optional.of(new BigDecimal("0.50")));
        when(runRepository.findWorkflowBudgetByRunIdPublic("run-1")).thenReturn(Optional.of(new BigDecimal("10")));

        service.recordAgentCost("run-1", "org-1", 2, new BigDecimal("0.50"));

        verify(runRepository).incrementRunCost("run-1", "org-1", "2", new BigDecimal("0.50"));
        verify(eventPublisher).emitRunCost("run-1", 2,
                new BigDecimal("0.50"), new BigDecimal("1.50"), new BigDecimal("10"));
    }

    @Test
    @DisplayName("emits a null budget when the workflow has none set")
    void emitsNullBudgetWhenUnset() {
        when(runRepository.incrementRunCost(eq("run-1"), isNull(), eq("1"), any())).thenReturn(1);
        when(runRepository.findCostCreditsByRunIdPublic("run-1")).thenReturn(Optional.of(new BigDecimal("0.25")));
        when(runRepository.findEpochCostByRunIdPublic("run-1", "1")).thenReturn(Optional.of(new BigDecimal("0.25")));
        when(runRepository.findWorkflowBudgetByRunIdPublic("run-1")).thenReturn(Optional.empty());

        service.recordAgentCost("run-1", null, 1, new BigDecimal("0.25"));

        verify(eventPublisher).emitRunCost("run-1", 1,
                new BigDecimal("0.25"), new BigDecimal("0.25"), null);
    }

    @Test
    @DisplayName("zero credits is a no-op (no increment, no emit)")
    void zeroCreditsNoop() {
        service.recordAgentCost("run-1", "org-1", 1, BigDecimal.ZERO);
        verifyNoInteractions(runRepository, eventPublisher);
    }

    @Test
    @DisplayName("negative credits is a no-op")
    void negativeCreditsNoop() {
        service.recordAgentCost("run-1", "org-1", 1, new BigDecimal("-1"));
        verifyNoInteractions(runRepository, eventPublisher);
    }

    @Test
    @DisplayName("null / blank runId is a no-op")
    void blankRunIdNoop() {
        service.recordAgentCost(null, "org-1", 1, new BigDecimal("1"));
        service.recordAgentCost("  ", "org-1", 1, new BigDecimal("1"));
        verifyNoInteractions(runRepository, eventPublisher);
    }

    @Test
    @DisplayName("no row matched (run deleted or scope mismatch) -> no event emitted")
    void zeroRowsNoEmit() {
        when(runRepository.incrementRunCost(any(), any(), any(), any())).thenReturn(0);

        service.recordAgentCost("run-1", "org-1", 1, new BigDecimal("1"));

        verify(eventPublisher, never()).emitRunCost(any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any());
    }

    @Test
    @DisplayName("a negative epoch is clamped to 0 for the bucket key")
    void negativeEpochClampedToZero() {
        when(runRepository.incrementRunCost("run-1", "org-1", "0", new BigDecimal("1"))).thenReturn(1);
        when(runRepository.findCostCreditsByRunIdPublic("run-1")).thenReturn(Optional.of(new BigDecimal("1")));
        when(runRepository.findEpochCostByRunIdPublic("run-1", "0")).thenReturn(Optional.of(new BigDecimal("1")));
        when(runRepository.findWorkflowBudgetByRunIdPublic("run-1")).thenReturn(Optional.empty());

        service.recordAgentCost("run-1", "org-1", -5, new BigDecimal("1"));

        verify(runRepository).incrementRunCost("run-1", "org-1", "0", new BigDecimal("1"));
    }

    @Test
    @DisplayName("an increment failure is swallowed (never throws to the caller)")
    void incrementFailureSwallowed() {
        when(runRepository.incrementRunCost(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db down"));

        // must not throw
        service.recordAgentCost("run-1", "org-1", 1, new BigDecimal("1"));

        verify(eventPublisher, never()).emitRunCost(any(), org.mockito.ArgumentMatchers.anyInt(),
                any(), any(), any());
    }
}
