package com.apimarketplace.common.storage.service;

import com.apimarketplace.common.storage.domain.StorageEntity;
import com.apimarketplace.common.storage.domain.StorageStatus;
import com.apimarketplace.common.storage.repository.StorageRepository;
import com.apimarketplace.common.storage.service.api.MappingOperations;
import com.apimarketplace.common.storage.service.api.QuotaOperations;
import com.apimarketplace.common.storage.util.JsonSkeletonGenerator;
import com.apimarketplace.common.storage.util.StorageUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the V313 manual-folder operations on {@link StorageService}:
 * create, move (with cycle prevention), and folder-delete child re-parenting.
 */
@DisplayName("StorageService - V313 manual folders")
@ExtendWith(MockitoExtension.class)
class StorageServiceFolderTest {

    private static final String TENANT_ID = "tenant-1";
    private static final String ORG_ID = "org-1";

    @Mock private StorageRepository storageRepository;
    @Mock private QuotaOperations quotaService;
    @Mock private MappingOperations mappingService;
    @Mock private StorageUtils storageUtils;
    @Mock private JsonSkeletonGenerator skeletonGenerator;
    @Mock private StorageBreakdownService breakdownService;

    private StorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new StorageService(
                storageRepository, quotaService, mappingService,
                storageUtils, skeletonGenerator, new ObjectMapper(), breakdownService);
    }

    private static StorageEntity folder(UUID id, UUID parentId) {
        StorageEntity e = new StorageEntity();
        e.setId(id);
        e.setTenantId(TENANT_ID);
        e.setOrganizationId(ORG_ID);
        e.setStatus(StorageStatus.ACTIVE);
        e.setIsFolder(true);
        e.setParentFolderId(parentId);
        e.setFileName("folder");
        e.setSizeBytes(0);
        e.setContentType("application/x-directory");
        return e;
    }

    private static StorageEntity file(UUID id, UUID parentId) {
        StorageEntity e = new StorageEntity();
        e.setId(id);
        e.setTenantId(TENANT_ID);
        e.setOrganizationId(ORG_ID);
        e.setStatus(StorageStatus.ACTIVE);
        e.setIsFolder(false);
        e.setParentFolderId(parentId);
        e.setFileName("file.pdf");
        e.setSizeBytes(100);
        e.setContentType("application/pdf");
        e.setSourceType("S3_FILE");
        return e;
    }

    @Nested
    @DisplayName("createFolderForScope")
    class CreateFolder {

        @Test
        @DisplayName("creates a top-level folder: is_folder=true, name trimmed, org+tenant scoped, no s3_key")
        void createsTopLevelFolder() {
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> {
                StorageEntity e = inv.getArgument(0);
                e.setId(UUID.randomUUID());
                return e;
            });

            StorageEntity created = storageService.createFolderForScope(TENANT_ID, ORG_ID, "  Reports  ", null);

            ArgumentCaptor<StorageEntity> captor = ArgumentCaptor.forClass(StorageEntity.class);
            verify(storageRepository).save(captor.capture());
            StorageEntity saved = captor.getValue();
            assertThat(saved.isFolder()).isTrue();
            assertThat(saved.getFileName()).isEqualTo("Reports"); // trimmed
            assertThat(saved.getOrganizationId()).isEqualTo(ORG_ID);
            assertThat(saved.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(saved.getParentFolderId()).isNull();
            assertThat(saved.getS3Key()).isNull();
            assertThat(saved.getSizeBytes()).isZero();
            assertThat(created.getId()).isNotNull();
        }

        @Test
        @DisplayName("blank name is rejected (no save)")
        void blankNameRejected() {
            assertThatThrownBy(() -> storageService.createFolderForScope(TENANT_ID, ORG_ID, "   ", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("name is required");
            verify(storageRepository, never()).save(any());
        }

        @Test
        @DisplayName("name longer than 100 chars is capped before persisting")
        void nameCapped() {
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            storageService.createFolderForScope(TENANT_ID, ORG_ID, "x".repeat(250), null);

            ArgumentCaptor<StorageEntity> captor = ArgumentCaptor.forClass(StorageEntity.class);
            verify(storageRepository).save(captor.capture());
            assertThat(captor.getValue().getFileName()).hasSize(100);
        }

        @Test
        @DisplayName("nesting under an existing same-org folder sets parent_folder_id")
        void nestsUnderValidParent() {
            UUID parentId = UUID.randomUUID();
            when(storageRepository.findFolderByIdAndOrganizationIdStrict(parentId, ORG_ID))
                    .thenReturn(Optional.of(folder(parentId, null)));
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            storageService.createFolderForScope(TENANT_ID, ORG_ID, "Sub", parentId);

            ArgumentCaptor<StorageEntity> captor = ArgumentCaptor.forClass(StorageEntity.class);
            verify(storageRepository).save(captor.capture());
            assertThat(captor.getValue().getParentFolderId()).isEqualTo(parentId);
        }

        @Test
        @DisplayName("parent that is not a same-org folder (file or cross-org) is rejected (no save)")
        void invalidParentRejected() {
            UUID parentId = UUID.randomUUID();
            // findFolder... returns empty for a file id OR a cross-org folder id.
            when(storageRepository.findFolderByIdAndOrganizationIdStrict(parentId, ORG_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> storageService.createFolderForScope(TENANT_ID, ORG_ID, "Sub", parentId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a folder");
            verify(storageRepository, never()).save(any());
        }

        @Test
        @DisplayName("null organizationId is rejected (org scope mandatory)")
        void nullOrgRejected() {
            assertThatThrownBy(() -> storageService.createFolderForScope(TENANT_ID, null, "X", null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("moveEntriesForScope")
    class Move {

        @Test
        @DisplayName("happy path: files into a folder set parent_folder_id; movedCount reflects them")
        void movesFilesIntoFolder() {
            UUID target = UUID.randomUUID();
            UUID f1 = UUID.randomUUID();
            UUID f2 = UUID.randomUUID();
            when(storageRepository.findFolderByIdAndOrganizationIdStrict(target, ORG_ID))
                    .thenReturn(Optional.of(folder(target, null)));
            when(storageRepository.findByIdAndOrganizationIdStrict(f1, ORG_ID)).thenReturn(Optional.of(file(f1, null)));
            when(storageRepository.findByIdAndOrganizationIdStrict(f2, ORG_ID)).thenReturn(Optional.of(file(f2, null)));
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            StorageService.MoveResult result = storageService.moveEntriesForScope(ORG_ID, List.of(f1, f2), target);

            assertThat(result.movedCount()).isEqualTo(2);
            assertThat(result.failed()).isEmpty();
            ArgumentCaptor<StorageEntity> captor = ArgumentCaptor.forClass(StorageEntity.class);
            verify(storageRepository, times(2)).save(captor.capture());
            assertThat(captor.getAllValues()).allMatch(e -> target.equals(e.getParentFolderId()));
        }

        @Test
        @DisplayName("move to root: null target clears parent_folder_id")
        void movesToRoot() {
            UUID someParent = UUID.randomUUID();
            UUID fileId = UUID.randomUUID();
            when(storageRepository.findByIdAndOrganizationIdStrict(fileId, ORG_ID))
                    .thenReturn(Optional.of(file(fileId, someParent)));
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            StorageService.MoveResult result = storageService.moveEntriesForScope(ORG_ID, List.of(fileId), null);

            assertThat(result.movedCount()).isEqualTo(1);
            ArgumentCaptor<StorageEntity> captor = ArgumentCaptor.forClass(StorageEntity.class);
            verify(storageRepository).save(captor.capture());
            assertThat(captor.getValue().getParentFolderId()).isNull();
        }

        @Test
        @DisplayName("cross-org / unknown id is reported as a failure, not moved")
        void crossOrgIdFails() {
            UUID target = UUID.randomUUID();
            UUID unknown = UUID.randomUUID();
            when(storageRepository.findFolderByIdAndOrganizationIdStrict(target, ORG_ID))
                    .thenReturn(Optional.of(folder(target, null)));
            // Row not in this org → strict finder returns empty.
            when(storageRepository.findByIdAndOrganizationIdStrict(unknown, ORG_ID)).thenReturn(Optional.empty());

            StorageService.MoveResult result = storageService.moveEntriesForScope(ORG_ID, List.of(unknown), target);

            assertThat(result.movedCount()).isZero();
            assertThat(result.failed()).singleElement()
                    .satisfies(f -> {
                        assertThat(f.id()).isEqualTo(unknown);
                        assertThat(f.reason()).contains("not found");
                    });
            verify(storageRepository, never()).save(any());
        }

        @Test
        @DisplayName("a non-existent (cross-org) TARGET fails the whole request")
        void crossOrgTargetRejected() {
            UUID target = UUID.randomUUID();
            when(storageRepository.findFolderByIdAndOrganizationIdStrict(target, ORG_ID))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> storageService.moveEntriesForScope(ORG_ID, List.of(UUID.randomUUID()), target))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a folder");
        }

        @Test
        @DisplayName("moving a folder into itself is rejected (cycle)")
        void cannotMoveFolderIntoItself() {
            UUID folderId = UUID.randomUUID();
            when(storageRepository.findFolderByIdAndOrganizationIdStrict(folderId, ORG_ID))
                    .thenReturn(Optional.of(folder(folderId, null)));
            when(storageRepository.findByIdAndOrganizationIdStrict(folderId, ORG_ID))
                    .thenReturn(Optional.of(folder(folderId, null)));

            StorageService.MoveResult result = storageService.moveEntriesForScope(ORG_ID, List.of(folderId), folderId);

            assertThat(result.movedCount()).isZero();
            assertThat(result.failed()).singleElement()
                    .satisfies(f -> assertThat(f.reason()).contains("into itself"));
            verify(storageRepository, never()).save(any());
        }

        @Test
        @DisplayName("moving a folder into one of its OWN DESCENDANTS is rejected (cycle, walks the chain)")
        void cannotMoveFolderIntoDescendant() {
            // Chain: parent (root) ← child ← grandchild. Try to move `parent` under `grandchild`.
            UUID parent = UUID.randomUUID();
            UUID child = UUID.randomUUID();
            UUID grandchild = UUID.randomUUID();
            StorageEntity parentFolder = folder(parent, null);
            StorageEntity childFolder = folder(child, parent);
            StorageEntity grandchildFolder = folder(grandchild, child);

            // Target validation: grandchild is a valid folder.
            when(storageRepository.findFolderByIdAndOrganizationIdStrict(grandchild, ORG_ID))
                    .thenReturn(Optional.of(grandchildFolder));
            // The moved row is `parent`.
            when(storageRepository.findByIdAndOrganizationIdStrict(parent, ORG_ID))
                    .thenReturn(Optional.of(parentFolder));
            // Cycle walk UP from grandchild: grandchild → child → parent (== moved id) → reject.
            when(storageRepository.findFolderByIdAndOrganizationIdStrict(child, ORG_ID))
                    .thenReturn(Optional.of(childFolder));

            StorageService.MoveResult result = storageService.moveEntriesForScope(ORG_ID, List.of(parent), grandchild);

            assertThat(result.movedCount()).isZero();
            assertThat(result.failed()).singleElement()
                    .satisfies(f -> assertThat(f.reason()).contains("descendant"));
            verify(storageRepository, never()).save(any());
        }

        @Test
        @DisplayName("a FILE may be moved into a folder even when that folder is unrelated (no cycle check for files)")
        void fileIntoFolderAllowed() {
            UUID target = UUID.randomUUID();
            UUID fileId = UUID.randomUUID();
            when(storageRepository.findFolderByIdAndOrganizationIdStrict(target, ORG_ID))
                    .thenReturn(Optional.of(folder(target, null)));
            when(storageRepository.findByIdAndOrganizationIdStrict(fileId, ORG_ID))
                    .thenReturn(Optional.of(file(fileId, null)));
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            StorageService.MoveResult result = storageService.moveEntriesForScope(ORG_ID, List.of(fileId), target);

            assertThat(result.movedCount()).isEqualTo(1);
            assertThat(result.failed()).isEmpty();
        }
    }

    @Nested
    @DisplayName("deleteByIdForScope - folder re-parents children up (no file loss)")
    class DeleteFolderReparent {

        @Test
        @DisplayName("deleting a folder re-parents its direct children to the folder's own parent, then deletes the folder")
        void reparentsChildrenUpThenDeletes() {
            UUID grandparent = UUID.randomUUID();
            UUID folderId = UUID.randomUUID();
            StorageEntity theFolder = folder(folderId, grandparent);
            UUID childFile = UUID.randomUUID();
            UUID childSubFolder = UUID.randomUUID();
            StorageEntity c1 = file(childFile, folderId);
            StorageEntity c2 = folder(childSubFolder, folderId);

            when(storageRepository.findByIdAndOrganizationIdStrict(folderId, ORG_ID))
                    .thenReturn(Optional.of(theFolder));
            when(storageRepository.findChildrenByParentFolderIdAndOrganizationId(folderId, ORG_ID))
                    .thenReturn(List.of(c1, c2));
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            boolean deleted = storageService.deleteByIdForScope(folderId, TENANT_ID, ORG_ID);

            assertThat(deleted).isTrue();
            // Children re-parented to the folder's own parent (grandparent), not deleted.
            ArgumentCaptor<StorageEntity> captor = ArgumentCaptor.forClass(StorageEntity.class);
            verify(storageRepository, times(2)).save(captor.capture());
            assertThat(captor.getAllValues()).allMatch(e -> grandparent.equals(e.getParentFolderId()));
            // The folder row itself is soft-deleted.
            verify(storageRepository).updateStatus(folderId, StorageStatus.DELETED);
        }

        @Test
        @DisplayName("deleting a top-level folder re-parents children to ROOT (null parent)")
        void reparentsChildrenToRootWhenFolderIsTopLevel() {
            UUID folderId = UUID.randomUUID();
            StorageEntity theFolder = folder(folderId, null); // top level
            UUID childFile = UUID.randomUUID();
            StorageEntity child = file(childFile, folderId);

            when(storageRepository.findByIdAndOrganizationIdStrict(folderId, ORG_ID))
                    .thenReturn(Optional.of(theFolder));
            when(storageRepository.findChildrenByParentFolderIdAndOrganizationId(folderId, ORG_ID))
                    .thenReturn(List.of(child));
            when(storageRepository.save(any(StorageEntity.class))).thenAnswer(inv -> inv.getArgument(0));

            storageService.deleteByIdForScope(folderId, TENANT_ID, ORG_ID);

            ArgumentCaptor<StorageEntity> captor = ArgumentCaptor.forClass(StorageEntity.class);
            verify(storageRepository).save(captor.capture());
            assertThat(captor.getValue().getParentFolderId()).isNull();
        }

        @Test
        @DisplayName("deleting a regular FILE does not look up children (unchanged behaviour)")
        void deletingFileDoesNotReparent() {
            UUID fileId = UUID.randomUUID();
            when(storageRepository.findByIdAndOrganizationIdStrict(fileId, ORG_ID))
                    .thenReturn(Optional.of(file(fileId, null)));

            boolean deleted = storageService.deleteByIdForScope(fileId, TENANT_ID, ORG_ID);

            assertThat(deleted).isTrue();
            verify(storageRepository, never()).findChildrenByParentFolderIdAndOrganizationId(any(), any());
            verify(storageRepository).updateStatus(fileId, StorageStatus.DELETED);
        }
    }
}
