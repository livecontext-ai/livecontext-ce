package com.apimarketplace.publication.repository;

import com.apimarketplace.publication.domain.PublicationSnapshotVersionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PublicationSnapshotVersionRepository extends JpaRepository<PublicationSnapshotVersionEntity, UUID> {

    @Query("SELECT MAX(v.version) FROM PublicationSnapshotVersionEntity v WHERE v.publicationId = :publicationId")
    Optional<Integer> getMaxVersion(@Param("publicationId") UUID publicationId);

    List<PublicationSnapshotVersionEntity> findByPublicationIdOrderByVersionDesc(UUID publicationId);

    Optional<PublicationSnapshotVersionEntity> findByPublicationIdAndVersion(UUID publicationId, Integer version);

    long countByPublicationId(UUID publicationId);

    /**
     * Drop the ENTIRE snapshot-version history of a publication. Used by the
     * retention policy for never-acquired publications (we keep nothing - the
     * live plan_snapshot on workflow_publications covers a fresh acquisition).
     * Replaces the old keep-last-5 {@code purgeOldVersions}.
     */
    @Modifying
    @Query(value = "DELETE FROM publication_snapshot_versions WHERE publication_id = :publicationId", nativeQuery = true)
    int deleteAllByPublicationId(@Param("publicationId") UUID publicationId);
}
