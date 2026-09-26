package com.apimarketplace.agent.client.dto.execution;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * DTO for requesting classification execution on agent-service.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ClassifyRequestDto(
    String content,
    String prompt,
    List<CategoryDto> categories,
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
    public ClassifyRequestDto withModel(String newProvider, String newModel) {
        return new ClassifyRequestDto(content, prompt, categories, newProvider, newModel,
            temperature, maxTokens, tenantId, agentEntityId);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CategoryDto(
        String label,
        String description
    ) {}
}
