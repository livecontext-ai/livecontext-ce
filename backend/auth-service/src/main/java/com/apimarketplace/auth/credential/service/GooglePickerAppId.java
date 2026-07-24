package com.apimarketplace.auth.credential.service;

import java.util.Optional;

/**
 * Derives the Google Picker "App ID" from an OAuth client ID.
 *
 * <p>{@code PickerBuilder.setAppId} is <b>required for the {@code drive.file} scope</b>: it is what
 * tells Google which application the per-file grant belongs to. Without it the Picker still renders
 * and still returns a file ID, but no grant is recorded, and the subsequent Drive/Docs/Sheets/Slides
 * API call fails with HTTP 404 {@code "Requested entity was not found."} (Drive answers 404, never
 * 403, so it does not leak the existence of a file the caller cannot see). The failure is therefore
 * silent in the browser and only surfaces at execution time.
 *
 * <p>Google defines the App ID as the <b>Cloud project number</b>, which is exactly the prefix of an
 * OAuth client ID: {@code 785967600625-abc123.apps.googleusercontent.com} carries project number
 * {@code 785967600625}. Deriving it from the credential the Picker token was minted from keeps the
 * two in lockstep by construction: cloud (platform client), CE and BYOK (the user's own client) each
 * get their own correct App ID with no extra configuration and no build-time value to keep in sync.
 *
 * @see <a href="https://developers.google.com/workspace/drive/picker/reference/picker.pickerbuilder.setappid">PickerBuilder.setAppId</a>
 */
public final class GooglePickerAppId {

    private GooglePickerAppId() {}

    /**
     * The project number of a Google OAuth client ID, or empty when {@code clientId} is null, blank,
     * or not a Google client ID (a non-Google provider, or a client ID whose prefix is not numeric).
     * Empty means "do not send an App ID" rather than "send a wrong one": a bogus App ID makes the
     * Picker itself fail, which is worse than the pre-existing behaviour.
     */
    public static Optional<String> fromClientId(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            return Optional.empty();
        }
        String trimmed = clientId.trim();
        int dash = trimmed.indexOf('-');
        if (dash <= 0) {
            return Optional.empty();
        }
        String prefix = trimmed.substring(0, dash);
        // A Google project number is all digits. Anything else is another provider's client ID.
        for (int i = 0; i < prefix.length(); i++) {
            if (!Character.isDigit(prefix.charAt(i))) {
                return Optional.empty();
            }
        }
        return Optional.of(prefix);
    }
}
