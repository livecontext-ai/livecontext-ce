package com.apimarketplace.catalog.service.credential;

import java.util.Locale;

/** How an integration slug is written out for a reader. */
public final class IntegrationNames {

    private IntegrationNames() {
    }

    /**
     * The integration's name as prose. Deliberately a capitalisation of the slug and
     * nothing cleverer: the slug is what the credential screens, the Connect card and
     * {@code credential(action='require')} all key on, so a prettier name that no
     * longer matches what the reader has to type would cost more than it reads better.
     */
    public static String displayName(String integration) {
        if (integration == null || integration.isBlank()) {
            return "provider";
        }
        String trimmed = integration.trim();
        return trimmed.substring(0, 1).toUpperCase(Locale.ROOT) + trimmed.substring(1);
    }
}
