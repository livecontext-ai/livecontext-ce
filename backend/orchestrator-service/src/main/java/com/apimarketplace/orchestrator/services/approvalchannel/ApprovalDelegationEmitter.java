package com.apimarketplace.orchestrator.services.approvalchannel;

import com.apimarketplace.orchestrator.domain.WorkflowRunEntity;
import com.apimarketplace.orchestrator.domain.execution.ApprovalChannelDeliveryEntity;
import com.apimarketplace.orchestrator.domain.execution.ApprovalChannelDeliveryEntity.DeliveryStatus;
import com.apimarketplace.orchestrator.domain.execution.SignalType;
import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import com.apimarketplace.orchestrator.execution.v2.services.SignalResolvedEvent;
import com.apimarketplace.orchestrator.domain.WorkflowEntity;
import com.apimarketplace.orchestrator.repository.ApprovalChannelDeliveryRepository;
import com.apimarketplace.orchestrator.repository.SignalWaitRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.repository.WorkflowRunRepository;
import com.apimarketplace.orchestrator.services.events.SignalsCancelledEvent;
import com.apimarketplace.orchestrator.services.events.WorkflowApprovalPendingEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Optional;

/**
 * AFTER_COMMIT bridge between the approval-signal lifecycle events and the
 * external approval channels ({@link ApprovalChannelNotifier}). Modeled on
 * {@code NotificationEmitter} (same events, same failure isolation) but with a
 * deliberate divergence: NO editor-run / pinned-version exclusion chain. An
 * author testing a delegated approval in the editor MUST receive the channel
 * message, otherwise the feature cannot be verified before publishing. Revisit
 * if editor noise becomes a problem.
 *
 * <p>Every listener is {@code @Async} on the dedicated delegation executor: the
 * channel work is an outbound HTTP call through the catalog and must never run
 * on (or block) the signal registration/resume threads.
 *
 * <p>Failure isolation: everything is caught, counted
 * ({@code approval.delegation.errors}) and logged. A channel failure never
 * affects the workflow: the approval stays resolvable in-app.
 */
