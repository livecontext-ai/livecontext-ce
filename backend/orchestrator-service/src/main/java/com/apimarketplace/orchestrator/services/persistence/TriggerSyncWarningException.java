package com.apimarketplace.orchestrator.services.persistence;

/**
 * Signals that the trigger sync completed with security-relevant warnings that the user
 * must see in the API response. Carries a human-readable message describing what failed
 * to take effect on the public-facing trigger surface.
 *
 * <p>Used by {@link PinAwareTriggerSyncService} to fail-loud (not fail-closed) on
 * transient webhook config push errors - auth/method changes that silently fail leave
 * a public endpoint on a stale auth_type, which the user must be told about.
 */
public class TriggerSyncWarningException extends RuntimeException {
    public TriggerSyncWarningException(String message) {
        super(message);
    }
}
