package com.apimarketplace.catalog.seed;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CatalogSeedStateRepository extends CrudRepository<CatalogSeedStateEntity, UUID> {

    Optional<CatalogSeedStateEntity> findBySeedId(String seedId);
}
