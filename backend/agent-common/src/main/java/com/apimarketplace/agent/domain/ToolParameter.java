package com.apimarketplace.agent.domain;

import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * Parameter definition for a tool.
 */
@Builder
public record ToolParameter(
    /**
     * Parameter name
     */
    String name,

    /**
     * Parameter type (string, number, boolean, array, object)
     */
    String type,

    /**
     * Description of the parameter
     */
    String description,

    /**
     * Whether the parameter is required
     */
    boolean required,

    /**
     * Default value if not provided
     */
    Object defaultValue,

    /**
     * Enum values if the parameter is an enum
     */
    List<String> enumValues,

    /**
     * Nested properties for object types
     */
    Map<String, ToolParameter> properties,
    
    // ===== VALIDATION CONSTRAINTS =====
    
    /**
     * Minimum length for string parameters
     */
    Integer minLength,
    
    /**
     * Maximum length for string parameters
     */
    Integer maxLength,
    
    /**
     * Minimum value for number parameters
     */
    Double minimum,
    
    /**
     * Maximum value for number parameters
     */
    Double maximum,
    
    /**
     * Pattern (regex) for string validation
     */
    String pattern
) {}
