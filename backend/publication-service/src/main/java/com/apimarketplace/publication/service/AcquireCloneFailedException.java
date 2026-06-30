package com.apimarketplace.publication.service;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Thrown when {@code SnapshotCloneService.cloneFromSnapshot} fails mid-pipeline during an acquire.
 *
 * <p>Carries the EXACT workflow ids this acquisition created so the caller can compensate by
 * deleting only those. The previous compensation enumerated every row for the (org, publication)
 * via an org-wide query - which, when two first-time acquires of the same publication raced, made
 * the loser's compensation delete the WINNER's just-created APPLICATION row. Scoping cleanup to the
 * ids carried here keeps a concurrent acquisition's rows untouched.</p>
 */
public class AcquireCloneFailedException extends RuntimeException {

    private final Set<String> createdWorkflowIds;

    public AcquireCloneFailedException(Set<String> createdWorkflowIds, Throwable cause) {
        super(cause != null ? cause.getMessage() : "acquire clone failed", cause);
        this.createdWorkflowIds = createdWorkflowIds != null
                ? Collections.unmodifiableSet(new HashSet<>(createdWorkflowIds))
                : Collections.emptySet();
    }

    /** Workflow ids (root + sub-workflows) created by the failed acquisition. */
    public Set<String> getCreatedWorkflowIds() {
        return createdWorkflowIds;
    }
}
