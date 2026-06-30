package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.storage.dto.StorageExplorerDto;
import com.apimarketplace.common.storage.dto.StorageExplorerProjection;
import com.apimarketplace.common.storage.dto.StoragePreviewFile;
import com.apimarketplace.common.storage.repository.StorageExplorerRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link StorageExplorerService#searchFolderScope}: it must batch the folder-only
 * aggregates (childCount + previewFiles) for the folder rows on the page in exactly TWO queries
 * (no N+1), map them onto folder DTOs only, and leave file DTOs' folder fields null/empty.
 */
@DisplayName("StorageExplorerService - folder-aware listing")
@ExtendWith(MockitoExtension.class)
class StorageExplorerServiceFolderTest {

    private static final String TENANT = "t-1";
    private static final String ORG = "org-1";

    @Mock private StorageExplorerRepository repository;

    private StorageExplorerService service;

    StorageExplorerServiceFolderTest() {}

    private StorageExplorerService service() {
        if (service == null) {
            service = new StorageExplorerService(repository);
        }
        return service;
    }

    private static StorageExplorerProjection folderRow(UUID id, Instant createdAt) {
        return new StorageExplorerProjection(id, "FOLDER", "FOLDER", "MyFolder", null, 0,
                createdAt, null, null, null, null, 0, null, "application/x-directory",
                /* isFolder */ true, /* parentFolderId */ null);
    }

    private static StorageExplorerProjection fileRow(UUID id, UUID parentId, Instant createdAt) {
        return new StorageExplorerProjection(id, "S3_FILE", "S3_FILE", "doc.pdf", "application/pdf", 100,
                createdAt, null, null, null, null, 0, "key/doc.pdf", "application/pdf",
                /* isFolder */ false, /* parentFolderId */ parentId);
    }

    /** A preview-file tile entry (id + type) the repo batch helpers hand back to the service. */
    private static StoragePreviewFile prev(UUID id) {
        return new StoragePreviewFile(id.toString(), "text/csv", "data.csv");
    }

    @Test
    @DisplayName("folder rows carry batched childCount + previewFiles; file rows leave them null")
    void mapsFolderAggregatesOntoFolderRowsOnly() {
        UUID folderId = UUID.randomUUID();
        UUID looseFileId = UUID.randomUUID();
        Instant now = Instant.now();
        List<StorageExplorerProjection> rows = List.of(
                folderRow(folderId, now),
                fileRow(looseFileId, null, now.minusSeconds(1)));

        when(repository.listFolderScope(eq(ORG), eq(null), any(), any(), any(), any(), any(),
                any(), any(), eq(false), eq(true), any(), any(), eq(20), eq(0)))
                .thenReturn(new StorageExplorerRepository.SliceResult(rows, 2));

        StoragePreviewFile previewA = prev(UUID.randomUUID());
        StoragePreviewFile previewB = prev(UUID.randomUUID());
        when(repository.countChildrenByParent(eq(ORG), any(), any())).thenReturn(Map.of(folderId, 5));
        when(repository.findPreviewFilesByParent(eq(ORG), any(), any()))
                .thenReturn(Map.of(folderId, List.of(previewA, previewB)));

        Page<StorageExplorerDto> page = service().searchFolderScope(
                TENANT, ORG, /* parentFolderId */ null, null, null, null, null, null, null, null,
                /* filesOnly */ false, /* s3Only */ true, null, List.of(), PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(2);
        StorageExplorerDto folderDto = page.getContent().get(0);
        StorageExplorerDto fileDto = page.getContent().get(1);

        assertThat(folderDto.isFolder()).isTrue();
        assertThat(folderDto.childCount()).isEqualTo(5);
        // The tile carries the typed preview files straight through (id + mime + name).
        assertThat(folderDto.previewFiles()).containsExactly(previewA, previewB);

        assertThat(fileDto.isFolder()).isFalse();
        assertThat(fileDto.childCount()).isNull();
        assertThat(fileDto.previewFiles()).isNull();
    }

    @Test
    @DisplayName("aggregates are fetched in ONE count query + ONE preview query (no N+1 over folders)")
    void batchesAggregatesNoN1() {
        Instant now = Instant.now();
        List<StorageExplorerProjection> folders = new ArrayList<>();
        List<UUID> folderIds = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            UUID id = UUID.randomUUID();
            folderIds.add(id);
            folders.add(folderRow(id, now.minusSeconds(i)));
        }
        when(repository.listFolderScope(eq(ORG), eq(null), any(), any(), any(), any(), any(),
                any(), any(), any(Boolean.class), any(Boolean.class), any(), any(), eq(20), eq(0)))
                .thenReturn(new StorageExplorerRepository.SliceResult(folders, 4));
        when(repository.countChildrenByParent(eq(ORG), any(), any())).thenReturn(Map.of());
        when(repository.findPreviewFilesByParent(eq(ORG), any(), any())).thenReturn(Map.of());

        service().searchFolderScope(TENANT, ORG, null, null, null, null, null, null, null, null,
                false, false, null, List.of(), PageRequest.of(0, 20));

        // Exactly ONE batched call each - never per-folder.
        verify(repository, times(1)).countChildrenByParent(eq(ORG), any(), any());
        verify(repository, times(1)).findPreviewFilesByParent(eq(ORG), any(), any());

        // And the batched id set is the page's folder ids (all 4).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Collection<UUID>> idsCaptor = ArgumentCaptor.forClass(java.util.Collection.class);
        verify(repository).countChildrenByParent(eq(ORG), idsCaptor.capture(), any());
        assertThat(idsCaptor.getValue()).containsExactlyInAnyOrderElementsOf(folderIds);
    }

