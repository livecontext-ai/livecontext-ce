package com.apimarketplace.agent.tools.validation;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.AgentToolRegistry;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ToolParameterValidator - ensures specific error codes are returned
 * when LLM doesn't provide required parameters or provides invalid values.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ToolParameterValidator Tests")
class ToolParameterValidatorTest {

    @Mock
    private AgentToolRegistry registry;

    private ToolParameterValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ToolParameterValidator(registry);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TOOL NOT FOUND TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Tool Not Found Errors")
    class ToolNotFoundTests {

        @Test
        @DisplayName("Should return TOOL_NOT_FOUND when tool doesn't exist")
        void shouldReturnToolNotFoundError() {
            // Given
            when(registry.getToolByName("unknown_tool")).thenReturn(Optional.empty());

            // When
            ValidationResult result = validator.validate("unknown_tool", Map.of("param1", "value1"));

            // Then
            assertFalse(result.isValid());
            assertEquals(ToolErrorCode.TOOL_NOT_FOUND, result.getPrimaryErrorCode());
            assertTrue(result.formatErrors().contains("Tool not found: unknown_tool"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MISSING PARAMETER TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Missing Parameter Errors (TOOL_011)")
    class MissingParameterTests {

        @Test
        @DisplayName("Should return MISSING_PARAMETER when required parameter is not provided")
        void shouldReturnMissingParameterError() {
            // Given
            AgentToolDefinition tool = createToolWithRequiredParams("test_tool", List.of("required_param"));
            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            // When - LLM calls tool without the required parameter
            ValidationResult result = validator.validate("test_tool", Map.of());

            // Then
            assertFalse(result.isValid());
            assertEquals(ToolErrorCode.MISSING_PARAMETER, result.getPrimaryErrorCode());
            assertTrue(result.formatErrors().contains("required_param"));
        }

        @Test
        @DisplayName("Should return MISSING_PARAMETER when required parameter is null")
        void shouldReturnMissingParameterErrorForNullValue() {
            // Given
            AgentToolDefinition tool = createToolWithRequiredParams("test_tool", List.of("required_param"));
            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            Map<String, Object> params = new HashMap<>();
            params.put("required_param", null);

            // When - LLM provides null for required parameter
            ValidationResult result = validator.validate("test_tool", params);

            // Then
            assertFalse(result.isValid());
            assertEquals(ToolErrorCode.MISSING_PARAMETER, result.getPrimaryErrorCode());
        }

        @Test
        @DisplayName("Should return multiple MISSING_PARAMETER errors for multiple missing params")
        void shouldReturnMultipleMissingParameterErrors() {
            // Given
            AgentToolDefinition tool = createToolWithRequiredParams("test_tool",
                List.of("param1", "param2", "param3"));
            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            // When - LLM provides only param2
            ValidationResult result = validator.validate("test_tool", Map.of("param2", "value2"));

            // Then
            assertFalse(result.isValid());
            assertEquals(2, result.errors().size()); // param1 and param3 missing
            assertTrue(result.errors().stream()
                .allMatch(e -> e.errorCode() == ToolErrorCode.MISSING_PARAMETER));
            assertTrue(result.formatErrors().contains("param1"));
            assertTrue(result.formatErrors().contains("param3"));
        }

        @Test
        @DisplayName("Should pass validation when all required parameters are provided")
        void shouldPassWhenAllRequiredParamsProvided() {
            // Given
            AgentToolDefinition tool = createToolWithRequiredParams("test_tool",
                List.of("param1", "param2"));
            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            // When
            ValidationResult result = validator.validate("test_tool",
                Map.of("param1", "value1", "param2", "value2"));

            // Then
            assertTrue(result.isValid());
            assertTrue(result.errors().isEmpty());
        }

        @Test
        @DisplayName("Should pass validation when parameters is null but no required params")
        void shouldPassWhenNoRequiredParams() {
            // Given
            AgentToolDefinition tool = createToolWithRequiredParams("test_tool", List.of());
            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            // When
            ValidationResult result = validator.validate("test_tool", null);

            // Then
            assertTrue(result.isValid());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INVALID PARAMETER TYPE TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Invalid Parameter Type Errors (TOOL_012)")
    class InvalidParameterTypeTests {

        @Test
        @DisplayName("Should return INVALID_PARAMETER_TYPE when string expected but number provided")
        void shouldReturnInvalidTypeForStringExpectedNumberProvided() {
            // Given
            AgentToolDefinition tool = createToolWithTypedParam("test_tool", "name", "string");
            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            // When - LLM provides number instead of string
            ValidationResult result = validator.validate("test_tool", Map.of("name", 123));

            // Then
            assertFalse(result.isValid());
            assertEquals(ToolErrorCode.INVALID_PARAMETER_TYPE, result.getPrimaryErrorCode());
            assertTrue(result.formatErrors().contains("expected type string"));
            assertTrue(result.formatErrors().contains("Integer"));
        }

        @Test
        @DisplayName("Should return INVALID_PARAMETER_TYPE when number expected but string provided")
        void shouldReturnInvalidTypeForNumberExpectedStringProvided() {
            // Given
            AgentToolDefinition tool = createToolWithTypedParam("test_tool", "count", "number");
            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            // When - LLM provides string instead of number
            ValidationResult result = validator.validate("test_tool", Map.of("count", "not_a_number"));

            // Then
            assertFalse(result.isValid());
            assertEquals(ToolErrorCode.INVALID_PARAMETER_TYPE, result.getPrimaryErrorCode());
            assertTrue(result.formatErrors().contains("expected type number"));
        }

        @Test
        @DisplayName("Should return INVALID_PARAMETER_TYPE when boolean expected but string provided")
        void shouldReturnInvalidTypeForBooleanExpectedStringProvided() {
            // Given
            AgentToolDefinition tool = createToolWithTypedParam("test_tool", "enabled", "boolean");
            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            // When - LLM provides string "true" instead of boolean true
            ValidationResult result = validator.validate("test_tool", Map.of("enabled", "true"));

            // Then
            assertFalse(result.isValid());
            assertEquals(ToolErrorCode.INVALID_PARAMETER_TYPE, result.getPrimaryErrorCode());
            assertTrue(result.formatErrors().contains("expected type boolean"));
        }

        @Test
        @DisplayName("Should return INVALID_PARAMETER_TYPE when array expected but object provided")
        void shouldReturnInvalidTypeForArrayExpectedObjectProvided() {
            // Given
            AgentToolDefinition tool = createToolWithTypedParam("test_tool", "items", "array");
            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            // When - LLM provides object instead of array
            ValidationResult result = validator.validate("test_tool", Map.of("items", Map.of("key", "value")));

            // Then
            assertFalse(result.isValid());
            assertEquals(ToolErrorCode.INVALID_PARAMETER_TYPE, result.getPrimaryErrorCode());
        }

        @Test
        @DisplayName("Should return INVALID_PARAMETER_TYPE when object expected but array provided")
        void shouldReturnInvalidTypeForObjectExpectedArrayProvided() {
            // Given
            AgentToolDefinition tool = createToolWithTypedParam("test_tool", "config", "object");
            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            // When - LLM provides array instead of object
            ValidationResult result = validator.validate("test_tool", Map.of("config", List.of("a", "b")));

            // Then
            assertFalse(result.isValid());
            assertEquals(ToolErrorCode.INVALID_PARAMETER_TYPE, result.getPrimaryErrorCode());
        }

        @Test
        @DisplayName("Should accept integer when number type is expected")
        void shouldAcceptIntegerWhenNumberExpected() {
            // Given
            AgentToolDefinition tool = createToolWithTypedParam("test_tool", "value", "number");
            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            // When - LLM provides integer for number field
            ValidationResult result = validator.validate("test_tool", Map.of("value", 42));

            // Then
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should accept Long when integer type is expected")
        void shouldAcceptLongWhenIntegerExpected() {
            // Given
            AgentToolDefinition tool = createToolWithTypedParam("test_tool", "id", "integer");
            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            // When - LLM provides Long
            ValidationResult result = validator.validate("test_tool", Map.of("id", 123L));

            // Then
            assertTrue(result.isValid());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INVALID ENUM VALUE TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Invalid Enum Value Errors (TOOL_014)")
    class InvalidEnumValueTests {

        @Test
        @DisplayName("Should return INVALID_ENUM_VALUE when value not in allowed list")
        void shouldReturnInvalidEnumValueError() {
            // Given
            AgentToolDefinition tool = createToolWithEnumParam("test_tool", "status",
                List.of("active", "inactive", "pending"));
            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            // When - LLM provides invalid enum value
            ValidationResult result = validator.validate("test_tool", Map.of("status", "invalid_status"));

            // Then
            assertFalse(result.isValid());
            assertEquals(ToolErrorCode.INVALID_ENUM_VALUE, result.getPrimaryErrorCode());
            assertTrue(result.formatErrors().contains("invalid_status"));
            assertTrue(result.formatErrors().contains("active"));
            assertTrue(result.formatErrors().contains("inactive"));
        }

        @Test
        @DisplayName("Should pass when valid enum value is provided")
        void shouldPassWhenValidEnumValue() {
            // Given
            AgentToolDefinition tool = createToolWithEnumParam("test_tool", "status",
                List.of("active", "inactive", "pending"));
            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            // When
            ValidationResult result = validator.validate("test_tool", Map.of("status", "active"));

            // Then
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should return INVALID_ENUM_VALUE for case-sensitive mismatch")
        void shouldReturnInvalidEnumValueForCaseMismatch() {
            // Given
            AgentToolDefinition tool = createToolWithEnumParam("test_tool", "status",
                List.of("active", "inactive"));
            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            // When - LLM provides wrong case
            ValidationResult result = validator.validate("test_tool", Map.of("status", "ACTIVE"));

            // Then
            assertFalse(result.isValid());
            assertEquals(ToolErrorCode.INVALID_ENUM_VALUE, result.getPrimaryErrorCode());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INVALID PARAMETER VALUE TESTS (Constraints)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Invalid Parameter Value Errors (TOOL_013) - Constraints")
    class InvalidParameterValueTests {

        @Test
        @DisplayName("Should return INVALID_PARAMETER_VALUE when string is too short")
        void shouldReturnInvalidValueForStringTooShort() {
            // Given
            AgentToolDefinition tool = createToolWithStringConstraints("test_tool", "name", 5, 100);
            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            // When - LLM provides string shorter than minLength
            ValidationResult result = validator.validate("test_tool", Map.of("name", "ab"));

            // Then
            assertFalse(result.isValid());
            assertEquals(ToolErrorCode.INVALID_PARAMETER_VALUE, result.getPrimaryErrorCode());
            assertTrue(result.formatErrors().contains("less than minimum"));
        }

        @Test
        @DisplayName("Should return INVALID_PARAMETER_VALUE when string is too long")
        void shouldReturnInvalidValueForStringTooLong() {
            // Given
            AgentToolDefinition tool = createToolWithStringConstraints("test_tool", "code", 1, 10);
            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            // When - LLM provides string longer than maxLength
            ValidationResult result = validator.validate("test_tool", Map.of("code", "this_string_is_way_too_long"));

            // Then
            assertFalse(result.isValid());
            assertEquals(ToolErrorCode.INVALID_PARAMETER_VALUE, result.getPrimaryErrorCode());
            assertTrue(result.formatErrors().contains("exceeds maximum"));
        }

        @Test
        @DisplayName("Should return INVALID_PARAMETER_VALUE when number is below minimum")
        void shouldReturnInvalidValueForNumberBelowMinimum() {
            // Given
            AgentToolDefinition tool = createToolWithNumberConstraints("test_tool", "age", 0.0, 150.0);
            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            // When - LLM provides negative age
            ValidationResult result = validator.validate("test_tool", Map.of("age", -5));

            // Then
            assertFalse(result.isValid());
            assertEquals(ToolErrorCode.INVALID_PARAMETER_VALUE, result.getPrimaryErrorCode());
            assertTrue(result.formatErrors().contains("less than minimum"));
        }

        @Test
        @DisplayName("Should return INVALID_PARAMETER_VALUE when number exceeds maximum")
        void shouldReturnInvalidValueForNumberAboveMaximum() {
            // Given
            AgentToolDefinition tool = createToolWithNumberConstraints("test_tool", "percentage", 0.0, 100.0);
            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            // When - LLM provides percentage > 100
            ValidationResult result = validator.validate("test_tool", Map.of("percentage", 150.5));

            // Then
            assertFalse(result.isValid());
            assertEquals(ToolErrorCode.INVALID_PARAMETER_VALUE, result.getPrimaryErrorCode());
            assertTrue(result.formatErrors().contains("exceeds maximum"));
        }

        @Test
        @DisplayName("Should pass when number is exactly at minimum boundary")
        void shouldPassWhenNumberAtMinimum() {
            // Given
            AgentToolDefinition tool = createToolWithNumberConstraints("test_tool", "value", 10.0, 100.0);
            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            // When
            ValidationResult result = validator.validate("test_tool", Map.of("value", 10.0));

            // Then
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should pass when number is exactly at maximum boundary")
        void shouldPassWhenNumberAtMaximum() {
            // Given
            AgentToolDefinition tool = createToolWithNumberConstraints("test_tool", "value", 0.0, 100.0);
            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            // When
            ValidationResult result = validator.validate("test_tool", Map.of("value", 100.0));

            // Then
            assertTrue(result.isValid());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PARAMETER ALIASING TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Parameter Aliasing")
    class ParameterAliasingTests {

        @Test
        @DisplayName("Should accept 'input' as alias for 'parameters'")
        void shouldAcceptInputAsAliasForParameters() {
            // Given - Tool expects 'parameters' of type 'object'
            AgentToolDefinition tool = createToolWithRequiredObjectParam("test_tool", "parameters");
            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            // When - LLM uses 'input' instead of 'parameters'
            ValidationResult result = validator.validate("test_tool",
                Map.of("input", Map.of("key", "value")));

            // Then - Should pass because 'input' is aliased to 'parameters'
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should accept 'params' as alias for 'parameters'")
        void shouldAcceptParamsAsAliasForParameters() {
            // Given - Tool expects 'parameters' of type 'object'
            AgentToolDefinition tool = createToolWithRequiredObjectParam("test_tool", "parameters");
            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            // When - LLM uses 'params' instead of 'parameters'
            ValidationResult result = validator.validate("test_tool",
                Map.of("params", Map.of("key", "value")));

            // Then
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("Should prefer canonical name over alias when both provided")
        void shouldPreferCanonicalOverAlias() {
            // Given
            AgentToolDefinition tool = createToolWithRequiredParams("test_tool", List.of("parameters"));
            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            // When - Both 'parameters' and 'input' provided
            Map<String, Object> params = new HashMap<>();
            params.put("parameters", "canonical_value");
            params.put("input", "alias_value");

            ValidationResult result = validator.validate("test_tool", params);

            // Then - Should use canonical 'parameters' value
            assertTrue(result.isValid());
        }

        @Test
        @DisplayName("applyParameterAliases should copy alias value to canonical")
        void shouldApplyAliasCorrectly() {
            // Given
            Map<String, Object> params = Map.of("input", "test_value");

            // When
            Map<String, Object> normalized = validator.applyParameterAliases(params);

            // Then
            assertEquals("test_value", normalized.get("parameters"));
            assertEquals("test_value", normalized.get("input")); // Original preserved
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COMBINED VALIDATION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Combined Validation Scenarios")
    class CombinedValidationTests {

        @Test
        @DisplayName("Should return multiple errors for multiple issues")
        void shouldReturnMultipleErrors() {
            // Given - Tool with required string param and enum param
            ToolParameter stringParam = ToolParameter.builder()
                .name("name")
                .type("string")
                .required(true)
                .build();
            ToolParameter enumParam = ToolParameter.builder()
                .name("status")
                .type("string")
                .enumValues(List.of("active", "inactive"))
                .required(true)
                .build();

            AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("test_tool")
                .description("Test tool")
                .category(ToolCategory.HELP)
                .parameters(List.of(stringParam, enumParam))
                .requiredParameters(List.of("name", "status"))
                .build();

            when(registry.getToolByName("test_tool")).thenReturn(Optional.of(tool));

            // When - Missing 'name', invalid enum for 'status'
            ValidationResult result = validator.validate("test_tool",
                Map.of("status", "invalid_status"));

            // Then - Should have 2 errors
            assertFalse(result.isValid());
            assertEquals(2, result.errors().size());

            // Check we have both error types
            assertTrue(result.errors().stream()
                .anyMatch(e -> e.errorCode() == ToolErrorCode.MISSING_PARAMETER));
            assertTrue(result.errors().stream()
                .anyMatch(e -> e.errorCode() == ToolErrorCode.INVALID_ENUM_VALUE));
        }

        @Test
        @DisplayName("Should validate real-world workflow scenario")
        void shouldValidateWorkflowBuilderScenario() {
            // Given - Simulating workflow tool
            ToolParameter actionParam = ToolParameter.builder()
                .name("action")
                .type("string")
                .enumValues(List.of("add_trigger", "add_step", "add_agent", "finalize"))
                .required(true)
                .build();
            ToolParameter triggerTypeParam = ToolParameter.builder()
                .name("trigger_type")
                .type("string")
                .enumValues(List.of("webhook", "schedule", "chat", "manual", "datasource"))
                .required(false)
                .build();

            AgentToolDefinition tool = AgentToolDefinition.builder()
                .name("workflow")
                .description("Build workflow")
                .category(ToolCategory.WORKFLOW)
                .parameters(List.of(actionParam, triggerTypeParam))
                .requiredParameters(List.of("action"))
                .build();

            when(registry.getToolByName("workflow")).thenReturn(Optional.of(tool));

            // When - LLM forgets action parameter
            ValidationResult result = validator.validate("workflow",
                Map.of("trigger_type", "webhook"));

            // Then
            assertFalse(result.isValid());
            assertEquals(ToolErrorCode.MISSING_PARAMETER, result.getPrimaryErrorCode());
            assertTrue(result.formatErrors().contains("action"));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    private AgentToolDefinition createToolWithRequiredParams(String name, List<String> requiredParams) {
        List<ToolParameter> params = requiredParams.stream()
            .map(p -> ToolParameter.builder().name(p).type("string").required(true).build())
            .toList();

        return AgentToolDefinition.builder()
            .name(name)
            .description("Test tool")
            .category(ToolCategory.HELP)
            .parameters(params)
            .requiredParameters(requiredParams)
            .build();
    }

    private AgentToolDefinition createToolWithRequiredObjectParam(String toolName, String paramName) {
        ToolParameter param = ToolParameter.builder()
            .name(paramName)
            .type("object")
            .required(true)
            .build();

        return AgentToolDefinition.builder()
            .name(toolName)
            .description("Test tool")
            .category(ToolCategory.HELP)
            .parameters(List.of(param))
            .requiredParameters(List.of(paramName))
            .build();
    }

    private AgentToolDefinition createToolWithTypedParam(String toolName, String paramName, String type) {
        ToolParameter param = ToolParameter.builder()
            .name(paramName)
            .type(type)
            .required(false)
            .build();

        return AgentToolDefinition.builder()
            .name(toolName)
            .description("Test tool")
            .category(ToolCategory.HELP)
            .parameters(List.of(param))
            .requiredParameters(List.of())
            .build();
    }

    private AgentToolDefinition createToolWithEnumParam(String toolName, String paramName, List<String> enumValues) {
        ToolParameter param = ToolParameter.builder()
            .name(paramName)
            .type("string")
            .enumValues(enumValues)
            .required(false)
            .build();

        return AgentToolDefinition.builder()
            .name(toolName)
            .description("Test tool")
            .category(ToolCategory.HELP)
            .parameters(List.of(param))
            .requiredParameters(List.of())
            .build();
    }

    private AgentToolDefinition createToolWithStringConstraints(String toolName, String paramName,
                                                                 Integer minLength, Integer maxLength) {
        ToolParameter param = ToolParameter.builder()
            .name(paramName)
            .type("string")
            .minLength(minLength)
            .maxLength(maxLength)
            .required(false)
            .build();

        return AgentToolDefinition.builder()
            .name(toolName)
            .description("Test tool")
            .category(ToolCategory.HELP)
            .parameters(List.of(param))
            .requiredParameters(List.of())
            .build();
    }

    private AgentToolDefinition createToolWithNumberConstraints(String toolName, String paramName,
                                                                 Double minimum, Double maximum) {
        ToolParameter param = ToolParameter.builder()
            .name(paramName)
            .type("number")
            .minimum(minimum)
            .maximum(maximum)
            .required(false)
            .build();

        return AgentToolDefinition.builder()
            .name(toolName)
            .description("Test tool")
            .category(ToolCategory.HELP)
            .parameters(List.of(param))
            .requiredParameters(List.of())
            .build();
    }
}
