package com.apimarketplace.orchestrator.controllers.storage;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.web.ContentDispositions;
import com.apimarketplace.common.storage.dto.StorageExplorerDto;
import com.apimarketplace.common.storage.dto.VirtualFolderAddress;
import com.apimarketplace.common.storage.service.StorageExplorerService;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * REST controller for the Storage Explorer feature.
 * Provides paginated, filtered listing and preview/download of storage entries.
 */
@RestController
@RequestMapping("/api/storage/explorer")
public class StorageExplorerController {

    private static final Logger logger = LoggerFactory.getLogger(StorageExplorerController.class);

    /** Hard cap on files per bulk-download ZIP request (abuse guard). */
    private static final int MAX_ZIP_ENTRIES = 200;
    private static final String FILE_RESOURCE_TYPE = "file";

    private final StorageExplorerService explorerService;
    private final StorageService storageService;
    private final OrgAccessGuard orgAccessGuard;
    private final WorkflowRepository workflowRepository;

    @Autowired(required = false)
    private FileStorageService fileStorageService;

    public StorageExplorerController(StorageExplorerService explorerService,
                                     StorageService storageService,
                                     OrgAccessGuard orgAccessGuard,
                                     WorkflowRepository workflowRepository) {
        this.explorerService = explorerService;
        this.storageService = storageService;
        this.orgAccessGuard = orgAccessGuard;
        this.workflowRepository = workflowRepository;
    }

    /**
     * Search storage entries with filtering and pagination.
     *
     * GET /api/storage/explorer?page=0&size=20&search=report&sourceType=STEP_OUTPUT&...
     */
    @GetMapping
    public ResponseEntity<Page<StorageExplorerDto>> search(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String storageType,
            @RequestParam(required = false) String workflowId,
            @RequestParam(required = false) String runId,
            @RequestParam(required = false) Instant dateFrom,
            @RequestParam(required = false) Instant dateTo,
            @RequestParam(required = false) String fileType,
            @RequestParam(defaultValue = "false") boolean filesOnly,
            @RequestParam(defaultValue = "false") boolean s3Only,
            @RequestParam(required = false) String parentFolderId,
            @RequestParam(defaultValue = "false") boolean virtualWorkflowFolders) {

        logger.debug("Storage explorer search: tenantId={}, orgId={}, page={}, size={}, search={}, fileType={}, filesOnly={}, s3Only={}, parentFolderId={}, virtualWorkflowFolders={}",
            tenantId, organizationId, page, size, search, fileType, filesOnly, s3Only, parentFolderId, virtualWorkflowFolders);

        Pageable pageable = PageRequest.of(page, Math.min(size, 100));

        // Phase 2b virtual workflow folder tree: the Files browser opts in with
        // virtualWorkflowFolders=true. The parentFolderId then carries a virtual address
        // ("root", or "wf:<id>[/e<n>[/s<n>[/i<n>]]]"). A bare folder UUID is still a manual
        // folder and falls through to the V313 branch below. (Manual folders and virtual
        // folders coexist: the root mixes both.)
        if (virtualWorkflowFolders) {
            VirtualFolderAddress address = VirtualFolderAddress.parse(parentFolderId);
            if (address != null || isRootFolderToken(parentFolderId)) {
                Page<StorageExplorerDto> virtualResults = explorerService.searchVirtualScope(
                    tenantId, organizationId, address, search, sourceType, storageType,
                    filesOnly, s3Only, fileType, dateFrom, dateTo,
                    restrictedFileIds(organizationId, tenantId, orgRole), pageable);
                return ResponseEntity.ok(resolveWorkflowNames(virtualResults, pageable));
            }
            // Not a virtual token (e.g. a manual folder UUID) → fall through to the V313 branch.
        }

        // V313 folder-aware mode: the Files browser opts in by sending parentFolderId
        // ("root" = top level, or a folder UUID = that folder's children). When the param is
        // absent the legacy flat listing is kept (side-panel explorer, other surfaces).
        if (parentFolderId != null) {
            UUID parentId = resolveParentFolderId(parentFolderId);
            // A non-null, non-"root" value that isn't a valid same-org folder → empty page
            // (mirrors how the explorer already degrades on bad input).
            if (parentId == null && !isRootFolderToken(parentFolderId)) {
                return ResponseEntity.ok(Page.empty(pageable));
            }
            if (parentId != null
                    && storageService.getEntityByIdForScope(parentId, tenantId, organizationId)
                        .filter(StorageEntity::isFolder).isEmpty()) {
                return ResponseEntity.ok(Page.empty(pageable));
            }
            Page<StorageExplorerDto> folderResults = explorerService.searchFolderScope(
                tenantId, organizationId, parentId, search, sourceType, storageType, workflowId, runId,
                dateFrom, dateTo, filesOnly, s3Only, fileType,
                restrictedFileIds(organizationId, tenantId, orgRole), pageable
            );
            return ResponseEntity.ok(folderResults);
        }

        Page<StorageExplorerDto> results = explorerService.search(
            tenantId, organizationId, search, sourceType, storageType, workflowId, runId,
            dateFrom, dateTo, filesOnly, s3Only, fileType, restrictedFileIds(organizationId, tenantId, orgRole), pageable
        );

        return ResponseEntity.ok(results);
    }

