package com.apimarketplace.auth.credential.repository;

import com.apimarketplace.auth.credential.domain.PlatformCredentialPricingVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlatformCredentialPricingVersionRepository
        extends JpaRepository<PlatformCredentialPricingVersion, Long> {

    Optional<PlatformCredentialPricingVersion> findByPlatformCredentialIdAndVersion(
            Long platformCredentialId, Integer version);

    List<PlatformCredentialPricingVersion> findByPlatformCredentialIdOrderByVersionDesc(
            Long platformCredentialId);

    /**
     * Get the highest version number for a credential, or null if none exist.
     * Used by {@code PlatformCredentialPricingService.publishNextVersion()} under an
     * advisory lock to compute the next version monotonically.
     */
    @Query("SELECT MAX(v.version) FROM PlatformCredentialPricingVersion v " +
           "WHERE v.platformCredentialId = :credentialId")
    Integer findMaxVersion(@Param("credentialId") Long credentialId);

    /**
     * Latest version for a credential (null if none). Reads via
     * {@code idx_pcpv_cred_latest}.
     */
    default Optional<PlatformCredentialPricingVersion> findLatest(Long credentialId) {
        Integer max = findMaxVersion(credentialId);
        if (max == null) {
            return Optional.empty();
        }
        return findByPlatformCredentialIdAndVersion(credentialId, max);
    }
}
