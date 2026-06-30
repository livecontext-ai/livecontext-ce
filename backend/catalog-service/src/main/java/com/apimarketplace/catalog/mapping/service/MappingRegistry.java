package com.apimarketplace.catalog.mapping.service;

import com.apimarketplace.catalog.mapping.dsl.MappingSpec;
import com.apimarketplace.catalog.mapping.dsl.SourceSpec;
import com.apimarketplace.catalog.mapping.entity.MappingDefinitionEntity;
import com.apimarketplace.catalog.mapping.entity.MappingVersionEntity;
import com.apimarketplace.catalog.domain.ToolNameEntity;
import com.apimarketplace.catalog.mapping.repository.MappingDefinitionRepository;
import com.apimarketplace.catalog.mapping.repository.MappingVersionRepository;
import com.apimarketplace.catalog.repository.ToolNameRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Service for managing mapping definitions and versions
 */
@Service
@Transactional
public class MappingRegistry {
    
    private static final Logger logger = LoggerFactory.getLogger(MappingRegistry.class);
    
    @Autowired
    private MappingDefinitionRepository mappingDefinitionRepository;
    
    @Autowired
    private MappingVersionRepository mappingVersionRepository;
    
    @Autowired
    private ToolNameRepository toolNameRepository;
    
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    
    
    
    /**
     * Find mapping definitions by tool ID
     */
    public List<MappingDefinitionEntity> findByToolId(java.util.UUID toolId) {
        logger.debug("Finding mapping definitions for tool ID {}", toolId);
        return mappingDefinitionRepository.findByToolId(toolId);
    }
    
    /**
     * Get mapping definitions by tool ID (alias for findByToolId)
     */
    public List<MappingDefinitionEntity> getMappingDefinitionsByToolId(java.util.UUID toolId) {
        return findByToolId(toolId);
    }
    
    /**
     * Find latest mapping definition by tool ID
     */
    public Optional<MappingDefinitionEntity> findLatestByToolId(java.util.UUID toolId) {
        logger.debug("Finding latest mapping definition for tool ID {}", toolId);
        return mappingDefinitionRepository.findLatestByToolId(toolId);
    }
    
    /**
     * Check if mapping definition exists by tool ID
     */
    public boolean existsByToolId(java.util.UUID toolId) {
        logger.debug("Checking if mapping definition exists for tool ID {}", toolId);
        return mappingDefinitionRepository.existsByToolId(toolId);
    }


    /**
     * Save mapping definition
     */
    public MappingDefinitionEntity save(MappingDefinitionEntity mappingDefinition) {
        logger.debug("Saving mapping definition for tool {}", mappingDefinition.getToolId());
        return mappingDefinitionRepository.save(mappingDefinition);
    }
    
    /**
     * Create a new mapping definition with tool ID
     */
    public MappingDefinitionEntity createMappingDefinitionWithToolId(java.util.UUID toolId, String displayName, String createdBy) {
        logger.debug("Creating mapping definition for tool ID {}", toolId);
        
        MappingDefinitionEntity mappingDefinition = new MappingDefinitionEntity(toolId, displayName, createdBy);
        return mappingDefinitionRepository.save(mappingDefinition);
    }

    /**
     * Find latest mapping version by tool ID
     */
    public Optional<MappingVersionEntity> findLatestMappingVersionByToolId(java.util.UUID toolId) {
        logger.debug("Finding latest mapping version for tool ID {}", toolId);
        
        // Direct query to get the latest mapping version by tool ID
        return mappingVersionRepository.findLatestByToolId(toolId);
    }
    
    /**
     * Save a new mapping version
     */
    public MappingVersionEntity saveMappingVersion(Long mappingDefinitionId, MappingSpec mappingSpec, String createdBy) {
        logger.debug("Saving new mapping version for mapping definition {}", mappingDefinitionId);
        
        // Generate version number
        String version = generateVersionNumber(mappingDefinitionId);
        
        // Clean the mapping spec before saving to avoid storing unnecessary fields
        // Create new mapping version
        MappingVersionEntity mappingVersion = new MappingVersionEntity(
                mappingDefinitionId,
                version,
                null, // Will be set by setParsedSpec
                createdBy
        );
        
        mappingVersion.setParsedSpec(mappingSpec);
        
        // Set all other versions as not latest
        mappingVersionRepository.setAllVersionsAsNotLatest(mappingDefinitionId);
        
        // Set this version as latest
        mappingVersion.setIsLatest(true);
        
        mappingVersion = mappingVersionRepository.save(mappingVersion);
        logger.info("Saved new mapping version: {} for mapping definition {}", mappingVersion.getId(), mappingDefinitionId);
        
        return mappingVersion;
    }
    
    /**
     * Nettoie le MappingSpec avant de le sauvegarder en base de donnees
     * pour eviter de stocker les champs inutiles
     */
    private MappingSpec cleanMappingSpecForStorage(MappingSpec spec) {
        if (spec == null) return null;
        
        MappingSpec cleaned = new MappingSpec();
        
        // Copier la source
        if (spec.getSource() != null) {
            SourceSpec source = new SourceSpec();
            source.setFormat(spec.getSource().getFormat());
            source.setItemsPath(spec.getSource().getItemsPath());
            source.setRoot(spec.getSource().getRoot());
            source.setRootAlternatives(spec.getSource().getRootAlternatives());
            cleaned.setSource(source);
        }
        
        // Copier les champs en supprimant les champs inutiles
        if (spec.getFields() != null) {
            Map<String, com.apimarketplace.catalog.mapping.dsl.FieldSpec> cleanedFields = new HashMap<>();
            for (Map.Entry<String, com.apimarketplace.catalog.mapping.dsl.FieldSpec> entry : spec.getFields().entrySet()) {
                String fieldName = entry.getKey();
                com.apimarketplace.catalog.mapping.dsl.FieldSpec fieldSpec = entry.getValue();
                
                com.apimarketplace.catalog.mapping.dsl.FieldSpec cleanedField = new com.apimarketplace.catalog.mapping.dsl.FieldSpec();
                cleanedField.setCandidates(fieldSpec.getCandidates());
                cleanedField.setTo(fieldSpec.getTo());
                cleanedField.setRequired(fieldSpec.getRequired());
                cleanedField.setDefaultValue(fieldSpec.getDefaultValue());
                // Ne pas copier les champs map, path_anyOf, max_fallbacks qui peuvent causer des problemes
                
                cleanedFields.put(fieldName, cleanedField);
            }
            cleaned.setFields(cleanedFields);
        }
        
        return cleaned;
    }
    
