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
