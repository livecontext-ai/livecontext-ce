package com.apimarketplace.catalog.repository;

import com.apimarketplace.catalog.domain.ToolSignalEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ToolSignalRepository extends CrudRepository<ToolSignalEntity, UUID> {
    Optional<ToolSignalEntity> findByToolId(UUID toolId);

    /**
     * Batch load tool signals for multiple tool IDs (fixes N+1 query problem).
     * Returns all signals for the given tool IDs in a single query.
     */
    @Query("SELECT ts FROM ToolSignalEntity ts WHERE ts.toolId IN :toolIds")
    List<ToolSignalEntity> findByToolIdIn(@Param("toolIds") Collection<UUID> toolIds);
}
