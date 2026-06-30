package com.apimarketplace.storage.web;

import com.apimarketplace.common.web.ContentDispositions;
import com.apimarketplace.storage.domain.StoredFile;
import com.apimarketplace.storage.service.StorageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/storage")
@CrossOrigin(origins = "*")
public class StorageController {

    @Autowired
    private StorageService storageService;

    /**
     * Resolve the caller's user id from the gateway-injected X-User-ID header.
     * Audit 2026-05-16 round-3 - every legacy {@code @RequestParam userId}
     * was a Bug-#4 surface (client-supplied identity). All endpoints now
     * derive userId from the header; any {@code {userId}}/`userId` query
     * arg MUST match.
     */
    private Long resolveCallerId(String userIdHeader) {
        if (userIdHeader == null || userIdHeader.isBlank()) return null;
        try {
            return Long.parseLong(userIdHeader);
        } catch (NumberFormatException nfe) {
            return null;
        }
    }

    private String resolveCurrentOrganizationId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        String organizationId = attrs.getRequest().getHeader("X-Organization-ID");
        return organizationId == null || organizationId.isBlank() ? null : organizationId.trim();
    }

    @PostMapping("/upload")
    public ResponseEntity<StoredFile> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "description", required = false) String description,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId) {
        Long userId = resolveCallerId(userIdHeader);
        if (userId == null) return ResponseEntity.status(401).build();
        if (organizationId == null || organizationId.isBlank()) {
            // Post-V261: organization_id is NOT NULL on stored_files. Fail fast
            // with a clear 400 instead of letting the JPA INSERT crash at NOT
            // NULL violation. Gateway injects the header from the JWT for any
            // authenticated request; absence here means a misconfigured client.
            return ResponseEntity.badRequest().build();
        }
        try {
            StoredFile storedFile = storageService.storeFile(file, userId, organizationId.trim(), description);
            return ResponseEntity.ok(storedFile);
        } catch (IOException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/files/{fileId}")
    public ResponseEntity<StoredFile> getFileInfo(
            @PathVariable Long fileId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader) {
        Long userId = resolveCallerId(userIdHeader);
        if (userId == null) return ResponseEntity.status(401).build();
        String organizationId = resolveCurrentOrganizationId();
        Optional<StoredFile> fileOpt = organizationId == null
                ? storageService.getFile(fileId, userId)
                : storageService.getFile(fileId, userId, organizationId);
        return fileOpt
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/files/{fileId}/download")
    public ResponseEntity<Resource> downloadFile(
            @PathVariable Long fileId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader) {
        Long userId = resolveCallerId(userIdHeader);
        if (userId == null) return ResponseEntity.status(401).build();
        try {
            String organizationId = resolveCurrentOrganizationId();
            Optional<StoredFile> fileOpt = organizationId == null
                    ? storageService.getFile(fileId, userId)
                    : storageService.getFile(fileId, userId, organizationId);
            if (fileOpt.isPresent()) {
                StoredFile storedFile = fileOpt.get();
                Path filePath = storageService.getFilePath(storedFile);
                Resource resource = new UrlResource(filePath.toUri());

                if (resource.exists() && resource.isReadable()) {
                    return ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType(storedFile.getContentType()))
                            .header(HttpHeaders.CONTENT_DISPOSITION,
                                    ContentDispositions.attachment(storedFile.getOriginalName()))
                            .body(resource);
                }
            }
            return ResponseEntity.notFound().build();
        } catch (MalformedURLException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/files/{fileId}/view")
    public ResponseEntity<Resource> viewFile(
            @PathVariable Long fileId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader) {
        Long userId = resolveCallerId(userIdHeader);
        if (userId == null) return ResponseEntity.status(401).build();
        try {
            String organizationId = resolveCurrentOrganizationId();
            Optional<StoredFile> fileOpt = organizationId == null
                    ? storageService.getFile(fileId, userId)
                    : storageService.getFile(fileId, userId, organizationId);
            if (fileOpt.isPresent()) {
                StoredFile storedFile = fileOpt.get();
                Path filePath = storageService.getFilePath(storedFile);
                Resource resource = new UrlResource(filePath.toUri());

                if (resource.exists() && resource.isReadable()) {
                    return ResponseEntity.ok()
                            .contentType(MediaType.parseMediaType(storedFile.getContentType()))
                            .body(resource);
                }
            }
            return ResponseEntity.notFound().build();
        } catch (MalformedURLException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/user/{userId}/files")
    public ResponseEntity<List<StoredFile>> getUserFiles(
            @PathVariable Long userId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader) {
        Long callerId = resolveCallerId(userIdHeader);
        if (callerId == null) return ResponseEntity.status(401).build();
        if (!callerId.equals(userId)) return ResponseEntity.status(403).build();
        String organizationId = resolveCurrentOrganizationId();
        List<StoredFile> files = organizationId == null
                ? storageService.getUserFiles(userId)
                : storageService.getUserFiles(userId, organizationId);
        return ResponseEntity.ok(files);
    }

    @GetMapping("/public/files")
    public ResponseEntity<List<StoredFile>> getPublicFiles() {
        String organizationId = resolveCurrentOrganizationId();
        List<StoredFile> files = organizationId == null
                ? storageService.getPublicFiles()
                : storageService.getPublicFiles(organizationId);
        return ResponseEntity.ok(files);
    }

    @PutMapping("/files/{fileId}")
    public ResponseEntity<StoredFile> updateFile(
            @PathVariable Long fileId,
            @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "isPublic", required = false) Boolean isPublic,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader) {
        Long userId = resolveCallerId(userIdHeader);
        if (userId == null) return ResponseEntity.status(401).build();
        try {
            String organizationId = resolveCurrentOrganizationId();
            StoredFile updatedFile = organizationId == null
                    ? storageService.updateFile(
                            fileId,
                            userId,
                            description != null ? description : "",
                            isPublic != null ? isPublic : false
                    )
                    : storageService.updateFile(
                            fileId,
                            userId,
                            organizationId,
                            description != null ? description : "",
                            isPublic != null ? isPublic : false
                    );
            return ResponseEntity.ok(updatedFile);
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Delete a single file. Identity comes from the gateway-injected {@code X-User-ID}
     * header, NEVER from a client-supplied query parameter. Audit 2026-05-16:
     * the prior signature accepted {@code @RequestParam Long userId} which let
     * any authenticated user delete any other user's file by guessing the fileId.
     */
    @DeleteMapping("/files/{fileId}")
    public ResponseEntity<Map<String, String>> deleteFile(
            @PathVariable Long fileId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader) {
        if (userIdHeader == null || userIdHeader.isBlank()) {
            return ResponseEntity.status(401).build();
        }
        long userId;
        try {
            userId = Long.parseLong(userIdHeader);
        } catch (NumberFormatException nfe) {
            return ResponseEntity.status(401).build();
        }
        try {
            String organizationId = resolveCurrentOrganizationId();
            if (organizationId == null) {
                storageService.deleteFile(fileId, userId);
            } else {
                storageService.deleteFile(fileId, userId, organizationId);
            }
            Map<String, String> response = new HashMap<>();
            response.put("message", "Fichier supprime avec succes");
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, String> response = new HashMap<>();
            response.put("message", "Erreur lors de la suppression: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Delete all of the caller's files. Identity is the gateway-injected
     * {@code X-User-ID} header - the {@code {userId}} path arg MUST match it,
     * otherwise we return 403. Audit 2026-05-16: prior signature trusted the
     * path arg, letting any authenticated user wipe another user's entire
     * file collection.
     */
    @DeleteMapping("/user/{userId}/files")
    public ResponseEntity<Map<String, String>> deleteUserFiles(
            @PathVariable Long userId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader) {
        if (userIdHeader == null || userIdHeader.isBlank()) {
            return ResponseEntity.status(401).build();
        }
        try {
            long callerId = Long.parseLong(userIdHeader);
            if (callerId != userId) {
                Map<String, String> response = new HashMap<>();
                response.put("message", "Forbidden: cannot delete another user's files");
                return ResponseEntity.status(403).body(response);
            }
            String organizationId = resolveCurrentOrganizationId();
            if (organizationId == null) {
                storageService.deleteUserFiles(userId);
            } else {
                storageService.deleteUserFiles(userId, organizationId);
            }
            Map<String, String> response = new HashMap<>();
            response.put("message", "Tous les fichiers de l'utilisateur ont ete supprimes");
            return ResponseEntity.ok(response);
        } catch (NumberFormatException nfe) {
            return ResponseEntity.status(401).build();
        } catch (Exception e) {
            Map<String, String> response = new HashMap<>();
            response.put("message", "Erreur lors de la suppression: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    @GetMapping("/user/{userId}/usage")
    public ResponseEntity<Map<String, Object>> getStorageUsage(
            @PathVariable Long userId,
            @RequestHeader(value = "X-User-ID", required = false) String userIdHeader) {
        Long callerId = resolveCallerId(userIdHeader);
        if (callerId == null) return ResponseEntity.status(401).build();
        if (!callerId.equals(userId)) return ResponseEntity.status(403).build();
        String organizationId = resolveCurrentOrganizationId();
        long usage = organizationId == null
                ? storageService.getStorageUsage(userId)
                : storageService.getStorageUsage(userId, organizationId);
        Map<String, Object> response = new HashMap<>();
        response.put("userId", userId);
        response.put("usageBytes", usage);
        response.put("usageMB", usage / (1024 * 1024));
        response.put("usageGB", usage / (1024 * 1024 * 1024));
        return ResponseEntity.ok(response);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> response = new HashMap<>();
        response.put("status", "UP");
        response.put("service", "storage-service");
        return ResponseEntity.ok(response);
    }
}
