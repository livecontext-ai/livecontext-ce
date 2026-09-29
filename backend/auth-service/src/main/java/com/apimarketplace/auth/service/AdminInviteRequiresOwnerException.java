package com.apimarketplace.auth.service;

/**
 * Raised when an ADMIN tries to invite someone as ADMIN. Only the OWNER may grant
 * ADMIN, through an invitation exactly as through a role change
 * ({@code changeRole} is OWNER-only); an ADMIN may invite MEMBER or VIEWER.
 *
 * <p>Extends {@link SecurityException} so every existing 403 mapping keeps working;
 * {@code OrganizationController} tags the response with {@link #CODE}.
 */
public class AdminInviteRequiresOwnerException extends SecurityException {

    /** Machine-readable error code returned to the client. */
    public static final String CODE = "ADMIN_INVITE_REQUIRES_OWNER";

    public AdminInviteRequiresOwnerException() {
        super("Only the OWNER can invite someone as ADMIN");
    }
}
