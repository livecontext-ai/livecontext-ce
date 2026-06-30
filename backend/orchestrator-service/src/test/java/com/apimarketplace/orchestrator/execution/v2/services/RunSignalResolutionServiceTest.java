package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RunSignalResolutionService} - the shared "find the right pending
 * signal for a node → resolve → resume" orchestration reused by the REST controllers and
 * the agent {@code resolve_approval} / {@code continue_interface} tool actions.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RunSignalResolutionService - resolve + resume a paused run's signal")
class RunSignalResolutionServiceTest {

    @Mock UnifiedSignalService signalService;
    @Mock SignalResumeService signalResumeService;
    @InjectMocks RunSignalResolutionService service;

    private SignalWaitEntity signal(String nodeId, SignalType type, int epoch, long id) {
        SignalWaitEntity s = mock(SignalWaitEntity.class);
        lenient().when(s.getNodeId()).thenReturn(nodeId);
        lenient().when(s.getSignalType()).thenReturn(type);
        lenient().when(s.getEpoch()).thenReturn(epoch);
        lenient().when(s.getId()).thenReturn(id);
        lenient().when(s.getItemId()).thenReturn(null);
        return s;
    }

    @Test
    @DisplayName("resolveApproval finds the USER_APPROVAL signal, resolves it, and resumes")
    void resolveApprovalResolvesAndResumes() {
        SignalWaitEntity appr = signal("core:review", SignalType.USER_APPROVAL, 1, 7L);
        when(signalService.getActiveSignals("run1")).thenReturn(List.of(appr));
        when(signalService.resolveSignal(7L, SignalResolution.APPROVED, Map.of(), "u1")).thenReturn(true);

        var out = service.resolveApproval("run1", "core:review", SignalResolution.APPROVED, Map.of(), "u1", null, null);

        assertThat(out.ok()).isTrue();
        assertThat(out.resolution()).isEqualTo("APPROVED");
        assertThat(out.signalId()).isEqualTo(7L);
        verify(signalResumeService).resumeAfterSignal(appr);
    }

    @Test
    @DisplayName("continueInterface resolves the INTERFACE_SIGNAL with CONTINUE and resumes")
    void continueInterfaceResolvesWithContinue() {
        SignalWaitEntity iface = signal("interface:page", SignalType.INTERFACE_SIGNAL, 1, 9L);
        when(signalService.getActiveSignals("run1")).thenReturn(List.of(iface));
        when(signalService.resolveSignal(eq(9L), eq(SignalResolution.CONTINUE), anyMap(), eq("u1"))).thenReturn(true);

        var out = service.continueInterface("run1", "interface:page", Map.of(), "u1", null, null);

        assertThat(out.ok()).isTrue();
        assertThat(out.resolution()).isEqualTo("CONTINUE");
        verify(signalResumeService).resumeAfterSignal(iface);
    }

    @Test
    @DisplayName("resolveApproval does NOT pick an INTERFACE_SIGNAL on the same node (type-scoped)")
    void resolveApprovalIgnoresInterfaceSignalOnSameNode() {
        SignalWaitEntity iface = signal("core:x", SignalType.INTERFACE_SIGNAL, 1, 3L);
        when(signalService.getActiveSignals("run1")).thenReturn(List.of(iface));

        var out = service.resolveApproval("run1", "core:x", SignalResolution.APPROVED, Map.of(), "u1", null, null);

        assertThat(out.ok()).isFalse();
        assertThat(out.reason()).isEqualTo("no_pending_approval");
        verifyNoInteractions(signalResumeService);
        verify(signalService, never()).resolveSignal(any(), any(), any(), any());
    }

    @Test
    @DisplayName("no pending signal -> not-found, no resolve/resume")
    void noPendingApprovalReturnsNotFound() {
        when(signalService.getActiveSignals("run1")).thenReturn(List.of());

        var out = service.resolveApproval("run1", "core:review", SignalResolution.APPROVED, Map.of(), "u1", null, null);

        assertThat(out.ok()).isFalse();
        assertThat(out.reason()).isEqualTo("no_pending_approval");
        verifyNoInteractions(signalResumeService);
    }

    @Test
    @DisplayName("picks the LATEST epoch when several signals share a node (no epoch specified)")
    void picksLatestEpochWhenMultiple() {
        SignalWaitEntity e1 = signal("core:review", SignalType.USER_APPROVAL, 1, 1L);
        SignalWaitEntity e3 = signal("core:review", SignalType.USER_APPROVAL, 3, 2L);
        when(signalService.getActiveSignals("run1")).thenReturn(List.of(e1, e3));
        when(signalService.resolveSignal(2L, SignalResolution.APPROVED, Map.of(), "u1")).thenReturn(true);

        var out = service.resolveApproval("run1", "core:review", SignalResolution.APPROVED, Map.of(), "u1", null, null);

        assertThat(out.ok()).isTrue();
        assertThat(out.epoch()).isEqualTo(3);
        verify(signalResumeService).resumeAfterSignal(e3);
    }

