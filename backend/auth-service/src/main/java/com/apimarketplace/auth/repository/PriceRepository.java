package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.Price;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PriceRepository extends JpaRepository<Price, Long> {

    /**
     * Trouve un prix par son ID Stripe
     */
    Optional<Price> findByProviderPriceId(String providerPriceId);

    /**
     * Trouve tous les prix d'un plan
     */
    List<Price> findByPlanId(Long planId);

    /**
     * Trouve un prix par plan et cadence
     */
    Optional<Price> findByPlanIdAndCadence(Long planId, String cadence);

    /**
     * Trouve tous les prix avec leurs plans
     */
    @Query("SELECT p FROM Price p JOIN FETCH p.plan ORDER BY p.plan.id, p.cadence")
    List<Price> findAllWithPlans();

    /**
     * V250 - list every PAYG cadence row ordered by amount, so
     * {@code GET /api/billing/payg-tiers} can render small/medium/large in the
     * canonical order. The V251 seed inserts {@code payg_small}, {@code payg_medium},
     * {@code payg_large} on the PAYG plan; this query is intentionally broad
     * ({@code LIKE 'payg\_%'}) so ops can introduce additional tiers without
     * touching code.
     */
    @Query("SELECT p FROM Price p JOIN FETCH p.plan WHERE p.cadence LIKE 'payg\\_%' ESCAPE '\\' ORDER BY p.amountCents")
    List<Price> findAllPaygTiersOrderedByPrice();
}
