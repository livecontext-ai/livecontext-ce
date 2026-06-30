package com.apimarketplace.agent.tools.skill;

import com.apimarketplace.agent.config.AgentDefaultsConfig;
import com.apimarketplace.agent.domain.AgentEntity;
import com.apimarketplace.agent.domain.SkillEntity;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.SkillService;
import com.apimarketplace.agent.skills.DefaultSkillsProvider;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.AgentListEnvelope;
import com.apimarketplace.agent.tools.common.ToolModule;
import com.apimarketplace.agent.tools.common.ToolRateLimiter;
import com.apimarketplace.common.web.AdminRoleGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.apimarketplace.agent.tools.common.ToolParamUtils.*;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * CRUD module for the skill tool - agent-service native version.
 * Uses SkillService directly (no HTTP hop via AgentClient).
 * Handles: create, get, list, update, delete, assign actions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillCrudModule implements ToolModule {

    private final SkillService skillService;
    private final AgentService agentService;
    private final AgentDefaultsConfig agentDefaults;

    private static final int MAX_CONSECUTIVE_UPDATES = 3;
    private final ToolRateLimiter updateLimiter = new ToolRateLimiter();
    private final ToolRateLimiter createLimiter = new ToolRateLimiter();

    /**
     * Resolves the per-turn skill-create cap via {@link com.apimarketplace.agent.config.GuardOverrides#resolve}:
     * caller-agent override (V100 column) → conversation-scope chatConfig
     * (via {@code __chatMaxPerResourcePerTurn__} credential) → YAML default. The same
     * cap is shared uniformly across every tracked resource type and counted
     * separately per type. Soft-fail on lookup error (same policy as
     * {@code AgentCrudModule.resolveMaxPerResourcePerTurn}).
     */
    int resolveMaxPerResourcePerTurn(ToolExecutionContext context) {
        int fallback = agentDefaults.getMaxPerResourcePerTurn();
        Map<String, Object> credentials = context != null ? context.credentials() : null;
        Integer agentOverride = null;
        if (credentials != null) {
            Object raw = credentials.get("__agentId__");
            if (raw != null) {
                try {
                    UUID callerId = UUID.fromString(raw.toString());
                    Optional<AgentEntity> entityOpt = agentService.findById(callerId);
                    agentOverride = entityOpt.map(AgentEntity::getMaxPerResourcePerTurn).orElse(null);
                } catch (Exception e) {
                    log.debug("[SKILL_CREATE] Failed to resolve per-agent maxPerResourcePerTurn, falling back: {}", e.toString());
                }
            }
        }
        return com.apimarketplace.agent.config.GuardOverrides.resolve(
            agentOverride, credentials,
            com.apimarketplace.agent.config.GuardOverrides.CRED_MAX_PER_RESOURCE_PER_TURN,
            fallback);
    }

    private static final Set<String> HANDLED_ACTIONS = Set.of("create", "get", "list", "update", "delete", "assign");

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

        // Access mode check (read/write)
        var accessDenied = com.apimarketplace.agent.config.ToolAccessControl.checkWriteAccess(
                context != null ? context.credentials() : null, "skill", action);
        if (accessDenied.isPresent()) return Optional.of(ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, accessDenied.get()));

        return Optional.of(switch (action) {
            case "create" -> executeCreate(parameters, tenantId, context);
            case "get" -> executeGet(parameters, tenantId);
            case "list" -> executeList(parameters, tenantId);
            case "update" -> executeUpdate(parameters, tenantId, context);
            case "delete" -> executeDelete(parameters, tenantId, context);
            case "assign" -> executeAssign(parameters, tenantId);
            default -> ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "Unknown action: " + action);
        });
    }

    // ==================== Create ====================

    private ToolExecutionResult executeCreate(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);

        String name = getStringParam(p, "name");
        String description = getStringParam(p, "description");
        String instructions = getStringParam(p, "instructions");
        String icon = getStringParam(p, "icon");

        if (name == null || name.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "'name' is REQUIRED for skill creation.\n\n" +
                "Use skill(action='help') to see all available parameters.");
        }
        if (description == null || description.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "'description' is REQUIRED for skill creation.\n" +
                "This description is injected into the agent's system prompt.\n\n" +
                "EXAMPLE:\n" +
                "skill(action='create', params={\n" +
                "  name: 'Email Classifier',\n" +
                "  description: 'Classifies incoming emails by priority and drafts replies.',\n" +
                "  instructions: '## Email Classification\\n...'\n" +
                "})");
        }
        if (instructions == null || instructions.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "'instructions' is REQUIRED for skill creation.\n" +
                "Full detailed instructions returned when the agent activates this skill.\n\n" +
                "EXAMPLE:\n" +
                "skill(action='create', params={\n" +
                "  name: 'Email Classifier',\n" +
                "  description: 'Classifies emails by priority.',\n" +
                "  instructions: '## Steps\\n1. Read the email\\n2. Classify priority...'\n" +
                "})");
        }

        // Check creation limit per message (turnId = unique per user message).
        // Cap comes from caller-agent override → AgentDefaultsConfig.maxPerResourcePerTurn fallback.
        String turnId = context != null ? getTurnId(context.credentials()) : null;
        if (turnId != null) {
            int maxCreates = resolveMaxPerResourcePerTurn(context);
            String createKey = tenantId + ":skill:" + turnId;
            var limitResult = createLimiter.checkLimit(createKey, maxCreates,
                "LIMIT REACHED: You have already created " + maxCreates + " skills for this message.\n\n" +
                "Use skill(action='list') to see existing skills.");
            if (limitResult.isPresent()) return limitResult.get();
        }

        UUID folderId = getUuidParam(p, "folder_id");

        try {
            // Direct service call - no HTTP hop. Org-aware: stamp organization_id
            // from MCP context so org-teammates see the skill. Audit 2026-05-16.
            String orgId = context != null ? context.orgId() : null;
            SkillEntity result = skillService.createSkill(tenantId, name, description, icon, instructions, folderId, false, false, orgId);

            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("id", result.getId().toString());
            resultMap.put("name", result.getName());
            if (folderId != null) {
                resultMap.put("folder_id", folderId.toString());
            }
            resultMap.put("status", "CREATED");
            resultMap.put("message", "Skill '" + result.getName() + "' created successfully (ID: " + result.getId() + ").");
            resultMap.put("next_step", "To assign this skill to an agent: skill(action='assign', agent_id='<agent-uuid>', skill_ids=['" + result.getId() + "'])");

            return ToolExecutionResult.success(resultMap);
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to create skill: " + e.getMessage());
        }
    }

    // ==================== Get ====================

    private static final String SKILL_ID_ERROR = """
        skill_id is required and must be a valid UUID.
        - To LIST existing skills: skill(action='list')
        - To CREATE new skill: skill(action='create', name='...', description='...', instructions='...')
        """;

    private ToolExecutionResult executeGet(Map<String, Object> parameters, String tenantId) {
        Map<String, Object> p = mergeParams(parameters);

        // Handle default skills (id starts with "default:")
        String skillIdStr = getStringParam(p, "skill_id");
        if (skillIdStr != null && DefaultSkillsProvider.isDefaultSkillId(skillIdStr)) {
            return DefaultSkillsProvider.getById(skillIdStr)
                .map(skill -> {
                    Map<String, Object> skillMap = new LinkedHashMap<>();
                    skillMap.put("id", skill.id());
                    skillMap.put("name", skill.name());
                    skillMap.put("description", skill.description());
                    skillMap.put("instructions", skill.instructions());
                    skillMap.put("icon", skill.icon());
                    skillMap.put("is_default", true);
                    return ToolExecutionResult.success(skillMap, Map.of("label", skill.name()));
                })
                .orElse(ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Default skill not found: " + skillIdStr));
        }

        UUID id = getUuidParam(p, "skill_id");
        if (id == null) return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, SKILL_ID_ERROR);

        try {
            Optional<SkillEntity> opt = skillService.getSkill(id, tenantId);
            if (opt.isEmpty()) return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Skill not found: " + id);
            SkillEntity entity = opt.get();

            Map<String, Object> skillMap = new LinkedHashMap<>();
            skillMap.put("id", entity.getId().toString());
            skillMap.put("name", entity.getName());
            skillMap.put("description", entity.getDescription());
            skillMap.put("instructions", entity.getInstructions());
            skillMap.put("icon", entity.getIcon());
            skillMap.put("is_active", entity.getIsActive());

            return ToolExecutionResult.success(skillMap, Map.of("label", entity.getName()));
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to get skill: " + e.getMessage());
        }
    }

    // ==================== List ====================

    private ToolExecutionResult executeList(Map<String, Object> parameters, String tenantId) {
        Map<String, Object> p = mergeParams(parameters);
        // skill.list = STANDARD caps. No general filter (the set is default skills +
        // tenant skills) - empty suggestedFilters suppresses the `refine` hint.
        AgentListEnvelope.Spec spec = AgentListEnvelope.Spec.of(
                        AgentListEnvelope.Caps.STANDARD, "skills", "skills", "skills")
                .withSuggestedFilters(List.of());
        AgentListEnvelope.Bounds bounds;
        try {
            bounds = AgentListEnvelope.readBounds(p, spec, Set.of());
        } catch (AgentListEnvelope.InvalidParamsException e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, e.code + ": " + e.getMessage());
        }

        try {
            // Build combined list: default skills first, then user skills
            List<Map<String, Object>> defaultSkillSummaries = DefaultSkillsProvider.getAll().stream()
                .map(skill -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", skill.id());
                    map.put("name", skill.name());
                    map.put("description", skill.description());
                    map.put("is_default", true);
                    return map;
                }).toList();

            List<SkillEntity> userSkills = skillService.listSkills(tenantId);
            List<Map<String, Object>> userSkillSummaries = userSkills.stream()
                .map(skill -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", skill.getId().toString());
                    map.put("name", skill.getName());
                    map.put("description", skill.getDescription());
                    map.put("is_active", skill.getIsActive());
                    return map;
                }).toList();

            List<Map<String, Object>> allSummaries = new ArrayList<>();
            allSummaries.addAll(defaultSkillSummaries);
            allSummaries.addAll(userSkillSummaries);

            return ToolExecutionResult.success(
                    AgentListEnvelope.paginateInMemory(allSummaries, bounds, spec));
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to list skills: " + e.getMessage());
        }
    }

    // ==================== Update ====================

    /**
     * Resolves the caller's platform-admin status from the tool context.
     * The gateway injects the caller's roles (X-User-Roles) which
     * {@code AgentToolsController} stores in credentials under
     * {@code __userRoles__}. Global (admin-managed) skills can only be
     * edited/deleted by admins; without this, the MCP tool path always acted
     * as a non-admin and blocked admins from editing global skills.
     */
    private boolean callerIsAdmin(ToolExecutionContext context) {
        if (context == null || context.credentials() == null) return false;
        Object roles = context.credentials().get("__userRoles__");
        return roles instanceof String s && AdminRoleGuard.isAdmin(s);
    }

    private ToolExecutionResult executeUpdate(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);
        UUID id = getUuidParam(p, "skill_id");
        if (id == null) return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, SKILL_ID_ERROR);

        String updateKey = tenantId + ":skill:" + id;
        var limitResult = updateLimiter.checkLimit(updateKey, MAX_CONSECUTIVE_UPDATES,
            "STOP: You have updated this skill " + MAX_CONSECUTIVE_UPDATES + " times already. " +
            "Ask the user if they want further changes.");
        if (limitResult.isPresent()) return limitResult.get();

        String name = getStringParam(p, "name");
        String description = getStringParam(p, "description");
        String instructions = getStringParam(p, "instructions");
        String icon = getStringParam(p, "icon");
        Boolean isActive = (Boolean) p.get("is_active");

        // folder_id: UUID to move to folder, "root"/"none" to move to root, absent = no move
        boolean hasFolderParam = p.containsKey("folder_id");
        UUID folderId = hasFolderParam
            ? SkillFolderModule.resolveNullableFolderId(p, "folder_id")
            : null;

        try {
            // Direct service call - no HTTP hop. Pass the caller's admin status so
            // an admin can edit global (admin-managed) skills via the tool path;
            // isGlobal=null leaves global visibility unchanged.
            SkillEntity result = skillService.updateSkill(id, tenantId, name, description, icon, instructions,
                    isActive, null, callerIsAdmin(context));

            // Move skill if folder_id param was explicitly provided
            if (hasFolderParam) {
                skillService.moveSkill(id, tenantId, folderId);
            }

            Map<String, Object> responseMap = new LinkedHashMap<>();
            responseMap.put("id", result.getId().toString());
            responseMap.put("name", result.getName());
            if (hasFolderParam) {
                responseMap.put("folder_id", folderId != null ? folderId.toString() : null);
            }
            responseMap.put("status", "UPDATED");
            String moveMsg = hasFolderParam
                ? (folderId != null ? " Moved to folder " + folderId + "." : " Moved to root.")
                : "";
            responseMap.put("message", "Skill '" + result.getName() + "' updated successfully." + moveMsg);

            return ToolExecutionResult.success(responseMap, Map.of("label", result.getName()));
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to update skill: " + e.getMessage());
        }
    }

    // ==================== Delete ====================

    private ToolExecutionResult executeDelete(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);
        UUID id = getUuidParam(p, "skill_id");
        if (id == null) return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, SKILL_ID_ERROR);

        try {
            Optional<SkillEntity> existingOpt = skillService.getSkill(id, tenantId);
            if (existingOpt.isEmpty()) {
                return ToolExecutionResult.failure(ToolErrorCode.RESOURCE_NOT_FOUND, "Skill not found: " + id);
            }
            SkillEntity existing = existingOpt.get();

            // Protect default skills from deletion
            if (existing.getDefaultKey() != null) {
                return ToolExecutionResult.failure(ToolErrorCode.PERMISSION_DENIED, "Cannot delete default skill '" + existing.getName() + "' (key: " + existing.getDefaultKey() + ").\n" +
                    "Default skills are editable but not deletable. Use update to modify its content.");
            }

            // Pass the caller's admin status so an admin can delete global
            // (admin-managed) skills via the tool path.
            skillService.deleteSkill(id, tenantId, callerIsAdmin(context));
            return ToolExecutionResult.success(Map.of(
                "id", id.toString(), "name", existing.getName(), "status", "DELETED",
                "message", "Skill '" + existing.getName() + "' deleted successfully."
            ), Map.of("label", existing.getName()));
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to delete skill: " + e.getMessage());
        }
    }

    // ==================== Assign ====================

    @SuppressWarnings("unchecked")
    private ToolExecutionResult executeAssign(Map<String, Object> parameters, String tenantId) {
        Map<String, Object> p = mergeParams(parameters);
        UUID agentId = getUuidParam(p, "agent_id");
        if (agentId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "agent_id is required for assign action.\n" +
                "Use agent(action='list') to find agent IDs.");
        }

        Object skillIdsObj = p.get("skill_ids");
        if (skillIdsObj == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "skill_ids is required for assign action.\n" +
                "Use skill(action='list') to find skill IDs.\n\n" +
                "IMPORTANT: Pass ALL skill IDs in ONE call (assign is ADDITIVE - does NOT remove existing skills).\n\n" +
                "EXAMPLE: skill(action='assign', agent_id='<uuid>', skill_ids=['<skill-uuid-1>', '<skill-uuid-2>', '<skill-uuid-3>'])");
        }

        List<SkillService.SkillAssignment> assignments = new ArrayList<>();
        if (skillIdsObj instanceof List<?> list) {
            for (Object item : list) {
                String skillIdStr = item instanceof String s ? s : item.toString();
                try {
                    UUID skillId = UUID.fromString(skillIdStr);
                    assignments.add(new SkillService.SkillAssignment(skillId));
                } catch (IllegalArgumentException e) {
                    return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "Invalid skill UUID: " + skillIdStr);
                }
            }
        } else {
            return ToolExecutionResult.failure(ToolErrorCode.INVALID_PARAMETER_VALUE, "skill_ids must be an array of UUIDs.");
        }

        try {
            // Direct service call - no HTTP hop
            int added = skillService.addAgentSkills(agentId, tenantId, assignments);

            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("agent_id", agentId.toString());
            resultMap.put("skills_added", added);
            resultMap.put("skills_requested", assignments.size());
            resultMap.put("status", "ASSIGNED");
            if (added < assignments.size()) {
                int skipped = assignments.size() - added;
                resultMap.put("message", added + " skill(s) added to agent " + agentId +
                    " (" + skipped + " already assigned, skipped).");
            } else {
                resultMap.put("message", added + " skill(s) added to agent " + agentId + ".");
            }

            return ToolExecutionResult.success(resultMap);
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to assign skills: " + e.getMessage());
        }
    }
}
