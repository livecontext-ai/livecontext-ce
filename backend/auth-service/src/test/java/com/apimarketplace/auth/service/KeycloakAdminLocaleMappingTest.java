package com.apimarketplace.auth.service;

import com.apimarketplace.common.i18n.MessageCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The app locale to Keycloak locale mapping.
 *
 * <p>It exists because the two spell two of the six differently: Keycloak's base theme ships
 * {@code pt-BR} and {@code zh-CN}, and it falls back to the realm default SILENTLY on a code it
 * does not know - so sending it "pt" would look like it worked and mail Brazilian customers in
 * English forever.
 */
@DisplayName("Keycloak locale mapping - every app locale lands on a code Keycloak knows")
class KeycloakAdminLocaleMappingTest {

    /** The codes Keycloak's base theme ships, which configure-keycloak.sh lists as supported. */
    private static final java.util.Set<String> KEYCLOAK_CODES =
            java.util.Set.of("en", "fr", "de", "es", "pt-BR", "zh-CN");

    @Test
    @DisplayName("a REGIONAL tag maps to its language, instead of being declined")
    void regionalTagsMapToTheirLanguage() {
        // Every other case in this file passes a bare code, so deleting the region-drop left the suite
        // green - on a branch whose javadoc gives it a paragraph: without it a regional tag answers
        // null, and the caller returns WITHOUT WRITING when this answers null, so the Keycloak
        // attribute silently stops being set the moment a regional value reaches the column (which is
        // eight characters wide and could hold one).
        assertThat(KeycloakAdminEmailVerifier.toKeycloakLocale("pt-BR")).isEqualTo("pt-BR");
        assertThat(KeycloakAdminEmailVerifier.toKeycloakLocale("pt_BR")).isEqualTo("pt-BR");
        assertThat(KeycloakAdminEmailVerifier.toKeycloakLocale("zh-CN")).isEqualTo("zh-CN");
        assertThat(KeycloakAdminEmailVerifier.toKeycloakLocale("FR-fr")).isEqualTo("fr");

        // A region on a language we do not ship is still DECLINED rather than becoming English: the
        // null answer is what makes the caller skip the write, and keeping that distinction is why the
        // region is dropped here instead of calling normalizeLocale, which answers English for
        // anything it does not recognise.
        assertThat(KeycloakAdminEmailVerifier.toKeycloakLocale("it-IT")).isNull();
    }

    @Test
    @DisplayName("all six app locales map, and onto a supported Keycloak code")
    void everyAppLocaleMaps() {
        for (String appLocale : MessageCatalog.LOCALES) {
            String mapped = KeycloakAdminEmailVerifier.toKeycloakLocale(appLocale);
            assertThat(mapped).as(appLocale + " must map").isNotNull();
            assertThat(KEYCLOAK_CODES).as(appLocale + " maps to a code the realm supports").contains(mapped);
        }
    }

    @Test
    @DisplayName("the two that differ are spelled Keycloak's way")
    void regionalCodesAreSpelledOut() {
        assertThat(KeycloakAdminEmailVerifier.toKeycloakLocale("pt")).isEqualTo("pt-BR");
        assertThat(KeycloakAdminEmailVerifier.toKeycloakLocale("zh")).isEqualTo("zh-CN");
    }

    @Test
    @DisplayName("an unknown, blank or null locale maps to nothing, so no attribute is written")
    void unknownLocaleIsRefused() {
        // Returning null is what makes setUserLocale return early: writing an unsupported code
        // would replace a good attribute with one Keycloak ignores.
        assertThat(KeycloakAdminEmailVerifier.toKeycloakLocale("it")).isNull();
        assertThat(KeycloakAdminEmailVerifier.toKeycloakLocale("")).isNull();
        assertThat(KeycloakAdminEmailVerifier.toKeycloakLocale(null)).isNull();
    }

    @Test
    @DisplayName("case and padding do not matter: the value comes from a JSON body")
    void inputIsNormalised() {
        assertThat(KeycloakAdminEmailVerifier.toKeycloakLocale(" FR ")).isEqualTo("fr");
        assertThat(KeycloakAdminEmailVerifier.toKeycloakLocale("PT")).isEqualTo("pt-BR");
    }
}
