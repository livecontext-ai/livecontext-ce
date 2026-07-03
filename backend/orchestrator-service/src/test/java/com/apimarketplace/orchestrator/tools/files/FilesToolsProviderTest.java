package com.apimarketplace.orchestrator.tools.files;

import com.apimarketplace.agent.registry.AgentToolDefinition;
import com.apimarketplace.agent.registry.ToolCategory;
import com.apimarketplace.agent.tools.ToolErrorCode;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import com.apimarketplace.agent.tools.common.ToolMediaMetadata;
import com.apimarketplace.auth.client.access.OrgAccessGuard;
import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.orchestrator.services.file.FileStorageService;
import com.apimarketplace.common.storage.dto.StorageExplorerDto;
import com.apimarketplace.common.storage.dto.VirtualFolderAddress;
import com.apimarketplace.common.storage.service.StorageExplorerService;
import com.apimarketplace.common.storage.service.StorageExplorerService.FilesSlice;
import com.apimarketplace.common.storage.service.StorageService;
import com.apimarketplace.common.storage.url.PublicFileUrlBuilder;
import com.apimarketplace.orchestrator.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("FilesToolsProvider - agent files browse/view tool")
class FilesToolsProviderTest {

    private static final String TENANT = "user-1";
    private static final String ORG = "org-uuid-1";
    private static final String BASE = "https://app.test";

    private StorageExplorerService explorerService;
    private StorageService storageService;
    private OrgAccessGuard orgAccessGuard;
    private WorkflowRepository workflowRepository;
    private FilesToolsProvider provider;

    @BeforeEach
    void setUp() {
        explorerService = mock(StorageExplorerService.class);
        storageService = mock(StorageService.class);
        orgAccessGuard = mock(OrgAccessGuard.class);
        workflowRepository = mock(WorkflowRepository.class);
        lenient().when(orgAccessGuard.getRestrictedResourceIds(any(), any(), eq("file"), any())).thenReturn(Set.of());
        lenient().when(orgAccessGuard.canAccess(any(), any(), eq("file"), any(), any())).thenReturn(true);
        lenient().when(orgAccessGuard.canWrite(any(), any(), eq("file"), any(), any())).thenReturn(true);
        provider = new FilesToolsProvider(explorerService, storageService, new PublicFileUrlBuilder(BASE),
                orgAccessGuard, workflowRepository);
    }

    private static ToolExecutionContext ctx(String tenant, String org) {
        return new ToolExecutionContext(tenant, Map.of(), Map.of(), Set.of(), null, null, org, null);
    }

    private static ToolExecutionContext ctx(String tenant, String org, String orgRole) {
        return new ToolExecutionContext(tenant, Map.of(), Map.of(), Set.of(), null, null, org, orgRole);
    }

    /** Context carrying an agent file allow-list (toolsConfig.files -> allowedFileIds). */
    private static ToolExecutionContext ctxAllowed(String tenant, String org, String orgRole, List<String> allowedFileIds) {
        return new ToolExecutionContext(tenant, Map.of("allowedFileIds", allowedFileIds), Map.of(), Set.of(), null, null, org, orgRole);
    }

    private static StorageExplorerDto dto(UUID id, String name, String mime) {
        return new StorageExplorerDto(id, "S3_FILE", "S3_FILE", name, mime, 1024, "1.0 KB",
                Instant.parse("2026-05-29T10:00:00Z"), "wf-1", null, null, "run-1", "step-1", 0,
                "tenant/wf/run/step/" + name, mime,
                /* isFolder */ false, /* parentFolderId */ null, /* childCount */ null, /* previewFiles */ null,
                /* virtualId */ null, /* virtualKind */ null, /* spawn */ null, /* itemIndex */ null);
    }

    private static StorageEntity entity(String storageType) {
        StorageEntity e = new StorageEntity();
        e.setId(UUID.randomUUID());
        e.setStorageType(storageType);
        e.setFileName("report.txt");
        e.setMimeType("text/plain");
        e.setContentType("text/plain");
        e.setSizeBytes(123);
        e.setCreatedAt(Instant.parse("2026-05-29T10:00:00Z"));
        e.setRunId("run-1");
        e.setWorkflowId("wf-1");
        e.setStepKey("step-1");
        return e;
    }

    /** A persisted MANUAL folder DTO (isFolder=true, id present, no virtual coordinates). */
    private static StorageExplorerDto manualFolderDto(UUID id, String name, int childCount) {
        return new StorageExplorerDto(id, "FOLDER", "FOLDER", name, null, 0, "0 B",
                Instant.parse("2026-05-29T10:00:00Z"), null, null, null, null, null, null,
                null, null, /* isFolder */ true, /* parentFolderId */ null, /* childCount */ childCount,
                /* previewFiles */ List.of(), /* virtualId */ null, /* virtualKind */ null,
                /* spawn */ null, /* itemIndex */ null);
    }

    /** A synthetic VIRTUAL folder DTO (id=null, virtualId/virtualKind set) - mirrors StorageExplorerDto.virtualFolder. */
    private static StorageExplorerDto virtualFolderDto(String virtualId, String virtualKind, String workflowId,
                                                       Integer epoch, Integer spawn, Integer itemIndex, int childCount) {
        return new StorageExplorerDto(null, "FOLDER", "FOLDER", null, null, null, "0 B",
                Instant.parse("2026-05-29T10:00:00Z"), workflowId, null, null, null, null, epoch,
                null, null, /* isFolder */ true, /* parentFolderId */ null, /* childCount */ childCount,
                /* previewFiles */ List.of(), virtualId, virtualKind, spawn, itemIndex);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolExecutionResult r) {
        assertThat(r.success()).as("expected success but got error: %s", r.error()).isTrue();
        return (Map<String, Object>) r.data();
    }

    // ==================== list ====================

    @Nested
    @DisplayName("list")
    class ListAction {

        @Test
        @DisplayName("emits the canonical AgentListEnvelope with file items (no s3_key / storageType leaked)")
        void listEmitsCanonicalEnvelope() {
            FilesSlice slice = new FilesSlice(
                    List.of(dto(UUID.randomUUID(), "a.pdf", "application/pdf"),
                            dto(UUID.randomUUID(), "b.png", "image/png")),
                    2);
            when(explorerService.searchFilesSlice(any(), eq(ORG), any(), any(), any(), any(), any(),
                    any(), any(), anyBoolean(), any(), anyInt(), anyInt())).thenReturn(slice);

            Map<String, Object> out = data(provider.execute("files", Map.of("action", "list"), ctx(TENANT, ORG)));

            assertThat(out).containsEntry("status", "OK").containsEntry("kind", "files");
            assertThat(out).containsEntry("total", 2L).containsEntry("offset", 0).containsEntry("limit", 25);
            assertThat(out).containsEntry("hasMore", false);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files = (List<Map<String, Object>>) out.get("files");
            assertThat(files).hasSize(2);
            Map<String, Object> first = files.get(0);
            assertThat(first).containsKeys("file_id", "name", "mime_type", "size_bytes", "kind", "url", "created_at");
            assertThat(first).containsEntry("kind", "document");
            assertThat(first).doesNotContainKeys("s3_key", "s3Key", "storageType", "storage_type");
            // Each list item carries an opaque absolute url - addressed by the row UUID, with no
            // s3 key and no "tenant/" prefix (the dto's s3Key is "tenant/wf/run/step/a.pdf").
            assertThat((String) first.get("url"))
                    .isEqualTo(BASE + "/api/proxy/files/by-id/" + first.get("file_id") + "/raw?disposition=inline")
                    .doesNotContain("tenant");
        }

        @Test
        @DisplayName("restricts to real files (filesOnly=true) so step-output JSON is excluded")
        void listRequestsFilesOnly() {
            when(explorerService.searchFilesSlice(any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), anyBoolean(), any(), anyInt(), anyInt())).thenReturn(new FilesSlice(List.of(), 0));

            provider.execute("files", Map.of("action", "list"), ctx(TENANT, ORG));

