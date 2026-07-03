package com.apimarketplace.orchestrator.controllers.storage;

import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.dto.StorageExplorerDto;
import com.apimarketplace.common.storage.service.StorageExplorerService;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("StorageExplorerController - preview")
class StorageExplorerControllerTest {

    private StorageExplorerService explorerService;
    private StorageService storageService;
    private FileStorageService fileStorageService;
    private OrgAccessGuard orgAccessGuard;
    private WorkflowRepository workflowRepository;
    private StorageExplorerController controller;

    @BeforeEach
    void setUp() {
        explorerService = mock(StorageExplorerService.class);
        storageService = mock(StorageService.class);
        fileStorageService = mock(FileStorageService.class);
        orgAccessGuard = mock(OrgAccessGuard.class);
        workflowRepository = mock(WorkflowRepository.class);
        lenient().when(orgAccessGuard.canAccess(any(), any(), eq("file"), any(), any())).thenReturn(true);
        lenient().when(orgAccessGuard.getRestrictedResourceIds(any(), any(), eq("file"), any())).thenReturn(Set.of());
        lenient().when(orgAccessGuard.canWrite(any(), any(), eq("file"), any(), any())).thenReturn(true);
        lenient().when(orgAccessGuard.getWriteRestrictedResourceIds(any(), any(), eq("file"), any())).thenReturn(Set.of());
        controller = new StorageExplorerController(explorerService, storageService, orgAccessGuard, workflowRepository);
        ReflectionTestUtils.setField(controller, "fileStorageService", fileStorageService);
    }

    private static StorageEntity s3File(UUID id) {
        StorageEntity e = new StorageEntity();
        e.setId(id);
        e.setStorageType("S3_FILE");
        e.setS3Key("1/wf/run/step/img.jpg");
        e.setFileName("img.jpg");
        e.setMimeType("image/jpeg");
        e.setSizeBytes(2048);
        // Owner tenant: the controller presigns/downloads under the KEY-OWNER tenant
        // (owner-aware presign), so the fixture must carry one for those calls to match.
        e.setTenantId("1");
        return e;
    }

    private static StorageEntity namedFile(UUID id, String name) {
        StorageEntity e = new StorageEntity();
        e.setId(id);
        e.setStorageType("S3_FILE");
        e.setFileName(name);
        return e;
    }

    @Test
    @DisplayName("names: resolves accessible named ids in one call; omits missing, name-less, and inaccessible ids")
    void namesByIdsResolvesAccessibleOnly() {
        UUID ok = UUID.randomUUID();
        UUID nameless = UUID.randomUUID();
        UUID blank = UUID.randomUUID();
        UUID denied = UUID.randomUUID();
        UUID missing = UUID.randomUUID();

        when(storageService.getEntityByIdForScope(eq(ok), any(), any())).thenReturn(Optional.of(namedFile(ok, "report.pdf")));
        when(storageService.getEntityByIdForScope(eq(nameless), any(), any())).thenReturn(Optional.of(namedFile(nameless, null)));
        when(storageService.getEntityByIdForScope(eq(blank), any(), any())).thenReturn(Optional.of(namedFile(blank, "   ")));
        when(storageService.getEntityByIdForScope(eq(denied), any(), any())).thenReturn(Optional.of(namedFile(denied, "secret.pdf")));
        when(storageService.getEntityByIdForScope(eq(missing), any(), any())).thenReturn(Optional.empty());
        // denied id fails the per-file ACL check; every other id is allowed by setUp default.
        when(orgAccessGuard.canAccess("org-1", "1", "file", denied.toString(), "MEMBER")).thenReturn(false);

        ResponseEntity<Map<String, String>> resp = controller.namesByIds("1", "org-1", "MEMBER",
                Map.of("ids", List.of(ok.toString(), nameless.toString(), blank.toString(), denied.toString(), missing.toString())));

        // Only the accessible, non-blank-named entry survives - name-less/blank/denied/missing are silently dropped.
        assertThat(resp.getBody()).hasSize(1).containsEntry(ok.toString(), "report.pdf");
        // ACL short-circuits BEFORE the storage read for the denied id - never touches the DB.
        verify(storageService, org.mockito.Mockito.never()).getEntityByIdForScope(eq(denied), any(), any());
    }

    @Test
    @DisplayName("names: malformed UUIDs are skipped, not fatal; empty/absent ids → empty map")
    void namesByIdsToleratesBadInput() {
        UUID ok = UUID.randomUUID();
        when(storageService.getEntityByIdForScope(eq(ok), any(), any())).thenReturn(Optional.of(namedFile(ok, "ok.txt")));

        ResponseEntity<Map<String, String>> resp = controller.namesByIds("1", "org-1", "MEMBER",
                Map.of("ids", List.of(ok.toString(), "not-a-uuid")));
        assertThat(resp.getBody()).hasSize(1).containsEntry(ok.toString(), "ok.txt");

        // No "ids" key at all → empty map, not a 4xx/5xx.
        assertThat(controller.namesByIds("1", "org-1", "MEMBER", Map.of()).getBody()).isEmpty();
    }

    @Test
    @DisplayName("names: caps the batch at 200 - extra ids are ignored, not read")
    void namesByIdsCapsBatch() {
        when(storageService.getEntityByIdForScope(any(), any(), any()))
                .thenReturn(Optional.of(namedFile(UUID.randomUUID(), "f.txt")));

        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            ids.add(UUID.randomUUID().toString());
        }
        controller.namesByIds("1", "org-1", "MEMBER", Map.of("ids", ids));

