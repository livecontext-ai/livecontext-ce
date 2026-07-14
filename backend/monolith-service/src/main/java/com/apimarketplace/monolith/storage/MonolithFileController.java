package com.apimarketplace.monolith.storage;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.exception.QuotaExceededException;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.common.storage.url.PublicFileUrlBuilder;
import com.apimarketplace.common.web.ContentDispositions;
import com.apimarketplace.common.web.TenantResolver;
import com.apimarketplace.storage.domain.FileRef;
import com.apimarketplace.storage.service.file.FileStorageService;
import com.apimarketplace.storage.util.FileConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CE-monolith bridge for the user-facing file API.
 *
 * <p>{@code storage.web.FileController} (which owns {@code POST /api/files/generic-upload} and the
 * opaque {@code GET /api/files/by-id/{id}/raw} serve in the cloud) is
 * {@code @ConditionalOnProperty(deployment.mode=microservice)}, so it is NOT mounted in the
 * monolith. {@code orchestrator FileDownloadController} only covers {@code storage.type=local}
 * (the CE slot runs {@code storage.type=s3}). Without this bridge, every CE flow that goes through
 * {@code fileService.uploadGeneric} (Files page, chat attachments, avatars, DataTable files) 404s
 * on upload, and the Files detail view 404s when it fetches content via {@code by-id/{id}/raw}.
 *
 * <p>This bridge mounts ONLY those two endpoints (the signed-proxy / delete paths stay cloud-only
 * by design) and delegates to the storage {@link FileStorageService} bean the monolith already
 * wires (see {@code MonolithFileStorageServiceAdapter}). It reads the gateway/embedded-auth headers
 * directly - the same {@code X-User-ID}/{@code X-Organization-ID} headers {@code StorageExplorerController}
 * reads and the CE org-context filter injects - mirroring {@code FileController}'s org-scope logic
 * (the {@code OrgScopedEntityListener} stamps {@code organization_id} during the indexing persist
 * inside {@code uploadGeneric}; the raw serve resolves the row org-scoped, then by owner fast-path).
 * The serve returns buffered bytes (no streaming) which is sufficient for the user-facing file sizes.
 */
@RestController
@RequestMapping("/api/files")
@ConditionalOnProperty(name = "deployment.mode", havingValue = "monolith")
public class MonolithFileController {

    private static final Logger logger = LoggerFactory.getLogger(MonolithFileController.class);
    private static final String FILE_RESOURCE_TYPE = "file";

    private final FileStorageService fileStorageService;
    private final PublicFileUrlBuilder publicFileUrlBuilder;
    private final StorageService storageService;
    private final OrgAccessGuard orgAccessGuard;

    public MonolithFileController(FileStorageService fileStorageService,
                                  PublicFileUrlBuilder publicFileUrlBuilder,
                                  StorageService storageService,
                                  OrgAccessGuard orgAccessGuard) {
        this.fileStorageService = fileStorageService;
        this.publicFileUrlBuilder = publicFileUrlBuilder;
        this.storageService = storageService;
        this.orgAccessGuard = orgAccessGuard;
    }

    /**
     * Generic upload without workflow context - the CE counterpart of
     * {@code FileController.genericUpload}. Same request shape (multipart {@code file} +
     * optional {@code category}) and same response shape so every cloud caller works unchanged.
     */
    @PostMapping(value = "/generic-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> genericUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "category", defaultValue = "general") String category,
            @RequestParam(value = "parentFolderId", required = false) String parentFolderIdRaw,
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {

        logger.info("CE generic upload: name={}, size={}, category={}, tenant={}, org={}",
                file.getOriginalFilename(), file.getSize(), category, tenantId, organizationId);

        if (file.getSize() > FileConstants.MAX_FILE_SIZE_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("error", "File too large. Maximum size: " + FileConstants.MAX_FILE_SIZE_MB + " MB"));
        }
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
        }

