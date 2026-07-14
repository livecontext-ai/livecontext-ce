package com.apimarketplace.agent.tools.agent;

import com.apimarketplace.agent.config.ToolAccessControl;
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
 * Marketplace publish/unpublish module for the agent tool.
 *
 * <p>publish: register an agent on the marketplace as an AGENT publication via
 *  publication-service. Requires a landing interface (the public-facing page
 *  shown to acquirers before they install the agent).
 * <p>unpublish: deactivate the existing agent publication. Acquirers who already
 *  installed it keep their copies.
 *
 * <p>Distinct from {@link AgentConversationModule}'s {@code share}/{@code unshare},
 * which expose a single conversation via a shareable link, not a marketplace listing.
 */
@Component
public class AgentPublishModule implements ToolModule {

    private static final Logger log = LoggerFactory.getLogger(AgentPublishModule.class);

    private static final Set<String> HANDLED_ACTIONS = Set.of("publish", "unpublish");

    private final PublicationClient publicationClient;

    public AgentPublishModule(PublicationClient publicationClient) {
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
        var accessDenied = ToolAccessControl.checkWriteAccess(
                context != null ? context.credentials() : null,
                "agent",
                action);
        if (accessDenied.isPresent()) {
            return Optional.of(ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, accessDenied.get()));
        }
        return Optional.of(switch (action) {
            case "publish" -> executePublish(parameters, tenantId, context);
            case "unpublish" -> executeUnpublish(parameters, tenantId, context);
            default -> ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "Unknown action: " + action);
        });
    }

    // ==================== Publish ====================

    private ToolExecutionResult executePublish(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);

        String agentIdStr = getStringParam(p, "agent_id");
        if (agentIdStr == null || agentIdStr.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "agent_id is required for publish");
        }

        UUID agentId;
        try {
            agentId = UUID.fromString(agentIdStr);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid agent_id format: " + agentIdStr);
        }

        String title = getStringParam(p, "title");
        if (title == null || title.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "title is required for publish");
        }

        String interfaceId = getStringParam(p, "interface_id");
        if (interfaceId == null || interfaceId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "interface_id is required to publish an agent (landing page presented to acquirers)");
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("agentConfigId", agentId.toString());
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
            // Audit 2026-05-16 round-2: thread ctx.orgId() so the publication
            // owner_type='ORG' is stamped when published from an org workspace.
            String orgId = context != null ? context.orgId() : null;
            Map<String, Object> response = publicationClient.publishAgent(request, tenantId, orgId);
            if (response == null) {
                return ToolExecutionResult.failure(ToolErrorCode.EXTERNAL_SERVICE_ERROR, "Publication service returned no response");
            }

            String publicationStatus = response.get("status") != null
                    ? response.get("status").toString() : "ACTIVE";
            boolean pending = "PENDING_REVIEW".equals(publicationStatus);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("status", publicationStatus);
            data.put("agent_id", agentIdStr);
            data.put("publication_id", response.get("id"));
            data.put("title", response.getOrDefault("title", title));
            data.put("visibility", visibility);
            data.put("credits_per_use", creditsPerUse);
            data.put("interface_id", interfaceId);
            data.put("message", (pending
                    ? "Agent submitted for review - not yet visible on the marketplace. "
                    : "Agent published. ")
                    + "Marketplace publication id: " + response.get("id"));
            return ToolExecutionResult.success(data);
        } catch (com.apimarketplace.publication.client.PublicationValidationException e) {
            String msg = buildValidationFailureMessage(e);
            log.warn("Agent publish refused for {}: {}", agentIdStr, e.getMessage());
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, msg);
        } catch (RuntimeException e) {
            String msg = extractPublicationErrorMessage(e);
            log.warn("Failed to publish agent {}: {}", agentIdStr, msg);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to publish agent: " + msg);
        }
    }

    /**
     * Render publication-service's structured 422 refusal as an agent-actionable
     * message: name the offending agents/resource types and the exact follow-up
     * actions available through this tool surface (`agent` update / list actions).
     */
    @SuppressWarnings("unchecked")
    private static String buildValidationFailureMessage(com.apimarketplace.publication.client.PublicationValidationException e) {
        Map<String, Object> body = e.getBody();
        String code = e.getErrorCode();

        if ("AGENT_ALL_ACCESS_NOT_PUBLISHABLE".equals(code)
                && body.get("violations") instanceof List<?> violations && !violations.isEmpty()) {
            StringBuilder sb = new StringBuilder("Publish refused: ");
            boolean first = true;
            for (Object vRaw : violations) {
                if (!(vRaw instanceof Map<?, ?> v)) continue;
                if (!first) sb.append("; ");
                first = false;
                Object name = v.get("agentName");
                String families = v.get("families") instanceof List<?> f
                        ? f.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("")
                        : String.valueOf(v.get("families"));
                sb.append("agent \"").append(name != null ? name : v.get("agentId")).append('"');
                if (v.get("referencedVia") instanceof List<?> via && !via.isEmpty()) {
                    sb.append(" (sub-agent of \"").append(via.get(via.size() - 1)).append("\")");
                }
                sb.append(" has grant \"all\" on ").append(families);
            }
            sb.append(". A publication only ships an explicit resource selection; \"all\" would embed every ")
              .append("resource of the account and is not publishable. Fix: call `agent` with action=update on each ")
              .append("listed agent and set those resource types to \"custom\" with the exact resource ids to ship ")
              .append("(use the `table` / `interface` / `workflow` list actions to find the ids), or to \"none\". ")
              .append("Then retry publish. The source agents keep working with \"all\" locally; only the published ")
              .append("copy needs an explicit selection.");
            return sb.toString();
        }

        if ("AGENT_SNAPSHOT_TOO_LARGE".equals(code)) {
            StringBuilder sb = new StringBuilder("Publish refused: ").append(e.getMessage());
            if (body.get("breakdown") instanceof List<?> breakdown && !breakdown.isEmpty()) {
                sb.append(" Heaviest resources: ");
                boolean first = true;
                for (Object bRaw : breakdown) {
                    if (!(bRaw instanceof Map<?, ?> b)) continue;
                    if (!first) sb.append(", ");
                    first = false;
                    Object name = b.get("name") != null ? b.get("name") : b.get("id");
                    sb.append(b.get("type")).append(" \"").append(name).append('"');
                    if (b.get("items") != null) sb.append(" (").append(b.get("items")).append(" rows)");
                }
                sb.append('.');
            }
            sb.append(" Fix: call `agent` with action=update and remove the heaviest resources from the agent's ")
              .append("custom selection (or reduce the table content), then retry publish.");
            return sb.toString();
        }

        return "Failed to publish agent: " + e.getMessage();
    }

    // ==================== Unpublish ====================

    private ToolExecutionResult executeUnpublish(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);

        String agentIdStr = getStringParam(p, "agent_id");
        if (agentIdStr == null || agentIdStr.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "agent_id is required for unpublish");
        }

        UUID agentId;
        try {
            agentId = UUID.fromString(agentIdStr);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid agent_id format: " + agentIdStr);
        }

        // 2026-05-21 sweep - isAgentPublished is global by agentConfigId
        // (downstream controller ignores org headers - confirmed by audit),
        // so no orgId needed for the pre-check. unpublishByAgentConfigId IS
        // org-aware: thread ctx.orgId() so the unpublish lands on the right
        // workspace's WorkflowPublication row (avoids cross-workspace bleed
        // when two members each published the same agent config).
        String orgId = context != null ? context.orgId() : null;
        if (!publicationClient.isAgentPublished(agentId, tenantId)) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Resource not published: agent " + agentIdStr + " has no active publication");
        }

        publicationClient.unpublishByAgentConfigId(agentId, tenantId, orgId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UNPUBLISHED");
        data.put("agent_id", agentIdStr);
        data.put("message", "Agent publication marked inactive. Existing acquirers keep their copies.");
        return ToolExecutionResult.success(data);
    }
}
