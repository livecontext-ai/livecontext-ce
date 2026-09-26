package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.ModelConfigOverrideEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ModelConfigOverrideRepository extends JpaRepository<ModelConfigOverrideEntity, Long> {

    List<ModelConfigOverrideEntity> findAllByOrderByRankingAsc();

    Optional<ModelConfigOverrideEntity> findByProviderAndModelId(String provider, String modelId);

    List<ModelConfigOverrideEntity> findByProvider(String provider);

    /** Explicitly disabled rows ({@code enabled = false}; NULL reads as enabled). */
    List<ModelConfigOverrideEntity> findByEnabledFalse();

    /**
     * Rows whose runs must be swapped for a replacement (V515 resolver): explicitly disabled
     * (retired rows always are), or deprecated. Deprecated matters on a CE, where a bundle
     * deprecates every model the cloud stopped shipping without touching its {@code enabled}:
     * a stored reference to one would otherwise keep being sent, and the cloud relay refuses it.
     */
    @Query("SELECT m FROM ModelConfigOverrideEntity m WHERE m.enabled = false OR m.deprecatedAt IS NOT NULL")
    List<ModelConfigOverrideEntity> findDisabledOrDeprecated();

    void deleteByProviderAndModelId(String provider, String modelId);

    /** V533: retired rows, most recently retired first (admin "Retired" list). */
    List<ModelConfigOverrideEntity> findByRetiredAtIsNotNullOrderByRetiredAtDesc();

    /** V533: every row an admin reset may delete. A retired row is a tombstone and is kept. */
    List<ModelConfigOverrideEntity> findByRetiredAtIsNull();

    @Query("SELECT COALESCE(MAX(m.ranking), 0) FROM ModelConfigOverrideEntity m WHERE m.ranking IS NOT NULL")
    int findMaxRanking();
}
