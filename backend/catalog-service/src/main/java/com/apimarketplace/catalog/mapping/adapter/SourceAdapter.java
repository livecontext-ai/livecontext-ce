package com.apimarketplace.catalog.mapping.adapter;

import com.apimarketplace.catalog.mapping.dsl.SourceSpec;
import java.util.Map;

/**
 * Interface for source adapters that handle different data formats
 */
public interface SourceAdapter {
    
    /**
     * Check if the source contains a collection of items
     * @param sourceSpec Source specification
     * @param input Raw input bytes
     * @return true if the source is a collection
     */
    boolean isCollection(SourceSpec sourceSpec, byte[] input);
    
    /**
     * Iterate over items in a collection
     * @param sourceSpec Source specification
     * @param input Raw input bytes
     * @return Iterable of items
     */
    Iterable<?> iterateItems(SourceSpec sourceSpec, byte[] input);
    
    /**
     * Evaluate a scalar value using a path/selector
     * @param context Context object (could be root or item)
     * @param candidate Path/selector to evaluate
     * @return Extracted value or null
     */
    Object evalScalar(Object context, String candidate);
    
    /**
     * Evaluate multiple nodes using a path
     * @param context Context object
     * @param pathAnyOf Path to evaluate
     * @return Iterable of matching nodes
     */
    Iterable<?> evalNodes(Object context, String pathAnyOf);
    
    /**
     * Flatten the input into path-value pairs for auto-mapping
     * @param input Raw input bytes
     * @return Map of path to value
     */
    Map<String, Object> flatten(byte[] input);
    
    /**
     * Get the root object from input
     * @param input Raw input bytes
     * @return Root object
     */
    Object getRoot(byte[] input);
}
