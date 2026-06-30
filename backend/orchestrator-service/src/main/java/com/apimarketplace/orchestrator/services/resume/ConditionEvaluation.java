package com.apimarketplace.orchestrator.services.resume;

/**
 * Details of a single condition evaluation in a decision node.
 *
 * @param type               The condition type: "if", "elseif", or "else"
 * @param expression         The original condition expression (null for else)
 * @param resolvedExpression The resolved expression with values substituted
 * @param result             The evaluation result
 * @param destination        The target step ID if condition is true
 */
public record ConditionEvaluation(
    String type,
    String expression,
    String resolvedExpression,
    boolean result,
    String destination
) {}
