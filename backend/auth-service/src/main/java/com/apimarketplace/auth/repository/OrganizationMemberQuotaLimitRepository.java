package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.OrganizationMemberQuotaLimit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PR11 - repository for {@link OrganizationMemberQuotaLimit}. Operations:
 * <ul>
 *   <li>{@link #findByOrgIdAndUserId} - hot path for {@code consume*}
 *       enforcement (called once per debit, must be ms-cheap; covered by
 *       the PK index).</li>
 *   <li>{@link #findByOrgId} - UI listing for OWNER/ADMIN admin panel.</li>
 *   <li>{@link #deleteByOrgIdAndUserId} - capacity removal (audit event
 *       {@code ORG_QUOTA_CAP_REMOVED} emitted by the calling service).</li>
 * </ul>
 *
 * <p>No "exists" check is provided - JPA-saved row IS the existence
 * signal; callers should fetch and check {@code Optional.isPresent()}.
 */
@Repository
public interface OrganizationMemberQuotaLimitRepository
        extends JpaRepository<OrganizationMemberQuotaLimit, OrganizationMemberQuotaLimit.PK> {

    /**
     * Hot-path read used by {@code MemberQuotaService.checkCap()} on every
     * billable consume. The PK index makes this O(1); the optional avoids
     * a sentinel-null path.
     */
    Optional<OrganizationMemberQuotaLimit> findByOrgIdAndUserId(UUID orgId, Long userId);

    /** Admin-panel listing - typically &lt;100 rows per org. */
    List<OrganizationMemberQuotaLimit> findByOrgId(UUID orgId);

    /** Reverse lookup used when a member leaves the org and we cleanup their caps. */
    List<OrganizationMemberQuotaLimit> findByUserId(Long userId);

    /**
     * Bulk delete on cap-removal. Idempotent - returns 0 when no row exists.
     * The caller writes the {@code ORG_QUOTA_CAP_REMOVED} audit event AFTER
     * this returns &gt; 0 (don't write audit for no-op deletes).
     */
    @Modifying
    @Query("delete from OrganizationMemberQuotaLimit q where q.orgId = :orgId and q.userId = :userId")
    int deleteByOrgIdAndUserId(@Param("orgId") UUID orgId, @Param("userId") Long userId);
}
