package com.apimarketplace.orchestrator.execution.v2.services;

import com.apimarketplace.orchestrator.domain.execution.SignalWaitEntity;
import org.springframework.context.ApplicationEvent;

/**
 * Spring Application Event published when a signal is resolved.
 *
 * Published by UnifiedSignalService.resolveSignal() inside @Transactional.
 * Consumed by SignalResumeService.onSignalResolved() via
 * @TransactionalEventListener(AFTER_COMMIT) to ensure DB locks are released
 * before node execution begins.
 */
public class SignalResolvedEvent extends ApplicationEvent {

    private final SignalWaitEntity resolvedSignal;

    public SignalResolvedEvent(Object source, SignalWaitEntity resolvedSignal) {
        super(source);
        this.resolvedSignal = resolvedSignal;
    }

    public SignalWaitEntity getResolvedSignal() {
        return resolvedSignal;
    }
}
