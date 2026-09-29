package com.apimarketplace.auth.service;

/**
 * Raised when an account whose email address is NOT verified tries to accept or
 * decline an organization invitation addressed to that email.
 *
 * <p>An invitation is keyed on an email address, so the only thing that proves the
 * caller owns that mailbox is a verified email. Without this check anyone could
 * register the invitee's address (without confirming it) and join the organization
 * at the invited role. It extends {@link SecurityException} so every existing
 * 403 mapping keeps working; {@code OrganizationController} additionally tags the
 * response with the {@link #CODE} error code so the UI can tell the user to verify
 * their email first.
 */
public class InvitationEmailNotVerifiedException extends SecurityException {

    /** Machine-readable error code returned to the client. */
    public static final String CODE = "EMAIL_NOT_VERIFIED";

    public InvitationEmailNotVerifiedException() {
        super("Verify your email address before accepting or declining an invitation");
    }
}
