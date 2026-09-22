package com.apimarketplace.catalog.tools;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.ToolModule;
import com.apimarketplace.catalog.domain.dto.ApiResponse;
import com.apimarketplace.catalog.service.CustomApiRegistrationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Module handling custom API registration operations.
 * Operations: register_api, update_api, delete_api, list_custom_apis
 */
@Slf4j
@Component
public class CatalogRegisterModule implements ToolModule {

    private final CustomApiRegistrationService registrationService;
    private final ObjectMapper objectMapper;

    private static final Set<String> HANDLED_ACTIONS = Set.of(
        "register_api", "update_api", "delete_api", "list_custom_apis"
    );

    public CatalogRegisterModule(CustomApiRegistrationService registrationService,
                                  ObjectMapper objectMapper) {
        this.registrationService = registrationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of();
    }

    @Override
    public boolean canHandle(String toolName) {
        return HANDLED_ACTIONS.contains(toolName);
    }

    @Override
    public Optional<ToolExecutionResult> execute(String toolName, Map<String, Object> parameters,
                                                  String tenantId, ToolExecutionContext context) {
        if (!canHandle(toolName)) {
            return Optional.empty();
        }

        if (tenantId == null || tenantId.isBlank()) {
            return Optional.of(ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "tenantId is required for custom API operations"));
        }

