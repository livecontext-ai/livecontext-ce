package com.apimarketplace.catalog.repository;

import com.apimarketplace.catalog.domain.ApiToolRedisConfigEntity;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiToolRedisConfigRepository extends CrudRepository<ApiToolRedisConfigEntity, UUID> {
    Optional<ApiToolRedisConfigEntity> findByApiToolId(UUID apiToolId);
    void deleteByApiToolId(UUID apiToolId);
}