    /**
     * Phase 2b - fill in the display name of WORKFLOW-kind virtual folders on the page.
     *
     * <p>The storage boundary cannot query the workflows table, so virtual workflow folders come back
     * with {@code workflowName == null}. Here we collect their {@code workflowId} strings, batch-fetch
     * the names from {@link WorkflowRepository#findIdNamePairs}, and rebuild each WORKFLOW DTO with the
     * resolved name. Non-WORKFLOW DTOs (epoch/spawn/iteration folders, files) are passed through
     * untouched.</p>
     *
     * <p>A WORKFLOW folder whose id does NOT resolve is an orphan: its workflow was deleted, but the
     * storage rows survive a workflow delete, so they keep grouping into a (now nameless) folder. We
     * {@code OMIT} such folders rather than render them blank. The orphan rows are removed wholesale by
     * the storage-cleanup-on-workflow-delete path and the one-off purge; this read-side filter is the
     * safety net for any orphan that still exists. The page total is left as-is for stable paging - in
     * the steady state (post-purge) nothing is orphaned, so nothing is omitted and the total is exact.</p>
     */
    private Page<StorageExplorerDto> resolveWorkflowNames(Page<StorageExplorerDto> page, Pageable pageable) {
        // Collect the workflow ids of the WORKFLOW-kind folders that need a name.
        Set<UUID> ids = new HashSet<>();
        for (StorageExplorerDto dto : page.getContent()) {
            if ("WORKFLOW".equals(dto.virtualKind()) && dto.workflowId() != null) {
                parseUuid(dto.workflowId()).ifPresent(ids::add);
            }
        }
        if (ids.isEmpty()) {
            return page;
        }

        Map<UUID, String> names = new HashMap<>();
        for (Object[] pair : workflowRepository.findIdNamePairs(ids)) {
            names.put((UUID) pair[0], (String) pair[1]);
        }

        List<StorageExplorerDto> resolved = new ArrayList<>(page.getContent().size());
        for (StorageExplorerDto dto : page.getContent()) {
            if ("WORKFLOW".equals(dto.virtualKind()) && dto.workflowId() != null) {
                String name = parseUuid(dto.workflowId()).map(names::get).orElse(null);
                if (name == null) {
                    // Orphan (workflow deleted) - omit instead of showing a nameless folder.
                    continue;
                }
                resolved.add(withWorkflowName(dto, name));
            } else {
                resolved.add(dto);
            }
        }
        return new org.springframework.data.domain.PageImpl<>(resolved, pageable, page.getTotalElements());
    }

    /** Rebuild a virtual-folder DTO with a resolved workflow name (records are immutable). */
    private static StorageExplorerDto withWorkflowName(StorageExplorerDto d, String workflowName) {
        return new StorageExplorerDto(
            d.id(), d.storageType(), d.sourceType(), d.fileName(), d.mimeType(), d.sizeBytes(),
            d.formattedSize(), d.createdAt(), d.workflowId(), workflowName, d.projectId(), d.runId(),
            d.stepKey(), d.epoch(), d.s3Key(), d.contentType(), d.isFolder(), d.parentFolderId(),
            d.childCount(), d.previewFiles(), d.virtualId(), d.virtualKind(), d.spawn(), d.itemIndex());
    }

