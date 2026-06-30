package com.apimarketplace.agent.tools.skill;

import com.apimarketplace.agent.domain.SkillFolderEntity;
import com.apimarketplace.agent.service.SkillFolderService;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionContext;
import com.apimarketplace.agent.tools.ToolsProvider.ToolExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SkillFolderModule Tests")
class SkillFolderModuleTest {

    @Mock private SkillFolderService skillFolderService;

    private SkillFolderModule module;

    private static final String TENANT = "tenant-123";
    private static final UUID FOLDER_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        module = new SkillFolderModule(skillFolderService);
    }

    private ToolExecutionContext ctx() {
        return new ToolExecutionContext(TENANT, null, Map.of(), null, null, null, null, null);
    }

    private SkillFolderEntity mockFolder(UUID id, String name, UUID parentId) {
        SkillFolderEntity e = new SkillFolderEntity();
        e.setId(id);
        e.setName(name);
        e.setParentId(parentId);
        return e;
    }

    @Nested
    @DisplayName("canHandle")
    class CanHandle {
        @Test void createFolder() { assertThat(module.canHandle("create_folder")).isTrue(); }
        @Test void listFolders() { assertThat(module.canHandle("list_folders")).isTrue(); }
        @Test void renameFolder() { assertThat(module.canHandle("rename_folder")).isTrue(); }
        @Test void moveFolder() { assertThat(module.canHandle("move_folder")).isTrue(); }
        @Test void deleteFolder() { assertThat(module.canHandle("delete_folder")).isTrue(); }
        @Test void rejectsCrud() { assertThat(module.canHandle("create")).isFalse(); }
        @Test void rejectsHelp() { assertThat(module.canHandle("help")).isFalse(); }
    }

    @Nested
    @DisplayName("Create Folder")
    class CreateFolder {

        @Test
        @DisplayName("Creates folder successfully")
        void createSuccess() {
            SkillFolderEntity folder = mockFolder(FOLDER_ID, "Marketing", null);
            when(skillFolderService.createFolder(TENANT, "Marketing", null, null)).thenReturn(folder);

            Map<String, Object> params = Map.of("action", "create_folder", "name", "Marketing");
            Optional<ToolExecutionResult> result = module.execute("create_folder", params, TENANT, ctx());

            assertThat(result.get().success()).isTrue();
            assertThat(result.get().toMap().toString()).contains("CREATED", "Marketing");
        }

        @Test
        @DisplayName("Creates nested folder")
        void createNested() {
            UUID parentId = UUID.randomUUID();
            SkillFolderEntity folder = mockFolder(FOLDER_ID, "SEO", parentId);
            when(skillFolderService.createFolder(TENANT, "SEO", parentId, null)).thenReturn(folder);

            Map<String, Object> params = Map.of("action", "create_folder", "name", "SEO", "parent_id", parentId.toString());
            Optional<ToolExecutionResult> result = module.execute("create_folder", params, TENANT, ctx());

            assertThat(result.get().success()).isTrue();
        }

        @Test
        @DisplayName("Fails without name")
        void failsWithoutName() {
            Map<String, Object> params = Map.of("action", "create_folder");
            Optional<ToolExecutionResult> result = module.execute("create_folder", params, TENANT, ctx());
            assertThat(result.get().success()).isFalse();
        }
    }

    @Nested
    @DisplayName("List Folders")
    class ListFolders {

        @Test
        @DisplayName("Returns all folders")
        void listSuccess() {
            UUID parentId = UUID.randomUUID();
            when(skillFolderService.listAllFolders(TENANT)).thenReturn(List.of(
                mockFolder(UUID.randomUUID(), "Root Folder", null),
                mockFolder(UUID.randomUUID(), "Child", parentId)
            ));

            Optional<ToolExecutionResult> result = module.execute("list_folders", Map.of(), TENANT, ctx());

            assertThat(result.get().success()).isTrue();
            assertThat(result.get().toMap().toString()).contains("Root Folder", "Child");
        }
    }

    @Nested
    @DisplayName("Rename Folder")
    class RenameFolder {

        @Test
        @DisplayName("Renames folder successfully")
        void renameSuccess() {
            SkillFolderEntity renamed = mockFolder(FOLDER_ID, "New Name", null);
            when(skillFolderService.renameFolder(FOLDER_ID, TENANT, "New Name")).thenReturn(renamed);

            Map<String, Object> params = Map.of("action", "rename_folder",
                "folder_id", FOLDER_ID.toString(), "name", "New Name");
            Optional<ToolExecutionResult> result = module.execute("rename_folder", params, TENANT, ctx());

            assertThat(result.get().success()).isTrue();
            assertThat(result.get().toMap().toString()).contains("RENAMED");
        }

        @Test
        @DisplayName("Fails without folder_id")
        void failsWithoutId() {
            Map<String, Object> params = Map.of("action", "rename_folder", "name", "X");
            Optional<ToolExecutionResult> result = module.execute("rename_folder", params, TENANT, ctx());
            assertThat(result.get().success()).isFalse();
        }

        @Test
        @DisplayName("Fails without name")
        void failsWithoutName() {
            Map<String, Object> params = Map.of("action", "rename_folder", "folder_id", FOLDER_ID.toString());
            Optional<ToolExecutionResult> result = module.execute("rename_folder", params, TENANT, ctx());
            assertThat(result.get().success()).isFalse();
        }
    }

    @Nested
    @DisplayName("Move Folder")
    class MoveFolder {

        @Test
        @DisplayName("Moves folder to new parent")
        void moveToParent() {
            UUID newParent = UUID.randomUUID();
            SkillFolderEntity moved = mockFolder(FOLDER_ID, "Moved", newParent);
            when(skillFolderService.moveFolder(FOLDER_ID, TENANT, newParent)).thenReturn(moved);

            Map<String, Object> params = Map.of("action", "move_folder",
                "folder_id", FOLDER_ID.toString(), "parent_id", newParent.toString());
            Optional<ToolExecutionResult> result = module.execute("move_folder", params, TENANT, ctx());

            assertThat(result.get().success()).isTrue();
            assertThat(result.get().toMap().toString()).contains("MOVED");
        }

        @Test
        @DisplayName("Moves folder to root with 'root' string")
        void moveToRoot() {
            SkillFolderEntity moved = mockFolder(FOLDER_ID, "AtRoot", null);
            when(skillFolderService.moveFolder(FOLDER_ID, TENANT, null)).thenReturn(moved);

            Map<String, Object> params = Map.of("action", "move_folder",
                "folder_id", FOLDER_ID.toString(), "parent_id", "root");
            Optional<ToolExecutionResult> result = module.execute("move_folder", params, TENANT, ctx());

            assertThat(result.get().success()).isTrue();
            assertThat(result.get().toMap().toString()).contains("root");
        }
    }

    @Nested
    @DisplayName("Delete Folder")
    class DeleteFolder {

        @Test
        @DisplayName("Deletes folder successfully")
        void deleteSuccess() {
            Map<String, Object> params = Map.of("action", "delete_folder", "folder_id", FOLDER_ID.toString());
            Optional<ToolExecutionResult> result = module.execute("delete_folder", params, TENANT, ctx());

            assertThat(result.get().success()).isTrue();
            assertThat(result.get().toMap().toString()).contains("DELETED");
            verify(skillFolderService).deleteFolder(FOLDER_ID, TENANT);
        }

        @Test
        @DisplayName("Fails without folder_id")
        void failsWithoutId() {
            Map<String, Object> params = Map.of("action", "delete_folder");
            Optional<ToolExecutionResult> result = module.execute("delete_folder", params, TENANT, ctx());
            assertThat(result.get().success()).isFalse();
        }
    }

    @Nested
    @DisplayName("resolveNullableFolderId helper")
    class ResolveNullableFolderId {

        @Test
        @DisplayName("UUID string returns UUID")
        void uuidReturnsUuid() {
            UUID id = UUID.randomUUID();
            assertThat(SkillFolderModule.resolveNullableFolderId(Map.of("k", id.toString()), "k")).isEqualTo(id);
        }

        @Test
        @DisplayName("'root' returns null")
        void rootReturnsNull() {
            assertThat(SkillFolderModule.resolveNullableFolderId(Map.of("k", "root"), "k")).isNull();
        }

        @Test
        @DisplayName("'none' returns null")
        void noneReturnsNull() {
            assertThat(SkillFolderModule.resolveNullableFolderId(Map.of("k", "none"), "k")).isNull();
        }

        @Test
        @DisplayName("Empty string returns null")
        void emptyReturnsNull() {
            assertThat(SkillFolderModule.resolveNullableFolderId(Map.of("k", ""), "k")).isNull();
        }

        @Test
        @DisplayName("Missing key returns null")
        void missingReturnsNull() {
            assertThat(SkillFolderModule.resolveNullableFolderId(Map.of(), "k")).isNull();
        }

        @Test
        @DisplayName("Invalid UUID returns null")
        void invalidUuidReturnsNull() {
            assertThat(SkillFolderModule.resolveNullableFolderId(Map.of("k", "not-uuid"), "k")).isNull();
        }
    }
}
