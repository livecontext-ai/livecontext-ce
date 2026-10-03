package com.apimarketplace.auth.credential.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Binds a user-scoped internal credential call to the identity the gateway signature covers
 * (CASA LC-008).
 *
 * <p>{@code /api/internal/credentials/} is HMAC-gated, and the signature binds the
 * {@code X-User-ID} header, not the {@code userId} query/body field these endpoints historically
 * read. Authorizing on the field meant a caller holding one signed request could ask for another
 * user's decrypted credentials by editing an unsigned parameter. Every in-cluster caller
 * ({@code CredentialClient}, {@code ConversationToolExecutionService}) already sends
 * {@code X-User-ID} equal to that field, so the rule is: the header is the identity, and a
 * field that disagrees with it is refused.
 */
final class InternalCallerIdentity {

    static final String HEADER_USER_ID = "X-User-ID";

    private InternalCallerIdentity() {}

    /**
     * @param signedUserId  the {@code X-User-ID} header (covered by the gateway signature)
     * @param claimedUserId the legacy {@code userId} parameter or body field, may be null
     * @return the signed user id
     * @throws ResponseStatusException 403 when the header is missing or disagrees with the claim
     */
    static String bind(String signedUserId, String claimedUserId) {
        if (signedUserId == null || signedUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "X-User-ID is required on user-scoped internal credential calls");
        }
        if (claimedUserId != null && !claimedUserId.isBlank() && !claimedUserId.equals(signedUserId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "userId does not match the signed caller identity");
        }
        return signedUserId;
    }
}
