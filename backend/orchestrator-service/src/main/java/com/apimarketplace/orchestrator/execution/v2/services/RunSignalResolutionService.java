package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.execution.SignalResolution;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Shared orchestration for resolving a run's pending blocking signal - a user
 * approval ({@link SignalType#USER_APPROVAL}) or an interface "continue"
 * ({@link SignalType#INTERFACE_SIGNAL}) - and resuming the DAG.
 *
 * <p>Shared primitive for the agent {@code workflow} tool actions
 * ({@code resolve_approval} / {@code continue_interface}): "find the right pending
 * signal for a node → {@link UnifiedSignalService#resolveSignal} →
 * {@link SignalResumeService#resumeAfterSignal}". It deliberately mirrors the
 * equivalent inline sequence in {@code WorkflowSignalController} (the user/UI REST
 * path) rather than coupling to it - the two are kept parallel (the controller may
 * adopt this service in a later refactor). The synchronous resume matches the
 * controller's contract: the async {@code @TransactionalEventListener} resume is a
 * safety net that may not fire in every context.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunSignalResolutionService {

    private final UnifiedSignalService signalService;
    private final SignalResumeService signalResumeService;

    /** Outcome of a resolve attempt - {@code ok=false} with a machine reason when nothing was resolved. */
    public record Outcome(boolean ok, String reason, Long signalId, Integer epoch, String resolution) {
        static Outcome notFound(String reason) { return new Outcome(false, reason, null, null, null); }
    }

    /** Currently-pending signals of a given type for a run (used for node auto-resolution + listing). */
    public List<SignalWaitEntity> pendingOfType(String runId, SignalType type) {
        return signalService.getActiveSignals(runId).stream()
                .filter(s -> s.getSignalType() == type)
                .toList();
    }

    /**
     * Resolve a {@code USER_APPROVAL} signal on {@code nodeId} (latest epoch, or the given
     * epoch/item) with APPROVED/REJECTED/TIMEOUT/CANCELLED, then resume.
     */
    public Outcome resolveApproval(String runId, String nodeId, SignalResolution resolution,
                                   Map<String, Object> data, String resolvedBy, Integer epoch, String itemId) {
        SignalWaitEntity target = find(runId, nodeId, SignalType.USER_APPROVAL, epoch, itemId);
        if (target == null) return Outcome.notFound("no_pending_approval");
        return resolveAndResume(target, resolution, data, resolvedBy);
    }

    /**
     * Continue a paused interface node: resolve its {@code INTERFACE_SIGNAL} with
     * {@link SignalResolution#CONTINUE} (the {@code __continue} advance) and resume.
     */
    public Outcome continueInterface(String runId, String nodeId, Map<String, Object> data,
                                     String resolvedBy, Integer epoch, String itemId) {
        SignalWaitEntity target = find(runId, nodeId, SignalType.INTERFACE_SIGNAL, epoch, itemId);
        if (target == null) return Outcome.notFound("no_pending_interface");
        Map<String, Object> resData = Map.of("action_name", "__continue", "data", data != null ? data : Map.of());
        return resolveAndResume(target, SignalResolution.CONTINUE, resData, resolvedBy);
    }

    /**
     * Find the target signal for a node. Scopes by itemId (split context) and epoch when
     * provided; otherwise picks the LATEST epoch's signal so an earlier epoch isn't resolved
     * by accident. Mirrors the selection logic in {@code WorkflowSignalController}.
     */
    private SignalWaitEntity find(String runId, String nodeId, SignalType type, Integer epoch, String itemId) {
        return signalService.getActiveSignals(runId).stream()
                .filter(s -> nodeId.equals(s.getNodeId()) && s.getSignalType() == type)
                .filter(s -> itemId == null || itemId.equals(s.getItemId()))
                .filter(s -> epoch == null || s.getEpoch() == epoch)
                .max(Comparator.comparingInt(SignalWaitEntity::getEpoch))
                .orElse(null);
    }

    /**
     * Shared primitive: resolve {@code target} with {@code resolution} then synchronously
     * resume the DAG. Used by the agent {@code resolve_approval} / {@code continue_interface}
     * actions; mirrors the equivalent inline resolve+resume block in {@code WorkflowSignalController}.
     */
    public Outcome resolveAndResume(SignalWaitEntity target, SignalResolution resolution,
                                     Map<String, Object> data, String resolvedBy) {
        boolean resolved = signalService.resolveSignal(target.getId(), resolution, data, resolvedBy);
        if (!resolved) {
            return new Outcome(false, "already_resolved", target.getId(), target.getEpoch(), resolution.name());
        }
        // Synchronous resume on the calling thread (same contract as WorkflowSignalController):
        // ensures successor nodes execute immediately; the async listener is a safety net.
        target.setResolution(resolution);
        target.setResolutionData(data);
        target.setResolvedAt(Instant.now());
        target.setResolvedBy(resolvedBy);
        try {
            signalResumeService.resumeAfterSignal(target);
        } catch (Exception e) {
            log.warn("[RunSignalResolution] Sync resume after signal failed (async listener is safety net): {}",
                    e.getMessage());
        }
        return new Outcome(true, "resolved", target.getId(), target.getEpoch(), resolution.name());
    }
}
