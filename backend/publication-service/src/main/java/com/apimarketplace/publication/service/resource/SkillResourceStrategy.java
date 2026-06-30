package com.apimarketplace.publication.service.resource;

import com.apimarketplace.agent.client.AgentClient;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.agent.client.dto.SkillDto;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.PublicationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publish/acquire strategy for standalone SKILL resources.
 * Skills are fully self-contained (no file assets, no referenced resources),
 * so the snapshot is just their plain fields: name, description, icon, instructions.
 */
@Component
public class SkillResourceStrategy implements ResourcePublicationStrategy {

    private static final Logger logger = LoggerFactory.getLogger(SkillResourceStrategy.class);

    private final AgentClient agentClient;

    public SkillResourceStrategy(AgentClient agentClient) {
        this.agentClient = agentClient;
    }

    @Override
    public PublicationType getPublicationType() {
        return PublicationType.SKILL;
    }

    @Override
    public DisplayMode getDisplayMode() {
        return DisplayMode.SKILL;
    }

    @Override
    public ResourceMetadata fetchOwnedResource(String resourceId, String tenantId) {
        UUID skillId = parseId(resourceId);
        SkillDto skill = agentClient.getSkill(skillId, tenantId);
        if (skill == null) {
            throw new IllegalArgumentException("Skill not found: " + resourceId);
        }
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (!ScopeGuard.isInStrictScope(tenantId, orgId, skill.getTenantId(), skill.getOrganizationId())) {
            throw new IllegalArgumentException("Skill does not belong to tenant");
        }
        return new ResourceMetadata(skill.getName(), skill.getDescription());
    }

    @Override
    public Map<String, Object> buildSnapshot(String resourceId, String tenantId) {
        UUID skillId = parseId(resourceId);
        SkillDto skill = agentClient.getSkill(skillId, tenantId);
        if (skill == null) {
            throw new IllegalArgumentException("Skill not found: " + resourceId);
        }
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("name", skill.getName());
        snapshot.put("description", skill.getDescription());
        snapshot.put("icon", skill.getIcon());
        snapshot.put("instructions", skill.getInstructions());
        return snapshot;
    }

    @Override
    public String cloneFromSnapshot(Map<String, Object> snapshot, String tenantId, UUID publicationId) {
        return cloneFromSnapshot(snapshot, tenantId, publicationId, null);
    }

    @Override
    public String cloneFromSnapshot(Map<String, Object> snapshot,
                                    String tenantId,
                                    UUID publicationId,
                                    String organizationId) {
        Map<String, Object> createRequest = new HashMap<>();
        createRequest.put("name", snapshot.get("name"));
        createRequest.put("description", snapshot.get("description"));
        createRequest.put("icon", snapshot.get("icon"));
        createRequest.put("instructions", snapshot.get("instructions"));
        createRequest.put("sourcePublicationId", publicationId.toString());
        if (organizationId != null && !organizationId.isBlank()) {
            createRequest.put("organizationId", organizationId);
        }

        SkillDto created = agentClient.createSkill(createRequest, tenantId);
        if (created == null || created.getId() == null) {
            throw new RuntimeException("Failed to clone skill from snapshot for tenant " + tenantId);
        }
        logger.info("Cloned skill publication {} -> skill {} for tenant {}", publicationId, created.getId(), tenantId);
        return created.getId().toString();
    }

    private UUID parseId(String resourceId) {
        try {
            return UUID.fromString(resourceId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid skill id format: " + resourceId);
        }
    }
}
