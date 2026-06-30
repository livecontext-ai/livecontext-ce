package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.Plan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PlanRepository extends JpaRepository<Plan, Long> {

    /**
     * Trouve un plan par son code
     */
    Optional<Plan> findByCode(String code);

    /**
     * Trouve tous les plans actifs (non supprimes)
     */
    @Query("SELECT p FROM Plan p ORDER BY p.id")
    List<Plan> findAllActive();

}
