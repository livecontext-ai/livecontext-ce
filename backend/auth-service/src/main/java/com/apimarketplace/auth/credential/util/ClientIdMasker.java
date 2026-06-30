package com.apimarketplace.auth.credential.util;

/**
 * Masks an OAuth2 client_id for display.
 *
 * <p>NOT appropriate for general string truncation - the &lt;8-char branch returns
 * {@code "****"} and silently destroys information for short strings, which is
 * the desired behaviour for OAuth client identifiers but would corrupt other
 * data. Keep callers limited to display masking of OAuth2 client ids.
 */
public final class ClientIdMasker {

    private ClientIdMasker() {
    }

    public static String mask(String clientId) {
        if (clientId == null || clientId.length() < 8) {
            return "****";
        }
        return clientId.substring(0, 4) + "****" + clientId.substring(clientId.length() - 4);
    }
}
