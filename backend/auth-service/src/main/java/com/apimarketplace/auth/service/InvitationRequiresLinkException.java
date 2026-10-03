package com.apimarketplace.auth.service;

/**
 * Raised when a CE account tries to answer an invitation from the inbox (accept or decline by
 * id) although it cannot prove it owns the invited mailbox: it was registered AFTER the
 * invitation was sent, or its creation date is unknown (CASA LC-084).
 *
 * <p>A CE account's address is never proven (see {@code OrganizationMemberService}), so only
 * the emailed link, whose token proves the mailbox, may answer such an invitation. It extends
 * {@link SecurityException} so every existing 403 mapping keeps working;
 * {@code OrganizationController} additionally tags the response with {@link #CODE} so the inbox
 * tells the user to open the link from the invitation email.
 */
public class InvitationRequiresLinkException extends SecurityException {

    /** Machine-readable error code returned to the client (the inbox translates it). */
    public static final String CODE = "invitation_requires_link";

    public InvitationRequiresLinkException() {
        super("Open the invitation link to answer this invitation");
    }
}