    @Test
    @DisplayName("a folder with >9 preview children exposes at most 9 previewFiles (the repo slices ≤9)")
    void previewCappedAtNine() {
        UUID folderId = UUID.randomUUID();
        Instant now = Instant.now();
        when(repository.listFolderScope(eq(ORG), eq(folderId), any(), any(), any(), any(), any(),
                any(), any(), any(Boolean.class), any(Boolean.class), any(), any(), eq(20), eq(0)))
                .thenReturn(new StorageExplorerRepository.SliceResult(List.of(folderRow(folderId, now)), 1));
        when(repository.countChildrenByParent(eq(ORG), any(), any())).thenReturn(Map.of(folderId, 25));
        // Repo already slices to ≤9 (PREVIEW_FILES_PER_FOLDER); simulate that contract.
        List<StoragePreviewFile> nine = IntStream.range(0, 9).mapToObj(i -> prev(UUID.randomUUID())).collect(Collectors.toList());
        when(repository.findPreviewFilesByParent(eq(ORG), any(), any())).thenReturn(Map.of(folderId, nine));

        Page<StorageExplorerDto> page = service().searchFolderScope(
                TENANT, ORG, folderId, null, null, null, null, null, null, null,
                false, false, null, List.of(), PageRequest.of(0, 20));

        StorageExplorerDto dto = page.getContent().get(0);
        assertThat(dto.childCount()).isEqualTo(25);
        assertThat(dto.previewFiles()).hasSize(9);
    }

