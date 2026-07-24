package com.apimarketplace.auth.credential.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The App ID is what makes a drive.file Picker grant actually stick (PickerBuilder.setAppId is
 * required for that scope). Getting it wrong is silent in the browser and only shows up as a 404 at
 * execution time, so the derivation is pinned here.
 */
class GooglePickerAppIdTest {

    @Test
    @DisplayName("derives the Cloud project number from a Google OAuth client ID")
    void derivesProjectNumberFromGoogleClientId() {
        assertEquals(
                Optional.of("785967600625"),
                GooglePickerAppId.fromClientId("785967600625-abc123def456.apps.googleusercontent.com"));
    }

    @Test
    @DisplayName("tolerates surrounding whitespace on the stored client ID")
    void trimsClientId() {
        assertEquals(
                Optional.of("785967600625"),
                GooglePickerAppId.fromClientId("  785967600625-abc.apps.googleusercontent.com  "));
    }

    @Test
    @DisplayName("returns empty for a non-Google client ID rather than a bogus App ID")
    void rejectsNonNumericPrefix() {
        // A wrong App ID breaks the Picker itself, so a non-Google client must yield nothing.
        assertTrue(GooglePickerAppId.fromClientId("acme-client-1234").isEmpty());
        assertTrue(GooglePickerAppId.fromClientId("7859abc-xyz.apps.googleusercontent.com").isEmpty());
    }

    @Test
    @DisplayName("returns empty when there is no project-number prefix to read")
    void rejectsClientIdWithoutDash() {
        assertTrue(GooglePickerAppId.fromClientId("785967600625").isEmpty());
        assertTrue(GooglePickerAppId.fromClientId("-abc.apps.googleusercontent.com").isEmpty());
    }

    @Test
    @DisplayName("returns empty for null or blank")
    void rejectsNullAndBlank() {
        assertTrue(GooglePickerAppId.fromClientId(null).isEmpty());
        assertTrue(GooglePickerAppId.fromClientId("").isEmpty());
        assertTrue(GooglePickerAppId.fromClientId("   ").isEmpty());
    }
}
