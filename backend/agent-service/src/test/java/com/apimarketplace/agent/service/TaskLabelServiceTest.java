package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.TaskLabelEntity;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.agent.repository.TaskLabelRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * F2: {@link TaskLabelService} catalog CRUD, the delete → task-scrub, and
 * validateLabelIds (parse / dedup / existence / caps).
 */
class TaskLabelServiceTest {

    private static final String T = "tenant-1";
    private static final String O = "org-1";

    private TaskLabelRepository repo;
    private AgentTaskRepository taskRepo;
    private TaskLabelService service;

    @BeforeEach
    void setUp() {
        repo = mock(TaskLabelRepository.class);
        taskRepo = mock(AgentTaskRepository.class);
        service = new TaskLabelService(repo, taskRepo);
        when(repo.save(any(TaskLabelEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repo.saveAndFlush(any(TaskLabelEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private TaskLabelEntity label(UUID id, String name) {
        TaskLabelEntity e = new TaskLabelEntity();
        e.setId(id);
        e.setTenantId(T);
        e.setOrganizationId(O);
        e.setName(name);
        return e;
    }

    @Test
    @DisplayName("create trims the name, keeps the color, and rejects blank / over-cap")
    void create() {
        when(repo.findBoard(T, O)).thenReturn(List.of());
        TaskLabelEntity created = service.create(T, O, "  Urgent  ", "text-red-500");
        assertEquals("Urgent", created.getName());
        assertEquals("text-red-500", created.getColor());

        assertThrows(IllegalArgumentException.class, () -> service.create(T, O, "   ", null));

        when(repo.findBoard(T, O)).thenReturn(Collections.nCopies(100, new TaskLabelEntity()));
        assertThrows(IllegalStateException.class, () -> service.create(T, O, "X", null));
    }

    @Test
    @DisplayName("create rejects a duplicate name (case-insensitive) with a 400-mapped IllegalArgumentException, not a DB 500")
    void createRejectsDuplicateName() {
        // Regression: a duplicate name used to slip past the service and hit the
        // uq_task_labels_board_name unique index -> DataIntegrityViolationException -> HTTP 500.
        UUID existing = UUID.randomUUID();
        when(repo.findBoard(T, O)).thenReturn(List.of());
        // The service trims the input ("  urgent  " -> "urgent") before the lookup; the
        // repository query itself is case-insensitive (lower(name) = lower(:name)).
        when(repo.findBoardByName(T, O, "urgent")).thenReturn(Optional.of(label(existing, "Urgent")));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(T, O, "  urgent  ", null)); // padding-trimmed name still collides
        assertTrue(ex.getMessage().contains("already exists"));
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("create maps a concurrent unique-index hit (the pre-check race) to a 400, not a 500")
    void createMapsRaceToBadRequest() {
        when(repo.findBoard(T, O)).thenReturn(List.of());
        when(repo.findBoardByName(T, O, "Urgent")).thenReturn(Optional.empty()); // pre-check passes
        when(repo.saveAndFlush(any(TaskLabelEntity.class)))
                .thenThrow(new DataIntegrityViolationException("uq_task_labels_board_name"));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.create(T, O, "Urgent", null));
        assertTrue(ex.getMessage().contains("already exists"));
    }

    @Test
    @DisplayName("getOrCreateByName reuses an existing label (case-insensitive) without creating")
    void getOrCreateReusesExisting() {
        UUID existing = UUID.randomUUID();
        // requireName trims "  Urgent  " -> "Urgent"; the repo lookup is case-insensitive.
        when(repo.findBoardByName(T, O, "Urgent")).thenReturn(Optional.of(label(existing, "urgent")));

        TaskLabelEntity got = service.getOrCreateByName(T, O, "  Urgent  ");

        assertEquals(existing, got.getId());
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("getOrCreateByName creates a new label when the name is absent on the board")
    void getOrCreateCreatesWhenAbsent() {
        when(repo.findBoardByName(T, O, "qa")).thenReturn(Optional.empty());
        when(repo.findBoard(T, O)).thenReturn(List.of()); // under the per-board cap

        TaskLabelEntity created = service.getOrCreateByName(T, O, "qa");

        assertEquals("qa", created.getName());
        verify(repo).saveAndFlush(any(TaskLabelEntity.class));
    }

    @Test
    @DisplayName("getOrCreateByName rejects a blank name")
    void getOrCreateRejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> service.getOrCreateByName(T, O, "   "));
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("update rejects renaming a label onto another label's name, but allows a no-op same-name save")
    void updateRejectsDuplicateRename() {
        UUID self = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        when(repo.findScoped(self, T, O)).thenReturn(Optional.of(label(self, "Bug")));
        // Renaming "Bug" -> "Urgent" where another label already owns "Urgent".
        when(repo.findBoardByName(T, O, "Urgent")).thenReturn(Optional.of(label(other, "Urgent")));
        assertThrows(IllegalArgumentException.class, () -> service.update(T, O, self, "Urgent", null));

        // Renaming a label to its OWN current name is fine (findBoardByName returns itself).
        when(repo.findBoardByName(T, O, "Bug")).thenReturn(Optional.of(label(self, "Bug")));
        TaskLabelEntity ok = service.update(T, O, self, "Bug", "text-red-500");
        assertEquals("Bug", ok.getName());
        assertEquals("text-red-500", ok.getColor());
    }

    @Test
    @DisplayName("delete removes the label and scrubs its id from every task")
    void deleteScrubs() {
        UUID id = UUID.randomUUID();
        when(repo.findScoped(id, T, O)).thenReturn(Optional.of(label(id, "Bug")));

        service.delete(T, O, id);

        verify(repo).delete(any(TaskLabelEntity.class));
        verify(taskRepo).removeLabelFromTasks(T, O, id.toString());
    }

    @Test
    @DisplayName("validateLabelIds returns canonical ids, deduped in order")
    void validateOk() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(repo.findBoardByIds(any(), eq(T), eq(O)))
                .thenReturn(List.of(label(a, "A"), label(b, "B")));

        List<String> out = service.validateLabelIds(T, O,
                new ArrayList<>(List.of(a.toString(), b.toString(), a.toString()))); // dup a
        assertEquals(List.of(a.toString(), b.toString()), out);
    }

    @Test
    @DisplayName("validateLabelIds rejects a malformed id and an unknown id")
    void validateRejects() {
        assertThrows(IllegalArgumentException.class,
                () -> service.validateLabelIds(T, O, List.of("not-a-uuid")));

        UUID a = UUID.randomUUID();
        when(repo.findBoardByIds(any(), eq(T), eq(O))).thenReturn(List.of()); // none found
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.validateLabelIds(T, O, List.of(a.toString())));
        assertTrue(ex.getMessage().contains("unknown label id"));
    }

    @Test
    @DisplayName("validateLabelIds returns empty for empty input and rejects over-cap")
    void validateEdges() {
        assertEquals(List.of(), service.validateLabelIds(T, O, null));
        assertEquals(List.of(), service.validateLabelIds(T, O, List.of()));

        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 26; i++) tooMany.add(UUID.randomUUID().toString());
        assertThrows(IllegalArgumentException.class, () -> service.validateLabelIds(T, O, tooMany));
    }
}
