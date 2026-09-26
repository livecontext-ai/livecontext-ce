package com.apimarketplace.agent.repository;

import com.apimarketplace.agent.domain.CatalogBundleSyncStatusEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

public interface CatalogBundleSyncStatusRepository
        extends JpaRepository<CatalogBundleSyncStatusEntity, Short> {

    /**
     * The only writer of the poll backoff columns (mapped read-only on the entity). Touches
     * nothing else on the row. Returns 0 if the singleton row is missing.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    // JPQL rather than native SQL: nextAttemptAt is bound through the attribute's own mapping
    // (and hibernate.jdbc.time_zone=UTC), exactly like the entity's other timestamps, and the
    // statement needs no schema qualification.
    @Query("UPDATE CatalogBundleSyncStatusEntity s SET s.backoffLevel = :level, s.nextAttemptAt = :nextAttemptAt "
            + "WHERE s.id = 1")
    int updateBackoff(@Param("level") int level, @Param("nextAttemptAt") Instant nextAttemptAt);
}
