package com.apimarketplace.datasource.tools.datasource;

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
import com.apimarketplace.agent.config.ToolAccessControl;

/**
 * Marketplace publish/unpublish module for the table tool.
 *
 * <p>publish: register a table on the marketplace as a TABLE publication via
 *  publication-service. Requires a landing interface (the public-facing page
 *  shown to acquirers before they install the table).
 * <p>unpublish: deactivate the existing table publication. Acquirers who already
 *  installed it keep their copies.
 */
@Component
public class TablePublishModule implements ToolModule {

    private static final Logger log = LoggerFactory.getLogger(TablePublishModule.class);

    private static final String RESOURCE_TYPE = "TABLE";
    private static final Set<String> HANDLED_ACTIONS = Set.of("publish", "unpublish");

    private final PublicationClient publicationClient;

    public TablePublishModule(PublicationClient publicationClient) {
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

        // publish/unpublish are WRITE actions: a read-only agent (tableAccessMode='read') must be
        // denied (they were ungated entirely before - a read-only/scoped agent could publish any
        // reachable table to the marketplace).
        var accessDenied = ToolAccessControl.checkWriteAccess(
                context != null ? context.credentials() : null, "table", action);
        if (accessDenied.isPresent()) return Optional.of(ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, accessDenied.get()));

        // Allow-list check: a tables=custom agent may only publish/unpublish its approved table ids.
        Map<String, Object> p = mergeParams(parameters);
        String tableIdStr = stringId(p, "table_id");
        if (tableIdStr == null) tableIdStr = stringId(p, "datasource_id");
        var notAllowed = TableToolAccess.denyIfNotAllowed(context, tableIdStr);
        if (notAllowed.isPresent()) return notAllowed;

        return Optional.of(switch (action) {
            case "publish" -> executePublish(parameters, tenantId, context);
            case "unpublish" -> executeUnpublish(parameters, tenantId, context);
            default -> ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "Unknown action: " + action);
        });
    }

    // ==================== Publish ====================

    private ToolExecutionResult executePublish(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);

        // Accept both table_id and datasource_id (provider maps table_id -> datasource_id)
        String tableIdStr = stringId(p, "table_id");
        if (tableIdStr == null) tableIdStr = stringId(p, "datasource_id");
        if (tableIdStr == null || tableIdStr.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "table_id is required for publish");
        }

        String title = getStringParam(p, "title");
        if (title == null || title.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "title is required for publish");
        }

        String interfaceId = getStringParam(p, "interface_id");
        if (interfaceId == null || interfaceId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "interface_id is required to publish a table (landing page presented to acquirers)");
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", RESOURCE_TYPE);
        request.put("resourceId", tableIdStr);
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
            data.put("table_id", tableIdStr);
            data.put("publication_id", response.get("id"));
            data.put("title", response.getOrDefault("title", title));
            data.put("visibility", visibility);
            data.put("credits_per_use", creditsPerUse);
            data.put("interface_id", interfaceId);
            data.put("message", (pending
                    ? "Table submitted for review - not yet visible on the marketplace. "
                    : "Table published. ")
                    + "Marketplace publication id: " + response.get("id"));
            return ToolExecutionResult.success(data);
        } catch (RuntimeException e) {
            String msg = extractPublicationErrorMessage(e);
            log.warn("Failed to publish table {}: {}", tableIdStr, msg);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to publish table: " + msg);
        }
    }

    // ==================== Unpublish ====================

    private ToolExecutionResult executeUnpublish(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);

        String tableIdStr = stringId(p, "table_id");
        if (tableIdStr == null) tableIdStr = stringId(p, "datasource_id");
        if (tableIdStr == null || tableIdStr.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "table_id is required for unpublish");
        }

        if (!publicationClient.isResourcePublished(RESOURCE_TYPE, tableIdStr)) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Resource not published: table " + tableIdStr + " has no active publication");
        }

        // 2026-05-21 sweep - mirror SkillPublishModule/AgentPublishModule org threading.
        String orgId = context != null ? context.orgId() : null;
        publicationClient.unpublishResource(RESOURCE_TYPE, tableIdStr, tenantId, orgId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UNPUBLISHED");
        data.put("table_id", tableIdStr);
        data.put("message", "Table publication marked inactive. Existing acquirers keep their copies.");
        return ToolExecutionResult.success(data);
    }

    /**
     * Tables are identified by integer IDs but the publication-service stores resourceId as a string.
     * Accept both numeric and string forms and normalize to a non-empty string.
     */
    private static String stringId(Map<String, Object> p, String key) {
        Object val = p.get(key);
        if (val == null) return null;
        if (val instanceof String s) return s.isBlank() ? null : s;
        return val.toString();
    }
}
