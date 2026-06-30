package com.apimarketplace.auth.web;

import com.apimarketplace.auth.domain.*;
import com.apimarketplace.auth.dto.InvitationDto;
import com.apimarketplace.auth.dto.OrganizationDto;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.UserRepository;
import com.apimarketplace.auth.service.OnboardingService;
import com.apimarketplace.auth.service.OrganizationMemberService;
import com.apimarketplace.auth.service.OrganizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.auth.util.InitialsAvatarGenerator;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller for organization management.
 */
@RestController
@RequestMapping("/api/organizations")
public class OrganizationController {

    private static final Logger log = LoggerFactory.getLogger(OrganizationController.class);

    private final OrganizationService organizationService;
    private final OrganizationMemberService memberService;
    private final OrganizationMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final OnboardingService onboardingService;
    private final com.apimarketplace.auth.repository.OrganizationAuditEventRepository auditEventRepository;
    private final com.apimarketplace.auth.service.GatewayCacheClient gatewayCacheClient;

    // Field-injected (optional) - flags "dormant" (paused) orgs in the /me workspace
    // list so the FE renders them paused + disables switching. Null in slim tests →
    // paused stays false (no behaviour change). Same single predicate the gateway uses.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.apimarketplace.auth.service.PlanResolutionService planResolutionService;

    /**
     * PR11d-b - cooldown between setDefaultOrganization flips (seconds).
     * Default 60s is generous for legitimate UI clicks, tight enough to
     * defeat scripted bypass of per-member quota caps. Override via env
     * AUTH_ORG_SETDEFAULT_COOLDOWN_SECONDS=N for tenant-specific tuning.
     */
    @org.springframework.beans.factory.annotation.Value("${auth.organization.set-default.cooldown-seconds:60}")
    private int setDefaultCooldownSeconds;

    /** Grace window before a soft-deleted workspace is hard-purged - drives the /me purgeAt hint. */
    @org.springframework.beans.factory.annotation.Value("${workspace.purge.grace-days:30}")
    private int workspacePurgeGraceDays;

    /**
     * Same edition signal {@link com.apimarketplace.auth.service.OrganizationMemberService}
     * uses (auth.mode=embedded → CE). Gates exposing the invitation token in
     * invite/pending responses: CE has no SMTP delivery, so a CE admin needs the
     * raw token to build a copyable accept link. Cloud delivers it by email only
     * and MUST never echo it (the default keycloak value keeps the token hidden).
     */
    @org.springframework.beans.factory.annotation.Value("${auth.mode:keycloak}")
    private String authMode = "keycloak";

    private boolean isEmbeddedAuthMode() {
        return "embedded".equalsIgnoreCase(authMode);
    }

    /**
     * PR11 round-3 fix (audit 1 2026-05-12 MUST-FIX #2): fail-fast on
     * cooldown <= 0. An env typo or hostile deploy setting
     * AUTH_ORG_SETDEFAULT_COOLDOWN_SECONDS=0 would silently disable the
     * multi-org-juggle defence (line 280 would compare `elapsed < 0`,
     * never trip). Refuse to boot the service rather than ship a
     * degraded security posture under cover of "default 60". Operator
     * MUST be explicit if they want to disable - set the JVM property
     * or env to a documented "0 + I-know-what-I-am-doing" override
     * (NOT shipped here; no override exists in v1).
     */
    @jakarta.annotation.PostConstruct
    void validateRateLimitConfig() {
        if (isEmbeddedAuthMode()) {
            // CE: the cooldown is never applied (see setDefaultOrganization), so the >=1 guard is
            // moot - the multi-tenant abuse surface it defends does not exist on a self-hosted install.
            log.info("setDefault rate-limit: DISABLED (auth.mode=embedded / CE)");
            return;
        }
        if (setDefaultCooldownSeconds < 1) {
            String msg = String.format(
                    "auth.organization.set-default.cooldown-seconds must be >= 1 (got %d). "
                            + "A cooldown of 0 disables the per-user setDefault rate-limit, "
                            + "reopening the multi-org cap-bypass surface audit B 2026-05-12 closed.",
                    setDefaultCooldownSeconds);
            log.error(msg);
            throw new IllegalStateException(msg);
        }
        log.info("setDefault rate-limit: cooldown={}s active", setDefaultCooldownSeconds);
    }

    public OrganizationController(OrganizationService organizationService,
                                  OrganizationMemberService memberService,
                                  OrganizationMemberRepository memberRepository,
                                  UserRepository userRepository,
                                  OnboardingService onboardingService,
                                  com.apimarketplace.auth.repository.OrganizationAuditEventRepository auditEventRepository,
                                  com.apimarketplace.auth.service.GatewayCacheClient gatewayCacheClient) {
        this.organizationService = organizationService;
        this.memberService = memberService;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.onboardingService = onboardingService;
        this.auditEventRepository = auditEventRepository;
        this.gatewayCacheClient = gatewayCacheClient;
    }

