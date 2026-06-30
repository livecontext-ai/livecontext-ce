package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.Organization;
import com.apimarketplace.auth.domain.OrganizationAuditEvent;
import com.apimarketplace.auth.domain.OrganizationMember;
import com.apimarketplace.auth.domain.OrganizationMemberQuotaLimit;
import com.apimarketplace.auth.domain.OrganizationRole;
import com.apimarketplace.auth.repository.OrganizationMemberQuotaLimitRepository;
import com.apimarketplace.auth.repository.OrganizationMemberRepository;
import com.apimarketplace.auth.repository.OrganizationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PR11c - CRUD façade for {@link OrganizationMemberQuotaLimit}. Encapsulates
 * the authorization checks, write semantics, and audit-event emission so
 * {@code OrganizationMemberQuotaController} can stay thin.
 *
 * <p>Authorization model: only the OWNER or ADMIN of the TARGET org (not
 * the actor's active workspace) can set/list/remove caps on members of
 * that org. This is deliberately stricter than the X-Organization-Role
 * header (which reflects the active workspace, not the path-param org)
 * - an OWNER managing multiple orgs must NOT have to switch active
 * workspace just to adjust a cap.
 *
 * <p>Soft-deleted orgs reject every operation with NOT_FOUND.
 *
 * <p>Audit events on set/remove. The set audit captures the OLD + NEW
 * cap values so the audit log carries enough context to answer "who
 * raised the cap from 100 to 1000?" without rejoining tables.
 */
@Service
public class MemberQuotaAdminService {

    private static final Logger log = LoggerFactory.getLogger(MemberQuotaAdminService.class);

    private final OrganizationMemberQuotaLimitRepository quotaRepository;
    private final OrganizationMemberRepository memberRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationAuditService auditService;

    public MemberQuotaAdminService(OrganizationMemberQuotaLimitRepository quotaRepository,
                                    OrganizationMemberRepository memberRepository,
                                    OrganizationRepository organizationRepository,
                                    OrganizationAuditService auditService) {
        this.quotaRepository = quotaRepository;
        this.memberRepository = memberRepository;
        this.organizationRepository = organizationRepository;
        this.auditService = auditService;
    }

    /** Result discriminator. Controllers translate to HTTP. */
    public enum Outcome {
        OK,
        FORBIDDEN,           // actor is not OWNER/ADMIN of target org
        ORG_NOT_FOUND,       // unknown org, or soft-deleted
        TARGET_NOT_MEMBER,   // target user is not a member of the org
        CONFLICT_TARGET_IS_OWNER  // cannot set cap on the org's OWNER (caps are for members)
    }

    /** Get the cap row for (org, user). {@link Optional#empty()} when no row exists. */
    @Transactional(readOnly = true)
    public Result<Optional<OrganizationMemberQuotaLimit>> get(Long actorUserId, UUID orgId, Long targetUserId) {
        Outcome auth = checkAuthAndOrg(actorUserId, orgId, targetUserId, /*allowOwnerTarget=*/true);
        if (auth != Outcome.OK) return Result.failure(auth);
        return Result.success(quotaRepository.findByOrgIdAndUserId(orgId, targetUserId));
    }

    /** List all caps in an org. Empty list when no rows. */
    @Transactional(readOnly = true)
    public Result<List<OrganizationMemberQuotaLimit>> list(Long actorUserId, UUID orgId) {
        Outcome auth = checkActorIsOwnerOrAdmin(actorUserId, orgId);
        if (auth != Outcome.OK) return Result.failure(auth);
        Organization org = organizationRepository.findById(orgId).orElse(null);
        if (org == null || org.isDeleted()) return Result.failure(Outcome.ORG_NOT_FOUND);
        return Result.success(quotaRepository.findByOrgId(orgId));
    }

    /**
     * Upsert a cap row. NULL on a dimension means "no cap on that dim"
     * (or "clear the existing cap for that dim if the row already
     * existed"). Audit event {@code ORG_QUOTA_CAP_SET} fires post-write
     * with both old + new cap values.
     */
    @Transactional
    public Result<OrganizationMemberQuotaLimit> upsert(Long actorUserId, UUID orgId, Long targetUserId,
                                                        BigDecimal periodCredits,
                                                        Long periodStorageBytes,
                                                        Long periodLlmTokens) {
        Outcome auth = checkAuthAndOrg(actorUserId, orgId, targetUserId, /*allowOwnerTarget=*/false);
        if (auth != Outcome.OK) return Result.failure(auth);

        Optional<OrganizationMemberQuotaLimit> existing = quotaRepository.findByOrgIdAndUserId(orgId, targetUserId);
        OrganizationMemberQuotaLimit row = existing.orElseGet(() ->
                new OrganizationMemberQuotaLimit(orgId, targetUserId, actorUserId));

        BigDecimal oldCredits = row.getPeriodCredits();
        Long oldStorage = row.getPeriodStorageBytes();
        Long oldTokens = row.getPeriodLlmTokens();

        row.setPeriodCredits(periodCredits);
        row.setPeriodStorageBytes(periodStorageBytes);
        row.setPeriodLlmTokens(periodLlmTokens);
        OrganizationMemberQuotaLimit saved = quotaRepository.save(row);

        Map<String, Object> data = new HashMap<>();
        data.put("targetUserId", targetUserId);
        data.put("oldPeriodCredits", oldCredits);
        data.put("newPeriodCredits", periodCredits);
        data.put("oldPeriodStorageBytes", oldStorage);
        data.put("newPeriodStorageBytes", periodStorageBytes);
        data.put("oldPeriodLlmTokens", oldTokens);
        data.put("newPeriodLlmTokens", periodLlmTokens);
        data.put("isNew", existing.isEmpty());
        auditService.record(orgId, actorUserId, OrganizationAuditEvent.Type.QUOTA_CAP_SET, data);

        log.info("Quota cap set: actor={} org={} target={} credits={} storage={} tokens={} isNew={}",
                actorUserId, orgId, targetUserId, periodCredits, periodStorageBytes, periodLlmTokens,
                existing.isEmpty());
        return Result.success(saved);
    }

    /**
     * Remove the cap row entirely. Idempotent: NOT_FOUND-on-target is
     * NOT an error (returns OK with deletedCount=0 in audit data).
     *
     * <p>Round-2 audit fix (PR11c-backend SHOULD-FIX #1): symmetric with
     * UPSERT - cannot REMOVE a cap on the org's OWNER. Owners have no
     * caps by design; allowing REMOVE-on-owner would let an admin
     * silently strip a row that was force-inserted via DB seed,
     * inconsistent with the UPSERT-rejected path.
     */
    @Transactional
    public Result<Integer> remove(Long actorUserId, UUID orgId, Long targetUserId) {
        Outcome auth = checkAuthAndOrg(actorUserId, orgId, targetUserId, /*allowOwnerTarget=*/false);
        if (auth != Outcome.OK) return Result.failure(auth);

        int deleted = quotaRepository.deleteByOrgIdAndUserId(orgId, targetUserId);
        if (deleted > 0) {
            // Only audit when something actually changed - avoids noise on idempotent retries.
            auditService.record(orgId, actorUserId, OrganizationAuditEvent.Type.QUOTA_CAP_REMOVED,
                    Map.of("targetUserId", targetUserId, "deletedCount", deleted));
            log.info("Quota cap removed: actor={} org={} target={}", actorUserId, orgId, targetUserId);
        }
        return Result.success(deleted);
    }

    // ---- private helpers ----

    /**
     * Composite auth + org-existence + target-membership check shared
     * by get/upsert/remove. {@code allowOwnerTarget} is true for GET
     * (you can READ a (non-existent) cap on an owner) and false for
     * SET (cannot CREATE a cap on the org's own owner - the cap concept
     * applies to members only).
     */
    private Outcome checkAuthAndOrg(Long actorUserId, UUID orgId, Long targetUserId, boolean allowOwnerTarget) {
        Outcome auth = checkActorIsOwnerOrAdmin(actorUserId, orgId);
        if (auth != Outcome.OK) return auth;
        // Round-2 audit fix (SHOULD-FIX #5): defensive null-target guard. The
        // HTTP path is safe (Spring rejects null path-vars on Long type), but
        // programmatic callers could pass null and NPE on line target-owner
        // identity check below.
        if (targetUserId == null) return Outcome.TARGET_NOT_MEMBER;

        Organization org = organizationRepository.findById(orgId).orElse(null);
        if (org == null || org.isDeleted()) return Outcome.ORG_NOT_FOUND;

        // Target must be a member of the org.
        Optional<OrganizationMember> targetMembership =
                memberRepository.findByOrganization_IdAndUser_Id(orgId, targetUserId);
        if (targetMembership.isEmpty()) return Outcome.TARGET_NOT_MEMBER;

        // The org's OWNER doesn't get capped (caps are MEMBER-facing).
        if (!allowOwnerTarget && org.getOwner() != null
                && targetUserId.equals(org.getOwner().getId())) {
            return Outcome.CONFLICT_TARGET_IS_OWNER;
        }
        return Outcome.OK;
    }

    private Outcome checkActorIsOwnerOrAdmin(Long actorUserId, UUID orgId) {
        if (actorUserId == null || orgId == null) return Outcome.FORBIDDEN;
        Optional<OrganizationMember> actorMembership =
                memberRepository.findByOrganization_IdAndUser_Id(orgId, actorUserId);
        if (actorMembership.isEmpty()) return Outcome.FORBIDDEN;
        OrganizationRole role = actorMembership.get().getRole();
        if (role != OrganizationRole.OWNER && role != OrganizationRole.ADMIN) return Outcome.FORBIDDEN;
        return Outcome.OK;
    }

    /**
     * Discriminated-union result type. {@code success} carries a value,
     * {@code failure} carries the outcome reason. Avoids exceptions for
     * expected-failure paths (403/404/409) which are not exceptional in
     * a CRUD API.
     */
    public record Result<T>(boolean success, T value, Outcome outcome) {
        public static <T> Result<T> success(T value) { return new Result<>(true, value, Outcome.OK); }
        public static <T> Result<T> failure(Outcome outcome) { return new Result<>(false, null, outcome); }
    }
}
