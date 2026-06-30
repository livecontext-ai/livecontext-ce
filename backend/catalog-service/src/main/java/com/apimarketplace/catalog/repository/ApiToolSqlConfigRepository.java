package com.apimarketplace.catalog.repository;

import com.apimarketplace.catalog.domain.ApiToolSqlConfigEntity;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ApiToolSqlConfigRepository extends CrudRepository<ApiToolSqlConfigEntity, UUID> {
    Optional<ApiToolSqlConfigEntity> findByApiToolId(UUID apiToolId);
    void deleteByApiToolId(UUID apiToolId);
}