    @Test
    @DisplayName("a folder with no preview children yields an empty (non-null) previewFiles list")
    void emptyPreviewListForFolderWithoutPreviewableChildren() {
        UUID folderId = UUID.randomUUID();
        when(repository.listFolderScope(eq(ORG), any(), any(), any(), any(), any(), any(),
                any(), any(), any(Boolean.class), any(Boolean.class), any(), any(), eq(20), eq(0)))
                .thenReturn(new StorageExplorerRepository.SliceResult(
                        List.of(folderRow(folderId, Instant.now())), 1));
        when(repository.countChildrenByParent(eq(ORG), any(), any())).thenReturn(Map.of(folderId, 0));
        when(repository.findPreviewFilesByParent(eq(ORG), any(), any())).thenReturn(Map.of());

        Page<StorageExplorerDto> page = service().searchFolderScope(
                TENANT, ORG, null, null, null, null, null, null, null, null,
                false, false, null, List.of(), PageRequest.of(0, 20));

        StorageExplorerDto dto = page.getContent().get(0);
        assertThat(dto.childCount()).isZero();
        assertThat(dto.previewFiles()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("forwards the parentFolderId straight through to the repository (inside-a-folder listing)")
    void forwardsParentFolderId() {
        UUID parentId = UUID.randomUUID();
        when(repository.listFolderScope(eq(ORG), eq(parentId), any(), any(), any(), any(), any(),
                any(), any(), any(Boolean.class), any(Boolean.class), any(), any(), eq(50), eq(0)))
                .thenReturn(new StorageExplorerRepository.SliceResult(List.of(), 0));

        service().searchFolderScope(TENANT, ORG, parentId, null, null, null, null, null, null, null,
                false, false, null, List.of(), PageRequest.of(0, 50));

        verify(repository).listFolderScope(eq(ORG), eq(parentId), any(), any(), any(), any(), any(),
                any(), any(), any(Boolean.class), any(Boolean.class), any(), any(), eq(50), eq(0));
    }

    @Test
    @DisplayName("FIX 2: a restricted child id in excludedIds is threaded into BOTH folder-aggregate helpers, "
            + "so its UUID is absent from previewFiles and the reduced childCount is honoured")
    void restrictedChildIsExcludedFromAggregates() {
        UUID folderId = UUID.randomUUID();
        UUID restrictedChild = UUID.randomUUID();
        StoragePreviewFile visiblePreview = prev(UUID.randomUUID());
        List<UUID> excluded = List.of(restrictedChild);

        when(repository.listFolderScope(eq(ORG), eq(null), any(), any(), any(), any(), any(),
                any(), any(), any(Boolean.class), any(Boolean.class), any(), eq(excluded), eq(20), eq(0)))
                .thenReturn(new StorageExplorerRepository.SliceResult(
                        List.of(folderRow(folderId, Instant.now())), 1));
        // Repo (with the deny-list applied) reports the REDUCED count and the preview set
        // WITHOUT the restricted child - these helpers must receive the same excludedIds.
        when(repository.countChildrenByParent(eq(ORG), any(), eq(excluded))).thenReturn(Map.of(folderId, 1));
        when(repository.findPreviewFilesByParent(eq(ORG), any(), eq(excluded)))
                .thenReturn(Map.of(folderId, List.of(visiblePreview)));

        Page<StorageExplorerDto> page = service().searchFolderScope(
                TENANT, ORG, /* parentFolderId */ null, null, null, null, null, null, null, null,
                false, false, null, excluded, PageRequest.of(0, 20));

        // The service must pass the SAME deny-list through to both batch helpers (fails pre-fix:
        // the 2-arg helpers took no excludedIds, so the restricted child leaked).
        verify(repository).countChildrenByParent(eq(ORG), any(), eq(excluded));
        verify(repository).findPreviewFilesByParent(eq(ORG), any(), eq(excluded));

        StorageExplorerDto folderDto = page.getContent().get(0);
        assertThat(folderDto.childCount()).isEqualTo(1); // reduced, not inflated
        assertThat(folderDto.previewFiles())
                .containsExactly(visiblePreview)
                .extracting(StoragePreviewFile::id)
                .doesNotContain(restrictedChild.toString()); // restricted UUID never disclosed
    }

    @Test
    @DisplayName("a folder's createdAt is STAMPED with its last activity (MAX child created_at) so it sorts/displays by the last element added")
    void folderCreatedAtStampedWithLastActivity() {
        UUID folderId = UUID.randomUUID();
        Instant folderOwnCreatedAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant lastChildAddedAt = Instant.parse("2026-06-17T12:00:00Z"); // much later than the folder itself
        when(repository.listFolderScope(eq(ORG), eq(null), any(), any(), any(), any(), any(),
                any(), any(), any(Boolean.class), any(Boolean.class), any(), any(), eq(20), eq(0)))
                .thenReturn(new StorageExplorerRepository.SliceResult(
                        List.of(folderRow(folderId, folderOwnCreatedAt)), 1));
        when(repository.countChildrenByParent(eq(ORG), any(), any())).thenReturn(Map.of(folderId, 3));
        when(repository.findPreviewFilesByParent(eq(ORG), any(), any())).thenReturn(Map.of());
        when(repository.latestChildCreatedAtByParent(eq(ORG), any(), any()))
                .thenReturn(Map.of(folderId, lastChildAddedAt));

        Page<StorageExplorerDto> page = service().searchFolderScope(
                TENANT, ORG, null, null, null, null, null, null, null, null,
                false, false, null, List.of(), PageRequest.of(0, 20));

        // The DTO must carry the LAST-ACTIVITY date, not the folder's own creation date.
        assertThat(page.getContent().get(0).createdAt()).isEqualTo(lastChildAddedAt);
    }

    @Test
    @DisplayName("a childless folder (absent from the activity map) keeps its OWN createdAt (fallback)")
    void childlessFolderKeepsOwnCreatedAt() {
        UUID folderId = UUID.randomUUID();
        Instant folderOwnCreatedAt = Instant.parse("2026-03-03T09:00:00Z");
        when(repository.listFolderScope(eq(ORG), eq(null), any(), any(), any(), any(), any(),
                any(), any(), any(Boolean.class), any(Boolean.class), any(), any(), eq(20), eq(0)))
                .thenReturn(new StorageExplorerRepository.SliceResult(
                        List.of(folderRow(folderId, folderOwnCreatedAt)), 1));
        when(repository.countChildrenByParent(eq(ORG), any(), any())).thenReturn(Map.of(folderId, 0));
        when(repository.findPreviewFilesByParent(eq(ORG), any(), any())).thenReturn(Map.of());
        when(repository.latestChildCreatedAtByParent(eq(ORG), any(), any())).thenReturn(Map.of()); // no activity

        Page<StorageExplorerDto> page = service().searchFolderScope(
                TENANT, ORG, null, null, null, null, null, null, null, null,
                false, false, null, List.of(), PageRequest.of(0, 20));

        assertThat(page.getContent().get(0).createdAt()).isEqualTo(folderOwnCreatedAt);
    }

    @Test
    @DisplayName("the restricted-id deny-list is threaded into latestChildCreatedAtByParent too (a restricted child can't bump a folder's activity)")
    void activityHelperHonoursExclusions() {
        UUID folderId = UUID.randomUUID();
        List<UUID> excluded = List.of(UUID.randomUUID());
        when(repository.listFolderScope(eq(ORG), eq(null), any(), any(), any(), any(), any(),
                any(), any(), any(Boolean.class), any(Boolean.class), any(), eq(excluded), eq(20), eq(0)))
                .thenReturn(new StorageExplorerRepository.SliceResult(
                        List.of(folderRow(folderId, Instant.now())), 1));
        when(repository.countChildrenByParent(eq(ORG), any(), eq(excluded))).thenReturn(Map.of(folderId, 1));
        when(repository.findPreviewFilesByParent(eq(ORG), any(), eq(excluded))).thenReturn(Map.of());
        when(repository.latestChildCreatedAtByParent(eq(ORG), any(), eq(excluded))).thenReturn(Map.of());

        service().searchFolderScope(TENANT, ORG, null, null, null, null, null, null, null, null,
                false, false, null, excluded, PageRequest.of(0, 20));

        verify(repository).latestChildCreatedAtByParent(eq(ORG), any(), eq(excluded));
    }
}
