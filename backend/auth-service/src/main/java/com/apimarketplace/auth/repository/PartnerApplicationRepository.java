package com.apimarketplace.auth.repository;

import com.apimarketplace.auth.domain.PartnerApplication;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PartnerApplicationRepository extends JpaRepository<PartnerApplication, Long> {

    /** The applicant's latest application, whatever its status: what their dashboard shows. */
    Optional<PartnerApplication> findFirstByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    /** The admin queue (V553 index on status, created_at). */
    List<PartnerApplication> findByStatusOrderByCreatedAtDescIdDesc(PartnerApplication.Status status);

    /** Every application, newest first, for the admin's decided view. */
    List<PartnerApplication> findTop200ByOrderByCreatedAtDescIdDesc();

    boolean existsByUserIdAndStatus(Long userId, PartnerApplication.Status status);
}