    /** {@code true} when the value is the root sentinel (top-level listing), case-insensitive. */
    private static boolean isRootFolderToken(String value) {
        return value == null || value.isBlank() || "root".equalsIgnoreCase(value.trim());
    }

    /**
     * Resolve the {@code parentFolderId} request param to a folder UUID, or null for the root
     * sentinel ("root"/blank). A malformed (non-UUID, non-"root") value also yields null - the
     * caller treats that as "bad input → empty page".
     */
    private static UUID resolveParentFolderId(String value) {
        if (isRootFolderToken(value)) {
            return null;
        }
        return parseUuid(value.trim()).orElse(null);
    }

    /**
     * Create a manual folder (V313). Body {@code {"name": string, "parentFolderId": string|null}}.
     * Org+tenant scoped; {@code parentFolderId} (when present) must be a same-org folder.
     */
    @PostMapping("/folders")
    public ResponseEntity<Map<String, Object>> createFolder(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestBody Map<String, Object> body) {

        Object nameRaw = body.get("name");
        String name = nameRaw == null ? "" : nameRaw.toString().trim();
        if (name.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "name is required"));
        }

        UUID parentId;
        try {
            parentId = parseNullableUuid(body.get("parentFolderId"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "parentFolderId is not a valid id"));
        }

        StorageEntity folder;
        try {
            folder = storageService.createFolderForScope(tenantId, organizationId, name, parentId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        Map<String, Object> response = new HashMap<>();
        response.put("id", folder.getId().toString());
        response.put("name", folder.getFileName());
        response.put("isFolder", true);
        response.put("parentFolderId", folder.getParentFolderId() != null
                ? folder.getParentFolderId().toString() : null);
        return ResponseEntity.ok(response);
    }

    /**
     * Every manual folder in the org (flat list) - feeds the Files "Move to…" tree picker, which
     * builds the folder arborescence client-side from {@code parentFolderId}. Returns
     * {@code [{"id", "name", "parentFolderId"}, ...]}, ordered by name. Member-restricted folders are
     * excluded (same deny-list the listing uses) so a restricted member can't target a folder they
     * can't see. (Coexists with the POST {@code /folders} create endpoint on the same path.)
     */
    @GetMapping("/folders")
    public ResponseEntity<List<Map<String, Object>>> listFolders(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {

        List<StorageExplorerDto> folders = explorerService.listAllManualFolders(
                tenantId, organizationId, restrictedFileIds(organizationId, tenantId, orgRole));

        List<Map<String, Object>> response = new ArrayList<>(folders.size());
        for (StorageExplorerDto f : folders) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", f.id() != null ? f.id().toString() : null);
            entry.put("name", f.fileName());
            entry.put("parentFolderId", f.parentFolderId());
            response.add(entry);
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Move storage rows into a folder (V313). Body
     * {@code {"ids": [string...], "parentFolderId": string|null}} - null target = move to root.
     * Returns {@code {"movedCount": N, "failed": [{"id", "reason"}...]}}.
     */
    @PostMapping("/move")
    public ResponseEntity<Map<String, Object>> moveEntries(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestBody Map<String, Object> body) {

        Object idsRaw = body.get("ids");
        if (!(idsRaw instanceof List<?> idList) || idList.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ids is required"));
        }

        UUID targetParentId;
        try {
            targetParentId = parseNullableUuid(body.get("parentFolderId"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "parentFolderId is not a valid id"));
        }

        // Resolve the requested ids; reject those the member may not WRITE before reaching the
        // service (read-only / DENY restriction is a per-id failure, not a 403 on the whole batch).
        List<UUID> writable = new ArrayList<>();
        List<Map<String, Object>> failed = new ArrayList<>();
        for (Object o : idList) {
            Optional<UUID> parsed = parseUuid(o == null ? null : o.toString());
            if (parsed.isEmpty()) {
                failed.add(Map.of("id", String.valueOf(o), "reason", "not a valid id"));
                continue;
            }
            UUID id = parsed.get();
            if (!canWriteFile(organizationId, tenantId, orgRole, id)) {
                failed.add(Map.of("id", id.toString(), "reason", "restricted"));
                continue;
            }
            writable.add(id);
        }

        StorageService.MoveResult result;
        try {
            result = storageService.moveEntriesForScope(organizationId, writable, targetParentId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }

        for (StorageService.MoveFailure f : result.failed()) {
            failed.add(Map.of("id", f.id().toString(), "reason", f.reason()));
        }
        return ResponseEntity.ok(Map.of(
            "movedCount", result.movedCount(),
            "failed", failed
        ));
    }

    /** Parse a nullable id field from a request body. null/blank → null; bad value → throws. */
    private static UUID parseNullableUuid(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        return UUID.fromString(s);
    }

    /**
     * Preview a storage entry.
     * - JSON: returns structure skeleton
     * - TEXT: returns first 500 chars
     * - S3_FILE: returns presigned URL
     * - BINARY: returns metadata only
     */
    @GetMapping("/{id}/preview")
    public ResponseEntity<Map<String, Object>> preview(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @PathVariable UUID id) {

        Optional<StorageEntity> entityOpt = storageService.getEntityByIdForScope(id, tenantId, organizationId);
        if (entityOpt.isEmpty() || !canAccessFile(organizationId, tenantId, orgRole, id)) {
            return ResponseEntity.notFound().build();
        }

        StorageEntity entity = entityOpt.get();
        Map<String, Object> preview = new HashMap<>();
        preview.put("id", entity.getId());
        preview.put("storageType", entity.getStorageType());
        preview.put("sourceType", entity.getSourceType());
        preview.put("sizeBytes", entity.getSizeBytes());

        switch (entity.getStorageType()) {
            case "JSON" -> {
                String skeleton = entity.getStructureSkeleton();
                preview.put("skeleton", skeleton);
            }
            case "TEXT" -> {
                String text = entity.getDataText();
                if (text != null && text.length() > 500) {
                    text = text.substring(0, 500) + "...";
                }
                preview.put("text", text);
            }
            case "S3_FILE" -> {
                // handled below in common block
            }
            case "BINARY" -> {
                // handled below in common block
            }
            default -> {}
        }

        // Always include file metadata if present (covers S3_FILE, BINARY,
        // and legacy entries where storageType=JSON but sourceType=S3_FILE)
        if (entity.getFileName() != null) {
            preview.put("fileName", entity.getFileName());
        }
        if (entity.getMimeType() != null) {
            preview.put("mimeType", entity.getMimeType());
        }
        if (entity.getS3Key() != null) {
            preview.put("s3Key", entity.getS3Key());
            if (fileStorageService != null) {
                // Presign can fail (object missing, object storage unreachable, or the
                // internal presign rejecting on a null/mismatched tenant) → it must NOT
                // 500 the whole preview. Degrade gracefully: the response still carries
                // s3Key + metadata, and the UI renders/downloads via the token'd file
                // proxy, not this downloadUrl. (Mirrors FilesToolsProvider.view.)
                try {
                    String url = fileStorageService.generateDownloadUrl(entity.getS3Key());
                    preview.put("downloadUrl", url);
                } catch (Exception ex) {
                    logger.warn("preview: download URL generation failed for id={}: {}",
                            entity.getId(), ex.getMessage());
                }
            }
        }

        return ResponseEntity.ok(preview);
    }

    /**
     * Download a storage entry.
     * - S3_FILE: redirect to presigned URL
     * - BINARY: stream from DB
     * - JSON/TEXT: return raw content
     */
    @GetMapping("/{id}/download")
    public ResponseEntity<?> download(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @PathVariable UUID id) {

        Optional<StorageEntity> entityOpt = storageService.getEntityByIdForScope(id, tenantId, organizationId);
        if (entityOpt.isEmpty() || !canAccessFile(organizationId, tenantId, orgRole, id)) {
            return ResponseEntity.notFound().build();
        }

        StorageEntity entity = entityOpt.get();

        return switch (entity.getStorageType()) {
            case "S3_FILE" -> {
                if (fileStorageService != null && entity.getS3Key() != null) {
                    String url = fileStorageService.generateDownloadUrl(entity.getS3Key());
                    yield ResponseEntity.status(302)
                        .header(HttpHeaders.LOCATION, url)
                        .build();
                }
                yield ResponseEntity.notFound().build();
            }
            case "BINARY" -> {
                byte[] data = entity.getDataBinary();
                if (data == null) {
                    yield ResponseEntity.notFound().build();
                }
                String contentType = entity.getMimeType() != null ? entity.getMimeType() : "application/octet-stream";
                yield ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDispositions.attachment(entity.getFileName() != null ? entity.getFileName() : "download"))
                    .body(data);
            }
            case "JSON" -> ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(entity.getData());
            case "TEXT" -> ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(entity.getDataText());
            default -> ResponseEntity.notFound().build();
        };
    }

    /**
     * Bulk download: stream the selected storage entries (by id) as a single ZIP.
     *
     * <p>Each id is resolved org-scoped via {@link StorageService#getEntityByIdForScope}
     * (same gate as preview/download) - cross-org, unknown, or non-file ids are
     * silently skipped. S3 files are fetched by key; inline BINARY/TEXT/JSON come
     * straight from the row (mirrors the per-type handling of {@code download}).
     * The ZIP is streamed to the client ({@link StreamingResponseBody}); only one
     * entry's bytes are held in memory at a time. This replaces the old per-file
     * loop, which the browser blocked after the first programmatic download - a
     * single .zip is one download.
     */
    @PostMapping("/download-zip")
    public ResponseEntity<StreamingResponseBody> downloadZip(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestBody Map<String, Object> request) {

        Object idsRaw = request.get("ids");
        if (!(idsRaw instanceof List<?> idList) || idList.isEmpty() || idList.size() > MAX_ZIP_ENTRIES) {
            return ResponseEntity.badRequest().build();
        }

        // Resolve + org-scope every id up front so the response stream can't 404
        // mid-zip, and so we know the names. Skip cross-org / unknown / non-file.
        List<StorageEntity> entities = new ArrayList<>();
        for (Object o : idList) {
            if (o == null) continue;
            UUID id;
            try {
                id = UUID.fromString(o.toString());
            } catch (IllegalArgumentException ignore) {
                continue;
            }
            storageService.getEntityByIdForScope(id, tenantId, organizationId)
                .filter(e -> canAccessFile(organizationId, tenantId, orgRole, e.getId()))
                .filter(e -> e.getFileName() != null)
                .ifPresent(entities::add);
        }
        if (entities.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        StreamingResponseBody stream = out -> {
            Set<String> usedNames = new HashSet<>();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                for (StorageEntity entity : entities) {
                    byte[] bytes = zipContentBytes(entity, tenantId);
                    if (bytes == null) {
                        // Unfetchable (e.g. object storage unreachable) - skip this
                        // entry rather than aborting the whole archive.
                        continue;
                    }
                    zip.putNextEntry(new ZipEntry(uniqueEntryName(usedNames, entity.getFileName())));
                    zip.write(bytes);
                    zip.closeEntry();
                }
            }
        };

        String zipName = "files-" + LocalDate.now(ZoneOffset.UTC) + ".zip";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositions.attachment(zipName))
            .contentType(MediaType.parseMediaType("application/zip"))
            .body(stream);
    }

    /** Bytes for one storage entry, by type (mirrors {@code download}). null = unfetchable. */
    private byte[] zipContentBytes(StorageEntity entity, String tenantId) {
        return switch (entity.getStorageType()) {
            case "S3_FILE" -> {
                if (fileStorageService == null || entity.getS3Key() == null) yield null;
                try {
                    // Pass the tenant (X-User-ID) so the internal storage download authorizes
                    // (key is namespaced by tenant; isKeyOwnedByTenant checks key prefix). The
                    // 1-arg download() sent tenant=null → 403 → S3 files came out empty/missing
                    // in the zip. Org was already enforced at resolve (getEntityByIdForScope).
                    yield fileStorageService.download(tenantId, entity.getS3Key()).orElse(null);
                } catch (Exception ex) {
                    logger.warn("download-zip: failed to fetch S3 object for id={}: {}", entity.getId(), ex.getMessage());
                    yield null;
                }
            }
            case "BINARY" -> entity.getDataBinary();
            case "JSON" -> entity.getData() != null ? entity.getData().getBytes(StandardCharsets.UTF_8) : null;
            case "TEXT" -> entity.getDataText() != null ? entity.getDataText().getBytes(StandardCharsets.UTF_8) : null;
            default -> null;
        };
    }

    /** Dedup duplicate filenames inside the ZIP: "a.pdf", "a (1).pdf", "a (2).pdf"… */
    private static String uniqueEntryName(Set<String> used, String fileName) {
        String base = (fileName == null || fileName.isBlank()) ? "file" : fileName;
        if (used.add(base)) return base;
        int dot = base.lastIndexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String ext = dot > 0 ? base.substring(dot) : "";
        for (int i = 1; ; i++) {
            String candidate = stem + " (" + i + ")" + ext;
            if (used.add(candidate)) return candidate;
        }
    }

    /**
     * Get aggregate stats for the active workspace.
     */
    @GetMapping("/stats")
    public ResponseEntity<List<Map<String, Object>>> getStats(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole) {

        List<Map<String, Object>> stats = explorerService.getStats(
                tenantId, organizationId, restrictedFileIds(organizationId, tenantId, orgRole));
        return ResponseEntity.ok(stats);
    }

    /**
     * Delete a single storage entry by ID - strict-isolation scoped.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteEntry(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @PathVariable UUID id) {

        if (!canWriteFile(organizationId, tenantId, orgRole, id)) {
            return ResponseEntity.notFound().build();
        }

        boolean deleted = storageService.deleteByIdForScope(id, tenantId, organizationId);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of("deleted", true, "id", id.toString()));
    }

    /**
     * Rename a single storage entry's display name - strict-isolation scoped.
     * Only {@code file_name} changes; the s3 object key is left untouched.
     */
    @PutMapping("/{id}/rename")
    public ResponseEntity<Map<String, Object>> renameEntry(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @PathVariable UUID id,
            @RequestBody Map<String, String> body) {

        if (!canWriteFile(organizationId, tenantId, orgRole, id)) {
            return ResponseEntity.notFound().build();
        }

        String raw = body.get("fileName");
        String fileName = raw == null ? "" : raw.trim();
        if (fileName.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "fileName is required"));
        }
        if (fileName.length() > 255) {
            fileName = fileName.substring(0, 255);
        }

        boolean renamed = storageService.renameByIdForScope(id, tenantId, organizationId, fileName);
        if (!renamed) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of("id", id.toString(), "fileName", fileName));
    }

    /**
     * Delete all S3 files within a date range - strict-isolation scoped.
     */
    @PostMapping("/batch-delete-by-date")
    public ResponseEntity<Map<String, Object>> deleteByDateRange(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestBody Map<String, String> body) {

        String dateFromStr = body.get("dateFrom");
        String dateToStr = body.get("dateTo");
        if (dateFromStr == null || dateToStr == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "dateFrom and dateTo are required"));
        }
        Instant dateFrom = Instant.parse(dateFromStr);
        Instant dateTo = Instant.parse(dateToStr);
        int deletedCount = storageService.deleteByDateRangeForScope(
                tenantId, organizationId, dateFrom, dateTo, writeRestrictedFileIds(organizationId, tenantId, orgRole));
        return ResponseEntity.ok(Map.of("deletedCount", deletedCount));
    }

    @PostMapping("/batch-delete")
    public ResponseEntity<Map<String, Object>> deleteEntries(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestBody Map<String, List<String>> body) {

        List<String> ids = body.getOrDefault("ids", List.of());
        int deletedCount = 0;

        for (String idStr : ids) {
            try {
                UUID uuid = UUID.fromString(idStr);
                if (canWriteFile(organizationId, tenantId, orgRole, uuid)
                        && storageService.deleteByIdForScope(uuid, tenantId, organizationId)) {
                    deletedCount++;
                }
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid UUID in batch delete: {}", idStr);
            }
        }

        return ResponseEntity.ok(Map.of(
            "deletedCount", deletedCount,
            "requestedCount", ids.size()
        ));
    }

    /**
     * Resolve display names for a batch of storage entry IDs in ONE request.
     *
     * Returns a {@code {id -> fileName}} map for the accessible, named entries only
     * (inaccessible, missing, name-less, or malformed IDs are silently omitted - the
     * caller falls back to an ID-slice label). This replaces N per-file
     * {@code GET /{id}/preview} round-trips (each of which streams structure/text/
     * presigned-URL payloads) when a surface only needs the names - e.g. the Agent
     * Fleet canvas resolving labels for files attached to agents. Reuses the same
     * per-entry scoped read + ACL check as preview, so it adds no new access path.
     *
     * POST /api/storage/explorer/names   body: {"ids": ["uuid", ...]}
     */
    @PostMapping("/names")
    public ResponseEntity<Map<String, String>> namesByIds(
            @RequestHeader("X-User-ID") String tenantId,
            @RequestHeader(value = "X-Organization-ID", required = false) String organizationId,
            @RequestHeader(value = "X-Organization-Role", required = false) String orgRole,
            @RequestBody Map<String, List<String>> body) {

        List<String> ids = body.getOrDefault("ids", List.of());
        Map<String, String> names = new HashMap<>();
        // Abuse guard: cap the batch (mirrors MAX_ZIP_ENTRIES). Extra IDs are ignored,
        // not an error - the caller resolves the remainder lazily / by ID-slice.
        int limit = Math.min(ids.size(), MAX_ZIP_ENTRIES);
        for (int i = 0; i < limit; i++) {
            String idStr = ids.get(i);
            try {
                UUID uuid = UUID.fromString(idStr);
                if (!canAccessFile(organizationId, tenantId, orgRole, uuid)) {
                    continue;
                }
                storageService.getEntityByIdForScope(uuid, tenantId, organizationId)
                        .map(StorageEntity::getFileName)
                        .filter(n -> n != null && !n.isBlank())
                        .ifPresent(n -> names.put(idStr, n));
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid UUID in names batch: {}", idStr);
            }
        }

        return ResponseEntity.ok(names);
    }

    private Set<UUID> restrictedFileIds(String organizationId, String userId, String orgRole) {
        if (orgAccessGuard == null || organizationId == null || organizationId.isBlank()) {
            return Set.of();
        }
        Set<String> restricted = orgAccessGuard.getRestrictedResourceIds(
                organizationId, userId, FILE_RESOURCE_TYPE, orgRole);
        if (restricted == null || restricted.isEmpty()) {
            return Set.of();
        }
        Set<UUID> ids = new HashSet<>();
        for (String resourceId : restricted) {
            parseUuid(resourceId).ifPresent(ids::add);
        }
        return ids;
    }

    private boolean canAccessFile(String organizationId, String userId, String orgRole, UUID id) {
        return orgAccessGuard == null
                || organizationId == null
                || organizationId.isBlank()
                || orgAccessGuard.canAccess(organizationId, userId, FILE_RESOURCE_TYPE, id.toString(), orgRole);
    }

    /** Files the member may not WRITE (delete) - DENY and READ-only both block. */
    private Set<UUID> writeRestrictedFileIds(String organizationId, String userId, String orgRole) {
        if (orgAccessGuard == null || organizationId == null || organizationId.isBlank()) {
            return Set.of();
        }
        Set<String> restricted = orgAccessGuard.getWriteRestrictedResourceIds(
                organizationId, userId, FILE_RESOURCE_TYPE, orgRole);
        if (restricted == null || restricted.isEmpty()) {
            return Set.of();
        }
        Set<UUID> ids = new HashSet<>();
        for (String resourceId : restricted) {
            parseUuid(resourceId).ifPresent(ids::add);
        }
        return ids;
    }

    /** A member may delete a file only with full write access (no DENY, no READ-only restriction). */
    private boolean canWriteFile(String organizationId, String userId, String orgRole, UUID id) {
        return orgAccessGuard == null
                || organizationId == null
                || organizationId.isBlank()
                || orgAccessGuard.canWrite(organizationId, userId, FILE_RESOURCE_TYPE, id.toString(), orgRole);
    }

    private static Optional<UUID> parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}
