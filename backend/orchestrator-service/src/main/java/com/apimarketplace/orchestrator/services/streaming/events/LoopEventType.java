package com.apimarketplace.orchestrator.services.streaming.events;

/**
 * Indique la nature de l'événement lié à une loop.
 */
public enum LoopEventType {
    STARTED,
    ITERATION_COMPLETED,
    COMPLETED,
    CANCELLED
}
