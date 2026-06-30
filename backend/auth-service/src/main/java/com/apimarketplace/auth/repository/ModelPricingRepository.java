package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.ModelPricing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ModelPricingRepository extends JpaRepository<ModelPricing, Integer> {

    @Query("SELECT mp FROM ModelPricing mp WHERE mp.provider = :provider AND mp.model = :model AND mp.isActive = true ORDER BY mp.effectiveFrom DESC")
    List<ModelPricing> findActiveByProviderAndModel(@Param("provider") String provider, @Param("model") String model);

    default Optional<ModelPricing> findCurrentPricing(String provider, String model) {
        List<ModelPricing> results = findActiveByProviderAndModel(provider, model);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    List<ModelPricing> findByIsActiveTrue();

    Optional<ModelPricing> findByProviderAndModelAndIsActiveTrue(String provider, String model);
}
