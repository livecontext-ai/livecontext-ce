package com.apimarketplace.publication.service;

/**
 * Thrown by the publish / unpublish / delete guards when the target publication
 * is in {@code PENDING_REVIEW}. This is a client-state <b>conflict</b> - the
 * controllers map it to HTTP 409 so the frontend surfaces the message and fails
 * fast (a 5xx would be retried).
 *
 * <p>It extends {@link IllegalStateException} so any existing broad
 * {@code catch (IllegalStateException)} keeps working, but it lets the
 * controllers distinguish the pending-review conflict from a genuine
 * server-side {@code IllegalStateException} - e.g. a transient showcase-snapshot
 * capture failure ({@code WorkflowPublicationService.captureAndStoreShowcaseSnapshot})
 * or an agent-snapshot build failure - which must stay 5xx so the client retries.
 * Catch <em>this</em> type for 409; let plain {@code IllegalStateException} fall
 * through to the generic 500 handler.
 */
public class PublicationPendingReviewException extends IllegalStateException {

    public PublicationPendingReviewException(String message) {
        super(message);
    }
}
