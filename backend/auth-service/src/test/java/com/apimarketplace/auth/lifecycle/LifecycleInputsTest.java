package com.apimarketplace.auth.lifecycle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LifecycleInputs - untrusted values are validated, bad ones dropped")
class LifecycleInputsTest {

    @ParameterizedTest
    @ValueSource(strings = {"en", "fr", "es", "de", "pt", "zh"})
    @DisplayName("the six app locales are accepted")
    void supportedLocales(String locale) {
        assertThat(LifecycleInputs.locale(locale)).isEqualTo(locale);
    }

    @Test
    @DisplayName("a locale is normalized to lowercase and trimmed")
    void localeNormalized() {
        assertThat(LifecycleInputs.locale(" FR ")).isEqualTo("fr");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"it", "fr-FR", "english", "x"})
    @DisplayName("an unsupported locale is ignored")
    void unsupportedLocale(String locale) {
        assertThat(LifecycleInputs.locale(locale)).isNull();
    }

    @Test
    @DisplayName("a valid IANA zone is kept")
    void validTimeZone() {
        assertThat(LifecycleInputs.timeZone("Europe/Paris")).isEqualTo("Europe/Paris");
        assertThat(LifecycleInputs.timeZone("UTC")).isEqualTo("UTC");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Mars/Olympus", "not a zone", "Europe/"})
    @DisplayName("an invalid zone is ignored")
    void invalidTimeZone(String zone) {
        assertThat(LifecycleInputs.timeZone(zone)).isNull();
    }

    @Test
    @DisplayName("a zone id longer than the column is ignored")
    void tooLongTimeZone() {
        assertThat(LifecycleInputs.timeZone("A".repeat(65))).isNull();
    }

    @Test
    @DisplayName("a Cloudflare country is uppercased")
    void country() {
        assertThat(LifecycleInputs.country("fr")).isEqualTo("FR");
        assertThat(LifecycleInputs.country("US")).isEqualTo("US");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"XX", "T1", "FRA", "F", "1A"})
    @DisplayName("unknown (XX), Tor (T1) and malformed countries are ignored")
    void invalidCountry(String country) {
        assertThat(LifecycleInputs.country(country)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"203.0.113.7", "0.0.0.0", "255.255.255.255", "2001:db8::1", "::1", "::ffff:192.0.2.1"})
    @DisplayName("IPv4 and IPv6 literals are accepted")
    void validIp(String ip) {
        assertThat(LifecycleInputs.ip(ip)).isEqualTo(ip);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"256.1.1.1", "1.2.3", "01.2.3.4", "example.com", "localhost", "1.2.3.4, 5.6.7.8",
            "2001:db8::zz", "fe80::1%eth0"})
    @DisplayName("anything that is not an IP literal is ignored, host names are never resolved")
    void invalidIp(String ip) {
        assertThat(LifecycleInputs.ip(ip)).isNull();
    }

    @Test
    @DisplayName("text is trimmed, blank becomes null, and it is capped")
    void textTrimmedAndCapped() {
        assertThat(LifecycleInputs.text("  google  ", 255)).isEqualTo("google");
        assertThat(LifecycleInputs.text("   ", 255)).isNull();
        assertThat(LifecycleInputs.text("x".repeat(300), 255)).hasSize(255);
    }
}
