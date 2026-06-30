package com.apimarketplace.catalog.repository;

import com.apimarketplace.catalog.domain.ToolResponseEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository JPA pour les reponses des outils avec support JSONB
 */
@Repository
public interface ToolResponseRepository extends JpaRepository<ToolResponseEntity, java.util.UUID> {

    /**
     * Trouve toutes les reponses pour un outil donne
     */
    List<ToolResponseEntity> findByToolId(java.util.UUID toolId);

    /**
     * Trouve la reponse par defaut pour un outil
     */
    Optional<ToolResponseEntity> findByToolIdAndIsDefaultTrue(java.util.UUID toolId);

    /**
     * Trouve une reponse specifique par nom pour un outil
     */
    Optional<ToolResponseEntity> findByToolIdAndName(java.util.UUID toolId, String name);

    /**
     * Trouve toutes les reponses avec un code de statut specifique pour un outil
     */
    List<ToolResponseEntity> findByToolIdAndStatusCode(java.util.UUID toolId, Integer statusCode);

    /**
     * Trouve les reponses par defaut pour un outil donne
     */
    List<ToolResponseEntity> findByToolIdAndIsDefault(java.util.UUID toolId, Boolean isDefault);

    /**
     * Compte le nombre de reponses pour un outil donne
     */
    long countByToolId(java.util.UUID toolId);


    /**
     * Verifie si une reponse avec ce nom existe deja pour cet outil
     */
    boolean existsByToolIdAndName(java.util.UUID toolId, String name);

    /**
     * Supprime toutes les reponses d'un outil
     */
    void deleteByToolId(java.util.UUID toolId);

    // ==============================================
    // REQUeTES JSONB AVANCeES
    // ==============================================

    /**
     * Recherche par containment JSONB - verifie si example_jsonb contient le filtre
     */
    @Query(value = "SELECT * FROM tool_responses WHERE example_jsonb @> cast(?1 AS jsonb)", nativeQuery = true)
    List<ToolResponseEntity> findByJsonbContainment(String filter);

    /**
     * Recherche par cle-valeur dans example_jsonb
     */
    @Query(value = "SELECT * FROM tool_responses WHERE example_jsonb ->> ?1 = ?2", nativeQuery = true)
    List<ToolResponseEntity> findByJsonbKeyValue(String key, String value);

    /**
     * Recherche par cle-valeur dans example_jsonb avec tool_id
     */
    @Query(value = "SELECT * FROM tool_responses WHERE tool_id = ?1 AND example_jsonb ->> ?2 = ?3", nativeQuery = true)
    List<ToolResponseEntity> findByToolIdAndJsonbKeyValue(java.util.UUID toolId, String key, String value);

    /**
     * Recherche par existence de cle dans example_jsonb
     */
    @Query(value = "SELECT * FROM tool_responses WHERE example_jsonb -> :key IS NOT NULL", nativeQuery = true)
    List<ToolResponseEntity> findByJsonbKeyExists(@Param("key") String key);

    /**
     * Recherche par format specifique
     */
    List<ToolResponseEntity> findByResponseFormat(com.apimarketplace.catalog.domain.ResponseFormat responseFormat);

    /**
     * Recherche par format et tool_id
     */
    List<ToolResponseEntity> findByToolIdAndResponseFormat(java.util.UUID toolId, com.apimarketplace.catalog.domain.ResponseFormat responseFormat);

    /**
     * Compte les reponses par format pour un outil
     */
    long countByToolIdAndResponseFormat(java.util.UUID toolId, com.apimarketplace.catalog.domain.ResponseFormat responseFormat);

    /**
     * Recherche les reponses actives pour un outil
     */
    List<ToolResponseEntity> findByToolIdAndIsActive(java.util.UUID toolId, Boolean isActive);

    /**
     * Recherche les reponses actives par format
     */
    List<ToolResponseEntity> findByResponseFormatAndIsActive(com.apimarketplace.catalog.domain.ResponseFormat responseFormat, Boolean isActive);

    /**
     * Recherche la reponse par defaut active pour un outil
     */
    Optional<ToolResponseEntity> findByToolIdAndIsDefaultTrueAndIsActiveTrue(java.util.UUID toolId);

    /**
     * Compte les reponses actives pour un outil
     */
    long countByToolIdAndIsActive(java.util.UUID toolId, Boolean isActive);

    /**
     * Trouve les reponses qui n'ont pas encore de squelette de structure (pour migration)
     */
    @Query(value = "SELECT * FROM tool_responses WHERE structure_skeleton IS NULL AND example_jsonb IS NOT NULL LIMIT :limit", nativeQuery = true)
    List<ToolResponseEntity> findResponsesWithoutSkeleton(@Param("limit") int limit);

    @Modifying
    @Query(value = "UPDATE tool_responses SET structure_skeleton = CAST(:skeleton AS jsonb) WHERE id = :id", nativeQuery = true)
    int updateStructureSkeleton(@Param("id") java.util.UUID id, @Param("skeleton") String skeleton);

    /**
     * Interface de projection pour les noeuds de structure
     */
    interface StructureNode {
        String getKey();
        String getType();
        Boolean getHasChildren();
    }

    /**
     * Extrait la racine du squelette
     */
    @Query(value = """
            SELECT key,
                   COALESCE(value->>'_t', trim(both '"' from value::text)) as type,
                   COALESCE(value->>'_t', trim(both '"' from value::text)) IN ('obj','arr') as hasChildren
            FROM jsonb_each((SELECT structure_skeleton->'props' FROM tool_responses WHERE id = :id))
            """, nativeQuery = true)
    List<StructureNode> getStructureRoot(@Param("id") java.util.UUID id);

    /**
     * Extrait un sous-chemin du squelette de maniere intelligente (gere obj et arr)
     */
    @Query(value = """
        WITH target_node AS (
          SELECT structure_skeleton #> cast(:path as text[]) as node 
          FROM tool_responses WHERE id = :id
        )
        SELECT key,
               COALESCE(value->>'_t', trim(both '"' from value::text)) as type,
               COALESCE(value->>'_t', trim(both '"' from value::text)) IN ('obj','arr') as hasChildren
        FROM target_node,
        LATERAL jsonb_each(
          CASE 
            WHEN node->>'_t' = 'arr' THEN node->'items'->'props'
            WHEN node->>'_t' = 'obj' THEN node->'props'
            ELSE node
          END
        )
        """, nativeQuery = true)
    List<StructureNode> getStructurePath(@Param("id") java.util.UUID id, @Param("path") String path);

}
