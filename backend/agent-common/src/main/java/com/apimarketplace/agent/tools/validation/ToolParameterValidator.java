package com.apimarketplace.agent.tools.validation;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.tools.ToolErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service for validating tool parameters against their JSON Schema.
 * Provides automatic validation before tool execution.
 *
 * Supports parameter aliasing to help LLMs that may use outdated parameter names.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolParameterValidator {

    private final AgentToolRegistry registry;

    /**
     * Known parameter aliases: canonical name -> list of accepted aliases.
     * When a required parameter is missing, we check if an alias was provided instead.
     */
    private static final Map<String, List<String>> PARAMETER_ALIASES = Map.of(
        "parameters", List.of("input", "params", "args")  // catalog_execute: 'input' -> 'parameters'
    );

    /**
     * Validate parameters for a tool.
     *
     * @param toolName   The tool name
     * @param parameters The parameters to validate
     * @return Validation result
     */
    public ValidationResult validate(String toolName, Map<String, Object> parameters) {
        Optional<AgentToolDefinition> toolOpt = registry.getToolByName(toolName);

        if (toolOpt.isEmpty()) {
            return ValidationResult.failure(new ValidationResult.ValidationError(
                null,
                "Tool not found: " + toolName,
                ToolErrorCode.TOOL_NOT_FOUND
            ));
        }

        AgentToolDefinition tool = toolOpt.get();

        // Apply parameter aliasing before validation
        Map<String, Object> normalizedParams = applyParameterAliases(parameters);

        return validateAgainstDefinition(tool, normalizedParams);
    }

    /**
     * Apply known parameter aliases to normalize the parameters map.
     * If a canonical parameter name is missing but an alias is present, copy the value.
     *
     * This method is public so that ToolsRegistrationService can also normalize
     * parameters before passing them to the tool execution.
     */
    public Map<String, Object> applyParameterAliases(Map<String, Object> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return parameters != null ? parameters : Map.of();
        }

        // Create mutable copy
        Map<String, Object> normalized = new java.util.HashMap<>(parameters);

        for (Map.Entry<String, List<String>> entry : PARAMETER_ALIASES.entrySet()) {
            String canonicalName = entry.getKey();

            // If canonical parameter is already present, skip
            if (normalized.containsKey(canonicalName)) {
                continue;
            }

            // Check if any alias is present
            for (String alias : entry.getValue()) {
                if (normalized.containsKey(alias)) {
                    Object value = normalized.get(alias);
                    normalized.put(canonicalName, value);
                    log.info("Parameter alias applied: '{}' -> '{}' (value copied)", alias, canonicalName);
                    break; // Only use first matching alias
                }
            }
        }

        return normalized;
    }

    /**
     * Validate parameters against a tool definition.
     */
    public ValidationResult validateAgainstDefinition(AgentToolDefinition tool, Map<String, Object> parameters) {
        ValidationResult.Builder builder = ValidationResult.builder();
        Map<String, Object> params = parameters != null ? parameters : Map.of();

        // Check required parameters
        List<String> requiredParams = tool.requiredParameters();
        if (requiredParams != null) {
            for (String requiredParam : requiredParams) {
                if (!params.containsKey(requiredParam) || params.get(requiredParam) == null) {
                    builder.addMissingParameter(requiredParam);
                }
            }
        }

        // Validate parameter types and values
        List<ToolParameter> paramDefs = tool.parameters();
        if (paramDefs != null) {
            for (ToolParameter paramDef : paramDefs) {
                String paramName = paramDef.name();
                Object value = params.get(paramName);
                
                if (value != null) {
                    validateParameter(builder, paramDef, value);
                }
            }
        }

        return builder.build();
    }

    /**
     * Validate a single parameter value against its definition.
     */
    private void validateParameter(ValidationResult.Builder builder, ToolParameter paramDef, Object value) {
        String paramName = paramDef.name();
        String expectedType = paramDef.type();

        if (expectedType == null) {
            return; // No type constraint
        }

        // Type validation
        boolean typeValid = switch (expectedType.toLowerCase()) {
            case "string" -> value instanceof String;
            case "integer", "int" -> value instanceof Integer || value instanceof Long;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List;
            case "object" -> value instanceof Map;
            default -> true; // Unknown type, skip validation
        };

        if (!typeValid) {
            builder.addInvalidType(paramName, expectedType, value.getClass().getSimpleName());
        }

        // Enum validation
        List<String> enumValues = paramDef.enumValues();
        if (enumValues != null && !enumValues.isEmpty()) {
            String stringValue = String.valueOf(value);
            if (!enumValues.contains(stringValue)) {
                builder.addInvalidEnumValue(paramName, value, enumValues);
            }
        }

        // String-specific validations
        if (value instanceof String strValue && expectedType.equalsIgnoreCase("string")) {
            // Min/max length (if defined in paramDef - would need extension)
            Integer minLength = paramDef.minLength();
            Integer maxLength = paramDef.maxLength();
            
            if (minLength != null && strValue.length() < minLength) {
                builder.addInvalidValue(paramName, 
                    "Value length " + strValue.length() + " is less than minimum " + minLength);
            }
            if (maxLength != null && strValue.length() > maxLength) {
                builder.addInvalidValue(paramName,
                    "Value length " + strValue.length() + " exceeds maximum " + maxLength);
            }
        }

        // Number-specific validations
        if (value instanceof Number numValue) {
            Double minimum = paramDef.minimum();
            Double maximum = paramDef.maximum();
            
            if (minimum != null && numValue.doubleValue() < minimum) {
                builder.addInvalidValue(paramName,
                    "Value " + numValue + " is less than minimum " + minimum);
            }
            if (maximum != null && numValue.doubleValue() > maximum) {
                builder.addInvalidValue(paramName,
                    "Value " + numValue + " exceeds maximum " + maximum);
            }
        }
    }
}
