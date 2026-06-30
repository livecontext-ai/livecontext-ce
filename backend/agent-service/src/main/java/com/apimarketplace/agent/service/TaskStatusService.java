package com.apimarketplace.agent.service;

import com.apimarketplace.agent.domain.TaskStatusCategory;
import com.apimarketplace.agent.domain.TaskStatusEntity;
import com.apimarketplace.agent.repository.AgentTaskRepository;
import com.apimarketplace.agent.repository.TaskStatusRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the configurable board columns ({@link TaskStatusEntity}).
 * <p>
 * The seven historical statuses are materialised per board on first access as
 * {@link TaskStatusEntity#isSystem() system} rows with their historical keys, so
 * an un-customised board is byte-identical to the pre-V351 behaviour. Users may
 * rename / reorder / recolour / hide / WIP-cap any column and add custom ones.
 * <p>
 * The {@link #resolveDefaultKey} / {@link #categoryOf} resolvers are the bridge
 * the agent state machine uses so it can reason about {@link TaskStatusCategory}
 * rather than literal status keys (the F4 "engine remap").
 */
@Service
public class TaskStatusService {

    private static final Logger logger = LoggerFactory.getLogger(TaskStatusService.class);

    /** The seven defaults, in display order, matching the pre-V351 board. */
    private record SeedStatus(String key, String label, TaskStatusCategory category,
                              String color, boolean hidden) {}

    private static final List<SeedStatus> DEFAULT_SEEDS = List.of(
            new SeedStatus("pending", "Pending", TaskStatusCategory.PENDING, "text-gray-400", false),
            new SeedStatus("in_progress", "In Progress", TaskStatusCategory.IN_PROGRESS, "text-blue-500", false),
            new SeedStatus("in_review", "In Review", TaskStatusCategory.IN_REVIEW, "text-orange-500", false),
            new SeedStatus("completed", "Completed", TaskStatusCategory.DONE, "text-green-500", false),
            new SeedStatus("failed", "Failed", TaskStatusCategory.FAILED, "text-red-500", true),
            new SeedStatus("cancelled", "Cancelled", TaskStatusCategory.CANCELLED, "text-gray-400", true),
            new SeedStatus("deleted", "Deleted", TaskStatusCategory.DELETED, "text-red-400", true)
    );

    public static final int MAX_LABEL_LEN = 60;
    public static final int MAX_STATUSES_PER_BOARD = 40;

    private final TaskStatusRepository repository;
    private final AgentTaskRepository taskRepository;

    public TaskStatusService(TaskStatusRepository repository, AgentTaskRepository taskRepository) {
        this.repository = repository;
        this.taskRepository = taskRepository;
    }

    // ========================================================================
    // Read
    // ========================================================================

    /**
     * Returns the board's columns in display order, lazily materialising the
     * seven defaults the first time a board is touched. Idempotent and
     * race-safe: a concurrent first-access loses the unique-index race and
     * simply re-reads the winner's rows.
     */
    @Transactional
    public List<TaskStatusEntity> getBoard(String tenantId, String organizationId) {
        ensureDefaults(tenantId, organizationId);
        return repository.findBoard(tenantId, organizationId);
    }

    /**
     * Inserts the seven default rows when the board has none. No-op once a board
     * has any rows (defaults already present, or the user has customised).
     */
    @Transactional
    public void ensureDefaults(String tenantId, String organizationId) {
        if (repository.countBoard(tenantId, organizationId) > 0) {
            return;
        }
        try {
            List<TaskStatusEntity> rows = new ArrayList<>(DEFAULT_SEEDS.size());
            int position = 0;
            for (SeedStatus seed : DEFAULT_SEEDS) {
                TaskStatusEntity e = new TaskStatusEntity();
                e.setTenantId(tenantId);
                e.setOrganizationId(organizationId);
                e.setKey(seed.key());
                e.setLabel(seed.label());
                e.setCategory(seed.category().wireKey());
                e.setColor(seed.color());
                e.setHidden(seed.hidden());
                e.setSystem(true);
                e.setPosition(position++);
                rows.add(e);
            }
            repository.saveAll(rows);
            logger.debug("Seeded {} default task statuses for board tenant={} org={}",
                    rows.size(), tenantId, organizationId);
        } catch (DataIntegrityViolationException race) {
            // A concurrent first-access already seeded the board; the unique
            // index rejected our duplicate. The winner's rows are authoritative.
            logger.debug("Default task statuses already seeded concurrently for tenant={} org={}",
                    tenantId, organizationId);
        }
    }

    // ========================================================================
    // Engine resolvers (used by the agent state machine - F4 step 2)
    // ========================================================================

    /**
     * The status key the engine should set when moving a task into
     * {@code category} on this board. Prefers a system status of that category
     * (the historical key), then the lowest-position status of the category,
     * and finally the category's historical default literal when the board has
     * not been materialised. Never returns null.
     */
    @Transactional(readOnly = true)
    public String resolveDefaultKey(String tenantId, String organizationId, TaskStatusCategory category) {
        List<TaskStatusEntity> board = repository.findBoard(tenantId, organizationId);
        TaskStatusEntity systemMatch = null;
        TaskStatusEntity firstMatch = null;
        for (TaskStatusEntity s : board) {
            if (s.categoryEnum() != category) continue;
            if (firstMatch == null) firstMatch = s;
            if (s.isSystem()) { systemMatch = s; break; }
        }
        if (systemMatch != null) return systemMatch.getKey();
        if (firstMatch != null) return firstMatch.getKey();
        return category.defaultStatusKey();
    }

    /**
     * The category a given status key maps to on this board. Falls back to the
     * historical default-key classification when the board has not been
     * materialised (so callers work before {@link #ensureDefaults} ever ran).
     */
    @Transactional(readOnly = true)
    public Optional<TaskStatusCategory> categoryOf(String tenantId, String organizationId, String statusKey) {
        if (statusKey == null) return Optional.empty();
        return repository.findBoardKey(tenantId, organizationId, statusKey)
                .map(TaskStatusEntity::categoryEnum)
                .or(() -> TaskStatusCategory.ofDefaultKey(statusKey));
    }

    // ========================================================================
    // Mutations
    // ========================================================================

    /** Create a custom column. Key is derived from the label, deduped per board. */
    @Transactional
    public TaskStatusEntity createStatus(String tenantId, String organizationId,
                                         String label, String categoryWire, String color, Integer wipLimit) {
        ensureDefaults(tenantId, organizationId);
        String cleanLabel = requireLabel(label);
        TaskStatusCategory category = TaskStatusCategory.fromWire(categoryWire)
                .orElseThrow(() -> new IllegalArgumentException(
                        "invalid category '" + categoryWire + "'. Valid: pending, in_progress, in_review, done, failed, cancelled, deleted"));
        if (category == TaskStatusCategory.DELETED) {
            throw new IllegalArgumentException(
                    "the Deleted/trash column is built-in; a custom column cannot use the 'deleted' category");
        }
        validateWipLimit(wipLimit);
        if (repository.countBoard(tenantId, organizationId) >= MAX_STATUSES_PER_BOARD) {
            throw new IllegalStateException("a board may hold at most " + MAX_STATUSES_PER_BOARD + " columns");
        }
        String key = uniqueKey(tenantId, organizationId, cleanLabel);
        TaskStatusEntity e = new TaskStatusEntity();
        e.setTenantId(tenantId);
        e.setOrganizationId(organizationId);
        e.setKey(key);
        e.setLabel(cleanLabel);
        e.setCategory(category.wireKey());
        e.setColor(trimToNull(color));
        e.setWipLimit(wipLimit);
        e.setSystem(false);
        e.setHidden(false);
        e.setPosition(repository.maxPosition(tenantId, organizationId) + 1);
        return repository.save(e);
    }

    /**
     * Patch a column. Null fields are left unchanged. A system status's
     * {@code category} cannot be changed (the engine relies on its mapping) and
     * none of the seven system keys can be re-keyed.
     */
    @Transactional
    public TaskStatusEntity updateStatus(String tenantId, String organizationId, UUID id,
                                         String label, String categoryWire, String color,
                                         Integer wipLimit, Boolean clearWipLimit, Boolean hidden) {
        TaskStatusEntity e = repository.findScoped(id, tenantId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("status not found: " + id));
        if (label != null) e.setLabel(requireLabel(label));
        if (color != null) e.setColor(trimToNull(color));
        if (Boolean.TRUE.equals(clearWipLimit)) {
            e.setWipLimit(null);
        } else if (wipLimit != null) {
            validateWipLimit(wipLimit);
            e.setWipLimit(wipLimit);
        }
        if (hidden != null) e.setHidden(hidden);
        if (categoryWire != null) {
            if (e.isSystem()) {
                throw new IllegalArgumentException("a system status's category is fixed and cannot be changed");
            }
            TaskStatusCategory category = TaskStatusCategory.fromWire(categoryWire)
                    .orElseThrow(() -> new IllegalArgumentException("invalid category '" + categoryWire + "'"));
            if (category == TaskStatusCategory.DELETED) {
                throw new IllegalArgumentException(
                        "a custom column cannot use the built-in 'deleted' category");
            }
            e.setCategory(category.wireKey());
        }
        return repository.save(e);
    }

    /** Outcome of {@link #deleteStatus}: the removed key and where its tasks must move. */
    public record DeletedStatus(String deletedKey, String fallbackKey) {}

    /**
     * Delete a custom column. System columns cannot be deleted (they guarantee
     * the engine always has a target for every required category). Returns the
     * removed key plus the fallback status key (its category's resolved default)
     * that any task sitting in the deleted column must be relocated to; the
     * caller performs the relocation against {@code agent_tasks}.
     */
    @Transactional
    public DeletedStatus deleteStatus(String tenantId, String organizationId, UUID id) {
        TaskStatusEntity e = repository.findScoped(id, tenantId, organizationId)
                .orElseThrow(() -> new IllegalArgumentException("status not found: " + id));
        if (e.isSystem()) {
            throw new IllegalArgumentException("the built-in '" + e.getKey() + "' column cannot be deleted; hide it instead");
        }
        String deletedKey = e.getKey();
        repository.delete(e);
        String fallbackKey = resolveDefaultKey(tenantId, organizationId, e.categoryEnum());
        return new DeletedStatus(deletedKey, fallbackKey);
    }

    /** Outcome of {@link #deleteStatusAndRelocate}: the removed key, where its tasks went, and how many moved. */
    public record DeletedStatusResult(String deletedKey, String fallbackKey, int movedTasks) {}

    /**
     * Delete a custom column AND relocate any task sitting in it to the fallback
     * status, atomically in one transaction. This is the single service entry
     * point the controller calls: keeping the delete + relocate (and the
     * validation that precedes them) inside one transactional service method
     * means a validation failure (system column / unknown id) rolls back cleanly
     * and surfaces as the original {@code IllegalArgumentException} to a
     * non-transactional controller, instead of poisoning a controller-level
     * transaction into an {@code UnexpectedRollbackException} (HTTP 500).
     */
    @Transactional
    public DeletedStatusResult deleteStatusAndRelocate(String tenantId, String organizationId, UUID id) {
        DeletedStatus res = deleteStatus(tenantId, organizationId, id);
        int moved = taskRepository.relocateStatusKey(
                tenantId, organizationId, res.deletedKey(), res.fallbackKey());
        return new DeletedStatusResult(res.deletedKey(), res.fallbackKey(), moved);
    }

    /** Apply a new display order. Any id omitted keeps its current position after the listed ones. */
    @Transactional
    public List<TaskStatusEntity> reorder(String tenantId, String organizationId, List<UUID> orderedIds) {
        ensureDefaults(tenantId, organizationId);
        Map<UUID, TaskStatusEntity> byId = new LinkedHashMap<>();
        for (TaskStatusEntity s : repository.findBoard(tenantId, organizationId)) {
            byId.put(s.getId(), s);
        }
        int position = 0;
        for (UUID id : orderedIds) {
            TaskStatusEntity s = byId.remove(id);
            if (s == null) {
                throw new IllegalArgumentException("status not on this board: " + id);
            }
            s.setPosition(position++);
        }
        // Any not listed keep their relative order after the explicitly ordered set.
        for (TaskStatusEntity s : byId.values()) {
            s.setPosition(position++);
        }
        return repository.findBoard(tenantId, organizationId);
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private String requireLabel(String label) {
        String trimmed = label == null ? "" : label.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("label is required");
        if (trimmed.length() > MAX_LABEL_LEN) {
            throw new IllegalArgumentException("label exceeds " + MAX_LABEL_LEN + " characters");
        }
        return trimmed;
    }

    private void validateWipLimit(Integer wipLimit) {
        if (wipLimit != null && wipLimit <= 0) {
            throw new IllegalArgumentException("wip_limit must be a positive integer (or omit it for no limit)");
        }
    }

    /** lowercase, non-alphanumeric → '_', collapse, trim, cap at 40; deduped per board. */
    private String uniqueKey(String tenantId, String organizationId, String label) {
        String base = normalizeKey(label);
        if (base.isEmpty()) base = "status";
        String candidate = base;
        int suffix = 2;
        while (repository.findBoardKey(tenantId, organizationId, candidate).isPresent()) {
            String tail = "_" + suffix++;
            candidate = base.length() + tail.length() > 40
                    ? base.substring(0, 40 - tail.length()) + tail
                    : base + tail;
        }
        return candidate;
    }

    private static String normalizeKey(String label) {
        String lower = label.toLowerCase();
        StringBuilder sb = new StringBuilder(lower.length());
        boolean lastUnderscore = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                lastUnderscore = false;
            } else if (!lastUnderscore) {
                sb.append('_');
                lastUnderscore = true;
            }
        }
        String key = sb.toString().replaceAll("^_+|_+$", "");
        return key.length() > 40 ? key.substring(0, 40).replaceAll("_+$", "") : key;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
