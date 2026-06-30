package com.apimarketplace.catalog.mapping.repository;

import com.apimarketplace.catalog.mapping.entity.MappingDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for mapping definitions
 */
@Repository
public interface MappingDefinitionRepository extends JpaRepository<MappingDefinitionEntity, Long> {
    
    
    /**
     * Find mapping definitions by tool ID
     */
    @Query("SELECT md FROM MappingDefinitionEntity md WHERE md.toolId = :toolId AND md.status = 'ACTIVE'")
    List<MappingDefinitionEntity> findByToolId(@Param("toolId") java.util.UUID toolId);
    
    /**
     * Find latest mapping definition by tool ID
     */
    @Query("SELECT md FROM MappingDefinitionEntity md " +
           "JOIN MappingVersionEntity mv ON md.id = mv.mappingDefinitionId " +
           "WHERE md.toolId = :toolId AND md.status = 'ACTIVE' AND mv.isLatest = true")
    Optional<MappingDefinitionEntity> findLatestByToolId(@Param("toolId") java.util.UUID toolId);
    
    /**
     * Check if mapping definition exists by tool ID
     */
    @Query("SELECT COUNT(md) > 0 FROM MappingDefinitionEntity md WHERE md.toolId = :toolId AND md.status = 'ACTIVE'")
    boolean existsByToolId(@Param("toolId") java.util.UUID toolId);
}
