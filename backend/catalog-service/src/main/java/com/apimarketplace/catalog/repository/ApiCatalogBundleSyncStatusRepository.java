package com.apimarketplace.catalog.repository;

import com.apimarketplace.catalog.domain.ApiCatalogBundleSyncStatusEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiCatalogBundleSyncStatusRepository
        extends JpaRepository<ApiCatalogBundleSyncStatusEntity, Short> {
}
