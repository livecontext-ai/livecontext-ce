package com.apimarketplace.catalog.repository;

import com.apimarketplace.catalog.domain.ApiToolMqttConfigEntity;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiToolMqttConfigRepository extends CrudRepository<ApiToolMqttConfigEntity, UUID> {
    Optional<ApiToolMqttConfigEntity> findByApiToolId(UUID apiToolId);
    void deleteByApiToolId(UUID apiToolId);
}
