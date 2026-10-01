package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.PersonalOfferMatrix;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface PersonalOfferMatrixRepository extends JpaRepository<PersonalOfferMatrix, PersonalOfferMatrix.Key> {
    List<PersonalOfferMatrix> findByPolicyId(Long policyId);
    List<PersonalOfferMatrix> findByPolicyIdAndMonthlyCredits(Long policyId, int monthlyCredits);
    Optional<PersonalOfferMatrix> findByPolicyIdAndPlanCodeAndMonthlyCredits(Long policyId, String planCode, int monthlyCredits);
    void deleteByPolicyId(Long policyId);
}
