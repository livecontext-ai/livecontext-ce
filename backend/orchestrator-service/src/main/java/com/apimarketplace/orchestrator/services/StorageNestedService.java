package com.apimarketplace.orchestrator.services;

import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.orchestrator.domain.StorageNestedModels.*;
import com.apimarketplace.orchestrator.persistence.StorageNestedRepositories;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.UUID;

/**
 * Service layer for nested JSON navigation in Storage
 * Supports hierarchical navigation through JSON structures stored in storage
 * Uses PostgreSQL JSONB operators for optimized extraction at SQL level
 */
@Service
@Transactional
public class StorageNestedService {
    
    private static final Logger logger = LoggerFactory.getLogger(StorageNestedService.class);
    
    private final StorageRepository storageRepository;
    private final StorageNestedRepositories nestedRepositories;
    
    public StorageNestedService(
            StorageRepository storageRepository,
            StorageNestedRepositories nestedRepositories) {
        this.storageRepository = storageRepository;
        this.nestedRepositories = nestedRepositories;
    }
    
    /**
     * Get nested data at a specific JSON path with pagination
     * Uses PostgreSQL JSONB operators for optimized extraction at SQL level
     * 
     * @param storageId ID of the storage
     * @param tenantId Tenant ID for isolation
     * @param jsonPath JSON path to extract (e.g., "user.address")
     * @param request Pagination request
     * @return Paginated response with nested data
     */
    public NestedPaginationResponse<StorageNestedRow> getNestedData(
            UUID storageId,
            String tenantId,
            String jsonPath,
            NestedDataRequest request) {
        
        // Validation
        if (storageId == null || tenantId == null || jsonPath == null) {
            throw new IllegalArgumentException("storageId, tenantId, and jsonPath cannot be null");
        }
        
        // Normaliser le chemin JSON
        String normalizedPath = normalizeJsonPath(jsonPath);
        
        // Vérifier que le storage existe
        if (!storageRepository.findByIdAndTenantId(storageId, tenantId).isPresent()) {
            throw new IllegalArgumentException("Storage not found: " + storageId);
        }
        
        try {
            // Extraire les données directement au niveau SQL avec JSONB
            List<StorageNestedRow> items = nestedRepositories.findNestedData(
                storageId,
                tenantId,
                normalizedPath,
                request.page(),
                request.limit(),
                request.sortBy(),
                request.sortOrder()
            );
            
            // Compter le total (pour la pagination)
            int totalItems = nestedRepositories.countNestedData(storageId, tenantId, normalizedPath);
            int totalPages = (int) Math.ceil((double) totalItems / request.limit());
            
            // Générer next cursor
            String nextCursor = null;
            boolean hasMore = (request.page() * request.limit()) < totalItems;
            if (hasMore && !items.isEmpty()) {
                StorageNestedRow lastItem = items.get(items.size() - 1);
                nextCursor = String.format("%d_%s", lastItem.id(), normalizedPath);
            }
            
            return new NestedPaginationResponse<>(
                items,
                totalItems,
                nextCursor,
                hasMore,
                totalPages,
                normalizedPath,
                calculateParentPath(normalizedPath),
                calculatePathSegments(normalizedPath)
            );
            
        } catch (Exception e) {
            logger.error("Erreur lors de l'extraction des données nested: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to extract nested data: " + e.getMessage(), e);
        }
    }
    
    
    /**
     * Normalize JSON path (remove spaces, validate format)
     */
    private String normalizeJsonPath(String jsonPath) {
        if (jsonPath == null || jsonPath.trim().isEmpty()) {
            return "";
        }
        
        // Enlever les espaces et normaliser
        String normalized = jsonPath.trim().replaceAll("\\s+", "");
        
        // Valider le format (pas de .., pas de caractères spéciaux dangereux)
        if (normalized.contains("..") || normalized.contains("/") || normalized.contains("\\")) {
            throw new IllegalArgumentException("Invalid JSON path format: " + jsonPath);
        }
        
        return normalized;
    }
    
    /**
     * Calculate parent path from current path
     * Example: "user.address" -> "user"
     */
    private String calculateParentPath(String jsonPath) {
        if (jsonPath == null || jsonPath.isEmpty()) {
            return null;
        }
        
        String[] parts = jsonPath.split("\\.");
        if (parts.length <= 1) {
            return null;
        }
        
        return String.join(".", Arrays.copyOf(parts, parts.length - 1));
    }
    
    /**
     * Calculate path segments for breadcrumb
     * Example: "user.address" -> ["user", "address"]
     */
    private List<String> calculatePathSegments(String jsonPath) {
        if (jsonPath == null || jsonPath.isEmpty()) {
            return new ArrayList<>();
        }
        
        return new ArrayList<>(Arrays.asList(jsonPath.split("\\.")));
    }
}

