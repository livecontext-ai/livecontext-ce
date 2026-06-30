package com.apimarketplace.catalog.repository;

import com.apimarketplace.catalog.domain.ApiToolMonetizationEntity;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for API tool monetization entities
 */
@Repository
public interface ApiToolMonetizationRepository extends CrudRepository<ApiToolMonetizationEntity, UUID> {
    
    /**
     * Find monetization by API tool ID
     */
    List<ApiToolMonetizationEntity> findByApiToolId(UUID apiToolId);
    
    /**
     * Find monetization by API tool ID and type
     */
    List<ApiToolMonetizationEntity> findByApiToolIdAndMonetizationType(UUID apiToolId, String monetizationType);
}