package com.apimarketplace.agent.controller;

import com.apimarketplace.agent.util.RequestParameterExtractor;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.web.AdminRoleGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.agent.domain.AgentSkillEntity;
import com.apimarketplace.agent.domain.SkillEntity;
import com.apimarketplace.agent.domain.SkillFolderEntity;
import com.apimarketplace.agent.service.AgentService;
import com.apimarketplace.agent.service.SkillFolderService;
import com.apimarketplace.agent.service.SkillService;
import com.apimarketplace.agent.service.SkillService.SkillAssignment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST controller for Skill operations and Agent-Skill assignments.
 */
@RestController
@RequestMapping("/api")
public class SkillController {

    private final SkillService skillService;
    private final SkillFolderService skillFolderService;
    private final AgentService agentService;
    private final TenantResolver tenantResolver;
    private final RequestParameterExtractor extractor;
    private final OrgAccessGuard orgAccessGuard;

    public SkillController(SkillService skillService,
                           SkillFolderService skillFolderService,
                           AgentService agentService,
                           TenantResolver tenantResolver,
                           RequestParameterExtractor extractor,
                           OrgAccessGuard orgAccessGuard) {
        this.skillService = skillService;
        this.skillFolderService = skillFolderService;
        this.agentService = agentService;
        this.tenantResolver = tenantResolver;
        this.extractor = extractor;
        this.orgAccessGuard = orgAccessGuard;
    }

    // ============================================
    // Per-member org RBAC (skill resource type)
    // ============================================

    /** Skill ids the caller is DENY-restricted from in their active org (empty when unrestricted). */
    private Set<String> restrictedSkillIds(HttpServletRequest request, String tenantId) {
        String orgId = tenantResolver.resolveOrgId(request);
        if (orgAccessGuard == null || orgId == null || orgId.isBlank()) return Set.of();
        return orgAccessGuard.getRestrictedResourceIds(
                orgId, tenantId, "skill", tenantResolver.resolveOrgRole(request));
    }

    /** True when the caller may WRITE this skill (no per-member "skill" write restriction). */
    private boolean canWriteSkill(HttpServletRequest request, String tenantId, UUID id) {
        String orgId = tenantResolver.resolveOrgId(request);
        if (orgAccessGuard == null || orgId == null || orgId.isBlank()) return true;
        return orgAccessGuard.canWrite(
                orgId, tenantId, "skill", id.toString(), tenantResolver.resolveOrgRole(request));
    }

    // ============================================
    // Skill CRUD
    // ============================================

