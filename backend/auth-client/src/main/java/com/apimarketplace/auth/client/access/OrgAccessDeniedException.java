package com.apimarketplace.auth.client.access;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a member's attempt to read/write an org-scoped resource is
 * blocked by an active {@code org-restrictions} entry.
 *
 * <p>Spring maps this to HTTP 403 via {@link ResponseStatus}, so callers can
 * let the exception propagate to the controller layer without explicit catch.
 *
 * <p>Used by {@link OrgAccessGuard} consumers when they want to short-circuit
 * a write path. The message intentionally omits the resourceId from the
 * client-facing response body to avoid leaking existence information ; the
 * resourceId is still logged server-side for ops triage.
 */
@ResponseStatus(HttpStatus.FORBIDDEN)
public class OrgAccessDeniedException extends RuntimeException {

    private final String resourceType;
    private final String resourceId;

    public OrgAccessDeniedException(String resourceType, String resourceId) {
        super("Access to this " + resourceType + " is restricted");
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }
}
