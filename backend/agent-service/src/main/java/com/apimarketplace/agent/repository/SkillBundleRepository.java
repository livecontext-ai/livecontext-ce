package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.SkillBundleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SkillBundleRepository extends JpaRepository<SkillBundleEntity, Long> {

    Optional<SkillBundleEntity> findByVersion(Long version);

    Optional<SkillBundleEntity> findFirstByActiveTrue();

    /**
     * Checksum of the active bundle, without loading its payload. Cloud: the ETag a CE poll is
     * compared against (304 when it matches). CE: the checksum it holds, sent as
     * {@code If-None-Match}.
     */
    @Query("SELECT b.checksum FROM SkillBundleEntity b WHERE b.active = true")
    Optional<String> findActiveChecksum();

    Optional<SkillBundleEntity> findTopByOrderByVersionDesc();

    /**
     * Atomically clear the active flag on every row. Callers run this inside the same
     * TX as a subsequent {@code save(newActive)} so the partial unique index
     * {@code idx_skill_bundles_one_active} is never violated.
     */
    @Modifying
    @Query("UPDATE SkillBundleEntity b SET b.active = false WHERE b.active = true")
    int deactivateAll();
}