@Component
public class ApprovalDelegationEmitter {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalDelegationEmitter.class);

    private final SignalWaitRepository signalWaitRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowRepository workflowRepository;
    private final ApprovalChannelDeliveryRepository deliveryRepository;
    private final ApprovalChannelNotifierRegistry registry;
    private final MeterRegistry meterRegistry;

    public ApprovalDelegationEmitter(SignalWaitRepository signalWaitRepository,
                                     WorkflowRunRepository workflowRunRepository,
                                     WorkflowRepository workflowRepository,
                                     ApprovalChannelDeliveryRepository deliveryRepository,
                                     ApprovalChannelNotifierRegistry registry,
                                     MeterRegistry meterRegistry) {
        this.signalWaitRepository = signalWaitRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.workflowRepository = workflowRepository;
        this.deliveryRepository = deliveryRepository;
        this.registry = registry;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Push the pending approval to its configured channel. No-op for the common
     * non-delegated approval (no {@code delegation} block in signal_config).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("approvalDelegationExecutor")
    public void onApprovalPending(WorkflowApprovalPendingEvent event) {
        try {
            Optional<SignalWaitEntity> signalOpt = signalWaitRepository.findById(event.signalWaitId());
            if (signalOpt.isEmpty()) {
                return;
            }
            SignalWaitEntity signal = signalOpt.get();
            ApprovalDelegationConfig config = ApprovalDelegationConfig.fromSignalConfig(signal.getSignalConfig());
            if (config == null) {
                return;
            }
            Optional<ApprovalChannelNotifier> notifier = registry.forChannel(config.channel());
            if (notifier.isEmpty()) {
                // Validation flags unknown channels at build time; reaching here means a
                // hand-crafted plan or a channel removed from the deployment.
                meterRegistry.counter("approval.delegation.errors", "type", "UnknownChannel").increment();
                logger.warn("[approval-delegation] no notifier for channel '{}' (signal {})",
                        config.channel(), signal.getId());
                return;
            }
            Optional<WorkflowRunEntity> runOpt = workflowRunRepository.findByRunIdPublic(signal.getRunId());
            if (runOpt.isEmpty() || runOpt.get().getTenantId() == null) {
                meterRegistry.counter("approval.delegation.errors", "type", "RunNotFound").increment();
                return;
            }
            WorkflowRunEntity run = runOpt.get();
            notifier.get().notifyPending(signal, config, run, resolveWorkflowName(run));
        } catch (Exception ex) {
            meterRegistry.counter("approval.delegation.errors",
                    "type", ex.getClass().getSimpleName()).increment();
            logger.warn("[approval-delegation] swallowed pending-dispatch for signal {}: {}",
                    event.signalWaitId(), ex.getMessage());
        }
    }

    /**
     * Resolve the run's workflow display name WITHOUT navigating the run's lazy
     * workflow association: this listener runs async with no open session, so the
     * run entity is detached and initializing the proxy would throw
     * LazyInitializationException (and get silently swallowed, killing the message
     * fallback text). Reading the proxy's id is safe (no initialization); the name
     * comes from a fresh repository read. Null on any failure: the notifier then
     * falls back to a generic label.
     */
    private String resolveWorkflowName(WorkflowRunEntity run) {
        try {
            if (run.getWorkflow() == null) {
                return null;
            }
            java.util.UUID workflowId = run.getWorkflow().getId();
            if (workflowId == null) {
                return null;
            }
            return workflowRepository.findById(workflowId).map(WorkflowEntity::getName).orElse(null);
        } catch (RuntimeException ex) {
            logger.debug("[approval-delegation] workflow name resolution failed: {}", ex.getMessage());
            return null;
        }
    }

    /**
     * Reflect any USER_APPROVAL resolution (channel click, in-app, MCP, timeout) on
     * the channel message: single edit path regardless of who decided.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("approvalDelegationExecutor")
    public void onSignalResolved(SignalResolvedEvent event) {
        try {
            SignalWaitEntity signal = event.getResolvedSignal();
            if (signal == null || signal.getSignalType() != SignalType.USER_APPROVAL) {
                return;
            }
            List<ApprovalChannelDeliveryEntity> deliveries =
                    deliveryRepository.findBySignalWaitIdInAndStatus(List.of(signal.getId()), DeliveryStatus.SENT);
            for (ApprovalChannelDeliveryEntity delivery : deliveries) {
                registry.forChannel(delivery.getChannel()).ifPresent(n ->
                        n.onResolved(delivery, signal.getResolution(), signal.getResolvedBy()));
            }
        } catch (Exception ex) {
            meterRegistry.counter("approval.delegation.errors",
                    "type", ex.getClass().getSimpleName()).increment();
            logger.warn("[approval-delegation] swallowed resolved-dispatch: {}", ex.getMessage());
        }
    }

    /**
     * Reflect bulk cancellations (run cancel, epoch close, trigger reset) on the
     * channel messages. These paths bypass {@link SignalResolvedEvent}.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("approvalDelegationExecutor")
    public void onSignalsCancelled(SignalsCancelledEvent event) {
        try {
            if (event.userApprovalSignalIds().isEmpty()) {
                return;
            }
            List<ApprovalChannelDeliveryEntity> deliveries = deliveryRepository
                    .findBySignalWaitIdInAndStatus(event.userApprovalSignalIds(), DeliveryStatus.SENT);
            for (ApprovalChannelDeliveryEntity delivery : deliveries) {
                registry.forChannel(delivery.getChannel()).ifPresent(n -> n.onCancelled(delivery));
            }
        } catch (Exception ex) {
            meterRegistry.counter("approval.delegation.errors",
                    "type", ex.getClass().getSimpleName()).increment();
            logger.warn("[approval-delegation] swallowed cancelled-dispatch for run {}: {}",
                    event.runIdPublic(), ex.getMessage());
        }
    }
}
