package com.apimarketplace.catalog.mapping.generator;

/**
 * Interface for generating strict JSONPath mappings from sample JSON data.
 * 
 * This interface defines the contract for generating mapping specifications
 * that can extract data from JSON responses using strict JSONPath expressions.
 * The generated mappings are deterministic and do not rely on runtime deductions.
 */
public interface StrictMappingGenerator {
    
    /**
     * Generates a strict mapping specification from sample JSON data.
     * 
     * @param sampleJson The sample JSON data to analyze
     * @param constraints The constraints for mapping generation
     * @return A JSON string representing the strict mapping specification
     * @throws MappingGenerationException if generation fails
     */
    String generateStrictMapping(String sampleJson, StrictMappingConstraints constraints) 
            throws MappingGenerationException;
    
    /**
     * Generates a strict mapping specification from sample JSON data with tool context.
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
    String generateStrictMappingWithContext(String sampleJson, 
                                          StrictMappingConstraints constraints,
                                          String toolName,
                                          String toolCategoryName,
                                          String toolSubCategoryName,
                                          String httpMethod,
                                          String endpoint,
                                          String toolDescription) throws MappingGenerationException;
    
    /**
     * Checks if this generator is available and ready to use.
     * 
     * @return true if the generator is available, false otherwise
     */
    boolean isAvailable();
}

