package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.PersonalOfferPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;

public interface PersonalOfferPolicyRepository extends JpaRepository<PersonalOfferPolicy, Long> {
    Optional<PersonalOfferPolicy> findByCampaignKeyAndState(String campaignKey, String state);
    List<PersonalOfferPolicy> findByCampaignKeyOrderByVersionDesc(String campaignKey);
    List<PersonalOfferPolicy> findAllByOrderByIdDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PersonalOfferPolicy p where p.campaignKey = :campaign order by p.id")
    List<PersonalOfferPolicy> lockCampaign(@Param("campaign") String campaign);
}
