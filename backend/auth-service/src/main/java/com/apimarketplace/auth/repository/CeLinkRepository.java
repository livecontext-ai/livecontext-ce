package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.CeLink;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for {@link CeLink}. Lookup primarily by {@code install_id}
 * (PK - squat detection happens here at insert time) or by
 * {@code (user_id, status='ACTIVE')} for the user's install list.
 */
@Repository
public interface CeLinkRepository extends JpaRepository<CeLink, UUID> {

    /**
     * Used by the §1 #4 mandatory-header policy: does this user have ANY active
     * link? Cheap EXISTS query - cached by {@code CeLinkActiveRowCache}
     * (PR3b) so the per-request cost stays under 0.1% of authed traffic.
     */
    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END "
         + "FROM CeLink c WHERE c.userId = :userId AND c.status = com.apimarketplace.auth.domain.CeLink.Status.ACTIVE")
    boolean userHasAnyActiveLink(@Param("userId") Long userId);

    /**
     * GET /api/ce-link/mine - paginated list, sorted client-side by last_seen
     * once heartbeat join lands (PR3b). For PR3a we just return ACTIVE rows.
     */
    Page<CeLink> findByUserIdAndStatus(Long userId, CeLink.Status status, Pageable pageable);

    /**
     * Same list, unpaginated - used by tests and OrphanRefreshTokenReporter.
     */
    List<CeLink> findAllByUserIdAndStatus(Long userId, CeLink.Status status);

    /**
     * Resolve an install_id while enforcing ownership at the DB level: returns
     * a row only when it belongs to the caller's user_id. Used by
     * - DELETE /api/ce-link/{install_id}
     * - X-LiveContext-Reset-Signal authorization (§1 #36 horizontal-privesc gate)
     */
    Optional<CeLink> findByInstallIdAndUserId(UUID installId, Long userId);

    /** Used by {@code CeLinkOrphanReporter} for the platform-wide ACTIVE-count gauge. */
    long countByStatus(CeLink.Status status);
}
