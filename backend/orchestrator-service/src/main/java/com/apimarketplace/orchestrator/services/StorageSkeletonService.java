package com.apimarketplace.orchestrator.services;

import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.orchestrator.domain.StorageNestedModels.StorageColumnDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for navigating in storage skeleton and determining columns
 * Uses the structure skeleton to determine available columns at a given JSON path
 */
@Service
@Transactional
public class StorageSkeletonService {
    
    private static final Logger logger = LoggerFactory.getLogger(StorageSkeletonService.class);

    private final StorageRepository storageRepository;
    private final ObjectMapper objectMapper;

    public StorageSkeletonService(StorageRepository storageRepository,
                                   ObjectMapper objectMapper) {
        this.storageRepository = storageRepository;
        this.objectMapper = objectMapper;
    }
    
    /**
     * Récupère les colonnes disponibles à un chemin donné
     * Utilise le squelette pour déterminer la structure
     */
    public List<StorageColumnDefinition> getColumnDefinitions(
            UUID storageId,
            String tenantId,
            String jsonPath) {
        
        StorageEntity storage = storageRepository.findByIdAndTenantId(storageId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Storage not found: " + storageId));
        
        String skeletonStr = storage.getStructureSkeleton();
        if (skeletonStr == null || skeletonStr.trim().isEmpty()) {
            // Si pas de squelette, inférer à partir des données
            logger.warn("Pas de squelette disponible pour storage {}, inférence depuis les données", storageId);
            return inferColumnsFromData(storage, jsonPath);
        }
        
        try {
            JsonNode skeleton = objectMapper.readTree(skeletonStr);
            
            // Naviguer dans le squelette jusqu'au chemin demandé
            JsonNode targetNode = navigateSkeleton(skeleton, jsonPath);
            
            if (targetNode == null) {
                logger.warn("Chemin {} non trouvé dans le squelette", jsonPath);
                return new ArrayList<>();
            }
            
            // Extraire les colonnes du nœud cible
            List<StorageColumnDefinition> columns = extractColumnsFromSkeleton(targetNode);
            
            // Analyser la fréquence de chaque colonne dans les données réelles
            Map<String, Integer> columnFrequency = analyzeColumnFrequency(storage, jsonPath, columns);
            
            // Trier les colonnes par fréquence décroissante
            columns.sort((a, b) -> {
                int freqA = columnFrequency.getOrDefault(a.field(), 0);
                int freqB = columnFrequency.getOrDefault(b.field(), 0);
                return Integer.compare(freqB, freqA); // Décroissant
            });
            
            // Ajouter la fréquence dans la définition
            int totalItems = getTotalItemsCount(storage);
            return columns.stream()
                .map(col -> new StorageColumnDefinition(
                    col.colId(),
                    col.headerName(),
                    col.field(),
                    col.type(),
                    col.editable(),
                    col.sortable(),
                    col.filterable(),
                    col.isNavigable(),
                    col.width(),
                    col.flex(),
                    columnFrequency.getOrDefault(col.field(), 0),
                    totalItems
                ))
                .toList();
            
        } catch (Exception e) {
            logger.error("Erreur lors de la récupération des colonnes: {}", e.getMessage(), e);
            return inferColumnsFromData(storage, jsonPath);
        }
    }

    // ========================================================================
    // OPTIMIZED LAZY LOADING METHODS (use lightweight queries)
    // ========================================================================

    /**
     * Retrieves only the structure skeleton without loading full payload.
     * This is the primary method for frontend tree view initialization.
     *
     * @param storageId Storage UUID
     * @param tenantId Tenant ID for security
     * @return Skeleton as JsonNode, or empty if not found
     */
    @Transactional(readOnly = true)
    public Optional<JsonNode> getSkeletonOnly(UUID storageId, String tenantId) {
        logger.debug("Getting skeleton only for storage: {}", storageId);

        String skeletonStr = storageRepository.getSkeletonOnly(storageId, tenantId);
        if (skeletonStr == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readTree(skeletonStr));
        } catch (Exception e) {
            logger.warn("Failed to parse skeleton JSON: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Retrieves a value at a specific JSON path without loading full payload.
     * Used for lazy loading individual values when user expands a tree node.
     *
     * @param storageId Storage UUID
     * @param tenantId Tenant ID for security
     * @param path Dot-separated path (e.g., "output.users.0.name")
     * @return Value at path as String, or empty if not found
     */
    @Transactional(readOnly = true)
    public Optional<String> getValueAtPath(UUID storageId, String tenantId, String path) {
        logger.debug("Getting value at path '{}' for storage: {}", path, storageId);

        String[] pathArray = parsePath(path);
        String value = storageRepository.extractJsonPath(storageId, tenantId, pathArray);
        return Optional.ofNullable(value);
    }

    /**
     * Retrieves a JSON object at a specific path without loading full payload.
     * Used for lazy loading sub-objects or array items.
     *
     * @param storageId Storage UUID
     * @param tenantId Tenant ID for security
     * @param path Dot-separated path (e.g., "output.users.0")
     * @return JSON object at path, or empty if not found
     */
    @Transactional(readOnly = true)
    public Optional<JsonNode> getObjectAtPath(UUID storageId, String tenantId, String path) {
        logger.debug("Getting object at path '{}' for storage: {}", path, storageId);

        String[] pathArray = parsePath(path);
        String jsonStr = storageRepository.extractJsonObject(storageId, tenantId, pathArray);
        if (jsonStr == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readTree(jsonStr));
        } catch (Exception e) {
            logger.warn("Failed to parse JSON object at path '{}': {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Gets an array item at a specific index within a path.
     * Optimized for navigation between items (prev/next).
     *
     * @param storageId Storage UUID
     * @param tenantId Tenant ID
     * @param arrayPath Path to the array (e.g., "output.users")
     * @param index Index of the item to retrieve
     * @return The item at the specified index
     */
    @Transactional(readOnly = true)
    public Optional<JsonNode> getArrayItemAtIndex(UUID storageId, String tenantId, String arrayPath, int index) {
        String itemPath = arrayPath.isEmpty() ? String.valueOf(index) : arrayPath + "." + index;
        return getObjectAtPath(storageId, tenantId, itemPath);
    }

    /**
     * Gets the count of items in an array at a specific path.
     * Used for pagination and navigation UI.
     *
     * @param storageId Storage UUID
     * @param tenantId Tenant ID
     * @param arrayPath Path to the array
     * @return Number of items, or 0 if not an array
     */
    @Transactional(readOnly = true)
    public int getArrayLength(UUID storageId, String tenantId, String arrayPath) {
        // Use jsonb_array_length via native query for efficiency
        try {
            String[] pathArray = parsePath(arrayPath);
            String lengthStr = storageRepository.extractJsonPath(storageId, tenantId,
                    appendToPath(pathArray, "#"));
            if (lengthStr != null) {
                return Integer.parseInt(lengthStr);
            }
        } catch (Exception e) {
            logger.debug("Could not get array length at '{}': {}", arrayPath, e.getMessage());
        }

        // Fallback: get the array and check size
        return getObjectAtPath(storageId, tenantId, arrayPath)
                .map(node -> node.isArray() ? node.size() : 0)
                .orElse(0);
    }

    /**
     * Parses a dot-separated path into array of keys.
     * Handles array indices (e.g., "users.0.name" -> ["users", "0", "name"])
     */
    private String[] parsePath(String path) {
        if (path == null || path.isEmpty()) {
            return new String[0];
        }
        return path.split("\\.");
    }

    /**
     * Appends a key to an existing path array.
     */
    private String[] appendToPath(String[] path, String key) {
        String[] newPath = new String[path.length + 1];
        System.arraycopy(path, 0, newPath, 0, path.length);
        newPath[path.length] = key;
        return newPath;
    }

    // ========================================================================
    // SKELETON NAVIGATION METHODS
    // ========================================================================

    /**
     * Navigue dans le squelette jusqu'au chemin demandé
     */
    private JsonNode navigateSkeleton(JsonNode skeleton, String jsonPath) {
        if (jsonPath == null || jsonPath.isEmpty()) {
            return skeleton;
        }
        
        String[] parts = jsonPath.split("\\.");
        JsonNode current = skeleton;
        
        for (String part : parts) {
            if (current == null) {
                return null;
            }
            
            // Gérer la navigation dans le squelette
            if (current.has("_t")) {
                String type = current.get("_t").asText();
                
                if ("arr".equals(type) && current.has("items")) {
                    // Si on est dans un tableau, descendre dans items
                    current = current.get("items");
                }
                
                if (current.has("props")) {
                    current = current.get("props").get(part);
                } else {
                    return null;
                }
            } else {
                return null;
            }
        }
        
        return current;
    }
    
    /**
     * Extrait les colonnes depuis un nœud de squelette
     */
    private List<StorageColumnDefinition> extractColumnsFromSkeleton(JsonNode targetNode) {
        List<StorageColumnDefinition> columns = new ArrayList<>();
        
        if (targetNode == null) {
            return columns;
        }
        
        if (targetNode.has("_t")) {
            String type = targetNode.get("_t").asText();
            
            if ("obj".equals(type) && targetNode.has("props")) {
                // Objet : les colonnes sont les propriétés
                JsonNode props = targetNode.get("props");
                props.fieldNames().forEachRemaining(key -> {
                    JsonNode child = props.get(key);
                    String childType = getChildType(child);
                    boolean hasChildren = "obj".equals(childType) || "arr".equals(childType);
                    
                    columns.add(new StorageColumnDefinition(
                        key,
                        key,
                        key,
                        childType != null ? childType : "string",
                        true,
                        true,
                        true,
                        hasChildren,
                        null,
                        null,
                        null,
                        null
                    ));
                });
            } else if ("arr".equals(type) && targetNode.has("items")) {
                // Tableau : les colonnes sont les propriétés des items
                JsonNode items = targetNode.get("items");
                if (items.has("props")) {
                    JsonNode props = items.get("props");
                    props.fieldNames().forEachRemaining(key -> {
                        JsonNode child = props.get(key);
                        String childType = getChildType(child);
                        boolean hasChildren = "obj".equals(childType) || "arr".equals(childType);
                        
                        columns.add(new StorageColumnDefinition(
                            key,
                            key,
                            key,
                            childType != null ? childType : "string",
                            true,
                            true,
                            true,
                            hasChildren,
                            null,
                            null,
                            null,
                            null
                        ));
                    });
                }
            }
        }
        
        return columns;
    }
    
    /**
     * Obtient le type d'un nœud enfant
     */
    private String getChildType(JsonNode child) {
        if (child.has("_t")) {
            return child.get("_t").asText();
        }
        if (child.isTextual()) {
            return child.asText();
        }
        return "string";
    }
    
    /**
     * Analyse la fréquence de chaque colonne dans les données réelles
     */
    private Map<String, Integer> analyzeColumnFrequency(
            StorageEntity storage, 
            String jsonPath, 
            List<StorageColumnDefinition> columns) {
        
        Map<String, Integer> frequency = new HashMap<>();
        
        try {
            String dataJson = storage.getData();
            if (dataJson == null || dataJson.trim().isEmpty()) {
                return frequency;
            }
            
            JsonNode root = objectMapper.readTree(dataJson);
            
            // Si c'est un tableau, analyser les items
            if (root.isArray()) {
                int sampleSize = Math.min(root.size(), 100); // Analyser jusqu'à 100 items
                
                for (int i = 0; i < sampleSize; i++) {
                    JsonNode item = root.get(i);
                    JsonNode nestedValue = evaluatePath(item, jsonPath);
                    
                    if (nestedValue != null && nestedValue.isObject()) {
                        nestedValue.fieldNames().forEachRemaining(key -> {
                            frequency.put(key, frequency.getOrDefault(key, 0) + 1);
                        });
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Erreur lors de l'analyse de fréquence: {}", e.getMessage());
        }
        
        return frequency;
    }
    
    /**
     * Obtient le nombre total d'items dans le storage
     */
    private int getTotalItemsCount(StorageEntity storage) {
        try {
            String dataJson = storage.getData();
            if (dataJson == null || dataJson.trim().isEmpty()) {
                return 0;
            }
            
            JsonNode root = objectMapper.readTree(dataJson);
            if (root.isArray()) {
                return root.size();
            }
            return 1;
        } catch (Exception e) {
            logger.warn("Erreur lors du comptage des items: {}", e.getMessage());
            return 0;
        }
    }
    
    /**
     * Évalue un chemin JSON sur un nœud
     */
    private JsonNode evaluatePath(JsonNode node, String jsonPath) {
        if (jsonPath == null || jsonPath.isEmpty()) {
            return node;
        }
        
        String[] parts = jsonPath.split("\\.");
        JsonNode current = node;
        
        for (String part : parts) {
            if (current == null || !current.isObject()) {
                return null;
            }
            current = current.get(part);
        }
        
        return current;
    }
    
    /**
     * Infère les colonnes à partir des données si pas de squelette
     */
    private List<StorageColumnDefinition> inferColumnsFromData(
            StorageEntity storage, 
            String jsonPath) {
        
        List<StorageColumnDefinition> columns = new ArrayList<>();
        
        try {
            String dataJson = storage.getData();
            if (dataJson == null || dataJson.trim().isEmpty()) {
                return columns;
            }
            
            JsonNode root = objectMapper.readTree(dataJson);
            JsonNode targetNode = evaluatePath(root, jsonPath);
            
            if (targetNode == null) {
                return columns;
            }
            
            // Si c'est un tableau, analyser le premier item
            if (targetNode.isArray() && targetNode.size() > 0) {
                JsonNode firstItem = targetNode.get(0);
                if (firstItem.isObject()) {
                    firstItem.fieldNames().forEachRemaining(key -> {
                        JsonNode value = firstItem.get(key);
                        String type = value.getNodeType().toString().toLowerCase();
                        boolean hasChildren = value.isObject() || value.isArray();
                        
                        columns.add(new StorageColumnDefinition(
                            key,
                            key,
                            key,
                            type,
                            true,
                            true,
                            true,
                            hasChildren,
                            null,
                            null,
                            null,
                            null
                        ));
                    });
                }
            } else if (targetNode.isObject()) {
                // Si c'est un objet, extraire les propriétés
                targetNode.fieldNames().forEachRemaining(key -> {
                    JsonNode value = targetNode.get(key);
                    String type = value.getNodeType().toString().toLowerCase();
                    boolean hasChildren = value.isObject() || value.isArray();
                    
                    columns.add(new StorageColumnDefinition(
                        key,
                        key,
                        key,
                        type,
                        true,
                        true,
                        true,
                        hasChildren,
                        null,
                        null,
                        null,
                        null
                    ));
                });
            }
        } catch (Exception e) {
            logger.error("Erreur lors de l'inférence des colonnes: {}", e.getMessage(), e);
        }
        
        return columns;
    }
}