    /**
     * Get required fields for a tool from database
     */
    public List<String> getRequiredFields(java.util.UUID toolId) {
        logger.debug("Getting required fields for tool ID {} - returning empty list as required fields are removed", toolId);
        // Return empty list since required_fields field has been removed
        return new ArrayList<>();
    }
    
    /**
     * Check if API tool exists by ID
     */
    public boolean apiToolExists(java.util.UUID toolId) {
        logger.info("=== MAPPING REGISTRY - API TOOL EXISTS CHECK ===");
        logger.info("Checking if API tool exists for ID: {} (regardless of active status)", toolId);
        logger.info("Tool ID type: {}, toString: {}", toolId.getClass().getSimpleName(), toolId);
        
        try {
            // Check if tool exists (regardless of active status)
            String sql = "SELECT COUNT(*) FROM api_tools WHERE id = ?";
            logger.info("Executing SQL: {} with parameter: {}", sql, toolId);
            
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, toolId);
            logger.info("SQL query result count: {}", count);
            
            // Also check active status for information
            String sqlActive = "SELECT COUNT(*) FROM api_tools WHERE id = ? AND is_active = true";
            Integer countActive = jdbcTemplate.queryForObject(sqlActive, Integer.class, toolId);
            logger.info("SQL query result count (active tools): {}", countActive);
            
            // Check if tool exists but is inactive
            String sqlInactive = "SELECT COUNT(*) FROM api_tools WHERE id = ? AND is_active = false";
            Integer countInactive = jdbcTemplate.queryForObject(sqlInactive, Integer.class, toolId);
            logger.info("SQL query result count (inactive tools): {}", countInactive);
            
            boolean exists = count != null && count > 0;
            logger.info("API tool exists result: {}", exists);
            
            // If tool doesn't exist, log some available tools for debugging
            if (!exists) {
                logger.warn("Tool not found! Listing some available tools for debugging:");
                try {
                    String listSql = "SELECT id, name, is_active FROM api_tools ORDER BY created_at DESC LIMIT 5";
                    List<Map<String, Object>> tools = jdbcTemplate.queryForList(listSql);
                    for (Map<String, Object> tool : tools) {
                        logger.warn("Available tool: ID={}, Name={}, Active={}", 
                            tool.get("id"), tool.get("name"), tool.get("is_active"));
                    }
                } catch (Exception e) {
                    logger.warn("Could not list available tools: {}", e.getMessage());
                }
            }
            
            return exists;
        } catch (Exception e) {
            logger.error("Error checking if API tool exists for id {}: {}", toolId, e.getMessage(), e);
            return false;
        }
    }
    
    
    /**
     * Generate version number
     */
    private String generateVersionNumber(Long mappingDefinitionId) {
        List<MappingVersionEntity> versions = mappingVersionRepository
                .findByMappingDefinitionIdOrderByCreatedAtDesc(mappingDefinitionId);
        
        if (versions.isEmpty()) {
            return "1.0";
        }
        
        // Get the latest version and increment
        String latestVersion = versions.get(0).getVersion();
        String[] parts = latestVersion.split("\\.");
        
        if (parts.length >= 2) {
            try {
                int major = Integer.parseInt(parts[0]);
                int minor = Integer.parseInt(parts[1]);
                minor++;
                return major + "." + minor;
            } catch (NumberFormatException e) {
                // If parsing fails, just increment the minor version
                return "1." + (versions.size() + 1);
            }
        }
        
        return "1." + (versions.size() + 1);
    }
    
    /**
     * Find or create "Other" category
     */
    private java.util.UUID findOrCreateOtherCategory() {
        logger.debug("Finding or creating 'Other' category");
        
        try {
            // Try to find existing "Other" category
            String sql = "SELECT id FROM tool_categories WHERE name = 'Other' AND is_active = true LIMIT 1";
            List<java.util.UUID> existing = jdbcTemplate.queryForList(sql, java.util.UUID.class);
            
            if (!existing.isEmpty()) {
                logger.debug("Found existing 'Other' category with ID: {}", existing.get(0));
                return existing.get(0);
            }
            
            // Create new "Other" category
            java.util.UUID categoryId = java.util.UUID.randomUUID();
            long currentTime = System.currentTimeMillis();
            
            String insertSql = "INSERT INTO tool_categories (id, name, description, icon, color, is_active, sort_order, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
            jdbcTemplate.update(insertSql,
                categoryId,
                "Other",
                "Default category for mapping tools and other uncategorized items",
                "📁",
                "#6B7280",
                true,
                999,
                currentTime,
                currentTime
            );
            
            logger.info("Created new 'Other' category with ID: {}", categoryId);
            return categoryId;
            
        } catch (Exception e) {
            logger.error("Error finding or creating 'Other' category: {}", e.getMessage(), e);
            // Fallback to a hardcoded UUID if something goes wrong
            return java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        }
    }

}
