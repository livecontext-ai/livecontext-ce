package com.apimarketplace.catalog.mapping.repository;

import com.apimarketplace.catalog.mapping.entity.MappingVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for mapping versions
 */
@Repository
public interface MappingVersionRepository extends JpaRepository<MappingVersionEntity, Long> {
    
    /**
     * Find mapping versions by mapping definition ID
     */
    List<MappingVersionEntity> findByMappingDefinitionIdOrderByCreatedAtDesc(Long mappingDefinitionId);
    
    /**
     * Find latest mapping version by mapping definition ID
     */
    @Query("SELECT mv FROM MappingVersionEntity mv WHERE mv.mappingDefinitionId = :mappingDefinitionId AND mv.isLatest = true")
    Optional<MappingVersionEntity> findLatestByMappingDefinitionId(@Param("mappingDefinitionId") Long mappingDefinitionId);
    
    /**
     * Find mapping version by mapping definition ID and version
     */
    @Query("SELECT mv FROM MappingVersionEntity mv WHERE mv.mappingDefinitionId = :mappingDefinitionId AND mv.version = :version")
    Optional<MappingVersionEntity> findByMappingDefinitionIdAndVersion(@Param("mappingDefinitionId") Long mappingDefinitionId, @Param("version") String version);
    
    /**
     * Set all versions as not latest for a mapping definition
     */
    @Modifying
    @Query("UPDATE MappingVersionEntity mv SET mv.isLatest = false WHERE mv.mappingDefinitionId = :mappingDefinitionId")
    void setAllVersionsAsNotLatest(@Param("mappingDefinitionId") Long mappingDefinitionId);
    
    /**
     * Set a specific version as latest
     */
    @Modifying
    @Query("UPDATE MappingVersionEntity mv SET mv.isLatest = true WHERE mv.id = :versionId")
    void setVersionAsLatest(@Param("versionId") Long versionId);
    
    /**
     * Find latest mapping version by tool ID
     */
    @Query("SELECT mv FROM MappingVersionEntity mv " +
           "JOIN MappingDefinitionEntity md ON mv.mappingDefinitionId = md.id " +
           "WHERE md.toolId = :toolId AND md.status = 'ACTIVE' AND mv.isLatest = true")
    Optional<MappingVersionEntity> findLatestByToolId(@Param("toolId") java.util.UUID toolId);
    
}
