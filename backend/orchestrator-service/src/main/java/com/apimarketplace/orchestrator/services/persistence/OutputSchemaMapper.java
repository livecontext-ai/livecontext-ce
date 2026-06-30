package com.apimarketplace.orchestrator.services.persistence;

import com.apimarketplace.orchestrator.services.persistence.schema.GenericOutputSchemaMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Facade service for transforming backend node output to DB schema format.
 *
 * Delegates to GenericOutputSchemaMapper which uses NodeSpec definitions
 * (with optional customTransform) as the single source of truth.
 *
 * This provides a clean separation between:
 * - Backend internal execution output format (optimized for execution)
 * - DB storage schema format (optimized for frontend display and consistency)
 */
@Service
public class OutputSchemaMapper {

    private static final Logger logger = LoggerFactory.getLogger(OutputSchemaMapper.class);

    private final GenericOutputSchemaMapper genericMapper;

    public OutputSchemaMapper(GenericOutputSchemaMapper genericMapper) {
        this.genericMapper = genericMapper;
    }

    /**
     * Transform backend node output to the expected DB schema format.
     *
     * @param backendOutput The raw output from backend node execution
     * @param nodeType The type of node (e.g., "DECISION", "SWITCH", "LOOP", etc.)
     * @return Transformed output matching the expected DB schema, or original if no definition found
     */
    public Map<String, Object> transformToDbSchema(Map<String, Object> backendOutput, String nodeType) {
        if (backendOutput == null || nodeType == null) {
            return backendOutput;
        }

        Map<String, Object> result = genericMapper.transform(nodeType, backendOutput);
        if (result != null) {
            logger.debug("Transformed {} output using GenericOutputSchemaMapper: keys={}", nodeType, result.keySet());
            return result;
        }

        // No definition found - return original output
        logger.debug("No NodeSpec found for node type: {}, using original output", nodeType);
        return backendOutput;
    }

    /**
     * Check if a schema mapper exists for the given node type.
     */
    public boolean hasMapper(String nodeType) {
        return genericMapper.canHandle(nodeType);
    }
}
