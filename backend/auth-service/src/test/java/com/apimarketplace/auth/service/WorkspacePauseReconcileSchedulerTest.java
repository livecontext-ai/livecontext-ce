package com.apimarketplace.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspacePauseReconcileSchedulerTest {

    @Mock private WorkspacePauseReconciler reconciler;
    @InjectMocks private WorkspacePauseReconcileScheduler scheduler;

    @Test
    @DisplayName("the scheduled sweep delegates to the reconciler")
    void delegatesToReconciler() {
        when(reconciler.reconcileAll()).thenReturn(2);

        scheduler.reconcileOverCapWorkspaces();

        verify(reconciler).reconcileAll();
    }

    @Test
    @DisplayName("a reconciler failure does not propagate out of the scheduled method")
    void swallowsReconcilerFailure() {
        when(reconciler.reconcileAll()).thenThrow(new RuntimeException("boom"));

        assertThatCode(scheduler::reconcileOverCapWorkspaces).doesNotThrowAnyException();

        verify(reconciler).reconcileAll();
    }
}