    @PostMapping("/skills")
    public ResponseEntity<SkillEntity> createSkill(
            HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        if (isViewerRole(httpRequest)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        boolean callerIsAdmin = AdminRoleGuard.isAdmin(httpRequest.getHeader("X-User-Roles"));
        Boolean isGlobal = extractor.getBoolean(request, "isGlobal");

        SkillEntity created = skillService.createSkill(
            tenantId,
            extractor.getText(request, "name"),
            extractor.getText(request, "description"),
            extractor.getText(request, "icon"),
            extractor.getText(request, "instructions"),
            extractor.getUUID(request, "folderId"),
            Boolean.TRUE.equals(isGlobal),
            callerIsAdmin
        );

        return ResponseEntity.ok(created);
    }

    @GetMapping("/skills")
    public ResponseEntity<List<SkillEntity>> listSkills(
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        String orgId = tenantResolver.resolveOrgId(request);
        List<SkillEntity> skills = skillService.listSkills(tenantId, orgId);
        // Drop the caller's per-member DENY-restricted skills (org RBAC). Empty set ⇒ no-op.
        Set<String> restricted = restrictedSkillIds(request, tenantId);
        if (!restricted.isEmpty()) {
            skills = skills.stream()
                    .filter(s -> s.getId() == null || !restricted.contains(s.getId().toString()))
                    .collect(Collectors.toList());
        }
        return ResponseEntity.ok(skills);
    }

    @GetMapping("/skills/{id}")
    public ResponseEntity<SkillEntity> getSkill(
            @PathVariable("id") UUID id,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(request);

        // Per-member DENY restriction (org RBAC): a denied skill reads as not-found.
        if (restrictedSkillIds(request, tenantId).contains(id.toString())) {
            return ResponseEntity.notFound().build();
        }
        return skillService.getSkill(id, tenantId, orgId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/skills/{id}")
    public ResponseEntity<SkillEntity> updateSkill(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(httpRequest);
        if (isViewerRole(httpRequest)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!canWriteSkill(httpRequest, tenantId, id)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        boolean callerIsAdmin = AdminRoleGuard.isAdmin(httpRequest.getHeader("X-User-Roles"));

        SkillEntity updated = skillService.updateSkill(
            id,
            tenantId,
            orgId,
            extractor.getText(request, "name"),
            extractor.getText(request, "description"),
            extractor.getText(request, "icon"),
            extractor.getText(request, "instructions"),
            extractor.getBoolean(request, "isActive"),
            extractor.getBoolean(request, "isGlobal"),
            extractor.getBoolean(request, "isDefaultActive"),
            callerIsAdmin
        );

        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/skills/{id}")
    public ResponseEntity<Void> deleteSkill(
            @PathVariable("id") UUID id,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(request);
        if (isViewerRole(request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!canWriteSkill(request, tenantId, id)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        boolean callerIsAdmin = AdminRoleGuard.isAdmin(request.getHeader("X-User-Roles"));
        skillService.deleteSkill(id, tenantId, orgId, callerIsAdmin);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/skills/{id}/reset")
    public ResponseEntity<SkillEntity> resetSkill(
            @PathVariable("id") UUID id,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        tenantResolver.validate(tenantId);

        // Default skills are personal-scope rows seeded per tenant; reset
        // stays tenant-only (no org workspace claim).
        SkillEntity reset = skillService.resetDefaultSkill(id, tenantId);
        return ResponseEntity.ok(reset);
    }

    @PutMapping("/skills/{id}/move")
    public ResponseEntity<SkillEntity> moveSkill(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(httpRequest);
        if (isViewerRole(httpRequest)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        if (!canWriteSkill(httpRequest, tenantId, id)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        UUID folderId = extractor.getUUID(request, "folderId");
        SkillEntity moved = skillService.moveSkill(id, tenantId, orgId, folderId);
        return ResponseEntity.ok(moved);
    }

    // ============================================
    // V276 - per-user override (active/inactive for "me", independent of others)
    // ============================================

    /**
     * V276 (2026-05-21) - set this user's per-skill override. Body shape:
     * {@code {"active": true|false}}. Insert-or-update; idempotent.
     *
     * <p>User can opt out of a global/org-shared skill the admin/owner marked
     * default-active, without affecting teammates. See {@code UserSkillOverrideEntity}.
     */
    @PutMapping("/skills/{id}/user-active")
    public ResponseEntity<Void> setUserSkillActive(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(httpRequest);

        Boolean active = extractor.getBoolean(request, "active");
        if (active == null) {
            throw new IllegalArgumentException("Field 'active' is required (true|false)");
        }

        // tenantId here is the X-User-ID header value (= the calling user's id);
        // the override row keys on user_id so we pass it as both the override
        // identity and let the visibility gate use orgId for scope.
        skillService.setUserSkillOverride(tenantId, id, active, orgId);
        return ResponseEntity.noContent().build();
    }

    /**
     * V276 - clear this user's override row. Chat-time resolution then falls
     * back to {@code skills.is_default_active}. Idempotent (200 even if no
     * override existed).
     */
    @DeleteMapping("/skills/{id}/user-active")
    public ResponseEntity<Void> clearUserSkillActive(
            @PathVariable("id") UUID id,
            HttpServletRequest httpRequest) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);

        skillService.clearUserSkillOverride(tenantId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * V276 - return the calling user's full override map as
     * {@code {skillId: active}}. Frontend merges with the regular skills list
     * to render the per-row toggle: effective = override ?? skill.isDefaultActive.
     */
    @GetMapping("/skills/me/overrides")
    public ResponseEntity<Map<String, Boolean>> getMyOverrides(HttpServletRequest httpRequest) {
        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);

        Map<UUID, Boolean> overrides = skillService.getUserOverrides(tenantId);
        Map<String, Boolean> stringKeyed = new HashMap<>(overrides.size());
        overrides.forEach((k, v) -> stringKeyed.put(k.toString(), v));
        return ResponseEntity.ok(stringKeyed);
    }

    // ============================================
    // Agent-Skill assignments
    // ============================================

    @GetMapping("/agents/{agentId}/skills")
    public ResponseEntity<List<AgentSkillEntity>> getAgentSkills(
            @PathVariable("agentId") UUID agentId,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(request);

        List<AgentSkillEntity> agentSkills = skillService.getAgentSkills(agentId, tenantId, orgId);
        return ResponseEntity.ok(agentSkills);
    }

    /**
     * Fleet batch - skill assignments for EVERY agent in the caller's workspace in
     * ONE call (each row carries {@code agentId}). Replaces the per-agent
     * GET /agents/{agentId}/skills fan-out the Agent Fleet canvas makes.
     */
    @GetMapping("/agents/skills")
    public ResponseEntity<List<AgentSkillEntity>> getAllAgentSkills(HttpServletRequest request) {
        String tenantId = tenantResolver.resolveOrNull(request);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(request);
        return ResponseEntity.ok(skillService.getAllAgentSkills(tenantId, orgId));
    }

    @SuppressWarnings("unchecked")
    @PutMapping("/agents/{agentId}/skills")
    public ResponseEntity<Void> setAgentSkills(
            @PathVariable("agentId") UUID agentId,
            HttpServletRequest httpRequest,
            @RequestBody List<Map<String, Object>> requestBody) {

        String tenantId = tenantResolver.resolveOrNull(httpRequest);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(httpRequest);
        if (isViewerRole(httpRequest)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        // Per-resource write gate: the coarse VIEWER check above only blocks the
        // org-wide VIEWER role. A non-VIEWER MEMBER restricted to READ/DENY on THIS
        // agent could otherwise still rewrite its skill set - mirror the
        // webhook/schedule/widget endpoints and enforce canWrite on the agent.
        agentService.assertCanWriteAgent(agentId, tenantId, orgId);

        List<SkillAssignment> assignments = new ArrayList<>();
        for (Map<String, Object> item : requestBody) {
            UUID skillId = extractor.getUUID(item, "skillId");
            if (skillId != null) {
                assignments.add(new SkillAssignment(skillId));
            }
        }

        skillService.setAgentSkills(agentId, tenantId, orgId, assignments);
        return ResponseEntity.ok().build();
    }

    // ============================================
    // Agent Skills Summary (lightweight, no instructions)
    // ============================================

    public record SkillSummaryDto(UUID id, String name, String description, UUID folderId, boolean isActive, boolean isDefaultActive) {}
    public record FolderSummaryDto(UUID id, String name, UUID parentId, boolean isGlobal) {}
    public record AgentSkillsSummaryDto(List<SkillSummaryDto> skills, List<FolderSummaryDto> folders) {}

    @GetMapping("/agents/{agentId}/skills/summary")
    public ResponseEntity<AgentSkillsSummaryDto> getAgentSkillsSummary(
            @PathVariable("agentId") UUID agentId,
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(request);

        List<AgentSkillEntity> agentSkills = skillService.getAgentSkills(agentId, tenantId, orgId);

        // Map skills to lightweight DTOs (no instructions LOB)
        List<SkillSummaryDto> skillDtos = agentSkills.stream()
            .filter(as -> as.getSkill() != null)
            .map(as -> {
                SkillEntity s = as.getSkill();
                return new SkillSummaryDto(
                    s.getId(), s.getName(), s.getDescription(),
                    s.getFolderId(),
                    Boolean.TRUE.equals(s.getIsActive()),
                    Boolean.TRUE.equals(s.getIsDefaultActive())
                );
            })
            .toList();

        // Collect referenced folderIds
        Set<UUID> referencedFolderIds = skillDtos.stream()
            .map(SkillSummaryDto::folderId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        // Build folder DTOs: include referenced folders + all ancestors
        List<FolderSummaryDto> folderDtos = List.of();
        if (!referencedFolderIds.isEmpty()) {
            List<SkillFolderEntity> allFolders = skillFolderService.listAllFolders(tenantId, orgId);
            Map<UUID, SkillFolderEntity> folderMap = allFolders.stream()
                .collect(Collectors.toMap(SkillFolderEntity::getId, f -> f));

            // Walk up ancestors for each referenced folder
            Set<UUID> relevantIds = new HashSet<>(referencedFolderIds);
            for (UUID fid : referencedFolderIds) {
                UUID current = fid;
                while (current != null && folderMap.containsKey(current)) {
                    relevantIds.add(current);
                    current = folderMap.get(current).getParentId();
                }
            }

            folderDtos = allFolders.stream()
                .filter(f -> relevantIds.contains(f.getId()))
                .map(f -> new FolderSummaryDto(f.getId(), f.getName(), f.getParentId(),
                        Boolean.TRUE.equals(f.getIsGlobal())))
                .toList();
        }

        return ResponseEntity.ok(new AgentSkillsSummaryDto(skillDtos, folderDtos));
    }

    // ============================================
    // Skills Summary by IDs (for general chat, not agent-scoped)
    // ============================================

    /**
     * Fetch lightweight skills summary for a list of skill IDs.
     * Used by conversation-service to inject user-created skills into the system prompt
     * for general chat (no agent required).
     */
    @PostMapping("/skills/summary")
    public ResponseEntity<AgentSkillsSummaryDto> getSkillsSummaryByIds(
            HttpServletRequest request,
            @RequestBody Map<String, Object> body) {

        String tenantId = tenantResolver.resolveOrNull(request);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(request);

        @SuppressWarnings("unchecked")
        List<String> skillIds = (List<String>) body.getOrDefault("skillIds", List.of());
        if (skillIds.isEmpty()) {
            return ResponseEntity.ok(new AgentSkillsSummaryDto(List.of(), List.of()));
        }

        // Fetch each skill by UUID
        List<SkillSummaryDto> skillDtos = new ArrayList<>();
        for (String idStr : skillIds) {
            try {
                UUID id = UUID.fromString(idStr);
                skillService.getSkill(id, tenantId, orgId).ifPresent(s ->
                    skillDtos.add(new SkillSummaryDto(
                        s.getId(), s.getName(), s.getDescription(),
                        s.getFolderId(),
                        Boolean.TRUE.equals(s.getIsActive()),
                        Boolean.TRUE.equals(s.getIsDefaultActive())
                    ))
                );
            } catch (IllegalArgumentException ignored) {
                // Skip invalid UUIDs (e.g. default:* IDs)
            }
        }

        // Collect referenced folderIds and resolve ancestors
        Set<UUID> referencedFolderIds = skillDtos.stream()
            .map(SkillSummaryDto::folderId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        List<FolderSummaryDto> folderDtos = List.of();
        if (!referencedFolderIds.isEmpty()) {
            List<SkillFolderEntity> allFolders = skillFolderService.listAllFolders(tenantId, orgId);
            Map<UUID, SkillFolderEntity> folderMap = allFolders.stream()
                .collect(Collectors.toMap(SkillFolderEntity::getId, f -> f));

            Set<UUID> relevantIds = new HashSet<>(referencedFolderIds);
            for (UUID fid : referencedFolderIds) {
                UUID current = fid;
                while (current != null && folderMap.containsKey(current)) {
                    relevantIds.add(current);
                    current = folderMap.get(current).getParentId();
                }
            }

            folderDtos = allFolders.stream()
                .filter(f -> relevantIds.contains(f.getId()))
                .map(f -> new FolderSummaryDto(f.getId(), f.getName(), f.getParentId(),
                        Boolean.TRUE.equals(f.getIsGlobal())))
                .toList();
        }

        return ResponseEntity.ok(new AgentSkillsSummaryDto(skillDtos, folderDtos));
    }

    // ============================================
    // Default-active skills (V275 - for general chat without explicit skillIds)
    // ============================================

    /**
     * V275/V276 (2026-05-21) - return the effective default-active skills for
     * the calling user × workspace. Applies the resolution rule
     * {@code COALESCE(user_override.active, skill.is_default_active)} over the
     * org-visible skills. Used by conversation-service AgentContextBuilder to
     * seed the general chat system prompt when no {@code defaultSkillIds}
     * arrived from the client (per-conv override still wins when present).
     * Mirrors the {@code /skills/summary} response shape so the existing
     * snapshot/render pipeline reuses without branching.
     */
    @GetMapping("/skills/default-active/summary")
    public ResponseEntity<AgentSkillsSummaryDto> getDefaultActiveSkillsSummary(
            HttpServletRequest request,
            @RequestParam(value = "tenantId", required = false) String tenantIdParam) {

        String tenantId = tenantResolver.resolveOrNull(request);
        tenantResolver.validate(tenantId);
        String orgId = tenantResolver.resolveOrgId(request);

        // tenantId == userId in this service (X-User-ID header).
        List<SkillEntity> defaultActive = skillService.listEffectiveDefaultActiveForUser(
            tenantId, tenantId, orgId);

        List<SkillSummaryDto> skillDtos = defaultActive.stream()
            .map(s -> new SkillSummaryDto(
                s.getId(), s.getName(), s.getDescription(),
                s.getFolderId(),
                Boolean.TRUE.equals(s.getIsActive()),
                Boolean.TRUE.equals(s.getIsDefaultActive())
            ))
            .toList();

        Set<UUID> referencedFolderIds = skillDtos.stream()
            .map(SkillSummaryDto::folderId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());

        List<FolderSummaryDto> folderDtos = List.of();
        if (!referencedFolderIds.isEmpty()) {
            List<SkillFolderEntity> allFolders = skillFolderService.listAllFolders(tenantId, orgId);
            Map<UUID, SkillFolderEntity> folderMap = allFolders.stream()
                .collect(Collectors.toMap(SkillFolderEntity::getId, f -> f));

            Set<UUID> relevantIds = new HashSet<>(referencedFolderIds);
            for (UUID fid : referencedFolderIds) {
                UUID current = fid;
                while (current != null && folderMap.containsKey(current)) {
                    relevantIds.add(current);
                    current = folderMap.get(current).getParentId();
                }
            }

            folderDtos = allFolders.stream()
                .filter(f -> relevantIds.contains(f.getId()))
                .map(f -> new FolderSummaryDto(f.getId(), f.getName(), f.getParentId(),
                        Boolean.TRUE.equals(f.getIsGlobal())))
                .toList();
        }

        return ResponseEntity.ok(new AgentSkillsSummaryDto(skillDtos, folderDtos));
    }

    private boolean isViewerRole(HttpServletRequest request) {
        String orgRole = tenantResolver.resolveOrgRole(request);
        return orgRole != null && "VIEWER".equalsIgnoreCase(orgRole.trim());
    }
}
