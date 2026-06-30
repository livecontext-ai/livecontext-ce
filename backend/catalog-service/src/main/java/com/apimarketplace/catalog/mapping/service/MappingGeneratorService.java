package com.apimarketplace.catalog.mapping.service;

import com.apimarketplace.catalog.mapping.generator.MappingGenerationException;
import com.apimarketplace.catalog.mapping.generator.StrictMappingConstraints;
import com.apimarketplace.catalog.mapping.generator.StrictMappingGenerator;
import com.apimarketplace.catalog.mapping.validator.MappingValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Service for generating strict JSONPath mappings.
 *
 * This service orchestrates the mapping generation process, including
 * caching, validation, and error handling.
 */
@Service
@ConditionalOnProperty(name = "ai.mapping.enabled", havingValue = "true")
public class MappingGeneratorService {
    
    private static final Logger logger = LoggerFactory.getLogger(MappingGeneratorService.class);
    
    private final StrictMappingGenerator mappingGenerator;
    
    @Autowired
    public MappingGeneratorService(StrictMappingGenerator mappingGenerator) {
        this.mappingGenerator = mappingGenerator;
    }
    
    /**
     * Generates a strict mapping specification from sample JSON data.
     * Results are cached based on the sample JSON and constraints.
     * 
     * @param sampleJson The sample JSON data to analyze
     * @param constraints The constraints for mapping generation
     * @return A JSON string representing the strict mapping specification
     * @throws MappingGenerationException if generation fails
     */
    @Cacheable(value = "mapping-cache", key = "#sampleJson.hashCode() + '_' + #constraints.hashCode()")
    public String generateStrictMapping(String sampleJson, StrictMappingConstraints constraints) 
            throws MappingGenerationException {
        
        try {
            logger.info("Generating strict mapping for sample JSON ({} chars) with constraints: {}", 
                       sampleJson.length(), constraints);
            
            // Check if generator is available
            if (!mappingGenerator.isAvailable()) {
                throw new MappingGenerationException("Mapping generator is not available");
            }
            
            // Generate mapping using AI
            String generatedMapping = mappingGenerator.generateStrictMapping(sampleJson, constraints);
            
            // Return AI response directly without validation
            logger.info("Successfully generated mapping from AI");
            return generatedMapping;
            
        } catch (Exception e) {
            logger.error("Failed to generate strict mapping: {}", e.getMessage(), e);
            throw new MappingGenerationException("Failed to generate strict mapping", e);
        }
    }
    
    /**
     * Generates a mapping with default constraints.
     * 
     * @param sampleJson The sample JSON data to analyze
     * @return A JSON string representing the strict mapping specification
     * @throws MappingGenerationException if generation fails
     */
    public String generateStrictMapping(String sampleJson) throws MappingGenerationException {
        StrictMappingConstraints constraints = new StrictMappingConstraints();
        return generateStrictMapping(sampleJson, constraints);
    }
    
    /**
     * Generates a mapping with a specific items path.
     * 
     * @param sampleJson The sample JSON data to analyze
     * @param itemsPath The items path to use
     * @return A JSON string representing the strict mapping specification
     * @throws MappingGenerationException if generation fails
     */
    public String generateStrictMapping(String sampleJson, String itemsPath) throws MappingGenerationException {
        StrictMappingConstraints constraints = new StrictMappingConstraints(itemsPath);
        return generateStrictMapping(sampleJson, constraints);
    }
    
    /**
     * Generates a mapping with tool context data.
     * 
     * @param sampleJson The sample JSON data to analyze
     * @param constraints The constraints for mapping generation
     * @param toolName The tool name
     * @param toolCategoryName The tool category name
     * @param toolSubCategoryName The tool subcategory name
     * @param httpMethod The HTTP method
     * @param endpoint The endpoint
     * @param toolDescription The tool description
     * @return A JSON string representing the strict mapping specification
     * @throws MappingGenerationException if generation fails
     */
    public String generateStrictMappingWithContext(String sampleJson, 
                                                  StrictMappingConstraints constraints,
                                                  String toolName,
                                                  String toolCategoryName,
                                                  String toolSubCategoryName,
                                                  String httpMethod,
                                                  String endpoint,
                                                  String toolDescription) throws MappingGenerationException {
        
        try {
            logger.info("Generating strict mapping with tool context: tool={}, category={}, method={}, endpoint={}", 
                       toolName, toolCategoryName, httpMethod, endpoint);
            
            // Check if generator is available
            if (!mappingGenerator.isAvailable()) {
                throw new MappingGenerationException("Mapping generator is not available");
            }
            
            // Generate mapping using AI with tool context
            String generatedMapping = mappingGenerator.generateStrictMappingWithContext(
                sampleJson, 
                constraints, 
                toolName, 
                toolCategoryName, 
                toolSubCategoryName, 
                httpMethod, 
                endpoint, 
                toolDescription
            );
            
            logger.info("Successfully generated mapping with tool context from AI");
            return generatedMapping;
            
        } catch (Exception e) {
            logger.error("Failed to generate strict mapping with context: {}", e.getMessage(), e);
            throw new MappingGenerationException("Failed to generate strict mapping with context", e);
        }
    }
    
    /**
     * Checks if the mapping generator is available.
     * 
     * @return true if the generator is available, false otherwise
     */
    public boolean isGeneratorAvailable() {
        return mappingGenerator.isAvailable();
    }
    
    /**
     * Generates a cache key for the given parameters.
     */
    private String generateCacheKey(String sampleJson, StrictMappingConstraints constraints) {
        try {
            String input = sampleJson + "|" + constraints.toString();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            logger.warn("Failed to generate cache key, using fallback");
            return String.valueOf((sampleJson + constraints).hashCode());
        }
    }
}
