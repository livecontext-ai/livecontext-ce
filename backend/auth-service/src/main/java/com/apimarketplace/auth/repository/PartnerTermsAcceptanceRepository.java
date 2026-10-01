package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.PartnerTermsAcceptance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PartnerTermsAcceptanceRepository extends JpaRepository<PartnerTermsAcceptance, Long> {

    /**
     * Record an acceptance. One statement that never fails on a repeat: accepting a version
     * already accepted (a double click, two tabs) keeps the FIRST acceptance, the one that
     * formed the contract, and writes nothing. Returns the rows written (0 = already accepted).
     */
    @Modifying(clearAutomatically = true)
    @Query(value = """
            INSERT INTO auth.partner_terms_acceptance
                   (user_id, terms_version, terms_fingerprint, accepted_at, source, ip_address, user_agent)
            VALUES (:userId, :version, :fingerprint, :now, :source, :ip, :userAgent)
            ON CONFLICT (user_id, terms_version) DO NOTHING
            """, nativeQuery = true)
    int record(@Param("userId") Long userId, @Param("version") String version,
               @Param("fingerprint") String fingerprint, @Param("now") Instant now,
               @Param("source") String source, @Param("ip") String ip, @Param("userAgent") String userAgent);

    /** The partner's most recent acceptance, whatever the version. */
    Optional<PartnerTermsAcceptance> findFirstByUserIdOrderByAcceptedAtDescIdDesc(Long userId);

    boolean existsByUserIdAndTermsVersion(Long userId, String termsVersion);

    boolean existsByUserId(Long userId);

    /** Every acceptance of these partners, for the admin report (latest picked per user by the caller). */
    List<PartnerTermsAcceptance> findByUserIdIn(Collection<Long> userIds);
}
