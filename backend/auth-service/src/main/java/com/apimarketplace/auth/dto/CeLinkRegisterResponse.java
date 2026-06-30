package com.apimarketplace.auth.dto;

/**
 * Response for {@code POST /api/ce-link/register}.
 * <ul>
 *   <li>201 {@code registered=true} on first-time register.</li>
 *   <li>409 {@code error="ALREADY_BOUND"} when install_id PK collides; the
 *       {@code boundToEmail} field is populated only when the caller IS the
 *       bound user (squat detection - they see their own masked email);
 *       otherwise null (no info leak).</li>
 *   <li>401 {@code error="INSTALL_ID_REQUIRED"} when header missing; or
 *       {@code error="LINK_REVOKED"} when the existing row is REVOKED.</li>
 * </ul>
 * Constant-time 400ms enforcement (PR3d filter) covers all branches.
 */
public record CeLinkRegisterResponse(
        boolean registered,
        String error,
        String boundToEmail,
        String scopes
) {
    public static CeLinkRegisterResponse ok(String scopes) {
        return new CeLinkRegisterResponse(true, null, null, scopes);
    }

    public static CeLinkRegisterResponse alreadyBound(String boundToEmailMasked) {
        return new CeLinkRegisterResponse(false, "ALREADY_BOUND", boundToEmailMasked, null);
    }
}
