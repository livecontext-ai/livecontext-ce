package com.apimarketplace.orchestrator.services.streaming.events;

/**
 * Cycle de vie standardisé pour les steps côté streaming.
 * Permet au processeur d'appliquer des transitions déterministes.
 */
public enum StepLifecycle {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILURE,
    SKIPPED,
    RETRYING,
    CANCELLED,
    AWAITING_SIGNAL
}
