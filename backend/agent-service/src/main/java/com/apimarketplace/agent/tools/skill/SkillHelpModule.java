package com.apimarketplace.agent.tools.skill;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.ToolModule;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Help module for the skill tool.
 * Provides documentation, parameter details, and examples.
 */
@Component
public class SkillHelpModule implements ToolModule {

    @Override
    public List<AgentToolDefinition> getToolDefinitions() {
        return List.of();
    }

    @Override
    public boolean canHandle(String action) {
        return "help".equals(action);
    }

    @Override
    public Optional<ToolExecutionResult> execute(String action, Map<String, Object> parameters,
                                                  String tenantId, ToolExecutionContext context) {
        if (!canHandle(action)) return Optional.empty();
        return Optional.of(executeHelp());
    }

    private ToolExecutionResult executeHelp() {
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("description", """
            SKILL TOOL - Create and manage reusable skill packages for AI agents.

            Skills are reusable instruction sets that agents can activate on demand via discover_skill.
            Only the skill description is loaded into the agent's system prompt (saving tokens).
            Full instructions are fetched when the agent activates the skill.

            WORKFLOW:
            1. Create a skill with name, description, and instructions
            2. Assign the skill to one or more agents
            3. The agent sees the skill description in its system prompt
            4. When the agent activates the skill, full instructions are loaded
            """);

        // Skill actions
        Map<String, String> skillActions = new LinkedHashMap<>();
        skillActions.put("create", "Create a new skill (name + description + instructions required). Optional: folder_id to place in a folder.");
        skillActions.put("get", "Get skill details by skill_id");
        skillActions.put("list", "List all skills with pagination (limit, offset)");
        skillActions.put("update", "Update skill fields by skill_id. Also moves skill if folder_id is provided (use 'root' to move to root).");
        skillActions.put("delete", "Delete a skill by skill_id");
        skillActions.put("assign", "Add skills to an agent (additive - existing skills are kept). Full contract on the skill_ids parameter below.");
        skillActions.put("publish",
            "Add the skill to the marketplace. Params: skill_id (required), title (required), " +
            "interface_id (REQUIRED - UUID of the landing interface shown to acquirers), " +
            "visibility ('PRIVATE' default, 'PUBLIC', 'UNLISTED'), credits_per_use (default 0). " +
            "PUBLIC listings go through platform review; PRIVATE/UNLISTED activate immediately.");
        skillActions.put("unpublish",
            "Mark the skill's marketplace listing inactive. Params: skill_id (required). " +
            "Existing acquirers keep their copies - only new installs are blocked.");
        result.put("skill_actions", skillActions);

        // Folder actions
        Map<String, String> folderActions = new LinkedHashMap<>();
        folderActions.put("create_folder", "Create a folder. Params: name (required), parent_id (optional, for nesting).");
        folderActions.put("list_folders", "List all folders. Returns flat list with id, name, parent_id.");
        folderActions.put("rename_folder", "Rename a folder. Params: folder_id (required), name (required).");
        folderActions.put("move_folder", "Move a folder. Params: folder_id (required), parent_id (UUID or 'root').");
        folderActions.put("delete_folder", "Delete a folder. Params: folder_id (required). Skills inside move to root.");
        result.put("folder_actions", folderActions);

        result.put("other_actions", Map.of("help", "Show this documentation"));

        result.put("parameters", buildParameterDocs());
        result.put("skill_examples", buildSkillExamples());
        result.put("folder_examples", buildFolderExamples());

        result.put("skill_tips", List.of(
            "Skills are REUSABLE - create once, assign to multiple agents",
            "Keep 'description' short (it goes into the agent's system prompt); put the detailed steps in 'instructions' (markdown, loaded only when the skill is activated)",
            "Use skill(action='list') to find existing skills before creating duplicates",
            "ASSIGN: pass ALL skill IDs in ONE call - it is additive (existing skills kept, already-assigned skipped), max 10 per agent",
            "To find agent IDs: agent(action='list')",
            "You can also assign skills during agent creation: agent(action='create', ..., skill_ids=['uuid1', 'uuid2'])"
        ));

        result.put("folder_tips", List.of(
            "Create skills in folder: skill(action='create', ..., folder_id='<uuid>')",
            "Move skill: skill(action='update', skill_id='...', folder_id='<uuid>' or 'root')",
            "Deleting a folder moves its skills to root - skills are NOT deleted"
        ));

        return ToolExecutionResult.success(result);
    }

