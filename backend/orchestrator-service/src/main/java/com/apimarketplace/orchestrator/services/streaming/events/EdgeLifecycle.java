package com.apimarketplace.orchestrator.services.streaming.events;

/**
 * Etats possibles pour un edge dans le graphe d'exécution.
 */
public enum EdgeLifecycle {
    REGISTERED,
    RUNNING,
    COMPLETED,
    SKIPPED
}
