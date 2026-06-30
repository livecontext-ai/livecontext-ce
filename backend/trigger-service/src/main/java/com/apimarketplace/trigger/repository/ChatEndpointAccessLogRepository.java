package com.apimarketplace.trigger.repository;

import com.apimarketplace.trigger.domain.ChatEndpointAccessLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface ChatEndpointAccessLogRepository extends JpaRepository<ChatEndpointAccessLogEntity, Long> {

    Page<ChatEndpointAccessLogEntity> findByChatEndpointIdOrderByAccessedAtDesc(UUID chatEndpointId, Pageable pageable);
}
