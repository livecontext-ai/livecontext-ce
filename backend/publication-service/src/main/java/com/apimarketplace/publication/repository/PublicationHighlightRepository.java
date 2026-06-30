package com.apimarketplace.publication.repository;

import com.apimarketplace.publication.domain.PublicationHighlightEntity;
import com.apimarketplace.publication.domain.WorkflowPublicationEntity.DisplayMode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PublicationHighlightRepository
        extends JpaRepository<PublicationHighlightEntity, PublicationHighlightEntity.PK> {

    List<PublicationHighlightEntity> findByDisplayModeOrderByRankAsc(DisplayMode displayMode);

    /**
     * Bulk delete via HQL - bypasses Hibernate's L1 cache and flushes synchronously
     * before the subsequent INSERTs in the same transaction. {@code clearAutomatically}
     * + {@code flushAutomatically} guarantees the deferred-unique constraint check
     * sees the right rows at commit time even when the same (displayMode, rank) pair
     * is briefly occupied by old + new entries during a re-rank.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM PublicationHighlightEntity h WHERE h.displayMode = :displayMode")
    int deleteAllByDisplayModeBulk(@Param("displayMode") DisplayMode displayMode);
}
