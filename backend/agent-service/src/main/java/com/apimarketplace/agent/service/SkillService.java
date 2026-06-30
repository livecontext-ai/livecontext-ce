package com.apimarketplace.agent.service;

import com.apimarketplace.common.storage.service.StorageBreakdownService;
import com.apimarketplace.common.scope.ScopeGuard;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.agent.domain.AgentSkillEntity;
import com.apimarketplace.agent.domain.SkillEntity;
import com.apimarketplace.agent.domain.UserSkillOverrideEntity;
import com.apimarketplace.agent.repository.AgentSkillRepository;
import com.apimarketplace.agent.repository.AgentRepository;
import com.apimarketplace.agent.repository.SkillRepository;
import com.apimarketplace.agent.repository.UserSkillOverrideRepository;
import com.apimarketplace.agent.skills.DefaultSkillsProvider;
import com.apimarketplace.agent.skills.DefaultSkillsProvider.DefaultSkill;
import com.apimarketplace.auth.client.AuthClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class SkillService {

    private static final Logger log = LoggerFactory.getLogger(SkillService.class);

    @Value("${skill.max-per-agent:10}")
    private int maxSkillsPerAgent;

    private final SkillRepository skillRepository;
    private final AgentSkillRepository agentSkillRepository;
    private final AgentRepository agentRepository;
    private final StorageBreakdownService breakdownService;
    private final UserSkillOverrideRepository userSkillOverrideRepository;

    /**
     * Optional (field-injected so the positional test constructors stay valid).
     * Used by {@link #seedDefaultSkills} to stamp seeded built-in rows with the
     * tenant's PERSONAL org instead of whatever workspace the first skills list
     * happens to run under - a team-workspace first fetch must not turn the
     * user's personal defaults into org-shared rows.
     */
    @Autowired(required = false)
    private AuthClient authClient;

    /**
     * Optional, same injection rationale as {@link #authClient}. Each seeded
     * row is inserted in its own REQUIRES_NEW transaction so a concurrent
     * seeder hitting the V334 partial unique index (tenant_id, default_key)
     * only kills that inner insert - the caller's listSkills transaction
     * survives and the loser logs + skips. Null (unit tests) → plain save.
     */
    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    public SkillService(SkillRepository skillRepository,
                        AgentSkillRepository agentSkillRepository,
                        AgentRepository agentRepository,
                        StorageBreakdownService breakdownService,
                        UserSkillOverrideRepository userSkillOverrideRepository) {
        this.skillRepository = skillRepository;
        this.agentSkillRepository = agentSkillRepository;
        this.agentRepository = agentRepository;
        this.breakdownService = breakdownService;
        this.userSkillOverrideRepository = userSkillOverrideRepository;
    }

    public SkillEntity createSkill(String tenantId,
                                    String name,
                                    String description,
                                    String icon,
                                    String instructions) {
        return createSkill(tenantId, name, description, icon, instructions, null, false, false);
    }

    public SkillEntity createSkill(String tenantId,
                                    String name,
                                    String description,
                                    String icon,
                                    String instructions,
                                    UUID folderId) {
        return createSkill(tenantId, name, description, icon, instructions, folderId, false, false);
    }

    public SkillEntity createSkill(String tenantId,
                                    String name,
                                    String description,
                                    String icon,
                                    String instructions,
                                    UUID folderId,
                                    boolean isGlobal,
                                    boolean callerIsAdmin) {
        // Audit 2026-05-17 round-6 - public REST callers (SkillController)
        // never threaded X-Organization-ID, so V217 column was unused for
        // HTTP-created skills. Pull from request context here so back-compat
        // 8-arg callers also stamp the org. Daemon paths still need to use
        // the 9-arg overload explicitly.
        return createSkill(tenantId, name, description, icon, instructions, folderId, isGlobal, callerIsAdmin,
                TenantResolver.currentRequestOrganizationId());
    }

    /**
     * Org-aware overload - stamps the skill's {@code organization_id} (V217
     * column) so org-teammates see it. Audit 2026-05-16.
     */
    public SkillEntity createSkill(String tenantId,
                                    String name,
                                    String description,
                                    String icon,
                                    String instructions,
                                    UUID folderId,
                                    boolean isGlobal,
                                    boolean callerIsAdmin,
                                    String organizationId) {
        validateSkill(tenantId, name, description, instructions);

        if (isGlobal && !callerIsAdmin) {
            throw new IllegalArgumentException("Only admins can create global skills");
        }

        SkillEntity entity = new SkillEntity(
            tenantId,
            name,
            description,
            icon,
            instructions,
            true
        );
        entity.setFolderId(folderId);
        entity.setIsGlobal(isGlobal);
        if (organizationId != null && !organizationId.isBlank()) {
            entity.setOrganizationId(organizationId);
        }

        SkillEntity saved = skillRepository.save(entity);
        long size = instructions != null ? instructions.length() : 0;
        breakdownService.trackSave(tenantId, "CONFIGURATION", size);
        log.info("Skill created: id={}, name={}, folderId={}, global={}, tenant={}, org={}",
            saved.getId(), name, folderId, isGlobal, tenantId, organizationId);
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<SkillEntity> getSkill(UUID id, String tenantId) {
        return getSkill(id, tenantId, TenantResolver.currentRequestOrganizationId());
    }

    /**
     * Phase 6c - org-aware single skill fetch. Routes through the strict-scope
     * finder pair AND falls back to admin-managed globals (which live outside
     * the per-workspace partition and stay visible everywhere - matches the
     * legacy {@code tenantId.equals(s.getTenantId()) || isGlobal} predicate).
     */
    @Transactional(readOnly = true)
    public Optional<SkillEntity> getSkill(UUID id, String tenantId, String organizationId) {
        return findInScopeOrGlobal(id, tenantId, organizationId);
    }

    public List<SkillEntity> listSkills(String tenantId) {
        return listSkills(tenantId, TenantResolver.currentRequestOrganizationId());
    }

    /**
     * Phase 6c - org-aware list. Org-workspace callers see the org's shared
     * skills + every admin-managed global skill. Personal-workspace callers
     * see their tenant-owned skills + globals.
     *
     * <p>2026-06-11 - the default-skills auto-seed runs on BOTH branches (it
     * is per-tenant and idempotent via the V334 unique index): post-V261 real
     * traffic always carries X-Organization-ID, so an org-gated seed never
     * fires. Seeded rows are stamped with the tenant's PERSONAL org inside
     * {@link #seedDefaultSkills}, so the seed is workspace-safe regardless of
     * which branch triggers it.
     */
    public List<SkillEntity> listSkills(String tenantId, String organizationId) {
        String decodedTenantId = tenantId != null ? tenantId.replace("%7C", "|") : tenantId;

        // Auto-seed missing default skills for this tenant - for BOTH branches.
        // Post-V261 every real request carries X-Organization-ID (the cloud
        // gateway and the CE MonolithSecurityFilter both fall back to the
        // caller's default/personal org when no active-org claim is usable),
        // so an org-gated seed never fires: tenants created after Phase 6c
        // (e1c10170f, 2026-05-19) got ZERO built-in skills. Seeding is
        // idempotent per tenant (V334 partial unique index on
        // (tenant_id, default_key)) and the rows are stamped with the tenant's
        // PERSONAL org inside seedDefaultSkills, so running it from an
        // org-workspace list is safe.
        int expectedDefaults = DefaultSkillsProvider.getAll().size();
        long existingDefaults = skillRepository.countByTenantIdAndDefaultKeyIsNotNull(decodedTenantId);
        if (existingDefaults < expectedDefaults) {
            int seeded = seedDefaultSkills(decodedTenantId);
            if (seeded > 0) {
                log.info("Auto-seeded {} default skills for tenant={} (had {}/{})", seeded, decodedTenantId, existingDefaults, expectedDefaults);
            }
        }

        if (organizationId != null && !organizationId.isBlank()) {
            return skillRepository.findVisibleForOrganization(organizationId);
        }
        // Tenant-owned skills + every admin-managed global skill (Deep-Research-like).
        return skillRepository.findVisibleForTenant(decodedTenantId);
    }

    public SkillEntity updateSkill(UUID id,
                                    String tenantId,
                                    String name,
                                    String description,
                                    String icon,
                                    String instructions,
                                    Boolean isActive) {
        return updateSkill(id, tenantId, name, description, icon, instructions, isActive, null, false);
    }

    public SkillEntity updateSkill(UUID id,
                                    String tenantId,
                                    String name,
                                    String description,
                                    String icon,
                                    String instructions,
                                    Boolean isActive,
                                    Boolean isGlobal,
                                    boolean callerIsAdmin) {
        return updateSkill(id, tenantId, TenantResolver.currentRequestOrganizationId(),
                name, description, icon, instructions, isActive, isGlobal, callerIsAdmin);
    }

    /**
     * Phase 6c - org-aware update. Routes through {@link #findInScopeOrGlobal}
     * so org-teammates can edit a workspace-shared skill and personal-scope
     * callers can't reach across into org rows. Globals stay admin-only.
     */
    public SkillEntity updateSkill(UUID id,
                                    String tenantId,
                                    String organizationId,
                                    String name,
                                    String description,
                                    String icon,
                                    String instructions,
                                    Boolean isActive,
                                    Boolean isGlobal,
                                    boolean callerIsAdmin) {
        return updateSkill(id, tenantId, organizationId, name, description, icon, instructions,
                isActive, isGlobal, null, callerIsAdmin);
    }

    /**
     * V275 (2026-05-21) - org-aware update with {@code isDefaultActive}.
     * {@code isDefaultActive} = null means "don't change". Owner toggles it
     * freely on personal skills; on globals the admin gate already enforced
     * above gates this flag too (no separate admin check needed).
     */
    public SkillEntity updateSkill(UUID id,
                                    String tenantId,
                                    String organizationId,
                                    String name,
                                    String description,
                                    String icon,
                                    String instructions,
                                    Boolean isActive,
                                    Boolean isGlobal,
                                    Boolean isDefaultActive,
                                    boolean callerIsAdmin) {
        SkillEntity existing = findInScopeOrGlobal(id, tenantId, organizationId)
            .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + id));

        boolean existingIsGlobal = Boolean.TRUE.equals(existing.getIsGlobal());

        // V374 - a global skill applied from the cloud SKILL BUNDLE is read-only on this
        // install: the cloud owns its content and every re-sync overwrites it, so a local
        // edit (even by an admin) would be silently reverted. Block it explicitly. End users
        // still hide it for themselves via the per-user override (setUserSkillOverride).
        if (existing.getSourceBundleKey() != null) {
            throw new IllegalArgumentException(
                "This global skill is provided by the cloud and is read-only on this install. "
                + "To stop it from appearing in your new chats, deactivate it for yourself.");
        }

        // Global skills are admin-managed: only admins may edit them (from any tenant).
        // Per-tenant / per-org skills are visible because findInScopeOrGlobal
        // already gated by workspace; no further tenant compare needed.
        if (existingIsGlobal) {
            if (!callerIsAdmin) {
                throw new IllegalArgumentException("Only admins can modify global skills");
            }
        }

        // Toggling is_global on/off is an admin-only operation.
        if (isGlobal != null && isGlobal.booleanValue() != existingIsGlobal) {
            if (!callerIsAdmin) {
                throw new IllegalArgumentException("Only admins can change global visibility of a skill");
            }
            existing.setIsGlobal(isGlobal);
        }

        long oldSize = existing.getInstructions() != null ? existing.getInstructions().length() : 0;

        if (name != null) {
            existing.setName(name);
        }
        if (description != null) {
            existing.setDescription(description);
        }
        if (icon != null) {
            existing.setIcon(icon);
        }
        if (instructions != null) {
            existing.setInstructions(instructions);
        }
        if (isActive != null) {
            existing.setIsActive(isActive);
        }
        if (isDefaultActive != null) {
            existing.setIsDefaultActive(isDefaultActive);
        }
        SkillEntity saved = skillRepository.save(existing);
        long newSize = saved.getInstructions() != null ? saved.getInstructions().length() : 0;
        if (newSize != oldSize) {
            // Attribute storage delta to the owning tenant, not the admin acting cross-tenant.
            breakdownService.trackSizeChange(existing.getTenantId(), "CONFIGURATION", newSize - oldSize);
        }
        log.info("Skill updated: id={}, name={}, global={}, defaultActive={}",
                saved.getId(), saved.getName(), saved.getIsGlobal(), saved.getIsDefaultActive());
        return saved;
    }

    /**
     * V275 (2026-05-21) - list the default-active skills visible in the
     * caller's workspace. Returns the org's own default-active skills + every
     * global default-active skill. Used by conversation-service
     * {@code AgentContextBuilder} to seed the system prompt for a new general
     * chat when the client did not pass an explicit {@code defaultSkillIds}.
     */
    @Transactional(readOnly = true)
    public List<SkillEntity> listDefaultActiveSkills(String tenantId, String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        return skillRepository.findDefaultActiveVisibleForOrganization(organizationId);
    }

    // ==================== V276: per-user override layer ====================

    /**
     * V276 (2026-05-21) - set / upsert the per-user override for a skill.
     * The user must be able to see the skill in their workspace (org-scope OR
     * global); otherwise we reject so a user can't manufacture override rows
     * for skills they can't even discover.
     *
     * <p>{@code userId} is the override row key - keep it distinct from any
     * notion of tenant. Even if today's controller passes the X-User-ID
     * header value as both userId and the tenantId context elsewhere, this
     * method should not assume the two coincide (impersonation, internal
     * dispatches, future role boundaries).
     */
    public UserSkillOverrideEntity setUserSkillOverride(String userId,
                                                        UUID skillId,
                                                        boolean active,
                                                        String organizationId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        // Visibility gate - read-only, fast. The strict-scope path uses orgId
        // alone; the global fallback ignores tenantId too, so we pass null.
        findInScopeOrGlobal(skillId, null, organizationId)
            .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + skillId));

        UserSkillOverrideEntity entity = userSkillOverrideRepository
            .findByUserIdAndSkillId(userId, skillId)
            .orElseGet(() -> new UserSkillOverrideEntity(userId, skillId, active));
        entity.setActive(active);
        UserSkillOverrideEntity saved = userSkillOverrideRepository.save(entity);
        log.info("User skill override set: user={}, skill={}, active={}", userId, skillId, active);
        return saved;
    }

    /**
     * V276 - forget the user's override row so chat-time resolution falls back
     * to {@link SkillEntity#getIsDefaultActive()}. Idempotent.
     */
    public void clearUserSkillOverride(String userId, UUID skillId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        int deleted = userSkillOverrideRepository.deleteByUserIdAndSkillId(userId, skillId);
        if (deleted > 0) {
            log.info("User skill override cleared: user={}, skill={}", userId, skillId);
        }
    }

    /**
     * V276 - read every override the user has set (across every tenant × org).
     * Returned as a (skillId -> active) map for cheap join with skills lists.
     */
    @Transactional(readOnly = true)
    public Map<UUID, Boolean> getUserOverrides(String userId) {
        if (userId == null || userId.isBlank()) {
            return Map.of();
        }
        List<UserSkillOverrideEntity> rows = userSkillOverrideRepository.findByUserId(userId);
        Map<UUID, Boolean> map = new HashMap<>(rows.size());
        for (UserSkillOverrideEntity row : rows) {
            map.put(row.getSkillId(), Boolean.TRUE.equals(row.getActive()));
        }
        return map;
    }

    /**
     * V276 (2026-05-21) - effective default-active list for a user. Applies
     * the resolution rule {@code COALESCE(override.active, skill.is_default_active)}
     * over the org-visible skills (org-scope + globals). Used by
     * conversation-service to seed the general-chat system prompt when no
     * per-conversation {@code defaultSkillIds} arrived from the client.
     *
     * <p>One DB hit for skills, one DB hit for overrides - no N+1 lookup per
     * skill, which matters because this fires on every new conversation.
     */
    @Transactional(readOnly = true)
    public List<SkillEntity> listEffectiveDefaultActiveForUser(String userId,
                                                                String tenantId,
                                                                String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        List<SkillEntity> visible = skillRepository.findVisibleForOrganization(organizationId);
        Map<UUID, Boolean> overrides = getUserOverrides(userId);

        List<SkillEntity> effective = new ArrayList<>();
        for (SkillEntity s : visible) {
            Boolean override = overrides.get(s.getId());
            boolean active = (override != null)
                ? override
                : Boolean.TRUE.equals(s.getIsDefaultActive());
            if (active) {
                effective.add(s);
            }
        }
        return effective;
    }

    public SkillEntity moveSkill(UUID id, String tenantId, UUID targetFolderId) {
        return moveSkill(id, tenantId, TenantResolver.currentRequestOrganizationId(), targetFolderId);
    }

    /**
     * Phase 6c - org-aware move. The skill must be visible in the caller's
     * workspace; globals stay outside any tenant's folder hierarchy.
     */
    public SkillEntity moveSkill(UUID id, String tenantId, String organizationId, UUID targetFolderId) {
        SkillEntity existing = findInScope(id, tenantId, organizationId)
            .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + id));
        // Folder structure is per-workspace. Global skills live outside any
        // workspace's folder hierarchy and cannot be moved via this endpoint.
        if (Boolean.TRUE.equals(existing.getIsGlobal())) {
            throw new IllegalArgumentException("Global skills cannot be moved into tenant folders");
        }

        existing.setFolderId(targetFolderId);
        SkillEntity saved = skillRepository.save(existing);
        log.info("Skill moved: id={}, targetFolderId={}", id, targetFolderId);
        return saved;
    }

    public void deleteSkill(UUID id, String tenantId) {
        deleteSkill(id, tenantId, false);
    }

    public void deleteSkill(UUID id, String tenantId, boolean callerIsAdmin) {
        deleteSkill(id, tenantId, TenantResolver.currentRequestOrganizationId(), callerIsAdmin);
    }

    /**
     * Phase 6c - org-aware delete. Routes through {@link #findInScopeOrGlobal}
     * so org-teammates can delete a workspace-shared skill. Default skills
     * cannot be deleted (only reset).
     */
    public void deleteSkill(UUID id, String tenantId, String organizationId, boolean callerIsAdmin) {
        SkillEntity existing = findInScopeOrGlobal(id, tenantId, organizationId)
            .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + id));

        boolean existingIsGlobal = Boolean.TRUE.equals(existing.getIsGlobal());

        // V374 - cloud-managed bundle skills are read-only on CE (see updateSkill). The
        // applier soft-removes them when the cloud drops them; a local delete would just
        // be re-applied on the next sync.
        if (existing.getSourceBundleKey() != null) {
            throw new IllegalArgumentException(
                "This global skill is provided by the cloud and is read-only on this install; it cannot be deleted here.");
        }

        if (existingIsGlobal && !callerIsAdmin) {
            throw new IllegalArgumentException("Only admins can delete global skills");
        }

        if (existing.getDefaultKey() != null) {
            throw new IllegalArgumentException("Cannot delete default skill '" + existing.getName()
                + "'. Use reset to restore original content.");
        }

        String name = existing.getName();
        skillRepository.delete(existing);
        log.info("Skill deleted: id={}, name={}, global={}, tenant={}, org={}",
            id, name, existingIsGlobal, existing.getTenantId(), organizationId);
    }

    /**
     * Phase 6c - strict-scope single skill fetch. Mirrors
     * {@code SkillFolderService#findInScope}: org-scope when
     * {@code organizationId} is set, personal-scope otherwise. Does NOT
     * include globals; for paths that need the "tenant + globals" or
     * "org + globals" view, use {@link #findInScopeOrGlobal}.
     */
    private Optional<SkillEntity> findInScope(UUID id, String tenantId, String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        return skillRepository.findByIdAndOrganizationIdStrict(id, organizationId);
    }

    /**
     * Phase 6c - scope-aware fetch that also includes admin-managed globals.
     * Used by update/delete/getSkill paths where a global skill is a valid
     * target regardless of caller workspace. Admin gating on the actual
     * mutation is enforced at the caller level.
     */
    private Optional<SkillEntity> findInScopeOrGlobal(UUID id, String tenantId, String organizationId) {
        Optional<SkillEntity> inScope = findInScope(id, tenantId, organizationId);
        if (inScope.isPresent()) {
            return inScope;
        }
        // Fall back to globals - admin-managed Deep-Research-like rows are
        // visible everywhere. Caller must still gate on the isGlobal flag
        // to enforce admin-only mutation.
        return skillRepository.findById(id)
            .filter(s -> Boolean.TRUE.equals(s.getIsGlobal()));
    }

    // ============================================
    // Default skill seeding & reset
    // ============================================

    /**
     * Seed default skills for a tenant if not already present.
     * Creates a DB entity for each DefaultSkillsProvider entry that doesn't
     * yet exist (matched by tenant_id + default_key unique constraint).
     *
     * <p>Seeded built-in skills are marked {@code is_default_active=TRUE} so a
     * NEW tenant gets the same out-of-the-box chat behavior the V275 backfill
     * granted existing tenants ({@code default_key IS NOT NULL} → default-active).
     * Without this the legacy localStorage seed was gone AND new tenants started
     * with every default skill unchecked. See V320 for the matching backfill of
     * tenants seeded between V275 and this fix.
     *
     * @return number of skills actually seeded (0 if all already exist)
     */
    public int seedDefaultSkills(String tenantId) {
        String decodedTenantId = tenantId != null ? tenantId.replace("%7C", "|") : tenantId;

        List<DefaultSkill> missing = new ArrayList<>();
        for (DefaultSkill ds : DefaultSkillsProvider.getAll()) {
            String key = ds.id().replace("default:", "");
            if (skillRepository.findByTenantIdAndDefaultKey(decodedTenantId, key).isEmpty()) {
                missing.add(ds);
            }
        }
        if (missing.isEmpty()) {
            return 0;
        }

        // Defaults are PERSONAL-workspace rows. Resolve the tenant's personal
        // org explicitly (only when something will actually be inserted): the
        // seed may run while the caller is in a TEAM workspace (listSkills
        // seeds on both branches), and letting the OrgScopedEntityListener
        // stamp the request's active org would leak the user's built-in
        // defaults into the team workspace (shared with every teammate,
        // invisible from the personal one). On null resolution the listener
        // fallback applies: request-scope stamp on request threads, fail-loud
        // IllegalStateException on unbound threads (pre-existing V263 contract).
        String personalOrgId = authClient != null
                ? authClient.getDefaultOrganizationIdForUser(decodedTenantId)
                : null;

        int seeded = 0;
        for (DefaultSkill ds : missing) {
            String key = ds.id().replace("default:", "");
            SkillEntity entity = new SkillEntity(
                decodedTenantId,
                ds.name(),
                ds.description(),
                ds.icon(),
                ds.instructions(),
                true
            );
            entity.setDefaultKey(key);
            // Built-in defaults are auto-active in new chats out of the box,
            // mirroring the V275 backfill (default_key rows → is_default_active).
            entity.setIsDefaultActive(true);
            if (personalOrgId != null) {
                entity.setOrganizationId(personalOrgId);
            }
            if (insertSeededSkill(entity)) {
                seeded++;
                log.info("Seeded default skill: key={}, name={}, tenant={}", key, ds.name(), decodedTenantId);
            }
        }

        if (seeded > 0) {
            log.info("Seeded {} default skills for tenant={}", seeded, decodedTenantId);
        }
        return seeded;
    }

    /**
     * Insert one seeded built-in row, tolerating a concurrent seeder. The
     * V334 partial unique index (tenant_id, default_key) makes the duplicate
     * insert fail; running it in REQUIRES_NEW confines the rollback to this
     * row so the caller's transaction (typically the listSkills read) is not
     * poisoned. Without a transaction manager (plain unit tests) the insert
     * degrades to a direct save.
     *
     * @return true when this call actually inserted the row
     */
    private boolean insertSeededSkill(SkillEntity entity) {
        if (transactionManager == null) {
            skillRepository.save(entity);
            return true;
        }
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        try {
            requiresNew.executeWithoutResult(status -> skillRepository.saveAndFlush(entity));
            return true;
        } catch (DataIntegrityViolationException e) {
            log.info("Default skill {} already seeded concurrently for tenant={} - skipping",
                    entity.getDefaultKey(), entity.getTenantId());
            return false;
        }
    }

    /**
     * Reset a default skill to its original content from DefaultSkillsProvider.
     * Only works for skills with a non-null defaultKey.
     */
    public SkillEntity resetDefaultSkill(UUID id, String tenantId) {
        SkillEntity existing = skillRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + id));
        String orgId = TenantResolver.currentRequestOrganizationId();
        if (!ScopeGuard.isInStrictScope(tenantId, orgId, existing.getTenantId(), existing.getOrganizationId())) {
            throw new IllegalArgumentException("Skill tenant mismatch");
        }
        if (existing.getDefaultKey() == null) {
            throw new IllegalArgumentException("Skill is not a default skill - cannot reset");
        }

        String defaultId = "default:" + existing.getDefaultKey();
        DefaultSkill original = DefaultSkillsProvider.getById(defaultId)
            .orElseThrow(() -> new IllegalArgumentException(
                "No default content found for key: " + existing.getDefaultKey()));

        existing.setName(original.name());
        existing.setDescription(original.description());
        existing.setIcon(original.icon());
        existing.setInstructions(original.instructions());
        existing.setIsActive(true);

        SkillEntity saved = skillRepository.save(existing);
        log.info("Reset default skill: id={}, key={}, tenant={}", id, existing.getDefaultKey(), tenantId);
        return saved;
    }

    // ============================================
    // Agent-Skill assignments
    // ============================================

    @Transactional(readOnly = true)
    public List<AgentSkillEntity> getAgentSkills(UUID agentId, String tenantId) {
        return getAgentSkills(agentId, tenantId, TenantResolver.currentRequestOrganizationId());
    }

    /**
     * Phase 6c - org-aware agent-skill list. Org-teammates can browse the
     * skill assignments of a workspace-shared agent.
     */
    @Transactional(readOnly = true)
    public List<AgentSkillEntity> getAgentSkills(UUID agentId, String tenantId, String organizationId) {
        // Verify agent is visible from the caller's active workspace
        assertAgentInScope(agentId, tenantId, organizationId);
        return agentSkillRepository.findByAgentIdOrderBySortOrderAsc(agentId);
    }

    /**
     * All skill assignments for EVERY agent in the caller's workspace - the Agent
     * Fleet batch lookup. Returns one flat list (each entity carries {@code agentId})
     * so the canvas resolves every agent's skills in a single call instead of one
     * {@link #getAgentSkills} per agent. Org-strict, so it covers workspace-shared
     * (teammate-owned) agents too.
     */
    @Transactional(readOnly = true)
    public List<AgentSkillEntity> getAllAgentSkills(String tenantId, String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            return List.of();
        }
        List<UUID> agentIds = agentRepository.findByOrganizationIdStrictOrderByCreatedAtDesc(organizationId)
                .stream().map(a -> a.getId()).toList();
        if (agentIds.isEmpty()) {
            return List.of();
        }
        return agentSkillRepository.findByAgentIdInOrderByAgentIdAscSortOrderAsc(agentIds);
    }

    /**
     * Replace all skill assignments for an agent.
     */
    public void setAgentSkills(UUID agentId, String tenantId, List<SkillAssignment> assignments) {
        setAgentSkills(agentId, tenantId, TenantResolver.currentRequestOrganizationId(), assignments);
    }

    /**
     * Phase 6c - org-aware setAgentSkills. The agent AND every assigned skill
     * must be visible from the caller's active workspace (or be admin-managed
     * globals). Mismatches yield IllegalArgumentException, mapped to 404 at
     * the controller layer.
     */
    public void setAgentSkills(UUID agentId, String tenantId, String organizationId, List<SkillAssignment> assignments) {
        // Verify agent is visible from the caller's active workspace
        assertAgentInScope(agentId, tenantId, organizationId);

        if (assignments != null && assignments.size() > maxSkillsPerAgent) {
            throw new IllegalArgumentException(
                "Cannot assign more than " + maxSkillsPerAgent + " skills to an agent (requested: " + assignments.size() + ")");
        }

        // Delete existing assignments
        agentSkillRepository.deleteByAgentId(agentId);

        // Insert new assignments
        if (assignments != null && !assignments.isEmpty()) {
            for (int i = 0; i < assignments.size(); i++) {
                SkillAssignment assignment = assignments.get(i);

                // Verify skill is visible in the workspace OR is a global
                SkillEntity skill = findInScopeOrGlobal(assignment.skillId(), tenantId, organizationId)
                    .orElseThrow(() -> new IllegalArgumentException("Skill not found or workspace mismatch: " + assignment.skillId()));

                AgentSkillEntity agentSkill = new AgentSkillEntity(
                    agentId,
                    skill.getId(),
                    i
                );
                agentSkillRepository.save(agentSkill);
            }
        }

        log.info("Agent skills updated: agentId={}, count={}", agentId,
            assignments != null ? assignments.size() : 0);
    }

    /**
     * Phase 6c - verify an agent is visible from the caller's active workspace
     * via the V210 strict-scope exists finders (shared with PR27, the WS
     * channel authorizer, and AgentTaskRecurrenceController.list).
     */
    private void assertAgentInScope(UUID agentId, String tenantId, String organizationId) {
        TenantResolver.requireOrgId(organizationId);
        if (!agentRepository.existsByIdAndOrganizationIdStrict(agentId, organizationId)) {
            throw new IllegalArgumentException("Agent not found or workspace mismatch: " + agentId);
        }
    }

    /**
     * Add skills to an agent without removing existing ones (merge/additive).
     * Skips skills already assigned. Respects maxSkillsPerAgent limit.
     *
     * @return number of new skills actually added
     */
    public int addAgentSkills(UUID agentId, String tenantId, List<SkillAssignment> newAssignments) {
        return addAgentSkills(agentId, tenantId, TenantResolver.currentRequestOrganizationId(), newAssignments);
    }

    /**
     * Phase 6c - org-aware additive assignment. Same gating shape as
     * {@link #setAgentSkills(UUID, String, String, List)}: agent + each
     * skill must be visible in the caller's active workspace (globals OK).
     */
    public int addAgentSkills(UUID agentId, String tenantId, String organizationId, List<SkillAssignment> newAssignments) {
        // Verify agent is visible from the caller's active workspace
        assertAgentInScope(agentId, tenantId, organizationId);

        if (newAssignments == null || newAssignments.isEmpty()) {
            return 0;
        }

        // Get existing skills for this agent
        List<AgentSkillEntity> existing = agentSkillRepository.findByAgentIdOrderBySortOrderAsc(agentId);
        var existingSkillIds = existing.stream()
            .map(AgentSkillEntity::getSkillId)
            .collect(java.util.stream.Collectors.toSet());

        // Filter out already-assigned skills
        List<SkillAssignment> toAdd = newAssignments.stream()
            .filter(a -> !existingSkillIds.contains(a.skillId()))
            .toList();

        if (toAdd.isEmpty()) {
            log.info("All requested skills already assigned to agent {}", agentId);
            return 0;
        }

        int totalAfter = existing.size() + toAdd.size();
        if (totalAfter > maxSkillsPerAgent) {
            throw new IllegalArgumentException(
                "Cannot assign: agent already has " + existing.size() + " skills, adding " + toAdd.size() +
                " would exceed limit of " + maxSkillsPerAgent);
        }

        int nextOrder = existing.stream()
            .mapToInt(AgentSkillEntity::getSortOrder)
            .max().orElse(-1) + 1;

        int added = 0;
        for (SkillAssignment assignment : toAdd) {
            SkillEntity skill = findInScopeOrGlobal(assignment.skillId(), tenantId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Skill not found or workspace mismatch: " + assignment.skillId()));

            AgentSkillEntity agentSkill = new AgentSkillEntity(
                agentId,
                skill.getId(),
                nextOrder++
            );
            agentSkillRepository.save(agentSkill);
            added++;
        }

        log.info("Agent skills added: agentId={}, added={}, total={}", agentId, added, existing.size() + added);
        return added;
    }

    private void validateSkill(String tenantId, String name, String description, String instructions) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId cannot be null or empty");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name cannot be null or empty");
        }
        if (name.length() > 255) {
            throw new IllegalArgumentException("name cannot exceed 255 characters");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description cannot be null or empty");
        }
        if (instructions == null || instructions.isBlank()) {
            throw new IllegalArgumentException("instructions cannot be null or empty");
        }
    }

    /**
     * Assignment record for setting agent skills.
     */
    public record SkillAssignment(UUID skillId) {
    }

}