    /**
     * Get all organizations the current user belongs to.
     */
    @GetMapping("/me")
    public ResponseEntity<List<OrganizationDto>> getMyOrganizations(
            @RequestHeader(value = "X-User-ID", required = false) Long userId) {

        if (userId == null) {
            log.warn("Missing X-User-ID header");
            return ResponseEntity.badRequest().build();
        }

        List<OrganizationMember> memberships = memberRepository.findByUserIdWithOrganization(userId);

        // "Dormant" orgs (owner no longer on a team plan) - resolved in ONE batched
        // query (same path as OrganizationService.listUserMembershipsDto), avoiding a
        // per-org N+1. Empty when the resolver isn't wired (slim tests) or in CE-free.
        java.util.Set<UUID> pausedOrgIds = planResolutionService != null
                ? planResolutionService.resolvePausedOrgIds(memberships)
                : java.util.Set.of();

        List<OrganizationDto> orgs = new java.util.ArrayList<>(memberships.stream()
                .map(membership -> {
                    Organization org = membership.getOrganization();
                    int memberCount = (int) memberRepository.countByOrganization_Id(org.getId());
                    OrganizationDto dto = OrganizationDto.fromEntity(org, membership, memberCount);
                    enrichWithTeamStatus(dto, org.getId());
                    // Paused = dormant team org this (non-owner) member can no longer enter.
                    dto.setPaused(pausedOrgIds.contains(org.getId()));
                    return dto;
                })
                .toList());

        // Append workspaces the OWNER soft-deleted that are still in the grace window, flagged
        // pendingDeletion + purgeAt so the FE can offer a restore. The gateway membership path
        // (findByUserIdWithOrganization) still excludes deleted orgs → they stay non-enterable.
        for (OrganizationMember m : memberRepository.findPendingDeletionOwnedByUser(userId)) {
            Organization org = m.getOrganization();
            int memberCount = (int) memberRepository.countByOrganization_Id(org.getId());
            OrganizationDto dto = OrganizationDto.fromEntity(org, m, memberCount);
            dto.setPendingDeletion(true);
            if (org.getDeletedAt() != null) {
                dto.setPurgeAt(org.getDeletedAt().plusDays(workspacePurgeGraceDays));
            }
            orgs.add(dto);
        }

        return ResponseEntity.ok(orgs);
    }

