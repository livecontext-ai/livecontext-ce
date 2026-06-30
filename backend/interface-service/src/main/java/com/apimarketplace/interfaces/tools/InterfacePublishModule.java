package com.apimarketplace.interfaces.tools;

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
 * Marketplace publish/unpublish module for the interface tool.
 *
 * <p>publish: register an interface on the marketplace as an INTERFACE publication
 *  via publication-service. The resource itself IS the landing page, so
 *  interface_id MUST NOT be passed (it would be redundant).
 * <p>unpublish: deactivate the existing interface publication. Acquirers who already
 *  installed it keep their copies.
 */
@Component
public class InterfacePublishModule implements ToolModule {

    private static final Logger log = LoggerFactory.getLogger(InterfacePublishModule.class);

    private static final String RESOURCE_TYPE = "INTERFACE";
    private static final Set<String> HANDLED_ACTIONS = Set.of("publish", "unpublish");

    private final PublicationClient publicationClient;

    public InterfacePublishModule(PublicationClient publicationClient) {
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

        String interfaceIdStr = getStringParam(p, "interface_id");
        if (interfaceIdStr == null || interfaceIdStr.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "interface_id is required for publish");
        }

        UUID interfaceId;
        try {
            interfaceId = UUID.fromString(interfaceIdStr);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid interface_id format: " + interfaceIdStr);
        }

        String title = getStringParam(p, "title");
        if (title == null || title.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "title is required for publish");
        }

        // For INTERFACE publications the resource IS its own landing page. TABLE/SKILL/AGENT
        // publications take a separate `landing_interface_id` - an LLM that just switched
        // from those tools tends to pass the same key here. Reject explicitly so it gets
        // a structured error instead of a silently-ignored param that diverges from the
        // REST contract enforced by `InterfaceResourcePublicationStrategy`.
        for (String forbidden : List.of("landing_interface_id", "landingInterfaceId", "landing_id")) {
            Object landingVal = p.get(forbidden);
            if (landingVal == null) continue;
            String landingStr = landingVal.toString();
            if (landingStr.isBlank()) continue;
            // Tolerate the no-op case where the caller echoed back interface_id as the
            // landing - it's redundant but not semantically wrong.
            if (landingStr.equals(interfaceIdStr)) continue;
            return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, "`" + forbidden + "` is not accepted for INTERFACE publications - "
                + "the interface itself is the landing page. Remove the param or publish "
                + "a TABLE/SKILL/AGENT if you want a distinct landing.");
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", RESOURCE_TYPE);
        request.put("resourceId", interfaceId.toString());
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
            data.put("interface_id", interfaceIdStr);
            data.put("publication_id", response.get("id"));
            data.put("title", response.getOrDefault("title", title));
            data.put("visibility", visibility);
            data.put("credits_per_use", creditsPerUse);
            data.put("message", (pending
                    ? "Interface submitted for review - not yet visible on the marketplace. "
                    : "Interface published. ")
                    + "Marketplace publication id: " + response.get("id"));
            return ToolExecutionResult.success(data);
        } catch (RuntimeException e) {
            String msg = extractPublicationErrorMessage(e);
            log.warn("Failed to publish interface {}: {}", interfaceIdStr, msg);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to publish interface: " + msg);
        }
    }

    // ==================== Unpublish ====================

    private ToolExecutionResult executeUnpublish(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);

        String interfaceIdStr = getStringParam(p, "interface_id");
        if (interfaceIdStr == null || interfaceIdStr.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "interface_id is required for unpublish");
        }

        try {
            UUID.fromString(interfaceIdStr);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid interface_id format: " + interfaceIdStr);
        }

        if (!publicationClient.isResourcePublished(RESOURCE_TYPE, interfaceIdStr)) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Resource not published: interface " + interfaceIdStr + " has no active publication");
        }

        // 2026-05-21 sweep - mirror SkillPublishModule/AgentPublishModule org threading.
        String orgId = context != null ? context.orgId() : null;
        publicationClient.unpublishResource(RESOURCE_TYPE, interfaceIdStr, tenantId, orgId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UNPUBLISHED");
        data.put("interface_id", interfaceIdStr);
        data.put("message", "Interface publication marked inactive. Existing acquirers keep their copies.");
        return ToolExecutionResult.success(data);
    }
}
