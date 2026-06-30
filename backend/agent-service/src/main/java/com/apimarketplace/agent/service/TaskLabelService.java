package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.TaskLabelEntity;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.agent.repository.TaskLabelRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Owns the per-board label catalog ({@link TaskLabelEntity}). Tasks reference
 * labels by id via the inline {@code agent_tasks.label_ids} array; this service
 * validates those ids against the catalog and scrubs an id from every task when
 * its label is deleted.
 */
@Service
public class TaskLabelService {

    public static final int MAX_NAME_LEN = 60;
    public static final int MAX_LABELS_PER_BOARD = 100;
    public static final int MAX_LABELS_PER_TASK = 25;

    private final TaskLabelRepository repository;
    private final AgentTaskRepository taskRepository;

    public TaskLabelService(TaskLabelRepository repository, AgentTaskRepository taskRepository) {
        this.repository = repository;
        this.taskRepository = taskRepository;
    }

    @Transactional(readOnly = true)
    public List<TaskLabelEntity> list(String tenantId, String organizationId) {
        return repository.findBoard(tenantId, organizationId);
    }

    @Transactional
    public TaskLabelEntity create(String tenantId, String organizationId, String name, String color) {
        String cleanName = requireName(name);
        requireNameAvailable(tenantId, organizationId, cleanName, null);
        if (repository.findBoard(tenantId, organizationId).size() >= MAX_LABELS_PER_BOARD) {
            throw new IllegalStateException("a board may hold at most " + MAX_LABELS_PER_BOARD + " labels");
        }
        TaskLabelEntity e = new TaskLabelEntity();
        e.setTenantId(tenantId);
        e.setOrganizationId(organizationId);
        e.setName(cleanName);
        e.setColor(trimToNull(color));
        return saveCatchingDuplicate(e, cleanName);
    }

    /**
     * Resolve a label NAME to a board label, creating it if absent. An existing label is reused
     * (case-insensitive, the same match the {@code uq_task_labels_board_name} index enforces);
     * otherwise a new label is created via {@link #create}. This lets an agent label a task by
     * name without first looking up a UUID. On the rare concurrent-create race, {@code create}
     * surfaces the duplicate as a 400 - a retry then finds the now-existing label.
     */
    @Transactional
    public TaskLabelEntity getOrCreateByName(String tenantId, String organizationId, String name) {
        String cleanName = requireName(name);
        return repository.findBoardByName(tenantId, organizationId, cleanName)
                .orElseGet(() -> create(tenantId, organizationId, cleanName, null));
    }

    @Transactional
    public TaskLabelEntity update(String tenantId, String organizationId, UUID id, String name, String color) {
        TaskLabelEntity e = repository.findScoped(id, tenantId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("label not found: " + id));
        String cleanName = e.getName();
        if (name != null) {
            cleanName = requireName(name);
            requireNameAvailable(tenantId, organizationId, cleanName, id);
            e.setName(cleanName);
        }
        if (color != null) e.setColor(trimToNull(color));
        return saveCatchingDuplicate(e, cleanName);
    }

    /**
     * Rejects a duplicate label name (case-insensitive, board-scoped) with a
     * friendly 400 instead of letting the {@code uq_task_labels_board_name} unique
     * index surface as an opaque 500. {@code selfId} (nullable) is the label being
     * renamed, so it does not collide with itself.
     */
    private void requireNameAvailable(String tenantId, String organizationId, String name, UUID selfId) {
        repository.findBoardByName(tenantId, organizationId, name)
                .filter(other -> selfId == null || !other.getId().equals(selfId))
                .ifPresent(other -> {
                    throw new IllegalArgumentException("a label named '" + name + "' already exists on this board");
                });
    }

    /** Backstop for the rare concurrent-create race the pre-check cannot win: map the unique-index hit to a 400. */
    private TaskLabelEntity saveCatchingDuplicate(TaskLabelEntity e, String name) {
        try {
            return repository.saveAndFlush(e);
        } catch (DataIntegrityViolationException dup) {
            throw new IllegalArgumentException("a label named '" + name + "' already exists on this board");
        }
    }

    /** Delete a label from the catalog and scrub its id from every task on the board. */
    @Transactional
    public void delete(String tenantId, String organizationId, UUID id) {
        TaskLabelEntity e = repository.findScoped(id, tenantId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("label not found: " + id));
        repository.delete(e);
        taskRepository.removeLabelFromTasks(tenantId, organizationId, id.toString());
    }

    /**
     * Parse + validate a raw list of label-id strings against the board catalog.
     * Deduplicates (preserving order), rejects malformed ids, an over-cap count,
     * or any id not on the board. Returns the canonical id strings to store.
     */
    @Transactional(readOnly = true)
    public List<String> validateLabelIds(String tenantId, String organizationId, List<String> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) return List.of();
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        for (String s : rawIds) {
            if (s == null) continue;
            try {
                ids.add(UUID.fromString(s.trim()));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("invalid label id: " + s);
            }
        }
        if (ids.isEmpty()) return List.of();
        if (ids.size() > MAX_LABELS_PER_TASK) {
            throw new IllegalArgumentException("a task may carry at most " + MAX_LABELS_PER_TASK + " labels");
        }
        List<TaskLabelEntity> found = repository.findBoardByIds(new ArrayList<>(ids), tenantId, organizationId);
        if (found.size() != ids.size()) {
            Set<UUID> foundIds = found.stream().map(TaskLabelEntity::getId).collect(Collectors.toSet());
            String missing = ids.stream().filter(i -> !foundIds.contains(i))
                    .map(UUID::toString).collect(Collectors.joining(", "));
            throw new IllegalArgumentException("unknown label id(s) for this board: " + missing);
        }
        return ids.stream().map(UUID::toString).toList();
    }

    private String requireName(String name) {
        String t = name == null ? "" : name.trim();
        if (t.isEmpty()) throw new IllegalArgumentException("label name is required");
        if (t.length() > MAX_NAME_LEN) throw new IllegalArgumentException("label name exceeds " + MAX_NAME_LEN + " characters");
        return t;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
