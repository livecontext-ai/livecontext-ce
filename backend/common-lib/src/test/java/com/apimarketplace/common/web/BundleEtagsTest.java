package com.apimarketplace.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BundleEtags - If-None-Match against a bundle checksum")
class BundleEtagsTest {

    private static final String SUM = "a".repeat(64);

    @Test
    @DisplayName("the quoted checksum a CE sends matches")
    void quotedMatches() {
        assertThat(BundleEtags.matches(BundleEtags.quoted(SUM), SUM)).isTrue();
    }

    @Test
    @DisplayName("weak, unquoted, listed and wildcard forms match (RFC 9110 weak comparison)")
    void otherForms() {
        assertThat(BundleEtags.matches("W/\"" + SUM + "\"", SUM)).isTrue();
        assertThat(BundleEtags.matches(SUM, SUM)).isTrue();
        assertThat(BundleEtags.matches("\"other\", \"" + SUM + "\"", SUM)).isTrue();
        assertThat(BundleEtags.matches("*", SUM)).isTrue();
    }

    @Test
    @DisplayName("a different checksum, a blank header or a missing checksum never match")
    void mismatches() {
        assertThat(BundleEtags.matches("\"" + "b".repeat(64) + "\"", SUM)).isFalse();
        assertThat(BundleEtags.matches("", SUM)).isFalse();
        assertThat(BundleEtags.matches(null, SUM)).isFalse();
        assertThat(BundleEtags.matches("*", null)).isFalse();
        assertThat(BundleEtags.matches("*", " ")).isFalse();
    }
}
