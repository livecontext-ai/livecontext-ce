package com.apimarketplace.storage.service;

import com.apimarketplace.storage.domain.StoredFile;
import com.apimarketplace.storage.repository.StoredFileRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

// Disambiguated bean name: this service manages the legacy user-uploaded
// `stored_files` table (per-user file CRUD). The catalog-tool indexer
// {@code com.apimarketplace.common.storage.service.StorageService} keeps
// the default name "storageService" to avoid touching every consumer that
// already wires it.
@Service("legacyStoredFileService")
public class StorageService {

    @Autowired
    private StoredFileRepository storedFileRepository;

    @Value("${storage.local.path:./uploads}")
    private String localStoragePath;

    @Value("${storage.max-file-size:10485760}") // 10MB par defaut
    private long maxFileSize;

    @Value("${storage.allowed-types:image/*,application/pdf,text/*}")
    private String allowedContentTypes;

    @Transactional
    public StoredFile storeFile(MultipartFile file, Long userId, String organizationId,
                                 String description) throws IOException {
        // Validation du fichier
        validateFile(file);

        // Generation d'un nom de fichier unique
        String originalFileName = file.getOriginalFilename();
        String fileExtension = getFileExtension(originalFileName);
        String uniqueFileName = UUID.randomUUID().toString() + fileExtension;

        // Creation du repertoire de stockage
        Path uploadDir = Paths.get(localStoragePath, userId.toString());
        Files.createDirectories(uploadDir);

        // Stockage du fichier
        Path filePath = uploadDir.resolve(uniqueFileName);
        Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);

        // Creation de l'entite StoredFile
        StoredFile storedFile = new StoredFile(
            userId,
            uniqueFileName,
            originalFileName,
            file.getContentType(),
            file.getSize(),
            filePath.toString()
        );
        storedFile.setDescription(description);
        storedFile.setStorageProvider("local");
        storedFile.setStorageKey(uniqueFileName);
        // V261 NOT NULL - stamp from request header. OrgScopedEntityListener
        // is the @PrePersist safety net but explicit stamping makes the
        // mandatory dependency obvious at the call site.
        storedFile.setOrganizationId(organizationId);

