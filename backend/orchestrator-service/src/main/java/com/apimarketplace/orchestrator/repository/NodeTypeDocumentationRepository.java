package com.apimarketplace.orchestrator.repository;

import com.apimarketplace.orchestrator.domain.NodeTypeDocumentationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for NodeTypeDocumentation with full-text search support.
 * Uses PostgreSQL ts_vector for efficient multi-language search.
 */
@Repository
public interface NodeTypeDocumentationRepository extends JpaRepository<NodeTypeDocumentationEntity, Long> {

    /**
     * Find a node type by its unique type identifier.
     */
    Optional<NodeTypeDocumentationEntity> findByType(String type);

    /**
     * Find all node types in a category.
     */
    List<NodeTypeDocumentationEntity> findByCategory(String category);

    /**
     * Full-text search across label, description, and keywords.
     * Uses PostgreSQL plainto_tsquery for natural language queries.
     * Searches in both French and English.
     *
     * @param query The search query (e.g., "boucle liste", "forEach array")
     * @param limit Maximum number of results
     * @return List of matching node types ordered by relevance
     */
    @Query(value = """
        SELECT * FROM node_type_documentation
        WHERE search_vector @@ (
            plainto_tsquery('french', :query) ||
            plainto_tsquery('english', :query) ||
            plainto_tsquery('simple', :query)
        )
        ORDER BY ts_rank(search_vector,
            plainto_tsquery('french', :query) ||
            plainto_tsquery('english', :query) ||
            plainto_tsquery('simple', :query)
        ) DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<NodeTypeDocumentationEntity> searchByQuery(@Param("query") String query, @Param("limit") int limit);

    /**
     * Full-text search with category filter.
     *
     * @param query    The search query
     * @param category The category to filter by (trigger, action, control_flow, ai)
     * @param limit    Maximum number of results
     * @return List of matching node types ordered by relevance
     */
    @Query(value = """
        SELECT * FROM node_type_documentation
        WHERE category = :category
        AND search_vector @@ (
            plainto_tsquery('french', :query) ||
            plainto_tsquery('english', :query) ||
            plainto_tsquery('simple', :query)
        )
        ORDER BY ts_rank(search_vector,
            plainto_tsquery('french', :query) ||
            plainto_tsquery('english', :query) ||
            plainto_tsquery('simple', :query)
        ) DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<NodeTypeDocumentationEntity> searchByQueryAndCategory(
            @Param("query") String query,
            @Param("category") String category,
            @Param("limit") int limit);

    /**
     * Fallback search using ILIKE for simple substring matching.
     * Used when full-text search returns no results.
     *
     * @param pattern The search pattern with wildcards (e.g., "%loop%")
     * @param limit   Maximum number of results
     * @return List of matching node types
     */
    @Query(value = """
        SELECT * FROM node_type_documentation
        WHERE LOWER(label) LIKE LOWER(:pattern)
           OR LOWER(description) LIKE LOWER(:pattern)
           OR LOWER(type) LIKE LOWER(:pattern)
        ORDER BY
            CASE WHEN LOWER(type) LIKE LOWER(:pattern) THEN 0 ELSE 1 END,
            CASE WHEN LOWER(label) LIKE LOWER(:pattern) THEN 0 ELSE 1 END,
            label
        LIMIT :limit
        """, nativeQuery = true)
    List<NodeTypeDocumentationEntity> searchByPattern(@Param("pattern") String pattern, @Param("limit") int limit);

    /**
     * Get all node types ordered by category and label.
     */
    List<NodeTypeDocumentationEntity> findAllByOrderByCategoryAscLabelAsc();

    /**
     * Get all enabled node types ordered by category and label (for agent).
     */
    List<NodeTypeDocumentationEntity> findByEnabledTrueOrderByCategoryAscLabelAsc();

    /**
     * Find all nodes with a specific variable prefix.
     */
    List<NodeTypeDocumentationEntity> findByVariablePrefix(String variablePrefix);

    /**
     * Find all enabled nodes with a specific variable prefix.
     */
    List<NodeTypeDocumentationEntity> findByEnabledTrueAndVariablePrefix(String variablePrefix);

    /**
     * Get all node types ordered by variable prefix and label.
     */
    List<NodeTypeDocumentationEntity> findAllByOrderByVariablePrefixAscLabelAsc();

    /**
     * Get all enabled node types ordered by variable prefix and label.
     */
    List<NodeTypeDocumentationEntity> findByEnabledTrueOrderByVariablePrefixAscLabelAsc();

    /**
     * Full-text search filtering only enabled node types (for agent).
     */
    @Query(value = """
        SELECT * FROM node_type_documentation
        WHERE enabled = true
        AND search_vector @@ (
            plainto_tsquery('french', :query) ||
            plainto_tsquery('english', :query) ||
            plainto_tsquery('simple', :query)
        )
        ORDER BY ts_rank(search_vector,
            plainto_tsquery('french', :query) ||
            plainto_tsquery('english', :query) ||
            plainto_tsquery('simple', :query)
        ) DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<NodeTypeDocumentationEntity> searchByQueryEnabled(@Param("query") String query, @Param("limit") int limit);

    /**
     * Fallback ILIKE search filtering only enabled node types (for agent).
     */
    @Query(value = """
        SELECT * FROM node_type_documentation
        WHERE enabled = true
        AND (LOWER(label) LIKE LOWER(:pattern)
           OR LOWER(description) LIKE LOWER(:pattern)
           OR LOWER(type) LIKE LOWER(:pattern))
        ORDER BY
            CASE WHEN LOWER(type) LIKE LOWER(:pattern) THEN 0 ELSE 1 END,
            CASE WHEN LOWER(label) LIKE LOWER(:pattern) THEN 0 ELSE 1 END,
            label
        LIMIT :limit
        """, nativeQuery = true)
    List<NodeTypeDocumentationEntity> searchByPatternEnabled(@Param("pattern") String pattern, @Param("limit") int limit);
}
