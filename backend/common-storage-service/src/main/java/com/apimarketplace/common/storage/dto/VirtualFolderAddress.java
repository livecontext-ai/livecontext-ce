package com.apimarketplace.common.storage.dto;

/**
 * Phase 2b - address of a VIRTUAL folder in the Files browser tree.
 *
 * <p>Virtual folders are NOT persisted. They group a workflow's files by
 * {@code workflow → epoch → spawn → iteration}, derived purely from the
 * run-context columns ({@code workflow_id, epoch, spawn, item_index}) already on
 * every workflow file row (Phase 2a). Only rows with {@code parent_folder_id IS NULL}
 * participate - a file moved into a manual folder leaves the virtual tree.</p>
 *
 * <p>The address is carried as a string in the {@code parentFolderId} request param. A run is
 * identified by {@code run_id} (an opaque string, never contains {@code /}); it sits between the
 * workflow and the epoch. The run segment is OPTIONAL: when a workflow has a single run the service
 * collapses it (no {@code /r…} segment, epoch is addressed directly under the workflow); when a
 * workflow has ≥2 runs the run segment pins which run an epoch belongs to. Grammar (a
 * {@code workflowId} is a UUID string and never contains {@code /}):</p>
 * <pre>
 *   wf:&lt;workflowId&gt;                              → WORKFLOW folder
 *   wf:&lt;workflowId&gt;/r&lt;runId&gt;                     → RUN folder (≥2 runs)
 *   wf:&lt;workflowId&gt;[/r&lt;runId&gt;]/e&lt;epoch&gt;          → EPOCH folder
 *   wf:&lt;workflowId&gt;[/r&lt;runId&gt;]/e&lt;epoch&gt;/s&lt;spawn&gt; → SPAWN folder
 *   wf:&lt;workflowId&gt;[/r&lt;runId&gt;]/e&lt;epoch&gt;/s&lt;spawn&gt;/i&lt;itemIndex&gt; → ITERATION folder
 * </pre>
 *
 * @param workflowId the {@code workflow_id} VARCHAR value (a UUID string). Never null for a
 *                   non-null address.
 * @param runId      the {@code run_id} of a specific run, or null when the run level is collapsed
 *                   (single-run workflow) - epoch/spawn/item are then unconstrained by run.
 * @param epoch      epoch index (≥0), or null if the address stops at the workflow/run level.
 * @param spawn      spawn index (≥0), or null if the address stops at the epoch level.
 * @param itemIndex  iteration item index (≥0), or null if the address stops at the spawn level.
 */
public record VirtualFolderAddress(String workflowId, String runId, Integer epoch, Integer spawn, Integer itemIndex) {

    /**
     * Backward-compatible constructor for run-collapsed addresses (no run level). Equivalent to
     * {@code new VirtualFolderAddress(workflowId, null, epoch, spawn, itemIndex)}.
     */
    public VirtualFolderAddress(String workflowId, Integer epoch, Integer spawn, Integer itemIndex) {
        this(workflowId, null, epoch, spawn, itemIndex);
    }

    /** Depth of a virtual address - which level of the tree it points at. */
    public enum Level {
        /** The workflow node itself (children = runs, or epochs when the single run is collapsed). */
        WORKFLOW,
        /** A single run of a workflow (children = epochs). Only addressed when the workflow has ≥2 runs. */
        RUN,
        /** A single epoch (children = spawns or iterations). */
        EPOCH,
        /** A single spawn within an epoch (children = iterations). */
        SPAWN,
        /** A single iteration (leaf - children = files). */
        ITERATION
    }

    /**
     * The depth this address points at: WORKFLOW if only the workflow id is set, RUN if a run is set
     * but no epoch, EPOCH if epoch is set but not spawn, SPAWN if spawn is set but not item, ITERATION
     * if item is set.
     */
    public Level level() {
        if (epoch == null) {
            return runId == null ? Level.WORKFLOW : Level.RUN;
        }
        if (spawn == null) {
            return Level.EPOCH;
        }
        if (itemIndex == null) {
            return Level.SPAWN;
        }
        return Level.ITERATION;
    }

