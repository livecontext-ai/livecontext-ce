package com.apimarketplace.agent.client.dto.execution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * DTO for requesting guardrail validation on agent-service.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GuardrailRequestDto(
    String content,
    String prompt,
    List<RuleDto> rules,
    String action,
    String provider,
    String model,
    Double temperature,
    Integer maxTokens,
    String tenantId,
    String agentEntityId
) {

    /**
     * The prompt as an instruction SEPARATE from the content, or {@code null} when there is none.
     *
     * <p>A node with no content configured sends its prompt in BOTH fields: the orchestrator
     * reads "no content" as "the prompt is the content". Treating the two as distinct put the
     * same text in front of the model twice (a whole email, billed twice, on every item), so a
     * prompt equal to the content is not an instruction of its own. Not a Jackson property:
     * a record serialises its components only.
     */
    public String distinctPrompt() {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }
        return prompt.equals(content) ? null : prompt;
    }

    /** Copy with the model pair replaced (a disabled model swapped for its replacement, V515). */
    public GuardrailRequestDto withModel(String newProvider, String newModel) {
        return new GuardrailRequestDto(content, prompt, rules, action, newProvider, newModel,
            temperature, maxTokens, tenantId, agentEntityId);
    }

    /**
     * One rule for the model. {@code type} names the rule's category (toxic_language,
     * topic_restriction, ...) and {@code action} what the node does on a violation
     * (block, flag, sanitize); both optional, a null action meaning "a violation fails".
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RuleDto(
        String id,
        String description,
        String type,
        String action
    ) {
        /** Previous arity: a rule known only by id and description. */
        public RuleDto(String id, String description) {
            this(id, description, null, null);
        }
    }
}
