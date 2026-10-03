package com.apimarketplace.auth.credential.service;

/**
 * Short, non-reversible reference to an OAuth2 {@code state} value, for logs and cookie names.
 *
 * <p>{@code state} is the Redis key of the flow's state blob, and it used to be logged verbatim at
 * initiate and at the callback, so anyone able to read a log line could address the blob directly
 * (LC-089). Twelve hex characters of a SHA-256 still correlate the initiate line with its callback
 * line, and are useless as a key.
 */
public final class OAuth2StateRef {

    private OAuth2StateRef() {
    }

    /** @return 12 lowercase hex chars of SHA-256(state), or {@code "none"} for a blank state. */
    public static String of(String state) {
        if (state == null || state.isBlank()) {
            return "none";
        }
        byte[] digest = OAuth2BrowserBinding.sha256(state);
        StringBuilder hex = new StringBuilder(12);
        for (int i = 0; i < 6; i++) {
            hex.append(String.format("%02x", digest[i]));
        }
        return hex.toString();
    }
}