    /**
     * Parse a {@code parentFolderId} token into a virtual address.
     *
     * <p>Returns {@code null} (the "this is not a virtual token" signal) for any token that is
     * null/blank, the root sentinel ({@code "root"}), does not start with {@code wf:}, or is malformed
     * in any way - out-of-order / unknown segment prefixes, a negative or non-numeric epoch/spawn/item,
     * a blank workflow id, or trailing junk. The parser is fully defensive: any unexpected shape → null.</p>
     */
    public static VirtualFolderAddress parse(String token) {
        if (token == null) {
            return null;
        }
        String trimmed = token.trim();
        if (trimmed.isEmpty() || "root".equalsIgnoreCase(trimmed)) {
            return null;
        }
        if (!trimmed.startsWith("wf:")) {
            return null;
        }

        String[] segments = trimmed.split("/", -1);
        // First segment is always "wf:<workflowId>"; reject a blank id.
        String wfSegment = segments[0];
        String workflowId = wfSegment.substring("wf:".length());
        if (workflowId.isBlank()) {
            return null;
        }

        String runId = null;
        Integer epoch = null;
        Integer spawn = null;
        Integer itemIndex = null;

        // Remaining segments must appear in the exact order [r<runId>], e<n>, s<n>, i<n>, each at
        // most once. We track the next expected position so out-of-order or duplicate segments are
        // rejected. Position: 0=run-or-epoch, 1=epoch, 2=spawn, 3=item, 4=none.
        int expected = 0;
        for (int i = 1; i < segments.length; i++) {
            String seg = segments[i];
            if (seg.isEmpty()) {
                return null; // empty segment (e.g. a stray "/")
            }
            char prefix = seg.charAt(0);
            String rest = seg.substring(1);
            switch (prefix) {
                case 'r' -> {
                    // The run segment, if present, is the FIRST sub-segment. Its value is an opaque
                    // run id (not numeric), so it is taken verbatim - only blank is rejected.
                    if (expected != 0 || rest.isBlank()) return null;
                    runId = rest;
                    expected = 1;
                }
                case 'e' -> {
                    if (expected > 1) return null; // epoch may follow the workflow (collapsed) or a run
                    Integer value = parseNonNegativeInt(rest);
                    if (value == null) return null;
                    epoch = value;
                    expected = 2;
                }
                case 's' -> {
                    if (expected != 2) return null;
                    Integer value = parseNonNegativeInt(rest);
                    if (value == null) return null;
                    spawn = value;
                    expected = 3;
                }
                case 'i' -> {
                    if (expected != 3) return null;
                    Integer value = parseNonNegativeInt(rest);
                    if (value == null) return null;
                    itemIndex = value;
                    expected = 4; // nothing may follow
                }
                default -> {
                    return null; // unknown prefix
                }
            }
        }

        return new VirtualFolderAddress(workflowId, runId, epoch, spawn, itemIndex);
    }

    /** Parse a non-negative integer, or null on any non-numeric / negative / overflow input. */
    private static Integer parseNonNegativeInt(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return null; // rejects '-', '+', spaces, anything non-numeric
            }
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null; // overflow
        }
    }

    /** Rebuild the canonical address string (used to set a child folder's navigation id). */
    public String toToken() {
        StringBuilder sb = new StringBuilder("wf:").append(workflowId);
        if (runId != null) {
            sb.append("/r").append(runId);
        }
        if (epoch != null) {
            sb.append("/e").append(epoch);
        }
        if (spawn != null) {
            sb.append("/s").append(spawn);
        }
        if (itemIndex != null) {
            sb.append("/i").append(itemIndex);
        }
        return sb.toString();
    }

    /**
     * The address one level UP - the deepest set coordinate removed
     * (ITERATION→SPAWN→EPOCH→RUN/WORKFLOW→WORKFLOW). An EPOCH's parent is the RUN when a run is set,
     * else the WORKFLOW (collapsed single run). Returns {@code null} at the WORKFLOW level: a workflow
     * folder's parent is the Files root, which is not a virtual address (the caller maps {@code null}
     * to {@code "root"}).
     */
    public VirtualFolderAddress parent() {
        if (itemIndex != null) {
            return new VirtualFolderAddress(workflowId, runId, epoch, spawn, null);
        }
        if (spawn != null) {
            return new VirtualFolderAddress(workflowId, runId, epoch, null, null);
        }
        if (epoch != null) {
            // EPOCH → RUN when this epoch belongs to a pinned run, else WORKFLOW (collapsed single run).
            return runId == null
                    ? new VirtualFolderAddress(workflowId, null, null, null, null)
                    : new VirtualFolderAddress(workflowId, runId, null, null, null);
        }
        if (runId != null) {
            return new VirtualFolderAddress(workflowId, null, null, null, null); // RUN → WORKFLOW
        }
        return null; // WORKFLOW level - parent is the root
    }
}
