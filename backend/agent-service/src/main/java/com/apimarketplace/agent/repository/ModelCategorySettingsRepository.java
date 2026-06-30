package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.ModelCategorySettingsEntity;
import com.apimarketplace.agent.domain.ModelCategorySettingsId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ModelCategorySettingsRepository
        extends JpaRepository<ModelCategorySettingsEntity, ModelCategorySettingsId> {

    /**
     * All settings for a category, ordered by rank ascending with null ranks
     * sorting last (so callers can fall back to the global
     * {@code model_config_overrides.ranking}).
     *
     * <p>Spring Data method-name derivation does not understand
     * {@code NullsLast} as a keyword (it tries to interpret it as a property
     * called {@code nullsLast} → context-load failure). Using an explicit
     * JPQL {@code ORDER BY ... ASC NULLS LAST} instead.
     */
    @Query("SELECT s FROM ModelCategorySettingsEntity s "
            + "WHERE s.category = :category "
            + "ORDER BY s.rank ASC NULLS LAST")
    List<ModelCategorySettingsEntity> findByCategoryOrderByRankAscNullsLast(@Param("category") String category);

    /** All categories defined for one model row - used when building the admin UI. */
    List<ModelCategorySettingsEntity> findByModelConfigId(Long modelConfigId);

    /** Bulk fetch for the catalog hot path (one query per category). */
    List<ModelCategorySettingsEntity> findByCategory(String category);

    void deleteByModelConfigId(Long modelConfigId);
}
