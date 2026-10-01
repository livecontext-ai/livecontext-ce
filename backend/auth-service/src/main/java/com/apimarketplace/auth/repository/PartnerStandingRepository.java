package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.PartnerStanding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface PartnerStandingRepository extends JpaRepository<PartnerStanding, Long> {

    /**
     * Raise a partner's tier, never lower it: one statement, so two concurrent upgrades (two
     * invoices paid at once, or an invoice and a founder grant) cannot interleave into a lower
     * result. Writes only when {@code tier} ranks above the stored one, or when it turns the
     * founder flag on; {@code reached_at} moves only with the tier. Returns the rows written
     * (0 = nothing to raise).
     *
     * <p>The rank is the position in SILVER, GOLD, PLATINUM, the same order as
     * {@code PartnerTier}.
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO auth.partner_standing AS s
                   (user_id, tier, founder, reached_at, updated_by_user_id, updated_at)
            VALUES (:userId, CAST(:tier AS VARCHAR), :founder, :now, CAST(:adminUserId AS BIGINT), :now)
            ON CONFLICT (user_id) DO UPDATE
               SET tier = CASE
                         WHEN array_position(ARRAY['SILVER','GOLD','PLATINUM'], EXCLUDED.tier)
                              > array_position(ARRAY['SILVER','GOLD','PLATINUM'], s.tier)
                         THEN EXCLUDED.tier ELSE s.tier END,
                   reached_at = CASE
                         WHEN array_position(ARRAY['SILVER','GOLD','PLATINUM'], EXCLUDED.tier)
                              > array_position(ARRAY['SILVER','GOLD','PLATINUM'], s.tier)
                         THEN EXCLUDED.reached_at ELSE s.reached_at END,
                   founder = s.founder OR EXCLUDED.founder,
                   updated_by_user_id = EXCLUDED.updated_by_user_id,
                   updated_at = EXCLUDED.updated_at
             WHERE array_position(ARRAY['SILVER','GOLD','PLATINUM'], EXCLUDED.tier)
                   > array_position(ARRAY['SILVER','GOLD','PLATINUM'], s.tier)
                OR (EXCLUDED.founder AND NOT s.founder)
            """, nativeQuery = true)
    int raise(@Param("userId") Long userId, @Param("tier") String tier, @Param("founder") boolean founder,
              @Param("adminUserId") Long adminUserId, @Param("now") Instant now);

    /**
     * End a partner's founder status (Partner Program Terms, clause 7.5): the founder flag goes
     * off and the tier becomes {@code tier}, the one their settled revenue has earned, which may
     * be lower. The one statement that can lower a tier, and only on a founder row: an admin
     * decision, never the revenue. Returns the rows written (0 = not a founder).
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE auth.partner_standing
               SET founder = FALSE,
                   tier = CAST(:tier AS VARCHAR),
                   reached_at = :now,
                   updated_by_user_id = CAST(:adminUserId AS BIGINT),
                   updated_at = :now
             WHERE user_id = :userId AND founder
            """, nativeQuery = true)
    int endFounder(@Param("userId") Long userId, @Param("tier") String tier,
                   @Param("adminUserId") Long adminUserId, @Param("now") Instant now);
}
