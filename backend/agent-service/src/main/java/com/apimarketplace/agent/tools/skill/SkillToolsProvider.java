package com.apimarketplace.agent.tools.skill;

import com.apimarketplace.agent.domain.ToolParameter;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolsProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.apimarketplace.agent.registry.ToolSchemaGenerator.*;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Provider for unified skill tool - agent-service native version.
 * Eliminates the orchestrator double-hop for all skill and folder operations.
 *
 * Delegates to specialized modules:
 * - SkillCrudModule: create, get, list, update, delete, assign (direct service calls)
 * - SkillFolderModule: create_folder, list_folders, rename_folder, move_folder, delete_folder
 * - SkillHelpModule: help
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillToolsProvider implements ToolsProvider {

    private final SkillCrudModule crudModule;
    private final SkillHelpModule helpModule;
    private final SkillFolderModule folderModule;
    private final SkillPublishModule publishModule;

    private static final List<String> VALID_ACTIONS = List.of(
        "create", "get", "list", "update", "delete", "assign",
        "create_folder", "list_folders", "rename_folder", "move_folder", "delete_folder",
        // Marketplace publication lifecycle
        "publish", "unpublish",
        "help"
    );

    @Override
    public ToolCategory getCategory() {
        return ToolCategory.AGENT;
    }

    @Override
    public List<AgentToolDefinition> getTools() {
        return List.of(buildUnifiedSkillTool());
    }

    @Override
    public ToolExecutionResult execute(String toolName, Map<String, Object> parameters, ToolExecutionContext context) {
        if (!"skill".equals(toolName)) {
            return ToolExecutionResult.failure(ToolErrorCode.TOOL_NOT_FOUND, "Unknown tool: " + toolName);
        }

        String action = (String) parameters.get("action");
        if (action == null || action.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "action is required. Valid actions: " + String.join(", ", VALID_ACTIONS));
        }

        try {
            String tenantId = context.tenantId();
            if (tenantId == null && !"help".equals(action)) {
                return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "tenantId is required");
            }

            if (crudModule.canHandle(action)) {
                return crudModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "CRUD module failed for action: " + action));
            }

            if (folderModule.canHandle(action)) {
                return folderModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Folder module failed for action: " + action));
            }

            if (helpModule.canHandle(action)) {
                return helpModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Help module failed"));
            }

            if (publishModule.canHandle(action)) {
                return publishModule.execute(action, parameters, tenantId, context)
                    .orElse(ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Publish module failed for action: " + action));
            }

            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Invalid action: " + action + ". Valid actions: " + String.join(", ", VALID_ACTIONS));

        } catch (Exception e) {
            log.error("Error executing skill action {}: {}", action, e.getMessage(), e);
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Error: " + e.getMessage());
        }
    }

    // ==================== Unified Tool Definition ====================

    private AgentToolDefinition buildUnifiedSkillTool() {
        List<ToolParameter> params = List.of(
            ToolParameter.builder()
                .name("action")
                .type("string")
                .description("Action to perform: create, get, list, update, delete, assign, create_folder, list_folders, rename_folder, move_folder, delete_folder, publish, unpublish, help")
                .required(true)
                .enumValues(VALID_ACTIONS)
                .build(),
            stringParam("skill_id", "Skill ID - UUID (for: get, update, delete)", false),
            stringParam("name", "Name for skill or folder (for: create, update, create_folder, rename_folder)", false),
            stringParam("description", "Short summary (1-2 sentences) - always loaded into agent system prompt. Keep concise to save tokens. (for: create, update)", false),
            stringParam("instructions", "Full detailed instructions in markdown - NOT loaded into system prompt, only fetched when agent calls discover_skill on demand. Can be long and detailed. (for: create, update)", false),
            stringParam("icon", "Optional icon identifier (for: create, update)", false),
            stringParam("folder_id", "Folder ID - UUID or 'root'. For create/update: place/move skill in folder ('root' to move to root). For rename_folder/move_folder/delete_folder: target folder.", false),
            stringParam("parent_id", "Parent folder ID - UUID or 'root'. For create_folder: nesting. For move_folder: new parent ('root' or omit for root).", false),
            stringParam("agent_id", "Agent ID - UUID (for: assign)", false),
            ToolParameter.builder()
                .name("skill_ids")
                .type("array")
                .description("List of skill UUIDs to assign to an agent (for: assign). ADDITIVE: adds without removing existing. Pass ALL skill IDs at once. Max 10 skills per agent.")
                .build(),
            intParam("limit", "Max results to return (for: list)", false, 25),
            intParam("offset", "Pagination offset (for: list)", false, 0),

            // ==================== Marketplace publication (publish, unpublish) ====================
            stringParam("title", "Marketplace listing title - REQUIRED for publish", false),
            stringParam("interface_id", "Landing interface UUID - REQUIRED for publish (the public-facing page presented to acquirers before they install the skill).", false),
            enumParam("visibility", "Marketplace visibility: 'PRIVATE' (default), 'PUBLIC', 'UNLISTED' (for: publish)", false,
                List.of("PRIVATE", "PUBLIC", "UNLISTED")),
            intParam("credits_per_use", "Credits charged to acquirers per use. Default 0 (free). (for: publish)", false, 0)
        );

        return AgentToolDefinition.builder()
            .name("skill")
            .description("""
                Reusable instruction sets for AI agents. 'description' (short) goes into the agent's system prompt; 'instructions' (detailed markdown) are fetched on demand when the agent activates the skill.
                Marketplace: publish requires title + interface_id (landing page). unpublish marks the listing inactive - acquirers keep their copies.
                Call skill(action='help') for full documentation and examples.
                """)
            .category(ToolCategory.AGENT)
            .parameters(params)
            .requiredParameters(List.of("action"))
            .inputSchema(generateInputSchema(params, List.of("action")))
            .helpText("Call skill(action='help') for full documentation.")
            .requiresAuth(true)
            .tags(List.of("skill", "crud", "unified", "agent"))
            .build();
    }
}
