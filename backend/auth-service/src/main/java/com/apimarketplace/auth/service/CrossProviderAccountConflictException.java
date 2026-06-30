package com.apimarketplace.auth.service;

import com.apimarketplace.auth.domain.AuthProvider;

/**
 * Raised when a login resolves to an existing account ONLY by email, but the
 * incoming sign-in method differs from the one that owns the account.
 *
 * <p>Linking on email alone would let, e.g., a Google sign-in silently take over
 * an account created with an email/password (Keycloak) credential - the reported
 * cross-provider account-merge vulnerability. We fail closed instead of merging.
 *
 * <p>This is the application-layer half of the defense; the Keycloak
 * {@code lc-first-broker-login} flow (which drops "Handle Existing Account") is
 * the other half. Either one being bypassed must not result in a silent merge.
 */
public class CrossProviderAccountConflictException extends RuntimeException {

    private final transient AuthProvider existingProvider;
    private final transient AuthProvider incomingProvider;

    public CrossProviderAccountConflictException(AuthProvider existingProvider, AuthProvider incomingProvider) {
        super("Account exists for this email under provider " + existingProvider
                + " but the incoming login uses " + incomingProvider + "; refusing to merge.");
        this.existingProvider = existingProvider;
        this.incomingProvider = incomingProvider;
    }

    public AuthProvider getExistingProvider() {
        return existingProvider;
    }

    public AuthProvider getIncomingProvider() {
        return incomingProvider;
    }
}
