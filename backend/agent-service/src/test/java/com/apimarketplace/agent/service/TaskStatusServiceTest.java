package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.TaskStatusCategory;
import com.apimarketplace.agent.domain.TaskStatusEntity;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.agent.repository.TaskStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit coverage for {@link TaskStatusService}: default seeding (idempotent +
 * race-safe), custom-column CRUD with key derivation/dedup, the system-status
 * guards, delete relocation target, reorder, and the category resolvers the
 * agent state machine depends on.
 */
class TaskStatusServiceTest {

    private static final String T = "tenant-1";
    private static final String O = "org-1";

    private TaskStatusRepository repo;
    private AgentTaskRepository taskRepo;
    private TaskStatusService service;

    @BeforeEach
    void setUp() {
        repo = mock(TaskStatusRepository.class);
        taskRepo = mock(AgentTaskRepository.class);
        service = new TaskStatusService(repo, taskRepo);
        // save() echoes its argument so callers observe the persisted entity.
        when(repo.save(any(TaskStatusEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static TaskStatusEntity status(String key, TaskStatusCategory cat, boolean system) {
        TaskStatusEntity e = new TaskStatusEntity();
        e.setId(UUID.randomUUID());
        e.setTenantId(T);
        e.setOrganizationId(O);
        e.setKey(key);
        e.setLabel(key);
        e.setCategory(cat.wireKey());
        e.setSystem(system);
        return e;
    }

    // ---------------------------------------------------------------- seeding

    @Test
    @DisplayName("ensureDefaults seeds the seven defaults in order on an empty board")
    void ensureDefaultsSeeds() {
        when(repo.countBoard(T, O)).thenReturn(0L);

        service.ensureDefaults(T, O);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TaskStatusEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(repo).saveAll(captor.capture());
        List<TaskStatusEntity> seeded = captor.getValue();
        assertEquals(7, seeded.size());
        assertEquals(List.of("pending", "in_progress", "in_review", "completed", "failed", "cancelled", "deleted"),
                seeded.stream().map(TaskStatusEntity::getKey).toList());
        // positions are 0..6 in order
        for (int i = 0; i < seeded.size(); i++) assertEquals(i, seeded.get(i).getPosition());
        // failed/cancelled/deleted ship hidden, the rest visible
        assertFalse(seeded.get(0).isHidden());
        assertTrue(seeded.get(4).isHidden());
        assertTrue(seeded.get(5).isHidden());
        assertTrue(seeded.get(6).isHidden());
        // all seven are system rows
        assertTrue(seeded.stream().allMatch(TaskStatusEntity::isSystem));
        // completed maps to the DONE category
        assertEquals(TaskStatusCategory.DONE.wireKey(), seeded.get(3).getCategory());
    }

    @Test
    @DisplayName("ensureDefaults is a no-op once the board has any rows")
    void ensureDefaultsNoopWhenPopulated() {
        when(repo.countBoard(T, O)).thenReturn(7L);
        service.ensureDefaults(T, O);
        verify(repo, never()).saveAll(any());
    }

    @Test
    @DisplayName("ensureDefaults swallows the unique-index race (concurrent first access)")
    void ensureDefaultsSwallowsRace() {
        when(repo.countBoard(T, O)).thenReturn(0L);
        when(repo.saveAll(any())).thenThrow(new DataIntegrityViolationException("dup key"));
        assertDoesNotThrow(() -> service.ensureDefaults(T, O));
    }

    @Test
    @DisplayName("getBoard materialises defaults then returns the board in order")
    void getBoardEnsuresThenReads() {
        when(repo.countBoard(T, O)).thenReturn(3L); // already populated → skip seed
        List<TaskStatusEntity> board = List.of(status("pending", TaskStatusCategory.PENDING, true));
        when(repo.findBoard(T, O)).thenReturn(board);

        assertEquals(board, service.getBoard(T, O));
        verify(repo, never()).saveAll(any());
    }

    // ----------------------------------------------------------------- create

    @Test
    @DisplayName("createStatus derives a snake_case key, defaults position to max+1")
    void createStatusDerivesKey() {
        when(repo.countBoard(T, O)).thenReturn(7L);
        when(repo.findBoardKey(eq(T), eq(O), anyString())).thenReturn(Optional.empty());
        when(repo.maxPosition(T, O)).thenReturn(6);

        TaskStatusEntity created = service.createStatus(T, O, "QA Review", "in_review", "text-purple-500", 3);

        assertEquals("qa_review", created.getKey());
        assertEquals("QA Review", created.getLabel());
        assertEquals(TaskStatusCategory.IN_REVIEW.wireKey(), created.getCategory());
        assertEquals(7, created.getPosition());
        assertEquals(3, created.getWipLimit());
        assertFalse(created.isSystem());
    }

    @Test
    @DisplayName("createStatus dedups a colliding key with a numeric suffix")
    void createStatusDedupsKey() {
        when(repo.countBoard(T, O)).thenReturn(7L);
        when(repo.findBoardKey(T, O, "qa")).thenReturn(Optional.of(status("qa", TaskStatusCategory.IN_REVIEW, false)));
        when(repo.findBoardKey(T, O, "qa_2")).thenReturn(Optional.empty());
        when(repo.maxPosition(T, O)).thenReturn(0);

        TaskStatusEntity created = service.createStatus(T, O, "QA", "in_review", null, null);
        assertEquals("qa_2", created.getKey());
    }

    @Test
    @DisplayName("createStatus rejects blank label, unknown category, non-positive WIP, and over-cap boards")
    void createStatusValidation() {
        when(repo.countBoard(T, O)).thenReturn(7L);
        when(repo.findBoardKey(eq(T), eq(O), anyString())).thenReturn(Optional.empty());
        when(repo.maxPosition(T, O)).thenReturn(0);

        assertThrows(IllegalArgumentException.class, () -> service.createStatus(T, O, "  ", "in_review", null, null));
        assertThrows(IllegalArgumentException.class, () -> service.createStatus(T, O, "X", "bogus", null, null));
        assertThrows(IllegalArgumentException.class, () -> service.createStatus(T, O, "X", "in_review", null, 0));

        when(repo.countBoard(T, O)).thenReturn(40L);
        assertThrows(IllegalStateException.class, () -> service.createStatus(T, O, "X", "in_review", null, null));
    }

    // ----------------------------------------------------------------- update

    @Test
    @DisplayName("updateStatus patches only provided fields and can clear the WIP limit")
    void updateStatusPatches() {
        TaskStatusEntity existing = status("qa", TaskStatusCategory.IN_REVIEW, false);
        existing.setWipLimit(5);
        when(repo.findScoped(existing.getId(), T, O)).thenReturn(Optional.of(existing));

        TaskStatusEntity updated = service.updateStatus(T, O, existing.getId(),
                "Quality", null, "text-pink-500", null, true, true);

        assertEquals("Quality", updated.getLabel());
        assertEquals("text-pink-500", updated.getColor());
        assertNull(updated.getWipLimit());     // cleared
        assertTrue(updated.isHidden());
        assertEquals(TaskStatusCategory.IN_REVIEW.wireKey(), updated.getCategory()); // unchanged
    }

    @Test
    @DisplayName("updateStatus refuses to re-category a system status")
    void updateStatusSystemCategoryLocked() {
        TaskStatusEntity sys = status("in_review", TaskStatusCategory.IN_REVIEW, true);
        when(repo.findScoped(sys.getId(), T, O)).thenReturn(Optional.of(sys));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateStatus(T, O, sys.getId(), null, "done", null, null, null, null));
    }

    @Test
    @DisplayName("updateStatus on a missing id fails")
    void updateStatusNotFound() {
        UUID id = UUID.randomUUID();
        when(repo.findScoped(id, T, O)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> service.updateStatus(T, O, id, "x", null, null, null, null, null));
    }

    // ----------------------------------------------------------------- delete

    @Test
    @DisplayName("deleteStatus refuses a system column")
    void deleteStatusSystemRejected() {
        TaskStatusEntity sys = status("in_review", TaskStatusCategory.IN_REVIEW, true);
        when(repo.findScoped(sys.getId(), T, O)).thenReturn(Optional.of(sys));
        assertThrows(IllegalArgumentException.class, () -> service.deleteStatus(T, O, sys.getId()));
        verify(repo, never()).delete(any());
    }

    @Test
    @DisplayName("deleteStatus removes a custom column and reports its category's default as fallback")
    void deleteStatusReturnsFallback() {
        TaskStatusEntity custom = status("qa", TaskStatusCategory.IN_REVIEW, false);
        when(repo.findScoped(custom.getId(), T, O)).thenReturn(Optional.of(custom));
        // After delete, resolveDefaultKey reads the board and prefers the system in_review row.
        when(repo.findBoard(T, O)).thenReturn(List.of(status("in_review", TaskStatusCategory.IN_REVIEW, true)));

        TaskStatusService.DeletedStatus res = service.deleteStatus(T, O, custom.getId());

        verify(repo).delete(custom);
        assertEquals("qa", res.deletedKey());
        assertEquals("in_review", res.fallbackKey());
    }

    @Test
    @DisplayName("deleteStatusAndRelocate deletes, relocates the column's tasks to the fallback, and reports the moved count")
    void deleteStatusAndRelocateMovesTasks() {
        TaskStatusEntity custom = status("qa", TaskStatusCategory.IN_REVIEW, false);
        when(repo.findScoped(custom.getId(), T, O)).thenReturn(Optional.of(custom));
        when(repo.findBoard(T, O)).thenReturn(List.of(status("in_review", TaskStatusCategory.IN_REVIEW, true)));
        when(taskRepo.relocateStatusKey(T, O, "qa", "in_review")).thenReturn(3);

        TaskStatusService.DeletedStatusResult res = service.deleteStatusAndRelocate(T, O, custom.getId());

        verify(repo).delete(custom);
        verify(taskRepo).relocateStatusKey(T, O, "qa", "in_review");
        assertEquals("qa", res.deletedKey());
        assertEquals("in_review", res.fallbackKey());
        assertEquals(3, res.movedTasks());
    }

    @Test
    @DisplayName("deleteStatusAndRelocate propagates a system-column rejection and never relocates")
    void deleteStatusAndRelocateSystemRejected() {
        TaskStatusEntity sys = status("in_review", TaskStatusCategory.IN_REVIEW, true);
        when(repo.findScoped(sys.getId(), T, O)).thenReturn(Optional.of(sys));

        assertThrows(IllegalArgumentException.class, () -> service.deleteStatusAndRelocate(T, O, sys.getId()));

        verify(repo, never()).delete(any());
        verify(taskRepo, never()).relocateStatusKey(any(), any(), any(), any());
    }

    // ---------------------------------------------------------------- reorder

    @Test
    @DisplayName("reorder assigns ascending positions following the requested order")
    void reorderAssignsPositions() {
        when(repo.countBoard(T, O)).thenReturn(2L);
        TaskStatusEntity a = status("a", TaskStatusCategory.PENDING, true);
        TaskStatusEntity b = status("b", TaskStatusCategory.DONE, true);
        when(repo.findBoard(T, O)).thenReturn(List.of(a, b));

        service.reorder(T, O, List.of(b.getId(), a.getId()));

        assertEquals(0, b.getPosition());
        assertEquals(1, a.getPosition());
    }

    @Test
    @DisplayName("reorder rejects an id that is not on the board")
    void reorderRejectsForeignId() {
        when(repo.countBoard(T, O)).thenReturn(1L);
        TaskStatusEntity a = status("a", TaskStatusCategory.PENDING, true);
        when(repo.findBoard(T, O)).thenReturn(List.of(a));
        assertThrows(IllegalArgumentException.class,
                () -> service.reorder(T, O, List.of(UUID.randomUUID())));
    }

    // -------------------------------------------------------- engine resolvers

    @Test
    @DisplayName("resolveDefaultKey prefers a system status of the category over a custom one")
    void resolveDefaultPrefersSystem() {
        when(repo.findBoard(T, O)).thenReturn(List.of(
                status("qa", TaskStatusCategory.IN_REVIEW, false),       // custom listed first
                status("in_review", TaskStatusCategory.IN_REVIEW, true)  // system
        ));
        assertEquals("in_review", service.resolveDefaultKey(T, O, TaskStatusCategory.IN_REVIEW));
    }

    @Test
    @DisplayName("resolveDefaultKey falls back to the first custom status when no system one exists")
    void resolveDefaultFallsBackToFirstCustom() {
        when(repo.findBoard(T, O)).thenReturn(List.of(status("qa", TaskStatusCategory.IN_REVIEW, false)));
        assertEquals("qa", service.resolveDefaultKey(T, O, TaskStatusCategory.IN_REVIEW));
    }

    @Test
    @DisplayName("resolveDefaultKey falls back to the historical literal on an un-materialised board")
    void resolveDefaultFallsBackToLiteral() {
        when(repo.findBoard(T, O)).thenReturn(List.of());
        assertEquals("completed", service.resolveDefaultKey(T, O, TaskStatusCategory.DONE));
    }

    @Test
    @DisplayName("categoryOf reads the board row, else classifies a historical literal")
    void categoryOf() {
        when(repo.findBoardKey(T, O, "qa")).thenReturn(Optional.of(status("qa", TaskStatusCategory.IN_REVIEW, false)));
        assertEquals(TaskStatusCategory.IN_REVIEW, service.categoryOf(T, O, "qa").orElseThrow());

        when(repo.findBoardKey(T, O, "completed")).thenReturn(Optional.empty());
        assertEquals(TaskStatusCategory.DONE, service.categoryOf(T, O, "completed").orElseThrow());

        when(repo.findBoardKey(T, O, "mystery")).thenReturn(Optional.empty());
        assertTrue(service.categoryOf(T, O, "mystery").isEmpty());
    }

    @Test
    @DisplayName("createStatus rejects the built-in 'deleted' (trash) category for custom columns")
    void createStatusRejectsDeletedCategory() {
        when(repo.countBoard(T, O)).thenReturn(7L);
        assertThrows(IllegalArgumentException.class,
                () -> service.createStatus(T, O, "Trash 2", "deleted", null, null));
    }

    // ------------------------------------------------ added coverage (gaps) ----

    @Test
    @DisplayName("updateStatus refuses to re-category a CUSTOM column to the built-in 'deleted' (trash) category")
    void updateStatusRejectsDeletedCategoryForCustom() {
        // The createStatus 'deleted' guard is tested; the parallel guard in updateStatus was not.
        TaskStatusEntity custom = status("qa", TaskStatusCategory.IN_REVIEW, false);
        when(repo.findScoped(custom.getId(), T, O)).thenReturn(Optional.of(custom));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateStatus(T, O, custom.getId(), null, "deleted", null, null, null, null));
        // The category must be left unchanged.
        assertEquals(TaskStatusCategory.IN_REVIEW.wireKey(), custom.getCategory());
    }

    @Test
    @DisplayName("updateStatus: clearWipLimit=true wins even when a wipLimit is also supplied")
    void updateStatusClearWipWinsOverWipLimit() {
        TaskStatusEntity existing = status("qa", TaskStatusCategory.IN_REVIEW, false);
        existing.setWipLimit(5);
        when(repo.findScoped(existing.getId(), T, O)).thenReturn(Optional.of(existing));
        // Both clearWipLimit=true AND wipLimit=9 - the clear must win (null), not 9.
        TaskStatusEntity updated = service.updateStatus(T, O, existing.getId(), null, null, null, 9, true, null);
        assertNull(updated.getWipLimit());
    }

    @Test
    @DisplayName("createStatus + updateStatus enforce the 60-char label boundary (60 ok, 61 rejected)")
    void labelLengthBoundary() {
        when(repo.countBoard(T, O)).thenReturn(7L);
        when(repo.findBoardKey(eq(T), eq(O), anyString())).thenReturn(Optional.empty());
        when(repo.maxPosition(T, O)).thenReturn(0);
        String ok = "x".repeat(60);
        String tooLong = "x".repeat(61);
        assertEquals(ok, service.createStatus(T, O, ok, "in_review", null, null).getLabel());
        assertThrows(IllegalArgumentException.class, () -> service.createStatus(T, O, tooLong, "in_review", null, null));

        TaskStatusEntity existing = status("qa", TaskStatusCategory.IN_REVIEW, false);
        when(repo.findScoped(existing.getId(), T, O)).thenReturn(Optional.of(existing));
        assertThrows(IllegalArgumentException.class,
                () -> service.updateStatus(T, O, existing.getId(), tooLong, null, null, null, null, null));
    }

    @Test
    @DisplayName("createStatus falls back to the 'status' key when the label has no alphanumerics, deduping the next one")
    void createStatusEmptyKeyFallsBackToStatus() {
        when(repo.countBoard(T, O)).thenReturn(7L);
        when(repo.maxPosition(T, O)).thenReturn(0);
        // A label of only punctuation normalizes to "" -> the 'status' fallback key.
        when(repo.findBoardKey(eq(T), eq(O), anyString())).thenReturn(Optional.empty());
        assertEquals("status", service.createStatus(T, O, "!!!", "in_review", null, null).getKey());

        // When 'status' is taken, the next punctuation-only label dedups to 'status_2'.
        when(repo.findBoardKey(T, O, "status")).thenReturn(Optional.of(status("status", TaskStatusCategory.IN_REVIEW, false)));
        when(repo.findBoardKey(T, O, "status_2")).thenReturn(Optional.empty());
        assertEquals("status_2", service.createStatus(T, O, "---", "in_review", null, null).getKey());
    }

    @Test
    @DisplayName("reorder keeps an unlisted status after the explicitly ordered ones (partial reorder never drops a column)")
    void reorderKeepsUnlistedAfterExplicit() {
        when(repo.countBoard(T, O)).thenReturn(3L);
        TaskStatusEntity a = status("a", TaskStatusCategory.PENDING, true);
        TaskStatusEntity b = status("b", TaskStatusCategory.IN_PROGRESS, true);
        TaskStatusEntity c = status("c", TaskStatusCategory.DONE, true);
        when(repo.findBoard(T, O)).thenReturn(List.of(a, b, c));

        // Only c then a are listed; b (omitted) must keep a position after them, not vanish.
        service.reorder(T, O, List.of(c.getId(), a.getId()));

        assertEquals(0, c.getPosition());
        assertEquals(1, a.getPosition());
        assertEquals(2, b.getPosition());
    }

    @Test
    @DisplayName("deleteStatusAndRelocate still calls relocate (and reports 0) for an empty column")
    void deleteStatusAndRelocateZeroTasks() {
        TaskStatusEntity custom = status("qa", TaskStatusCategory.IN_REVIEW, false);
        when(repo.findScoped(custom.getId(), T, O)).thenReturn(Optional.of(custom));
        when(repo.findBoard(T, O)).thenReturn(List.of(status("in_review", TaskStatusCategory.IN_REVIEW, true)));
        when(taskRepo.relocateStatusKey(T, O, "qa", "in_review")).thenReturn(0);

        TaskStatusService.DeletedStatusResult res = service.deleteStatusAndRelocate(T, O, custom.getId());

        verify(taskRepo).relocateStatusKey(T, O, "qa", "in_review");
        assertEquals(0, res.movedTasks());
        assertEquals("in_review", res.fallbackKey());
    }
}
