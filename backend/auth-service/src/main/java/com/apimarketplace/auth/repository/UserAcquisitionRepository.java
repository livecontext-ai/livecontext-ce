package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.UserAcquisition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface UserAcquisitionRepository extends JpaRepository<UserAcquisition, Long> {

    /**
     * Write-once insert of the first-touch attribution. A second report for the same user
     * is a no-op (returns 0), never an update and never a constraint violation, so two
     * racing reports cannot poison the caller's transaction. {@code save()} is not used on
     * purpose: with an assigned id it MERGES, which would overwrite the first touch.
     */
    @Modifying
    @Query(value = "INSERT INTO auth.user_acquisition (user_id, utm_source, utm_medium, utm_campaign, "
            + "utm_content, utm_term, referrer, landing_path, first_seen_at, captured_at) "
            + "VALUES (:userId, :utmSource, :utmMedium, :utmCampaign, :utmContent, :utmTerm, "
            + ":referrer, :landingPath, :firstSeenAt, :capturedAt) "
            + "ON CONFLICT (user_id) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("userId") Long userId,
                       @Param("utmSource") String utmSource,
                       @Param("utmMedium") String utmMedium,
                       @Param("utmCampaign") String utmCampaign,
                       @Param("utmContent") String utmContent,
                       @Param("utmTerm") String utmTerm,
                       @Param("referrer") String referrer,
                       @Param("landingPath") String landingPath,
                       @Param("firstSeenAt") Instant firstSeenAt,
                       @Param("capturedAt") Instant capturedAt);
}
