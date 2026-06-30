package com.apimarketplace.catalog.repository;

import com.apimarketplace.catalog.domain.ApiToolAmqpConfigEntity;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiToolAmqpConfigRepository extends CrudRepository<ApiToolAmqpConfigEntity, UUID> {
    Optional<ApiToolAmqpConfigEntity> findByApiToolId(UUID apiToolId);
    void deleteByApiToolId(UUID apiToolId);
}