            ArgumentCaptor<Boolean> filesOnly = ArgumentCaptor.forClass(Boolean.class);
            verify(explorerService).searchFilesSlice(any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), filesOnly.capture(), any(), anyInt(), anyInt());
            assertThat(filesOnly.getValue()).isTrue();
        }

        @Test
        @DisplayName("excludes files restricted for the current org member")
        void listExcludesRestrictedFiles() {
            UUID restricted = UUID.randomUUID();
            when(orgAccessGuard.getRestrictedResourceIds(ORG, TENANT, "file", "MEMBER"))
                    .thenReturn(Set.of(restricted.toString(), "not-a-uuid"));
            when(explorerService.searchFilesSlice(any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), anyBoolean(), any(), anyInt(), anyInt())).thenReturn(new FilesSlice(List.of(), 0));

            provider.execute("files", Map.of("action", "list"), ctx(TENANT, ORG, "MEMBER"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<UUID>> excluded = ArgumentCaptor.forClass(Collection.class);
            verify(explorerService).searchFilesSlice(eq(TENANT), eq(ORG), any(), any(), any(), any(), any(),
                    any(), any(), eq(true), excluded.capture(), anyInt(), anyInt());
            assertThat(excluded.getValue()).containsExactly(restricted);
        }

        @Test
        @DisplayName("emits a refine hint at offset 0 for a large unfiltered result set")
        void listRefineHintOnLargeResultSet() {
            List<StorageExplorerDto> page = new java.util.ArrayList<>();
            for (int i = 0; i < 25; i++) page.add(dto(UUID.randomUUID(), "f" + i + ".pdf", "application/pdf"));
            when(explorerService.searchFilesSlice(any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), anyBoolean(), any(), anyInt(), anyInt())).thenReturn(new FilesSlice(page, 150));

            Map<String, Object> out = data(provider.execute("files", Map.of("action", "list"), ctx(TENANT, ORG)));

            @SuppressWarnings("unchecked")
            Map<String, Object> hint = (Map<String, Object>) out.get("hint");
            assertThat(hint).isNotNull();
            assertThat(hint).containsEntry("action", "refine");
        }

        @Test
        @DisplayName("hard-refuses deep pagination without a filter (PAGINATION_LIMIT_WITHOUT_FILTER)")
        void listHardRefusesDeepOffsetWithoutFilter() {
            ToolExecutionResult r = provider.execute("files",
                    Map.of("action", "list", "offset", 5000), ctx(TENANT, ORG));

            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(r.error()).contains("PAGINATION_LIMIT_WITHOUT_FILTER");
            verifyNoInteractions(explorerService);
        }

        @Test
        @DisplayName("forwards query / run_id / workflow_id filters to the storage service (filesOnly=true)")
        void listForwardsFiltersToService() {
            when(explorerService.searchFilesSlice(any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), anyBoolean(), any(), anyInt(), anyInt())).thenReturn(new FilesSlice(List.of(), 0));

            provider.execute("files", Map.of("action", "list",
                    "query", "inv", "run_id", "run-9", "workflow_id", "wf-9"), ctx(TENANT, ORG));

            verify(explorerService).searchFilesSlice(eq(TENANT), eq(ORG), eq("inv"), isNull(), isNull(),
                    eq("wf-9"), eq("run-9"), any(), any(), eq(true), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("suggests broaden when there are no results")
        void listBroadenHintOnEmptyResult() {
            when(explorerService.searchFilesSlice(any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), anyBoolean(), any(), anyInt(), anyInt())).thenReturn(new FilesSlice(List.of(), 0));

            Map<String, Object> out = data(provider.execute("files", Map.of("action", "list"), ctx(TENANT, ORG)));

            @SuppressWarnings("unchecked")
            Map<String, Object> hint = (Map<String, Object>) out.get("hint");
            assertThat(hint).containsEntry("action", "broaden");
        }
    }

    // ==================== get ====================

    @Nested
    @DisplayName("get")
    class GetAction {

        @Test
        @DisplayName("returns NOT_FOUND for an unknown or cross-organization file_id")
        void crossOrgFileIdReturnsNotFound() {
            UUID id = UUID.randomUUID();
            when(storageService.getEntityByIdForScope(id, TENANT, ORG)).thenReturn(Optional.empty());

            ToolExecutionResult r = provider.execute("files",
                    Map.of("action", "get", "file_id", id.toString()), ctx(TENANT, ORG));

            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
        }

        @Test
        @DisplayName("returns NOT_FOUND for a restricted file_id without reading the storage row")
        void restrictedFileIdReturnsNotFound() {
            UUID id = UUID.randomUUID();
            when(orgAccessGuard.canAccess(ORG, TENANT, "file", id.toString(), "MEMBER")).thenReturn(false);

            ToolExecutionResult r = provider.execute("files",
                    Map.of("action", "get", "file_id", id.toString()), ctx(TENANT, ORG, "MEMBER"));

            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
            verify(storageService, never()).getEntityByIdForScope(id, TENANT, ORG);
        }

        @Test
        @DisplayName("returns metadata + a cheap preview, never the full content")
        void getReturnsPreviewNotFullContent() {
            StorageEntity e = entity("TEXT");
            e.setDataText("X".repeat(5000));
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "get", "file_id", e.getId().toString()), ctx(TENANT, ORG)));

            assertThat(out).containsKeys("file_id", "name", "mime_type", "size_bytes", "preview", "NEXT");
            assertThat(out).containsEntry("viewable", "text");
            assertThat((String) out.get("preview")).hasSizeLessThan(600).endsWith("…");
            assertThat(out).doesNotContainKey("content");
        }

        @Test
        @DisplayName("file_id is required")
        void getRequiresFileId() {
            ToolExecutionResult r = provider.execute("files", Map.of("action", "get"), ctx(TENANT, ORG));
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }

        @Test
        @DisplayName("returns a JSON skeleton preview (not the full payload) for a JSON file")
        void getReturnsSkeletonForJsonFile() {
            StorageEntity e = entity("JSON");
            e.setData("{\"a\":1,\"b\":2}");
            e.setStructureSkeleton("{\"a\":\"number\",\"b\":\"number\"}");
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "get", "file_id", e.getId().toString()), ctx(TENANT, ORG)));

            assertThat(out).containsEntry("viewable", "text");
            assertThat(out).containsKey("preview_skeleton");
            assertThat(out).doesNotContainKey("content");
        }

        @Test
        @DisplayName("returns NOT_FOUND for a non-file storage row (no file name - e.g. a step output)")
        void getReturnsNotFoundForNonFileRow() {
            StorageEntity e = entity("JSON");
            e.setFileName(null);
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            ToolExecutionResult r = provider.execute("files",
                    Map.of("action", "get", "file_id", e.getId().toString()), ctx(TENANT, ORG));

            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
        }

        @Test
        @DisplayName("includes a FileRef (ref) for an object-storage file so it can be wired into a workflow")
        void getIncludesFileRefForObjectStorageFile() {
            StorageEntity e = entity("S3_FILE");
            e.setS3Key("tenant/wf/run/step/report.pdf");
            e.setMimeType("application/pdf");
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "get", "file_id", e.getId().toString()), ctx(TENANT, ORG)));

            @SuppressWarnings("unchecked")
            Map<String, Object> ref = (Map<String, Object>) out.get("ref");
            assertThat(ref).isNotNull();
            assertThat(ref).containsEntry("_type", "file")
                    .containsEntry("path", "tenant/wf/run/step/report.pdf")
                    .containsEntry("mimeType", "application/pdf")
                    .containsKeys("name", "size");
            // get surfaces the opaque url (top-level + mirrored on the ref); `path` stays the s3 key.
            assertThat((String) out.get("url"))
                    .isEqualTo(BASE + "/api/proxy/files/by-id/" + e.getId() + "/raw?disposition=inline");
            assertThat((String) ref.get("url")).isEqualTo(out.get("url"));
        }

        @Test
        @DisplayName("includes ref whenever a storage path exists, regardless of storage type (guard is on key, not type)")
        void getIncludesRefForKeyedNonS3Type() {
            StorageEntity e = entity("JSON");
            e.setS3Key("tenant/wf/run/step/data.json");
            e.setStructureSkeleton("{\"a\":\"number\"}");
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "get", "file_id", e.getId().toString()), ctx(TENANT, ORG)));

            @SuppressWarnings("unchecked")
            Map<String, Object> ref = (Map<String, Object>) out.get("ref");
            assertThat(ref).isNotNull();
            assertThat(ref).containsEntry("_type", "file").containsEntry("path", "tenant/wf/run/step/data.json");
        }

        @Test
        @DisplayName("omits ref for an inline file (no storage path - cannot be wired)")
        void getOmitsRefForInlineFile() {
            StorageEntity e = entity("TEXT");
            e.setDataText("hi");
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "get", "file_id", e.getId().toString()), ctx(TENANT, ORG)));

            assertThat(out).doesNotContainKey("ref");
        }

        @Test
        @DisplayName("viewable='text' for an object-storage DOCUMENT (view will extract its text)")
        void getViewableTextForDocument() {
            StorageEntity e = entity("S3_FILE");
            e.setFileName("thesis.pdf");
            e.setMimeType("application/pdf");
            e.setS3Key("tenant/wf/run/thesis.pdf");
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "get", "file_id", e.getId().toString()), ctx(TENANT, ORG)));

            assertThat(out).containsEntry("viewable", "text");
        }

        @Test
        @DisplayName("viewable='image' for an object-storage IMAGE (view inlines it for vision)")
        void getViewableImageForImage() {
            StorageEntity e = entity("S3_FILE");
            e.setFileName("shot.png");
            e.setMimeType("image/png");
            e.setS3Key("tenant/wf/run/shot.png");
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "get", "file_id", e.getId().toString()), ctx(TENANT, ORG)));

            assertThat(out).containsEntry("viewable", "image");
        }

        @Test
        @DisplayName("viewable='download' for an object-storage binary with no text/visual representation (audio)")
        void getViewableDownloadForAudio() {
            StorageEntity e = entity("S3_FILE");
            e.setFileName("song.mp3");
            e.setMimeType("audio/mpeg");
            e.setS3Key("tenant/wf/run/song.mp3");
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "get", "file_id", e.getId().toString()), ctx(TENANT, ORG)));

            assertThat(out).containsEntry("viewable", "download");
        }
    }

    // ==================== view (truncation + expand) ====================

    @Nested
    @DisplayName("view")
    class ViewAction {

        @Test
        @DisplayName("inlines short text verbatim with truncated=false and no next")
        void viewInlinesShortText() {
            StorageEntity e = entity("TEXT");
            e.setDataText("hello world");
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "view", "file_id", e.getId().toString()), ctx(TENANT, ORG)));

            assertThat(out).containsEntry("content", "hello world");
            assertThat(out).containsEntry("truncated", false);
            assertThat(out).containsEntry("original_length", 11);
            assertThat(out).doesNotContainKey("NEXT");
        }

        @Test
        @DisplayName("returns NOT_FOUND for a restricted file_id without reading content")
        void viewRestrictedFileIdReturnsNotFound() {
            UUID id = UUID.randomUUID();
            when(orgAccessGuard.canAccess(ORG, TENANT, "file", id.toString(), "MEMBER")).thenReturn(false);

            ToolExecutionResult r = provider.execute("files",
                    Map.of("action", "view", "file_id", id.toString()), ctx(TENANT, ORG, "MEMBER"));

            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
            verify(storageService, never()).getEntityByIdForScope(id, TENANT, ORG);
        }

        @Test
        @DisplayName("inlines JSON content from the data column")
        void viewInlinesJsonContent() {
            StorageEntity e = entity("JSON");
            e.setData("{\"k\":\"v\"}");
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "view", "file_id", e.getId().toString()), ctx(TENANT, ORG)));

            assertThat(out).containsEntry("content", "{\"k\":\"v\"}");
            assertThat(out).containsEntry("truncated", false);
        }

        @Test
        @DisplayName("truncates long text at max_bytes and offers an offset-based expand (next)")
        void viewTruncatesLongTextAndOffersExpand() {
            StorageEntity e = entity("TEXT");
            e.setDataText("0123456789ABCDEFGHIJ"); // length 20
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "view", "file_id", e.getId().toString(), "max_bytes", 10), ctx(TENANT, ORG)));

            assertThat(out).containsEntry("content", "0123456789");
            assertThat(out).containsEntry("returned_bytes", 10);
            assertThat(out).containsEntry("original_length", 20);
            assertThat(out).containsEntry("truncated", true);
            assertThat((String) out.get("NEXT")).contains("offset=10");
        }

        @Test
        @DisplayName("expands from a byte offset to read the next window")
        void viewExpandsFromOffset() {
            StorageEntity e = entity("TEXT");
            e.setDataText("0123456789ABCDEFGHIJ"); // length 20
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "view", "file_id", e.getId().toString(), "offset", 10, "max_bytes", 10),
                    ctx(TENANT, ORG)));

            assertThat(out).containsEntry("content", "ABCDEFGHIJ");
            assertThat(out).containsEntry("offset", 10);
            assertThat(out).containsEntry("truncated", false);
        }

        @Test
        @DisplayName("returns an opaque absolute url for an S3 file (no presigned link), never inline bytes")
        void viewReturnsOpaqueUrlForS3File() {
            StorageEntity e = entity("S3_FILE");
            e.setS3Key("tenant/wf/run/step/report.pdf");
            e.setMimeType("application/pdf");
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "view", "file_id", e.getId().toString()), ctx(TENANT, ORG)));

            assertThat((String) out.get("url"))
                    .isEqualTo(BASE + "/api/proxy/files/by-id/" + e.getId() + "/raw?disposition=inline");
            assertThat(out).doesNotContainKeys("content", "download_url");
        }

        @Test
        @DisplayName("the opaque url never contains the raw s3 key or the tenant id (no leak)")
        void viewUrlNeverLeaksKeyOrTenant() {
            StorageEntity e = entity("S3_FILE");
            e.setS3Key("user-1/wf/run/step/secret-key.png");
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "view", "file_id", e.getId().toString()), ctx(TENANT, ORG)));

            String url = (String) out.get("url");
            assertThat(url).isNotNull();
            assertThat(url).doesNotContain("secret-key.png");   // no s3 key
            assertThat(url).doesNotContain("user-1/");          // no tenant prefix
            assertThat(url).contains(e.getId().toString());     // opaque row UUID instead
        }

        @Test
        @DisplayName("does not inline raw bytes for inline binary content")
        void viewDoesNotInlineBinary() {
            StorageEntity e = entity("BINARY");
            e.setS3Key(null);
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "view", "file_id", e.getId().toString()), ctx(TENANT, ORG)));

            assertThat(out).containsKey("note");
            assertThat(out).doesNotContainKeys("content", "download_url");
        }

        @Test
        @DisplayName("includes a FileRef (ref) with the s3 key in path and the opaque url in ref.url")
        void viewIncludesFileRefForObjectStorageFile() {
            StorageEntity e = entity("S3_FILE");
            e.setS3Key("tenant/wf/run/step/report.pdf");
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "view", "file_id", e.getId().toString()), ctx(TENANT, ORG)));

            @SuppressWarnings("unchecked")
            Map<String, Object> ref = (Map<String, Object>) out.get("ref");
            assertThat(ref).containsEntry("_type", "file").containsEntry("path", "tenant/wf/run/step/report.pdf");
            assertThat((String) ref.get("url")).contains("/api/proxy/files/by-id/" + e.getId() + "/raw");
            assertThat((String) out.get("url")).contains("/api/proxy/files/by-id/" + e.getId() + "/raw");
            assertThat(out).doesNotContainKey("download_url");
        }

        @Test
        @DisplayName("inline binary view emits a click-to-open card marker so the user can still see it")
        void viewBinaryPointsToVisualize() {
            StorageEntity e = entity("BINARY");
            e.setS3Key(null);
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "view", "file_id", e.getId().toString()), ctx(TENANT, ORG)));

            assertThat(out).containsEntry("marker", "[visualize:file:" + e.getId() + "]");
            assertThat(out).containsKey("note");
            assertThat(out).doesNotContainKeys("content", "download_url", "ref");
        }

        @Test
        @DisplayName("EVERY view emits the [visualize:file:<id>] card marker so the user always sees the file")
        void viewAlwaysEmitsVisualizeMarker() {
            // text file → content inlined for the agent AND a card for the user
            StorageEntity text = entity("TEXT");
            text.setDataText("hello");
            when(storageService.getEntityByIdForScope(text.getId(), TENANT, ORG)).thenReturn(Optional.of(text));
            Map<String, Object> textOut = data(provider.execute("files",
                    Map.of("action", "view", "file_id", text.getId().toString()), ctx(TENANT, ORG)));
            assertThat(textOut).containsEntry("content", "hello");
            assertThat(textOut).containsEntry("marker", "[visualize:file:" + text.getId() + "]");
            assertThat(textOut).containsKey("show_to_user");

            // S3 file → opaque url AND a card for the user
            StorageEntity s3 = entity("S3_FILE");
            s3.setS3Key("tenant/wf/run/step/report.pdf");
            s3.setMimeType("application/pdf");
            when(storageService.getEntityByIdForScope(s3.getId(), TENANT, ORG)).thenReturn(Optional.of(s3));
            Map<String, Object> s3Out = data(provider.execute("files",
                    Map.of("action", "view", "file_id", s3.getId().toString()), ctx(TENANT, ORG)));
            assertThat(s3Out).containsKey("url");
            assertThat(s3Out).containsEntry("marker", "[visualize:file:" + s3.getId() + "]");
        }
    }

    // ==================== visualize ====================

    @Nested
    @DisplayName("visualize")
    class VisualizeAction {

        @Test
        @DisplayName("emits a [visualize:file:<id>] marker + metadata for a real file (raw s3 key never leaked)")
        void visualizeEmitsFileMarker() {
            StorageEntity e = entity("S3_FILE");
            e.setS3Key("tenant/secret-bucket/leak-key.bin");
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "visualize", "file_id", e.getId().toString()), ctx(TENANT, ORG)));

            assertThat(out).containsEntry("marker", "[visualize:file:" + e.getId() + "]");
            assertThat(out).containsKeys("file_id", "name", "mime_type", "message");
            assertThat(out).doesNotContainKeys("s3_key", "s3Key", "download_url", "content");
            assertThat(out.toString()).doesNotContain("secret-bucket");
        }

        @Test
        @DisplayName("file_id is required")
        void visualizeRequiresFileId() {
            ToolExecutionResult r = provider.execute("files", Map.of("action", "visualize"), ctx(TENANT, ORG));
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.MISSING_PARAMETER);
        }

        @Test
        @DisplayName("returns NOT_FOUND for an unknown or cross-organization file_id")
        void visualizeCrossOrgReturnsNotFound() {
            UUID id = UUID.randomUUID();
            when(storageService.getEntityByIdForScope(id, TENANT, ORG)).thenReturn(Optional.empty());

            ToolExecutionResult r = provider.execute("files",
                    Map.of("action", "visualize", "file_id", id.toString()), ctx(TENANT, ORG));

            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
        }

        @Test
        @DisplayName("returns NOT_FOUND for a restricted file_id without reading the storage row")
        void visualizeRestrictedFileIdReturnsNotFound() {
            UUID id = UUID.randomUUID();
            when(orgAccessGuard.canAccess(ORG, TENANT, "file", id.toString(), "MEMBER")).thenReturn(false);

            ToolExecutionResult r = provider.execute("files",
                    Map.of("action", "visualize", "file_id", id.toString()), ctx(TENANT, ORG, "MEMBER"));

            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
            verify(storageService, never()).getEntityByIdForScope(id, TENANT, ORG);
        }

        @Test
        @DisplayName("returns NOT_FOUND for a non-file storage row (no file name - e.g. a step output)")
        void visualizeReturnsNotFoundForNonFileRow() {
            StorageEntity e = entity("JSON");
            e.setFileName(null);
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            ToolExecutionResult r = provider.execute("files",
                    Map.of("action", "visualize", "file_id", e.getId().toString()), ctx(TENANT, ORG));

            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    // ==================== agent file allow-list (toolsConfig.files) ====================

    @Nested
    @DisplayName("agent file scope (allowedFileIds)")
    class AgentFileScope {

        @Test
        @DisplayName("get returns NOT_FOUND for a file outside the agent's allow-list (no storage read)")
        void getOutsideAllowListReturnsNotFound() {
            UUID allowed = UUID.randomUUID();
            UUID other = UUID.randomUUID();

            ToolExecutionResult r = provider.execute("files",
                    Map.of("action", "get", "file_id", other.toString()),
                    ctxAllowed(TENANT, ORG, "MEMBER", List.of(allowed.toString())));

            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.RESOURCE_NOT_FOUND);
            verify(storageService, never()).getEntityByIdForScope(other, TENANT, ORG);
        }

        @Test
        @DisplayName("get succeeds for a file inside the agent's allow-list")
        void getInsideAllowListSucceeds() {
            StorageEntity e = entity("TEXT");
            e.setDataText("hello");
            when(storageService.getEntityByIdForScope(e.getId(), TENANT, ORG)).thenReturn(Optional.of(e));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "get", "file_id", e.getId().toString()),
                    ctxAllowed(TENANT, ORG, "MEMBER", List.of(e.getId().toString()))));

            assertThat(out).containsEntry("file_id", e.getId().toString());
        }

        @Test
        @DisplayName("list returns ONLY the allow-listed files (resolved by id), not the whole workspace")
        void listScopedToAllowList() {
            StorageEntity e = entity("TEXT");
            when(storageService.getEntityByIdForScope(e.getId(), TENANT, ORG)).thenReturn(Optional.of(e));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "list"),
                    ctxAllowed(TENANT, ORG, "MEMBER", List.of(e.getId().toString()))));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) out.get("files");
            assertThat(items).hasSize(1);
            assertThat(items.get(0)).containsEntry("file_id", e.getId().toString());
            // The whole-workspace slice search is bypassed when an allow-list is present.
            verify(explorerService, never()).searchFilesSlice(any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), anyBoolean(), any(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("an EMPTY allow-list is unrestricted (opt-in), NOT deny-all - get reaches the file")
        void emptyAllowListIsUnrestricted() {
            StorageEntity e = entity("TEXT");
            e.setDataText("hello");
            when(storageService.getEntityByIdForScope(e.getId(), TENANT, ORG)).thenReturn(Optional.of(e));

            // allowedFileIds=[] must behave like "no allow-list" (full org access) - the inverse
            // of the 5 internal resources where [] means deny-all. Only a NON-empty list scopes.
            // This is the contract the sub-agent / workflow credential paths rely on.
            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "get", "file_id", e.getId().toString()),
                    ctxAllowed(TENANT, ORG, "MEMBER", List.of())));

            assertThat(out).containsEntry("file_id", e.getId().toString());
        }
    }

    // ==================== scope / routing / help ====================

    @Nested
    @DisplayName("scope, routing & help")
    class ScopeRoutingHelp {

        @Test
        @DisplayName("data actions require an active organization scope")
        void dataActionsRequireOrgScope() {
            ToolExecutionResult r = provider.execute("files", Map.of("action", "list"), ctx(TENANT, "  "));

            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.AUTHENTICATION_REQUIRED);
            verifyNoInteractions(explorerService, storageService);
        }

        @Test
        @DisplayName("rejects an unknown action")
        void rejectsUnknownAction() {
            ToolExecutionResult r = provider.execute("files", Map.of("action", "frobnicate"), ctx(TENANT, ORG));
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
        }

        @Test
        @DisplayName("rejects an unknown tool name")
        void rejectsUnknownToolName() {
            ToolExecutionResult r = provider.execute("not_files", Map.of("action", "list"), ctx(TENANT, ORG));
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.TOOL_NOT_FOUND);
        }

        @Test
        @DisplayName("help is free, needs no scope, and documents every action + the get_run boundary")
        void helpIsConciseAndComplete() {
            // No org scope on purpose - help must work for a stateless agent before any scope is set.
            Map<String, Object> out = data(provider.execute("files", Map.of("action", "help"), ctx(null, null)));

            assertThat((String) out.get("description")).contains("get_run");
            @SuppressWarnings("unchecked")
            Map<String, Object> actions = (Map<String, Object>) out.get("actions");
            assertThat(actions).containsKeys("list", "get", "view", "visualize", "create_folder", "move_to_folder", "help");
            verifyNoInteractions(explorerService, storageService);
        }

        @Test
        @DisplayName("help documents the folder param on list + the create_folder/move_to_folder actions + the folders concept")
        void helpDocumentsFolderActionsAndConcept() {
            Map<String, Object> out = data(provider.execute("files", Map.of("action", "help"), ctx(null, null)));

            @SuppressWarnings("unchecked")
            Map<String, Object> actions = (Map<String, Object>) out.get("actions");
            // list now documents the `folder` navigation param + folder_ref / folder_kind response fields.
            assertThat(actions.get("list").toString()).contains("folder").contains("folder_ref").contains("folder_kind");
            // create_folder documents name (max 100) + the folder_id it returns.
            assertThat(actions.get("create_folder").toString()).contains("name").contains("folder_id").contains("100");
            // move_to_folder documents file_ids + moved_count + failed[].reason.
            assertThat(actions.get("move_to_folder").toString())
                    .contains("file_ids").contains("moved_count").contains("failed").contains("reason");

            @SuppressWarnings("unchecked")
            Map<String, Object> concepts = (Map<String, Object>) out.get("concepts");
            assertThat(concepts).containsKey("folders");
            assertThat(concepts.get("folders").toString()).contains("wf:").contains("manual").contains("move");
        }

        @Test
        @DisplayName("help explains how to FIND a file (search by name vs browse) and that a workflow folder "
                + "only appears when a real file exists (never empty)")
        void helpExplainsFindingAndNonEmptyWorkflowFolders() {
            Map<String, Object> out = data(provider.execute("files", Map.of("action", "help"), ctx(null, null)));

            @SuppressWarnings("unchecked")
            Map<String, Object> concepts = (Map<String, Object>) out.get("concepts");
            // A dedicated "how to find a file" recipe: search by name (flat) vs browse the tree, then open.
            assertThat(concepts).containsKey("finding_a_file");
            String finding = concepts.get("finding_a_file").toString();
            assertThat(finding).contains("query").contains("view").contains("flat");
            // The folders concept now states a workflow folder appears only when a real file exists.
            String folders = concepts.get("folders").toString();
            assertThat(folders).contains("appears ONLY when").contains("never open an empty");

            @SuppressWarnings("unchecked")
            Map<String, Object> examples = (Map<String, Object>) out.get("examples");
            assertThat(examples).containsKey("find_then_open");
        }

        @Test
        @DisplayName("concepts pointer chain resolves at BOTH ends: finding_a_file -> reading_content -> show_to_user keep their deferred content")
        void conceptsPointerChainResolvesBothEnds() {
            // 2026-07 dedup: finding_a_file no longer restates the view type-trichotomy and
            // reading_content no longer restates the visualize path - each points at the
            // concept that owns the rule. Guard both ends so a later trim of an authority
            // cannot silently dangle the pointer.
            Map<String, Object> out = data(provider.execute("files", Map.of("action", "help"), ctx(null, null)));

            @SuppressWarnings("unchecked")
            Map<String, Object> concepts = (Map<String, Object>) out.get("concepts");
            assertThat(concepts.get("finding_a_file").toString()).contains("reading_content");
            String reading = concepts.get("reading_content").toString();
            assertThat(reading)
                    .as("authority for the view type-trichotomy")
                    .contains("extracted to")
                    .contains("SEE")
                    .contains("url")
                    .contains("show_to_user");
            assertThat(concepts.get("show_to_user").toString())
                    .as("authority for making a file visible to the user")
                    .contains("marker")
                    .contains("visualize");
        }

        @Test
        @DisplayName("exposes a single 'files' tool under the UTILITY category with its declared actions")
        void getToolsExposesSingleFilesTool() {
            List<AgentToolDefinition> tools = provider.getTools();
            assertThat(tools).hasSize(1);
            AgentToolDefinition def = tools.get(0);
            assertThat(def.name()).isEqualTo("files");
            assertThat(def.category()).isEqualTo(ToolCategory.UTILITY);
            assertThat(provider.getCategory()).isEqualTo(ToolCategory.UTILITY);
            assertThat(def.requiredParameters()).contains("action");
        }
    }

    // ==================== create_folder ====================

    @Nested
    @DisplayName("create_folder")
    class CreateFolder {

        @Test
        @DisplayName("creates a top-level manual folder and returns folder_id + name + parent='root'")
        void createsTopLevelFolder() {
            StorageEntity folder = new StorageEntity();
            UUID id = UUID.randomUUID();
            folder.setId(id);
            folder.setFileName("Invoices");
            when(storageService.createFolderForScope(eq(TENANT), eq(ORG), eq("Invoices"), isNull()))
                    .thenReturn(folder);

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "create_folder", "name", "Invoices"), ctx(TENANT, ORG)));

            assertThat(out).containsEntry("status", "OK")
                    .containsEntry("folder_id", id.toString())
                    .containsEntry("name", "Invoices")
                    .containsEntry("parent", "root");
            assertThat((String) out.get("hint")).contains(id.toString());
        }

        @Test
        @DisplayName("nests under a manual parent folder when folder is a UUID")
        void nestsUnderManualParent() {
            UUID parent = UUID.randomUUID();
            StorageEntity folder = new StorageEntity();
            folder.setId(UUID.randomUUID());
            folder.setFileName("Q1");
            when(storageService.createFolderForScope(eq(TENANT), eq(ORG), eq("Q1"), eq(parent)))
                    .thenReturn(folder);

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "create_folder", "name", "Q1", "folder", parent.toString()), ctx(TENANT, ORG)));

            assertThat(out).containsEntry("parent", parent.toString());
            verify(storageService).createFolderForScope(TENANT, ORG, "Q1", parent);
        }

        @Test
        @DisplayName("blank name → VALIDATION_ERROR, no service call")
        void blankNameRejected() {
            ToolExecutionResult r = provider.execute("files",
                    Map.of("action", "create_folder", "name", "   "), ctx(TENANT, ORG));

            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(r.error()).contains("name is required");
            verifyNoInteractions(storageService);
        }

        @Test
        @DisplayName("a workflow-folder parent (wf:...) → VALIDATION_ERROR, no service call")
        void virtualTokenParentRejected() {
            ToolExecutionResult r = provider.execute("files",
                    Map.of("action", "create_folder", "name", "X", "folder", "wf:abc/e0"), ctx(TENANT, ORG));

            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(r.error()).contains("workflow folder");
            verifyNoInteractions(storageService);
        }

        @Test
        @DisplayName("a non-UUID, non-'root' parent → VALIDATION_ERROR")
        void badParentTokenRejected() {
            ToolExecutionResult r = provider.execute("files",
                    Map.of("action", "create_folder", "name", "X", "folder", "not-a-uuid"), ctx(TENANT, ORG));

            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(r.error()).contains("folder id or 'root'");
            verifyNoInteractions(storageService);
        }

        @Test
        @DisplayName("a backend IllegalArgumentException is surfaced as VALIDATION_ERROR with its message")
        void backendValidationSurfaced() {
            when(storageService.createFolderForScope(any(), any(), any(), any()))
                    .thenThrow(new IllegalArgumentException("parentFolderId is not a folder in this workspace"));

            ToolExecutionResult r = provider.execute("files",
                    Map.of("action", "create_folder", "name", "X", "folder", UUID.randomUUID().toString()),
                    ctx(TENANT, ORG));

            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(r.error()).contains("not a folder in this workspace");
        }
    }

    // ==================== move_to_folder ====================

    @Nested
    @DisplayName("move_to_folder")
    class MoveToFolder {

        @Test
        @DisplayName("moves valid ids into a target folder: moved_count + parsed UUIDs + target passed to the service")
        void movesIntoFolder() {
            UUID target = UUID.randomUUID();
            UUID f1 = UUID.randomUUID();
            UUID f2 = UUID.randomUUID();
            when(storageService.moveEntriesForScope(eq(ORG), any(), eq(target)))
                    .thenReturn(new StorageService.MoveResult(2, List.of()));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "move_to_folder",
                            "file_ids", List.of(f1.toString(), f2.toString()),
                            "folder", target.toString()),
                    ctx(TENANT, ORG)));

            assertThat(out).containsEntry("status", "OK").containsEntry("moved_count", 2);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> failed = (List<Map<String, Object>>) out.get("failed");
            assertThat(failed).isEmpty();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
            verify(storageService).moveEntriesForScope(eq(ORG), ids.capture(), eq(target));
            assertThat(ids.getValue()).containsExactlyInAnyOrder(f1, f2);
        }

        @Test
        @DisplayName("folder='root' (and omitted) moves to the top level - null target to the service")
        void movesToRoot() {
            UUID f1 = UUID.randomUUID();
            when(storageService.moveEntriesForScope(eq(ORG), any(), isNull()))
                    .thenReturn(new StorageService.MoveResult(1, List.of()));

            data(provider.execute("files",
                    Map.of("action", "move_to_folder", "file_ids", List.of(f1.toString()), "folder", "root"),
                    ctx(TENANT, ORG)));

            verify(storageService).moveEntriesForScope(eq(ORG), any(), isNull());
        }

        @Test
        @DisplayName("a bad id is reported in failed[] and excluded from the service call")
        void badIdReportedInFailed() {
            UUID good = UUID.randomUUID();
            UUID target = UUID.randomUUID();
            when(storageService.moveEntriesForScope(eq(ORG), any(), eq(target)))
                    .thenReturn(new StorageService.MoveResult(1, List.of()));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "move_to_folder",
                            "file_ids", List.of(good.toString(), "not-a-uuid"),
                            "folder", target.toString()),
                    ctx(TENANT, ORG)));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> failed = (List<Map<String, Object>>) out.get("failed");
            assertThat(failed).hasSize(1);
            assertThat(failed.get(0)).containsEntry("file_id", "not-a-uuid").containsEntry("reason", "not a valid id");

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
            verify(storageService).moveEntriesForScope(eq(ORG), ids.capture(), eq(target));
            assertThat(ids.getValue()).containsExactly(good); // bad id never reaches the service
        }

        @Test
        @DisplayName("the service's per-id MoveResult.failed is merged into the response failed[]")
        void backendFailedMerged() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            UUID target = UUID.randomUUID();
            when(storageService.moveEntriesForScope(eq(ORG), any(), eq(target)))
                    .thenReturn(new StorageService.MoveResult(1,
                            List.of(new StorageService.MoveFailure(b, "cannot move a folder into its own descendant"))));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "move_to_folder",
                            "file_ids", List.of(a.toString(), b.toString()),
                            "folder", target.toString()),
                    ctx(TENANT, ORG)));

            assertThat(out).containsEntry("moved_count", 1);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> failed = (List<Map<String, Object>>) out.get("failed");
            assertThat(failed).hasSize(1);
            assertThat(failed.get(0)).containsEntry("file_id", b.toString())
                    .containsEntry("reason", "cannot move a folder into its own descendant");
        }

        @Test
        @DisplayName("empty file_ids → VALIDATION_ERROR, no service call")
        void emptyFileIdsRejected() {
            ToolExecutionResult r = provider.execute("files",
                    Map.of("action", "move_to_folder", "file_ids", List.of()), ctx(TENANT, ORG));

            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(r.error()).contains("file_ids is required");
            verifyNoInteractions(storageService);
        }

        @Test
        @DisplayName("a workflow-folder target (wf:...) → VALIDATION_ERROR, no service call")
        void virtualTokenTargetRejected() {
            ToolExecutionResult r = provider.execute("files",
                    Map.of("action", "move_to_folder",
                            "file_ids", List.of(UUID.randomUUID().toString()),
                            "folder", "wf:abc/e0/s0"),
                    ctx(TENANT, ORG));

            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(r.error()).contains("workflow folder");
            verifyNoInteractions(storageService);
        }

        @Test
        @DisplayName("an id outside the agent allow-list is rejected as 'restricted' and never reaches the service")
        void outsideAllowListRejected() {
            UUID allowed = UUID.randomUUID();
            UUID other = UUID.randomUUID();
            UUID target = UUID.randomUUID();
            when(storageService.moveEntriesForScope(eq(ORG), any(), eq(target)))
                    .thenReturn(new StorageService.MoveResult(1, List.of()));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "move_to_folder",
                            "file_ids", List.of(allowed.toString(), other.toString()),
                            "folder", target.toString()),
                    ctxAllowed(TENANT, ORG, "MEMBER", List.of(allowed.toString()))));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> failed = (List<Map<String, Object>>) out.get("failed");
            assertThat(failed).anySatisfy(f -> {
                assertThat(f).containsEntry("file_id", other.toString()).containsEntry("reason", "restricted");
            });
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
            verify(storageService).moveEntriesForScope(eq(ORG), ids.capture(), eq(target));
            assertThat(ids.getValue()).containsExactly(allowed);
        }

        @Test
        @DisplayName("an id the org member may not WRITE is rejected 'restricted' and never reaches the service "
                + "(mirrors the UI move guard - no agent allow-list needed)")
        void orgWriteRestrictedRejected() {
            UUID writable = UUID.randomUUID();
            UUID restricted = UUID.randomUUID();
            UUID target = UUID.randomUUID();
            // No agent allow-list here: the gap was that org member write-restriction was skipped for
            // unrestricted agents, letting a member re-parent (and confirm the existence of) a file it
            // cannot write. canWrite=false ONLY for the restricted id (setUp defaults the rest to true).
            when(orgAccessGuard.canWrite(eq(ORG), any(), eq("file"), eq(restricted.toString()), any())).thenReturn(false);
            when(storageService.moveEntriesForScope(eq(ORG), any(), eq(target)))
                    .thenReturn(new StorageService.MoveResult(1, List.of()));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "move_to_folder",
                            "file_ids", List.of(writable.toString(), restricted.toString()),
                            "folder", target.toString()),
                    ctx(TENANT, ORG, "MEMBER")));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> failed = (List<Map<String, Object>>) out.get("failed");
            assertThat(failed).anySatisfy(f ->
                    assertThat(f).containsEntry("file_id", restricted.toString()).containsEntry("reason", "restricted"));
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
            verify(storageService).moveEntriesForScope(eq(ORG), ids.capture(), eq(target));
            assertThat(ids.getValue()).containsExactly(writable); // the write-restricted file never reaches the service
        }
    }

    // ==================== list (folder navigation) ====================

    @Nested
    @DisplayName("list (folder navigation)")
    class ListFolder {

        @Test
        @DisplayName("folder='root' calls searchVirtualScope(address=null) and returns folders[] + files[]")
        void rootListsVirtualScope() {
            UUID manualId = UUID.randomUUID();
            List<StorageExplorerDto> rows = List.of(
                    manualFolderDto(manualId, "Invoices", 3),
                    virtualFolderDto("wf:wf-1", "WORKFLOW", "11111111-1111-1111-1111-111111111111", null, null, null, 7),
                    dto(UUID.randomUUID(), "loose.pdf", "application/pdf"));
            Page<StorageExplorerDto> page = new PageImpl<>(rows, Pageable.unpaged(), 3);
            when(explorerService.searchVirtualScope(eq(TENANT), eq(ORG), isNull(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any())).thenReturn(page);
            when(workflowRepository.findIdNamePairs(any())).thenReturn(List.<Object[]>of(
                    new Object[]{UUID.fromString("11111111-1111-1111-1111-111111111111"), "My Workflow"}));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "list", "folder", "root"), ctx(TENANT, ORG)));

            assertThat(out).containsEntry("status", "OK").containsEntry("kind", "files").containsEntry("folder", "root");
            assertThat(out).containsEntry("total", 3L).containsEntry("count", 3);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> folders = (List<Map<String, Object>>) out.get("folders");
            assertThat(folders).hasSize(2);
            // Manual folder → folder_ref = id, folder_kind = manual, name = its file name.
            assertThat(folders.get(0)).containsEntry("folder_ref", manualId.toString())
                    .containsEntry("folder_kind", "manual").containsEntry("name", "Invoices").containsEntry("child_count", 3);
            // Virtual WORKFLOW folder → folder_ref = virtualId, folder_kind = workflow, name resolved via findIdNamePairs.
            assertThat(folders.get(1)).containsEntry("folder_ref", "wf:wf-1")
                    .containsEntry("folder_kind", "workflow").containsEntry("name", "My Workflow").containsEntry("child_count", 7);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files = (List<Map<String, Object>>) out.get("files");
            assertThat(files).hasSize(1);
            assertThat(files.get(0)).containsEntry("name", "loose.pdf").containsKey("file_id");
        }

        @Test
        @DisplayName("a blank/empty workflow name falls back to 'Workflow'")
        void workflowNameFallsBack() {
            List<StorageExplorerDto> rows = List.of(
                    virtualFolderDto("wf:wf-2", "WORKFLOW", "22222222-2222-2222-2222-222222222222", null, null, null, 1));
            when(explorerService.searchVirtualScope(any(), any(), isNull(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(rows, Pageable.unpaged(), 1));
            when(workflowRepository.findIdNamePairs(any())).thenReturn(List.of()); // unresolved

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "list", "folder", "root"), ctx(TENANT, ORG)));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> folders = (List<Map<String, Object>>) out.get("folders");
            assertThat(folders.get(0)).containsEntry("name", "Workflow");
        }

        @Test
        @DisplayName("a wf:<id> token calls searchVirtualScope with the parsed address")
        void workflowTokenParsesAddress() {
            when(explorerService.searchVirtualScope(any(), any(), any(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), Pageable.unpaged(), 0));

            data(provider.execute("files",
                    Map.of("action", "list", "folder", "wf:wf-9/e0"), ctx(TENANT, ORG)));

            ArgumentCaptor<VirtualFolderAddress> addr = ArgumentCaptor.forClass(VirtualFolderAddress.class);
            verify(explorerService).searchVirtualScope(eq(TENANT), eq(ORG), addr.capture(), any(), any(), any(),
                    eq(true), eq(true), any(), any(), any());
            assertThat(addr.getValue()).isNotNull();
            assertThat(addr.getValue().workflowId()).isEqualTo("wf-9");
            assertThat(addr.getValue().epoch()).isEqualTo(0);
        }

        @Test
        @DisplayName("virtual EPOCH/SPAWN/ITERATION folders get 1-based localized labels")
        void virtualFolderLabelsAreOneBased() {
            List<StorageExplorerDto> rows = List.of(
                    virtualFolderDto("wf:w/e0", "EPOCH", "w", 0, null, null, 1),
                    virtualFolderDto("wf:w/e0/s2", "SPAWN", "w", 0, 2, null, 1),
                    virtualFolderDto("wf:w/e0/s2/i4", "ITERATION", "w", 0, 2, 4, 1));
            when(explorerService.searchVirtualScope(any(), any(), any(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(rows, Pageable.unpaged(), 3));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "list", "folder", "wf:w/e0"), ctx(TENANT, ORG)));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> folders = (List<Map<String, Object>>) out.get("folders");
            assertThat(folders.get(0)).containsEntry("name", "Epoch 1").containsEntry("folder_kind", "epoch");
            assertThat(folders.get(1)).containsEntry("name", "Run 3").containsEntry("folder_kind", "spawn");
            assertThat(folders.get(2)).containsEntry("name", "Item 5").containsEntry("folder_kind", "iteration");
        }

        @Test
        @DisplayName("a manual-folder UUID calls searchFolderScope(parentFolderId=uuid) - not the virtual scope")
        void manualFolderUuidListsFolderScope() {
            UUID folderId = UUID.randomUUID();
            when(explorerService.searchFolderScope(any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(dto(UUID.randomUUID(), "in-folder.pdf", "application/pdf")),
                            Pageable.unpaged(), 1));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "list", "folder", folderId.toString()), ctx(TENANT, ORG)));

            assertThat(out).containsEntry("folder", folderId.toString());
            verify(explorerService).searchFolderScope(eq(TENANT), eq(ORG), eq(folderId), any(), any(), any(), any(),
                    any(), any(), any(), eq(true), eq(true), any(), any(), any());
            verify(explorerService, never()).searchVirtualScope(any(), any(), any(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any());
        }

        @Test
        @DisplayName("a folder token that is neither virtual nor a UUID → VALIDATION_ERROR")
        void badFolderTokenRejected() {
            ToolExecutionResult r = provider.execute("files",
                    Map.of("action", "list", "folder", "garbage"), ctx(TENANT, ORG));

            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.VALIDATION_ERROR);
            assertThat(r.error()).contains("folder must be 'root'");
            verifyNoInteractions(explorerService);
        }

        @Test
        @DisplayName("the flat list (no folder param) is unchanged - still uses searchFilesSlice")
        void flatListUnchangedWithoutFolder() {
            when(explorerService.searchFilesSlice(any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), anyBoolean(), any(), anyInt(), anyInt()))
                    .thenReturn(new FilesSlice(List.of(dto(UUID.randomUUID(), "flat.pdf", "application/pdf")), 1));

            Map<String, Object> out = data(provider.execute("files", Map.of("action", "list"), ctx(TENANT, ORG)));

            // Canonical flat envelope: a `files` array, no `folders` key.
            assertThat(out).containsKey("files").doesNotContainKey("folders");
            verify(explorerService).searchFilesSlice(any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), anyBoolean(), any(), anyInt(), anyInt());
            verify(explorerService, never()).searchVirtualScope(any(), any(), any(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any());
        }

        @Test
        @DisplayName("hasMore is true when offset+count < total")
        void hasMoreReflectsRemainingRows() {
            List<StorageExplorerDto> rows = new java.util.ArrayList<>();
            for (int i = 0; i < 25; i++) rows.add(dto(UUID.randomUUID(), "f" + i + ".pdf", "application/pdf"));
            when(explorerService.searchVirtualScope(any(), any(), isNull(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(rows, Pageable.unpaged(), 100));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "list", "folder", "root"), ctx(TENANT, ORG)));

            assertThat(out).containsEntry("count", 25).containsEntry("total", 100L).containsEntry("hasMore", true);
        }

        @Test
        @DisplayName("a scoped agent's allow-list drops out-of-list files; hasMore reflects the DB page, not the filtered count")
        void allowListDropsFilesAndKeepsPagingHonest() {
            UUID mine = UUID.randomUUID();
            UUID hidden = UUID.randomUUID();
            // The DB page returned BOTH rows (total 2); the agent allow-list permits only `mine`.
            when(explorerService.searchVirtualScope(any(), any(), isNull(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(dto(mine, "mine.pdf", "application/pdf"),
                            dto(hidden, "not-mine.pdf", "application/pdf")), Pageable.unpaged(), 2));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "list", "folder", "root"),
                    ctxAllowed(TENANT, ORG, "MEMBER", List.of(mine.toString()))));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files = (List<Map<String, Object>>) out.get("files");
            assertThat(files).hasSize(1);
            assertThat(files.get(0)).containsEntry("file_id", mine.toString()); // the out-of-list file is dropped
            // The DB page returned all rows → there is NO next page. hasMore must be false, not a phantom
            // true from the under-counted post-filter `count` (the bug this guards).
            assertThat(out).containsEntry("hasMore", false);
        }

        @Test
        @DisplayName("org member restrictions are threaded as excludedIds into the folder/virtual scope query")
        void orgRestrictionsThreadedAsExcludedIds() {
            UUID restricted = UUID.randomUUID();
            when(orgAccessGuard.getRestrictedResourceIds(eq(ORG), any(), eq("file"), any()))
                    .thenReturn(Set.of(restricted.toString()));
            when(explorerService.searchVirtualScope(any(), any(), isNull(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), Pageable.unpaged(), 0));

            data(provider.execute("files", Map.of("action", "list", "folder", "root"), ctx(TENANT, ORG, "MEMBER")));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<UUID>> excluded = ArgumentCaptor.forClass(Collection.class);
            verify(explorerService).searchVirtualScope(eq(TENANT), eq(ORG), isNull(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), excluded.capture(), any());
            assertThat(excluded.getValue()).containsExactly(restricted);
        }

        // ---- parent / back-navigation (so a context-free agent is never stranded) ----

        @Test
        @DisplayName("root listing has NO parent and no 'back to the top' hint (already at the top)")
        void rootListingHasNoParent() {
            when(explorerService.searchVirtualScope(any(), any(), isNull(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(dto(UUID.randomUUID(), "a.pdf", "application/pdf")), Pageable.unpaged(), 1));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "list", "folder", "root"), ctx(TENANT, ORG)));

            assertThat(out).doesNotContainKey("parent");
            assertThat((String) out.get("hint")).doesNotContain("Back to the top").doesNotContain("Go up one level");
        }

        @Test
        @DisplayName("a manual folder that doesn't resolve (absent / out of scope) defaults parent to 'root' (no NPE)")
        void manualFolderNotFoundParentIsRoot() {
            UUID folderId = UUID.randomUUID();
            // getEntityByIdForScope returns empty (folder absent or cross-org) → parent must default to root.
            when(storageService.getEntityByIdForScope(eq(folderId), any(), eq(ORG)))
                    .thenReturn(java.util.Optional.empty());
            when(explorerService.searchFolderScope(any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), Pageable.unpaged(), 0));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "list", "folder", folderId.toString()), ctx(TENANT, ORG)));

            assertThat(out).containsEntry("parent", "root");
        }

        @Test
        @DisplayName("an epoch listing exposes parent=the workflow folder + a 'go up' AND a 'back to the top' hint")
        void epochListingExposesParentAndUpHint() {
            when(explorerService.searchVirtualScope(any(), any(), any(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), Pageable.unpaged(), 0));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "list", "folder", "wf:wf-x/e0"), ctx(TENANT, ORG)));

            assertThat(out).containsEntry("parent", "wf:wf-x");
            String hint = (String) out.get("hint");
            assertThat(hint).contains("Go up one level: files(action='list', folder='wf:wf-x')")
                    .contains("Back to the top: files(action='list', folder='root')");
        }

        @Test
        @DisplayName("a workflow listing has parent='root' (one level up IS the top - no separate 'go up')")
        void workflowListingParentIsRoot() {
            when(explorerService.searchVirtualScope(any(), any(), any(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), Pageable.unpaged(), 0));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "list", "folder", "wf:wf-x"), ctx(TENANT, ORG)));

            assertThat(out).containsEntry("parent", "root");
            String hint = (String) out.get("hint");
            assertThat(hint).contains("Back to the top").doesNotContain("Go up one level");
        }

        @Test
        @DisplayName("a nested manual folder's parent is its own parent_folder_id")
        void manualFolderParentFromEntity() {
            UUID folderId = UUID.randomUUID();
            UUID parentFolderId = UUID.randomUUID();
            StorageEntity folder = new StorageEntity();
            folder.setId(folderId);
            folder.setIsFolder(true);
            folder.setParentFolderId(parentFolderId);
            when(storageService.getEntityByIdForScope(eq(folderId), any(), eq(ORG)))
                    .thenReturn(java.util.Optional.of(folder));
            when(explorerService.searchFolderScope(any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), anyBoolean(), anyBoolean(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), Pageable.unpaged(), 0));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "list", "folder", folderId.toString()), ctx(TENANT, ORG)));

            assertThat(out).containsEntry("parent", parentFolderId.toString());
        }

        @Test
        @DisplayName("an empty folder listing tells the agent the folder is empty")
        void emptyFolderHintSaysEmpty() {
            when(explorerService.searchVirtualScope(any(), any(), any(), any(), any(), any(),
                    anyBoolean(), anyBoolean(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(), Pageable.unpaged(), 0));

            Map<String, Object> out = data(provider.execute("files",
                    Map.of("action", "list", "folder", "wf:wf-x/e0"), ctx(TENANT, ORG)));

            assertThat((String) out.get("hint")).startsWith("This folder is empty.");
        }
    }

    // ==================== view - vision channel (image media) ====================

    @Nested
    @DisplayName("view - vision channel (inlines image bytes for vision-capable models)")
    class ViewVisionChannel {

        private FileStorageService fileStorageService;
        private FilesToolsProvider visionProvider;

        @BeforeEach
        void setUpVision() {
            fileStorageService = mock(FileStorageService.class);
            // 1 KB inline cap so the "too large" path is exercisable without huge fixtures.
            visionProvider = new FilesToolsProvider(explorerService, storageService,
                    new PublicFileUrlBuilder(BASE), orgAccessGuard, workflowRepository, fileStorageService, 1024);
        }

        private StorageEntity image(String storageType, String mime) {
            StorageEntity e = new StorageEntity();
            e.setId(UUID.randomUUID());
            e.setStorageType(storageType);
            e.setFileName("shot.png");
            e.setMimeType(mime);
            e.setContentType(mime);
            e.setCreatedAt(Instant.parse("2026-05-29T10:00:00Z"));
            return e;
        }

        @SuppressWarnings("unchecked")
        private List<Map<String, Object>> media(ToolExecutionResult r) {
            assertThat(r.success()).isTrue();
            assertThat(r.metadata()).isNotNull();
            return (List<Map<String, Object>>) r.metadata().get(ToolMediaMetadata.MEDIA_KEY);
        }

        @Test
        @DisplayName("S3 image within the cap is inlined: __media__ carries the base64 + the 'vision' note says it is SEEable")
        void s3ImageInlinedAsVisionBlock() {
            StorageEntity e = image("S3_FILE", "image/png");
            e.setS3Key("tenant/wf/run/step/shot.png");
            e.setSizeBytes(4);
            byte[] bytes = {1, 2, 3, 4};
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));
            when(fileStorageService.download(TENANT, "tenant/wf/run/step/shot.png")).thenReturn(Optional.of(bytes));

            ToolExecutionResult r = visionProvider.execute("files",
                    Map.of("action", "view", "file_id", e.getId().toString()), ctx(TENANT, ORG));

            List<Map<String, Object>> media = media(r);
            assertThat(media).hasSize(1);
            assertThat(media.get(0))
                    .containsEntry(ToolMediaMetadata.TYPE, "image")
                    .containsEntry(ToolMediaMetadata.MIME_TYPE, "image/png")
                    .containsEntry(ToolMediaMetadata.DATA_BASE64,
                            java.util.Base64.getEncoder().encodeToString(bytes));
            assertThat((String) data(r).get("vision")).contains("SEE it directly");
        }

        @Test
        @DisplayName("S3 download uses the KEY-OWNER tenant (e.getTenantId()), not the caller's context tenant - storage-service 403 regression")
        void s3DownloadUsesKeyOwnerTenantNotContextTenant() {
            // Reproduces the prod bug: the file is owned by tenant "1" (key prefix "1/..."),
            // but the agent's context tenant is "user-1". storage-service refuses the internal
            // download with 403 unless X-User-ID == the key's tenant prefix. The fix downloads
            // under e.getTenantId() (the owner), so it must NOT be the context TENANT here.
            StorageEntity e = image("S3_FILE", "image/png");
            e.setTenantId("1");                          // owner tenant (matches key prefix)
            e.setS3Key("1/wf/run/step/shot.png");
            e.setSizeBytes(4);
            byte[] bytes = {1, 2, 3, 4};
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));
            // Owner-tenant download succeeds; context-tenant download (the buggy path) returns empty.
            when(fileStorageService.download("1", "1/wf/run/step/shot.png")).thenReturn(Optional.of(bytes));
            when(fileStorageService.download(TENANT, "1/wf/run/step/shot.png")).thenReturn(Optional.empty());

            ToolExecutionResult r = visionProvider.execute("files",
                    Map.of("action", "view", "file_id", e.getId().toString()), ctx(TENANT, ORG));

            // Pre-fix this is empty (context tenant used) → no media; post-fix the owner tenant is used.
            List<Map<String, Object>> media = media(r);
            assertThat(media).hasSize(1);
            assertThat(media.get(0)).containsEntry(ToolMediaMetadata.DATA_BASE64,
                    java.util.Base64.getEncoder().encodeToString(bytes));
            verify(fileStorageService).download(eq("1"), eq("1/wf/run/step/shot.png"));
            verify(fileStorageService, never()).download(eq(TENANT), anyString());
        }

        @Test
        @DisplayName("inline BINARY image inlines the row's own bytes (no object-storage download)")
        void inlineBinaryImageInlinedFromRowBytes() {
            StorageEntity e = image("BINARY", "image/jpeg");
            e.setS3Key(null);
            byte[] bytes = {9, 8, 7};
            e.setDataBinary(bytes);
            e.setSizeBytes(3);
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            ToolExecutionResult r = visionProvider.execute("files",
                    Map.of("action", "view", "file_id", e.getId().toString()), ctx(TENANT, ORG));

            List<Map<String, Object>> media = media(r);
            assertThat(media).hasSize(1);
            assertThat(media.get(0)).containsEntry(ToolMediaMetadata.DATA_BASE64,
                    java.util.Base64.getEncoder().encodeToString(bytes));
            verifyNoInteractions(fileStorageService);
        }

        @Test
        @DisplayName("a non-image binary (PDF) is never inlined to the VISION channel (it uses text extraction instead)")
        void nonImageNeverInlined() {
            StorageEntity e = image("S3_FILE", "application/pdf");
            e.setFileName("doc.pdf");
            e.setS3Key("tenant/wf/run/step/doc.pdf");
            e.setSizeBytes(4);
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));
            // No bytes returned -> extraction yields nothing -> degrades to the url; the point of THIS
            // test is only that a PDF never reaches the image vision channel (no __media__, no 'vision').
            when(fileStorageService.download(anyString(), anyString())).thenReturn(Optional.empty());

            ToolExecutionResult r = visionProvider.execute("files",
                    Map.of("action", "view", "file_id", e.getId().toString()), ctx(TENANT, ORG));

            assertThat(r.metadata()).doesNotContainKey(ToolMediaMetadata.MEDIA_KEY);
            assertThat(data(r)).doesNotContainKey("vision");
        }

        @Test
        @DisplayName("an image whose declared size exceeds the cap is NOT inlined (download skipped), 'vision' says too large")
        void oversizedImageNotInlined() {
            StorageEntity e = image("S3_FILE", "image/png");
            e.setS3Key("tenant/wf/run/step/big.png");
            e.setSizeBytes(2048); // > 1024 cap
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            ToolExecutionResult r = visionProvider.execute("files",
                    Map.of("action", "view", "file_id", e.getId().toString()), ctx(TENANT, ORG));

            assertThat(r.metadata()).doesNotContainKey(ToolMediaMetadata.MEDIA_KEY);
            assertThat((String) data(r).get("vision")).contains("too large");
            // Oversize is rejected from declared size alone - we never pay the download.
            verifyNoInteractions(fileStorageService);
        }

        @Test
        @DisplayName("when object storage returns no bytes, view degrades gracefully (no media, 'vision' says unavailable)")
        void unavailableBytesDegradeGracefully() {
            StorageEntity e = image("S3_FILE", "image/png");
            e.setS3Key("tenant/wf/run/step/shot.png");
            e.setSizeBytes(4);
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));
            when(fileStorageService.download(anyString(), anyString())).thenReturn(Optional.empty());

            ToolExecutionResult r = visionProvider.execute("files",
                    Map.of("action", "view", "file_id", e.getId().toString()), ctx(TENANT, ORG));

            assertThat(r.metadata()).doesNotContainKey(ToolMediaMetadata.MEDIA_KEY);
            assertThat((String) data(r).get("vision")).contains("unavailable");
        }

        @Test
        @DisplayName("without a FileStorageService wired in, an S3 image cannot be read and is not inlined (no throw)")
        void noFileStorageServiceDegradesGracefully() {
            // 5-arg convenience constructor → fileStorageService is null.
            FilesToolsProvider noStorage = new FilesToolsProvider(explorerService, storageService,
                    new PublicFileUrlBuilder(BASE), orgAccessGuard, workflowRepository);
            StorageEntity e = image("S3_FILE", "image/png");
            e.setS3Key("tenant/wf/run/step/shot.png");
            e.setSizeBytes(4);
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            ToolExecutionResult r = noStorage.execute("files",
                    Map.of("action", "view", "file_id", e.getId().toString()), ctx(TENANT, ORG));

            assertThat(r.success()).isTrue();
            assertThat(r.metadata() == null || !r.metadata().containsKey(ToolMediaMetadata.MEDIA_KEY)).isTrue();
            assertThat((String) data(r).get("vision")).contains("unavailable");
        }
    }

    // ==================== view - document text extraction ====================

    @Nested
    @DisplayName("view - document text extraction (reads PDF/Word/text content, not just a link)")
    class ViewDocumentExtraction {

        private FileStorageService fileStorageService;
        private FilesToolsProvider docProvider;

        @BeforeEach
        void setUpDocs() {
            fileStorageService = mock(FileStorageService.class);
            // Generous extract cap (5 MB) so real fixture documents extract; the "too large" path
            // gets its own tiny-cap provider inside that test.
            docProvider = new FilesToolsProvider(explorerService, storageService,
                    new PublicFileUrlBuilder(BASE), orgAccessGuard, workflowRepository, fileStorageService, 1024, 5_000_000);
        }

        private StorageEntity doc(String mime, String fileName, String s3Key, int sizeBytes) {
            StorageEntity e = new StorageEntity();
            e.setId(UUID.randomUUID());
            e.setStorageType("S3_FILE");
            e.setFileName(fileName);
            e.setMimeType(mime);
            e.setContentType(mime);
            e.setS3Key(s3Key);
            e.setSizeBytes(sizeBytes);
            e.setCreatedAt(Instant.parse("2026-05-29T10:00:00Z"));
            return e;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> out(ToolExecutionResult r) {
            assertThat(r.success()).isTrue();
            return (Map<String, Object>) r.data();
        }

        @Test
        @DisplayName("a text file stored in object storage is extracted and inlined as content (was url-only before)")
        void s3TextFileInlinedAsContent() {
            StorageEntity e = doc("text/plain", "notes.txt", "tenant/wf/run/notes.txt", 11);
            byte[] bytes = "hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));
            when(fileStorageService.download(TENANT, "tenant/wf/run/notes.txt")).thenReturn(Optional.of(bytes));

            Map<String, Object> out = out(docProvider.execute("files",
                    Map.of("action", "view", "file_id", e.getId().toString()), ctx(TENANT, ORG)));

            assertThat(out).containsEntry("extracted_text", true);
            assertThat((String) out.get("content")).isEqualTo("hello world");
            // The original is still openable + wireable.
            assertThat(out).containsKey("url");
            assertThat(out).containsKey("marker");
        }

        @Test
        @DisplayName("a real PDF in object storage comes back as extracted text content")
        void s3PdfExtractedToText() throws Exception {
            byte[] pdf = makePdf("Dissertation chapter two body");
            StorageEntity e = doc("application/pdf", "thesis.pdf", "tenant/wf/run/thesis.pdf", pdf.length);
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));
            when(fileStorageService.download(TENANT, "tenant/wf/run/thesis.pdf")).thenReturn(Optional.of(pdf));

            ToolExecutionResult r = docProvider.execute("files",
                    Map.of("action", "view", "file_id", e.getId().toString()), ctx(TENANT, ORG));

            Map<String, Object> out = out(r);
            assertThat(out).containsEntry("extracted_text", true);
            assertThat((String) out.get("content")).contains("Dissertation chapter two body");
            // Text extraction rides in the result body, NOT the image vision channel.
            assertThat(r.metadata() == null || !r.metadata().containsKey(ToolMediaMetadata.MEDIA_KEY)).isTrue();
        }

        @Test
        @DisplayName("a document over the extract cap is NOT downloaded; returns url + extract_note instead of content")
        void oversizedDocumentNotExtracted() {
            // Tiny-cap provider so a small declared size already trips the "too large" guard.
            FilesToolsProvider smallCap = new FilesToolsProvider(explorerService, storageService,
                    new PublicFileUrlBuilder(BASE), orgAccessGuard, workflowRepository, fileStorageService, 1024, 64);
            StorageEntity e = doc("application/pdf", "huge.pdf", "tenant/wf/run/huge.pdf", 5000); // > 64 cap
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            Map<String, Object> out = out(smallCap.execute("files",
                    Map.of("action", "view", "file_id", e.getId().toString()), ctx(TENANT, ORG)));

            assertThat(out).doesNotContainKey("extracted_text");
            assertThat(out).doesNotContainKey("content");
            assertThat((String) out.get("extract_note")).contains("too large");
            assertThat(out).containsKey("url");
            // No contradicting "no text representation" note when the skip was purely about size.
            assertThat(out).doesNotContainKey("note");
            // Oversize rejected from the declared size alone - we never pay the download.
            verifyNoInteractions(fileStorageService);
        }

        @Test
        @DisplayName("an audio binary is NOT extracted (no text representation): url + note, no download attempted")
        void audioNotExtracted() {
            StorageEntity e = doc("audio/mpeg", "song.mp3", "tenant/wf/run/song.mp3", 10);
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            Map<String, Object> out = out(docProvider.execute("files",
                    Map.of("action", "view", "file_id", e.getId().toString()), ctx(TENANT, ORG)));

            assertThat(out).doesNotContainKey("extracted_text");
            assertThat(out).doesNotContainKey("content");
            assertThat(out).containsKey("url");
            assertThat((String) out.get("note")).contains("not available as text");
            // A non-extractable type is rejected before any download.
            verifyNoInteractions(fileStorageService);
        }

        @Test
        @DisplayName("when the document bytes are unavailable, view degrades to the url (no content)")
        void unavailableDocumentBytesDegrade() {
            StorageEntity e = doc("application/pdf", "thesis.pdf", "tenant/wf/run/thesis.pdf", 50);
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));
            when(fileStorageService.download(anyString(), anyString())).thenReturn(Optional.empty());

            Map<String, Object> out = out(docProvider.execute("files",
                    Map.of("action", "view", "file_id", e.getId().toString()), ctx(TENANT, ORG)));

            assertThat(out).doesNotContainKey("extracted_text");
            assertThat(out).doesNotContainKey("content");
            assertThat(out).containsKey("url");
        }

        @Test
        @DisplayName("corrupt bytes claiming to be a PDF degrade to the url instead of throwing")
        void corruptDocumentDegradesGracefully() {
            StorageEntity e = doc("application/pdf", "broken.pdf", "tenant/wf/run/broken.pdf", 14);
            byte[] notReallyPdf = "not a real pdf".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));
            when(fileStorageService.download(TENANT, "tenant/wf/run/broken.pdf")).thenReturn(Optional.of(notReallyPdf));

            ToolExecutionResult r = docProvider.execute("files",
                    Map.of("action", "view", "file_id", e.getId().toString()), ctx(TENANT, ORG));

            assertThat(r.success()).isTrue();
            Map<String, Object> out = out(r);
            assertThat(out).doesNotContainKey("extracted_text");
            assertThat(out).containsKey("url");
        }

        @Test
        @DisplayName("a document stored INLINE as binary (no s3 key) is also extracted to content - and its note claims no 'url'")
        void inlineBinaryDocumentExtracted() {
            StorageEntity e = new StorageEntity();
            e.setId(UUID.randomUUID());
            e.setStorageType("BINARY");
            e.setFileName("inline.txt");
            e.setMimeType("text/plain");
            e.setContentType("text/plain");
            e.setS3Key(null);
            byte[] bytes = "inline document text".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            e.setDataBinary(bytes);
            e.setSizeBytes(bytes.length);
            e.setCreatedAt(Instant.parse("2026-05-29T10:00:00Z"));
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));

            Map<String, Object> out = out(docProvider.execute("files",
                    Map.of("action", "view", "file_id", e.getId().toString()), ctx(TENANT, ORG)));

            assertThat(out).containsEntry("extracted_text", true);
            assertThat((String) out.get("content")).isEqualTo("inline document text");
            // Inline binaries have no shareable link, so neither the field nor the note may promise a 'url'.
            assertThat(out).doesNotContainKey("url");
            assertThat((String) out.get("note")).doesNotContain("url");
            // Inline bytes are read straight off the row - no object-storage download.
            verifyNoInteractions(fileStorageService);
        }

        @Test
        @DisplayName("a document download uses the KEY-OWNER tenant, not the caller's context tenant (storage 403 guard)")
        void documentDownloadUsesKeyOwnerTenant() throws Exception {
            StorageEntity e = doc("application/pdf", "owned.pdf", "owner-9/wf/run/owned.pdf", 60);
            e.setTenantId("owner-9");                       // owner tenant (matches key prefix)
            when(storageService.getEntityByIdForScope(any(), any(), any())).thenReturn(Optional.of(e));
            // Owner-tenant download succeeds; the caller-tenant path (the bug) returns empty.
            when(fileStorageService.download("owner-9", "owner-9/wf/run/owned.pdf"))
                    .thenReturn(Optional.of(makePdf("Owner tenant document body")));
            when(fileStorageService.download(TENANT, "owner-9/wf/run/owned.pdf")).thenReturn(Optional.empty());

            Map<String, Object> out = out(docProvider.execute("files",
                    Map.of("action", "view", "file_id", e.getId().toString()), ctx(TENANT, ORG)));

            assertThat(out).containsEntry("extracted_text", true);
            assertThat((String) out.get("content")).contains("Owner tenant document body");
            verify(fileStorageService).download(eq("owner-9"), eq("owner-9/wf/run/owned.pdf"));
            verify(fileStorageService, never()).download(eq(TENANT), anyString());
        }

        private byte[] makePdf(String text) throws Exception {
            try (org.apache.pdfbox.pdmodel.PDDocument pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
                org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
                pdf.addPage(page);
                try (org.apache.pdfbox.pdmodel.PDPageContentStream cs =
                             new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
                    cs.beginText();
                    cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                            org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 700);
                    cs.showText(text);
                    cs.endText();
                }
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                pdf.save(bos);
                return bos.toByteArray();
            }
        }
    }

    @Nested
    @DisplayName("read-only file access (toolsConfig.fileAccessMode='read')")
    class ReadOnlyFileAccessTests {
        /** Context with the agent's per-agent file mode set to read-only. */
        private ToolExecutionContext readOnlyCtx() {
            return new ToolExecutionContext(TENANT, Map.of("fileAccessMode", "read"),
                    Map.of(), Set.of(), null, null, ORG, null);
        }

        @Test
        @DisplayName("denies move_to_folder (PERMISSION_DENIED) before touching storage - independent of org canWrite=true")
        void deniesMoveToFolder() {
            ToolExecutionResult r = provider.execute("files",
                    Map.of("action", "move_to_folder", "file_id", "f1", "folder_id", "fol1"), readOnlyCtx());
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            assertThat(r.error()).contains("read-only").contains("move_to_folder");
            verifyNoInteractions(storageService);
        }

        @Test
        @DisplayName("denies create_folder (PERMISSION_DENIED)")
        void deniesCreateFolder() {
            ToolExecutionResult r = provider.execute("files",
                    Map.of("action", "create_folder", "name", "New"), readOnlyCtx());
            assertThat(r.success()).isFalse();
            assertThat(r.errorCode()).isEqualTo(ToolErrorCode.PERMISSION_DENIED);
            assertThat(r.error()).contains("create_folder");
            verifyNoInteractions(storageService);
        }

        @Test
        @DisplayName("allows a read action (list) under read-mode (the gate short-circuits reads)")
        void allowsListUnderReadMode() {
            when(explorerService.searchFilesSlice(any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), anyBoolean(), any(), anyInt(), anyInt())).thenReturn(new FilesSlice(List.of(), 0));
            ToolExecutionResult r = provider.execute("files", Map.of("action", "list"), readOnlyCtx());
            assertThat(r.success()).isTrue();
        }
    }
}
