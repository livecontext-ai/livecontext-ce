package com.apimarketplace.orchestrator.services.streaming.events;

/**
 * Types d'événements émis pour suivre la vie d'un merge node.
 */
public enum MergeEventType {
    ENQUEUED,
    MERGED,
    SKIPPED
}