        // Only the first 200 (MAX_ZIP_ENTRIES) ids are read; the extra 50 are dropped.
        verify(storageService, org.mockito.Mockito.times(200)).getEntityByIdForScope(any(), any(), any());
    }

    @Test
    @DisplayName("degrades to 200 without downloadUrl when presign throws - never 500 (regression: preview 500 → 'Failed to load file')")
    void previewDegradesWhenPresignThrows() {
        UUID id = UUID.randomUUID();
        when(storageService.getEntityByIdForScope(eq(id), any(), any())).thenReturn(Optional.of(s3File(id)));
        when(fileStorageService.generateDownloadUrl(anyString(), anyString()))
                .thenThrow(new RuntimeException("403 on POST .../api/internal/storage/presign: not owned by tenantId='null'"));

        ResponseEntity<Map<String, Object>> resp = controller.preview("1", "org-1", "MEMBER", id);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody()).containsEntry("s3Key", "1/wf/run/step/img.jpg");
        assertThat(resp.getBody()).containsEntry("fileName", "img.jpg");
        assertThat(resp.getBody()).doesNotContainKey("downloadUrl");
    }

    @Test
    @DisplayName("includes downloadUrl when presign succeeds")
    void previewIncludesDownloadUrlWhenPresignSucceeds() {
        UUID id = UUID.randomUUID();
        when(storageService.getEntityByIdForScope(eq(id), any(), any())).thenReturn(Optional.of(s3File(id)));
        when(fileStorageService.generateDownloadUrl(anyString(), anyString())).thenReturn("https://minio/signed");

        ResponseEntity<Map<String, Object>> resp = controller.preview("1", "org-1", "MEMBER", id);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("downloadUrl", "https://minio/signed");
    }

    @Test
    @DisplayName("returns 404 for an unknown or cross-organization id")
    void previewReturnsNotFoundForUnknownId() {
        UUID id = UUID.randomUUID();
        when(storageService.getEntityByIdForScope(eq(id), any(), any())).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> resp = controller.preview("1", "org-1", "MEMBER", id);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("preview returns 404 when the file is restricted for the org member")
    void previewReturnsNotFoundWhenRestricted() {
        UUID id = UUID.randomUUID();
        when(storageService.getEntityByIdForScope(eq(id), any(), any())).thenReturn(Optional.of(s3File(id)));
        when(orgAccessGuard.canAccess("org-1", "1", "file", id.toString(), "MEMBER")).thenReturn(false);

        ResponseEntity<Map<String, Object>> resp = controller.preview("1", "org-1", "MEMBER", id);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    // ==================== search (filesOnly / s3Only) ====================

    @Test
    @DisplayName("search forwards filesOnly=true to the service - full-page Files browser lists real files only (file_name not null)")
    void searchForwardsFilesOnlyTrue() {
        when(explorerService.search(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(Page.empty());

        ResponseEntity<Page<com.apimarketplace.common.storage.dto.StorageExplorerDto>> resp =
                controller.search("1", "org-1", "MEMBER", 0, 50, null, null, null, null, null, null, null, null, true, false, null, false);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(explorerService).search(eq("1"), eq("org-1"), any(), any(), any(), any(), any(), any(), any(), eq(true), anyBoolean(), any(), any(), any());
    }

    @Test
    @DisplayName("search forwards filesOnly=false (the @RequestParam default) - side-panel explorer keeps its legacy all-rows behaviour")
    void searchForwardsFilesOnlyFalse() {
        when(explorerService.search(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(Page.empty());

        controller.search("1", "org-1", "MEMBER", 0, 20, null, null, null, null, null, null, null, null, false, false, null, false);

        verify(explorerService).search(eq("1"), eq("org-1"), any(), any(), any(), any(), any(), any(), any(), eq(false), anyBoolean(), any(), any(), any());
    }

    @Test
    @DisplayName("search forwards s3Only=true to the service - full-page Files browser shows ONLY real object-storage files (s3_key not null), hiding DB-resident pseudo-files")
    void searchForwardsS3OnlyTrue() {
        when(explorerService.search(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(Page.empty());

        controller.search("1", "org-1", "MEMBER", 0, 50, null, null, null, null, null, null, null, null, true, true, null, false);

        verify(explorerService).search(eq("1"), eq("org-1"), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), eq(true), any(), any(), any());
    }

    @Test
    @DisplayName("search forwards s3Only=false - non-Files surfaces keep DB-resident rows")
    void searchForwardsS3OnlyFalse() {
        when(explorerService.search(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(Page.empty());

        controller.search("1", "org-1", "MEMBER", 0, 20, null, null, null, null, null, null, null, null, false, false, null, false);

        verify(explorerService).search(eq("1"), eq("org-1"), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), eq(false), any(), any(), any());
    }

    @Test
    @DisplayName("search excludes file ids restricted for the current member")
    void searchPassesRestrictedFileIdsToService() {
        UUID restricted = UUID.randomUUID();
        when(orgAccessGuard.getRestrictedResourceIds("org-1", "1", "file", "MEMBER"))
                .thenReturn(Set.of(restricted.toString(), "not-a-uuid"));
        when(explorerService.search(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(Page.empty());

        controller.search("1", "org-1", "MEMBER", 0, 20, null, null, null, null, null, null, null, null, true, false, null, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> excluded = ArgumentCaptor.forClass(Collection.class);
        verify(explorerService).search(eq("1"), eq("org-1"), any(), any(), any(), any(), any(), any(), any(),
                eq(true), anyBoolean(), any(), excluded.capture(), any());
        assertThat(excluded.getValue()).containsExactly(restricted);
    }

    // ==================== download-zip (bulk) ====================

    private static StorageEntity textEntity(UUID id, String name, String text) {
        StorageEntity e = new StorageEntity();
        e.setId(id);
        e.setStorageType("TEXT");
        e.setFileName(name);
        e.setDataText(text);
        return e;
    }

    private static Map<String, String> readZip(byte[] zipBytes) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                out.put(entry.getName(), new String(zis.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static byte[] realize(ResponseEntity<StreamingResponseBody> resp) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        resp.getBody().writeTo(bos);
        return bos.toByteArray();
    }

    @Test
    @DisplayName("download-zip streams a single ZIP of the resolved files (S3 fetched by key + inline), org-scoped")
    void downloadZipStreamsSelectedFiles() throws IOException {
        UUID s3Id = UUID.randomUUID();
        UUID textId = UUID.randomUUID();
        StorageEntity s3 = s3File(s3Id);
        s3.setFileName("a.pdf");
        s3.setS3Key("1/wf/run/step/a.pdf");
        when(storageService.getEntityByIdForScope(eq(s3Id), any(), any())).thenReturn(Optional.of(s3));
        when(storageService.getEntityByIdForScope(eq(textId), any(), any()))
                .thenReturn(Optional.of(textEntity(textId, "b.txt", "hello")));
        when(fileStorageService.download(eq("1"), eq("1/wf/run/step/a.pdf"))).thenReturn(Optional.of("PDFBYTES".getBytes(StandardCharsets.UTF_8)));

        ResponseEntity<StreamingResponseBody> resp = controller.downloadZip("1", "org-1", "MEMBER",
                Map.of("ids", List.of(s3Id.toString(), textId.toString())));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentType()).isNotNull();
        assertThat(resp.getHeaders().getContentType().toString()).contains("zip");
        Map<String, String> entries = readZip(realize(resp));
        assertThat(entries).containsKeys("a.pdf", "b.txt");
        assertThat(entries).containsEntry("a.pdf", "PDFBYTES").containsEntry("b.txt", "hello");
    }

    @Test
    @DisplayName("download-zip skips cross-org / unknown ids; 404 when none resolve in scope")
    void downloadZipSkipsCrossOrgAndReturns404WhenEmpty() {
        UUID id = UUID.randomUUID();
        when(storageService.getEntityByIdForScope(eq(id), any(), any())).thenReturn(Optional.empty());

        ResponseEntity<StreamingResponseBody> resp = controller.downloadZip("1", "org-1", "MEMBER",
                Map.of("ids", List.of(id.toString())));

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("download-zip rejects an empty / missing id list with 400")
    void downloadZipRejectsEmpty() {
        assertThat(controller.downloadZip("1", "org-1", "MEMBER", Map.of()).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.downloadZip("1", "org-1", "MEMBER", Map.of("ids", List.of())).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("download-zip de-duplicates identical filenames so no ZIP entry is lost")
    void downloadZipDedupsDuplicateNames() throws IOException {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(storageService.getEntityByIdForScope(eq(id1), any(), any())).thenReturn(Optional.of(textEntity(id1, "dup.txt", "one")));
        when(storageService.getEntityByIdForScope(eq(id2), any(), any())).thenReturn(Optional.of(textEntity(id2, "dup.txt", "two")));

        ResponseEntity<StreamingResponseBody> resp = controller.downloadZip("1", "org-1", "MEMBER",
                Map.of("ids", List.of(id1.toString(), id2.toString())));

        Map<String, String> entries = readZip(realize(resp));
        assertThat(entries).containsKeys("dup.txt", "dup (1).txt");
        assertThat(entries.values()).containsExactlyInAnyOrder("one", "two");
    }

    @Test
    @DisplayName("download-zip rejects a selection larger than the 200-file cap with 400")
    void downloadZipRejectsOverCap() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 201; i++) ids.add(UUID.randomUUID().toString());

        assertThat(controller.downloadZip("1", "org-1", "MEMBER", Map.of("ids", ids)).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("download-zip skips an unfetchable entry (S3 download empty) but still archives the rest")
    void downloadZipSkipsUnfetchableEntry() throws IOException {
        UUID badS3 = UUID.randomUUID();
        UUID okText = UUID.randomUUID();
        StorageEntity s3 = s3File(badS3);
        s3.setFileName("gone.pdf");
        s3.setS3Key("1/wf/run/step/gone.pdf");
        when(storageService.getEntityByIdForScope(eq(badS3), any(), any())).thenReturn(Optional.of(s3));
        when(storageService.getEntityByIdForScope(eq(okText), any(), any())).thenReturn(Optional.of(textEntity(okText, "ok.txt", "kept")));
        when(fileStorageService.download(eq("1"), eq("1/wf/run/step/gone.pdf"))).thenReturn(Optional.empty());

        ResponseEntity<StreamingResponseBody> resp = controller.downloadZip("1", "org-1", "MEMBER",
                Map.of("ids", List.of(badS3.toString(), okText.toString())));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, String> entries = readZip(realize(resp));
        assertThat(entries).containsOnlyKeys("ok.txt");
        assertThat(entries).containsEntry("ok.txt", "kept");
    }

    @Test
    @DisplayName("download-zip includes inline BINARY bytes verbatim")
    void downloadZipIncludesBinaryBytes() throws IOException {
        UUID id = UUID.randomUUID();
        StorageEntity bin = new StorageEntity();
        bin.setId(id);
        bin.setStorageType("BINARY");
        bin.setFileName("logo.png");
        byte[] pngMagic = {(byte) 0x89, 'P', 'N', 'G'};
        bin.setDataBinary(pngMagic);
        when(storageService.getEntityByIdForScope(eq(id), any(), any())).thenReturn(Optional.of(bin));

        ResponseEntity<StreamingResponseBody> resp = controller.downloadZip("1", "org-1", "MEMBER", Map.of("ids", List.of(id.toString())));

        byte[] entryBytes = zipEntryBytes(realize(resp), "logo.png");
        assertThat(entryBytes).isEqualTo(pngMagic);
    }

    private static byte[] zipEntryBytes(byte[] zipBytes, String name) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(name)) return zis.readAllBytes();
            }
        }
        return null;
    }

    // ==================== org-member access restrictions ====================
    // Every read & mutate path must honour a per-member file restriction, not only `search`/`preview`.

    @Test
    @DisplayName("download returns 404 when the file is restricted for the org member (never streams bytes)")
    void downloadReturnsNotFoundWhenRestricted() {
        UUID id = UUID.randomUUID();
        when(storageService.getEntityByIdForScope(eq(id), any(), any())).thenReturn(Optional.of(s3File(id)));
        when(orgAccessGuard.canAccess("org-1", "1", "file", id.toString(), "MEMBER")).thenReturn(false);

        ResponseEntity<?> resp = controller.download("1", "org-1", "MEMBER", id);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        // Bytes are never fetched for a restricted file.
        verify(fileStorageService, org.mockito.Mockito.never()).download(anyString(), anyString());
    }

    @Test
    @DisplayName("download-zip drops a restricted file but still archives the allowed ones")
    void downloadZipDropsRestrictedFile() throws IOException {
        UUID allowed = UUID.randomUUID();
        UUID restricted = UUID.randomUUID();
        when(storageService.getEntityByIdForScope(eq(allowed), any(), any()))
                .thenReturn(Optional.of(textEntity(allowed, "allowed.txt", "kept")));
        when(storageService.getEntityByIdForScope(eq(restricted), any(), any()))
                .thenReturn(Optional.of(textEntity(restricted, "secret.txt", "hidden")));
        when(orgAccessGuard.canAccess("org-1", "1", "file", restricted.toString(), "MEMBER")).thenReturn(false);

        ResponseEntity<StreamingResponseBody> resp = controller.downloadZip("1", "org-1", "MEMBER",
                Map.of("ids", List.of(allowed.toString(), restricted.toString())));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        Map<String, String> entries = readZip(realize(resp));
        assertThat(entries).containsOnlyKeys("allowed.txt");
        assertThat(entries).containsEntry("allowed.txt", "kept");
    }

    @Test
    @DisplayName("delete returns 404 for a write-restricted file and never touches storage")
    void deleteEntryReturnsNotFoundWhenRestricted() {
        UUID id = UUID.randomUUID();
        when(orgAccessGuard.canWrite("org-1", "1", "file", id.toString(), "MEMBER")).thenReturn(false);

        ResponseEntity<Map<String, Object>> resp = controller.deleteEntry("1", "org-1", "MEMBER", id);

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        verify(storageService, org.mockito.Mockito.never()).deleteByIdForScope(eq(id), any(), any());
    }

    @Test
    @DisplayName("bulk delete skips restricted ids and only counts the deletions it was allowed to make")
    void deleteEntriesSkipsRestrictedIds() {
        UUID allowed = UUID.randomUUID();
        UUID restricted = UUID.randomUUID();
        when(orgAccessGuard.canWrite("org-1", "1", "file", restricted.toString(), "MEMBER")).thenReturn(false);
        when(storageService.deleteByIdForScope(eq(allowed), eq("1"), eq("org-1"))).thenReturn(true);

        ResponseEntity<Map<String, Object>> resp = controller.deleteEntries("1", "org-1", "MEMBER",
                Map.of("ids", List.of(allowed.toString(), restricted.toString())));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("deletedCount", 1);
        assertThat(resp.getBody()).containsEntry("requestedCount", 2);
        verify(storageService, org.mockito.Mockito.never()).deleteByIdForScope(eq(restricted), any(), any());
    }

    @Test
    @DisplayName("date-range delete excludes the member's write-restricted files from the wipe")
    void deleteByDateRangeExcludesRestrictedFiles() {
        UUID restricted = UUID.randomUUID();
        when(orgAccessGuard.getWriteRestrictedResourceIds("org-1", "1", "file", "MEMBER"))
                .thenReturn(Set.of(restricted.toString()));
        when(storageService.deleteByDateRangeForScope(any(), any(), any(), any(), any())).thenReturn(3);

        controller.deleteByDateRange("1", "org-1", "MEMBER",
                Map.of("dateFrom", "2026-01-01T00:00:00Z", "dateTo", "2026-02-01T00:00:00Z"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> excluded = ArgumentCaptor.forClass(Collection.class);
        verify(storageService).deleteByDateRangeForScope(eq("1"), eq("org-1"), any(), any(), excluded.capture());
        assertThat(excluded.getValue()).containsExactly(restricted);
    }

    @Test
    @DisplayName("stats excludes the member's restricted file ids from the aggregate")
    void statsExcludesRestrictedFiles() {
        UUID restricted = UUID.randomUUID();
        when(orgAccessGuard.getRestrictedResourceIds("org-1", "1", "file", "MEMBER"))
                .thenReturn(Set.of(restricted.toString()));
        when(explorerService.getStats(any(), any(), any())).thenReturn(List.of());

        controller.getStats("1", "org-1", "MEMBER");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> excluded = ArgumentCaptor.forClass(Collection.class);
        verify(explorerService).getStats(eq("1"), eq("org-1"), excluded.capture());
        assertThat(excluded.getValue()).containsExactly(restricted);
    }

    @Test
    @DisplayName("read-only restriction: the member can preview the file but cannot delete it")
    void readOnlyFileIsPreviewableButNotDeletable() {
        UUID id = UUID.randomUUID();
        when(storageService.getEntityByIdForScope(eq(id), any(), any())).thenReturn(Optional.of(s3File(id)));
        when(fileStorageService.generateDownloadUrl(anyString(), anyString())).thenReturn("https://minio/signed");
        // READ-only = readable (canAccess true) but not writable (canWrite false).
        when(orgAccessGuard.canAccess("org-1", "1", "file", id.toString(), "MEMBER")).thenReturn(true);
        when(orgAccessGuard.canWrite("org-1", "1", "file", id.toString(), "MEMBER")).thenReturn(false);

        assertThat(controller.preview("1", "org-1", "MEMBER", id).getStatusCode().value())
                .as("read-only member can preview").isEqualTo(200);
        assertThat(controller.deleteEntry("1", "org-1", "MEMBER", id).getStatusCode().value())
                .as("read-only member cannot delete").isEqualTo(404);
        verify(storageService, org.mockito.Mockito.never()).deleteByIdForScope(eq(id), any(), any());
    }

    // ==================== rename ====================

    @Test
    @DisplayName("rename: 200 + echoes new name when the service renames in scope")
    void renameReturns200() {
        UUID id = UUID.randomUUID();
        when(storageService.renameByIdForScope(eq(id), eq("1"), eq("org-1"), eq("new.png"))).thenReturn(true);

        ResponseEntity<Map<String, Object>> resp = controller.renameEntry(
                "1", "org-1", "MEMBER", id, Map.of("fileName", "new.png"));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("fileName", "new.png");
    }

    @Test
    @DisplayName("rename: trims surrounding whitespace and caps the name at 255 chars before persisting")
    void renameTrimsAndCaps() {
        UUID id = UUID.randomUUID();
        when(storageService.renameByIdForScope(eq(id), any(), any(), anyString())).thenReturn(true);

        String longName = "  " + "a".repeat(400) + "  ";
        controller.renameEntry("1", "org-1", "MEMBER", id, Map.of("fileName", longName));

        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        verify(storageService).renameByIdForScope(eq(id), eq("1"), eq("org-1"), nameCaptor.capture());
        assertThat(nameCaptor.getValue()).hasSize(255).doesNotStartWith(" ");
    }

    @Test
    @DisplayName("rename: blank/whitespace name → 400 and no service call")
    void renameBlankReturns400() {
        UUID id = UUID.randomUUID();
        ResponseEntity<Map<String, Object>> resp = controller.renameEntry(
                "1", "org-1", "MEMBER", id, Map.of("fileName", "   "));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verify(storageService, org.mockito.Mockito.never()).renameByIdForScope(any(), any(), any(), any());
    }

    @Test
    @DisplayName("rename: not found in scope → 404")
    void renameNotFoundReturns404() {
        UUID id = UUID.randomUUID();
        when(storageService.renameByIdForScope(eq(id), any(), any(), anyString())).thenReturn(false);

        ResponseEntity<Map<String, Object>> resp = controller.renameEntry(
                "1", "org-1", "MEMBER", id, Map.of("fileName", "new.png"));

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("rename: write-restricted (read-only) member → 404 and no service call")
    void renameWriteRestrictedReturns404() {
        UUID id = UUID.randomUUID();
        when(orgAccessGuard.canWrite("org-1", "1", "file", id.toString(), "MEMBER")).thenReturn(false);

        ResponseEntity<Map<String, Object>> resp = controller.renameEntry(
                "1", "org-1", "MEMBER", id, Map.of("fileName", "new.png"));

        assertThat(resp.getStatusCode().value()).isEqualTo(404);
        verify(storageService, org.mockito.Mockito.never()).renameByIdForScope(any(), any(), any(), any());
    }

    // ==================== V313 manual folders ====================

    private static StorageEntity folderEntity(UUID id, String name, UUID parentId) {
        StorageEntity e = new StorageEntity();
        e.setId(id);
        e.setFileName(name);
        e.setIsFolder(true);
        e.setParentFolderId(parentId);
        return e;
    }

    @Test
    @DisplayName("createFolder: 200 + echoes the created folder (id, name, isFolder, null parent at root)")
    void createFolderReturns200() {
        UUID newId = UUID.randomUUID();
        when(storageService.createFolderForScope(eq("1"), eq("org-1"), eq("Reports"), eq(null)))
                .thenReturn(folderEntity(newId, "Reports", null));

        ResponseEntity<Map<String, Object>> resp = controller.createFolder(
                "1", "org-1", "MEMBER", new java.util.HashMap<>(Map.of("name", "Reports")));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("id", newId.toString());
        assertThat(resp.getBody()).containsEntry("name", "Reports");
        assertThat(resp.getBody()).containsEntry("isFolder", true);
        assertThat(resp.getBody().get("parentFolderId")).isNull();
    }

    @Test
    @DisplayName("createFolder: nests under a parent and echoes the parentFolderId")
    void createFolderNested() {
        UUID parentId = UUID.randomUUID();
        UUID newId = UUID.randomUUID();
        when(storageService.createFolderForScope(eq("1"), eq("org-1"), eq("Sub"), eq(parentId)))
                .thenReturn(folderEntity(newId, "Sub", parentId));

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", "Sub");
        body.put("parentFolderId", parentId.toString());
        ResponseEntity<Map<String, Object>> resp = controller.createFolder("1", "org-1", "MEMBER", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("parentFolderId", parentId.toString());
    }

    @Test
    @DisplayName("createFolder: blank name → 400 and no service call")
    void createFolderBlankNameReturns400() {
        ResponseEntity<Map<String, Object>> resp = controller.createFolder(
                "1", "org-1", "MEMBER", new java.util.HashMap<>(Map.of("name", "   ")));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verify(storageService, org.mockito.Mockito.never()).createFolderForScope(any(), any(), any(), any());
    }

    @Test
    @DisplayName("createFolder: malformed parentFolderId → 400 before reaching the service")
    void createFolderBadParentReturns400() {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", "X");
        body.put("parentFolderId", "not-a-uuid");
        ResponseEntity<Map<String, Object>> resp = controller.createFolder("1", "org-1", "MEMBER", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verify(storageService, org.mockito.Mockito.never()).createFolderForScope(any(), any(), any(), any());
    }

    @Test
    @DisplayName("createFolder: service rejects an invalid (cross-org / non-folder) parent → 400 with the reason")
    void createFolderServiceRejectionReturns400() {
        UUID parentId = UUID.randomUUID();
        when(storageService.createFolderForScope(eq("1"), eq("org-1"), eq("X"), eq(parentId)))
                .thenThrow(new IllegalArgumentException("parentFolderId is not a folder in this workspace"));

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", "X");
        body.put("parentFolderId", parentId.toString());
        ResponseEntity<Map<String, Object>> resp = controller.createFolder("1", "org-1", "MEMBER", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody().get("error").toString()).contains("not a folder");
    }

    private static StorageExplorerDto folderDto(UUID id, String name, UUID parentId) {
        return new StorageExplorerDto(
                id, "FOLDER", "FOLDER", name, null, null, "0 B", Instant.parse("2026-06-08T00:00:00Z"),
                null, null, null, null, null, null, null, null, true,
                parentId != null ? parentId.toString() : null, 0, List.of(), null, null, null, null);
    }

    @Test
    @DisplayName("listFolders: returns every manual folder as {id, name, parentFolderId}, org-scoped")
    void listFoldersReturnsFlatTree() {
        UUID rootId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        when(explorerService.listAllManualFolders(eq("1"), eq("org-1"), any()))
                .thenReturn(List.of(folderDto(rootId, "Reports", null), folderDto(childId, "2025", rootId)));

        ResponseEntity<List<Map<String, Object>>> resp = controller.listFolders("1", "org-1", "MEMBER");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).hasSize(2);
        assertThat(resp.getBody().get(0)).containsEntry("id", rootId.toString())
                .containsEntry("name", "Reports").containsEntry("parentFolderId", null);
        assertThat(resp.getBody().get(1)).containsEntry("id", childId.toString())
                .containsEntry("name", "2025").containsEntry("parentFolderId", rootId.toString());
    }

    @Test
    @DisplayName("listFolders: excludes the member's restricted folder ids (deny-list threaded into the read)")
    void listFoldersExcludesRestricted() {
        UUID restricted = UUID.randomUUID();
        when(orgAccessGuard.getRestrictedResourceIds("org-1", "1", "file", "MEMBER"))
                .thenReturn(Set.of(restricted.toString()));
        when(explorerService.listAllManualFolders(eq("1"), eq("org-1"), any())).thenReturn(List.of());

        controller.listFolders("1", "org-1", "MEMBER");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> excluded = ArgumentCaptor.forClass(Collection.class);
        verify(explorerService).listAllManualFolders(eq("1"), eq("org-1"), excluded.capture());
        assertThat(excluded.getValue()).containsExactly(restricted);
    }

    @Test
    @DisplayName("move: 200 + movedCount; restricted ids are reported in failed (per-id, not 403)")
    void moveReturns200WithRestrictedReported() {
        UUID target = UUID.randomUUID();
        UUID ok = UUID.randomUUID();
        UUID restricted = UUID.randomUUID();
        when(orgAccessGuard.canWrite("org-1", "1", "file", restricted.toString(), "MEMBER")).thenReturn(false);
        when(storageService.moveEntriesForScope(eq("org-1"), any(), eq(target)))
                .thenReturn(new StorageService.MoveResult(1, List.of()));

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("ids", List.of(ok.toString(), restricted.toString()));
        body.put("parentFolderId", target.toString());
        ResponseEntity<Map<String, Object>> resp = controller.moveEntries("1", "org-1", "MEMBER", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("movedCount", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failed = (List<Map<String, Object>>) resp.getBody().get("failed");
        assertThat(failed).anySatisfy(f -> {
            assertThat(f.get("id")).isEqualTo(restricted.toString());
            assertThat(f.get("reason")).isEqualTo("restricted");
        });
        // Only the writable id reaches the service.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> writable = ArgumentCaptor.forClass(Collection.class);
        verify(storageService).moveEntriesForScope(eq("org-1"), writable.capture(), eq(target));
        assertThat(writable.getValue()).containsExactly(ok);
    }

    @Test
    @DisplayName("move: service cycle/cross-org failures are surfaced in the failed list")
    void moveSurfacesServiceFailures() {
        UUID target = UUID.randomUUID();
        UUID folderId = UUID.randomUUID();
        when(storageService.moveEntriesForScope(eq("org-1"), any(), eq(target)))
                .thenReturn(new StorageService.MoveResult(0,
                        List.of(new StorageService.MoveFailure(folderId, "cannot move a folder into its own descendant"))));

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("ids", List.of(folderId.toString()));
        body.put("parentFolderId", target.toString());
        ResponseEntity<Map<String, Object>> resp = controller.moveEntries("1", "org-1", "MEMBER", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("movedCount", 0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failed = (List<Map<String, Object>>) resp.getBody().get("failed");
        assertThat(failed).anySatisfy(f -> assertThat(f.get("reason").toString()).contains("descendant"));
    }

    @Test
    @DisplayName("move to root: null parentFolderId passes null target to the service")
    void moveToRootPassesNullTarget() {
        UUID ok = UUID.randomUUID();
        when(storageService.moveEntriesForScope(eq("org-1"), any(), eq(null)))
                .thenReturn(new StorageService.MoveResult(1, List.of()));

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("ids", List.of(ok.toString()));
        // no parentFolderId → move to root
        ResponseEntity<Map<String, Object>> resp = controller.moveEntries("1", "org-1", "MEMBER", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(storageService).moveEntriesForScope(eq("org-1"), any(), eq(null));
    }

    @Test
    @DisplayName("move: empty/missing ids → 400 and no service call")
    void moveEmptyIdsReturns400() {
        assertThat(controller.moveEntries("1", "org-1", "MEMBER", new java.util.HashMap<>())
                .getStatusCode().value()).isEqualTo(400);
        Map<String, Object> emptyIds = new java.util.HashMap<>();
        emptyIds.put("ids", List.of());
        assertThat(controller.moveEntries("1", "org-1", "MEMBER", emptyIds).getStatusCode().value()).isEqualTo(400);
        verify(storageService, org.mockito.Mockito.never()).moveEntriesForScope(any(), any(), any());
    }

    @Test
    @DisplayName("move: malformed parentFolderId → 400 before reaching the service")
    void moveBadTargetReturns400() {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("ids", List.of(UUID.randomUUID().toString()));
        body.put("parentFolderId", "not-a-uuid");
        ResponseEntity<Map<String, Object>> resp = controller.moveEntries("1", "org-1", "MEMBER", body);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verify(storageService, org.mockito.Mockito.never()).moveEntriesForScope(any(), any(), any());
    }

    @Test
    @DisplayName("listing: parentFolderId=root routes to the folder-aware service with null parent")
    void listingRootRoutesToFolderScope() {
        when(explorerService.searchFolderScope(eq("1"), eq("org-1"), eq(null), any(), any(), any(), any(),
                any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(Page.empty());

        ResponseEntity<Page<com.apimarketplace.common.storage.dto.StorageExplorerDto>> resp =
                controller.search("1", "org-1", "MEMBER", 0, 20, null, null, null, null, null, null, null, null, true, true, "root", false);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(explorerService).searchFolderScope(eq("1"), eq("org-1"), eq(null), any(), any(), any(), any(),
                any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any());
        // legacy flat search is NOT used in folder-aware mode
        verify(explorerService, org.mockito.Mockito.never())
                .search(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any());
    }

    @Test
    @DisplayName("listing: parentFolderId=<valid folder UUID> routes to the folder's children")
    void listingInsideFolderRoutesWithParentId() {
        UUID folderId = UUID.randomUUID();
        when(storageService.getEntityByIdForScope(eq(folderId), any(), any()))
                .thenReturn(Optional.of(folderEntity(folderId, "F", null)));
        when(explorerService.searchFolderScope(eq("1"), eq("org-1"), eq(folderId), any(), any(), any(), any(),
                any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(Page.empty());

        ResponseEntity<Page<com.apimarketplace.common.storage.dto.StorageExplorerDto>> resp =
                controller.search("1", "org-1", "MEMBER", 0, 20, null, null, null, null, null, null, null, null, true, true, folderId.toString(), false);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        verify(explorerService).searchFolderScope(eq("1"), eq("org-1"), eq(folderId), any(), any(), any(), any(),
                any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any());
    }

    @Test
    @DisplayName("listing: parentFolderId pointing at a non-folder / cross-org id → empty page, never queries children")
    void listingBadParentReturnsEmptyPage() {
        UUID notAFolder = UUID.randomUUID();
        when(storageService.getEntityByIdForScope(eq(notAFolder), any(), any())).thenReturn(Optional.empty());

        ResponseEntity<Page<com.apimarketplace.common.storage.dto.StorageExplorerDto>> resp =
                controller.search("1", "org-1", "MEMBER", 0, 20, null, null, null, null, null, null, null, null, true, true, notAFolder.toString(), false);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().getTotalElements()).isZero();
        verify(explorerService, org.mockito.Mockito.never()).searchFolderScope(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any());
    }

    @Test
    @DisplayName("listing: malformed parentFolderId (not 'root', not a UUID) → empty page")
    void listingMalformedParentReturnsEmptyPage() {
        ResponseEntity<Page<com.apimarketplace.common.storage.dto.StorageExplorerDto>> resp =
                controller.search("1", "org-1", "MEMBER", 0, 20, null, null, null, null, null, null, null, null, true, true, "garbage", false);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().getTotalElements()).isZero();
        verify(explorerService, org.mockito.Mockito.never()).searchFolderScope(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any());
    }

    @Test
    @DisplayName("delete: a folder routes through deleteByIdForScope (which re-parents children, re-import-safe)")
    void deleteFolderRoutesThroughService() {
        UUID folderId = UUID.randomUUID();
        when(storageService.deleteByIdForScope(eq(folderId), eq("1"), eq("org-1"))).thenReturn(true);

        ResponseEntity<Map<String, Object>> resp = controller.deleteEntry("1", "org-1", "MEMBER", folderId);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsEntry("deleted", true);
        verify(storageService).deleteByIdForScope(eq(folderId), eq("1"), eq("org-1"));
    }

    // ==================== Phase 2b virtual workflow folders ====================

    @Test
    @DisplayName("virtualWorkflowFolders=true + 'root' routes to searchVirtualScope (null address) and resolves WORKFLOW names")
    void virtualRootResolvesWorkflowNames() {
        UUID wfId = UUID.randomUUID();
        StorageExplorerDto wfFolder = StorageExplorerDto.virtualFolder(
                "wf:" + wfId, "WORKFLOW", wfId.toString(), /* name */ null,
                null, null, null, 5, List.of(), Instant.parse("2026-06-08T00:00:00Z"));
        when(explorerService.searchVirtualScope(eq("1"), eq("org-1"), eq(null), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(wfFolder), PageRequest.of(0, 20), 1));
        when(workflowRepository.findIdNamePairs(any()))
                .thenReturn(List.<Object[]>of(new Object[]{wfId, "My Workflow"}));

        ResponseEntity<Page<StorageExplorerDto>> resp =
                controller.search("1", "org-1", "MEMBER", 0, 20, null, null, null, null, null, null, null, null, true, true, "root", true);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        StorageExplorerDto resolved = resp.getBody().getContent().get(0);
        assertThat(resolved.virtualKind()).isEqualTo("WORKFLOW");
        assertThat(resolved.workflowName()).isEqualTo("My Workflow");
        // The legacy flat search and the V313 folder scope are NOT used in virtual mode.
        verify(explorerService, org.mockito.Mockito.never())
                .search(any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any());
        verify(explorerService, org.mockito.Mockito.never())
                .searchFolderScope(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any());
    }

    @Test
    @DisplayName("virtual mode: a WORKFLOW folder whose id does not resolve (deleted workflow) is OMITTED, not shown nameless")
    void virtualUnresolvedWorkflowFolderIsOmitted() {
        UUID wfId = UUID.randomUUID();
        StorageExplorerDto wfFolder = StorageExplorerDto.virtualFolder(
                "wf:" + wfId, "WORKFLOW", wfId.toString(), null,
                null, null, null, 1, List.of(), Instant.parse("2026-06-08T00:00:00Z"));
        when(explorerService.searchVirtualScope(eq("1"), eq("org-1"), eq(null), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(wfFolder), PageRequest.of(0, 20), 1));
        when(workflowRepository.findIdNamePairs(any())).thenReturn(List.of()); // deleted / cross-org

        ResponseEntity<Page<StorageExplorerDto>> resp =
                controller.search("1", "org-1", "MEMBER", 0, 20, null, null, null, null, null, null, null, null, true, true, "root", true);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        // The orphaned folder is dropped from the page content (no blank folder rendered).
        assertThat(resp.getBody().getContent()).isEmpty();
    }

    @Test
    @DisplayName("virtual mode: a mixed page keeps the resolvable WORKFLOW folder and omits the orphaned one")
    void virtualMixedPageKeepsResolvableOmitsOrphan() {
        UUID liveId = UUID.randomUUID();
        UUID deletedId = UUID.randomUUID();
        StorageExplorerDto live = StorageExplorerDto.virtualFolder(
                "wf:" + liveId, "WORKFLOW", liveId.toString(), null,
                null, null, null, 3, List.of(), Instant.parse("2026-06-08T00:00:00Z"));
        StorageExplorerDto orphan = StorageExplorerDto.virtualFolder(
                "wf:" + deletedId, "WORKFLOW", deletedId.toString(), null,
                null, null, null, 1, List.of(), Instant.parse("2026-06-08T00:00:00Z"));
        when(explorerService.searchVirtualScope(eq("1"), eq("org-1"), eq(null), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(live, orphan), PageRequest.of(0, 20), 2));
        // Only the live workflow resolves; the deleted one returns no pair.
        when(workflowRepository.findIdNamePairs(any()))
                .thenReturn(List.<Object[]>of(new Object[]{liveId, "Live Workflow"}));

        ResponseEntity<Page<StorageExplorerDto>> resp =
                controller.search("1", "org-1", "MEMBER", 0, 20, null, null, null, null, null, null, null, null, true, true, "root", true);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().getContent()).hasSize(1);
        StorageExplorerDto kept = resp.getBody().getContent().get(0);
        assertThat(kept.workflowId()).isEqualTo(liveId.toString());
        assertThat(kept.workflowName()).isEqualTo("Live Workflow");
    }

    @Test
    @DisplayName("virtual mode: a nested address (wf:<id>/e0) parses and routes to searchVirtualScope with that address")
    void virtualNestedAddressRoutes() {
        UUID wfId = UUID.randomUUID();
        when(explorerService.searchVirtualScope(eq("1"), eq("org-1"), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(), any(), any(), any()))
                .thenReturn(Page.empty());

        ResponseEntity<Page<StorageExplorerDto>> resp =
                controller.search("1", "org-1", "MEMBER", 0, 20, null, null, null, null, null, null, null, null, true, true, "wf:" + wfId + "/e0", true);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        ArgumentCaptor<com.apimarketplace.common.storage.dto.VirtualFolderAddress> addr =
                ArgumentCaptor.forClass(com.apimarketplace.common.storage.dto.VirtualFolderAddress.class);
        verify(explorerService).searchVirtualScope(eq("1"), eq("org-1"), addr.capture(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(), any(), any(), any());
        assertThat(addr.getValue()).isNotNull();
        assertThat(addr.getValue().workflowId()).isEqualTo(wfId.toString());
        assertThat(addr.getValue().epoch()).isEqualTo(0);
    }

    @Test
    @DisplayName("virtual mode: a manual-folder UUID token is NOT a virtual address → falls through to the V313 folder scope")
    void virtualModeFallsThroughForManualFolderUuid() {
        UUID folderId = UUID.randomUUID();
        when(storageService.getEntityByIdForScope(eq(folderId), any(), any()))
                .thenReturn(Optional.of(folderEntity(folderId, "F", null)));
        when(explorerService.searchFolderScope(eq("1"), eq("org-1"), eq(folderId), any(), any(), any(), any(),
                any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(Page.empty());

        ResponseEntity<Page<StorageExplorerDto>> resp =
                controller.search("1", "org-1", "MEMBER", 0, 20, null, null, null, null, null, null, null, null, true, true, folderId.toString(), true);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        // UUID is not a virtual token → virtual scope is never called; the V313 branch handles it.
        verify(explorerService, org.mockito.Mockito.never())
                .searchVirtualScope(any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), any());
        verify(explorerService).searchFolderScope(eq("1"), eq("org-1"), eq(folderId), any(), any(), any(), any(),
                any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any());
    }

    @Test
    @DisplayName("virtualWorkflowFolders=false keeps legacy behaviour: a 'root' token still routes to the V313 folder scope, never the virtual scope")
    void flagOffKeepsLegacyBehaviour() {
        when(explorerService.searchFolderScope(eq("1"), eq("org-1"), eq(null), any(), any(), any(), any(),
                any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                .thenReturn(Page.empty());

        controller.search("1", "org-1", "MEMBER", 0, 20, null, null, null, null, null, null, null, null, true, true, "root", false);

        verify(explorerService, org.mockito.Mockito.never())
                .searchVirtualScope(any(), any(), any(), any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any(), any(), any());
        verify(explorerService).searchFolderScope(eq("1"), eq("org-1"), eq(null), any(), any(), any(), any(),
                any(), any(), any(), anyBoolean(), anyBoolean(), any(), any(), any());
    }

    @Test
    @DisplayName("virtual mode does not resolve names when there are no WORKFLOW folders (epoch/file pages skip the workflow query)")
    void virtualModeSkipsNameResolutionWhenNoWorkflowFolders() {
        StorageExplorerDto epochFolder = StorageExplorerDto.virtualFolder(
                "wf:abc/e0", "EPOCH", "abc", null, 0, null, null, 2, List.of(), Instant.parse("2026-06-08T00:00:00Z"));
        when(explorerService.searchVirtualScope(eq("1"), eq("org-1"), any(), any(), any(), any(),
                anyBoolean(), anyBoolean(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(epochFolder), PageRequest.of(0, 20), 1));

        controller.search("1", "org-1", "MEMBER", 0, 20, null, null, null, null, null, null, null, null, true, true, "wf:abc", true);

        verify(workflowRepository, org.mockito.Mockito.never()).findIdNamePairs(any());
    }
}
