package com.apimarketplace.trigger.repository;

import com.apimarketplace.trigger.domain.WebhookCallLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Repository for webhook call logs.
 */
@Repository
public interface WebhookCallLogRepository extends JpaRepository<WebhookCallLogEntity, Long> {

    Page<WebhookCallLogEntity> findByWebhookIdOrderByCalledAtDesc(UUID webhookId, Pageable pageable);
}
