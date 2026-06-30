package com.apimarketplace.orchestrator.services.state.patch;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Plan v4 §1.4 per-method patch-target marker - annotates a specific
 * patch-emitting method inside a {@link PatchClass}-annotated builder.
 *
 * <p>Carries the JSON path the method writes (e.g. {@code "nodes.X.completed"})
 * plus the operation kind. The {@link RunCoalescingService} uses these to
 * detect same-path collisions and choose merge-vs-force-flush.
 *
 * <p>The path string is informational - the actual {@link JsonbPatch#path()}
 * array is built by the method at runtime from runtime params (nodeId, epoch
 * index, etc.). The annotation captures the SHAPE of the path so static analysis
 * can detect collisions without executing the builder.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PatchTarget {

    /**
     * The shape of the JSON path this method writes, with named placeholders
     * for runtime values (e.g. {@code "nodes.{nodeId}.completed"}).
     */
    String path();

    /** The composition kind of this specific method's emitted patch. */
    PatchClass.OpKind opKind();
}
