package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.util.RequestParameterExtractor;
import com.apimarketplace.common.web.AdminRoleGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.agent.domain.SkillFolderEntity;
import com.apimarketplace.agent.service.SkillFolderService;
import com.apimarketplace.agent.service.SkillFolderService.FolderContents;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST controller for Skill Folder operations.
 */
@RestController
@RequestMapping("/api/skill-folders")
public class SkillFolderController {

    private final SkillFolderService folderService;
    private final TenantResolver tenantResolver;
    private final RequestParameterExtractor extractor;

    public SkillFolderController(SkillFolderService folderService,
                                  TenantResolver tenantResolver,
                                  RequestParameterExtractor extractor) {
        this.folderService = folderService;
        this.tenantResolver = tenantResolver;
        this.extractor = extractor;
    }

    @PostMapping
    public ResponseEntity<SkillFolderEntity> createFolder(
            HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(httpRequest);
        if (isViewerRole(httpRequest)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String name = extractor.getString(request, "name");
        UUID parentId = extractor.getUUID(request, "parentId");

        SkillFolderEntity created = folderService.createFolder(tenantId, name, parentId, orgId);
        return ResponseEntity.ok(created);
    }

    @GetMapping
    public ResponseEntity<List<SkillFolderEntity>> listAllFolders(
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        String orgId = tenantResolver.resolveOrgId(request);
        List<SkillFolderEntity> folders = folderService.listAllFolders(tenantId, orgId);
        return ResponseEntity.ok(folders);
    }

    @PutMapping("/{id}")
    public ResponseEntity<SkillFolderEntity> renameFolder(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(httpRequest);
        if (isViewerRole(httpRequest)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String newName = extractor.getString(request, "name");
        SkillFolderEntity renamed = folderService.renameFolder(id, tenantId, orgId, newName);
        return ResponseEntity.ok(renamed);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFolder(
            @PathVariable("id") UUID id,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(request);
        if (isViewerRole(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        folderService.deleteFolder(id, tenantId, orgId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/move")
    public ResponseEntity<SkillFolderEntity> moveFolder(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(httpRequest);
        if (isViewerRole(httpRequest)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        UUID parentId = extractor.getUUID(request, "parentId");
        SkillFolderEntity moved = folderService.moveFolder(id, tenantId, orgId, parentId);
        return ResponseEntity.ok(moved);
    }

    @GetMapping("/{id}/contents")
    public ResponseEntity<FolderContents> getFolderContents(
            @PathVariable("id") UUID id,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        String orgId = tenantResolver.resolveOrgId(request);
        FolderContents contents = folderService.getFolderContents(tenantId, orgId, id);
        return ResponseEntity.ok(contents);
    }

    @GetMapping("/root/contents")
    public ResponseEntity<FolderContents> getRootContents(
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        String orgId = tenantResolver.resolveOrgId(request);
        FolderContents contents = folderService.getFolderContents(tenantId, orgId, null);
        return ResponseEntity.ok(contents);
    }

    /**
     * V275 (2026-05-21) - admin-only toggle of {@code is_global} on a folder.
     * Body shape: {@code {"isGlobal": true|false}}. Mirrors the admin gating
     * already used on {@link SkillFolderEntity#getIsGlobal()} for skills.
     */
    @PutMapping("/{id}/global")
    public ResponseEntity<SkillFolderEntity> setFolderGlobal(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(httpRequest);
        if (isViewerRole(httpRequest)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        boolean callerIsAdmin = AdminRoleGuard.isAdmin(httpRequest.getHeader("X-User-Roles"));

        Boolean isGlobal = extractor.getBoolean(request, "isGlobal");
        if (isGlobal == null) {
            throw new IllegalArgumentException("Field 'isGlobal' is required (true|false)");
        }

        SkillFolderEntity updated = folderService.setFolderGlobal(
            id, tenantId, orgId, isGlobal, callerIsAdmin);
        return ResponseEntity.ok(updated);
    }

    private boolean isViewerRole(HttpServletRequest request) {
        String orgRole = tenantResolver.resolveOrgRole(request);
        return orgRole != null && "VIEWER".equalsIgnoreCase(orgRole.trim());
    }
}
