package com.apimarketplace.agent.domain;

import java.util.Optional;

/**
 * Canonical lifecycle role a board status maps to.
 * <p>
 * The agent state machine (claim / complete / review / approve / reject /
 * cancel / soft-delete) reasons about the CATEGORY, never the literal status
 * key. This lets a board carry any number of custom statuses (e.g. a "QA" and a
 * "Staging" column both in the {@link #IN_REVIEW} category) while the
 * worker/reviewer loop still knows what "in review" or "done" means.
 * <p>
 * Each category names the historical default status key it resolves to when the
 * engine must MOVE a task into that category (see
 * {@code TaskStatusService.resolveDefaultKey}). For an un-customised board the
 * default keys equal the seven historical literals, so behaviour is identical.
 */
public enum TaskStatusCategory {

    PENDING("pending", AgentTaskEntity.STATUS_PENDING, true, false),
    IN_PROGRESS("in_progress", AgentTaskEntity.STATUS_IN_PROGRESS, true, false),
    IN_REVIEW("in_review", AgentTaskEntity.STATUS_IN_REVIEW, true, false),
    DONE("done", AgentTaskEntity.STATUS_COMPLETED, false, true),
    FAILED("failed", AgentTaskEntity.STATUS_FAILED, false, true),
    CANCELLED("cancelled", AgentTaskEntity.STATUS_CANCELLED, false, true),
    /**
     * The trash. A status in this category is soft-deleted: it leaves its
     * previous column (preserved in {@code previous_status}) and is hard-purged
     * after the retention window. Soft-delete remains driven by
     * {@code agent_tasks.deleted_at}; this category only classifies the column.
     */
    DELETED("deleted", AgentTaskEntity.STATUS_DELETED, false, false);

    private final String wireKey;
    private final String defaultStatusKey;
    private final boolean active;
    private final boolean terminal;

    TaskStatusCategory(String wireKey, String defaultStatusKey, boolean active, boolean terminal) {
        this.wireKey = wireKey;
        this.defaultStatusKey = defaultStatusKey;
        this.active = active;
        this.terminal = terminal;
    }

    /** Lowercase token persisted in {@code task_statuses.category} and sent over the wire. */
    public String wireKey() {
        return wireKey;
    }

    /** Historical default status key for this category (the pre-V351 literal). */
    public String defaultStatusKey() {
        return defaultStatusKey;
    }

    /**
     * Active = the task is live on the board and visible in an agent inbox
     * ({@link #PENDING}, {@link #IN_PROGRESS}, {@link #IN_REVIEW}). The board's
     * "active" classification drives inbox / backlog / cascade-cancel queries.
     */
    public boolean isActive() {
        return active;
    }

    /**
     * Terminal = a finished outcome the worker/reviewer loop stops on
     * ({@link #DONE}, {@link #FAILED}, {@link #CANCELLED}). {@link #DELETED} is
     * NOT terminal: it is an orthogonal soft-delete state.
     */
    public boolean isTerminal() {
        return terminal;
    }

    /** Parse a wire token (case-insensitive); empty when unknown. */
    public static Optional<TaskStatusCategory> fromWire(String raw) {
        if (raw == null) return Optional.empty();
        String norm = raw.trim().toLowerCase();
        for (TaskStatusCategory c : values()) {
            if (c.wireKey.equals(norm)) return Optional.of(c);
        }
        return Optional.empty();
    }

    /**
     * The category of one of the seven historical default status keys, used to
     * classify a task whose board has not been customised (no task_statuses
     * rows). Empty when the key is not a known default literal.
     */
    public static Optional<TaskStatusCategory> ofDefaultKey(String statusKey) {
        if (statusKey == null) return Optional.empty();
        for (TaskStatusCategory c : values()) {
            if (c.defaultStatusKey.equals(statusKey)) return Optional.of(c);
        }
        return Optional.empty();
    }
}
