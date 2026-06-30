package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.BillingCustomer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BillingCustomerRepository extends JpaRepository<BillingCustomer, Long> {

    /**
     * Trouve un client de facturation par son ID utilisateur
     */
    Optional<BillingCustomer> findByUserId(Long userId);

    /**
     * Trouve un client de facturation par son ID Stripe
     */
    Optional<BillingCustomer> findByProviderCustomerId(String providerCustomerId);

    /**
     * Verifie si un utilisateur a deja un client de facturation
     */
    boolean existsByUserId(Long userId);
}
