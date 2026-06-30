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

    void deleteByProviderAndModelId(String provider, String modelId);

    @Query("SELECT COALESCE(MAX(m.ranking), 0) FROM ModelConfigOverrideEntity m WHERE m.ranking IS NOT NULL")
    int findMaxRanking();
}
