package com.apimarketplace.orchestrator.services.agent;

/**
 * Rule definition for guardrail validation by the model.
 *
 * @param type   the rule's category (toxic_language, topic_restriction, ...), or null
 * @param action what a violation does (block, flag, sanitize), or null: a violation fails
 */
public record GuardrailRule(String id, String description, String type, String action) {

    /** Previous arity: a rule known only by id and description. */
    public GuardrailRule(String id, String description) {
        this(id, description, null, null);
    }
}
