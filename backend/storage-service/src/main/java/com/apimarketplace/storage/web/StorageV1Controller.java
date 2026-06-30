package com.apimarketplace.storage.web;

import com.apimarketplace.storage.domain.StoredFile;
import com.apimarketplace.storage.service.StorageService;
import com.apimarketplace.storage.web.dto.DocumentRequest;
import com.apimarketplace.storage.web.dto.DocumentResponse;
import com.apimarketplace.storage.web.dto.RagUpsertRequest;
import com.apimarketplace.storage.web.dto.RagUpsertResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Contrôleur v1 pour l'API Storage
 * Endpoints publics via Gateway avec authentification JWT
 */
@RestController
@RequestMapping("/storage/v1")
@CrossOrigin(origins = "*")
public class StorageV1Controller {

    private static final Logger logger = LoggerFactory.getLogger(StorageV1Controller.class);

    @Autowired
    private StorageService storageService;

    /**
     * POST /storage/v1/documents - Creer un document
     */
    @PostMapping("/documents")
    public ResponseEntity<DocumentResponse> createDocument(
            @RequestBody DocumentRequest request,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        
        try {
            logger.info("Creation de document - collection={}, userId={}, orgId={}", 
                       request.getCollection(), userId, orgId);
            
            // Pour l'instant, nous simulons la creation d'un document
            // In production, you should implement document storage logic
            DocumentResponse response = DocumentResponse.builder()
                    .id("doc-" + System.currentTimeMillis())
                    .collection(request.getCollection())
                    .document(request.getDocument())
                    .createdAt(System.currentTimeMillis())
                    .updatedAt(System.currentTimeMillis())
                    .build();
            
            logger.info("Document cree avec l'ID: {}", response.getId());
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Erreur lors de la creation du document", e);
            return ResponseEntity.status(500).body(
                DocumentResponse.builder()
                    .error("Erreur lors de la creation du document: " + e.getMessage())
                    .build()
            );
        }
    }

    /**
     * GET /storage/v1/documents/{id} - Recuperer un document
     */
    @GetMapping("/documents/{id}")
    public ResponseEntity<DocumentResponse> getDocument(
            @PathVariable String id,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        
        try {
            logger.info("Recuperation du document - id={}, userId={}, orgId={}", id, userId, orgId);
            
            // Pour l'instant, nous simulons la recuperation d'un document
            // In production, you should query the database
            DocumentResponse response = DocumentResponse.builder()
                    .id(id)
                    .collection("default")
                    .document(Map.of("content", "Contenu simule du document " + id))
                    .createdAt(System.currentTimeMillis() - 86400000) // Il y a 1 jour
                    .updatedAt(System.currentTimeMillis() - 3600000)  // Il y a 1 heure
                    .build();
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Erreur lors de la recuperation du document {}", id, e);
            return ResponseEntity.status(500).body(
                DocumentResponse.builder()
                    .error("Erreur lors de la recuperation du document: " + e.getMessage())
                    .build()
            );
        }
    }

    /**
     * POST /storage/v1/rag/upsert - Indexer un document pour RAG
     */
    @PostMapping("/rag/upsert")
    public ResponseEntity<RagUpsertResponse> upsertRagDocument(
            @RequestBody RagUpsertRequest request,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String orgId) {
        
        try {
            logger.info("Indexation RAG - id={}, userId={}, orgId={}", 
                       request.getId(), userId, orgId);
            
            // Pour l'instant, nous simulons l'indexation
            // In production, you should use pgvector or another vector system
            RagUpsertResponse response = RagUpsertResponse.builder()
                    .id(request.getId())
                    .status("indexed")
                    .vectorCount(1)
                    .indexedAt(System.currentTimeMillis())
                    .build();
            
            logger.info("Document RAG indexe avec l'ID: {}", response.getId());
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Erreur lors de l'indexation RAG", e);
            return ResponseEntity.status(500).body(
                RagUpsertResponse.builder()
                    .id(request.getId())
                    .status("error")
                    .error("Erreur lors de l'indexation: " + e.getMessage())
                    .build()
            );
        }
    }

    /**
     * GET /storage/v1/health - Health check
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "storage-v1",
            "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * POST /storage/v1/upload - Upload de fichier (compatible avec l'API existante)
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "X-User-ID", required = false) String userId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestParam(value = "description", required = false) String description) {

        try {
            if (userId == null) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "X-User-Id header is required"
                ));
            }
            if (organizationId == null || organizationId.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "X-Organization-ID header is required (post-V261)"
                ));
            }

            Long userIdLong = Long.parseLong(userId);
            StoredFile storedFile = storageService.storeFile(file, userIdLong, organizationId.trim(), description);
            
            Map<String, Object> response = new HashMap<>();
            response.put("id", storedFile.getId());
            response.put("filename", storedFile.getOriginalName());
            response.put("size", storedFile.getFileSize());
            response.put("contentType", storedFile.getContentType());
            response.put("uploadedAt", storedFile.getCreatedAt());
            
            return ResponseEntity.ok(response);
            
        } catch (IOException e) {
            logger.error("Erreur lors de l'upload du fichier", e);
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Erreur lors de l'upload: " + e.getMessage()
            ));
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "X-User-Id doit etre un nombre valide"
            ));
        }
    }
}
