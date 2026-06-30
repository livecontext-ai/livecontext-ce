package com.apimarketplace.orchestrator.repository;

import java.time.Instant;

/**
 * Projection for distinct (epoch, itemIndex) pairs with optional timing.
 * Used by InterfaceRenderService to avoid loading full entities just for pagination.
 */
public interface EpochItemProjection {

    Integer getEpoch();

    Integer getItemIndex();

    Integer getSpawn();

    Instant getMinStartTime();
}
