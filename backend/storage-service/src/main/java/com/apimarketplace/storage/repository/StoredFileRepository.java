package com.apimarketplace.storage.repository;

import com.apimarketplace.storage.domain.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {

    /**
     * TOLERANT (cross-workspace) finder family - {@code findByUserId*} /
     * {@code findByIdAndUserId} filter by owner only, WITHOUT an org predicate.
     * Post-V263 every row carries a non-null {@code organization_id}, and all
     * user-facing traffic reaches {@code StorageService} WITH the gateway-injected
     * org header (which routes to the {@code *AndOrganizationId} strict variants).
     * These owner-only finders serve the null-org branch: INTERNAL/daemon callers
     * with no request context, which legitimately see the user's rows across
     * their workspaces (same user - never cross-user). Do NOT use them on a
     * gateway-facing path: that would leak org-workspace rows into personal
     * scope, violating the ScopeGuard strict-isolation contract.
     */
    List<StoredFile> findByUserId(Long userId);

    List<StoredFile> findByUserIdAndOrganizationId(Long userId, String organizationId);
    
    List<StoredFile> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<StoredFile> findByUserIdAndOrganizationIdOrderByCreatedAtDesc(Long userId, String organizationId);
    
    List<StoredFile> findByIsPublicTrueOrderByCreatedAtDesc();

    List<StoredFile> findByIsPublicTrueAndOrganizationIdOrderByCreatedAtDesc(String organizationId);
    
    Optional<StoredFile> findByIdAndUserId(Long id, Long userId);

    Optional<StoredFile> findByIdAndUserIdAndOrganizationId(Long id, Long userId, String organizationId);
    
    List<StoredFile> findByUserIdAndContentTypeContaining(Long userId, String contentType);
    
    List<StoredFile> findByUserIdAndFileNameContainingIgnoreCase(Long userId, String fileName);
    
    @Query("SELECT f FROM StoredFile f WHERE f.userId = :userId AND f.fileSize > :minSize")
    List<StoredFile> findByUserIdAndFileSizeGreaterThan(@Param("userId") Long userId, @Param("minSize") Long minSize);
    
    @Query("SELECT f FROM StoredFile f WHERE f.lastAccessedAt < :threshold")
    List<StoredFile> findByLastAccessedAtBefore(@Param("threshold") LocalDateTime threshold);
    
    @Query("SELECT SUM(f.fileSize) FROM StoredFile f WHERE f.userId = :userId")
    Long getTotalStorageUsedByUser(@Param("userId") Long userId);
    
    @Query("SELECT COUNT(f) FROM StoredFile f WHERE f.userId = :userId")
    Long countByUserId(@Param("userId") Long userId);
    
    @Modifying
    @Query("DELETE FROM StoredFile f WHERE f.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("DELETE FROM StoredFile f WHERE f.userId = :userId AND f.organizationId = :organizationId")
    void deleteByUserIdAndOrganizationId(@Param("userId") Long userId, @Param("organizationId") String organizationId);
    
    @Modifying
    @Query("UPDATE StoredFile f SET f.isPublic = :isPublic WHERE f.id = :fileId AND f.userId = :userId")
    int updateFileVisibility(@Param("fileId") Long fileId, @Param("userId") Long userId, @Param("isPublic") boolean isPublic);
    
    @Query("SELECT f FROM StoredFile f WHERE f.userId = :userId AND f.createdAt >= :since")
    List<StoredFile> findRecentFilesByUser(@Param("userId") Long userId, @Param("since") LocalDateTime since);
    
    @Query("SELECT f.contentType, COUNT(f) FROM StoredFile f WHERE f.userId = :userId GROUP BY f.contentType")
    List<Object[]> getFileTypeDistributionByUser(@Param("userId") Long userId);
}
