package com.apimarketplace.orchestrator.services.interfaces;

import com.apimarketplace.orchestrator.domain.WorkflowExecutionContext;
import com.apimarketplace.orchestrator.services.TemplateEngine.ConditionEvaluationResult;

import java.util.Map;

/**
 * Interface for template resolution and expression evaluation.
 * Defines the contract for evaluating SpEL expressions and templates.
 *
 * Implementations handle:
 * - Template evaluation ({{expression}} syntax)
 * - Condition evaluation (boolean expressions)
 * - Step input resolution
 *
 * @see com.apimarketplace.orchestrator.services.TemplateEngine
 */
public interface TemplateResolver {

    // ==================== Template Evaluation ====================

    /**
     * Evaluate a template string using workflow context.
     *
     * @param template The template string (e.g., "Hello {{name}}")
     * @param context The workflow execution context
     * @return The evaluated result
     */
    Object evaluateTemplate(String template, WorkflowExecutionContext context);

    /**
     * Evaluate a template string using a simple map.
     *
     * @param template The template string
     * @param context Map of variable name to value
     * @return The evaluated result
     */
    Object evaluateTemplateWithMap(String template, Map<String, Object> context);

    /**
     * Resolve templates in a string, returning a string result.
     *
     * @param template The template string
     * @param context Map of variable name to value
     * @return The resolved string
     */
    String resolveWithMap(String template, Map<String, Object> context);

    /**
     * Simple template resolution (string to string).
     *
     * @param template The template string
     * @param variables Map of variable name to value
     * @return The resolved string
     */
    String resolveTemplatesSimple(String template, Map<String, Object> variables);

    // ==================== Condition Evaluation ====================

    /**
     * Evaluate a condition expression.
     *
     * @param condition The condition expression
     * @param context The workflow execution context
     * @return true if condition is satisfied
     */
    boolean evaluateCondition(String condition, WorkflowExecutionContext context);

    /**
     * Evaluate a condition with detailed result information.
     *
     * @param condition The condition expression
     * @param context The workflow execution context
     * @return Evaluation result with details
     */
    ConditionEvaluationResult evaluateConditionWithDetails(String condition, WorkflowExecutionContext context);

    /**
     * Evaluate a condition using a simple map.
     *
     * @param condition The condition expression
     * @param context Map of variable name to value
     * @return true if condition is satisfied
     */
    boolean evaluateConditionWithMap(String condition, Map<String, Object> context);

    // ==================== Step Input ====================

    /**
     * Evaluate all templates in a step input map.
     *
     * @param inputTemplate Map with template values
     * @param context The workflow execution context
     * @return Map with evaluated values
     */
    Map<String, Object> evaluateStepInput(Map<String, Object> inputTemplate, WorkflowExecutionContext context);
}