        try {
            String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : FileConstants.DEFAULT_FILENAME;
            String mimeType = file.getContentType() != null ? file.getContentType() : FileConstants.DEFAULT_MIME_TYPE;
            var inputStream = file.getInputStream();
            long size = file.getSize();

            // V313 folder-aware upload: place the file in the caller's current manual folder
            // when a parentFolderId is supplied (indexer validates + drops to root otherwise).
            final UUID parentFolderId = parseFolderId(parentFolderIdRaw);

            AtomicReference<FileRef> result = new AtomicReference<>();
            // Stamp the persisted row with the caller's active workspace, exactly like
            // FileController.genericUpload (uploadGeneric indexes the row server-side).
            TenantResolver.runWithOrgScope(organizationId, orgRole, () ->
                    result.set(parentFolderId == null
                            ? fileStorageService.uploadGeneric(tenantId, category, fileName, mimeType, inputStream, size)
                            : fileStorageService.uploadGeneric(tenantId, category, fileName, mimeType, inputStream, size, parentFolderId)));
            FileRef fileRef = result.get();

            String storageId = fileRef == null ? null : fileRef.id();
            if (storageId == null) {
                logger.error("CE generic upload produced no storage id (name={}, tenant={}, category={})",
                        fileName, tenantId, category);
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Upload succeeded but the file could not be indexed"));
            }

            return ResponseEntity.ok(Map.of(
                    "url", publicFileUrlBuilder.fileUrl(storageId, true),
                    "id", storageId,
                    "storageKey", fileRef.path(),
                    "fileName", fileRef.name(),
                    "mimeType", fileRef.mimeType(),
                    "size", fileRef.size()
            ));
        } catch (IOException e) {
            logger.error("CE generic upload failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Upload failed: " + e.getMessage()));
        } catch (QuotaExceededException e) {
            logger.warn("CE generic upload refused by storage quota: tenant={}, category={}, error={}",
                    tenantId, category, e.getMessage());
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of("error", "Storage quota exceeded"));
        }
    }

    /**
     * Opaque, org-scoped file serve by storage-row UUID - the CE counterpart of
     * {@code FileController.rawById}. Resolves the {@code storage.storage} row within the caller's
     * active org, or by an owner fast-path (so a browser request that can't carry the active-org
     * header still serves the owner's file). 404 (never 403) on miss. Returns buffered bytes.
     */
    @GetMapping("/by-id/{id}/raw")
    public ResponseEntity<byte[]> rawById(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "inline") String disposition,
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {

        // Org-scoped first (any workspace member), then the OWNER fast-path (the uploader can serve
        // their OWN file regardless of the active workspace) - mirrors FileController.rawById.
        StorageEntity entity = null;
        if (organizationId != null && !organizationId.isBlank()) {
            entity = storageService.getEntityByIdForScope(id, tenantId, organizationId).orElse(null);
        }
        if (entity == null) {
            entity = storageService.getEntityById(id, tenantId).orElse(null);
        }
        if (entity == null || entity.getFileName() == null) {
            return ResponseEntity.notFound().build();
        }
        if (!canAccessFile(entity, id, tenantId, organizationId, orgRole)) {
            return ResponseEntity.notFound().build();
        }

        String fileName = entity.getFileName();
        String mimeType = entity.getMimeType() != null ? entity.getMimeType()
                : (entity.getContentType() != null ? entity.getContentType() : "application/octet-stream");
        String contentDisposition = ContentDispositions.of(
                "attachment".equalsIgnoreCase(disposition) ? "attachment" : "inline", fileName);

        byte[] data;
        if (entity.getS3Key() != null) {
            data = fileStorageService.download(entity.getS3Key()).orElse(null);
        } else {
            data = inlineBytes(entity);
        }
        if (data == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition)
                .header(HttpHeaders.CONTENT_TYPE, mimeType)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .body(data);
    }

    /**
     * Anonymous avatar serve - CE counterpart of {@code FileController.avatarById}.
     * Agent avatars must render for viewers who are NOT the uploader (marketplace cards,
     * shared applications, widget embeds) where a plain {@code <img>} carries no token.
     * Only rows eligible per {@code StorageService.getPublicAvatarEntity} resolve
     * (generic {@code avatar} category + image mime); everything else 404s. SVGs get a
     * no-script CSP + nosniff since they are user/AI supplied markup on the app origin.
     */
    @GetMapping("/avatar/{id}")
    public ResponseEntity<byte[]> avatarById(@PathVariable UUID id) {
        StorageEntity entity = storageService.getPublicAvatarEntity(id).orElse(null);
        // Eligibility guarantees a non-null s3 key (the rule is key-anchored),
        // so there is no inline-bytes branch here.
        if (entity == null || entity.getFileName() == null || entity.getS3Key() == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] data = fileStorageService.download(entity.getS3Key()).orElse(null);
        if (data == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositions.of("inline", entity.getFileName()))
                .header(HttpHeaders.CONTENT_TYPE, entity.getMimeType())
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'")
                .body(data);
    }

    /** Parse the optional {@code parentFolderId} upload field; null/blank/malformed → null (root).
     *  A non-folder/cross-org id is dropped to root by the indexer, not here. */
    private static UUID parseFolderId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Inline content stored in the row (no object-storage key) - BINARY bytes, or TEXT/JSON as UTF-8. */
    private static byte[] inlineBytes(StorageEntity entity) {
        byte[] data = entity.getDataBinary();
        if (data != null) {
            return data;
        }
        String textual = entity.getDataText();
        if (textual == null && entity.getData() != null) {
            textual = entity.getData();
        }
        return textual != null ? textual.getBytes(StandardCharsets.UTF_8) : null;
    }

    /** Org-access check on the file's own org, with role applied only when it matches the request org. */
    private boolean canAccessFile(StorageEntity entity, UUID id, String tenantId, String requestOrgId, String requestOrgRole) {
        String orgId = entity.getOrganizationId();
        if (orgId == null || orgId.isBlank()) {
            orgId = requestOrgId;
        }
        if (orgId == null || orgId.isBlank()) {
            return true;
        }
        String orgRole = orgId.equals(requestOrgId) ? requestOrgRole : null;
        return orgAccessGuard.canAccess(orgId, tenantId, FILE_RESOURCE_TYPE, id.toString(), orgRole);
    }
}
