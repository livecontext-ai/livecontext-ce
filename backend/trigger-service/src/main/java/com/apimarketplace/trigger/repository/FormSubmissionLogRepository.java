package com.apimarketplace.trigger.repository;

import com.apimarketplace.trigger.domain.FormSubmissionLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface FormSubmissionLogRepository extends JpaRepository<FormSubmissionLogEntity, Long> {

    Page<FormSubmissionLogEntity> findByFormEndpointIdOrderBySubmittedAtDesc(UUID formEndpointId, Pageable pageable);
}
