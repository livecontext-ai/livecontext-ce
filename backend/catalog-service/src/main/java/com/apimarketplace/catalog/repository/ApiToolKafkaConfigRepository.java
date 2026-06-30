package com.apimarketplace.catalog.repository;

import com.apimarketplace.catalog.domain.ApiToolKafkaConfigEntity;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiToolKafkaConfigRepository extends CrudRepository<ApiToolKafkaConfigEntity, UUID> {
    Optional<ApiToolKafkaConfigEntity> findByApiToolId(UUID apiToolId);
    void deleteByApiToolId(UUID apiToolId);
}
