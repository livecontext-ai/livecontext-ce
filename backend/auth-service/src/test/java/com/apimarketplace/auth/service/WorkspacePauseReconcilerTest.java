package com.apimarketplace.auth.service;

import com.apimarketplace.auth.repository.OrganizationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkspacePauseReconcilerTest {

    @Mock private OrganizationRepository organizationRepository;
    @Mock private OrganizationService organizationService;
    @InjectMocks private WorkspacePauseReconciler reconciler;

    @Test
    @DisplayName("reconcileAll reconciles every owner that has an active non-personal workspace")
    void reconcilesEachOwner() {
        when(organizationRepository.findDistinctOwnerIdsWithActiveNonPersonalWorkspaces())
                .thenReturn(List.of(1L, 2L, 3L));

        int processed = reconciler.reconcileAll();

        assertThat(processed).isEqualTo(3);
        verify(organizationService).reconcileWorkspacePauseState(1L);
        verify(organizationService).reconcileWorkspacePauseState(2L);
        verify(organizationService).reconcileWorkspacePauseState(3L);
    }

    @Test
    @DisplayName("reconcileAll is a no-op when no owner has a non-personal workspace")
    void noOpWhenNoOwners() {
        when(organizationRepository.findDistinctOwnerIdsWithActiveNonPersonalWorkspaces())
                .thenReturn(List.of());

        int processed = reconciler.reconcileAll();

        assertThat(processed).isZero();
        verify(organizationService, never()).reconcileWorkspacePauseState(anyLong());
    }

    @Test
    @DisplayName("a single owner's reconcile failure is swallowed and does not stop the rest of the sweep")
    void oneFailureDoesNotStopOthers() {
        when(organizationRepository.findDistinctOwnerIdsWithActiveNonPersonalWorkspaces())
                .thenReturn(List.of(1L, 2L, 3L));
        // lenient: only owner 2 is stubbed to throw; owners 1 and 3 are unstubbed no-ops, which would
        // otherwise trip strict-stub PotentialStubbingProblem on the non-matching calls.
        lenient().doThrow(new RuntimeException("boom"))
                .when(organizationService).reconcileWorkspacePauseState(2L);

        int processed = reconciler.reconcileAll();

        // owner 2 fails but 1 and 3 are still reconciled; the failure is counted out, not propagated.
        assertThat(processed).isEqualTo(2);
        verify(organizationService).reconcileWorkspacePauseState(1L);
        verify(organizationService).reconcileWorkspacePauseState(2L);
        verify(organizationService).reconcileWorkspacePauseState(3L);
    }
}
