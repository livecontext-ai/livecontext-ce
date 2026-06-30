package com.apimarketplace.common.storage.service.api;

import com.apimarketplace.common.storage.dto.MappingResolutionResult;
import com.apimarketplace.common.storage.dto.MappingSpec;

import java.util.UUID;

/**
 * Interface definissant les operations de mapping (Interface Segregation Principle).
 */
public interface MappingOperations {

    /**
     * Recupere le MappingSpec depuis le catalog-service.
     */
    MappingSpec getMappingSpec(UUID toolId);

    /**
     * Resout le mapping pour un toolId et des donnees JSON.
     */
    MappingResolutionResult resolve(UUID toolId, String jsonData);

    /**
     * Verifie si le service de mapping est active.
     */
    boolean isEnabled();
}
