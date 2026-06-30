package com.apimarketplace.datasource.services;

import com.apimarketplace.datasource.domain.DataSourceNestedModels.*;
import com.apimarketplace.datasource.persistence.DataSourceNestedRepositories;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Service layer for nested JSON navigation in DataSource tables
 * Supports hierarchical navigation through JSON structures
 */
@Service
@Transactional
public class DataSourceNestedService {
    
    private final DataSourceNestedRepositories repositories;
    
    public DataSourceNestedService(DataSourceNestedRepositories repositories) {
        this.repositories = repositories;
    }
    
    /**
     * Get nested data at a specific JSON path with pagination
     * 
     * @param dataSourceId ID of the data source
     * @param tenantId Tenant ID for isolation
     * @param jsonPath JSON path to extract (e.g., "metadata.user.profile")
     * @param request Pagination request
     * @return Paginated response with nested data
     */
    public NestedPaginationResponse<DataSourceNestedRow> getNestedData(
            Long dataSourceId,
            String tenantId,
            String jsonPath,
            NestedDataRequest request) {
        
        // 🇫🇷 Validation
        if (dataSourceId == null || tenantId == null || jsonPath == null) {
            throw new IllegalArgumentException("dataSourceId, tenantId, and jsonPath cannot be null");
        }
        
        // 🇫🇷 Normaliser le chemin JSON (enlever les espaces, etc.)
        String normalizedPath = normalizeJsonPath(jsonPath);
        
        // 🇫🇷 Calculer le nombre total
        int totalItems = repositories.countNestedData(dataSourceId, tenantId, normalizedPath);
        
        // 🇫🇷 Calculer le nombre de pages
        int totalPages = (int) Math.ceil((double) totalItems / request.limit());
        
        // 🇫🇷 Récupérer les données avec pagination
        List<DataSourceNestedRow> items = repositories.findNestedData(
            dataSourceId,
            tenantId,
            normalizedPath,
            request.page(),
            request.limit(),
            request.sortBy(),
            request.sortOrder()
        );
        
        // 🇫🇷 Calculer le chemin parent
        String parentPath = calculateParentPath(normalizedPath);
        
        // 🇫🇷 Calculer les segments du chemin pour breadcrumb
        List<String> pathSegments = calculatePathSegments(normalizedPath);
        
        // 🇫🇷 Générer next cursor (simplifié pour l'instant)
        String nextCursor = null;
        boolean hasMore = (request.page() * request.limit()) < totalItems;
        if (hasMore && !items.isEmpty()) {
            DataSourceNestedRow lastItem = items.get(items.size() - 1);
            nextCursor = String.format("%d_%s", lastItem.id(), normalizedPath);
        }
        
        return new NestedPaginationResponse<>(
            items,
            totalItems,
            nextCursor,
            hasMore,
            totalPages,
            normalizedPath,
            parentPath,
            pathSegments
        );
    }
    
    /**
     * Get column definitions for nested data at a specific JSON path
     * 
     * @param dataSourceId ID of the data source
     * @param tenantId Tenant ID for isolation
     * @param jsonPath JSON path to analyze
     * @return List of column definitions
     */
    public List<NestedColumnDefinition> getNestedColumnDefinitions(
            Long dataSourceId,
            String tenantId,
            String jsonPath) {
        
        // 🇫🇷 Validation
        if (dataSourceId == null || tenantId == null || jsonPath == null) {
            throw new IllegalArgumentException("dataSourceId, tenantId, and jsonPath cannot be null");
        }
        
        // 🇫🇷 Normaliser le chemin JSON
        String normalizedPath = normalizeJsonPath(jsonPath);
        
        // 🇫🇷 Analyser la structure JSON pour déterminer les colonnes
        // Utiliser un échantillon de 100 items pour analyser la structure
        return repositories.getNestedColumnDefinitions(dataSourceId, tenantId, normalizedPath, 100);
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
     * Example: "metadata.user.profile" -> "metadata.user"
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
     * Example: "metadata.user.profile" -> ["metadata", "user", "profile"]
     */
    private List<String> calculatePathSegments(String jsonPath) {
        if (jsonPath == null || jsonPath.isEmpty()) {
            return new ArrayList<>();
        }
        
        return new ArrayList<>(Arrays.asList(jsonPath.split("\\.")));
    }
}

