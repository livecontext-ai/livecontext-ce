package com.apimarketplace.agent.tools.skill;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.ToolModule;
import com.apimarketplace.publication.client.PublicationClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.apimarketplace.agent.tools.common.ToolParamUtils.*;
import com.apimarketplace.agent.tools.common.ToolParamUtils.InvalidVisibilityException;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Marketplace publish/unpublish module for the skill tool.
 *
 * <p>publish: register a skill on the marketplace as a SKILL publication via
 *  publication-service. Requires a landing interface (the public-facing page
 *  shown to acquirers before they install the skill).
 * <p>unpublish: deactivate the existing skill publication. Acquirers who already
 *  installed it keep their copies.
 */
@Component
public class SkillPublishModule implements ToolModule {

    private static final Logger log = LoggerFactory.getLogger(SkillPublishModule.class);

    private static final String RESOURCE_TYPE = "SKILL";
    private static final Set<String> HANDLED_ACTIONS = Set.of("publish", "unpublish");

    private final PublicationClient publicationClient;

    public SkillPublishModule(PublicationClient publicationClient) {
        this.publicationClient = publicationClient;
    }

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of();
    }

    @Override
    public boolean canHandle(String action) {
        return HANDLED_ACTIONS.contains(action);
    }

    @Override
    public Optional<ToolExecutionResult> execute(String action, Map<String, Object> parameters,
                                                  String tenantId, ToolExecutionContext context) {
        if (!canHandle(action)) return Optional.empty();
        return Optional.of(switch (action) {
            case "publish" -> executePublish(parameters, tenantId, context);
            case "unpublish" -> executeUnpublish(parameters, tenantId, context);
            default -> ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "Unknown action: " + action);
        });
    }

    // ==================== Publish ====================

    private ToolExecutionResult executePublish(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);

        String skillIdStr = getStringParam(p, "skill_id");
        if (skillIdStr == null || skillIdStr.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "skill_id is required for publish");
        }

        UUID skillId;
        try {
            skillId = UUID.fromString(skillIdStr);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid skill_id format: " + skillIdStr);
        }

        String title = getStringParam(p, "title");
        if (title == null || title.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "title is required for publish");
        }

        String interfaceId = getStringParam(p, "interface_id");
        if (interfaceId == null || interfaceId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "interface_id is required to publish a skill (landing page presented to acquirers)");
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", RESOURCE_TYPE);
        request.put("resourceId", skillId.toString());
        request.put("interfaceId", interfaceId);
        request.put("title", title);

        String description = getStringParam(p, "description");
        if (description != null && !description.isBlank()) request.put("description", description);

        Integer creditsPerUse = getIntParam(p, "credits_per_use", 0);
        request.put("creditsPerUse", creditsPerUse);

        String visibility;
        try {
            visibility = normalizeVisibility(getStringParam(p, "visibility"));
        } catch (InvalidVisibilityException e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.getMessage());
        }
        request.put("visibility", visibility);

        try {
            // Audit 2026-05-16 round-2: thread ctx.orgId() so publication owner_type='ORG' when in org workspace.
            String orgId = context != null ? context.orgId() : null;
            Map<String, Object> response = publicationClient.publishResource(request, tenantId, orgId);
            if (response == null) {
                return ToolExecutionResult.failure(ToolErrorCode.EXTERNAL_SERVICE_ERROR, "Publication service returned no response");
            }

            String publicationStatus = response.get("status") != null
                    ? response.get("status").toString() : "ACTIVE";
            boolean pending = "PENDING_REVIEW".equals(publicationStatus);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", publicationStatus);
            data.put("skill_id", skillIdStr);
            data.put("publication_id", response.get("id"));
            data.put("title", response.getOrDefault("title", title));
            data.put("visibility", visibility);
            data.put("credits_per_use", creditsPerUse);
            data.put("interface_id", interfaceId);
            data.put("message", (pending
                    ? "Skill submitted for review - not yet visible on the marketplace. "
                    : "Skill published. ")
                    + "Marketplace publication id: " + response.get("id"));
            return ToolExecutionResult.success(data);
        } catch (RuntimeException e) {
            String msg = extractPublicationErrorMessage(e);
            log.warn("Failed to publish skill {}: {}", skillIdStr, msg);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to publish skill: " + msg);
        }
    }

    // ==================== Unpublish ====================

    private ToolExecutionResult executeUnpublish(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);

        String skillIdStr = getStringParam(p, "skill_id");
        if (skillIdStr == null || skillIdStr.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "skill_id is required for unpublish");
        }

        try {
            UUID.fromString(skillIdStr);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid skill_id format: " + skillIdStr);
        }

        // 2026-05-21 sweep follow-up - mirror executePublish org threading so
        // org-scoped (owner_type='ORG') publications resolve correctly.
        String orgId = context != null ? context.orgId() : null;
        if (!publicationClient.isResourcePublished(RESOURCE_TYPE, skillIdStr)) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Resource not published: skill " + skillIdStr + " has no active publication");
        }

        publicationClient.unpublishResource(RESOURCE_TYPE, skillIdStr, tenantId, orgId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UNPUBLISHED");
        data.put("skill_id", skillIdStr);
        data.put("message", "Skill publication marked inactive. Existing acquirers keep their copies.");
        return ToolExecutionResult.success(data);
    }
}