    /**
     * Create an additional workspace owned by the caller. Shared-wallet model - gated by the
     * caller's plan {@code max_workspaces} cap (PRO 3 / TEAM 10 / ENTERPRISE unlimited; FREE/
     * STARTER 1 = personal only). Body: {"name": "My Workspace"}.
     *
     * Returns:
     *   200 + the new OrganizationDto on success
     *   400 if X-User-ID missing, user not found, or name blank
     *   403 if the caller is at their plan's workspace cap (errorCode WORKSPACE_LIMIT_REACHED)
     */
    @PostMapping
    public ResponseEntity<?> createOrganization(
            @RequestHeader(value = "X-User-ID", required = false) Long userId,
            @RequestBody(required = false) Map<String, String> body) {

        if (userId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "X-User-ID header required"));
        }
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "User not found"));
        }
        String name = body == null ? null : body.get("name");
        try {
            Organization org = organizationService.createOrganization(user, name);
            // Make the new membership visible to the gateway immediately (else /me lags by the cache TTL).
            String providerId = user.getProviderId();
            if (providerId != null && !providerId.isBlank()) {
                gatewayCacheClient.invalidateUserCache(providerId);
            }
            Optional<OrganizationMember> membership = memberRepository.findByOrganization_IdAndUser_Id(org.getId(), userId);
            return ResponseEntity.ok(OrganizationDto.fromEntity(org, membership.orElse(null), 1));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "errorCode", "WORKSPACE_LIMIT_REACHED",
                    "message", e.getMessage()));
        }
    }

    /**
     * Get the current (default) organization details.
     */
    @GetMapping("/current")
    public ResponseEntity<OrganizationDto> getCurrentOrganization(
            @RequestHeader(value = "X-User-ID", required = false) Long userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgIdStr) {

        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }

        // Try to use X-Organization-ID if provided
        if (orgIdStr != null && !orgIdStr.isEmpty()) {
            try {
                UUID orgId = UUID.fromString(orgIdStr);
                Optional<OrganizationMember> membership = memberRepository.findActiveByOrganizationIdAndUserId(orgId, userId);
                if (membership.isPresent()) {
                    Organization org = membership.get().getOrganization();
                    int memberCount = (int) memberRepository.countByOrganization_Id(org.getId());
                    OrganizationDto dto = OrganizationDto.fromEntity(org, membership.get(), memberCount);
                    enrichWithTeamStatus(dto, orgId);
                    return ResponseEntity.ok(dto);
                }
            } catch (IllegalArgumentException e) {
                log.warn("Invalid organization ID: {}", orgIdStr);
            }
        }

        // Fallback to default organization
        Optional<OrganizationMember> defaultMembership = memberRepository.findActiveDefaultByUserId(userId);
        if (defaultMembership.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Organization org = defaultMembership.get().getOrganization();
        int memberCount = (int) memberRepository.countByOrganization_Id(org.getId());
        OrganizationDto dto = OrganizationDto.fromEntity(org, defaultMembership.get(), memberCount);
        enrichWithTeamStatus(dto, org.getId());
        return ResponseEntity.ok(dto);
    }

    /**
     * Get organization details by ID.
     */
    @Transactional(readOnly = true)
    @GetMapping("/{orgId}")
    public ResponseEntity<OrganizationDto> getOrganization(
            @PathVariable UUID orgId,
            @RequestHeader(value = "X-User-ID", required = false) Long userId) {

        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }

        // Verify user is a member
        Optional<OrganizationMember> membership = memberRepository.findActiveByOrganizationIdAndUserId(orgId, userId);
        if (membership.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Organization org = membership.get().getOrganization();
        int memberCount = (int) memberRepository.countByOrganization_Id(org.getId());

        // Get all members with their display names. Resolve in one batch through the
        // shared fallback chain (onboarding display_name → full name → username →
        // email) so CE members - who have no onboarding row - still show their real
        // name instead of degrading to the raw email.
        List<OrganizationMember> members = memberRepository.findByOrganization_Id(orgId);
        Map<Long, String> memberNames = onboardingService.resolveDisplayNames(
                members.stream().map(m -> m.getUser().getId()).toList());
        List<OrganizationDto.MemberDto> memberDtos = members.stream()
                .map(m -> new OrganizationDto.MemberDto(
                        m, memberNames.getOrDefault(m.getUser().getId(), m.getUser().getEmail())))
                .toList();

        OrganizationDto dto = OrganizationDto.fromEntity(org, membership.get(), memberCount);
        dto.setMembers(memberDtos);
        enrichWithTeamStatus(dto, orgId);

        return ResponseEntity.ok(dto);
    }

    /**
     * Update organization name (owner/admin only).
     */
    @PutMapping("/{orgId}")
    public ResponseEntity<OrganizationDto> updateOrganization(
            @PathVariable UUID orgId,
            @RequestHeader(value = "X-User-ID", required = false) Long userId,
            @RequestBody Map<String, String> updates) {

        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }

        // Verify user is owner or admin
        Optional<OrganizationMember> membership = memberRepository.findActiveByOrganizationIdAndUserId(orgId, userId);
        if (membership.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        OrganizationMember member = membership.get();
        if (member.getRole() != OrganizationRole.OWNER &&
            member.getRole() != OrganizationRole.ADMIN) {
            return ResponseEntity.status(403).build();
        }

        Organization org = member.getOrganization();

        if (updates.containsKey("name")) {
            String newName = updates.get("name");
            if (newName != null && !newName.trim().isEmpty()) {
                org.setName(newName.trim());
            }
        }

        organizationService.save(org);

        int memberCount = (int) memberRepository.countByOrganization_Id(org.getId());
        return ResponseEntity.ok(OrganizationDto.fromEntity(org, member, memberCount));
    }

    /**
     * Set an organization as the default for the current user.
     *
     * <p>PR11d-b (audit B 2026-05-12 MUST-FIX #2) - per-user cooldown on
     * rapid flips (default 60s, overridable via
     * {@code AUTH_ORG_SETDEFAULT_COOLDOWN_SECONDS}). Without this, a
     * member in 2+ TEAM orgs could script flips to juggle wallets and
     * defeat per-member quota caps that are keyed on the default
     * workspace at consume time. The cooldown blocks ONLY same-target
     * flips between distinct orgs; idempotent re-flips to the SAME org
     * pass through with no penalty.
     */
    @PostMapping("/{orgId}/set-default")
    public ResponseEntity<Void> setDefaultOrganization(
            @PathVariable UUID orgId,
            @RequestHeader(value = "X-User-ID", required = false) Long userId) {

        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }

        Optional<OrganizationMember> targetMembership = memberRepository.findByOrganization_IdAndUser_Id(orgId, userId);
        if (targetMembership.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // A soft-deleted (pending-purge) workspace is never enterable - refuse to make it the
        // default, mirroring the gateway membership filter (defense-in-depth so a stale client
        // can't re-pin a deleted workspace as the active context). Placed BEFORE the idempotent
        // no-op on purpose (unlike the dormant/paused guard, which runs after): re-confirming a
        // workspace that was deleted while it was the current default must also be rejected.
        if (organizationService.findById(orgId).map(Organization::isDeleted).orElse(false)) {
            return ResponseEntity.status(409).build();
        }

        Optional<OrganizationMember> currentDefault = memberRepository.findByUser_IdAndIsDefaultTrue(userId);
        // No-op fast-path: target IS the current default. Idempotent, no
        // cooldown penalty, no cache-bust round-trip.
        if (currentDefault.isPresent()
                && currentDefault.get().getOrganization() != null
                && currentDefault.get().getOrganization().getId().equals(orgId)) {
            return ResponseEntity.ok().build();
        }

        // Dormant-org guard (closes the deferred set-default ORG_PAUSED case):
        // refuse to make a "paused" org (owner no longer on a team plan, this
        // user is not the owner) the default - mirrors the gateway/sidebar
        // enforcement so a member can't strand themselves on (or re-pin) a
        // workspace they cannot actually enter. Re-fetched via
        // findActiveByOrganizationIdAndUserId which JOIN FETCHes the owner, so
        // canMemberActInOrg resolves the owner's plan without a lazy round-trip.
        // Placed AFTER the idempotent no-op so re-confirming an EXISTING default
        // never 409s. Skipped when the resolver isn't wired (slim tests / CE-free).
        if (planResolutionService != null) {
            OrganizationMember forGate = memberRepository
                    .findActiveByOrganizationIdAndUserId(orgId, userId)
                    .orElse(targetMembership.get());
            if (!planResolutionService.canMemberActInOrg(forGate)) {
                log.info("setDefault rejected: org {} is paused for user {}", orgId, userId);
                return ResponseEntity.status(409).build();
            }
        }

        // PR11d-b - rate-limit the flip when it's a REAL switch (different org).
        // CE (auth.mode=embedded): skip entirely - a single-tenant self-hosted install has no
        // multi-org quota-bypass surface to defend, and a 60s block on switching your OWN workspace
        // is pure friction. Cloud keeps the limit. userOpt is still resolved (used below to stamp).
        java.util.Optional<com.apimarketplace.auth.domain.User> userOpt = userRepository.findById(userId);
        if (!isEmbeddedAuthMode() && userOpt.isPresent()) {
            java.time.LocalDateTime lastFlip = userOpt.get().getLastDefaultFlipAt();
            if (lastFlip != null) {
                long elapsed = java.time.Duration.between(lastFlip, java.time.LocalDateTime.now()).getSeconds();
                if (elapsed < setDefaultCooldownSeconds) {
                    long retryAfter = setDefaultCooldownSeconds - elapsed;
                    log.info("setDefault rate-limited user={} elapsed={}s cooldown={}s",
                            userId, elapsed, setDefaultCooldownSeconds);
                    return ResponseEntity.status(429)
                            .header("Retry-After", String.valueOf(retryAfter))
                            .build();
                }
            }
        }

        if (currentDefault.isPresent()) {
            OrganizationMember current = currentDefault.get();
            current.setDefault(false);
            memberRepository.save(current);
        }

        OrganizationMember target = targetMembership.get();
        target.setDefault(true);
        memberRepository.save(target);

        // PR11d-b - stamp the flip time AFTER the membership write so a
        // failed flip (DB exception on save) doesn't burn the cooldown.
        userOpt.ifPresent(u -> {
            u.setLastDefaultFlipAt(java.time.LocalDateTime.now());
            userRepository.save(u);
        });

        // PR1 (Bug-1): bust the gateway's user-resolution cache so the new
        // default-org context is visible on the next request instead of
        // lagging by up to 5 min (QuotaCacheService TTL). Best-effort: the
        // GatewayCacheClient logs+swallows on failure - the cache will self-
        // heal at the next TTL boundary even if this call drops.
        userRepository.findById(userId).ifPresent(u -> {
            String providerId = u.getProviderId();
            if (providerId != null && !providerId.isBlank()) {
                gatewayCacheClient.invalidateUserCache(providerId);
            }
        });

        return ResponseEntity.ok().build();
    }

    // ===== WORKSPACE AVATAR (single image per org; mirrors user-avatar storage) =====

    /**
     * Upload (or replace) the workspace avatar. OWNER/ADMIN only. The image is
     * stored in {@code storage.storage} (tenant = orgId) and {@code avatar_url}
     * points at the new storage UUID. The {@code ?v=storageId} query on the
     * returned URL busts the browser cache so the new image shows immediately.
     */
    @PostMapping(value = "/{orgId}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadOrgAvatar(
            @PathVariable UUID orgId,
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-User-ID", required = false) Long userId) {

        ResponseEntity<?> denied = requireOrgManager(orgId, userId);
        if (denied != null) {
            return denied;
        }
        try {
            String storageId = organizationService.uploadAvatar(
                    orgId, file.getBytes(), file.getContentType(), file.getOriginalFilename());
            return ResponseEntity.ok(Map.of(
                    "storageId", storageId,
                    "avatarUrl", "/api/organizations/" + orgId + "/avatar?v=" + storageId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Workspace avatar upload failed for org {}", orgId, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to upload avatar"));
        }
    }

    /**
     * Remove the workspace avatar (revert to the initials fallback).
     * OWNER/ADMIN only. Idempotent.
     */
    @DeleteMapping("/{orgId}/avatar")
    public ResponseEntity<?> deleteOrgAvatar(
            @PathVariable UUID orgId,
            @RequestHeader(value = "X-User-ID", required = false) Long userId) {

        ResponseEntity<?> denied = requireOrgManager(orgId, userId);
        if (denied != null) {
            return denied;
        }
        organizationService.deleteAvatar(orgId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Serve the workspace avatar image. Public (GET) - never returns 404: the
     * uploaded image when present, else a deterministic initials SVG derived
     * from the workspace name (mirrors the user-avatar endpoint so the
     * {@code <img>} never breaks on a delete/upload race).
     */
    @GetMapping("/{orgId}/avatar")
    public ResponseEntity<byte[]> getOrgAvatar(@PathVariable UUID orgId) {
        Optional<StorageEntity> entityOpt = organizationService.getAvatarEntity(orgId);
        if (entityOpt.isPresent() && entityOpt.get().getDataBinary() != null) {
            StorageEntity entity = entityOpt.get();
            String mimeType = entity.getMimeType() != null ? entity.getMimeType() : "image/jpeg";
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(mimeType))
                    // Mutable resource on a stable URL (re-upload / workspace rename flips
                    // the bytes). A bare max-age pinned a stale avatar for a day; no-cache
                    // forces revalidation so a changed workspace avatar shows immediately.
                    // (No ETag here yet, so revalidation returns a full 200 - negligible:
                    // workspace avatars are few and small. The user-avatar endpoint adds an
                    // ETag for cheap 304s; mirror it here if org-avatar traffic ever grows.)
                    .cacheControl(CacheControl.noCache())
                    .body(entity.getDataBinary());
        }
        byte[] svg = organizationService.findById(orgId)
                .map(o -> InitialsAvatarGenerator.generateSvg(null, null, cleanWorkspaceName(o.getName()), null))
                .orElseGet(() -> InitialsAvatarGenerator.generateUnknownSvg("org:" + orgId));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("image/svg+xml"))
                // Initials SVG changes on rename - never pin it for a day.
                .cacheControl(CacheControl.noCache())
                .body(svg);
    }

    /**
     * Returns a 4xx ResponseEntity when the user may not manage the org's avatar
     * (missing header, not a member, or not OWNER/ADMIN); {@code null} when allowed.
     */
    private ResponseEntity<?> requireOrgManager(UUID orgId, Long userId) {
        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }
        Optional<OrganizationMember> membership = memberRepository.findByOrganization_IdAndUser_Id(orgId, userId);
        if (membership.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        OrganizationRole role = membership.get().getRole();
        if (role != OrganizationRole.OWNER && role != OrganizationRole.ADMIN) {
            return ResponseEntity.status(403).body(Map.of("error", "Only OWNER or ADMIN can change the workspace avatar"));
        }
        return null;
    }

    /**
     * Strip the possessive "'s" and the generic "Workspace" word so the initials
     * fallback matches the frontend chip ("ada lovelace's Workspace" → "AL").
     */
    private static String cleanWorkspaceName(String name) {
        if (name == null) {
            return null;
        }
        return name.replaceAll("(?i)['’]s\\b", "")
                   .replaceAll("(?i)\\bworkspace\\b", "")
                   .trim();
    }

    /**
     * Member voluntarily leaves an organization. The OWNER cannot leave -
     * they must transferOwnership first (PR-4c). PR-4a.
     *
     * Returns:
     *   204 No Content on success
     *   400 if X-User-ID missing
     *   404 if the user is not a member of this org
     *   409 if the user is the OWNER (must transfer first)
     */
    @PostMapping("/{orgId}/leave")
    public ResponseEntity<?> leaveOrganization(
            @PathVariable UUID orgId,
            @RequestHeader(value = "X-User-ID", required = false) Long userId) {

        if (userId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "X-User-ID header required"));
        }

        try {
            memberService.leave(orgId, userId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            // Not a member - same response shape as set-default to keep the contract uniform.
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            // OWNER cannot leave - surface the message so the frontend can guide the user
            // to transferOwnership instead of swallowing it silently.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "OWNER_CANNOT_LEAVE",
                    "message", e.getMessage()));
        }
    }

    /**
     * Transfer org ownership from current OWNER to an existing member.
     * PR-4c. Body: {"newOwnerUserId": 42}
     *
     * Returns:
     *   204 No Content on success
     *   400 if body invalid or current user is the proposed new owner
     *   403 if requester is not the OWNER
     *   404 if requester or target is not a member
     */
    @PostMapping("/{orgId}/transfer-ownership")
    public ResponseEntity<?> transferOwnership(
            @PathVariable UUID orgId,
            @RequestHeader(value = "X-User-ID", required = false) Long userId,
            @RequestBody Map<String, Object> body) {

        if (userId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "X-User-ID header required"));
        }
        Object raw = body.get("newOwnerUserId");
        if (raw == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "newOwnerUserId is required"));
        }
        Long newOwnerUserId;
        try {
            newOwnerUserId = (raw instanceof Number n) ? n.longValue() : Long.parseLong(raw.toString());
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "newOwnerUserId must be numeric"));
        }

        try {
            memberService.transferOwnership(orgId, userId, newOwnerUserId);
            return ResponseEntity.noContent().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "errorCode", "ONLY_OWNER_CAN_TRANSFER",
                    "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            // Not a member (current or target) - 404 to avoid existence leak
            // about who is/isn't in the org.
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            // No-op transfer (same user as current owner).
            return ResponseEntity.badRequest().body(Map.of(
                    "errorCode", "TRANSFER_TO_SELF",
                    "message", e.getMessage()));
        }
    }

    /**
     * Soft-delete an organization. OWNER + GitHub-style name confirmation.
     * PR-cascade simplified. Body: {"confirmName": "Exact Org Name"}.
     *
     * Returns:
     *   204 No Content on success (org is soft-deleted, hard-purge cron TBD)
     *   400 if body invalid OR confirmName mismatch OR org already deleted
     *   403 if requester is not the OWNER
     *   404 if requester is not a member / org not found
     */
    @DeleteMapping("/{orgId}")
    public ResponseEntity<?> deleteOrganization(
            @PathVariable UUID orgId,
            @RequestHeader(value = "X-User-ID", required = false) Long userId,
            @RequestBody(required = false) Map<String, Object> body) {

        if (userId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "X-User-ID header required"));
        }
        if (body == null || body.get("confirmName") == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "errorCode", "CONFIRM_NAME_REQUIRED",
                    "message", "Body must include confirmName matching the organization's exact name"));
        }
        String confirmName = body.get("confirmName").toString();

        try {
            memberService.softDeleteOrganization(orgId, userId, confirmName);
            return ResponseEntity.noContent().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "errorCode", "ONLY_OWNER_CAN_DELETE",
                    "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            // Org not found / requester not a member - 404 anti-leak.
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            // confirmName mismatch OR already deleted.
            return ResponseEntity.badRequest().body(Map.of(
                    "errorCode", "DELETE_NOT_CONFIRMED",
                    "message", e.getMessage()));
        }
    }

    /**
     * Restore a soft-deleted workspace within its grace window. OWNER-only.
     *
     * Returns:
     *   204 No Content on success (deleted_at cleared, workspace visible again)
     *   400 if X-User-ID missing, the workspace is not deleted, or it was already purged
     *   403 if requester is not the OWNER
     *   404 if requester is not a member / org not found
     */
    @PostMapping("/{orgId}/restore")
    public ResponseEntity<?> restoreOrganization(
            @PathVariable UUID orgId,
            @RequestHeader(value = "X-User-ID", required = false) Long userId) {

        if (userId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "X-User-ID header required"));
        }
        try {
            memberService.restoreOrganization(orgId, userId);
            return ResponseEntity.noContent().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "errorCode", "ONLY_OWNER_CAN_RESTORE",
                    "message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            // Org not found / requester not a member - 404 anti-leak.
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            // Not deleted, or already purged (data gone).
            return ResponseEntity.badRequest().body(Map.of(
                    "errorCode", "RESTORE_NOT_POSSIBLE",
                    "message", e.getMessage()));
        }
    }

    /**
     * Org audit log (PR-4b MVP). Accessible by OWNER or ADMIN. Returns
     * newest events first. Optional {@code category} param filters by
     * event_type (e.g. {@code ORG_MEMBER_INVITED}).
     */
    @GetMapping("/{orgId}/audit-log")
    public ResponseEntity<?> getAuditLog(
            @PathVariable UUID orgId,
            @RequestHeader(value = "X-User-ID", required = false) Long userId,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {

        if (userId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "X-User-ID header required"));
        }

        // Authorize: must be OWNER or ADMIN of the org.
        var membership = memberRepository.findByOrganization_IdAndUser_Id(orgId, userId);
        if (membership.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        OrganizationRole role = membership.get().getRole();
        if (role != OrganizationRole.OWNER && role != OrganizationRole.ADMIN) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "errorCode", "ONLY_OWNER_ADMIN_CAN_READ_AUDIT",
                    "message", "Only OWNER or ADMIN can read the audit log"));
        }

        int safeSize = Math.min(Math.max(size, 1), 200);
        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(Math.max(page, 0), safeSize);

        org.springframework.data.domain.Page<com.apimarketplace.auth.domain.OrganizationAuditEvent> result =
                (category != null && !category.isBlank())
                        ? auditEventRepository.findByOrgIdAndEventTypeOrderByCreatedAtDesc(orgId, category, pageable)
                        : auditEventRepository.findByOrgIdOrderByCreatedAtDesc(orgId, pageable);

        var items = result.getContent().stream().map(e -> {
            java.util.Map<String, Object> dto = new java.util.LinkedHashMap<>();
            dto.put("id", e.getId());
            dto.put("eventType", e.getEventType());
            dto.put("actorUserId", e.getActorUserId());
            dto.put("eventData", e.getEventData());
            dto.put("createdAt", e.getCreatedAt());
            return dto;
        }).toList();

        // Resolve every user id referenced on this page (actor + the
        // target/owner ids inside event_data) to a display name, so the UI
        // shows "Jane Doe" instead of "user #5". Single batched lookup.
        java.util.Set<Long> referencedUserIds = new java.util.HashSet<>();
        for (var e : result.getContent()) {
            if (e.getActorUserId() != null) {
                referencedUserIds.add(e.getActorUserId());
            }
            Map<String, Object> d = e.getEventData();
            if (d != null) {
                for (String key : new String[]{"targetUserId", "previousOwnerUserId", "newOwnerUserId"}) {
                    Long id = asLong(d.get(key));
                    if (id != null) {
                        referencedUserIds.add(id);
                    }
                }
            }
        }
        // Resolve to the SAME display name shown in the sidebar / members table
        // (user_onboarding.display_name), not raw first/last - so the log reads
        // "… by ada lovelace", not "… by user #1".
        Map<String, String> userNames = new java.util.LinkedHashMap<>();
        onboardingService.resolveDisplayNames(referencedUserIds)
                .forEach((id, name) -> userNames.put(String.valueOf(id), name));

        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("items", items);
        response.put("totalCount", result.getTotalElements());
        response.put("page", result.getNumber());
        response.put("size", result.getSize());
        response.put("userNames", userNames);
        return ResponseEntity.ok(response);
    }

    /** Coerce a JSONB value (Number or String) to a user id, or null. */
    private static Long asLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    // (display-name resolution moved to OnboardingService.resolveDisplayNames -
    //  uses user_onboarding.display_name, the same name shown in the sidebar.)

    // ==================== Member Management Endpoints ====================

    /**
     * Invite a member to the organization by email.
     */
    @PostMapping("/{orgId}/members/invite")
    public ResponseEntity<?> inviteMember(
            @PathVariable UUID orgId,
            @RequestHeader(value = "X-User-ID", required = false) Long userId,
            @RequestBody Map<String, String> body) {

        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }

        String email = body.get("email");
        String roleStr = body.getOrDefault("role", "MEMBER");

        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Email is required"));
        }

        OrganizationRole role;
        try {
            role = OrganizationRole.valueOf(roleStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid role: " + roleStr));
        }

        try {
            OrganizationInvitation invitation = memberService.inviteMember(orgId, email.trim().toLowerCase(), role, userId);
            // Resolve the inviter through the shared fallback chain (display_name →
            // full name → username → email) so a CE inviter without an onboarding
            // row shows their real name instead of "Unknown".
            String inviterName = onboardingService.resolveDisplayName(userId);
            // S-4: always-PENDING factory - see InvitationDto.forInviteResponse.
            // CE (embedded) also gets the raw token so the admin can copy an
            // accept link; cloud omits it (email-only delivery).
            return ResponseEntity.ok(InvitationDto.forInviteResponse(invitation, inviterName, isEmbeddedAuthMode()));
        } catch (com.apimarketplace.auth.service.InvitationRateLimitException e) {
            return ResponseEntity.status(429).body(Map.of("error", e.getMessage()));
        } catch (UnsupportedOperationException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * List pending invitations for an organization.
     */
    @GetMapping("/{orgId}/invitations")
    public ResponseEntity<?> getPendingInvitations(
            @PathVariable UUID orgId,
            @RequestHeader(value = "X-User-ID", required = false) Long userId) {

        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }

        try {
            List<OrganizationInvitation> invitations = memberService.getPendingInvitations(orgId, userId);
            boolean includeToken = isEmbeddedAuthMode();
            // Batch-resolve the inviter names through the shared fallback chain so a
            // CE inviter without an onboarding row reads as their real name, not
            // "Unknown".
            Map<Long, String> inviterNames = onboardingService.resolveDisplayNames(
                    invitations.stream().map(inv -> inv.getInvitedBy().getId()).toList());
            List<InvitationDto> dtos = invitations.stream()
                    // CE: expose the token so an admin can re-copy the accept link
                    // for a still-pending invite. Cloud keeps it hidden.
                    .map(inv -> new InvitationDto(
                            inv, inviterNames.get(inv.getInvitedBy().getId()), includeToken))
                    .toList();
            return ResponseEntity.ok(dtos);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Public (no auth) lookup of an invitation by token, for the accept page to
     * prefill the email and choose register-vs-login. Returns
     * {@code {valid, email, organizationName, role, hasAccount}} for a PENDING,
     * non-expired token; {@code {valid:false}} for anything else (missing /
     * unknown / expired / cancelled / accepted) so a bogus token leaks nothing.
     */
    @GetMapping("/invitations/info")
    public ResponseEntity<?> getInvitationInfo(@RequestParam(required = false) String token) {
        OrganizationMemberService.InvitationInfo info = memberService.getInvitationInfo(token);
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("valid", info.valid());
        if (info.valid()) {
            body.put("email", info.email());
            body.put("organizationName", info.organizationName());
            body.put("role", info.role());
            body.put("hasAccount", info.hasAccount());
        }
        return ResponseEntity.ok(body);
    }

    /**
     * PR4b - accept a PENDING invitation by its ID (auth-protected). Used by
     * the /app/invitations inbox where the user is already signed in and the
     * server already knows their email - no need to expose the invitation
     * token client-side. The classic token-clicked path
     * ({@code POST /invitations/accept?token=...}) remains for the email
     * link flow.
     */
    @PostMapping("/invitations/{invId}/accept-by-id")
    public ResponseEntity<?> acceptInvitationById(
            @PathVariable UUID invId,
            @RequestHeader(value = "X-User-ID", required = false) Long userId) {

        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }
        try {
            OrganizationInvitation invitation = memberService.acceptInvitationById(invId, userId);
            Organization org = invitation.getOrganization();
            Optional<OrganizationMember> membership = memberRepository.findByOrganization_IdAndUser_Id(org.getId(), userId);
            int memberCount = (int) memberRepository.countByOrganization_Id(org.getId());
            OrganizationDto dto = OrganizationDto.fromEntity(org, membership.orElse(null), memberCount);
            return ResponseEntity.ok(dto);
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Decline a PENDING invitation by its ID from the authenticated user's inbox.
     */
    @PostMapping("/invitations/{invId}/decline-by-id")
    public ResponseEntity<?> declineInvitationById(
            @PathVariable UUID invId,
            @RequestHeader(value = "X-User-ID", required = false) Long userId) {

        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }
        try {
            OrganizationInvitation invitation = memberService.declineInvitationById(invId, userId);
            String inviterName = onboardingService.resolveDisplayName(invitation.getInvitedBy().getId());
            return ResponseEntity.ok(new InvitationDto(invitation, inviterName));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * PR4b - list the PENDING invitations addressed to the CURRENT user.
     *
     * <p>Powers the /app/invitations inbox page. After PR4a (Q2=a explicit
     * consent) the silent auto-accept was killed - users need a UI to see
     * org invitations addressed to them and click accept. The email link
     * still works as the primary entry point; this inbox is the secondary
     * "I lost the email" path.
     */
    @GetMapping("/invitations/mine")
    public ResponseEntity<?> getMyPendingInvitations(
            @RequestHeader(value = "X-User-ID", required = false) Long userId) {

        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }

        // Resolve the caller's email - invitations are keyed on email, not userId,
        // because they may be created before the recipient has an account.
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getEmail() == null || user.getEmail().isBlank()) {
            return ResponseEntity.ok(List.of());
        }

        List<OrganizationInvitation> invitations = memberService.getPendingInvitationsForEmail(user.getEmail());
        // Batch-resolve the inviter names through the shared fallback chain so a CE
        // inviter without an onboarding row reads as their real name, not "Unknown".
        Map<Long, String> inviterNames = onboardingService.resolveDisplayNames(
                invitations.stream().map(inv -> inv.getInvitedBy().getId()).toList());
        List<InvitationDto> dtos = invitations.stream()
                .map(inv -> new InvitationDto(inv, inviterNames.get(inv.getInvitedBy().getId())))
                .toList();
        return ResponseEntity.ok(dtos);
    }

    /**
     * Cancel a pending invitation.
     */
    @DeleteMapping("/{orgId}/invitations/{invId}")
    public ResponseEntity<?> cancelInvitation(
            @PathVariable UUID orgId,
            @PathVariable UUID invId,
            @RequestHeader(value = "X-User-ID", required = false) Long userId) {

        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }

        try {
            memberService.cancelInvitation(orgId, invId, userId);
            return ResponseEntity.noContent().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Accept an invitation by token.
     */
    @PostMapping("/invitations/accept")
    public ResponseEntity<?> acceptInvitation(
            @RequestParam String token,
            @RequestHeader(value = "X-User-ID", required = false) Long userId) {

        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }

        try {
            OrganizationInvitation invitation = memberService.acceptInvitation(token, userId);
            Organization org = invitation.getOrganization();
            Optional<OrganizationMember> membership = memberRepository.findByOrganization_IdAndUser_Id(org.getId(), userId);
            int memberCount = (int) memberRepository.countByOrganization_Id(org.getId());
            OrganizationDto dto = OrganizationDto.fromEntity(org, membership.orElse(null), memberCount);
            return ResponseEntity.ok(dto);
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Remove a member from an organization. The path parameter is the user id of the
     * member to remove (matches what {@code MemberDto.userId} exposes to clients).
     */
    @DeleteMapping("/{orgId}/members/{targetUserId}")
    public ResponseEntity<?> removeMember(
            @PathVariable UUID orgId,
            @PathVariable Long targetUserId,
            @RequestHeader(value = "X-User-ID", required = false) Long userId) {

        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }

        try {
            memberService.removeMember(orgId, targetUserId, userId);
            return ResponseEntity.noContent().build();
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Change a member's role. The path parameter is the user id of the member whose role
     * is being changed (matches what {@code MemberDto.userId} exposes to clients).
     */
    @PutMapping("/{orgId}/members/{targetUserId}/role")
    public ResponseEntity<?> changeMemberRole(
            @PathVariable UUID orgId,
            @PathVariable Long targetUserId,
            @RequestHeader(value = "X-User-ID", required = false) Long userId,
            @RequestBody Map<String, String> body) {

        if (userId == null) {
            return ResponseEntity.badRequest().build();
        }

        String roleStr = body.get("role");
        if (roleStr == null || roleStr.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Role is required"));
        }

        OrganizationRole newRole;
        try {
            newRole = OrganizationRole.valueOf(roleStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid role: " + roleStr));
        }

        try {
            OrganizationMember updated = memberService.changeRole(orgId, targetUserId, newRole, userId);
            String displayName = onboardingService.resolveDisplayName(updated.getUser().getId());
            if (displayName == null) {
                displayName = updated.getUser().getEmail();
            }
            return ResponseEntity.ok(new OrganizationDto.MemberDto(updated, displayName));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ==================== Private Helpers ====================

    private void enrichWithTeamStatus(OrganizationDto dto, UUID orgId) {
        try {
            OrganizationMemberService.TeamStatus status = memberService.getTeamStatus(orgId);
            dto.setPlanCode(status.planCode());
            dto.setMaxMembers(status.maxMembers());
            dto.setCanInvite(status.canInvite());
            dto.setPendingInvitationCount(status.pendingInvitations());
        } catch (Exception e) {
            log.debug("Could not enrich team status for org {}: {}", orgId, e.getMessage());
            dto.setPlanCode("FREE");
            dto.setMaxMembers(1);
            dto.setCanInvite(false);
            dto.setPendingInvitationCount(0);
        }
    }
}
