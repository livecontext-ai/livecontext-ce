package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.domain.Plan;
import com.apimarketplace.auth.domain.Subscription;
import com.apimarketplace.auth.domain.User;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.OrganizationRepository;
import com.apimarketplace.auth.repository.PlanRepository;
import com.apimarketplace.auth.repository.SubscriptionRepository;
import com.apimarketplace.common.plan.CloudPlanAccess;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.service.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing organizations and memberships.
 */
@Service
@Transactional
public class OrganizationService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationService.class);

    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository memberRepository;

    // Field-injected (optional) to compute the per-membership "paused" (dormant-org)
    // flag without changing the constructor - keeps existing OrganizationServiceTest
    // wiring green. Same package, no import needed. Null in slim tests → paused=false.
    @Autowired(required = false)
    private PlanResolutionService planResolutionService;

    // Field-injected (optional) so the existing 2-arg constructor + its tests stay
    // green. Backs the workspace-avatar upload/serve/delete (mirrors the user-avatar
    // path in UserService). Null in slim tests → avatar mutators no-op-throw.
    @Autowired(required = false)
    private StorageService storageService;

    // Field-injected (optional) so the 2-arg constructor + slim tests stay green. Back the
    // per-plan workspace cap at creation time. Null in slim tests → cap defaults to 1 (FREE).
    @Autowired(required = false)
    private SubscriptionRepository subscriptionRepository;
    @Autowired(required = false)
    private PlanRepository planRepository;
    // CE↔Cloud: a CLOUD-sourced linked install's workspace cap follows the bound cloud account's
    // plan. Optional/field-injected - null in the cloud deployment, so the cap stays purely local.
    @Autowired(required = false)
    private CloudPlanAccess cloudPlanAccess;

    /** ORG_AVATAR storage source type - one active avatar per organization. */
    private static final String ORG_AVATAR_SOURCE = "ORG_AVATAR";
    private static final int MAX_AVATAR_SIZE = 5 * 1024 * 1024; // 5MB
    private static final java.util.Set<String> ALLOWED_AVATAR_TYPES =
            java.util.Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    public OrganizationService(OrganizationRepository organizationRepository,
                               OrganizationMemberRepository memberRepository) {
        this.organizationRepository = organizationRepository;
        this.memberRepository = memberRepository;
    }

    /**
     * Creates a personal organization for a user (called during onboarding).
     * This operation is idempotent - if a personal org already exists, it returns the existing one.
     *
     * @param user        The user to create the org for
     * @param displayName The user's display name (used to generate org name)
     * @return The created or existing personal organization
     */
    public Organization createPersonalOrganization(User user, String displayName) {
        log.info("🏢 Creating personal organization for user: {} ({})", user.getId(), displayName);

        // Check if personal org already exists (idempotent)
        Optional<Organization> existing = organizationRepository.findByOwnerIdAndIsPersonalTrue(user.getId());
        if (existing.isPresent()) {
            log.info("🏢 Personal organization already exists for user: {}", user.getId());
            return existing.get();
        }

        // Generate unique slug from display name
        String baseSlug = generateSlug(displayName);
        String slug = ensureUniqueSlug(baseSlug);

        // Create organization
        String orgName = displayName + "'s Workspace";
        Organization org = new Organization(orgName, slug, true, user);
        org = organizationRepository.save(org);

        log.info("🏢 Created organization: {} (slug: {}, id: {})", orgName, slug, org.getId());

        // Add user as owner member with isDefault=true
        OrganizationMember member = new OrganizationMember(org, user, OrganizationRole.OWNER, true);
        memberRepository.save(member);

        log.info("🏢 Added user {} as OWNER of organization {}", user.getId(), org.getId());

        return org;
    }

    /**
     * Create an additional (non-personal) workspace owned by {@code owner}, gated by the owner's
     * plan {@code max_workspaces} cap. Shared-wallet model (ADR-009): the new workspace draws from
     * the owner's existing credit wallet - no new subscription. The owner is added as OWNER member
     * with {@code is_default=false} (switching into it is an explicit action by the caller).
     *
     * @throws IllegalArgumentException if the name is blank
     * @throws IllegalStateException    if the owner is already at their plan's workspace cap
     */
    public Organization createOrganization(User owner, String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Workspace name is required");
        }
        // Cap check is a soft entitlement, not a security boundary. It's a plain count-then-insert
        // with no row lock / DB constraint, so two concurrent creates can both pass and exceed the
        // cap by one. Acceptable (worst case: one extra workspace; no billing/security impact). If
        // it ever needs to be exact, lock under findByIdForUpdate or add a per-owner count constraint.
        Integer max = resolveMaxWorkspaces(owner.getId());
        if (max != null) {
            long owned = organizationRepository.countByOwnerIdAndDeletedAtIsNull(owner.getId());
            if (owned >= max) {
                throw new IllegalStateException(
                        "Workspace limit reached (" + max + "). Upgrade your plan to create more workspaces.");
            }
        }

        String slug = ensureUniqueSlug(generateSlug(name));
        Organization org = new Organization(name.trim(), slug, false, owner);
        org = organizationRepository.save(org);

        OrganizationMember member = new OrganizationMember(org, owner, OrganizationRole.OWNER, false);
        memberRepository.save(member);

        log.info("🏢 Created workspace {} (slug {}, owner {}, cap {})", org.getId(), slug, owner.getId(), max);
        return org;
    }

    /**
     * The owner's plan workspace cap (NULL = unlimited). Resolves the active subscription's plan,
     * else the FREE plan. Falls back to 1 (single workspace) when no plan row is resolvable so a
     * missing seed/slim wiring never accidentally grants unlimited workspaces.
     */
    private Integer resolveMaxWorkspaces(Long ownerId) {
        Plan plan = null;
        // CE↔Cloud: when CLOUD-linked, the workspace cap follows the bound cloud account's plan
        // (resolved locally by code - CE and cloud share identical plan rows). So creating extra
        // workspaces requires the cloud plan to allow them, exactly like cloud.
        if (cloudPlanAccess != null && planRepository != null) {
            plan = cloudPlanAccess.governingPlanCode(ownerId).flatMap(planRepository::findByCode).orElse(null);
        }
        if (plan == null && subscriptionRepository != null) {
            plan = subscriptionRepository.findActiveByUserId(ownerId).map(Subscription::getPlan).orElse(null);
        }
        if (plan == null && planRepository != null) {
            plan = planRepository.findByCode("FREE").orElse(null);
        }
        // No plan row resolvable → conservative single-workspace cap. Otherwise return the plan's
        // value AS-IS (NULL = unlimited). NB: do NOT use a `plan != null ? plan.getMaxWorkspaces() : 1`
        // ternary - mixing Integer + int auto-unboxes and NPEs on an unlimited (null) cap.
        if (plan == null) {
            return 1;
        }
        return plan.getMaxWorkspaces();
    }

    /**
     * Reconcile the OWNER's workspace pause-state to their current plan's
     * workspace cap (V311). Keeps the personal workspace + the (cap-1) OLDEST
     * non-personal workspaces ACTIVE and PAUSES the most-recently-created excess.
     * On upgrade (cap grows / unlimited) the formerly-paused workspaces are
     * un-paused up to the new cap. Idempotent - only rows whose state actually
     * changes are written.
     *
     * <p>Invoked on every plan change (SubscriptionService / AdminPlanService).
     * The pause is enforced by {@code PlanResolutionService.canMemberActInOrg} /
     * {@code resolvePausedOrgIds}, which the gateway resolve, the {@code /me}
     * workspace list, and the setDefault gate all funnel through - so a paused
     * workspace becomes non-enterable for the owner AND every member, and the
     * request edge falls back to the personal workspace.
     */
    @Transactional
    public void reconcileWorkspacePauseState(Long ownerId) {
        if (ownerId == null) return;
        Integer cap = resolveMaxWorkspaces(ownerId);
        List<Organization> owned =
                organizationRepository.findByOwnerIdAndDeletedAtIsNullOrderByCreatedAtAsc(ownerId);

        // cap counts the personal workspace, so the non-personal budget is cap-1.
        // NULL cap = unlimited → keep (and un-pause) everything.
        int nonPersonalBudget = cap == null ? Integer.MAX_VALUE : Math.max(0, cap - 1);
        int keptNonPersonal = 0;

        LocalDateTime now = LocalDateTime.now();
        int paused = 0, unpaused = 0;
        for (Organization org : owned) {
            boolean shouldPause;
            if (org.isPersonal()) {
                shouldPause = false;                 // personal is never paused
            } else if (keptNonPersonal < nonPersonalBudget) {
                keptNonPersonal++;                   // oldest-first → keep the head
                shouldPause = false;
            } else {
                shouldPause = true;                  // most-recent excess → pause
            }

            if (shouldPause && !org.isPaused()) {
                org.setPausedAt(now);
                organizationRepository.save(org);
                paused++;
            } else if (!shouldPause && org.isPaused()) {
                org.setPausedAt(null);
                organizationRepository.save(org);
                unpaused++;
            }
        }
        if (paused > 0 || unpaused > 0) {
            log.info("🔄 Workspace pause reconcile (owner {}, cap {}): paused {}, un-paused {}",
                    ownerId, cap, paused, unpaused);
        }
    }

    /**
     * Gets the user's default organization.
     *
     * @param userId User ID
     * @return Optional containing the default organization
     */
    @Transactional(readOnly = true)
    public Optional<Organization> getDefaultOrganization(Long userId) {
        return memberRepository.findActiveDefaultByUserId(userId)
                .map(OrganizationMember::getOrganization);
    }

    /**
     * Gets the user's default organization membership (includes org + role).
     *
     * <p>Filters out SOFT-DELETED orgs (deletedAt IS NULL): a workspace pending
     * purge is never enterable, so it must not be returned as the user's default
     * workspace context. Feeds {@code UserResolutionResponse.defaultOrganizationId}
     * - if a deleted org leaked here, the gateway would stamp it as the active org
     * downstream and strand the user on a deleted workspace (prod bug 2026-06-06).
     * The soft-delete flow already clears {@code is_default} on the deleted
     * membership and promotes a live one; this is the defense-in-depth guarantee.
     *
     * @param userId User ID
     * @return Optional containing the default (non-deleted) OrganizationMember
     */
    @Transactional(readOnly = true)
    public Optional<OrganizationMember> getDefaultMembership(Long userId) {
        return memberRepository.findActiveDefaultByUserId(userId);
    }

    /**
     * Gets the user's default organization ID as a string (for headers).
     *
     * @param userId User ID
     * @return Optional containing the default organization ID as string
     */
    @Transactional(readOnly = true)
    public Optional<String> getDefaultOrganizationId(Long userId) {
        return getDefaultOrganization(userId)
                .map(org -> org.getId().toString());
    }

    /**
     * Gets all organizations a user belongs to.
     *
     * @param userId User ID
     * @return List of organizations
     */
    @Transactional(readOnly = true)
    public List<Organization> getUserOrganizations(Long userId) {
        return memberRepository.findByUserIdWithOrganization(userId).stream()
                .map(OrganizationMember::getOrganization)
                .toList();
    }

    /**
     * Returns a lightweight list of (orgId, role) pairs for every non-deleted
     * org the user belongs to. Consumed by the gateway via
     * {@link com.apimarketplace.auth.dto.UserResolutionResponse#getMemberships()}
     * to validate active-org claims sent by the frontend without an extra
     * HTTP round-trip (PR0.5 of the org/membership redesign).
     */
    @Transactional(readOnly = true)
    public List<com.apimarketplace.auth.dto.OrgMembershipDto> listUserMembershipsDto(Long userId) {
        List<OrganizationMember> memberships = memberRepository.findByUserIdWithOrganization(userId);
        // "Dormant" orgs: a non-owner team membership whose owner is no longer on a
        // team plan. Computed in ONE batched query (no per-org N+1). The gateway reads
        // the resulting `paused` flag to reject active-org claims for these orgs.
        java.util.Set<UUID> pausedOrgIds = planResolutionService != null
                ? planResolutionService.resolvePausedOrgIds(memberships)
                : java.util.Set.of();
        return memberships.stream()
                .map(m -> new com.apimarketplace.auth.dto.OrgMembershipDto(
                        m.getOrganization().getId().toString(),
                        m.getRole().name(),
                        // Surfaces is_personal to the gateway/frontend so the UI
                        // can render the right workspace pill + workflow-builder
                        // breadcrumb. Owner-pays no longer depends on this flag
                        // for credit-read routing (every read resolves the payer
                        // and queries the owner's wallet by user_id).
                        m.getOrganization().isPersonal(),
                        pausedOrgIds.contains(m.getOrganization().getId())))
                .toList();
    }

    /**
     * Gets an organization by ID.
     *
     * @param organizationId Organization UUID
     * @return Optional containing the organization
     */
    @Transactional(readOnly = true)
    public Optional<Organization> findById(UUID organizationId) {
        return organizationRepository.findById(organizationId);
    }

    /**
     * Gets an organization by slug.
     *
     * @param slug Organization slug
     * @return Optional containing the organization
     */
    @Transactional(readOnly = true)
    public Optional<Organization> findBySlug(String slug) {
        return organizationRepository.findBySlug(slug);
    }

    /**
     * Checks if a user is a member of an organization.
     *
     * @param organizationId Organization UUID
     * @param userId         User ID
     * @return true if user is a member
     */
    @Transactional(readOnly = true)
    public boolean isMember(UUID organizationId, Long userId) {
        return memberRepository.existsByOrganization_IdAndUser_Id(organizationId, userId);
    }

    /**
     * Gets a user's membership in an organization.
     *
     * @param organizationId Organization UUID
     * @param userId         User ID
     * @return Optional containing the membership
     */
    @Transactional(readOnly = true)
    public Optional<OrganizationMember> getMembership(UUID organizationId, Long userId) {
        return memberRepository.findByOrganization_IdAndUser_Id(organizationId, userId);
    }

    /**
     * Saves an organization entity.
     */
    public Organization save(Organization org) {
        return organizationRepository.save(org);
    }

    // ===== WORKSPACE AVATAR =====
    // Mirrors the user-avatar storage path (UserService.uploadAvatar), but
    // single-avatar: one active image per org, replacing on re-upload. The org's
    // avatar_url column holds the storage UUID (or null → initials fallback).

    /**
     * Upload (or replace) the workspace avatar. Stores the binary in
     * {@code storage.storage} under tenant = orgId, deletes any previous
     * ORG_AVATAR for this org (single-avatar), and points {@code avatar_url}
     * at the new storage UUID.
     *
     * @return the new storage UUID string
     * @throws IllegalArgumentException on empty/oversized/unsupported image
     * @throws IllegalStateException    when storage is not wired (slim tests)
     */
    public String uploadAvatar(UUID orgId, byte[] data, String mimeType, String fileName) {
        if (storageService == null) {
            throw new IllegalStateException("StorageService not available");
        }
        if (data == null || data.length == 0) {
            throw new IllegalArgumentException("Avatar file is empty");
        }
        if (data.length > MAX_AVATAR_SIZE) {
            throw new IllegalArgumentException("Avatar exceeds maximum size of 5MB");
        }
        if (!ALLOWED_AVATAR_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException("Unsupported image type: " + mimeType);
        }

        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + orgId));
        String tenantId = org.getId().toString();

        // Single-avatar: drop any previous image(s) so storage doesn't accumulate
        // orphans on every replace.
        for (StorageEntity prev : storageService.listByTenantAndSourceType(tenantId, ORG_AVATAR_SOURCE)) {
            storageService.deleteById(prev.getId(), tenantId);
        }

        UUID storageId = storageService.saveBinary(tenantId, data, fileName, mimeType, null, ORG_AVATAR_SOURCE);
        org.setAvatarUrl(storageId.toString());
        organizationRepository.save(org);

        log.info("Workspace avatar uploaded for org {}: storageId={}, size={} bytes",
                org.getId(), storageId, data.length);
        return storageId.toString();
    }

    /**
     * Returns the org's stored avatar binary, or empty when none is set (caller
     * serves an initials fallback). Resolves by the avatar_url UUID first, then
     * falls back to the latest stored ORG_AVATAR if the column drifted.
     *
     * <p>NOT {@code readOnly} on purpose: {@code StorageService.getEntityById}
     * bumps {@code storage.accessed_at} (an UPDATE), which Postgres rejects
     * inside a read-only tx ("cannot execute UPDATE in a read-only transaction")
     * - that 500'd the avatar GET. The user-avatar path (UserService) is also
     * non-readOnly for the same reason.
     */
    @Transactional
    public Optional<StorageEntity> getAvatarEntity(UUID orgId) {
        if (storageService == null) {
            return Optional.empty();
        }
        String tenantId = orgId.toString();
        Optional<Organization> orgOpt = organizationRepository.findById(orgId);
        if (orgOpt.isEmpty()) {
            return Optional.empty();
        }
        String avatarUrl = orgOpt.get().getAvatarUrl();
        if (avatarUrl != null) {
            try {
                UUID storageId = UUID.fromString(avatarUrl);
                Optional<StorageEntity> byId = storageService.getEntityById(storageId, tenantId);
                if (byId.isPresent()) {
                    return byId;
                }
            } catch (IllegalArgumentException ignored) {
                // avatar_url isn't a storage UUID - fall through to the list lookup
            }
        }
        List<StorageEntity> stored = storageService.listByTenantAndSourceType(tenantId, ORG_AVATAR_SOURCE);
        return stored.isEmpty() ? Optional.empty() : Optional.of(stored.get(0));
    }

    /**
     * Remove the workspace avatar: deletes the stored image(s) and clears
     * {@code avatar_url} so the UI reverts to the initials fallback. Idempotent.
     */
    public void deleteAvatar(UUID orgId) {
        Organization org = organizationRepository.findById(orgId)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + orgId));
        String tenantId = org.getId().toString();
        if (storageService != null) {
            for (StorageEntity prev : storageService.listByTenantAndSourceType(tenantId, ORG_AVATAR_SOURCE)) {
                storageService.deleteById(prev.getId(), tenantId);
            }
        }
        if (org.getAvatarUrl() != null) {
            org.setAvatarUrl(null);
            organizationRepository.save(org);
        }
        log.info("Workspace avatar removed for org {}", org.getId());
    }

    /**
     * Generates a URL-friendly slug from a display name.
     * Handles accented characters and special characters.
     */
    private String generateSlug(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "workspace";
        }

        // Normalize accented characters to ASCII
        String normalized = Normalizer.normalize(displayName, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");

        // Convert to lowercase and replace non-alphanumeric with hyphens
        String slug = normalized.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "")  // Remove leading/trailing hyphens
                .replaceAll("-+", "-");    // Collapse multiple hyphens

        // Ensure slug is not empty
        if (slug.isBlank()) {
            slug = "workspace";
        }

        // Limit length
        if (slug.length() > 50) {
            slug = slug.substring(0, 50).replaceAll("-$", "");
        }

        return slug;
    }

    /**
     * Ensures the slug is unique by appending a counter if necessary.
     */
    private String ensureUniqueSlug(String baseSlug) {
        String slug = baseSlug;
        int counter = 1;

        while (organizationRepository.existsBySlug(slug)) {
            slug = baseSlug + "-" + counter++;
            // Prevent infinite loop (should never happen)
            if (counter > 1000) {
                slug = baseSlug + "-" + UUID.randomUUID().toString().substring(0, 8);
                break;
            }
        }

        return slug;
    }
}