        return storedFileRepository.save(storedFile);
    }

    /**
     * Met a jour le lastAccessed d'un fichier dans une transaction separee
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateLastAccessed(StoredFile file) {
        file.updateLastAccessed();
        storedFileRepository.save(file);
    }

    @Transactional(readOnly = true)
    public Optional<StoredFile> getFile(Long fileId, Long userId) {
        return getFile(fileId, userId, null);
    }

    @Transactional(readOnly = true)
    public Optional<StoredFile> getFile(Long fileId, Long userId, String organizationId) {
        Optional<StoredFile> fileOpt = storedFileRepository.findById(fileId);
        if (fileOpt.isPresent()) {
            StoredFile file = fileOpt.get();
            if (matchesOrganization(file, organizationId) && (file.getUserId().equals(userId) || file.isPublic())) {
                // Mise a jour du lastAccessed dans une transaction separee
                updateLastAccessed(file);
                return Optional.of(file);
            }
        }
        return Optional.empty();
    }

    @Transactional(readOnly = true)
    public List<StoredFile> getUserFiles(Long userId) {
        return getUserFiles(userId, null);
    }

    @Transactional(readOnly = true)
    public List<StoredFile> getUserFiles(Long userId, String organizationId) {
        if (isBlank(organizationId)) {
            return storedFileRepository.findByUserIdOrderByCreatedAtDesc(userId);
        }
        return storedFileRepository.findByUserIdAndOrganizationIdOrderByCreatedAtDesc(userId, organizationId.trim());
    }

    @Transactional(readOnly = true)
    public List<StoredFile> getPublicFiles() {
        return getPublicFiles(null);
    }

    @Transactional(readOnly = true)
    public List<StoredFile> getPublicFiles(String organizationId) {
        if (isBlank(organizationId)) {
            return storedFileRepository.findByIsPublicTrueOrderByCreatedAtDesc();
        }
        return storedFileRepository.findByIsPublicTrueAndOrganizationIdOrderByCreatedAtDesc(organizationId.trim());
    }

    @Transactional
    public StoredFile updateFile(Long fileId, Long userId, String description, boolean isPublic) {
        return updateFile(fileId, userId, null, description, isPublic);
    }

    @Transactional
    public StoredFile updateFile(Long fileId, Long userId, String organizationId, String description, boolean isPublic) {
        Optional<StoredFile> fileOpt = isBlank(organizationId)
                ? storedFileRepository.findByIdAndUserId(fileId, userId)
                : storedFileRepository.findByIdAndUserIdAndOrganizationId(fileId, userId, organizationId.trim());
        if (fileOpt.isPresent()) {
            StoredFile file = fileOpt.get();
            file.setDescription(description);
            file.setPublic(isPublic);
            return storedFileRepository.save(file);
        }
        throw new RuntimeException("Fichier non trouve ou acces non autorise");
    }

    @Transactional
    public void deleteFile(Long fileId, Long userId) throws IOException {
        deleteFile(fileId, userId, null);
    }

    @Transactional
    public void deleteFile(Long fileId, Long userId, String organizationId) throws IOException {
        Optional<StoredFile> fileOpt = isBlank(organizationId)
                ? storedFileRepository.findByIdAndUserId(fileId, userId)
                : storedFileRepository.findByIdAndUserIdAndOrganizationId(fileId, userId, organizationId.trim());
        if (fileOpt.isPresent()) {
            StoredFile file = fileOpt.get();
            
            // Suppression du fichier physique
            Path filePath = Paths.get(file.getFilePath());
            if (Files.exists(filePath)) {
                Files.delete(filePath);
            }
            
            // Suppression de l'enregistrement en base
            storedFileRepository.delete(file);
        } else {
            throw new RuntimeException("Fichier non trouve ou acces non autorise");
        }
    }

    @Transactional
    public void deleteUserFiles(Long userId) throws IOException {
        deleteUserFiles(userId, null);
    }

    @Transactional
    public void deleteUserFiles(Long userId, String organizationId) throws IOException {
        List<StoredFile> userFiles = isBlank(organizationId)
                ? storedFileRepository.findByUserId(userId)
                : storedFileRepository.findByUserIdAndOrganizationId(userId, organizationId.trim());
        
        for (StoredFile file : userFiles) {
            // Suppression du fichier physique
            Path filePath = Paths.get(file.getFilePath());
            if (Files.exists(filePath)) {
                Files.delete(filePath);
            }
        }
        
        // Suppression de tous les enregistrements
        if (isBlank(organizationId)) {
            storedFileRepository.deleteByUserId(userId);
        } else {
            storedFileRepository.deleteByUserIdAndOrganizationId(userId, organizationId.trim());
        }
    }

    private boolean matchesOrganization(StoredFile file, String organizationId) {
        return isBlank(organizationId) || organizationId.trim().equals(file.getOrganizationId());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public Path getFilePath(StoredFile storedFile) {
        return Paths.get(storedFile.getFilePath());
    }

    public long getStorageUsage(Long userId) {
        return getStorageUsage(userId, null);
    }

    public long getStorageUsage(Long userId, String organizationId) {
        List<StoredFile> userFiles = isBlank(organizationId)
                ? storedFileRepository.findByUserId(userId)
                : storedFileRepository.findByUserIdAndOrganizationId(userId, organizationId.trim());
        return userFiles.stream()
                .mapToLong(StoredFile::getFileSize)
                .sum();
    }

    public void cleanupExpiredFiles() {
        // Suppression des fichiers expires (plus de 30 jours sans acces)
        LocalDateTime threshold = LocalDateTime.now().minusDays(30);
        List<StoredFile> expiredFiles = storedFileRepository.findByLastAccessedAtBefore(threshold);
        
        for (StoredFile file : expiredFiles) {
            try {
                deleteFile(file.getId(), file.getUserId());
            } catch (IOException e) {
                // Log l'erreur mais continue avec les autres fichiers
                System.err.println("Erreur lors de la suppression du fichier expire: " + file.getId());
            }
        }
    }

    private String getFileExtension(String fileName) {
        if (fileName == null || fileName.lastIndexOf(".") == -1) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf("."));
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new RuntimeException("Le fichier est vide");
        }

        if (file.getSize() > maxFileSize) {
            throw new RuntimeException("Le fichier est trop volumineux. Taille maximale: " + (maxFileSize / 1024 / 1024) + "MB");
        }

        // Validation du type de contenu
        String contentType = file.getContentType();
        if (contentType != null && !isAllowedContentType(contentType)) {
            throw new RuntimeException("Type de fichier non autorise: " + contentType);
        }
    }

    private boolean isAllowedContentType(String contentType) {
        String[] allowedTypes = allowedContentTypes.split(",");
        for (String allowedType : allowedTypes) {
            if (contentType.matches(allowedType.trim().replace("*", ".*"))) {
                return true;
            }
        }
        return false;
    }
}