    private Map<String, Object> buildParameterDocs() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", Map.of(
            "type", "string", "required", true,
            "description", "Name for skill or folder",
            "for_actions", "create, update, create_folder, rename_folder"
        ));
        params.put("description", Map.of(
            "type", "string", "required", true,
            "description", "Short description of what the skill does. Injected into the agent's system prompt. Keep it concise.",
            "for_actions", "create, update"
        ));
        params.put("instructions", Map.of(
            "type", "string", "required", true,
            "description", "Full detailed instructions in markdown format. Fetched on demand when the agent activates the skill via discover_skill. Can be lengthy and detailed.",
            "for_actions", "create, update"
        ));
        params.put("icon", Map.of(
            "type", "string", "required", false,
            "description", "Optional icon identifier for the skill",
            "for_actions", "create, update"
        ));
        params.put("folder_id", Map.of(
            "type", "string (UUID or 'root')", "required", false,
            "description", "Target folder ID. For create/update: place/move skill into folder (use 'root' to move to root). For rename_folder/move_folder/delete_folder: the folder to act on.",
            "for_actions", "create, update, rename_folder, move_folder, delete_folder"
        ));
        params.put("parent_id", Map.of(
            "type", "string (UUID or 'root')", "required", false,
            "description", "Parent folder ID. For create_folder: nesting. For move_folder: new parent (use 'root' or omit for root).",
            "for_actions", "create_folder, move_folder"
        ));
        params.put("skill_id", Map.of(
            "type", "string (UUID)", "required", "depends on action",
            "description", "Skill ID - required for get, update, delete actions",
            "for_actions", "get, update, delete"
        ));
        params.put("is_active", Map.of(
            "type", "boolean", "required", false, "default", true,
            "description", "If false, skill is disabled",
            "for_actions", "update"
        ));
        params.put("agent_id", Map.of(
            "type", "string (UUID)", "required", "required for assign",
            "description", "Target agent ID to assign skills to",
            "for_actions", "assign"
        ));
        params.put("skill_ids", Map.of(
            "type", "array of UUIDs", "required", "required for assign",
            "description", "List of skill UUIDs to add to the agent. ADDITIVE: does NOT remove existing skills. Already assigned skills are skipped. Pass ALL skill IDs at once (not one by one). Max 10 skills per agent.",
            "for_actions", "assign",
            "example", List.of("skill-uuid-1", "skill-uuid-2", "skill-uuid-3")
        ));
        params.put("limit", Map.of(
            "type", "integer", "required", false, "default", 25,
            "description", "Max results to return",
            "for_actions", "list"
        ));
        params.put("offset", Map.of(
            "type", "integer", "required", false, "default", 0,
            "description", "Pagination offset",
            "for_actions", "list"
        ));
        return params;
    }

    private Map<String, Object> buildSkillExamples() {
        Map<String, Object> examples = new LinkedHashMap<>();
        examples.put("create", Map.of(
            "description", "Create a skill",
            "call", """
                skill(action='create', params={
                  name: 'Email Classifier',
                  description: 'Classifies incoming emails by priority and drafts replies.',
                  instructions: '## Email Classification\\n1. Read the email subject and body\\n2. Classify priority: HIGH, MEDIUM, LOW\\n3. Draft a reply based on the classification'
                })"""
        ));
        examples.put("create_in_folder", Map.of(
            "description", "Create a skill inside a folder",
            "call", "skill(action='create', name='SEO Analyzer', description='Analyzes SEO.', instructions='## Steps\\n...', folder_id='<folder-uuid>')"
        ));
        examples.put("update", Map.of(
            "description", "Update skill instructions",
            "call", "skill(action='update', skill_id='<uuid>', instructions='## Updated\\n1. New step')"
        ));
        examples.put("move_to_folder", Map.of(
            "description", "Move an existing skill to a folder",
            "call", "skill(action='update', skill_id='<uuid>', folder_id='<folder-uuid>')"
        ));
        examples.put("move_to_root", Map.of(
            "description", "Move a skill back to root (out of any folder)",
            "call", "skill(action='update', skill_id='<uuid>', folder_id='root')"
        ));
        examples.put("assign", Map.of(
            "description", "Add skills to an agent (pass ALL at once)",
            "call", "skill(action='assign', agent_id='<agent-uuid>', skill_ids=['<skill-1>', '<skill-2>'])"
        ));
        examples.put("full_workflow", Map.of(
            "description", "Create folder + skills + assign to agent",
            "steps", List.of(
                "1. skill(action='create_folder', name='Marketing')",
                "2. skill(action='create', name='SEO', description='...', instructions='...', folder_id='<folder-id>')",
                "3. skill(action='create', name='Ads', description='...', instructions='...', folder_id='<folder-id>')",
                "4. skill(action='assign', agent_id='<agent-uuid>', skill_ids=['<seo-id>', '<ads-id>'])"
            )
        ));
        return examples;
    }

    private Map<String, Object> buildFolderExamples() {
        Map<String, Object> examples = new LinkedHashMap<>();
        examples.put("create", "skill(action='create_folder', name='Marketing')");
        examples.put("create_nested", "skill(action='create_folder', name='SEO', parent_id='<parent-uuid>')");
        examples.put("list", "skill(action='list_folders')");
        examples.put("move", "skill(action='move_folder', folder_id='<uuid>', parent_id='<new-parent>' or 'root')");
        examples.put("delete", "skill(action='delete_folder', folder_id='<uuid>') - skills move to root");
        return examples;
    }
}