        return Optional.of(switch (toolName) {
            case "register_api" -> executeRegister(parameters, tenantId);
            case "update_api" -> executeUpdate(parameters, tenantId);
            case "delete_api" -> executeDelete(parameters, tenantId);
            case "list_custom_apis" -> executeList(tenantId);
            default -> ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown action: " + toolName);
        });
    }

    private ToolExecutionResult executeRegister(Map<String, Object> parameters, String tenantId) {
        Object apiDef = parameters.get("api_definition");
        if (apiDef == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "api_definition is required. Provide the API definition as a JSON object with at least: " +
                "apiName, baseUrl, endpoints[{name, endpoint, method, description, " +
                "outputSchema: [{key, type, description}]}]. " +
                "outputSchema is REQUIRED on every endpoint - it defines the typed response shape " +
                "used by workflows and the variable picker. " +
                "Constant request headers: put them in requiredHeaders {name: value} at the top level " +
                "when they apply to every endpoint (an API version pin), or in headers {name: value} on " +
                "one endpoint. Both are sent automatically and stay hidden from the tool's parameters. " +
                "Only short literal values are kept (no spaces, at most 64 characters, no { } placeholder), " +
                "and a header is ignored when the transport owns it (Content-Type, Content-Length, Host, " +
                "Connection, Transfer-Encoding, Accept-Encoding) or when its name is the one the credential " +
                "already fills (that header depends on authType and on auth[0].apiKeyConfig - see " +
                "auth_placement in catalog(action='help', topics=['register'])). " +
                "Accept registers but is never sent: the platform sets it to application/json first, " +
                "and a declared header never overrides one that is already present. " +
                "Per-call values belong in params with location 'query', 'path', 'body' or 'header' instead.");
        }

        try {
            JsonNode apiJson = objectMapper.valueToTree(apiDef);

            ApiResponse response = registrationService.registerCustomApi(apiJson, tenantId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("apiId", response.id() != null ? response.id().toString() : "");
            result.put("apiName", response.apiName() != null ? response.apiName() : "");
            result.put("tools", buildToolSummary(response));
            result.put("message", "Custom API registered successfully");
            // An API that needs a key is NOT usable yet, and nothing used to say so: the reply was
            // a flat success, and the first sign of trouble was a 401 from the provider several
            // steps later. Name the connection the tools will look for, so the next move is
            // obvious instead of guessed.
            String credentialName = CustomApiRegistrationService.credentialNameFor(apiJson, response.apiName());
            if (credentialName != null && !credentialName.isBlank()) {
                result.put("credential_name", credentialName);
                result.put("next_step", "This API authenticates, so its tools stay unusable until a "
                        + "credential named '" + credentialName + "' holds a key for this user. Ask for one with "
                        + "credential(action='require', services=['" + credentialName + "']), then run a tool to verify.");
            }
            return ToolExecutionResult.success(result);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                "Invalid API definition: " + e.getMessage());
        } catch (Exception e) {
            log.error("Error registering custom API for tenant {}: {}", tenantId, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Failed to register API: " + e.getMessage());
        }
    }

    private ToolExecutionResult executeUpdate(Map<String, Object> parameters, String tenantId) {
        String apiId = (String) parameters.get("api_id");
        if (apiId == null || apiId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "api_id is required for update_api");
        }

        Object updates = parameters.get("api_definition");
        if (updates == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "api_definition is required for update_api. Include endpoints with " +
                "outputSchema: [{key, type, description}] on every endpoint.");
        }

        try {
            JsonNode updatesJson = objectMapper.valueToTree(updates);

            ApiResponse response = registrationService.updateCustomApi(apiId, updatesJson, tenantId);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("apiId", response.id() != null ? response.id().toString() : "");
            result.put("apiName", response.apiName() != null ? response.apiName() : "");
            result.put("tools", buildToolSummary(response));
            result.put("message", "Custom API updated successfully");
            // The warning below tells the caller to reconnect under this name after a rename, so
            // the name has to be IN the response. Referring to a field that is not returned is
            // the same dead end as naming an action that does not exist.
            String updatedCredentialName =
                    CustomApiRegistrationService.credentialNameFor(updatesJson, response.apiName());
            if (updatedCredentialName != null && !updatedCredentialName.isBlank()) {
                result.put("credential_name", updatedCredentialName);
            }
            // Conditional, because the sentence names a response FIELD: an API that needs no
            // credential has no credential_name in the result, and pointing at one that is not
            // there is the same dead end as naming an action that does not exist.
            String keptKeyNote = updatedCredentialName == null || updatedCredentialName.isBlank()
                    ? "This API needs no credential."
                    : "The stored key is kept as long as apiName and iconSlug are unchanged. Renaming "
                            + "either one changes the credential name the tools require, and the key "
                            + "stored under the old name does NOT move with it: the tools fail on their "
                            + "first call until a key is connected under credential_name '"
                            + updatedCredentialName + "'.";
            result.put("warning", "Update generates a new API ID and new tool IDs: a workflow step "
                    + "pointing at an old tool ID no longer resolves and must be re-pointed at the IDs "
                    + "in 'tools' above. " + keptKeyNote + " Changing apiName or iconSlug also moves the "
                    + "API's icon slug, which is the first segment of the node type a workflow node was "
                    + "placed with, so keep both unchanged unless you mean to re-point those nodes too.");
            return ToolExecutionResult.success(result);
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                "Invalid API update: " + e.getMessage());
        } catch (Exception e) {
            log.error("Error updating custom API {} for tenant {}: {}", apiId, tenantId, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Failed to update API: " + e.getMessage());
        }
    }

    /**
     * Build a compact {tool_id, name} summary from the {@link ApiResponse} so the LLM
     * can reference newly-created tools without a separate {@code search} round-trip.
     */
    private List<Map<String, String>> buildToolSummary(ApiResponse response) {
        if (response == null || response.tools() == null) return List.of();
        List<Map<String, String>> out = new ArrayList<>(response.tools().size());
        for (ApiResponse.ToolResponse t : response.tools()) {
            if (t == null || t.id() == null) continue;
            if (Boolean.FALSE.equals(t.isActive())) continue;
            Map<String, String> row = new LinkedHashMap<>();
            row.put("tool_id", t.id().toString());
            row.put("name", t.name() != null ? t.name() : "");
            out.add(row);
        }
        return out;
    }

    private ToolExecutionResult executeDelete(Map<String, Object> parameters, String tenantId) {
        String apiId = (String) parameters.get("api_id");
        if (apiId == null || apiId.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER,
                "api_id is required for delete_api");
        }

        try {
            registrationService.deleteCustomApi(apiId, tenantId);
            return ToolExecutionResult.success(Map.of(
                "success", true,
                "message", "Custom API deleted successfully"
            ));
        } catch (IllegalArgumentException e) {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE,
                "Cannot delete API: " + e.getMessage());
        } catch (Exception e) {
            log.error("Error deleting custom API {} for tenant {}: {}", apiId, tenantId, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Failed to delete API");
        }
    }

    private ToolExecutionResult executeList(String tenantId) {
        try {
            List<Map<String, Object>> apis = registrationService.listCustomApis(tenantId);
            return ToolExecutionResult.success(Map.of(
                "apis", apis,
                "count", apis.size()
            ));
        } catch (Exception e) {
            log.error("Error listing custom APIs for tenant {}: {}", tenantId, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED,
                "Failed to list custom APIs");
        }
    }
}
