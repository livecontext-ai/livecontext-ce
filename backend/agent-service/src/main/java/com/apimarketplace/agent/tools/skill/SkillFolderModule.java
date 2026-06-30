package com.apimarketplace.agent.tools.skill;

import com.apimarketplace.agent.domain.SkillFolderEntity;
import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.service.SkillFolderService;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.ToolModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

import static com.apimarketplace.agent.tools.common.ToolParamUtils.*;
import com.apimarketplace.agent.tools.ToolErrorCode;

/**
 * Folder management module for the skill tool - agent-service native version.
 * Uses SkillFolderService directly (no HTTP hop via AgentClient).
 * Handles: create_folder, list_folders, rename_folder, move_folder, delete_folder actions.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillFolderModule implements ToolModule {

    private final SkillFolderService skillFolderService;

    private static final Set<String> HANDLED_ACTIONS = Set.of(
        "create_folder", "list_folders", "rename_folder", "move_folder", "delete_folder"
    );

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
            case "create_folder" -> executeCreateFolder(parameters, tenantId, context);
            case "list_folders" -> executeListFolders(tenantId);
            case "rename_folder" -> executeRenameFolder(parameters, tenantId);
            case "move_folder" -> executeMoveFolder(parameters, tenantId);
            case "delete_folder" -> executeDeleteFolder(parameters, tenantId);
            default -> ToolExecutionResult.failure(ToolErrorCode.INVALID_ENUM_VALUE, "Unknown folder action: " + action);
        });
    }

    // ==================== Create Folder ====================

    private ToolExecutionResult executeCreateFolder(Map<String, Object> parameters, String tenantId, ToolExecutionContext context) {
        Map<String, Object> p = mergeParams(parameters);

        String name = getStringParam(p, "name");
        if (name == null || name.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "'name' is REQUIRED for folder creation.\n\n" +
                "EXAMPLE: skill(action='create_folder', name='Marketing')");
        }

        UUID parentId = getUuidParam(p, "parent_id");

        try {
            String orgId = context != null ? context.orgId() : null;
            SkillFolderEntity folder = skillFolderService.createFolder(tenantId, name, parentId, orgId);

            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("id", folder.getId().toString());
            resultMap.put("name", folder.getName());
            resultMap.put("parent_id", folder.getParentId() != null ? folder.getParentId().toString() : null);
            resultMap.put("status", "CREATED");
            resultMap.put("message", "Folder '" + folder.getName() + "' created successfully (ID: " + folder.getId() + ").");

            return ToolExecutionResult.success(resultMap);
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to create folder: " + e.getMessage());
        }
    }

    // ==================== List Folders ====================

    private ToolExecutionResult executeListFolders(String tenantId) {
        try {
            List<SkillFolderEntity> folders = skillFolderService.listAllFolders(tenantId);

            List<Map<String, Object>> folderList = folders.stream()
                .map(folder -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", folder.getId().toString());
                    map.put("name", folder.getName());
                    map.put("parent_id", folder.getParentId() != null ? folder.getParentId().toString() : null);
                    return map;
                }).toList();

            return ToolExecutionResult.success(Map.of(
                "folders", folderList,
                "count", folderList.size(),
                "status", "OK",
                "message", "Found " + folderList.size() + " folder(s)."
            ));
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to list folders: " + e.getMessage());
        }
    }

    // ==================== Rename Folder ====================

    private ToolExecutionResult executeRenameFolder(Map<String, Object> parameters, String tenantId) {
        Map<String, Object> p = mergeParams(parameters);

        UUID folderId = getUuidParam(p, "folder_id");
        if (folderId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "'folder_id' is REQUIRED for rename_folder.\n" +
                "Use skill(action='list_folders') to find folder IDs.");
        }

        String name = getStringParam(p, "name");
        if (name == null || name.isBlank()) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "'name' is REQUIRED for rename_folder.\n\n" +
                "EXAMPLE: skill(action='rename_folder', folder_id='<uuid>', name='New Name')");
        }

        try {
            SkillFolderEntity folder = skillFolderService.renameFolder(folderId, tenantId, name);

            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("id", folder.getId().toString());
            resultMap.put("name", folder.getName());
            resultMap.put("status", "RENAMED");
            resultMap.put("message", "Folder renamed to '" + folder.getName() + "' successfully.");

            return ToolExecutionResult.success(resultMap);
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to rename folder: " + e.getMessage());
        }
    }

    // ==================== Move Folder ====================

    private ToolExecutionResult executeMoveFolder(Map<String, Object> parameters, String tenantId) {
        Map<String, Object> p = mergeParams(parameters);

        UUID folderId = getUuidParam(p, "folder_id");
        if (folderId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "'folder_id' is REQUIRED for move_folder.\n" +
                "Use skill(action='list_folders') to find folder IDs.");
        }

        // parent_id: UUID for target folder, "root" or absent/null for root
        UUID newParentId = resolveNullableFolderId(p, "parent_id");

        try {
            SkillFolderEntity folder = skillFolderService.moveFolder(folderId, tenantId, newParentId);

            Map<String, Object> resultMap = new LinkedHashMap<>();
            resultMap.put("id", folder.getId().toString());
            resultMap.put("name", folder.getName());
            resultMap.put("parent_id", folder.getParentId() != null ? folder.getParentId().toString() : null);
            resultMap.put("status", "MOVED");
            resultMap.put("message", newParentId == null
                ? "Folder '" + folder.getName() + "' moved to root."
                : "Folder '" + folder.getName() + "' moved under folder " + newParentId + ".");

            return ToolExecutionResult.success(resultMap);
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to move folder: " + e.getMessage());
        }
    }

    // ==================== Delete Folder ====================

    private ToolExecutionResult executeDeleteFolder(Map<String, Object> parameters, String tenantId) {
        Map<String, Object> p = mergeParams(parameters);

        UUID folderId = getUuidParam(p, "folder_id");
        if (folderId == null) {
            return ToolExecutionResult.failure(ToolErrorCode.MISSING_PARAMETER, "'folder_id' is REQUIRED for folder deletion.\n" +
                "Use skill(action='list_folders') to find folder IDs.");
        }

        try {
            skillFolderService.deleteFolder(folderId, tenantId);
            return ToolExecutionResult.success(Map.of(
                "id", folderId.toString(),
                "status", "DELETED",
                "message", "Folder deleted successfully. Skills in this folder have been moved to root."
            ));
        } catch (Exception e) {
            return ToolExecutionResult.failure(ToolErrorCode.EXECUTION_FAILED, "Failed to delete folder: " + e.getMessage());
        }
    }

    // ==================== Helpers ====================

    /**
     * Resolve a folder ID param that can be a UUID, "root"/"none"/null (meaning root).
     * Returns null for root, UUID otherwise.
     */
    static UUID resolveNullableFolderId(Map<String, Object> params, String key) {
        Object val = params.get(key);
        if (val == null) return null;
        if (val instanceof String str) {
            String trimmed = str.trim().toLowerCase();
            if (trimmed.isEmpty() || "root".equals(trimmed) || "none".equals(trimmed) || "null".equals(trimmed)) {
                return null;
            }
            try {
                return UUID.fromString(str.trim());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }
}
