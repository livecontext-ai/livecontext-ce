package com.apimarketplace.auth.credential.repository;

import com.apimarketplace.auth.credential.domain.PricingVersionEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PricingVersionEntryRepository extends JpaRepository<PricingVersionEntry, Long> {

    List<PricingVersionEntry> findByPricingVersionId(Long pricingVersionId);

    Optional<PricingVersionEntry> findByPricingVersionIdAndApiToolId(
            Long pricingVersionId, UUID apiToolId);
}
