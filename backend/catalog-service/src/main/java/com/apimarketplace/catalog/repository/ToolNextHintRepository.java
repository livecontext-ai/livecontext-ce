package com.apimarketplace.catalog.repository;

import com.apimarketplace.catalog.domain.ToolNextHintEntity;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for tool_next_hint table.
 * Retrieves hints for LLM about what to do after using a tool.
 */
@Repository
public interface ToolNextHintRepository extends CrudRepository<ToolNextHintEntity, UUID> {

    /**
     * Find active hints for a specific API tool, ordered by priority.
     */
    @Query("SELECT h FROM ToolNextHintEntity h WHERE h.apiToolId = :apiToolId AND h.isActive = true ORDER BY h.priority ASC")
    List<ToolNextHintEntity> findByApiToolId(@Param("apiToolId") UUID apiToolId);

    /**
     * Find active hints for a tool name (generic, applies to all implementations).
     */
    @Query("SELECT h FROM ToolNextHintEntity h WHERE h.toolNameId = :toolNameId AND h.isActive = true ORDER BY h.priority ASC")
    List<ToolNextHintEntity> findByToolNameId(@Param("toolNameId") UUID toolNameId);

    /**
     * Find hints for either the specific API tool or its generic tool name.
     * Returns hints ordered by priority, with specific hints first.
     */
    @Query("""
        SELECT h FROM ToolNextHintEntity h
        WHERE (h.apiToolId = :apiToolId OR h.toolNameId = :toolNameId)
          AND h.isActive = true
        ORDER BY
          CASE WHEN h.apiToolId = :apiToolId THEN 0 ELSE 1 END,
          h.priority ASC
        """)
    List<ToolNextHintEntity> findByApiToolIdOrToolNameId(
        @Param("apiToolId") UUID apiToolId,
        @Param("toolNameId") UUID toolNameId
    );
}
