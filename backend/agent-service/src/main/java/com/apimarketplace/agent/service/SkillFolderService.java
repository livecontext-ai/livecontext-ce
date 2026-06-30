package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.SkillEntity;
import com.apimarketplace.agent.domain.SkillFolderEntity;
import com.apimarketplace.agent.repository.SkillFolderRepository;
import com.apimarketplace.agent.repository.SkillRepository;
import com.apimarketplace.common.web.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Service for managing skill folders (hierarchical organization).
 */
@Service
@Transactional
public class SkillFolderService {

    private static final Logger log = LoggerFactory.getLogger(SkillFolderService.class);

    private final SkillFolderRepository folderRepository;
    private final SkillRepository skillRepository;

    public SkillFolderService(SkillFolderRepository folderRepository,
                              SkillRepository skillRepository) {
        this.folderRepository = folderRepository;
        this.skillRepository = skillRepository;
    }

    public SkillFolderEntity createFolder(String tenantId, String name, UUID parentId) {
        // Audit 2026-05-17 round-6 - pull X-Organization-ID from request so
        // REST callers stamp V217 column automatically.
        return createFolder(tenantId, name, parentId, TenantResolver.currentRequestOrganizationId());
    }

    /**
     * Org-aware overload - stamps the folder's {@code organization_id} (V217
     * column) so org-teammates see it. Audit 2026-05-16 - without this,
     * folders created via MCP `skill(action='create_folder')` land NULL-org.
     *
     * <p>Phase 6c (2026-05-19) - parent-folder gate now routes through
     * {@link #findInScope} so an org-teammate can create a subfolder under
     * an org-shared parent (whose {@code tenant_id} is the owner's, not the
     * teammate's). Without this the prior {@code tenantId.equals(...)}
     * predicate rejected legitimate org-teammate subfolder creation.
     */
    public SkillFolderEntity createFolder(String tenantId, String name, UUID parentId, String organizationId) {
        validateFolderName(name);

        if (parentId != null) {
            findInScope(parentId, tenantId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Parent folder not found: " + parentId));
        }

        SkillFolderEntity folder = new SkillFolderEntity(tenantId, name.trim(), parentId);
        if (organizationId != null && !organizationId.isBlank()) {
            folder.setOrganizationId(organizationId);
        }
        SkillFolderEntity saved = folderRepository.save(folder);
        log.info("Skill folder created: id={}, name={}, parentId={}, tenant={}, org={}",
            saved.getId(), name, parentId, tenantId, organizationId);
        return saved;
    }

    public SkillFolderEntity renameFolder(UUID id, String tenantId, String newName) {
        return renameFolder(id, tenantId, TenantResolver.currentRequestOrganizationId(), newName);
    }

    /**
     * Phase 6c - org-aware rename. Org-teammates can rename a workspace-shared
     * folder; personal-scope callers stay limited to their own NULL-org rows.
     */
    public SkillFolderEntity renameFolder(UUID id, String tenantId, String organizationId, String newName) {
        validateFolderName(newName);

        SkillFolderEntity folder = findInScope(id, tenantId, organizationId)
            .orElseThrow(() -> new IllegalArgumentException("Folder not found: " + id));

        folder.setName(newName.trim());
        SkillFolderEntity saved = folderRepository.save(folder);
        log.info("Skill folder renamed: id={}, newName={}", id, newName);
        return saved;
    }

    public void deleteFolder(UUID id, String tenantId) {
        deleteFolder(id, tenantId, TenantResolver.currentRequestOrganizationId());
    }

    /**
     * Phase 6c - org-aware delete. Routes through the strict-scope finder so
     * org-teammates can delete a workspace-shared folder and personal-scope
     * callers can't reach across into org rows.
     */
    public void deleteFolder(UUID id, String tenantId, String organizationId) {
        SkillFolderEntity folder = findInScope(id, tenantId, organizationId)
            .orElseThrow(() -> new IllegalArgumentException("Folder not found: " + id));

        String name = folder.getName();
        // Skills in this folder will have folder_id set to NULL (ON DELETE SET NULL)
        // Subfolders will be deleted (ON DELETE CASCADE on parent_id)
        folderRepository.delete(folder);
        log.info("Skill folder deleted: id={}, name={}, tenant={}, org={}", id, name, tenantId, organizationId);
    }

    public SkillFolderEntity moveFolder(UUID id, String tenantId, UUID newParentId) {
        return moveFolder(id, tenantId, TenantResolver.currentRequestOrganizationId(), newParentId);
    }

    /**
     * Phase 6c - org-aware move. The folder AND target parent must both live
     * in the same active workspace as the caller (an org-scope folder cannot
     * be moved under a personal-scope parent and vice versa).
     */
    public SkillFolderEntity moveFolder(UUID id, String tenantId, String organizationId, UUID newParentId) {
        SkillFolderEntity folder = findInScope(id, tenantId, organizationId)
            .orElseThrow(() -> new IllegalArgumentException("Folder not found: " + id));

        // Moving to root
        if (newParentId == null) {
            folder.setParentId(null);
            SkillFolderEntity saved = folderRepository.save(folder);
            log.info("Skill folder moved to root: id={}", id);
            return saved;
        }

        // Cannot move to self
        if (id.equals(newParentId)) {
            throw new IllegalArgumentException("Cannot move a folder into itself");
        }

        // Verify target parent exists in the same scope as the moved folder
        findInScope(newParentId, tenantId, organizationId)
            .orElseThrow(() -> new IllegalArgumentException("Target folder not found: " + newParentId));

        // Prevent circular references: walk up from newParentId and check none is 'id'
        if (wouldCreateCircle(id, newParentId, tenantId, organizationId)) {
            throw new IllegalArgumentException("Moving this folder would create a circular reference");
        }

        folder.setParentId(newParentId);
        SkillFolderEntity saved = folderRepository.save(folder);
        log.info("Skill folder moved: id={}, newParentId={}", id, newParentId);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<SkillFolderEntity> listAllFolders(String tenantId) {
        return listAllFolders(tenantId, TenantResolver.currentRequestOrganizationId());
    }

    /**
     * Phase 6c - org-aware folder list. Returns the workspace folders plus
     * V275 admin-managed global folders so every tenant × org sees the same
     * shared shelf alongside their own.
     */
    @Transactional(readOnly = true)
    public List<SkillFolderEntity> listAllFolders(String tenantId, String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        return folderRepository.findVisibleForOrganization(organizationId);
    }

    /**
     * V275 (2026-05-21) - admin-only toggle of {@code skill_folders.is_global}.
     * Mirrors the gating contract from {@link SkillService#updateSkill}: only
     * admins can flip the flag in either direction; non-admins get an
     * {@link IllegalArgumentException}.
     */
    public SkillFolderEntity setFolderGlobal(UUID id, String tenantId, String organizationId,
                                              boolean isGlobal, boolean callerIsAdmin) {
        if (!callerIsAdmin) {
            throw new IllegalArgumentException("Only admins can change global visibility of a folder");
        }
        // Admin must be able to reach a folder they didn't create - when a
        // global folder lives outside the admin's active org, the strict-org
        // finder would miss it. Route through the V275 visibility-aware finder.
        SkillFolderEntity folder = folderRepository.findVisibleByIdForOrganization(id, organizationId)
            .orElseThrow(() -> new IllegalArgumentException("Folder not found: " + id));
        folder.setIsGlobal(isGlobal);
        SkillFolderEntity saved = folderRepository.save(folder);
        log.info("Skill folder global flag updated: id={}, isGlobal={}, tenant={}, org={}",
                id, isGlobal, tenantId, organizationId);
        return saved;
    }

    @Transactional(readOnly = true)
    public FolderContents getFolderContents(String tenantId, UUID folderId) {
        return getFolderContents(tenantId, TenantResolver.currentRequestOrganizationId(), folderId);
    }

    /**
     * Phase 6c - org-aware folder contents (subfolders + skills). The folder
     * itself, its subfolders, and the skills under it are all filtered on the
     * same active workspace; org-teammates see each other's workspace folders.
     */
    @Transactional(readOnly = true)
    public FolderContents getFolderContents(String tenantId, String organizationId, UUID folderId) {
        TenantResolver.requireOrgId(organizationId);
        String decoded = tenantId != null ? tenantId.replace("%7C", "|") : tenantId;

        List<SkillFolderEntity> subfolders;
        List<SkillEntity> skills;

        if (folderId == null) {
            // Root contents - include globals alongside workspace folders so admin-
            // managed shared folders surface for every (tenant × org) caller (V275).
            subfolders = folderRepository.findVisibleRootForOrganization(organizationId);
            skills = skillRepository.findByOrganizationIdAndFolderIdIsNullStrictOrderByCreatedAtDesc(organizationId);
        } else {
            // Verify folder exists in the active workspace (or is a global) before
            // exposing its children - V275 lets globals live outside the org partition.
            findInScopeOrGlobal(folderId, decoded, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Folder not found: " + folderId));

            subfolders = folderRepository.findVisibleChildrenForOrganization(organizationId, folderId);
            skills = skillRepository.findByOrganizationIdAndFolderIdStrictOrderByCreatedAtDesc(organizationId, folderId);
        }

        return new FolderContents(subfolders, skills);
    }

    /**
     * Phase 6c - strict-scope single fetch. Mirrors the routing contract from
     * {@code InterfaceService#findInScope} and the skill / agent strict-scope
     * pairs: org-scope when {@code organizationId} is set, personal-scope
     * otherwise; neither path leaks across workspaces.
     */
    private Optional<SkillFolderEntity> findInScope(UUID folderId, String tenantId, String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        return folderRepository.findByIdAndOrganizationIdStrict(folderId, organizationId);
    }

    /**
     * V275 (2026-05-21) - read-side single fetch that also accepts global
     * folders, mirroring {@link SkillService}'s {@code findInScopeOrGlobal}.
     * Used as the gate before exposing folder contents - admins shared a
     * folder globally, and that folder must be reachable from every
     * (tenant × org).
     */
    private Optional<SkillFolderEntity> findInScopeOrGlobal(UUID folderId, String tenantId, String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        return folderRepository.findVisibleByIdForOrganization(folderId, organizationId);
    }

    /**
     * Check if moving folderId under newParentId would create a circular
     * reference. Walks the parent chain via raw {@code findById}; the
     * caller's workspace gating happens upstream (both folder and target
     * parent are pre-verified via {@link #findInScope}). The chain itself
     * is scope-internal - folders only ever point to parents in their own
     * workspace, so the walk cannot bleed across workspaces.
     */
    private boolean wouldCreateCircle(UUID folderId, UUID newParentId,
                                       String tenantId, String organizationId) {
        Set<UUID> visited = new HashSet<>();
        UUID current = newParentId;
        while (current != null) {
            if (current.equals(folderId)) {
                return true;
            }
            if (!visited.add(current)) {
                // Already visited this node -- broken chain, stop
                return false;
            }
            UUID finalCurrent = current;
            current = folderRepository.findById(finalCurrent)
                .map(SkillFolderEntity::getParentId)
                .orElse(null);
        }
        return false;
    }

    private void validateFolderName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Folder name cannot be empty");
        }
        if (name.trim().length() > 255) {
            throw new IllegalArgumentException("Folder name cannot exceed 255 characters");
        }
    }

    /**
     * Response record for folder contents.
     */
    public record FolderContents(List<SkillFolderEntity> folders, List<SkillEntity> skills) {
    }
}
