package com.apimarketplace.catalog.repository;

import com.apimarketplace.catalog.domain.ApiToolParameterEntity;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for API tool parameters
 */
@Repository
public interface ApiToolParameterRepository extends CrudRepository<ApiToolParameterEntity, UUID> {
    
    /**
     * Find parameters by API tool ID
     */
    List<ApiToolParameterEntity> findByApiToolId(UUID apiToolId);
    
    /**
     * Find parameters by API tool ID and parameter type
     */
    List<ApiToolParameterEntity> findByApiToolIdAndParameterType(UUID apiToolId, String parameterType);
    
    /**
     * Delete all parameters for a specific API tool
     */
    void deleteByApiToolId(UUID apiToolId);
}