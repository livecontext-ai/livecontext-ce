package com.apimarketplace.orchestrator.services.template;

/**
 * Why a path resolved to {@code null}.
 *
 * <p>{@link PathNavigator#getVariableValueFromMap} answers {@code null} for two
 * situations a reader must never confuse: the reference points at a value that is
 * genuinely null, or it points at nothing at all (a mistyped node label, a node
 * that never ran, a field the producer does not emit). Both render as {@code null}
 * in a resolved condition, so {@code {{trigger:x.output.task}} == null} is true
 * either way and a branch can be taken on a typo with no signal anywhere.
 *
 * <p>A probe carries the second answer. It is DIAGNOSTIC only: nothing routes on
 * it, so a probe that is wrong degrades the explanation, never the run.
 */
public record PathProbe(Status status, String resolvedPrefix, String missingSegment) {

    public enum Status {
        /** Every segment exists. The value may still be null, and that null is real. */
        RESOLVED,
        /** The first segment is not a key of the context at all. */
        MISSING_ROOT,
        /** The root exists but the path stops short of its last segment. */
        MISSING_SEGMENT
    }

    public boolean isMissing() {
        return status != Status.RESOLVED;
    }

    public static PathProbe resolved() {
        return new PathProbe(Status.RESOLVED, null, null);
    }

    public static PathProbe missingRoot(String root) {
        return new PathProbe(Status.MISSING_ROOT, null, root);
    }

    public static PathProbe missingSegment(String resolvedPrefix, String missingSegment) {
        return new PathProbe(Status.MISSING_SEGMENT, resolvedPrefix, missingSegment);
    }

    /**
     * One short sentence naming what is missing, for an agent or a user reading a
     * failed condition. Never includes the value, only the shape of the miss.
     */
    public String describe(String fullPath) {
        return switch (status) {
            case RESOLVED -> fullPath + " resolved";
            case MISSING_ROOT -> "no node or trigger named '" + missingSegment + "'";
            case MISSING_SEGMENT -> "'" + resolvedPrefix + "' has no field '" + missingSegment + "'";
        };
    }
}