    @Test
    @DisplayName("already-resolved (resolveSignal returns false) -> not ok, no resume")
    void alreadyResolvedDoesNotResume() {
        SignalWaitEntity appr = signal("core:review", SignalType.USER_APPROVAL, 1, 7L);
        when(signalService.getActiveSignals("run1")).thenReturn(List.of(appr));
        when(signalService.resolveSignal(7L, SignalResolution.APPROVED, Map.of(), "u1")).thenReturn(false);

        var out = service.resolveApproval("run1", "core:review", SignalResolution.APPROVED, Map.of(), "u1", null, null);

        assertThat(out.ok()).isFalse();
        assertThat(out.reason()).isEqualTo("already_resolved");
        verifyNoInteractions(signalResumeService);
    }

    @Test
    @DisplayName("pendingOfType filters active signals to the requested type")
    void pendingOfTypeFilters() {
        SignalWaitEntity appr = signal("core:review", SignalType.USER_APPROVAL, 1, 1L);
        SignalWaitEntity iface = signal("interface:p", SignalType.INTERFACE_SIGNAL, 1, 2L);
        when(signalService.getActiveSignals("run1")).thenReturn(List.of(appr, iface));

        assertThat(service.pendingOfType("run1", SignalType.USER_APPROVAL)).containsExactly(appr);
        assertThat(service.pendingOfType("run1", SignalType.INTERFACE_SIGNAL)).containsExactly(iface);
    }

    @Test
    @DisplayName("explicit epoch selects that epoch's signal, not the latest")
    void explicitEpochSelectsThatEpoch() {
        SignalWaitEntity e1 = signal("core:review", SignalType.USER_APPROVAL, 1, 1L);
        SignalWaitEntity e3 = signal("core:review", SignalType.USER_APPROVAL, 3, 2L);
        when(signalService.getActiveSignals("run1")).thenReturn(List.of(e1, e3));
        when(signalService.resolveSignal(1L, SignalResolution.APPROVED, Map.of(), "u1")).thenReturn(true);

        var out = service.resolveApproval("run1", "core:review", SignalResolution.APPROVED, Map.of(), "u1", 1, null);

        assertThat(out.ok()).isTrue();
        assertThat(out.epoch()).isEqualTo(1);
        verify(signalResumeService).resumeAfterSignal(e1);
    }

    @Test
    @DisplayName("item_id scopes to the matching per-item signal (split context)")
    void itemIdScopesToItem() {
        SignalWaitEntity a = signal("core:review", SignalType.USER_APPROVAL, 1, 1L);
        SignalWaitEntity b = signal("core:review", SignalType.USER_APPROVAL, 1, 2L);
        lenient().when(a.getItemId()).thenReturn("a");
        lenient().when(b.getItemId()).thenReturn("b");
        when(signalService.getActiveSignals("run1")).thenReturn(List.of(a, b));
        when(signalService.resolveSignal(2L, SignalResolution.APPROVED, Map.of(), "u1")).thenReturn(true);

        var out = service.resolveApproval("run1", "core:review", SignalResolution.APPROVED, Map.of(), "u1", null, "b");

        assertThat(out.ok()).isTrue();
        assertThat(out.signalId()).isEqualTo(2L);
        verify(signalResumeService).resumeAfterSignal(b);
    }

    @Test
    @DisplayName("resume throwing is swallowed - outcome stays ok (async listener is the safety net)")
    void resumeExceptionIsSwallowed() {
        SignalWaitEntity appr = signal("core:review", SignalType.USER_APPROVAL, 1, 7L);
        when(signalService.getActiveSignals("run1")).thenReturn(List.of(appr));
        when(signalService.resolveSignal(7L, SignalResolution.APPROVED, Map.of(), "u1")).thenReturn(true);
        doThrow(new RuntimeException("resume boom")).when(signalResumeService).resumeAfterSignal(appr);

        var out = service.resolveApproval("run1", "core:review", SignalResolution.APPROVED, Map.of(), "u1", null, null);

        assertThat(out.ok()).as("resolve succeeded; resume failure must not fail the action").isTrue();
        verify(signalResumeService).resumeAfterSignal(appr);
    }
}
