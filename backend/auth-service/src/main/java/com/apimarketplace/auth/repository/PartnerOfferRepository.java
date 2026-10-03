package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.PartnerOffer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PartnerOfferRepository extends JpaRepository<PartnerOffer, Long> {

    Optional<PartnerOffer> findByToken(String token);

    boolean existsByToken(String token);

    /** The partner's live offers, newest first (V559 index on partner_user_id, created_at). */
    List<PartnerOffer> findByPartnerUserIdAndActiveTrueOrderByCreatedAtDescIdDesc(Long partnerUserId);

    long countByPartnerUserIdAndActiveTrue(Long partnerUserId);
}
