package com.apimarketplace.agent.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;

/**
 * DTO for agent-skill assignment.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentSkillDto {

    private UUID id;
    private UUID agentId;
    private UUID skillId;
    private int sortOrder;
    private String skillName;
    private String skillDescription;
    private String skillIcon;
    private String skillInstructions;

    public AgentSkillDto() {}

    public AgentSkillDto(UUID id, UUID agentId, UUID skillId, int sortOrder,
                         String skillName, String skillDescription,
                         String skillIcon, String skillInstructions) {
        this.id = id;
        this.agentId = agentId;
        this.skillId = skillId;
        this.sortOrder = sortOrder;
        this.skillName = skillName;
        this.skillDescription = skillDescription;
        this.skillIcon = skillIcon;
        this.skillInstructions = skillInstructions;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getAgentId() { return agentId; }
    public void setAgentId(UUID agentId) { this.agentId = agentId; }

    public UUID getSkillId() { return skillId; }
    public void setSkillId(UUID skillId) { this.skillId = skillId; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }

    public String getSkillName() { return skillName; }
    public void setSkillName(String skillName) { this.skillName = skillName; }

    public String getSkillDescription() { return skillDescription; }
    public void setSkillDescription(String skillDescription) { this.skillDescription = skillDescription; }

    public String getSkillIcon() { return skillIcon; }
    public void setSkillIcon(String skillIcon) { this.skillIcon = skillIcon; }

    public String getSkillInstructions() { return skillInstructions; }
    public void setSkillInstructions(String skillInstructions) { this.skillInstructions = skillInstructions; }
}
