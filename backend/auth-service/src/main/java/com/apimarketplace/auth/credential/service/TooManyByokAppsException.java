package com.apimarketplace.auth.credential.service;

/**
 * Thrown when a tenant attempts to create a new BYOK platform credential row
 * past the {@code MAX_BYOK_PER_TENANT} cap. The cap is a defense against
 * accidental loops or abusive clients that would otherwise unbounded-grow
 * {@code auth.platform_credentials}.
 *
 * <p>The controller maps this to HTTP 409 Conflict.
 */
public class TooManyByokAppsException extends RuntimeException {

    private final int maxAllowed;

    public TooManyByokAppsException(int maxAllowed) {
        super("Tenant has reached the maximum of " + maxAllowed
                + " custom OAuth connections. Delete an unused one to add a new one.");
        this.maxAllowed = maxAllowed;
    }

    public int maxAllowed() {
        return maxAllowed;
    }
}
